package ae.familyfinance.core.live;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/**
 * Sends one web push message to one phone: encrypts it ({@link WebPushCrypto}) and POSTs it to the push service the
 * phone's browser uses (Google for Chrome, Mozilla for Firefox, Apple for Safari), signed with our VAPID key.
 */
@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    public enum Result { SENT, GONE, FAILED }

    public record Subscription(String endpoint, String p256dh, String auth) {
    }

    private final VapidKeys keys;
    private final String subject;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PushSender(VapidKeys keys, @Value("${app.push.subject:}") String subject, Clock clock) {
        this.keys = keys;
        // Push services want a way to reach whoever runs the server: a mailto: or https: address
        this.subject = subject.isBlank() ? "https://github.com/khaledrefai/family-finance-agent" : subject.trim();
        this.clock = clock;
    }

    public Result send(Subscription to, String json) {
        try {
            URI endpoint = URI.create(to.endpoint());
            byte[] body = WebPushCrypto.encrypt(json.getBytes(StandardCharsets.UTF_8),
                    WebPushCrypto.b64(to.p256dh()), WebPushCrypto.b64(to.auth()));
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "vapid t=" + token(endpoint) + ", k=" + keys.publicKey())
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", "86400")       // keep trying for a day if the phone is off
                    .header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code >= 200 && code < 300) {
                return Result.SENT;
            }
            if (code == 404 || code == 410) {
                return Result.GONE; // the phone unsubscribed or the browser was reset
            }
            log.warn("Push service answered {} for {}: {}", code, endpoint.getHost(), cut(res.body()));
            return Result.FAILED;
        } catch (IOException | RuntimeException | JOSEException e) {
            log.warn("Push failed: {}", e.toString());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    /** RFC 8292: a short-lived ES256 token naming the push service's origin, signed with our private key. */
    String token(URI endpoint) throws JOSEException {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(audience)
                .expirationTime(Date.from(clock.instant().plus(Duration.ofHours(12))))
                .subject(subject)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims);
        ECDSASigner signer = new ECDSASigner(keys.privateKey(), Curve.P_256);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }
}
