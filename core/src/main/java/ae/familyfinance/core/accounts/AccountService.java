package ae.familyfinance.core.accounts;

import ae.familyfinance.core.accounts.CardBillEngine.Bill;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Amounts;
import ae.familyfinance.core.plan.Cashflow.Line;
import ae.familyfinance.core.plan.CashflowEngine.Projection;
import ae.familyfinance.core.plan.MoneyFacts;
import ae.familyfinance.core.plan.MoneyFacts.AccountFact;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bank accounts, credit cards and cash, typed in by a parent (no bank links), and payments tagged with what paid them.
 * For each card the engine forecasts the next bill and compares it with the money free on its due date.
 */
@Service
public class AccountService {

    /** Family spending categories for payments a parent types in (kids' categories are in SpendCategory). */
    public static final List<String> CATEGORIES = List.of("GROCERIES", "EATING_OUT", "ENTERTAINMENT", "TRANSPORT",
            "SHOPPING", "HOTEL", "HEALTH", "HOME", "OTHER");

    public record NewAccount(String kind, String label, String last4, UUID ownerId, String balance, LocalDate asOf,
                             boolean savings, String limit, Integer statementDay, Integer dueDay) {
    }

    public record NewPayment(String amount, String description, String merchant, String category, UUID paidFromAccountId,
                             LocalDate occurredOn, UUID transferToAccountId) {
    }

    /** A card's next bill against what is free to pay it. {@code ok} = the bill fits. */
    public record CardBill(LocalDate statementOn, LocalDate dueOn, MoneyView bill, MoneyView freeByDue, boolean ok,
                           MoneyView shortBy, List<Line> why) {
    }

    public record AccountView(UUID id, String kind, String label, String last4, UUID ownerId, String ownerName,
                              MoneyView balance, LocalDate asOf, boolean estimated, boolean savings, MoneyView limit,
                              int usedPercent, Integer statementDay, Integer dueDay, CardBill bill) {
    }

    public record PaymentView(UUID id, LocalDate occurredOn, String description, String merchant, String category,
                              MoneyView amount, UUID paidFromAccountId, String paidFrom, UUID transferToAccountId,
                              CardBill cardBill) {
    }

    /** The card check before a purchase: the bill with the purchase, against what is free on the due date. */
    public record CardCheck(UUID accountId, String card, LocalDate dueOn, MoneyView billNow, MoneyView billWith,
                            MoneyView freeByDue, boolean fits, MoneyView shortBy, String line, List<Line> why) {
    }

    private final MoneyFacts facts;
    private final JdbcClient db;
    private final Clock clock;

    public AccountService(MoneyFacts facts, JdbcClient db, Clock clock) {
        this.facts = facts;
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AccountView> list(CurrentMember me) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        return s.accounts().stream().map(a -> view(s, a)).toList();
    }

    @Transactional
    public AccountView create(CurrentMember me, NewAccount in) {
        requireParent(me);
        Snapshot before = facts.load(me.familyId());
        String cur = before.currency();
        String kind = in.kind() == null ? "" : in.kind().toUpperCase(Locale.ROOT);
        if (!List.of("BANK", "CREDIT_CARD", "CASH").contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Is it a bank account, a credit card or cash?");
        }
        boolean card = kind.equals("CREDIT_CARD");
        if (in.last4() != null && !in.last4().matches("[0-9]{4}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only the last 4 digits, please");
        }
        Money balance = in.balance() == null || in.balance().isBlank() || in.balance().trim().equals("0")
                ? Money.zero(cur) : Amounts.parse(in.balance(), cur, card ? "amount owed" : "balance");
        Money limit = card ? Amounts.parse(in.limit(), cur, "card limit") : null;
        if (card && (in.statementDay() == null || in.dueDay() == null
                || in.statementDay() < 1 || in.statementDay() > 31 || in.dueDay() < 1 || in.dueDay() > 31)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A card needs its statement day and due day (1 to 31)");
        }
        if (in.ownerId() != null) {
            db.sql("SELECT id FROM member WHERE id = ? AND family_id = ? AND role = 'PARENT'")
                    .params(in.ownerId(), me.familyId()).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Parent not found"));
        }
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO money_account (id, family_id, owner_member_id, kind, label, last4, balance_minor, balance_as_of,
                                           is_savings, limit_minor, statement_day, due_day, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), in.ownerId(), kind, label(in.label(), kind), in.last4(), balance.minor(),
                        in.asOf() == null ? LocalDate.now(clock) : in.asOf(), kind.equals("BANK") && in.savings(),
                        limit == null ? null : limit.minor(), card ? in.statementDay() : null, card ? in.dueDay() : null, cur)
                .update();
        Snapshot s = facts.load(me.familyId());
        return view(s, account(s, id));
    }

    /** A parent types today's balance (or what is owed on a card). */
    @Transactional
    public AccountView updateBalance(CurrentMember me, UUID id, String balanceText, LocalDate asOf) {
        requireParent(me);
        Snapshot before = facts.load(me.familyId());
        AccountFact a = account(before, id);
        Money balance = balanceText != null && balanceText.trim().matches("0+(\\.0+)?") ? Money.zero(before.currency())
                : Amounts.parse(balanceText, before.currency(), a.isCard() ? "amount owed" : "balance");
        db.sql("UPDATE money_account SET balance_minor = ?, balance_as_of = ? WHERE id = ? AND family_id = ?")
                .params(balance.minor(), asOf == null ? LocalDate.now(clock) : asOf, id, me.familyId()).update();
        Snapshot s = facts.load(me.familyId());
        return view(s, account(s, id));
    }

    /**
     * A parent types a payment by hand (restaurant, hotel, transport, shopping). It must say what paid it.
     * Paying a card bill is a transfer into the card: it is never counted as spending.
     */
    @Transactional
    public PaymentView logPayment(CurrentMember me, NewPayment in) {
        requireParent(me);
        Snapshot before = facts.load(me.familyId());
        String cur = before.currency();
        if (in.paidFromAccountId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "What paid for it? Pick a card, an account or cash");
        }
        AccountFact from = account(before, in.paidFromAccountId());
        AccountFact to = in.transferToAccountId() == null ? null : account(before, in.transferToAccountId());
        if (to != null && (!to.isCard() || from.isCard())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A transfer pays a card from a bank account or cash");
        }
        Money amount = Amounts.parse(in.amount(), cur, "amount");
        LocalDate today = LocalDate.now(clock);
        LocalDate date = in.occurredOn() == null ? today : in.occurredOn();
        if (date.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That date is in the future");
        }
        String category = to != null ? "CARD_PAYMENT"
                : in.category() == null ? "OTHER" : in.category().toUpperCase(Locale.ROOT);
        if (to == null && !CATEGORIES.contains(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick one of: " + String.join(", ", CATEGORIES));
        }
        String description = in.description() == null || in.description().isBlank()
                ? (to != null ? "Card payment, " + to.label() : "Payment") : in.description().trim();
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO ledger_transaction (id, family_id, member_id, occurred_on, description, merchant, category,
                                                direction, amount_minor, currency, duplicate_hash, source,
                                                paid_from_account_id, transfer_to_account_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DEBIT', ?, ?, ?, 'MANUAL', ?, ?)
                """).params(id, me.familyId(), me.memberId(), date, cut(description, 255), cut(in.merchant(), 160),
                        category, amount.minor(), cur, hash(me.memberId(), date, amount, description), from.id(),
                        to == null ? null : to.id())
                .update();
        Snapshot s = facts.load(me.familyId());
        AccountFact card = to != null ? to : from.isCard() ? from : null;
        return new PaymentView(id, date, description, in.merchant(), category, MoneyView.of(amount), from.id(),
                from.label(), to == null ? null : to.id(), card == null ? null : bill(s, card));
    }

    @Transactional(readOnly = true)
    public CardCheck cardCheck(CurrentMember me, UUID accountId, String amountText) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        Money amount = Amounts.parse(amountText, s.currency(), "amount");
        CardCheck check = cardCheck(s, accountId, amount.minor());
        if (check == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That is not a credit card");
        }
        return check;
    }

    /** The card check for a purchase of {@code amountMinor} today on {@code accountId}; null if it is not a card. */
    public CardCheck cardCheck(Snapshot s, UUID accountId, long amountMinor) {
        AccountFact a = account(s, accountId);
        if (!a.isCard()) {
            return null;
        }
        Bill now = s.bills().get(a.id());
        Bill with = CardBillEngine.withPurchase(s.card(a), s.cardLines().getOrDefault(a.id(), List.of()), s.today(), amountMinor);
        Projection free = s.engine().freeOn(with.dueOn(), a.id());
        boolean fits = with.bill().compareTo(free.free()) <= 0;
        Money shortBy = with.bill().minus(free.free()).max(Money.zero(s.currency()));
        String line = fits
                ? "This card's bill on " + day(with.dueOn()) + " would be " + MoneyView.of(with.bill()).text() + ", and "
                  + MoneyView.of(free.free()).text() + " is free then."
                : "This card's bill on " + day(with.dueOn()) + " would be " + MoneyView.of(with.bill()).text()
                  + ", and only " + MoneyView.of(free.free().max(Money.zero(s.currency()))).text() + " is free.";
        return new CardCheck(a.id(), a.label(), with.dueOn(), MoneyView.of(now.bill()), MoneyView.of(with.bill()),
                MoneyView.of(free.free()), fits, MoneyView.of(shortBy), line, free.why());
    }

    /** A card and its next bill, for the core's own "card bill due" alerts. */
    public record DueCard(UUID accountId, String label, CardBill bill) {
    }

    public List<DueCard> dueCards(Snapshot s) {
        return s.accounts().stream().filter(AccountFact::isCard).map(a -> new DueCard(a.id(), a.label(), bill(s, a))).toList();
    }

    public static AccountFact account(Snapshot s, UUID id) {
        return s.accounts().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private AccountView view(Snapshot s, AccountFact a) {
        Bill b = s.bills().get(a.id());
        Money balance = new Money(a.balanceMinor(), s.currency());
        int used = 0;
        if (a.isCard()) {
            balance = b.owedNow();
            used = b.usedPercent();
        }
        return new AccountView(a.id(), a.kind(), a.label(), a.last4(), a.ownerId(), a.ownerName(), MoneyView.of(balance),
                a.asOf(), true, a.savings(), a.limitMinor() == null ? null : MoneyView.of(new Money(a.limitMinor(), s.currency())),
                used, a.statementDay(), a.dueDay(), a.isCard() ? bill(s, a) : null);
    }

    private CardBill bill(Snapshot s, AccountFact card) {
        Bill b = s.bills().get(card.id());
        Projection free = s.engine().freeOn(b.dueOn(), card.id());
        boolean ok = b.bill().compareTo(free.free()) <= 0;
        return new CardBill(b.statementOn(), b.dueOn(), MoneyView.of(b.bill()), MoneyView.of(free.free()), ok,
                MoneyView.of(b.bill().minus(free.free()).max(Money.zero(s.currency()))), free.why());
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family's accounts");
        }
    }

    private static String day(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String label(String label, String kind) {
        if (label != null && !label.isBlank()) {
            return cut(label.trim(), 80);
        }
        return switch (kind) {
            case "CREDIT_CARD" -> "Credit card";
            case "CASH" -> "Cash at home";
            default -> "Bank account";
        };
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Same person, same day, same amount, same words = probably typed twice (week 3 flags these). */
    private static String hash(UUID member, LocalDate date, Money amount, String description) {
        String key = "payment|" + member + "|" + date + "|" + amount.minor() + "|"
                + description.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
