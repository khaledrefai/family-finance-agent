package ae.familyfinance.core.meals;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.budgets.BudgetService;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.common.ClockConfig;
import ae.familyfinance.core.live.LiveKind;
import ae.familyfinance.core.live.Outbox;
import ae.familyfinance.core.meals.MealEngine.Costed;
import ae.familyfinance.core.meals.MealEngine.Delivery;
import ae.familyfinance.core.meals.MealEngine.Eater;
import ae.familyfinance.core.meals.MealEngine.FoodMoney;
import ae.familyfinance.core.meals.MealEngine.Line;
import ae.familyfinance.core.meals.MealEngine.Night;
import ae.familyfinance.core.meals.MealEngine.NightKind;
import ae.familyfinance.core.meals.MealEngine.Plan;
import ae.familyfinance.core.meals.MealEngine.Row;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.rules.Allergen;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The weekly dinner plan and shopping list.
 * <ol>
 *   <li>Recipes, grocery prices and delivery dinners come from our saved lists ({@link MealCatalogue}), so a plan never
 *       waits on a shop's website.</li>
 *   <li>Who eats what: each member's food profile (halal, vegetarian, vegan, dislikes) plus the family's allergy
 *       rules, so one "no peanuts for Adam" rule covers ask-to-buy and dinners.</li>
 *   <li>The {@link MealEngine} picks or checks the dinners, adds up the shopping list in whole packs and checks it
 *       against the week's grocery money. The Planner agent may pick dinners from a parent's words; the core checks
 *       its pick the same way.</li>
 *   <li>A parent saves the week; everyone hears what's for dinner, the kids without the prices.</li>
 * </ol>
 */
@Service
public class MealsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    public record ProfileView(UUID memberId, String name, boolean child, boolean halal, boolean vegetarian, boolean vegan,
                              List<String> dislikes, List<String> allergies) {
    }

    public record RecipeView(String key, String name, String main, int minutes, String about, MoneyView cost,
                             boolean vegetarian, boolean suits, String unsuitable) {

        static RecipeView of(Costed c) {
            return new RecipeView(c.recipe().key(), c.recipe().name(), c.recipe().main().name(), c.recipe().minutes(),
                    c.recipe().about(), MoneyView.of(c.cost()), c.recipe().vegetarian(), c.suits(), c.unsuitable());
        }
    }

    public record DeliveryView(String key, String name, String about, MoneyView price, boolean suits, String unsuitable) {
    }

    /** One night. {@code cost} is null for a child. {@code minutes} only for a cooked dinner. */
    public record NightView(LocalDate date, String label, NightKind kind, String key, String name, String about,
                            Integer minutes, MoneyView cost) {
    }

    public record LineView(String key, String name, String pack, long packs, MoneyView cost, String uses, String aisle,
                           boolean halalMark) {
    }

    /** A week's dinners as the app shows it. {@code pricesFrom}: the date the saved price list was typed in. */
    public record PlanView(LocalDate start, LocalDate end, String weekLabel, List<NightView> nights, List<LineView> list,
                           List<String> staples, MoneyView shopping, MoneyView dinners, MoneyView inPacks,
                           MoneyView groceryWeek, MoneyView forDinners, MoneyView weekLeft, MoneyView over, String swap,
                           MoneyView delivery, MoneyView eatingOutLeft, MoneyView cookInstead, List<String> leftOut,
                           List<String> facts, List<Row> why, LocalDate pricesFrom) {
    }

    /** The saved week. Kids get the nights without prices, and no shopping list. */
    public record Saved(LocalDate weekStart, String weekLabel, List<NightView> nights, MoneyView shopping, MoneyView delivery,
                        String savedBy, OffsetDateTime savedAt, PlanView plan) {
    }

    public record Day(LocalDate date, String label) {
    }

    /** Everything the dinners screen shows before a plan: the week, the budgets, who eats what, the saved lists. */
    public record Board(LocalDate weekStart, String weekLabel, List<Day> days, MoneyView groceryLimit, MoneyView grocerySpent,
                        MoneyView groceryWeek, MoneyView forDinners, MoneyView eatingOutLimit, MoneyView eatingOutLeft,
                        List<ProfileView> profiles, List<RecipeView> recipes, List<DeliveryView> deliveries, Saved saved,
                        LocalDate pricesFrom) {
    }

    /** A parent's changes to one member's food profile. {@code dislikes}: comma separated, as typed. */
    public record ProfileChange(UUID memberId, boolean halal, boolean vegetarian, boolean vegan, String dislikes) {
    }

    private final MealCatalogue catalogue;
    private final BudgetService budgets;
    private final Outbox outbox;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;

    public MealsService(MealCatalogue catalogue, BudgetService budgets, Outbox outbox, JdbcClient db, ObjectMapper json,
                        Clock clock) {
        this.catalogue = catalogue;
        this.budgets = budgets;
        this.outbox = outbox;
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the screen

    @Transactional(readOnly = true)
    public Board board(CurrentMember me) {
        requireParent(me);
        UUID family = me.familyId();
        String cur = currency(family);
        LocalDate start = LocalDate.now(clock);
        List<Eater> eaters = eaters(family);
        FoodMoney money = money(family, cur);
        List<RecipeView> recipes = MealEngine.costAll(catalogue.recipes(), eaters, cur).stream()
                .sorted(java.util.Comparator.comparing((Costed c) -> !c.suits()).thenComparing(Costed::cost))
                .map(RecipeView::of).toList();
        List<DeliveryView> deliveries = catalogue.deliveries().stream()
                .sorted(java.util.Comparator.comparing(Delivery::price))
                .map(d -> {
                    String why = MealEngine.unsuitable(d, eaters);
                    return new DeliveryView(d.key(), d.name(), d.about(), MoneyView.of(d.price()), why == null, why);
                }).toList();
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < MealEngine.NIGHTS; i++) {
            days.add(new Day(start.plusDays(i), DAY.format(start.plusDays(i))));
        }
        Money week = money.groceryLimit().isPositive() ? MealEngine.groceryWeek(money.groceryLimit()) : null;
        Money eatingOutLeft = money.eatingOutLimit().isPositive() ? money.eatingOutLimit().minus(money.eatingOutSpent()) : null;
        return new Board(start, DAY.format(start) + " to " + DAY.format(start.plusDays(MealEngine.NIGHTS - 1)), days,
                MoneyView.of(money.groceryLimit()), MoneyView.of(money.grocerySpent()), mv(week),
                week == null ? null : MoneyView.of(MealEngine.forDinners(week)), MoneyView.of(money.eatingOutLimit()),
                mv(eatingOutLeft), profiles(family), recipes, deliveries, saved(me), catalogue.checkedOn());
    }

    // ---------------------------------------------------------------- plans

    /** The week from today. {@code keys} empty: the engine's own pick. Otherwise the Planner's or parent's, checked. */
    @Transactional(readOnly = true)
    public PlanView plan(CurrentMember me, List<String> keys, Set<LocalDate> deliveryDays, List<String> deliveryKeys) {
        requireParent(me);
        return view(plan(me.familyId(), keys, deliveryDays, deliveryKeys), true);
    }

    Plan plan(UUID family, List<String> keys, Set<LocalDate> deliveryDays, List<String> deliveryKeys) {
        String cur = currency(family);
        List<Eater> eaters = eaters(family);
        Plan p = MealEngine.plan(LocalDate.now(clock), MealEngine.costAll(catalogue.recipes(), eaters, cur),
                catalogue.deliveries(), eaters, money(family, cur), keys, deliveryDays, deliveryKeys, cur);
        List<String> facts = new ArrayList<>(p.facts());
        facts.add("Prices are from our saved list of " + catalogue.checkedOn().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                  + ", not live. Shop prices vary.");
        return new Plan(p.start(), p.end(), p.weekLabel(), p.nights(), p.list(), p.staples(), p.shopping(), p.dinners(),
                p.inPacks(), p.groceryWeek(), p.forDinners(), p.weekLeft(), p.over(), p.swap(), p.delivery(), p.eatingOutLeft(),
                p.cookInstead(), p.leftOut(), List.copyOf(facts), p.why());
    }

    private PlanView view(Plan p, boolean prices) {
        List<NightView> nights = p.nights().stream().map(n -> night(n, prices)).toList();
        List<LineView> list = p.list().stream().map((Line l) -> new LineView(l.item().key(), l.item().name(), l.item().pack(),
                l.packs(), MoneyView.of(l.cost()), l.uses(), l.item().aisle().name(), l.halalMark())).toList();
        return new PlanView(p.start(), p.end(), p.weekLabel(), nights, list, p.staples(), MoneyView.of(p.shopping()),
                MoneyView.of(p.dinners()), MoneyView.of(p.inPacks()), mv(p.groceryWeek()), mv(p.forDinners()), mv(p.weekLeft()),
                mv(p.over()), p.swap(), MoneyView.of(p.delivery()), mv(p.eatingOutLeft()), mv(p.cookInstead()), p.leftOut(),
                p.facts(), p.why(), catalogue.checkedOn());
    }

    private static NightView night(Night n, boolean prices) {
        return n.kind() == NightKind.COOK
                ? new NightView(n.date(), n.label(), n.kind(), n.dinner().recipe().key(), n.dinner().recipe().name(),
                n.dinner().recipe().about(), n.dinner().recipe().minutes(), prices ? MoneyView.of(n.cost()) : null)
                : new NightView(n.date(), n.label(), n.kind(), n.delivery().key(), n.delivery().name(), n.delivery().about(),
                null, prices ? MoneyView.of(n.cost()) : null);
    }

    /** A parent saves the week (the engine checks it again). It replaces a saved week that overlaps it. */
    @Transactional
    public Saved save(CurrentMember me, List<String> keys, Set<LocalDate> deliveryDays, List<String> deliveryKeys) {
        requireParent(me);
        Plan p = plan(me.familyId(), keys, deliveryDays, deliveryKeys);
        if (p.nights().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "No saved dinner suits everyone in the family yet.");
        }
        PlanView view = view(p, true);
        db.sql("DELETE FROM meal_plan WHERE family_id = ? AND week_start > ? AND week_start < ?")
                .params(me.familyId(), p.start().minusDays(MealEngine.NIGHTS), p.start().plusDays(MealEngine.NIGHTS)).update();
        try {
            db.sql("""
                    INSERT INTO meal_plan (family_id, week_start, plan, shopping_minor, delivery_minor, saved_by)
                    VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """).params(me.familyId(), p.start(), json.writeValueAsString(view), p.shopping().minor(),
                    p.delivery().minor(), me.memberId()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<String> names = p.nights().stream().map(n -> n.kind() == NightKind.COOK ? n.name() : "a delivery night").toList();
        String dinners = "Tonight: " + names.get(0) + (names.size() > 1 ? ". Then " + names.get(1).toLowerCase(Locale.ROOT)
                + (names.size() > 2 ? " and " + (names.size() - 2) + " more." : ".") : ".");
        outbox.publish(me.familyId(), LiveKind.MEALS_PLANNED, me.memberId(), me.memberId(), Map.of("dinners", dinners));
        return saved(me);
    }

    /** The saved week that covers today, or null. Any member; a child sees the dinners without the prices. */
    @Transactional(readOnly = true)
    public Saved saved(CurrentMember me) {
        LocalDate today = LocalDate.now(clock);
        return db.sql("""
                SELECT p.plan::text, m.display_name, p.saved_at
                FROM meal_plan p LEFT JOIN member m ON m.id = p.saved_by
                WHERE p.family_id = ? AND p.week_start <= ? AND p.week_start > ?
                ORDER BY p.week_start DESC LIMIT 1
                """).params(me.familyId(), today, today.minusDays(MealEngine.NIGHTS))
                .query((rs, n) -> {
                    PlanView plan;
                    try {
                        plan = json.readValue(rs.getString(1), PlanView.class);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                    OffsetDateTime at = rs.getTimestamp(3).toInstant().atZone(ClockConfig.UAE).toOffsetDateTime();
                    if (me.isParent()) {
                        return new Saved(plan.start(), plan.weekLabel(), plan.nights(), plan.shopping(), plan.delivery(),
                                rs.getString(2), at, plan);
                    }
                    List<NightView> nights = plan.nights().stream().map(x -> new NightView(x.date(), x.label(), x.kind(),
                            x.key(), x.name(), x.about(), x.minutes(), null)).toList();
                    return new Saved(plan.start(), plan.weekLabel(), nights, null, null, rs.getString(2), at, null);
                }).optional().orElse(null);
    }

    // ---------------------------------------------------------------- who eats what

    @Transactional
    public List<ProfileView> updateProfiles(CurrentMember me, List<ProfileChange> changes) {
        requireParent(me);
        for (ProfileChange c : changes) {
            Integer found = db.sql("SELECT count(*) FROM member WHERE id = ? AND family_id = ?")
                    .params(c.memberId(), me.familyId()).query(Integer.class).single();
            if (found == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "No such member in this family");
            }
            String dislikes = String.join(", ", dislikes(c.dislikes()));
            if (dislikes.length() > 300) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Keep the foods someone doesn't like to a short list");
            }
            db.sql("""
                    INSERT INTO member_food_profile (member_id, family_id, halal, vegetarian, vegan, dislikes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (member_id) DO UPDATE SET halal = EXCLUDED.halal, vegetarian = EXCLUDED.vegetarian,
                        vegan = EXCLUDED.vegan, dislikes = EXCLUDED.dislikes, updated_at = now()
                    """).params(c.memberId(), me.familyId(), c.halal(), c.vegetarian() || c.vegan(), c.vegan(), dislikes)
                    .update();
        }
        return profiles(me.familyId());
    }

    /** Parents first, then the children, oldest first; with each one's allergies from the family's active rules. */
    List<ProfileView> profiles(UUID family) {
        Map<UUID, Set<Allergen>> allergies = allergies(family);
        return db.sql("""
                SELECT m.id, m.display_name, m.role, coalesce(f.halal, false), coalesce(f.vegetarian, false),
                       coalesce(f.vegan, false), coalesce(f.dislikes, '')
                FROM member m LEFT JOIN member_food_profile f ON f.member_id = m.id
                WHERE m.family_id = ?
                ORDER BY CASE m.age_band WHEN 'ADULT' THEN 0 WHEN 'TEEN_13_17' THEN 1 WHEN 'PRETEEN_10_12' THEN 2 ELSE 3 END,
                         m.created_at, m.display_name
                """).param(family)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new ProfileView(id, rs.getString(2), "CHILD".equals(rs.getString(3)), rs.getBoolean(4),
                            rs.getBoolean(5), rs.getBoolean(6), dislikes(rs.getString(7)),
                            allergies.getOrDefault(id, Set.of()).stream().map(Allergen::label).toList());
                }).list();
    }

    List<Eater> eaters(UUID family) {
        Map<UUID, Set<Allergen>> allergies = allergies(family);
        return profiles(family).stream().map(p -> new Eater(p.name(), p.halal(), p.vegetarian(), p.vegan(), p.dislikes(),
                allergies.getOrDefault(p.memberId(), Set.of()))).toList();
    }

    /** Active allergy rules: one for a child, or (no child named) for every child. */
    private Map<UUID, Set<Allergen>> allergies(UUID family) {
        List<UUID> children = db.sql("SELECT id FROM member WHERE family_id = ? AND role = 'CHILD'").param(family)
                .query(UUID.class).list();
        Map<UUID, Set<Allergen>> out = new HashMap<>();
        db.sql("""
                SELECT applies_to_member_id, allergen FROM family_rule
                WHERE family_id = ? AND kind = 'BLOCK_ALLERGEN' AND status = 'ACTIVE' AND allergen IS NOT NULL
                """).param(family)
                .query((rs, n) -> {
                    UUID who = rs.getObject(1, UUID.class);
                    Allergen a = Allergen.valueOf(rs.getString(2));
                    for (UUID id : who == null ? children : List.of(who)) {
                        out.computeIfAbsent(id, k -> EnumSet.noneOf(Allergen.class)).add(a);
                    }
                    return null;
                }).list();
        return out;
    }

    static List<String> dislikes(String typed) {
        if (typed == null || typed.isBlank()) {
            return List.of();
        }
        return Arrays.stream(typed.split("[,;\\n]")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .distinct().limit(12).toList();
    }

    // ---------------------------------------------------------------- the budgets

    FoodMoney money(UUID family, String cur) {
        List<BudgetEngine.Line> lines = budgets.month(family).lines();
        BudgetEngine.Line groceries = lines.stream().filter(l -> "GROCERIES".equals(l.category())).findFirst().orElse(null);
        BudgetEngine.Line out = lines.stream().filter(l -> "EATING_OUT".equals(l.category())).findFirst().orElse(null);
        return new FoodMoney(
                new Money(groceries == null ? 0 : groceries.limit().minor(), cur),
                new Money(groceries == null ? 0 : groceries.spent().minor(), cur),
                new Money(out == null ? 0 : out.limit().minor(), cur),
                new Money(out == null ? 0 : out.spent().minor(), cur));
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }

    private static MoneyView mv(Money m) {
        return m == null ? null : MoneyView.of(m);
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can plan the family's dinners");
        }
    }
}
