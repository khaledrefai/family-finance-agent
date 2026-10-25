package ae.familyfinance.core.money;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount of money in minor units (fils for AED: 1 AED = 100 fils).
 * All money maths in the system goes through this type. Agents never do arithmetic themselves;
 * they ask the core API, which uses this. Overflow throws instead of silently wrapping.
 */
public record Money(long minor, String currency) implements Comparable<Money> {

    public static final String AED = "AED";

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code: " + currency);
        }
    }

    public static Money aed(long minor) {
        return new Money(minor, AED);
    }

    /** Parses "300", "300.5" or "300.50" as a whole amount; rejects more than 2 decimal places. */
    public static Money ofMajor(String amount, String currency) {
        BigDecimal value = new BigDecimal(amount);
        if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("more than 2 decimal places: " + amount);
        }
        return new Money(value.movePointRight(2).longValueExact(), currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minor, factor), currency);
    }

    /**
     * Whole-number share of {@code total}, rounded half up: 30 of 120 is 25. Returns 0 when total is zero.
     * Percentages live here so the agents never divide anything themselves.
     */
    public int percentOf(Money total) {
        requireSameCurrency(total);
        if (total.minor == 0) {
            return 0;
        }
        return (int) ((Math.multiplyExact(minor, 100L) * 2 + total.minor) / (total.minor * 2));
    }

    /** Rounded-half-up average of {@code count} items, still in minor units. */
    public Money averageOver(long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        return new Money((minor * 2 + count) / (count * 2), currency);
    }

    /** {@code this × numerator ÷ denominator}, rounded half up. 52 weeks ÷ 12 months: {@code weekly.scaled(52, 12)}. */
    public Money scaled(long numerator, long denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive: " + denominator);
        }
        long product = Math.multiplyExact(minor, numerator);
        long rounded = product >= 0
                ? (product * 2 + denominator) / (denominator * 2)
                : -((-product * 2 + denominator) / (denominator * 2));
        return new Money(rounded, currency);
    }

    /** Divided into {@code parts}, rounded up so the parts never fall short (monthly reserves). */
    public Money dividedRoundingUp(long parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be positive: " + parts);
        }
        return new Money(Math.ceilDiv(minor, parts), currency);
    }

    /** Rounded up to a whole multiple of {@code unitMinor}: AED 1,051.20 to the next AED 50 is AED 1,100. */
    public Money roundedUpTo(long unitMinor) {
        return new Money(Math.multiplyExact(Math.ceilDiv(minor, unitMinor), unitMinor), currency);
    }

    /** Rounded down to a whole multiple of {@code unitMinor} (a cautious planning base). */
    public Money roundedDownTo(long unitMinor) {
        return new Money(Math.multiplyExact(Math.floorDiv(minor, unitMinor), unitMinor), currency);
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    /** For people: "AED 12" for whole amounts, "AED 12.50" otherwise, "AED 34,500" with thousands, "-AED 2,400" below zero. */
    public String toDisplay() {
        String sign = minor < 0 ? "-" : "";
        BigDecimal abs = toMajor().abs();
        String number = minor % 100 == 0
                ? String.format(Locale.ROOT, "%,d", abs.longValueExact())
                : String.format(Locale.ROOT, "%,.2f", abs);
        return sign + currency + " " + number;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, 2);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return currency + " " + toMajor().toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
