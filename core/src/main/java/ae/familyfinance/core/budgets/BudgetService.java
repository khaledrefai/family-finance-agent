package ae.familyfinance.core.budgets;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetEngine.Budget;
import ae.familyfinance.core.budgets.BudgetEngine.Month;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The family's monthly budgets against what the parents' payments, receipts and statements say was spent.
 * The kids' own spending comes out of their pocket money, so it is not counted here.
 */
@Service
public class BudgetService {

    static final int HISTORY_MONTHS = 3;

    private final JdbcClient db;
    private final Clock clock;

    public BudgetService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Month month(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family's budgets");
        }
        return month(me.familyId());
    }

    @Transactional(readOnly = true)
    public Month month(UUID family) {
        return month(family, YearMonth.now(clock));
    }

    /** One month: this month up to today, or an earlier month as it ended (for the monthly summary). */
    @Transactional(readOnly = true)
    public Month month(UUID family, YearMonth month) {
        LocalDate now = LocalDate.now(clock);
        LocalDate today = month.equals(YearMonth.from(now)) ? now : month.atEndOfMonth();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate historyStart = monthStart.minusMonths(HISTORY_MONTHS);
        String currency = db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();

        // category -> month index (0 = this month, 1..3 = the months before) -> spent
        Map<String, long[]> spent = new HashMap<>();
        db.sql("""
                SELECT t.category, t.occurred_on, t.amount_minor
                FROM ledger_transaction t LEFT JOIN member m ON m.id = t.member_id
                WHERE t.family_id = ? AND t.direction = 'DEBIT' AND t.duplicate_of_id IS NULL
                  AND t.transfer_to_account_id IS NULL AND (m.id IS NULL OR m.role = 'PARENT')
                  AND t.occurred_on BETWEEN ? AND ?
                """).params(family, historyStart, today)
                .query((rs, n) -> {
                    LocalDate on = rs.getObject(2, LocalDate.class);
                    int idx = on.isBefore(monthStart)
                            ? (int) java.time.temporal.ChronoUnit.MONTHS.between(on.withDayOfMonth(1), monthStart) : 0;
                    spent.computeIfAbsent(rs.getString(1), k -> new long[HISTORY_MONTHS + 1])[idx] += rs.getLong(3);
                    return null;
                }).list();
        List<Budget> budgets = new ArrayList<>();
        db.sql("""
                SELECT category, limit_minor FROM budget
                WHERE family_id = ? AND member_id IS NULL AND period = 'MONTHLY'
                ORDER BY limit_minor DESC, category
                """).param(family)
                .query((rs, n) -> {
                    String category = rs.getString(1);
                    long[] s = spent.getOrDefault(category, new long[HISTORY_MONTHS + 1]);
                    List<Long> last = new ArrayList<>();
                    // A family with no spending before this month has no "usual" yet (all zeros)
                    for (int i = 1; i <= HISTORY_MONTHS; i++) {
                        last.add(s[i]);
                    }
                    budgets.add(new Budget(category, label(category), rs.getLong(2), s[0], last));
                    return null;
                }).list();
        return BudgetEngine.month(budgets, today, currency);
    }

    /** One payment in a budget, newest first; {@code items} are the receipt's lines when a receipt is attached. */
    public record Spend(UUID id, LocalDate on, String shop, String description, MoneyView amount, String who,
                        String source, String paidFrom, List<Item> items) {
    }

    public record Item(String description, MoneyView amount, boolean credit) {
    }

    /** Every payment counted in one budget's bar for one month. {@code spent} is the same number as the bar. */
    public record Spending(String category, String label, String month, String monthLabel, boolean running,
                           MoneyView limit, MoneyView spent, MoneyView left, MoneyView over, int count,
                           List<Spend> spends) {
    }

    /** The months a parent can look back on: this one and the {@value #HISTORY_MONTHS} before it. */
    public YearMonth month(String text) {
        YearMonth now = YearMonth.now(clock);
        if (text == null || text.isBlank()) {
            return now;
        }
        YearMonth m;
        try {
            m = YearMonth.parse(text.strip());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Give the month as 2026-10");
        }
        if (m.isAfter(now) || m.isBefore(now.minusMonths(HISTORY_MONTHS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Budgets go back " + HISTORY_MONTHS + " months");
        }
        return m;
    }

    /**
     * What makes up one budget's bar: the same payments {@link #month(UUID, YearMonth)} adds up (the parents', not
     * duplicates, not card payments), each with its date, shop, who, where it came from and its receipt lines.
     */
    @Transactional(readOnly = true)
    public Spending spending(CurrentMember me, String categoryText, YearMonth month) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family's budgets");
        }
        UUID family = me.familyId();
        String category = categoryText.toUpperCase(java.util.Locale.ROOT);
        LocalDate now = LocalDate.now(clock);
        boolean running = month.equals(YearMonth.from(now));
        LocalDate to = running ? now : month.atEndOfMonth();
        LocalDate from = month.atDay(1);
        String currency = db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
        Long limitMinor = db.sql("""
                SELECT limit_minor FROM budget
                WHERE family_id = ? AND member_id IS NULL AND period = 'MONTHLY' AND category = ?
                """).params(family, category).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "There is no budget for that"));

        record Row(UUID id, LocalDate on, String shop, String description, long minor, String who, String source,
                   String docKind, String account, String last4, UUID receipt) {
        }
        // A receipt is the payment's own document, or one confirmed against a payment already in the spending
        List<Row> rows = db.sql("""
                SELECT t.id, t.occurred_on, t.merchant, t.description, t.amount_minor, m.display_name, t.source, d.kind,
                       a.label, a.last4,
                       coalesce(CASE WHEN d.kind IN ('RECEIPT', 'INVOICE') THEN d.id END,
                                (SELECT r.id FROM source_document r
                                 WHERE r.matched_tx_id = t.id AND r.family_id = t.family_id
                                   AND r.status = 'CONFIRMED' AND r.kind IN ('RECEIPT', 'INVOICE')
                                 ORDER BY r.confirmed_at DESC LIMIT 1))
                FROM ledger_transaction t
                LEFT JOIN member m ON m.id = t.member_id
                LEFT JOIN source_document d ON d.id = t.source_document_id
                LEFT JOIN money_account a ON a.id = t.paid_from_account_id
                WHERE t.family_id = ? AND t.category = ? AND t.direction = 'DEBIT' AND t.duplicate_of_id IS NULL
                  AND t.transfer_to_account_id IS NULL AND (m.id IS NULL OR m.role = 'PARENT')
                  AND t.occurred_on BETWEEN ? AND ?
                ORDER BY t.occurred_on DESC, t.created_at DESC
                """).params(family, category, from, to)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, UUID.class)))
                .list();

        Money spent = Money.zero(currency);
        List<Spend> spends = new ArrayList<>();
        for (Row r : rows) {
            Money amount = new Money(r.minor(), currency);
            spent = spent.plus(amount);
            List<Item> items = r.receipt() == null ? List.of() : db.sql("""
                    SELECT description, amount_minor, direction FROM document_line
                    WHERE document_id = ? AND status = 'ITEM' ORDER BY line_no
                    """).param(r.receipt())
                    .query((rs, n) -> new Item(rs.getString(1), MoneyView.of(new Money(rs.getLong(2), currency)),
                            "CREDIT".equals(rs.getString(3))))
                    .list();
            String paidFrom = r.account() == null ? null : r.account() + (r.last4() == null ? "" : " ···" + r.last4());
            spends.add(new Spend(r.id(), r.on(), r.shop(), r.description(), MoneyView.of(amount), r.who(),
                    source(r.source(), r.docKind(), r.receipt() != null), paidFrom, items));
        }
        Money limit = new Money(limitMinor, currency);
        String monthLabel = month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
        return new Spending(category, label(category), month.toString(), monthLabel, running, MoneyView.of(limit),
                MoneyView.of(spent), MoneyView.of(limit.minus(spent).max(Money.zero(currency))),
                MoneyView.of(spent.minus(limit).max(Money.zero(currency))), spends.size(), List.copyOf(spends));
    }

    /** Where a payment came from, in the parent's words. */
    static String source(String source, String docKind, boolean receipt) {
        boolean statement = "DOCUMENT".equals(source) && (docKind == null
                || docKind.equals("BANK_STATEMENT") || docKind.equals("CARD_STATEMENT"));
        String from = switch (source) {
            case "MANUAL" -> "Typed in";
            case "CHAT_LOG" -> "Logged in chat";
            case "SEED" -> "Demo sample";
            default -> statement ? "Statement" : "Receipt";
        };
        return receipt && !from.equals("Receipt") ? from + ", receipt added" : from;
    }

    static String label(String category) {
        return switch (category) {
            case "GROCERIES" -> "Groceries";
            case "EATING_OUT" -> "Eating out";
            case "ENTERTAINMENT" -> "Entertainment";
            case "TRANSPORT" -> "Transport";
            case "SHOPPING" -> "Shopping";
            case "HOME" -> "Home";
            case "HEALTH" -> "Health";
            default -> category.charAt(0) + category.substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }
}
