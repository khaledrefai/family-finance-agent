package ae.familyfinance.core.live;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import static ae.familyfinance.core.live.WebPushCrypto.b64;
import static org.assertj.core.api.Assertions.assertThat;

/** RFC 8291 Appendix A: the published worked example, replayed byte for byte. */
class WebPushCryptoTest {

    static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6Tlz"
            + "AC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void matches_the_rfc_8291_example() throws Exception {
        ECPrivateKey asPrivate = WebPushCrypto.decodePrivate(b64(AS_PRIVATE));
        ECPublicKey asPublic = WebPushCrypto.decodePublic(b64(AS_PUBLIC));

        byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), b64(UA_PUBLIC), b64(AUTH),
                asPrivate, asPublic, b64(SALT));

        assertThat(b64(body)).isEqualTo(BODY);
    }

    @Test
    void raw_keys_round_trip() throws Exception {
        var pair = WebPushCrypto.newKeyPair();
        byte[] pub = WebPushCrypto.encodePoint((ECPublicKey) pair.getPublic());
        byte[] priv = WebPushCrypto.encodePrivate((ECPrivateKey) pair.getPrivate());

        assertThat(pub).hasSize(65);
        assertThat(priv).hasSize(32);
        assertThat(WebPushCrypto.encodePoint(WebPushCrypto.decodePublic(pub))).isEqualTo(pub);
        assertThat(WebPushCrypto.encodePrivate(WebPushCrypto.decodePrivate(priv))).isEqualTo(priv);
    }

    @Test
    void a_fresh_message_has_the_rfc_header() throws Exception {
        byte[] body = WebPushCrypto.encrypt("hi".getBytes(StandardCharsets.UTF_8), b64(UA_PUBLIC), b64(AUTH));
        // salt 16 + record size 4 + key id length 1 + key 65 + ("hi" + delimiter + 16-byte tag)
        assertThat(body).hasSize(16 + 4 + 1 + 65 + 3 + 16);
        assertThat(body[20]).isEqualTo((byte) 65);
        assertThat(body[21]).isEqualTo((byte) 4);
    }
}
