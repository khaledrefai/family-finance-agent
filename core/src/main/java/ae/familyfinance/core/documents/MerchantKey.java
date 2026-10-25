package ae.familyfinance.core.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A short, stable name for a shop, so the same shop matches across a receipt, a typed payment and a statement:
 * "SPINNEYS JUMEIRAH DUBAI AE" and "Spinneys" are both SPINNEYS. Short first words keep the second word
 * ("AL MAYA", "DU TELECOM"). Tokens with digits (branch and terminal numbers) and place names are dropped.
 */
public final class MerchantKey {

    private static final Set<String> NOISE = Set.of("DUBAI", "ABU", "DHABI", "SHARJAH", "AJMAN", "AE", "ARE", "UAE",
            "LLC", "LTD", "CO", "COM", "FZE", "FZCO", "BRANCH", "POS", "PURCHASE", "CARD", "THE");

    private MerchantKey() {
    }

    /** {@code null} when nothing is left to go on. */
    public static String of(String text) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return null;
        }
        String key = words.get(0).length() <= 3 && words.size() > 1 ? words.get(0) + " " + words.get(1) : words.get(0);
        return key.length() > 60 ? key.substring(0, 60) : key;
    }

    /** A shop's name for people, from what the statement says: "SHAHID VIP DUBAI" -> "Shahid VIP". */
    public static String display(String text) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return text;
        }
        return label(String.join(" ", words.subList(0, Math.min(2, words.size()))));
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String w : text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim().split("\\s+")) {
            if (!w.isEmpty() && !w.matches(".*[0-9].*") && !NOISE.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    /** Both name the same shop: the same first word. */
    public static boolean same(String a, String b) {
        return a != null && b != null && first(a).equals(first(b));
    }

    /** True when a commitment's name already covers this shop ("du home and mobile" covers DU TELECOM). */
    public static boolean coveredBy(String key, String commitmentName) {
        if (key == null || commitmentName == null) {
            return false;
        }
        Set<String> names = new java.util.HashSet<>(List.of(commitmentName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim().split("\\s+")));
        for (String w : key.split(" ")) {
            if (w.length() >= 2 && names.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** "SHAHID VIP" -> "Shahid Vip", for showing a key back to a person. */
    public static String label(String key) {
        StringBuilder out = new StringBuilder();
        for (String w : key.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(w.length() <= 3 ? w : w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String first(String key) {
        int space = key.indexOf(' ');
        return space < 0 ? key : key.substring(0, space);
    }
}
