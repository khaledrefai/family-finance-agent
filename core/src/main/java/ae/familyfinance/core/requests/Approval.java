package ae.familyfinance.core.requests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval")
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "approver_id", nullable = false)
    private UUID approverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Decision decision;

    @Column(length = 500)
    private String note;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt;

    protected Approval() {
    }

    public Approval(UUID requestId, UUID approverId, Decision decision, String note) {
        this.requestId = requestId;
        this.approverId = approverId;
        this.decision = decision;
        this.note = note;
        this.decidedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getApproverId() { return approverId; }
    public Decision getDecision() { return decision; }
    public String getNote() { return note; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
}
