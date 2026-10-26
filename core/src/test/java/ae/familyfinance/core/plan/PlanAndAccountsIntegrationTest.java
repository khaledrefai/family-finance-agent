package ae.familyfinance.core.plan;

import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Week 2 through the real API and the seeded Haddad family: the plan, accounts and cards, drafts, the audit trail. */
@AutoConfigureMockMvc
class PlanAndAccountsIntegrationTest extends PostgresIntegrationTest {

    static final String VISA = "00000000-0000-0000-0000-0000000000e3";
    static final String MASTERCARD = "00000000-0000-0000-0000-0000000000e4";
    static final String TRIP = "00000000-0000-0000-0000-0000000003f2";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions postJson(String url, String token, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String openRun(String token, String agent) throws Exception {
        return read(postJson("/api/agent-runs", token,
                "{\"agent\":\"" + agent + "\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"test\"}")
                .andExpect(status().isCreated())).get("id").asText();
    }

    @Test
    void cashflow_plans_sara_commission_on_the_cautious_base() throws Exception {
        JsonNode c = read(mvc.perform(get("/api/plan/cashflow").header("Authorization", login("sara")))
                .andExpect(status().isOk()));
        JsonNode lines = c.get("income").get("lines");
        assertThat(lines.findValuesAsText("label")).contains("Omar salary", "Sara commission");
        JsonNode sara = lines.get(1);
        assertThat(sara.get("amount").get("text").asText()).isEqualTo("AED 6,500");
        assertThat(c.get("months")).hasSize(12);
        assertThat(c.get("untilNextIncome").get("nextIncomeLabel").asText()).isEqualTo("Omar salary");
        // free = income − commitments − everyday, all worked out by the engine
        long free = c.get("income").get("total").get("minor").asLong() - c.get("commitments").get("total").get("minor").asLong()
                    - c.get("everyday").get("total").get("minor").asLong();
        assertThat(c.get("free").get("minor").asLong()).isEqualTo(free);
    }

    @Test
    void children_cannot_see_the_family_plan_or_accounts() throws Exception {
        String layla = login("layla");
        mvc.perform(get("/api/plan/cashflow").header("Authorization", layla)).andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts").header("Authorization", layla)).andExpect(status().isForbidden());
    }

    @Test
    void headphones_on_the_visa_show_the_card_warning() throws Exception {
        JsonNode check = read(mvc.perform(get("/api/accounts/" + VISA + "/card-check").param("amount", "300")
                .header("Authorization", login("omar"))).andExpect(status().isOk()));
        assertThat(check.get("fits").asBoolean()).isFalse();
        assertThat(check.get("line").asText()).startsWith("This card's bill on ").contains("AED 6,250", "only");
    }

    @Test
    void a_child_assessing_sees_their_wallet_not_the_family_cash() throws Exception {
        JsonNode a = read(postJson("/api/requests/assess", login("layla"), "{\"amount\":\"300\"}").andExpect(status().isOk()));
        assertThat(a.get("freeUntilPay").isNull()).isTrue();
        assertThat(a.get("kid").get("name").asText()).isEqualTo("Layla");
        assertThat(a.get("needsParent").asBoolean()).isTrue();          // over the AED 50 approval limit
        assertThat(a.get("facts").toString()).doesNotContain("Free until");
    }

    @Test
    void a_parent_assessing_on_a_card_gets_the_card_line_in_the_facts() throws Exception {
        JsonNode a = read(postJson("/api/requests/assess", login("sara"), """
                {"amount":"300","requesterId":"%s","paidFromAccountId":"%s"}""".formatted(DemoFamily.LAYLA, VISA))
                .andExpect(status().isOk()));
        assertThat(a.get("card").get("fits").asBoolean()).isFalse();
        assertThat(a.get("verdict").asText()).isEqualTo("DOES_NOT_FIT");
        assertThat(a.get("facts").toString()).contains("Free until pay day", "This card's bill");
    }

    @Test
    void a_new_request_stores_the_assessment() throws Exception {
        String id = read(postJson("/api/requests", login("adam"),
                "{\"kind\":\"ASK_TO_BUY\",\"title\":\"Football\",\"amountMinor\":4500}").andExpect(status().isCreated()))
                .get("id").asText();
        Integer version = jdbc.queryForObject("SELECT assessment_version FROM spending_request WHERE id = ?::uuid",
                Integer.class, id);
        String stored = jdbc.queryForObject("SELECT assessment::text FROM spending_request WHERE id = ?::uuid", String.class, id);
        assertThat(version).isEqualTo(2);
        assertThat(stored).contains("Adam's wallet has");
    }

    @Test
    void a_payment_must_say_what_paid_it_and_card_payments_are_transfers() throws Exception {
        String sara = login("sara");
        postJson("/api/payments", sara, "{\"amount\":\"85\",\"category\":\"EATING_OUT\"}").andExpect(status().isBadRequest());
        JsonNode paid = read(postJson("/api/payments", sara, """
                {"amount":"1","description":"Test coffee","category":"EATING_OUT","paidFromAccountId":"%s"}""".formatted(MASTERCARD))
                .andExpect(status().isCreated()));
        assertThat(paid.get("cardBill")).isNotNull();
        JsonNode transfer = read(postJson("/api/payments", sara, """
                {"amount":"1","paidFromAccountId":"00000000-0000-0000-0000-0000000000e1","transferToAccountId":"%s"}"""
                .formatted(MASTERCARD)).andExpect(status().isCreated()));
        assertThat(transfer.get("category").asText()).isEqualTo("CARD_PAYMENT");
        jdbc.update("DELETE FROM ledger_transaction WHERE id IN (?::uuid, ?::uuid)", paid.get("id").asText(),
                transfer.get("id").asText());
    }

    @Test
    void plan_options_offer_three_paces_for_the_summer_trip() throws Exception {
        JsonNode o = read(postJson("/api/plan/options", login("sara"), "{\"goalId\":\"" + TRIP + "\"}").andExpect(status().isOk()));
        assertThat(o.get("options").findValuesAsText("id")).containsExactly("relaxed", "steady", "faster");
        long relaxed = o.get("options").get(0).get("monthly").get("minor").asLong();
        long faster = o.get("options").get(2).get("monthly").get("minor").asLong();
        assertThat(relaxed).isLessThan(faster);
        assertThat(relaxed % 5_000).isZero();                              // rounded up to AED 50
    }

    @Test
    void the_calculator_starts_from_a_goal_and_shows_1_5_and_10_years() throws Exception {
        String home = "00000000-0000-0000-0000-0000000003f3";
        JsonNode c = read(postJson("/api/plan/calculator", login("sara"),
                "{\"goalId\":\"" + home + "\",\"monthly\":\"1,000\",\"rate\":\"5\"}").andExpect(status().isOk()));
        assertThat(c.get("from").asText()).isEqualTo("GOAL");
        assertThat(c.get("goalName").asText()).isEqualTo("Home deposit");
        JsonNode p = c.get("projection");
        assertThat(p.get("marks").findValuesAsText("year")).containsExactly("1", "5", "10");
        assertThat(p.get("marks").get(2).get("putIn").get("minor").asLong()).isEqualTo(12_000_000);
        assertThat(p.get("reach").get("target").get("minor").asLong()).isEqualTo(15_000_000);
        assertThat(p.get("reach").get("monthlyNeeded").get("minor").asLong()).isPositive();
    }

    @Test
    void the_calculator_can_start_from_the_investing_bucket_and_children_cannot_use_it() throws Exception {
        JsonNode c = read(postJson("/api/plan/calculator", login("omar"), "{\"from\":\"INVEST_ASIDE\",\"rate\":\"0\"}")
                .andExpect(status().isOk()));
        assertThat(c.get("from").asText()).isEqualTo("INVEST_ASIDE");
        long monthly = c.get("projection").get("monthly").get("minor").asLong();
        assertThat(monthly).isPositive();
        assertThat(c.get("projection").get("marks").get(0).get("total").get("minor").asLong()).isEqualTo(12 * monthly);
        postJson("/api/plan/calculator", login("omar"), "{\"monthly\":\"500\",\"rate\":\"40\"}").andExpect(status().isBadRequest());
        postJson("/api/plan/calculator", login("layla"), "{\"monthly\":\"50\"}").andExpect(status().isForbidden());
    }

    @Test
    void buckets_add_up_to_the_income() throws Exception {
        JsonNode b = read(mvc.perform(get("/api/plan/buckets").header("Authorization", login("sara"))).andExpect(status().isOk()));
        long sum = 0;
        for (JsonNode bucket : b.get("buckets")) {
            sum += bucket.get("amount").get("minor").asLong();
        }
        assertThat(sum).isEqualTo(b.get("income").get("minor").asLong());
        assertThat(b.get("survivalCovered").asBoolean()).isTrue();
    }

    @Test
    void a_parent_confirms_buckets_and_an_agent_cannot() throws Exception {
        String sara = login("sara");
        String run = openRun(sara, "PLANNER");
        mvc.perform(post("/api/plan/buckets/confirm").header("Authorization", sara).header("X-Agent-Run-Id", run))
                .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM bucket_plan WHERE family_id = ?", DemoFamily.FAMILY);
        // No draft yet: confirming takes the engine's own suggestion
        JsonNode b = read(mvc.perform(post("/api/plan/buckets/confirm").header("Authorization", sara)).andExpect(status().isOk()));
        assertThat(b.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(b.get("proposedBy").asText()).isEqualTo("ENGINE");
        mvc.perform(post("/api/plan/buckets/confirm").header("Authorization", sara)).andExpect(status().isConflict());
        jdbc.update("DELETE FROM bucket_plan WHERE family_id = ?", DemoFamily.FAMILY);
    }

    @Test
    void an_agent_can_only_save_drafts_and_cannot_confirm_them() throws Exception {
        String sara = login("sara");
        String run = openRun(sara, "PLANNER");
        JsonNode draft = read(mvc.perform(post("/api/plan/income").header("Authorization", sara)
                        .header("X-Agent-Run-Id", run).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test bonus","kind":"ONE_OFF","amount":"2,000","frequency":"ONCE","nextOn":"2026-01-01"}"""))
                .andExpect(status().isCreated()));
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        String id = draft.get("id").asText();

        mvc.perform(post("/api/plan/drafts/income/" + id + "/confirm").header("Authorization", sara).header("X-Agent-Run-Id", run))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/plan/drafts/income/" + id + "/discard").header("Authorization", sara))
                .andExpect(status().isNoContent());

        // The core wrote the audit trail, not the agent
        Integer calls = jdbc.queryForObject("SELECT count(*) FROM tool_execution WHERE agent_run_id = ?::uuid", Integer.class, run);
        assertThat(calls).isGreaterThanOrEqualTo(2);
        String tool = jdbc.queryForObject("SELECT tool_name FROM tool_execution WHERE agent_run_id = ?::uuid ORDER BY executed_at LIMIT 1",
                String.class, run);
        assertThat(tool).isEqualTo("POST /api/plan/income");
    }

    @Test
    void a_run_from_another_family_or_a_finished_run_is_refused() throws Exception {
        String sara = login("sara");
        mvc.perform(get("/api/plan/cashflow").header("Authorization", sara).header("X-Agent-Run-Id", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        String run = openRun(sara, "PLANNER");
        postJson("/api/agent-runs/" + run + "/finish", sara, "{\"status\":\"SUCCEEDED\",\"llmUsed\":false,\"fallbackReason\":\"LLM off\"}")
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/plan/cashflow").header("Authorization", sara).header("X-Agent-Run-Id", run))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent-runs").header("Authorization", sara)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fallbackReason").exists());
    }
}
