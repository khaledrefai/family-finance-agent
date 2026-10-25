package ae.familyfinance.core.advice;

import ae.familyfinance.core.advice.WaysToSaveEngine.Facts;
import ae.familyfinance.core.advice.WaysToSaveEngine.Outings;
import ae.familyfinance.core.advice.WaysToSaveEngine.Recurring;
import ae.familyfinance.core.advice.WaysToSaveEngine.Rent;
import ae.familyfinance.core.advice.WaysToSaveEngine.Tip;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaysToSaveEngineTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    static Facts haddads(List<Recurring> subs) {
        return new Facts(TODAY, "AED", 3_450_000, subs, new Rent("Apartment rent", 12_000_000, LocalDate.of(2027, 10, 1)),
                List.of(new Recurring("du home and mobile", 45_000)), new Outings(120_000, 152_000L));
    }

    @Test
    void savings_the_engine_can_work_out_come_first_then_the_biggest_cost_to_compare() {
        List<Tip> tips = WaysToSaveEngine.tips(haddads(List.of(new Recurring("Streaming bundle", 5_900),
                new Recurring("SHAHID VIP", 3_500))));
        assertThat(tips).extracting(Tip::key).containsExactly("outings", "subscriptions", "rent", "telecom");
    }

    @Test
    void outings_over_budget_say_what_keeping_to_it_frees() {
        Tip t = WaysToSaveEngine.tips(haddads(List.of())).get(0);
        assertThat(t.text()).isEqualTo("Entertainment averaged AED 1,520 a month over the last 3 months, AED 320 over its "
                + "AED 1,200 budget. Keeping to the budget frees about AED 3,840 a year.");
        assertThat(NumberCheck.ok(t.text(), t.facts())).isTrue();
    }

    @Test
    void two_subscriptions_suggest_dropping_the_smallest() {
        Tip t = WaysToSaveEngine.subscriptions(haddads(List.of(new Recurring("Streaming bundle", 5_900),
                new Recurring("SHAHID VIP", 3_500)))).orElseThrow();
        assertThat(t.text()).isEqualTo("You pay AED 94 a month for 2 subscriptions: Streaming bundle and SHAHID VIP. "
                + "Dropping SHAHID VIP saves AED 420 a year.");
        assertThat(t.savingMinor()).isEqualTo(42_000);
        assertThat(NumberCheck.ok(t.text(), t.facts())).isTrue();
    }

    @Test
    void rent_share_of_income_and_the_next_contract() {
        Tip t = WaysToSaveEngine.rent(haddads(List.of())).orElseThrow();
        assertThat(t.text()).startsWith("Rent is 29% of the income you plan on, AED 120,000 a year.")
                .contains("The next contract starts on 1 Oct");
        assertThat(NumberCheck.ok(t.text(), t.facts())).isTrue();
    }

    @Test
    void cheap_rent_and_outings_inside_the_budget_give_no_tip() {
        Facts f = new Facts(TODAY, "AED", 3_450_000, List.of(), new Rent("Rent", 6_000_000, null), List.of(),
                new Outings(120_000, 100_000L));
        assertThat(WaysToSaveEngine.tips(f)).isEmpty();
    }

    @Test
    void the_number_check_refuses_a_made_up_number() {
        List<String> facts = List.of("Entertainment, 3-month average: AED 1,520 a month.");
        assertThat(NumberCheck.ok("You spent about AED 1,520 a month over 3 months.", facts)).isTrue();
        assertThat(NumberCheck.ok("You could save AED 2,000.", facts)).isFalse();
        assertThat(NumberCheck.ok("AED 12.50", List.of("AED 12.5"))).isTrue();
    }
}
