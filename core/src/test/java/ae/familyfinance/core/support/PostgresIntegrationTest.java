package ae.familyfinance.core.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real PostgreSQL (Flyway migrations + demo seed).
 *
 * - By default it starts a throwaway Postgres in Docker (Testcontainers). Just run `mvn test`.
 * - If TEST_DB_URL is set (e.g. in CI or a machine without Docker) it uses that database instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("demo")
public abstract class PostgresIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (EXTERNAL_URL == null) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
            POSTGRES.start(); // stopped automatically when the JVM exits
        } else {
            POSTGRES = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // No timers in tests: they call OutboxDispatcher.dispatchPending() and AlertService.check() themselves
        registry.add("app.live.scheduler", () -> "false");
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "ffa"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "ffa"));
        }
    }
}
