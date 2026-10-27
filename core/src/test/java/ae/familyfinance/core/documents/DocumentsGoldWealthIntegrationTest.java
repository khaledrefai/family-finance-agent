package ae.familyfinance.core.documents;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Week 3 through the real API and the seeded Haddad family: the demo receipt and Visa statement read, matched and
 * confirmed; the double charge and the forgotten subscription found; gold and family wealth worked out.
 */
@AutoConfigureMockMvc
class DocumentsGoldWealthIntegrationTest extends PostgresIntegrationTest {

    static final String VISA = "00000000-0000-0000-0000-0000000000e3";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        // leave the seeded family as the other tests expect it
        jdbc.update("DELETE FROM ledger_transaction WHERE source_document_id IS NOT NULL");
        jdbc.update("DELETE FROM source_document");
        jdbc.update("DELETE FROM finding_decision");
        jdbc.update("UPDATE ledger_transaction SET duplicate_of_id = NULL");
        jdbc.update("DELETE FROM commitment WHERE source = 'DOCUMENT'");
        jdbc.update("DELETE FROM gold_holding WHERE description = 'Test coin'");
        jdbc.update("DELETE FROM gold_price WHERE source LIKE 'Typed in by%'");
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

    private ResultActions postJson(String url, String token, String body, String run) throws Exception {
        var req = post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
        if (run != null) {
            req.header("X-Agent-Run-Id", run);
        }
        return mvc.perform(req);
    }

    private String openRun(String token) throws Exception {
        return read(postJson("/api/agent-runs", token,
                "{\"agent\":\"BOOKKEEPER\",\"triggerKind\":\"MESSAGE\",\"inputSummary\":\"read a document\"}", null)
                .andExpect(status().isCreated())).get("id").asText();
    }

    private String sample(String token, String name) throws Exception {
        return mvc.perform(get("/api/documents/samples/" + name).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode upload(String token, String name, String type, String text, String kind) throws Exception {
        return read(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", name, type, text.getBytes(StandardCharsets.UTF_8)))
                        .param("kind", kind).header("Authorization", token))
                .andExpect(status().isCreated()));
    }

    /** What the Bookkeeper's rules would read from the demo statement (the agent's own tests check that part). */
    private String statementDraft(String csv) {
        List<String> lines = new ArrayList<>();
        boolean body = false;
        for (String row : csv.split("\n")) {
            if (row.startsWith("Date,")) {
                body = true;
                continue;
            }
            if (body && !row.isBlank()) {
                String[] c = row.split(",");
                lines.add("{\"date\":\"%s\",\"description\":\"%s\",\"amount\":\"%s\"}".formatted(c[0], c[1], c[2]));
            }
        }
        return "{\"kind\":\"CARD_STATEMENT\",\"cardLast4\":\"7710\",\"closingBalance\":\"5950.00\",\"readBy\":\"RULES\","
               + "\"lines\":[" + String.join(",", lines) + "]}";
    }

    @Test
    void receipt_then_statement_counts_each_purchase_once_and_finds_the_double_charge_and_the_subscription() throws Exception {
        String sara = login("sara");
        String run = openRun(sara);

        // 1. The receipt: read by the agent (a draft), matched to the Visa by its last 4 digits
        String receiptText = sample(sara, "receipt");
        JsonNode receipt = upload(sara, "carrefour-receipt.txt", "text/plain", receiptText, "RECEIPT");
        assertThat(receipt.get("status").asText()).isEqualTo("UPLOADED");
        String rid = receipt.get("id").asText();
        String date = receiptText.lines().filter(l -> l.startsWith("Date:")).findFirst().orElseThrow().substring(6, 16);
        JsonNode draft = read(postJson("/api/documents/" + rid + "/extraction", sara, """
                {"kind":"RECEIPT","merchant":"Carrefour Mirdif City Centre","date":"%s","total":"641.50","cardLast4":"7710",
                 "readBy":"RULES","lines":[{"description":"Whole chicken x2","amount":"44.00"},{"description":"Rest","amount":"597.50"}]}
                """.formatted(date), run).andExpect(status().isOk()));
        assertThat(draft.get("status").asText()).isEqualTo("EXTRACTED");
        assertThat(draft.get("accountId").asText()).isEqualTo(VISA);
        assertThat(draft.get("matchReason").asText()).isEqualTo("Matched by the last 4 digits 7710");
        assertThat(draft.get("total").get("text").asText()).isEqualTo("AED 641.50");
        assertThat(draft.get("itemsTotal").get("text").asText()).isEqualTo("AED 641.50");
        assertThat(draft.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(draft.get("readBy").asText()).isEqualTo("RULES");

        // the agent can never confirm; the parent can
        postJson("/api/documents/" + rid + "/confirm", sara, "{}", run).andExpect(status().isForbidden());
        JsonNode confirmed = read(postJson("/api/documents/" + rid + "/confirm", sara, "{}", null).andExpect(status().isOk()));
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE source_document_id = ?::uuid",
                Long.class, rid)).isEqualTo(1);

        // the same file again is refused
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "again.txt", "text/plain",
                receiptText.getBytes(StandardCharsets.UTF_8))).header("Authorization", sara)).andExpect(status().isConflict());

        // 2. The Visa statement: 13 lines; the receipt, the fuel and the dinner are already in
        String csv = sample(sara, "statement");
        JsonNode statement = upload(sara, "visa-statement.csv", "text/csv", csv, "STATEMENT");
        String sid = statement.get("id").asText();
        JsonNode read = read(postJson("/api/documents/" + sid + "/extraction", sara, statementDraft(csv), run)
                .andExpect(status().isOk()));
        assertThat(read.get("kind").asText()).isEqualTo("CARD_STATEMENT");
        assertThat(read.get("counts").get("lines").asInt()).isEqualTo(13);
        assertThat(read.get("counts").get("alreadyLogged").asInt()).isEqualTo(3);
        assertThat(read.get("counts").get("added").asInt()).isEqualTo(10);
        assertThat(read.get("counts").get("possibleDuplicates").asInt()).isEqualTo(1);
        assertThat(read.get("summary").asText()).startsWith("13 lines. 10 new").contains("For Omar's Visa ···7710");
        JsonNode carrefour = null;
        for (JsonNode l : read.get("lines")) {
            if (l.get("description").asText().startsWith("CARREFOUR")) {
                carrefour = l;
            }
        }
        assertThat(carrefour.get("status").asText()).isEqualTo("ALREADY_LOGGED");
        assertThat(carrefour.get("matchNote").asText()).contains("from your receipt");

        // the bill forecast doesn't move: every line is before the Visa's typed balance
        String omar = login("omar");
        String before = read(mvc.perform(get("/api/accounts/" + VISA + "/card-check").param("amount", "300")
                .header("Authorization", omar))).get("line").asText();
        postJson("/api/documents/" + sid + "/confirm", sara, "{\"updateBalance\":true}", null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE source_document_id = ?::uuid",
                Long.class, sid)).isEqualTo(10);
        JsonNode after = read(mvc.perform(get("/api/accounts/" + VISA + "/card-check").param("amount", "300")
                .header("Authorization", omar)));
        assertThat(after.get("fits").asBoolean()).isFalse();
        assertThat(after.get("line").asText()).isEqualTo(before);

        // 3. Findings: the Spinneys double charge and the Shahid subscription
        JsonNode findings = read(mvc.perform(get("/api/findings").header("Authorization", sara)).andExpect(status().isOk()));
        assertThat(findings.findValuesAsText("kind")).containsExactlyInAnyOrder("DUPLICATE", "SUBSCRIPTION");
        String dup = null;
        String sub = null;
        for (JsonNode f : findings) {
            if (f.get("kind").asText().equals("DUPLICATE")) {
                dup = f.get("key").asText();
                assertThat(f.get("title").asText()).startsWith("Two charges of AED 189 at Spinneys on ");
            } else {
                sub = f.get("key").asText();
                assertThat(f.get("title").asText()).startsWith("AED 35 to Shahid VIP every month since ");
            }
        }
        postJson("/api/findings/decide", sara, "{\"key\":\"" + sub + "\",\"action\":\"ADD\"}", run).andExpect(status().isForbidden());
        postJson("/api/findings/decide", sara, "{\"key\":\"" + dup + "\",\"action\":\"ADD\"}", null).andExpect(status().isBadRequest());
        JsonNode left = read(postJson("/api/findings/decide", sara, "{\"key\":\"" + dup + "\",\"action\":\"DUPLICATE\"}", null)
                .andExpect(status().isOk()));
        assertThat(left).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE duplicate_of_id IS NOT NULL", Long.class))
                .isEqualTo(1);
        left = read(postJson("/api/findings/decide", sara, "{\"key\":\"" + sub + "\",\"action\":\"ADD\"}", null)
                .andExpect(status().isOk()));
        assertThat(left).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM commitment WHERE kind = 'SUBSCRIPTION' AND status = 'ACTIVE' AND source = 'DOCUMENT'
                  AND amount_minor = 3500 AND next_due_on >= (now() AT TIME ZONE 'Asia/Dubai')::date""", Long.class)).isEqualTo(1);
    }

    @Test
    void a_document_the_agent_could_not_read_says_so_and_can_be_thrown_away() throws Exception {
        String sara = login("sara");
        JsonNode doc = upload(sara, "photo.png", "image/png", "not really a png", "RECEIPT");
        String id = doc.get("id").asText();
        JsonNode failed = read(postJson("/api/documents/" + id + "/failed", sara,
                "{\"reason\":\"I can't read photos while the AI is switched off. Type it in instead.\"}", openRun(sara))
                .andExpect(status().isOk()));
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("summary").asText()).contains("Type it in");
        postJson("/api/documents/" + id + "/confirm", sara, "{}", null).andExpect(status().isConflict());
        postJson("/api/documents/" + id + "/discard", sara, "", null).andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/" + id).header("Authorization", sara)).andExpect(status().isNotFound());
    }

    @Test
    void children_cannot_upload_or_see_documents_gold_or_wealth() throws Exception {
        String layla = login("layla");
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "r.txt", "text/plain", "x".getBytes()))
                .header("Authorization", layla)).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents").header("Authorization", layla)).andExpect(status().isForbidden());
        mvc.perform(get("/api/findings").header("Authorization", layla)).andExpect(status().isForbidden());
        mvc.perform(get("/api/gold").header("Authorization", layla)).andExpect(status().isForbidden());
        mvc.perform(get("/api/wealth").header("Authorization", layla)).andExpect(status().isForbidden());
    }

    @Test
    void gold_is_valued_at_todays_price_and_only_a_parent_logs_it() throws Exception {
        String sara = login("sara");
        JsonNode gold = read(mvc.perform(get("/api/gold").header("Authorization", sara)).andExpect(status().isOk()));
        assertThat(gold.get("value").get("text").asText()).isEqualTo("AED 15,765");
        assertThat(gold.get("paid").get("text").asText()).isEqualTo("AED 15,400");
        assertThat(gold.get("grams").findValuesAsText("grams")).containsExactly("40", "10");
        assertThat(gold.get("byGoal").findValuesAsText("name")).containsExactly("Home deposit", "Emergency buffer");
        assertThat(gold.get("priceSource").asText()).isEqualTo("Sample Dubai retail rate (demo)");
        JsonNode bangle = gold.get("holdings").get(2);
        assertThat(bangle.get("belowPaid").asBoolean()).isTrue();

        postJson("/api/gold", sara, "{\"grams\":\"5\",\"purity\":\"24K\",\"paid\":\"1700\"}", openRun(sara))
                .andExpect(status().isForbidden());
        postJson("/api/gold", sara, "{\"grams\":\"five\",\"purity\":\"24K\",\"paid\":\"1700\"}", null)
                .andExpect(status().isBadRequest());
        JsonNode coin = read(postJson("/api/gold", sara, """
                {"description":"Test coin","grams":"2.5","purity":"24K","paid":"850","seller":"Gold Souk"}""", null)
                .andExpect(status().isCreated()));
        assertThat(coin.get("value").get("text").asText()).isEqualTo("AED 800");
        assertThat(coin.get("goalName").asText()).isEqualTo("Savings");

        JsonNode price = read(postJson("/api/gold/price", sara, "{\"purity\":\"24K\",\"perGram\":\"330\"}", null)
                .andExpect(status().isOk()));
        assertThat(price.get("source").asText()).isEqualTo("Typed in by Sara");
        gold = read(mvc.perform(get("/api/gold").header("Authorization", sara)));
        // 42.5 g x AED 330 + 10 g x AED 296.50 = AED 14,025 + AED 2,965
        assertThat(gold.get("value").get("text").asText()).isEqualTo("AED 16,990");
    }

    @Test
    void wealth_adds_what_the_family_owns_and_takes_off_what_it_owes() throws Exception {
        JsonNode w = read(mvc.perform(get("/api/wealth").header("Authorization", login("omar"))).andExpect(status().isOk()));
        // bank 11,650 + 32,600, cash 650, gold 15,765 = 60,665; cards 5,950 + 1,350 and the car loan 38,400
        assertThat(w.get("owned").get("text").asText()).isEqualTo("AED 60,665");
        assertThat(w.get("owed").get("text").asText()).isEqualTo("AED 45,700");
        assertThat(w.get("total").get("text").asText()).isEqualTo("AED 14,965");
        assertThat(w.get("steps").findValuesAsText("label")).containsExactly("Bank accounts", "Cash",
                "Gold, at today's price", "Everything you own", "Credit cards", "Car loan left", "Family wealth");
    }
}
