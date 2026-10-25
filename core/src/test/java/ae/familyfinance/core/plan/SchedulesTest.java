package ae.familyfinance.core.plan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulesTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void the_31st_stays_the_31st_after_february() {
        assertThat(Schedules.dates(d("2027-01-31"), Frequency.MONTHLY, null, d("2027-01-01"), d("2027-04-30")))
                .containsExactly(d("2027-01-31"), d("2027-02-28"), d("2027-03-31"), d("2027-04-30"));
    }

    @Test
    void a_loan_stops_after_its_remaining_payments() {
        assertThat(Schedules.dates(d("2026-11-16"), Frequency.MONTHLY, 2, d("2026-10-25"), d("2027-12-31")))
                .containsExactly(d("2026-11-16"), d("2026-12-16"));
    }

    @Test
    void custom_dates_are_not_generated() {
        assertThat(Schedules.dates(d("2026-11-16"), Frequency.CUSTOM, null, d("2026-10-25"), d("2027-12-31"))).isEmpty();
    }

    @Test
    void a_one_off_in_the_past_has_no_next_date() {
        assertThat(Schedules.nextOnOrAfter(d("2026-09-01"), Frequency.ONCE, null, d("2026-10-25"))).isNull();
    }

    @Test
    void day_31_in_february_is_the_last_day() {
        assertThat(Schedules.dayOf(YearMonth.of(2028, 2), 31)).isEqualTo(d("2028-02-29"));
    }

    @Test
    void the_next_statement_is_this_month_unless_it_has_passed() {
        assertThat(Schedules.nextStatement(d("2026-10-25"), 26)).isEqualTo(d("2026-10-26"));
        assertThat(Schedules.nextStatement(d("2026-10-25"), 25)).isEqualTo(d("2026-10-25"));
        assertThat(Schedules.nextStatement(d("2026-10-25"), 5)).isEqualTo(d("2026-11-05"));
        assertThat(Schedules.previousStatement(d("2026-10-25"), 5)).isEqualTo(d("2026-10-05"));
    }

    @Test
    void the_due_date_is_the_first_due_day_after_the_statement() {
        assertThat(Schedules.dueAfter(d("2026-10-26"), 13)).isEqualTo(d("2026-11-13"));
        assertThat(Schedules.dueAfter(d("2026-10-02"), 27)).isEqualTo(d("2026-10-27"));
    }
}
