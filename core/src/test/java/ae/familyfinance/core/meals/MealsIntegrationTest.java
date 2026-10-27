package ae.familyfinance.core.meals;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The weekly dinners through the real API, with the demo family's seed: everyone eats halal, Layla doesn't like
 * mushrooms, Adam has a peanut rule, groceries AED 3,000 and eating out AED 1,500 a month.
 */
@AutoConfigureMockMvc
class MealsIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM meal_plan");
        jdbc.update("DELETE FROM family_event WHERE kind = 'MEALS_PLANNED'");
        jdbc.update("UPDATE member_food_profile SET halal = true, vegetarian = false, vegan = false, dislikes = '' WHERE member_id <> ?",
                DemoFamily.LAYLA);
        jdbc.update("UPDATE member_food_profile SET halal = true, vegetarian = false, vegan = false, dislikes = 'mushrooms' WHERE member_id = ?",
                DemoFamily.LAYLA);
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

    private ResultActions send(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        return mvc.perform(req.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String openRun(String token) throws Exception {
        return read(send(post("/api/agent-runs"), token, "{\"agent\":\"PLANNER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"dinners\"}")
                .andExpect(status().isCreated())).get("id").asText();
    }

    @Test
    void the_board_shows_the_week_the_grocery_money_who_eats_what_and_which_dinners_suit() throws Exception {
        JsonNode b = read(mvc.perform(get("/api/meals").header("Authorization", login("omar"))).andExpect(status().isOk()));

        assertThat(b.get("weekStart").asText()).isEqualTo(LocalDate.now(clock).toString());
        assertThat(b.get("days")).hasSize(7);
        assertThat(b.get("groceryLimit").get("text").asText()).isEqualTo("AED 3,000");
        assertThat(b.get("groceryWeek").get("text").asText()).isEqualTo("AED 690");
        assertThat(b.get("forDinners").get("text").asText()).isEqualTo("AED 345");
        assertThat(b.get("profiles").findValuesAsText("name")).containsExactly("Omar", "Sara", "Layla", "Adam");
        assertThat(b.get("profiles").findValues("halal")).allMatch(JsonNode::asBoolean);
        assertThat(b.get("profiles").get(2).get("dislikes").toString()).isEqualTo("[\"mushrooms\"]");
        assertThat(b.get("profiles").get(3).get("allergies").toString()).isEqualTo("[\"peanuts\"]");
        assertThat(b.get("recipes")).hasSize(18);
        assertThat(b.get("recipes").get(0).get("suits").asBoolean()).isTrue();  // dinners that suit come first, cheapest first
        String recipes = b.get("recipes").toString();
        assertThat(recipes).contains("It has peanuts, and Adam must not have peanuts.", "Layla doesn't like mushrooms.");
        assertThat(b.get("deliveries")).hasSize(5);
        assertThat(b.get("saved").isNull()).isTrue();
        assertThat(b.get("pricesFrom").asText()).isEqualTo("2026-10-26");
    }

    @Test
    void a_plan_with_two_delivery_nights_and_the_planners_pick_checked() throws Exception {
        String omar = login("omar");
        String run = openRun(omar);
        LocalDate today = LocalDate.now(clock);
        JsonNode p = read(send(post("/api/meals/plan").header("X-Agent-Run-Id", run), omar,
                "{\"keys\":[\"bolognese\",\"satay-noodles\"],\"deliveryDays\":[\"" + today.plusDays(1) + "\",\"" + today.plusDays(3) + "\"]}")
                .andExpect(status().isOk()));

        assertThat(p.get("nights")).hasSize(7);
        assertThat(p.get("nights").get(0).get("key").asText()).isEqualTo("bolognese");
        assertThat(p.get("nights").get(1).get("kind").asText()).isEqualTo("DELIVERY");
        assertThat(p.get("nights").get(3).get("kind").asText()).isEqualTo("DELIVERY");
        assertThat(p.get("delivery").get("text").asText()).isEqualTo("AED 160");
        assertThat(p.get("leftOut").get(0).asText()).isEqualTo("Left out Chicken satay noodles: It has peanuts, and Adam must not have peanuts.");
        assertThat(p.toString()).doesNotContain("satay-noodles\"", "creamy-chicken-pasta");
        assertThat(p.get("list")).isNotEmpty();
        assertThat(p.get("list").findValues("halalMark").stream().filter(JsonNode::asBoolean)).isNotEmpty();
        assertThat(p.get("facts").toString()).contains("from the eating-out budget", "halal mark", "not live");
        long shopping = p.get("shopping").get("minor").asLong();
        assertThat(p.get("weekLeft").get("minor").asLong()).isEqualTo(69_000 - shopping);

        send(post("/api/meals/plan"), login("layla"), "{}").andExpect(status().isForbidden());
        mvc.perform(get("/api/meals").header("Authorization", login("adam"))).andExpect(status().isForbidden());
    }

    @Test
    void a_parent_saves_the_week_and_the_kids_see_whats_for_dinner_without_prices() throws Exception {
        String sara = login("sara");
        String run = openRun(sara);
        send(put("/api/meals/saved").header("X-Agent-Run-Id", run), sara, "{}").andExpect(status().isForbidden());

        mvc.perform(get("/api/meals/saved").header("Authorization", login("layla"))).andExpect(status().isNoContent());
        JsonNode saved = read(send(put("/api/meals/saved"), sara, "{\"keys\":[\"lentil-soup\"]}").andExpect(status().isOk()));
        assertThat(saved.get("nights")).hasSize(7);
        assertThat(saved.get("nights").get(0).get("name").asText()).isEqualTo("Red lentil soup with bread");
        assertThat(saved.get("savedBy").asText()).isEqualTo("Sara");
        assertThat(saved.get("plan").get("list")).isNotEmpty();

        JsonNode kid = read(mvc.perform(get("/api/meals/saved").header("Authorization", login("adam"))).andExpect(status().isOk()));
        assertThat(kid.get("nights").get(0).get("name").asText()).isEqualTo("Red lentil soup with bread");
        assertThat(kid.get("plan").isNull()).isTrue();
        assertThat(kid.toString()).doesNotContain("AED");

        Integer events = jdbc.queryForObject("SELECT count(*) FROM family_event WHERE kind = 'MEALS_PLANNED'", Integer.class);
        assertThat(events).isEqualTo(1);
        String body = jdbc.queryForObject("SELECT payload->>'dinners' FROM family_event WHERE kind = 'MEALS_PLANNED'", String.class);
        assertThat(body).startsWith("Tonight: Red lentil soup with bread. Then ");

        // Saving again replaces the week
        send(put("/api/meals/saved"), sara, "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_plan", Integer.class)).isEqualTo(1);
        // The board shows the saved week
        JsonNode b = read(mvc.perform(get("/api/meals").header("Authorization", sara)).andExpect(status().isOk()));
        assertThat(b.get("saved").get("shopping").get("minor").asLong()).isPositive();

        send(put("/api/meals/saved"), login("layla"), "{}").andExpect(status().isForbidden());
    }

    @Test
    void a_parent_changes_who_eats_what_and_the_plan_follows() throws Exception {
        String omar = login("omar");
        String body = "{\"members\":[{\"memberId\":\"" + DemoFamily.SARA + "\",\"halal\":true,\"vegetarian\":true,\"vegan\":false,"
                      + "\"dislikes\":\"Okra, aubergine\"}]}";
        String run = openRun(omar);
        send(put("/api/meals/profiles").header("X-Agent-Run-Id", run), omar, body).andExpect(status().isForbidden());
        JsonNode profiles = read(send(put("/api/meals/profiles"), omar, body).andExpect(status().isOk()));
        assertThat(profiles.get(1).get("name").asText()).isEqualTo("Sara");
        assertThat(profiles.get(1).get("vegetarian").asBoolean()).isTrue();
        assertThat(profiles.get(1).get("dislikes").toString()).isEqualTo("[\"okra\",\"aubergine\"]");

        JsonNode p = read(send(post("/api/meals/plan"), omar, "{\"keys\":[\"kofta-rice\"]}").andExpect(status().isOk()));
        assertThat(p.get("leftOut").get(0).asText()).isEqualTo("Left out Beef kofta with rice and salad: Sara is vegetarian, and it has meat.");
        assertThat(p.get("list").findValuesAsText("key")).doesNotContain("beef-mince", "chicken-whole", "chicken-thighs", "okra");

        String stranger = "{\"members\":[{\"memberId\":\"00000000-0000-0000-0000-000000000999\",\"halal\":true,\"vegetarian\":false,"
                          + "\"vegan\":false,\"dislikes\":\"\"}]}";
        send(put("/api/meals/profiles"), omar, stranger).andExpect(status().isNotFound());
        send(put("/api/meals/profiles"), login("layla"), body).andExpect(status().isForbidden());
    }
}
