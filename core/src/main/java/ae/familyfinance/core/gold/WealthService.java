package ae.familyfinance.core.gold;

import ae.familyfinance.core.accounts.CardBillEngine.Bill;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.gold.WealthEngine.Part;
import ae.familyfinance.core.gold.WealthEngine.Step;
import ae.familyfinance.core.gold.WealthEngine.Wealth;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.MoneyFacts;
import ae.familyfinance.core.plan.MoneyFacts.AccountFact;
import ae.familyfinance.core.plan.MoneyFacts.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** The family wealth view, from the accounts, cards, loans and gold the parents typed in. Parents only. */
@Service
public class WealthService {

    public record StepView(String label, MoneyView amount, int fromBp, int toBp, String kind, LocalDate asOf,
                           LocalDate asOfLatest) {
    }

    public record WealthView(MoneyView total, MoneyView owned, MoneyView owed, LocalDate asOf, List<StepView> steps,
                             String note) {
    }

    private final MoneyFacts facts;
    private final GoldService gold;
    private final JdbcClient db;

    public WealthService(MoneyFacts facts, GoldService gold, JdbcClient db) {
        this.facts = facts;
        this.gold = gold;
        this.db = db;
    }

    @Transactional(readOnly = true)
    public WealthView wealth(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can see the family's wealth");
        }
        Snapshot s = facts.load(me.familyId());
        String cur = s.currency();
        List<Part> parts = new ArrayList<>();
        sum(s, "Bank accounts", a -> a.kind().equals("BANK"), parts);
        sum(s, "Cash", a -> a.kind().equals("CASH"), parts);
        GoldService.Total g = gold.total(me.familyId(), cur);
        if (g.any()) {
            parts.add(new Part("Gold, at today's price", "OWN", g.value(), g.priceAsOf(), g.priceAsOf()));
        }
        // What is on each card today: as typed, plus card payments logged since
        List<AccountFact> cards = s.accounts().stream().filter(AccountFact::isCard).toList();
        if (!cards.isEmpty()) {
            Money owed = Money.zero(cur);
            for (AccountFact c : cards) {
                Bill b = s.bills().get(c.id());
                owed = owed.plus(b.owedNow());
            }
            parts.add(new Part("Credit cards", "OWE", owed, oldest(cards), newest(cards)));
        }
        db.sql("""
                SELECT name, balance_left_minor, created_at::date FROM commitment
                WHERE family_id = ? AND kind = 'LOAN' AND status = 'ACTIVE' AND balance_left_minor > 0
                ORDER BY balance_left_minor DESC
                """).param(me.familyId())
                .query((rs, n) -> new Part(rs.getString(1) + " left", "OWE", new Money(rs.getLong(2), cur),
                        rs.getObject(3, LocalDate.class), rs.getObject(3, LocalDate.class)))
                .list().forEach(parts::add);

        Wealth w = WealthEngine.compute(parts, cur);
        LocalDate asOf = parts.stream().map(Part::asOfLatest).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .orElse(s.today());
        return new WealthView(MoneyView.of(w.total()), MoneyView.of(w.owned()), MoneyView.of(w.owed()), asOf,
                w.steps().stream().map(x -> new StepView(x.label(), MoneyView.of(x.amount()), x.fromBp(), x.toBp(),
                        x.kind(), x.asOf(), x.asOfLatest())).toList(),
                "An estimate from the balances you typed in. No bank is connected.");
    }

    private static void sum(Snapshot s, String label, Predicate<AccountFact> which, List<Part> parts) {
        List<AccountFact> list = s.accounts().stream().filter(which).toList();
        if (list.isEmpty()) {
            return;
        }
        Money total = Money.zero(s.currency());
        for (AccountFact a : list) {
            total = total.plus(new Money(a.balanceMinor(), s.currency()));
        }
        parts.add(new Part(label, "OWN", total, oldest(list), newest(list)));
    }

    private static LocalDate oldest(List<AccountFact> list) {
        return list.stream().map(AccountFact::asOf).min(Comparator.naturalOrder()).orElse(null);
    }

    private static LocalDate newest(List<AccountFact> list) {
        return list.stream().map(AccountFact::asOf).max(Comparator.naturalOrder()).orElse(null);
    }
}
