package ae.familyfinance.core.family;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "member")
public class Member {

    @Id
    private UUID id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_band", nullable = false, length = 16)
    private AgeBand ageBand;

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 160)
    private String email;

    @Column(nullable = false)
    private boolean active;

    protected Member() {
    }

    public UUID getId() { return id; }
    public UUID getFamilyId() { return familyId; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public AgeBand getAgeBand() { return ageBand; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getEmail() { return email; }
    public boolean isActive() { return active; }
    public boolean isParent() { return role == Role.PARENT; }
}
