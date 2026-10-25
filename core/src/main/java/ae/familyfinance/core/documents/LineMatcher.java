package ae.familyfinance.core.documents;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stops the same purchase being counted twice. Each payment line of an uploaded document is compared with what is
 * already in the ledger (a payment a parent typed in, a confirmed receipt, an earlier statement):
 * same account, same amount, and dates at most {@link #DAYS} apart (a card statement often shows a purchase a day
 * or two after the receipt). A match is ALREADY_LOGGED and is not added again on Confirm.
 *
 * <p>It also marks lines inside one statement that look charged twice: same day, same amount, same shop.
 * Pure: no database, no clock.
 */
public final class LineMatcher {

    /** How far apart a receipt's date and the statement's posting date can be. */
    public static final int DAYS = 3;

    /** Something already in the ledger. {@code transferTo} set = a payment into a card. */
    public record Known(UUID txId, LocalDate on, long minor, boolean credit, UUID paidFrom, UUID transferTo,
                        String merchantKey) {
    }

    /** One payment line of the document (for a receipt: its total). */
    public record Line(int no, LocalDate on, long minor, boolean credit, String merchantKey) {
    }

    /** {@code txId} null = new. */
    public record Result(int no, UUID txId, boolean possibleDuplicate) {
    }

    private LineMatcher() {
    }

    /**
     * @param account the account or card the document is for, or null when nobody knows yet (then the shop must
     *                match as well as the amount and date)
     */
    public static List<Result> match(List<Line> lines, List<Known> known, UUID account) {
        Set<UUID> used = new HashSet<>();
        List<Result> out = new ArrayList<>();
        List<Line> seen = new ArrayList<>();
        for (Line l : lines) {
            Known best = known.stream()
                    .filter(k -> !used.contains(k.txId()) && fits(l, k, account))
                    .min(Comparator.comparingLong((Known k) -> Math.abs(ChronoUnit.DAYS.between(k.on(), l.on())))
                            .thenComparing(k -> MerchantKey.same(k.merchantKey(), l.merchantKey()) ? 0 : 1))
                    .orElse(null);
            if (best != null) {
                used.add(best.txId());
            }
            boolean twice = !l.credit() && l.merchantKey() != null && seen.stream().anyMatch(s -> !s.credit()
                    && s.minor() == l.minor() && Objects.equals(s.on(), l.on()) && MerchantKey.same(s.merchantKey(), l.merchantKey()));
            seen.add(l);
            out.add(new Result(l.no(), best == null ? null : best.txId(), twice));
        }
        return out;
    }

    private static boolean fits(Line l, Known k, UUID account) {
        if (k.minor() != l.minor() || l.on() == null || Math.abs(ChronoUnit.DAYS.between(k.on(), l.on())) > DAYS) {
            return false;
        }
        if (account == null) {
            return !l.credit() && !k.credit() && k.transferTo() == null && MerchantKey.same(k.merchantKey(), l.merchantKey());
        }
        if (l.credit()) {
            // a payment into the card that a parent typed as a transfer, or a refund already logged on it
            return account.equals(k.transferTo()) || (k.credit() && account.equals(k.paidFrom()));
        }
        return !k.credit() && k.transferTo() == null && account.equals(k.paidFrom());
    }
}
