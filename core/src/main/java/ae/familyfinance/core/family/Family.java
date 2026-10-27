package ae.familyfinance.core.family;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "family")
public class Family {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "setup_done_at", insertable = false, updatable = false)
    private OffsetDateTime setupDoneAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Family() {
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public boolean isDemo() { return demo; }
    /** The demo family is always set up: a reseed recreates its row without the date. */
    public boolean isSetupDone() { return demo || setupDoneAt != null; }
}
