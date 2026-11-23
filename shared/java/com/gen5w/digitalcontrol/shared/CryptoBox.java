package com.gen5w.digitalcontrol.shared;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cross-platform cryptographic primitives compatible across:
 *   - Android 4.4.2 KitKat (API 19, Gen5W Head Unit)
 *   - Modern Android 7.0 - 16+ (Companion Phone App)
 *   - Apple CryptoKit + CommonCrypto (iOS Companion App)
 *   - Standard Web/Server runtimes (Node.js, Python, Go, C#, Java)
 *
 * Uses EC P-256 (secp256r1) X.509 SPKI DER public keys, ASN.1 DER SHA256withECDSA signatures,
 * RFC 5869 HKDF-SHA256 over 32-byte normalized ECDH shared secret, AES-256-CBC-PKCS7 + HMAC-SHA256
 * (Encrypt-then-MAC with AAD), 4-character unambiguous SAS pairing codes, and RFC 2898 PBKDF2-HMAC-SHA256.
 */
public final class CryptoBox {
    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String SAS_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 unambiguous chars
    public static final int PIN_PBKDF2_ITERATIONS = 20_000;

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] B64_TABLE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] B64_INV = new int[128];

    static {
        Arrays.fill(B64_INV, -1);
        for (int i = 0; i < B64_TABLE.length; i++) {
            B64_INV[B64_TABLE[i]] = i;
        }
    }

    private CryptoBox() {}

    public static final class EncryptedBox {
        public final String ephemeralPubSpkiB64;
        public final String ivB64;
        public final String ciphertextB64;
        public final String macB64;

        public EncryptedBox(String ephemeralPubSpkiB64, String ivB64, String ciphertextB64, String macB64) {
            this.ephemeralPubSpkiB64 = ephemeralPubSpkiB64;
            this.ivB64 = ivB64;
            this.ciphertextB64 = ciphertextB64;
            this.macB64 = macB64;
        }
    }

    public static byte[] randomBytes(int len) {
        byte[] out = new byte[len];
        RNG.nextBytes(out);
        return out;
    }

    public static String randomHex(int byteLen) {
        return toHex(randomBytes(byteLen));
    }

    public static KeyPair generateEcP256KeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        try {
            kpg.initialize(new ECGenParameterSpec("secp256r1"), RNG);
            return kpg.generateKeyPair();
        } catch (Throwable ignored) {
            try {
                kpg.initialize(new ECGenParameterSpec("prime256v1"), RNG);
                return kpg.generateKeyPair();
            } catch (Throwable ignored2) {
                kpg.initialize(256, RNG);
                return kpg.generateKeyPair();
            }
        }
    }

    public static String encodePublicKeySpkiB64(PublicKey pub) {
        return base64Encode(pub.getEncoded());
    }

    public static String encodePrivateKeyPkcs8B64(PrivateKey priv) {
        return base64Encode(priv.getEncoded());
    }

    public static PublicKey decodePublicKeySpkiB64(String b64) throws GeneralSecurityException {
        byte[] der = base64Decode(b64);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePublic(new X509EncodedKeySpec(der));
    }

    public static PrivateKey decodePrivateKeyPkcs8B64(String b64) throws GeneralSecurityException {
        byte[] der = base64Decode(b64);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * Extracts the unsigned 32-byte big-endian EC P-256 private scalar S.
     * Byte-for-byte identical to Apple CryptoKit P256.Signing.PrivateKey.rawRepresentation.
     */
    public static byte[] extractEcPrivateScalar32(PrivateKey priv) throws GeneralSecurityException {
        if (!(priv instanceof ECPrivateKey)) {
            throw new GeneralSecurityException("PrivateKey is not an ECPrivateKey");
        }
        BigInteger s = ((ECPrivateKey) priv).getS();
        byte[] raw = s.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        byte[] norm = new byte[32];
        if (raw.length > 32) {
            for (int i = 0; i < raw.length - 32; i++) {
                if (raw[i] != 0) {
                    throw new GeneralSecurityException("EC scalar exceeds 32 bytes");
                }
            }
            System.arraycopy(raw, raw.length - 32, norm, 0, 32);
        } else {
            System.arraycopy(raw, 0, norm, 32 - raw.length, raw.length);
        }
        return norm;
    }

    /**
     * Reconstructs a JCA EC PrivateKey from a 32-byte big-endian P-256 private scalar and its
     * matching X.509 SPKI public key, verifying cryptographic keypair consistency.
     */
    public static PrivateKey decodeEcPrivateKeyFromScalar32(byte[] scalar32, PublicKey matchingPub)
            throws GeneralSecurityException {
        if (scalar32 == null || scalar32.length != 32) {
            throw new GeneralSecurityException("Expected 32-byte EC P-256 private scalar");
        }
        if (!(matchingPub instanceof ECPublicKey)) {
            throw new GeneralSecurityException("PublicKey is not an ECPublicKey");
        }
        BigInteger s = new BigInteger(1, scalar32);
        if (s.signum() <= 0) {
            throw new GeneralSecurityException("Invalid zero EC scalar");
        }
        ECPrivateKeySpec spec = new ECPrivateKeySpec(s, ((ECPublicKey) matchingPub).getParams());
        PrivateKey priv = KeyFactory.getInstance("EC").generatePrivate(spec);
        byte[] probe = ("GEN5W-KEY-CHECK|" + randomHex(8)).getBytes(UTF8);
        byte[] sig = signEcdsaSha256(priv, probe);
        if (!verifyEcdsaSha256(matchingPub, probe, sig)) {
            throw new GeneralSecurityException("Private scalar does not match public key");
        }
        return priv;
    }

    public static String signEcdsaSha256B64(PrivateKey priv, String canonicalMessage) throws GeneralSecurityException {
        return base64Encode(signEcdsaSha256(priv, canonicalMessage.getBytes(UTF8)));
    }

    public static byte[] signEcdsaSha256(PrivateKey priv, byte[] data) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(priv, RNG);
        sig.update(data);
        return sig.sign();
    }

    public static boolean verifyEcdsaSha256B64(PublicKey pub, String canonicalMessage, String sigDerB64) {
        if (pub == null || canonicalMessage == null || sigDerB64 == null || sigDerB64.isEmpty()) {
            return false;
        }
        try {
            byte[] sigBytes = base64Decode(sigDerB64);
            return verifyEcdsaSha256(pub, canonicalMessage.getBytes(UTF8), sigBytes);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean verifyEcdsaSha256(PublicKey pub, byte[] data, byte[] sigDer) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pub);
            sig.update(data);
            return sig.verify(sigDer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Computes normalized 32-byte big-endian P-256 ECDH shared secret Z (matches Apple CryptoKit IKM).
     */
    public static byte[] ecdhSharedSecret32(PrivateKey myPriv, PublicKey peerPub) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(myPriv);
        ka.doPhase(peerPub, true);
        byte[] raw = ka.generateSecret();
        if (raw.length == 32) {
            return raw;
        }
        byte[] norm = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, norm, 0, 32);
        } else {
            System.arraycopy(raw, 0, norm, 32 - raw.length, raw.length);
        }
        return norm;
    }

    /**
     * RFC 5869 HKDF-SHA256 (Extract + Expand). Matches Apple CryptoKit hkdfDerivedSymmetricKey.
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen) throws GeneralSecurityException {
        if (outLen <= 0 || outLen > 255 * 32) {
            throw new IllegalArgumentException("Invalid HKDF length: " + outLen);
        }
        byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        byte[] prk = hmacSha256(actualSalt, ikm);
        byte[] actualInfo = info == null ? new byte[0] : info;

        ByteArrayOutputStream okm = new ByteArrayOutputStream(outLen + 32);
        byte[] prevT = new byte[0];
        int blocks = (outLen + 31) / 32;
        for (int i = 1; i <= blocks; i++) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            if (prevT.length > 0) {
                mac.update(prevT);
            }
            if (actualInfo.length > 0) {
                mac.update(actualInfo);
            }
            mac.update((byte) i);
            prevT = mac.doFinal();
            okm.write(prevT, 0, prevT.length);
        }
        byte[] full = okm.toByteArray();
        return Arrays.copyOf(full, outLen);
    }

    /**
     * Derives the 4-character unambiguous visual confirmation code (SAS-4) from the pairing transcript
     * and ephemeral ECDH shared secret so both car and phone show the exact same 4 characters and
     * defeat Man-in-the-Middle attacks.
     */
    public static String deriveSas4Code(
            PrivateKey myEphemeralPriv,
            PublicKey peerEphemeralPub,
            String carStaticPubB64,
            String phoneStaticPubB64,
            String carEphPubB64,
            String phoneEphPubB64,
            String carNonce,
            String phoneNonce) throws GeneralSecurityException {
        String transcript = "GEN5W-SAS-V1|"
                + carStaticPubB64.trim() + "|"
                + phoneStaticPubB64.trim() + "|"
                + carEphPubB64.trim() + "|"
                + phoneEphPubB64.trim() + "|"
                + carNonce.trim() + "|"
                + phoneNonce.trim();
        byte[] salt = sha256(transcript.getBytes(UTF8));
        byte[] z = ecdhSharedSecret32(myEphemeralPriv, peerEphemeralPub);
        byte[] sasBytes = hkdfSha256(z, salt, "GEN5W-SAS4".getBytes(UTF8), 4);
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            chars[i] = SAS_ALPHABET.charAt((sasBytes[i] & 0xFF) % 32);
        }
        return new String(chars);
    }

    /**
     * End-to-end encrypts UTF-8 plaintext to recipientStaticPub using fresh ephemeral ECDH-P256 +
     * HKDF-SHA256 + AES-256-CBC-PKCS7 + HMAC-SHA256 (Encrypt-then-MAC with AAD).
     */
    public static EncryptedBox encryptToPublicKey(
            PublicKey recipientStaticPub,
            String contextSalt,
            String aad,
            String plaintextUtf8) throws GeneralSecurityException {
        KeyPair eph = generateEcP256KeyPair();
        String ephPubB64 = encodePublicKeySpkiB64(eph.getPublic());
        byte[] z = ecdhSharedSecret32(eph.getPrivate(), recipientStaticPub);
        byte[] keyMat = hkdfSha256(
                z,
                (contextSalt == null ? "" : contextSalt).getBytes(UTF8),
                "GEN5W-E2EE-V1".getBytes(UTF8),
                64);
        byte[] encKey = Arrays.copyOfRange(keyMat, 0, 32);
        byte[] macKey = Arrays.copyOfRange(keyMat, 32, 64);

        byte[] iv = randomBytes(16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(plaintextUtf8.getBytes(UTF8));

        String effectiveAad = (aad == null ? "" : aad) + "|" + ephPubB64;
        byte[] mac = computeEtMTag(macKey, effectiveAad.getBytes(UTF8), iv, ciphertext);
        return new EncryptedBox(
                ephPubB64,
                base64Encode(iv),
                base64Encode(ciphertext),
                base64Encode(mac));
    }

    /**
     * Verifies HMAC-SHA256 in constant time and decrypts an EncryptedBox addressed to recipientStaticPriv.
     */
    public static String decryptWithPrivateKey(
            PrivateKey recipientStaticPriv,
            String contextSalt,
            String aad,
            EncryptedBox box) throws GeneralSecurityException {
        if (box == null || box.ephemeralPubSpkiB64 == null || box.ivB64 == null
                || box.ciphertextB64 == null || box.macB64 == null) {
            throw new GeneralSecurityException("Incomplete encrypted envelope");
        }
        PublicKey ephPub = decodePublicKeySpkiB64(box.ephemeralPubSpkiB64);
        byte[] iv = base64Decode(box.ivB64);
        byte[] ciphertext = base64Decode(box.ciphertextB64);
        byte[] mac = base64Decode(box.macB64);
        if (iv.length != 16 || mac.length != 32 || ciphertext.length == 0 || (ciphertext.length % 16) != 0) {
            throw new GeneralSecurityException("Malformed IV, MAC, or ciphertext block length");
        }

        byte[] z = ecdhSharedSecret32(recipientStaticPriv, ephPub);
        byte[] keyMat = hkdfSha256(
                z,
                (contextSalt == null ? "" : contextSalt).getBytes(UTF8),
                "GEN5W-E2EE-V1".getBytes(UTF8),
                64);
        byte[] encKey = Arrays.copyOfRange(keyMat, 0, 32);
        byte[] macKey = Arrays.copyOfRange(keyMat, 32, 64);

        String effectiveAad = (aad == null ? "" : aad) + "|" + box.ephemeralPubSpkiB64.trim();
        byte[] expectedMac = computeEtMTag(macKey, effectiveAad.getBytes(UTF8), iv, ciphertext);
        if (!constantTimeEquals(expectedMac, mac)) {
            throw new GeneralSecurityException("E2EE HMAC authentication failed");
        }

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, UTF8);
    }

    /**
     * Symmetric session encryption using a 64-byte session key (32B AES-256 + 32B HMAC-SHA256)
     * derived during mutual Bluetooth challenge-response authentication.
     */
    public static String encryptSessionFrame(byte[] sessionKey64, long seq, String plaintextJson)
            throws GeneralSecurityException {
        byte[] encKey = Arrays.copyOfRange(sessionKey64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(sessionKey64, 32, 64);
        byte[] iv = randomBytes(16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintextJson.getBytes(UTF8));
        byte[] aad = ("SEQ:" + seq).getBytes(UTF8);
        byte[] mac = computeEtMTag(macKey, aad, iv, ct);
        return seq + "." + base64Encode(iv) + "." + base64Encode(ct) + "." + base64Encode(mac);
    }

    public static String decryptSessionFrame(byte[] sessionKey64, long minAllowedSeq, String packedFrame)
            throws GeneralSecurityException {
        if (packedFrame == null) throw new GeneralSecurityException("Null session frame");
        String[] parts = packedFrame.trim().split("\\.");
        if (parts.length != 4) throw new GeneralSecurityException("Malformed session frame");
        long seq;
        try {
            seq = Long.parseLong(parts[0]);
        } catch (NumberFormatException nfe) {
            throw new GeneralSecurityException("Bad frame sequence");
        }
        if (seq < minAllowedSeq) {
            throw new GeneralSecurityException("Replayed session frame seq=" + seq);
        }
        byte[] iv = base64Decode(parts[1]);
        byte[] ct = base64Decode(parts[2]);
        byte[] mac = base64Decode(parts[3]);
        if (iv.length != 16 || mac.length != 32 || ct.length == 0 || (ct.length % 16) != 0) {
            throw new GeneralSecurityException("Invalid session cipher parameters");
        }
        byte[] encKey = Arrays.copyOfRange(sessionKey64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(sessionKey64, 32, 64);
        byte[] expected = computeEtMTag(macKey, ("SEQ:" + seq).getBytes(UTF8), iv, ct);
        if (!constantTimeEquals(expected, mac)) {
            throw new GeneralSecurityException("Session frame MAC mismatch");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(ct), UTF8);
    }

    private static byte[] computeEtMTag(byte[] macKey, byte[] aad, byte[] iv, byte[] ct)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        mac.update(int32Be(aad.length));
        mac.update(aad);
        mac.update(iv);
        mac.update(ct);
        return mac.doFinal();
    }

    private static byte[] int32Be(int v) {
        return new byte[] {
                (byte) ((v >>> 24) & 0xFF),
                (byte) ((v >>> 16) & 0xFF),
                (byte) ((v >>> 8) & 0xFF),
                (byte) (v & 0xFF)
        };
    }

    /**
     * Pure-Java RFC 2898 PBKDF2-HMAC-SHA256 (works on Android 4.4.2 API 19 where SecretKeyFactory
     * lacks PBKDF2WithHmacSHA256).
     */
    public static byte[] pbkdf2HmacSha256(String pin, byte[] salt, int iterations, int dkLen)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pin.getBytes(UTF8), "HmacSHA256"));
        int blocks = (dkLen + 31) / 32;
        byte[] out = new byte[blocks * 32];
        byte[] blockInput = new byte[salt.length + 4];
        System.arraycopy(salt, 0, blockInput, 0, salt.length);

        for (int b = 1; b <= blocks; b++) {
            blockInput[salt.length] = (byte) ((b >>> 24) & 0xFF);
            blockInput[salt.length + 1] = (byte) ((b >>> 16) & 0xFF);
            blockInput[salt.length + 2] = (byte) ((b >>> 8) & 0xFF);
            blockInput[salt.length + 3] = (byte) (b & 0xFF);

            byte[] u = mac.doFinal(blockInput);
            byte[] t = Arrays.copyOf(u, u.length);
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < 32; j++) {
                    t[j] ^= u[j];
                }
            }
            System.arraycopy(t, 0, out, (b - 1) * 32, 32);
        }
        return Arrays.copyOf(out, dkLen);
    }

    public static final class PassphraseBox {
        public final int iterations;
        public final String saltB64;
        public final String ivB64;
        public final String ciphertextB64;
        public final String macB64;

        public PassphraseBox(int iterations, String saltB64, String ivB64, String ciphertextB64, String macB64) {
            this.iterations = iterations;
            this.saltB64 = saltB64;
            this.ivB64 = ivB64;
            this.ciphertextB64 = ciphertextB64;
            this.macB64 = macB64;
        }
    }

    /**
     * Encrypts UTF-8 plaintext under a user passphrase via PBKDF2-HMAC-SHA256 (64B output ->
     * 32B AES-256-CBC-PKCS7 + 32B HMAC-SHA256 Encrypt-then-MAC with AAD).
     */
    public static PassphraseBox encryptWithPassphrase(
            String passphrase,
            String aadDomain,
            int iterations,
            String plaintextUtf8) throws GeneralSecurityException {
        if (passphrase == null || passphrase.length() < 6) {
            throw new GeneralSecurityException("Passphrase must be at least 6 characters");
        }
        int iter = Math.max(20_000, iterations);
        byte[] salt = randomBytes(16);
        byte[] iv = randomBytes(16);
        byte[] key64 = pbkdf2HmacSha256(passphrase, salt, iter, 64);
        byte[] encKey = Arrays.copyOfRange(key64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(key64, 32, 64);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintextUtf8.getBytes(UTF8));

        String saltB64 = base64Encode(salt);
        byte[] aad = ((aadDomain == null ? "" : aadDomain) + "|" + iter + "|" + saltB64).getBytes(UTF8);
        byte[] mac = computeEtMTag(macKey, aad, iv, ct);
        return new PassphraseBox(iter, saltB64, base64Encode(iv), base64Encode(ct), base64Encode(mac));
    }

    public static String decryptWithPassphrase(
            String passphrase,
            String aadDomain,
            PassphraseBox box) throws GeneralSecurityException {
        if (passphrase == null || passphrase.isEmpty() || box == null) {
            throw new GeneralSecurityException("Passphrase required");
        }
        if (box.iterations < 10_000 || box.iterations > 2_000_000) {
            throw new GeneralSecurityException("Unsupported KDF iteration count");
        }
        byte[] salt = base64Decode(box.saltB64);
        byte[] iv = base64Decode(box.ivB64);
        byte[] ct = base64Decode(box.ciphertextB64);
        byte[] mac = base64Decode(box.macB64);
        if (salt.length < 16 || iv.length != 16 || mac.length != 32 || ct.length == 0 || (ct.length % 16) != 0) {
            throw new GeneralSecurityException("Malformed encrypted backup file");
        }

        byte[] key64 = pbkdf2HmacSha256(passphrase, salt, box.iterations, 64);
        byte[] encKey = Arrays.copyOfRange(key64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(key64, 32, 64);

        byte[] aad = ((aadDomain == null ? "" : aadDomain) + "|" + box.iterations + "|" + box.saltB64).getBytes(UTF8);
        byte[] expectedMac = computeEtMTag(macKey, aad, iv, ct);
        if (!constantTimeEquals(expectedMac, mac)) {
            throw new GeneralSecurityException("Incorrect passphrase or corrupted backup file");
        }

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(ct), UTF8);
    }

    public static String derivePseudonymousCarId(String installSaltHex, String hardwareHint) {
        String material = "GEN5W-CAR-ID-V1|" + installSaltHex + "|" + (hardwareHint == null ? "" : hardwareHint);
        String hex = sha256Hex(material.getBytes(UTF8));
        return "car_" + hex.substring(0, 24);
    }

    public static String deriveKeyFingerprintHex(String spkiB64) {
        return sha256Hex(base64Decode(spkiB64));
    }

    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return toHex(sha256(data));
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    public static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] fromHex(String hex) {
        String s = hex == null ? "" : hex.trim();
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("Odd hex string length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex digit");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** RFC 4648 Standard Base64 (no line wraps) — portable across API 19 and Host JVM. */
    public static String base64Encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int b1 = (i < data.length) ? (data[i++] & 0xFF) : -1;
            int b2 = (i < data.length) ? (data[i++] & 0xFF) : -1;

            sb.append(B64_TABLE[b0 >>> 2]);
            if (b1 < 0) {
                sb.append(B64_TABLE[(b0 & 0x03) << 4]);
                sb.append("==");
            } else if (b2 < 0) {
                sb.append(B64_TABLE[((b0 & 0x03) << 4) | (b1 >>> 4)]);
                sb.append(B64_TABLE[(b1 & 0x0F) << 2]);
                sb.append('=');
            } else {
                sb.append(B64_TABLE[((b0 & 0x03) << 4) | (b1 >>> 4)]);
                sb.append(B64_TABLE[((b1 & 0x0F) << 2) | (b2 >>> 6)]);
                sb.append(B64_TABLE[b2 & 0x3F]);
            }
        }
        return sb.toString();
    }

    public static byte[] base64Decode(String b64) {
        if (b64 == null) return new byte[0];
        String clean = b64.trim();
        if (clean.isEmpty()) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream((clean.length() * 3) / 4);
        int accum = 0;
        int bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            if (c == '=') break;
            int val = (c < 128) ? B64_INV[c] : -1;
            if (val < 0) {
                if (c == '-') val = 62;
                else if (c == '_') val = 63;
                else throw new IllegalArgumentException("Invalid Base64 char: " + c);
            }
            accum = (accum << 6) | val;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bos.write((accum >>> bits) & 0xFF);
            }
        }
        return bos.toByteArray();
    }
}
