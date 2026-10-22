package ae.familyfinance.core.requests;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.FamilyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RequestService {

    private final SpendingRequestRepository requests;
    private final ApprovalRepository approvals;
    private final FamilyRepository families;

    public RequestService(SpendingRequestRepository requests, ApprovalRepository approvals, FamilyRepository families) {
        this.requests = requests;
        this.approvals = approvals;
        this.families = families;
    }

    /** Any member can ask. In week 4 the Gatekeeper agent adds its explanation before a parent sees it. */
    @Transactional
    public SpendingRequest create(CurrentMember me, RequestKind kind, String title, long amountMinor,
                                  String category, String productUrl) {
        String currency = families.findById(me.familyId()).orElseThrow().getCurrency();
        return requests.save(new SpendingRequest(me.familyId(), me.memberId(), kind, title,
                amountMinor, currency, category, productUrl));
    }

    /** Parents see the whole family's requests; a child sees only their own. */
    @Transactional(readOnly = true)
    public List<SpendingRequest> list(CurrentMember me) {
        return me.isParent()
                ? requests.findByFamilyIdOrderByCreatedAtDesc(me.familyId())
                : requests.findByFamilyIdAndRequesterIdOrderByCreatedAtDesc(me.familyId(), me.memberId());
    }

    @Transactional
    public SpendingRequest decide(CurrentMember me, UUID requestId, Decision decision, String note) {
        SpendingRequest request = requests.findByIdAndFamilyId(requestId, me.familyId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Request not found"));

        ApprovalPolicy.Verdict verdict = ApprovalPolicy.check(
                new ApprovalPolicy.Approver(me.memberId(), me.familyId(), me.role()),
                new ApprovalPolicy.RequestFacts(request.getFamilyId(), request.getRequesterId(), request.getStatus()));

        switch (verdict) {
            case ALLOWED -> { }
            case DENIED_OTHER_FAMILY -> throw new ApiException(HttpStatus.NOT_FOUND, "Request not found");
            case DENIED_OWN_REQUEST -> throw new ApiException(HttpStatus.FORBIDDEN, "You can't decide your own request");
            case DENIED_NOT_A_PARENT -> throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can decide a request");
            case DENIED_NOT_PENDING -> throw new ApiException(HttpStatus.CONFLICT, "This request was already decided");
        }

        approvals.save(new Approval(request.getId(), me.memberId(), decision, note));
        request.decide(decision);
        return request;
    }
}
