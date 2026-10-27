package ae.familyfinance.core.summary;

import ae.familyfinance.core.advice.NumberCheck;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetService;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.family.Member;
import ae.familyfinance.core.family.MemberRepository;
import ae.familyfinance.core.plan.PlanService;
import ae.familyfinance.core.plan.PlanService.GoalView;
import ae.familyfinance.core.spend.SpendService;
import ae.familyfinance.core.summary.MonthSummaryEngine.Facts;
import ae.familyfinance.core.summary.MonthSummaryEngine.GoalFact;
import ae.familyfinance.core.summary.MonthSummaryEngine.Kid;
import ae.familyfinance.core.summary.MonthSummaryEngine.KidFact;
import ae.familyfinance.core.summary.MonthSummaryEngine.MonthOption;
import ae.familyfinance.core.summary.MonthSummaryEngine.Parents;
import ae.familyfinance.core.summary.MonthSummaryEngine.Requests;
import ae.familyfinance.core.summary.MonthSummaryEngine.Writing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The monthly family summary: this month so far, or one of the {@value #MONTHS_BACK} months before it. The engine
 * ({@link MonthSummaryEngine}) works out the page; the Planner agent rewrites the headline, the story and each kid's
 * camel note, and this service keeps those words only if every number in them is in their facts.
 *
 * <p>The words are kept in {@code app_setting} under {@code summary:<family>:<month>}, each with a fingerprint of the
 * facts it was written from. When the month moves on (a new payment, a request), the fingerprint no longer matches,
 * the engine's sentence shows again and the agent is asked for fresh words. (No new table, so no migration.)
 */
@Service
public class SummaryService {

    static final int MONTHS_BACK = 2;
    private static final TypeReference<Map<String, Stored>> STORED = new TypeReference<>() {
    };

    /** The agent's words for one piece of the summary ({@code key}: headline, story, or kid:&lt;id&gt;). */
    public record Words(String key, String text, String writtenBy) {
    }

    /** What is kept for one piece: the words and the fingerprint of the facts they were written from. */
    record Stored(String text, String by, String facts) {
    }

    private final BudgetService budgets;
    private final PlanService plan;
    private final SpendService spend;
    private final MemberRepository members;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;

    public SummaryService(BudgetService budgets, PlanService plan, SpendService spend, MemberRepository members,
                          JdbcClient db, ObjectMapper json, Clock clock) {
        this.budgets = budgets;
        this.plan = plan;
        this.spend = spend;
        this.members = members;
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Parents parents(CurrentMember me, YearMonth month) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family summary");
        }
        return page(me.familyId(), month);
    }

    /** A kid's own page. A parent may look at any of their kids' pages, a kid only at their own. */
    @Transactional(readOnly = true)
    public Kid kid(CurrentMember me, UUID memberId, YearMonth month) {
        UUID id = memberId == null ? me.memberId() : memberId;
        if (!me.isParent() && !id.equals(me.memberId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only see your own month");
        }
        return page(me.familyId(), month).kidPages().stream().filter(k -> k.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "There is no kid's summary for that member"));
    }

    /** Stores the agent's words. Words with a number that is not in that piece's facts are dropped. */
    @Transactional
    public Parents saveWords(CurrentMember me, YearMonth month, List<Words> words) {
        Parents now = parents(me, month);
        Map<String, Writing> byKey = new HashMap<>();
        now.writing().forEach(w -> byKey.put(w.key(), w));
        Map<String, Stored> stored = load(me.familyId(), now.month());
        for (Words w : words) {
            Writing piece = byKey.get(w.key());
            String text = w.text() == null ? "" : w.text().strip();
            if (piece == null || text.isEmpty() || text.length() > 600 || !NumberCheck.ok(text, piece.facts())) {
                continue; // the engine's own sentence stays
            }
            stored.put(w.key(), new Stored(text, "LLM".equals(w.writtenBy()) ? "LLM" : "RULES", fingerprint(piece)));
        }
        String value;
        try {
            value = json.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        db.sql("""
                INSERT INTO app_setting (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, created_at = now()
                """).params(key(me.familyId(), now.month()), value).update();
        return page(me.familyId(), month);
    }

    /** The month a request means: none = this month; never the future, never before {@value #MONTHS_BACK} months ago. */
    public YearMonth month(String text) {
        YearMonth now = YearMonth.now(clock);
        if (text == null || text.isBlank()) {
            return now;
        }
        YearMonth m;
        try {
            m = YearMonth.parse(text.strip());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Give the month as 2026-10");
        }
        if (m.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That month hasn't happened yet");
        }
        if (m.isBefore(now.minusMonths(MONTHS_BACK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The summary goes back " + MONTHS_BACK + " months");
        }
        return m;
    }

    // ------------------------------------------------------------------ building the page

    Parents page(UUID family, YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        boolean running = month.equals(YearMonth.from(today));
        LocalDate to = running ? today : month.atEndOfMonth();
        String currency = db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();

        List<GoalFact> goals = plan.goals(family).stream().map(SummaryService::goalFact).toList();
        List<KidFact> kids = new ArrayList<>();
        for (Member m : members.findByFamilyIdOrderByRoleDescDisplayNameAsc(family)) {
            if (m.isParent() || !m.isActive()) {
                continue;
            }
            int[] stars = stars(m.getId(), month);
            kids.add(new KidFact(m.getId(), m.getDisplayName(), m.getAgeBand() == AgeBand.CHILD_6_9,
                    spend.summary(family, m.getId(), month), stars[0], stars[1], requests(family, m.getId(), month)));
        }
        Parents p = MonthSummaryEngine.summarise(
                new Facts(month, running, to, currency, budgets.month(family, month), goals, kids));

        Map<String, Stored> stored = load(family, p.month());
        List<MonthOption> options = new ArrayList<>();
        YearMonth now = YearMonth.from(today);
        for (int i = 0; i <= MONTHS_BACK; i++) {
            YearMonth m = now.minusMonths(i);
            options.add(new MonthOption(m.toString(), m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                                                      + (i == 0 ? " so far" : "")));
        }
        return p.with(w -> {
            Stored s = stored.get(w.key());
            return s != null && s.facts().equals(fingerprint(w)) ? w.withWords(s.text(), s.by()) : w;
        }, options);
    }

    private static GoalFact goalFact(GoalView g) {
        return new GoalFact(g.name(), g.tierLabel(), g.saved().minor(), g.target().minor(),
                g.monthly() == null ? null : g.monthly().minor(), g.targetDate(), g.fullBy());
    }

    /** {stars earned in the month, stars in all}. Stars are counted on the UAE date they were given. */
    private int[] stars(UUID member, YearMonth month) {
        return db.sql("""
                SELECT COALESCE(SUM(points) FILTER (WHERE (created_at AT TIME ZONE 'Asia/Dubai')::date BETWEEN ? AND ?), 0),
                       COALESCE(SUM(points), 0)
                FROM points_entry WHERE member_id = ?
                """).params(month.atDay(1), month.atEndOfMonth(), member)
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
    }

    /** The kid's requests made in the month. A request they cancelled (a repeat) doesn't count. */
    private Requests requests(UUID family, UUID member, YearMonth month) {
        int[] counts = new int[5];
        List<String> blocked = new ArrayList<>();
        db.sql("""
                SELECT status, title FROM spending_request
                WHERE family_id = ? AND requester_id = ? AND status <> 'CANCELLED'
                  AND (created_at AT TIME ZONE 'Asia/Dubai')::date BETWEEN ? AND ?
                ORDER BY created_at
                """).params(family, member, month.atDay(1), month.atEndOfMonth())
                .query((rs, n) -> {
                    counts[0]++;
                    switch (rs.getString(1)) {
                        case "APPROVED" -> counts[1]++;
                        case "REJECTED" -> counts[2]++;
                        case "BLOCKED" -> {
                            counts[3]++;
                            blocked.add(rs.getString(2));
                        }
                        default -> counts[4]++;
                    }
                    return null;
                }).list();
        return new Requests(counts[0], counts[1], counts[2], counts[3], counts[4], List.copyOf(blocked));
    }

    // ------------------------------------------------------------------ stored words

    private Map<String, Stored> load(UUID family, String month) {
        return db.sql("SELECT value FROM app_setting WHERE key = ?").param(key(family, month)).query(String.class)
                .optional().map(v -> {
                    try {
                        return new HashMap<>(json.readValue(v, STORED));
                    } catch (JsonProcessingException e) {
                        return new HashMap<String, Stored>(); // unreadable: the engine's words show and get rewritten
                    }
                }).orElseGet(HashMap::new);
    }

    private static String key(UUID family, String month) {
        return "summary:" + family + ":" + month;
    }

    /** The facts a piece of words was written from, and what it is about, as a short hash. */
    static String fingerprint(Writing w) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest((w.about() + "\n" + String.join("\n", w.facts())).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
