package ae.familyfinance.core.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * The core's web push identity (VAPID, RFC 8292): one P-256 key pair. Phones subscribe against the public key,
 * so it must stay the same for as long as their subscriptions should work.
 *
 * <p>If VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are set they are used. Otherwise the core makes a pair on its first
 * start and keeps it in {@code app_setting}, so the database (which survives every deploy) is where it lives.
 */
@Component
public class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);
    static final String PUBLIC = "vapid.public";
    static final String PRIVATE = "vapid.private";

    private final JdbcClient db;
    private final String envPublic;
    private final String envPrivate;
    private volatile Pair pair;

    record Pair(String publicKey, ECPrivateKey privateKey) {
    }

    public VapidKeys(JdbcClient db, @Value("${app.push.vapid-public-key:}") String envPublic,
                     @Value("${app.push.vapid-private-key:}") String envPrivate) {
        this.db = db;
        this.envPublic = envPublic;
        this.envPrivate = envPrivate;
    }

    /**
     * Make or load the pair once at start, outside any request. The first caller used to be GET /api/push/settings,
     * which runs read-only, so on a database without keys the insert failed and the Family notifications card broke.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        try {
            pair();
        } catch (RuntimeException e) {
            log.warn("Web push keys not ready: {}", e.getMessage());
        }
    }

    /** base64url, as the browser's pushManager.subscribe wants it. */
    public String publicKey() {
        return pair().publicKey();
    }

    ECPrivateKey privateKey() {
        return pair().privateKey();
    }

    private Pair pair() {
        Pair p = pair;
        if (p == null) {
            synchronized (this) {
                if (pair == null) {
                    pair = load();
                }
                p = pair;
            }
        }
        return p;
    }

    private Pair load() {
        try {
            if (!envPublic.isBlank() && !envPrivate.isBlank()) {
                WebPushCrypto.decodePublic(WebPushCrypto.b64(envPublic)); // fail early on a bad key
                return new Pair(envPublic.trim(), WebPushCrypto.decodePrivate(WebPushCrypto.b64(envPrivate)));
            }
            KeyPair fresh = WebPushCrypto.newKeyPair();
            db.sql("INSERT INTO app_setting (key, value) VALUES (?, ?), (?, ?) ON CONFLICT (key) DO NOTHING")
                    .params(PUBLIC, WebPushCrypto.b64(WebPushCrypto.encodePoint((ECPublicKey) fresh.getPublic())),
                            PRIVATE, WebPushCrypto.b64(WebPushCrypto.encodePrivate((ECPrivateKey) fresh.getPrivate())))
                    .update();
            // Whoever wrote first wins, so two cores starting together still agree
            String pub = db.sql("SELECT value FROM app_setting WHERE key = ?").param(PUBLIC).query(String.class).single();
            String priv = db.sql("SELECT value FROM app_setting WHERE key = ?").param(PRIVATE).query(String.class).single();
            log.info("Web push keys loaded from the database");
            return new Pair(pub, WebPushCrypto.decodePrivate(WebPushCrypto.b64(priv)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY are not a valid P-256 key pair", e);
        }
    }
}
