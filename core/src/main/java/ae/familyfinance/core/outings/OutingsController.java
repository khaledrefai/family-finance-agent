package ae.familyfinance.core.outings;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.outings.OutingEngine.Kind;
import ae.familyfinance.core.outings.OutingEngine.When;
import ae.familyfinance.core.outings.OutingsService.Board;
import ae.familyfinance.core.outings.OutingsService.PlanView;
import ae.familyfinance.core.outings.OutingsService.Saved;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Cheaper family outings that fit the entertainment budget (week 7). */
@RestController
@RequestMapping("/api/outings")
public class OutingsController {

    private final OutingsService outings;

    public OutingsController(OutingsService outings) {
        this.outings = outings;
    }

    /**
     * {@code keys}: the outings the Planner picked from the parent's words (empty = the engine picks, from {@code kinds}).
     */
    public record PlanBody(@NotNull When when, @Size(max = 8) Set<Kind> kinds, @Size(max = 8) List<@Size(max = 40) String> keys) {
    }

    public record SaveBody(@NotNull When when, @NotNull @Size(min = 1, max = 8) List<@Size(max = 40) String> keys) {
    }

    /** Parents: the budget, every outing with this family's cost, this week's events and the saved plans. */
    @GetMapping
    public Board board(@AuthenticationPrincipal Jwt jwt) {
        return outings.board(CurrentMember.from(jwt));
    }

    /** Parents (and the Planner agent for them): a plan for this month or next, checked by the engine. */
    @PostMapping("/plan")
    public PlanView plan(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlanBody body) {
        return outings.plan(CurrentMember.from(jwt), body.when(), body.kinds(), body.keys());
    }

    /** Any member: this month's and next month's saved outings. Kids get the places only. */
    @GetMapping("/saved")
    public List<Saved> saved(@AuthenticationPrincipal Jwt jwt) {
        return outings.saved(CurrentMember.from(jwt));
    }

    /** A parent saves the month's outings, in the app. The family hears about it. */
    @PutMapping("/saved")
    public Saved save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveBody body, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can save the family's outings, in the app");
        return outings.save(CurrentMember.from(jwt), body.when(), body.keys());
    }
}
