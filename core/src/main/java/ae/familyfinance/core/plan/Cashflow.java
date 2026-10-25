package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.MoneyView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/plan/cashflow: the family's money month by month, as the money engine sees it.
 * Every amount is worked out in Java; the agent and the web app only show it.
 */
public record Cashflow(
        String currency,
        LocalDate today,
        String monthLabel,
        Section income,
        Section commitments,
        Section everyday,
        MoneyView free,
        Section saving,
        MoneyView spare,
        List<Step> steps,
        UntilNextIncome untilNextIncome,
        Upcoming nextCommitment,
        List<Upcoming> upcoming,
        List<Month> months,
        Health health,
        List<String> notes) {

    /** A titled group of lines with its total. */
    public record Section(MoneyView total, List<Line> lines) {
    }

    /** {@code note} is a short plain fact, e.g. "Planned on a cautious base" or "Reserve for 4 cheques a year". */
    public record Line(String label, MoneyView amount, String note) {
    }

    /**
     * One bar of the step-down chart. {@code fromBp} and {@code toBp} place it on the income bar in basis points
     * (5014 = 50.14%), so the web app draws it without dividing anything itself.
     */
    public record Step(String label, MoneyView amount, int fromBp, int toBp, String kind) {
    }

    /** "Free until pay day": money you can use now, minus everything that has to be paid before the next pay. */
    public record UntilNextIncome(LocalDate nextIncomeOn, String nextIncomeLabel, MoneyView free, List<Line> why) {
    }

    public enum State {
        /** Comes out of the month's pay (rent paid monthly, du, the car loan). */
        FROM_PAY,
        /** A lumpy payment whose reserve will be full by its date. */
        RESERVED,
        /** A lumpy payment whose reserve will not be full by its date: the gap comes out of free cash. */
        SHORT
    }

    public record Upcoming(UUID commitmentId, String name, CommitmentKind kind, String kindLabel, LocalDate dueOn,
                           int daysLeft, MoneyView amount, boolean estimate, State state, MoneyView shortBy,
                           String label) {
    }

    /**
     * One month of the 12-month calendar. {@code shareOfMax} is the free-cash bar height as a % of the best month.
     * {@code tight} = free cash is below the usual month because a reserve falls short.
     */
    public record Month(String month, String label, MoneyView income, MoneyView commitments, MoneyView reserveShortfall,
                        MoneyView everyday, MoneyView free, MoneyView saving, MoneyView spare, int shareOfMax,
                        boolean tight, boolean negative) {
    }

    /**
     * Health checks. {@code debtSharePercent}: loan and card commitments as a share of planned income.
     * {@code variableIncomePercent}: how much of planned income is commission or sales.
     */
    public record Health(int debtSharePercent, int variableIncomePercent, List<String> negativeMonths,
                         String tightestMonth, MoneyView tightestFree) {
    }
}
