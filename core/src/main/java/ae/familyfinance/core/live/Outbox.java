package ae.familyfinance.core.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writes a {@code family_event} row. Call it inside the transaction that makes the change, so the event exists if
 * and only if the change does. {@link OutboxDispatcher} picks it up within about 2 seconds and sends it on.
 */
@Component
public class Outbox {

    private final JdbcClient db;
    private final ObjectMapper json;

    public Outbox(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** {@code payload} is plain facts with ready-made text (names, "AED 300"), never figures to work out later. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID family, LiveKind kind, UUID subject, UUID actor, Map<String, String> payload) {
        insert(family, kind, subject, actor, payload, null);
    }

    /**
     * For alerts the core raises by itself: the same {@code dedupeKey} is only ever sent once per family.
     * Returns false when it had already been sent.
     */
    @Transactional
    public boolean publishOnce(UUID family, LiveKind kind, String dedupeKey, Map<String, String> payload) {
        return insert(family, kind, null, null, payload, dedupeKey) > 0;
    }

    private int insert(UUID family, LiveKind kind, UUID subject, UUID actor, Map<String, String> payload, String dedupeKey) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return db.sql("""
                INSERT INTO family_event (family_id, kind, subject_member_id, actor_member_id, payload, dedupe_key)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT ON CONSTRAINT family_event_once DO NOTHING
                """).params(family, kind.name(), subject, actor, body, dedupeKey).update();
    }
}
