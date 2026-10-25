package ae.familyfinance.core.pricing;

import ae.familyfinance.core.money.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks shop offers for one {@link PurchaseIntent}. Pure Java, no database, no network, so the tests can replay
 * recorded searches through it.
 * <ul>
 *   <li>Parts and add-ons ("case for ...") and things that don't fit the words are left out.</li>
 *   <li>Asked for a model (or a GTIN): offers for that exact item are ranked; other models are kept apart as
 *       substitutes. Asked in general ("wireless headphones"): everything that fits is ranked together.</li>
 *   <li>The same listing twice (same shop, same title) counts once, at its lower total.</li>
 *   <li>An offer priced at less than half the usual price is dropped as too good to be true. The usual price is the
 *       saved price list's for that exact item, otherwise the middle price of the offers found.</li>
 *   <li>Ranked by payable total (price + delivery + fees). When delivery isn't listed the known part is used, and at
 *       the same total a known delivery comes first. The best offer is the cheapest that was re-checked and has a
 *       known delivery, then the cheapest re-checked, then the cheapest.</li>
 * </ul>
 */
public final class PriceEngine {

    /** At most this many offers and substitutes are shown. */
    static final int MAX_OFFERS = 6;
    static final int MAX_SUBSTITUTES = 3;

    private PriceEngine() {
    }

    /**
     * @param dropped    how many offers were left out as priced too low to trust
     * @param reference  the usual price used for that check (null when there was nothing to compare with)
     */
    public record Ranked(List<Candidate> offers, List<Candidate> substitutes, Candidate best, int dropped,
                         Money reference) {
    }

    static final Comparator<Candidate> BY_TOTAL = Comparator
            .comparingLong((Candidate c) -> c.total().minor())
            .thenComparing(c -> !c.deliveryKnown())
            .thenComparing(c -> !c.verified())
            .thenComparing(c -> Words.normal(c.store()));

    /**
     * @param usualPrice the saved price list's price for the exact item asked for, or null
     * @param kind       the kind of thing ("headphones") when a model was asked for and the saved list knows it;
     *                   substitutes must be that kind
     */
    public static Ranked rank(PurchaseIntent intent, List<Candidate> all, Money usualPrice, String kind) {
        boolean exact = intent.exact();
        List<String> words = Words.keywords(intent.query(), intent.brand(), intent.model());
        List<String> kindWords = kind == null ? words : Words.keywords(kind, null, null);
        String head = exact && PurchaseIntent.notBlank(intent.model()) ? intent.model()
                : words.isEmpty() ? "" : words.get(words.size() - 1);

        List<Candidate> main = new ArrayList<>();
        List<Candidate> others = new ArrayList<>();
        for (Candidate c : all) {
            if (Words.accessory(c.title(), intent.query(), head)) {
                continue;
            }
            if (exact) {
                if (same(intent, c)) {
                    main.add(c);
                } else if (Words.fits(c.title(), kindWords)) {
                    others.add(c);
                }
            } else if (Words.fits(c.title(), words)) {
                main.add(c);
            }
        }
        List<Candidate> ranked = once(main);
        List<Candidate> subs = cheapestPerModel(once(others));

        Money reference = exact && usualPrice != null ? usualPrice : middlePrice(ranked);
        int before = ranked.size();
        if (reference != null) {
            // Less than half the usual price: a wrong listing, a part, or a scam, so it is never ranked
            ranked.removeIf(c -> c.price().times(2).compareTo(reference) < 0);
        }
        int dropped = before - ranked.size();

        ranked.sort(BY_TOTAL);
        subs.sort(BY_TOTAL);
        List<Candidate> shown = List.copyOf(ranked.subList(0, Math.min(MAX_OFFERS, ranked.size())));
        Candidate best = shown.stream().filter(c -> c.verified() && c.deliveryKnown()).findFirst()
                .or(() -> shown.stream().filter(Candidate::verified).findFirst())
                .orElse(shown.isEmpty() ? null : shown.get(0));
        return new Ranked(shown, List.copyOf(subs.subList(0, Math.min(MAX_SUBSTITUTES, subs.size()))), best, dropped,
                reference);
    }

    /** The exact item: the same GTIN, or the model code in the title. */
    static boolean same(PurchaseIntent intent, Candidate c) {
        if (PurchaseIntent.notBlank(intent.gtin()) && intent.gtin().equals(c.gtin())) {
            return true;
        }
        return PurchaseIntent.notBlank(intent.model()) && Words.hasModel(c.title(), intent.model());
    }

    /** One per shop and title, the lower total kept. */
    private static List<Candidate> once(List<Candidate> list) {
        Map<String, Candidate> by = new LinkedHashMap<>();
        for (Candidate c : list) {
            by.merge(Words.listingKey(c.store(), c.title()), c, (a, b) -> BY_TOTAL.compare(a, b) <= 0 ? a : b);
        }
        return new ArrayList<>(by.values());
    }

    /** Substitutes are other models: each one once, at the shop where it costs least. */
    private static List<Candidate> cheapestPerModel(List<Candidate> list) {
        Map<String, Candidate> by = new LinkedHashMap<>();
        for (Candidate c : list) {
            by.merge(Words.flat(c.title()), c, (a, b) -> BY_TOTAL.compare(a, b) <= 0 ? a : b);
        }
        return new ArrayList<>(by.values());
    }

    /** The middle price (the lower middle for an even count), or null with fewer than 3 offers to compare. */
    static Money middlePrice(List<Candidate> list) {
        if (list.size() < 3) {
            return null;
        }
        List<Money> prices = list.stream().map(Candidate::price).sorted().toList();
        return prices.get((prices.size() - 1) / 2);
    }
}
