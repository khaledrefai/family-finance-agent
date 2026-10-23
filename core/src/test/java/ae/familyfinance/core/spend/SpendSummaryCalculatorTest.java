package ae.familyfinance.core.spend;

import ae.familyfinance.core.spend.SpendSummary.Direction;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Facts;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Goal;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Line;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Period;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ae.familyfinance.core.spend.SpendCategory.EATING_OUT;
import static ae.familyfinance.core.spend.SpendCategory.IN_GAME_PURCHASES;
import static ae.familyfinance.core.spend.SpendCategory.SCHOOL_CANTEEN;
import static ae.familyfinance.core.spend.SpendCategory.SNACKS;
import static org.assertj.core.api.Assertions.assertThat;

class SpendSummaryCalculatorTest {

    // Wednesday 1 Oct 2025 .. Wednesday 22 Oct 2025
    static final Period OCT = new Period(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 22), "October 2025", true);
    static final Period SEP = new Period(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 22), "September 2025", false);

    static Line line(String date, String what, SpendCategory c, long fils) {
        return new Line(UUID.randomUUID(), LocalDate.parse(date), what, null, c, fils, null, false);
    }

    static Facts facts(List<Line> lines, List<Line> previous) {
        return new Facts(UUID.randomUUID(), "Layla", "AED", OCT, lines, SEP, previous, 15000,
                LocalDate.of(2025, 10, 22), List.of(new Goal("Headphones", 30000, 6000)),
                Map.of(IN_GAME_PURCHASES, "No in-game purchases"));
    }

    @Test
    void totals_shares_and_top_category() {
        List<Line> lines = List.of(
                line("2025-10-06", "Chicken wrap", SCHOOL_CANTEEN, 800),
                line("2025-10-07", "chicken  wrap", SCHOOL_CANTEEN, 800),
                line("2025-10-08", "Pasta", SCHOOL_CANTEEN, 700),
                line("2025-10-11", "Bubble tea", EATING_OUT, 1600),
                line("2025-10-12", "Crisps", SNACKS, 400));
        SpendSummary s = SpendSummaryCalculator.summarise(facts(lines, List.of()));

        assertThat(s.total().minor()).isEqualTo(4300);
        assertThat(s.total().text()).isEqualTo("AED 43");
        assertThat(s.purchaseCount()).isEqualTo(5);
        assertThat(s.daysLogged()).isEqualTo(5);
        assertThat(s.daysInPeriod()).isEqualTo(22);
        assertThat(s.categories().get(0).category()).isEqualTo(SCHOOL_CANTEEN);
        assertThat(s.categories().get(0).total().text()).isEqualTo("AED 23");
        assertThat(s.categories().get(0).percent()).isEqualTo(53);          // 2300 / 4300 = 53.5 -> 53
        assertThat(s.categories().get(0).averagePerPurchase().text()).isEqualTo("AED 7.67");
        // "Chicken wrap" and "chicken  wrap" are the same item
        assertThat(s.topItems().get(0).description()).isEqualTo("Chicken wrap");
        assertThat(s.topItems().get(0).count()).isEqualTo(2);
        assertThat(s.biggestPurchase().description()).isEqualTo("Bubble tea");
        assertThat(s.spentPercentOfPocketMoney()).isEqualTo(29);            // 4300 / 15000
        assertThat(s.goals().get(0).remaining().text()).isEqualTo("AED 240");
        assertThat(s.goals().get(0).percent()).isEqualTo(20);
    }

    @Test
    void weeks_run_monday_to_sunday_and_count_the_streak() {
        List<Line> lines = new ArrayList<>();
        lines.add(line("2025-10-02", "Bubble tea", EATING_OUT, 1600));     // week of 29 Sep: eating out
        lines.add(line("2025-10-06", "Wrap", SCHOOL_CANTEEN, 800));        // 6-12 Oct: canteen
        lines.add(line("2025-10-14", "Wrap", SCHOOL_CANTEEN, 800));        // 13-19 Oct: canteen
        lines.add(line("2025-10-20", "Wrap", SCHOOL_CANTEEN, 800));        // 20-22 Oct: canteen
        SpendSummary s = SpendSummaryCalculator.summarise(facts(lines, List.of()));

        assertThat(s.weeks()).hasSize(4);
        assertThat(s.weeks().get(0).weekStart()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(s.weeks().get(0).weekEnd()).isEqualTo(LocalDate.of(2025, 10, 5));
        assertThat(s.weeks().get(3).weekEnd()).isEqualTo(LocalDate.of(2025, 10, 22));
        assertThat(s.categories().get(0).category()).isEqualTo(SCHOOL_CANTEEN);
        assertThat(s.topCategoryWeeksRunning()).isEqualTo(3);
    }

    @Test
    void a_week_still_running_does_not_break_the_streak() {
        List<Line> lines = List.of(
                line("2025-10-06", "Wrap", SCHOOL_CANTEEN, 3000),
                line("2025-10-14", "Wrap", SCHOOL_CANTEEN, 3000),
                line("2025-10-21", "Bubble tea", EATING_OUT, 1600));   // this week so far (Mon 20 - Wed 22)
        SpendSummary s = SpendSummaryCalculator.summarise(facts(lines, List.of()));
        assertThat(s.topCategoryWeeksRunning()).isEqualTo(2);
    }

    @Test
    void compares_with_the_same_days_last_month() {
        SpendSummary more = SpendSummaryCalculator.summarise(facts(
                List.of(line("2025-10-06", "Wrap", SCHOOL_CANTEEN, 2500)),
                List.of(line("2025-09-08", "Wrap", SCHOOL_CANTEEN, 1000))));
        assertThat(more.previous().direction()).isEqualTo(Direction.MORE);
        assertThat(more.previous().difference().text()).isEqualTo("AED 15");
        assertThat(more.previous().daysLogged()).isEqualTo(1);

        SpendSummary less = SpendSummaryCalculator.summarise(facts(
                List.of(line("2025-10-06", "Wrap", SCHOOL_CANTEEN, 750)),
                List.of(line("2025-09-08", "Wrap", SCHOOL_CANTEEN, 1000))));
        assertThat(less.previous().direction()).isEqualTo(Direction.LESS);
        assertThat(less.previous().difference().text()).isEqualTo("AED 2.50");
    }

    @Test
    void flags_purchases_against_family_rules() {
        SpendSummary s = SpendSummaryCalculator.summarise(facts(List.of(
                line("2025-10-06", "Robux gift card", IN_GAME_PURCHASES, 2000),
                line("2025-10-07", "Wrap", SCHOOL_CANTEEN, 800)), List.of()));
        assertThat(s.againstFamilyRules()).singleElement()
                .satisfies(e -> {
                    assertThat(e.description()).isEqualTo("Robux gift card");
                    assertThat(e.familyRuleNote()).isEqualTo("No in-game purchases");
                });
    }

    @Test
    void nothing_logged_is_not_an_error() {
        SpendSummary s = SpendSummaryCalculator.summarise(facts(List.of(), List.of()));
        assertThat(s.total().text()).isEqualTo("AED 0");
        assertThat(s.categories()).isEmpty();
        assertThat(s.biggestPurchase()).isNull();
        assertThat(s.topCategoryWeeksRunning()).isZero();
        assertThat(s.previous().direction()).isEqualTo(Direction.SAME);
    }
}
