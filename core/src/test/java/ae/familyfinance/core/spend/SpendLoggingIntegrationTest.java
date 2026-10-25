package ae.familyfinance.core.spend;

import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spend logging through the real HTTP API with the seeded demo family. */
@AutoConfigureMockMvc
class SpendLoggingIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private JsonNode getJson(String token, String url) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void child_logs_spend_and_the_engine_converts_to_fils() throws Exception {
        String adam = login("adam");
        long before = getJson(adam, "/api/spend/status").get("todayTotal").get("minor").asLong();

        String body = mvc.perform(post("/api/spend").header("Authorization", adam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"amount":"12","description":"Shawarma","category":"EATING_OUT"},
                                          {"amount":"3.50","description":"Juice","category":"SNACKS"}],
                                 "loggedText":"12 dirhams shawarma and juice 3.50"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.logged[0].amount.minor").value(1200))
                .andExpect(jsonPath("$.logged[0].amount.text").value("AED 12"))
                .andExpect(jsonPath("$.logged[1].amount.minor").value(350))
                .andExpect(jsonPath("$.logged[1].categoryLabel").value("snacks and treats"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("today").get("minor").asLong()).isEqualTo(before + 1550);
        assertThat(getJson(adam, "/api/spend/status").get("loggedToday").asBoolean()).isTrue();
    }

    @Test
    void logging_the_same_thing_twice_is_flagged_and_rule_breaks_are_noted() throws Exception {
        String adam = login("adam");
        String robux = """
                {"items":[{"amount":"20","description":"Robux card test","category":"IN_GAME_PURCHASES"}]}
                """;
        mvc.perform(post("/api/spend").header("Authorization", adam).contentType(MediaType.APPLICATION_JSON).content(robux))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.logged[0].familyRuleNote").value("No in-game purchases"));
        mvc.perform(post("/api/spend").header("Authorization", adam).contentType(MediaType.APPLICATION_JSON).content(robux))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.logged[0].possibleDuplicate").value(true));
    }

    @Test
    void silly_amounts_and_dates_are_refused() throws Exception {
        String layla = login("layla");
        for (String item : new String[]{
                "{\"amount\":\"0\",\"description\":\"x\",\"category\":\"OTHER\"}",
                "{\"amount\":\"twelve\",\"description\":\"x\",\"category\":\"OTHER\"}",
                "{\"amount\":\"1000.01\",\"description\":\"x\",\"category\":\"OTHER\"}",
                "{\"amount\":\"1.005\",\"description\":\"x\",\"category\":\"OTHER\"}"}) {
            mvc.perform(post("/api/spend").header("Authorization", layla).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[" + item + "]}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/spend").header("Authorization", layla).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"amount\":\"5\",\"description\":\"x\",\"category\":\"OTHER\"}],\"occurredOn\":\"2001-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parent_sees_a_childs_summary_from_the_seed() throws Exception {
        String omar = login("omar");
        JsonNode s = getJson(omar, "/api/spend/summary?memberId=" + DemoFamily.LAYLA);

        assertThat(s.get("displayName").asText()).isEqualTo("Layla");
        assertThat(s.get("total").get("text").asText()).startsWith("AED ");
        assertThat(s.get("goals").get(0).get("name").asText()).isEqualTo("Headphones");
        // This month can still be empty (the seed logs nothing today, so on the 1st there is nothing yet), and early
        // in the month its comparison is only a day or two. Last month and the one before are always covered by
        // the seed (about 10 weeks of data).
        String lastMonth = s.get("previous").get("from").asText().substring(0, 7);
        JsonNode last = getJson(omar, "/api/spend/summary?memberId=" + DemoFamily.LAYLA + "&month=" + lastMonth);
        assertThat(last.get("previous").get("total").get("minor").asLong()).isPositive();
        assertThat(last.get("categories").get(0).get("category").asText()).isIn("SCHOOL_CANTEEN", "EATING_OUT",
                "CLOTHES_AND_ACCESSORIES", "GIFTS_AND_CHARITY", "SNACKS", "BOOKS_AND_STATIONERY", "TRANSPORT");

        JsonNode adam = getJson(omar, "/api/spend/summary?memberId=" + DemoFamily.ADAM + "&month=" + lastMonth);
        assertThat(adam.get("monthToDate").asBoolean()).isFalse();
        assertThat(adam.get("purchaseCount").asInt()).isPositive();
    }

    @Test
    void a_child_only_sees_their_own_spending() throws Exception {
        String layla = login("layla");
        mvc.perform(get("/api/spend/summary").header("Authorization", layla))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Layla"));
        mvc.perform(get("/api/spend/summary?memberId=" + DemoFamily.ADAM).header("Authorization", layla))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/spend?memberId=" + DemoFamily.ADAM).header("Authorization", layla))
                .andExpect(status().isForbidden());
    }

    @Test
    void another_familys_member_does_not_exist() throws Exception {
        String omar = login("omar");
        mvc.perform(get("/api/spend/summary?memberId=00000000-0000-0000-0000-000000000999").header("Authorization", omar))
                .andExpect(status().isNotFound());
    }
}
