package ae.familyfinance.core.rules;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Amounts;
import ae.familyfinance.core.rules.RuleEngine.Rule;
import ae.familyfinance.core.rules.RuleEngine.Spent;
import ae.familyfinance.core.spend.SpendCategory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Family rules: a parent writes one in plain words, the Gatekeeper agent turns it into a typed RuleDraft, the core
 * checks the draft and keeps it as DRAFT, and only a parent's tap in the app turns it on. The engine
 * ({@link RuleEngine}) then checks every child's request against the rules that are on.
 */
@Service
public class RuleService {

    public enum Status { DRAFT, ACTIVE, ARCHIVED }

    /**
     * A typed rule as an agent (or a parent) sends it. Amounts are the text written ("100" or "12.50"); the core
     * turns them into fils. {@code forChild} null = every child.
     */
    public record Draft(RuleKind kind, UUID forChild, SpendCategory category, Allergen allergen, String limit,
                        boolean weekly, String said, String draftedBy) {
    }

    public record RuleView(UUID id, RuleKind kind, String kindLabel, Status status, String said, String label,
                           UUID forChildId, String forChildName, SpendCategory category, String categoryLabel,
                           Allergen allergen, String allergenLabel, MoneyView limit, String period,
                           String draftedBy, List<String> chips, List<String> replaces, OffsetDateTime createdAt) {
    }

    private final JdbcClient db;
    private final Clock clock;

    public RuleService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ what parents and children see

    /** Parents: every rule that is on or waiting. A child: the rules that are on for them. */
    @Transactional(readOnly = true)
    public List<RuleView> list(CurrentMember me) {
        List<RuleView> all = load(me.familyId(), null);
        if (me.isParent()) {
            return all.stream().filter(r -> r.status() != Status.ARCHIVED).toList();
        }
        return all.stream().filter(r -> r.status() == Status.ACTIVE
                && (r.forChildId() == null || r.forChildId().equals(me.memberId()))).toList();
    }

    @Transactional
    public RuleView draft(CurrentMember me, Draft d, UUID agentRunId) {
        requireParent(me);
        if (d.kind() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Say what kind of rule it is");
        }
        String currency = currency(me.familyId());
        if (d.forChild() != null) {
            boolean child = db.sql("SELECT count(*) FROM member WHERE id = ? AND family_id = ? AND role = 'CHILD'")
                    .params(d.forChild(), me.familyId()).query(Long.class).single() > 0;
            if (!child) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Rules are for the children in your family");
            }
        }
        Money limit = null;
        switch (d.kind()) {
            case BLOCK_CATEGORY -> requireCategory(d);
            case BLOCK_ALLERGEN -> {
                if (d.allergen() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Say which allergy the rule is for");
                }
            }
            case APPROVAL_LIMIT, MONTHLY_CAP -> limit = Amounts.parse(d.limit(), currency, "limit");
            case CATEGORY_LIMIT -> {
                requireCategory(d);
                limit = Amounts.parse(d.limit(), currency, "limit");
            }
        }
        if (limit != null && !limit.isPositive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The limit must be more than zero");
        }
        String said = d.said() == null || d.said().isBlank() ? null : d.said().strip();
        if (said != null && said.length() > 300) {
            said = said.substring(0, 300);
        }
        String period = d.kind() == RuleKind.CATEGORY_LIMIT ? (d.weekly() ? "WEEKLY" : "MONTHLY")
                : d.kind() == RuleKind.MONTHLY_CAP ? "MONTHLY" : null;
        String draftedBy = agentRunId == null ? "PARENT" : "LLM".equals(d.draftedBy()) ? "LLM" : "RULES";
        UUID id = db.sql("""
                INSERT INTO family_rule (family_id, applies_to_member_id, kind, category, allergen, limit_minor, period,
                                         note, said, status, source, drafted_by, agent_run_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?) RETURNING id
                """).params(me.familyId(), d.forChild(), d.kind().name(),
                        d.category() == null || !usesCategory(d.kind()) ? null : d.category().name(),
                        d.kind() == RuleKind.BLOCK_ALLERGEN ? d.allergen().name() : null,
                        limit == null ? null : limit.minor(), period,
                        said == null || said.length() <= 255 ? said : said.substring(0, 255), said,
                        agentRunId == null ? "MANUAL" : "AGENT", draftedBy, agentRunId, me.memberId())
                .query(UUID.class).single();
        return one(me.familyId(), id);
    }

    /**
     * A parent turns a draft on. A rule that says the same thing for the same child (a new approval limit, a new
     * cap) replaces the old one, which is archived, so there is never two answers to one question.
     */
    @Transactional
    public RuleView confirm(CurrentMember me, UUID id) {
        requireParent(me);
        RuleView r = one(me.familyId(), id);
        if (r.status() != Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, r.status() == Status.ACTIVE ? "This rule is already on" : "This rule was turned off");
        }
        List<RuleView> same = sameQuestion(me.familyId(), r);
        for (RuleView old : same) {
            archive(me.familyId(), old.id());
        }
        db.sql("UPDATE family_rule SET status = 'ACTIVE', confirmed_by = ?, confirmed_at = now() WHERE id = ? AND family_id = ?")
                .params(me.memberId(), id, me.familyId()).update();
        RuleView on = one(me.familyId(), id);
        return withReplaces(on, same.stream().map(RuleView::label).toList());
    }

    /** Turns a rule off (kept for history). A draft nobody turned on is simply removed. */
    @Transactional
    public void turnOff(CurrentMember me, UUID id) {
        requireParent(me);
        RuleView r = one(me.familyId(), id);
        if (r.status() == Status.DRAFT) {
            db.sql("DELETE FROM family_rule WHERE id = ? AND family_id = ?").params(id, me.familyId()).update();
        } else if (r.status() == Status.ACTIVE) {
            archive(me.familyId(), id);
        }
    }

    // ------------------------------------------------------------------ for the request check

    /** The rules that are on for this child, ready for {@link RuleEngine}. */
    @Transactional(readOnly = true)
    public List<Rule> activeFor(UUID family, UUID child) {
        return load(family, Status.ACTIVE).stream()
                .filter(r -> r.forChildId() == null || r.forChildId().equals(child))
                .map(r -> new Rule(r.id(), r.kind(), r.forChildId(), r.label(), r.category(), r.allergen(),
                        r.limit() == null ? null : r.limit().minor(), "WEEKLY".equals(r.period())))
                .toList();
    }

    /** What the child has spent (as logged) this month and this week, for limits and the cap. */
    @Transactional(readOnly = true)
    public Spent spentBy(UUID family, UUID child) {
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = monthStart.isBefore(weekStart) ? monthStart : weekStart;
        long[] month = {0};
        Map<SpendCategory, Long> byMonth = new EnumMap<>(SpendCategory.class);
        Map<SpendCategory, Long> byWeek = new EnumMap<>(SpendCategory.class);
        db.sql("""
                SELECT category, occurred_on, amount_minor FROM ledger_transaction
                WHERE family_id = ? AND member_id = ? AND direction = 'DEBIT' AND duplicate_of_id IS NULL
                  AND occurred_on BETWEEN ? AND ?
                """).params(family, child, from, today)
                .query((rs, n) -> {
                    LocalDate on = rs.getObject(2, LocalDate.class);
                    long minor = rs.getLong(3);
                    SpendCategory c = category(rs.getString(1));
                    if (!on.isBefore(monthStart)) {
                        month[0] = Math.addExact(month[0], minor);
                        if (c != null) {
                            byMonth.merge(c, minor, Math::addExact);
                        }
                    }
                    if (!on.isBefore(weekStart) && c != null) {
                        byWeek.merge(c, minor, Math::addExact);
                    }
                    return null;
                }).list();
        return new Spent(month[0], byMonth, byWeek);
    }

    // ------------------------------------------------------------------ plain words for a typed rule

    /** "No peanuts for Adam", "Anything over AED 50 needs a parent", "Layla: snacks up to AED 50 a month". */
    static String describe(RuleKind kind, String child, SpendCategory category, Allergen allergen, Money limit, boolean weekly) {
        String who = child == null ? "the kids" : child;
        return switch (kind) {
            case BLOCK_CATEGORY -> "No " + category.label() + " for " + who;
            case BLOCK_ALLERGEN -> "No " + allergen.label() + " for " + who;
            case APPROVAL_LIMIT -> "Anything over " + limit.toDisplay() + " needs a parent" + (child == null ? "" : " (" + child + ")");
            case CATEGORY_LIMIT -> RuleEngine.capital(category.label()) + " up to " + limit.toDisplay()
                                   + (weekly ? " a week" : " a month") + " for " + who;
            case MONTHLY_CAP -> "Up to " + limit.toDisplay() + " a month in all for " + who;
        };
    }

    // ------------------------------------------------------------------ helpers

    private List<RuleView> load(UUID family, Status only) {
        String currency = currency(family);
        return db.sql("""
                SELECT r.id, r.kind, r.status, r.said, r.applies_to_member_id, m.display_name, r.category, r.allergen,
                       r.limit_minor, r.period, r.drafted_by, r.created_at
                FROM family_rule r LEFT JOIN member m ON m.id = r.applies_to_member_id
                WHERE r.family_id = ? AND (CAST(? AS VARCHAR) IS NULL OR r.status = ?)
                ORDER BY CASE r.status WHEN 'DRAFT' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, r.created_at, r.id
                """).params(family, only == null ? null : only.name(), only == null ? null : only.name())
                .query((rs, n) -> {
                    RuleKind kind = RuleKind.valueOf(rs.getString(2));
                    String child = rs.getString(6);
                    SpendCategory category = category(rs.getString(7));
                    Allergen allergen = rs.getString(8) == null ? null : Allergen.valueOf(rs.getString(8));
                    Long limitMinor = rs.getObject(9, Long.class);
                    Money limit = limitMinor == null ? null : new Money(limitMinor, currency);
                    boolean weekly = "WEEKLY".equals(rs.getString(10));
                    String said = rs.getString(4);
                    String label = said != null && !said.isBlank() ? capitalSentence(said)
                            : describe(kind, child, category, allergen, limit, weekly);
                    return new RuleView(rs.getObject(1, UUID.class), kind, kind.label(), Status.valueOf(rs.getString(3)),
                            said, label, rs.getObject(5, UUID.class), child, category,
                            category == null ? null : category.label(), allergen, allergen == null ? null : allergen.label(),
                            limit == null ? null : MoneyView.of(limit), rs.getString(10), rs.getString(11),
                            chips(kind, child, category, allergen, limit, weekly), List.of(),
                            rs.getObject(12, OffsetDateTime.class));
                }).list();
    }

    private static List<String> chips(RuleKind kind, String child, SpendCategory category, Allergen allergen,
                                      Money limit, boolean weekly) {
        List<String> chips = new ArrayList<>();
        chips.add(kind.label());
        if (allergen != null) {
            chips.add(RuleEngine.capital(allergen.label()));
        }
        if (category != null) {
            chips.add(RuleEngine.capital(category.label()));
        }
        if (limit != null) {
            chips.add(limit.toDisplay() + (kind == RuleKind.CATEGORY_LIMIT ? (weekly ? " a week" : " a month")
                    : kind == RuleKind.MONTHLY_CAP ? " a month" : ""));
        }
        chips.add(child == null ? "All the kids" : child);
        return chips;
    }

    private RuleView one(UUID family, UUID id) {
        return load(family, null).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rule not found"));
    }

    /** ACTIVE rules that answer the same question as {@code r} for the same child. */
    private List<RuleView> sameQuestion(UUID family, RuleView r) {
        return load(family, Status.ACTIVE).stream()
                .filter(o -> o.kind() == r.kind() && java.util.Objects.equals(o.forChildId(), r.forChildId()))
                .filter(o -> switch (r.kind()) {
                    case APPROVAL_LIMIT, MONTHLY_CAP -> true;
                    case BLOCK_CATEGORY -> o.category() == r.category();
                    case CATEGORY_LIMIT -> o.category() == r.category() && java.util.Objects.equals(o.period(), r.period());
                    case BLOCK_ALLERGEN -> o.allergen() == r.allergen();
                })
                .toList();
    }

    private void archive(UUID family, UUID id) {
        db.sql("UPDATE family_rule SET status = 'ARCHIVED', archived_at = now() WHERE id = ? AND family_id = ?")
                .params(id, family).update();
    }

    private static RuleView withReplaces(RuleView r, List<String> replaces) {
        return new RuleView(r.id(), r.kind(), r.kindLabel(), r.status(), r.said(), r.label(), r.forChildId(),
                r.forChildName(), r.category(), r.categoryLabel(), r.allergen(), r.allergenLabel(), r.limit(), r.period(),
                r.draftedBy(), r.chips(), replaces, r.createdAt());
    }

    private static boolean usesCategory(RuleKind kind) {
        return kind == RuleKind.BLOCK_CATEGORY || kind == RuleKind.CATEGORY_LIMIT;
    }

    private static void requireCategory(Draft d) {
        if (d.category() == null || d.category() == SpendCategory.OTHER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Say which kind of thing the rule is about");
        }
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can set family rules");
        }
    }

    private static SpendCategory category(String name) {
        if (name == null) {
            return null;
        }
        try {
            return SpendCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null; // a family budget category (GROCERIES), not something a child buys
        }
    }

    private static String capitalSentence(String s) {
        String t = s.strip();
        if (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }
}
