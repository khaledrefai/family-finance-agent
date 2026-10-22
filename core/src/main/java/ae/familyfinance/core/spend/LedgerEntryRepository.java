package ae.familyfinance.core.spend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByFamilyIdAndMemberIdAndDirectionAndOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(
            UUID familyId, UUID memberId, String direction, LocalDate from, LocalDate to);

    Optional<LedgerEntry> findFirstByFamilyIdAndDuplicateHashOrderByCreatedAtAsc(UUID familyId, String duplicateHash);

    Optional<LedgerEntry> findFirstByFamilyIdAndMemberIdAndDirectionOrderByOccurredOnDesc(
            UUID familyId, UUID memberId, String direction);
}
