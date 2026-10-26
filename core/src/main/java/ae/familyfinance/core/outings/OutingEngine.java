package ae.familyfinance.core.outings;

import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.money.Money;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Outings that fit the month" (study, Budget advice, week 7): what each outing costs this family, and a plan of a
 * month's outings that fits the entertainment budget. Pure: the service loads the family, the budget and the list.
 *
 * <p>Two ways to make a plan, and the engine checks both the same way:
 * <ul>
 *   <li>{@link #suggest}: the engine's own pick. When the month's money is used up, free outings only. Otherwise up to
 *       2 paid outings, each costing at most half the family's usual outing, then free ones, up to one a weekend.</li>
 *   <li>{@link #check}: the outings the Planner agent picked from the parent's words. Unknown or closed ones are left
 *       out, and while they come to more than what is left, the dearest one is left out.</li>
 * </ul>
 * Every number the agent may say is in the plan's {@code facts}.
 */
public final class OutingEngine {

    /** One outing a weekend. */
    public static final int MAX_PICKS = 4;
    /** When this month's entertainment money is used up, a few free ideas. */
    static final int MAX_FREE_WHEN_OVER = 3;
    static final int MAX_PAID = 2;
    /** When few outings of the kind asked for fit, free ones of any kind fill the plan up to this many. */
    static final int MIN_PICKS = 3;

    public enum When { THIS_MONTH, NEXT_MONTH }

    /** What a parent may ask for. FREE means it costs nothing to get in. */
    public enum Kind { FREE, INDOOR, OUTDOOR, ANIMALS, NATURE, BEACH, CULTURE, PLAY }

    public record Person(String name, AgeBand band) {
    }

    /** One outing from the saved list. {@code months} empty = open all year. {@code childUpTo}: oldest child-ticket age. */
    public record Option(String key, String name, String area, Set<Kind> kinds, Money adult, Money child, int childUpTo,
                         Set<Integer> months, String about, String site) {

        boolean openIn(YearMonth m) {
            return months.isEmpty() || months.contains(m.getMonthValue());
        }
    }

    /**
     * The entertainment budget. {@code usualOutingMinor}: the family's average outing over the last 3 months (whole
     * dirhams; null without history), from {@code outingsCounted} payments.
     */
    public record Fun(long limitMinor, long spentMinor, Long usualOutingMinor, int outingsCounted) {
    }

    public record Row(String label, String value) {
    }

    /** An outing with this family's cost: one row per ticket. */
    public record Costed(Option option, Money cost, List<Row> tickets, String costLine) {

        boolean free() {
            return cost.minor() == 0;
        }
    }

    /**
     * A month's outings. {@code allowance}: what the plan may spend (what is left this month, or next month's budget);
     * {@code left}: the allowance after the plan; {@code usualCost}: the same number of usual outings (null without
     * history); {@code saving}: usualCost − total, when that is more than zero.
     */
    public record Plan(When when, YearMonth month, String monthLabel, Money budget, Money spent, Money allowance,
                       List<Costed> picks, Money total, Money left, Money usualOuting, Money usualCost, Money saving,
                       List<String> leftOut, List<String> facts, List<Row> why) {
    }

    private OutingEngine() {
    }

    // ---------------------------------------------------------------- what an outing costs this family

    /** The family's tickets. A child whose age band reaches past the child-ticket age pays the adult price. */
    public static Costed cost(Option o, List<Person> people, String currency) {
        Money total = Money.zero(currency);
        List<Row> rows = new ArrayList<>();
        int adults = 0;
        Money adultPrice = o.adult();
        for (Person p : people) {
            boolean childTicket = p.band() != AgeBand.ADULT && oldest(p.band()) <= o.childUpTo();
            Money price = childTicket ? o.child() : o.adult();
            total = total.plus(price);
            if (p.band() == AgeBand.ADULT) {
                adults++;
            }
            rows.add(new Row(p.name() + (p.band() == AgeBand.ADULT ? "" : childTicket ? ", child ticket" : ", adult ticket"),
                    price.minor() == 0 ? "Free" : price.toDisplay()));
        }
        String line;
        if (total.minor() == 0) {
            line = "Free to get in";
        } else {
            List<String> parts = new ArrayList<>();
            if (adults > 0) {
                parts.add(adults + (adults == 1 ? " adult at " : " adults at ") + adultPrice.toDisplay());
            }
            for (Person p : people) {
                if (p.band() != AgeBand.ADULT) {
                    boolean childTicket = oldest(p.band()) <= o.childUpTo();
                    Money price = childTicket ? o.child() : o.adult();
                    parts.add(p.name() + " " + (price.minor() == 0 ? "free" : price.toDisplay()));
                }
            }
            line = String.join(", ", parts);
        }
        return new Costed(o, total, List.copyOf(rows), line);
    }

    /** The oldest age in a band: a band that reaches past the child-ticket age is counted at the adult price. */
    static int oldest(AgeBand band) {
        return switch (band) {
            case CHILD_6_9 -> 9;
            case PRETEEN_10_12 -> 12;
            case TEEN_13_17 -> 17;
            case ADULT -> Integer.MAX_VALUE;
        };
    }

    public static List<Costed> costAll(List<Option> options, List<Person> people, String currency) {
        return options.stream().map(o -> cost(o, people, currency)).toList();
    }

    // ---------------------------------------------------------------- the month

    public static YearMonth month(When when, YearMonth now) {
        return when == When.THIS_MONTH ? now : now.plusMonths(1);
    }

    /** What a plan may spend: what is left of this month's budget (never below zero), or next month's whole budget. */
    public static Money allowance(When when, Fun fun, String currency) {
        Money limit = new Money(fun.limitMinor(), currency);
        if (when == When.NEXT_MONTH) {
            return limit;
        }
        Money left = limit.minus(new Money(fun.spentMinor(), currency));
        return left.isNegative() ? Money.zero(currency) : left;
    }

    /** The outings open in that month that fit what was asked for (any of the kinds; none asked = all). */
    static List<Costed> fitting(List<Costed> all, YearMonth month, Set<Kind> wanted) {
        return all.stream().filter(c -> c.option().openIn(month))
                .filter(c -> wanted.isEmpty() || wanted.stream().anyMatch(k -> k == Kind.FREE ? c.free() : c.option().kinds().contains(k)))
                .toList();
    }

    // ---------------------------------------------------------------- the engine's own plan

    public static Plan suggest(List<Costed> all, Set<Kind> wanted, When when, YearMonth now, Fun fun, String currency) {
        YearMonth month = month(when, now);
        Money allowance = allowance(when, fun, currency);
        List<Costed> open = fitting(all, month, wanted);
        List<String> leftOut = new ArrayList<>();
        if (open.isEmpty() && !wanted.isEmpty()) {
            leftOut.add("Nothing on the list is " + kindsText(wanted) + " and open in " + label(month) + ", so these are other ideas.");
            open = fitting(all, month, Set.of());
        }
        List<Costed> picks = new ArrayList<>();
        Money total = Money.zero(currency);
        if (!allowance.isPositive()) {
            open.stream().filter(Costed::free).limit(MAX_FREE_WHEN_OVER).forEach(picks::add);
        } else {
            Money cap = paidCap(fun, allowance, currency);
            List<Costed> paid = open.stream().filter(c -> !c.free() && c.cost().compareTo(cap) <= 0)
                    .sorted(Comparator.comparing(Costed::cost).reversed()).toList();
            for (Costed c : paid) {
                if (picks.size() == MAX_PAID) {
                    break;
                }
                if (total.plus(c.cost()).compareTo(allowance) <= 0) {
                    picks.add(c);
                    total = total.plus(c.cost());
                }
            }
            open.stream().filter(Costed::free).limit(MAX_PICKS - picks.size()).forEach(picks::add);
        }
        if (!wanted.isEmpty() && picks.size() < MIN_PICKS) {
            // Only one or two of the kind asked for fit: add free ideas of any kind, and say so
            List<Costed> more = fitting(all, month, Set.of()).stream().filter(Costed::free).filter(c -> !picks.contains(c))
                    .limit(MIN_PICKS - picks.size()).toList();
            if (!more.isEmpty()) {
                int asked = picks.size();
                picks.addAll(more);
                leftOut.add(asked == 0 ? "None of the kind asked for fit, so these are free ideas."
                        : "Only " + asked + " of the kind asked for " + (asked == 1 ? "fits" : "fit")
                          + ", so free ideas of other kinds are added.");
            }
        }
        return plan(when, month, fun, allowance, picks, leftOut, currency, paidCapNote(fun, allowance, currency, picks));
    }

    /** A paid outing in the engine's plan costs at most half the family's usual outing (or a quarter of the allowance). */
    static Money paidCap(Fun fun, Money allowance, String currency) {
        return fun.usualOutingMinor() != null && fun.usualOutingMinor() > 0
                ? new Money(fun.usualOutingMinor(), currency).scaled(1, 2)
                : allowance.scaled(1, 4);
    }

    private static String paidCapNote(Fun fun, Money allowance, String currency, List<Costed> picks) {
        if (!allowance.isPositive() || picks.stream().allMatch(Costed::free)) {
            return null;
        }
        Money cap = paidCap(fun, allowance, currency);
        return fun.usualOutingMinor() != null && fun.usualOutingMinor() > 0
                ? "Each paid outing costs at most " + cap.toDisplay() + ", half the family's usual outing."
                : "Each paid outing costs at most " + cap.toDisplay() + ", a quarter of the money for the month.";
    }

    // ---------------------------------------------------------------- the Planner's pick, checked

    public static Plan check(List<Costed> all, List<String> keys, When when, YearMonth now, Fun fun, String currency) {
        YearMonth month = month(when, now);
        Money allowance = allowance(when, fun, currency);
        List<String> leftOut = new ArrayList<>();
        List<Costed> picks = new ArrayList<>();
        for (String key : new LinkedHashSet<>(keys)) {
            Costed c = all.stream().filter(x -> x.option().key().equals(key)).findFirst().orElse(null);
            if (c == null) {
                continue;  // not on the list: never shown, never priced
            }
            if (!c.option().openIn(month)) {
                leftOut.add("Left out " + c.option().name() + ": it is closed in " + label(month) + ".");
                continue;
            }
            if (picks.size() == MAX_PICKS) {
                leftOut.add("Left out " + c.option().name() + ": one outing a weekend is " + MAX_PICKS + " a month.");
                continue;
            }
            picks.add(c);
        }
        Money total = sum(picks, currency);
        while (total.compareTo(allowance) > 0) {
            Costed dearest = picks.stream().max(Comparator.comparing(Costed::cost)).orElseThrow();
            picks.remove(dearest);
            total = total.minus(dearest.cost());
            leftOut.add("Left out " + dearest.option().name() + " (" + dearest.cost().toDisplay()
                        + "): with it the plan came to more than the " + allowance.toDisplay() + " there is.");
        }
        return plan(when, month, fun, allowance, picks, leftOut, currency, null);
    }

    // ---------------------------------------------------------------- the plan and its facts

    private static Plan plan(When when, YearMonth month, Fun fun, Money allowance, List<Costed> picks, List<String> leftOut,
                             String currency, String capNote) {
        Money budget = new Money(fun.limitMinor(), currency);
        Money spent = new Money(fun.spentMinor(), currency);
        Money total = sum(picks, currency);
        Money left = allowance.minus(total);
        Money usual = fun.usualOutingMinor() == null ? null : new Money(fun.usualOutingMinor(), currency);
        Money usualCost = usual == null || picks.isEmpty() ? null : usual.times(picks.size());
        Money saving = usualCost == null || usualCost.compareTo(total) <= 0 ? null : usualCost.minus(total);
        String monthLabel = label(month);

        List<String> facts = new ArrayList<>();
        List<Row> why = new ArrayList<>();
        if (!budget.isPositive()) {
            facts.add("There is no entertainment budget yet, so these are free outings. Set one in Money, Budgets.");
        } else {
            facts.add("Entertainment budget: " + budget.toDisplay() + " a month.");
            why.add(new Row("Entertainment budget, a month", budget.toDisplay()));
            if (when == When.THIS_MONTH) {
                Money over = spent.minus(budget);
                facts.add(over.isPositive()
                        ? "Spent on entertainment this month: " + spent.toDisplay() + ", " + over.toDisplay() + " over the budget."
                        : "Spent on entertainment this month: " + spent.toDisplay() + ", " + allowance.toDisplay() + " left.");
                why.add(new Row("Spent this month", spent.toDisplay()));
                why.add(new Row(over.isPositive() ? "Over the budget" : "Left this month", over.isPositive() ? over.toDisplay() : allowance.toDisplay()));
            } else {
                facts.add("The plan is for " + monthLabel + ", with the whole " + budget.toDisplay() + " budget.");
            }
        }
        if (usual != null) {
            facts.add("The family's usual outing over the last 3 months cost about " + usual.toDisplay() + " ("
                      + fun.outingsCounted() + " outings).");
            why.add(new Row("Usual outing, last 3 months", "about " + usual.toDisplay()));
        }
        if (when == When.THIS_MONTH && !allowance.isPositive() && budget.isPositive()) {
            facts.add("Nothing is left for outings in " + monthLabel + ", so these are free ones.");
        }
        if (capNote != null) {
            facts.add(capNote);
        }
        for (Costed c : picks) {
            facts.add(c.option().name() + ": " + (c.free() ? "free to get in." : c.cost().toDisplay() + " for the family (" + c.costLine() + ")."));
            why.add(new Row(c.option().name(), c.free() ? "Free" : c.cost().toDisplay()));
        }
        if (picks.isEmpty()) {
            facts.add("No outing on the list fits what is left for " + monthLabel + ".");
        } else if (total.minor() == 0) {
            facts.add(picks.size() == 1 ? "It is free to get in." : "All " + picks.size() + " are free to get in.");
            why.add(new Row("This plan", "Free"));
        } else {
            facts.add("Together: " + total.toDisplay() + " for " + picks.size() + (picks.size() == 1 ? " outing" : " outings")
                      + (allowance.isPositive() ? ", leaving " + left.toDisplay() + " of the " + allowance.toDisplay() + "." : "."));
            why.add(new Row("This plan", total.toDisplay()));
            if (allowance.isPositive()) {
                why.add(new Row("Left after it", left.toDisplay()));
            }
        }
        if (saving != null) {
            facts.add(picks.size() + (picks.size() == 1 ? " usual outing" : " usual outings") + " would cost about "
                      + usualCost.toDisplay() + "; this plan costs " + saving.toDisplay() + " less.");
            why.add(new Row(picks.size() + " usual outings", "about " + usualCost.toDisplay()));
            why.add(new Row("Saved with this plan", saving.toDisplay()));
        }
        facts.addAll(leftOut);
        return new Plan(when, month, monthLabel, budget, spent, allowance, List.copyOf(picks), total, left, usual, usualCost,
                saving, List.copyOf(leftOut), List.copyOf(facts), List.copyOf(why));
    }

    private static Money sum(List<Costed> picks, String currency) {
        Money total = Money.zero(currency);
        for (Costed c : picks) {
            total = total.plus(c.cost());
        }
        return total;
    }

    static String label(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String kindsText(Set<Kind> kinds) {
        return String.join(" or ", kinds.stream().map(k -> k.name().toLowerCase(Locale.ROOT)).toList());
    }
}
