package ae.familyfinance.core.rules;

import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Week 4 through the real API and the seeded Haddad family: rules, the request check, overspend, ways to save. */
@AutoConfigureMockMvc
class RulesBudgetsAdviceIntegrationTest extends PostgresIntegrationTest {

    static final String SEED_LIMIT = "Anything over AED 50 needs a parent";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void putTheSeedBack() {
        jdbc.update("DELETE FROM family_rule WHERE family_id = ? AND source <> 'SEED'", DemoFamily.FAMILY);
        jdbc.update("UPDATE family_rule SET status = 'ACTIVE', archived_at = NULL WHERE family_id = ?", DemoFamily.FAMILY);
        jdbc.update("DELETE FROM saving_tip_decision WHERE family_id = ?", DemoFamily.FAMILY);
        jdbc.update("DELETE FROM saving_tip_words WHERE family_id = ?", DemoFamily.FAMILY);
        jdbc.update("DELETE FROM spending_request WHERE family_id = ? AND status = 'BLOCKED'", DemoFamily.FAMILY);
    }

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

    private ResultActions postJson(String url, String token, String run, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).header("X-Agent-Run-Id", run)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String openRun(String token) throws Exception {
        return read(postJson("/api/agent-runs", token,
                "{\"agent\":\"GATEKEEPER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"test\"}")
                .andExpect(status().isCreated())).get("id").asText();
    }

    // ------------------------------------------------------------------ rules

    @Test
    void the_seeded_rules_are_on_and_a_child_sees_only_their_own() throws Exception {
        JsonNode all = read(mvc.perform(get("/api/rules").header("Authorization", login("sara"))).andExpect(status().isOk()));
        assertThat(all.findValuesAsText("label")).contains("No peanuts for Adam", SEED_LIMIT);
        assertThat(all.findValuesAsText("status")).containsOnly("ACTIVE");

        JsonNode layla = read(mvc.perform(get("/api/rules").header("Authorization", login("layla"))).andExpect(status().isOk()));
        assertThat(layla.findValuesAsText("label")).contains(SEED_LIMIT).doesNotContain("No peanuts for Adam");
    }

    @Test
    void an_agent_drafts_a_rule_and_only_a_parent_turns_it_on() throws Exception {
        String omar = login("omar");
        String run = openRun(omar);
        JsonNode draft = read(postJson("/api/rules/drafts", omar, run, """
                {"kind":"APPROVAL_LIMIT","limit":"100","said":"anything over AED 100 needs me","draftedBy":"RULES"}""")
                .andExpect(status().isCreated()));
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("label").asText()).isEqualTo("Anything over AED 100 needs me");
        assertThat(draft.get("chips").toString()).contains("AED 100", "All the kids");
        String id = draft.get("id").asText();

        // While it is a draft, the old AED 50 limit still decides
        JsonNode before = read(postJson("/api/requests/assess", login("layla"), "{\"amount\":\"60\",\"title\":\"Book\"}"));
        assertThat(before.get("needsParent").asBoolean()).isTrue();

        postJson("/api/rules/" + id + "/confirm", omar, run, "").andExpect(status().isForbidden());
        postJson("/api/rules/" + id + "/confirm", login("layla"), "").andExpect(status().isForbidden());
        JsonNode on = read(postJson("/api/rules/" + id + "/confirm", omar, "").andExpect(status().isOk()));
        assertThat(on.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(on.get("replaces").toString()).contains(SEED_LIMIT);

        // A parent is needed anyway when Layla's wallet can't cover the book. Other tests may have paid from it
        // (an approved AED 300 pair of headphones can leave AED 1), so put the AED 60 in first.
        jdbc.update("""
                INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description)
                SELECT id, 'ADJUSTMENT', 6000, 'Top-up for a test (demo money)' FROM demo_wallet WHERE member_id = ?
                """, DemoFamily.LAYLA);
        JsonNode after = read(postJson("/api/requests/assess", login("layla"),
                "{\"amount\":\"60\",\"title\":\"Book\",\"category\":\"BOOKS_AND_STATIONERY\"}"));
        assertThat(after.get("approvalLimit").get("text").asText()).isEqualTo("AED 100");
        assertThat(after.get("needsParent").asBoolean()).isFalse();
    }

    @Test
    void a_child_cannot_write_rules_and_a_bad_draft_is_refused() throws Exception {
        postJson("/api/rules/drafts", login("layla"), "{\"kind\":\"MONTHLY_CAP\",\"limit\":\"500\"}").andExpect(status().isForbidden());
        String sara = login("sara");
        postJson("/api/rules/drafts", sara, "{\"kind\":\"CATEGORY_LIMIT\",\"limit\":\"50\"}").andExpect(status().isBadRequest());
        postJson("/api/rules/drafts", sara, "{\"kind\":\"MONTHLY_CAP\",\"limit\":\"lots\"}").andExpect(status().isBadRequest());
        postJson("/api/rules/drafts", sara, """
                {"kind":"BLOCK_ALLERGEN","allergen":"SESAME","forChild":"%s"}""".formatted(DemoFamily.OMAR))
                .andExpect(status().isBadRequest());
    }

    @Test
    void turning_off_a_draft_removes_it_and_turning_off_a_rule_archives_it() throws Exception {
        String sara = login("sara");
        String id = read(postJson("/api/rules/drafts", sara, """
                {"kind":"CATEGORY_LIMIT","category":"SNACKS","limit":"40","forChild":"%s"}""".formatted(DemoFamily.LAYLA))
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/rules/" + id + "/off", sara, "").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM family_rule WHERE id = ?::uuid", Long.class, id)).isZero();

        String games = jdbc.queryForObject("SELECT id::text FROM family_rule WHERE family_id = ? AND category = 'IN_GAME_PURCHASES'",
                String.class, DemoFamily.FAMILY);
        postJson("/api/rules/" + games + "/off", sara, "").andExpect(status().isNoContent());
        JsonNode robux = read(postJson("/api/requests/assess", login("adam"),
                "{\"amount\":\"20\",\"title\":\"Robux card\",\"category\":\"IN_GAME_PURCHASES\"}"));
        assertThat(robux.get("blocked").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ the request check

    @Test
    void adam_asking_for_peanut_snacks_is_blocked_and_told_why() throws Exception {
        String adam = login("adam");
        JsonNode a = read(postJson("/api/requests/assess", adam,
                "{\"amount\":\"8\",\"title\":\"Peanut butter cups\",\"category\":\"SNACKS\"}").andExpect(status().isOk()));
        assertThat(a.get("blocked").asBoolean()).isTrue();
        assertThat(a.get("needsParent").asBoolean()).isFalse();
        assertThat(a.get("rules").get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(a.get("facts").toString()).contains("Family rule: No peanuts for Adam").doesNotContain("Free until");

        JsonNode request = read(postJson("/api/requests", adam, """
                {"kind":"ASK_TO_BUY","title":"Peanut butter cups","amountMinor":800,"category":"SNACKS"}""")
                .andExpect(status().isCreated()));
        assertThat(request.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(request.get("agentSummary").asText()).contains("peanut");
        // Blocked by the rule: no parent can approve it afterwards
        postJson("/api/requests/" + request.get("id").asText() + "/approve", login("sara"), "").andExpect(status().isConflict());
    }

    @Test
    void cola_is_blocked_for_adam_but_not_for_layla() throws Exception {
        String body = "{\"amount\":\"3\",\"title\":\"Cola\",\"category\":\"SUGARY_DRINKS\"}";
        assertThat(read(postJson("/api/requests/assess", login("adam"), body)).get("blocked").asBoolean()).isTrue();
        assertThat(read(postJson("/api/requests/assess", login("layla"), body)).get("blocked").asBoolean()).isFalse();
    }

    @Test
    void laylas_headphones_still_need_a_parent_and_the_parent_sees_the_rule_hits() throws Exception {
        JsonNode a = read(postJson("/api/requests/assess", login("omar"), """
                {"amount":"300","requesterId":"%s","title":"Wireless headphones","paidFromAccountId":"00000000-0000-0000-0000-0000000000e3"}"""
                .formatted(DemoFamily.LAYLA)).andExpect(status().isOk()));
        assertThat(a.get("needsParent").asBoolean()).isTrue();
        assertThat(a.get("blocked").asBoolean()).isFalse();
        assertThat(a.get("rules").get("hits").findValuesAsText("rule")).contains(SEED_LIMIT);
        assertThat(a.get("card").get("fits").asBoolean()).isFalse();                // the week 2 card warning still shows
    }

    // ------------------------------------------------------------------ overspend

    @Test
    void entertainment_is_over_its_budget_this_month() throws Exception {
        JsonNode m = read(mvc.perform(get("/api/budgets").header("Authorization", login("sara"))).andExpect(status().isOk()));
        JsonNode fun = null;
        for (JsonNode l : m.get("lines")) {
            if ("ENTERTAINMENT".equals(l.get("category").asText())) {
                fun = l;
            }
        }
        assertThat(fun).isNotNull();
        assertThat(fun.get("spent").get("minor").asLong()).isEqualTo(156_000);
        assertThat(fun.get("usual").get("minor").asLong()).isEqualTo(152_000);
        assertThat(fun.get("state").asText()).isEqualTo("OVER");
        assertThat(m.get("alerts").get(0).get("alert").asText()).startsWith("Entertainment is at 130% of its AED 1,200 budget");
        mvc.perform(get("/api/budgets").header("Authorization", login("layla"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ ways to save

    @Test
    void ways_to_save_ranks_outings_first_and_a_parent_can_dismiss_or_snooze() throws Exception {
        String sara = login("sara");
        JsonNode card = read(mvc.perform(get("/api/ways-to-save").header("Authorization", sara)).andExpect(status().isOk()));
        assertThat(card.get("tips").findValuesAsText("key")).containsExactly("outings", "subscriptions", "rent");
        assertThat(card.get("needsWords").asBoolean()).isTrue();
        assertThat(card.get("tips").get(0).get("text").asText()).contains("AED 1,520", "AED 3,840");

        String run = openRun(sara);
        postJson("/api/ways-to-save/outings/dismiss", sara, run, "").andExpect(status().isForbidden());
        JsonNode after = read(postJson("/api/ways-to-save/outings/dismiss", sara, "").andExpect(status().isOk()));
        assertThat(after.get("tips").findValuesAsText("key")).containsExactly("subscriptions", "rent", "telecom");
        JsonNode snoozed = read(postJson("/api/ways-to-save/rent/snooze", sara, "").andExpect(status().isOk()));
        assertThat(snoozed.get("tips").findValuesAsText("key")).containsExactly("subscriptions", "telecom");
        assertThat(snoozed.get("hidden").asInt()).isEqualTo(2);
        mvc.perform(get("/api/ways-to-save").header("Authorization", login("adam"))).andExpect(status().isForbidden());
    }

    @Test
    void the_agents_words_are_kept_only_when_their_numbers_are_the_engines() throws Exception {
        String sara = login("sara");
        String run = openRun(sara);
        JsonNode card = read(postJson("/api/ways-to-save/words", sara, run, """
                {"tips":[
                  {"key":"outings","text":"Outings cost AED 1,520 a month lately. Sticking to AED 1,200 frees about AED 3,840 a year.","writtenBy":"LLM"},
                  {"key":"rent","text":"Rent takes 35% of your income.","writtenBy":"LLM"}]}""").andExpect(status().isOk()));
        JsonNode outings = card.get("tips").get(0);
        assertThat(outings.get("writtenBy").asText()).isEqualTo("LLM");
        assertThat(outings.get("text").asText()).startsWith("Outings cost AED 1,520");
        JsonNode rent = card.get("tips").get(2);
        assertThat(rent.get("writtenBy").asText()).isEqualTo("ENGINE");          // 35% is not the engine's number
        assertThat(rent.get("text").asText()).startsWith("Rent is 29%");
    }
}
