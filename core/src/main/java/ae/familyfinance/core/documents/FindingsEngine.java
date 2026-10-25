package ae.familyfinance.core.documents;

import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Looks through the family's payments for two things a parent should see (feature 8, first half):
 * <ul>
 *   <li>a double charge: the same card or account, the same day, the same amount, the same shop, twice;</li>
 *   <li>a subscription nobody listed: the same amount to the same shop about once a month, at least
 *       {@link #MIN_MONTHS} times, still going, and not on the commitments list.</li>
 * </ul>
 * Each finding has a stable key, so a parent's answer ("both are right", "ignore") hides it for good.
 * Pure: the service loads the payments; every amount in the words comes from {@link Money}.
 */
public final class FindingsEngine {

    static final int MIN_MONTHS = 3;
    static final int MIN_GAP_DAYS = 26;
    static final int MAX_GAP_DAYS = 35;
    /** A subscription whose last payment is older than this has probably stopped. */
    static final int STILL_GOING_DAYS = 40;

    public enum Kind { DUPLICATE, SUBSCRIPTION }

    /** A payment in the ledger, from an account or card. */
    public record Payment(UUID id, UUID accountId, String accountLabel, LocalDate on, long minor, String merchantKey,
                          String description) {
    }

    /**
     * {@code txIds}: for a double charge, the first payment and then the extra one(s).
     * {@code nextOn}: for a subscription, when the next one is expected (to add it as a commitment).
     */
    public record Finding(String key, Kind kind, String title, String detail, MoneyView amount, List<UUID> txIds,
                          String merchant, UUID accountId, LocalDate nextOn, List<String> facts) {
    }

    private FindingsEngine() {
    }

    public static List<Finding> find(List<Payment> payments, List<String> commitmentNames, Set<String> answered,
                                     LocalDate today, String currency) {
        List<Finding> out = new ArrayList<>();
        out.addAll(duplicates(payments, currency));
        out.addAll(subscriptions(payments, commitmentNames, today, currency));
        return out.stream().filter(f -> !answered.contains(f.key())).toList();
    }

    static List<Finding> duplicates(List<Payment> payments, String currency) {
        Map<String, List<Payment>> groups = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.accountId() != null && p.merchantKey() != null) {
                String key = "dup:" + p.accountId() + ":" + p.on() + ":" + p.minor() + ":" + first(p.merchantKey());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
        }
        List<Finding> out = new ArrayList<>();
        groups.forEach((key, list) -> {
            if (list.size() < 2) {
                return;
            }
            Payment p = list.get(0);
            Money amount = new Money(p.minor(), currency);
            String shop = MerchantKey.label(p.merchantKey());
            String times = list.size() == 2 ? "Two" : String.valueOf(list.size());
            out.add(new Finding(cut(key), Kind.DUPLICATE,
                    times + " charges of " + amount.toDisplay() + " at " + shop + " on " + day(p.on()) + ".",
                    "Both on " + (p.accountLabel() == null ? "the same account" : p.accountLabel()) + ", the same day, "
                    + "the same amount. Looks like a double charge.",
                    MoneyView.of(amount), list.stream().map(Payment::id).toList(), shop, p.accountId(), null,
                    List.of("Charges: " + list.size(), "Each: " + amount.toDisplay(), "Shop: " + shop,
                            "Date: " + day(p.on()))));
        });
        return out;
    }

    static List<Finding> subscriptions(List<Payment> payments, List<String> commitmentNames, LocalDate today,
                                       String currency) {
        Map<String, List<Payment>> byShopAndAmount = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.merchantKey() != null && p.accountId() != null) {
                byShopAndAmount.computeIfAbsent(first(p.merchantKey()) + ":" + p.minor(), k -> new ArrayList<>()).add(p);
            }
        }
        List<Finding> out = new ArrayList<>();
        byShopAndAmount.forEach((groupKey, list) -> {
            List<Payment> sorted = list.stream().sorted(Comparator.comparing(Payment::on)).toList();
            // the longest run of roughly monthly payments that ends with the latest one
            List<Payment> run = new ArrayList<>();
            for (Payment p : sorted) {
                if (!run.isEmpty()) {
                    long gap = ChronoUnit.DAYS.between(run.get(run.size() - 1).on(), p.on());
                    if (gap == 0) {
                        continue; // a double charge, not another month
                    }
                    if (gap < MIN_GAP_DAYS || gap > MAX_GAP_DAYS) {
                        run.clear();
                    }
                }
                run.add(p);
            }
            if (run.size() < MIN_MONTHS) {
                return;
            }
            Payment last = run.get(run.size() - 1);
            String key = last.merchantKey();
            if (ChronoUnit.DAYS.between(last.on(), today) > STILL_GOING_DAYS
                    || commitmentNames.stream().anyMatch(n -> MerchantKey.coveredBy(key, n))) {
                return;
            }
            Money amount = new Money(last.minor(), currency);
            Money yearly = amount.times(12);
            String shop = MerchantKey.display(last.description());
            String since = run.get(0).on().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            out.add(new Finding(cut("sub:" + groupKey), Kind.SUBSCRIPTION,
                    amount.toDisplay() + " to " + shop + " every month since " + since + ".",
                    "It is not on your commitments list yet. That is " + yearly.toDisplay() + " a year. Add it?",
                    MoneyView.of(amount), run.stream().map(Payment::id).toList(), shop, last.accountId(),
                    last.on().plusMonths(1),
                    List.of("Payments found: " + run.size(), "Each: " + amount.toDisplay(), "A year: " + yearly.toDisplay(),
                            "Last one: " + day(last.on()))));
        });
        return out;
    }

    private static String first(String key) {
        int space = key.indexOf(' ');
        return space < 0 ? key : key.substring(0, space);
    }

    private static String day(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String cut(String key) {
        return key.length() <= 120 ? key : key.substring(0, 120);
    }
}
