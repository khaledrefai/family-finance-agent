package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The long-term savings calculator: what a starting amount plus the same amount each month comes to after
 * 1, 5 and 10 years at a yearly rate the parent picks, next to the same money kept as cash (0%).
 * Education only: the rate is the parent's example, never a forecast, and nothing here names a fund, a
 * product, gold or a time to buy. Interest is added monthly (rate ÷ 12) to the balance at the start of the
 * month, then the month's saving goes in, each step rounded to the fils, so the totals are exact and repeatable
 * (and shown to the nearest dirham).
 * Rates are basis points (500 = 5% a year) so there are no floats.
 */
public final class SavingsCalculator {

    public static final int MAX_RATE_BP = 1_500;
    public static final int MAX_YEARS = 40;
    public static final int DEFAULT_YEARS = 10;
    /** The marks every result shows, whatever the horizon. */
    static final int[] MARKS = {1, 5, 10};
    /** How far "when would it reach the target" looks. */
    static final int REACH_LIMIT_MONTHS = 50 * 12;

    public record Input(Money start, Money monthly, int rateBp, int years, Money target) {
    }

    /** One point on the way: what went in, what the rate added, the total, and the same money kept as cash. */
    public record YearLine(int year, MoneyView putIn, MoneyView growth, MoneyView total, MoneyView asCash,
                           int totalBp, int cashBp) {
    }

    /**
     * Only when there is a target. {@code months}: when the total first reaches it at this rate (null if not
     * within 50 years); {@code monthlyNeeded}: the whole-dirham monthly amount that reaches it within the horizon.
     */
    public record Reach(MoneyView target, Integer months, String when, MoneyView monthlyNeeded, int years,
                        String line) {
    }

    public record Projection(MoneyView start, MoneyView monthly, int rateBp, String rateText, int years,
                             List<YearLine> marks, List<YearLine> everyYear, Reach reach, String summary,
                             List<String> notes) {
    }

    private SavingsCalculator() {
    }

    public static Projection project(Input in) {
        int years = in.years();
        int span = Math.max(years, DEFAULT_YEARS);
        Money[] totals = balances(in.start(), in.monthly(), in.rateBp(), span * 12);

        Money top = totals[years * 12].max(cash(in, years * 12));
        List<YearLine> every = new ArrayList<>();
        for (int y = 0; y <= years; y++) {
            every.add(line(in, y, totals[y * 12], top));
        }
        TreeSet<Integer> markYears = new TreeSet<>();
        for (int m : MARKS) {
            markYears.add(m);
        }
        markYears.add(years);
        Money markTop = totals[markYears.last() * 12].max(cash(in, markYears.last() * 12));
        List<YearLine> marks = markYears.stream().map(y -> line(in, y, totals[y * 12], markTop)).toList();

        YearLine end = every.get(every.size() - 1);
        String rate = rateText(in.rateBp());
        String summary = years + (years == 1 ? " year" : " years") + " of " + MoneyView.of(in.monthly()).text()
                + " a month" + (in.start().isPositive() ? ", starting from " + MoneyView.of(in.start()).text() : "")
                + ": " + end.putIn().text() + " put in. At an example " + rate + " a year it comes to "
                + end.total().text() + (in.rateBp() > 0 ? ", " + end.growth().text() + " of it growth." : ".");

        List<String> notes = new ArrayList<>();
        notes.add("An example at the rate you picked, not a forecast or a promise. Real returns go up and down, "
                + "and some years they are below zero.");
        notes.add("Kept as cash is the same money at 0%. Prices also rise over the years, so the same amount buys less later.");
        notes.add("The app never names a fund, a product or a time to buy. Real investing would come later, "
                + "through a licensed partner.");
        return new Projection(MoneyView.of(in.start()), MoneyView.of(in.monthly()), in.rateBp(), rate, years, marks,
                every, reach(in), summary, notes);
    }

    /** The balance at the end of each month, from month 0 (the start) to {@code months}. */
    static Money[] balances(Money start, Money monthly, int rateBp, int months) {
        Money[] out = new Money[months + 1];
        Money balance = start;
        out[0] = balance;
        for (int m = 1; m <= months; m++) {
            balance = balance.plus(interest(balance, rateBp)).plus(monthly);
            out[m] = balance;
        }
        return out;
    }

    /** One month's interest: balance × rate ÷ 12, rounded half up to the fils. */
    static Money interest(Money balance, int rateBp) {
        return balance.scaled(rateBp, 12L * BucketEngine.WHOLE);
    }

    private static Money cash(Input in, int months) {
        return in.start().plus(in.monthly().times(months));
    }

    /** Totals are shown to the nearest dirham (an example has no use for fils); growth is total minus what went in. */
    private static YearLine line(Input in, int year, Money exact, Money top) {
        Money total = exact.scaled(1, 100).times(100);
        Money putIn = cash(in, year * 12);
        return new YearLine(year, MoneyView.of(putIn), MoneyView.of(total.minus(putIn)), MoneyView.of(total),
                MoneyView.of(putIn), share(total, top), share(putIn, top));
    }

    private static int share(Money part, Money top) {
        return top.isPositive() ? (int) Math.min(BucketEngine.WHOLE, part.scaled(BucketEngine.WHOLE, top.minor()).minor()) : 0;
    }

    private static Reach reach(Input in) {
        if (in.target() == null || !in.target().isPositive()) {
            return null;
        }
        Money target = in.target();
        Integer months = null;
        Money balance = in.start();
        for (int m = 0; m <= REACH_LIMIT_MONTHS; m++) {
            if (balance.compareTo(target) >= 0) {
                months = m;
                break;
            }
            balance = balance.plus(interest(balance, in.rateBp())).plus(in.monthly());
        }
        Money needed = monthlyNeeded(in.start(), target, in.rateBp(), in.years() * 12);
        String when = months == null ? "not within 50 years" : months == 0 ? "already there" : duration(months);
        String line = months != null && months == 0
                ? "Already at " + MoneyView.of(target).text() + "."
                : (in.monthly().isPositive() || in.start().isPositive()
                        ? "At " + MoneyView.of(in.monthly()).text() + " a month it reaches " + MoneyView.of(target).text()
                        + (months == null ? " not within 50 years" : " in " + when) + ". "
                        : "Nothing goes in each month yet. ")
                + "To get there in " + in.years()
                + (in.years() == 1 ? " year" : " years") + " at an example " + rateText(in.rateBp()) + ", it takes "
                + MoneyView.of(needed).text() + " a month.";
        return new Reach(MoneyView.of(target), months, when, MoneyView.of(needed), in.years(), line);
    }

    /**
     * The smallest whole-dirham monthly amount whose total reaches {@code target} after {@code months}, found by
     * running the same month-by-month sums (so it matches the projection to the fils, rounding and all).
     */
    static Money monthlyNeeded(Money start, Money target, int rateBp, int months) {
        String cur = target.currency();
        if (balances(start, Money.zero(cur), rateBp, months)[months].compareTo(target) >= 0) {
            return Money.zero(cur);
        }
        long lo = 0;
        long hi = Math.ceilDiv(target.minor(), 100L); // AED target a month always gets there in a month or more
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (balances(start, new Money(mid * 100, cur), rateBp, months)[months].compareTo(target) >= 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return new Money(lo * 100, cur);
    }

    /** 500 as "5%", 450 as "4.5%", 425 as "4.25%". */
    public static String rateText(int rateBp) {
        return BucketEngine.percent(rateBp);
    }

    /** 100 months as "8 years 4 months". */
    static String duration(int months) {
        int y = months / 12;
        int m = months % 12;
        String years = y == 0 ? "" : y + (y == 1 ? " year" : " years");
        String rest = m == 0 ? "" : m + (m == 1 ? " month" : " months");
        return (years + (years.isEmpty() || rest.isEmpty() ? "" : " ") + rest).trim();
    }
}
