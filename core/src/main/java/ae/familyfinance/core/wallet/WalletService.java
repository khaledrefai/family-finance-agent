package ae.familyfinance.core.wallet;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.live.LiveKind;
import ae.familyfinance.core.live.Outbox;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A child's demo wallet (DEMO MONEY, never real). The balance is the sum of its entries, worked out here.
 * A child sees only their own wallet; a parent can see each child's.
 */
@Service
public class WalletService {

    public record EntryView(String kind, MoneyView amount, String description, OffsetDateTime at) {
    }

    public record WalletView(UUID memberId, String name, MoneyView balance, boolean demoMoney, List<EntryView> recent) {
    }

    private final JdbcClient db;
    private final Outbox outbox;

    public WalletService(JdbcClient db, Outbox outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public WalletView view(CurrentMember me, UUID memberId) {
        UUID who = memberId == null ? me.memberId() : memberId;
        if (!me.isParent() && !who.equals(me.memberId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only see your own wallet");
        }
        record Owner(UUID wallet, String name, String currency) {
        }
        Owner o = db.sql("""
                SELECT w.id, m.display_name, w.currency FROM demo_wallet w JOIN member m ON m.id = w.member_id
                WHERE w.member_id = ? AND w.family_id = ?
                """).params(who, me.familyId())
                .query((rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No wallet for this member"));
        long balance = balanceMinor(o.wallet());
        List<EntryView> recent = db.sql("""
                SELECT kind, amount_minor, description, created_at FROM demo_wallet_entry
                WHERE wallet_id = ? ORDER BY created_at DESC, id LIMIT 8
                """).param(o.wallet())
                .query((rs, n) -> new EntryView(rs.getString(1), MoneyView.of(new Money(rs.getLong(2), o.currency())),
                        rs.getString(3), rs.getObject(4, OffsetDateTime.class)))
                .list();
        return new WalletView(who, o.name(), MoneyView.of(new Money(balance, o.currency())), true, recent);
    }

    /**
     * Pays for an approved request out of the child's wallet if it holds enough, inside the approval's transaction.
     * Returns the balance after, or empty when the wallet can't cover it (a parent pays then).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Money> payForRequest(UUID family, UUID child, UUID requestId, String title, Money amount) {
        record W(UUID id, String currency) {
        }
        Optional<W> wallet = db.sql("SELECT id, currency FROM demo_wallet WHERE member_id = ? AND family_id = ? FOR UPDATE")
                .params(child, family).query((rs, n) -> new W(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (wallet.isEmpty() || amount.minor() <= 0) {
            return Optional.empty();
        }
        long before = balanceMinor(wallet.get().id());
        if (before < amount.minor()) {
            return Optional.empty();
        }
        db.sql("""
                INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, request_id, description)
                VALUES (?, 'PAYMENT', ?, ?, ?)
                """).params(wallet.get().id(), -amount.minor(), requestId, cut(title, 180) + " (demo money)").update();
        Money after = new Money(before - amount.minor(), wallet.get().currency());
        outbox.publish(family, LiveKind.WALLET_CHANGED, child, null, Map.of("balance", MoneyView.of(after).text()));
        return Optional.of(after);
    }

    private long balanceMinor(UUID wallet) {
        return db.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM demo_wallet_entry WHERE wallet_id = ?")
                .param(wallet).query(Long.class).single();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
