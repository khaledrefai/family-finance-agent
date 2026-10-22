package ae.familyfinance.core.auth;

import ae.familyfinance.core.family.Role;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Who is calling, read from the verified JWT. Every query is scoped by familyId from here,
 * never from a request parameter, so one family can never read another family's data.
 */
public record CurrentMember(UUID memberId, UUID familyId, Role role) {

    public static CurrentMember from(Jwt jwt) {
        return new CurrentMember(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_FAMILY)),
                Role.valueOf(jwt.getClaimAsString(TokenService.CLAIM_ROLE)));
    }

    public boolean isParent() {
        return role == Role.PARENT;
    }
}
