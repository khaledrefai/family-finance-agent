package ae.familyfinance.core;

import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthAndSeedIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void demo_family_is_seeded_with_two_parents_and_two_children() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM member WHERE family_id = ? AND role = 'PARENT'", Integer.class, DemoFamily.FAMILY))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT age_band FROM member WHERE family_id = ? AND role = 'CHILD' ORDER BY age_band", String.class, DemoFamily.FAMILY))
                .containsExactly("CHILD_6_9", "TEEN_13_17");
    }

    @Test
    void wrong_password_is_rejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sara\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void an_old_token_left_in_the_browser_does_not_block_logging_in() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer expired-or-from-before-a-redeploy")
                        .content("{\"username\":\"omar\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Omar"));
    }

    @Test
    void api_needs_a_token() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void login_returns_a_token_that_identifies_the_member() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"adam\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CHILD"))
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("accessToken").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Adam"))
                .andExpect(jsonPath("$.ageBand").value("CHILD_6_9"))
                .andExpect(jsonPath("$.currency").value("AED"));
    }
}
