package ae.familyfinance.core.summary;

import ae.familyfinance.core.advice.NumberCheck;
import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.budgets.BudgetEngine.Budget;
import ae.familyfinance.core.spend.SpendCategory;
import ae.familyfinance.core.spend.SpendSummary;
import ae.familyfinance.core.spend.SpendSummaryCalculator;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Line;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Period;
import ae.familyfinance.core.summary.MonthSummaryEngine.Facts;
import ae.familyfinance.core.summary.MonthSummaryEngine.GoalFact;
import ae.familyfinance.core.summary.MonthSummaryEngine.Kid;
import ae.familyfinance.core.summary.MonthSummaryEngine.KidFact;
import ae.familyfinance.core.summary.MonthSummaryEngine.Parents;
import ae.familyfinance.core.summary.MonthSummaryEngine.Point;
import ae.familyfinance.core.summary.MonthSummaryEngine.Requests;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MonthSummaryEngineTest {

    static final YearMonth OCT = YearMonth.of(2026, 10);
    static final LocalDate OCT_26 = LocalDate.of(2026, 10, 26);
    static final UUID LAYLA = UUID.randomUUID();
    static final UUID ADAM = UUID.randomUUID();

    static final GoalFact TRIP = new GoalFact("Summer trip", "Good life", 360_000, 1_200_000, 150_000L,
            LocalDate.of(2027, 6, 30), "March 2027");
    static final GoalFact BUFFER = new GoalFact("Emergency buffer", "Survival", 1_000_000, 2_500_000, 500_000L, null,
            "December 2026");

    /** A kid who logged one canteen lunch on each of {@code days} days, with a goal {@code percent} full. */
    static SpendSummary spend(UUID id, String name, LocalDate to, int days, int percent, Map<SpendCategory, String> blocked,
                              Line... extra) {
        List<Line> lines = new ArrayList<>();
        for (int d = 1; d <= days; d++) {
            lines.add(new Line(UUID.randomUUID(), to.withDayOfMonth(d), "Lunch", "Canteen", SpendCategory.SCHOOL_CANTEEN,
                    800, null, false));
        }
        lines.addAll(List.of(extra));
        Period p = new Period(to.withDayOfMonth(1), to, "October 2026", true);
        Period prev = new Period(to.withDayOfMonth(1).minusMonths(1), to.minusMonths(1), "September 2026", false);
        return SpendSummaryCalculator.summarise(new SpendSummaryCalculator.Facts(id, name, "AED", p, lines, prev, List.of(),
                40_000, to, percent < 0 ? List.of() : List.of(new SpendSummaryCalculator.Goal("Goal", 10_000, percent * 100L)),
                blocked));
    }

    static KidFact kid(UUID id, String name, int days, int percent, int stars, Requests r, Line... extra) {
        return new KidFact(id, name, false, spend(id, name, OCT_26, days, percent,
                Map.of(SpendCategory.SUGARY_DRINKS, "No fizzy drinks"), extra), stars, stars + 10, r);
    }

    static BudgetEngine.Month budgets(LocalDate today, long funSpent) {
        return BudgetEngine.month(List.of(
                new Budget("GROCERIES", "Groceries", 300_000, 61_250, List.of()),
                new Budget("ENTERTAINMENT", "Entertainment", 120_000, funSpent, List.of())), today, "AED");
    }

    static Parents october(List<KidFact> kids, long funSpent) {
        return MonthSummaryEngine.summarise(new Facts(OCT, true, OCT_26, "AED", budgets(OCT_26, funSpent),
                List.of(BUFFER, TRIP), kids));
    }

    static final Line COLA = new Line(UUID.randomUUID(), OCT_26.minusDays(3), "Cola", "Shop", SpendCategory.SUGARY_DRINKS,
            300, null, false);

    @Test
    void a_running_month_says_so_far_and_puts_the_overspend_first_to_watch() {
        Parents p = october(List.of(kid(LAYLA, "Layla", 20, 40, 40, Requests.none())), 156_000);
        assertThat(p.periodLabel()).isEqualTo("1 to 26 October");
        assertThat(p.headline().text()).isEqualTo("October so far: AED 2,172.50 of the AED 4,200 family budgets spent, "
                                                 + "1 of 2 budgets within the limit, and the summer trip 30% saved.");
        assertThat(p.toWatch().get(0).text())
                .isEqualTo("Entertainment is AED 360 over its AED 1,200 budget (130%) with 5 days to go.");
        assertThat(p.wentWell()).extracting(Point::key).containsExactly("budgets-within", "goal", "kept-track");
        assertThat(p.wentWell().get(0).text()).isEqualTo("1 of 2 family budgets are within their limit: groceries.");
        // The trip has a date, so it is the goal to talk about, even though the buffer comes first in the plan
        assertThat(p.wentWell().get(1).text())
                .isEqualTo("The summer trip is 30% saved: AED 3,600 of AED 12,000, full by March 2027 on the plan.");
        assertThat(p.needsWords()).isTrue();
    }

    @Test
    void a_finished_month_reads_in_the_past() {
        LocalDate end = OCT.atEndOfMonth();
        Parents p = MonthSummaryEngine.summarise(new Facts(OCT, false, end, "AED", budgets(end, 156_000), List.of(TRIP),
                List.of()));
        assertThat(p.periodLabel()).isEqualTo("October 2026");
        assertThat(p.headline().text()).startsWith("October: ");
        assertThat(p.toWatch().get(0).text()).isEqualTo("Entertainment finished AED 360 over its AED 1,200 budget (130%).");
        assertThat(p.wentWell().get(0).text()).isEqualTo("1 of 2 family budgets stayed within their limit: groceries.");
    }

    @Test
    void every_budget_within_and_nothing_to_watch() {
        Parents p = october(List.of(), 50_000);
        assertThat(p.wentWell().get(0).text()).isEqualTo("Every family budget is within its limit so far.");
        assertThat(p.toWatch()).isEmpty();
        assertThat(p.story().text()).endsWith("Nothing to watch this month.");
    }

    @Test
    void saver_of_the_month_is_furthest_to_their_own_goal_and_a_tie_is_shared() {
        Parents p = october(List.of(kid(LAYLA, "Layla", 20, 40, 40, Requests.none()),
                kid(ADAM, "Adam", 15, 45, 25, Requests.none())), 0);
        assertThat(p.saver().winners()).containsExactly("Adam");
        assertThat(p.saver().race()).extracting(MonthSummaryEngine.Runner::name).containsExactly("Adam", "Layla");
        assertThat(p.kidPages().get(1).saverOfTheMonth()).isTrue();
        assertThat(p.kidPages().get(0).highlights()).contains("Adam is Saver of the Month this time. You can catch up next month.");

        Parents tie = october(List.of(kid(LAYLA, "Layla", 20, 45, 40, Requests.none()),
                kid(ADAM, "Adam", 15, 45, 25, Requests.none())), 0);
        assertThat(tie.saver().winners()).containsExactly("Adam", "Layla");
        assertThat(tie.kidPages().get(0).highlights()).contains("You share Saver of the Month.");

        assertThat(october(List.of(kid(LAYLA, "Layla", 20, -1, 40, Requests.none())), 0).saver()).isNull();
    }

    @Test
    void rules_working_patchy_logging_and_waiting_requests() {
        Requests adams = new Requests(3, 1, 0, 1, 1, List.of("Peanut butter cups"));
        Parents p = october(List.of(kid(ADAM, "Adam", 4, 45, 25, adams, COLA)), 0);
        assertThat(p.wentWell()).extracting(Point::text)
                .contains("Family rules stopped 1 request before it reached you: Adam's Peanut butter cups.");
        assertThat(p.toWatch()).extracting(Point::text).contains(
                "Adam logged 1 thing the family rules say no to: cola. Worth a calm chat.",
                "Adam logged spending on only 5 of 26 days, so the picture of where the pocket money went is patchy.",
                "1 of the kids' requests is still waiting for a parent.");

        Kid adam = p.kidPages().get(0);
        assertThat(adam.highlights()).contains(
                "You asked for 3 things and your parents said yes to 1.",
                "Thanks for being honest about the cola. It is on the family's no list, so let's skip it next month.");
    }

    @Test
    void every_number_in_the_engines_own_words_is_in_its_facts_and_kids_facts_hold_no_family_money() {
        Requests r = new Requests(2, 2, 0, 0, 0, List.of());
        Parents p = october(List.of(kid(LAYLA, "Layla", 20, 40, 40, r), kid(ADAM, "Adam", 4, 45, 25, Requests.none(), COLA)),
                156_000);
        for (MonthSummaryEngine.Writing w : p.writing()) {
            assertThat(NumberCheck.ok(w.text(), w.facts())).as(w.key() + ": " + w.text()).isTrue();
        }
        for (Kid k : p.kidPages()) {
            String kidFacts = String.join(" | ", k.camel().facts()) + String.join(" | ", k.highlights());
            assertThat(kidFacts).doesNotContain("1,200", "4,200", "3,600", "12,000", "Entertainment", "budget");
            assertThat(NumberCheck.ok("The family spent AED 2,172.50", k.camel().facts())).isFalse();
        }
        assertThat(p.kidPages().get(0).highlights()).contains("Your parents said yes to all 2 of your requests.");
    }

    @Test
    void names_keep_brands_and_short_capitals() {
        assertThat(MonthSummaryEngine.lower("Summer trip")).isEqualTo("summer trip");
        assertThat(MonthSummaryEngine.lower("Robux gift card")).isEqualTo("Robux gift card");
        assertThat(MonthSummaryEngine.lower("DEWA bill")).isEqualTo("DEWA bill");
        assertThat(MonthSummaryEngine.names(List.of("a", "b", "c"))).isEqualTo("a, b and c");
    }
}
