package ae.familyfinance.core.outings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * SerpApi's Google Events search ({@code engine=google_events}), this week's events in Dubai. Uses the same
 * SERPAPI_KEY as the price search; one search a day at most, thanks to the cache. Parameter names are from
 * SerpApi's documentation and not yet tried with a real key (verify).
 */
@Component
public class SerpApiEventsSource implements EventsSource {

    private static final Logger log = LoggerFactory.getLogger(SerpApiEventsSource.class);

    private final String key;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;

    public SerpApiEventsSource(@Value("${app.pricing.serpapi-key:}") String key,
                               @Value("${app.pricing.serpapi-url:https://serpapi.com/search.json}") String baseUrl,
                               @Value("${app.pricing.timeout:6s}") Duration timeout) {
        this.key = key == null ? "" : key.trim();
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String engine() {
        return "google_events";
    }

    @Override
    public boolean available() {
        return !key.isEmpty();
    }

    @Override
    public Optional<String> search(String query) {
        if (!available()) {
            return Optional.empty();
        }
        String url = baseUrl + "?engine=google_events&gl=ae&hl=en&htichips=date:week"
                     + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                     + "&api_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                String body = res.body() == null ? "" : res.body();
                log.warn("Events search failed with HTTP {}: {}", res.statusCode(), body.length() > 200 ? body.substring(0, 200) : body);
                return Optional.empty();
            }
            return Optional.of(res.body());
        } catch (java.io.IOException e) {
            log.warn("Events search failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
