package ae.familyfinance.core.accounts;

import ae.familyfinance.core.accounts.AccountService.AccountView;
import ae.familyfinance.core.accounts.AccountService.CardCheck;
import ae.familyfinance.core.accounts.AccountService.NewAccount;
import ae.familyfinance.core.accounts.AccountService.NewPayment;
import ae.familyfinance.core.accounts.AccountService.PaymentView;
import ae.familyfinance.core.auth.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Accounts, cards and cash a parent types in, and payments tagged with what paid them. Parents only. */
@RestController
@RequestMapping("/api")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    public record AccountBody(@NotNull String kind, @Size(max = 80) String label, @Size(max = 4) String last4,
                              UUID ownerId, @Size(max = 20) String balance, LocalDate asOf, boolean savings,
                              @Size(max = 20) String limit, Integer statementDay, Integer dueDay) {
    }

    public record BalanceBody(@NotNull @Size(max = 20) String balance, LocalDate asOf) {
    }

    public record PaymentBody(@NotNull @Size(max = 20) String amount, @Size(max = 255) String description,
                              @Size(max = 160) String merchant, @Size(max = 40) String category,
                              UUID paidFromAccountId, LocalDate occurredOn, UUID transferToAccountId) {
    }

    @GetMapping("/accounts")
    public List<AccountView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentMember.from(jwt));
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AccountBody b) {
        return service.create(CurrentMember.from(jwt), new NewAccount(b.kind(), b.label(), b.last4(), b.ownerId(),
                b.balance(), b.asOf(), b.savings(), b.limit(), b.statementDay(), b.dueDay()));
    }

    /** Today's balance, or what is owed on a card. Typed by a parent: there are no bank links. */
    @PostMapping("/accounts/{id}/balance")
    public AccountView balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody BalanceBody b) {
        return service.updateBalance(CurrentMember.from(jwt), id, b.balance(), b.asOf());
    }

    /** "If I put this on the card, can we pay the bill?" */
    @GetMapping("/accounts/{id}/card-check")
    public CardCheck cardCheck(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam String amount) {
        return service.cardCheck(CurrentMember.from(jwt), id, amount);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView pay(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentBody b) {
        return service.logPayment(CurrentMember.from(jwt), new NewPayment(b.amount(), b.description(), b.merchant(),
                b.category(), b.paidFromAccountId(), b.occurredOn(), b.transferToAccountId()));
    }
}
