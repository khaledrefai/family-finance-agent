package ae.familyfinance.core.money;

import java.math.BigDecimal;
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
