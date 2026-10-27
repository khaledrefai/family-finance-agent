package ae.familyfinance.core.plan;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.plan.BucketEngine.Buckets;
import ae.familyfinance.core.plan.BucketEngine.Share;
import ae.familyfinance.core.plan.PlanOptionsEngine.PlanOptions;
import ae.familyfinance.core.plan.PlanService.Calculator;
import ae.familyfinance.core.plan.PlanService.CalculatorAsk;
import ae.familyfinance.core.plan.PlanService.CommitmentEdit;
import ae.familyfinance.core.plan.PlanService.CommitmentView;
import ae.familyfinance.core.plan.PlanService.DraftType;
import ae.familyfinance.core.plan.PlanService.Drafts;
import ae.familyfinance.core.plan.PlanService.GoalEdit;
import ae.familyfinance.core.plan.PlanService.GoalView;
import ae.familyfinance.core.plan.PlanService.IncomeEdit;
import ae.familyfinance.core.plan.PlanService.IncomeView;
import ae.familyfinance.core.plan.PlanService.NewCommitment;
import ae.familyfinance.core.plan.PlanService.NewDate;
import ae.familyfinance.core.plan.PlanService.NewIncome;
import ae.familyfinance.core.plan.PlanService.NewReceipt;
import ae.familyfinance.core.plan.PlanService.OneOffEffect;
import ae.familyfinance.core.plan.PlanService.ReceiptResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The family plan API. Parents only (checked in {@link PlanService}). */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    public record IncomeBody(UUID memberId, @Size(max = 120) String name, IncomeKind kind,
                             @Size(max = 20) String amount, Frequency frequency, LocalDate nextOn,
                             @Size(max = 20) String baseOverride, @Size(max = 12) List<@Valid ReceiptBody> receipts,
                             boolean draft) {
    }

    public record ReceiptBody(@NotNull LocalDate receivedOn, @Size(max = 20) String amount) {
    }

    public record CommitmentBody(@Size(max = 120) String name, CommitmentKind kind, @Size(max = 20) String amount,
                                 Frequency frequency, LocalDate nextDueOn, Integer remainingCount, boolean estimate,
                                 @Size(max = 20) String summerAmount, @Size(max = 20) String reserved,
                                 @Size(max = 20) String balanceLeft, UUID paidFromAccountId,
                                 @Size(max = 24) List<@Valid DateBody> dates, boolean draft) {
    }

    public record DateBody(@NotNull LocalDate dueOn, @Size(max = 20) String amount, @Size(max = 80) String label) {
    }

    public record OptionsBody(@NotNull UUID goalId, LocalDate targetDate) {
    }

    public record PaceBody(@NotNull @Size(max = 20) String monthly, LocalDate targetDate) {
    }

    public record ShareBody(@NotNull BucketKind kind, int shareBp, @Size(max = 240) String reason) {
    }

    /** {@code from}: "INVEST_ASIDE" starts from that bucket; a {@code goalId} starts from the goal. */
    public record CalculatorBody(UUID goalId, @Size(max = 20) String from, @Size(max = 20) String start,
                                 @Size(max = 20) String monthly, @Size(max = 12) String rate, Integer years,
                                 @Size(max = 20) String target) {
    }

    public record BucketDraftBody(@NotEmpty @Size(max = 6) List<@Valid ShareBody> shares) {
    }

    public record IncomeEditBody(@Size(max = 120) String name, @Size(max = 20) String amount, LocalDate nextOn,
                                 @Size(max = 20) String baseOverride) {
    }

    public record CommitmentEditBody(@Size(max = 120) String name, @Size(max = 20) String amount, Frequency frequency,
                                     LocalDate nextDueOn, @Size(max = 20) String reserved) {
    }

    public record GoalEditBody(@Size(max = 160) String name, @Size(max = 20) String target, LocalDate targetDate) {
    }

    /** What a parent typed for each bucket, in AED. Fun is left out: it gets the rest. */
    public record BucketAmountsBody(@NotNull @Size(max = 6) Map<BucketKind, @Size(max = 20) String> amounts) {
    }

    /** Free cash, the 12-month calendar, upcoming bills and what is free until the next pay. */
    @GetMapping("/cashflow")
    public Cashflow cashflow(@AuthenticationPrincipal Jwt jwt) {
        return service.cashflow(CurrentMember.from(jwt));
    }

    @GetMapping("/income")
    public List<IncomeView> incomes(@AuthenticationPrincipal Jwt jwt) {
        return service.incomes(CurrentMember.from(jwt));
    }

    /** Through an agent run this is always saved as a draft for a parent to confirm. */
    @PostMapping("/income")
    @ResponseStatus(HttpStatus.CREATED)
    public IncomeView addIncome(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IncomeBody b, HttpServletRequest http) {
        return service.createIncome(CurrentMember.from(jwt), new NewIncome(b.memberId(), b.name(), b.kind(), b.amount(),
                b.frequency(), b.nextOn(), b.baseOverride(),
                b.receipts() == null ? null : b.receipts().stream().map(r -> new NewReceipt(r.receivedOn(), r.amount())).toList(),
                b.draft()), AgentRunFilter.isAgentCall(http));
    }

    /** A parent changes an income after setup. Never through an agent: an agent can only add a draft. */
    @PutMapping("/income/{id}")
    public IncomeView updateIncome(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody IncomeEditBody b,
                                   HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can change the income, in the app");
        return service.updateIncome(CurrentMember.from(jwt), id, new IncomeEdit(b.name(), b.amount(), b.nextOn(), b.baseOverride()));
    }

    @DeleteMapping("/income/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeIncome(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can remove an income, in the app");
        service.removeIncome(CurrentMember.from(jwt), id);
    }

    /** What a commission or sales income paid this month. */
    @PostMapping("/income/{id}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptResult addReceipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReceiptBody b) {
        return service.addReceipt(CurrentMember.from(jwt), id, new NewReceipt(b.receivedOn(), b.amount()));
    }

    @GetMapping("/commitments")
    public List<CommitmentView> commitments(@AuthenticationPrincipal Jwt jwt) {
        return service.commitments(CurrentMember.from(jwt));
    }

    /** Through an agent run this is always saved as a draft for a parent to confirm. */
    @PostMapping("/commitments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommitmentView addCommitment(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CommitmentBody b,
                                        HttpServletRequest http) {
        return service.createCommitment(CurrentMember.from(jwt), new NewCommitment(b.name(), b.kind(), b.amount(),
                b.frequency(), b.nextDueOn(), b.remainingCount(), b.estimate(), b.summerAmount(), b.reserved(),
                b.balanceLeft(), b.paidFromAccountId(),
                b.dates() == null ? null : b.dates().stream().map(d -> new NewDate(d.dueOn(), d.amount(), d.label())).toList(),
                b.draft()), AgentRunFilter.isAgentCall(http));
    }

    @PutMapping("/commitments/{id}")
    public CommitmentView updateCommitment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody CommitmentEditBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can change a bill, in the app");
        return service.updateCommitment(CurrentMember.from(jwt), id,
                new CommitmentEdit(b.name(), b.amount(), b.frequency(), b.nextDueOn(), b.reserved()));
    }

    @DeleteMapping("/commitments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCommitment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can remove a bill, in the app");
        service.removeCommitment(CurrentMember.from(jwt), id);
    }

    /** Income and commitments waiting for a parent's Confirm. */
    @GetMapping("/drafts")
    public Drafts drafts(@AuthenticationPrincipal Jwt jwt) {
        return service.drafts(CurrentMember.from(jwt));
    }

    @PostMapping("/drafts/{type}/{id}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String type, @PathVariable UUID id,
                        HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can confirm a draft, in the app");
        service.decideDraft(CurrentMember.from(jwt), draftType(type), id, true);
    }

    @PostMapping("/drafts/{type}/{id}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@AuthenticationPrincipal Jwt jwt, @PathVariable String type, @PathVariable UUID id) {
        service.decideDraft(CurrentMember.from(jwt), draftType(type), id, false);
    }

    @GetMapping("/goals")
    public List<GoalView> goals(@AuthenticationPrincipal Jwt jwt) {
        return service.goals(CurrentMember.from(jwt));
    }

    /** A parent changes a family goal's name, amount or date. New goals are added with POST /api/setup/goals. */
    @PutMapping("/goals/{id}")
    public GoalView updateGoal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody GoalEditBody b,
                               HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can change a goal, in the app");
        return service.updateGoal(CurrentMember.from(jwt), id, new GoalEdit(b.name(), b.target(), b.targetDate()));
    }

    @DeleteMapping("/goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGoal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can remove a goal, in the app");
        service.removeGoal(CurrentMember.from(jwt), id);
    }

    /** Relaxed, steady and faster ways to reach a family goal, each checked against every month's spare cash. */
    @PostMapping("/options")
    public PlanOptions options(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OptionsBody b) {
        return service.options(CurrentMember.from(jwt), b.goalId(), b.targetDate());
    }

    /** A parent picks a pace for a goal. */
    @PostMapping("/goals/{id}/pace")
    public GoalView pace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PaceBody b,
                         HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can pick the plan, in the app");
        return service.setPace(CurrentMember.from(jwt), id, b.monthly(), b.targetDate());
    }

    /** What a bonus or gift would do for each goal. */
    @GetMapping("/one-off-effect")
    public OneOffEffect oneOffEffect(@AuthenticationPrincipal Jwt jwt, @RequestParam String amount) {
        return service.oneOffEffect(CurrentMember.from(jwt), amount);
    }

    /**
     * The long-term savings calculator: 1, 5 and 10 years (and the horizon picked) at the parent's example rate,
     * next to the same money kept as cash. Education only. Agents may call it; it saves nothing.
     */
    @PostMapping("/calculator")
    public Calculator calculator(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CalculatorBody b) {
        return service.calculator(CurrentMember.from(jwt), new CalculatorAsk(b.goalId(),
                "INVEST_ASIDE".equalsIgnoreCase(b.from()) || "invest".equalsIgnoreCase(b.from()), b.start(), b.monthly(),
                b.rate(), b.years(), b.target()));
    }

    /** The family's money buckets: the waiting draft if there is one, else the confirmed split, else the engine's. */
    @GetMapping("/buckets")
    public Buckets buckets(@AuthenticationPrincipal Jwt jwt) {
        return service.buckets(CurrentMember.from(jwt));
    }

    @GetMapping("/buckets/suggestion")
    public List<Share> suggestion(@AuthenticationPrincipal Jwt jwt) {
        return service.suggestion(CurrentMember.from(jwt));
    }

    /** A BucketPlanDraft: from the Planner (through an agent run) or a parent editing the shares. */
    @PostMapping("/buckets/draft")
    public Buckets saveBucketDraft(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BucketDraftBody b,
                                   HttpServletRequest http) {
        return service.saveBucketDraft(CurrentMember.from(jwt),
                b.shares().stream().map(s -> new Share(s.kind(), s.shareBp(), s.reason())).toList(),
                AgentRunFilter.isAgentCall(http) ? "PLANNER" : "PARENT");
    }

    /** A parent types each bucket in AED (Fun gets the rest). Saved as the parent's draft to confirm. */
    @PostMapping("/buckets/draft/amounts")
    public Buckets saveBucketAmounts(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BucketAmountsBody b,
                                     HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can change the buckets by hand, in the app");
        return service.saveBucketAmounts(CurrentMember.from(jwt), b.amounts());
    }

    /** Keep the confirmed split: drop the waiting draft. */
    @DeleteMapping("/buckets/draft")
    public Buckets discardBucketDraft(@AuthenticationPrincipal Jwt jwt) {
        return service.discardBucketDraft(CurrentMember.from(jwt));
    }

    @PostMapping("/buckets/confirm")
    public Buckets confirmBuckets(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can confirm the buckets, in the app");
        return service.confirmBuckets(CurrentMember.from(jwt));
    }

    private static DraftType draftType(String type) {
        return switch (type) {
            case "income" -> DraftType.INCOME;
            case "commitment", "commitments" -> DraftType.COMMITMENT;
            default -> throw new ae.familyfinance.core.common.ApiException(HttpStatus.NOT_FOUND, "Unknown draft type");
        };
    }
}
