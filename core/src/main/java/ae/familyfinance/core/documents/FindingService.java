package ae.familyfinance.core.documents;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.documents.FindingsEngine.Finding;
import ae.familyfinance.core.documents.FindingsEngine.Kind;
import ae.familyfinance.core.documents.FindingsEngine.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * "Found in your uploads": double charges and subscriptions nobody listed, worked out by {@link FindingsEngine}
 * from the family's payments each time they are asked for, and what a parent decides about each one.
 */
@Service
public class FindingService {

    /** How far back to look. Six months is enough for a monthly subscription and keeps the query small. */
    static final int LOOK_BACK_DAYS = 200;

    public enum Action { DUPLICATE, KEEP, ADD, IGNORE }

    private final JdbcClient db;
    private final Clock clock;

    public FindingService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Finding> list(CurrentMember me) {
        requireParent(me);
        return find(me.familyId());
    }

    /**
     * A parent's answer. DUPLICATE marks the extra charges so they stop counting (the bank still has to refund them);
     * KEEP says both were real; ADD puts a subscription on the commitments list; IGNORE hides it.
     */
    @Transactional
    public List<Finding> decide(CurrentMember me, String key, Action action) {
        requireParent(me);
        Finding f = find(me.familyId()).stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That finding is gone. It may have been answered already"));
        String decision = switch (action) {
            case DUPLICATE -> {
                if (f.kind() != Kind.DUPLICATE) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "That is not a double charge");
                }
                UUID first = f.txIds().get(0);
                for (UUID extra : f.txIds().subList(1, f.txIds().size())) {
                    db.sql("UPDATE ledger_transaction SET duplicate_of_id = ? WHERE id = ? AND family_id = ?")
                            .params(first, extra, me.familyId()).update();
                }
                yield "DUPLICATE";
            }
            case KEEP -> "KEEP";
            case ADD -> {
                if (f.kind() != Kind.SUBSCRIPTION) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Only a subscription can be added to your commitments");
                }
                LocalDate today = LocalDate.now(clock);
                LocalDate next = f.nextOn();
                while (next.isBefore(today)) {
                    next = next.plusMonths(1);
                }
                // A parent's tap: it counts straight away, like a commitment they typed in
                db.sql("""
                        INSERT INTO commitment (family_id, name, kind, amount_minor, currency, frequency, next_due_on,
                                                status, source, paid_from_account_id, created_by, confirmed_by)
                        VALUES (?, ?, 'SUBSCRIPTION', ?, ?, 'MONTHLY', ?, 'ACTIVE', 'DOCUMENT', ?, ?, ?)
                        """).params(me.familyId(), f.merchant(), f.amount().minor(), f.amount().currency(), next,
                                f.accountId(), me.memberId(), me.memberId())
                        .update();
                yield "ADDED";
            }
            case IGNORE -> "IGNORED";
        };
        db.sql("""
                INSERT INTO finding_decision (family_id, finding_key, decision, decided_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (family_id, finding_key) DO UPDATE SET decision = EXCLUDED.decision,
                    decided_by = EXCLUDED.decided_by, decided_at = now()
                """).params(me.familyId(), key, decision, me.memberId()).update();
        return find(me.familyId());
    }

    public static Action action(String text) {
        try {
            return Action.valueOf(text == null ? "" : text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Answer with DUPLICATE, KEEP, ADD or IGNORE");
        }
    }

    /** Subscriptions found in the payments that a parent has not answered yet (for "Ways to save"). */
    @Transactional(readOnly = true)
    public List<Finding> subscriptionsFound(UUID family) {
        return find(family).stream().filter(f -> f.kind() == Kind.SUBSCRIPTION).toList();
    }

    private List<Finding> find(UUID family) {
        LocalDate today = LocalDate.now(clock);
        String cur = db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
        List<Payment> payments = db.sql("""
                SELECT t.id, t.paid_from_account_id, a.label, t.occurred_on, t.amount_minor, coalesce(t.merchant, t.description),
                       t.description
                FROM ledger_transaction t JOIN money_account a ON a.id = t.paid_from_account_id
                WHERE t.family_id = ? AND t.direction = 'DEBIT' AND t.duplicate_of_id IS NULL
                  AND t.transfer_to_account_id IS NULL AND t.occurred_on >= ?
                ORDER BY t.occurred_on, t.created_at, t.id
                """).params(family, today.minusDays(LOOK_BACK_DAYS))
                .query((rs, n) -> new Payment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getLong(5), MerchantKey.of(rs.getString(6)), rs.getString(7)))
                .list();
        List<String> commitments = db.sql("SELECT name FROM commitment WHERE family_id = ? AND status <> 'ARCHIVED'")
                .param(family).query(String.class).list();
        Set<String> answered = new HashSet<>(db.sql("SELECT finding_key FROM finding_decision WHERE family_id = ?")
                .param(family).query(String.class).list());
        return FindingsEngine.find(payments, commitments, answered, today, cur);
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see what was found in the family's payments");
        }
    }
}
