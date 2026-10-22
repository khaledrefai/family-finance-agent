package ae.familyfinance.core.requests;

import ae.familyfinance.core.family.Role;
import ae.familyfinance.core.requests.ApprovalPolicy.Approver;
import ae.familyfinance.core.requests.ApprovalPolicy.RequestFacts;
import ae.familyfinance.core.requests.ApprovalPolicy.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast unit tests for the approval rule. No Spring, no database. */
class ApprovalPolicyTest {

    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID OTHER_FAMILY = UUID.randomUUID();

    private final Approver mum = new Approver(UUID.randomUUID(), FAMILY, Role.PARENT);
    private final Approver dad = new Approver(UUID.randomUUID(), FAMILY, Role.PARENT);
    private final Approver teen = new Approver(UUID.randomUUID(), FAMILY, Role.CHILD);
    private final Approver younger = new Approver(UUID.randomUUID(), FAMILY, Role.CHILD);
    private final Approver strangerParent = new Approver(UUID.randomUUID(), OTHER_FAMILY, Role.PARENT);

    private static RequestFacts pendingBy(Approver requester) {
        return new RequestFacts(requester.familyId(), requester.memberId(), RequestStatus.PENDING);
    }

    @Test
    void child_can_never_approve_their_own_request() {
        assertThat(ApprovalPolicy.check(teen, pendingBy(teen))).isEqualTo(Verdict.DENIED_OWN_REQUEST);
        assertThat(ApprovalPolicy.check(younger, pendingBy(younger))).isEqualTo(Verdict.DENIED_OWN_REQUEST);
    }

    @Test
    void child_cannot_approve_a_siblings_request() {
        assertThat(ApprovalPolicy.check(teen, pendingBy(younger))).isEqualTo(Verdict.DENIED_NOT_A_PARENT);
        assertThat(ApprovalPolicy.check(younger, pendingBy(teen))).isEqualTo(Verdict.DENIED_NOT_A_PARENT);
    }

    @Test
    void child_cannot_approve_a_parents_request() {
        assertThat(ApprovalPolicy.check(teen, pendingBy(mum))).isEqualTo(Verdict.DENIED_NOT_A_PARENT);
    }

    @Test
    void parent_cannot_approve_their_own_request_but_the_other_parent_can() {
        assertThat(ApprovalPolicy.check(mum, pendingBy(mum))).isEqualTo(Verdict.DENIED_OWN_REQUEST);
        assertThat(ApprovalPolicy.check(dad, pendingBy(mum))).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void parent_can_approve_a_childs_pending_request() {
        assertThat(ApprovalPolicy.check(mum, pendingBy(teen))).isEqualTo(Verdict.ALLOWED);
        assertThat(ApprovalPolicy.check(dad, pendingBy(younger)).allowed()).isTrue();
    }

    @Test
    void parent_from_another_family_cannot_approve() {
        assertThat(ApprovalPolicy.check(strangerParent, pendingBy(teen))).isEqualTo(Verdict.DENIED_OTHER_FAMILY);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void only_pending_requests_can_be_decided(RequestStatus status) {
        RequestFacts decided = new RequestFacts(FAMILY, teen.memberId(), status);
        assertThat(ApprovalPolicy.check(mum, decided)).isEqualTo(Verdict.DENIED_NOT_PENDING);
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void a_child_is_never_allowed_whatever_the_status(RequestStatus status) {
        for (Approver child : new Approver[]{teen, younger}) {
            for (Approver requester : new Approver[]{teen, younger, mum, dad}) {
                RequestFacts facts = new RequestFacts(FAMILY, requester.memberId(), status);
                assertThat(ApprovalPolicy.check(child, facts).allowed()).isFalse();
            }
        }
    }
}
