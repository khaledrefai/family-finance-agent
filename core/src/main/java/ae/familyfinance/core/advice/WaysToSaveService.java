package ae.familyfinance.core.advice;

import ae.familyfinance.core.advice.WaysToSaveEngine.Facts;
import ae.familyfinance.core.advice.WaysToSaveEngine.Outings;
import ae.familyfinance.core.advice.WaysToSaveEngine.Recurring;
import ae.familyfinance.core.advice.WaysToSaveEngine.Rent;
import ae.familyfinance.core.advice.WaysToSaveEngine.Row;
import ae.familyfinance.core.advice.WaysToSaveEngine.Tip;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.budgets.BudgetService;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.documents.FindingService;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.outings.OutingEngine;
import ae.familyfinance.core.outings.OutingsService;
import ae.familyfinance.core.plan.CashflowEngine.CommitmentFact;
import ae.familyfinance.core.plan.CashflowEngine.Dated;
import ae.familyfinance.core.plan.CommitmentKind;
import ae.familyfinance.core.plan.MoneyFacts;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import ae.familyfinance.core.plan.Schedules;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The monthly "Ways to save" card: the engine's tips ({@link WaysToSaveEngine}), minus the ones a parent dismissed or
 * snoozed, in the words the agent wrote for this month if it did (numbers checked again here), else the engine's.
 */
@Service
public class WaysToSaveService {

    public enum Decision { DISMISS, SNOOZE }

    /** {@code writtenBy}: LLM (Claude), RULES (the agent's template) or ENGINE (not reworded yet). */
    public record TipView(String key, String title, String text, String writtenBy, List<String> facts, List<Row> why) {
    }

    /** {@code needsWords}: the agent has not written this month's words yet. */
    public record Card(LocalDate month, String monthLabel, List<TipView> tips, int hidden, boolean needsWords) {
    }

    public record Words(String key, String text, String writtenBy) {
    }

    private final MoneyFacts moneyFacts;
    private final BudgetService budgets;
    private final FindingService findings;
    private final OutingsService outings;
    private final JdbcClient db;

    public WaysToSaveService(MoneyFacts moneyFacts, BudgetService budgets, FindingService findings, OutingsService outings,
                             JdbcClient db) {
        this.moneyFacts = moneyFacts;
        this.budgets = budgets;
        this.findings = findings;
        this.outings = outings;
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Card card(CurrentMember me) {
        requireParent(me);
        return card(me.familyId());
    }

    /** Stores the agent's words for this month's tips. Words with a number not in the tip's facts are dropped. */
    @Transactional
    public Card saveWords(CurrentMember me, List<Words> words, UUID agentRunId) {
        requireParent(me);
        Card now = card(me.familyId());
        Map<String, TipView> byKey = new HashMap<>();
        now.tips().forEach(t -> byKey.put(t.key(), t));
        for (Words w : words) {
            TipView tip = byKey.get(w.key());
            String text = w.text() == null ? "" : w.text().strip();
            if (tip == null || text.isEmpty() || text.length() > 400) {
                continue;
            }
            List<String> allowed = new ArrayList<>(tip.facts());
            allowed.add(tip.title());
            if (!NumberCheck.ok(text, allowed)) {
                continue; // the engine's own sentence stays
            }
            db.sql("""
                    INSERT INTO saving_tip_words (family_id, month, tip_key, text, written_by, agent_run_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (family_id, month, tip_key)
                    DO UPDATE SET text = EXCLUDED.text, written_by = EXCLUDED.written_by,
                                  agent_run_id = EXCLUDED.agent_run_id, created_at = now()
                    """).params(me.familyId(), now.month(), w.key(), text, "LLM".equals(w.writtenBy()) ? "LLM" : "RULES",
                            agentRunId).update();
        }
        return card(me.familyId());
    }

    /** Dismiss: never show this tip again. Snooze: hide it until next month. */
    @Transactional
    public Card decide(CurrentMember me, String key, Decision decision) {
        requireParent(me);
        Card now = card(me.familyId());
        if (now.tips().stream().noneMatch(t -> t.key().equals(key))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That tip is not on this month's card");
        }
        db.sql("""
                INSERT INTO saving_tip_decision (family_id, tip_key, decision, until_month, decided_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (family_id, tip_key)
                DO UPDATE SET decision = EXCLUDED.decision, until_month = EXCLUDED.until_month,
                              decided_by = EXCLUDED.decided_by, decided_at = now()
                """).params(me.familyId(), key, decision == Decision.DISMISS ? "DISMISSED" : "SNOOZED",
                        decision == Decision.SNOOZE ? now.month().plusMonths(1) : null, me.memberId()).update();
        return card(me.familyId());
    }

    // ------------------------------------------------------------------ building the card

    Card card(UUID family) {
        Snapshot s = moneyFacts.load(family);
        LocalDate month = s.today().withDayOfMonth(1);
        List<Tip> all = WaysToSaveEngine.tips(facts(family, s));

        Map<String, LocalDate> hiddenUntil = new HashMap<>();
        db.sql("SELECT tip_key, decision, until_month FROM saving_tip_decision WHERE family_id = ?").param(family)
                .query((rs, n) -> {
                    hiddenUntil.put(rs.getString(1), "DISMISSED".equals(rs.getString(2)) ? LocalDate.MAX
                            : rs.getObject(3, LocalDate.class));
                    return null;
                }).list();
        Map<String, String[]> words = new HashMap<>();
        db.sql("SELECT tip_key, text, written_by FROM saving_tip_words WHERE family_id = ? AND month = ?")
                .params(family, month)
                .query((rs, n) -> words.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)})).list();

        List<TipView> shown = new ArrayList<>();
        int hidden = 0;
        for (Tip t : all) {
            LocalDate until = hiddenUntil.get(t.key());
            if (until != null && until.isAfter(month)) {
                hidden++;
                continue;
            }
            if (shown.size() == WaysToSaveEngine.MAX_TIPS) {
                break;
            }
            String[] w = words.get(t.key());
            shown.add(new TipView(t.key(), t.title(), w == null ? t.text() : w[0], w == null ? "ENGINE" : w[1],
                    t.facts(), t.why()));
        }
        boolean needsWords = shown.stream().anyMatch(t -> "ENGINE".equals(t.writtenBy()));
        return new Card(month, month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), List.copyOf(shown),
                hidden, needsWords);
    }

    private Facts facts(UUID family, Snapshot s) {
        LocalDate today = s.today();
        LocalDate yearEnd = today.plusDays(364);
        List<Recurring> subs = new ArrayList<>();
        List<Recurring> telecom = new ArrayList<>();
        Rent rent = null;
        long rentYearly = 0;
        LocalDate contract = null;
        String rentName = null;
        for (CommitmentFact c : s.commitments()) {
            long yearly = yearly(c, today, yearEnd);
            if (c.kind() == CommitmentKind.SUBSCRIPTION) {
                subs.add(new Recurring(c.name(), monthly(c, yearly, s.currency())));
            } else if (c.kind() == CommitmentKind.TELECOM) {
                telecom.add(new Recurring(c.name(), monthly(c, yearly, s.currency())));
            } else if (c.kind() == CommitmentKind.RENT) {
                rentYearly = Math.addExact(rentYearly, yearly);
                rentName = rentName == null ? c.name() : rentName;
                for (Dated d : c.dates()) {
                    if (contract == null && d.label() != null && d.label().toLowerCase(Locale.ROOT).contains("contract")
                        && !d.on().isBefore(today)) {
                        contract = d.on();
                    }
                }
            }
        }
        if (rentName != null && rentYearly > 0) {
            rent = new Rent(rentName, rentYearly, contract);
        }
        // Subscriptions found in statements that nobody has put on the commitments list yet
        findings.subscriptionsFound(family).forEach(f -> subs.add(new Recurring(f.merchant(), f.amount().minor())));

        BudgetEngine.Line fun = budgets.month(family).lines().stream()
                .filter(l -> "ENTERTAINMENT".equals(l.category())).findFirst().orElse(null);
        Outings outings = null;
        if (fun != null) {
            // The outings planner's own plan for next month: what the tip can point to (week 7)
            OutingEngine.Plan next = this.outings.suggestNextMonth(family);
            outings = new Outings(fun.limit().minor(), fun.usual() == null ? null : fun.usual().minor(),
                    next.picks().isEmpty() ? null
                            : new WaysToSaveEngine.CheaperOutings(next.monthLabel(), next.picks().size(), next.total().minor()));
        }
        long income = s.engine().summary().income().total().minor();
        return new Facts(today, s.currency(), income, subs, rent, telecom, outings);
    }

    /** What it costs over the next 12 months: its listed dates, or its schedule. */
    private static long yearly(CommitmentFact c, LocalDate from, LocalDate to) {
        if (!c.dates().isEmpty()) {
            return c.dates().stream().filter(d -> !d.on().isBefore(from) && !d.on().isAfter(to))
                    .mapToLong(Dated::minor).reduce(0L, Math::addExact);
        }
        return Math.multiplyExact(c.amountMinor(),
                Schedules.dates(c.nextDueOn(), c.frequency(), c.remainingCount(), from, to).size());
    }

    private static long monthly(CommitmentFact c, long yearly, String currency) {
        return c.frequency() == ae.familyfinance.core.plan.Frequency.MONTHLY ? c.amountMinor()
                : new Money(yearly, currency).scaled(1, 12).minor();
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see ways to save");
        }
    }
}
