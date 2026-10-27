package ae.familyfinance.core.setup;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.AgeBand;
import ae.familyfinance.core.family.Role;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Amounts;
import ae.familyfinance.core.plan.MoneyFacts;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import ae.familyfinance.core.plan.Tier;
import ae.familyfinance.core.setup.StarterBudgetEngine.Buffer;
import ae.familyfinance.core.setup.StarterBudgetEngine.Kid;
import ae.familyfinance.core.setup.StarterBudgetEngine.Starter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * First-run setup for a real new family: a parent signs up (which makes the family), adds the other parent and
 * the kids, types the income and the big bills (the plan API), confirms the first budgets the engine starts from,
 * picks a first goal and its pace, confirms the Planner's money buckets, and finishes.
 *
 * <p>Every amount the family sees here is worked out by {@link StarterBudgetEngine} or the plan engines. Adding
 * people, saving budgets, adding goals and finishing are a parent's taps in the app, never an agent's call.
 */
@Service
public class SetupService {

    static final int MAX_PARENTS = 2;
    static final int MAX_KIDS = 6;
    static final int MIN_PARENT_PASSWORD = 8;
    static final int MIN_KID_PASSWORD = 6;
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,29}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public enum Step { FAMILY, INCOME, BUDGETS, PLAN, FINISH, DONE }

    /** Where a family is in the setup. {@code next} is the first step still to do. */
    public record Status(boolean done, Step next, String familyName, int parents, int kids, int incomes,
                         int commitments, int budgets, int goals, String buckets) {
    }

    public record NewFamily(String familyName, String displayName, String username, String password, String email) {
    }

    public record NewMember(Role role, String displayName, Integer age, String username, String password, String email,
                            boolean consent, String goalName, String goalPrice) {
    }

    public record MemberAdded(UUID id, String displayName, Role role, AgeBand ageBand, String username) {
    }

    public record BudgetLine(String category, String amount) {
    }

    public record PocketLine(UUID memberId, String weekly) {
    }

    public record NewGoal(String name, String target, LocalDate targetDate, Tier tier) {
    }

    public record GoalAdded(UUID id, String name, Tier tier, MoneyView target, LocalDate targetDate) {
    }

    private final JdbcClient db;
    private final MoneyFacts facts;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public SetupService(JdbcClient db, MoneyFacts facts, PasswordEncoder passwords, Clock clock) {
        this.db = db;
        this.facts = facts;
        this.passwords = passwords;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ sign up

    /** Makes the family and its first parent. Returns the new parent's id; the caller logs them in. */
    @Transactional
    public UUID signUp(NewFamily in) {
        String name = required(in.displayName(), 80, "your name");
        String family = in.familyName() == null || in.familyName().isBlank()
                ? "The " + name + " family" : required(in.familyName(), 120, "family name");
        String username = username(in.username());
        password(in.password(), MIN_PARENT_PASSWORD);
        String email = email(in.email());
        UUID familyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        db.sql("INSERT INTO family (id, name, currency, is_demo) VALUES (?, ?, 'AED', FALSE)")
                .params(familyId, family).update();
        db.sql("""
                INSERT INTO member (id, family_id, display_name, role, age_band, username, password_hash, email)
                VALUES (?, ?, ?, 'PARENT', 'ADULT', ?, ?, ?)
                """).params(memberId, familyId, name, username, passwords.encode(in.password()), email).update();
        return memberId;
    }

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true)
    public Status status(CurrentMember me) {
        requireParent(me);
        UUID f = me.familyId();
        record Fam(String name, boolean done) {
        }
        Fam fam = db.sql("SELECT name, is_demo OR setup_done_at IS NOT NULL FROM family WHERE id = ?").param(f)
                .query((rs, n) -> new Fam(rs.getString(1), rs.getBoolean(2))).single();
        int parents = count("SELECT count(*) FROM member WHERE family_id = ? AND role = 'PARENT' AND active", f);
        int kids = count("SELECT count(*) FROM member WHERE family_id = ? AND role = 'CHILD' AND active", f);
        int incomes = count("SELECT count(*) FROM income WHERE family_id = ? AND status = 'ACTIVE'", f);
        int commitments = count("SELECT count(*) FROM commitment WHERE family_id = ? AND status = 'ACTIVE'", f);
        int budgets = count("SELECT count(*) FROM budget WHERE family_id = ?", f);
        int goals = count("SELECT count(*) FROM goal WHERE family_id = ? AND member_id IS NULL AND status = 'ACTIVE'", f);
        String buckets = db.sql("""
                SELECT status FROM bucket_plan WHERE family_id = ? AND status IN ('ACTIVE', 'DRAFT')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END LIMIT 1
                """).param(f).query(String.class).optional().orElse("NONE");
        Step next;
        if (fam.done()) {
            next = Step.DONE;
        } else if (incomes == 0) {
            next = parents + kids == 1 ? Step.FAMILY : Step.INCOME;
        } else if (budgets == 0) {
            next = Step.BUDGETS;
        } else if (!"ACTIVE".equals(buckets)) {
            next = Step.PLAN;
        } else {
            next = Step.FINISH;
        }
        return new Status(fam.done(), next, fam.name(), parents, kids, incomes, commitments, budgets, goals, buckets);
    }

    // ------------------------------------------------------------------ family

    @Transactional
    public MemberAdded addMember(CurrentMember me, NewMember in) {
        requireParent(me);
        Role role = in.role() == null ? Role.CHILD : in.role();
        String name = required(in.displayName(), 80, "name");
        String username = username(in.username());
        UUID id = UUID.randomUUID();
        AgeBand band;
        if (role == Role.PARENT) {
            if (count("SELECT count(*) FROM member WHERE family_id = ? AND role = 'PARENT'", me.familyId()) >= MAX_PARENTS) {
                throw new ApiException(HttpStatus.CONFLICT, "A family has up to two parents in the app");
            }
            password(in.password(), MIN_PARENT_PASSWORD);
            band = AgeBand.ADULT;
            db.sql("""
                    INSERT INTO member (id, family_id, display_name, role, age_band, username, password_hash, email)
                    VALUES (?, ?, ?, 'PARENT', 'ADULT', ?, ?, ?)
                    """).params(id, me.familyId(), name, username, passwords.encode(in.password()), email(in.email()))
                    .update();
        } else {
            if (count("SELECT count(*) FROM member WHERE family_id = ? AND role = 'CHILD'", me.familyId()) >= MAX_KIDS) {
                throw new ApiException(HttpStatus.CONFLICT, "A family has up to six children in the app");
            }
            band = ageBand(in.age());
            if (!in.consent()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "A parent's consent is needed before a child can have an account");
            }
            password(in.password(), MIN_KID_PASSWORD);
            // PDPL: a child keeps only a name, an age band and a login. No birth date, no email.
            db.sql("""
                    INSERT INTO member (id, family_id, display_name, role, age_band, username, password_hash)
                    VALUES (?, ?, ?, 'CHILD', ?, ?, ?)
                    """).params(id, me.familyId(), name, band.name(), username, passwords.encode(in.password())).update();
            for (String purpose : List.of("CHILD_ACCOUNT", "AI_PROCESSING")) {
                db.sql("INSERT INTO consent (family_id, subject_member_id, granted_by_id, purpose) VALUES (?, ?, ?, ?)")
                        .params(me.familyId(), id, me.memberId(), purpose).update();
            }
            // The kid's wallet only ever holds demo money
            db.sql("INSERT INTO demo_wallet (family_id, member_id) VALUES (?, ?)").params(me.familyId(), id).update();
            if (in.goalName() != null && !in.goalName().isBlank()) {
                Money price = Amounts.parse(in.goalPrice(), currency(me.familyId()), "price of " + name + "'s goal");
                db.sql("""
                        INSERT INTO goal (family_id, member_id, name, tier, target_minor, currency, priority)
                        VALUES (?, ?, ?, 'GOOD_LIFE', ?, ?, 100)
                        """).params(me.familyId(), id, required(in.goalName(), 120, "goal"), price.minor(),
                                price.currency()).update();
            }
        }
        return new MemberAdded(id, name, role, band, username);
    }

    // ------------------------------------------------------------------ first budgets

    /** The engine's starting budgets for this family. Nothing is saved until a parent confirms. */
    @Transactional(readOnly = true)
    public Starter starterBudgets(CurrentMember me) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        if (s.incomes().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Add the family's income first");
        }
        int adults = count("SELECT count(*) FROM member WHERE family_id = ? AND role = 'PARENT' AND active", me.familyId());
        List<Kid> kids = db.sql("""
                SELECT id, display_name, age_band FROM member WHERE family_id = ? AND role = 'CHILD' AND active
                ORDER BY CASE age_band WHEN 'TEEN_13_17' THEN 0 WHEN 'PRETEEN_10_12' THEN 1 ELSE 2 END, created_at, display_name
                """).param(me.familyId())
                .query((rs, n) -> new Kid(rs.getObject(1, UUID.class), rs.getString(2), AgeBand.valueOf(rs.getString(3))))
                .list();
        return StarterBudgetEngine.starter(s.engine().plannedIncome(), s.engine().commitmentsDue(), adults, kids);
    }

    /** The budgets and pocket money as they are now, with the engine's start as a guide: for changing them later. */
    @Transactional(readOnly = true)
    public Starter currentBudgets(CurrentMember me) {
        Starter guide = starterBudgets(me);
        java.util.Map<String, Long> monthly = new java.util.HashMap<>();
        java.util.Map<UUID, Long> weekly = new java.util.HashMap<>();
        db.sql("SELECT member_id, category, limit_minor FROM budget WHERE family_id = ?").param(me.familyId())
                .query((rs, n) -> {
                    UUID member = rs.getObject(1, UUID.class);
                    if (member == null) {
                        monthly.merge(rs.getString(2), rs.getLong(3), Long::sum);
                    } else if ("POCKET_MONEY".equals(rs.getString(2))) {
                        weekly.put(member, rs.getLong(3));
                    }
                    return null;
                }).list();
        return StarterBudgetEngine.current(guide, monthly, weekly);
    }

    /**
     * A parent confirms the family's monthly budgets and each child's weekly pocket money. Replaces the ones there
     * were, so the same step can be gone through again. An amount of 0 leaves that budget out.
     */
    @Transactional
    public void saveBudgets(CurrentMember me, List<BudgetLine> lines, List<PocketLine> pocket) {
        requireParent(me);
        String cur = currency(me.familyId());
        Set<String> seen = new HashSet<>();
        db.sql("DELETE FROM budget WHERE family_id = ? AND member_id IS NULL AND period = 'MONTHLY'")
                .param(me.familyId()).update();
        for (BudgetLine l : lines == null ? List.<BudgetLine>of() : lines) {
            if (l.category() == null || !StarterBudgetEngine.isStarterCategory(l.category())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown budget: " + l.category());
            }
            if (!seen.add(l.category())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, StarterBudgetEngine.labelOf(l.category()) + " is listed twice");
            }
            Money amount = amountOrZero(l.amount(), cur, StarterBudgetEngine.labelOf(l.category()) + " budget");
            if (amount.isPositive()) {
                db.sql("""
                        INSERT INTO budget (family_id, member_id, category, period, limit_minor, currency, bucket)
                        VALUES (?, NULL, ?, 'MONTHLY', ?, ?, ?)
                        """).params(me.familyId(), l.category(), amount.minor(), cur,
                                StarterBudgetEngine.bucketOf(l.category())).update();
            }
        }
        for (PocketLine p : pocket == null ? List.<PocketLine>of() : pocket) {
            String role = db.sql("SELECT role FROM member WHERE id = ? AND family_id = ?").params(p.memberId(), me.familyId())
                    .query(String.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
            if (!Role.CHILD.name().equals(role)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Pocket money is for the children");
            }
            db.sql("DELETE FROM budget WHERE family_id = ? AND member_id = ? AND category = 'POCKET_MONEY'")
                    .params(me.familyId(), p.memberId()).update();
            Money weekly = amountOrZero(p.weekly(), cur, "pocket money");
            if (weekly.isPositive()) {
                db.sql("""
                        INSERT INTO budget (family_id, member_id, category, period, limit_minor, currency, bucket)
                        VALUES (?, ?, 'POCKET_MONEY', 'WEEKLY', ?, ?, 'EVERYDAY')
                        """).params(me.familyId(), p.memberId(), weekly.minor(), cur).update();
            }
        }
        if (count("SELECT count(*) FROM budget WHERE family_id = ?", me.familyId()) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Type an amount for at least one budget, groceries for example, before you go on");
        }
    }

    // ------------------------------------------------------------------ first goal

    /** The size of the first goal the setup offers: an emergency buffer, from the confirmed budgets. */
    @Transactional(readOnly = true)
    public Buffer buffer(CurrentMember me) {
        requireParent(me);
        Snapshot s = facts.load(me.familyId());
        return StarterBudgetEngine.buffer(s.engine().commitmentsDue(), s.engine().everyday());
    }

    @Transactional
    public GoalAdded addGoal(CurrentMember me, NewGoal in) {
        requireParent(me);
        String cur = currency(me.familyId());
        String name = required(in.name(), 120, "goal name");
        Money target = Amounts.parse(in.target(), cur, "goal amount");
        LocalDate today = LocalDate.now(clock);
        if (in.targetDate() != null && !in.targetDate().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The target date has to be in the future");
        }
        Tier tier = in.tier() == null ? Tier.GOOD_LIFE : in.tier();
        int priority = switch (tier) {
            case SURVIVAL -> 10;
            case GOOD_LIFE -> 50;
            case DREAM -> 90;
        };
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO goal (id, family_id, member_id, name, tier, target_minor, currency, target_date, priority)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), name, tier.name(), target.minor(), cur, in.targetDate(), priority).update();
        return new GoalAdded(id, name, tier, MoneyView.of(target), in.targetDate());
    }

    // ------------------------------------------------------------------ finish

    @Transactional
    public Status finish(CurrentMember me) {
        Status s = status(me);
        if (s.done()) {
            return s;
        }
        if (s.incomes() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Add the family's income first");
        }
        if (s.budgets() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Confirm the first budgets first");
        }
        if (!"ACTIVE".equals(s.buckets())) {
            throw new ApiException(HttpStatus.CONFLICT, "Confirm the money buckets first");
        }
        db.sql("UPDATE family SET setup_done_at = ? WHERE id = ?").params(OffsetDateTime.now(clock), me.familyId()).update();
        return status(me);
    }

    // ------------------------------------------------------------------ helpers

    static AgeBand ageBand(Integer age) {
        if (age == null || age < 6 || age > 17) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Children from 6 to 17 can have their own login");
        }
        return age <= 9 ? AgeBand.CHILD_6_9 : age <= 12 ? AgeBand.PRETEEN_10_12 : AgeBand.TEEN_13_17;
    }

    private String username(String raw) {
        String u = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A username is 3 to 30 letters or numbers (dots, dashes and _ are fine), with no spaces");
        }
        if (count("SELECT count(*) FROM member WHERE username = ?", u) > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The username \"" + u + "\" is taken. Try another, for example with your family name added");
        }
        return u;
    }

    private static void password(String p, int min) {
        if (p == null || p.length() < min) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The password needs at least " + min + " characters");
        }
        if (p.length() > 72) { // bcrypt reads only the first 72 bytes
            throw new ApiException(HttpStatus.BAD_REQUEST, "The password can have at most 72 characters");
        }
    }

    private static String email(String e) {
        if (e == null || e.isBlank()) {
            return null;
        }
        String t = e.trim();
        if (t.length() > 160 || !EMAIL.matcher(t).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That email address doesn't look right");
        }
        return t;
    }

    private static String required(String text, int max, String what) {
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please give the " + what);
        }
        String t = text.trim();
        if (t.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The " + what + " can have at most " + max + " characters");
        }
        return t;
    }

    private static Money amountOrZero(String text, String cur, String what) {
        if (text == null || text.isBlank() || text.trim().matches("0+([.]0+)?")) {
            return Money.zero(cur);
        }
        return Amounts.parse(text, cur, what);
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can set up the family");
        }
    }

    private int count(String sql, Object param) {
        return db.sql(sql).param(param).query(Long.class).single().intValue();
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }
}
