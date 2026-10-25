package ae.familyfinance.core.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The open screens, by member. Each one is a Server-Sent Events stream (GET /api/live). A ping only says what
 * changed ("REQUEST_DECIDED"); the screen then reloads it through the normal API, so the same permission checks
 * apply and a missed ping costs nothing (screens also reload when the stream reconnects).
 *
 * <p>One core instance holds all streams in memory, which is fine for one VM. More instances would need the
 * poller to tell each other (Postgres LISTEN/NOTIFY), not needed for Phase 1.
 */
@Component
public class LiveHub {

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);
    /** Streams are closed after this and the browser reconnects, so a dead phone doesn't keep one open forever. */
    static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

    public SseEmitter open(UUID member) {
        SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
        Set<SseEmitter> mine = streams.computeIfAbsent(member, k -> new CopyOnWriteArraySet<>());
        mine.add(emitter);
        Runnable drop = () -> mine.remove(emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(() -> {
            drop.run();
            emitter.complete();
        });
        emitter.onError(t -> drop.run());
        // The first message tells the screen it is connected; it reloads once in case it missed something.
        send(emitter, SseEmitter.event().name("hello").data("{}", MediaType.APPLICATION_JSON).reconnectTime(3000));
        return emitter;
    }

    /** {@code data} is a small JSON object; no amounts or names of other members go here. */
    public void ping(UUID member, LiveKind kind, String data) {
        Set<SseEmitter> mine = streams.get(member);
        if (mine == null) {
            return;
        }
        for (SseEmitter e : mine) {
            if (!send(e, SseEmitter.event().name(kind.name()).data(data, MediaType.APPLICATION_JSON))) {
                mine.remove(e);
            }
        }
    }

    public int openStreams(UUID member) {
        Set<SseEmitter> mine = streams.get(member);
        return mine == null ? 0 : mine.size();
    }

    /** A comment line every 20 seconds keeps proxies from closing a quiet stream and finds dead ones. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    void heartbeat() {
        streams.values().forEach(set -> set.removeIf(e -> !send(e, SseEmitter.event().comment("still here"))));
    }

    private static boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Live stream closed: {}", e.getMessage());
            emitter.completeWithError(e);
            return false;
        }
    }
}
