package ae.familyfinance.core.setup;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.AuthController.LoginResponse;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.auth.TokenService;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.Member;
import ae.familyfinance.core.family.MemberRepository;
import ae.familyfinance.core.family.Role;
import ae.familyfinance.core.plan.Tier;
import ae.familyfinance.core.setup.SetupService.BudgetLine;
import ae.familyfinance.core.setup.SetupService.GoalAdded;
import ae.familyfinance.core.setup.SetupService.MemberAdded;
import ae.familyfinance.core.setup.SetupService.NewFamily;
import ae.familyfinance.core.setup.SetupService.NewGoal;
import ae.familyfinance.core.setup.SetupService.NewMember;
import ae.familyfinance.core.setup.SetupService.PocketLine;
import ae.familyfinance.core.setup.SetupService.Status;
import ae.familyfinance.core.setup.StarterBudgetEngine.Buffer;
import ae.familyfinance.core.setup.StarterBudgetEngine.Starter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sign up and the first-run setup. Income and big bills use the plan API (/api/plan/income, /api/plan/commitments),
 * the goal's pace uses /api/plan/options and /api/plan/goals/{id}/pace, and the money buckets use the Planner's
 * draft and /api/plan/buckets/confirm, so a new family's plan is made by the same engines as everyone else's.
 */
@RestController
public class SetupController {

    private final SetupService setup;
    private final MemberRepository members;
    private final TokenService tokens;
    private final boolean signupEnabled;

    public SetupController(SetupService setup, MemberRepository members, TokenService tokens,
                           @Value("${app.signup-enabled:true}") boolean signupEnabled) {
        this.setup = setup;
        this.members = members;
        this.tokens = tokens;
        this.signupEnabled = signupEnabled;
    }

    public record SignupBody(@Size(max = 120) String familyName, @Size(max = 80) String displayName,
                             @Size(max = 60) String username, @Size(max = 200) String password,
                             @Size(max = 160) String email) {
    }

    public record MemberBody(Role role, @Size(max = 80) String displayName, Integer age, @Size(max = 60) String username,
                             @Size(max = 200) String password, @Size(max = 160) String email, boolean consent,
                             @Size(max = 120) String goalName, @Size(max = 20) String goalPrice) {
    }

    public record BudgetLineBody(@NotNull @Size(max = 40) String category, @Size(max = 20) String amount) {
    }

    public record PocketBody(@NotNull UUID memberId, @Size(max = 20) String weekly) {
    }

    public record BudgetsBody(@Size(max = 10) List<@Valid BudgetLineBody> lines, @Size(max = 6) List<@Valid PocketBody> pocket) {
    }

    public record GoalBody(@Size(max = 120) String name, @Size(max = 20) String target, LocalDate targetDate, Tier tier) {
    }

    /** A parent makes a new family and is logged in straight away. */
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse signup(@Valid @RequestBody SignupBody b) {
        if (!signupEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "New families can't sign up here yet");
        }
        UUID id = setup.signUp(new NewFamily(b.familyName(), b.displayName(), b.username(), b.password(), b.email()));
        Member m = members.findById(id).orElseThrow();
        return new LoginResponse(tokens.issue(m), "Bearer", tokens.ttlSeconds(), m.getId(), m.getDisplayName(), m.getRole());
    }

    /** Where this family is in the setup, and the next step. */
    @GetMapping("/api/setup")
    public Status status(@AuthenticationPrincipal Jwt jwt) {
        return setup.status(CurrentMember.from(jwt));
    }

    /** Adds the other parent, or a child with a login and the parent's consent. */
    @PostMapping("/api/setup/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberAdded addMember(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MemberBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can add someone to the family, in the app");
        return setup.addMember(CurrentMember.from(jwt), new NewMember(b.role(), b.displayName(), b.age(), b.username(),
                b.password(), b.email(), b.consent(), b.goalName(), b.goalPrice()));
    }

    /** The engine's starting budgets and pocket money for this family. Saves nothing. */
    @GetMapping("/api/setup/budgets")
    public Starter starterBudgets(@AuthenticationPrincipal Jwt jwt) {
        return setup.starterBudgets(CurrentMember.from(jwt));
    }

    /** The family's budgets and pocket money now, with the engine's start as a guide. Saves nothing. */
    @GetMapping("/api/setup/budgets/current")
    public Starter currentBudgets(@AuthenticationPrincipal Jwt jwt) {
        return setup.currentBudgets(CurrentMember.from(jwt));
    }

    /** A parent confirms the first budgets (as the engine started them, or changed). */
    @PostMapping("/api/setup/budgets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveBudgets(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BudgetsBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can confirm the budgets, in the app");
        setup.saveBudgets(CurrentMember.from(jwt),
                b.lines() == null ? List.of() : b.lines().stream().map(l -> new BudgetLine(l.category(), l.amount())).toList(),
                b.pocket() == null ? List.of() : b.pocket().stream().map(p -> new PocketLine(p.memberId(), p.weekly())).toList());
    }

    /** How big the first goal, an emergency buffer, would be. */
    @GetMapping("/api/setup/buffer")
    public Buffer buffer(@AuthenticationPrincipal Jwt jwt) {
        return setup.buffer(CurrentMember.from(jwt));
    }

    /** A parent adds a family goal. Its pace is picked next, from /api/plan/options. */
    @PostMapping("/api/setup/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalAdded addGoal(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoalBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can add a family goal, in the app");
        return setup.addGoal(CurrentMember.from(jwt), new NewGoal(b.name(), b.target(), b.targetDate(), b.tier()));
    }

    /** Setup is done: the app opens on Today from now on. */
    @PostMapping("/api/setup/finish")
    public Status finish(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can finish the setup, in the app");
        return setup.finish(CurrentMember.from(jwt));
    }
}
