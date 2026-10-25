package ae.familyfinance.core.alerts;

import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Cashflow;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The weekly re-plan: on Sunday, what this week can take. Pure Java, every figure worked out here.
 *
 * <ul>
 *   <li><b>Free this week</b>: the free cash until pay day (the cash-flow engine's figure, bills before pay already
 *       taken out), shared evenly over the days to pay day, times this week's days. Rounded down to AED 10.</li>
 *   <li><b>Bills this week</b>: everything the cash-flow calendar has due in the next 7 days.</li>
 *   <li><b>Budgets to slow down</b>: a budget already over has nothing left this week; one heading over gets what is
 *       left shared over the weeks left in the month (rounded down to AED 10).</li>
 * </ul>
 */
public final class WeeklyPlanEngine {

    static final long ROUND_TO_MINOR = 1_000; // AED 10
    static final int WEEK = 7;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    public record Line(String label, MoneyView amount, String note) {
    }

    /** {@code body}: the one-paragraph version for a phone notification or an email. */
    public record WeekPlan(LocalDate from, LocalDate to, String label, MoneyView freeThisWeek, LocalDate nextIncomeOn,
                           int daysToIncome, String headline, List<Line> bills, List<Line> slowDown, String body) {
    }

    private WeeklyPlanEngine() {
    }

    public static WeekPlan plan(LocalDate today, String currency, Cashflow.UntilNextIncome untilPay,
                                List<Cashflow.Upcoming> upcoming, BudgetEngine.Month budgets) {
        LocalDate to = today.plusDays(WEEK - 1);
        LocalDate pay = untilPay == null ? null : untilPay.nextIncomeOn();
        int daysToPay = pay == null ? WEEK : (int) Math.max(1, ChronoUnit.DAYS.between(today, pay));
        int weekDays = Math.min(WEEK, daysToPay);
        Money free = untilPay == null ? Money.zero(currency) : new Money(untilPay.free().minor(), currency);
        Money week = free.isPositive()
                ? free.scaled(weekDays, daysToPay).roundedDownTo(ROUND_TO_MINOR)
                : Money.zero(currency);

        String headline;
        if (!free.isPositive()) {
            headline = "Nothing is free to spend this week: the money until pay day is already taken by bills.";
        } else if (daysToPay <= WEEK) {
            headline = MoneyView.of(week).text() + " is free to spend until pay day on " + SHORT.format(pay) + ".";
        } else {
            headline = MoneyView.of(week).text() + " is free to spend this week, keeping the same for each week until pay day on "
                    + SHORT.format(pay) + ".";
        }

        List<Line> bills = new ArrayList<>();
        for (Cashflow.Upcoming u : upcoming) {
            if (!u.dueOn().isBefore(today) && !u.dueOn().isAfter(to)) {
                String note = DAY.format(u.dueOn()) + (u.state() == Cashflow.State.SHORT
                        ? ", the reserve is " + u.shortBy().text() + " short" : u.state() == Cashflow.State.RESERVED ? ", already put aside" : "");
                bills.add(new Line(u.name(), u.amount(), note));
            }
        }

        List<Line> slowDown = new ArrayList<>();
        int weeksLeft = Math.max(1, (budgets.daysLeft() + WEEK) / WEEK); // this week counts as one
        for (BudgetEngine.Line b : budgets.lines()) {
            if (b.state() == BudgetEngine.State.OVER) {
                slowDown.add(new Line(b.label(), new MoneyView(0, currency, Money.zero(currency).toDisplay()),
                        b.over().text() + " over its budget already, so nothing is left for it this month"));
            } else if (b.state() == BudgetEngine.State.HEADING_OVER) {
                Money perWeek = new Money(b.left().minor(), currency).scaled(1, weeksLeft).roundedDownTo(ROUND_TO_MINOR);
                slowDown.add(new Line(b.label(), MoneyView.of(perWeek), "a week keeps it inside the budget ("
                        + b.left().text() + " left, " + weeksLeft + (weeksLeft == 1 ? " week" : " weeks") + " to go)"));
            }
        }

        StringBuilder body = new StringBuilder(headline);
        if (!bills.isEmpty()) {
            body.append(" Bills this week: ");
            body.append(String.join(", ", bills.stream().map(b -> b.label() + " " + b.amount().text()).toList())).append('.');
        }
        for (Line s : slowDown) {
            body.append(' ').append(s.amount().minor() == 0
                    ? s.label() + " is over its budget." : s.label() + ": " + s.amount().text() + " this week.");
        }
        return new WeekPlan(today, to, "Week of " + SHORT.format(today), MoneyView.of(week), pay, daysToPay, headline,
                List.copyOf(bills), List.copyOf(slowDown), body.toString());
    }
}
