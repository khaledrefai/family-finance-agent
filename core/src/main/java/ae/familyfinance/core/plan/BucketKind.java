package ae.familyfinance.core.plan;

/** The six paper buckets a month's income is split into. Nothing moves between real accounts. */
public enum BucketKind {
    COMMITMENTS("Commitments", "Survival first: rent, school and bills are covered before anything else."),
    EVERYDAY("Everyday", "What the family spends week to week. The budgets live here."),
    GOALS("Goals", "Saving for what the family wants, the safety net first."),
    INVEST_ASIDE("Set aside for investing", "An amount kept apart for the long term. The app never names a fund or product."),
    FUN("Fun", "Money to enjoy: outings, eating out, each parent's own money."),
    GIVING("Giving", "Optional. Charity and sadaqah. Turn it off any time.");

    private final String label;
    private final String about;

    BucketKind(String label, String about) {
        this.label = label;
        this.about = about;
    }

    public String label() {
        return label;
    }

    public String about() {
        return about;
    }
}
