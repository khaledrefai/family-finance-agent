package ae.familyfinance.core.audit;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The audit lives in the core, so a bug in the agent service can't skip it. An agent opens a run
 * (POST /api/agent-runs) and sends its id as {@code X-Agent-Run-Id} on every call it makes with the member's token.
 * This filter records each such call in {@code tool_execution}: what was asked, what came back, whether it
 * changed anything, and whose permission covered it. Runs after Spring Security, so the caller is known.
 */
@Component
public class AgentRunFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Agent-Run-Id";
    static final String ATTRIBUTE = AgentRunFilter.class.getName() + ".run";
    /** Payloads above this are stored as their size only. */
    static final int MAX_PAYLOAD_CHARS = 20_000;

    private final JdbcClient db;
    private final ObjectMapper json;

    public AgentRunFilter(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** True when this request was made by an agent during a run. Anything it creates must then be a draft. */
    public static boolean isAgentCall(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) != null;
    }

    /** The open agent run this request belongs to, or null for a member's own tap in the app. */
    public static UUID runId(HttpServletRequest request) {
        return (UUID) request.getAttribute(ATTRIBUTE);
    }

    /** Some steps are a parent's tap in the app, never an agent's call: confirming a draft, picking the plan. */
    public static void refuseAgentCall(HttpServletRequest request, String message) {
        if (isAgentCall(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, message);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(HEADER) == null || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }
        CurrentMember me = CurrentMember.from(jwt);
        UUID run;
        try {
            run = UUID.fromString(request.getHeader(HEADER));
        } catch (IllegalArgumentException e) {
            refuse(response, "The agent run id is not valid");
            return;
        }
        boolean open = db.sql("SELECT count(*) FROM agent_run WHERE id = ? AND family_id = ? AND status = 'RUNNING'")
                .params(run, me.familyId()).query(Long.class).single() > 0;
        if (!open) {
            refuse(response, "This agent run is not open");
            return;
        }
        request.setAttribute(ATTRIBUTE, run);
        if (request.getRequestURI().startsWith("/api/agent-runs")) {
            chain.doFilter(request, response); // opening and closing the run is not a tool call
            return;
        }

        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, MAX_PAYLOAD_CHARS);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(req, res);
        } finally {
            record(run, me, req, res);
            res.copyBodyToResponse();
        }
    }

    private void record(UUID run, CurrentMember me, ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String tool = req.getMethod() + " " + (pattern == null ? req.getRequestURI() : pattern);
        ObjectNode asked = json.createObjectNode();
        asked.put("path", req.getRequestURI());
        if (req.getQueryString() != null) {
            asked.put("query", req.getQueryString());
        }
        String body = new String(req.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!body.isBlank()) {
            asked.set("body", parse(body));
        }
        int status = res.getStatus();
        String type = res.getContentType();
        // A file (a receipt photo the agent reads) is recorded by its size, not its bytes
        String answer = type != null && !type.contains("json") && !type.startsWith("text/")
                ? json.createObjectNode().put("contentType", type).put("bytes", res.getContentSize()).toString()
                : new String(res.getContentAsByteArray(), StandardCharsets.UTF_8).replace("\0", "");
        String outcome = status < 400 ? "OK" : (status == 401 || status == 403 || status == 404) ? "DENIED" : "ERROR";
        boolean changes = !"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod());
        db.sql("""
                INSERT INTO tool_execution (agent_run_id, tool_name, request_payload, response_payload, changes_state,
                                            authorised_by_id, status, http_status)
                VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                """).params(run, tool.length() > 80 ? tool.substring(0, 80) : tool, asked.toString(),
                        answer.isBlank() ? null : parse(answer).toString(), changes, me.memberId(), outcome, status)
                .update();
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String text) {
        if (text.length() > MAX_PAYLOAD_CHARS) {
            return json.createObjectNode().put("truncated", true).put("chars", text.length());
        }
        try {
            return json.readTree(text);
        } catch (IOException e) {
            return json.getNodeFactory().textNode(text);
        }
    }

    private void refuse(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.createObjectNode().put("status", 400).put("detail", detail).toString());
    }
}
