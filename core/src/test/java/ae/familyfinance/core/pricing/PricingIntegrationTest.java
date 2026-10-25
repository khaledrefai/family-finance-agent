package ae.familyfinance.core.pricing;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Week 6 through the real API, with SerpApi replaced by recorded answers ({@link ReplaySource}): the cache, the
 * re-check, the saved price list fallback, SEARCH_LIVE, and requests made from an offer (Layla's headphones still
 * show the card warning; Adam's peanut butter cups are still blocked).
 */
@AutoConfigureMockMvc
class PricingIntegrationTest extends PostgresIntegrationTest {

    static final String VISA = "00000000-0000-0000-0000-0000000000e3";
    static final String SONY = """
            {"intent":{"query":"Sony WH-CH720N","brand":"Sony","model":"WH-CH720N","said":"is the Sony WH-CH720N cheaper anywhere?"}}""";

    @TestConfiguration
    static class Replay {
        @Bean
        @Primary
        ReplaySource replaySource() {
            return new ReplaySource();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReplaySource replay;
    @Autowired PricingService pricing;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM price_search_cache");
        jdbc.update("DELETE FROM demo_wallet_entry WHERE request_id IN (SELECT id FROM spending_request WHERE offer IS NOT NULL)");
        jdbc.update("DELETE FROM spending_request WHERE offer IS NOT NULL");
        jdbc.update("DELETE FROM price_comparison");
        replay.searches.clear();
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

    private ResultActions postJson(String url, String token, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode compare(String token, String body) throws Exception {
        return read(postJson("/api/pricing/compare", token, body).andExpect(status().isOk()));
    }

    @Test
    void layla_compares_her_headphones_live_and_gets_the_best_rechecked_total() throws Exception {
        JsonNode c = compare(login("layla"), SONY);

        assertThat(c.get("source").asText()).isEqualTo("LIVE");
        assertThat(c.get("exact").asBoolean()).isTrue();
        assertThat(c.get("offers").findValuesAsText("store")).containsExactly("Amazon.ae", "noon", "Sharaf DG");
        JsonNode best = c.get("best");
        assertThat(best.get("store").asText()).isEqualTo("Amazon.ae");
        assertThat(best.get("total").get("minor").asLong()).isEqualTo(28_900);
        assertThat(best.get("verified").asBoolean()).isTrue();
        // The re-check found noon at AED 295 with free delivery; Sharaf DG's listing wasn't found again
        JsonNode noon = c.get("offers").get(1);
        assertThat(noon.get("total").get("text").asText()).isEqualTo("AED 295");
        assertThat(noon.get("verified").asBoolean()).isTrue();
        assertThat(c.get("offers").get(2).get("verified").asBoolean()).isFalse();
        assertThat(c.get("substitutes").findValuesAsText("title")).hasSize(2).allMatch(t -> !t.contains("WH-CH720N"));
        assertThat(c.get("dropped").asInt()).isEqualTo(1);
        // A child sees no shop links, only her own wallet side, and the facts the agent may say
        assertThat(c.get("offers").findValues("link")).allMatch(JsonNode::isNull);
        assertThat(c.get("assessment").get("freeUntilPay").isNull()).isTrue();
        assertThat(c.get("assessment").get("needsParent").asBoolean()).isTrue();
        assertThat(c.get("facts").toString()).contains("Best re-checked total: AED 289 at Amazon.ae (AED 289 + free delivery)",
                "A different model, not the same item", "less than half the usual price", "Layla's wallet");
        assertThat(replay.searches).hasSize(4);                     // the search and three re-checks
    }

    @Test
    void the_same_search_again_comes_from_the_cache_without_paying_for_a_search() throws Exception {
        compare(login("omar"), SONY);
        replay.searches.clear();

        JsonNode again = compare(login("sara"), SONY);
        assertThat(again.get("source").asText()).isEqualTo("CACHE");
        assertThat(again.get("best").get("total").get("minor").asLong()).isEqualTo(28_900);
        assertThat(again.get("best").get("link").asText()).startsWith("https://");   // parents get the shop's page
        assertThat(replay.searches).isEmpty();
    }

    @Test
    void cache_mode_uses_an_old_search_and_never_searches_live() throws Exception {
        compare(login("omar"), SONY);
        jdbc.update("UPDATE price_search_cache SET fetched_at = now() - interval '3 days'");
        replay.searches.clear();
        pricing.useMode(PricingService.Mode.CACHE);

        JsonNode c = compare(login("omar"), SONY);
        assertThat(c.get("source").asText()).isEqualTo("CACHE");
        assertThat(c.get("mode").asText()).isEqualTo("CACHE");
        assertThat(replay.searches).isEmpty();

        // Not in the cache: the saved price list answers, still without a live search
        JsonNode other = compare(login("omar"), "{\"intent\":{\"query\":\"air fryer\"}}");
        assertThat(other.get("source").asText()).isEqualTo("SEEDED");
        assertThat(other.get("offers").size()).isGreaterThan(2);
        assertThat(replay.searches).isEmpty();
    }

    @Test
    void with_search_off_or_nothing_found_the_saved_price_list_answers_and_says_so() throws Exception {
        pricing.useMode(PricingService.Mode.OFF);
        JsonNode c = compare(login("layla"), "{\"intent\":{\"query\":\"wireless headphones\"}}");
        assertThat(c.get("source").asText()).isEqualTo("SEEDED");
        assertThat(c.get("offers").findValuesAsText("title")).allMatch(t -> t.toLowerCase().contains("headphone"));
        assertThat(c.get("facts").get(0).asText()).contains("from our saved price list", "not live prices");
        assertThat(replay.searches).isEmpty();

        pricing.useMode(PricingService.Mode.LIVE);
        JsonNode none = compare(login("omar"), "{\"intent\":{\"query\":\"zzqx gadget\"}}");
        assertThat(none.get("best").isNull()).isTrue();
        assertThat(none.get("facts").get(0).asText()).startsWith("I couldn't find");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_search_cache", Integer.class)).isZero(); // "no results" isn't kept
    }

    @Test
    void a_request_from_an_offer_takes_the_cores_price_and_the_headphones_card_warning_still_shows() throws Exception {
        String layla = login("layla");
        JsonNode c = compare(layla, SONY);
        // The typed title and amount are ignored: the saved comparison decides
        JsonNode r = read(postJson("/api/requests", layla, """
                {"kind":"ASK_TO_BUY","title":"cheap!","amountMinor":100,"comparisonId":"%s","offerKey":"%s"}"""
                .formatted(c.get("id").asText(), c.get("best").get("key").asText())).andExpect(status().isCreated()));
        assertThat(r.get("amountMinor").asLong()).isEqualTo(28_900);
        assertThat(r.get("title").asText()).isEqualTo("Sony WH-CH720N Wireless Noise Cancelling Headphones");
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertThat(r.get("offer").get("store").asText()).isEqualTo("Amazon.ae");
        assertThat(r.get("offer").get("verified").asBoolean()).isTrue();

        // Omar opens it and picks his Visa: the bill before pay day still isn't covered
        JsonNode a = read(postJson("/api/requests/assess", login("omar"), """
                {"amount":"289","requesterId":"%s","paidFromAccountId":"%s","title":"%s"}"""
                .formatted(DemoFamily.LAYLA, VISA, r.get("title").asText())).andExpect(status().isOk()));
        assertThat(a.get("card").get("fits").asBoolean()).isFalse();
    }

    @Test
    void adams_peanut_butter_cups_are_blocked_from_the_comparison_on() throws Exception {
        String adam = login("adam");
        JsonNode c = compare(adam, "{\"intent\":{\"query\":\"peanut butter cups\",\"category\":\"SNACKS\"}}");
        assertThat(c.get("source").asText()).isEqualTo("SEEDED");          // no live answer recorded for it
        assertThat(c.get("assessment").get("blocked").asBoolean()).isTrue();

        JsonNode r = read(postJson("/api/requests", adam, """
                {"kind":"ASK_TO_BUY","title":"x","amountMinor":0,"comparisonId":"%s","offerKey":"o1"}"""
                .formatted(c.get("id").asText())).andExpect(status().isCreated()));
        assertThat(r.get("status").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void a_child_cannot_use_someone_elses_comparison() throws Exception {
        JsonNode c = compare(login("layla"), SONY);
        postJson("/api/requests", login("adam"), """
                {"kind":"ASK_TO_BUY","title":"x","amountMinor":0,"comparisonId":"%s","offerKey":"o1"}"""
                .formatted(c.get("id").asText())).andExpect(status().isNotFound());
    }

    @Test
    void parents_see_which_searches_are_on() throws Exception {
        JsonNode s = read(mvc.perform(get("/api/pricing/status").header("Authorization", login("sara"))).andExpect(status().isOk()));
        assertThat(s.get("mode").asText()).isEqualTo("LIVE");
        assertThat(s.get("savedItems").asInt()).isGreaterThanOrEqualTo(90);
        mvc.perform(get("/api/pricing/status").header("Authorization", login("layla"))).andExpect(status().isForbidden());
    }
}
