package ae.familyfinance.core.advice;

import ae.familyfinance.core.advice.WaysToSaveService.Card;
import ae.familyfinance.core.advice.WaysToSaveService.Decision;
import ae.familyfinance.core.advice.WaysToSaveService.Words;
import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The monthly "Ways to save" card on the parent's Today screen. */
@RestController
@RequestMapping("/api/ways-to-save")
public class WaysToSaveController {

    private final WaysToSaveService service;

    public WaysToSaveController(WaysToSaveService service) {
        this.service = service;
    }

    public record WordsBody(@NotNull @Size(max = 10) List<@Valid Words> tips) {
    }

    @GetMapping
    public Card card(@AuthenticationPrincipal Jwt jwt) {
        return service.card(CurrentMember.from(jwt));
    }

    /** The agent's words for this month's tips. Only the words: the tips and their numbers are the engine's. */
    @PostMapping("/words")
    public Card words(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WordsBody body, HttpServletRequest http) {
        return service.saveWords(CurrentMember.from(jwt), body.tips(), AgentRunFilter.runId(http));
    }

    @PostMapping("/{key}/dismiss")
    public Card dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can dismiss a tip, in the app");
        return service.decide(CurrentMember.from(jwt), key, Decision.DISMISS);
    }

    @PostMapping("/{key}/snooze")
    public Card snooze(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can snooze a tip, in the app");
        return service.decide(CurrentMember.from(jwt), key, Decision.SNOOZE);
    }
}
