package ae.familyfinance.core.meals;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.meals.MealsService.Board;
import ae.familyfinance.core.meals.MealsService.PlanView;
import ae.familyfinance.core.meals.MealsService.ProfileChange;
import ae.familyfinance.core.meals.MealsService.ProfileView;
import ae.familyfinance.core.meals.MealsService.Saved;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** The weekly dinner plan with its shopping list, and who eats what. */
@RestController
@RequestMapping("/api/meals")
public class MealsController {

    private final MealsService meals;

    public MealsController(MealsService meals) {
        this.meals = meals;
    }

    /**
     * {@code keys}: dinners for the cooking nights in order (empty = the engine picks). {@code deliveryDays}: busy
     * nights this week; {@code deliveryKeys}: what to order on them (empty = the cheapest that suits everyone).
     */
    public record PlanBody(@Size(max = 7) List<@Size(max = 40) String> keys, @Size(max = 7) Set<LocalDate> deliveryDays,
                           @Size(max = 7) List<@Size(max = 40) String> deliveryKeys) {
    }

    public record ProfilesBody(@NotNull @Size(min = 1, max = 12) List<@Valid @NotNull ProfileChange> members) {
    }

    /** Parents: the week, the grocery money, who eats what, the saved recipes with this family's cost, the saved week. */
    @GetMapping
    public Board board(@AuthenticationPrincipal Jwt jwt) {
        return meals.board(CurrentMember.from(jwt));
    }

    /** Parents (and the Planner agent for them): a week of dinners from today, checked by the engine. */
    @PostMapping("/plan")
    public PlanView plan(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlanBody body) {
        return meals.plan(CurrentMember.from(jwt), body.keys(), body.deliveryDays(), body.deliveryKeys());
    }

    /** Any member: the saved week (204 when there is none). Kids get the dinners only. */
    @GetMapping("/saved")
    public ResponseEntity<Saved> saved(@AuthenticationPrincipal Jwt jwt) {
        Saved s = meals.saved(CurrentMember.from(jwt));
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(s);
    }

    /** A parent saves the week, in the app. The family hears what's for dinner. */
    @PutMapping("/saved")
    public Saved save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlanBody body, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can save the family's dinners, in the app");
        return meals.save(CurrentMember.from(jwt), body.keys(), body.deliveryDays(), body.deliveryKeys());
    }

    /** A parent changes who eats what (halal, vegetarian, vegan, dislikes), in the app. Allergies stay family rules. */
    @PutMapping("/profiles")
    public List<ProfileView> profiles(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfilesBody body,
                                      HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can change what the family eats, in the app");
        return meals.updateProfiles(CurrentMember.from(jwt), body.members());
    }
}
