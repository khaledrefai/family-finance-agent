package ae.familyfinance.core.plan;

/**
 * DRAFT: an agent (or a parent) filled it in and nobody confirmed it yet. The engine ignores drafts.
 * ACTIVE: counts. ARCHIVED: kept for history.
 */
public enum RecordStatus {
    DRAFT, ACTIVE, ARCHIVED
}
