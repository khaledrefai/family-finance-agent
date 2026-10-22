package ae.familyfinance.core.requests;

import ae.familyfinance.core.auth.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService service;

    public RequestController(RequestService service) {
        this.service = service;
    }

    public record CreateRequest(@NotNull RequestKind kind,
                                @NotBlank @Size(max = 200) String title,
                                @PositiveOrZero long amountMinor,
                                @Size(max = 40) String category,
                                @Size(max = 1000) String productUrl) {
    }

    public record DecideRequest(@Size(max = 500) String note) {
    }

    public record RequestView(UUID id, UUID requesterId, RequestKind kind, String title, long amountMinor,
                              String currency, String category, String productUrl, RequestStatus status,
                              String agentSummary, OffsetDateTime createdAt, OffsetDateTime decidedAt) {

        static RequestView of(SpendingRequest r) {
            return new RequestView(r.getId(), r.getRequesterId(), r.getKind(), r.getTitle(), r.getAmountMinor(),
                    r.getCurrency(), r.getCategory(), r.getProductUrl(), r.getStatus(), r.getAgentSummary(),
                    r.getCreatedAt(), r.getDecidedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest body) {
        return RequestView.of(service.create(CurrentMember.from(jwt), body.kind(), body.title(),
                body.amountMinor(), body.category(), body.productUrl()));
    }

    @GetMapping
    public List<RequestView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentMember.from(jwt)).stream().map(RequestView::of).toList();
    }

    // hasRole('PARENT') is a first, coarse check; ApprovalPolicy inside the service is the real rule.
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('PARENT')")
    public RequestView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody(required = false) DecideRequest body) {
        return RequestView.of(service.decide(CurrentMember.from(jwt), id, Decision.APPROVED, note(body)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('PARENT')")
    public RequestView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody(required = false) DecideRequest body) {
        return RequestView.of(service.decide(CurrentMember.from(jwt), id, Decision.REJECTED, note(body)));
    }

    private static String note(DecideRequest body) {
        return body == null ? null : body.note();
    }
}
