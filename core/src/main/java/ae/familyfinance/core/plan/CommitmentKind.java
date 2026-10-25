package ae.familyfinance.core.plan;

/** What must go out. Mirrors the CHECK on {@code commitment.kind}. */
public enum CommitmentKind {
    RENT("Rent"),
    SCHOOL_FEES("School fees"),
    UTILITY("Utilities"),
    TELECOM("Phone and internet"),
    CREDIT_CARD("Credit card"),
    LOAN("Loan"),
    INSURANCE("Insurance"),
    SUBSCRIPTION("Subscription"),
    OTHER("Other");

    private final String label;

    CommitmentKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Counts towards the share of pay that goes on debt (a health check). */
    public boolean isDebt() {
        return this == LOAN || this == CREDIT_CARD;
    }
}
