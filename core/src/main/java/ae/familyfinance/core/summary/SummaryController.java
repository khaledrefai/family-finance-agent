package ae.familyfinance.core.summary;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.summary.MonthSummaryEngine.Kid;
import ae.familyfinance.core.summary.MonthSummaryEngine.Parents;
import ae.familyfinance.core.summary.SummaryService.Words;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The monthly family summary: the parents' page, each kid's page, and the agent's words for them. */
@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final SummaryService service;

    public SummaryController(SummaryService service) {
        this.service = service;
    }

    public record WordsBody(@NotNull @Size(max = 12) List<@Valid Words> words) {
    }

    /** {@code month}: 2026-10; none = this month so far. */
    @GetMapping
    public Parents parents(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        return service.parents(CurrentMember.from(jwt), service.month(month));
    }

    /** A kid's own page; a parent can pass {@code memberId} to see what that kid sees. */
    @GetMapping("/kid")
    public Kid kid(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month,
                   @RequestParam(required = false) UUID memberId) {
        return service.kid(CurrentMember.from(jwt), memberId, service.month(month));
    }

    /** The agent's words for the month. Only the words: every number on the page is the engine's. */
    @PostMapping("/words")
    public Parents words(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month,
                         @Valid @RequestBody WordsBody body) {
        return service.saveWords(CurrentMember.from(jwt), service.month(month), body.words());
    }
}
