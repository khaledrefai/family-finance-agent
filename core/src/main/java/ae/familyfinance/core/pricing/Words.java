package ae.familyfinance.core.pricing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text helpers for matching shop titles to what was asked for. No money here. */
final class Words {

    private Words() {
    }

    private static final Set<String> STOP = Set.of("a", "an", "the", "for", "with", "and", "of", "new", "some", "me",
            "my", "pair", "please", "buy", "want", "cheap", "cheaper", "cheapest", "best", "price", "prices",
            "compare", "find", "in", "on", "uae", "dubai", "online", "to", "i", "get", "one", "it", "is", "how", "much");

    /** Words that make a listing a part or an add-on for the thing, not the thing ("case for ..."). */
    private static final List<String> ACCESSORY = List.of("case", "cover", "pouch", "sleeve", "earpads", "ear pads",
            "ear cushion", "cushions", "replacement", "strap", "protector", "cable", "charger", "adapter", "holder",
            "skin", "decal", "sticker", "mount", "compatible with", "refill");

    private static final Pattern MODEL = Pattern.compile("\\b(?=[A-Za-z0-9-]*\\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\\b");

    /** "WH-CH720N" and "wh ch720n" are the same model: upper case, letters and digits only. */
    static String flat(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** The same text a cache key and a comparison use: lower case, single spaces. */
    static String normal(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static boolean hasModel(String title, String model) {
        String m = flat(model);
        return m.length() >= 3 && flat(title).contains(m);
    }

    /** Model-like codes in a text: "Sony WH-CH720N" gives [WH-CH720N]. */
    static List<String> modelCodes(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = MODEL.matcher(text == null ? "" : text);
        while (m.find()) {
            if (m.group().length() >= 3) {
                out.add(m.group());
            }
        }
        return out;
    }

    /** The describing words of a query, singular-ish: "wireless headphones" gives [wireless, headphone]. */
    static List<String> keywords(String query, String brand, String model) {
        Set<String> skip = new HashSet<>(List.of(normal(brand), normal(model)));
        List<String> out = new ArrayList<>();
        for (String w : normal(query).split("[^a-z0-9]+")) {
            if (w.isEmpty() || STOP.contains(w) || skip.contains(w) || w.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (model != null && flat(model).contains(flat(w)) && w.chars().anyMatch(Character::isDigit)) {
                continue;
            }
            out.add(stem(w));
        }
        return out;
    }

    /** "headphones" to "headphone", "watches" to "watch"; "wireless" stays. Enough for "is this word in the title". */
    static String stem(String w) {
        if (w.length() <= 3 || !w.endsWith("s") || w.endsWith("ss")) {
            return w;
        }
        if (w.matches(".*(ch|sh|x|z)es")) {
            return w.substring(0, w.length() - 2);
        }
        return w.substring(0, w.length() - 1);
    }

    /**
     * True when the title is the kind of thing asked for: it has the last describing word (the thing itself:
     * "headphones" in "wireless headphones") and at least half of all of them.
     */
    static boolean fits(String title, List<String> keywords) {
        if (keywords.isEmpty()) {
            return true;
        }
        String t = normal(title).replaceAll("[^a-z0-9]", "");
        long found = keywords.stream().filter(k -> t.contains(k.replaceAll("[^a-z0-9]", ""))).count();
        return t.contains(keywords.get(keywords.size() - 1)) && found * 2 >= keywords.size();
    }

    /**
     * A part or add-on, unless that is what was asked for: the add-on word comes before the thing itself
     * ("Case for Sony WH-1000XM5"), or the title says "for" the thing. "Headphones with carrying case" is the thing.
     * {@code head} is the thing: the model when one was asked for, otherwise the last describing word.
     */
    static boolean accessory(String title, String query, String head) {
        String t = " " + normal(title) + " ";
        String q = " " + normal(query) + " ";
        String h = normal(head);
        int at = h.isEmpty() ? -1 : t.indexOf(h);
        if (at < 0 && !h.isEmpty()) {
            at = t.replace("-", "").indexOf(h.replace("-", ""));
        }
        int thing = at;
        return ACCESSORY.stream().anyMatch(a -> {
            int i = t.indexOf(" " + a);
            return i >= 0 && !q.contains(" " + a) && (thing < 0 || i < thing || t.contains(" for " + h));
        });
    }

    /** Two listings are the same when the shop and the title (letters and digits) are. */
    static String listingKey(String store, String title) {
        return normal(store) + "|" + flat(title);
    }
}
