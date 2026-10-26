package ae.familyfinance.core.live;

/** What happened. Each kind has its own audience and words in {@link LivePolicy}. */
public enum LiveKind {
    /** A child (or a parent) asked for something and it waits for a parent. */
    REQUEST_WAITING,
    /** A request was against a family rule and was stopped before it reached a parent. */
    REQUEST_BLOCKED,
    /** A parent said yes or no. */
    REQUEST_DECIDED,
    /** Money went into or out of a child's demo wallet. */
    WALLET_CHANGED,
    /** A bill (rent cheque, DEWA, school fees...) is due in the next few days. */
    BILL_DUE,
    /** A credit card bill is due soon, or will not be covered by the free cash on its due date. */
    CARD_DUE,
    /** A budget is over, heading over, or above the family's usual. */
    OVERSPEND,
    /** Sunday's plan for the week: what is safe to spend, the bills, the budgets to slow down. */
    WEEKLY_PLAN,
    /** A parent saved the family's outings for a month (week 7). Names only, no prices. */
    OUTINGS_PLANNED,
    /** The month's family summary is ready: parents get their page, each kid their own (no money in the words). */
    MONTH_SUMMARY,
    /** "Send me a test" from the notifications card. */
    TEST
}
