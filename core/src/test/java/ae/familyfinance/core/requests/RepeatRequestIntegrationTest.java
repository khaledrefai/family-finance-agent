package ae.familyfinance.core.requests;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tapping "Send to a parent" again, or four times at once, still makes one request. */
@AutoConfigureMockMvc
class RepeatRequestIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private String send(String token, String title, long amountMinor) throws Exception {
        String body = mvc.perform(post("/api/requests").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"ASK_TO_BUY","title":"%s","amountMinor":%d}
                                """.formatted(title, amountMinor)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private int saved(String title) {
        return jdbc.queryForObject("SELECT count(*) FROM spending_request WHERE title = ?", Integer.class, title);
    }

    @Test
    void sending_the_same_ask_again_returns_the_request_already_sent() throws Exception {
        String layla = login("layla");
        String first = send(layla, "Repeat test: drawing tablet", 25000);
        assertThat(send(layla, "Repeat test: drawing tablet", 25000)).isEqualTo(first);
        assertThat(saved("Repeat test: drawing tablet")).isEqualTo(1);

        // A different price is a different ask
        assertThat(send(layla, "Repeat test: drawing tablet", 26000)).isNotEqualTo(first);
    }

    @Test
    void four_taps_at_the_same_moment_make_one_request() throws Exception {
        String adam = login("adam");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> taps = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                taps.add(pool.submit(() -> send(adam, "Repeat test: football", 6000)));
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> tap : taps) {
                ids.add(tap.get());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdown();
        }
        assertThat(saved("Repeat test: football")).isEqualTo(1);
    }

    @Test
    void a_decided_ask_can_be_sent_again() throws Exception {
        String layla = login("layla");
        String first = send(layla, "Repeat test: book", 4000);
        mvc.perform(post("/api/requests/" + first + "/reject").header("Authorization", login("sara")))
                .andExpect(status().isOk());
        assertThat(send(layla, "Repeat test: book", 4000)).isNotEqualTo(first);
    }

    @Test
    void cancelled_repeats_are_left_out_of_the_lists() throws Exception {
        String layla = login("layla");
        String id = send(layla, "Repeat test: old copy", 1000);
        jdbc.update("UPDATE spending_request SET status = 'CANCELLED' WHERE id = ?::uuid", id);
        for (String who : List.of("layla", "omar")) {
            JsonNode list = json.readTree(mvc.perform(get("/api/requests").header("Authorization", login(who)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            list.forEach(r -> assertThat(r.get("id").asText()).isNotEqualTo(id));
        }
    }
}
