package ae.familyfinance.core.rules;

import ae.familyfinance.core.rules.RuleEngine.Ask;
import ae.familyfinance.core.rules.RuleEngine.Outcome;
import ae.familyfinance.core.rules.RuleEngine.Result;
import ae.familyfinance.core.rules.RuleEngine.Rule;
import ae.familyfinance.core.rules.RuleEngine.Spent;
import ae.familyfinance.core.spend.SpendCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    static final UUID ADAM = UUID.randomUUID();
    static final Rule PEANUTS = new Rule(UUID.randomUUID(), RuleKind.BLOCK_ALLERGEN, ADAM, "No peanuts for Adam", null,
            Allergen.PEANUT, null, false);
    static final Rule NO_GAMES = new Rule(UUID.randomUUID(), RuleKind.BLOCK_CATEGORY, null, "No in-game purchases",
            SpendCategory.IN_GAME_PURCHASES, null, null, false);
    static final Rule OVER_50 = new Rule(UUID.randomUUID(), RuleKind.APPROVAL_LIMIT, null, "Anything over AED 50 needs a parent",
            null, null, 5000L, false);

    private static Result check(List<Rule> rules, long amount, SpendCategory category, String text, Spent spent) {
        return RuleEngine.check(rules, new Ask(amount, category, text), spent, "Adam", "AED");
    }

    @Test
    void a_peanut_snack_is_blocked_by_its_name() {
        Result r = check(List.of(PEANUTS, OVER_50), 800, SpendCategory.SNACKS, "Peanut butter cups", Spent.none());
        assertThat(r.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(r.sentences().get(0)).contains("\"peanut\"", "must not have peanuts", "pack label");
    }

    @Test
    void food_without_the_word_still_waits_for_a_parent_to_check_the_label() {
        Result r = check(List.of(PEANUTS, OVER_50), 800, SpendCategory.SNACKS, "Chocolate bar", Spent.none());
        assertThat(r.outcome()).isEqualTo(Outcome.NEEDS_PARENT);
        assertThat(r.sentences()).anyMatch(s -> s.contains("checks the pack label"));
    }

    @Test
    void a_toy_is_not_food_so_the_allergy_rule_says_nothing() {
        Result r = check(List.of(PEANUTS, OVER_50), 1500, SpendCategory.TOYS_AND_HOBBIES, "Pokemon cards", Spent.none());
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void doughnut_is_not_a_nut_but_almonds_are() {
        assertThat(Allergen.TREE_NUT.foundIn("Doughnut")).isEmpty();
        assertThat(Allergen.TREE_NUT.foundIn("Chocolate almonds")).contains("almonds");
        assertThat(Allergen.PEANUT.foundIn("PEANUTTY crunch")).contains("peanut");
    }

    @Test
    void a_blocked_category_blocks_whatever_the_price() {
        Result r = check(List.of(NO_GAMES, OVER_50), 2000, SpendCategory.IN_GAME_PURCHASES, "Robux", Spent.none());
        assertThat(r.blocked()).isTrue();
        assertThat(r.sentences().get(0)).contains("No in-game purchases");
    }

    @Test
    void over_the_approval_limit_needs_a_parent_and_the_childs_own_limit_wins() {
        Rule adamOwn = new Rule(UUID.randomUUID(), RuleKind.APPROVAL_LIMIT, ADAM, "Adam: over AED 20", null, null, 2000L, false);
        Result r = check(List.of(OVER_50, adamOwn), 3000, SpendCategory.BOOKS_AND_STATIONERY, "Comic", Spent.none());
        assertThat(r.outcome()).isEqualTo(Outcome.NEEDS_PARENT);
        assertThat(r.approvalLimit().minor()).isEqualTo(2000);
        assertThat(r.hits()).hasSize(1);
    }

    @Test
    void a_category_limit_counts_what_was_already_spent_this_month() {
        Rule snacks = new Rule(UUID.randomUUID(), RuleKind.CATEGORY_LIMIT, ADAM, "Snacks up to AED 50 a month", SpendCategory.SNACKS,
                null, 5000L, false);
        Spent spent = new Spent(4000, Map.of(SpendCategory.SNACKS, 4500L), Map.of());
        Result r = check(List.of(snacks), 800, SpendCategory.SNACKS, "Crisps", spent);
        assertThat(r.outcome()).isEqualTo(Outcome.NEEDS_PARENT);
        assertThat(r.sentences().get(0)).contains("AED 45 so far", "AED 53", "AED 50 limit");

        Result fine = check(List.of(snacks), 300, SpendCategory.SNACKS, "Water", spent);
        assertThat(fine.outcome()).isEqualTo(Outcome.OK);
        assertThat(fine.hits().get(0).detail()).isEqualTo("AED 2 left this month after this.");
    }

    @Test
    void a_weekly_limit_uses_this_weeks_spending() {
        Rule weekly = new Rule(UUID.randomUUID(), RuleKind.CATEGORY_LIMIT, null, "Eating out up to AED 30 a week",
                SpendCategory.EATING_OUT, null, 3000L, true);
        Spent spent = new Spent(9000, Map.of(SpendCategory.EATING_OUT, 9000L), Map.of(SpendCategory.EATING_OUT, 1000L));
        assertThat(check(List.of(weekly), 1500, SpendCategory.EATING_OUT, "Shawarma", spent).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void the_monthly_cap_counts_everything() {
        Rule cap = new Rule(UUID.randomUUID(), RuleKind.MONTHLY_CAP, ADAM, "Up to AED 200 a month", null, null, 20000L, false);
        Result r = check(List.of(cap), 2500, SpendCategory.TOYS_AND_HOBBIES, "Lego", new Spent(19000, Map.of(), Map.of()));
        assertThat(r.outcome()).isEqualTo(Outcome.NEEDS_PARENT);
        assertThat(r.sentences().get(0)).contains("AED 190 this month", "AED 215", "AED 200 monthly cap");
    }

    @Test
    void blocked_beats_needs_parent() {
        Result r = check(List.of(PEANUTS, OVER_50), 9000, SpendCategory.SNACKS, "Peanut M&Ms party bag", Spent.none());
        assertThat(r.outcome()).isEqualTo(Outcome.BLOCKED);
    }

    @Test
    void with_no_rules_everything_is_ok() {
        Result r = check(List.of(), 9000, null, "Anything", Spent.none());
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.approvalLimit()).isNull();
    }

    @Test
    void plain_words_for_typed_rules() {
        assertThat(RuleService.describe(RuleKind.BLOCK_ALLERGEN, "Adam", null, Allergen.PEANUT, null, false))
                .isEqualTo("No peanuts for Adam");
        assertThat(RuleService.describe(RuleKind.APPROVAL_LIMIT, null, null, null, ae.familyfinance.core.money.Money.aed(10000), false))
                .isEqualTo("Anything over AED 100 needs a parent");
        assertThat(RuleService.describe(RuleKind.CATEGORY_LIMIT, "Layla", SpendCategory.SNACKS, null,
                ae.familyfinance.core.money.Money.aed(5000), true)).isEqualTo("Snacks and treats up to AED 50 a week for Layla");
    }
}
