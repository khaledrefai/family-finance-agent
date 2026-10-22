package ae.familyfinance.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    public JwtProperties {
        // HS256 needs a key of at least 256 bits; refuse to start with a weak one.
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret (APP_JWT_SECRET) must be at least 32 bytes");
        }
        if (issuer == null) issuer = "family-finance-core";
        if (ttl == null) ttl = Duration.ofHours(8);
    }
}
