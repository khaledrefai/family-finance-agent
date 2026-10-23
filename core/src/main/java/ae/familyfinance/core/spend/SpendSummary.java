package ae.familyfinance.core.spend;

import ae.familyfinance.core.money.MoneyView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the agent may say about one member's spending in one period. Every number is worked out
 * here by the money engine (see {@link SpendSummaryCalculator}); the agent only puts it into words.
 */
public record SpendSummary(UUID memberId,
                           String displayName,
                           String periodLabel,          // "October 2026"
                           LocalDate from,
                           LocalDate to,
                           boolean monthToDate,         // true while the month is still running
                           int daysInPeriod,
                           MoneyView total,
                           int purchaseCount,
                           int daysLogged,              // days with at least one logged purchase
                           LocalDate lastLoggedOn,      // across all time, null if never
                           MoneyView pocketMoney,       // pocket money paid into the demo wallet in the period
                           int spentPercentOfPocketMoney,
                           List<CategoryLine> categories,  // biggest first
                           List<ItemLine> topItems,         // biggest first, at most 8
                           EntryView biggestPurchase,       // null if nothing was logged
                           List<WeekLine> weeks,            // oldest first
                           int topCategoryWeeksRunning,     // latest weeks in a row where categories[0] was the top
                           Comparison previous,
                           List<GoalLine> goals,
                           List<EntryView> againstFamilyRules) {

    public record CategoryLine(SpendCategory category, String label, MoneyView total, int count, int percent,
                               MoneyView averagePerPurchase) {
    }

    public record ItemLine(String description, SpendCategory category, int count, MoneyView total) {
    }

    public record WeekLine(LocalDate weekStart, LocalDate weekEnd, MoneyView total, SpendCategory topCategory,
                           String topCategoryLabel) {
    }

    /** The same stretch of the previous month (1st to the same day), or the whole previous month. */
    public record Comparison(String periodLabel, LocalDate from, LocalDate to, MoneyView total, int daysLogged,
                             MoneyView difference, Direction direction) {
    }

    public enum Direction { MORE, LESS, SAME }

    public record GoalLine(String name, MoneyView target, MoneyView saved, MoneyView remaining, int percent) {
    }

    public record EntryView(UUID id, LocalDate occurredOn, String description, String merchant,
                            SpendCategory category, String categoryLabel, MoneyView amount, String loggedText,
                            boolean possibleDuplicate, String familyRuleNote) {
    }
}
