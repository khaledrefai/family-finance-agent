package ae.familyfinance.core.advice;

import ae.familyfinance.core.money.Money;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * "Ways to save" the family didn't ask about (study, Budget advice 4, small version): once a month the engine ranks
 * the family's recurring costs and picks up to {@link #MAX_TIPS} tips, each with its own numbers. The agent may
 * reword a tip, but only with these numbers. Pure: the service loads the facts.
 *
 * <p>Ranking: tips with a saving the engine can work out (drop a subscription, keep outings to the budget) come
 * first, biggest saving first; then the "worth comparing" ones (rent, phone and internet), biggest yearly cost first.
 */
public final class WaysToSaveEngine {

    public static final int MAX_TIPS = 3;
    /** Rent above this share of the income the family plans on is worth a look (a common rule of thumb, verify). */
    static final int RENT_SHARE_PERCENT = 25;

    /** A monthly recurring cost: a subscription, a phone plan. */
    public record Recurring(String name, long monthlyMinor) {
    }

    /** {@code contractFrom}: when the next tenancy contract starts, if the family entered it (else null). */
    public record Rent(String name, long yearlyMinor, LocalDate contractFrom) {
    }

    /** {@code usualMinor}: the average of the last 3 months (null when there is no history). */
    public record Outings(long budgetMinor, Long usualMinor) {
    }

    public record Facts(LocalDate today, String currency, long incomeMonthlyMinor, List<Recurring> subscriptions,
                        Rent rent, List<Recurring> telecom, Outings outings) {
    }

    public record Row(String label, String value) {
    }

    /**
     * One tip. {@code text} is the engine's own sentence; {@code facts} are the only numbers the agent may use;
     * {@code savingMinor} is the yearly saving the engine worked out (0 = not known, a "worth comparing" tip).
     */
    public record Tip(String key, String title, String text, List<String> facts, List<Row> why, long savingMinor,
                      long yearlyMinor) {
    }

    private WaysToSaveEngine() {
    }

    /** Every tip that applies, best first. The service drops dismissed and snoozed ones and keeps the first 3. */
    public static List<Tip> tips(Facts f) {
        List<Tip> out = new ArrayList<>();
        subscriptions(f).ifPresent(out::add);
        outings(f).ifPresent(out::add);
        rent(f).ifPresent(out::add);
        telecom(f).ifPresent(out::add);
        return out.stream()
                .sorted(Comparator.comparing((Tip t) -> t.savingMinor() > 0 ? 0 : 1)
                        .thenComparing(Tip::savingMinor, Comparator.reverseOrder())
                        .thenComparing(Tip::yearlyMinor, Comparator.reverseOrder()))
                .toList();
    }

    static java.util.Optional<Tip> subscriptions(Facts f) {
        List<Recurring> subs = f.subscriptions();
        if (subs.isEmpty()) {
            return java.util.Optional.empty();
        }
        String cur = f.currency();
        Money monthly = sum(subs.stream().map(Recurring::monthlyMinor).toList(), cur);
        Recurring smallest = subs.stream().min(Comparator.comparingLong(Recurring::monthlyMinor)).orElseThrow();
        Money saving = new Money(smallest.monthlyMinor(), cur).times(12);
        String names = subs.stream().map(Recurring::name).collect(Collectors.joining(" and "));
        String text;
        List<String> facts = new ArrayList<>();
        if (subs.size() == 1) {
            text = "You pay " + monthly.toDisplay() + " a month for " + names + ". If nobody uses it much, cancelling it saves "
                   + saving.toDisplay() + " a year.";
            facts.add("Subscription: " + names + ", " + monthly.toDisplay() + " a month.");
            facts.add("Cancelling it saves " + saving.toDisplay() + " a year.");
        } else {
            text = "You pay " + monthly.toDisplay() + " a month for " + subs.size() + " subscriptions: " + names
                   + ". Dropping " + smallest.name() + " saves " + saving.toDisplay() + " a year.";
            facts.add("Subscriptions: " + subs.size() + " (" + names + ").");
            facts.add("Paid each month in all: " + monthly.toDisplay() + ".");
            facts.add("Smallest: " + smallest.name() + ", " + new Money(smallest.monthlyMinor(), cur).toDisplay()
                      + " a month; dropping it saves " + saving.toDisplay() + " a year.");
        }
        List<Row> why = new ArrayList<>();
        subs.forEach(s -> why.add(new Row(s.name() + ", a month", new Money(s.monthlyMinor(), cur).toDisplay())));
        if (subs.size() > 1) {
            why.add(new Row("All of them, a month", monthly.toDisplay()));
        }
        why.add(new Row(subs.size() == 1 ? "Cancelling it, a year" : "Dropping " + smallest.name() + ", a year", saving.toDisplay()));
        return java.util.Optional.of(new Tip("subscriptions", subs.size() == 1 ? "Subscription" : "Subscriptions", text,
                List.copyOf(facts), List.copyOf(why), saving.minor(), monthly.times(12).minor()));
    }

    static java.util.Optional<Tip> outings(Facts f) {
        Outings o = f.outings();
        if (o == null || o.usualMinor() == null || o.budgetMinor() <= 0 || o.usualMinor() <= o.budgetMinor()) {
            return java.util.Optional.empty();
        }
        String cur = f.currency();
        Money usual = new Money(o.usualMinor(), cur);
        Money budget = new Money(o.budgetMinor(), cur);
        Money over = usual.minus(budget);
        Money yearly = over.times(12);
        String text = "Entertainment averaged " + usual.toDisplay() + " a month over the last 3 months, " + over.toDisplay()
                      + " over its " + budget.toDisplay() + " budget. Keeping to the budget frees about "
                      + yearly.toDisplay() + " a year.";
        return java.util.Optional.of(new Tip("outings", "Outings", text,
                List.of("Entertainment, 3-month average: " + usual.toDisplay() + " a month.",
                        "Entertainment budget: " + budget.toDisplay() + " a month.",
                        "Over the budget: " + over.toDisplay() + " a month, " + yearly.toDisplay() + " a year."),
                List.of(new Row("Entertainment, 3-month average", usual.toDisplay()),
                        new Row("Entertainment budget", budget.toDisplay()),
                        new Row("Over the budget, a year", yearly.toDisplay())),
                yearly.minor(), usual.times(12).minor()));
    }

    static java.util.Optional<Tip> rent(Facts f) {
        Rent r = f.rent();
        if (r == null || f.incomeMonthlyMinor() <= 0) {
            return java.util.Optional.empty();
        }
        String cur = f.currency();
        Money yearly = new Money(r.yearlyMinor(), cur);
        Money monthly = yearly.scaled(1, 12);
        Money income = new Money(f.incomeMonthlyMinor(), cur);
        int share = monthly.percentOf(income);
        if (share < RENT_SHARE_PERCENT) {
            return java.util.Optional.empty();
        }
        List<String> facts = new ArrayList<>(List.of(
                "Rent: " + yearly.toDisplay() + " a year, about " + monthly.toDisplay() + " a month.",
                "Income you plan on: " + income.toDisplay() + " a month.",
                "Rent is " + share + "% of it."));
        List<Row> why = new ArrayList<>(List.of(new Row("Rent, a year", yearly.toDisplay()),
                new Row("Income you plan on, a month", income.toDisplay()), new Row("Share of it", share + "%")));
        String text = "Rent is " + share + "% of the income you plan on, " + yearly.toDisplay() + " a year.";
        if (r.contractFrom() != null && !r.contractFrom().isBefore(f.today())) {
            String when = r.contractFrom().getDayOfMonth() + " " + r.contractFrom().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            text += " The next contract starts on " + when + ": compare other flats a few months before.";
            facts.add("Next contract starts: " + when + ".");
            why.add(new Row("Next contract starts", when));
        } else {
            text += " Compare other flats before the contract renews.";
        }
        return java.util.Optional.of(new Tip("rent", "Rent", text, List.copyOf(facts), List.copyOf(why), 0, yearly.minor()));
    }

    static java.util.Optional<Tip> telecom(Facts f) {
        if (f.telecom().isEmpty()) {
            return java.util.Optional.empty();
        }
        String cur = f.currency();
        Money monthly = sum(f.telecom().stream().map(Recurring::monthlyMinor).toList(), cur);
        Money yearly = monthly.times(12);
        String names = f.telecom().stream().map(Recurring::name).collect(Collectors.joining(" and "));
        return java.util.Optional.of(new Tip("telecom", "Phone and internet",
                names + " costs " + monthly.toDisplay() + " a month, " + yearly.toDisplay()
                + " a year. Compare plans before the contract renews.",
                List.of("Phone and internet: " + names + ", " + monthly.toDisplay() + " a month, " + yearly.toDisplay() + " a year."),
                List.of(new Row("Phone and internet, a month", monthly.toDisplay()), new Row("A year", yearly.toDisplay())),
                0, yearly.minor()));
    }

    private static Money sum(List<Long> minors, String cur) {
        return new Money(minors.stream().mapToLong(Long::longValue).reduce(0L, Math::addExact), cur);
    }
}
