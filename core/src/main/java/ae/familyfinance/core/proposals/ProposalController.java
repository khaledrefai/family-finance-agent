package ae.familyfinance.core.proposals;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.live.LiveKind;
import ae.familyfinance.core.live.Outbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the weekly money check proposes. Only an agent run can propose ({@code POST} with the run header); only a
 * parent's own tap in the app can approve or dismiss, so those refuse agent calls. Approving a NUDGE sends the child
 * the message; NOTE and BUDGET are marked done (a budget is changed by the parent in Budgets, never by the agent).
 */
@RestController
@RequestMapping("/api/agent-proposals")
public class ProposalController {

    public record NewProposal(@NotBlank @Pattern(regexp = "NOTE|BUDGET|NUDGE") String kind,
                              @NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 400) String body,
                              @Size(max = 8) List<@Size(max = 600) String> why, @Size(max = 120) String link,
                              UUID childId, @Size(max = 300) String childMessage) {
    }

    public record ProposalView(UUID id, String kind, String title, String body, List<String> why, String link,
                               UUID childId, String childName, String childMessage, String status,
                               OffsetDateTime createdAt, String decidedBy, OffsetDateTime decidedAt,
                               String triggeredBy) {
    }

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Outbox outbox;

    public ProposalController(JdbcClient db, ObjectMapper json, Outbox outbox) {
        this.db = db;
        this.json = json;
        this.outbox = outbox;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProposalView propose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewProposal p,
                                HttpServletRequest http) {
        CurrentMember me = CurrentMember.from(jwt);
        UUID run = AgentRunFilter.runId(http);
        if (run == null || !me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the money check can make a proposal, for a parent");
        }
        if ("NUDGE".equals(p.kind())) {
            boolean child = p.childId() != null && p.childMessage() != null && !p.childMessage().isBlank()
                    && db.sql("SELECT count(*) FROM member WHERE id = ? AND family_id = ? AND role = 'CHILD'")
                    .params(p.childId(), me.familyId()).query(Long.class).single() > 0;
            if (!child) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A nudge needs one of the family's children and a message");
            }
        }
        if (p.link() != null && !p.link().startsWith("/parent")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A proposal can only link to a parent screen");
        }
        UUID id = UUID.randomUUID();
        try {
            db.sql("""
                    INSERT INTO agent_proposal (id, family_id, agent_run_id, kind, title, body, why, link, child_id,
                                                child_message)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """).params(id, me.familyId(), run, p.kind(), p.title(), p.body(), write(p.why()), p.link(),
                            "NUDGE".equals(p.kind()) ? p.childId() : null,
                            "NUDGE".equals(p.kind()) ? p.childMessage() : null)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "The same suggestion is already waiting for a parent");
        }
        outbox.publish(me.familyId(), LiveKind.AGENT_PROPOSAL, null, null,
                Map.of("title", p.title(), "body", p.body()));
        return find(me, id);
    }

    /** The proposals, newest first: waiting ones by default, or all of them ({@code all=true}). Parents only. */
    @GetMapping
    public List<ProposalView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean all) {
        CurrentMember me = CurrentMember.from(jwt);
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the money check");
        }
        return query(me, all ? "" : "AND p.status = 'PROPOSED'", null);
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public ProposalView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can approve a suggestion, in the app");
        CurrentMember me = decide(jwt, id, "APPROVED");
        ProposalView p = find(me, id);
        if ("NUDGE".equals(p.kind())) {
            outbox.publish(me.familyId(), LiveKind.AGENT_NUDGE, p.childId(), me.memberId(),
                    Map.of("title", "A note from your parents", "body", p.childMessage()));
        }
        return p;
    }

    @PostMapping("/{id}/dismiss")
    @Transactional
    public ProposalView dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can dismiss a suggestion, in the app");
        return find(decide(jwt, id, "DISMISSED"), id);
    }

    private CurrentMember decide(Jwt jwt, UUID id, String status) {
        CurrentMember me = CurrentMember.from(jwt);
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can decide on a suggestion");
        }
        int changed = db.sql("""
                UPDATE agent_proposal SET status = ?, decided_by = ?, decided_at = now()
                WHERE id = ? AND family_id = ? AND status = 'PROPOSED'
                """).params(status, me.memberId(), id, me.familyId()).update();
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Suggestion not found, or already decided");
        }
        return me;
    }

    private ProposalView find(CurrentMember me, UUID id) {
        return query(me, "AND p.id = ?", id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Suggestion not found"));
    }

    private List<ProposalView> query(CurrentMember me, String where, UUID id) {
        var sql = db.sql("""
                SELECT p.id, p.kind, p.title, p.body, p.why::text, p.link, p.child_id, c.display_name, p.child_message,
                       p.status, p.created_at, d.display_name, p.decided_at, r.trigger_kind
                FROM agent_proposal p
                LEFT JOIN member c ON c.id = p.child_id
                LEFT JOIN member d ON d.id = p.decided_by
                LEFT JOIN agent_run r ON r.id = p.agent_run_id
                WHERE p.family_id = ? %s
                ORDER BY p.created_at DESC LIMIT 30
                """.formatted(where)).param(me.familyId());
        if (id != null) {
            sql = sql.param(id);
        }
        return sql.query((rs, n) -> new ProposalView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), read(rs.getString(5)), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class), rs.getString(12),
                rs.getObject(13, OffsetDateTime.class), rs.getString(14))).list();
    }

    private String write(List<String> why) {
        try {
            return json.writeValueAsString(why == null ? List.of() : why);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> read(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
