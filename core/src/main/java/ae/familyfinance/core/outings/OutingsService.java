package ae.familyfinance.core.outings;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.budgets.BudgetService;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.common.ClockConfig;
import ae.familyfinance.core.documents.FindingService;
import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.live.LiveKind;
import ae.familyfinance.core.live.Outbox;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.outings.EventsParser.Event;
import ae.familyfinance.core.outings.OutingEngine.Costed;
import ae.familyfinance.core.outings.OutingEngine.Fun;
import ae.familyfinance.core.outings.OutingEngine.Kind;
import ae.familyfinance.core.outings.OutingEngine.Person;
import ae.familyfinance.core.outings.OutingEngine.Plan;
import ae.familyfinance.core.outings.OutingEngine.Row;
import ae.familyfinance.core.outings.OutingEngine.When;
import ae.familyfinance.core.pricing.PricingService;
import ae.familyfinance.core.pricing.PricingService.Mode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cheaper family outings that fit the entertainment budget (week 7).
 * <ol>
 *   <li>The outings come from our saved list ({@link OutingCatalogue}), so a plan never waits on a website.</li>
 *   <li>The {@link OutingEngine} works out each outing's cost for this family (adult and child tickets) and a plan for
 *       this month or next that fits what is left of the entertainment budget.</li>
 *   <li>The Planner agent may pick the outings from a parent's words; the core checks its pick the same way.</li>
 *   <li>A parent saves the plan; everyone in the family hears about it, the kids without the prices.</li>
 *   <li>"On this week": live events (SerpApi Google Events) by the same SEARCH_LIVE rules as the price search, with a
 *       24 hour cache. They are extra ideas with the organiser's link, never counted in a plan.</li>
 * </ol>
 */
@Service
public class OutingsService {

    private static final Logger log = LoggerFactory.getLogger(OutingsService.class);
    static final String EVENTS_QUERY = "family events in Dubai";
    static final int MAX_EVENTS = 3;

    public record OptionView(String key, String name, String area, Set<Kind> kinds, MoneyView cost, String costLine,
                             List<Row> tickets, boolean openThisMonth, boolean openNextMonth, String about, String site) {

        static OptionView of(Costed c, YearMonth now) {
            return new OptionView(c.option().key(), c.option().name(), c.option().area(), c.option().kinds(),
                    MoneyView.of(c.cost()), c.costLine(), c.tickets(), c.option().openIn(now), c.option().openIn(now.plusMonths(1)),
                    c.option().about(), c.option().site());
        }
    }

    /** A plan as the app shows it. {@code pricesFrom}: the date the saved list was typed in (not live prices). */
    public record PlanView(When when, String month, String monthLabel, MoneyView budget, MoneyView spent,
                           MoneyView allowance, List<OptionView> picks, MoneyView total, MoneyView left,
                           MoneyView usualOuting, MoneyView usualCost, MoneyView saving, List<String> leftOut,
                           List<String> facts, List<Row> why, LocalDate pricesFrom) {
    }

    public record EventView(String title, String when, String where, String link, String priceNote) {
    }

    /** {@code source}: LIVE, CACHE or NONE (no search allowed, no key, or it failed with nothing cached). */
    public record Events(String source, OffsetDateTime checkedAt, List<EventView> events) {
    }

    /** One saved outing. {@code cost} is null for a child: kids see the places, not the prices. */
    public record SavedPick(String key, String name, String area, String about, MoneyView cost) {
    }

    public record Saved(String month, String monthLabel, List<SavedPick> picks, MoneyView total, String savedBy,
                        OffsetDateTime savedAt) {
    }

    /** Everything the outings screen shows: the budget, every outing with this family's cost, events, saved plans. */
    public record Board(String thisMonth, String nextMonth, MoneyView budget, MoneyView spent, MoneyView leftThisMonth,
                        MoneyView over, MoneyView usualOuting, int outingsCounted, List<OptionView> options,
                        LocalDate pricesFrom, Events events, List<Saved> saved, List<String> facts) {
    }

    private final OutingCatalogue catalogue;
    private final BudgetService budgets;
    private final FindingService findings;
    private final PricingService pricing;
    private final EventsSource events;
    private final Outbox outbox;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration cacheFor;

    public OutingsService(OutingCatalogue catalogue, BudgetService budgets, FindingService findings, PricingService pricing,
                          EventsSource events, Outbox outbox, JdbcClient db, ObjectMapper json, Clock clock,
                          @Value("${app.pricing.cache-for:24h}") Duration cacheFor) {
        this.catalogue = catalogue;
        this.budgets = budgets;
        this.findings = findings;
        this.pricing = pricing;
        this.events = events;
        this.outbox = outbox;
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.cacheFor = cacheFor;
    }

    // ---------------------------------------------------------------- the screen

    @Transactional
    public Board board(CurrentMember me) {
        requireParent(me);
        UUID family = me.familyId();
        String cur = currency(family);
        YearMonth now = YearMonth.now(clock);
        Fun fun = fun(family, cur);
        List<Costed> all = OutingEngine.costAll(catalogue.options(), people(family), cur);
        Money budget = new Money(fun.limitMinor(), cur);
        Money spent = new Money(fun.spentMinor(), cur);
        Money over = spent.minus(budget);
        List<OptionView> options = all.stream()
                .sorted(java.util.Comparator.comparing(Costed::cost))
                .map(c -> OptionView.of(c, now)).toList();
        Plan plan = OutingEngine.suggest(all, Set.of(), When.THIS_MONTH, now, fun, cur);
        return new Board(OutingEngine.label(now), OutingEngine.label(now.plusMonths(1)), MoneyView.of(budget), MoneyView.of(spent),
                MoneyView.of(OutingEngine.allowance(When.THIS_MONTH, fun, cur)), over.isPositive() ? MoneyView.of(over) : null,
                fun.usualOutingMinor() == null ? null : MoneyView.of(new Money(fun.usualOutingMinor(), cur)), fun.outingsCounted(),
                options, catalogue.checkedOn(), events(), saved(me), plan.facts().subList(0, Math.min(3, plan.facts().size())));
    }

    // ---------------------------------------------------------------- plans

    /**
     * A plan for this month or next. {@code keys} empty: the engine's own pick, of the {@code kinds} asked for.
     * Otherwise the outings the Planner picked, checked by the engine.
     */
    @Transactional(readOnly = true)
    public PlanView plan(CurrentMember me, When when, Set<Kind> kinds, List<String> keys) {
        requireParent(me);
        return view(plan(me.familyId(), when, kinds, keys));
    }

    /** The engine's own plan for next month, for the Ways to save card. */
    public Plan suggestNextMonth(UUID family) {
        return plan(family, When.NEXT_MONTH, Set.of(), List.of());
    }

    Plan plan(UUID family, When when, Set<Kind> kinds, List<String> keys) {
        String cur = currency(family);
        YearMonth now = YearMonth.now(clock);
        Fun fun = fun(family, cur);
        List<Costed> all = OutingEngine.costAll(catalogue.options(), people(family), cur);
        Plan p = keys == null || keys.isEmpty()
                ? OutingEngine.suggest(all, kinds == null ? Set.of() : kinds, when, now, fun, cur)
                : OutingEngine.check(all, keys, when, now, fun, cur);
        List<String> facts = new ArrayList<>(p.facts());
        facts.add("Prices are from our saved list of " + catalogue.checkedOn().format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                  + ", not live. Check each price on the site before booking; travel, food and extras are not included.");
        return new Plan(p.when(), p.month(), p.monthLabel(), p.budget(), p.spent(), p.allowance(), p.picks(), p.total(), p.left(),
                p.usualOuting(), p.usualCost(), p.saving(), p.leftOut(), List.copyOf(facts), p.why());
    }

    private PlanView view(Plan p) {
        YearMonth now = YearMonth.now(clock);
        return new PlanView(p.when(), p.month().toString(), p.monthLabel(), MoneyView.of(p.budget()), MoneyView.of(p.spent()),
                MoneyView.of(p.allowance()), p.picks().stream().map(c -> OptionView.of(c, now)).toList(), MoneyView.of(p.total()),
                MoneyView.of(p.left()), mv(p.usualOuting()), mv(p.usualCost()), mv(p.saving()), p.leftOut(), p.facts(), p.why(),
                catalogue.checkedOn());
    }

    /** A parent saves the month's outings (the engine checks them again). Everyone hears; the kids get the places only. */
    @Transactional
    public Saved save(CurrentMember me, When when, List<String> keys) {
        requireParent(me);
        if (keys == null || keys.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick at least one outing");
        }
        Plan p = plan(me.familyId(), when, Set.of(), keys);
        if (p.picks().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, String.join(" ", p.leftOut().isEmpty()
                    ? List.of("None of these outings fit " + p.monthLabel() + ".") : p.leftOut()));
        }
        List<Map<String, Object>> picks = p.picks().stream().map(c -> Map.<String, Object>of(
                "key", c.option().key(), "name", c.option().name(), "area", c.option().area(), "about", c.option().about(),
                "costMinor", c.cost().minor())).toList();
        try {
            db.sql("""
                    INSERT INTO outing_plan (family_id, month, picks, total_minor, saved_by)
                    VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                    ON CONFLICT (family_id, month)
                    DO UPDATE SET picks = EXCLUDED.picks, total_minor = EXCLUDED.total_minor, saved_by = EXCLUDED.saved_by,
                                  saved_at = now()
                    """).params(me.familyId(), p.month().atDay(1), json.writeValueAsString(picks), p.total().minor(), me.memberId())
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<String> names = p.picks().stream().map(c -> c.option().name()).toList();
        String places = names.size() <= 2 ? String.join(" and ", names) + "."
                : names.get(0) + ", " + names.get(1) + " and " + (names.size() - 2) + " more.";
        outbox.publish(me.familyId(), LiveKind.OUTINGS_PLANNED, me.memberId(), me.memberId(),
                Map.of("month", p.monthLabel(), "places", places));
        return saved(me).stream().filter(s -> s.month().equals(p.month().toString())).findFirst().orElseThrow();
    }

    /** This month's and later saved plans. Any member; a child sees the places without the prices. */
    @Transactional(readOnly = true)
    public List<Saved> saved(CurrentMember me) {
        String cur = currency(me.familyId());
        LocalDate from = YearMonth.now(clock).atDay(1);
        return db.sql("""
                SELECT p.month, p.picks::text, p.total_minor, m.display_name, p.saved_at
                FROM outing_plan p LEFT JOIN member m ON m.id = p.saved_by
                WHERE p.family_id = ? AND p.month >= ? ORDER BY p.month
                """).params(me.familyId(), from)
                .query((rs, n) -> {
                    YearMonth month = YearMonth.from(rs.getObject(1, LocalDate.class));
                    List<SavedPick> picks = new ArrayList<>();
                    for (Map<String, Object> x : readPicks(rs.getString(2))) {
                        Money cost = new Money(((Number) x.get("costMinor")).longValue(), cur);
                        picks.add(new SavedPick((String) x.get("key"), (String) x.get("name"), (String) x.get("area"),
                                (String) x.get("about"), me.isParent() ? MoneyView.of(cost) : null));
                    }
                    return new Saved(month.toString(), OutingEngine.label(month), List.copyOf(picks),
                            me.isParent() ? MoneyView.of(new Money(rs.getLong(3), cur)) : null, rs.getString(4),
                            rs.getTimestamp(5).toInstant().atZone(ClockConfig.UAE).toOffsetDateTime());
                }).list();
    }

    private List<Map<String, Object>> readPicks(String text) {
        try {
            return json.readValue(text, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- the family and its fun budget

    /** The parents first, then the children, oldest first. */
    List<Person> people(UUID family) {
        return db.sql("""
                SELECT display_name, age_band FROM member WHERE family_id = ?
                ORDER BY CASE age_band WHEN 'ADULT' THEN 0 WHEN 'TEEN_13_17' THEN 1 WHEN 'PRETEEN_10_12' THEN 2 ELSE 3 END,
                         created_at, display_name
                """)
                .param(family)
                .query((rs, n) -> new Person(rs.getString(1), AgeBand.valueOf(rs.getString(2)))).list();
    }

    Fun fun(UUID family, String cur) {
        BudgetEngine.Line line = budgets.month(family).lines().stream()
                .filter(l -> "ENTERTAINMENT".equals(l.category())).findFirst().orElse(null);
        long limit = line == null ? 0 : line.limit().minor();
        long spent = line == null ? 0 : line.spent().minor();

        // The usual outing: the parents' entertainment payments in the 3 months before this one, without subscriptions
        // (found in statements, on the commitments list, or paid the same in two months)
        Set<String> subscriptions = new HashSet<>();
        findings.subscriptionsFound(family).forEach(f -> subscriptions.add(norm(f.merchant())));
        db.sql("SELECT name FROM commitment WHERE family_id = ? AND kind = 'SUBSCRIPTION'").param(family)
                .query(String.class).list().forEach(n -> subscriptions.add(norm(n)));
        LocalDate monthStart = LocalDate.now(clock).withDayOfMonth(1);
        record Paid(String merchant, long minor, YearMonth month) {
        }
        List<Paid> paid = db.sql("""
                SELECT coalesce(t.merchant, t.description), t.amount_minor, t.occurred_on
                FROM ledger_transaction t LEFT JOIN member m ON m.id = t.member_id
                WHERE t.family_id = ? AND t.direction = 'DEBIT' AND t.category = 'ENTERTAINMENT'
                  AND t.duplicate_of_id IS NULL AND t.transfer_to_account_id IS NULL
                  AND (m.id IS NULL OR m.role = 'PARENT') AND t.occurred_on >= ? AND t.occurred_on < ?
                """).params(family, monthStart.minusMonths(3), monthStart)
                .query((rs, n) -> new Paid(norm(rs.getString(1)), rs.getLong(2), YearMonth.from(rs.getObject(3, LocalDate.class))))
                .list();
        // The same amount to the same place in two different months is a subscription, not an outing
        java.util.Map<String, Set<YearMonth>> months = new java.util.HashMap<>();
        paid.forEach(p -> months.computeIfAbsent(p.merchant() + "|" + p.minor(), k -> new HashSet<>()).add(p.month()));
        long[] sumAndCount = {0, 0};
        for (Paid p : paid) {
            if (!subscriptions.contains(p.merchant()) && months.get(p.merchant() + "|" + p.minor()).size() < 2) {
                sumAndCount[0] = Math.addExact(sumAndCount[0], p.minor());
                sumAndCount[1]++;
            }
        }
        Long usual = null;
        if (sumAndCount[1] > 0) {
            // Whole dirhams, so "about AED 374" times 4 is a number a parent can check
            usual = new Money(sumAndCount[0], cur).averageOver(sumAndCount[1]).scaled(1, 100).times(100).minor();
        }
        return new Fun(limit, spent, usual, (int) sumAndCount[1]);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }

    // ---------------------------------------------------------------- "on this week", with the cache

    /** This week's events by the rules of SEARCH_LIVE: fresh cache, live search, then any cached answer. */
    Events events() {
        Mode mode = pricing.mode();
        if (mode == Mode.OFF) {
            return new Events("NONE", null, List.of());
        }
        String key = events.engine() + "|" + EVENTS_QUERY.toLowerCase(Locale.ROOT);
        Optional<Cached> cached = cached(key);
        Instant now = clock.instant();
        if (cached.isPresent() && (mode == Mode.CACHE || cached.get().at().plus(cacheFor).isAfter(now))) {
            return parsed(cached.get().body(), "CACHE", cached.get().at());
        }
        if (mode == Mode.LIVE && events.available()) {
            Optional<JsonNode> body = events.search(EVENTS_QUERY).flatMap(this::readJson);
            if (body.isPresent() && !body.get().has("error")) {
                store(key, body.get(), now);
                return parsed(body.get(), "LIVE", now);
            }
            body.ifPresent(b -> log.info("Events search found nothing: {}", b.path("error").asText()));
        }
        return cached.map(c -> parsed(c.body(), "CACHE", c.at())).orElse(new Events("NONE", null, List.of()));
    }

    private Events parsed(JsonNode body, String source, Instant at) {
        List<EventView> list = EventsParser.parse(body, MAX_EVENTS).stream()
                .map((Event e) -> new EventView(e.title(), e.when(), e.where(), e.link(), e.priceNote())).toList();
        return new Events(list.isEmpty() ? "NONE" : source, at.atZone(ClockConfig.UAE).toOffsetDateTime(), list);
    }

    private record Cached(JsonNode body, Instant at) {
    }

    private Optional<Cached> cached(String key) {
        return db.sql("SELECT body::text, fetched_at FROM price_search_cache WHERE cache_key = ?").param(key)
                .query((rs, n) -> new Cached(readJson(rs.getString(1)).orElse(json.createObjectNode()), rs.getTimestamp(2).toInstant()))
                .optional();
    }

    private void store(String key, JsonNode body, Instant at) {
        db.sql("""
                INSERT INTO price_search_cache (cache_key, engine, query, body, fetched_at) VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (cache_key) DO UPDATE SET body = EXCLUDED.body, fetched_at = EXCLUDED.fetched_at
                """).params(key, events.engine(), EVENTS_QUERY, body.toString(), Timestamp.from(at)).update();
    }

    private Optional<JsonNode> readJson(String body) {
        try {
            return Optional.of(json.readTree(body));
        } catch (JsonProcessingException e) {
            log.warn("Events search answer was not JSON");
            return Optional.empty();
        }
    }

    private static MoneyView mv(Money m) {
        return m == null ? null : MoneyView.of(m);
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can plan the family's outings");
        }
    }
}
