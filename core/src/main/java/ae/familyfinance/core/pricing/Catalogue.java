package ae.familyfinance.core.pricing;

import ae.familyfinance.core.common.ClockConfig;
import ae.familyfinance.core.money.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Our saved price list ({@code resources/pricing/catalogue.json}): about 90 things families buy, each with a few
 * UAE shops' prices typed in by hand. It is the last fallback when there is no live search (SEARCH_LIVE=off, no
 * SerpApi key, the search failed or found nothing), and it gives the usual price of a known model for the
 * too-cheap check. Its offers are shown as "saved prices", never as live ones.
 */
@Component
public class Catalogue {

    /** One thing, with the shops' prices as typed: "289" or "289.50" dirhams; delivery null = not listed. */
    public record Item(String id, String title, String brand, String model, String kind, String category,
                       List<String> words, List<Price> offers) {

        String searchText() {
            return title + " " + (kind == null ? "" : kind) + " " + String.join(" ", words == null ? List.of() : words);
        }
    }

    public record Price(String store, String price, String delivery, String fees, String link) {
    }

    record File(LocalDate checkedOn, String note, List<Item> items) {
    }

    private final File file;

    public Catalogue(ObjectMapper json) {
        try (InputStream in = new ClassPathResource("pricing/catalogue.json").getInputStream()) {
            this.file = json.readValue(in, File.class);
        } catch (IOException e) {
            throw new UncheckedIOException("pricing/catalogue.json could not be read", e);
        }
    }

    public int size() {
        return file.items().size();
    }

    public LocalDate checkedOn() {
        return file.checkedOn();
    }

    /** The item for this exact model or GTIN, if the list has it. */
    public Optional<Item> exact(PurchaseIntent intent) {
        if (!intent.exact()) {
            return Optional.empty();
        }
        return file.items().stream()
                .filter(i -> PurchaseIntent.notBlank(intent.model()) && PurchaseIntent.notBlank(i.model())
                             && Words.flat(i.model()).equals(Words.flat(intent.model())))
                .findFirst();
    }

    /** What the list has for the intent: the exact item and others of its kind, or everything that fits the words. */
    public List<Item> matching(PurchaseIntent intent) {
        Optional<Item> exact = exact(intent);
        List<String> words = exact.map(i -> Words.keywords(i.kind() == null ? "" : i.kind(), null, null))
                .orElseGet(() -> Words.keywords(intent.query(), intent.brand(), intent.model()));
        List<Item> out = new ArrayList<>();
        exact.ifPresent(out::add);
        if (words.isEmpty()) {
            return out;
        }
        file.items().stream().filter(i -> exact.map(e -> e != i).orElse(true))
                .filter(i -> Words.fits(i.searchText(), words)).forEach(out::add);
        return out;
    }

    /** The saved offers of these items as candidates for the {@link PriceEngine}. */
    public List<Candidate> candidates(List<Item> items) {
        Instant at = file.checkedOn().atStartOfDay(ClockConfig.UAE).toInstant();
        List<Candidate> out = new ArrayList<>();
        for (Item i : items) {
            for (Price p : i.offers()) {
                out.add(new Candidate(p.store(), i.title(), money(p.price()), p.delivery() == null ? null : money(p.delivery()),
                        p.fees() == null ? Money.aed(0) : money(p.fees()), p.link(), null, Candidate.Source.SEEDED, at, false));
            }
        }
        return out;
    }

    /** The lowest saved price of the item: the "usual price" for the too-cheap check. */
    public Money usualPrice(Item item) {
        return item.offers().stream().map(p -> money(p.price())).min(Comparator.naturalOrder()).orElse(null);
    }

    private static Money money(String text) {
        return Money.ofMajor(text, Money.AED);
    }
}
