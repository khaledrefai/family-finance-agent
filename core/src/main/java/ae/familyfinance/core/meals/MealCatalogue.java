package ae.familyfinance.core.meals;

import ae.familyfinance.core.meals.MealEngine.Aisle;
import ae.familyfinance.core.meals.MealEngine.Animal;
import ae.familyfinance.core.meals.MealEngine.Delivery;
import ae.familyfinance.core.meals.MealEngine.Item;
import ae.familyfinance.core.meals.MealEngine.Main;
import ae.familyfinance.core.meals.MealEngine.Recipe;
import ae.familyfinance.core.meals.MealEngine.Use;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.rules.Allergen;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Our saved UAE grocery price list, family recipes and delivery dinners ({@code resources/meals/}), typed in by hand
 * (estimates, verify). The weekly dinner plan always works from them, so a plan never waits on a shop's website, and
 * the app says these are saved prices, not live.
 */
@Component
public class MealCatalogue {

    record ItemEntry(String key, String name, String pack, long size, String unit, String price, Aisle aisle, Animal animal,
                     List<Allergen> allergens, boolean staple) {
    }

    record ItemFile(LocalDate checkedOn, String note, List<ItemEntry> items) {
    }

    /** {@code uses}: [item key, quantity for 4 in the item's unit]. */
    record RecipeEntry(String key, String name, Main main, int minutes, String about, List<List<Object>> uses) {
    }

    record RecipeFile(String note, List<RecipeEntry> recipes) {
    }

    record DeliveryEntry(String key, String name, String price, boolean vegetarian, boolean vegan, List<Allergen> allergens,
                         String about) {
    }

    record DeliveryFile(String note, List<DeliveryEntry> meals) {
    }

    private final LocalDate checkedOn;
    private final List<Recipe> recipes;
    private final List<Delivery> deliveries;

    public MealCatalogue(ObjectMapper json) {
        ItemFile items = read(json, "meals/groceries.json", ItemFile.class);
        this.checkedOn = items.checkedOn();
        Map<String, Item> byKey = items.items().stream().map(e -> new Item(e.key(), e.name(), e.pack(), e.size(), e.unit(),
                        Money.ofMajor(e.price(), Money.AED), e.aisle(), e.animal(), set(e.allergens()), e.staple()))
                .collect(Collectors.toMap(Item::key, Function.identity()));
        this.recipes = read(json, "meals/recipes.json", RecipeFile.class).recipes().stream().map(r -> new Recipe(r.key(), r.name(),
                r.main(), r.minutes(), r.about(), r.uses().stream().map(u -> {
                    Item it = byKey.get((String) u.get(0));
                    if (it == null) {
                        throw new IllegalStateException("meals/recipes.json: " + r.key() + " uses unknown item " + u.get(0));
                    }
                    return new Use(it, ((Number) u.get(1)).longValue());
                }).toList())).toList();
        this.deliveries = read(json, "meals/delivery.json", DeliveryFile.class).meals().stream().map(d -> new Delivery(d.key(),
                d.name(), Money.ofMajor(d.price(), Money.AED), d.vegetarian(), d.vegan(), set(d.allergens()), d.about())).toList();
    }

    private static <T> T read(ObjectMapper json, String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(path + " could not be read", e);
        }
    }

    private static java.util.Set<Allergen> set(List<Allergen> list) {
        return list == null || list.isEmpty() ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(list);
    }

    public List<Recipe> recipes() {
        return recipes;
    }

    public List<Delivery> deliveries() {
        return deliveries;
    }

    public LocalDate checkedOn() {
        return checkedOn;
    }
}
