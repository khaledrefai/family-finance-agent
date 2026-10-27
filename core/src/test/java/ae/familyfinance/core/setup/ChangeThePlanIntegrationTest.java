package ae.familyfinance.core.setup;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** After setup a parent can change every part of the plan: budgets, goals, buckets, income and bills. */
@AutoConfigureMockMvc
class ChangeThePlanIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private final String tag = UUID.randomUUID().toString().substring(0, 6);
    private String parent;
    private String salary;
    private String rent;
    private String kid;
    private String goal;

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String body) throws Exception {
        req.header("Authorization", parent);
        return mvc.perform(body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String agentRun() throws Exception {
        return read(send(post("/api/agent-runs"), "{\"agent\":\"PLANNER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"t\"}"))
                .get("id").asText();
    }

    /** A family that went through setup: one salary, rent, a child, budgets, a buffer goal and confirmed buckets. */
    @BeforeEach
    void setUpFamily() throws Exception {
        parent = "Bearer " + read(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"familyName":"The Saeed Family","displayName":"Reem","username":"reem%s","password":"family-pass-1"}"""
                .formatted(tag)))).get("accessToken").asText();
        kid = read(send(post("/api/setup/members"), """
                {"role":"CHILD","displayName":"Ali","age":11,"username":"ali%s","password":"ali123","consent":true}"""
                .formatted(tag))).get("id").asText();
        salary = read(send(post("/api/plan/income"), """
                {"name":"Salary","kind":"FIXED","amount":"20,000","frequency":"MONTHLY","nextOn":"%s"}"""
                .formatted(LocalDate.now().plusDays(10)))).get("id").asText();
        rent = read(send(post("/api/plan/commitments"), """
                {"name":"Rent","kind":"RENT","amount":"6,000","frequency":"MONTHLY","nextDueOn":"%s"}"""
                .formatted(LocalDate.now()))).get("id").asText(); // due today, so due this month whatever the date
        send(post("/api/setup/budgets"), """
                {"lines":[{"category":"GROCERIES","amount":"2,000"},{"category":"TRANSPORT","amount":"500"},
                          {"category":"EATING_OUT","amount":"600"},{"category":"ENTERTAINMENT","amount":"0"}],
                 "pocket":[{"memberId":"%s","weekly":"70"}]}""".formatted(kid)).andExpect(status().isNoContent());
        goal = read(send(post("/api/setup/goals"), "{\"name\":\"Emergency buffer\",\"target\":\"30000\",\"tier\":\"SURVIVAL\"}"))
                .get("id").asText();
        send(post("/api/plan/goals/" + goal + "/pace"), "{\"monthly\":\"1500\"}").andExpect(status().isOk());
        send(post("/api/plan/buckets/confirm"), null).andExpect(status().isOk());
        send(post("/api/setup/finish"), "{}").andExpect(jsonPath("$.done").value(true));
    }

    @Test
    void budgets_and_pocket_money_can_be_changed_after_setup() throws Exception {
        JsonNode now = read(send(get("/api/setup/budgets/current"), null).andExpect(status().isOk()));
        assertThat(now.get("lines")).hasSize(4);
        assertThat(now.get("lines").get(0).get("category").asText()).isEqualTo("GROCERIES");
        assertThat(now.get("lines").get(0).get("amount").get("text").asText()).isEqualTo("AED 2,000");
        assertThat(now.get("lines").get(3).get("amount").get("minor").asLong()).isZero(); // entertainment was left out
        assertThat(now.get("lines").get(0).get("reason").asText()).startsWith("The app's guide: AED ");
        assertThat(now.get("pocket").get(0).get("weekly").get("text").asText()).isEqualTo("AED 70");

        send(post("/api/setup/budgets"), """
                {"lines":[{"category":"GROCERIES","amount":"2,400"},{"category":"TRANSPORT","amount":"500"},
                          {"category":"EATING_OUT","amount":"0"},{"category":"ENTERTAINMENT","amount":"300"}],
                 "pocket":[{"memberId":"%s","weekly":"80"}]}""".formatted(kid)).andExpect(status().isNoContent());
        JsonNode after = read(send(get("/api/setup/budgets/current"), null));
        assertThat(after.get("lines").get(0).get("amount").get("text").asText()).isEqualTo("AED 2,400");
        assertThat(after.get("lines").get(2).get("amount").get("minor").asLong()).isZero();
        assertThat(after.get("pocket").get(0).get("weekly").get("text").asText()).isEqualTo("AED 80");
        send(get("/api/budgets"), null).andExpect(jsonPath("$.lines.length()").value(3));
        // Still set up: changing the budgets doesn't send the family back to the setup steps
        send(get("/api/setup"), null).andExpect(jsonPath("$.done").value(true));
    }

    @Test
    void a_goal_can_be_changed_removed_and_a_new_one_added() throws Exception {
        LocalDate date = LocalDate.now().plusMonths(18);
        send(put("/api/plan/goals/" + goal), """
                {"name":"Rainy day fund","target":"36,000","targetDate":"%s"}""".formatted(date))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Rainy day fund"))
                .andExpect(jsonPath("$.target.text").value("AED 36,000"))
                .andExpect(jsonPath("$.targetDate").value(date.toString()))
                .andExpect(jsonPath("$.monthly.text").value("AED 1,500")); // the pace stays
        send(put("/api/plan/goals/" + goal), "{\"name\":\"Rainy day fund\",\"target\":\"36000\",\"targetDate\":\"2020-01-01\"}")
                .andExpect(status().isBadRequest());

        String trip = read(send(post("/api/setup/goals"), "{\"name\":\"Summer trip\",\"target\":\"12000\",\"tier\":\"GOOD_LIFE\"}")
                .andExpect(status().isCreated())).get("id").asText();
        send(get("/api/plan/goals"), null).andExpect(jsonPath("$.length()").value(2));
        send(delete("/api/plan/goals/" + trip), null).andExpect(status().isNoContent());
        send(get("/api/plan/goals"), null).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT status FROM goal WHERE id = ?", String.class, UUID.fromString(trip)))
                .isEqualTo("CANCELLED");
        // A child's own goal is not a family goal
        send(put("/api/plan/goals/" + UUID.randomUUID()), "{\"name\":\"x\",\"target\":\"1\"}").andExpect(status().isNotFound());
    }

    @Test
    void income_and_bills_can_be_changed_and_removed() throws Exception {
        send(put("/api/plan/income/" + salary), """
                {"name":"Reem salary","amount":"22,500","nextOn":"%s"}""".formatted(LocalDate.now().plusDays(5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Reem salary"))
                .andExpect(jsonPath("$.plannedMonthly.text").value("AED 22,500"));
        // The last income can't go: the plan needs one
        send(delete("/api/plan/income/" + salary), null).andExpect(status().isConflict());
        String bonus = read(send(post("/api/plan/income"), """
                {"name":"Side job","kind":"FIXED","amount":"1,000","frequency":"MONTHLY","nextOn":"%s"}"""
                .formatted(LocalDate.now().plusDays(3))).andExpect(status().isCreated())).get("id").asText();
        send(delete("/api/plan/income/" + bonus), null).andExpect(status().isNoContent());
        send(get("/api/plan/income"), null).andExpect(jsonPath("$.length()").value(1));

        send(put("/api/plan/commitments/" + rent), """
                {"name":"Rent","amount":"19,500","frequency":"QUARTERLY","nextDueOn":"%s","reserved":"6,500"}"""
                .formatted(LocalDate.now().plusDays(60)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.frequency").value("QUARTERLY"))
                .andExpect(jsonPath("$.amount.text").value("AED 19,500"))
                .andExpect(jsonPath("$.reserved.text").value("AED 6,500"));
        send(delete("/api/plan/commitments/" + rent), null).andExpect(status().isNoContent());
        send(get("/api/plan/commitments"), null).andExpect(jsonPath("$.length()").value(0));
        send(delete("/api/plan/commitments/" + rent), null).andExpect(status().isNotFound());
    }

    @Test
    void a_parent_types_the_buckets_in_aed_and_fun_gets_the_rest() throws Exception {
        JsonNode draft = read(send(post("/api/plan/buckets/draft/amounts"), """
                {"amounts":{"COMMITMENTS":"6000","EVERYDAY":"5,200","GOALS":"1500","INVEST_ASIDE":"1000","GIVING":"300"}}""")
                .andExpect(status().isOk()));
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("proposedBy").asText()).isEqualTo("PARENT");
        JsonNode fun = null;
        for (JsonNode b : draft.get("buckets")) {
            if (b.get("kind").asText().equals("FUN")) {
                fun = b;
            }
        }
        assertThat(fun).isNotNull();
        assertThat(fun.get("amount").get("text").asText()).isEqualTo("AED 6,000"); // 20,000 less 14,000 typed

        // The confirmed split stays until the parent confirms; keeping it drops the draft
        send(delete("/api/plan/buckets/draft"), null).andExpect(jsonPath("$.status").value("ACTIVE"));
        send(post("/api/plan/buckets/draft/amounts"), """
                {"amounts":{"COMMITMENTS":"6000","EVERYDAY":"5200","GOALS":"1500"}}""").andExpect(status().isOk());
        send(post("/api/plan/buckets/confirm"), null).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.proposedBy").value("PARENT"));

        // More than the income, or too little for the bills, is refused in words
        send(post("/api/plan/buckets/draft/amounts"), "{\"amounts\":{\"COMMITMENTS\":\"6000\",\"EVERYDAY\":\"15000\"}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("AED 1,000 more than the income")));
        send(post("/api/plan/buckets/draft/amounts"), "{\"amounts\":{\"COMMITMENTS\":\"5000\"}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Commitments needs at least")));
    }

    @Test
    void agents_cannot_change_the_plan() throws Exception {
        String run = agentRun();
        mvc.perform(put("/api/plan/goals/" + goal).header("Authorization", parent).header("X-Agent-Run-Id", run)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"target\":\"1\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/plan/commitments/" + rent).header("Authorization", parent).header("X-Agent-Run-Id", run))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/plan/income/" + salary).header("Authorization", parent).header("X-Agent-Run-Id", run)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"1\",\"nextOn\":\"2099-01-01\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/plan/buckets/draft/amounts").header("Authorization", parent).header("X-Agent-Run-Id", run)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amounts\":{\"COMMITMENTS\":\"6000\"}}"))
                .andExpect(status().isForbidden());
        // A child can't either
        String ali = "Bearer " + read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ali" + tag + "\",\"password\":\"ali123\"}"))).get("accessToken").asText();
        mvc.perform(put("/api/plan/goals/" + goal).header("Authorization", ali).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"target\":\"1\"}")).andExpect(status().isForbidden());
    }
}
