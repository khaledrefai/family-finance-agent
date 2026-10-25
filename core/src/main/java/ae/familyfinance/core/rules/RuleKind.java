package ae.familyfinance.core.rules;

/** What a family rule does. Every rule is about the children; a parent's own spending has no rules. */
public enum RuleKind {
    /** A kind of thing a child can't buy at all ("no in-game purchases"). */
    BLOCK_CATEGORY("Blocked"),
    /** A food a child must not have ("no peanuts for Adam"). The app flags, it never certifies a product. */
    BLOCK_ALLERGEN("Allergy"),
    /** Anything over this amount needs a parent's yes. */
    APPROVAL_LIMIT("Needs a parent over"),
    /** At most this much a month (or week) on one kind of thing ("snacks up to AED 50 a month"). */
    CATEGORY_LIMIT("Limit"),
    /** At most this much a month in all. */
    MONTHLY_CAP("Monthly cap");

    private final String label;

    RuleKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
