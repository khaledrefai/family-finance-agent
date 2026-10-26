package ae.familyfinance.core.alerts;

import ae.familyfinance.core.alerts.AlertService.Checked;
import ae.familyfinance.core.alerts.WeeklyPlanEngine.WeekPlan;
import ae.familyfinance.core.auth.CurrentMember;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('PARENT')")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    /** This week's plan (the same one Sunday's notification is made from). */
    @GetMapping("/api/plan/week")
    public WeekPlan week(@AuthenticationPrincipal Jwt jwt) {
        return alerts.weekPlan(CurrentMember.from(jwt).familyId());
    }

    /**
     * Runs the alert rules now instead of waiting for the 15-minute check. Anything already sent is not sent again.
     * {@code weekly=true} also sends this week's plan if it hasn't gone yet (for the demo, any day of the week);
     * {@code summary=true} sends this month's family summary so far (for the demo, once a day).
     */
    @PostMapping("/api/alerts/check")
    public Checked check(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean weekly,
                         @RequestParam(defaultValue = "false") boolean summary) {
        return alerts.check(CurrentMember.from(jwt).familyId(), weekly, summary);
    }
}
