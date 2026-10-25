package ae.familyfinance.core.alerts;

import ae.familyfinance.core.alerts.WeeklyPlanEngine.WeekPlan;
import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Cashflow;
import ae.familyfinance.core.plan.CommitmentKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyPlanEngineTest {

    static final LocalDate SUNDAY = LocalDate.of(2026, 10, 25);

    static MoneyView aed(long major) {
        return MoneyView.of(Money.aed(major * 100));
    }

    static Cashflow.UntilNextIncome untilPay(long freeMajor, int days) {
        return new Cashflow.UntilNextIncome(SUNDAY.plusDays(days), "Omar salary", aed(freeMajor), List.of());
    }

    static Cashflow.Upcoming bill(String name, long major, int days) {
        return new Cashflow.Upcoming(UUID.randomUUID(), name, CommitmentKind.UTILITY, "Utility", SUNDAY.plusDays(days), days,
                aed(major), false, Cashflow.State.FROM_PAY, aed(0), null);
    }

    static BudgetEngine.Month budgets(int day, BudgetEngine.Budget... b) {
        return BudgetEngine.month(List.of(b), SUNDAY.withDayOfMonth(day), "AED");
    }

    @Test
    void free_cash_is_shared_evenly_over_the_days_to_pay_day() {
        // AED 6,000 free for 20 days: AED 300 a day, 7 days = AED 2,100
        WeekPlan p = WeeklyPlanEngine.plan(SUNDAY, "AED", untilPay(6000, 20), List.of(), budgets(25));
        assertThat(p.freeThisWeek().text()).isEqualTo("AED 2,100");
        assertThat(p.headline()).isEqualTo("AED 2,100 is free to spend this week, keeping the same for each week until pay day on 14 Nov.");
    }

    @Test
    void pay_day_inside_the_week_gives_all_of_it_and_rounds_down_to_ten() {
        WeekPlan p = WeeklyPlanEngine.plan(SUNDAY, "AED", untilPay(1234, 4), List.of(), budgets(25));
        assertThat(p.freeThisWeek().text()).isEqualTo("AED 1,230");
        assertThat(p.headline()).endsWith("until pay day on 29 Oct.");
    }

    @Test
    void nothing_free_says_so() {
        WeekPlan p = WeeklyPlanEngine.plan(SUNDAY, "AED", untilPay(-200, 10), List.of(), budgets(25));
        assertThat(p.freeThisWeek().minor()).isZero();
        assertThat(p.headline()).startsWith("Nothing is free to spend this week");
    }

    @Test
    void lists_this_weeks_bills_and_the_budgets_to_slow_down() {
        WeekPlan p = WeeklyPlanEngine.plan(SUNDAY, "AED", untilPay(6000, 20),
                List.of(bill("DEWA", 900, 1), bill("du", 450, 7)),
                budgets(25,
                        new BudgetEngine.Budget("ENTERTAINMENT", "Entertainment", 120000, 156000, List.of()),
                        // 2,900 of 3,000 by the 25th: heading over (pace AED 3,596), AED 100 left for the last week
                        new BudgetEngine.Budget("GROCERIES", "Groceries", 300000, 290000, List.of())));
        assertThat(p.bills()).extracting(WeeklyPlanEngine.Line::label).containsExactly("DEWA");
        assertThat(p.slowDown()).extracting(WeeklyPlanEngine.Line::label).containsExactly("Entertainment", "Groceries");
        assertThat(p.slowDown().get(0).note()).startsWith("AED 360 over its budget");
        assertThat(p.slowDown().get(1).amount().text()).isEqualTo("AED 100");
        assertThat(p.body()).contains("Bills this week: DEWA AED 900.").contains("Entertainment is over its budget.")
                .contains("Groceries: AED 100 this week.");
    }
}
