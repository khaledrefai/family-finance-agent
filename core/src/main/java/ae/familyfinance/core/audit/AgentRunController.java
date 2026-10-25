package ae.familyfinance.core.audit;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent runs, opened and closed by the agent service with the member's own token. Every call in between is
 * recorded by {@link AgentRunFilter}. Parents can read the trail ("What the agent did").
 */
@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {

    public record OpenRun(@NotNull @Pattern(regexp = "ORCHESTRATOR|BOOKKEEPER|GATEKEEPER|SHOPPER|COACH|PLANNER") String agent,
                          @Pattern(regexp = "MESSAGE|SCHEDULE|EVENT") String triggerKind,
                          @Size(max = 2000) String inputSummary) {
    }

    public record FinishRun(@NotNull @Pattern(regexp = "SUCCEEDED|FAILED") String status,
                            @Size(max = 4000) String outputSummary, @Size(max = 80) String model,
                            Boolean llmUsed, @Size(max = 120) String fallbackReason,
                            @PositiveOrZero Integer latencyMs, @PositiveOrZero Integer inputTokens,
                            @PositiveOrZero Integer outputTokens) {
    }

    public record RunId(UUID id) {
    }

    public record ToolCall(String tool, boolean changesState, String status, Integer httpStatus, JsonNode asked,
                           OffsetDateTime at) {
    }

    public record RunView(UUID id, String agent, String triggeredBy, String status, String inputSummary,
                          String outputSummary, String model, Boolean llmUsed, String fallbackReason,
                          Integer latencyMs, Integer inputTokens, Integer outputTokens, OffsetDateTime startedAt,
                          OffsetDateTime finishedAt, List<ToolCall> calls) {
    }

    private final JdbcClient db;
    private final ObjectMapper json;

    public AgentRunController(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunId open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenRun body) {
        CurrentMember me = CurrentMember.from(jwt);
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO agent_run (id, family_id, triggered_by_id, agent, trigger_kind, input_summary)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), me.memberId(), body.agent(),
                        body.triggerKind() == null ? "MESSAGE" : body.triggerKind(), body.inputSummary())
                .update();
        return new RunId(id);
    }

    /** Close a run with what happened: which model answered, or why the rule-based fallback did instead. */
    @PostMapping("/{id}/finish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FinishRun body) {
        CurrentMember me = CurrentMember.from(jwt);
        int changed = db.sql("""
                UPDATE agent_run SET status = ?, output_summary = ?, model = ?, llm_used = ?, fallback_reason = ?,
                       latency_ms = ?, input_tokens = ?, output_tokens = ?, finished_at = now()
                WHERE id = ? AND family_id = ? AND triggered_by_id = ? AND status = 'RUNNING'
                """).params(body.status(), body.outputSummary(), body.model(), body.llmUsed(), body.fallbackReason(),
                        body.latencyMs(), body.inputTokens(), body.outputTokens(), id, me.familyId(), me.memberId())
                .update();
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent run not found, or already closed");
        }
    }

    /** "What the agent did": the latest runs with every call they made. Parents only. */
    @GetMapping
    public List<RunView> recent(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
        CurrentMember me = CurrentMember.from(jwt);
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see what the agents did");
        }
        Map<UUID, List<ToolCall>> calls = new LinkedHashMap<>();
        db.sql("""
                SELECT t.agent_run_id, t.tool_name, t.changes_state, t.status, t.http_status, t.request_payload::text, t.executed_at
                FROM tool_execution t JOIN agent_run r ON r.id = t.agent_run_id
                WHERE r.family_id = ? ORDER BY t.executed_at
                """).param(me.familyId())
                .query((rs, n) -> calls.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new ToolCall(rs.getString(2), rs.getBoolean(3), rs.getString(4),
                                rs.getObject(5, Integer.class), read(rs.getString(6)),
                                rs.getObject(7, OffsetDateTime.class))))
                .list();
        return db.sql("""
                SELECT r.id, r.agent, m.display_name, r.status, r.input_summary, r.output_summary, r.model, r.llm_used,
                       r.fallback_reason, r.latency_ms, r.input_tokens, r.output_tokens, r.started_at, r.finished_at
                FROM agent_run r LEFT JOIN member m ON m.id = r.triggered_by_id
                WHERE r.family_id = ? ORDER BY r.started_at DESC LIMIT ?
                """).params(me.familyId(), Math.max(1, Math.min(limit, 100)))
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new RunView(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getObject(8, Boolean.class), rs.getString(9),
                            rs.getObject(10, Integer.class), rs.getObject(11, Integer.class), rs.getObject(12, Integer.class),
                            rs.getObject(13, OffsetDateTime.class), rs.getObject(14, OffsetDateTime.class),
                            calls.getOrDefault(id, List.of()));
                }).list();
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }
}
