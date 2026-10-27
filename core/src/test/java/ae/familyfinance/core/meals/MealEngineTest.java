package ae.familyfinance.core.meals;

import ae.familyfinance.core.meals.MealEngine.Costed;
import ae.familyfinance.core.meals.MealEngine.Eater;
import ae.familyfinance.core.meals.MealEngine.FoodMoney;
import ae.familyfinance.core.meals.MealEngine.Line;
import ae.familyfinance.core.meals.MealEngine.Main;
import ae.familyfinance.core.meals.MealEngine.Night;
import ae.familyfinance.core.meals.MealEngine.NightKind;
import ae.familyfinance.core.meals.MealEngine.Plan;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.rules.Allergen;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The weekly dinners with the saved lists and the demo family: halal, Layla doesn't like mushrooms, Adam no peanuts. */
class MealEngineTest {

    static final MealCatalogue CAT = new MealCatalogue(new ObjectMapper().findAndRegisterModules());
    static final LocalDate MON = LocalDate.of(2026, 10, 26);
    static final FoodMoney MONEY = new FoodMoney(Money.aed(300_000), Money.aed(100_000), Money.aed(150_000), Money.aed(30_000));
    static final List<Eater> HADDADS = List.of(
            new Eater("Sara", true, false, false, List.of(), Set.of()),
            new Eater("Omar", true, false, false, List.of(), Set.of()),
            new Eater("Layla", true, false, false, List.of("mushrooms"), Set.of()),
            new Eater("Adam", true, false, false, List.of(), EnumSet.of(Allergen.PEANUT)));

    static Plan plan(List<Eater> eaters, FoodMoney money, List<String> keys, Set<LocalDate> busy, List<String> order) {
        return MealEngine.plan(MON, MealEngine.costAll(CAT.recipes(), eaters, "AED"), CAT.deliveries(), eaters, money, keys,
                busy, order, "AED");
    }

    static Costed recipe(List<Eater> eaters, String key) {
        return MealEngine.costAll(CAT.recipes(), eaters, "AED").stream().filter(c -> c.recipe().key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void a_recipe_costs_its_share_of_each_pack_and_staples_are_not_counted() {
        // Lentil soup for 4: 400 g of a AED 9 kilo of lentils is AED 3.60, onions 0.80, carrots 1, lemons 1, bread 3
        assertThat(recipe(HADDADS, "lentil-soup").cost()).isEqualTo(Money.aed(940));
        // Two people eat half: 200 g lentils = 1.80, 100 g onions 0.40, carrots 0.50, lemons 0.50, 3 of 6 breads 1.50
        assertThat(recipe(HADDADS.subList(0, 2), "lentil-soup").cost()).isEqualTo(Money.aed(470));
    }

    @Test
    void what_doesnt_suit_someone_says_who_and_why() {
        assertThat(recipe(HADDADS, "satay-noodles").unsuitable()).isEqualTo("It has peanuts, and Adam must not have peanuts.");
        assertThat(recipe(HADDADS, "creamy-chicken-pasta").unsuitable()).isEqualTo("Layla doesn't like mushrooms.");
        assertThat(recipe(HADDADS, "lentil-soup").suits()).isTrue();

        List<Eater> veg = List.of(new Eater("Sara", false, true, false, List.of(), Set.of()));
        assertThat(recipe(veg, "kofta-rice").unsuitable()).isEqualTo("Sara is vegetarian, and it has meat.");
        assertThat(recipe(veg, "shakshuka").suits()).isTrue();
        List<Eater> vegan = List.of(new Eater("Omar", false, true, true, List.of(), Set.of()));
        assertThat(recipe(vegan, "shakshuka").unsuitable()).isEqualTo("Omar eats vegan, and it has eggs and dairy.");
        assertThat(recipe(vegan, "lentil-soup").suits()).isTrue();
    }

    @Test
    void the_engines_week_is_seven_cheap_dinners_that_suit_everyone_with_variety() {
        Plan p = plan(HADDADS, MONEY, List.of(), Set.of(), List.of());

        assertThat(p.nights()).hasSize(7).allMatch(n -> n.kind() == NightKind.COOK && n.dinner().suits());
        assertThat(p.nights().get(0).date()).isEqualTo(MON);
        assertThat(p.nights().get(0).label()).isEqualTo("Mon 26 Oct");
        assertThat(p.weekLabel()).isEqualTo("Mon 26 Oct to Sun 1 Nov");
        assertThat(p.nights().stream().map(n -> n.dinner().recipe().key()).distinct()).hasSize(7);
        for (Main m : Main.values()) {
            assertThat(p.nights().stream().filter(n -> n.dinner().recipe().main() == m).count()).isLessThanOrEqualTo(2);
        }
        for (int i = 1; i < 7; i++) {
            assertThat(p.nights().get(i).dinner().recipe().main()).isNotEqualTo(p.nights().get(i - 1).dinner().recipe().main());
        }
        // A week of grocery money is AED 3,000 x 12 / 52 = 692.31, rounded down to AED 690; dinners aim for half
        assertThat(p.groceryWeek()).isEqualTo(Money.aed(69_000));
        assertThat(p.forDinners()).isEqualTo(Money.aed(34_500));
        assertThat(p.over()).isNull();
        assertThat(p.weekLeft()).isEqualTo(Money.aed(69_000).minus(p.shopping()));
        assertThat(p.facts()).contains("Grocery budget: AED 3,000 a month, about AED 690 a week.",
                "Buy meat and chicken with a halal mark. The app doesn't certify any product.",
                "Dinners with peanuts are left out for Adam.");
    }

    @Test
    void the_shopping_list_buys_whole_packs_and_adds_up() {
        Plan p = plan(HADDADS, MONEY, List.of(), Set.of(), List.of());
        Money sum = Money.aed(0);
        for (Line l : p.list()) {
            assertThat(l.packs() * l.item().size()).isGreaterThanOrEqualTo(l.need());
            assertThat((l.packs() - 1) * l.item().size()).isLessThan(l.need());
            assertThat(l.cost()).isEqualTo(l.item().price().times(l.packs()));
            assertThat(l.item().staple()).isFalse();
            assertThat(l.halalMark()).isEqualTo(l.item().needsHalalMark());
            sum = sum.plus(l.cost());
        }
        assertThat(p.shopping()).isEqualTo(sum);
        Money dinners = p.nights().stream().map(Night::cost).reduce(Money.aed(0), Money::plus);
        assertThat(p.dinners()).isEqualTo(dinners);
        assertThat(p.inPacks()).isEqualTo(sum.minus(dinners));
        assertThat(p.staples()).contains("Salt and pepper").doesNotContainNull();
        // Nobody eats halal: no halal lines and no halal sentence
        List<Eater> any = HADDADS.stream().map(e -> new Eater(e.name(), false, false, false, e.dislikes(), e.allergies())).toList();
        Plan q = plan(any, MONEY, List.of(), Set.of(), List.of());
        assertThat(q.list()).noneMatch(Line::halalMark);
        assertThat(q.facts()).noneMatch(f -> f.contains("halal"));
    }

    @Test
    void busy_nights_are_delivery_nights_priced_from_the_saved_list_with_the_cost_of_cooking_instead() {
        Plan p = plan(HADDADS, MONEY, List.of(), Set.of(MON.plusDays(1), MON.plusDays(3), MON.plusDays(20)), List.of());

        assertThat(p.nights().stream().filter(n -> n.kind() == NightKind.DELIVERY).map(Night::label))
                .containsExactly("Tue 27 Oct", "Thu 29 Oct");
        // The two cheapest that suit everyone: falafel AED 70, chicken biryani AED 90
        assertThat(p.delivery()).isEqualTo(Money.aed(16_000));
        assertThat(p.nights().stream().filter(n -> n.kind() == NightKind.COOK)).hasSize(5);
        assertThat(p.cookInstead()).isNotNull().isLessThan(p.delivery());
        assertThat(p.eatingOutLeft()).isEqualTo(Money.aed(120_000));
        assertThat(p.facts()).contains("2 delivery nights (Tue and Thu): AED 160 from the eating-out budget (AED 1,200 left this month).",
                "Sun 15 Nov is not in this week, so it is not a delivery night.");
        assertThat(p.facts()).anyMatch(f -> f.startsWith("Cooking on those 2 nights instead would cost about AED "));
    }

    @Test
    void a_delivery_dinner_with_someones_allergen_is_swapped_for_one_that_suits() {
        List<Eater> sesame = List.of(new Eater("Adam", false, false, false, List.of(), EnumSet.of(Allergen.SESAME)));
        Plan p = plan(sesame, MONEY, List.of(), Set.of(MON), List.of("falafel-box"));
        assertThat(p.nights().get(0).delivery().key()).isEqualTo("chicken-biryani");
        assertThat(p.leftOut()).containsExactly("Left out Falafel and hummus box: It has sesame, and Adam must not have sesame.");
    }

    @Test
    void the_planners_pick_is_checked_and_the_engine_fills_the_other_nights() {
        Plan p = plan(HADDADS, MONEY, List.of("bolognese", "satay-noodles", "made-up", "creamy-chicken-pasta", "bolognese"),
                Set.of(), List.of());

        assertThat(p.nights().get(0).dinner().recipe().key()).isEqualTo("bolognese");
        assertThat(p.nights()).hasSize(7).allMatch(n -> n.dinner().suits());
        assertThat(p.nights().stream().filter(n -> n.dinner().recipe().key().equals("bolognese"))).hasSize(1);
        assertThat(p.leftOut()).containsExactly(
                "Left out Chicken satay noodles: It has peanuts, and Adam must not have peanuts.",
                "Left out Creamy chicken and mushroom pasta: Layla doesn't like mushrooms.");
    }

    @Test
    void over_the_money_for_dinners_says_by_how_much_and_the_swap_that_saves_most() {
        FoodMoney tight = new FoodMoney(Money.aed(80_000), Money.aed(0), Money.aed(0), Money.aed(0));  // AED 184 a week
        Plan p = plan(HADDADS, tight, List.of("sayadieh", "bamia", "halloumi-wraps"), Set.of(), List.of());

        assertThat(p.forDinners()).isEqualTo(Money.aed(9_000));
        assertThat(p.over()).isEqualTo(p.shopping().minus(Money.aed(9_000)));
        assertThat(p.swap()).startsWith("Swapping Fish sayadieh for ");
        assertThat(p.facts()).contains("That is " + p.over().toDisplay() + " over the AED 90 for dinners.", p.swap());
        assertThat(p.eatingOutLeft()).isNull();
    }

    @Test
    void a_vegetarian_family_gets_vegetarian_dinners_even_past_two_of_a_kind() {
        List<Eater> veg = List.of(new Eater("Sara", false, true, false, List.of(), Set.of()),
                new Eater("Omar", false, false, false, List.of(), Set.of()));
        Plan p = plan(veg, MONEY, List.of(), Set.of(MON), List.of());
        assertThat(p.nights().stream().filter(n -> n.kind() == NightKind.COOK)).hasSize(6)
                .allMatch(n -> n.dinner().recipe().vegetarian());
        assertThat(p.nights().get(0).delivery().vegetarian()).isTrue();

        List<Eater> vegan = List.of(new Eater("Omar", false, true, true, List.of(), Set.of()));
        Plan q = plan(vegan, MONEY, List.of(), Set.of(), List.of());
        assertThat(q.nights()).hasSize(7).allMatch(n -> n.dinner().recipe().vegan());
        assertThat(q.leftOut()).containsExactly("Only 2 saved dinners suit everyone, so some come twice.");
    }

    @Test
    void without_a_grocery_budget_the_plan_has_nothing_to_check_against() {
        FoodMoney none = new FoodMoney(Money.aed(0), Money.aed(0), Money.aed(0), Money.aed(0));
        Plan p = plan(HADDADS, none, List.of(), Set.of(), List.of());
        assertThat(p.groceryWeek()).isNull();
        assertThat(p.over()).isNull();
        assertThat(p.facts().get(0)).startsWith("There is no grocery budget yet");
    }

    @Test
    void amounts_read_like_a_shopping_list() {
        assertThat(MealEngine.amount(1200, "g")).isEqualTo("1.2 kg");
        assertThat(MealEngine.amount(800, "g")).isEqualTo("800 g");
        assertThat(MealEngine.amount(2000, "ml")).isEqualTo("2 L");
        assertThat(MealEngine.amount(4, "pc")).isEqualTo("4");
        assertThat(MealEngine.about(Money.aed(940))).isEqualTo("AED 9");
        assertThat(MealsService.dislikes(" Mushrooms, olives;; okra ")).containsExactly("mushrooms", "olives", "okra");
    }
}
