package ae.familyfinance.core.gold;

import ae.familyfinance.core.money.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The family wealth view: everything the family owns (bank accounts, cash, gold at today's price) minus what it
 * owes (what is on the credit cards today, loans still to pay), all from what the parents typed in.
 * Also places each part on one bar, as shares of everything owned in basis points, for the chart.
 * Pure: no database, no clock.
 */
public final class WealthEngine {

    /** One part: OWN or OWE. {@code asOf} = the oldest date behind it, since the figures are typed estimates. */
    public record Part(String label, String side, Money amount, LocalDate asOf, LocalDate asOfLatest) {
    }

    /** A bar on the chart; {@code kind} is s1 (own), s4 (gold), s2 (owe) or total, as the web's waterfall draws. */
    public record Step(String label, Money amount, int fromBp, int toBp, String kind, LocalDate asOf,
                       LocalDate asOfLatest) {
    }

    public record Wealth(Money owned, Money owed, Money total, List<Step> steps) {
    }

    private WealthEngine() {
    }

    public static Wealth compute(List<Part> parts, String currency) {
        Money owned = Money.zero(currency);
        Money owed = Money.zero(currency);
        for (Part p : parts) {
            if (p.side().equals("OWN")) {
                owned = owned.plus(p.amount());
            } else {
                owed = owed.plus(p.amount());
            }
        }
        Money total = owned.minus(owed);
        // The bar is "everything owned" = 100%. Debts are cut off from its right end; a family that owes more
        // than it owns shows the whole bar as debt and a negative total.
        long scale = Math.max(owned.minor(), owed.minor());
        List<Step> steps = new ArrayList<>();
        long at = 0;
        for (Part p : parts) {
            if (p.side().equals("OWN") && p.amount().isPositive()) {
                long to = at + p.amount().minor();
                steps.add(new Step(p.label(), p.amount(), bp(at, scale), bp(to, scale),
                        p.label().startsWith("Gold") ? "s4" : "s1", p.asOf(), p.asOfLatest()));
                at = to;
            }
        }
        steps.add(new Step("Everything you own", owned, 0, bp(owned.minor(), scale), "total", null, null));
        long right = owned.minor();
        for (Part p : parts) {
            if (p.side().equals("OWE") && p.amount().isPositive()) {
                long from = right - p.amount().minor();
                steps.add(new Step(p.label(), p.amount().negate(), bp(Math.max(from, 0), scale), bp(Math.max(right, 0), scale), "s2",
                        p.asOf(), p.asOfLatest()));
                right = from;
            }
        }
        steps.add(new Step("Family wealth", total, 0, bp(Math.max(total.minor(), 0), scale), "total", null, null));
        return new Wealth(owned, owed, total, steps);
    }

    /** {@code part} of {@code whole} in basis points, rounded half up: 1 of 3 = 3333. */
    static int bp(long part, long whole) {
        if (whole <= 0) {
            return 0;
        }
        return (int) ((Math.multiplyExact(part, 20_000L) + whole) / (whole * 2));
    }
}
