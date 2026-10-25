package ae.familyfinance.core.live;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts a web push message for one phone, exactly as RFC 8291 ("Message Encryption for Web Push") says, with
 * the aes128gcm content coding of RFC 8188. Only standard JDK building blocks are used (P-256 ECDH, HMAC-SHA-256
 * for HKDF, AES-128-GCM); {@code WebPushCryptoTest} checks the output byte for byte against the RFC's own
 * worked example (Appendix A), so this is the published algorithm, not a home-made one.
 *
 * <p>Why not a library: the usual Java web-push library pulls in two HTTP clients and an old jose4j. The VAPID
 * token is signed with Nimbus JOSE, which the core already uses for logins.
 */
public final class WebPushCrypto {

    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    /** The encrypted body to POST to the phone's push endpoint. {@code uaPublic} and {@code authSecret} come from the phone. */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        KeyPair ephemeral = newKeyPair();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, (ECPrivateKey) ephemeral.getPrivate(),
                (ECPublicKey) ephemeral.getPublic(), salt);
    }

    /** The same with the sender's key pair and salt given, so the RFC's example can be replayed in a test. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, ECPrivateKey asPrivate,
                          ECPublicKey asPublic, byte[] salt) {
        try {
            byte[] asPublicBytes = encodePoint(asPublic);
            byte[] ecdhSecret = ecdh(asPrivate, decodePublic(uaPublic));

            // RFC 8291 section 3.4: combine the shared secret with the phone's auth secret
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublicBytes);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));

            // RFC 8188 section 2.2 and 2.3: content encryption key and nonce
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

            // One record: the message, then the 0x02 "last record" delimiter, no extra padding
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] cipherText = aes.doFinal(concat(plaintext, new byte[] {2}));

            // Header: salt (16) | record size (4) | key id length (1) | key id = sender's public key (65)
            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublicBytes.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublicBytes.length).put(asPublicBytes);
            return concat(header.array(), cipherText);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the push message", e);
        }
    }

    // ------------------------------------------------------------------ P-256 keys as raw bytes (what browsers use)

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 65 bytes: 0x04, then X and Y (32 bytes each). */
    public static byte[] encodePoint(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(key.getW().getAffineX(), out, 1);
        copy32(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] encodePrivate(ECPrivateKey key) {
        byte[] out = new byte[32];
        copy32(key.getS(), out, 0);
        return out;
    }

    public static ECPublicKey decodePublic(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Not an uncompressed P-256 public key");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
    }

    public static ECPrivateKey decodePrivate(byte[] raw) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
    }

    public static byte[] b64(String base64url) {
        return Base64.getUrlDecoder().decode(base64url.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] ecdh(ECPrivateKey mine, ECPublicKey theirs) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        return ka.generateSecret();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static void copy32(BigInteger n, byte[] out, int at) {
        byte[] b = n.toByteArray(); // big-endian, may have a leading 0 or be shorter than 32
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, at + 32 - len, len);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
