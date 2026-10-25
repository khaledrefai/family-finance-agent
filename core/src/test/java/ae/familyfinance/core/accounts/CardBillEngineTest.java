package ae.familyfinance.core.accounts;

import ae.familyfinance.core.accounts.CardBillEngine.Bill;
import ae.familyfinance.core.accounts.CardBillEngine.Card;
import ae.familyfinance.core.accounts.CardBillEngine.CardLine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardBillEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    // Owes AED 5,950 as typed yesterday. Statement on the 26th, payment due on the 13th. Limit AED 20,000.
    private static final Card VISA = new Card(UUID.randomUUID(), "Visa", 595_000, TODAY.minusDays(1), 2_000_000, 26, 13, "AED");

    @Test
    void the_bill_is_what_is_owed_plus_purchases_up_to_the_statement() {
        Bill b = CardBillEngine.forecast(VISA, List.of(
                new CardLine(TODAY.minusDays(3), 18_000),   // before the balance was typed: already inside it
                new CardLine(TODAY, 8_500)), TODAY);
        assertThat(b.statementOn()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(b.dueOn()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(b.bill().minor()).isEqualTo(603_500);
        assertThat(b.owedNow().minor()).isEqualTo(603_500);
        assertThat(b.usedPercent()).isEqualTo(30);
        assertThat(b.overLimit()).isFalse();
    }

    @Test
    void a_payment_into_the_card_lowers_the_bill() {
        Bill b = CardBillEngine.forecast(VISA, List.of(new CardLine(TODAY, -200_000)), TODAY);
        assertThat(b.bill().minor()).isEqualTo(395_000);
    }

    @Test
    void a_purchase_today_lands_on_the_coming_bill() {
        Bill b = CardBillEngine.withPurchase(VISA, List.of(), TODAY, 30_000);
        assertThat(b.bill().minor()).isEqualTo(625_000);
    }
}
