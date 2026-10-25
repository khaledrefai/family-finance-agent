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
import ae.familyfinance.core.rules.RuleEngine;
import ae.familyfinance.core.rules.RuleEngine.Ask;
import ae.familyfinance.core.rules.RuleEngine.Hit;
import ae.familyfinance.core.rules.RuleEngine.Outcome;
import ae.familyfinance.core.rules.RuleKind;
import ae.familyfinance.core.rules.RuleService;
import ae.familyfinance.core.spend.SpendCategory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Can we afford it, and is it allowed?" worked out by the core: the money engine for the money, the
 * {@link RuleEngine} for the family rules (blocked kinds of thing, allergens, limits, the monthly cap).
 * The Gatekeeper agent only puts these facts into words; every number it may say is in {@link Assessment#facts()}.
 */
@Service
public class AffordabilityService {

    /** Bump when the shape or the rules of the assessment change, so old stored ones can be told apart. */
    public static final int VERSION = 2;

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

    /**
     * The family rules checked for a child's request. {@code outcome} BLOCKED means it can't be bought;
     * NEEDS_PARENT means a parent decides; OK means no rule said anything. Empty for a parent's own spending.
     */
    public record RuleCheck(Outcome outcome, List<Hit> hits) {
    }

    public record Assessment(int version, MoneyView amount, Verdict verdict, MoneyView freeUntilPay,
                             LocalDate nextIncomeOn, MoneyView freeAfter, String nextCommitment,
                             boolean needsParent, boolean blocked, MoneyView approvalLimit, KidSide kid,
                             RuleCheck rules, CardCheck card, List<String> facts) {

        /** What a child may see: their wallet and their family rules, never the family's free cash. */
        public Assessment forChild() {
            List<String> own = new ArrayList<>();
            own.add(facts.get(0));
            facts.stream().filter(f -> f.contains("wallet") || f.startsWith("A parent says yes") || f.startsWith(RULE))
                    .forEach(own::add);
            Verdict v = kid != null && kid.walletCovers() ? Verdict.FITS : Verdict.DOES_NOT_FIT;
            return new Assessment(version, amount, v, null, null, null, null, needsParent, blocked, approvalLimit, kid,
                    rules, null, List.copyOf(own));
        }
    }

    /** Every fact about a family rule starts with this, so the child's view can keep them. */
    static final String RULE = "Family rule: ";

    private final MoneyFacts moneyFacts;
    private final AccountService accounts;
    private final RuleService rules;
    private final JdbcClient db;

    public AffordabilityService(MoneyFacts moneyFacts, AccountService accounts, RuleService rules, JdbcClient db) {
        this.moneyFacts = moneyFacts;
        this.accounts = accounts;
        this.rules = rules;
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Assessment assess(CurrentMember me, UUID requesterId, long amountMinor, UUID paidFromAccountId) {
        return assess(me, requesterId, amountMinor, paidFromAccountId, null, null);
    }

    /**
     * Assesses {@code amountMinor} for {@code requesterId} (null = the caller). A child can only assess their own
     * spending; {@code paidFromAccountId} is a parent's card or account, which adds the card-bill check.
     * {@code text} (the name or product text) and {@code category} are what the family rules are checked against.
     */
    @Transactional(readOnly = true)
    public Assessment assess(CurrentMember me, UUID requesterId, long amountMinor, UUID paidFromAccountId,
                             String text, SpendCategory category) {
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
        RuleCheck ruleCheck = null;
        Money limit = null;
        boolean needsParent = false;
        boolean blocked = false;
        if (member.child()) {
            Money wallet = new Money(walletMinor(who), cur);
            boolean covers = wallet.compareTo(amount) >= 0;
            kid = new KidSide(who, member.name(), MoneyView.of(wallet), covers, MoneyView.of(wallet.minus(amount)));
            facts.add(member.name() + "'s wallet has " + MoneyView.of(wallet).text() + (covers
                    ? ", enough for this."
                    : ", " + MoneyView.of(amount.minus(wallet)).text() + " short."));

            RuleEngine.Result r = RuleEngine.check(rules.activeFor(me.familyId(), who),
                    new Ask(amountMinor, category, text), rules.spentBy(me.familyId(), who), member.name(), cur);
            ruleCheck = new RuleCheck(r.outcome(), r.hits());
            limit = r.approvalLimit();
            blocked = r.blocked();
            // With no approval limit set, a parent says yes to everything
            needsParent = !blocked && (!covers || limit == null || r.outcome() != Outcome.OK);
            if (limit != null) {
                facts.add("A parent says yes to anything over " + MoneyView.of(limit).text() + ".");
            }
            r.hits().stream().filter(h -> h.outcome() != Outcome.OK && h.kind() != RuleKind.APPROVAL_LIMIT)
                    .forEach(h -> facts.add(RULE + h.rule() + ". " + h.detail()));
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
                flow.untilNextIncome().nextIncomeOn(), MoneyView.of(after), next, needsParent, blocked,
                limit == null ? null : MoneyView.of(limit), kid, ruleCheck, card, List.copyOf(facts));
    }

    private static String day(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
    }

    private record Member(String name, boolean child) {
    }

    private long walletMinor(UUID member) {
        return db.sql("""
                SELECT COALESCE(SUM(e.amount_minor), 0) FROM demo_wallet_entry e
                JOIN demo_wallet w ON w.id = e.wallet_id WHERE w.member_id = ?
                """).param(member).query(Long.class).single();
    }
}
