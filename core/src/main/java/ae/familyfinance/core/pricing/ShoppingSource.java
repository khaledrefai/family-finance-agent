package ae.familyfinance.core.pricing;

import java.util.Optional;

/**
 * Where live prices come from. Today only SerpApi's Google Shopping search ({@link SerpApiSource}); tests replace
 * it with recorded answers. Approved retailer feeds can be added later as another source.
 */
public interface ShoppingSource {

    /** The cache key prefix and audit name of this source. */
    String engine();

    /** False when it can't be used at all (no API key), so the core goes straight to the cache or the saved list. */
    boolean available();

    /** The raw JSON answer for this search, or empty when the search failed or timed out. Never throws. */
    Optional<String> search(String query);
}
