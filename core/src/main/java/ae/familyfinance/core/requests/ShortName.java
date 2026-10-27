package ae.familyfinance.core.requests;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A short name for a shop's long product title, for the request card, the notifications and the kid's list:
 * "Sony WH-CH720N Wireless Noise Cancelling Over-Ear Headphones" becomes "Sony WH-CH720N headphones".
 * The brand, the model (or the first word that says something), then what kind of thing it is.
 */
final class ShortName {

    static final int MAX = 32;

    /** Kinds of thing, longest first so "air fryer" wins over "fryer". */
    private static final List<String> KINDS = List.of(
            "washing machine", "building set", "board game", "card game", "air fryer", "smartwatch", "headphones",
            "controller", "calculator", "backpack", "earbuds", "football", "speaker", "scooter", "console", "e-reader",
            "tablet", "laptop", "vacuum", "stroller", "blender", "mixer", "shoes", "phone", "bike", "game", "tv");

    private static final Set<String> SKIP = Set.of("by", "the", "kids", "kid's", "wireless", "smart", "portable",
            "classic", "original", "new", "official");

    private ShortName() {
    }

    static String of(String title) {
        String t = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (t.length() <= MAX) {
            return t;
        }
        String[] w = t.split(" ");
        String lower = t.toLowerCase(Locale.ROOT);
        String kind = KINDS.stream().filter(k -> lower.contains(k) || lower.contains(k.replace(" ", ""))).findFirst()
                .orElse(null);
        if (kind == null || w.length < 2) {
            return String.join(" ", List.of(w).subList(0, Math.min(4, w.length)));
        }
        String second = null;
        for (int i = 1; i < Math.min(4, w.length) && second == null; i++) {
            if (model(w[i])) {
                second = w[i];
            }
        }
        for (int i = 1; i < w.length && second == null; i++) {
            if (!SKIP.contains(w[i].toLowerCase(Locale.ROOT)) && !size(w[i])) {
                second = w[i];
            }
        }
        String flat = second == null ? "" : second.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        boolean saysKind = flat.contains(kind.replaceAll("[^a-z0-9]", ""));
        String shown = kind.equals("tv") ? "TV" : kind;
        return w[0] + (second == null ? "" : " " + second) + (saysKind ? "" : " " + shown);
    }

    /** A model code: letters and digits together, like WH-CH720N or JR310BT, but not a size like 55-inch or 64GB. */
    private static boolean model(String word) {
        return word.matches(".*[0-9].*") && word.matches(".*[A-Za-z].*") && !size(word);
    }

    private static boolean size(String word) {
        String x = word.toLowerCase(Locale.ROOT);
        return x.matches(".*[0-9].*") && x.matches(".*(inch|gb|tb|cm|mm|ml|kg|oz|[0-9]l|[0-9]g)\\)?$");
    }
}
