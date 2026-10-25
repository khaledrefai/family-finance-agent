package ae.familyfinance.core.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replays SerpApi answers kept in test/resources/pricing/serpapi (index.json maps each search to its file), so the
 * tests never call the paid search. An unknown search answers nothing, like a timeout. {@link #searches} lists what was asked.
 */
public class ReplaySource implements ShoppingSource {

    public final List<String> searches = new ArrayList<>();
    private final JsonNode index;

    public ReplaySource() {
        this.index = read("index.json");
    }

    private static JsonNode read(String name) {
        try (InputStream in = new ClassPathResource("pricing/serpapi/" + name).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String body(String name) {
        try (InputStream in = new ClassPathResource("pricing/serpapi/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String engine() {
        return "google_shopping";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public synchronized Optional<String> search(String query) {
        searches.add(query);
        JsonNode file = index.get(Words.normal(query));
        return file == null ? Optional.empty() : Optional.of(body(file.asText()));
    }
}
