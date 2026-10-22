package ae.familyfinance.core.family;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FamilyRepository extends JpaRepository<Family, UUID> {
}
