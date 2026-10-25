package ae.familyfinance.core.documents;

import ae.familyfinance.core.documents.FindingsEngine.Finding;
import ae.familyfinance.core.documents.FindingsEngine.Kind;
import ae.familyfinance.core.documents.FindingsEngine.Payment;
import ae.familyfinance.core.documents.LineMatcher.Known;
import ae.familyfinance.core.documents.LineMatcher.Line;
import ae.familyfinance.core.documents.LineMatcher.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The week 3 rules that stop double counting and find double charges and forgotten subscriptions. */
class MatchingAndFindingsTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    static final UUID VISA = UUID.randomUUID();
    static final UUID BANK = UUID.randomUUID();

    @Test
    void merchant_keys_ignore_branches_places_and_numbers() {
        assertThat(MerchantKey.of("SPINNEYS JUMEIRAH DUBAI AE")).isEqualTo("SPINNEYS");
        assertThat(MerchantKey.of("ENOC 1023 AL BARSHA")).isEqualTo("ENOC");
        assertThat(MerchantKey.of("Carrefour")).isEqualTo("CARREFOUR");
        assertThat(MerchantKey.of("DU TELECOM")).isEqualTo("DU TELECOM");
        assertThat(MerchantKey.of("1234 *")).isNull();
        assertThat(MerchantKey.same("CARREFOUR MIRDIF", "CARREFOUR")).isTrue();
        assertThat(MerchantKey.coveredBy("DU TELECOM", "du home and mobile")).isTrue();
        assertThat(MerchantKey.coveredBy("SHAHID VIP", "Streaming bundle")).isFalse();
        assertThat(MerchantKey.label("SHAHID VIP")).isEqualTo("Shahid VIP");
    }

    @Test
    void a_statement_line_already_typed_in_or_on_a_receipt_is_not_counted_again() {
        UUID fuel = UUID.randomUUID();
        UUID receipt = UUID.randomUUID();
        UUID bankPayment = UUID.randomUUID();
        List<Known> known = List.of(
                new Known(fuel, TODAY.minusDays(3), 18000, false, VISA, null, "ENOC"),
                new Known(receipt, TODAY.minusDays(6), 64150, false, VISA, null, "CARREFOUR"),
                new Known(bankPayment, TODAY.minusDays(5), 9900, false, BANK, null, "NOON"),
                new Known(UUID.randomUUID(), TODAY.minusDays(15), 320000, false, BANK, VISA, "CARD"));
        List<Line> lines = List.of(
                new Line(1, TODAY.minusDays(3), 18000, false, "ENOC"),
                new Line(2, TODAY.minusDays(5), 64150, false, "CARREFOUR MIRDIF"),     // posted a day after the receipt
                new Line(3, TODAY.minusDays(5), 9900, false, "NOON"),                  // same amount, but paid from the bank
                new Line(4, TODAY.minusDays(15), 320000, true, "PAYMENT RECEIVED"),     // the card payment typed as a transfer
                new Line(5, TODAY.minusDays(20), 18000, false, "ENOC"));               // too far from the typed one
        List<Result> r = LineMatcher.match(lines, known, VISA);
        assertThat(r.get(0).txId()).isEqualTo(fuel);
        assertThat(r.get(1).txId()).isEqualTo(receipt);
        assertThat(r.get(2).txId()).isNull();
        assertThat(r.get(3).txId()).isNotNull();
        assertThat(r.get(4).txId()).isNull();
    }

    @Test
    void each_known_payment_matches_only_one_line_and_a_repeat_on_the_same_day_is_flagged() {
        UUID typed = UUID.randomUUID();
        List<Result> r = LineMatcher.match(List.of(
                new Line(1, TODAY.minusDays(14), 18900, false, "SPINNEYS"),
                new Line(2, TODAY.minusDays(14), 18900, false, "SPINNEYS JUMEIRAH")),
                List.of(new Known(typed, TODAY.minusDays(14), 18900, false, VISA, null, "SPINNEYS")), VISA);
        assertThat(r.get(0).txId()).isEqualTo(typed);
        assertThat(r.get(1).txId()).isNull();
        assertThat(r.get(1).possibleDuplicate()).isTrue();
        assertThat(r.get(0).possibleDuplicate()).isFalse();
    }

    @Test
    void without_an_account_the_shop_must_match_too() {
        UUID typed = UUID.randomUUID();
        List<Known> known = List.of(new Known(typed, TODAY, 5000, false, VISA, null, "TALABAT"));
        assertThat(LineMatcher.match(List.of(new Line(1, TODAY, 5000, false, "CAREEM")), known, null).get(0).txId()).isNull();
        assertThat(LineMatcher.match(List.of(new Line(1, TODAY, 5000, false, "TALABAT")), known, null).get(0).txId()).isEqualTo(typed);
    }

    private static Payment pay(int daysAgo, long minor, String shop) {
        return new Payment(UUID.randomUUID(), VISA, "Omar's Visa", TODAY.minusDays(daysAgo), minor, MerchantKey.of(shop), shop);
    }

    @Test
    void two_same_charges_on_one_day_are_a_double_charge() {
        List<Finding> f = FindingsEngine.find(List.of(pay(14, 18900, "SPINNEYS JUMEIRAH"), pay(14, 18900, "SPINNEYS JUMEIRAH"),
                pay(13, 18900, "SPINNEYS JUMEIRAH")), List.of(), Set.of(), TODAY, "AED");
        assertThat(f).hasSize(1);
        assertThat(f.get(0).kind()).isEqualTo(Kind.DUPLICATE);
        assertThat(f.get(0).title()).isEqualTo("Two charges of AED 189 at Spinneys on 11 Oct.");
        assertThat(f.get(0).txIds()).hasSize(2);
    }

    @Test
    void a_monthly_payment_not_on_the_list_is_a_subscription() {
        List<Payment> p = List.of(pay(71, 3500, "SHAHID VIP"), pay(40, 3500, "SHAHID VIP"), pay(9, 3500, "SHAHID VIP"));
        List<Finding> f = FindingsEngine.find(p, List.of("Streaming bundle", "du home and mobile"), Set.of(), TODAY, "AED");
        assertThat(f).hasSize(1);
        Finding sub = f.get(0);
        assertThat(sub.kind()).isEqualTo(Kind.SUBSCRIPTION);
        assertThat(sub.title()).isEqualTo("AED 35 to Shahid VIP every month since Aug.");
        assertThat(sub.detail()).contains("AED 420 a year");
        assertThat(sub.nextOn()).isEqualTo(TODAY.minusDays(9).plusMonths(1));
    }

    @Test
    void no_subscription_when_listed_stopped_too_few_or_not_monthly() {
        List<Payment> du = List.of(pay(72, 45000, "DU TELECOM"), pay(42, 45000, "DU"), pay(12, 45000, "DU TELECOM"));
        assertThat(FindingsEngine.find(du, List.of("du home and mobile"), Set.of(), TODAY, "AED")).isEmpty();
        List<Payment> stopped = List.of(pay(150, 3500, "OSN"), pay(120, 3500, "OSN"), pay(90, 3500, "OSN"));
        assertThat(FindingsEngine.find(stopped, List.of(), Set.of(), TODAY, "AED")).isEmpty();
        List<Payment> two = List.of(pay(40, 3500, "SHAHID"), pay(9, 3500, "SHAHID"));
        assertThat(FindingsEngine.find(two, List.of(), Set.of(), TODAY, "AED")).isEmpty();
        List<Payment> weekly = List.of(pay(21, 18000, "ENOC"), pay(14, 18000, "ENOC"), pay(7, 18000, "ENOC"), pay(1, 18000, "ENOC"));
        assertThat(FindingsEngine.find(weekly, List.of(), Set.of(), TODAY, "AED")).isEmpty();
    }

    @Test
    void an_answered_finding_is_not_shown_again() {
        List<Payment> p = List.of(pay(14, 18900, "SPINNEYS"), pay(14, 18900, "SPINNEYS"));
        String key = FindingsEngine.find(p, List.of(), Set.of(), TODAY, "AED").get(0).key();
        assertThat(FindingsEngine.find(p, List.of(), Set.of(key), TODAY, "AED")).isEmpty();
    }

    @Test
    void amounts_read_from_documents_are_parsed_by_the_core() {
        assertThat(DocumentService.signed("189.00", "AED").money().minor()).isEqualTo(18900);
        assertThat(DocumentService.signed("-3,200.00", "AED").credit()).isTrue();
        assertThat(DocumentService.signed("3,200.00 CR", "AED").money().minor()).isEqualTo(320000);
        assertThat(DocumentService.signed("(12.50)", "AED").credit()).isTrue();
        assertThat(DocumentService.signed("abc", "AED")).isNull();
        assertThat(DocumentService.date("14/10/2026")).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(DocumentService.date("2026-10-14")).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(DocumentService.date("31/02/2026")).isNull();
        assertThat(DocumentService.category(null, false, "TALABAT DUBAI", null)).isEqualTo("EATING_OUT");
        assertThat(DocumentService.category(null, false, "NOON FOOD", null)).isEqualTo("EATING_OUT");
        assertThat(DocumentService.category(null, false, "NOON.COM", null)).isEqualTo("SHOPPING");
        assertThat(DocumentService.category(null, true, "PAYMENT RECEIVED", null)).isEqualTo("CARD_PAYMENT");
    }

    @Test
    void the_demo_receipt_items_add_up_to_its_total() {
        BigDecimal sum = DemoSamples.RECEIPT_ITEMS.stream().map(i -> new BigDecimal(i[1])).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(DemoSamples.RECEIPT_TOTAL);
        assertThat(DemoSamples.statement(TODAY)).contains("2026-10-16,SHAHID VIP,35.00").contains("7710");
    }
}
