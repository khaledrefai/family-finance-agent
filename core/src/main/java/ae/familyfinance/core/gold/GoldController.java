package ae.familyfinance.core.gold;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.gold.GoldService.GoldView;
import ae.familyfinance.core.gold.GoldService.HoldingView;
import ae.familyfinance.core.gold.GoldService.NewGold;
import ae.familyfinance.core.gold.GoldService.Price;
import ae.familyfinance.core.gold.WealthService.WealthView;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Saving in gold and the family wealth view. Parents only (checked in the services). */
@RestController
@RequestMapping("/api")
public class GoldController {

    private final GoldService gold;
    private final WealthService wealth;

    public GoldController(GoldService gold, WealthService wealth) {
        this.gold = gold;
        this.wealth = wealth;
    }

    public record GoldBody(@Size(max = 120) String description, @NotNull @Size(max = 12) String grams,
                           @NotNull @Size(max = 3) String purity, @NotNull @Size(max = 20) String paid,
                           LocalDate boughtOn, @Size(max = 120) String seller, UUID goalId,
                           @Size(max = 16) String bucket, boolean jewellery) {
    }

    public record PriceBody(@NotNull @Size(max = 3) String purity, @NotNull @Size(max = 20) String perGram) {
    }

    /** What the family's gold is worth today, in grams and AED, and the price it was valued at. */
    @GetMapping("/gold")
    public GoldView gold(@AuthenticationPrincipal Jwt jwt) {
        return gold.view(CurrentMember.from(jwt));
    }

    /** A parent logs gold they bought. Never through an agent: the app records gold, it doesn't suggest it. */
    @PostMapping("/gold")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldingView log(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoldBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent logs gold, in the app");
        return gold.log(CurrentMember.from(jwt), new NewGold(b.description(), b.grams(), b.purity(), b.paid(),
                b.boughtOn(), b.seller(), b.goalId(), b.bucket(), b.jewellery()));
    }

    @PostMapping("/gold/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent changes the family's gold, in the app");
        gold.archive(CurrentMember.from(jwt), id);
    }

    /** Today's price per gram as a parent read it at the shop. */
    @PostMapping("/gold/price")
    public Price price(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PriceBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent sets the gold price, in the app");
        return gold.setPrice(CurrentMember.from(jwt), b.purity(), b.perGram());
    }

    /** Bank + cash + gold at today's price − cards − loans, with the date behind each part. */
    @GetMapping("/wealth")
    public WealthView wealth(@AuthenticationPrincipal Jwt jwt) {
        return wealth.wealth(CurrentMember.from(jwt));
    }
}
