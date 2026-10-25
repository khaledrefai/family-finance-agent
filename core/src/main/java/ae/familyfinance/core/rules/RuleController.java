package ae.familyfinance.core.rules;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.rules.RuleService.Draft;
import ae.familyfinance.core.rules.RuleService.RuleView;
import ae.familyfinance.core.spend.SpendCategory;
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

import java.util.List;
import java.util.UUID;

/**
 * Family rules. An agent may only draft one ({@code POST /api/rules/drafts} during an agent run); turning a rule on
 * or off is a parent's tap in the app, so those endpoints refuse agent calls.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService rules;

    public RuleController(RuleService rules) {
        this.rules = rules;
    }

    /** A RuleDraft. {@code limit} is the amount as written ("100"); {@code draftedBy} is LLM or RULES for an agent. */
    public record DraftBody(@NotNull RuleKind kind, UUID forChild, SpendCategory category, Allergen allergen,
                            @Size(max = 20) String limit, Boolean weekly, @Size(max = 300) String said,
                            @Size(max = 8) String draftedBy) {
    }

    @GetMapping
    public List<RuleView> list(@AuthenticationPrincipal Jwt jwt) {
        return rules.list(CurrentMember.from(jwt));
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView draft(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DraftBody b, HttpServletRequest http) {
        return rules.draft(CurrentMember.from(jwt), new Draft(b.kind(), b.forChild(), b.category(), b.allergen(),
                b.limit(), Boolean.TRUE.equals(b.weekly()), b.said(), b.draftedBy()), AgentRunFilter.runId(http));
    }

    @PostMapping("/{id}/confirm")
    public RuleView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can turn a rule on, in the app");
        return rules.confirm(CurrentMember.from(jwt), id);
    }

    @PostMapping("/{id}/off")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void off(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can turn a rule off, in the app");
        rules.turnOff(CurrentMember.from(jwt), id);
    }
}
