package ae.familyfinance.core.setup;

import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A new family's first budgets, and the first goal's size. Pure: the service hands in the month's planned income,
 * what the commitments need, and who is in the family.
 *
 * <p>Each budget starts from an amount per adult and per child each month, and each child's pocket money from their
 * age band. If those add up to more than 60% of what is left after the commitments, every one is cut by the same
 * share (rounded down to AED 50, pocket money to AED 5), so the first plan always leaves room for goals and fun.
 * The parent changes any of them before confirming; nothing is saved until they do.
 *
 * <p>The starting amounts are a rough guide for a UAE family, not survey numbers (verify before launch).
 */
public final class StarterBudgetEngine {

    /** Budgets plus pocket money take at most this share of what is left after commitments: 60%. */
    static final int MAX_EVERYDAY_BP = 6_000;
    static final long BUDGET_ROUND_MINOR = 5_000;
    static final long POCKET_ROUND_MINOR = 500;
    /** The emergency buffer holds this many months of commitments and everyday spending. */
    static final int BUFFER_MONTHS = 3;
    static final long BUFFER_ROUND_MINOR = 100_000;

    /** A budget's starting point: so much per adult and per child each month. */
    record Start(String category, String label, String bucket, long adultMinor, long childMinor) {
    }

    static final List<Start> STARTS = List.of(
            new Start("GROCERIES", "Groceries", "EVERYDAY", 90_000, 50_000),
            new Start("TRANSPORT", "Transport", "EVERYDAY", 50_000, 0),
            new Start("EATING_OUT", "Eating out", "FUN", 40_000, 20_000),
            new Start("ENTERTAINMENT", "Entertainment", "FUN", 30_000, 20_000));

    /** Weekly pocket money by age band (the demo family: Layla 13 gets AED 100, Adam 9 gets AED 60). */
    static final Map<AgeBand, Long> POCKET_WEEKLY = Map.of(
            AgeBand.CHILD_6_9, 6_000L,
            AgeBand.PRETEEN_10_12, 8_000L,
            AgeBand.TEEN_13_17, 10_000L);

    public record Kid(UUID memberId, String name, AgeBand ageBand) {
    }

    public record Line(String category, String label, String bucket, MoneyView amount, String reason) {
    }

    public record Pocket(UUID memberId, String name, MoneyView weekly, MoneyView monthly, String reason) {
    }

    /**
     * {@code room}: income less commitments. {@code cap}: the most the budgets and pocket money take of it.
     * {@code total}: what these budgets and pocket money come to in a month.
     */
    public record Starter(MoneyView income, MoneyView commitments, MoneyView room, MoneyView cap, MoneyView total,
                          boolean cut, String note, List<Line> lines, List<Pocket> pocket) {
    }

    public record Buffer(MoneyView target, String reason) {
    }

    private StarterBudgetEngine() {
    }

    public static boolean isStarterCategory(String category) {
        return STARTS.stream().anyMatch(s -> s.category().equals(category));
    }

    public static String bucketOf(String category) {
        return STARTS.stream().filter(s -> s.category().equals(category)).map(Start::bucket).findFirst().orElse("EVERYDAY");
    }

    public static String labelOf(String category) {
        return STARTS.stream().filter(s -> s.category().equals(category)).map(Start::label).findFirst().orElse(category);
    }

    public static Starter starter(Money income, Money commitments, int adults, List<Kid> kids) {
        String cur = income.currency();
        Money zero = Money.zero(cur);
        Money room = income.minus(commitments).max(zero);
        Money cap = room.scaled(MAX_EVERYDAY_BP, 10_000);
        int children = kids.size();

        List<Money> amounts = new ArrayList<>();
        Money total = zero;
        for (Start s : STARTS) {
            Money m = new Money(s.adultMinor(), cur).times(adults).plus(new Money(s.childMinor(), cur).times(children));
            amounts.add(m);
            total = total.plus(m);
        }
        List<Money> weekly = new ArrayList<>();
        for (Kid k : kids) {
            Money w = new Money(POCKET_WEEKLY.getOrDefault(k.ageBand(), 6_000L), cur);
            weekly.add(w);
            total = total.plus(w.scaled(52, 12));
        }

        // Too much for this month: cut every amount by the same share, rounded down so the total stays under the cap
        boolean cut = total.compareTo(cap) > 0;
        if (cut) {
            Money before = total;
            total = zero;
            for (int k = 0; k < amounts.size(); k++) {
                Money m = amounts.get(k).scaled(cap.minor(), before.minor()).roundedDownTo(BUDGET_ROUND_MINOR);
                amounts.set(k, m);
                total = total.plus(m);
            }
            for (int k = 0; k < weekly.size(); k++) {
                Money w = weekly.get(k).scaled(cap.minor(), before.minor()).roundedDownTo(POCKET_ROUND_MINOR);
                weekly.set(k, w);
                total = total.plus(w.scaled(52, 12));
            }
        }

        List<Line> lines = new ArrayList<>();
        for (int k = 0; k < STARTS.size(); k++) {
            Start s = STARTS.get(k);
            String per = text(new Money(s.adultMinor(), cur)) + " a month per adult"
                    + (s.childMinor() > 0 && children > 0 ? " and " + text(new Money(s.childMinor(), cur)) + " per child" : "");
            lines.add(new Line(s.category(), s.label(), s.bucket(), MoneyView.of(amounts.get(k)),
                    cut ? "Cut to fit your month (the start is " + per + ")" : "A starting point: " + per));
        }
        List<Pocket> pocket = new ArrayList<>();
        for (int k = 0; k < kids.size(); k++) {
            Kid kid = kids.get(k);
            Money start = new Money(POCKET_WEEKLY.getOrDefault(kid.ageBand(), 6_000L), cur);
            pocket.add(new Pocket(kid.memberId(), kid.name(), MoneyView.of(weekly.get(k)),
                    MoneyView.of(weekly.get(k).scaled(52, 12)),
                    cut ? "Cut to fit your month (the start for this age is " + text(start) + " a week)"
                        : "A usual amount for this age: " + text(start) + " a week"));
        }

        String note;
        if (!room.isPositive()) {
            note = "Your commitments take all of this month's income, so there is no room for budgets yet. "
                    + "Check the income and the bills before you go on.";
        } else if (cut) {
            note = "The usual starting amounts came to more than " + text(cap) + ", 60% of the "
                    + text(room) + " left after commitments, so each one is cut by the same share.";
        } else {
            note = "These come to " + text(total) + " a month, out of " + text(room) + " left after commitments.";
        }
        return new Starter(MoneyView.of(income), MoneyView.of(commitments), MoneyView.of(room), MoneyView.of(cap),
                MoneyView.of(total), cut, note, List.copyOf(lines), List.copyOf(pocket));
    }

    /**
     * The family's budgets as they are now, to change after setup: the same lines as the start, each with the amount
     * saved (0 where it was left out) and the app's starting point as the guide. Pocket money is weekly, by child.
     */
    public static Starter current(Starter guide, Map<String, Long> monthlyMinor, Map<UUID, Long> weeklyMinor) {
        String cur = guide.income().currency();
        Money total = Money.zero(cur);
        List<Line> lines = new ArrayList<>();
        for (Line l : guide.lines()) {
            Money now = new Money(monthlyMinor.getOrDefault(l.category(), 0L), cur);
            total = total.plus(now);
            lines.add(new Line(l.category(), l.label(), l.bucket(), MoneyView.of(now),
                    "The app's guide: " + text(new Money(l.amount().minor(), cur)) + " a month"));
        }
        List<Pocket> pocket = new ArrayList<>();
        for (Pocket p : guide.pocket()) {
            Money now = new Money(weeklyMinor.getOrDefault(p.memberId(), 0L), cur);
            total = total.plus(now.scaled(52, 12));
            pocket.add(new Pocket(p.memberId(), p.name(), MoneyView.of(now), MoneyView.of(now.scaled(52, 12)),
                    "The app's guide: " + text(new Money(p.weekly().minor(), cur)) + " a week"));
        }
        Money room = new Money(guide.room().minor(), cur);
        String note = "Your budgets and pocket money come to " + text(total) + " a month, out of " + text(room)
                + " left after commitments.";
        return new Starter(guide.income(), guide.commitments(), guide.room(), guide.cap(), MoneyView.of(total),
                false, note, List.copyOf(lines), List.copyOf(pocket));
    }

    /** The first family goal: an emergency buffer of 3 months of commitments and everyday spending. */
    public static Buffer buffer(Money commitments, Money everyday) {
        Money month = commitments.plus(everyday);
        Money target = month.times(BUFFER_MONTHS).roundedUpTo(BUFFER_ROUND_MINOR);
        return new Buffer(MoneyView.of(target), BUFFER_MONTHS + " months of commitments and everyday spending ("
                + text(month) + " a month), rounded up to the next AED 1,000");
    }

    /** Whole dirhams in a sentence ("AED 4,593", not "AED 4,593.33"); the exact amounts stay in the fields. */
    private static String text(Money m) {
        long whole = m.toMajor().setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        return m.currency() + " " + String.format(java.util.Locale.ROOT, "%,d", whole);
    }
}
