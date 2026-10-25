package ae.familyfinance.core.requests;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.FamilyRepository;
import ae.familyfinance.core.live.LiveKind;
import ae.familyfinance.core.live.Outbox;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.requests.AffordabilityService.Assessment;
import ae.familyfinance.core.rules.RuleEngine;
import ae.familyfinance.core.spend.SpendCategory;
import ae.familyfinance.core.wallet.WalletService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RequestService {

    private final SpendingRequestRepository requests;
    private final ApprovalRepository approvals;
    private final FamilyRepository families;
    private final AffordabilityService affordability;
    private final WalletService wallets;
    private final Outbox outbox;
    private final JdbcClient db;
    private final ObjectMapper json;

    public RequestService(SpendingRequestRepository requests, ApprovalRepository approvals, FamilyRepository families,
                          AffordabilityService affordability, WalletService wallets, Outbox outbox, JdbcClient db,
                          ObjectMapper json) {
        this.requests = requests;
        this.approvals = approvals;
        this.families = families;
        this.affordability = affordability;
        this.wallets = wallets;
        this.outbox = outbox;
        this.db = db;
        this.json = json;
    }

    /**
     * Any member can ask. The money engine's assessment is stored with the request, so the parent sees the same
     * facts the asker saw. A request against a family rule (a blocked kind of thing, an allergen in its name) is stored as BLOCKED.
     */
    @Transactional
    public SpendingRequest create(CurrentMember me, RequestKind kind, String title, long amountMinor,
                                  String category, String productUrl) {
        String currency = families.findById(me.familyId()).orElseThrow().getCurrency();
        SpendingRequest saved = requests.saveAndFlush(new SpendingRequest(me.familyId(), me.memberId(), kind, title,
                amountMinor, currency, category, productUrl));
        Assessment assessment = affordability.assess(me, me.memberId(), amountMinor, null, title, spendCategory(category));
        try {
            db.sql("UPDATE spending_request SET assessment = CAST(? AS jsonb), assessment_version = ? WHERE id = ?")
                    .params(json.writeValueAsString(assessment), AffordabilityService.VERSION, saved.getId()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (assessment.blocked()) {
            // Against a family rule: it never reaches a parent's "Waiting for you", and the child is told why
            String why = assessment.rules().hits().stream()
                    .filter(h -> h.outcome() == RuleEngine.Outcome.BLOCKED).map(RuleEngine.Hit::detail)
                    .findFirst().orElse("It is against a family rule.");
            saved.block(why);
            outbox.publish(me.familyId(), LiveKind.REQUEST_BLOCKED, me.memberId(), me.memberId(),
                    facts(saved, Map.of("reason", why)));
        } else {
            // The parents' Waiting list updates live, and their phones get a push
            String wallet = assessment.facts().stream().filter(f -> f.contains("wallet")).findFirst().orElse("");
            outbox.publish(me.familyId(), LiveKind.REQUEST_WAITING, me.memberId(), me.memberId(),
                    facts(saved, Map.of("summary", wallet)));
        }
        return saved;
    }

    private static Map<String, String> facts(SpendingRequest r, Map<String, String> more) {
        Map<String, String> m = new HashMap<>(more);
        m.put("requestId", r.getId().toString());
        m.put("title", r.getTitle());
        m.put("amount", MoneyView.of(new Money(r.getAmountMinor(), r.getCurrency())).text());
        return m;
    }

    static SpendCategory spendCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return SpendCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

        // A child pays with their own demo money when the wallet holds enough; otherwise a parent pays
        Map<String, String> more = new HashMap<>();
        more.put("decision", decision.name());
        more.put("decidedBy", nameOf(me.memberId()));
        boolean child = db.sql("SELECT role = 'CHILD' FROM member WHERE id = ?").param(request.getRequesterId())
                .query(Boolean.class).single();
        if (child && decision == Decision.APPROVED) {
            Money amount = new Money(request.getAmountMinor(), request.getCurrency());
            Optional<Money> left = request.getKind() == RequestKind.REWARD ? Optional.empty()
                    : wallets.payForRequest(request.getFamilyId(), request.getRequesterId(), request.getId(),
                    request.getTitle(), amount);
            more.put("paidNote", left
                    .map(after -> MoneyView.of(amount).text() + " came out of your wallet. You have " + MoneyView.of(after).text() + " left.")
                    .orElse("A parent pays for this one."));
        }
        outbox.publish(me.familyId(), LiveKind.REQUEST_DECIDED, request.getRequesterId(), me.memberId(), facts(request, more));
        return request;
    }

    private String nameOf(UUID member) {
        return db.sql("SELECT display_name FROM member WHERE id = ?").param(member).query(String.class).single();
    }
}
