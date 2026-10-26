package ae.familyfinance.core.outings;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.outings.OutingEngine.Kind;
import ae.familyfinance.core.outings.OutingEngine.Option;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Our saved list of Dubai family outings ({@code resources/outings/outings.json}): about 15 places with ticket prices
 * typed in by hand (estimates, verify). The outings planner always works from it, so a plan never waits on a website,
 * and the app says these are saved prices to check on the site before booking.
 */
@Component
public class OutingCatalogue {

    /** As typed: prices in dirhams ("50"), months 1-12 (empty = all year). */
    record Entry(String key, String name, String area, List<Kind> kinds, String adult, String child, int childUpTo,
                 List<Integer> months, String about, String site) {
    }

    record File(LocalDate checkedOn, String note, List<Entry> outings) {
    }

    private final LocalDate checkedOn;
    private final List<Option> options;

    public OutingCatalogue(ObjectMapper json) {
        File file;
        try (InputStream in = new ClassPathResource("outings/outings.json").getInputStream()) {
            file = json.readValue(in, File.class);
        } catch (IOException e) {
            throw new UncheckedIOException("outings/outings.json could not be read", e);
        }
        this.checkedOn = file.checkedOn();
        this.options = file.outings().stream().map(e -> new Option(e.key(), e.name(), e.area(),
                e.kinds().isEmpty() ? EnumSet.noneOf(Kind.class) : EnumSet.copyOf(e.kinds()),
                Money.ofMajor(e.adult(), Money.AED), Money.ofMajor(e.child(), Money.AED), e.childUpTo(),
                new LinkedHashSet<>(e.months() == null ? List.of() : e.months()), e.about(), e.site())).toList();
    }

    public List<Option> options() {
        return options;
    }

    public LocalDate checkedOn() {
        return checkedOn;
    }
}
