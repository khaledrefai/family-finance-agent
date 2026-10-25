package ae.familyfinance.core.live;

import ae.familyfinance.core.alerts.AlertService;
import ae.familyfinance.core.support.DemoFamily;
import ae.familyfinance.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox end to end: a change writes an event, the poller turns it into the right people's bell rows, live pings,
 * web pushes and emails. Uses the seeded Haddad family; the timers are off, so each test runs the poller itself.
 */
@AutoConfigureMockMvc
class LiveUpdatesIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired AlertService alerts;

    @BeforeEach
    void drain() {
        dispatcher.dispatchPending(); // whatever earlier tests left behind
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DemoFamily.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private JsonNode ask(String token, String kind, String title, long amountMinor, String category) throws Exception {
        String body = mvc.perform(post("/api/requests").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", kind, "title", title, "amountMinor", amountMinor,
                                "category", category))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Who got a bell row for the event about this request, and its title. */
    private Map<UUID, String> notified(String requestId, String kind) {
        Map<UUID, String> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT n.member_id, n.title FROM notification n JOIN family_event e ON e.id = n.event_id
                WHERE e.payload->>'requestId' = ? AND e.kind = ?
                """, rs -> {
            out.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, requestId, kind);
        return out;
    }

    private long wallet(UUID member) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount_minor), 0) FROM demo_wallet_entry e JOIN demo_wallet w ON w.id = e.wallet_id
                WHERE w.member_id = ?
                """, Long.class, member);
    }

    @Test
    void a_childs_request_reaches_both_parents_and_no_child() throws Exception {
        String id = ask(login("layla"), "SPEND", "Phone case", 4500, "ELECTRONICS").get("id").asText();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM family_event WHERE payload->>'requestId' = ? AND processed_at IS NULL",
                Integer.class, id)).isEqualTo(1);
        dispatcher.dispatchPending();

        Map<UUID, String> who = notified(id, "REQUEST_WAITING");
        assertThat(who).containsOnlyKeys(DemoFamily.SARA, DemoFamily.OMAR);
        assertThat(who.get(DemoFamily.SARA)).isEqualTo("Layla asks for Phone case");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM family_event WHERE payload->>'requestId' = ? AND processed_at IS NULL",
                Integer.class, id)).isZero();
    }

    @Test
    void a_parents_own_request_goes_to_the_other_parent_only() throws Exception {
        String id = ask(login("sara"), "SPEND", "New kettle", 15000, "HOUSEHOLD").get("id").asText();
        dispatcher.dispatchPending();

        assertThat(notified(id, "REQUEST_WAITING")).containsOnlyKeys(DemoFamily.OMAR);
    }

    @Test
    void approving_pays_from_the_childs_wallet_and_tells_the_child() throws Exception {
        String omar = login("omar");
        long before = wallet(DemoFamily.LAYLA);
        String id = ask(login("layla"), "SPEND", "Bus card top-up", 2000, "TRANSPORT").get("id").asText();

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", omar)).andExpect(status().isOk());
        dispatcher.dispatchPending();

        assertThat(wallet(DemoFamily.LAYLA)).isEqualTo(before - 2000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_wallet_entry WHERE request_id = ?::uuid AND amount_minor = -2000",
                Integer.class, id)).isEqualTo(1);
        Map<UUID, String> told = notified(id, "REQUEST_DECIDED");
        assertThat(told).containsOnlyKeys(DemoFamily.LAYLA);
        assertThat(told.get(DemoFamily.LAYLA)).isEqualTo("Omar said yes to Bus card top-up");
        String body = jdbc.queryForObject("""
                SELECT n.body FROM notification n JOIN family_event e ON e.id = n.event_id
                WHERE e.payload->>'requestId' = ? AND e.kind = 'REQUEST_DECIDED'
                """, String.class, id);
        assertThat(body).startsWith("AED 20 came out of your wallet. You have AED ");
        // The wallet change is its own event, for the open screens
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM family_event WHERE kind = 'WALLET_CHANGED' AND subject_member_id = ? AND processed_at IS NOT NULL
                """, Integer.class, DemoFamily.LAYLA)).isPositive();
    }

    @Test
    void when_the_wallet_is_short_a_parent_pays_and_the_wallet_is_untouched() throws Exception {
        long before = wallet(DemoFamily.ADAM);
        String id = ask(login("adam"), "ASK_TO_BUY", "Big telescope", before + 50000, "TOYS_AND_HOBBIES").get("id").asText();

        mvc.perform(post("/api/requests/" + id + "/approve").header("Authorization", login("sara"))).andExpect(status().isOk());
        dispatcher.dispatchPending();

        assertThat(wallet(DemoFamily.ADAM)).isEqualTo(before);
        String body = jdbc.queryForObject("""
                SELECT n.body FROM notification n JOIN family_event e ON e.id = n.event_id
                WHERE e.payload->>'requestId' = ? AND n.member_id = ?
                """, String.class, id, DemoFamily.ADAM);
        assertThat(body).isEqualTo("A parent pays for this one.");
    }

    @Test
    void a_blocked_request_tells_the_parents_quietly_and_never_waits() throws Exception {
        JsonNode r = ask(login("adam"), "SPEND", "Peanut butter cups", 800, "SNACKS");
        assertThat(r.get("status").asText()).isEqualTo("BLOCKED");
        dispatcher.dispatchPending();

        String id = r.get("id").asText();
        assertThat(notified(id, "REQUEST_WAITING")).isEmpty();
        Map<UUID, String> told = notified(id, "REQUEST_BLOCKED");
        assertThat(told).containsOnlyKeys(DemoFamily.SARA, DemoFamily.OMAR);
        assertThat(told.get(DemoFamily.OMAR)).isEqualTo("Blocked by a family rule: Peanut butter cups");
    }

    @Test
    void a_child_sees_only_their_own_wallet() throws Exception {
        mvc.perform(get("/api/wallet").header("Authorization", login("layla")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Layla"))
                .andExpect(jsonPath("$.demoMoney").value(true))
                .andExpect(jsonPath("$.balance.minor").value(wallet(DemoFamily.LAYLA)));
        mvc.perform(get("/api/wallet").param("memberId", DemoFamily.ADAM.toString()).header("Authorization", login("layla")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/wallet").param("memberId", DemoFamily.ADAM.toString()).header("Authorization", login("omar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Adam"));
    }

    @Test
    void the_live_stream_pings_the_parents_screen_when_a_child_asks() throws Exception {
        mvc.perform(get("/api/live")).andExpect(status().isUnauthorized());

        MvcResult stream = mvc.perform(get("/api/live").header("Authorization", login("sara")).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        ask(login("layla"), "SPEND", "Cinema ticket", 4000, "ENTERTAINMENT");
        dispatcher.dispatchPending();

        String sent = stream.getResponse().getContentAsString();
        assertThat(sent).contains("event:hello");
        assertThat(sent).contains("event:REQUEST_WAITING");
        assertThat(sent).contains("Layla asks for Cinema ticket");
        assertThat(stream.getResponse().getHeader("Cache-Control")).contains("no-transform");
    }

    @Test
    void the_core_raises_bill_budget_and_weekly_alerts_once() throws Exception {
        jdbc.update("DELETE FROM family_event WHERE family_id = ? AND dedupe_key IS NOT NULL", DemoFamily.FAMILY);

        List<String> first = alerts.check(DemoFamily.FAMILY, true).sent();
        // Seed: DEWA is due tomorrow, Entertainment is AED 360 over its AED 1,200 budget
        assertThat(first).contains("DEWA electricity and water due tomorrow", "Entertainment is over budget", "This week's plan");
        assertThat(alerts.check(DemoFamily.FAMILY, true).sent()).isEmpty();

        dispatcher.dispatchPending();
        String body = jdbc.queryForObject("""
                SELECT n.body FROM notification n JOIN family_event e ON e.id = n.event_id
                WHERE e.dedupe_key LIKE 'budget:ENTERTAINMENT:%' AND n.member_id = ?
                """, String.class, DemoFamily.SARA);
        assertThat(body).contains("AED 360 over");
        // Parents only; the kids never hear about the family's bills
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification n JOIN family_event e ON e.id = n.event_id
                WHERE e.dedupe_key IS NOT NULL AND n.member_id IN (?, ?)
                """, Integer.class, DemoFamily.LAYLA, DemoFamily.ADAM)).isZero();
    }

    @Test
    void the_weekly_plan_endpoint_is_for_parents() throws Exception {
        mvc.perform(get("/api/plan/week").header("Authorization", login("omar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").exists())
                .andExpect(jsonPath("$.bills[0].label").value("DEWA electricity and water"));
        mvc.perform(get("/api/plan/week").header("Authorization", login("layla"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/alerts/check").header("Authorization", login("adam"))).andExpect(status().isForbidden());
    }

    @Test
    void the_bell_lists_and_clears_my_notifications() throws Exception {
        String sara = login("sara");
        ask(login("adam"), "SPEND", "Football stickers", 1500, "TOYS_AND_HOBBIES");
        dispatcher.dispatchPending();

        String bell = mvc.perform(get("/api/notifications").header("Authorization", sara))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(bell).get("unread").asInt()).isPositive();
        assertThat(bell).contains("Adam asks for Football stickers");

        mvc.perform(post("/api/notifications/read").header("Authorization", sara))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void only_real_push_services_can_be_subscribed() throws Exception {
        String layla = login("layla");
        String keys = "{\"p256dh\":\"" + WebPushCryptoTest.UA_PUBLIC + "\",\"auth\":\"" + WebPushCryptoTest.AUTH + "\"}";
        mvc.perform(post("/api/push/subscriptions").header("Authorization", layla).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"http://169.254.169.254/latest\",\"keys\":" + keys + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/push/subscriptions").header("Authorization", layla).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/layla-test\",\"keys\":{\"p256dh\":\"abc\",\"auth\":\"x\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/push/subscriptions").header("Authorization", layla).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/layla-test\",\"keys\":" + keys + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phones").value(1))
                .andExpect(jsonPath("$.publicKey").isString());
        mvc.perform(post("/api/push/unsubscribe").header("Authorization", layla).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/layla-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phones").value(0));
    }

    @Test
    void a_push_is_encrypted_signed_and_a_gone_phone_is_dropped() throws Exception {
        List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.add(Map.of("auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                    "encoding", String.valueOf(ex.getRequestHeaders().getFirst("Content-Encoding")),
                    "ttl", String.valueOf(ex.getRequestHeaders().getFirst("TTL")),
                    "size", String.valueOf(body.length)));
            ex.sendResponseHeaders(calls.incrementAndGet() == 1 ? 201 : 410, -1);
            ex.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
            // Straight into the table: the API only accepts real push services, this is a stand-in for one
            jdbc.update("INSERT INTO push_subscription (member_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)",
                    DemoFamily.OMAR, endpoint, WebPushCryptoTest.UA_PUBLIC, WebPushCryptoTest.AUTH);
            String omar = login("omar");

            mvc.perform(post("/api/push/test").header("Authorization", omar)).andExpect(status().isOk());
            dispatcher.dispatchPending();

            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).get("auth")).startsWith("vapid t=ey").contains(", k=B");
            assertThat(seen.get(0).get("encoding")).isEqualTo("aes128gcm");
            assertThat(seen.get(0).get("ttl")).isEqualTo("86400");
            assertThat(Integer.parseInt(seen.get(0).get("size"))).isGreaterThan(86 + 16);
            assertThat(jdbc.queryForObject("""
                    SELECT pushed_at IS NOT NULL FROM notification WHERE member_id = ? AND kind = 'TEST'
                    ORDER BY created_at DESC LIMIT 1
                    """, Boolean.class, DemoFamily.OMAR)).isTrue();

            // The second time the push service says 410 Gone: the phone is forgotten
            mvc.perform(post("/api/push/test").header("Authorization", omar)).andExpect(status().isOk());
            dispatcher.dispatchPending();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscription WHERE endpoint = ?", Integer.class, endpoint)).isZero();
        } finally {
            server.stop(0);
            jdbc.update("DELETE FROM push_subscription WHERE member_id = ?", DemoFamily.OMAR);
        }
    }

    @Test
    void a_parent_sets_an_email_and_a_child_cannot() throws Exception {
        String sara = login("sara");
        mvc.perform(put("/api/me/email").header("Authorization", sara).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not an email\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/email").header("Authorization", sara).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Sara@Example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sara@example.com"))
                .andExpect(jsonPath("$.emailReady").value(false));
        mvc.perform(put("/api/me/email").header("Authorization", login("layla")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"layla@example.com\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE member SET email = 'sara@demo.local' WHERE id = ?", DemoFamily.SARA);
    }
}
