package ae.familyfinance.core.requests;

import ae.familyfinance.core.accounts.AccountService;
import ae.familyfinance.core.accounts.AccountService.CardCheck;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Cashflow;
import ae.familyfinance.core.plan.MoneyFacts;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Can we afford it?" worked out by the money engine. The Gatekeeper agent only puts these facts into words;
 * every number it may say is in {@link Assessment#facts()}.
 */
@Service
public class AffordabilityService {

    /** Bump when the shape or the rules of the assessment change, so old stored ones can be told apart. */
    public static final int VERSION = 1;

    /** For a child paying from their wallet the wallet decides; otherwise free cash until pay day and the card bill. */
    public enum Verdict {
        /** Free cash until pay day covers it with room to spare. */
        FITS,
        /** It fits, but leaves less than a tenth of what is free until pay day. */
        TIGHT,
        /** Free cash until pay day does not cover it. */
        DOES_NOT_FIT
    }

    /** For a child: their demo wallet and what this does to it. */
    public record KidSide(UUID memberId, String name, MoneyView wallet, boolean walletCovers, MoneyView walletAfter) {
    }

    public record Assessment(int version, MoneyView amount, Verdict verdict, MoneyView freeUntilPay,
                             LocalDate nextIncomeOn, MoneyView freeAfter, String nextCommitment,
                             boolean needsParent, MoneyView approvalLimit, KidSide kid, CardCheck card,
                             List<String> facts) {

        /** What a child may see: their wallet and the approval rule, never the family's free cash. */
        public Assessment forChild() {
            List<String> own = new ArrayList<>();
            own.add(facts.get(0));
            facts.stream().filter(f -> f.contains("wallet") || f.startsWith("A parent says yes")).forEach(own::add);
            Verdict v = kid != null && kid.walletCovers() ? Verdict.FITS : Verdict.DOES_NOT_FIT;
            return new Assessment(version, amount, v, null, null, null, null, needsParent, approvalLimit, kid, null,
                    List.copyOf(own));
        }
    }

    private final MoneyFacts moneyFacts;
    private final AccountService accounts;
    private final JdbcClient db;

    public AffordabilityService(MoneyFacts moneyFacts, AccountService accounts, JdbcClient db) {
        this.moneyFacts = moneyFacts;
        this.accounts = accounts;
        this.db = db;
    }

    /**
     * Assesses {@code amountMinor} for {@code requesterId} (null = the caller). A child can only assess their own
     * spending; {@code paidFromAccountId} is a parent's card or account, which adds the card-bill check.
     */
    @Transactional(readOnly = true)
    public Assessment assess(CurrentMember me, UUID requesterId, long amountMinor, UUID paidFromAccountId) {
        UUID who = requesterId == null ? me.memberId() : requesterId;
        if (!me.isParent() && !who.equals(me.memberId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only check your own spending");
        }
        if (!me.isParent() && paidFromAccountId != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can pick a card or account");
        }
        Member member = db.sql("SELECT display_name, role FROM member WHERE id = ? AND family_id = ?")
                .params(who, me.familyId())
                .query((rs, n) -> new Member(rs.getString(1), "CHILD".equals(rs.getString(2)))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));

        Snapshot s = moneyFacts.load(me.familyId());
        String cur = s.currency();
        Money amount = new Money(amountMinor, cur);
        Cashflow flow = s.engine().summary();
        Money free = new Money(flow.untilNextIncome().free().minor(), cur);
        Money after = free.minus(amount);
        Verdict verdict = after.isNegative() ? Verdict.DOES_NOT_FIT
                : after.compareTo(free.scaled(1, 10)) < 0 ? Verdict.TIGHT : Verdict.FITS;

        List<String> facts = new ArrayList<>();
        String amountText = MoneyView.of(amount).text();
        facts.add("It costs " + amountText + ".");
        String until = flow.untilNextIncome().nextIncomeOn() == null ? "Free now: "
                : "Free until pay day (" + flow.untilNextIncome().nextIncomeLabel() + ", "
                  + day(flow.untilNextIncome().nextIncomeOn()) + "): ";
        facts.add(until + MoneyView.of(free).text() + (after.isNegative()
                ? ". This would leave it " + MoneyView.of(after.negate()).text() + " short."
                : ", and " + MoneyView.of(after).text() + " after this."));
        String next = null;
        if (flow.nextCommitment() != null) {
            Cashflow.Upcoming c = flow.nextCommitment();
            next = c.name() + " " + c.amount().text() + (c.daysLeft() <= 1 ? " tomorrow" : " in " + c.daysLeft() + " days");
            facts.add("Next bill: " + next + ".");
        }

        KidSide kid = null;
        Money limit = approvalLimit(me.familyId(), who);
        boolean needsParent;
        if (member.child()) {
            Money wallet = new Money(walletMinor(who), cur);
            boolean covers = wallet.compareTo(amount) >= 0;
            kid = new KidSide(who, member.name(), MoneyView.of(wallet), covers, MoneyView.of(wallet.minus(amount)));
            facts.add(member.name() + "'s wallet has " + MoneyView.of(wallet).text() + (covers
                    ? ", enough for this."
                    : ", " + MoneyView.of(amount.minus(wallet)).text() + " short."));
            needsParent = !covers || limit == null || amount.compareTo(limit) > 0;
            if (limit != null) {
                facts.add("A parent says yes to anything over " + MoneyView.of(limit).text() + ".");
            }
        } else {
            needsParent = false;
        }

        CardCheck card = paidFromAccountId == null ? null : accounts.cardCheck(s, paidFromAccountId, amountMinor);
        if (card != null) {
            facts.add(card.line());
        }
        if (kid != null && paidFromAccountId == null) {
            // Paid from the child's own demo wallet: the wallet decides, the family's cash is shown for context
            verdict = kid.walletCovers() ? Verdict.FITS : Verdict.DOES_NOT_FIT;
        } else if (card != null && !card.fits()) {
            verdict = Verdict.DOES_NOT_FIT;
        }
        return new Assessment(VERSION, MoneyView.of(amount), verdict, MoneyView.of(free),
                flow.untilNextIncome().nextIncomeOn(), MoneyView.of(after), next, needsParent,
                limit == null ? null : MoneyView.of(limit), kid, card, List.copyOf(facts));
    }

    private static String day(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
    }

    private record Member(String name, boolean child) {
    }

    /** The member's own limit if a parent set one, else the family's. */
    private Money approvalLimit(UUID family, UUID member) {
        return db.sql("""
                SELECT r.limit_minor, f.currency FROM family_rule r JOIN family f ON f.id = r.family_id
                WHERE r.family_id = ? AND r.kind = 'APPROVAL_LIMIT'
                  AND (r.applies_to_member_id = ? OR r.applies_to_member_id IS NULL)
                ORDER BY r.applies_to_member_id NULLS LAST LIMIT 1
                """).params(family, member)
                .query((rs, n) -> new Money(rs.getLong(1), rs.getString(2))).optional().orElse(null);
    }

    private long walletMinor(UUID member) {
        return db.sql("""
                SELECT COALESCE(SUM(e.amount_minor), 0) FROM demo_wallet_entry e
                JOIN demo_wallet w ON w.id = e.wallet_id WHERE w.member_id = ?
                """).param(member).query(Long.class).single();
    }
}
