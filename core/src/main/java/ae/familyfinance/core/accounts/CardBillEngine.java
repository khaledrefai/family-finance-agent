package ae.familyfinance.core.accounts;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.plan.Schedules;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Forecasts a credit card's next bill from what the family typed in, with no bank link:
 * what was owed on the as-of date, plus card payments logged after it up to the next statement,
 * minus any card payments (transfers into the card) logged after it.
 */
public final class CardBillEngine {

    public record Card(UUID id, String label, long owedMinor, LocalDate asOf, long limitMinor, int statementDay,
                       int dueDay, String currency) {
    }

    /** A ledger line on this card: a purchase ({@code minor} > 0) or a payment into it ({@code minor} < 0). */
    public record CardLine(LocalDate on, long minor) {
    }

    /**
     * {@code bill}: the forecast statement balance, paid in full on {@code dueOn}.
     * {@code owedNow}: owed today (as typed plus logged since). {@code usedPercent}: owed now as a share of the limit.
     */
    public record Bill(UUID cardId, LocalDate statementOn, LocalDate dueOn, Money bill, Money owedNow, int usedPercent,
                       boolean overLimit) {
    }

    private CardBillEngine() {
    }

    public static Bill forecast(Card card, List<CardLine> lines, LocalDate today) {
        LocalDate statement = Schedules.nextStatement(today, card.statementDay());
        LocalDate due = Schedules.dueAfter(statement, card.dueDay());
        Money owedNow = new Money(card.owedMinor(), card.currency());
        Money bill = owedNow;
        for (CardLine l : lines) {
            if (!l.on().isAfter(card.asOf())) {
                continue; // already inside the typed balance
            }
            Money amount = new Money(l.minor(), card.currency());
            if (!l.on().isAfter(today)) {
                owedNow = owedNow.plus(amount);
            }
            if (!l.on().isAfter(statement)) {
                bill = bill.plus(amount);
            }
        }
        Money limit = new Money(card.limitMinor(), card.currency());
        bill = bill.max(Money.zero(card.currency()));
        owedNow = owedNow.max(Money.zero(card.currency()));
        return new Bill(card.id(), statement, due, bill, owedNow, owedNow.percentOf(limit), owedNow.compareTo(limit) > 0);
    }

    /** The same forecast with one more purchase on the card today (the card check before buying). */
    public static Bill withPurchase(Card card, List<CardLine> lines, LocalDate today, long purchaseMinor) {
        List<CardLine> plus = new java.util.ArrayList<>(lines);
        plus.add(new CardLine(today.isAfter(card.asOf()) ? today : card.asOf().plusDays(1), purchaseMinor));
        return forecast(card, plus, today);
    }
}
