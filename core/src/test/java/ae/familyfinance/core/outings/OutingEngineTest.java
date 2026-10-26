package ae.familyfinance.core.outings;

import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.outings.OutingEngine.Costed;
import ae.familyfinance.core.outings.OutingEngine.Fun;
import ae.familyfinance.core.outings.OutingEngine.Kind;
import ae.familyfinance.core.outings.OutingEngine.Option;
import ae.familyfinance.core.outings.OutingEngine.Person;
import ae.familyfinance.core.outings.OutingEngine.Plan;
import ae.familyfinance.core.outings.OutingEngine.When;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The outings engine on its own, with the demo family and the saved list, in October (the demo month). */
class OutingEngineTest {

    static final List<Person> HADDADS = List.of(new Person("Sara", AgeBand.ADULT), new Person("Omar", AgeBand.ADULT),
            new Person("Layla", AgeBand.TEEN_13_17), new Person("Adam", AgeBand.CHILD_6_9));
    static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    // The demo seed: AED 1,200 budget, AED 1,560 spent this month, usual outing about AED 374 (12 outings)
    static final Fun OVER = new Fun(120000, 156000, 37400L, 12);

    final List<Costed> all = OutingEngine.costAll(new OutingCatalogue(new ObjectMapper().findAndRegisterModules()).options(),
            HADDADS, "AED");

    static Option option(String key, String adult, String child, int childUpTo, Integer... months) {
        return new Option(key, key, "Dubai", EnumSet.of(Kind.OUTDOOR), Money.ofMajor(adult, "AED"), Money.ofMajor(child, "AED"),
                childUpTo, Set.of(months), "", "https://example.com");
    }

    private Costed costed(String key) {
        return all.stream().filter(c -> c.option().key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void a_teen_past_the_child_ticket_age_pays_the_adult_price_and_a_nine_year_old_the_child_price() {
        Costed frame = OutingEngine.cost(option("frame", "50", "20", 12), HADDADS, "AED");

        assertThat(frame.cost()).isEqualTo(Money.aed(17000));  // 3 x AED 50 + AED 20
        assertThat(frame.costLine()).isEqualTo("2 adults at AED 50, Layla AED 50, Adam AED 20");
        assertThat(frame.tickets()).extracting(OutingEngine.Row::label)
                .containsExactly("Sara", "Omar", "Layla, adult ticket", "Adam, child ticket");
    }

    @Test
    void an_age_band_that_crosses_the_child_ticket_age_is_counted_at_the_adult_price() {
        // 10 to 12 with child tickets up to 11: some of them pay the adult price, so the engine never under-counts
        Costed c = OutingEngine.cost(option("x", "40", "10", 11),
                List.of(new Person("Mum", AgeBand.ADULT), new Person("Zara", AgeBand.PRETEEN_10_12)), "AED");
        assertThat(c.cost()).isEqualTo(Money.aed(8000));
    }

    @Test
    void free_outings_cost_nothing_and_say_so() {
        assertThat(costed("kite-beach").cost()).isEqualTo(Money.aed(0));
        assertThat(costed("kite-beach").costLine()).isEqualTo("Free to get in");
    }

    @Test
    void when_this_month_is_over_budget_the_plan_is_free_outings_only() {
        Plan p = OutingEngine.suggest(all, Set.of(), When.THIS_MONTH, OCTOBER, OVER, "AED");

        assertThat(p.allowance()).isEqualTo(Money.aed(0));
        assertThat(p.picks()).hasSize(3).allMatch(Costed::free);
        assertThat(p.total()).isEqualTo(Money.aed(0));
        assertThat(p.facts()).contains("Spent on entertainment this month: AED 1,560, AED 360 over the budget.",
                "Nothing is left for outings in October, so these are free ones.");
    }

    @Test
    void next_month_has_two_paid_outings_at_most_half_the_usual_outing_and_free_ones_one_a_weekend() {
        Plan p = OutingEngine.suggest(all, Set.of(), When.NEXT_MONTH, OCTOBER, OVER, "AED");

        assertThat(p.monthLabel()).isEqualTo("November");
        assertThat(p.picks()).hasSize(OutingEngine.MAX_PICKS);
        assertThat(p.picks()).extracting(c -> c.option().key())
                .containsExactly("safari-park", "dubai-frame", "kite-beach", "dubai-fountain");
        assertThat(p.total()).isEqualTo(Money.aed(34000));
        assertThat(p.left()).isEqualTo(Money.aed(86000));
        assertThat(p.usualCost()).isEqualTo(Money.aed(149600));
        assertThat(p.saving()).isEqualTo(Money.aed(115600));
        assertThat(p.facts()).contains(
                "Each paid outing costs at most AED 187, half the family's usual outing.",
                "Dubai Safari Park: AED 170 for the family (2 adults at AED 50, Layla AED 50, Adam AED 20).",
                "Together: AED 340 for 4 outings, leaving AED 860 of the AED 1,200.",
                "4 usual outings would cost about AED 1,496; this plan costs AED 1,156 less.");
    }

    @Test
    void asking_for_indoor_outings_keeps_to_indoor_ones() {
        Plan p = OutingEngine.suggest(all, Set.of(Kind.INDOOR), When.NEXT_MONTH, OCTOBER, OVER, "AED");

        assertThat(p.picks()).isNotEmpty().allMatch(c -> c.option().kinds().contains(Kind.INDOOR));
        assertThat(p.picks()).extracting(c -> c.option().key()).doesNotContain("green-planet");  // over half the usual
    }

    @Test
    void the_planners_pick_is_checked_closed_and_unknown_ones_left_out_and_the_dearest_dropped_until_it_fits() {
        Fun small = new Fun(60000, 0, 37400L, 12);  // AED 600 a month
        Plan p = OutingEngine.check(all, List.of("green-planet", "miracle-garden", "made-up", "dubai-frame", "kite-beach"),
                When.THIS_MONTH, OCTOBER, small, "AED");

        // Miracle Garden opens in November; Green Planet (AED 605) alone is more than the AED 600
        assertThat(p.picks()).extracting(c -> c.option().key()).containsExactly("dubai-frame", "kite-beach");
        assertThat(p.total()).isEqualTo(Money.aed(17000));
        assertThat(p.leftOut()).containsExactly(
                "Left out Dubai Miracle Garden: it is closed in October.",
                "Left out The Green Planet (AED 605): with it the plan came to more than the AED 600 there is.");
    }

    @Test
    void no_entertainment_budget_means_free_outings_and_a_note_to_set_one() {
        Plan p = OutingEngine.suggest(all, Set.of(), When.NEXT_MONTH, OCTOBER, new Fun(0, 0, null, 0), "AED");

        assertThat(p.picks()).isNotEmpty().allMatch(Costed::free);
        assertThat(p.facts().get(0)).startsWith("There is no entertainment budget yet");
        assertThat(p.saving()).isNull();
    }
}
