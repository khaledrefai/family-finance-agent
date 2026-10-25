package ae.familyfinance.core.plan;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Date maths for repeating things: when is the next rent cheque, the next pay day, the next card statement.
 * Plain Java, no database, so the awkward cases (the 31st, February) are easy to test.
 */
public final class Schedules {

    /** Guard against an endless loop on bad data (a weekly item stepping across many years). */
    private static final int MAX_STEPS = 2_000;

    private Schedules() {
    }

    /**
     * Every date in {@code [from, to]} for something that first falls on {@code start} and repeats with
     * {@code frequency}, at most {@code remaining} times counted from {@code start} (null = no end).
     * Each date is worked out from {@code start}, not from the previous date, so the 31st stays the 31st
     * where the month has one (31 Jan, 28 Feb, 31 Mar), instead of drifting to the 28th.
     * CUSTOM has no rule: its dates are stored one by one, so this returns nothing for it.
     */
    public static List<LocalDate> dates(LocalDate start, Frequency frequency, Integer remaining, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if (frequency == Frequency.CUSTOM || to.isBefore(from)) {
            return out;
        }
        for (int k = 0; k < MAX_STEPS; k++) {
            if (remaining != null && k >= remaining) {
                break;
            }
            LocalDate d = step(start, frequency, k);
            if (d.isAfter(to)) {
                break;
            }
            if (!d.isBefore(from)) {
                out.add(d);
            }
            if (frequency == Frequency.ONCE) {
                break;
            }
        }
        return out;
    }

    /** The first date on or after {@code from}, or null if there is none (a one-off in the past, a loan paid off). */
    public static LocalDate nextOnOrAfter(LocalDate start, Frequency frequency, Integer remaining, LocalDate from) {
        List<LocalDate> next = dates(start, frequency, remaining, from, from.plusYears(2));
        return next.isEmpty() ? null : next.get(0);
    }

    static LocalDate step(LocalDate start, Frequency frequency, int k) {
        return switch (frequency) {
            case ONCE, CUSTOM -> start;
            case WEEKLY -> start.plusWeeks(k);
            case MONTHLY -> start.plusMonths(k);
            case QUARTERLY -> start.plusMonths(3L * k);
            case TERMLY -> start.plusMonths(4L * k);
            case YEARLY -> start.plusYears(k);
        };
    }

    /** {@code day} of the given month, moved back to the last day if the month is shorter (31 in February = 28 or 29). */
    public static LocalDate dayOf(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    /** The next card statement date: this month's statement day if it is today or later, otherwise next month's. */
    public static LocalDate nextStatement(LocalDate today, int statementDay) {
        LocalDate thisMonth = dayOf(YearMonth.from(today), statementDay);
        return thisMonth.isBefore(today) ? dayOf(YearMonth.from(today).plusMonths(1), statementDay) : thisMonth;
    }

    /** The statement before {@link #nextStatement}: where the bill now being built up started. */
    public static LocalDate previousStatement(LocalDate today, int statementDay) {
        return dayOf(YearMonth.from(nextStatement(today, statementDay)).minusMonths(1), statementDay);
    }

    /** The payment due date for a statement: the first due day after the statement date. */
    public static LocalDate dueAfter(LocalDate statement, int dueDay) {
        LocalDate sameMonth = dayOf(YearMonth.from(statement), dueDay);
        return sameMonth.isAfter(statement) ? sameMonth : dayOf(YearMonth.from(statement).plusMonths(1), dueDay);
    }
}
