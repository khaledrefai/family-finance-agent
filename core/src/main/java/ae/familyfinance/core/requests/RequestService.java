package ae.familyfinance.core.requests;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.FamilyRepository;
import ae.familyfinance.core.requests.AffordabilityService.Assessment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final AffordabilityService affordability;
    private final JdbcClient db;
    private final ObjectMapper json;

    public RequestService(SpendingRequestRepository requests, ApprovalRepository approvals, FamilyRepository families,
                          AffordabilityService affordability, JdbcClient db, ObjectMapper json) {
        this.requests = requests;
        this.approvals = approvals;
        this.families = families;
        this.affordability = affordability;
        this.db = db;
        this.json = json;
    }

    /**
     * Any member can ask. The money engine's assessment is stored with the request, so the parent sees the same
     * facts the asker saw. In week 4 the Gatekeeper agent adds its explanation before a parent sees it.
     */
    @Transactional
    public SpendingRequest create(CurrentMember me, RequestKind kind, String title, long amountMinor,
                                  String category, String productUrl) {
        String currency = families.findById(me.familyId()).orElseThrow().getCurrency();
        SpendingRequest saved = requests.saveAndFlush(new SpendingRequest(me.familyId(), me.memberId(), kind, title,
                amountMinor, currency, category, productUrl));
        Assessment assessment = affordability.assess(me, me.memberId(), amountMinor, null);
        try {
            db.sql("UPDATE spending_request SET assessment = CAST(? AS jsonb), assessment_version = ? WHERE id = ?")
                    .params(json.writeValueAsString(assessment), AffordabilityService.VERSION, saved.getId()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return saved;
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
