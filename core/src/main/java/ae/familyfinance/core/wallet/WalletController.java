package ae.familyfinance.core.wallet;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.wallet.WalletService.WalletView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /** My wallet, or (a parent) a child's: {@code ?memberId=}. */
    @GetMapping("/api/wallet")
    public WalletView wallet(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID memberId) {
        return wallets.view(CurrentMember.from(jwt), memberId);
    }
}
