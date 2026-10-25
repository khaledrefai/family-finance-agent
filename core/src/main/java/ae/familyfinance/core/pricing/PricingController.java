package ae.familyfinance.core.pricing;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.pricing.PricingService.Comparison;
import ae.familyfinance.core.pricing.PricingService.Mode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    private final PricingService pricing;

    public PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    /**
     * {@code intent} is what the Shopper agent read from the person's words. {@code requesterId} (a parent checking for
     * a child; null = the caller) and {@code paidFromAccountId} (a parent's card) go to the affordability check.
     */
    public record CompareBody(@NotNull @Valid PurchaseIntent intent, UUID requesterId, UUID paidFromAccountId) {
    }

    public record Status(Mode mode, boolean liveAvailable, int savedItems) {
    }

    /** "Where is it cheapest, and can we afford it?" Any member; a child sees their own wallet side only. */
    @PostMapping("/compare")
    public Comparison compare(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompareBody body) {
        return pricing.compare(CurrentMember.from(jwt), body.intent(), body.requesterId(), body.paidFromAccountId());
    }

    /** Which searches are on (SEARCH_LIVE, a SerpApi key), for the pre-warm script and the parents' settings. */
    @GetMapping("/status")
    @PreAuthorize("hasRole('PARENT')")
    public Status status() {
        return new Status(pricing.mode(), pricing.liveAvailable(), pricing.catalogueSize());
    }
}
