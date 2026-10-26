package ae.familyfinance.core.plan;

import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.plan.SavingsCalculator.Input;
import ae.familyfinance.core.plan.SavingsCalculator.Projection;
import ae.familyfinance.core.plan.SavingsCalculator.YearLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsCalculatorTest {

    private static Projection project(long start, long monthly, int rateBp, int years, Long target) {
        return SavingsCalculator.project(new Input(Money.aed(start), Money.aed(monthly), rateBp, years,
                target == null ? null : Money.aed(target)));
    }

    @Test
    void shows_1_5_and_10_years_with_cash_alongside() {
        Projection p = project(0, 100_000, 500, 10, null);
        assertThat(p.marks()).extracting(YearLine::year).containsExactly(1, 5, 10);
        YearLine ten = p.marks().get(2);
        assertThat(ten.putIn().minor()).isEqualTo(12_000_000);
        assertThat(ten.asCash().minor()).isEqualTo(12_000_000);
        // Closed form 1,000 × ((1 + 0.05/12)^120 − 1) ÷ (0.05/12) = AED 155,282.28, shown to the nearest dirham
        assertThat(ten.total().minor()).isEqualTo(15_528_200L);
        assertThat(ten.total().text()).isEqualTo("AED 155,282");
        assertThat(ten.growth().minor()).isEqualTo(ten.total().minor() - 12_000_000);
        assertThat(p.everyYear()).hasSize(11);
        assertThat(p.everyYear().get(10).totalBp()).isEqualTo(BucketEngine.WHOLE);
        assertThat(p.rateText()).isEqualTo("5%");
    }

    @Test
    void at_zero_percent_the_total_is_what_went_in() {
        Projection p = project(500_000, 100_000, 0, 5, null);
        YearLine five = p.marks().get(1);
        assertThat(five.total().minor()).isEqualTo(500_000 + 60 * 100_000);
        assertThat(five.growth().minor()).isZero();
    }

    @Test
    void a_longer_horizon_adds_its_own_mark() {
        Projection p = project(0, 100_000, 500, 20, null);
        assertThat(p.marks()).extracting(YearLine::year).containsExactly(1, 5, 10, 20);
        assertThat(p.everyYear()).hasSize(21);
    }

    @Test
    void a_short_horizon_still_shows_10_years_in_the_marks() {
        Projection p = project(0, 100_000, 500, 3, null);
        assertThat(p.marks()).extracting(YearLine::year).containsExactly(1, 3, 5, 10);
        assertThat(p.everyYear()).hasSize(4);
    }

    @Test
    void the_monthly_needed_is_the_smallest_whole_dirham_that_gets_there() {
        Money target = Money.aed(15_000_000);
        Money needed = SavingsCalculator.monthlyNeeded(Money.zero("AED"), target, 500, 120);
        assertThat(needed.minor() % 100).isZero();
        assertThat(SavingsCalculator.balances(Money.zero("AED"), needed, 500, 120)[120]).isGreaterThanOrEqualTo(target);
        Money less = needed.minus(Money.aed(100));
        assertThat(SavingsCalculator.balances(Money.zero("AED"), less, 500, 120)[120]).isLessThan(target);
    }

    @Test
    void a_target_says_when_it_is_reached() {
        Projection p = project(0, 100_000, 0, 10, 1_200_000L);
        assertThat(p.reach().months()).isEqualTo(12);
        assertThat(p.reach().when()).isEqualTo("1 year");
        assertThat(p.reach().monthlyNeeded().minor()).isEqualTo(10_000); // AED 12,000 over 120 months at 0%
    }

    @Test
    void a_target_too_far_away_says_so() {
        Projection p = project(0, 100, 0, 10, 15_000_000L);
        assertThat(p.reach().months()).isNull();
        assertThat(p.reach().line()).contains("not within 50 years");
    }

    @Test
    void a_goal_with_nothing_going_in_yet_still_says_what_it_takes() {
        Projection p = project(0, 0, 500, 10, 15_000_000L);
        assertThat(p.reach().line()).startsWith("Nothing goes in each month yet. To get there in 10 years");
        assertThat(p.reach().monthlyNeeded().minor()).isPositive();
        assertThat(p.everyYear().get(10).totalBp()).isZero();
    }

    @Test
    void the_notes_never_name_a_product() {
        Projection p = project(0, 100_000, 700, 10, null);
        assertThat(String.join(" ", p.notes())).contains("not a forecast").contains("never names a fund");
    }

    @Test
    void durations_read_as_years_and_months() {
        assertThat(SavingsCalculator.duration(100)).isEqualTo("8 years 4 months");
        assertThat(SavingsCalculator.duration(13)).isEqualTo("1 year 1 month");
        assertThat(SavingsCalculator.duration(5)).isEqualTo("5 months");
    }

    @Test
    void rates_are_read_as_basis_points_and_capped() {
        assertThat(PlanService.rateBp("5")).isEqualTo(500);
        assertThat(PlanService.rateBp("4.25%")).isEqualTo(425);
        assertThat(PlanService.rateBp("6% a year")).isEqualTo(600);
        assertThat(PlanService.rateBp(null)).isEqualTo(500);
        assertThatThrownBy(() -> PlanService.rateBp("16")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanService.rateBp("-1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanService.rateBp("4.125")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanService.rateBp("lots")).isInstanceOf(ApiException.class);
    }
}
