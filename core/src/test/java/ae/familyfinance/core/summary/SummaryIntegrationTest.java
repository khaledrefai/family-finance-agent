package ae.familyfinance.core.summary;

import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The monthly family summary through the real API, with the demo family's seeded month: entertainment AED 360 over
 * its AED 1,200 budget, the summer trip AED 3,600 of 12,000, Layla's headphones 40% and Adam's building set 45%.
 */
@AutoConfigureMockMvc
class SummaryIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'summary:%'");
        jdbc.update("DELETE FROM family_event WHERE kind = 'MONTH_SUMMARY'");
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

    private JsonNode parents(String token) throws Exception {
        return read(mvc.perform(get("/api/summary").header("Authorization", token)).andExpect(status().isOk()));
    }

    @Test
    void the_parents_page_has_the_months_budgets_goals_kids_and_what_to_watch() throws Exception {
        JsonNode s = parents(login("omar"));
        assertThat(s.get("month").asText()).isEqualTo(YearMonth.now(ZoneId.of("Asia/Dubai")).toString());
        assertThat(s.get("running").asBoolean()).isTrue();
        assertThat(s.get("months")).hasSize(3);
        assertThat(s.get("months").get(0).get("label").asText()).endsWith(" so far");

        // Entertainment is the one budget over; it heads the list of things to watch
        assertThat(s.get("budgetLimit").get("text").asText()).isEqualTo("AED 6,700");
        assertThat(s.get("budgetsWithin").asInt()).isEqualTo(3);
        JsonNode watch = s.get("toWatch");
        assertThat(watch.get(0).get("key").asText()).isEqualTo("budget-entertainment");
        assertThat(watch.get(0).get("text").asText()).startsWith("Entertainment is AED 360 over its AED 1,200 budget (130%)");
        // Adam logged a cola and a Robux card, both against family rules
        assertThat(watch.toString()).contains("Adam logged 2 things the family rules say no to");

        // The summer trip is the family's goal to talk about
        assertThat(s.get("wentWell").findValuesAsText("text"))
                .anyMatch(t -> t.startsWith("The summer trip is 30% saved: AED 3,600 of AED 12,000, full by "));

        // Saver of the Month: Adam at 45% of his building set beats Layla at 40% of her headphones
        assertThat(s.get("saver").get("winners").get(0).asText()).isEqualTo("Adam");
        assertThat(s.get("saver").get("percent").asInt()).isEqualTo(45);
        assertThat(s.get("kids").findValuesAsText("name")).containsExactlyInAnyOrder("Adam", "Layla");

        // Nothing is written yet, so the engine's own words show and the agent is asked for its words
        assertThat(s.get("needsWords").asBoolean()).isTrue();
        assertThat(s.get("headline").get("writtenBy").asText()).isEqualTo("ENGINE");
        assertThat(s.get("headline").get("text").asText()).contains("of the AED 6,700 family budgets spent", "summer trip 30% saved");
    }

    @Test
    void a_kids_page_has_their_own_goal_and_stars_and_no_family_money() throws Exception {
        String layla = login("layla");
        JsonNode k = read(mvc.perform(get("/api/summary/kid").header("Authorization", layla)).andExpect(status().isOk()));
        assertThat(k.get("name").asText()).isEqualTo("Layla");
        assertThat(k.get("goalName").asText()).isEqualTo("Headphones");
        assertThat(k.get("goalPercent").asInt()).isEqualTo(40);
        assertThat(k.get("starsThisMonth").asInt()).isEqualTo(40);
        assertThat(k.get("saverOfTheMonth").asBoolean()).isFalse();
        assertThat(k.get("familyGoal").get("percent").asInt()).isEqualTo(30);
        assertThat(k.get("familyGoal").has("saved")).isFalse();
        // No family figure anywhere on a kid's page
        assertThat(k.toString()).doesNotContain("1,200", "6,700", "3,600", "12,000", "Entertainment");

        // Kids see only their own page; parents can look at any of theirs
        mvc.perform(get("/api/summary/kid").param("memberId", DemoFamily.ADAM.toString()).header("Authorization", layla))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/summary").header("Authorization", layla)).andExpect(status().isForbidden());
        JsonNode adam = read(mvc.perform(get("/api/summary/kid").param("memberId", DemoFamily.ADAM.toString())
                .header("Authorization", login("sara"))).andExpect(status().isOk()));
        assertThat(adam.get("saverOfTheMonth").asBoolean()).isTrue();
        assertThat(adam.get("highlights").toString()).contains("You are Saver of the Month", "family's no list");
    }

    @Test
    void the_agents_words_are_kept_only_with_numbers_from_the_facts() throws Exception {
        String sara = login("sara");
        JsonNode s = parents(sara);
        String kid = "kid:" + DemoFamily.LAYLA;
        String body = """
                {"words": [
                  {"key": "headline", "text": "A steady month: the summer trip is 30%% saved.", "writtenBy": "LLM"},
                  {"key": "story", "text": "Entertainment went AED 999 over.", "writtenBy": "LLM"},
                  {"key": "%s", "text": "Layla, your headphones jar is 40%% full. Keep going.", "writtenBy": "LLM"},
                  {"key": "%s", "text": "Layla, the family spent AED 6,700.", "writtenBy": "LLM"}
                ]}""".formatted(kid, "kid:" + DemoFamily.ADAM);
        JsonNode after = read(mvc.perform(post("/api/summary/words").header("Authorization", sara)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()));

        assertThat(after.get("headline").get("text").asText()).isEqualTo("A steady month: the summer trip is 30% saved.");
        assertThat(after.get("headline").get("writtenBy").asText()).isEqualTo("LLM");
        // A made-up number: the engine's sentence stays
        assertThat(after.get("story").get("writtenBy").asText()).isEqualTo("ENGINE");
        assertThat(after.get("story").get("text").asText()).isEqualTo(s.get("story").get("text").asText());
        // A family figure in a kid's words is not in the kid's facts, so it is dropped
        JsonNode adam = read(mvc.perform(get("/api/summary/kid").param("memberId", DemoFamily.ADAM.toString())
                .header("Authorization", sara)).andExpect(status().isOk()));
        assertThat(adam.get("camel").get("writtenBy").asText()).isEqualTo("ENGINE");
        JsonNode layla = read(mvc.perform(get("/api/summary/kid").header("Authorization", login("layla"))).andExpect(status().isOk()));
        assertThat(layla.get("camel").get("text").asText()).isEqualTo("Layla, your headphones jar is 40% full. Keep going.");
        assertThat(after.get("needsWords").asBoolean()).isTrue();

        // Kids can't write the words
        mvc.perform(post("/api/summary/words").header("Authorization", login("adam"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());

        // When the month moves on (a new star for Layla), her old words no longer match and the engine's show again
        jdbc.update("INSERT INTO points_entry (family_id, member_id, points, reason) VALUES (?, ?, 5, 'Test')",
                DemoFamily.FAMILY, DemoFamily.LAYLA);
        try {
            JsonNode moved = read(mvc.perform(get("/api/summary/kid").header("Authorization", login("layla"))).andExpect(status().isOk()));
            assertThat(moved.get("camel").get("writtenBy").asText()).isEqualTo("ENGINE");
            assertThat(moved.get("starsThisMonth").asInt()).isEqualTo(45);
        } finally {
            jdbc.update("DELETE FROM points_entry WHERE reason = 'Test'");
        }
    }

    @Test
    void months_are_this_one_or_the_two_before_and_a_finished_month_reads_in_the_past() throws Exception {
        String omar = login("omar");
        YearMonth now = YearMonth.now(ZoneId.of("Asia/Dubai"));
        JsonNode last = read(mvc.perform(get("/api/summary").param("month", now.minusMonths(1).toString())
                .header("Authorization", omar)).andExpect(status().isOk()));
        assertThat(last.get("running").asBoolean()).isFalse();
        assertThat(last.get("periodLabel").asText()).isEqualTo(
                last.get("monthLabel").asText() + " " + now.minusMonths(1).getYear());
        mvc.perform(get("/api/summary").param("month", now.plusMonths(1).toString()).header("Authorization", omar))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/summary").param("month", now.minusMonths(3).toString()).header("Authorization", omar))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/summary").param("month", "October").header("Authorization", omar))
                .andExpect(status().isBadRequest());
    }

    @Test
    void the_demo_button_sends_the_summary_to_everyone_once_a_day() throws Exception {
        String omar = login("omar");
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM family_event", Long.class);
        try {
            sendsOnce(omar);
        } finally {
            jdbc.update("DELETE FROM family_event WHERE id > ?", before); // the bill alerts the check sent too
        }
    }

    private void sendsOnce(String omar) throws Exception {
        JsonNode first = read(mvc.perform(post("/api/alerts/check").param("summary", "true").header("Authorization", omar))
                .andExpect(status().isOk()));
        assertThat(first.get("sent").toString()).contains("so far");
        JsonNode again = read(mvc.perform(post("/api/alerts/check").param("summary", "true").header("Authorization", omar))
                .andExpect(status().isOk()));
        assertThat(again.get("sent").toString()).doesNotContain("so far");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM family_event WHERE kind = 'MONTH_SUMMARY'", Integer.class))
                .isEqualTo(1);
    }
}
