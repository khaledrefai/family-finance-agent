package ae.familyfinance.core.proposals;

import ae.familyfinance.core.live.OutboxDispatcher;
import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The weekly money check's proposals: only an agent run proposes, only a parent's own tap decides. */
@AutoConfigureMockMvc
class ProposalIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_proposal");
        jdbc.update("DELETE FROM family_event WHERE kind IN ('AGENT_PROPOSAL', 'AGENT_NUDGE')");
        dispatcher.dispatchPending();
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private String openRun(String token) throws Exception {
        String body = mvc.perform(post("/api/agent-runs").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent\":\"PLANNER\",\"triggerKind\":\"SCHEDULE\",\"inputSummary\":\"Weekly money check\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private String nudge() throws Exception {
        return json.writeValueAsString(Map.of("kind", "NUDGE", "title", "Well done, Layla",
                "body", "Layla is close to her goal.", "why", List.of("Layla's goal Headphones: 75%."),
                "childId", DemoFamily.LAYLA.toString(), "childMessage", "You're nearly there with your headphones!"));
    }

    @Test
    void onlyAnAgentRunCanProposeAndOnlyAParentsTapDecides() throws Exception {
        String sara = login("sara");
        String omar = login("omar");
        String layla = login("layla");

        // Without an agent run, even a parent cannot make a proposal
        mvc.perform(post("/api/agent-proposals").header("Authorization", sara).contentType(MediaType.APPLICATION_JSON)
                .content(nudge())).andExpect(status().isForbidden());

        String run = openRun(sara);
        String body = mvc.perform(post("/api/agent-proposals").header("Authorization", sara)
                        .header("X-Agent-Run-Id", run).contentType(MediaType.APPLICATION_JSON).content(nudge()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.childName").value("Layla"))
                .andExpect(jsonPath("$.triggeredBy").value("SCHEDULE"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asText());

        // The same suggestion is not proposed twice while it waits
        mvc.perform(post("/api/agent-proposals").header("Authorization", sara).header("X-Agent-Run-Id", run)
                .contentType(MediaType.APPLICATION_JSON).content(nudge())).andExpect(status().isConflict());

        // Parents are told; the proposal sends nothing to the child yet
        dispatcher.dispatchPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification n JOIN family_event e ON e.id = n.event_id "
                + "WHERE e.kind = 'AGENT_PROPOSAL' AND n.member_id = ?", Long.class, DemoFamily.OMAR)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM family_event WHERE kind = 'AGENT_NUDGE'", Long.class))
                .isZero();

        // A child can't see or decide; the agent can't approve its own suggestion
        mvc.perform(get("/api/agent-proposals").header("Authorization", layla)).andExpect(status().isForbidden());
        mvc.perform(post("/api/agent-proposals/" + id + "/approve").header("Authorization", sara)
                .header("X-Agent-Run-Id", run)).andExpect(status().isForbidden());

        mvc.perform(get("/api/agent-proposals").header("Authorization", omar))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].why[0]").value("Layla's goal Headphones: 75%."));

        // Omar approves: the message goes to Layla, once
        mvc.perform(post("/api/agent-proposals/" + id + "/approve").header("Authorization", omar))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("Omar"));
        mvc.perform(post("/api/agent-proposals/" + id + "/dismiss").header("Authorization", omar))
                .andExpect(status().isNotFound());
        dispatcher.dispatchPending();
        JsonNode note = json.readTree(json.writeValueAsString(jdbc.queryForMap(
                "SELECT n.title, n.body FROM notification n JOIN family_event e ON e.id = n.event_id "
                        + "WHERE e.kind = 'AGENT_NUDGE' AND n.member_id = ?", DemoFamily.LAYLA)));
        assertThat(note.get("body").asText()).isEqualTo("You're nearly there with your headphones!");
    }

    @Test
    void aNudgeMustBeForOneOfTheFamilysChildren() throws Exception {
        String sara = login("sara");
        String run = openRun(sara);
        String toParent = json.writeValueAsString(Map.of("kind", "NUDGE", "title", "Hi", "body", "x",
                "childId", DemoFamily.OMAR.toString(), "childMessage", "Hello"));
        mvc.perform(post("/api/agent-proposals").header("Authorization", sara).header("X-Agent-Run-Id", run)
                .contentType(MediaType.APPLICATION_JSON).content(toParent)).andExpect(status().isBadRequest());
    }
}
