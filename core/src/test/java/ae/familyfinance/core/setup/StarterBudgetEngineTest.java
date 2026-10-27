package ae.familyfinance.core.setup;

import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.setup.StarterBudgetEngine.Kid;
import ae.familyfinance.core.setup.StarterBudgetEngine.Line;
import ae.familyfinance.core.setup.StarterBudgetEngine.Starter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StarterBudgetEngineTest {

    static final List<Kid> LAYLA_AND_ADAM = List.of(
            new Kid(UUID.randomUUID(), "Layla", AgeBand.TEEN_13_17),
            new Kid(UUID.randomUUID(), "Adam", AgeBand.CHILD_6_9));

    private static long amount(Starter s, String category) {
        return s.lines().stream().filter(l -> l.category().equals(category)).findFirst().map(Line::amount).orElseThrow().minor();
    }

    @Test
    void a_family_with_room_gets_the_starting_amounts_per_person() {
        Starter s = StarterBudgetEngine.starter(Money.aed(3_450_000), Money.aed(1_720_000), 2, LAYLA_AND_ADAM);

        assertThat(s.cut()).isFalse();
        assertThat(amount(s, "GROCERIES")).isEqualTo(280_000);      // 2 × 900 + 2 × 500
        assertThat(amount(s, "TRANSPORT")).isEqualTo(100_000);      // 2 × 500
        assertThat(amount(s, "EATING_OUT")).isEqualTo(120_000);     // 2 × 400 + 2 × 200
        assertThat(amount(s, "ENTERTAINMENT")).isEqualTo(100_000);  // 2 × 300 + 2 × 200
        assertThat(s.pocket()).extracting(p -> p.weekly().minor()).containsExactly(10_000L, 6_000L);
        // 6,000 of budgets + pocket money as a month (100 and 60 a week × 52 ÷ 12)
        assertThat(s.total().minor()).isEqualTo(600_000 + 43_333 + 26_000);
        assertThat(s.room().text()).isEqualTo("AED 17,300");
        assertThat(s.lines().get(0).reason()).isEqualTo("A starting point: AED 900 a month per adult and AED 500 per child");
        assertThat(s.lines()).extracting(Line::bucket).containsExactly("EVERYDAY", "EVERYDAY", "FUN", "FUN");
    }

    @Test
    void a_tight_month_cuts_every_budget_by_the_same_share_and_stays_under_60_percent() {
        Starter s = StarterBudgetEngine.starter(Money.aed(1_200_000), Money.aed(900_000), 2,
                List.of(new Kid(UUID.randomUUID(), "Layla", AgeBand.TEEN_13_17)));

        assertThat(s.cut()).isTrue();
        assertThat(s.cap().text()).isEqualTo("AED 1,800");
        assertThat(s.total().minor()).isLessThanOrEqualTo(s.cap().minor());
        // Groceries start at AED 2,300 of AED 5,533.33 in all: × 1,800 ÷ 5,533.33 = AED 748.20, down to AED 700
        assertThat(amount(s, "GROCERIES")).isEqualTo(70_000);
        assertThat(s.pocket().get(0).weekly().minor() % 500).isZero();
        assertThat(s.note()).contains("60% of the AED 3,000 left after commitments");
    }

    @Test
    void commitments_above_the_income_leave_no_room() {
        Starter s = StarterBudgetEngine.starter(Money.aed(800_000), Money.aed(900_000), 1, List.of());

        assertThat(s.room().minor()).isZero();
        assertThat(s.lines()).allMatch(l -> l.amount().minor() == 0);
        assertThat(s.note()).startsWith("Your commitments take all of this month's income");
    }

    @Test
    void a_single_parent_without_kids_has_no_child_amounts_or_pocket_money() {
        Starter s = StarterBudgetEngine.starter(Money.aed(2_000_000), Money.aed(800_000), 1, List.of());

        assertThat(amount(s, "GROCERIES")).isEqualTo(90_000);
        assertThat(s.pocket()).isEmpty();
        assertThat(s.lines().get(0).reason()).isEqualTo("A starting point: AED 900 a month per adult");
    }

    @Test
    void the_emergency_buffer_is_three_months_rounded_up_to_a_thousand() {
        var b = StarterBudgetEngine.buffer(Money.aed(1_720_000), Money.aed(669_333));

        assertThat(b.target().text()).isEqualTo("AED 72,000"); // 3 × 23,893.33 = 71,679.99
        assertThat(b.reason()).startsWith("3 months of commitments and everyday spending (AED 23,893 a month)");
    }

    @Test
    void ages_map_to_bands_and_only_6_to_17_get_a_login() {
        assertThat(SetupService.ageBand(6)).isEqualTo(AgeBand.CHILD_6_9);
        assertThat(SetupService.ageBand(9)).isEqualTo(AgeBand.CHILD_6_9);
        assertThat(SetupService.ageBand(10)).isEqualTo(AgeBand.PRETEEN_10_12);
        assertThat(SetupService.ageBand(13)).isEqualTo(AgeBand.TEEN_13_17);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SetupService.ageBand(5))
                .hasMessageContaining("from 6 to 17");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SetupService.ageBand(18))
                .hasMessageContaining("from 6 to 17");
    }
}
