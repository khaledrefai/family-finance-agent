package ae.familyfinance.core.proposals;

import ae.familyfinance.core.auth.TokenService;
import ae.familyfinance.core.family.Member;
import ae.familyfinance.core.family.MemberRepository;
import ae.familyfinance.core.family.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Wakes the Planner agent for the weekly money check, every Sunday morning (UAE time), for each family that has
 * finished setting up. The agent acts as the family's first parent, with a short-lived token of that parent, so it can
 * never do more than that parent could; everything it proposes waits for a parent's tap.
 * Off when {@code app.agents.url} is empty (tests, a core run on its own).
 */
@Component
public class AgentCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckScheduler.class);

    private final JdbcClient db;
    private final MemberRepository members;
    private final TokenService tokens;
    private final String agentsUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // uvicorn speaks HTTP/1.1 only; an h2c upgrade loses the body
            .connectTimeout(Duration.ofSeconds(5)).build();

    public AgentCheckScheduler(JdbcClient db, MemberRepository members, TokenService tokens,
                               @Value("${app.agents.url:}") String agentsUrl) {
        this.db = db;
        this.members = members;
        this.tokens = tokens;
        this.agentsUrl = agentsUrl == null ? "" : agentsUrl.replaceAll("/+$", "");
    }

    @Scheduled(cron = "${app.agents.check-cron:0 0 8 * * SUN}", zone = "Asia/Dubai")
    void weekly() {
        if (agentsUrl.isBlank()) {
            return;
        }
        List<UUID> families = db.sql("SELECT id FROM family WHERE setup_done_at IS NOT NULL OR is_demo")
                .query(UUID.class).list();
        for (UUID family : families) {
            try {
                runFor(family);
            } catch (Exception e) {
                log.warn("Weekly money check failed for a family: {}", e.toString());
            }
        }
    }

    /** Asks the agent service to run the check for one family. Returns the agent's HTTP status. */
    public int runFor(UUID family) throws Exception {
        Member parent = members.findByFamilyIdOrderByRoleDescDisplayNameAsc(family).stream()
                .filter(m -> m.getRole() == Role.PARENT).findFirst().orElse(null);
        if (parent == null) {
            return 0;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(agentsUrl + "/check"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + tokens.issue(parent))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"trigger\":\"SCHEDULE\"}"))
                .build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status >= 400) {
            log.warn("Weekly money check: the agent service answered {}", status);
        }
        return status;
    }
}
