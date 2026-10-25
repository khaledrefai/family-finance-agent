package ae.familyfinance.core.budgets;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.budgets.BudgetEngine.Month;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    /** This month's family budgets: spent so far, the limit, and the overspend alerts. */
    @GetMapping
    public Month month(@AuthenticationPrincipal Jwt jwt) {
        return budgets.month(CurrentMember.from(jwt));
    }
}
