package ae.familyfinance.core.gold;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.gold.GoldEngine.Holding;
import ae.familyfinance.core.gold.GoldEngine.Value;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Amounts;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Saving in gold: the gold a family already holds, valued at today's price per gram. Saving, not investing:
 * the app records what the parents bought and never suggests buying gold, an amount or a time. Parents only.
 *
 * <p>The price per purity: a real price before a seeded one, the family's own (typed in from the shop) before the
 * shared one, then the newest. The shared price is the seeded fallback for now; a daily feed comes later (source
 * still to be chosen). The screen always says which price it used and when.
 */
@Service
public class GoldService {

    public static final String MAKING_NOTE = "Jewellery prices include making charges, so it is worth less than you paid "
            + "if you sell it by weight.";

    public record NewGold(String description, String grams, String purity, String paid, LocalDate boughtOn,
                          String seller, UUID goalId, String bucket, boolean jewellery) {
    }

    public record Price(String purity, MoneyView perGram, OffsetDateTime asOf, String source, boolean seeded,
                        boolean typedByFamily) {
    }

    public record HoldingView(UUID id, String description, String grams, long gramsMg, String purity, MoneyView paid,
                              LocalDate boughtOn, String seller, String bucket, UUID goalId, String goalName,
                              boolean jewellery, MoneyView value, boolean belowPaid) {
    }

    public record GramsLine(String purity, String grams, long gramsMg, MoneyView value) {
    }

    public record GoalLine(UUID goalId, String name, String grams, MoneyView value) {
    }

    public record GoldView(MoneyView value, MoneyView paid, List<GramsLine> grams, List<GoalLine> byGoal,
                           List<HoldingView> holdings, List<Price> prices, OffsetDateTime priceAsOf, String priceSource,
                           boolean priceIsSample, String makingNote) {
    }

    /** What the wealth view needs. */
    public record Total(Money value, LocalDate priceAsOf, boolean any) {
    }

    private final JdbcClient db;
    private final Clock clock;

    public GoldService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GoldView view(CurrentMember me) {
        requireParent(me);
        String cur = currency(me.familyId());
        Map<String, Price> prices = prices(me.familyId(), cur);
        Map<String, Long> perGram = perGram(prices);
        List<HoldingView> holdings = holdings(me.familyId(), cur, perGram);

        Money value = Money.zero(cur);
        Money paid = Money.zero(cur);
        Map<String, long[]> byPurity = new LinkedHashMap<>();
        Map<UUID, Object[]> byGoal = new LinkedHashMap<>();
        for (HoldingView h : holdings) {
            value = value.plus(money(h.value()));
            paid = paid.plus(money(h.paid()));
            byPurity.computeIfAbsent(h.purity(), k -> new long[2]);
            byPurity.get(h.purity())[0] = Math.addExact(byPurity.get(h.purity())[0], h.gramsMg());
            byPurity.get(h.purity())[1] = Math.addExact(byPurity.get(h.purity())[1], h.value().minor());
            if (h.goalId() != null) {
                Object[] g = byGoal.computeIfAbsent(h.goalId(), k -> new Object[]{h.goalName(), 0L, 0L});
                g[1] = Math.addExact((Long) g[1], h.gramsMg());
                g[2] = Math.addExact((Long) g[2], h.value().minor());
            }
        }
        List<GramsLine> grams = new ArrayList<>();
        for (String p : GoldEngine.PURITIES) {
            long[] t = byPurity.get(p);
            if (t != null) {
                grams.add(new GramsLine(p, GoldEngine.grams(t[0]), t[0], MoneyView.of(new Money(t[1], cur))));
            }
        }
        List<GoalLine> goals = new ArrayList<>();
        byGoal.forEach((id, g) -> goals.add(new GoalLine(id, (String) g[0], GoldEngine.grams((Long) g[1]),
                MoneyView.of(new Money((Long) g[2], cur)))));
        Price main = prices.get("24K");
        return new GoldView(MoneyView.of(value), MoneyView.of(paid), grams, goals, holdings,
                new ArrayList<>(prices.values()), main.asOf(), main.source(), main.seeded(), MAKING_NOTE);
    }

    /** The family's gold at today's price, for the wealth view. */
    @Transactional(readOnly = true)
    public Total total(UUID family, String cur) {
        Map<String, Price> prices = prices(family, cur);
        Money value = Money.zero(cur);
        List<HoldingView> holdings = holdings(family, cur, perGram(prices));
        for (HoldingView h : holdings) {
            value = value.plus(money(h.value()));
        }
        OffsetDateTime asOf = prices.values().stream().map(Price::asOf).min(OffsetDateTime::compareTo).orElseThrow();
        return new Total(value, asOf.atZoneSameInstant(clock.getZone()).toLocalDate(), !holdings.isEmpty());
    }

    @Transactional
    public HoldingView log(CurrentMember me, NewGold in) {
        requireParent(me);
        String cur = currency(me.familyId());
        Long mg = GoldEngine.parseGrams(in.grams());
        if (mg == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the weight in grams, like 10 or 2.5");
        }
        String purity = purity(in.purity());
        Money paid = Amounts.parse(in.paid(), cur, "price paid");
        LocalDate today = LocalDate.now(clock);
        LocalDate on = in.boughtOn() == null ? today : in.boughtOn();
        if (on.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That date is in the future");
        }
        String bucket = in.bucket() == null || in.bucket().isBlank() ? "GOALS" : in.bucket().toUpperCase(Locale.ROOT);
        if (!List.of("GOALS", "INVEST_ASIDE").contains(bucket)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Gold is kept for a goal or set aside for investing");
        }
        if (in.goalId() != null) {
            if (!bucket.equals("GOALS")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A goal's gold comes from the Goals bucket");
            }
            db.sql("SELECT id FROM goal WHERE id = ? AND family_id = ? AND member_id IS NULL")
                    .params(in.goalId(), me.familyId()).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Goal not found"));
        }
        String description = in.description() == null || in.description().isBlank()
                ? "Gold, " + purity : cut(in.description().trim(), 120);
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO gold_holding (id, family_id, description, grams_mg, purity, paid_minor, currency, bought_on,
                                          seller, bucket, goal_id, is_jewellery, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), description, mg, purity, paid.minor(), cur, on,
                        in.seller() == null || in.seller().isBlank() ? null : cut(in.seller().trim(), 120), bucket,
                        in.goalId(), in.jewellery(), me.memberId())
                .update();
        return holdings(me.familyId(), cur, perGram(prices(me.familyId(), cur))).stream()
                .filter(h -> h.id().equals(id)).findFirst().orElseThrow();
    }

    /** Sold or given away: kept for history, no longer counted. */
    @Transactional
    public void archive(CurrentMember me, UUID id) {
        requireParent(me);
        int n = db.sql("UPDATE gold_holding SET status = 'ARCHIVED' WHERE id = ? AND family_id = ? AND status = 'ACTIVE'")
                .params(id, me.familyId()).update();
        if (n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Gold not found");
        }
    }

    /** Today's price per gram, as a parent read it at the shop. Used for this family only. */
    @Transactional
    public Price setPrice(CurrentMember me, String purityText, String perGramText) {
        requireParent(me);
        String cur = currency(me.familyId());
        String purity = purity(purityText);
        Money perGram = Amounts.parse(perGramText, cur, "price per gram");
        String name = db.sql("SELECT display_name FROM member WHERE id = ?").param(me.memberId()).query(String.class).single();
        db.sql("""
                INSERT INTO gold_price (family_id, purity, per_gram_minor, currency, as_of, source)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(me.familyId(), purity, perGram.minor(), cur, OffsetDateTime.now(clock),
                        cut("Typed in by " + name, 120))
                .update();
        return prices(me.familyId(), cur).get(purity);
    }

    // ------------------------------------------------------------------ queries

    Map<String, Price> prices(UUID family, String cur) {
        Map<String, Price> out = new LinkedHashMap<>();
        for (String p : GoldEngine.PURITIES) {
            db.sql("""
                    SELECT per_gram_minor, as_of, source, seeded, family_id IS NOT NULL
                    FROM gold_price WHERE purity = ? AND (family_id = ? OR family_id IS NULL) AND currency = ?
                    ORDER BY seeded, family_id NULLS LAST, as_of DESC LIMIT 1
                    """).params(p, family, cur)
                    .query((rs, n) -> new Price(p, MoneyView.of(new Money(rs.getLong(1), cur)),
                            rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)))
                    .optional().ifPresent(price -> out.put(p, price));
        }
        if (out.size() < GoldEngine.PURITIES.size()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The gold price isn't set up yet");
        }
        return out;
    }

    private List<HoldingView> holdings(UUID family, String cur, Map<String, Long> perGram) {
        return db.sql("""
                SELECT h.id, h.description, h.grams_mg, h.purity, h.paid_minor, h.bought_on, h.seller, h.bucket,
                       h.goal_id, g.name, h.is_jewellery
                FROM gold_holding h LEFT JOIN goal g ON g.id = h.goal_id
                WHERE h.family_id = ? AND h.status = 'ACTIVE' ORDER BY h.bought_on, h.created_at
                """).param(family)
                .query((rs, n) -> {
                    long mg = rs.getLong(3);
                    String purity = rs.getString(4);
                    Value v = GoldEngine.value(new Holding(purity, mg, rs.getLong(5), rs.getBoolean(11)), perGram, cur);
                    String bucket = rs.getString(8);
                    String goalName = rs.getString(10);
                    return new HoldingView(rs.getObject(1, UUID.class), rs.getString(2), GoldEngine.grams(mg), mg, purity,
                            MoneyView.of(v.paid()), rs.getObject(6, LocalDate.class), rs.getString(7), bucket,
                            rs.getObject(9, UUID.class),
                            goalName != null ? goalName : bucket.equals("INVEST_ASIDE") ? "Set aside for investing" : "Savings",
                            rs.getBoolean(11), MoneyView.of(v.value()), v.belowPaid());
                }).list();
    }

    private static Map<String, Long> perGram(Map<String, Price> prices) {
        Map<String, Long> out = new LinkedHashMap<>();
        prices.forEach((k, v) -> out.put(k, v.perGram().minor()));
        return out;
    }

    private static Money money(MoneyView v) {
        return new Money(v.minor(), v.currency());
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }

    private static String purity(String text) {
        String p = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        if (!GoldEngine.PURITIES.contains(p)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick the purity: " + String.join(", ", GoldEngine.PURITIES));
        }
        return p;
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family's gold");
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
