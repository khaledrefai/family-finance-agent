package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Cashflow.Line;
import ae.familyfinance.core.plan.CashflowEngine.GoalFact;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/plan/options: two or three ways to pay for a family goal (relaxed, steady, faster), each with a
 * monthly amount, a ready date, and whether it fits the spare cash of every month on the way.
 * The Planner agent explains them and says which it would pick; the numbers all come from here.
 */
public final class PlanOptionsEngine {

    /** Monthly amounts are rounded up to a whole AED 50, so they are easy to remember and never fall short. */
    static final long ROUND_TO_MINOR = 5_000;
    /** Without a target date, "steady" spreads the goal over this many months. */
    static final int DEFAULT_MONTHS = 8;

    public record Option(String id, String name, MoneyView monthly, int months, LocalDate readyBy, String readyMonth,
                         boolean meetsDate, boolean fits, String tightestMonth, MoneyView spareInTightest) {
    }

    public record PlanOptions(UUID goalId, String goalName, Tier tier, MoneyView target, MoneyView saved,
                              MoneyView remaining, LocalDate targetDate, MoneyView currentMonthly,
                              List<Option> options, String recommended, String recommendedReason, List<Line> why) {
    }

    private PlanOptionsEngine() {
    }

    public static PlanOptions options(CashflowEngine engine, GoalFact goal, LocalDate targetDate, String currency) {
        Money target = new Money(goal.targetMinor(), currency);
        Money saved = new Money(goal.savedMinor(), currency);
        Money remaining = target.minus(saved).max(Money.zero(currency));
        LocalDate date = targetDate != null ? targetDate : goal.targetDate();
        Money current = goal.monthlyMinor() == null ? Money.zero(currency) : new Money(goal.monthlyMinor(), currency);
        Money[] spare = engine.spareWithout(goal.id());

        List<Line> why = new ArrayList<>();
        why.add(new Line("Left to save", MoneyView.of(remaining), null));
        why.add(new Line("Spare a month without this goal, " + engine.month(0).getMonth()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH), MoneyView.of(spare[0]), null));
        if (!remaining.isPositive()) {
            return new PlanOptions(goal.id(), goal.name(), goal.tier(), MoneyView.of(target), MoneyView.of(saved),
                    MoneyView.of(remaining), date, MoneyView.of(current), List.of(), null, "This goal is already reached.", why);
        }

        int months = date == null ? DEFAULT_MONTHS
                : (int) Math.max(1, ChronoUnit.MONTHS.between(engine.month(0), YearMonth.from(date)) + 1);
        Map<String, Integer> paces = new LinkedHashMap<>();
        paces.put("relaxed", months + Math.max(3, months / 3));
        paces.put("steady", months);
        paces.put("faster", Math.max(1, months - Math.max(2, months / 4)));

        List<Option> options = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (Map.Entry<String, Integer> pace : paces.entrySet()) {
            Money monthly = remaining.dividedRoundingUp(pace.getValue()).roundedUpTo(ROUND_TO_MINOR);
            if (seen.contains(monthly.minor())) {
                continue;
            }
            seen.add(monthly.minor());
            options.add(option(engine, pace.getKey(), monthly, remaining, spare, date, currency));
        }

        Option pick = options.stream().filter(o -> o.id().equals("steady") && o.fits()).findFirst()
                .or(() -> options.stream().filter(o -> o.id().equals("relaxed") && o.fits()).findFirst())
                .or(() -> options.stream().filter(Option::fits).findFirst())
                .orElse(null);
        String reason;
        if (pick == null) {
            reason = "None of the paces fits every month. Free up money first, or move the date.";
        } else if (pick.id().equals("steady")) {
            reason = date == null ? "Fits every month with money to spare." : "Meets the date and fits every month.";
        } else {
            reason = "The steady pace does not fit every month; this one does.";
        }
        why.add(new Line("Months to the target date", null, date == null ? "No date set, planned over " + months + " months" : String.valueOf(months)));
        return new PlanOptions(goal.id(), goal.name(), goal.tier(), MoneyView.of(target), MoneyView.of(saved),
                MoneyView.of(remaining), date, MoneyView.of(current), options, pick == null ? null : pick.id(), reason, why);
    }

    private static Option option(CashflowEngine engine, String id, Money monthly, Money remaining, Money[] spare,
                                 LocalDate date, String currency) {
        int needed = (int) Math.ceilDiv(remaining.minor(), monthly.minor());
        YearMonth ready = engine.month(needed - 1);
        LocalDate readyBy = ready.atEndOfMonth();
        boolean fits = true;
        int tightest = 0;
        Money tightestSpare = null;
        Money left = remaining;
        for (int m = 0; m < Math.min(needed, CashflowEngine.MONTHS); m++) {
            Money put = monthly.min(left);
            left = left.minus(put);
            Money after = spare[m].minus(put);
            if (tightestSpare == null || after.compareTo(tightestSpare) < 0) {
                tightestSpare = after;
                tightest = m;
            }
            if (after.isNegative()) {
                fits = false;
            }
        }
        String name = Character.toUpperCase(id.charAt(0)) + id.substring(1);
        return new Option(id, name, MoneyView.of(monthly), needed, readyBy,
                ready.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ready.getYear(),
                date == null || !readyBy.isAfter(date.withDayOfMonth(date.lengthOfMonth())), fits,
                engine.month(tightest).getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                MoneyView.of(tightestSpare == null ? Money.zero(currency) : tightestSpare));
    }
}
