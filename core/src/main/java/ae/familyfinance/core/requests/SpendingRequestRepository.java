package ae.familyfinance.core.requests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpendingRequestRepository extends JpaRepository<SpendingRequest, UUID> {

    /** Always look a request up inside the caller's family; another family's request simply "does not exist". */
    Optional<SpendingRequest> findByIdAndFamilyId(UUID id, UUID familyId);

    /** The lists leave out CANCELLED requests: those are repeats of one already sent (see V8). */
    List<SpendingRequest> findByFamilyIdAndStatusNotOrderByCreatedAtDesc(UUID familyId, RequestStatus status);

    List<SpendingRequest> findByFamilyIdAndRequesterIdAndStatusNotOrderByCreatedAtDesc(UUID familyId, UUID requesterId,
                                                                                      RequestStatus status);

    /** The same ask from the same member, still waiting or blocked, sent after {@code since}: a repeat tap. */
    Optional<SpendingRequest> findFirstByRequesterIdAndKindAndTitleAndAmountMinorAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID requesterId, RequestKind kind, String title, long amountMinor, List<RequestStatus> statuses,
            OffsetDateTime since);
}
