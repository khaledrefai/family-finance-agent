package ae.familyfinance.core.plan;

/** Goal tiers from the money buckets idea: what the family needs, what makes life good, and the big dream. */
public enum Tier {
    SURVIVAL("Survival"), GOOD_LIFE("Good Life"), DREAM("Dream");

    private final String label;

    Tier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
