package ae.familyfinance.core.rules;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.spend.SpendCategory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Checks one thing a child wants against the family's rules (feature 6). Pure: the service loads the rules and
 * what the child has spent, this decides. No LLM anywhere in here: the agent only explains the result.
 *
 * <p>Three outcomes, worst wins:
 * <ul>
 *   <li>BLOCKED: a blocked kind of thing, or the product's name points to the child's allergen;</li>
 *   <li>NEEDS_PARENT: over the approval limit, over a category limit or the monthly cap, or food for a child with an
 *       allergy rule (a parent checks the pack label: the app can't know what is in it);</li>
 *   <li>OK: no rule says anything about it.</li>
 * </ul>
 */
public final class RuleEngine {

    public enum Outcome { OK, NEEDS_PARENT, BLOCKED }

    /** Food and drink: what an allergen rule cares about when the name alone doesn't say. */
    static final Set<SpendCategory> FOOD = EnumSet.of(SpendCategory.SCHOOL_CANTEEN, SpendCategory.SNACKS,
            SpendCategory.SUGARY_DRINKS, SpendCategory.EATING_OUT);

    /** An ACTIVE rule that applies to this child. {@code forChild} null = every child. */
    public record Rule(UUID id, RuleKind kind, UUID forChild, String label, SpendCategory category,
                       Allergen allergen, Long limitMinor, boolean weekly) {
    }

    /** What the child wants: the price, the kind of thing (null = not known) and its name or product text. */
    public record Ask(long amountMinor, SpendCategory category, String text) {
    }

    /** What the child has already spent: this month in all, and this month and this week per kind of thing. */
    public record Spent(long monthMinor, Map<SpendCategory, Long> monthByCategory, Map<SpendCategory, Long> weekByCategory) {

        public static Spent none() {
            return new Spent(0, Map.of(), Map.of());
        }
    }

    /** One rule and what it said. {@code detail} is a whole sentence for a parent or the child. */
    public record Hit(UUID ruleId, RuleKind kind, String rule, Outcome outcome, String detail) {
    }

    public record Result(Outcome outcome, List<Hit> hits, Money approvalLimit) {

        public boolean blocked() {
            return outcome == Outcome.BLOCKED;
        }

        /** The sentences for the rules that said something (not OK), in the order checked. */
        public List<String> sentences() {
            return hits.stream().filter(h -> h.outcome() != Outcome.OK).map(Hit::detail).toList();
        }
    }

    private RuleEngine() {
    }

    public static Result check(List<Rule> rules, Ask ask, Spent spent, String childName, String currency) {
        Money amount = new Money(ask.amountMinor(), currency);
        // A rule just for this child beats the one for every child (Adam's own approval limit, say)
        Rule approval = rules.stream().filter(r -> r.kind() == RuleKind.APPROVAL_LIMIT)
                .reduce(null, (best, r) -> best == null || (best.forChild() == null && r.forChild() != null) ? r : best);
        Rule cap = rules.stream().filter(r -> r.kind() == RuleKind.MONTHLY_CAP)
                .reduce(null, (best, r) -> best == null || (best.forChild() == null && r.forChild() != null) ? r : best);

        List<Hit> hits = new ArrayList<>();
        for (Rule r : rules) {
            switch (r.kind()) {
                case BLOCK_CATEGORY -> hits.add(blockCategory(r, ask));
                case BLOCK_ALLERGEN -> hits.add(allergen(r, ask, childName));
                case APPROVAL_LIMIT -> {
                    if (r == approval) {
                        hits.add(approvalLimit(r, amount));
                    }
                }
                case CATEGORY_LIMIT -> hits.add(categoryLimit(r, ask, spent, amount));
                case MONTHLY_CAP -> {
                    if (r == cap) {
                        hits.add(monthlyCap(r, spent, amount, childName));
                    }
                }
            }
        }
        Outcome worst = hits.stream().map(Hit::outcome).max(Enum::compareTo).orElse(Outcome.OK);
        return new Result(worst, List.copyOf(hits),
                approval == null ? null : new Money(approval.limitMinor(), currency));
    }

    private static Hit blockCategory(Rule r, Ask ask) {
        if (ask.category() == r.category()) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.BLOCKED,
                    "This is " + r.category().label() + ", and the family rule says: " + r.label() + ".");
        }
        return new Hit(r.id(), r.kind(), r.label(), Outcome.OK, "Not " + r.category().label() + ".");
    }

    private static Hit allergen(Rule r, Ask ask, String child) {
        Allergen a = r.allergen();
        var word = a.foundIn(ask.text());
        if (word.isPresent()) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.BLOCKED,
                    "The name says \"" + word.get() + "\", and " + child + " must not have " + a.label()
                    + ". Check the pack label for anything else.");
        }
        if (ask.category() == null) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.NEEDS_PARENT,
                    "I can't tell if this is food. " + child + " has a " + a.label() + " rule, so a parent checks first.");
        }
        if (FOOD.contains(ask.category())) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.NEEDS_PARENT,
                    "This is food and " + child + " must not have " + a.label()
                    + ". A parent checks the pack label first: the app can't be sure what is in it.");
        }
        return new Hit(r.id(), r.kind(), r.label(), Outcome.OK, "Not food.");
    }

    private static Hit approvalLimit(Rule r, Money amount) {
        Money limit = new Money(r.limitMinor(), amount.currency());
        if (amount.compareTo(limit) > 0) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.NEEDS_PARENT,
                    amount.toDisplay() + " is over " + limit.toDisplay() + ", so a parent says yes first.");
        }
        return new Hit(r.id(), r.kind(), r.label(), Outcome.OK, "Within " + limit.toDisplay() + ".");
    }

    private static Hit categoryLimit(Rule r, Ask ask, Spent spent, Money amount) {
        if (ask.category() != r.category()) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.OK, "Not " + r.category().label() + ".");
        }
        String cur = amount.currency();
        Money limit = new Money(r.limitMinor(), cur);
        Money so = new Money((r.weekly() ? spent.weekByCategory() : spent.monthByCategory())
                .getOrDefault(r.category(), 0L), cur);
        Money after = so.plus(amount);
        String period = r.weekly() ? "this week" : "this month";
        if (after.compareTo(limit) > 0) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.NEEDS_PARENT,
                    capital(r.category().label()) + " " + period + ": " + so.toDisplay() + " so far, and this makes "
                    + after.toDisplay() + ", over the " + limit.toDisplay() + " limit. A parent decides.");
        }
        return new Hit(r.id(), r.kind(), r.label(), Outcome.OK,
                limit.minus(after).toDisplay() + " left " + period + " after this.");
    }

    private static Hit monthlyCap(Rule r, Spent spent, Money amount, String child) {
        String cur = amount.currency();
        Money cap = new Money(r.limitMinor(), cur);
        Money so = new Money(spent.monthMinor(), cur);
        Money after = so.plus(amount);
        if (after.compareTo(cap) > 0) {
            return new Hit(r.id(), r.kind(), r.label(), Outcome.NEEDS_PARENT,
                    child + " has spent " + so.toDisplay() + " this month, and this makes " + after.toDisplay()
                    + ", over the " + cap.toDisplay() + " monthly cap. A parent decides.");
        }
        return new Hit(r.id(), r.kind(), r.label(), Outcome.OK, cap.minus(after).toDisplay() + " left this month after this.");
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
