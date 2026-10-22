package ae.familyfinance.core.auth;

import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.Member;
import ae.familyfinance.core.family.MemberRepository;
import ae.familyfinance.core.family.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    public AuthController(MemberRepository members, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn,
                                UUID memberId, String displayName, Role role) {
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        Member member = members.findByUsername(body.username().trim().toLowerCase())
                .filter(Member::isActive)
                .filter(m -> passwordEncoder.matches(body.password(), m.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong username or password"));

        // PDPL: a child account only works while a parent's consent is in place.
        if (member.getRole() == Role.CHILD && !members.hasChildAccountConsent(member.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "A parent needs to give consent before this account can be used");
        }

        return new LoginResponse(tokens.issue(member), "Bearer", tokens.ttlSeconds(),
                member.getId(), member.getDisplayName(), member.getRole());
    }
}
