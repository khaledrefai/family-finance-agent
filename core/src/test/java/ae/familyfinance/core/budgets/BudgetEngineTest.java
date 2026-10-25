package ae.familyfinance.core.budgets;

import ae.familyfinance.core.budgets.BudgetEngine.Budget;
import ae.familyfinance.core.budgets.BudgetEngine.Line;
import ae.familyfinance.core.budgets.BudgetEngine.Month;
import ae.familyfinance.core.budgets.BudgetEngine.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetEngineTest {

    static final LocalDate OCT_22 = LocalDate.of(2026, 10, 22);

    private static Line only(Budget b, LocalDate today) {
        return BudgetEngine.month(List.of(b), today, "AED").lines().get(0);
    }

    @Test
    void over_the_limit_says_how_far_and_how_long_to_go() {
        Line l = only(new Budget("ENTERTAINMENT", "Entertainment", 120_000, 156_000, List.of()), OCT_22);
        assertThat(l.state()).isEqualTo(State.OVER);
        assertThat(l.percent()).isEqualTo(130);
        assertThat(l.alert()).isEqualTo("Entertainment is at 130% of its AED 1,200 budget with 9 days to go: AED 360 over.");
        assertThat(l.fillBp()).isEqualTo(7500);
        assertThat(l.pastBp()).isEqualTo(2250);
    }

    @Test
    void heading_over_at_this_pace() {
        // AED 2,400 by the 15th of a 31-day month: about AED 4,960 by the end, over 115% of AED 3,000
        Line l = only(new Budget("GROCERIES", "Groceries", 300_000, 240_000, List.of()), LocalDate.of(2026, 10, 15));
        assertThat(l.state()).isEqualTo(State.HEADING_OVER);
        assertThat(l.atThisPace().minor()).isEqualTo(496_000);
        assertThat(l.alert()).contains("80% of its AED 3,000 budget with 16 days to go", "near AED 4,960");
    }

    @Test
    void no_pace_warning_in_the_first_week() {
        Line l = only(new Budget("GROCERIES", "Groceries", 300_000, 150_000, List.of()), LocalDate.of(2026, 10, 3));
        assertThat(l.state()).isEqualTo(State.OK);
    }

    @Test
    void far_above_the_usual_month_even_inside_the_limit() {
        Line l = only(new Budget("EATING_OUT", "Eating out", 300_000, 140_000, List.of(90_000L, 100_000L, 110_000L)),
                LocalDate.of(2026, 10, 5));
        assertThat(l.state()).isEqualTo(State.ABOVE_USUAL);
        assertThat(l.usual().minor()).isEqualTo(100_000);
        assertThat(l.alert()).contains("above its usual month of about AED 1,000");
    }

    @Test
    void alerts_put_the_worst_first_and_skip_the_fine_ones() {
        Month m = BudgetEngine.month(List.of(
                new Budget("TRANSPORT", "Transport", 100_000, 30_000, List.of()),
                new Budget("GROCERIES", "Groceries", 300_000, 240_000, List.of()),
                new Budget("ENTERTAINMENT", "Entertainment", 120_000, 156_000, List.of())), LocalDate.of(2026, 10, 15), "AED");
        assertThat(m.alerts()).extracting(Line::category).containsExactly("ENTERTAINMENT", "GROCERIES");
        assertThat(m.spent().minor()).isEqualTo(426_000);
        assertThat(m.daysLeft()).isEqualTo(16);
    }
}
