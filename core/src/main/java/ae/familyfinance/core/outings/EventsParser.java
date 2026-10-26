package ae.familyfinance.core.outings;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a SerpApi Google Events answer into {@link Event}s: {@code events_results[]} with {@code title},
 * {@code date.when}, {@code address[]}, {@code link}, {@code venue.name} and {@code description}. A price is only
 * shown when the listing itself says one in dirhams ("Tickets from AED 45"); it is the listing's words, not ours,
 * and it is never added up.
 */
public final class EventsParser {

    public record Event(String title, String when, String where, String link, String priceNote) {
    }

    private static final Pattern PRICE = Pattern.compile("(?i)(?:from\\s+)?(?:AED|Dhs?)\\s?\\d[\\d,]*(?:\\.\\d{1,2})?");

    private EventsParser() {
    }

    public static List<Event> parse(JsonNode body, int max) {
        List<Event> out = new ArrayList<>();
        for (JsonNode r : body.path("events_results")) {
            String title = r.path("title").asText("").trim();
            String link = r.path("link").asText("").trim();
            if (title.isEmpty() || !link.startsWith("https://")) {
                continue;
            }
            String when = r.path("date").path("when").asText("").trim();
            String where = r.path("venue").path("name").asText("").trim();
            if (where.isEmpty() && r.path("address").isArray() && !r.path("address").isEmpty()) {
                where = r.path("address").get(0).asText("").trim();
            }
            out.add(new Event(cut(title, 120), cut(when, 80), cut(where, 80), link, priceNote(r.path("description").asText(""))));
            if (out.size() == max) {
                break;
            }
        }
        return out;
    }

    static String priceNote(String text) {
        Matcher m = PRICE.matcher(text == null ? "" : text);
        return m.find() ? m.group().trim() : null;
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n).trim() : s;
    }
}
