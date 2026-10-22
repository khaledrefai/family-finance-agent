package ae.familyfinance.core.family;

import ae.familyfinance.core.auth.CurrentMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class FamilyController {

    private final MemberRepository members;
    private final FamilyRepository families;

    public FamilyController(MemberRepository members, FamilyRepository families) {
        this.members = members;
        this.families = families;
    }

    public record MeResponse(UUID memberId, String displayName, Role role, AgeBand ageBand,
                             UUID familyId, String familyName, String currency) {
    }

    public record MemberView(UUID id, String displayName, Role role, AgeBand ageBand) {
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        CurrentMember current = CurrentMember.from(jwt);
        Member m = members.findById(current.memberId()).orElseThrow();
        Family f = families.findById(current.familyId()).orElseThrow();
        return new MeResponse(m.getId(), m.getDisplayName(), m.getRole(), m.getAgeBand(),
                f.getId(), f.getName(), f.getCurrency());
    }

    /** Everyone in the family can see who is in it; only names, roles and age bands are shown. */
    @GetMapping("/family/members")
    public List<MemberView> members(@AuthenticationPrincipal Jwt jwt) {
        CurrentMember current = CurrentMember.from(jwt);
        return members.findByFamilyIdOrderByRoleDescDisplayNameAsc(current.familyId()).stream()
                .map(m -> new MemberView(m.getId(), m.getDisplayName(), m.getRole(), m.getAgeBand()))
                .toList();
    }
}
