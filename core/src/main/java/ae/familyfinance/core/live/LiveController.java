package ae.familyfinance.core.live;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Live updates and notifications for the signed-in member:
 * the live stream, the bell, this phone's web push subscription, and (parents) the email address for alerts.
 */
@RestController
public class LiveController {

    /** Only real browser push services, so the core can never be pointed at another address to POST to. */
    static final List<String> PUSH_HOSTS = List.of(".googleapis.com", ".mozilla.com", ".push.apple.com",
            ".notify.windows.com");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record NotificationView(UUID id, String kind, String title, String body, String link,
                                   OffsetDateTime createdAt, boolean read) {
    }

    public record Bell(int unread, List<NotificationView> items) {
    }

    /** What the Notifications card needs. {@code emailReady}: the server can send email (SMTP is set up). */
    public record Settings(String publicKey, int phones, String email, boolean emailReady) {
    }

    public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    public record NewSubscription(@NotBlank @Size(max = 1000) String endpoint, @NotNull @Valid Keys keys) {
    }

    public record Endpoint(@NotBlank @Size(max = 1000) String endpoint) {
    }

    public record EmailBody(@Size(max = 160) String email) {
    }

    private final LiveHub hub;
    private final VapidKeys vapid;
    private final Mailer mail;
    private final Outbox outbox;
    private final JdbcClient db;

    public LiveController(LiveHub hub, VapidKeys vapid, Mailer mail, Outbox outbox, JdbcClient db) {
        this.hub = hub;
        this.vapid = vapid;
        this.mail = mail;
        this.outbox = outbox;
        this.db = db;
    }

    /**
     * Server-Sent Events. The web app reads it with fetch (so the login token goes in the Authorization header,
     * never in the address). "no-transform" stops the web app's proxy from compressing, which would hold events back.
     */
    @GetMapping(path = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(@AuthenticationPrincipal Jwt jwt, HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-cache, no-transform");
        res.setHeader("X-Accel-Buffering", "no");
        return hub.open(CurrentMember.from(jwt).memberId());
    }

    @GetMapping("/api/notifications")
    @Transactional(readOnly = true)
    public Bell bell(@AuthenticationPrincipal Jwt jwt) {
        UUID me = CurrentMember.from(jwt).memberId();
        List<NotificationView> items = db.sql("""
                SELECT id, kind, title, body, link, created_at, read_at IS NOT NULL
                FROM notification WHERE member_id = ? ORDER BY created_at DESC, id LIMIT 30
                """).param(me)
                .query((rs, n) -> new NotificationView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getBoolean(7)))
                .list();
        int unread = db.sql("SELECT count(*) FROM notification WHERE member_id = ? AND read_at IS NULL")
                .param(me).query(Integer.class).single();
        return new Bell(unread, items);
    }

    @PostMapping("/api/notifications/read")
    @Transactional
    public Bell read(@AuthenticationPrincipal Jwt jwt) {
        db.sql("UPDATE notification SET read_at = now() WHERE member_id = ? AND read_at IS NULL")
                .param(CurrentMember.from(jwt).memberId()).update();
        return bell(jwt);
    }

    @GetMapping("/api/push/settings")
    @Transactional(readOnly = true)
    public Settings settings(@AuthenticationPrincipal Jwt jwt) {
        CurrentMember me = CurrentMember.from(jwt);
        int phones = db.sql("SELECT count(*) FROM push_subscription WHERE member_id = ?").param(me.memberId())
                .query(Integer.class).single();
        String email = db.sql("SELECT email FROM member WHERE id = ?").param(me.memberId()).query(String.class).optional().orElse(null);
        return new Settings(vapid.publicKey(), phones, email, mail.enabled());
    }

    /** This phone said yes to notifications. Saying it again (same endpoint) just refreshes its keys and owner. */
    @PostMapping("/api/push/subscriptions")
    @Transactional
    public Settings subscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewSubscription body,
                              @RequestHeader(value = "User-Agent", required = false) String agent) {
        CurrentMember me = CurrentMember.from(jwt);
        if (!isPushService(body.endpoint())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This browser's push address isn't one we know");
        }
        try {
            WebPushCrypto.decodePublic(WebPushCrypto.b64(body.keys().p256dh()));
            if (WebPushCrypto.b64(body.keys().auth()).length != 16) {
                throw new IllegalArgumentException("auth");
            }
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This browser sent keys we can't use");
        }
        db.sql("""
                INSERT INTO push_subscription (member_id, endpoint, p256dh, auth, user_agent) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (endpoint) DO UPDATE SET member_id = EXCLUDED.member_id, p256dh = EXCLUDED.p256dh,
                    auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent, failures = 0
                """).params(me.memberId(), body.endpoint(), body.keys().p256dh(), body.keys().auth(),
                        agent == null ? null : agent.substring(0, Math.min(agent.length(), 200)))
                .update();
        return settings(jwt);
    }

    @PostMapping("/api/push/unsubscribe")
    @Transactional
    public Settings unsubscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Endpoint body) {
        db.sql("DELETE FROM push_subscription WHERE member_id = ? AND endpoint = ?")
                .params(CurrentMember.from(jwt).memberId(), body.endpoint()).update();
        return settings(jwt);
    }

    /** "Send me a test": goes through the outbox like everything else, so it proves the whole path. */
    @PostMapping("/api/push/test")
    @Transactional
    public Map<String, String> test(@AuthenticationPrincipal Jwt jwt) {
        CurrentMember me = CurrentMember.from(jwt);
        outbox.publish(me.familyId(), LiveKind.TEST, me.memberId(), me.memberId(), Map.of());
        return Map.of("status", "Sent. It should arrive in a few seconds.");
    }

    /** A parent's address for bill, card and budget alerts and Sunday's plan. Empty turns email off. */
    @PutMapping("/api/me/email")
    @Transactional
    public Settings email(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EmailBody body) {
        CurrentMember me = CurrentMember.from(jwt);
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can get email alerts");
        }
        String email = body.email() == null ? "" : body.email().trim();
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That doesn't look like an email address");
        }
        db.sql("UPDATE member SET email = ? WHERE id = ?").params(email.isEmpty() ? null : email.toLowerCase(Locale.ROOT), me.memberId()).update();
        return settings(jwt);
    }

    static boolean isPushService(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null
                    && PUSH_HOSTS.stream().anyMatch(h -> host.toLowerCase(Locale.ROOT).endsWith(h));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
