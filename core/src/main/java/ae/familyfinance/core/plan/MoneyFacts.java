package ae.familyfinance.core.plan;

import ae.familyfinance.core.accounts.CardBillEngine;
import ae.familyfinance.core.accounts.CardBillEngine.Bill;
import ae.familyfinance.core.accounts.CardBillEngine.Card;
import ae.familyfinance.core.accounts.CardBillEngine.CardLine;
import ae.familyfinance.core.plan.CashflowEngine.BudgetFact;
import ae.familyfinance.core.plan.CashflowEngine.CardDue;
import ae.familyfinance.core.plan.CashflowEngine.CommitmentFact;
import ae.familyfinance.core.plan.CashflowEngine.Dated;
import ae.familyfinance.core.plan.CashflowEngine.Facts;
import ae.familyfinance.core.plan.CashflowEngine.GoalFact;
import ae.familyfinance.core.plan.CashflowEngine.IncomeFact;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads everything the money engine needs for one family, in one place, and builds the engines from it.
 * Only ACTIVE records count: drafts an agent filled in are ignored until a parent confirms them.
 */
@Component
public class MoneyFacts {

    /** An account or card as the family typed it. */
    public record AccountFact(UUID id, UUID ownerId, String ownerName, String kind, String label, String last4,
                              long balanceMinor, LocalDate asOf, boolean savings, Long limitMinor,
                              Integer statementDay, Integer dueDay) {

        public boolean isCard() {
            return "CREDIT_CARD".equals(kind);
        }

        /** Money that can be used day to day: current accounts and cash, not savings and not cards. */
        public boolean isSpendable() {
            return "CASH".equals(kind) || ("BANK".equals(kind) && !savings);
        }
    }

    /** One family's money facts at one moment, plus the engines built from them. */
    public record Snapshot(UUID familyId, LocalDate today, String currency, List<IncomeFact> incomes,
                           List<CommitmentFact> commitments, List<BudgetFact> budgets, List<GoalFact> goals,
                           List<AccountFact> accounts, Map<UUID, List<CardLine>> cardLines,
                           Map<UUID, Bill> bills, CashflowEngine engine) {

        public Card card(AccountFact a) {
            return MoneyFacts.card(a, currency);
        }
    }

    private final JdbcClient db;
    private final Clock clock;

    public MoneyFacts(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Snapshot load(UUID family) {
        LocalDate today = LocalDate.now(clock);
        String currency = db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();

        List<IncomeFact> incomes = incomes(family);
        List<CommitmentFact> commitments = commitments(family);
        List<BudgetFact> budgets = budgets(family);
        List<GoalFact> goals = familyGoals(family);
        List<AccountFact> accounts = accounts(family);
        Map<UUID, List<CardLine>> cardLines = cardLines(family);

        Map<UUID, Bill> bills = new LinkedHashMap<>();
        List<CardDue> dues = new ArrayList<>();
        long spendable = 0;
        for (AccountFact a : accounts) {
            if (a.isCard()) {
                Bill b = CardBillEngine.forecast(card(a, currency), cardLines.getOrDefault(a.id(), List.of()), today);
                bills.put(a.id(), b);
                dues.add(new CardDue(a.id(), a.label(), b.dueOn(), b.bill().minor()));
            } else if (a.isSpendable()) {
                spendable = Math.addExact(spendable, a.balanceMinor());
            }
        }
        CashflowEngine engine = new CashflowEngine(new Facts(today, currency, incomes, commitments, budgets, goals,
                spendable, dues));
        return new Snapshot(family, today, currency, incomes, commitments, budgets, goals, accounts, cardLines, bills, engine);
    }

    static Card card(AccountFact a, String currency) {
        return new Card(a.id(), a.label(), a.balanceMinor(), a.asOf(), a.limitMinor(), a.statementDay(), a.dueDay(), currency);
    }

    // ------------------------------------------------------------------ queries

    private List<IncomeFact> incomes(UUID family) {
        Map<UUID, List<Dated>> receipts = new HashMap<>();
        db.sql("""
                SELECT r.income_id, r.received_on, r.amount_minor
                FROM income_receipt r JOIN income i ON i.id = r.income_id
                WHERE i.family_id = ? ORDER BY r.received_on
                """).param(family)
                .query((rs, n) -> {
                    receipts.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Dated(rs.getObject(2, LocalDate.class), rs.getLong(3), null));
                    return null;
                }).list();
        return db.sql("""
                SELECT id, name, kind, amount_minor, frequency, next_on, base_override_minor
                FROM income WHERE family_id = ? AND status = 'ACTIVE' ORDER BY amount_minor DESC, name
                """).param(family)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new IncomeFact(id, rs.getString(2), IncomeKind.valueOf(rs.getString(3)), rs.getLong(4),
                            Frequency.valueOf(rs.getString(5)), rs.getObject(6, LocalDate.class),
                            rs.getObject(7, Long.class), receipts.getOrDefault(id, List.of()));
                }).list();
    }

    private List<CommitmentFact> commitments(UUID family) {
        Map<UUID, List<Dated>> dates = new HashMap<>();
        db.sql("""
                SELECT o.commitment_id, o.due_on, o.amount_minor, o.label
                FROM commitment_occurrence o JOIN commitment c ON c.id = o.commitment_id
                WHERE c.family_id = ? ORDER BY o.due_on
                """).param(family)
                .query((rs, n) -> {
                    dates.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Dated(rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4)));
                    return null;
                }).list();
        return db.sql("""
                SELECT id, name, kind, amount_minor, frequency, next_due_on, remaining_count, summer_amount_minor,
                       reserved_minor, is_estimate
                FROM commitment WHERE family_id = ? AND status = 'ACTIVE' ORDER BY next_due_on, name
                """).param(family)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new CommitmentFact(id, rs.getString(2), CommitmentKind.valueOf(rs.getString(3)), rs.getLong(4),
                            Frequency.valueOf(rs.getString(5)), rs.getObject(6, LocalDate.class),
                            rs.getObject(7, Integer.class), rs.getObject(8, Long.class), rs.getLong(9),
                            rs.getBoolean(10), dates.getOrDefault(id, List.of()));
                }).list();
    }

    private List<BudgetFact> budgets(UUID family) {
        return db.sql("""
                SELECT b.category, b.period, b.limit_minor, m.display_name
                FROM budget b LEFT JOIN member m ON m.id = b.member_id
                WHERE b.family_id = ? ORDER BY b.member_id NULLS FIRST, b.limit_minor DESC
                """).param(family)
                .query((rs, n) -> new BudgetFact(budgetLabel(rs.getString(1), rs.getString(4)),
                        "WEEKLY".equals(rs.getString(2)), rs.getLong(3)))
                .list();
    }

    private List<GoalFact> familyGoals(UUID family) {
        return db.sql("""
                SELECT id, name, tier, target_minor, saved_minor, monthly_minor, target_date, priority
                FROM goal WHERE family_id = ? AND member_id IS NULL AND status = 'ACTIVE'
                ORDER BY priority, created_at
                """).param(family)
                .query((rs, n) -> new GoalFact(rs.getObject(1, UUID.class), rs.getString(2),
                        Tier.valueOf(rs.getString(3)), rs.getLong(4), rs.getLong(5), rs.getObject(6, Long.class),
                        rs.getObject(7, LocalDate.class), rs.getInt(8)))
                .list();
    }

    private List<AccountFact> accounts(UUID family) {
        return db.sql("""
                SELECT a.id, a.owner_member_id, m.display_name, a.kind, a.label, a.last4, a.balance_minor,
                       a.balance_as_of, a.is_savings, a.limit_minor, a.statement_day, a.due_day
                FROM money_account a LEFT JOIN member m ON m.id = a.owner_member_id
                WHERE a.family_id = ? AND a.status = 'ACTIVE'
                ORDER BY CASE a.kind WHEN 'BANK' THEN 0 WHEN 'CREDIT_CARD' THEN 1 ELSE 2 END, a.created_at, a.label
                """).param(family)
                .query((rs, n) -> new AccountFact(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7),
                        rs.getObject(8, LocalDate.class), rs.getBoolean(9), rs.getObject(10, Long.class),
                        rs.getObject(11, Integer.class), rs.getObject(12, Integer.class)))
                .list();
    }

    /** Purchases on a card (+), and refunds or payments into it (−). */
    private Map<UUID, List<CardLine>> cardLines(UUID family) {
        Map<UUID, List<CardLine>> out = new HashMap<>();
        db.sql("""
                SELECT t.paid_from_account_id, t.transfer_to_account_id, t.direction, t.occurred_on, t.amount_minor
                FROM ledger_transaction t
                WHERE t.family_id = ? AND t.duplicate_of_id IS NULL
                  AND (t.paid_from_account_id IN (SELECT id FROM money_account WHERE family_id = ? AND kind = 'CREDIT_CARD')
                    OR t.transfer_to_account_id IN (SELECT id FROM money_account WHERE family_id = ? AND kind = 'CREDIT_CARD'))
                """).params(family, family, family)
                .query((rs, n) -> {
                    UUID from = rs.getObject(1, UUID.class);
                    UUID to = rs.getObject(2, UUID.class);
                    boolean debit = "DEBIT".equals(rs.getString(3));
                    LocalDate on = rs.getObject(4, LocalDate.class);
                    long amount = rs.getLong(5);
                    if (to != null) {
                        out.computeIfAbsent(to, k -> new ArrayList<>()).add(new CardLine(on, -amount));
                    }
                    if (from != null && to == null) {
                        out.computeIfAbsent(from, k -> new ArrayList<>()).add(new CardLine(on, debit ? amount : -amount));
                    }
                    return null;
                }).list();
        return out;
    }

    static String budgetLabel(String category, String memberName) {
        String label = switch (category) {
            case "GROCERIES" -> "Groceries";
            case "EATING_OUT" -> "Eating out";
            case "ENTERTAINMENT" -> "Entertainment";
            case "TRANSPORT" -> "Transport";
            case "POCKET_MONEY" -> "Pocket money";
            case "SHOPPING" -> "Shopping";
            case "HOME" -> "Home";
            case "HEALTH" -> "Health";
            default -> category.charAt(0) + category.substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
        return memberName == null ? label : label + ", " + memberName;
    }
}
