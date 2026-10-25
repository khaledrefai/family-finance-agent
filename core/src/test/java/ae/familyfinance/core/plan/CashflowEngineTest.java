package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.plan.CashflowEngine.BudgetFact;
import ae.familyfinance.core.plan.CashflowEngine.CardDue;
import ae.familyfinance.core.plan.CashflowEngine.CommitmentFact;
import ae.familyfinance.core.plan.CashflowEngine.Dated;
import ae.familyfinance.core.plan.CashflowEngine.Facts;
import ae.familyfinance.core.plan.CashflowEngine.GoalFact;
import ae.familyfinance.core.plan.CashflowEngine.IncomeFact;
import ae.familyfinance.core.plan.CashflowEngine.Projection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CashflowEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final UUID CARD = UUID.randomUUID();

    private static IncomeFact salary(long minor, LocalDate next) {
        return new IncomeFact(UUID.randomUUID(), "Salary", IncomeKind.FIXED, minor, Frequency.MONTHLY, next, null, List.of());
    }

    private static CommitmentFact monthly(String name, long minor, LocalDate next) {
        return new CommitmentFact(UUID.randomUUID(), name, CommitmentKind.UTILITY, minor, Frequency.MONTHLY, next, null,
                null, 0, false, List.of());
    }

    private static CommitmentFact cheques(String name, long reserved, LocalDate... dates) {
        List<Dated> list = java.util.Arrays.stream(dates).map(d -> new Dated(d, 3_000_000, null)).toList();
        return new CommitmentFact(UUID.randomUUID(), name, CommitmentKind.RENT, 3_000_000, Frequency.CUSTOM, dates[0], null,
                null, reserved, false, list);
    }

    private static CashflowEngine engine(List<IncomeFact> incomes, List<CommitmentFact> commitments, long spendable,
                                         List<CardDue> cards, List<GoalFact> goals) {
        return new CashflowEngine(new Facts(TODAY, "AED", incomes, commitments,
                List.of(new BudgetFact("Groceries", false, 300_000)), goals, spendable, cards));
    }

    @Test
    void cautious_base_is_the_average_of_the_three_lowest_of_six_months() {
        List<Dated> receipts = List.of(
                new Dated(LocalDate.of(2026, 4, 25), 680_000, null), new Dated(LocalDate.of(2026, 5, 25), 1_120_000, null),
                new Dated(LocalDate.of(2026, 6, 25), 650_000, null), new Dated(LocalDate.of(2026, 7, 25), 740_000, null),
                new Dated(LocalDate.of(2026, 8, 25), 620_000, null), new Dated(LocalDate.of(2026, 9, 25), 980_000, null),
                new Dated(LocalDate.of(2026, 10, 3), 50_000, null));    // this month is not full yet: left out
        // (6,200 + 6,500 + 6,800) / 3 = 6,500
        assertThat(CashflowEngine.cautiousBase(receipts, TODAY, "AED")).isEqualTo(Money.aed(650_000));
    }

    @Test
    void cautious_base_rounds_down_to_a_whole_dirham() {
        List<Dated> receipts = List.of(new Dated(LocalDate.of(2026, 7, 1), 100_000, null),
                new Dated(LocalDate.of(2026, 8, 1), 100_000, null), new Dated(LocalDate.of(2026, 9, 1), 100_100, null));
        assertThat(CashflowEngine.cautiousBase(receipts, TODAY, "AED")).isEqualTo(Money.aed(100_000));
    }

    @Test
    void free_cash_is_income_minus_commitments_reserves_and_everyday() {
        CashflowEngine e = engine(List.of(salary(2_800_000, LocalDate.of(2026, 10, 28))),
                List.of(monthly("DEWA", 90_000, LocalDate.of(2026, 10, 26))), 0, List.of(), List.of());
        assertThat(e.plannedIncome()).isEqualTo(Money.aed(2_800_000));
        assertThat(e.commitmentsDue()).isEqualTo(Money.aed(90_000));
        assertThat(e.free(0)).isEqualTo(Money.aed(2_800_000 - 90_000 - 300_000));
    }

    @Test
    void lumpy_rent_gets_a_monthly_reserve_of_a_twelfth() {
        CashflowEngine e = engine(List.of(salary(2_800_000, LocalDate.of(2026, 10, 28))),
                List.of(cheques("Rent", 3_000_000, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 2),
                        LocalDate.of(2027, 7, 2), LocalDate.of(2027, 10, 1))), 0, List.of(), List.of());
        // 4 cheques of 30,000 in the next 12 months = 120,000 / 12
        assertThat(e.commitmentsDue()).isEqualTo(Money.aed(1_000_000));
        assertThat(e.summary().months()).noneMatch(m -> m.tight());
    }

    @Test
    void a_reserve_that_cannot_fill_in_time_charges_the_gap_to_the_last_pay_month_before_it() {
        // One cheque in 2 months with nothing put aside: the reserve is 30,000 / 12 = 2,500 a month,
        // so by 1 Dec only 2 pay days have added 5,000 and the other 25,000 comes out of November.
        CashflowEngine e = engine(List.of(salary(2_800_000, LocalDate.of(2026, 10, 28))),
                List.of(cheques("Rent", 0, LocalDate.of(2026, 12, 1))), 0, List.of(), List.of());
        var summary = e.summary();
        assertThat(summary.months().get(1).reserveShortfall().minor()).isEqualTo(2_500_000);
        assertThat(summary.months().get(1).tight()).isTrue();
        assertThat(summary.upcoming()).anyMatch(u -> u.state() == Cashflow.State.SHORT && u.shortBy().minor() == 2_500_000);
    }

    @Test
    void free_on_a_date_counts_cash_now_bills_cards_and_everyday_spending() {
        CashflowEngine e = engine(List.of(salary(2_800_000, LocalDate.of(2026, 11, 14))),
                List.of(monthly("du", 45_000, LocalDate.of(2026, 11, 1))), 1_230_000,
                List.of(new CardDue(CARD, "Visa", LocalDate.of(2026, 11, 13), 595_000)), List.of());
        LocalDate due = LocalDate.of(2026, 11, 13);
        // 12,300 now − du 450 − 19 days of AED 3,000 a month (a month counts as 30 days: 1,900)
        Projection without = e.freeOn(due, CARD);
        assertThat(without.free()).isEqualTo(Money.aed(1_230_000 - 45_000 - 190_000));
        // Counting the Visa bill too
        assertThat(e.freeOn(due, null).free()).isEqualTo(Money.aed(1_230_000 - 45_000 - 190_000 - 595_000));
    }

    @Test
    void goals_are_funded_in_priority_order_until_full() {
        GoalFact buffer = new GoalFact(UUID.randomUUID(), "Buffer", Tier.SURVIVAL, 2_500_000, 1_000_000, 500_000L, null, 10);
        GoalFact trip = new GoalFact(UUID.randomUUID(), "Trip", Tier.GOOD_LIFE, 1_200_000, 360_000, 150_000L, null, 50);
        CashflowEngine e = engine(List.of(salary(2_800_000, LocalDate.of(2026, 10, 28))), List.of(), 0, List.of(),
                List.of(buffer, trip));
        assertThat(e.savingFor(buffer.id())[0]).isEqualTo(Money.aed(500_000));
        assertThat(e.savingFor(buffer.id())[3]).isEqualTo(Money.aed(0));          // full after 3 months
        assertThat(e.saving(0)).isEqualTo(Money.aed(650_000));
    }
}
