package ae.familyfinance.core.pricing;

import ae.familyfinance.core.money.Money;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a SerpApi Google Shopping answer ({@code engine=google_shopping}) into {@link Candidate}s.
 * Each result has a {@code title}, the shop as {@code source}, the price as text ({@code "AED 1,299.00"}) and
 * sometimes {@code delivery} ({@code "Free delivery"}, {@code "AED 15.00 delivery"}).
 * Prices are read from the text, not from SerpApi's {@code extracted_price} decimal, and only prices in dirhams
 * are kept. Delivery the shop doesn't state stays unknown.
 */
public final class SerpApiParser {

    private SerpApiParser() {
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d{1,2})?");
    private static final Pattern DIRHAMS = Pattern.compile("(?i)\\bAED\\b|\\bdhs?\\b|د\\.إ");
    private static final Pattern FREE = Pattern.compile("(?i)\\bfree\\b.*\\b(delivery|shipping)\\b|\\b(delivery|shipping)\\b.*\\bfree\\b");
    private static final Pattern PAID_DELIVERY = Pattern.compile("(?i)\\b(delivery|shipping)\\b");

    public static List<Candidate> parse(JsonNode body, Candidate.Source source, Instant fetchedAt) {
        List<Candidate> out = new ArrayList<>();
        for (String list : List.of("shopping_results", "inline_shopping_results")) {
            for (JsonNode r : body.path(list)) {
                Money price = dirhams(r.path("price").asText(""));
                String title = r.path("title").asText("").trim();
                String store = r.path("source").asText("").trim();
                if (price == null || !price.isPositive() || title.isEmpty() || store.isEmpty()) {
                    continue;
                }
                String link = text(r, "product_link", "link");
                out.add(new Candidate(store, title, price, delivery(r), Money.aed(0), link, null, source, fetchedAt, false));
            }
        }
        return out;
    }

    /** "AED 1,299.00", "Dhs 45", "د.إ.‏ 299.00" in fils; null when it isn't a dirham price. */
    static Money dirhams(String text) {
        if (text == null || !DIRHAMS.matcher(text).find()) {
            return null;
        }
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return null;
        }
        String number = m.group(1).replace(",", "") + (m.group(2) == null ? "" : m.group(2));
        return new Money(new BigDecimal(number).movePointRight(2).longValueExact(), Money.AED);
    }

    /** Free delivery is zero; a stated dirham amount is that amount; anything else is unknown (null). */
    static Money delivery(JsonNode r) {
        List<String> texts = new ArrayList<>();
        if (r.hasNonNull("delivery")) {
            texts.add(r.get("delivery").asText());
        }
        r.path("extensions").forEach(e -> texts.add(e.asText()));
        for (String t : texts) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (FREE.matcher(lower).find()) {
                return Money.aed(0);
            }
            if (PAID_DELIVERY.matcher(lower).find()) {
                Money m = dirhams(t);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode r, String... fields) {
        for (String f : fields) {
            if (r.hasNonNull(f) && !r.get(f).asText().isBlank()) {
                return r.get(f).asText();
            }
        }
        return null;
    }
}
