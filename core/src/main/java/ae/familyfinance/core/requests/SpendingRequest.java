package ae.familyfinance.core.requests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "spending_request")
public class SpendingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestKind kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 40)
    private String category;

    @Column(name = "product_url", length = 1000)
    private String productUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RequestStatus status;

    @Column(name = "agent_summary", columnDefinition = "text")
    private String agentSummary;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** The price comparison offer this request was made from (shop, price, delivery, total, when checked), or null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "offer", columnDefinition = "jsonb")
    private Map<String, Object> offer;

    protected SpendingRequest() {
    }

    public SpendingRequest(UUID familyId, UUID requesterId, RequestKind kind, String title,
                           long amountMinor, String currency, String category, String productUrl) {
        this.familyId = familyId;
        this.requesterId = requesterId;
        this.kind = kind;
        this.title = title;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.category = category;
        this.productUrl = productUrl;
        this.status = RequestStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
    }

    void fromOffer(Map<String, Object> offer) {
        this.offer = offer;
    }

    void decide(Decision decision) {
        this.status = decision == Decision.APPROVED ? RequestStatus.APPROVED : RequestStatus.REJECTED;
        this.decidedAt = OffsetDateTime.now();
    }

    /** Against a family rule: decided by the rule, with the core's sentence saying which one. */
    void block(String why) {
        this.status = RequestStatus.BLOCKED;
        this.agentSummary = why;
        this.decidedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getFamilyId() { return familyId; }
    public UUID getRequesterId() { return requesterId; }
    public RequestKind getKind() { return kind; }
    public String getTitle() { return title; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public String getProductUrl() { return productUrl; }
    public RequestStatus getStatus() { return status; }
    public String getAgentSummary() { return agentSummary; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public Map<String, Object> getOffer() { return offer; }
}
