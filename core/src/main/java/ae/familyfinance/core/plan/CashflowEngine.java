package ae.familyfinance.core.plan;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Cashflow.Health;
import ae.familyfinance.core.plan.Cashflow.Line;
import ae.familyfinance.core.plan.Cashflow.Month;
import ae.familyfinance.core.plan.Cashflow.Section;
import ae.familyfinance.core.plan.Cashflow.State;
import ae.familyfinance.core.plan.Cashflow.Step;
import ae.familyfinance.core.plan.Cashflow.UntilNextIncome;
import ae.familyfinance.core.plan.Cashflow.Upcoming;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The money engine's cash-flow maths: income in, commitments out, what is truly free each month,
 * and what is free before a given date. Plain Java with no database, so every rule is easy to test.
 * {@link PlanService} loads the facts and hands them in.
 *
 * <p>The rules, in the order the family plan uses them:
 * <ol>
 *   <li>Income to plan on: fixed pay as it is; variable pay on a cautious base (the average of the 3 lowest of
 *       the last 6 months, or the parent's own number); one-off money only in the month it actually arrived.</li>
 *   <li>Regular commitments (weekly, monthly) come out of each month's pay.</li>
 *   <li>Lumpy commitments (rent cheques, school terms, yearly bills) get a monthly reserve: the next 12 months'
 *       payments divided by 12, rounded up. If a reserve will not be full on a payment's date, the gap comes
 *       out of the free cash of the month whose pay comes before it.</li>
 *   <li>Everyday spending is the family's budgets (weekly budgets × 52 ÷ 12).</li>
 *   <li>Free cash = income − regular commitments − reserves − reserve gaps − everyday.
 *       Saving for goals (in priority order, each until it is full) comes out of free cash; what is left is spare.</li>
 * </ol>
 */
public final class CashflowEngine {

    public static final int MONTHS = 12;
    /** Everyday spending is spread over a month of 30 days when only part of a month is counted. */
    static final int DAYS_PER_MONTH = 30;
    /** Bills shown on the upcoming list, besides any lumpy payment that falls short. */
    static final int UPCOMING_DAYS = 35;

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    public record IncomeFact(UUID id, String name, IncomeKind kind, long amountMinor, Frequency frequency,
                             LocalDate nextOn, Long baseOverrideMinor, List<Dated> receipts) {
    }

    /** A dated amount: a variable income's past payment, or one rent cheque / school term. */
    public record Dated(LocalDate on, long minor, String label) {
    }

    public record CommitmentFact(UUID id, String name, CommitmentKind kind, long amountMinor, Frequency frequency,
                                 LocalDate nextDueOn, Integer remainingCount, Long summerAmountMinor,
                                 long reservedMinor, boolean estimate, List<Dated> dates) {
    }

    public record BudgetFact(String label, boolean weekly, long limitMinor) {
    }

    public record GoalFact(UUID id, String name, Tier tier, long targetMinor, long savedMinor, Long monthlyMinor,
                           LocalDate targetDate, int priority) {
    }

    /** A credit card bill the card engine forecast, so the days before it can count it. */
    public record CardDue(UUID accountId, String label, LocalDate dueOn, long amountMinor) {
    }

    /**
     * {@code spendableNowMinor}: current accounts plus cash, as the family typed them (savings accounts excluded).
     */
    public record Facts(LocalDate today, String currency, List<IncomeFact> incomes, List<CommitmentFact> commitments,
                        List<BudgetFact> budgets, List<GoalFact> goals, long spendableNowMinor, List<CardDue> cardBills) {
    }

    /** One dated payment of a commitment, and whether its reserve covers it. */
    record Occurrence(CommitmentFact commitment, LocalDate on, Money amount, String label, State state, Money shortBy,
                      int fundMonth) {
    }

    private final Facts f;
    private final String cur;
    private final YearMonth first;
    private final int payDay;
    private final Money[] income = new Money[MONTHS];
    private final Money[] regular = new Money[MONTHS];
    private final Money[] reserves = new Money[MONTHS];
    private final Money[] shortfall = new Money[MONTHS];
    private final Money everyday;
    private final Map<UUID, Money> reserveOf = new HashMap<>();
    private final List<Occurrence> lumpy = new ArrayList<>();
    private final Map<UUID, Money[]> savingByGoal = new LinkedHashMap<>();

    public CashflowEngine(Facts facts) {
        this.f = facts;
        this.cur = facts.currency();
        this.first = YearMonth.from(facts.today());
        this.payDay = payDay();
        this.everyday = everydayMonthly();
        for (int m = 0; m < MONTHS; m++) {
            income[m] = incomeFor(m);
            regular[m] = regularFor(m);
            reserves[m] = zero();
            shortfall[m] = zero();
        }
        runReserves();
        runSaving();
    }

    // ------------------------------------------------------------------ public results

    public Cashflow summary() {
        List<Line> incomeLines = incomeLines();
        List<Line> commitmentLines = commitmentLines();
        List<Line> everydayLines = f.budgets().stream()
                .map(b -> new Line(b.label(), view(monthly(b)), b.weekly() ? "Weekly budget, as a month" : null))
                .sorted(Comparator.comparingLong((Line l) -> l.amount().minor()).reversed())
                .toList();
        List<Line> savingLines = savingLines();

        Money commitmentsTotal = regular[0].plus(reserves[0]).plus(shortfall[0]);
        Money free = free(0);
        Money saving = saving(0);
        Money spare = free.minus(saving);

        List<Month> months = new ArrayList<>();
        Money best = zero();
        for (int m = 0; m < MONTHS; m++) {
            best = best.max(free(m));
        }
        Money usual = usualFree();
        List<String> negative = new ArrayList<>();
        int tightest = 0;
        for (int m = 0; m < MONTHS; m++) {
            Money fm = free(m);
            if (fm.compareTo(free(tightest)) < 0) {
                tightest = m;
            }
            if (fm.isNegative()) {
                negative.add(monthName(m));
            }
            int share = fm.isPositive() && best.isPositive() ? fm.percentOf(best) : 0;
            months.add(new Month(first.plusMonths(m).toString(), shortMonth(m), view(income[m]),
                    view(regular[m].plus(reserves[m])), view(shortfall[m]), view(everyday), view(fm),
                    view(saving(m)), view(fm.minus(saving(m))), share,
                    fm.compareTo(usual) < 0 && shortfall[m].isPositive(), fm.isNegative()));
        }

        Money plannedIncome = income[0];
        Money debt = zero();
        Money variable = zero();
        for (CommitmentFact c : active()) {
            if (c.kind().isDebt()) {
                debt = debt.plus(c.frequency().isRegular() ? regularMonthly(c) : reserveOf.getOrDefault(c.id(), zero()));
            }
        }
        for (IncomeFact i : f.incomes()) {
            if (i.kind() == IncomeKind.VARIABLE) {
                variable = variable.plus(plannedMonthly(i));
            }
        }
        Health health = new Health(debt.percentOf(plannedIncome.max(money(1))),
                variable.percentOf(plannedIncome.max(money(1))), negative, monthName(tightest), view(free(tightest)));

        return new Cashflow(cur, f.today(), monthName(0),
                new Section(view(plannedIncome), incomeLines),
                new Section(view(commitmentsTotal), commitmentLines),
                new Section(view(everyday), everydayLines),
                view(free),
                new Section(view(saving), savingLines),
                view(spare),
                steps(plannedIncome, commitmentsTotal, free, saving, spare),
                untilNextIncome(),
                nextCommitment(),
                upcoming(),
                months,
                health,
                notes());
    }

    /** Planned income this month (the base the buckets split). */
    public Money plannedIncome() {
        return income[0];
    }

    /** What commitments need this month: regular payments, reserves and any reserve gap. The Survival number. */
    public Money commitmentsDue() {
        return regular[0].plus(reserves[0]).plus(shortfall[0]);
    }

    public Money everyday() {
        return everyday;
    }

    public Money free(int m) {
        return income[m].minus(regular[m]).minus(reserves[m]).minus(shortfall[m]).minus(everyday);
    }

    public Money saving(int m) {
        Money s = zero();
        for (Money[] g : savingByGoal.values()) {
            s = s.plus(g[m]);
        }
        return s;
    }

    /** What one goal gets each month under the current plan (zero when it is not funded or already full). */
    public Money[] savingFor(UUID goalId) {
        Money[] none = new Money[MONTHS];
        java.util.Arrays.fill(none, zero());
        return savingByGoal.getOrDefault(goalId, none);
    }

    /** Spare cash each month as if {@code goalId} received nothing: the room a new pace for that goal can use. */
    public Money[] spareWithout(UUID goalId) {
        Money[] out = new Money[MONTHS];
        Money[] own = savingFor(goalId);
        for (int m = 0; m < MONTHS; m++) {
            out[m] = free(m).minus(saving(m)).plus(own[m]);
        }
        return out;
    }

    public YearMonth month(int m) {
        return first.plusMonths(m);
    }

    /**
     * Money expected to be free on {@code date}: what the family can use today, plus pay that arrives by then
     * (less the month's reserves and savings, set aside on pay day), minus bills, reserve gaps, other card bills
     * and the everyday budget for those days. {@code excludeCard} leaves that card's own bill out, so the card
     * check can compare the bill against what is free to pay it.
     */
    public Projection freeOn(LocalDate date, UUID excludeCard) {
        LocalDate today = f.today();
        List<Line> why = new ArrayList<>();
        Money total = money(f.spendableNowMinor());
        why.add(new Line("In current accounts and cash today", view(total), null));
        if (!date.isAfter(today)) {
            return new Projection(total, why);
        }

        Money pay = zero();
        for (IncomeFact i : f.incomes()) {
            for (LocalDate d : incomeDates(i, today.plusDays(1), date)) {
                pay = pay.plus(i.kind() == IncomeKind.VARIABLE ? plannedMonthly(i) : money(i.amountMinor()));
            }
        }
        Money setAside = zero();
        for (int m = 0; m < MONTHS; m++) {
            LocalDate payDate = payDate(m);
            if (payDate.isAfter(today) && !payDate.isAfter(date)) {
                setAside = setAside.plus(reserves[m]).plus(saving(m));
            }
        }
        Money bills = zero();
        for (CommitmentFact c : active()) {
            if (c.frequency().isRegular()) {
                for (Dated d : occurrences(c, today.plusDays(1), date)) {
                    bills = bills.plus(money(d.minor()));
                }
            }
        }
        Money gaps = zero();
        for (Occurrence o : lumpy) {
            if (o.on().isAfter(today) && !o.on().isAfter(date)) {
                gaps = gaps.plus(o.shortBy());
            }
        }
        Money cards = zero();
        for (CardDue c : f.cardBills()) {
            if (!Objects.equals(c.accountId(), excludeCard) && c.dueOn().isAfter(today) && !c.dueOn().isAfter(date)) {
                cards = cards.plus(money(c.amountMinor()));
            }
        }
        long days = ChronoUnit.DAYS.between(today, date);
        // Rounded up to a whole AED: cautious, and nobody needs fils in a forecast
        Money spending = everyday.scaled(days, DAYS_PER_MONTH).roundedUpTo(100);

        if (pay.isPositive()) {
            why.add(new Line("Pay arriving by then", view(pay), null));
            why.add(new Line("Reserves and savings set aside on pay day", view(setAside.negate()), null));
        }
        why.add(new Line("Bills due by then", view(bills.negate()), null));
        if (gaps.isPositive()) {
            why.add(new Line("Reserve gaps due by then", view(gaps.negate()), null));
        }
        if (cards.isPositive()) {
            why.add(new Line("Other card bills due by then", view(cards.negate()), null));
        }
        why.add(new Line("Everyday budget for " + days + (days == 1 ? " day" : " days"), view(spending.negate()), null));
        total = total.plus(pay).minus(setAside).minus(bills).minus(gaps).minus(cards).minus(spending);
        return new Projection(total, why);
    }

    public record Projection(Money free, List<Line> why) {
    }

    // ------------------------------------------------------------------ income

    /** What an income source counts for in a month's plan. */
    Money plannedMonthly(IncomeFact i) {
        return switch (i.kind()) {
            case ONE_OFF -> zero();
            case VARIABLE -> i.baseOverrideMinor() != null ? money(i.baseOverrideMinor()) : cautiousBase(i.receipts(), f.today(), cur);
            case FIXED -> switch (i.frequency()) {
                case WEEKLY -> money(i.amountMinor()).scaled(52, 12);
                case YEARLY -> money(i.amountMinor()).scaled(1, 12);
                default -> money(i.amountMinor());
            };
        };
    }

    /**
     * The cautious base for commission or sales: the average of the 3 lowest of the last 6 full months,
     * rounded down to a whole AED. Months with nothing recorded are left out (not counted as zero), and with
     * fewer than 3 months on record the lowest month is used.
     */
    public static Money cautiousBase(List<Dated> receipts, LocalDate today, String currency) {
        YearMonth thisMonth = YearMonth.from(today);
        Map<YearMonth, Long> byMonth = new HashMap<>();
        for (Dated r : receipts) {
            YearMonth ym = YearMonth.from(r.on());
            if (ym.isBefore(thisMonth) && !ym.isBefore(thisMonth.minusMonths(6))) {
                byMonth.merge(ym, r.minor(), Math::addExact);
            }
        }
        List<Long> totals = byMonth.values().stream().sorted().toList();
        if (totals.isEmpty()) {
            return Money.zero(currency);
        }
        if (totals.size() < 3) {
            return new Money(totals.get(0), currency).roundedDownTo(100);
        }
        Money sum = Money.zero(currency);
        for (int k = 0; k < 3; k++) {
            sum = sum.plus(new Money(totals.get(k), currency));
        }
        return new Money(Math.floorDiv(sum.minor(), 3), currency).roundedDownTo(100);
    }

    private Money incomeFor(int m) {
        Money total = zero();
        YearMonth ym = month(m);
        for (IncomeFact i : f.incomes()) {
            if (i.kind() == IncomeKind.ONE_OFF) {
                // Never assumed in advance: only counts in the month it arrived, once it has.
                if (YearMonth.from(i.nextOn()).equals(ym) && !i.nextOn().isAfter(f.today())) {
                    total = total.plus(money(i.amountMinor()));
                }
            } else {
                total = total.plus(plannedMonthly(i));
            }
        }
        return total;
    }

    private List<LocalDate> incomeDates(IncomeFact i, LocalDate from, LocalDate to) {
        if (i.kind() == IncomeKind.ONE_OFF) {
            return List.of();
        }
        Frequency freq = i.kind() == IncomeKind.VARIABLE ? Frequency.MONTHLY : i.frequency();
        return Schedules.dates(i.nextOn(), freq, null, from, to);
    }

    /** The day of the month the plan treats as pay day: the earliest next pay among fixed and variable income. */
    private int payDay() {
        LocalDate next = null;
        for (IncomeFact i : f.incomes()) {
            if (i.kind() == IncomeKind.ONE_OFF) {
                continue;
            }
            LocalDate d = Schedules.nextOnOrAfter(i.nextOn(), i.frequency(), null, f.today());
            if (d != null && (next == null || d.getDayOfMonth() < next.getDayOfMonth())) {
                next = d;
            }
        }
        return next == null ? 1 : next.getDayOfMonth();
    }

    private LocalDate nextIncome() {
        LocalDate next = null;
        for (IncomeFact i : f.incomes()) {
            List<LocalDate> d = incomeDates(i, f.today().plusDays(1), f.today().plusYears(1));
            if (!d.isEmpty() && (next == null || d.get(0).isBefore(next))) {
                next = d.get(0);
            }
        }
        return next;
    }

    private List<Line> incomeLines() {
        List<Line> lines = new ArrayList<>();
        for (IncomeFact i : f.incomes()) {
            Money planned = plannedMonthly(i);
            switch (i.kind()) {
                case FIXED -> lines.add(new Line(i.name(), view(planned), "Take-home pay"));
                case VARIABLE -> lines.add(new Line(i.name(), view(planned), i.baseOverrideMinor() != null
                        ? "Planned on your own base"
                        : "Planned on a cautious base: the 3 lowest of the last 6 months"));
                case ONE_OFF -> {
                    if (YearMonth.from(i.nextOn()).equals(first) && !i.nextOn().isAfter(f.today())) {
                        lines.add(new Line(i.name(), view(money(i.amountMinor())), "One-off, arrived " + DAY_MONTH.format(i.nextOn())));
                    }
                }
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ commitments

    private List<CommitmentFact> active() {
        return f.commitments();
    }

    /** A commitment's payments between two dates, with the summer amount where there is one. */
    static List<Dated> occurrences(CommitmentFact c, LocalDate from, LocalDate to) {
        if (c.frequency() == Frequency.CUSTOM) {
            return c.dates().stream().filter(d -> !d.on().isBefore(from) && !d.on().isAfter(to))
                    .sorted(Comparator.comparing(Dated::on)).toList();
        }
        return Schedules.dates(c.nextDueOn(), c.frequency(), c.remainingCount(), from, to).stream()
                .map(d -> new Dated(d, amountOn(c, d), null))
                .toList();
    }

    private static long amountOn(CommitmentFact c, LocalDate d) {
        boolean summer = d.getMonthValue() >= 6 && d.getMonthValue() <= 9;
        return summer && c.summerAmountMinor() != null ? c.summerAmountMinor() : c.amountMinor();
    }

    private Money sumOccurrences(CommitmentFact c, YearMonth ym) {
        Money s = zero();
        for (Dated d : occurrences(c, ym.atDay(1), ym.atEndOfMonth())) {
            s = s.plus(money(d.minor()));
        }
        return s;
    }

    private Money regularFor(int m) {
        Money s = zero();
        for (CommitmentFact c : active()) {
            if (c.frequency().isRegular()) {
                s = s.plus(sumOccurrences(c, month(m)));
            }
        }
        return s;
    }

    /**
     * Every lumpy commitment gets its monthly reserve in the plan. Then, in date order, the reserve fills on each
     * pay day from today and pays the dated payments. A payment the reserve can't cover is SHORT, and the gap is
     * charged to the month of the last pay day before it (or this month if no pay day comes first).
     */
    private void runReserves() {
        LocalDate today = f.today();
        LocalDate horizonEnd = month(MONTHS - 1).atEndOfMonth();
        int firstPayMonth = payDate(0).isBefore(today) ? 1 : 0;
        for (CommitmentFact c : active()) {
            if (c.frequency().isRegular()) {
                continue;
            }
            List<Dated> ahead = occurrences(c, today, horizonEnd);
            if (ahead.isEmpty()) {
                continue;
            }
            // A one-off payment only needs saving for until its date; a repeating one is reserved for every month.
            int lastMonth = MONTHS - 1;
            if (c.frequency() == Frequency.ONCE) {
                LocalDate due = ahead.get(0).on();
                int dm = (int) ChronoUnit.MONTHS.between(first, YearMonth.from(due));
                lastMonth = Math.max(0, payDate(dm).isAfter(due) ? dm - 1 : dm);
            }
            Money reserve = monthlyReserve(c, ahead, Math.max(1, lastMonth - firstPayMonth + 1));
            reserveOf.put(c.id(), reserve);
            for (int m = 0; m <= lastMonth; m++) {
                reserves[m] = reserves[m].plus(reserve);
            }

            Money balance = money(c.reservedMinor());
            int m = firstPayMonth;
            int lastPaid = 0;
            for (Dated d : ahead) {
                while (m <= lastMonth && !payDate(m).isAfter(d.on())) {
                    balance = balance.plus(reserve);
                    lastPaid = m;
                    m++;
                }
                int dueMonth = (int) ChronoUnit.MONTHS.between(first, YearMonth.from(d.on()));
                Money amount = money(d.minor());
                if (balance.compareTo(amount) >= 0) {
                    balance = balance.minus(amount);
                    lumpy.add(new Occurrence(c, d.on(), amount, d.label(), State.RESERVED, zero(), dueMonth));
                } else {
                    Money gap = amount.minus(balance);
                    balance = zero();
                    shortfall[lastPaid] = shortfall[lastPaid].plus(gap);
                    lumpy.add(new Occurrence(c, d.on(), amount, d.label(), State.SHORT, gap, lastPaid));
                }
            }
        }
    }

    private LocalDate payDate(int m) {
        return Schedules.dayOf(month(m), payDay);
    }

    /**
     * Next 12 months of payments ÷ 12, rounded up to a whole AED. A one-off payment is spread over the pay days
     * left before it instead, minus what is already set aside.
     */
    private Money monthlyReserve(CommitmentFact c, List<Dated> ahead, int payDaysLeft) {
        if (c.frequency() == Frequency.ONCE) {
            Money left = money(ahead.get(0).minor()).minus(money(c.reservedMinor())).max(zero());
            return left.dividedRoundingUp(payDaysLeft).roundedUpTo(100);
        }
        Money year = zero();
        for (Dated d : occurrences(c, f.today(), f.today().plusYears(1).minusDays(1))) {
            year = year.plus(money(d.minor()));
        }
        return year.dividedRoundingUp(12).roundedUpTo(100);
    }

    /** A weekly or monthly payment as a month's amount, whichever month it falls in. */
    private Money regularMonthly(CommitmentFact c) {
        return c.frequency() == Frequency.WEEKLY ? money(c.amountMinor()).scaled(52, 12) : money(c.amountMinor());
    }

    private List<Line> commitmentLines() {
        List<Line> lines = new ArrayList<>();
        for (CommitmentFact c : active()) {
            if (c.frequency().isRegular()) {
                Money amount = sumOccurrences(c, first);
                if (amount.isPositive()) {
                    lines.add(new Line(c.name(), view(amount), c.estimate() ? "About, it changes" : c.kind().label()));
                }
            } else if (reserveOf.containsKey(c.id())) {
                long count = occurrences(c, f.today(), f.today().plusYears(1).minusDays(1)).size();
                String note = c.frequency() == Frequency.ONCE ? "Reserve for one payment"
                        : "Reserve for " + count + (count == 1 ? " payment" : " payments") + " in the next 12 months";
                lines.add(new Line(c.name() + " reserve", view(reserveOf.get(c.id())), note));
            }
        }
        if (shortfall[0].isPositive()) {
            lines.add(new Line("Reserve gap this month", view(shortfall[0]), "A payment comes before its reserve is full"));
        }
        lines.sort(Comparator.comparingLong((Line l) -> l.amount().minor()).reversed());
        return lines;
    }

    private Money everydayMonthly() {
        Money s = zero();
        for (BudgetFact b : f.budgets()) {
            s = s.plus(monthly(b));
        }
        return s;
    }

    private Money monthly(BudgetFact b) {
        return b.weekly() ? money(b.limitMinor()).scaled(52, 12) : money(b.limitMinor());
    }

    /** The free cash of a month with no reserve gap: what a "usual" month looks like. */
    private Money usualFree() {
        Money usual = null;
        for (int m = 0; m < MONTHS; m++) {
            if (!shortfall[m].isPositive()) {
                Money fm = free(m);
                usual = usual == null ? fm : usual.max(fm);
            }
        }
        return usual == null ? free(0) : usual;
    }

    // ------------------------------------------------------------------ saving

    /** Goals in priority order, each getting its monthly amount until it is full. */
    private void runSaving() {
        List<GoalFact> goals = f.goals().stream()
                .sorted(Comparator.comparingInt(GoalFact::priority).thenComparing(GoalFact::name))
                .toList();
        for (GoalFact g : goals) {
            Money[] per = new Money[MONTHS];
            Money left = money(g.targetMinor() - g.savedMinor()).max(zero());
            Money monthly = g.monthlyMinor() == null ? zero() : money(g.monthlyMinor());
            for (int m = 0; m < MONTHS; m++) {
                Money put = monthly.min(left);
                per[m] = put;
                left = left.minus(put);
            }
            savingByGoal.put(g.id(), per);
        }
    }

    private List<Line> savingLines() {
        List<Line> lines = new ArrayList<>();
        for (GoalFact g : f.goals()) {
            Money now = savingFor(g.id())[0];
            if (!now.isPositive()) {
                continue;
            }
            int fullIn = fullMonth(g);
            String note = fullIn >= 0 ? "Full in " + monthName(fullIn) : view(money(g.savedMinor())).text()
                    + " of " + view(money(g.targetMinor())).text() + " saved";
            lines.add(new Line(g.name(), view(now), note));
        }
        return lines;
    }

    /** The month a goal fills up under the current plan, or -1 if not within 12 months. */
    int fullMonth(GoalFact g) {
        Money left = money(g.targetMinor() - g.savedMinor());
        Money[] per = savingFor(g.id());
        for (int m = 0; m < MONTHS; m++) {
            left = left.minus(per[m]);
            if (!left.isPositive()) {
                return m;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ views

    private List<Step> steps(Money incomeTotal, Money commitments, Money free, Money saving, Money spare) {
        List<Step> steps = new ArrayList<>();
        if (!incomeTotal.isPositive()) {
            return steps;
        }
        int top = 10_000;
        steps.add(new Step("Income to plan on", view(incomeTotal), 0, top, "total"));
        int afterCommitments = bpLeft(incomeTotal, incomeTotal.minus(commitments));
        steps.add(new Step("Commitments and reserves", view(commitments.negate()), afterCommitments, top, "s1"));
        int afterEveryday = bpLeft(incomeTotal, free);
        steps.add(new Step("Everyday spending", view(everyday.negate()), afterEveryday, afterCommitments, "s2"));
        steps.add(new Step("Free cash", view(free), 0, afterEveryday, "total"));
        int afterSaving = bpLeft(incomeTotal, spare);
        steps.add(new Step("Saving for goals", view(saving.negate()), afterSaving, afterEveryday, "s3"));
        steps.add(new Step("Spare", view(spare), 0, afterSaving, "total"));
        return steps;
    }

    private static int bpLeft(Money total, Money left) {
        if (!left.isPositive()) {
            return 0;
        }
        return (int) Math.min(10_000, left.scaled(10_000, total.minor()).minor());
    }

    private UntilNextIncome untilNextIncome() {
        LocalDate next = nextIncome();
        if (next == null) {
            return null;
        }
        String label = f.incomes().stream()
                .filter(i -> incomeDates(i, next, next).contains(next))
                .map(IncomeFact::name).findFirst().orElse("Pay");
        Projection p = freeOn(next.minusDays(1), null);
        return new UntilNextIncome(next, label, view(p.free()), p.why());
    }

    private List<Occurrence> regularAhead(LocalDate to) {
        List<Occurrence> out = new ArrayList<>();
        for (CommitmentFact c : active()) {
            if (c.frequency().isRegular()) {
                for (Dated d : occurrences(c, f.today(), to)) {
                    out.add(new Occurrence(c, d.on(), money(d.minor()), d.label(), State.FROM_PAY, zero(), 0));
                }
            }
        }
        return out;
    }

    private Upcoming nextCommitment() {
        List<Occurrence> all = new ArrayList<>(regularAhead(f.today().plusYears(1)));
        all.addAll(lumpy);
        return all.stream().filter(o -> o.on().isAfter(f.today()))
                .min(Comparator.comparing(Occurrence::on).thenComparing(o -> o.commitment().name()))
                .map(this::upcoming).orElse(null);
    }

    private List<Upcoming> upcoming() {
        LocalDate soon = f.today().plusDays(UPCOMING_DAYS);
        List<Occurrence> list = new ArrayList<>(regularAhead(soon));
        Map<UUID, Boolean> shownNext = new HashMap<>();
        for (Occurrence o : lumpy) {
            boolean firstOfIt = shownNext.putIfAbsent(o.commitment().id(), true) == null;
            if (!o.on().isAfter(soon) || o.state() == State.SHORT || firstOfIt) {
                list.add(o);
            }
        }
        List<Upcoming> out = new ArrayList<>(list.stream()
                .sorted(Comparator.comparing(Occurrence::on).thenComparing(o -> o.commitment().name()))
                .map(this::upcoming).toList());
        for (CardDue c : f.cardBills()) {
            if (!c.dueOn().isBefore(f.today()) && c.amountMinor() > 0) {
                out.add(new Upcoming(null, c.label() + ", full balance", CommitmentKind.CREDIT_CARD,
                        CommitmentKind.CREDIT_CARD.label(), c.dueOn(), days(c.dueOn()), view(money(c.amountMinor())),
                        false, State.FROM_PAY, view(zero()), null));
            }
        }
        out.sort(Comparator.comparing(Upcoming::dueOn));
        return out;
    }

    private Upcoming upcoming(Occurrence o) {
        CommitmentFact c = o.commitment();
        return new Upcoming(c.id(), c.name(), c.kind(), c.kind().label(), o.on(), days(o.on()), view(o.amount()),
                c.estimate(), o.state(), view(o.shortBy()), o.label());
    }

    private int days(LocalDate d) {
        return (int) ChronoUnit.DAYS.between(f.today(), d);
    }

    private List<String> notes() {
        List<String> notes = new ArrayList<>();
        for (Occurrence o : lumpy) {
            if (o.state() == State.SHORT) {
                Money had = o.amount().minus(o.shortBy());
                notes.add(o.commitment().name() + (o.label() == null ? "" : ", " + o.label().toLowerCase(Locale.ROOT))
                        + " on " + DAY_MONTH.format(o.on()) + ": the reserve will have " + view(had).text() + " of "
                        + view(o.amount()).text() + ". The other " + view(o.shortBy()).text() + " comes out of "
                        + monthName(o.fundMonth()) + "'s free cash.");
            }
        }
        for (IncomeFact i : f.incomes()) {
            if (i.kind() == IncomeKind.VARIABLE && i.baseOverrideMinor() == null) {
                notes.add(i.name() + " is planned on a cautious base of " + view(plannedMonthly(i)).text()
                        + " a month: the average of the 3 lowest of the last 6 months.");
            }
        }
        for (CommitmentFact c : active()) {
            if (c.summerAmountMinor() != null) {
                notes.add(c.name() + " is about " + view(money(c.summerAmountMinor())).text()
                        + " a month from June to September.");
            }
        }
        return notes;
    }

    private String monthName(int m) {
        return month(m).getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private String shortMonth(int m) {
        return month(m).getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private Money money(long minor) {
        return new Money(minor, cur);
    }

    private Money zero() {
        return Money.zero(cur);
    }

    private static MoneyView view(Money m) {
        return MoneyView.of(m);
    }
}
