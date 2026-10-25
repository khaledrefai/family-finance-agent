package ae.familyfinance.core.live;

import ae.familyfinance.core.live.LivePolicy.Delivery;
import ae.familyfinance.core.live.LivePolicy.Member;
import ae.familyfinance.core.live.LivePolicy.Note;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

/**
 * Reads new {@code family_event} rows and sends them on. Called every 2 seconds by {@link LiveScheduler}
 * (and directly by the tests).
 *
 * <p>Each event is handled in its own transaction: lock it ({@code FOR UPDATE SKIP LOCKED}, so two cores would never
 * take the same row), work out who hears about it ({@link LivePolicy}), write their bell rows, mark it done. Only after
 * that commits are the open screens pinged, phones pushed and parents emailed. Push and email are best effort: a
 * phone that is off still has the bell, and a screen that missed a ping reloads when it reconnects.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    static final int BATCH = 50;
    static final int MAX_ATTEMPTS = 5;

    record Pending(long id, UUID family, LiveKind kind, UUID subject, UUID actor, Map<String, String> payload) {
    }

    /** A bell row that was written, to send on after the commit. */
    record Sent(UUID notificationId, Note note, String email) {
    }

    record Work(Pending event, Delivery delivery, List<Sent> sent) {
    }

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final LiveHub hub;
    private final PushSender push;
    private final Mailer mail;

    public OutboxDispatcher(JdbcClient db, PlatformTransactionManager txm, ObjectMapper json, LiveHub hub,
                            PushSender push, Mailer mail) {
        this.db = db;
        this.tx = new TransactionTemplate(txm);
        this.json = json;
        this.hub = hub;
        this.push = push;
        this.mail = mail;
    }

    /** Sends everything waiting, up to one batch. Returns how many events were handled. */
    public int dispatchPending() {
        int done = 0;
        while (done < BATCH) {
            Work work;
            try {
                work = tx.execute(s -> takeOne());
            } catch (RuntimeException e) {
                log.warn("Outbox event failed: {}", e.toString());
                break; // the failed row's attempts were counted in markFailed; try again on the next tick
            }
            if (work == null) {
                break;
            }
            deliver(work);
            done++;
        }
        return done;
    }

    private Work takeOne() {
        Pending e = db.sql("""
                SELECT id, family_id, kind, subject_member_id, actor_member_id, payload::text
                FROM family_event WHERE processed_at IS NULL AND attempts < ?
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param(MAX_ATTEMPTS)
                .query((rs, n) -> new Pending(rs.getLong(1), rs.getObject(2, UUID.class), kind(rs.getString(3)),
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), payload(rs.getString(6))))
                .optional().orElse(null);
        if (e == null) {
            return null;
        }
        if (e.kind() == null) {
            markDone(e.id(), "Unknown kind");
            return new Work(e, new Delivery(Set.of(), List.of()), List.of());
        }
        try {
            List<Member> family = db.sql("""
                    SELECT id, display_name, role, email FROM member WHERE family_id = ? AND active ORDER BY created_at, id
                    """).param(e.family())
                    .query((rs, n) -> new Member(rs.getObject(1, UUID.class), rs.getString(2),
                            "PARENT".equals(rs.getString(3)), rs.getString(4)))
                    .list();
            Delivery d = LivePolicy.plan(new LivePolicy.Event(e.kind(), e.subject(), e.actor(), e.payload()), family);
            List<Sent> sent = new ArrayList<>();
            for (Note note : d.notes()) {
                UUID id = db.sql("""
                        INSERT INTO notification (family_id, member_id, event_id, kind, title, body, link)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT notification_once DO NOTHING RETURNING id
                        """).params(e.family(), note.memberId(), e.id(), e.kind().name(), cut(note.title(), 120),
                                cut(note.body(), 400), note.link())
                        .query(UUID.class).optional().orElse(null);
                if (id != null) {
                    String email = family.stream().filter(m -> m.id().equals(note.memberId()) && m.parent())
                            .map(Member::email).findFirst().orElse(null);
                    sent.add(new Sent(id, note, note.email() ? email : null));
                }
            }
            markDone(e.id(), null);
            return new Work(e, d, sent);
        } catch (RuntimeException ex) {
            // Count the attempt outside this (rolled back) transaction, so a broken event can't block the queue
            markFailed(e.id(), ex);
            throw ex;
        }
    }

    private void deliver(Work w) {
        Map<UUID, Sent> mine = new LinkedHashMap<>();
        w.sent().forEach(s -> mine.put(s.note().memberId(), s));
        for (UUID member : w.delivery().liveTo()) {
            Sent s = mine.get(member);
            hub.ping(member, w.event().kind(), toJson(s == null ? Map.of("kind", w.event().kind().name())
                    : Map.of("kind", w.event().kind().name(), "notification", shown(s))));
        }
        for (Sent s : w.sent()) {
            pushTo(s);
            if (s.email() != null && mail.send(s.email(), s.note().title(), s.note().body(), s.note().link())) {
                db.sql("UPDATE notification SET emailed_at = now() WHERE id = ?").param(s.notificationId()).update();
            }
        }
    }

    private void pushTo(Sent s) {
        List<PushSender.Subscription> phones = db.sql("SELECT endpoint, p256dh, auth FROM push_subscription WHERE member_id = ?")
                .param(s.note().memberId())
                .query((rs, n) -> new PushSender.Subscription(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        boolean any = false;
        for (PushSender.Subscription phone : phones) {
            switch (push.send(phone, toJson(shown(s)))) {
                case SENT -> {
                    any = true;
                    db.sql("UPDATE push_subscription SET last_ok_at = now(), failures = 0 WHERE endpoint = ?").param(phone.endpoint()).update();
                }
                case GONE -> db.sql("DELETE FROM push_subscription WHERE endpoint = ?").param(phone.endpoint()).update();
                case FAILED -> db.sql("UPDATE push_subscription SET failures = failures + 1 WHERE endpoint = ?").param(phone.endpoint()).update();
            }
        }
        // A phone that failed 20 times in a row is most likely gone for good
        db.sql("DELETE FROM push_subscription WHERE member_id = ? AND failures >= 20").param(s.note().memberId()).update();
        if (any) {
            db.sql("UPDATE notification SET pushed_at = now() WHERE id = ?").param(s.notificationId()).update();
        }
    }

    private static Map<String, String> shown(Sent s) {
        return Map.of("id", s.notificationId().toString(), "title", s.note().title(), "body", s.note().body(),
                "link", s.note().link());
    }

    private void markDone(long id, String note) {
        db.sql("UPDATE family_event SET processed_at = now(), attempts = attempts + 1, last_error = ? WHERE id = ?")
                .params(note, id).update();
    }

    private void markFailed(long id, RuntimeException ex) {
        try {
            new TransactionTemplate(tx.getTransactionManager(), new DefaultTransactionDefinition(PROPAGATION_REQUIRES_NEW))
                    .executeWithoutResult(s -> db.sql("UPDATE family_event SET attempts = attempts + 1, last_error = ? WHERE id = ?")
                            .params(cut(ex.toString(), 300), id).update());
        } catch (RuntimeException ignored) {
            // nothing more to do; the next tick will try again
        }
    }

    private static LiveKind kind(String s) {
        try {
            return LiveKind.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, String> payload(String text) {
        try {
            return json.readValue(text, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
