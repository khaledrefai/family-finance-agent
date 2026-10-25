package ae.familyfinance.core.pricing;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.pricing.Candidate.Source;
import ae.familyfinance.core.pricing.PriceEngine.Ranked;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The parser and the ranking, on SerpApi answers recorded in test/resources/pricing/serpapi. No network, no database. */
class PriceEngineTest {

    static final Instant AT = Instant.parse("2026-10-25T17:30:00Z");
    final ObjectMapper json = new ObjectMapper();

    List<Candidate> replay(String file) throws Exception {
        return SerpApiParser.parse(json.readTree(ReplaySource.body(file)), Source.LIVE, AT);
    }

    static PurchaseIntent sony() {
        return new PurchaseIntent("Sony WH-CH720N", "Sony", "WH-CH720N", null, null, null);
    }

    // ------------------------------------------------------------------ reading the shops' prices

    @Test
    void prices_are_read_from_the_text_in_fils_and_only_dirhams_count() {
        assertThat(SerpApiParser.dirhams("AED 1,299.00")).isEqualTo(Money.aed(129_900));
        assertThat(SerpApiParser.dirhams("Dhs 45")).isEqualTo(Money.aed(4_500));
        assertThat(SerpApiParser.dirhams("د.إ.‏ 299.50")).isEqualTo(Money.aed(29_950));
        assertThat(SerpApiParser.dirhams("$349.99")).isNull();
        assertThat(SerpApiParser.dirhams("349.99")).isNull();
    }

    @Test
    void delivery_is_free_a_stated_amount_or_unknown() throws Exception {
        List<Candidate> c = replay("wireless-headphones.json");
        assertThat(c).extracting(Candidate::store).doesNotContain("Virgin Megastore");   // priced in dollars
        Candidate amazon = c.get(0);
        assertThat(amazon.delivery()).isEqualTo(Money.aed(0));
        assertThat(c.get(1).deliveryKnown()).isFalse();                                  // noon doesn't say
        assertThat(c.get(2).delivery()).isEqualTo(Money.aed(0));                         // "Free delivery by Wed"
        Candidate soundcore = c.stream().filter(x -> x.title().startsWith("Soundcore")).findFirst().orElseThrow();
        assertThat(soundcore.delivery()).isEqualTo(Money.aed(1_000));
        assertThat(soundcore.total()).isEqualTo(Money.aed(16_900));                      // 159 + 10
    }

    // ------------------------------------------------------------------ ranking

    @Test
    void asked_in_general_everything_that_fits_is_ranked_by_total_without_parts_or_scams() throws Exception {
        Ranked r = PriceEngine.rank(PurchaseIntent.of("wireless headphones"), replay("wireless-headphones.json"), null, null);

        assertThat(r.offers()).extracting(Candidate::store, c -> c.total().minor()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("noon", 14_900L),        // JBL, the AED 155 copy counted once
                org.assertj.core.groups.Tuple.tuple("Amazon.ae", 16_900L),   // Soundcore 159 + 10 delivery
                org.assertj.core.groups.Tuple.tuple("Amazon.ae", 28_900L),
                org.assertj.core.groups.Tuple.tuple("noon", 29_900L),        // delivery not listed
                org.assertj.core.groups.Tuple.tuple("Sharaf DG", 109_900L));
        assertThat(r.offers()).extracting(Candidate::title).noneMatch(t -> t.contains("Ear Pads"));
        assertThat(r.dropped()).isEqualTo(1);                                // AED 35, under half the middle price
        assertThat(r.reference()).isEqualTo(Money.aed(15_900));
        assertThat(r.substitutes()).isEmpty();
        assertThat(r.best().total()).isEqualTo(Money.aed(14_900));
    }

    @Test
    void asked_for_a_model_other_models_are_substitutes_and_the_usual_price_drops_the_fake() throws Exception {
        Ranked r = PriceEngine.rank(sony(), replay("sony-wh-ch720n.json"), Money.aed(28_900), "headphones");

        assertThat(r.offers()).extracting(Candidate::store).containsExactly("Amazon.ae", "noon", "Sharaf DG");
        assertThat(r.dropped()).isEqualTo(1);                                 // AED 119 "original": too good to be true
        assertThat(r.substitutes()).extracting(Candidate::title)
                .containsExactly("Sony WH-CH520 Wireless On-Ear Headphones", "JBL Tune 720BT Wireless Over-Ear Headphones");
        assertThat(r.offers()).extracting(Candidate::title).noneMatch(t -> t.startsWith("Hard Case"));
    }

    @Test
    void the_best_offer_is_the_cheapest_rechecked_one_with_known_delivery() {
        Candidate cheapUnchecked = offer("A", 25_000, 0, false);
        Candidate checkedNoDelivery = offer("B", 26_000, null, true);
        Candidate checked = offer("C", 27_000, 0, true);
        Ranked r = PriceEngine.rank(PurchaseIntent.of("headphones"), List.of(checked, checkedNoDelivery, cheapUnchecked), null, null);

        assertThat(r.offers()).extracting(Candidate::store).containsExactly("A", "B", "C");
        assertThat(r.best().store()).isEqualTo("C");
    }

    @Test
    void at_the_same_total_a_known_delivery_comes_first() {
        Candidate unknown = offer("A", 20_000, null, false);
        Candidate known = offer("B", 20_000, 0, false);
        Ranked r = PriceEngine.rank(PurchaseIntent.of("headphones"), List.of(unknown, known), null, null);
        assertThat(r.offers()).extracting(Candidate::store).containsExactly("B", "A");
    }

    @Test
    void words_find_the_thing_and_its_model() {
        assertThat(Words.keywords("cheap wireless headphones", null, null)).containsExactly("wireless", "headphone");
        assertThat(Words.hasModel("SONY WH CH720N Black", "WH-CH720N")).isTrue();
        assertThat(Words.hasModel("Sony WH-CH520", "WH-CH720N")).isFalse();
        assertThat(Words.modelCodes("is the Sony WH-CH720N cheaper on noon")).containsExactly("WH-CH720N");
        assertThat(Words.accessory("Sony Headphones with Carrying Case", "sony headphones", "headphone")).isFalse();
        assertThat(Words.accessory("Carrying Case for Sony Headphones", "sony headphones", "headphone")).isTrue();
    }

    private static Candidate offer(String store, long price, Integer delivery, boolean verified) {
        return new Candidate(store, "Wireless headphones " + store, Money.aed(price),
                delivery == null ? null : Money.aed(delivery), Money.aed(0), null, null, Source.LIVE, AT, verified);
    }
}
