package ae.familyfinance.core.plan;

import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import org.springframework.http.HttpStatus;

/**
 * Amounts arrive as the text a person (or an agent copying them) typed: "30000", "30,000", "7.50".
 * The core turns them into fils; nobody else converts money.
 */
public final class Amounts {

    /** AED 10 million: far above any family bill, low enough to catch a typo with extra zeros. */
    static final long MAX_MINOR = 1_000_000_000L;

    private Amounts() {
    }

    public static Money parse(String text, String currency, String what) {
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please give the " + what);
        }
        String cleaned = text.trim().replaceFirst("(?i)^(aed|dhs?|dirhams?)\\s*", "")
                .replaceFirst("(?i)\\s*(aed|dhs?|dirhams?)$", "").replace(",", "").replace(" ", "");
        Money amount;
        try {
            amount = Money.ofMajor(cleaned, currency);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "I couldn't read the " + what + " \"" + text + "\"");
        }
        if (amount.minor() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The " + what + " must be more than zero");
        }
        if (amount.minor() > MAX_MINOR) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The " + what + " looks too big. Check the number of zeros");
        }
        return amount;
    }

    public static Money parseOptional(String text, String currency, String what) {
        return text == null || text.isBlank() ? null : parse(text, currency, what);
    }
}
