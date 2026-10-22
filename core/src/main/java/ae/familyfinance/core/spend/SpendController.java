package ae.familyfinance.core.spend;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.spend.SpendSummary.EntryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/spend")
public class SpendController {

    private final SpendService service;

    public SpendController(SpendService service) {
        this.service = service;
    }

    /** One thing bought. {@code amount} is what the child typed ("12", "7.50"); the core turns it into fils. */
    public record LogItem(@NotBlank @Size(max = 12) String amount,
                          @NotBlank @Size(max = 120) String description,
                          @Size(max = 80) String merchant,
                          @NotNull SpendCategory category) {
    }

    public record LogRequest(@NotEmpty @Size(max = SpendService.MAX_ITEMS) List<@Valid LogItem> items,
                             LocalDate occurredOn,
                             @Size(max = 500) String loggedText) {
    }

    /** Log my own spending. Anyone can only log for themselves. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpendService.LogResult log(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LogRequest body) {
        return service.log(CurrentMember.from(jwt),
                body.items().stream()
                        .map(i -> new SpendService.NewItem(i.amount(), i.description(), i.merchant(), i.category()))
                        .toList(),
                body.occurredOn(), body.loggedText());
    }

    /** Logged purchases for a month, newest first. Parents: anyone in the family. Children: themselves. */
    @GetMapping
    public List<EntryView> entries(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(required = false) UUID memberId,
                                   @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.entries(CurrentMember.from(jwt), memberId, month);
    }

    /** "Where did the money go?" — every number the agent may use, worked out by the money engine. */
    @GetMapping("/summary")
    public SpendSummary summary(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam(required = false) UUID memberId,
                                @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.summary(CurrentMember.from(jwt), memberId, month);
    }

    /** My logging status today, used by the agent to decide on a nudge. */
    @GetMapping("/status")
    public SpendService.LoggingStatus status(@AuthenticationPrincipal Jwt jwt) {
        return service.status(CurrentMember.from(jwt));
    }
}
