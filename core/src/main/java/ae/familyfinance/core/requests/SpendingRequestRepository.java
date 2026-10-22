package ae.familyfinance.core.requests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpendingRequestRepository extends JpaRepository<SpendingRequest, UUID> {

    /** Always look a request up inside the caller's family; another family's request simply "does not exist". */
    Optional<SpendingRequest> findByIdAndFamilyId(UUID id, UUID familyId);

    List<SpendingRequest> findByFamilyIdOrderByCreatedAtDesc(UUID familyId);

    List<SpendingRequest> findByFamilyIdAndRequesterIdOrderByCreatedAtDesc(UUID familyId, UUID requesterId);
}
