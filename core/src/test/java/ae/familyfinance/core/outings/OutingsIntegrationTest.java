package ae.familyfinance.core.outings;

import ae.familyfinance.core.pricing.PricingService;
import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Week 7 through the real API, with the demo family's seeded numbers: the fun budget is AED 325 over this month, the
 * family's usual outing is about AED 374, and SerpApi's events search is replaced by a recorded answer.
 * (The outings in a plan depend on the month the test runs in, so it checks the rules, not the names.)
 */
@AutoConfigureMockMvc
class OutingsIntegrationTest extends PostgresIntegrationTest {

    /** A recorded Google Events answer; {@link #fail} makes it answer nothing, like a timeout. */
    static class ReplayEvents implements EventsSource {
        final List<String> searches = new ArrayList<>();
        boolean fail;

        @Override
        public String engine() {
            return "google_events";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public synchronized Optional<String> search(String query) {
            searches.add(query);
            if (fail) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ClassPathResource("outings/google-events-dubai.json").getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @TestConfiguration
    static class Replay {
        @Bean
        @Primary
        ReplayEvents replayEvents() {
            return new ReplayEvents();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReplayEvents replay;
    @Autowired PricingService pricing;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM price_search_cache WHERE engine = 'google_events'");
        jdbc.update("DELETE FROM outing_plan");
        jdbc.update("DELETE FROM family_event WHERE kind = 'OUTINGS_PLANNED'");
        replay.searches.clear();
        replay.fail = false;
        pricing.useMode(PricingService.Mode.LIVE);
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
        return read(send(post("/api/agent-runs"), token, "{\"agent\":\"PLANNER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"outings\"}")
                .andExpect(status().isCreated())).get("id").asText();
    }

    @Test
    void the_board_shows_the_fun_budget_over_the_usual_outing_without_subscriptions_and_each_outings_family_cost()
            throws Exception {
        JsonNode b = read(mvc.perform(get("/api/outings").header("Authorization", login("omar"))).andExpect(status().isOk()));

        assertThat(b.get("budget").get("text").asText()).isEqualTo("AED 1,200");
        assertThat(b.get("spent").get("text").asText()).isEqualTo("AED 1,525");
        assertThat(b.get("over").get("text").asText()).isEqualTo("AED 325");
        assertThat(b.get("leftThisMonth").get("minor").asLong()).isZero();
        // 12 family outings in the 3 months before this one; the Shahid VIP subscription is not an outing
        assertThat(b.get("outingsCounted").asInt()).isEqualTo(12);
        assertThat(b.get("usualOuting").get("text").asText()).isEqualTo("AED 374");
        assertThat(b.get("options")).hasSize(16);
        JsonNode frame = null;
        for (JsonNode o : b.get("options")) {
            if (o.get("key").asText().equals("dubai-frame")) {
                frame = o;
            }
        }
        assertThat(frame).isNotNull();
        assertThat(frame.get("cost").get("text").asText()).isEqualTo("AED 170");     // Sara, Omar, Layla AED 50; Adam AED 20
        assertThat(frame.get("costLine").asText()).isEqualTo("2 adults at AED 50, Layla AED 50, Adam AED 20");
        assertThat(b.get("options").get(0).get("cost").get("minor").asLong()).isZero();  // cheapest first
        assertThat(b.get("pricesFrom").asText()).isEqualTo("2026-10-26");
    }

    @Test
    void this_weeks_events_come_live_once_then_from_the_cache_and_off_means_none() throws Exception {
        String omar = login("omar");
        JsonNode first = read(mvc.perform(get("/api/outings").header("Authorization", omar)).andExpect(status().isOk())).get("events");
        assertThat(first.get("source").asText()).isEqualTo("LIVE");
        assertThat(first.get("events").findValuesAsText("title"))
                .containsExactly("Family Movie Night at the Park", "Kids' Science Saturday", "Heritage Village Weekend");
        assertThat(first.get("events").get(0).get("priceNote").asText()).isEqualTo("from AED 30");
        assertThat(first.get("events").get(1).get("where").asText()).isEqualTo("Dubai Festival City");
        assertThat(first.get("events").get(1).get("priceNote").isNull()).isTrue();

        JsonNode second = read(mvc.perform(get("/api/outings").header("Authorization", omar)).andExpect(status().isOk())).get("events");
        assertThat(second.get("source").asText()).isEqualTo("CACHE");
        assertThat(replay.searches).hasSize(1);

        pricing.useMode(PricingService.Mode.OFF);
        JsonNode off = read(mvc.perform(get("/api/outings").header("Authorization", omar)).andExpect(status().isOk())).get("events");
        assertThat(off.get("source").asText()).isEqualTo("NONE");
        assertThat(off.get("events")).isEmpty();
    }

    @Test
    void a_failed_events_search_with_nothing_cached_still_shows_the_planner() throws Exception {
        replay.fail = true;
        JsonNode b = read(mvc.perform(get("/api/outings").header("Authorization", login("sara"))).andExpect(status().isOk()));
        assertThat(b.get("events").get("source").asText()).isEqualTo("NONE");
        assertThat(b.get("options")).hasSize(16);
    }

    @Test
    void this_month_is_over_budget_so_the_plan_is_free_outings_and_next_month_fits_the_budget() throws Exception {
        String sara = login("sara");
        JsonNode now = read(send(post("/api/outings/plan"), sara, "{\"when\":\"THIS_MONTH\"}").andExpect(status().isOk()));
        assertThat(now.get("allowance").get("minor").asLong()).isZero();
        assertThat(now.get("picks")).isNotEmpty();
        assertThat(now.get("picks").findValues("cost")).allMatch(c -> c.get("minor").asLong() == 0);
        assertThat(now.get("facts").findValuesAsText("")).isEmpty();
        assertThat(now.get("facts").toString()).contains("AED 325 over the budget", "not live");

        JsonNode next = read(send(post("/api/outings/plan"), sara, "{\"when\":\"NEXT_MONTH\"}").andExpect(status().isOk()));
        long total = next.get("total").get("minor").asLong();
        assertThat(next.get("picks").size()).isBetween(1, OutingEngine.MAX_PICKS);
        assertThat(total).isLessThanOrEqualTo(120_000);
        assertThat(next.get("left").get("minor").asLong()).isEqualTo(120_000 - total);
        // Every paid outing in the engine's plan costs at most half the usual AED 374
        assertThat(next.get("picks").findValues("cost")).allMatch(c -> c.get("minor").asLong() <= 18_700);
        assertThat(next.get("usualOuting").get("text").asText()).isEqualTo("AED 374");
    }

    @Test
    void the_planners_pick_is_checked_and_kids_cannot_plan() throws Exception {
        String omar = login("omar");
        String run = openRun(omar);
        JsonNode p = read(send(post("/api/outings/plan").header("X-Agent-Run-Id", run), omar,
                "{\"when\":\"NEXT_MONTH\",\"keys\":[\"dubai-frame\",\"made-up\",\"kite-beach\"]}").andExpect(status().isOk()));
        assertThat(p.get("picks").findValuesAsText("key")).containsExactly("dubai-frame", "kite-beach");
        assertThat(p.get("total").get("text").asText()).isEqualTo("AED 170");

        send(post("/api/outings/plan"), login("layla"), "{\"when\":\"NEXT_MONTH\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/outings").header("Authorization", login("adam"))).andExpect(status().isForbidden());
        send(post("/api/outings/plan"), omar, "{\"when\":\"SOMETIME\"}").andExpect(status().isBadRequest());
    }

    @Test
    void a_parent_saves_the_plan_and_the_kids_see_the_places_without_prices() throws Exception {
        String sara = login("sara");
        String run = openRun(sara);
        // Saving is a parent's tap in the app, never the agent's
        send(put("/api/outings/saved").header("X-Agent-Run-Id", run), sara,
                "{\"when\":\"NEXT_MONTH\",\"keys\":[\"dubai-frame\"]}").andExpect(status().isForbidden());

        JsonNode saved = read(send(put("/api/outings/saved"), sara,
                "{\"when\":\"NEXT_MONTH\",\"keys\":[\"dubai-frame\",\"kite-beach\"]}").andExpect(status().isOk()));
        assertThat(saved.get("picks").findValuesAsText("name")).containsExactly("Dubai Frame", "Kite Beach");
        assertThat(saved.get("total").get("text").asText()).isEqualTo("AED 170");
        assertThat(saved.get("savedBy").asText()).isEqualTo("Sara");

        JsonNode kid = read(mvc.perform(get("/api/outings/saved").header("Authorization", login("layla"))).andExpect(status().isOk()));
        assertThat(kid).hasSize(1);
        assertThat(kid.get(0).get("picks").findValuesAsText("name")).containsExactly("Dubai Frame", "Kite Beach");
        assertThat(kid.get(0).get("total").isNull()).isTrue();
        assertThat(kid.get(0).get("picks").get(0).get("cost").isNull()).isTrue();
        assertThat(kid.toString()).doesNotContain("AED");

        // Everyone hears about it through the outbox
        Integer events = jdbc.queryForObject("SELECT count(*) FROM family_event WHERE kind = 'OUTINGS_PLANNED'", Integer.class);
        assertThat(events).isEqualTo(1);

        // Saving again replaces the month's plan
        read(send(put("/api/outings/saved"), sara, "{\"when\":\"NEXT_MONTH\",\"keys\":[\"kite-beach\"]}").andExpect(status().isOk()));
        Integer plans = jdbc.queryForObject("SELECT count(*) FROM outing_plan", Integer.class);
        assertThat(plans).isEqualTo(1);

        send(put("/api/outings/saved"), login("adam"), "{\"when\":\"NEXT_MONTH\",\"keys\":[\"kite-beach\"]}")
                .andExpect(status().isForbidden());
    }

    @Test
    void ways_to_save_points_to_the_outings_planner() throws Exception {
        JsonNode card = read(mvc.perform(get("/api/ways-to-save").header("Authorization", login("omar"))).andExpect(status().isOk()));
        JsonNode outings = card.get("tips").get(0);
        assertThat(outings.get("key").asText()).isEqualTo("outings");
        assertThat(outings.get("text").asText()).contains("The outings planner found");
        assertThat(outings.get("facts").toString()).contains("The outings planner found");
    }
}
