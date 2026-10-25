package ae.familyfinance.core.plan;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.BucketEngine.Buckets;
import ae.familyfinance.core.plan.BucketEngine.Needs;
import ae.familyfinance.core.plan.BucketEngine.Share;
import ae.familyfinance.core.plan.CashflowEngine.Dated;
import ae.familyfinance.core.plan.CashflowEngine.GoalFact;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import ae.familyfinance.core.plan.PlanOptionsEngine.PlanOptions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The family plan: income sources, commitments, the cash-flow calendar, plan options for a goal, and money buckets.
 * Parents only: children never see the parents' figures.
 *
 * <p>Anything created through an agent run (the request carries {@code X-Agent-Run-Id}) is saved as a DRAFT,
 * whatever the request says, and counts only once a parent confirms it. Agents never write a live record.
 */
@Service
public class PlanService {

    public enum DraftType { INCOME, COMMITMENT }

    // ------------------------------------------------------------------ inputs (amounts are text, see Amounts)

    public record NewIncome(UUID memberId, String name, IncomeKind kind, String amount, Frequency frequency,
                            LocalDate nextOn, String baseOverride, List<NewReceipt> receipts, boolean draft) {
    }

    public record NewReceipt(LocalDate receivedOn, String amount) {
    }

    public record NewCommitment(String name, CommitmentKind kind, String amount, Frequency frequency, LocalDate nextDueOn,
                                Integer remainingCount, boolean estimate, String summerAmount, String reserved,
                                String balanceLeft, UUID paidFromAccountId, List<NewDate> dates, boolean draft) {
    }

    public record NewDate(LocalDate dueOn, String amount, String label) {
    }

    // ------------------------------------------------------------------ views

    public record IncomeView(UUID id, UUID memberId, String memberName, String name, IncomeKind kind, MoneyView amount,
                             Frequency frequency, LocalDate nextOn, MoneyView plannedMonthly, RecordStatus status,
                             String source, String note) {
    }

    public record CommitmentView(UUID id, String name, CommitmentKind kind, String kindLabel, MoneyView amount,
                                 Frequency frequency, LocalDate nextDueOn, Integer remainingCount, boolean estimate,
                                 MoneyView summerAmount, MoneyView reserved, MoneyView balanceLeft,
                                 RecordStatus status, String source, List<DateView> dates) {
    }

    public record DateView(LocalDate dueOn, MoneyView amount, String label) {
    }

    public record Drafts(List<IncomeView> incomes, List<CommitmentView> commitments) {
    }

    public record ReceiptResult(IncomeView income, MoneyView received, MoneyView base, boolean belowBase, String note) {
    }

    public record GoalView(UUID id, String name, Tier tier, String tierLabel, MoneyView target, MoneyView saved,
                           MoneyView left, int percent, MoneyView monthly, LocalDate targetDate, String fullBy) {
    }

    /** What a one-off amount would do: each goal's full month without it and with all of it put there. */
    public record OneOffEffect(MoneyView amount, List<EffectLine> goals, String note) {
    }

    public record EffectLine(UUID goalId, String goal, String fullBefore, String fullAfter, int monthsSooner) {
    }

    private final MoneyFacts facts;
    private final JdbcClient db;
    private final Clock clock;

    public PlanService(MoneyFacts facts, JdbcClient db, Clock clock) {
        this.facts = facts;
        this.db = db;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ cash flow

    @Transactional(readOnly = true)
    public Cashflow cashflow(CurrentMember me) {
        requireParent(me);
        return facts.load(me.familyId()).engine().summary();
    }

    // ------------------------------------------------------------------ income

    @Transactional(readOnly = true)
    public List<IncomeView> incomes(CurrentMember me) {
        requireParent(me);
        return incomeViews(me.familyId(), null);
    }

    @Transactional
    public IncomeView createIncome(CurrentMember me, NewIncome in, boolean fromAgent) {
        requireParent(me);
        String cur = currency(me.familyId());
        LocalDate today = LocalDate.now(clock);
        IncomeKind kind = in.kind() == null ? IncomeKind.FIXED : in.kind();
        Frequency freq = kind == IncomeKind.ONE_OFF ? Frequency.ONCE : in.frequency() == null ? Frequency.MONTHLY : in.frequency();
        if (kind != IncomeKind.ONE_OFF && !(freq == Frequency.WEEKLY || freq == Frequency.MONTHLY || freq == Frequency.YEARLY)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pay comes weekly, monthly or yearly");
        }
        if (in.nextOn() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, kind == IncomeKind.ONE_OFF
                    ? "When did the money arrive?" : "When is the next pay day?");
        }
        if (kind == IncomeKind.ONE_OFF && in.nextOn().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "One-off money only counts once it has arrived. Add it on the day it comes");
        }
        // For commission or sales this is a usual month, for show; the plan uses the cautious base from its receipts.
        Money amount = Amounts.parse(in.amount(), cur, kind == IncomeKind.ONE_OFF ? "amount" : "take-home pay");
        Money base = Amounts.parseOptional(in.baseOverride(), cur, "planning base");
        UUID member = in.memberId() == null ? null : requireMember(me, in.memberId());
        RecordStatus status = fromAgent || in.draft() ? RecordStatus.DRAFT : RecordStatus.ACTIVE;
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO income (id, family_id, member_id, name, amount_minor, currency, frequency, next_on, kind,
                                    base_override_minor, status, source, created_by, confirmed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), member, name(in.name(), "Income"),
                        amount.minor(), cur, freq.name(), in.nextOn(),
                        kind.name(), base == null ? null : base.minor(), status.name(), fromAgent ? "SETUP_CHAT" : "MANUAL",
                        me.memberId(), status == RecordStatus.ACTIVE ? me.memberId() : null)
                .update();
        if (in.receipts() != null) {
            for (NewReceipt r : in.receipts()) {
                insertReceipt(id, r, cur, today);
            }
        }
        return incomeViews(me.familyId(), id).get(0);
    }

    /** A variable income was paid: record it, and say straight away if it came in below the planning base. */
    @Transactional
    public ReceiptResult addReceipt(CurrentMember me, UUID incomeId, NewReceipt r) {
        requireParent(me);
        String cur = currency(me.familyId());
        String kind = db.sql("SELECT kind FROM income WHERE id = ? AND family_id = ?").params(incomeId, me.familyId())
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Income not found"));
        if (!IncomeKind.VARIABLE.name().equals(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only commission or sales income has monthly amounts to record");
        }
        Money received = insertReceipt(incomeId, r, cur, LocalDate.now(clock));
        IncomeView view = incomeViews(me.familyId(), incomeId).get(0);
        Money base = new Money(view.plannedMonthly().minor(), cur);
        boolean below = received.compareTo(base) < 0;
        String note = below
                ? "That is " + MoneyView.of(base.minus(received)).text() + " below the planning base of "
                  + MoneyView.of(base).text() + ". The buffer covers the gap and the plan re-runs."
                : "That is " + MoneyView.of(received.minus(base)).text() + " above the planning base. "
                  + "The extra can go to the buffer first, then the family goal.";
        return new ReceiptResult(view, MoneyView.of(received), MoneyView.of(base), below, note);
    }

    private Money insertReceipt(UUID incomeId, NewReceipt r, String cur, LocalDate today) {
        if (r.receivedOn() == null || r.receivedOn().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "When was it paid? It can't be in the future");
        }
        Money amount = r.amount() != null && r.amount().trim().matches("0+(\\.0+)?")
                ? Money.zero(cur) : Amounts.parse(r.amount(), cur, "amount paid");
        db.sql("""
                INSERT INTO income_receipt (income_id, received_on, amount_minor) VALUES (?, ?, ?)
                ON CONFLICT (income_id, received_on) DO UPDATE SET amount_minor = EXCLUDED.amount_minor
                """).params(incomeId, r.receivedOn(), amount.minor()).update();
        return amount;
    }

    private List<IncomeView> incomeViews(UUID family, UUID only) {
        Snapshot s = facts.load(family);
        return db.sql("""
                SELECT i.id, i.member_id, m.display_name, i.name, i.kind, i.amount_minor, i.frequency, i.next_on,
                       i.base_override_minor, i.status, i.source
                FROM income i LEFT JOIN member m ON m.id = i.member_id
                WHERE i.family_id = :family AND i.status <> 'ARCHIVED' AND (CAST(:only AS uuid) IS NULL OR i.id = :only)
                ORDER BY i.status, i.amount_minor DESC
                """).param("family", family).param("only", only)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    IncomeKind kind = IncomeKind.valueOf(rs.getString(5));
                    List<Dated> receipts = s.incomes().stream().filter(i -> i.id().equals(id)).findFirst()
                            .map(CashflowEngine.IncomeFact::receipts).orElseGet(() -> receiptsOf(id));
                    Long override = rs.getObject(9, Long.class);
                    CashflowEngine.IncomeFact fact = new CashflowEngine.IncomeFact(id, rs.getString(4), kind,
                            rs.getLong(6), Frequency.valueOf(rs.getString(7)), rs.getObject(8, LocalDate.class),
                            override, receipts);
                    Money plannedMonthly = s.engine().plannedMonthly(fact);
                    String note = switch (kind) {
                        case FIXED -> "Take-home pay: what lands in the account";
                        case VARIABLE -> override != null ? "Planned on your own base"
                                : "Planned on a cautious base: the average of the 3 lowest of the last 6 months";
                        case ONE_OFF -> "One-off: counted only in the month it arrived";
                    };
                    return new IncomeView(id, rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), kind,
                            MoneyView.of(new Money(rs.getLong(6), s.currency())), Frequency.valueOf(rs.getString(7)),
                            rs.getObject(8, LocalDate.class), MoneyView.of(plannedMonthly),
                            RecordStatus.valueOf(rs.getString(10)), rs.getString(11), note);
                }).list();
    }

    private List<Dated> receiptsOf(UUID income) {
        return db.sql("SELECT received_on, amount_minor FROM income_receipt WHERE income_id = ?").param(income)
                .query((rs, n) -> new Dated(rs.getObject(1, LocalDate.class), rs.getLong(2), null)).list();
    }

    // ------------------------------------------------------------------ commitments

    @Transactional(readOnly = true)
    public List<CommitmentView> commitments(CurrentMember me) {
        requireParent(me);
        return commitmentViews(me.familyId(), null);
    }

    @Transactional
    public CommitmentView createCommitment(CurrentMember me, NewCommitment in, boolean fromAgent) {
        requireParent(me);
        String cur = currency(me.familyId());
        if (in.kind() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "What kind of commitment is it? Rent, school fees, a bill, a loan...");
        }
        Frequency freq = in.frequency() == null ? Frequency.MONTHLY : in.frequency();
        List<NewDate> dates = in.dates() == null ? List.of() : in.dates();
        if (freq == Frequency.CUSTOM && dates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "List the payment dates (for example each rent cheque)");
        }
        if (freq != Frequency.CUSTOM && !dates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Payment dates are only for commitments with their own dates");
        }
        List<Money> dateAmounts = new ArrayList<>();
        for (NewDate d : dates) {
            if (d.dueOn() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Each payment needs a date");
            }
            dateAmounts.add(Amounts.parse(d.amount() == null ? in.amount() : d.amount(), cur, "payment amount"));
        }
        LocalDate next = freq == Frequency.CUSTOM
                ? dates.stream().map(NewDate::dueOn).min(LocalDate::compareTo).orElseThrow() : in.nextDueOn();
        if (next == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "When is the next payment due?");
        }
        Money amount = freq == Frequency.CUSTOM && (in.amount() == null || in.amount().isBlank())
                ? dateAmounts.get(0) : Amounts.parse(in.amount(), cur, "amount");
        if (in.remainingCount() != null && in.remainingCount() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Payments left must be 1 or more");
        }
        Money summer = Amounts.parseOptional(in.summerAmount(), cur, "summer amount");
        Money reserved = in.reserved() == null || in.reserved().isBlank() || in.reserved().trim().equals("0")
                ? Money.zero(cur) : Amounts.parse(in.reserved(), cur, "amount set aside");
        Money left = Amounts.parseOptional(in.balanceLeft(), cur, "amount still owed");
        if (in.paidFromAccountId() != null) {
            requireAccount(me, in.paidFromAccountId());
        }
        RecordStatus status = fromAgent || in.draft() ? RecordStatus.DRAFT : RecordStatus.ACTIVE;
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO commitment (id, family_id, name, kind, amount_minor, currency, frequency, next_due_on,
                                        remaining_count, is_estimate, summer_amount_minor, reserved_minor,
                                        balance_left_minor, paid_from_account_id, status, source, created_by, confirmed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), name(in.name(), in.kind().label()), in.kind().name(), amount.minor(), cur,
                        freq.name(), next, in.remainingCount(), in.estimate(), summer == null ? null : summer.minor(),
                        reserved.minor(), left == null ? null : left.minor(), in.paidFromAccountId(), status.name(),
                        fromAgent ? "SETUP_CHAT" : "MANUAL", me.memberId(),
                        status == RecordStatus.ACTIVE ? me.memberId() : null)
                .update();
        for (int k = 0; k < dates.size(); k++) {
            db.sql("INSERT INTO commitment_occurrence (commitment_id, due_on, amount_minor, label) VALUES (?, ?, ?, ?)")
                    .params(id, dates.get(k).dueOn(), dateAmounts.get(k).minor(), dates.get(k).label()).update();
        }
        return commitmentViews(me.familyId(), id).get(0);
    }

    private List<CommitmentView> commitmentViews(UUID family, UUID only) {
        String cur = currency(family);
        Map<UUID, List<DateView>> dates = new java.util.HashMap<>();
        db.sql("""
                SELECT o.commitment_id, o.due_on, o.amount_minor, o.label
                FROM commitment_occurrence o JOIN commitment c ON c.id = o.commitment_id
                WHERE c.family_id = ? ORDER BY o.due_on
                """).param(family)
                .query((rs, n) -> dates.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new DateView(rs.getObject(2, LocalDate.class), view(rs.getLong(3), cur), rs.getString(4))))
                .list();
        return db.sql("""
                SELECT id, name, kind, amount_minor, frequency, next_due_on, remaining_count, is_estimate,
                       summer_amount_minor, reserved_minor, balance_left_minor, status, source
                FROM commitment
                WHERE family_id = :family AND status <> 'ARCHIVED' AND (CAST(:only AS uuid) IS NULL OR id = :only)
                ORDER BY status, next_due_on, name
                """).param("family", family).param("only", only)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    CommitmentKind kind = CommitmentKind.valueOf(rs.getString(3));
                    Long summer = rs.getObject(9, Long.class);
                    Long left = rs.getObject(11, Long.class);
                    return new CommitmentView(id, rs.getString(2), kind, kind.label(), view(rs.getLong(4), cur),
                            Frequency.valueOf(rs.getString(5)), rs.getObject(6, LocalDate.class),
                            rs.getObject(7, Integer.class), rs.getBoolean(8), summer == null ? null : view(summer, cur),
                            view(rs.getLong(10), cur), left == null ? null : view(left, cur),
                            RecordStatus.valueOf(rs.getString(12)), rs.getString(13), dates.getOrDefault(id, List.of()));
                }).list();
    }

    // ------------------------------------------------------------------ drafts

    @Transactional(readOnly = true)
    public Drafts drafts(CurrentMember me) {
        requireParent(me);
        return new Drafts(
                incomeViews(me.familyId(), null).stream().filter(i -> i.status() == RecordStatus.DRAFT).toList(),
                commitmentViews(me.familyId(), null).stream().filter(c -> c.status() == RecordStatus.DRAFT).toList());
    }

    /** A parent taps Confirm: the draft becomes a live record the engine counts. */
    @Transactional
    public void decideDraft(CurrentMember me, DraftType type, UUID id, boolean confirm) {
        requireParent(me);
        String table = type == DraftType.INCOME ? "income" : "commitment";
        int changed = db.sql("UPDATE " + table + " SET status = ?, confirmed_by = ? "
                        + "WHERE id = ? AND family_id = ? AND status = 'DRAFT'")
                .params(confirm ? "ACTIVE" : "ARCHIVED", confirm ? me.memberId() : null, id, me.familyId())
                .update();
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Draft not found, or already decided");
        }
    }

    // ------------------------------------------------------------------ goals and plan options

    @Transactional(readOnly = true)
    public List<GoalView> goals(CurrentMember me) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        return s.goals().stream().map(g -> goalView(s, g)).toList();
    }

    private GoalView goalView(Snapshot s, GoalFact g) {
        String cur = s.currency();
        Money target = new Money(g.targetMinor(), cur);
        Money saved = new Money(g.savedMinor(), cur);
        int full = s.engine().fullMonth(g);
        return new GoalView(g.id(), g.name(), g.tier(), g.tier().label(), MoneyView.of(target), MoneyView.of(saved),
                MoneyView.of(target.minus(saved).max(Money.zero(cur))), Math.min(100, saved.percentOf(target)),
                g.monthlyMinor() == null ? null : view(g.monthlyMinor(), cur), g.targetDate(),
                full < 0 ? null : monthLabel(s.engine().month(full)));
    }

    @Transactional(readOnly = true)
    public PlanOptions options(CurrentMember me, UUID goalId, LocalDate targetDate) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        GoalFact goal = goal(s, goalId);
        if (targetDate != null && !targetDate.isAfter(s.today())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The target date has to be in the future");
        }
        return PlanOptionsEngine.options(s.engine(), goal, targetDate, s.currency());
    }

    /** A parent picks a pace: the goal gets this much each month from now on. */
    @Transactional
    public GoalView setPace(CurrentMember me, UUID goalId, String monthly, LocalDate targetDate) {
        requireParent(me);
        Snapshot before = facts.load(me.familyId());
        goal(before, goalId);
        Money amount = monthly != null && monthly.trim().equals("0") ? Money.zero(before.currency())
                : Amounts.parse(monthly, before.currency(), "monthly amount");
        db.sql("UPDATE goal SET monthly_minor = ?, target_date = COALESCE(?, target_date) WHERE id = ? AND family_id = ?")
                .params(amount.minor(), targetDate, goalId, me.familyId()).update();
        Snapshot after = facts.load(me.familyId());
        return goalView(after, goal(after, goalId));
    }

    /**
     * A bonus or gift arrived: for each funded goal, when it fills up now, and when it would if all of the money
     * went to it. The agent suggests a split from this; the parent decides.
     */
    @Transactional(readOnly = true)
    public OneOffEffect oneOffEffect(CurrentMember me, String amountText) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        Money amount = Amounts.parse(amountText, s.currency(), "amount");
        List<EffectLine> lines = new ArrayList<>();
        for (GoalFact g : s.goals()) {
            if (g.monthlyMinor() == null || g.monthlyMinor() == 0 || g.savedMinor() >= g.targetMinor()) {
                continue;
            }
            int before = s.engine().fullMonth(g);
            GoalFact boosted = new GoalFact(g.id(), g.name(), g.tier(), g.targetMinor(),
                    Math.min(g.targetMinor(), Math.addExact(g.savedMinor(), amount.minor())), g.monthlyMinor(),
                    g.targetDate(), g.priority());
            List<GoalFact> goals = s.goals().stream().map(x -> x.id().equals(g.id()) ? boosted : x).toList();
            CashflowEngine other = new CashflowEngine(new CashflowEngine.Facts(s.today(), s.currency(), s.incomes(),
                    s.commitments(), s.budgets(), goals, 0, List.of()));
            int after = other.fullMonth(boosted);
            lines.add(new EffectLine(g.id(), g.name(), before < 0 ? "after 12 months" : monthLabel(s.engine().month(before)),
                    monthLabel(s.engine().month(Math.max(0, after))), before < 0 ? 0 : Math.max(0, before - Math.max(0, after))));
        }
        return new OneOffEffect(MoneyView.of(amount), lines,
                "One-off money is never planned in advance. These show each goal if all of it went there.");
    }

    // ------------------------------------------------------------------ money buckets

    @Transactional(readOnly = true)
    public Buckets buckets(CurrentMember me) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        Needs needs = needs(s);
        record Plan(UUID id, String status, String by) {
        }
        Plan plan = db.sql("""
                SELECT id, status, proposed_by FROM bucket_plan WHERE family_id = ? AND status IN ('ACTIVE', 'DRAFT')
                ORDER BY CASE status WHEN 'DRAFT' THEN 0 ELSE 1 END LIMIT 1
                """).param(me.familyId())
                .query((rs, n) -> new Plan(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional().orElse(null);
        String month = monthLabel(s.engine().month(0));
        if (plan == null) {
            return BucketEngine.view(null, null, "ENGINE", month, BucketEngine.suggestion(needs), needs);
        }
        return BucketEngine.view(plan.id(), RecordStatus.valueOf(plan.status()), plan.by(), month, shares(plan.id()), needs);
    }

    /** The engine's own split: the Planner's fallback, and a starting point for a parent. */
    @Transactional(readOnly = true)
    public List<Share> suggestion(CurrentMember me) {
        requireParent(me);
        return BucketEngine.suggestion(needs(facts.load(me.familyId())));
    }

    /**
     * Saves a split as the family's waiting draft (replacing any earlier draft). Refused with the reasons if the
     * shares don't add up or leave commitments uncovered, so the Planner can revise and try again.
     */
    @Transactional
    public Buckets saveBucketDraft(CurrentMember me, List<Share> shares, String proposedBy) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        List<String> problems = BucketEngine.problems(shares, needs(s));
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, String.join(". ", problems) + ".");
        }
        db.sql("DELETE FROM bucket_plan WHERE family_id = ? AND status = 'DRAFT'").param(me.familyId()).update();
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO bucket_plan (id, family_id, status, proposed_by, created_by) VALUES (?, ?, 'DRAFT', ?, ?)")
                .params(id, me.familyId(), proposedBy, me.memberId()).update();
        for (Share sh : shares) {
            db.sql("INSERT INTO bucket_plan_line (plan_id, kind, share_bp, reason) VALUES (?, ?, ?, ?)")
                    .params(id, sh.kind().name(), sh.shareBp(), trim(sh.reason(), 240)).update();
        }
        return buckets(me);
    }

    /** A parent confirms the waiting split. The old one is kept as ARCHIVED. */
    @Transactional
    public Buckets confirmBuckets(CurrentMember me) {
        requireParent(me);
        UUID draft = db.sql("SELECT id FROM bucket_plan WHERE family_id = ? AND status = 'DRAFT'").param(me.familyId())
                .query(UUID.class).optional().orElse(null);
        if (draft == null) {
            boolean active = db.sql("SELECT count(*) FROM bucket_plan WHERE family_id = ? AND status = 'ACTIVE'")
                    .param(me.familyId()).query(Long.class).single() > 0;
            if (active) {
                throw new ApiException(HttpStatus.CONFLICT, "These buckets are already confirmed");
            }
            // Nothing drafted yet: the parent is confirming the engine's own suggestion
            saveBucketDraft(me, suggestion(me), "ENGINE");
            draft = db.sql("SELECT id FROM bucket_plan WHERE family_id = ? AND status = 'DRAFT'").param(me.familyId())
                    .query(UUID.class).single();
        }
        List<String> problems = BucketEngine.problems(shares(draft), needs(facts.load(me.familyId())));
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The family's numbers changed since this split was made. " + String.join(". ", problems) + ".");
        }
        db.sql("UPDATE bucket_plan SET status = 'ARCHIVED' WHERE family_id = ? AND status = 'ACTIVE'").param(me.familyId()).update();
        db.sql("UPDATE bucket_plan SET status = 'ACTIVE', confirmed_by = ?, confirmed_at = ? WHERE id = ?")
                .params(me.memberId(), OffsetDateTime.now(clock), draft).update();
        return buckets(me);
    }

    private List<Share> shares(UUID plan) {
        Map<BucketKind, Share> byKind = new EnumMap<>(BucketKind.class);
        db.sql("SELECT kind, share_bp, reason FROM bucket_plan_line WHERE plan_id = ?").param(plan)
                .query((rs, n) -> byKind.put(BucketKind.valueOf(rs.getString(1)),
                        new Share(BucketKind.valueOf(rs.getString(1)), rs.getInt(2), rs.getString(3))))
                .list();
        return new ArrayList<>(byKind.values());
    }

    Needs needs(Snapshot s) {
        CashflowEngine e = s.engine();
        Cashflow c = e.summary();
        Money[] monthly = new Money[s.goals().size()];
        for (int k = 0; k < monthly.length; k++) {
            monthly[k] = e.savingFor(s.goals().get(k).id())[0];
        }
        return new Needs(e.plannedIncome(), e.commitmentsDue(), c.commitments().lines(), e.everyday(),
                c.everyday().lines(), e.saving(0), s.goals(), monthly);
    }

    // ------------------------------------------------------------------ helpers

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family's plan");
        }
    }

    private UUID requireMember(CurrentMember me, UUID member) {
        return db.sql("SELECT id FROM member WHERE id = ? AND family_id = ?").params(member, me.familyId())
                .query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
    }

    private void requireAccount(CurrentMember me, UUID account) {
        db.sql("SELECT id FROM money_account WHERE id = ? AND family_id = ? AND status = 'ACTIVE'")
                .params(account, me.familyId()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private static GoalFact goal(Snapshot s, UUID id) {
        return s.goals().stream().filter(g -> g.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Family goal not found"));
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }

    private static MoneyView view(long minor, String currency) {
        return MoneyView.of(new Money(minor, currency));
    }

    static String monthLabel(java.time.YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + m.getYear();
    }

    private static String name(String name, String fallback) {
        String n = name == null || name.isBlank() ? fallback : name.trim();
        return trim(n, 120);
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
