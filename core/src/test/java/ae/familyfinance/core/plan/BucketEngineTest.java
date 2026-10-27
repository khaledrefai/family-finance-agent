package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.plan.BucketEngine.Needs;
import ae.familyfinance.core.plan.BucketEngine.Share;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BucketEngineTest {

    private static final Money INCOME = Money.aed(3_450_000);

    private static Needs needs(long commitments, long everyday, long goals) {
        return new Needs(INCOME, Money.aed(commitments), List.of(), Money.aed(everyday), List.of(), Money.aed(goals),
                List.of(), new Money[0]);
    }

    @Test
    void amounts_add_up_to_the_income_to_the_fils() {
        List<Share> shares = List.of(new Share(BucketKind.COMMITMENTS, 3333, null), new Share(BucketKind.EVERYDAY, 3333, null),
                new Share(BucketKind.FUN, 3334, null));
        Map<BucketKind, Money> amounts = BucketEngine.amounts(Money.aed(100_001), shares);
        assertThat(amounts.values().stream().mapToLong(Money::minor).sum()).isEqualTo(100_001);
    }

    @Test
    void the_suggestion_covers_commitments_everyday_and_goals_first() {
        Needs n = needs(1_470_900, 739_333, 650_000);
        List<Share> s = BucketEngine.suggestion(n);
        assertThat(s.stream().mapToInt(Share::shareBp).sum()).isEqualTo(BucketEngine.WHOLE);
        assertThat(BucketEngine.problems(s, n)).isEmpty();
        Map<BucketKind, Money> amounts = BucketEngine.amounts(INCOME, s);
        assertThat(amounts.get(BucketKind.COMMITMENTS)).isGreaterThanOrEqualTo(Money.aed(1_470_900));
        assertThat(amounts.get(BucketKind.EVERYDAY)).isGreaterThanOrEqualTo(Money.aed(739_333));
        assertThat(amounts.get(BucketKind.GOALS)).isGreaterThanOrEqualTo(Money.aed(650_000));
    }

    @Test
    void a_split_that_starves_commitments_is_refused() {
        Needs n = needs(1_470_900, 739_333, 650_000);
        List<Share> s = List.of(new Share(BucketKind.COMMITMENTS, 2000, null), new Share(BucketKind.FUN, 8000, null));
        assertThat(BucketEngine.problems(s, n)).anyMatch(p -> p.startsWith("Commitments needs at least"));
    }

    @Test
    void shares_must_add_up_to_100_percent() {
        List<Share> s = List.of(new Share(BucketKind.COMMITMENTS, 5000, null), new Share(BucketKind.FUN, 4000, null));
        assertThat(BucketEngine.problems(s, needs(0, 0, 0))).contains("The shares add up to 90%, not 100%");
    }

    @Test
    void a_split_typed_in_aed_holds_each_amount_and_fun_gets_the_rest() {
        Map<BucketKind, Money> typed = Map.of(BucketKind.COMMITMENTS, Money.aed(1_470_900), BucketKind.EVERYDAY, Money.aed(800_000),
                BucketKind.GOALS, Money.aed(650_000), BucketKind.INVEST_ASIDE, Money.aed(100_000), BucketKind.GIVING, Money.aed(0));
        List<Share> s = BucketEngine.fromAmounts(INCOME, typed);
        assertThat(s.stream().mapToInt(Share::shareBp).sum()).isEqualTo(BucketEngine.WHOLE);
        assertThat(BucketEngine.problems(s, needs(1_470_900, 739_333, 650_000))).isEmpty();
        Map<BucketKind, Money> amounts = BucketEngine.amounts(INCOME, s);
        typed.forEach((kind, m) -> assertThat(amounts.get(kind)).isGreaterThanOrEqualTo(m));
        // 34,500 less 30,209 typed: about AED 4,291 of fun, give or take a dirham of rounding
        assertThat(amounts.get(BucketKind.FUN).minor()).isBetween(428_000L, 429_200L);
        assertThat(s).filteredOn(x -> x.kind() == BucketKind.GIVING).first().extracting(Share::shareBp).isEqualTo(0);
    }

    @Test
    void a_split_typed_to_the_last_dirham_still_adds_up_to_100_percent() {
        // Every share rounded up would come to more than 100%: the overflow comes off the biggest bucket after commitments
        Map<BucketKind, Money> typed = Map.of(BucketKind.COMMITMENTS, Money.aed(1_000_100), BucketKind.EVERYDAY, Money.aed(1_000_100),
                BucketKind.GOALS, Money.aed(1_449_800));
        List<Share> s = BucketEngine.fromAmounts(INCOME, typed);
        assertThat(s.stream().mapToInt(Share::shareBp).sum()).isEqualTo(BucketEngine.WHOLE);
        assertThat(BucketEngine.amounts(INCOME, s).get(BucketKind.COMMITMENTS)).isGreaterThanOrEqualTo(Money.aed(1_000_100));
    }
}
