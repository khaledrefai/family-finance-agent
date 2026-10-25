package ae.familyfinance.core.plan;

/** How often an income or a commitment repeats. CUSTOM = the dates are listed one by one (rent cheques, school terms). */
public enum Frequency {
    ONCE, WEEKLY, MONTHLY, QUARTERLY, TERMLY, YEARLY, CUSTOM;

    /** Paid out of each month's pay as it comes (no reserve needed). */
    public boolean isRegular() {
        return this == WEEKLY || this == MONTHLY;
    }
}
