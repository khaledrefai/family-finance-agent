package ae.familyfinance.core.spend;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.spend.SpendSummary.CategoryLine;
import ae.familyfinance.core.spend.SpendSummary.Comparison;
import ae.familyfinance.core.spend.SpendSummary.Direction;
import ae.familyfinance.core.spend.SpendSummary.EntryView;
import ae.familyfinance.core.spend.SpendSummary.GoalLine;
import ae.familyfinance.core.spend.SpendSummary.ItemLine;
import ae.familyfinance.core.spend.SpendSummary.WeekLine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The money engine's spending maths, as plain Java with no database, so it is easy to test.
 * {@link SpendService} loads the facts and hands them in.
 */
public final class SpendSummaryCalculator {

    /** One logged purchase. */
    public record Line(UUID id, LocalDate date, String description, String merchant, SpendCategory category,
                       long minor, String loggedText, boolean possibleDuplicate) {
    }

    public record Period(LocalDate from, LocalDate to, String label, boolean monthToDate) {
        public int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    public record Goal(String name, long targetMinor, long savedMinor) {
    }

    public record Facts(UUID memberId, String displayName, String currency, Period period, List<Line> lines,
                        Period previousPeriod, List<Line> previousLines, long pocketMoneyMinor,
                        LocalDate lastLoggedOn, List<Goal> goals, Map<SpendCategory, String> blockedCategories) {
    }

    private SpendSummaryCalculator() {
    }

    public static SpendSummary summarise(Facts f) {
        String cur = f.currency();
        Money total = sum(f.lines(), cur);

        // Categories, biggest first (ties: more purchases first, then enum order, so it's stable)
        Map<SpendCategory, List<Line>> byCategory = new EnumMap<>(SpendCategory.class);
        f.lines().forEach(l -> byCategory.computeIfAbsent(l.category(), c -> new ArrayList<>()).add(l));
        List<CategoryLine> categories = byCategory.entrySet().stream()
                .map(e -> {
                    Money catTotal = sum(e.getValue(), cur);
                    return new CategoryLine(e.getKey(), e.getKey().label(), view(catTotal), e.getValue().size(),
                            catTotal.percentOf(total), view(catTotal.averageOver(e.getValue().size())));
                })
                .sorted(Comparator.comparingLong((CategoryLine c) -> c.total().minor()).reversed()
                        .thenComparing(Comparator.comparingInt(CategoryLine::count).reversed())
                        .thenComparing(CategoryLine::category))
                .toList();

        // Same thing bought again and again ("chicken wrap" x 9)
        Map<String, List<Line>> byItem = new LinkedHashMap<>();
        f.lines().forEach(l -> byItem.computeIfAbsent(normalise(l.description()), k -> new ArrayList<>()).add(l));
        List<ItemLine> topItems = byItem.values().stream()
                .map(ls -> new ItemLine(ls.get(0).description(), ls.get(0).category(), ls.size(), view(sum(ls, cur))))
                .sorted(Comparator.comparingLong((ItemLine i) -> i.total().minor()).reversed()
                        .thenComparing(Comparator.comparingInt(ItemLine::count).reversed()))
                .limit(8)
                .toList();

        EntryView biggest = f.lines().stream()
                .max(Comparator.comparingLong(Line::minor).thenComparing(Line::date))
                .map(l -> entry(l, cur, f.blockedCategories()))
                .orElse(null);

        List<WeekLine> weeks = weeks(f.period(), f.lines(), cur);
        int weeksRunning = categories.isEmpty() ? 0 : weeksRunning(weeks, categories.get(0).category(), f.period());

        Money previousTotal = sum(f.previousLines(), cur);
        Money difference = total.minus(previousTotal);
        Comparison previous = new Comparison(f.previousPeriod().label(), f.previousPeriod().from(),
                f.previousPeriod().to(), view(previousTotal), daysLogged(f.previousLines()),
                view(new Money(Math.abs(difference.minor()), cur)),
                difference.minor() > 0 ? Direction.MORE : difference.minor() < 0 ? Direction.LESS : Direction.SAME);

        List<GoalLine> goals = f.goals().stream().map(g -> {
            Money target = new Money(g.targetMinor(), cur);
            Money saved = new Money(g.savedMinor(), cur);
            Money left = target.minus(saved);
            return new GoalLine(g.name(), view(target), view(saved),
                    view(left.isNegative() ? Money.zero(cur) : left), Math.min(100, saved.percentOf(target)));
        }).toList();

        List<EntryView> againstRules = f.lines().stream()
                .filter(l -> f.blockedCategories().containsKey(l.category()))
                .sorted(Comparator.comparing(Line::date))
                .map(l -> entry(l, cur, f.blockedCategories()))
                .toList();

        Money pocketMoney = new Money(f.pocketMoneyMinor(), cur);
        return new SpendSummary(f.memberId(), f.displayName(), f.period().label(), f.period().from(),
                f.period().to(), f.period().monthToDate(), f.period().days(), view(total), f.lines().size(),
                daysLogged(f.lines()), f.lastLoggedOn(), view(pocketMoney), total.percentOf(pocketMoney),
                categories, topItems, biggest, weeks, weeksRunning, previous, goals, againstRules);
    }

    public static EntryView entry(Line l, String currency, Map<SpendCategory, String> blockedCategories) {
        return new EntryView(l.id(), l.date(), l.description(), l.merchant(), l.category(), l.category().label(),
                view(new Money(l.minor(), currency)), l.loggedText(), l.possibleDuplicate(),
                blockedCategories.get(l.category()));
    }

    /** Monday-to-Sunday weeks (the UAE week) that overlap the period, clipped to it. */
    private static List<WeekLine> weeks(Period p, List<Line> lines, String cur) {
        List<WeekLine> weeks = new ArrayList<>();
        LocalDate start = p.from();
        while (!start.isAfter(p.to())) {
            LocalDate sunday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            LocalDate end = sunday.isAfter(p.to()) ? p.to() : sunday;
            LocalDate s = start;
            List<Line> inWeek = lines.stream().filter(l -> !l.date().isBefore(s) && !l.date().isAfter(end)).toList();
            SpendCategory top = topCategory(inWeek);
            weeks.add(new WeekLine(start, end, view(sum(inWeek, cur)), top, top == null ? null : top.label()));
            start = end.plusDays(1);
        }
        return weeks;
    }

    private static SpendCategory topCategory(List<Line> lines) {
        Map<SpendCategory, Long> totals = new EnumMap<>(SpendCategory.class);
        lines.forEach(l -> totals.merge(l.category(), l.minor(), Math::addExact));
        return totals.entrySet().stream()
                .max(Map.Entry.<SpendCategory, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Counts back from the latest week. A week that is still running (today is Wednesday) only counts if it
     * already agrees; otherwise it's skipped, so two days of data can't break a streak.
     */
    private static int weeksRunning(List<WeekLine> weeks, SpendCategory category, Period p) {
        int run = 0;
        for (int i = weeks.size() - 1; i >= 0; i--) {
            WeekLine w = weeks.get(i);
            boolean stillRunning = i == weeks.size() - 1 && p.monthToDate() && w.weekEnd().getDayOfWeek() != DayOfWeek.SUNDAY;
            if (w.topCategory() != category) {
                if (stillRunning) {
                    continue;
                }
                break;
            }
            run++;
        }
        return run;
    }

    private static int daysLogged(List<Line> lines) {
        return (int) lines.stream().map(Line::date).distinct().count();
    }

    private static Money sum(List<Line> lines, String cur) {
        Money total = Money.zero(cur);
        for (Line l : lines) {
            total = total.plus(new Money(l.minor(), cur));
        }
        return total;
    }

    private static MoneyView view(Money m) {
        return MoneyView.of(m);
    }

    static String normalise(String description) {
        return Objects.requireNonNullElse(description, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
