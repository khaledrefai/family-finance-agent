package ae.familyfinance.core.gold;

import ae.familyfinance.core.gold.GoldEngine.Holding;
import ae.familyfinance.core.gold.WealthEngine.Part;
import ae.familyfinance.core.gold.WealthEngine.Step;
import ae.familyfinance.core.gold.WealthEngine.Wealth;
import ae.familyfinance.core.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldAndWealthEngineTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    @Test
    void grams_are_valued_at_the_price_for_their_purity() {
        Map<String, Long> price = Map.of("24K", 32000L, "22K", 29650L);
        assertThat(GoldEngine.value(new Holding("24K", 40000, 1200000, false), price, "AED").value())
                .isEqualTo(Money.aed(1280000));
        GoldEngine.Value bangle = GoldEngine.value(new Holding("22K", 10000, 340000, true), price, "AED");
        assertThat(bangle.value()).isEqualTo(Money.aed(296500));
        assertThat(bangle.belowPaid()).isTrue();
        // 2.345 g at AED 320.00 = AED 750.40
        assertThat(GoldEngine.value(2345, 32000, "AED")).isEqualTo(Money.aed(75040));
    }

    @Test
    void weights_are_read_in_grams_up_to_milligrams() {
        assertThat(GoldEngine.parseGrams("10")).isEqualTo(10000L);
        assertThat(GoldEngine.parseGrams("2.5")).isEqualTo(2500L);
        assertThat(GoldEngine.parseGrams("0.001")).isEqualTo(1L);
        assertThat(GoldEngine.parseGrams("0")).isNull();
        assertThat(GoldEngine.parseGrams("2.5555")).isNull();
        assertThat(GoldEngine.parseGrams("ten")).isNull();
        assertThat(GoldEngine.grams(10500)).isEqualTo("10.5");
        assertThat(GoldEngine.grams(40000)).isEqualTo("40");
    }

    @Test
    void wealth_is_what_you_own_minus_what_you_owe() {
        Wealth w = WealthEngine.compute(List.of(
                new Part("Bank accounts", "OWN", Money.aed(4425000), DAY, DAY),
                new Part("Cash", "OWN", Money.aed(65000), DAY, DAY),
                new Part("Gold, at today's price", "OWN", Money.aed(1576500), DAY, DAY),
                new Part("Credit cards", "OWE", Money.aed(730000), DAY, DAY),
                new Part("Car loan left", "OWE", Money.aed(3840000), DAY, DAY)), "AED");
        assertThat(w.owned()).isEqualTo(Money.aed(6066500));
        assertThat(w.owed()).isEqualTo(Money.aed(4570000));
        assertThat(w.total()).isEqualTo(Money.aed(1496500));
        List<Step> s = w.steps();
        assertThat(s).extracting(Step::label).containsExactly("Bank accounts", "Cash", "Gold, at today's price",
                "Everything you own", "Credit cards", "Car loan left", "Family wealth");
        assertThat(s.get(2).kind()).isEqualTo("s4");
        assertThat(s.get(3).toBp()).isEqualTo(10000);
        assertThat(s.get(4).toBp()).isEqualTo(10000);                       // debts are cut from the right end
        assertThat(s.get(5).toBp()).isEqualTo(s.get(4).fromBp());
        assertThat(s.get(6).toBp()).isEqualTo(s.get(5).fromBp());
    }

    @Test
    void owing_more_than_you_own_gives_a_negative_total_and_no_negative_bars() {
        Wealth w = WealthEngine.compute(List.of(new Part("Cash", "OWN", Money.aed(100000), DAY, DAY),
                new Part("Car loan left", "OWE", Money.aed(300000), DAY, DAY)), "AED");
        assertThat(w.total()).isEqualTo(Money.aed(-200000));
        assertThat(w.steps()).allSatisfy(st -> assertThat(st.fromBp()).isBetween(0, 10000));
    }
}
