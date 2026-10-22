package ae.familyfinance.core.requests;

import ae.familyfinance.core.family.Role;

import java.util.UUID;

/**
 * The single place that decides who may approve or reject a spending request.
 * Pure Java (no Spring, no database) so it is easy to read and to unit test.
 *
 * Rules, checked in this order:
 *   1. The approver must be in the same family as the request.
 *   2. Nobody can decide their own request (a child never approves their own; a parent's own
 *      request goes to the other parent).
 *   3. Only a parent can decide a request.
 *   4. Only a PENDING request can be decided.
 *
 * The database has the same rules 1-3 in a trigger (approval_guard) as a safety net.
 */
public final class ApprovalPolicy {

    public enum Verdict {
        ALLOWED,
        DENIED_OTHER_FAMILY,
        DENIED_OWN_REQUEST,
        DENIED_NOT_A_PARENT,
        DENIED_NOT_PENDING;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    public record Approver(UUID memberId, UUID familyId, Role role) {
    }

    public record RequestFacts(UUID familyId, UUID requesterId, RequestStatus status) {
    }

    private ApprovalPolicy() {
    }

    public static Verdict check(Approver approver, RequestFacts request) {
        if (!approver.familyId().equals(request.familyId())) {
            return Verdict.DENIED_OTHER_FAMILY;
        }
        if (approver.memberId().equals(request.requesterId())) {
            return Verdict.DENIED_OWN_REQUEST;
        }
        if (approver.role() != Role.PARENT) {
            return Verdict.DENIED_NOT_A_PARENT;
        }
        if (request.status() != RequestStatus.PENDING) {
            return Verdict.DENIED_NOT_PENDING;
        }
        return Verdict.ALLOWED;
    }
}
