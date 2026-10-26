package ae.familyfinance.core.outings;

import java.util.Optional;

/**
 * Where "on this week" events come from. Today only SerpApi's Google Events search ({@link SerpApiEventsSource});
 * tests replace it with recorded answers. Events are extra ideas: their prices are on the organisers' sites, so the
 * outings planner never counts them in a plan.
 */
public interface EventsSource {

    /** The cache key prefix and audit name of this source. */
    String engine();

    /** False when it can't be used at all (no API key). */
    boolean available();

    /** The raw JSON answer, or empty when the search failed or timed out. Never throws. */
    Optional<String> search(String query);
}
