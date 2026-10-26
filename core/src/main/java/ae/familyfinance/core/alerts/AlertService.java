package ae.familyfinance.core.alerts;

import ae.familyfinance.core.accounts.AccountService;
import ae.familyfinance.core.accounts.AccountService.DueCard;
import ae.familyfinance.core.alerts.WeeklyPlanEngine.WeekPlan;
import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.budgets.BudgetService;
import ae.familyfinance.core.common.ClockConfig;
import ae.familyfinance.core.live.LiveKind;
import ae.familyfinance.core.live.Outbox;
import ae.familyfinance.core.plan.Cashflow;
import ae.familyfinance.core.plan.MoneyFacts;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The core's own alerts, by fixed rules (no AI decides when to buzz a parent's phone):
 *
 * <ul>
 *   <li>a bill (commitment) due in the next {@value #BILL_DAYS} days, with what is put aside for it;</li>
 *   <li>a credit card bill due in the next {@value #CARD_DAYS} days, or within {@value #CARD_SHORT_DAYS} days when the
 *       free cash on its due date won't cover it;</li>
 *   <li>a budget over, heading over, or above the usual month (the budget engine's alerts);</li>
 *   <li>on Sunday from 8 am (UAE), the weekly plan ({@link WeeklyPlanEngine}).</li>
 * </ul>
 *
 * Checked every 15 minutes. Each alert has a key (bill + date, budget + month + state, week number), so it is only
 * ever sent once; the outbox refuses the second copy.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    static final int BILL_DAYS = 3;
    static final int CARD_DAYS = 5;
    static final int CARD_SHORT_DAYS = 14;
    static final int WEEKLY_FROM_HOUR = 8;

    public record Checked(List<String> sent) {
    }

    private final MoneyFacts moneyFacts;
    private final BudgetService budgets;
    private final AccountService accounts;
    private final Outbox outbox;
    private final JdbcClient db;
    private final Clock clock;

    public AlertService(MoneyFacts moneyFacts, BudgetService budgets, AccountService accounts, Outbox outbox,
                        JdbcClient db, Clock clock) {
        this.moneyFacts = moneyFacts;
        this.budgets = budgets;
        this.accounts = accounts;
        this.outbox = outbox;
        this.db = db;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${app.alerts.every-ms:900000}")
    void checkAll() {
        for (UUID family : db.sql("SELECT id FROM family").query(UUID.class).list()) {
            try {
                check(family, false);
            } catch (RuntimeException e) {
                log.warn("Alert check failed for a family: {}", e.toString());
            }
        }
    }

    public Checked check(UUID family, boolean weeklyNow) {
        return check(family, weeklyNow, false);
    }

    /**
     * {@code weeklyNow}: send this week's plan now instead of waiting for Sunday (the "Check now" button).
     * {@code summaryNow}: send this month's summary so far now instead of waiting for the 1st (for the demo).
     */
    public Checked check(UUID family, boolean weeklyNow, boolean summaryNow) {
        Snapshot s = moneyFacts.load(family);
        Cashflow flow = s.engine().summary();
        LocalDate today = s.today();
        List<String> sent = new ArrayList<>();

        for (Cashflow.Upcoming u : flow.upcoming()) {
            if (u.commitmentId() != null && u.daysLeft() >= 0 && u.daysLeft() <= BILL_DAYS) {
                String title = u.name() + " due " + when(u.daysLeft());
                String body = u.amount().text() + (u.estimate() ? " (an estimate)" : "") + switch (u.state()) {
                    case FROM_PAY -> ", paid from this month's money.";
                    case RESERVED -> ". The money is already put aside.";
                    case SHORT -> ". The reserve is " + u.shortBy().text() + " short, so that part comes out of free cash.";
                };
                once(family, LiveKind.BILL_DUE, "bill:" + u.commitmentId() + ":" + u.dueOn(), title, body, sent);
            }
        }

        for (DueCard c : accounts.dueCards(s)) {
            AccountService.CardBill b = c.bill();
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(today, b.dueOn());
            if (b.bill().minor() <= 0 || days < 0 || !(days <= CARD_DAYS || (!b.ok() && days <= CARD_SHORT_DAYS))) {
                continue;
            }
            String title = c.label() + " bill due " + when(days);
            String body = b.bill().text() + " to pay in full. " + b.freeByDue().text() + " free by then"
                    + (b.ok() ? ", enough to cover it." : ", so " + b.shortBy().text() + " short.");
            once(family, LiveKind.CARD_DUE, "card:" + c.accountId() + ":" + b.dueOn() + ":" + (b.ok() ? "ok" : "short"),
                    title, body, sent);
        }

        BudgetEngine.Month month = budgets.month(family);
        for (BudgetEngine.Line line : month.alerts()) {
            String title = line.label() + switch (line.state()) {
                case OVER -> " is over budget";
                case HEADING_OVER -> " is heading over budget";
                default -> " is above the usual month";
            };
            once(family, LiveKind.OVERSPEND, "budget:" + line.category() + ":" + today.withDayOfMonth(1) + ":" + line.state(),
                    title, line.alert(), sent);
        }

        LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.UAE));
        if (weeklyNow || (now.getDayOfWeek() == DayOfWeek.SUNDAY && now.getHour() >= WEEKLY_FROM_HOUR)) {
            WeekPlan plan = WeeklyPlanEngine.plan(today, s.currency(), flow.untilNextIncome(), flow.upcoming(), month);
            String week = today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            once(family, LiveKind.WEEKLY_PLAN, "week:" + week, "This week's plan", plan.body(), sent);
        }

        // On the 1st from 8 am, last month's family summary; the words on the page are the summary's own
        if (now.getDayOfMonth() == 1 && now.getHour() >= WEEKLY_FROM_HOUR) {
            YearMonth last = YearMonth.from(today).minusMonths(1);
            monthSummary(family, last, "summary:" + last, sent);
        }
        if (summaryNow) {
            monthSummary(family, YearMonth.from(today), "summary-now:" + today, sent);
        }
        return new Checked(List.copyOf(sent));
    }

    private void monthSummary(UUID family, YearMonth month, String key, List<String> sent) {
        String label = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        boolean running = month.equals(YearMonth.now(clock));
        String title = "The family's " + label + (running ? " so far" : "");
        String body = "Budgets, goals and the kids' month on one page. The kids get their own version, without the money.";
        if (outbox.publishOnce(family, LiveKind.MONTH_SUMMARY, key,
                Map.of("title", title, "body", body, "month", month.toString(), "monthLabel", label))) {
            sent.add(title);
        }
    }

    public WeekPlan weekPlan(UUID family) {
        Snapshot s = moneyFacts.load(family);
        Cashflow flow = s.engine().summary();
        return WeeklyPlanEngine.plan(s.today(), s.currency(), flow.untilNextIncome(), flow.upcoming(), budgets.month(family));
    }

    private void once(UUID family, LiveKind kind, String key, String title, String body, List<String> sent) {
        if (outbox.publishOnce(family, kind, key, Map.of("title", title, "body", body))) {
            sent.add(title);
        }
    }

    private static String when(int days) {
        return days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
    }
}
