package ae.familyfinance.core.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByUsername(String username);

    List<Member> findByFamilyIdOrderByRoleDescDisplayNameAsc(UUID familyId);

    @Query(value = """
            SELECT count(*) > 0 FROM consent
            WHERE subject_member_id = :memberId AND purpose = 'CHILD_ACCOUNT' AND revoked_at IS NULL
            """, nativeQuery = true)
    boolean hasChildAccountConsent(UUID memberId);
}
