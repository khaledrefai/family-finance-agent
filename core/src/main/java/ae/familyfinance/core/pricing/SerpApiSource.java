package ae.familyfinance.core.pricing;

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
 * Google Shopping results for the UAE through SerpApi (https://serpapi.com/google-shopping-api): one paid search
 * per call, with {@code gl=ae} so prices are the UAE shops' prices in dirhams. Each search waits at most
 * {@code app.pricing.timeout} (6 s by default); after that the core uses the cache or the saved price list.
 * The key comes from SERPAPI_KEY and is never logged.
 */
@Component
public class SerpApiSource implements ShoppingSource {

    private static final Logger log = LoggerFactory.getLogger(SerpApiSource.class);

    private final String key;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;

    public SerpApiSource(@Value("${app.pricing.serpapi-key:}") String key,
                         @Value("${app.pricing.serpapi-url:https://serpapi.com/search.json}") String baseUrl,
                         @Value("${app.pricing.timeout:6s}") Duration timeout) {
        this.key = key == null ? "" : key.trim();
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String engine() {
        return "google_shopping";
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
        String url = baseUrl + "?engine=google_shopping&gl=ae&hl=en&google_domain=google.ae"
                     + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                     + "&api_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                // The body says why (no searches left, bad key) without repeating the key
                log.warn("Price search failed with HTTP {}: {}", res.statusCode(), abbreviate(res.body()));
                return Optional.empty();
            }
            return Optional.of(res.body());
        } catch (java.io.IOException e) {
            log.warn("Price search failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static String abbreviate(String body) {
        return body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
    }
}
