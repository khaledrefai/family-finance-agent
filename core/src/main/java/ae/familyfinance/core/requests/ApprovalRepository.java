package ae.familyfinance.core.requests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
}
