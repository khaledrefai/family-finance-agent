package ae.familyfinance.core.summary;

import ae.familyfinance.core.budgets.BudgetEngine;
import ae.familyfinance.core.budgets.BudgetEngine.Line;
import ae.familyfinance.core.budgets.BudgetEngine.State;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.spend.SpendSummary;
import ae.familyfinance.core.spend.SpendSummary.GoalLine;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The monthly family summary (study: "monthly family summary", the closing shot of the demo): one page for the parents
 * with the month's budgets, goals, each kid's month, Saver of the Month, what went well and what to watch, and a
 * playful page for each kid without the family's money.
 *
 * <p>Pure: the service loads the month. The engine picks what goes in and writes its own plain sentences; each piece
 * the agent may reword comes with its {@code facts}, the only numbers the agent may use. Kids' facts never hold a
 * family figure (budgets, bills, income), so the agent can't put one on a kid's page either.
 */
public final class MonthSummaryEngine {

    static final int MAX_POINTS = 4;
    /** A kid "kept track" when they logged on at least half the days of the month so far... */
    static final int LOGGED_WELL_PERCENT = 50;
    /** ...and the picture is patchy below a third of the days (only said once a week has gone by). */
    static final int LOGGED_PATCHY_PERCENT = 33;

    public record Row(String label, String value) {
    }

    /** A family goal as it stands today. {@code fullBy}: the month the plan fills it ("June 2027"), or null. */
    public record GoalFact(String name, String tierLabel, long savedMinor, long targetMinor, Long monthlyMinor,
                           LocalDate targetDate, String fullBy) {
    }

    /** A kid's requests made in the month. {@code blockedTitles}: what a family rule stopped. */
    public record Requests(int asked, int approved, int declined, int blocked, int waiting, List<String> blockedTitles) {

        public static Requests none() {
            return new Requests(0, 0, 0, 0, 0, List.of());
        }
    }

    /** One kid's month: what they logged (from the spend engine), their stars, their requests. */
    public record KidFact(UUID id, String name, boolean young, SpendSummary spend, int starsThisMonth, int starsTotal,
                          Requests requests) {
    }

    public record Facts(YearMonth month, boolean running, LocalDate to, String currency, BudgetEngine.Month budgets,
                        List<GoalFact> goals, List<KidFact> kids) {
    }

    /** A sentence on the summary. {@code facts}: the numbers behind it, for the agent and the "Why this?" sheet. */
    public record Point(String key, String text, List<String> facts, List<Row> why) {
    }

    public record FamilyGoalLine(String name, String tierLabel, MoneyView saved, MoneyView target, int percent,
                            MoneyView monthly, String fullBy) {
    }

    /** A kid as the parents see them. {@code goal*} are null when the kid has no goal yet. */
    public record KidLine(UUID id, String name, MoneyView spent, MoneyView pocketMoney, int spentPercent, int daysLogged,
                          int daysInPeriod, String topCategory, String goalName, MoneyView goalSaved,
                          MoneyView goalTarget, Integer goalPercent, int starsThisMonth, Requests requests,
                          List<String> againstRules) {
    }

    public record Runner(UUID id, String name, int percent) {
    }

    /** Saver of the Month: furthest toward their own goal, so ages and pocket money don't matter. */
    public record Saver(List<String> winners, int percent, List<Runner> race) {
    }

    /**
     * Words the agent may write: what they are about, the text, who wrote it (ENGINE: the engine's own sentence;
     * LLM: Claude; RULES: the agent's template), and the only numbers allowed in it.
     */
    public record Writing(String key, String about, String text, String writtenBy, List<String> facts) {

        Writing(String key, String about, String text, List<String> facts) {
            this(key, about, text, ENGINE, facts);
        }

        public Writing withWords(String text, String writtenBy) {
            return new Writing(key, about, text, writtenBy, facts);
        }
    }

    public static final String ENGINE = "ENGINE";

    /** A month the summary can show: "2026-10", "October 2026". */
    public record MonthOption(String month, String label) {
    }

    /**
     * The parents' page. {@code kidPages} are what each kid sees. {@code needsWords}: some words are still the
     * engine's own, so the agent has something to write.
     */
    public record Parents(String month, String monthLabel, String periodLabel, boolean running, LocalDate from,
                          LocalDate to, MoneyView budgetSpent, MoneyView budgetLimit, int budgetsWithin,
                          List<Line> budgets, List<FamilyGoalLine> goals, List<KidLine> kids, Saver saver,
                          List<Point> wentWell, List<Point> toWatch, Writing headline, Writing story,
                          List<Kid> kidPages, List<MonthOption> months, boolean needsWords) {

        /** Every piece the agent may write, in one list. */
        public List<Writing> writing() {
            List<Writing> all = new ArrayList<>(List.of(headline, story));
            kidPages.forEach(k -> all.add(k.camel()));
            return all;
        }

        /** The same page with stored words (where they still match the facts) and the months to pick from. */
        public Parents with(java.util.function.UnaryOperator<Writing> words, List<MonthOption> monthOptions) {
            Writing h = words.apply(headline);
            Writing s = words.apply(story);
            List<Kid> pages = kidPages.stream().map(k -> k.withCamel(words.apply(k.camel()))).toList();
            boolean needs = ENGINE.equals(h.writtenBy()) || ENGINE.equals(s.writtenBy())
                            || pages.stream().anyMatch(k -> ENGINE.equals(k.camel().writtenBy()));
            return new Parents(month, monthLabel, periodLabel, running, from, to, budgetSpent, budgetLimit, budgetsWithin,
                    budgets, goals, kids, saver, wentWell, toWatch, h, s, pages, List.copyOf(monthOptions), needs);
        }
    }

    /** A family goal on a kid's page: its name and how far along it is, never the amounts. */
    public record FamilyGoal(String name, int percent) {
    }

    /** One kid's page. Their own goal and stars; no family money anywhere. */
    public record Kid(UUID id, String name, boolean young, String month, String monthLabel, String periodLabel,
                      boolean running, String goalName, MoneyView goalSaved, MoneyView goalTarget, Integer goalPercent,
                      int starsThisMonth, int starsTotal, int daysLogged, int daysInPeriod, int asked, int yes,
                      Saver saver, boolean saverOfTheMonth, FamilyGoal familyGoal, List<String> highlights,
                      Writing camel) {

        Kid withCamel(Writing words) {
            return new Kid(id, name, young, month, monthLabel, periodLabel, running, goalName, goalSaved, goalTarget,
                    goalPercent, starsThisMonth, starsTotal, daysLogged, daysInPeriod, asked, yes, saver,
                    saverOfTheMonth, familyGoal, highlights, words);
        }
    }

    private MonthSummaryEngine() {
    }

    public static Parents summarise(Facts f) {
        String cur = f.currency();
        String monthLabel = f.month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String period = periodLabel(f);
        BudgetEngine.Month b = f.budgets();

        List<Line> within = b.lines().stream().filter(MonthSummaryEngine::within).toList();
        List<FamilyGoalLine> goals = f.goals().stream().map(g -> goalLine(g, cur)).toList();
        List<KidLine> kids = f.kids().stream().map(MonthSummaryEngine::kidLine).toList();
        Saver saver = saver(f.kids());
        FamilyGoal familyGoal = spineGoal(f.goals()).map(g -> new FamilyGoal(g.name(), percent(g))).orElse(null);

        List<Point> wentWell = wentWell(f, within, cur);
        List<Point> toWatch = toWatch(f, cur);

        // The headline: the budgets in one line, and the family's main goal
        List<String> headFacts = new ArrayList<>();
        headFacts.add("Period: " + period);
        headFacts.add("Family budgets: " + b.spent().text() + " spent of " + b.limit().text());
        headFacts.add(within.size() + " of " + b.lines().size() + " budgets within their limit");
        StringBuilder head = new StringBuilder(f.running() ? monthLabel + " so far: " : monthLabel + ": ");
        if (b.lines().isEmpty()) {
            head.append("no family budgets set yet");
        } else {
            head.append(b.spent().text()).append(" of the ").append(b.limit().text()).append(" family budgets spent, ")
                    .append(within.size() == b.lines().size() ? "every budget" : within.size() + " of " + b.lines().size() + " budgets")
                    .append(" within the limit");
        }
        if (familyGoal != null) {
            GoalFact g = spineGoal(f.goals()).orElseThrow();
            headFacts.add(g.name() + ": " + familyGoal.percent() + "% saved");
            head.append(", and the ").append(lower(g.name())).append(" ").append(familyGoal.percent()).append("% saved");
        }
        head.append(".");

        // The story: the facts of everything on the page, so the agent can say which matters most
        List<String> storyFacts = new ArrayList<>(headFacts);
        wentWell.forEach(p -> storyFacts.addAll(p.facts()));
        toWatch.forEach(p -> storyFacts.addAll(p.facts()));
        if (saver != null) {
            storyFacts.add("Saver of the Month: " + String.join(" and ", saver.winners()) + ", " + saver.percent()
                           + "% of the way to their own goal");
        }
        String story = (wentWell.isEmpty() ? "" : wentWell.get(0).text())
                       + (toWatch.isEmpty() ? (wentWell.isEmpty() ? "A quiet month: nothing to watch." : " Nothing to watch this month.")
                                            : (wentWell.isEmpty() ? "" : " ") + "To watch: " + toWatch.get(0).text());

        List<Kid> kidPages = f.kids().stream().map(k -> kid(f, k, monthLabel, period, saver, familyGoal)).toList();
        return new Parents(f.month().toString(), monthLabel, period, f.running(), f.month().atDay(1), f.to(), b.spent(),
                b.limit(), within.size(), b.lines(), goals, kids, saver, wentWell, toWatch,
                new Writing("headline", "One line for the top of the parents' summary", head.toString(), List.copyOf(headFacts)),
                new Writing("story", "Two or three sentences for the parents: what went well, what to watch",
                        story.strip(), List.copyOf(storyFacts)),
                kidPages, List.of(), true);
    }

    // ------------------------------------------------------------------ the parents' points

    private static List<Point> wentWell(Facts f, List<Line> within, String cur) {
        List<Point> out = new ArrayList<>();
        List<Line> lines = f.budgets().lines();
        if (!lines.isEmpty() && !within.isEmpty()) {
            List<Row> why = within.stream().map(l -> new Row(l.label(), l.spent().text() + " of " + l.limit().text())).toList();
            List<String> facts = within.stream()
                    .map(l -> l.label() + ": " + l.spent().text() + " of " + l.limit().text() + " (" + l.percent() + "%)").toList();
            String text = within.size() == lines.size()
                    ? (f.running() ? "Every family budget is within its limit so far." : "Every family budget stayed within its limit.")
                    : within.size() + " of " + lines.size() + " family budgets " + (f.running() ? "are" : "stayed")
                      + " within their limit: " + names(within.stream().map(l -> lower(l.label())).toList()) + ".";
            out.add(new Point("budgets-within", text, withCount(facts, within.size(), lines.size()), why));
        }

        spineGoal(f.goals()).filter(g -> g.fullBy() != null && g.savedMinor() > 0).ifPresent(g -> {
            Money saved = new Money(g.savedMinor(), cur);
            Money target = new Money(g.targetMinor(), cur);
            int pct = percent(g);
            String text = "The " + lower(g.name()) + " is " + pct + "% saved: " + saved.toDisplay() + " of "
                          + target.toDisplay() + ", full by " + g.fullBy() + " on the plan.";
            List<Row> why = new ArrayList<>(List.of(new Row("Saved", saved.toDisplay()), new Row("Target", target.toDisplay())));
            if (g.monthlyMinor() != null) {
                why.add(new Row("Each month", new Money(g.monthlyMinor(), cur).toDisplay()));
            }
            why.add(new Row("Full by", g.fullBy()));
            out.add(new Point("goal", text, List.of(g.name() + ": " + saved.toDisplay() + " of " + target.toDisplay()
                                                    + " (" + pct + "%), full by " + g.fullBy()), why));
        });

        List<KidFact> keptTrack = f.kids().stream().filter(k -> k.spend().daysInPeriod() > 0
                && k.spend().daysLogged() * 100 >= LOGGED_WELL_PERCENT * k.spend().daysInPeriod()).toList();
        if (!keptTrack.isEmpty()) {
            int days = keptTrack.get(0).spend().daysInPeriod();
            List<String> parts = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            List<Row> why = new ArrayList<>();
            for (int i = 0; i < keptTrack.size(); i++) {
                KidFact k = keptTrack.get(i);
                parts.add(i == 0 ? k.name() + " logged spending on " + k.spend().daysLogged() + " of " + days + " days"
                                 : k.name() + " on " + k.spend().daysLogged());
                facts.add(k.name() + " logged spending on " + k.spend().daysLogged() + " of " + days + " days");
                why.add(new Row(k.name(), k.spend().daysLogged() + " of " + days + " days"));
            }
            out.add(new Point("kept-track", names(parts) + ".", facts, why));
        }

        List<String> stopped = new ArrayList<>();
        for (KidFact k : f.kids()) {
            k.requests().blockedTitles().forEach(t -> stopped.add(k.name() + "'s " + t));
        }
        if (!stopped.isEmpty()) {
            int n = stopped.size();
            out.add(new Point("rules-worked", "Family rules stopped " + n + (n == 1 ? " request" : " requests")
                    + " before " + (n == 1 ? "it" : "they") + " reached you: " + names(stopped) + ".",
                    List.of(n + " requests blocked by a family rule"),
                    stopped.stream().map(s -> new Row(s, "Blocked")).toList()));
        }
        return out.stream().limit(MAX_POINTS).toList();
    }

    private static List<Point> toWatch(Facts f, String cur) {
        List<Point> out = new ArrayList<>();
        int daysLeft = f.budgets().daysLeft();
        String togo = daysLeft == 0 ? "" : " with " + daysLeft + (daysLeft == 1 ? " day" : " days") + " to go";
        List<Line> alerts = f.budgets().alerts();
        for (Line l : alerts) {
            List<Row> why = new ArrayList<>(List.of(new Row("Budget", l.limit().text()), new Row("Spent", l.spent().text())));
            String text;
            String fact;
            switch (l.state()) {
                case OVER -> {
                    text = l.label() + (f.running() ? " is " : " finished ") + l.over().text() + " over its "
                           + l.limit().text() + " budget (" + l.percent() + "%)" + (f.running() ? togo : "") + ".";
                    fact = l.label() + ": " + l.spent().text() + " of " + l.limit().text() + " (" + l.percent() + "%), "
                           + l.over().text() + " over" + (f.running() && daysLeft > 0 ? ", " + daysLeft + " days to go" : "");
                    why.add(new Row("Over by", l.over().text()));
                }
                case HEADING_OVER -> {
                    text = l.alert();
                    fact = l.label() + ": " + l.spent().text() + " of " + l.limit().text() + " (" + l.percent()
                           + "%), at this pace about " + new Money(l.atThisPace().minor(), cur).roundedUpTo(1000).toDisplay()
                           + ", " + daysLeft + " days to go";
                    why.add(new Row("At this pace", new Money(l.atThisPace().minor(), cur).roundedUpTo(1000).toDisplay()));
                }
                default -> {
                    String usual = new Money(l.usual().minor(), cur).roundedUpTo(1000).toDisplay();
                    text = l.label() + (f.running() ? " is already " : " came to ") + l.spent().text()
                           + ", above its usual month of about " + usual + ".";
                    fact = l.label() + ": " + l.spent().text() + ", usual month about " + usual;
                    why.add(new Row("Usual month", usual));
                }
            }
            out.add(new Point("budget-" + l.category().toLowerCase(Locale.ROOT), text, List.of(fact), why));
        }

        for (KidFact k : f.kids()) {
            List<String> items = new ArrayList<>(new LinkedHashSet<>(
                    k.spend().againstFamilyRules().stream().map(SpendSummary.EntryView::description).toList()));
            if (!items.isEmpty()) {
                int n = k.spend().againstFamilyRules().size();
                out.add(new Point("rules-" + k.name().toLowerCase(Locale.ROOT),
                        k.name() + " logged " + n + (n == 1 ? " thing" : " things") + " the family rules say no to: "
                        + names(items.stream().map(MonthSummaryEngine::lower).toList()) + ". Worth a calm chat.",
                        List.of(k.name() + " logged " + n + " things against family rules: " + String.join(", ", items)),
                        k.spend().againstFamilyRules().stream()
                                .map(e -> new Row(e.description(), e.familyRuleNote() == null ? "Against a family rule" : e.familyRuleNote()))
                                .toList()));
            }
            int days = k.spend().daysInPeriod();
            if (days >= 7 && k.spend().daysLogged() * 100 < LOGGED_PATCHY_PERCENT * days) {
                out.add(new Point("patchy-" + k.name().toLowerCase(Locale.ROOT),
                        k.name() + " logged spending on only " + k.spend().daysLogged() + " of " + days
                        + " days, so the picture of where the pocket money went is patchy.",
                        List.of(k.name() + " logged spending on " + k.spend().daysLogged() + " of " + days + " days"),
                        List.of(new Row("Days logged", k.spend().daysLogged() + " of " + days))));
            }
        }

        int waiting = f.kids().stream().mapToInt(k -> k.requests().waiting()).sum();
        if (waiting > 0) {
            out.add(new Point("waiting", waiting + (waiting == 1 ? " of the kids' requests is" : " of the kids' requests are")
                    + " still waiting for a parent.", List.of(waiting + " requests waiting"),
                    List.of(new Row("Waiting", String.valueOf(waiting)))));
        }

        for (GoalFact g : f.goals()) {
            if (g.monthlyMinor() != null && g.monthlyMinor() > 0 && g.fullBy() == null && g.savedMinor() < g.targetMinor()) {
                out.add(new Point("goal-slow-" + g.name().toLowerCase(Locale.ROOT).replace(' ', '-'),
                        "At today's pace the " + lower(g.name()) + " is not full within a year.",
                        List.of(g.name() + ": " + new Money(g.savedMinor(), cur).toDisplay() + " of "
                                + new Money(g.targetMinor(), cur).toDisplay() + ", not full within 12 months"),
                        List.of(new Row("Saved", new Money(g.savedMinor(), cur).toDisplay()),
                                new Row("Target", new Money(g.targetMinor(), cur).toDisplay()))));
            }
        }
        return out.stream().limit(MAX_POINTS).toList();
    }

    // ------------------------------------------------------------------ a kid's page

    private static Kid kid(Facts f, KidFact k, String monthLabel, String period, Saver saver, FamilyGoal familyGoal) {
        GoalLine goal = k.spend().goals().isEmpty() ? null : k.spend().goals().get(0);
        boolean won = saver != null && saver.winners().contains(k.name());
        int yes = k.requests().approved();
        int asked = k.requests().asked();
        int days = k.spend().daysInPeriod();
        int logged = k.spend().daysLogged();

        // Kid-sized sentences, warm, never scolding. Facts are the kid's own: no family money.
        List<String> highlights = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        facts.add("Month: " + monthLabel);
        if (logged > 0) {
            highlights.add("You told me what you bought on " + logged + " of " + days + " days. Thank you.");
            facts.add("Days logged: " + logged + " of " + days);
        }
        if (k.starsThisMonth() > 0) {
            highlights.add("You earned " + k.starsThisMonth() + (k.starsThisMonth() == 1 ? " star." : " stars."));
            facts.add("Stars this month: " + k.starsThisMonth() + ", stars in all: " + k.starsTotal());
        }
        if (goal != null) {
            highlights.add("Your " + lower(goal.name()) + " jar is " + goal.percent() + "% full.");
            facts.add("Own goal " + goal.name() + ": " + goal.percent() + "% full, " + goal.saved().text() + " of "
                      + goal.target().text());
        }
        if (asked > 0) {
            highlights.add(yes == asked
                    ? "Your parents said yes to " + (asked == 1 ? "your request." : "all " + asked + " of your requests.")
                    : "You asked for " + asked + (asked == 1 ? " thing" : " things") + " and your parents said yes to " + yes + ".");
            facts.add("Requests: " + asked + " asked, " + yes + " yes");
        }
        List<String> noList = new ArrayList<>(new LinkedHashSet<>(
                k.spend().againstFamilyRules().stream().map(e -> lower(e.description())).toList()));
        if (!noList.isEmpty()) {
            highlights.add("Thanks for being honest about the " + names(noList)
                           + ". " + (noList.size() == 1 ? "It is" : "They are") + " on the family's no list, so let's skip "
                           + (noList.size() == 1 ? "it" : "them") + " next month.");
            facts.add("Things on the family's no list: " + String.join(", ", noList));
        }
        if (saver != null) {
            highlights.add(won ? (saver.winners().size() > 1 ? "You share Saver of the Month." : "You are Saver of the Month.")
                               : String.join(" and ", saver.winners()) + " is Saver of the Month this time. You can catch up next month.");
            facts.add("Saver of the Month: " + String.join(" and ", saver.winners()));
        }
        if (familyGoal != null) {
            highlights.add("The family is " + familyGoal.percent() + "% of the way to the " + lower(familyGoal.name()) + ".");
            facts.add("Family goal " + familyGoal.name() + ": " + familyGoal.percent() + "%");
        }

        String camel;
        if (won) {
            camel = k.name() + ", what a month. You are Saver of the Month"
                    + (goal == null ? "." : ", and your " + lower(goal.name()) + " jar is " + goal.percent() + "% full.");
        } else if (goal != null && goal.percent() > 0) {
            camel = k.name() + ", your " + lower(goal.name()) + " jar is " + goal.percent() + "% full. Every dirham you keep gets it closer.";
        } else if (logged > 0) {
            camel = k.name() + ", thank you for telling me what you bought. Want to pick a goal to save for next month?";
        } else {
            camel = k.name() + ", a new month is a fresh start. Tell me what you buy, and we can save for something you love.";
        }
        if (!won && k.starsThisMonth() > 0) {
            camel += " You earned " + k.starsThisMonth() + (k.starsThisMonth() == 1 ? " star." : " stars.");
        }
        return new Kid(k.id(), k.name(), k.young(), f.month().toString(), monthLabel, period, f.running(),
                goal == null ? null : goal.name(), goal == null ? null : goal.saved(), goal == null ? null : goal.target(),
                goal == null ? null : goal.percent(), k.starsThisMonth(), k.starsTotal(), logged, days, asked, yes, saver,
                won, familyGoal, List.copyOf(highlights),
                new Writing("kid:" + k.id(), "The camel's note to " + k.name() + (k.young() ? " (a young child: very short, simple words)" : ""),
                        camel, List.copyOf(facts)));
    }

    // ------------------------------------------------------------------ helpers

    /** Saver of the Month: the kids with a goal, furthest first; a tie is shared. Null when nobody has saved yet. */
    static Saver saver(List<KidFact> kids) {
        List<Runner> race = kids.stream().filter(k -> !k.spend().goals().isEmpty())
                .map(k -> new Runner(k.id(), k.name(), k.spend().goals().get(0).percent()))
                .sorted(Comparator.comparingInt(Runner::percent).reversed().thenComparing(Runner::name))
                .toList();
        if (race.isEmpty() || race.get(0).percent() == 0) {
            return null;
        }
        int top = race.get(0).percent();
        return new Saver(race.stream().filter(r -> r.percent() == top).map(Runner::name).toList(), top, race);
    }

    /** The goal the family talks about: the first one with a date to save for (the summer trip), else the first funded. */
    static java.util.Optional<GoalFact> spineGoal(List<GoalFact> goals) {
        return goals.stream().filter(g -> g.targetDate() != null && g.savedMinor() < g.targetMinor()).findFirst()
                .or(() -> goals.stream().filter(g -> g.monthlyMinor() != null && g.savedMinor() < g.targetMinor()).findFirst());
    }

    static boolean within(Line l) {
        return l.state() != State.OVER && l.state() != State.HEADING_OVER;
    }

    private static FamilyGoalLine goalLine(GoalFact g, String cur) {
        return new FamilyGoalLine(g.name(), g.tierLabel(), MoneyView.of(new Money(g.savedMinor(), cur)),
                MoneyView.of(new Money(g.targetMinor(), cur)), percent(g),
                g.monthlyMinor() == null ? null : MoneyView.of(new Money(g.monthlyMinor(), cur)), g.fullBy());
    }

    private static KidLine kidLine(KidFact k) {
        SpendSummary s = k.spend();
        GoalLine goal = s.goals().isEmpty() ? null : s.goals().get(0);
        return new KidLine(k.id(), k.name(), s.total(), s.pocketMoney(), s.spentPercentOfPocketMoney(), s.daysLogged(),
                s.daysInPeriod(), s.categories().isEmpty() ? null : s.categories().get(0).label(),
                goal == null ? null : goal.name(), goal == null ? null : goal.saved(), goal == null ? null : goal.target(),
                goal == null ? null : goal.percent(), k.starsThisMonth(), k.requests(),
                List.copyOf(new LinkedHashSet<>(s.againstFamilyRules().stream().map(SpendSummary.EntryView::description).toList())));
    }

    static int percent(GoalFact g) {
        return Math.min(100, Money.aed(g.savedMinor()).percentOf(Money.aed(g.targetMinor())));
    }

    static String periodLabel(Facts f) {
        String name = f.month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (!f.running()) {
            return name + " " + f.month().getYear();
        }
        return f.to().getDayOfMonth() == 1 ? "1 " + name : "1 to " + f.to().getDayOfMonth() + " " + name;
    }

    private static List<String> withCount(List<String> facts, int within, int all) {
        List<String> out = new ArrayList<>(facts);
        out.add(within + " of " + all + " budgets within their limit");
        return out;
    }

    /** "a", "a and b", "a, b and c". */
    static String names(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** "Summer trip" -> "summer trip", but "Robux gift card" keeps its brand ("Robux"), and "DEWA" stays. */
    static String lower(String name) {
        if (name.isEmpty()) {
            return name;
        }
        String first = name.split(" ", 2)[0];
        boolean keep = first.length() > 1 && (first.equals(first.toUpperCase(Locale.ROOT)) || BRANDS.contains(first));
        return keep ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static final java.util.Set<String> BRANDS = java.util.Set.of("Robux", "Pokemon", "Lego", "Roblox");
}
