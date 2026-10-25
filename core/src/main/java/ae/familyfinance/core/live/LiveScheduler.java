package ae.familyfinance.core.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Turns on the timers: the outbox poller every 2 seconds, the live-stream heartbeat, and the core's own alert checks
 * ({@code AlertService}). The tests switch it off ({@code app.live.scheduler=false}) and call the poller themselves.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.live.scheduler", havingValue = "true", matchIfMissing = true)
public class LiveScheduler {

    private final OutboxDispatcher dispatcher;

    public LiveScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.live.poll-ms:2000}", initialDelay = 5000)
    void poll() {
        dispatcher.dispatchPending();
    }
}
