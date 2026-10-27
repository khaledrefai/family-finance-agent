package ae.familyfinance.core.requests;

import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end permission tests through the real HTTP API, JWT login and PostgreSQL,
 * using the seeded demo family (Sara and Omar are parents, Layla 13 and Adam 9 are children).
 */
@AutoConfigureMockMvc
class RequestApprovalIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /**
     * Approving pays from Layla's demo wallet, and her headphones jar first; give the money back and put the goal back
     * so later tests start from the seed's wallet and goal.
     */
    @AfterEach
    void refundTheWallets() {
        jdbc.update("DELETE FROM demo_wallet_entry WHERE request_id IN (SELECT id FROM spending_request WHERE family_id = ?)",
                DemoFamily.FAMILY);
        jdbc.update("""
                UPDATE goal SET status = 'ACTIVE', saved_minor = 12000, target_minor = 30000, reached_at = NULL
                WHERE id = '00000000-0000-0000-0000-0000000003c1'
                """);
        jdbc.update("DELETE FROM points_entry WHERE reason LIKE 'Goal reached:%'");
    }

    @Test
    void headphones_she_saved_for_are_paid_from_her_jar_first_and_reach_the_goal() throws Exception {
        String layla = login("layla");
        String id = createRequest(layla, "Sony WH-CH720N headphones", 28900);
        long walletBefore = walletOf(DemoFamily.LAYLA);

        // Omar's list says who pays on a yes, so there is no card to pick
        JsonNode list = json.readTree(mvc.perform(get("/api/requests").header("Authorization", login("omar")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode mine = null;
        for (JsonNode r : list) {
            if (r.get("id").asText().equals(id)) {
                mine = r;
            }
        }
        assertThat(mine.get("childPays").asText()).isEqualTo("Layla's headphones jar and wallet");

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", login("omar")))
                .andExpect(status().isOk());

        // AED 120 from the jar, AED 169 from the wallet; the goal is reached at the price paid, with 5 stars
        assertThat(walletBefore - walletOf(DemoFamily.LAYLA)).isEqualTo(16_900);
        assertThat(jdbc.queryForMap("SELECT status, saved_minor, target_minor FROM goal WHERE id = '00000000-0000-0000-0000-0000000003c1'"))
                .containsEntry("status", "REACHED").containsEntry("saved_minor", 28_900L).containsEntry("target_minor", 28_900L);
        assertThat(jdbc.queryForObject("SELECT points FROM points_entry WHERE member_id = ? AND reason = 'Goal reached: Headphones'",
                Integer.class, DemoFamily.LAYLA)).isEqualTo(5);
    }

    private long walletOf(java.util.UUID member) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount_minor), 0) FROM demo_wallet_entry e JOIN demo_wallet w ON w.id = e.wallet_id
                WHERE w.member_id = ?
                """, Long.class, member);
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private String createRequest(String token, String title, long amountMinor) throws Exception {
        String body = mvc.perform(post("/api/requests").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"ASK_TO_BUY","title":"%s","amountMinor":%d,"category":"ELECTRONICS"}
                                """.formatted(title, amountMinor)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void child_can_never_approve_their_own_request() throws Exception {
        String layla = login("layla");
        String id = createRequest(layla, "Wireless headphones", 30000);

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", layla))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/requests/" + id + "/reject").header("Authorization", layla))
                .andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(approvalCount(id)).isZero();
    }

    @Test
    void sibling_cannot_approve_and_parent_can() throws Exception {
        String layla = login("layla");
        String adam = login("adam");
        String omar = login("omar");
        String id = createRequest(layla, "Headphones", 30000);

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", adam))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", omar)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"OK, from your savings\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Deciding twice is refused.
        mvc.perform(post("/api/requests/" + id + "/reject").header("Authorization", login("sara")))
                .andExpect(status().isConflict());

        assertThat(approvalCount(id)).isEqualTo(1);
    }

    @Test
    void parent_cannot_approve_own_request_but_other_parent_can() throws Exception {
        String sara = login("sara");
        String id = createRequest(sara, "New laptop bag", 25000);

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", sara))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", login("omar")))
                .andExpect(status().isOk());
    }

    @Test
    void child_sees_only_own_requests_parent_sees_all() throws Exception {
        String layla = login("layla");
        String adam = login("adam");
        String laylaReq = createRequest(layla, "Book", 4500);
        String adamReq = createRequest(adam, "Snack", 500);

        String laylaList = mvc.perform(get("/api/requests").header("Authorization", layla))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode laylaItems = json.readTree(laylaList);
        assertThat(laylaItems.findValuesAsText("id")).contains(laylaReq).doesNotContain(adamReq);
        assertThat(laylaItems.findValuesAsText("requesterId")).containsOnly(DemoFamily.LAYLA.toString());

        String parentList = mvc.perform(get("/api/requests").header("Authorization", login("sara")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(parentList).findValuesAsText("id")).contains(laylaReq, adamReq);
    }

    @Test
    void unknown_or_other_family_request_is_not_found() throws Exception {
        mvc.perform(post("/api/requests/" + UUID.randomUUID() + "/approve").header("Authorization", login("sara")))
                .andExpect(status().isNotFound());
    }

    @Test
    void database_also_refuses_a_self_approval_even_if_the_code_had_a_bug() throws Exception {
        String id = createRequest(login("layla"), "Game credits", 2000);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO approval (request_id, approver_id, decision) VALUES (?::uuid, ?, 'APPROVED')",
                id, DemoFamily.LAYLA))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot decide their own request");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO approval (request_id, approver_id, decision) VALUES (?::uuid, ?, 'APPROVED')",
                id, DemoFamily.ADAM))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("only a parent");
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM spending_request WHERE id = ?::uuid", String.class, id);
    }

    private int approvalCount(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM approval WHERE request_id = ?::uuid", Integer.class, id);
    }
}
