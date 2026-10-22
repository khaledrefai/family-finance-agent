package ae.familyfinance.core.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void adds_and_subtracts_in_fils_without_rounding_errors() {
        Money total = Money.ofMajor("0.10", "AED").plus(Money.ofMajor("0.20", "AED"));
        assertThat(total).isEqualTo(Money.aed(30));
        assertThat(Money.aed(30000).minus(Money.aed(18000))).isEqualTo(Money.ofMajor("120", "AED"));
    }

    @Test
    void formats_as_major_units() {
        assertThat(Money.aed(30000)).hasToString("AED 300.00");
        assertThat(Money.aed(-1250)).hasToString("AED -12.50");
    }

    @Test
    void refuses_mixed_currencies() {
        assertThatThrownBy(() -> Money.aed(100).plus(new Money(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refuses_fractions_of_a_fil() {
        assertThatThrownBy(() -> Money.ofMajor("1.005", "AED")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.ofMajor("1.500", "AED")).isEqualTo(Money.aed(150));
    }

    @Test
    void overflow_throws_instead_of_wrapping() {
        assertThatThrownBy(() -> Money.aed(Long.MAX_VALUE).plus(Money.aed(1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void compares_amounts() {
        assertThat(Money.aed(18000)).isLessThan(Money.aed(30000));
        assertThat(Money.aed(-1).isNegative()).isTrue();
    }

    @Test
    void percentages_and_averages_round_half_up() {
        assertThat(Money.aed(3000).percentOf(Money.aed(12000))).isEqualTo(25);
        assertThat(Money.aed(1).percentOf(Money.aed(8))).isEqualTo(13);     // 12.5 -> 13
        assertThat(Money.aed(5).percentOf(Money.aed(0))).isZero();
        assertThat(Money.aed(2300).averageOver(3)).isEqualTo(Money.aed(767));
    }

    @Test
    void displays_amounts_for_people() {
        assertThat(Money.aed(1200).toDisplay()).isEqualTo("AED 12");
        assertThat(Money.aed(1250).toDisplay()).isEqualTo("AED 12.50");
        assertThat(Money.aed(5).toDisplay()).isEqualTo("AED 0.05");
    }
}
