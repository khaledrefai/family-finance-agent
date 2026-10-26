package ae.familyfinance.core.meals;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.rules.Allergen;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The weekly dinner plan and its shopping list (study, Should: "weekly grocery plan with what to cook"). Pure: the
 * service loads the saved price list, the recipes, the family's food profiles and the budgets; this does the sums.
 *
 * <p>Seven dinners from today. A parent may mark busy nights as delivery nights (priced from a few saved delivery
 * dinners, out of the eating-out budget). The other nights are cooked from saved recipes, and only recipes that suit
 * everyone at the table: nobody's allergy (from the family rules), nobody's dislikes, vegetarian or vegan if anyone is.
 * Halal never excludes anything: the list has no pork or alcohol, and meat and chicken lines say to check the halal
 * mark. The app never certifies a product.
 *
 * <p>Two ways to make a plan, checked the same way:
 * <ul>
 *   <li>the engine's own pick: the cheapest dinners for this family, at most 2 of one kind (chicken, meat, fish,
 *       vegetarian) while there is a choice, no two of a kind on following nights;</li>
 *   <li>the Planner agent's (or the parent's) pick: unknown recipes are ignored, ones that don't suit someone are left
 *       out with the reason, and the engine fills the nights still empty.</li>
 * </ul>
 * The shopping list adds up what the dinners use and buys whole packs. Dinners aim for half of the week's grocery
 * money (the rest is breakfasts, lunch boxes and the house). Every number the agent may say is in {@code facts}.
 */
public final class MealEngine {

    public static final int NIGHTS = 7;
    public static final int MAX_DELIVERY = 3;
    static final int MAX_PER_MAIN = 2;
    /** Every saved recipe is written for 4 people. */
    static final int SERVES = 4;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

    public enum Aisle { FRUIT_VEG, MEAT_FISH, DAIRY_EGGS, BAKERY_GRAINS, TINS_DRY, FROZEN, PANTRY }

    public enum Animal { MEAT, POULTRY, FISH, DAIRY, EGG }

    public enum Main { CHICKEN, MEAT, FISH, VEG }

    public enum NightKind { COOK, DELIVERY }

    /** One thing on the saved price list. {@code size} is the pack in {@code unit} (g, ml or pc). */
    public record Item(String key, String name, String pack, long size, String unit, Money price, Aisle aisle,
                       Animal animal, Set<Allergen> allergens, boolean staple) {

        boolean needsHalalMark() {
            return animal == Animal.MEAT || animal == Animal.POULTRY;
        }
    }

    /** How much of an item a recipe for 4 uses, in the item's unit. */
    public record Use(Item item, long qty) {
    }

    public record Recipe(String key, String name, Main main, int minutes, String about, List<Use> uses) {

        boolean vegetarian() {
            return uses.stream().noneMatch(u -> u.item().animal() == Animal.MEAT || u.item().animal() == Animal.POULTRY
                                                || u.item().animal() == Animal.FISH);
        }

        boolean vegan() {
            return uses.stream().allMatch(u -> u.item().animal() == null);
        }

        Set<Allergen> allergens() {
            Set<Allergen> all = EnumSet.noneOf(Allergen.class);
            uses.forEach(u -> all.addAll(u.item().allergens()));
            return all;
        }
    }

    /** A saved delivery dinner for 4, with delivery and fees. */
    public record Delivery(String key, String name, Money price, boolean vegetarian, boolean vegan, Set<Allergen> allergens,
                           String about) {
    }

    /** Someone at the table. {@code allergies}: from the family's active allergy rules. */
    public record Eater(String name, boolean halal, boolean vegetarian, boolean vegan, List<String> dislikes,
                        Set<Allergen> allergies) {
    }

    /** The month's grocery and eating-out budgets (zero = none set) and what was spent on them this month. */
    public record FoodMoney(Money groceryLimit, Money grocerySpent, Money eatingOutLimit, Money eatingOutSpent) {
    }

    /** A recipe with what it costs this family (its share of the packs), and why it doesn't suit, if it doesn't. */
    public record Costed(Recipe recipe, Money cost, String unsuitable) {

        boolean suits() {
            return unsuitable == null;
        }
    }

    public record Night(LocalDate date, String label, NightKind kind, Costed dinner, Delivery delivery, Money cost) {

        String name() {
            return kind == NightKind.COOK ? dinner.recipe().name() : delivery.name();
        }
    }

    /** One line of the shopping list: {@code packs} of the item, and what the dinners use of them. */
    public record Line(Item item, long need, long packs, Money cost, String uses, boolean halalMark) {
    }

    public record Row(String label, String value) {
    }

    /**
     * A week's dinners. {@code shopping}: the list's total at saved prices; {@code dinners}: what the dinners use of it
     * (the rest stays in the packs); {@code forDinners}: half the week's grocery money (null without a budget);
     * {@code over}: shopping − forDinners when more than zero; {@code cookInstead}: what cooking on the delivery
     * nights would cost (null without delivery nights or recipes to spare).
     */
    public record Plan(LocalDate start, LocalDate end, String weekLabel, List<Night> nights, List<Line> list,
                       List<String> staples, Money shopping, Money dinners, Money inPacks, Money groceryWeek,
                       Money forDinners, Money weekLeft, Money over, String swap, Money delivery, Money eatingOutLeft,
                       Money cookInstead, List<String> leftOut, List<String> facts, List<Row> why) {
    }

    private MealEngine() {
    }

    // ---------------------------------------------------------------- what suits the family, and what it costs

    /** The first reason this recipe doesn't suit someone at the table, or null. */
    static String unsuitable(Recipe r, List<Eater> eaters) {
        Set<Allergen> has = r.allergens();
        for (Eater e : eaters) {
            for (Allergen a : e.allergies()) {
                if (has.contains(a) || a.foundIn(r.name()).isPresent()) {
                    return "It has " + a.label() + ", and " + e.name() + " must not have " + a.label() + ".";
                }
            }
        }
        for (Eater e : eaters) {
            if (e.vegan() && !r.vegan()) {
                return e.name() + " eats vegan, and it has " + animalWord(r) + ".";
            }
            if (e.vegetarian() && !r.vegetarian()) {
                return e.name() + " is vegetarian, and it has " + animalWord(r) + ".";
            }
        }
        for (Eater e : eaters) {
            Optional<String> word = disliked(e, r.name() + " " + String.join(" ", r.uses().stream().map(u -> u.item().name()).toList()));
            if (word.isPresent()) {
                return e.name() + " doesn't like " + word.get() + ".";
            }
        }
        return null;
    }

    static String unsuitable(Delivery d, List<Eater> eaters) {
        for (Eater e : eaters) {
            for (Allergen a : e.allergies()) {
                if (d.allergens().contains(a) || a.foundIn(d.name()).isPresent()) {
                    return "It has " + a.label() + ", and " + e.name() + " must not have " + a.label() + ".";
                }
            }
        }
        for (Eater e : eaters) {
            if (e.vegan() && !d.vegan()) {
                return e.name() + " eats vegan.";
            }
            if (e.vegetarian() && !d.vegetarian()) {
                return e.name() + " is vegetarian.";
            }
            Optional<String> word = disliked(e, d.name());
            if (word.isPresent()) {
                return e.name() + " doesn't like " + word.get() + ".";
            }
        }
        return null;
    }

    /** "mushrooms" matches "Mushroom" and "mushrooms": the word without a final s, anywhere in the text. */
    private static Optional<String> disliked(Eater e, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String d : e.dislikes()) {
            String w = d.trim().toLowerCase(Locale.ROOT);
            if (w.length() < 3) {
                continue;
            }
            String stem = w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w;
            if (lower.contains(stem)) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    private static String animalWord(Recipe r) {
        Set<String> words = new LinkedHashSet<>();
        for (Use u : r.uses()) {
            if (u.item().animal() != null) {
                words.add(switch (u.item().animal()) {
                    case POULTRY -> "chicken";
                    case MEAT -> "meat";
                    case FISH -> "fish";
                    case DAIRY -> "dairy";
                    case EGG -> "eggs";
                });
            }
        }
        return join(List.copyOf(words));
    }

    /** How much of an item {@code people} eat, from a recipe for 4: rounded up, never short. */
    static long need(Use u, int people) {
        return Math.ceilDiv(Math.multiplyExact(u.qty(), people), SERVES);
    }

    /** A recipe's cost for this family: its share of each pack it uses (staples are not counted). */
    public static Money cost(Recipe r, int people, String currency) {
        Money total = Money.zero(currency);
        for (Use u : r.uses()) {
            if (!u.item().staple()) {
                total = total.plus(u.item().price().scaled(need(u, people), u.item().size()));
            }
        }
        return total;
    }

    public static List<Costed> costAll(List<Recipe> recipes, List<Eater> eaters, String currency) {
        int people = Math.max(1, eaters.size());
        return recipes.stream().map(r -> new Costed(r, cost(r, people, currency), unsuitable(r, eaters))).toList();
    }

    // ---------------------------------------------------------------- the budgets

    /** A week of the monthly grocery budget (× 12 ÷ 52), rounded down to AED 10. */
    public static Money groceryWeek(Money monthly) {
        return monthly.scaled(12, 52).roundedDownTo(1000);
    }

    /** Dinners aim for half the week's grocery money, rounded down to AED 5. */
    public static Money forDinners(Money week) {
        return week.scaled(1, 2).roundedDownTo(500);
    }

    // ---------------------------------------------------------------- the plan

    /**
     * The week from {@code start}. {@code keys}: dinners picked by the Planner or the parent, for the cooking nights
     * in order (empty = the engine picks). {@code deliveryDays}: the busy nights; {@code deliveryKeys}: what to order
     * on them, in order (empty = the cheapest that suits everyone).
     */
    public static Plan plan(LocalDate start, List<Costed> recipes, List<Delivery> deliveries, List<Eater> eaters,
                            FoodMoney money, List<String> keys, Set<LocalDate> deliveryDays, List<String> deliveryKeys,
                            String currency) {
        LocalDate end = start.plusDays(NIGHTS - 1);
        List<String> leftOut = new ArrayList<>();

        // The busy nights: inside this week, at most 3
        List<LocalDate> busy = new ArrayList<>(new TreeSet<>(deliveryDays == null ? Set.of() : deliveryDays));
        busy.removeIf(d -> {
            if (d.isBefore(start) || d.isAfter(end)) {
                leftOut.add(DAY.format(d) + " is not in this week, so it is not a delivery night.");
                return true;
            }
            return false;
        });
        while (busy.size() > MAX_DELIVERY) {
            LocalDate d = busy.remove(busy.size() - 1);
            leftOut.add(DAY.format(d) + " is a cooking night: the plan has at most " + MAX_DELIVERY + " delivery nights.");
        }

        // What to order on them
        List<Delivery> fitting = deliveries.stream().filter(d -> unsuitable(d, eaters) == null)
                .sorted(Comparator.comparing(Delivery::price).thenComparing(Delivery::key)).toList();
        List<Delivery> ordered = new ArrayList<>();
        List<String> wantedDelivery = deliveryKeys == null ? List.of() : deliveryKeys;
        for (int i = 0; i < busy.size(); i++) {
            Delivery pick = null;
            if (i < wantedDelivery.size()) {
                String key = wantedDelivery.get(i);
                Delivery d = deliveries.stream().filter(x -> x.key().equals(key)).findFirst().orElse(null);
                if (d != null && unsuitable(d, eaters) != null) {
                    leftOut.add("Left out " + d.name() + ": " + unsuitable(d, eaters));
                } else {
                    pick = d;
                }
            }
            if (pick == null) {
                pick = fitting.stream().filter(d -> !ordered.contains(d)).findFirst()
                        .orElse(fitting.isEmpty() ? null : fitting.get(0));
            }
            if (pick == null) {
                leftOut.add("No saved delivery dinner suits everyone, so " + DAY.format(busy.get(i)) + " is a cooking night.");
                busy.remove(i--);
                continue;
            }
            ordered.add(pick);
        }

        // The dinners to cook: the picked ones that suit everyone, then the engine's cheapest
        int cookNights = NIGHTS - busy.size();
        List<Costed> chosen = new ArrayList<>();
        for (String key : new LinkedHashSet<>(keys == null ? List.<String>of() : keys)) {
            Costed c = recipes.stream().filter(x -> x.recipe().key().equals(key)).findFirst().orElse(null);
            if (c == null) {
                continue;  // not on the list: never shown, never priced
            }
            if (!c.suits()) {
                leftOut.add("Left out " + c.recipe().name() + ": " + c.unsuitable());
                continue;
            }
            if (chosen.size() == cookNights) {
                leftOut.add("Left out " + c.recipe().name() + ": there are " + cookNights + " cooking nights.");
                continue;
            }
            chosen.add(c);
        }
        List<Costed> suitable = recipes.stream().filter(Costed::suits)
                .sorted(Comparator.comparing(Costed::cost).thenComparing(c -> c.recipe().key())).toList();
        List<Costed> filled = fill(suitable, chosen, cookNights);
        List<Costed> dinners = new ArrayList<>(chosen);
        dinners.addAll(spread(filled.subList(chosen.size(), filled.size()), chosen.isEmpty() ? null : chosen.get(chosen.size() - 1)));
        if (suitable.size() < cookNights && !suitable.isEmpty()) {
            leftOut.add("Only " + suitable.size() + (suitable.size() == 1 ? " saved dinner suits" : " saved dinners suit")
                        + " everyone, so some come twice.");
        }

        // The nights in date order
        List<Night> nights = new ArrayList<>();
        int cook = 0;
        int del = 0;
        for (int i = 0; i < NIGHTS; i++) {
            LocalDate day = start.plusDays(i);
            if (busy.contains(day)) {
                Delivery d = ordered.get(del++);
                nights.add(new Night(day, DAY.format(day), NightKind.DELIVERY, null, d, d.price()));
            } else if (cook < dinners.size()) {
                Costed c = dinners.get(cook++);
                nights.add(new Night(day, DAY.format(day), NightKind.COOK, c, null, c.cost()));
            }
        }

        // The shopping list: whole packs of what the dinners use, by aisle
        int people = Math.max(1, eaters.size());
        Map<Item, Long> needs = new LinkedHashMap<>();
        Set<String> staples = new LinkedHashSet<>();
        for (Night n : nights) {
            if (n.kind() == NightKind.COOK) {
                for (Use u : n.dinner().recipe().uses()) {
                    if (u.item().staple()) {
                        staples.add(u.item().name());
                    } else {
                        needs.merge(u.item(), need(u, people), Long::sum);
                    }
                }
            }
        }
        boolean halal = eaters.stream().anyMatch(Eater::halal);
        List<Line> list = needs.entrySet().stream().map(e -> {
            Item it = e.getKey();
            long packs = Math.ceilDiv(e.getValue(), it.size());
            return new Line(it, e.getValue(), packs, it.price().times(packs),
                    e.getValue() == packs * it.size() ? "All used this week"
                            : "You use " + amount(e.getValue(), it.unit()) + " of " + amount(packs * it.size(), it.unit()),
                    halal && it.needsHalalMark());
        }).sorted(Comparator.comparing((Line l) -> l.item().aisle()).thenComparing(l -> l.item().name())).toList();

        Money shopping = list.stream().map(Line::cost).reduce(Money.zero(currency), Money::plus);
        Money dinnersCost = nights.stream().filter(n -> n.kind() == NightKind.COOK).map(Night::cost)
                .reduce(Money.zero(currency), Money::plus);
        Money inPacks = shopping.minus(dinnersCost);
        Money delivery = ordered.stream().map(Delivery::price).reduce(Money.zero(currency), Money::plus);

        Money week = money.groceryLimit().isPositive() ? groceryWeek(money.groceryLimit()) : null;
        Money half = week == null ? null : forDinners(week);
        Money weekLeft = week == null ? null : week.minus(shopping);
        Money over = half != null && shopping.compareTo(half) > 0 ? shopping.minus(half) : null;
        String swap = over == null ? null : swap(dinners, suitable, currency);
        Money eatingOutLeft = money.eatingOutLimit().isPositive() ? money.eatingOutLimit().minus(money.eatingOutSpent()) : null;

        // What the delivery nights would cost cooked: the next cheapest dinners that suit everyone
        Money cookInstead = null;
        if (!ordered.isEmpty()) {
            List<Costed> spare = suitable.stream().filter(c -> !dinners.contains(c)).limit(ordered.size()).toList();
            if (spare.size() == ordered.size()) {
                cookInstead = spare.stream().map(Costed::cost).reduce(Money.zero(currency), Money::plus);
            }
        }

        String weekLabel = DAY.format(start) + " to " + DAY.format(end);
        List<String> facts = new ArrayList<>();
        List<Row> why = new ArrayList<>();
        if (week == null) {
            facts.add("There is no grocery budget yet, so there is no limit to check the plan against. Set one in Money, Budgets.");
        } else {
            facts.add("Grocery budget: " + money.groceryLimit().toDisplay() + " a month, about " + week.toDisplay() + " a week.");
            facts.add("The plan keeps dinners to half of that, " + half.toDisplay()
                      + ", leaving the rest for breakfasts, lunch boxes and the house.");
            why.add(new Row("Grocery money, a week", week.toDisplay()));
            why.add(new Row("For dinners, half of it", half.toDisplay()));
        }
        for (Night n : nights) {
            facts.add(n.label() + ": " + (n.kind() == NightKind.COOK
                    ? n.name() + ", about " + about(n.cost()) + " of the shopping."
                    : "delivery night, " + n.name() + ", " + n.cost().toDisplay() + "."));
        }
        if (!list.isEmpty()) {
            facts.add("Shopping list: " + list.size() + " items, " + shopping.toDisplay() + " at our saved prices.");
            why.add(new Row("Shopping list, " + list.size() + " items", shopping.toDisplay()));
            if (inPacks.isPositive()) {
                facts.add("The dinners use about " + about(dinnersCost) + " of it; about " + about(inPacks)
                          + " stays in the packs for next week.");
                why.add(new Row("Used by the dinners", "about " + about(dinnersCost)));
            }
        }
        if (half != null) {
            if (over == null) {
                facts.add("That is " + half.minus(shopping).toDisplay() + " under the " + half.toDisplay() + " for dinners, leaving "
                          + weekLeft.toDisplay() + " of the week's " + week.toDisplay() + ".");
                why.add(new Row("Left of the week's grocery money", weekLeft.toDisplay()));
            } else {
                facts.add("That is " + over.toDisplay() + " over the " + half.toDisplay() + " for dinners.");
                why.add(new Row("Over the money for dinners", over.toDisplay()));
                if (swap != null) {
                    facts.add(swap);
                }
            }
        }
        if (!ordered.isEmpty()) {
            List<String> days = nights.stream().filter(n -> n.kind() == NightKind.DELIVERY).map(n -> SHORT.format(n.date())).toList();
            facts.add(ordered.size() + (ordered.size() == 1 ? " delivery night (" : " delivery nights (") + join(days) + "): "
                      + delivery.toDisplay() + " from the eating-out budget"
                      + (eatingOutLeft == null ? "." : eatingOutLeft.isNegative()
                    ? ", which is already " + eatingOutLeft.negate().toDisplay() + " over this month."
                    : " (" + eatingOutLeft.toDisplay() + " left this month)."));
            why.add(new Row("Delivery nights, " + ordered.size(), delivery.toDisplay()));
            if (cookInstead != null && cookInstead.compareTo(delivery) < 0) {
                facts.add("Cooking on " + (ordered.size() == 1 ? "that night" : "those " + ordered.size() + " nights")
                          + " instead would cost about " + about(cookInstead) + " in groceries, about "
                          + about(delivery.minus(cookInstead)) + " less.");
                why.add(new Row("Cooking those nights instead", "about " + about(cookInstead)));
            }
        }
        if (halal && list.stream().anyMatch(Line::halalMark)) {
            facts.add("Buy meat and chicken with a halal mark. The app doesn't certify any product.");
        }
        Set<String> allergyNotes = new LinkedHashSet<>();
        for (Eater e : eaters) {
            for (Allergen a : e.allergies()) {
                allergyNotes.add("Dinners with " + a.label() + " are left out for " + e.name() + ".");
            }
        }
        if (!allergyNotes.isEmpty()) {
            facts.addAll(allergyNotes);
            facts.add("Always check the pack labels: the app can't be sure what is in a product.");
        }
        facts.addAll(leftOut);
        return new Plan(start, end, weekLabel, List.copyOf(nights), list, List.copyOf(staples), shopping, dinnersCost, inPacks,
                week, half, weekLeft, over, swap, delivery, eatingOutLeft, cookInstead, List.copyOf(leftOut), List.copyOf(facts),
                List.copyOf(why));
    }

    /**
     * The chosen dinners, then the cheapest that suit everyone up to {@code n}: at most 2 of a kind while there is a
     * choice, then any kind, then (a family with very few dinners that suit) the same dinner twice.
     */
    static List<Costed> fill(List<Costed> suitable, List<Costed> chosen, int n) {
        List<Costed> picks = new ArrayList<>(chosen);
        for (Costed c : suitable) {
            if (picks.size() >= n) {
                break;
            }
            long sameMain = picks.stream().filter(p -> p.recipe().main() == c.recipe().main()).count();
            if (!picks.contains(c) && sameMain < MAX_PER_MAIN) {
                picks.add(c);
            }
        }
        for (Costed c : suitable) {
            if (picks.size() >= n) {
                break;
            }
            if (!picks.contains(c)) {
                picks.add(c);
            }
        }
        for (int i = 0; picks.size() < n && !suitable.isEmpty(); i++) {
            picks.add(suitable.get(i % suitable.size()));
        }
        return picks;
    }

    /** The same dinners, ordered so no two of a kind follow each other where that can be helped. */
    static List<Costed> spread(List<Costed> picks, Costed before) {
        List<Costed> left = new ArrayList<>(picks);
        List<Costed> out = new ArrayList<>();
        Main last = before == null ? null : before.recipe().main();
        while (!left.isEmpty()) {
            Main avoid = last;
            Costed next = left.stream().filter(c -> c.recipe().main() != avoid).findFirst().orElse(left.get(0));
            left.remove(next);
            out.add(next);
            last = next.recipe().main();
        }
        return out;
    }

    /** Over the money for dinners: the dearest dinner for the cheapest one not in the plan, if that saves anything. */
    private static String swap(List<Costed> dinners, List<Costed> suitable, String currency) {
        Costed dearest = dinners.stream().max(Comparator.comparing(Costed::cost)).orElse(null);
        Costed cheapest = suitable.stream().filter(c -> !dinners.contains(c)).findFirst().orElse(null);
        if (dearest == null || cheapest == null || cheapest.cost().compareTo(dearest.cost()) >= 0) {
            return null;
        }
        return "Swapping " + dearest.recipe().name() + " for " + cheapest.recipe().name() + " would save about "
               + about(dearest.cost().minus(cheapest.cost())) + ".";
    }

    /** "About AED 9" for AED 9.40: shares of packs are estimates, so the words use whole dirhams. */
    static String about(Money m) {
        return m.scaled(1, 100).times(100).toDisplay();
    }

    /** "1.2 kg", "800 g", "1.5 L", "6". */
    static String amount(long qty, String unit) {
        return switch (unit) {
            case "g" -> qty >= 1000 ? trim(qty / 1000.0) + " kg" : qty + " g";
            case "ml" -> qty >= 1000 ? trim(qty / 1000.0) + " L" : qty + " ml";
            default -> String.valueOf(qty);
        };
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static String join(List<String> words) {
        if (words.size() <= 1) {
            return words.isEmpty() ? "" : words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }
}
