package ae.familyfinance.core.budgets;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spent so far against each monthly family budget, and the overspend alerts (feature 8, second half): a budget
 * already over its limit, one heading over it at this pace, or one far above its usual month. Pure: the service
 * loads the budgets and what was spent. Every number in the words comes from {@link Money}.
 */
public final class BudgetEngine {

    /** From this day of the month the pace is worth a warning (earlier, one big shop skews it). */
    static final int PACE_FROM_DAY = 7;
    /** "Heading over" when the month would end this far over the limit at today's pace: 115%. */
    static final int PACE_PERCENT = 115;
    /** "Above its usual month" when this month is already this far over the 3-month average: 125%. */
    static final int USUAL_PERCENT = 125;
    /** The limit sits at this point of the bar, so overspending has room to show (bar widths in basis points). */
    static final int LIMIT_AT_BP = 7500;

    public enum State { OK, ABOVE_USUAL, HEADING_OVER, OVER }

    /** A family budget for one category, and what was spent on it this month and in the 3 months before. */
    public record Budget(String category, String label, long limitMinor, long spentMinor, List<Long> lastMonthsMinor) {
    }

    /**
     * One budget's line. {@code fillBp} and {@code pastBp} are bar widths where the limit sits at 75%; {@code usual}
     * is the average of the last 3 months (null when there is no history); {@code alert} is a sentence, or null.
     */
    public record Line(String category, String label, MoneyView limit, MoneyView spent, int percent, MoneyView left,
                       MoneyView over, MoneyView usual, MoneyView atThisPace, State state, String alert,
                       int fillBp, int pastBp) {
    }

    public record Month(String monthLabel, int daysLeft, MoneyView limit, MoneyView spent, List<Line> lines,
                        List<Line> alerts) {
    }

    private BudgetEngine() {
    }

    public static Month month(List<Budget> budgets, LocalDate today, String currency) {
        YearMonth ym = YearMonth.from(today);
        int day = today.getDayOfMonth();
        int daysInMonth = ym.lengthOfMonth();
        int daysLeft = daysInMonth - day;
        Money totalLimit = Money.zero(currency);
        Money totalSpent = Money.zero(currency);
        List<Line> lines = new ArrayList<>();
        for (Budget b : budgets) {
            Money limit = new Money(b.limitMinor(), currency);
            Money spent = new Money(b.spentMinor(), currency);
            totalLimit = totalLimit.plus(limit);
            totalSpent = totalSpent.plus(spent);
            lines.add(line(b, limit, spent, day, daysInMonth, daysLeft, currency));
        }
        List<Line> alerts = lines.stream().filter(l -> l.state() != State.OK)
                .sorted(Comparator.comparing(Line::state).reversed().thenComparing(Line::percent, Comparator.reverseOrder()))
                .toList();
        String label = ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
        return new Month(label, daysLeft, MoneyView.of(totalLimit), MoneyView.of(totalSpent), List.copyOf(lines), alerts);
    }

    static Line line(Budget b, Money limit, Money spent, int day, int daysInMonth, int daysLeft, String currency) {
        int percent = spent.percentOf(limit);
        Money left = limit.minus(spent).max(Money.zero(currency));
        Money over = spent.minus(limit).max(Money.zero(currency));
        Money pace = spent.scaled(daysInMonth, day);
        Money usual = b.lastMonthsMinor().isEmpty() ? null
                : new Money(b.lastMonthsMinor().stream().mapToLong(Long::longValue).sum(), currency)
                        .averageOver(b.lastMonthsMinor().size());
        String name = b.label();
        String togo = daysLeft == 0 ? "on the last day of the month" : "with " + daysLeft + (daysLeft == 1 ? " day" : " days") + " to go";

        State state = State.OK;
        String alert = null;
        if (limit.isPositive() && spent.compareTo(limit) > 0) {
            state = State.OVER;
            alert = name + " is at " + percent + "% of its " + limit.toDisplay() + " budget " + togo
                    + ": " + over.toDisplay() + " over.";
        } else if (limit.isPositive() && day >= PACE_FROM_DAY && daysLeft > 0
                   && pace.compareTo(limit.scaled(PACE_PERCENT, 100)) > 0) {
            state = State.HEADING_OVER;
            alert = name + " is at " + percent + "% of its " + limit.toDisplay() + " budget " + togo
                    + ". At this pace the month ends near " + pace.roundedUpTo(1000).toDisplay() + ".";
        } else if (usual != null && usual.isPositive() && spent.compareTo(usual.scaled(USUAL_PERCENT, 100)) > 0) {
            state = State.ABOVE_USUAL;
            alert = name + " is already " + spent.toDisplay() + " this month, above its usual month of about "
                    + usual.roundedUpTo(1000).toDisplay() + ".";
        }

        // The bar: the limit at 75%; anything over it fills the last quarter, capped at the end
        int fillBp = limit.isPositive() ? (int) Math.min(LIMIT_AT_BP, (long) LIMIT_AT_BP * spent.minor() / limit.minor()) : 0;
        int pastBp = limit.isPositive() && over.isPositive()
                ? (int) Math.min(10_000 - LIMIT_AT_BP, (long) LIMIT_AT_BP * over.minor() / limit.minor()) : 0;
        return new Line(b.category(), name, MoneyView.of(limit), MoneyView.of(spent), percent, MoneyView.of(left),
                MoneyView.of(over), usual == null ? null : MoneyView.of(usual), MoneyView.of(pace), state, alert,
                fillBp, pastBp);
    }
}
