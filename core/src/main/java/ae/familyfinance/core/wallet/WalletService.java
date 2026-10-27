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

    /** A kid's own goal jar whose name is in what they asked for ("Headphones" in "Sony WH-CH720N headphones"). */
    public record Jar(UUID goalId, String name, long savedMinor) {
    }

    /** What a child's own money covers for a request: the matching goal jar first, then the wallet. */
    public record Cover(Jar jar, long jarMinor, long walletMinor, long walletBalanceMinor, boolean covers) {
    }

    /** The child's active goal whose name appears in {@code title}, if any. */
    public Optional<Jar> jarFor(UUID family, UUID child, String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String t = title.toLowerCase(java.util.Locale.ROOT);
        return db.sql("""
                SELECT id, name, saved_minor FROM goal
                WHERE family_id = ? AND member_id = ? AND status = 'ACTIVE' AND saved_minor > 0 ORDER BY created_at
                """).params(family, child)
                .query((rs, n) -> new Jar(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3))).list().stream()
                .filter(j -> matches(t, j.name()))
                .findFirst();
    }

    static boolean matches(String titleLower, String goalName) {
        String g = goalName.toLowerCase(java.util.Locale.ROOT).trim();
        if (g.isEmpty()) {
            return false;
        }
        // "Headphones" matches "headphone", "Building set" matches "LEGO Classic building set"
        String stem = g.endsWith("s") && g.length() > 3 ? g.substring(0, g.length() - 1) : g;
        return titleLower.contains(stem);
    }

    /** How the child's own demo money would pay {@code amountMinor}: the jar first, the rest from the wallet. */
    public Cover cover(UUID family, UUID child, String title, long amountMinor) {
        Optional<Jar> jar = jarFor(family, child, title);
        long wallet = db.sql("""
                SELECT COALESCE(SUM(e.amount_minor), 0) FROM demo_wallet_entry e
                JOIN demo_wallet w ON w.id = e.wallet_id WHERE w.member_id = ? AND w.family_id = ?
                """).params(child, family).query(Long.class).single();
        long fromJar = jar.map(j -> Math.min(j.savedMinor(), amountMinor)).orElse(0L);
        long fromWallet = amountMinor - fromJar;
        return new Cover(jar.orElse(null), fromJar, fromWallet, wallet, amountMinor > 0 && wallet >= fromWallet);
    }

    /** What paying for a request did: the wallet after, and the goal it reached (null if none). */
    public record Paid(Money walletAfter, Money fromJar, String goalReached) {
    }

    /**
     * Pays for an approved request out of the child's own demo money, inside the approval's transaction: the goal jar
     * it was saved for first (the goal is then reached, +5 stars), the rest from the wallet. Empty when the two
     * together can't cover it (a parent pays then).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Paid> payForRequest(UUID family, UUID child, UUID requestId, String title, Money amount) {
        record W(UUID id, String currency) {
        }
        Optional<W> wallet = db.sql("SELECT id, currency FROM demo_wallet WHERE member_id = ? AND family_id = ? FOR UPDATE")
                .params(child, family).query((rs, n) -> new W(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (wallet.isEmpty() || amount.minor() <= 0) {
            return Optional.empty();
        }
        Cover c = cover(family, child, title, amount.minor());
        if (!c.covers()) {
            return Optional.empty();
        }
        if (c.walletMinor() > 0) {
            db.sql("""
                    INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, request_id, description)
                    VALUES (?, 'PAYMENT', ?, ?, ?)
                    """).params(wallet.get().id(), -c.walletMinor(), requestId, cut(title, 180) + " (demo money)").update();
        }
        String reached = null;
        if (c.jar() != null) {
            // Bought what they saved for: the goal is done at the price paid
            db.sql("""
                    UPDATE goal SET saved_minor = ?, target_minor = ?, status = 'REACHED', reached_at = now()
                    WHERE id = ? AND status = 'ACTIVE'
                    """).params(amount.minor(), amount.minor(), c.jar().goalId()).update();
            db.sql("INSERT INTO points_entry (family_id, member_id, points, reason) VALUES (?, ?, 5, ?)")
                    .params(family, child, cut("Goal reached: " + c.jar().name(), 200)).update();
            reached = c.jar().name();
        }
        Money after = new Money(c.walletBalanceMinor() - c.walletMinor(), wallet.get().currency());
        outbox.publish(family, LiveKind.WALLET_CHANGED, child, null, Map.of("balance", MoneyView.of(after).text()));
        return Optional.of(new Paid(after, new Money(c.jarMinor(), wallet.get().currency()), reached));
    }

    private long balanceMinor(UUID wallet) {
        return db.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM demo_wallet_entry WHERE wallet_id = ?")
                .param(wallet).query(Long.class).single();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
