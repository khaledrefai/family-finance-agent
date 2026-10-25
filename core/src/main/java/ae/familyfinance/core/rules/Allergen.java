package ae.familyfinance.core.rules;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The allergens a parent can set a rule for (the common ones on UAE food labels, verify against the GSO list),
 * with the words that point to each one in a product's name. Matching words is a flag for a parent, never a
 * guarantee: a product that doesn't say "peanut" may still contain it, so food always gets the "check the
 * pack label" caveat.
 */
public enum Allergen {
    PEANUT("peanuts", List.of("peanut", "peanuts", "groundnut", "groundnuts", "arachis", "monkey nut", "satay",
            "reese", "snickers", "m&m's peanut", "peanut butter")),
    TREE_NUT("tree nuts", List.of("nut", "nuts", "almond", "almonds", "cashew", "cashews", "hazelnut", "hazelnuts",
            "pistachio", "pistachios", "walnut", "walnuts", "pecan", "pecans", "macadamia", "praline", "nutella",
            "marzipan", "baklava")),
    MILK("milk", List.of("milk", "dairy", "cheese", "butter", "cream", "yoghurt", "yogurt", "laban", "whey", "ghee")),
    EGG("eggs", List.of("egg", "eggs", "mayonnaise", "mayo", "meringue")),
    WHEAT_GLUTEN("wheat and gluten", List.of("wheat", "gluten", "bread", "pasta", "flour", "biscuit", "biscuits",
            "cake", "croissant")),
    SOY("soy", List.of("soy", "soya", "tofu", "edamame")),
    FISH("fish", List.of("fish", "tuna", "salmon", "sardine", "sardines", "hammour", "anchovy")),
    SHELLFISH("shellfish", List.of("shrimp", "prawn", "prawns", "crab", "lobster", "shellfish")),
    SESAME("sesame", List.of("sesame", "tahini", "halva", "halwa", "simsim"));

    private final String label;
    private final List<String> words;

    Allergen(String label, List<String> words) {
        this.label = label;
        this.words = words;
    }

    public String label() {
        return label;
    }

    /** The first word in {@code text} that points to this allergen, if any ("Peanut butter cups" -> "peanut"). */
    public Optional<String> foundIn(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(w) + "(?![a-z])").matcher(lower).find()) {
                return Optional.of(w);
            }
        }
        // "peanut" in "Peanutty" or "PeanutButter": the stem alone is enough for the first word
        String stem = words.get(0);
        return lower.contains(stem) && stem.length() >= 5 ? Optional.of(stem) : Optional.empty();
    }
}
