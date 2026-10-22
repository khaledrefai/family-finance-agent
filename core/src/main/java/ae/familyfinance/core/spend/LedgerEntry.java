package ae.familyfinance.core.spend;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line in {@code ledger_transaction}. For now only the fields spend logging needs are mapped;
 * documents and duplicate handling (week 3) add the rest.
 */
@Entity
@Table(name = "ledger_transaction")
public class LedgerEntry {

    public static final String DEBIT = "DEBIT";
    public static final String SOURCE_CHAT_LOG = "CHAT_LOG";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "member_id")
    private UUID memberId;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(nullable = false)
    private String description;

    @Column(length = 160)
    private String merchant;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "duplicate_hash", nullable = false, length = 64)
    private String duplicateHash;

    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "logged_text", length = 500)
    private String loggedText;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry() {
    }

    static LedgerEntry chatLoggedSpend(UUID familyId, UUID memberId, LocalDate occurredOn, String description,
                                       String merchant, SpendCategory category, long amountMinor, String currency,
                                       String duplicateHash, UUID duplicateOfId, String loggedText) {
        LedgerEntry e = new LedgerEntry();
        e.familyId = familyId;
        e.memberId = memberId;
        e.occurredOn = occurredOn;
        e.description = description;
        e.merchant = merchant;
        e.category = category.name();
        e.direction = DEBIT;
        e.amountMinor = amountMinor;
        e.currency = currency;
        e.duplicateHash = duplicateHash;
        e.duplicateOfId = duplicateOfId;
        e.source = SOURCE_CHAT_LOG;
        e.loggedText = loggedText;
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    public UUID getId() { return id; }
    public UUID getFamilyId() { return familyId; }
    public UUID getMemberId() { return memberId; }
    public LocalDate getOccurredOn() { return occurredOn; }
    public String getDescription() { return description; }
    public String getMerchant() { return merchant; }
    public String getCategory() { return category; }
    public String getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getDuplicateHash() { return duplicateHash; }
    public UUID getDuplicateOfId() { return duplicateOfId; }
    public String getSource() { return source; }
    public String getLoggedText() { return loggedText; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** Ledger lines from before spend logging may use categories outside {@link SpendCategory}. */
    public SpendCategory spendCategory() {
        try {
            return SpendCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return SpendCategory.OTHER;
        }
    }
}
