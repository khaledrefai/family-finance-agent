package ae.familyfinance.core.setup;

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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A real new family, from signing up to its first confirmed plan, through the API. */
@AutoConfigureMockMvc
class SetupIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** Usernames are unique across all families, and the test database is shared. */
    private final String tag = UUID.randomUUID().toString().substring(0, 6);

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions postJson(String url, String token, String body) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(token == null ? req : req.header("Authorization", token));
    }

    private ResultActions getAs(String url, String token) throws Exception {
        return mvc.perform(get(url).header("Authorization", token));
    }

    private String login(String username, String password) throws Exception {
        return "Bearer " + read(postJson("/api/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private String signUp(String who) throws Exception {
        JsonNode r = read(postJson("/api/auth/signup", null, """
                {"familyName":"The Nasser Family","displayName":"Huda","username":"%s","password":"family-pass-1",
                 "email":"huda@example.com"}""".formatted(who)).andExpect(status().isCreated()));
        assertThat(r.get("role").asText()).isEqualTo("PARENT");
        return "Bearer " + r.get("accessToken").asText();
    }

    @Test
    void a_new_family_goes_from_sign_up_to_a_confirmed_first_plan() throws Exception {
        String huda = signUp("huda" + tag);
        getAs("/api/me", huda).andExpect(jsonPath("$.familyName").value("The Nasser Family"))
                .andExpect(jsonPath("$.setupDone").value(false));
        getAs("/api/setup", huda).andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("FAMILY")).andExpect(jsonPath("$.parents").value(1));

        // The family: the other parent, and two kids with their own logins and the parent's consent
        postJson("/api/setup/members", huda, """
                {"role":"PARENT","displayName":"Khalid","username":"khalid%s","password":"family-pass-2"}""".formatted(tag))
                .andExpect(status().isCreated());
        JsonNode mariam = read(postJson("/api/setup/members", huda, """
                {"role":"CHILD","displayName":"Mariam","age":12,"username":"mariam%s","password":"mariam1","consent":true,
                 "goalName":"Bike","goalPrice":"450"}""".formatted(tag)).andExpect(status().isCreated()));
        assertThat(mariam.get("ageBand").asText()).isEqualTo("PRETEEN_10_12");
        postJson("/api/setup/members", huda, """
                {"role":"CHILD","displayName":"Yousef","age":7,"username":"yousef%s","password":"yousef1","consent":true}"""
                .formatted(tag)).andExpect(status().isCreated());
        getAs("/api/setup", huda).andExpect(jsonPath("$.next").value("INCOME")).andExpect(jsonPath("$.kids").value(2));

        // The kids can log in straight away, see only their own things, and can't set up the family
        String mariamToken = login("mariam" + tag, "mariam1");
        getAs("/api/setup", mariamToken).andExpect(status().isForbidden());
        getAs("/api/wallet", mariamToken).andExpect(status().isOk());

        // Income (the plan API) and one big bill
        UUID khalid = jdbc.queryForObject("SELECT id FROM member WHERE username = ?", UUID.class, "khalid" + tag);
        postJson("/api/plan/income", huda, """
                {"memberId":"%s","name":"Khalid salary","kind":"FIXED","amount":"24,000","frequency":"MONTHLY","nextOn":"%s"}"""
                .formatted(khalid, LocalDate.now().plusDays(10))).andExpect(status().isCreated());
        postJson("/api/plan/commitments", huda, """
                {"name":"Rent","kind":"RENT","amount":"8,000","frequency":"MONTHLY","nextDueOn":"%s"}"""
                .formatted(LocalDate.now().plusDays(12))).andExpect(status().isCreated());
        getAs("/api/setup", huda).andExpect(jsonPath("$.next").value("BUDGETS"));

        // First budgets: the engine's starting amounts, which the parent changes and confirms
        JsonNode starter = read(getAs("/api/setup/budgets", huda).andExpect(status().isOk()));
        assertThat(starter.get("cut").asBoolean()).isFalse();
        assertThat(starter.get("lines").get(0).get("amount").get("text").asText()).isEqualTo("AED 2,800"); // 2×900 + 2×500
        assertThat(starter.get("pocket")).hasSize(2);
        assertThat(starter.get("pocket").get(0).get("name").asText()).isEqualTo("Mariam");
        assertThat(starter.get("pocket").get(0).get("weekly").get("text").asText()).isEqualTo("AED 80");
        String mariamId = mariam.get("id").asText();
        String yousefId = starter.get("pocket").get(1).get("memberId").asText();
        postJson("/api/setup/budgets", huda, """
                {"lines":[{"category":"GROCERIES","amount":"3,000"},{"category":"TRANSPORT","amount":"1000"},
                          {"category":"EATING_OUT","amount":"1200"},{"category":"ENTERTAINMENT","amount":"0"}],
                 "pocket":[{"memberId":"%s","weekly":"75"},{"memberId":"%s","weekly":"50"}]}""".formatted(mariamId, yousefId))
                .andExpect(status().isNoContent());
        UUID family = jdbc.queryForObject("SELECT family_id FROM member WHERE id = ?", UUID.class, UUID.fromString(mariamId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget WHERE family_id = ?", Integer.class, family)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT bucket FROM budget WHERE family_id = ? AND category = 'EATING_OUT'",
                String.class, family)).isEqualTo("FUN");
        getAs("/api/setup", huda).andExpect(jsonPath("$.next").value("PLAN"));

        // The first goal, sized by the engine, and its pace from the plan options
        JsonNode buffer = read(getAs("/api/setup/buffer", huda).andExpect(status().isOk()));
        String target = buffer.get("target").get("minor").asText();
        JsonNode goal = read(postJson("/api/setup/goals", huda, """
                {"name":"Emergency buffer","target":"%s","tier":"SURVIVAL"}""".formatted(Long.parseLong(target) / 100))
                .andExpect(status().isCreated()));
        JsonNode options = read(postJson("/api/plan/options", huda, "{\"goalId\":\"" + goal.get("id").asText() + "\"}")
                .andExpect(status().isOk()));
        JsonNode steady = options.get("options").get(1);
        postJson("/api/plan/goals/" + goal.get("id").asText() + "/pace", huda,
                "{\"monthly\":\"" + steady.get("monthly").get("minor").asLong() / 100 + "\"}").andExpect(status().isOk());

        // Finishing before the buckets are confirmed is refused
        postJson("/api/setup/finish", huda, "{}").andExpect(status().isConflict());

        // The buckets: a draft (the Planner's, through an agent run), then the parent's Confirm
        String run = read(postJson("/api/agent-runs", huda,
                "{\"agent\":\"PLANNER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"first plan\"}")
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode shares = read(getAs("/api/plan/buckets/suggestion", huda).andExpect(status().isOk()));
        mvc.perform(post("/api/plan/buckets/draft").header("Authorization", huda).header("X-Agent-Run-Id", run)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shares\":" + shares + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.survivalCovered").value(true));
        mvc.perform(post("/api/plan/buckets/confirm").header("Authorization", huda).header("X-Agent-Run-Id", run))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/plan/buckets/confirm").header("Authorization", huda)).andExpect(status().isOk());

        getAs("/api/setup", huda).andExpect(jsonPath("$.next").value("FINISH"));
        postJson("/api/setup/finish", huda, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true)).andExpect(jsonPath("$.next").value("DONE"));
        getAs("/api/me", huda).andExpect(jsonPath("$.setupDone").value(true));

        // The kids' goal and the family's plan are the family's own: the demo family's buckets are untouched
        assertThat(jdbc.queryForObject("SELECT target_minor FROM goal WHERE member_id = ?", Long.class,
                UUID.fromString(mariamId))).isEqualTo(45_000L);
        getAs("/api/plan/buckets", huda).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.income.text").value("AED 24,000"));
    }

    @Test
    void a_child_account_needs_the_parent_consent_and_an_age_from_6_to_17() throws Exception {
        String p = signUp("p1" + tag);
        postJson("/api/setup/members", p, """
                {"role":"CHILD","displayName":"Sami","age":10,"username":"sami%s","password":"sami12"}""".formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("consent")));
        postJson("/api/setup/members", p, """
                {"role":"CHILD","displayName":"Sami","age":4,"username":"sami%s","password":"sami12","consent":true}"""
                .formatted(tag)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE username = ?", Integer.class, "sami" + tag)).isZero();
    }

    @Test
    void usernames_are_unique_and_passwords_have_a_minimum() throws Exception {
        signUp("dup" + tag);
        postJson("/api/auth/signup", null, """
                {"displayName":"Other","username":"dup%s","password":"family-pass-1"}""".formatted(tag))
                .andExpect(status().isConflict());
        postJson("/api/auth/signup", null, """
                {"displayName":"Short","username":"short%s","password":"abc"}""".formatted(tag))
                .andExpect(status().isBadRequest());
        // Demo usernames are taken too
        postJson("/api/auth/signup", null, """
                {"displayName":"Omar","username":"omar","password":"family-pass-1"}""")
                .andExpect(status().isConflict());
        // No family name given: one is made from the parent's name
        JsonNode r = read(postJson("/api/auth/signup", null, """
                {"displayName":"Noor","username":"noor%s","password":"family-pass-1"}""".formatted(tag))
                .andExpect(status().isCreated()));
        getAs("/api/me", "Bearer " + r.get("accessToken").asText()).andExpect(jsonPath("$.familyName").value("The Noor family"));
    }

    @Test
    void a_family_has_at_most_two_parents_and_agents_cannot_add_people() throws Exception {
        String p = signUp("two" + tag);
        postJson("/api/setup/members", p, """
                {"role":"PARENT","displayName":"B","username":"b%s","password":"family-pass-2"}""".formatted(tag))
                .andExpect(status().isCreated());
        postJson("/api/setup/members", p, """
                {"role":"PARENT","displayName":"C","username":"c%s","password":"family-pass-3"}""".formatted(tag))
                .andExpect(status().isConflict());
        String run = read(postJson("/api/agent-runs", p,
                "{\"agent\":\"PLANNER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"t\"}")).get("id").asText();
        mvc.perform(post("/api/setup/members").header("Authorization", p).header("X-Agent-Run-Id", run)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"role":"CHILD","displayName":"D","age":8,"username":"d%s","password":"dddddd","consent":true}"""
                                .formatted(tag)))
                .andExpect(status().isForbidden());
        // Budgets need income first
        getAs("/api/setup/budgets", p).andExpect(status().isConflict());
    }

    @Test
    void the_demo_family_is_already_set_up() throws Exception {
        String omar = login("omar", DemoFamily.PASSWORD);
        getAs("/api/setup", omar).andExpect(jsonPath("$.done").value(true)).andExpect(jsonPath("$.next").value("DONE"));
        getAs("/api/me", omar).andExpect(jsonPath("$.setupDone").value(true));
    }
}
