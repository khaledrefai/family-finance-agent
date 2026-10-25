package ae.familyfinance.core.gold;

import ae.familyfinance.core.money.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Values the gold a family holds at today's price per gram for its purity. Weights are whole milligrams and
 * prices whole fils per gram, so the value is one exact multiplication, rounded half up to the fil.
 * Pure: no database, no clock.
 */
public final class GoldEngine {

    /** Purities a family can log, purest first. */
    public static final List<String> PURITIES = List.of("24K", "22K", "21K", "18K");

    public record Holding(String purity, long gramsMg, long paidMinor, boolean jewellery) {
    }

    /** {@code belowPaid}: worth less today than was paid (usually jewellery: the making charge). */
    public record Value(Money value, Money paid, boolean belowPaid) {
    }

    private GoldEngine() {
    }

    /** grams × price per gram. 10.5 g at AED 320.00 = AED 3,360. */
    public static Money value(long gramsMg, long perGramMinor, String currency) {
        return new Money(perGramMinor, currency).scaled(gramsMg, 1000);
    }

    public static Value value(Holding h, Map<String, Long> perGram, String currency) {
        Long price = perGram.get(h.purity());
        if (price == null) {
            throw new IllegalStateException("No gold price for " + h.purity());
        }
        Money value = value(h.gramsMg(), price, currency);
        Money paid = new Money(h.paidMinor(), currency);
        return new Value(value, paid, value.compareTo(paid) < 0);
    }

    /** "10.5" or "10" grams -> 10500 mg. Up to 3 decimal places; null when it isn't a weight. */
    public static Long parseGrams(String text) {
        if (text == null || !text.trim().matches("[0-9]{1,6}(\\.[0-9]{1,3})?")) {
            return null;
        }
        long mg = new BigDecimal(text.trim()).movePointRight(3).longValueExact();
        return mg > 0 ? mg : null;
    }

    /** 10500 mg -> "10.5", 40000 -> "40". */
    public static String grams(long mg) {
        return BigDecimal.valueOf(mg, 3).stripTrailingZeros().toPlainString();
    }
}
