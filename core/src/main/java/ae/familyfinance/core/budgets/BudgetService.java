package ae.familyfinance.core.budgets;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetEngine.Budget;
import ae.familyfinance.core.budgets.BudgetEngine.Month;
import ae.familyfinance.core.common.ApiException;
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
