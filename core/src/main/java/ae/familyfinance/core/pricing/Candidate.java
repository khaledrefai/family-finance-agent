package ae.familyfinance.core.pricing;

import ae.familyfinance.core.money.Money;

import java.time.Instant;

/**
 * One shop's offer before ranking, from the live search, the cache or the saved price list.
 * Prices are already {@link Money} (fils): the parser converts the shop's text, nothing else does.
 *
 * @param delivery null when the shop doesn't say what delivery costs (shown as unknown, never guessed)
 * @param fees     other fees the shop lists (cash on delivery, service); zero when none are listed
 * @param verified a second, narrower search found the same shop selling this item at this price
 */
public record Candidate(String store, String title, Money price, Money delivery, Money fees, String link,
                        String gtin, Source source, Instant checkedAt, boolean verified) {

    public enum Source {
        /** Searched just now. */
        LIVE,
        /** A search from the last 24 hours (or the pre-warmed cache while recording). */
        CACHE,
        /** Our hand-filled price list: used when there is no live search or it found nothing. */
        SEEDED
    }

    public boolean deliveryKnown() {
        return delivery != null;
    }

    /** What you pay: price + delivery + fees. With delivery unknown, the part that is known (price + fees). */
    public Money total() {
        Money t = price.plus(fees);
        return delivery == null ? t : t.plus(delivery);
    }

    Candidate checked(Money newPrice, Money newDelivery, Instant at) {
        return new Candidate(store, title, newPrice, newDelivery, fees, link, gtin, source, at, true);
    }

    Candidate unverified() {
        return new Candidate(store, title, price, delivery, fees, link, gtin, source, checkedAt, false);
    }
}
