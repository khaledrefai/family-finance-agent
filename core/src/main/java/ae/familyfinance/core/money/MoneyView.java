package ae.familyfinance.core.money;

/**
 * How money goes out over the API: the exact amount in minor units plus a ready-made text.
 * The agents copy {@code text} into their answers instead of formatting (or calculating) anything.
 */
public record MoneyView(long minor, String currency, String text) {

    public static MoneyView of(Money money) {
        return new MoneyView(money.minor(), money.currency(), money.toDisplay());
    }
}
