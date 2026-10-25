package ae.familyfinance.core.pricing;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.common.ClockConfig;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.pricing.Candidate.Source;
import ae.familyfinance.core.pricing.Catalogue.Item;
import ae.familyfinance.core.pricing.PriceEngine.Ranked;
import ae.familyfinance.core.requests.AffordabilityService;
import ae.familyfinance.core.requests.AffordabilityService.Assessment;
import ae.familyfinance.core.spend.SpendCategory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Where is it cheapest?" worked out by the core (POST /api/pricing/compare).
 * <ol>
 *   <li>The Shopper agent fills a {@link PurchaseIntent} from what the person typed. That is all it does.</li>
 *   <li>Search, in this order: a cached search from the last 24 hours, then a live search (SerpApi Google Shopping),
 *       then an older cached one, then our saved price list ({@link Catalogue}). SEARCH_LIVE picks how far it may go:
 *       {@code live} (all), {@code cache} (cache, any age, then the saved list; for recording the demo after
 *       ops/prewarm-prices.sh) or {@code off} (the saved list only).</li>
 *   <li>The {@link PriceEngine} ranks the offers by payable total and keeps substitutes apart.</li>
 *   <li>The top 3 are re-checked with a narrower search (that shop and that item). Only re-checked offers can be
 *       "best", so the promise is: the best re-checked offer among the shops we searched.</li>
 *   <li>The money engine and the family rules check the best offer for whoever asked, exactly as for any request.</li>
 * </ol>
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm 'on' d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    public enum Mode { LIVE, CACHE, OFF }

    /** One offer as the app shows it. {@code delivery} null = the shop doesn't say; {@code link} is the shop's page. */
    public record OfferView(String key, String store, String title, MoneyView price, MoneyView delivery,
                            boolean deliveryKnown, MoneyView fees, MoneyView total, Source source, boolean verified,
                            OffsetDateTime checkedAt, String link) {

        static OfferView of(String key, Candidate c) {
            return new OfferView(key, c.store(), c.title(), MoneyView.of(c.price()),
                    c.delivery() == null ? null : MoneyView.of(c.delivery()), c.deliveryKnown(), MoneyView.of(c.fees()),
                    MoneyView.of(c.total()), c.source(), c.verified(), c.checkedAt().atZone(ClockConfig.UAE).toOffsetDateTime(),
                    c.link());
        }

        OfferView withoutLink() {
            return new OfferView(key, store, title, price, delivery, deliveryKnown, fees, total, source, verified, checkedAt, null);
        }
    }

    /**
     * @param exact        a model (or GTIN) was asked for: {@code offers} are that item, {@code substitutes} other models
     * @param best         the offer to suggest, one of {@code offers}; null when nothing was found
     * @param shopsChecked how many different shops the offers came from
     * @param dropped      offers left out as priced too low to trust
     * @param assessment   the money engine and family rules on the best offer, for whoever asked (a child's own view)
     * @param facts        every sentence (and number) the agent may use
     */
    public record Comparison(UUID id, PurchaseIntent intent, String searchedFor, Source source, Mode mode,
                             OffsetDateTime checkedAt, boolean exact, List<OfferView> offers,
                             List<OfferView> substitutes, OfferView best, int shopsChecked, int dropped,
                             Assessment assessment, List<String> facts) {
    }

    /** What a request made from an offer needs. */
    public record ChosenOffer(OfferView offer, String category, UUID comparisonId, int offersCompared) {
    }

    record Search(List<Candidate> candidates, Source source, Instant at) {
    }

    private final ShoppingSource source;
    private final Catalogue catalogue;
    private final AffordabilityService affordability;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration cacheFor;
    private final int verifyTop;
    private volatile Mode mode;

    public PricingService(ShoppingSource source, Catalogue catalogue, AffordabilityService affordability, JdbcClient db,
                          ObjectMapper json, Clock clock,
                          @Value("${app.pricing.search-live:live}") String mode,
                          @Value("${app.pricing.cache-for:24h}") Duration cacheFor,
                          @Value("${app.pricing.verify-top:3}") int verifyTop) {
        this.source = source;
        this.catalogue = catalogue;
        this.affordability = affordability;
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.cacheFor = cacheFor;
        this.verifyTop = Math.max(0, verifyTop);
        this.mode = parseMode(mode);
    }

    static Mode parseMode(String text) {
        try {
            return Mode.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            log.warn("SEARCH_LIVE={} is not live, cache or off; using live", text);
            return Mode.LIVE;
        }
    }

    public Mode mode() {
        return mode;
    }

    /** For tests: the same service with another SEARCH_LIVE. */
    void useMode(Mode mode) {
        this.mode = mode;
    }

    public boolean liveAvailable() {
        return source.available();
    }

    public int catalogueSize() {
        return catalogue.size();
    }

    // ---------------------------------------------------------------- compare

    public Comparison compare(CurrentMember me, PurchaseIntent asked, UUID requesterId, UUID paidFromAccountId) {
        PurchaseIntent intent = clean(asked);
        Optional<Item> known = catalogue.exact(intent);
        String kind = known.map(Item::kind).orElse(null);
        Money usual = known.map(catalogue::usualPrice).orElse(null);
        String query = searchText(intent);

        Ranked ranked = null;
        Search found = search(query);
        if (found != null) {
            ranked = PriceEngine.rank(intent, found.candidates(), usual, kind);
            if (ranked.offers().isEmpty() && ranked.substitutes().isEmpty()) {
                ranked = null;  // the search found nothing that fits: the saved list may have it
            } else {
                ranked = verify(intent, ranked, found, usual, kind);
            }
        }
        Source from;
        Instant at;
        if (ranked != null) {
            from = found.source();
            at = found.at();
        } else {
            List<Candidate> saved = catalogue.candidates(catalogue.matching(intent));
            ranked = PriceEngine.rank(intent, saved, usual, kind);
            from = Source.SEEDED;
            at = catalogue.checkedOn().atStartOfDay(ClockConfig.UAE).toInstant();
        }

        List<OfferView> offers = new ArrayList<>();
        OfferView best = null;
        for (int i = 0; i < ranked.offers().size(); i++) {
            Candidate c = ranked.offers().get(i);
            OfferView v = OfferView.of("o" + (i + 1), c);
            offers.add(v);
            if (c == ranked.best()) {
                best = v;
            }
        }
        List<OfferView> substitutes = new ArrayList<>();
        for (int i = 0; i < ranked.substitutes().size(); i++) {
            substitutes.add(OfferView.of("s" + (i + 1), ranked.substitutes().get(i)));
        }
        Set<String> shops = new LinkedHashSet<>();
        ranked.offers().forEach(c -> shops.add(Words.normal(c.store())));

        SpendCategory category = spendCategory(intent.category() != null ? intent.category()
                : known.map(Item::category).orElse(null));
        Assessment assessment = null;
        if (best != null) {
            assessment = affordability.assess(me, requesterId, best.total().minor(), paidFromAccountId, best.title(), category);
            if (!me.isParent()) {
                assessment = assessment.forChild();
            }
        }
        List<String> facts = facts(intent, query, from, at, offers, substitutes, best, shops.size(), ranked.dropped());
        if (assessment != null) {
            facts.addAll(assessment.facts().subList(1, assessment.facts().size()));  // the first one repeats the price
        }

        UUID id = UUID.randomUUID();
        Comparison c = new Comparison(id, intent, query, from, mode, at.atZone(ClockConfig.UAE).toOffsetDateTime(),
                intent.exact(), List.copyOf(offers), List.copyOf(substitutes), best, shops.size(), ranked.dropped(),
                assessment, List.copyOf(facts));
        save(me, c);
        // A child sees the shops and prices, but buying is a parent's job: no links to the shops' pages
        return me.isParent() ? c : forChild(c);
    }

    private static Comparison forChild(Comparison c) {
        return new Comparison(c.id(), c.intent(), c.searchedFor(), c.source(), c.mode(), c.checkedAt(), c.exact(),
                c.offers().stream().map(OfferView::withoutLink).toList(),
                c.substitutes().stream().map(OfferView::withoutLink).toList(),
                c.best() == null ? null : c.best().withoutLink(), c.shopsChecked(), c.dropped(), c.assessment(), c.facts());
    }

    private static PurchaseIntent clean(PurchaseIntent i) {
        return new PurchaseIntent(i.query().trim(), blankToNull(i.brand()), blankToNull(i.model()),
                blankToNull(i.gtin()), blankToNull(i.category()), blankToNull(i.said()));
    }

    /** What is searched: the words, plus the brand and model when the words don't already say them. */
    static String searchText(PurchaseIntent i) {
        StringBuilder q = new StringBuilder();
        String words = Words.normal(i.query());
        if (PurchaseIntent.notBlank(i.brand()) && !words.contains(Words.normal(i.brand()))) {
            q.append(i.brand().trim()).append(' ');
        }
        if (PurchaseIntent.notBlank(i.model()) && !Words.flat(i.query()).contains(Words.flat(i.model()))) {
            q.append(i.model().trim()).append(' ');
        }
        return q.append(i.query().trim()).toString().trim();
    }

    // ---------------------------------------------------------------- searching, with the cache

    /** A search by the rules of SEARCH_LIVE, or null when there is no search result to use. */
    Search search(String query) {
        if (mode == Mode.OFF) {
            return null;
        }
        String key = source.engine() + "|" + Words.normal(query);
        Optional<Cached> cached = cached(key);
        Instant now = clock.instant();
        if (cached.isPresent() && (mode == Mode.CACHE || cached.get().at().plus(cacheFor).isAfter(now))) {
            return parse(cached.get().body(), Source.CACHE, cached.get().at());
        }
        if (mode == Mode.LIVE && source.available()) {
            Optional<JsonNode> body = source.search(query).flatMap(this::readJson);
            if (body.isPresent() && !body.get().has("error")) {
                store(key, query, body.get(), now);
                return parse(body.get(), Source.LIVE, now);
            }
            body.ifPresent(b -> log.info("Price search for \"{}\" found nothing: {}", query, b.path("error").asText()));
        }
        // An older search beats no search; the saved price list comes after both
        return cached.map(c -> parse(c.body(), Source.CACHE, c.at())).orElse(null);
    }

    private Search parse(JsonNode body, Source s, Instant at) {
        return new Search(SerpApiParser.parse(body, s, at), s, at);
    }

    private Optional<JsonNode> readJson(String body) {
        try {
            return Optional.of(json.readTree(body));
        } catch (JsonProcessingException e) {
            log.warn("Price search answer was not JSON");
            return Optional.empty();
        }
    }

    private record Cached(JsonNode body, Instant at) {
    }

    private Optional<Cached> cached(String key) {
        return db.sql("SELECT body::text, fetched_at FROM price_search_cache WHERE cache_key = ?").param(key)
                .query((rs, n) -> new Cached(readJson(rs.getString(1)).orElse(json.createObjectNode()),
                        rs.getTimestamp(2).toInstant()))
                .optional();
    }

    private void store(String key, String query, JsonNode body, Instant at) {
        db.sql("""
                INSERT INTO price_search_cache (cache_key, engine, query, body, fetched_at) VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (cache_key) DO UPDATE SET body = EXCLUDED.body, fetched_at = EXCLUDED.fetched_at
                """).params(key.length() > 300 ? key.substring(0, 300) : key, source.engine(),
                query.length() > 300 ? query.substring(0, 300) : query, body.toString(), Timestamp.from(at)).update();
    }

    // ---------------------------------------------------------------- re-checking the top offers

    /**
     * Re-checks the top offers with a narrower search: that item at that shop. An offer the second search finds at the
     * same shop takes the price and delivery found there and counts as re-checked; the others stay unchecked.
     */
    private Ranked verify(PurchaseIntent intent, Ranked ranked, Search found, Money usual, String kind) {
        if (verifyTop == 0 || found.source() == Source.SEEDED) {
            return ranked;
        }
        List<Candidate> all = new ArrayList<>(found.candidates());
        int n = Math.min(verifyTop, ranked.offers().size());
        boolean changed = false;
        for (Candidate top : ranked.offers().subList(0, n)) {
            Search check = search(top.title() + " " + top.store());
            if (check == null) {
                continue;
            }
            Optional<Candidate> again = check.candidates().stream()
                    .filter(c -> Words.normal(c.store()).equals(Words.normal(top.store())))
                    .filter(c -> sameItem(intent, top, c))
                    .min(PriceEngine.BY_TOTAL);
            if (again.isPresent()) {
                Candidate checked = top.checked(again.get().price(),
                        again.get().deliveryKnown() ? again.get().delivery() : top.delivery(), check.at());
                all.replaceAll(c -> c == top ? checked : c);
                changed = true;
            }
        }
        return changed ? PriceEngine.rank(intent, all, usual, kind) : ranked;
    }

    private static boolean sameItem(PurchaseIntent intent, Candidate first, Candidate again) {
        if (PurchaseIntent.notBlank(intent.model())) {
            return Words.hasModel(again.title(), intent.model());
        }
        String a = Words.flat(first.title());
        String b = Words.flat(again.title());
        return a.equals(b) || (b.length() >= 12 && a.contains(b)) || (a.length() >= 12 && b.contains(a));
    }

    // ---------------------------------------------------------------- the words the agent may use

    private List<String> facts(PurchaseIntent intent, String query, Source from, Instant at, List<OfferView> offers,
                               List<OfferView> substitutes, OfferView best, int shops, int dropped) {
        List<String> facts = new ArrayList<>();
        String when = at.atZone(ClockConfig.UAE).format(from == Source.SEEDED ? DAY : WHEN);
        if (offers.isEmpty()) {
            facts.add(intent.exact()
                    ? "I couldn't find \"" + query + "\" itself in the shops I searched."
                    : "I couldn't find \"" + query + "\" in the shops I searched.");
        } else {
            facts.add("I compared " + offers.size() + (offers.size() == 1 ? " offer" : " offers") + " from " + shops
                      + (shops == 1 ? " shop" : " shops") + " for \"" + query + "\" ("
                      + switch (from) {
                          case LIVE -> "live prices, checked at " + when;
                          case CACHE -> "prices checked at " + when;
                          case SEEDED -> "from our saved price list of " + when + ", not live prices";
                      } + ").");
        }
        if (best != null) {
            facts.add((best.verified() ? "Best re-checked total: " : "Lowest total: ") + best.total().text() + " at "
                      + best.store() + " (" + priceParts(best) + ").");
            if (!best.deliveryKnown()) {
                facts.add(best.store() + " doesn't list delivery, so check that before paying.");
            }
            offers.stream().filter(o -> o != best).findFirst()
                    .ifPresent(o -> facts.add("Next: " + o.total().text() + " at " + o.store()
                                              + (o.deliveryKnown() ? "" : " (delivery not listed)") + "."));
        }
        if (!substitutes.isEmpty()) {
            OfferView s = substitutes.get(0);
            facts.add("A different model, not the same item: " + s.title() + ", " + s.total().text() + " at " + s.store() + ".");
        }
        if (dropped > 0) {
            facts.add("I left out " + dropped + (dropped == 1 ? " offer" : " offers")
                      + " priced at less than half the usual price, which is too good to trust.");
        }
        return facts;
    }

    private static String priceParts(OfferView o) {
        String delivery = !o.deliveryKnown() ? "delivery not listed"
                : o.delivery().minor() == 0 ? "free delivery" : o.delivery().text() + " delivery";
        String fees = o.fees().minor() == 0 ? "" : " + " + o.fees().text() + " fees";
        return o.price().text() + " + " + delivery + fees;
    }

    // ---------------------------------------------------------------- saved comparisons and requests from them

    private void save(CurrentMember me, Comparison c) {
        try {
            db.sql("INSERT INTO price_comparison (id, family_id, member_id, intent, result) VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))")
                    .params(c.id(), me.familyId(), me.memberId(), json.writeValueAsString(c.intent()), json.writeValueAsString(c))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The offer a request is made from, read back from the saved comparison, so the price on the request is the
     * core's. A child can only use their own comparisons; a parent any of the family's.
     */
    public ChosenOffer chosen(CurrentMember me, UUID comparisonId, String key) {
        String body = db.sql("SELECT result::text FROM price_comparison WHERE id = ? AND family_id = ?"
                             + (me.isParent() ? "" : " AND member_id = ?"))
                .params(me.isParent() ? List.of(comparisonId, me.familyId()) : List.of(comparisonId, me.familyId(), me.memberId()))
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That price comparison is no longer here. Search again."));
        try {
            JsonNode c = json.readTree(body);
            List<JsonNode> all = new ArrayList<>();
            c.path("offers").forEach(all::add);
            c.path("substitutes").forEach(all::add);
            JsonNode offer = all.stream().filter(o -> o.path("key").asText().equals(key)).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That offer is not in this comparison"));
            String category = c.path("intent").path("category").isTextual() ? c.path("intent").path("category").asText() : null;
            return new ChosenOffer(json.treeToValue(offer, OfferView.class), category, comparisonId, all.size());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SpendCategory spendCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return SpendCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
