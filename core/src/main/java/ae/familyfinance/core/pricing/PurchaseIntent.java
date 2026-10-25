package ae.familyfinance.core.pricing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What someone wants to buy, as the Shopper agent read it from their words ("Sony WH-CH720N headphones",
 * "a wireless speaker"). The agent only fills this in; the core searches, converts prices to fils and ranks.
 *
 * @param query    what to search for, in plain words
 * @param brand    the brand, if they named one
 * @param model    the model code, if they named one ("WH-CH720N"). With a model (or a GTIN) the core looks for that
 *                 exact item and keeps other models apart as substitutes; without one, everything that fits counts.
 * @param gtin     the barcode number, if known (rare when typed)
 * @param category one of the spend categories (SpendCategory), for the family rules; optional
 * @param said     the words as typed, for the audit
 */
public record PurchaseIntent(@NotBlank @Size(max = 120) String query,
                             @Size(max = 60) String brand,
                             @Size(max = 60) String model,
                             @Size(max = 20) String gtin,
                             @Size(max = 40) String category,
                             @Size(max = 300) String said) {

    public static PurchaseIntent of(String query) {
        return new PurchaseIntent(query, null, null, null, null, null);
    }

    /** A specific item was asked for, so other models are substitutes. */
    public boolean exact() {
        return notBlank(model) || notBlank(gtin);
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
