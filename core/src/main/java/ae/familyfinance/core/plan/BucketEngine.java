package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Cashflow.Line;
import ae.familyfinance.core.plan.CashflowEngine.GoalFact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money buckets: a month's planned income split on paper into Commitments, Everyday, Goals, Set aside for
 * investing, Fun and Giving. A parent (or the Planner, as a draft) picks each bucket's share; this turns the
 * shares into AED, checks Commitments covers what is due (the Survival number), and lists what is in each bucket.
 * Shares are basis points (4986 = 49.86%) so there are no floats.
 */
public final class BucketEngine {

    public static final int WHOLE = 10_000;
    static final int SUGGESTED_GIVING_BP = 150;
    static final int SUGGESTED_INVEST_BP = 300;

    public record Share(BucketKind kind, int shareBp, String reason) {
    }

    public record Item(String label, MoneyView amount, int shareBp, Tier tier, String tierLabel, String note) {
    }

    /**
     * {@code needed}: what the family's own numbers say this bucket has to hold (commitments due, budgets, goal
     * savings); null where nothing is needed. {@code fromBp}/{@code toBp} place the bucket on the income bar.
     */
    public record Bucket(BucketKind kind, String label, String about, int shareBp, MoneyView amount, int fromBp,
                         int toBp, MoneyView needed, MoneyView shortBy, String reason, List<Item> items) {
    }

    public record TierLine(Tier tier, String label, int goals, MoneyView left, MoneyView monthly) {
    }

    public record Buckets(UUID planId, RecordStatus status, String proposedBy, String month, MoneyView income,
                          List<Bucket> buckets, MoneyView survivalNeeded, boolean survivalCovered,
                          List<TierLine> tiers, List<String> warnings) {
    }

    /** The facts a split is checked against, from the cash-flow engine. */
    public record Needs(Money income, Money commitmentsDue, List<Line> commitmentLines, Money everyday,
                        List<Line> everydayLines, Money goalSaving, List<GoalFact> goals, Money[] goalMonthly) {
    }

    private BucketEngine() {
    }

    /** Turns shares into AED with the largest-remainder rule, so the buckets add up to the income to the fils. */
    public static Map<BucketKind, Money> amounts(Money income, List<Share> shares) {
        Map<BucketKind, Money> out = new EnumMap<>(BucketKind.class);
        long total = income.minor();
        long given = 0;
        List<long[]> remainders = new ArrayList<>(); // [kind ordinal, remainder]
        for (Share s : shares) {
            long product = Math.multiplyExact(total, (long) s.shareBp());
            long base = product / WHOLE;
            out.put(s.kind(), new Money(base, income.currency()));
            given += base;
            remainders.add(new long[]{s.kind().ordinal(), product % WHOLE});
        }
        remainders.sort(Comparator.comparingLong((long[] r) -> r[1]).reversed().thenComparingLong(r -> r[0]));
        long left = shares.stream().mapToInt(Share::shareBp).sum() == WHOLE ? total - given : 0;
        for (int k = 0; left > 0 && !remainders.isEmpty(); k = (k + 1) % remainders.size()) {
            BucketKind kind = BucketKind.values()[(int) remainders.get(k)[0]];
            out.put(kind, out.get(kind).plus(new Money(1, income.currency())));
            left--;
        }
        return out;
    }

    /** The smallest share (rounded up) that holds {@code amount} of {@code income}. */
    public static int minShare(Money amount, Money income) {
        if (!income.isPositive()) {
            return WHOLE;
        }
        return (int) Math.min(WHOLE, Math.ceilDiv(Math.multiplyExact(Math.max(0, amount.minor()), (long) WHOLE), income.minor()));
    }

    /**
     * Why a split can't be saved, or an empty list. Every share is 0 to 100%, no bucket twice, they add up to
     * 100%, and Commitments holds at least what is due.
     */
    public static List<String> problems(List<Share> shares, Needs needs) {
        List<String> problems = new ArrayList<>();
        EnumMap<BucketKind, Integer> seen = new EnumMap<>(BucketKind.class);
        int sum = 0;
        for (Share s : shares) {
            if (s.shareBp() < 0 || s.shareBp() > WHOLE) {
                problems.add(s.kind().label() + " must be between 0% and 100%");
            }
            if (seen.put(s.kind(), s.shareBp()) != null) {
                problems.add(s.kind().label() + " is listed twice");
            }
            sum += s.shareBp();
        }
        if (sum != WHOLE) {
            problems.add("The shares add up to " + percent(sum) + ", not 100%");
        }
        int need = minShare(needs.commitmentsDue(), needs.income());
        int commitments = seen.getOrDefault(BucketKind.COMMITMENTS, 0);
        if (commitments < need) {
            problems.add("Commitments needs at least " + percent(need) + " to cover "
                    + MoneyView.of(needs.commitmentsDue()).text() + " due this month");
        }
        return problems;
    }

    /**
     * The engine's own split, used when the Planner is off: cover commitments, budgets and goal savings first,
     * then 1.5% giving and 3% set aside for investing if there is room, and the rest is fun.
     */
    public static List<Share> suggestion(Needs needs) {
        int c = minShare(needs.commitmentsDue(), needs.income());
        int e = Math.min(WHOLE - c, minShare(needs.everyday(), needs.income()));
        int g = Math.min(WHOLE - c - e, minShare(needs.goalSaving(), needs.income()));
        int rest = WHOLE - c - e - g;
        int give = Math.min(rest, SUGGESTED_GIVING_BP);
        int invest = Math.min(rest - give, SUGGESTED_INVEST_BP);
        int fun = rest - give - invest;
        return List.of(
                new Share(BucketKind.COMMITMENTS, c, "Covers " + MoneyView.of(needs.commitmentsDue()).text() + " due this month"),
                new Share(BucketKind.EVERYDAY, e, "Covers the budgets, " + MoneyView.of(needs.everyday()).text() + " a month"),
                new Share(BucketKind.GOALS, g, "Covers " + MoneyView.of(needs.goalSaving()).text() + " of goal saving"),
                new Share(BucketKind.INVEST_ASIDE, invest, invest > 0 ? "A small amount kept apart for the long term" : "No room this month"),
                new Share(BucketKind.FUN, fun, "What is left after the rest"),
                new Share(BucketKind.GIVING, give, give > 0 ? "Optional. Turn it off any time" : "No room this month"));
    }

    public static Buckets view(UUID planId, RecordStatus status, String proposedBy, String month, List<Share> shares,
                               Needs needs) {
        Money income = needs.income();
        String cur = income.currency();
        Map<BucketKind, Money> amounts = amounts(income, shares);
        Map<BucketKind, Share> byKind = new EnumMap<>(BucketKind.class);
        shares.forEach(s -> byKind.put(s.kind(), s));

        List<Bucket> buckets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int from = 0;
        for (BucketKind kind : BucketKind.values()) {
            Share s = byKind.get(kind);
            if (s == null) {
                continue;
            }
            Money amount = amounts.get(kind);
            int to = from + s.shareBp();
            Money needed = switch (kind) {
                case COMMITMENTS -> needs.commitmentsDue();
                case EVERYDAY -> needs.everyday();
                case GOALS -> needs.goalSaving();
                default -> null;
            };
            Money shortBy = needed == null ? Money.zero(cur) : needed.minus(amount).max(Money.zero(cur));
            if (shortBy.isPositive()) {
                warnings.add(kind.label() + " gets " + MoneyView.of(amount).text() + " but needs "
                        + MoneyView.of(needed).text() + ": " + MoneyView.of(shortBy).text() + " short.");
            }
            buckets.add(new Bucket(kind, kind.label(), kind.about(), s.shareBp(), MoneyView.of(amount), from, to,
                    needed == null ? null : MoneyView.of(needed), MoneyView.of(shortBy), s.reason(),
                    items(kind, amount, needs)));
            from = to;
        }

        Money survival = needs.commitmentsDue().plus(needs.everyday());
        Money survivalHeld = amounts.getOrDefault(BucketKind.COMMITMENTS, Money.zero(cur))
                .plus(amounts.getOrDefault(BucketKind.EVERYDAY, Money.zero(cur)));
        boolean covered = survivalHeld.compareTo(survival) >= 0;
        if (!covered) {
            warnings.add(0, "This split leaves Survival uncovered: commitments and everyday need "
                    + MoneyView.of(survival).text() + ".");
        }
        return new Buckets(planId, status, proposedBy, month, MoneyView.of(income), buckets, MoneyView.of(survival),
                covered, tiers(needs), warnings);
    }

    private static List<Item> items(BucketKind kind, Money amount, Needs needs) {
        List<Item> items = new ArrayList<>();
        switch (kind) {
            case COMMITMENTS -> needs.commitmentLines().forEach(l -> items.add(item(l.label(), l.amount(), amount, null, l.note())));
            case EVERYDAY -> needs.everydayLines().forEach(l -> items.add(item(l.label(), l.amount(), amount, null, null)));
            case GOALS -> {
                for (int k = 0; k < needs.goals().size(); k++) {
                    GoalFact g = needs.goals().get(k);
                    Money monthly = needs.goalMonthly()[k];
                    String note = MoneyView.of(new Money(g.savedMinor(), amount.currency())).text() + " of "
                            + MoneyView.of(new Money(g.targetMinor(), amount.currency())).text() + " saved"
                            + (monthly.isPositive() ? "" : ", not funded yet");
                    items.add(item(g.name(), MoneyView.of(monthly), amount, g.tier(), note));
                }
            }
            case INVEST_ASIDE -> items.add(item("Set aside this month", MoneyView.of(amount), amount, null,
                    "Education only: the app never names a fund or product"));
            case FUN -> items.add(item("Fun money this month", MoneyView.of(amount), amount, null, null));
            case GIVING -> items.add(item("Charity and sadaqah", MoneyView.of(amount), amount, null, null));
        }
        return items;
    }

    private static Item item(String label, MoneyView value, Money bucket, Tier tier, String note) {
        int share = bucket.isPositive() ? (int) Math.min(WHOLE, new Money(value.minor(), bucket.currency())
                .scaled(WHOLE, bucket.minor()).minor()) : 0;
        return new Item(label, value, share, tier, tier == null ? null : tier.label(), note);
    }

    private static List<TierLine> tiers(Needs needs) {
        List<TierLine> out = new ArrayList<>();
        String cur = needs.income().currency();
        for (Tier t : Tier.values()) {
            int count = 0;
            Money left = Money.zero(cur);
            Money monthly = Money.zero(cur);
            for (int k = 0; k < needs.goals().size(); k++) {
                GoalFact g = needs.goals().get(k);
                if (g.tier() == t) {
                    count++;
                    left = left.plus(new Money(Math.max(0, g.targetMinor() - g.savedMinor()), cur));
                    monthly = monthly.plus(needs.goalMonthly()[k]);
                }
            }
            out.add(new TierLine(t, t.label(), count, MoneyView.of(left), MoneyView.of(monthly)));
        }
        return out;
    }

    /** 4986 as "49.86%", 5000 as "50%", 4950 as "49.5%". Text only, no floating point. */
    static String percent(int bp) {
        String frac = String.format(java.util.Locale.ROOT, "%02d", bp % 100).replaceAll("0$", "");
        return bp / 100 + (bp % 100 == 0 ? "" : "." + frac) + "%";
    }
}
