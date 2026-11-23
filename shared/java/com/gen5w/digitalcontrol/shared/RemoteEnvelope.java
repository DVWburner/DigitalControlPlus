package com.gen5w.digitalcontrol.shared;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;

/**
 * Zero-Trust End-to-End Encrypted & Signed remote envelope contracts for Digital Control+.
 *
 * Security guarantees:
 * 1. Only a phone holding a paired EC P-256 private key (whose public key was physically paired
 *    over Bluetooth inside the car after PIN unlock + 4-character SAS verification) can sign a
 *    valid command envelope. Even a completely compromised remote server CANNOT forge commands.
 * 2. Command payloads (action, args, climate theme) are end-to-end encrypted to the car's EC P-256
 *    public key using ephemeral ECDH + HKDF-SHA256 + AES-256-CBC + HMAC-SHA256. The server routes
 *    only opaque ciphertext.
 * 3. Every command carries a strict 5-minute TTL (300,000 ms) enforced both by the server queue
 *    and independently by the car's local clock + persistent commandId anti-replay ring buffer.
 */
public final class RemoteEnvelope {
    public static final long COMMAND_TTL_MS = 5L * 60L * 1000L; // 5 minutes
    public static final long MAX_CLOCK_SKEW_MS = 90L * 1000L;   // 90s tolerance for NTP drift

    private RemoteEnvelope() {}

    /**
     * Canonical string signed by Car's EC P-256 private key during Bluetooth pairing to prove
     * to the remote server (without trusting unverified uploads) that this phonePublicKey was
     * genuinely authorized by carId.
     */
    public static String buildPairingGrantCanonical(
            String carId,
            String phoneId,
            String phonePubSpkiB64,
            long pairedAtMs) {
        String phoneKeyHash = CryptoBox.deriveKeyFingerprintHex(phonePubSpkiB64);
        return "GEN5W-GRANT-V1|" + carId + "|" + phoneId + "|" + phoneKeyHash + "|" + pairedAtMs;
    }

    public static String signPairingGrant(
            PrivateKey carPriv,
            String carId,
            String phoneId,
            String phonePubSpkiB64,
            long pairedAtMs) throws GeneralSecurityException {
        return CryptoBox.signEcdsaSha256B64(
                carPriv,
                buildPairingGrantCanonical(carId, phoneId, phonePubSpkiB64, pairedAtMs));
    }

    public static boolean verifyPairingGrant(
            PublicKey carPub,
            String carId,
            String phoneId,
            String phonePubSpkiB64,
            long pairedAtMs,
            String grantSigB64) {
        try {
            return CryptoBox.verifyEcdsaSha256B64(
                    carPub,
                    buildPairingGrantCanonical(carId, phoneId, phonePubSpkiB64, pairedAtMs),
                    grantSigB64);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String buildEnvelopeSigningCanonical(
            String commandId,
            String carId,
            String phoneId,
            long issuedAtMs,
            long expiresAtMs,
            CryptoBox.EncryptedBox box) {
        String boxDigest = CryptoBox.sha256Hex(
                (box.ephemeralPubSpkiB64 + "." + box.ivB64 + "." + box.ciphertextB64 + "." + box.macB64)
                        .getBytes(CryptoBox.UTF8));
        return "GEN5W-ENV-V1|" + commandId + "|" + carId + "|" + phoneId
                + "|" + issuedAtMs + "|" + expiresAtMs + "|" + boxDigest;
    }

    public static String buildEnvelopeAad(
            String commandId,
            String carId,
            String phoneId,
            long issuedAtMs,
            long expiresAtMs) {
        return "GEN5W-AAD-V1|" + commandId + "|" + carId + "|" + phoneId + "|" + issuedAtMs + "|" + expiresAtMs;
    }

    /**
     * Creates a signed + E2EE command submission JSON ready for POST /api/v1/commands.
     */
    public static JSONObject createSignedEncryptedCommand(
            String carId,
            PublicKey carPublicKey,
            String phoneId,
            PrivateKey phonePrivateKey,
            String phonePublicKeySpkiB64,
            long pairedAtMs,
            String pairingGrantSigB64,
            String action,
            String arg,
            JSONObject themeJson,
            String displaySummary,
            long nowMs) throws GeneralSecurityException, JSONException {
        String commandId = "cmd_" + UUID.randomUUID().toString().replace("-", "");
        long issuedAtMs = nowMs;
        long expiresAtMs = nowMs + COMMAND_TTL_MS;

        JSONObject inner = new JSONObject();
        inner.put("v", 1);
        inner.put("commandId", commandId);
        inner.put("carId", carId);
        inner.put("phoneId", phoneId);
        inner.put("action", action);
        inner.put("arg", arg == null ? "" : arg);
        if (themeJson != null) {
            inner.put("climateTheme", themeJson);
        }
        inner.put("issuedAtMs", issuedAtMs);
        inner.put("expiresAtMs", expiresAtMs);
        inner.put("nonce", CryptoBox.randomHex(16));

        String aad = buildEnvelopeAad(commandId, carId, phoneId, issuedAtMs, expiresAtMs);
        CryptoBox.EncryptedBox box = CryptoBox.encryptToPublicKey(
                carPublicKey,
                commandId,
                aad,
                inner.toString());

        String canonical = buildEnvelopeSigningCanonical(commandId, carId, phoneId, issuedAtMs, expiresAtMs, box);
        String phoneSigB64 = CryptoBox.signEcdsaSha256B64(phonePrivateKey, canonical);

        JSONObject e2ee = new JSONObject();
        e2ee.put("ephemeralPubKey", box.ephemeralPubSpkiB64);
        e2ee.put("iv", box.ivB64);
        e2ee.put("ciphertext", box.ciphertextB64);
        e2ee.put("mac", box.macB64);

        JSONObject grant = new JSONObject();
        grant.put("phonePublicKey", phonePublicKeySpkiB64);
        grant.put("pairedAtMs", pairedAtMs);
        grant.put("carGrantSignature", pairingGrantSigB64 == null ? "" : pairingGrantSigB64);

        JSONObject outer = new JSONObject();
        outer.put("commandId", commandId);
        outer.put("carId", carId);
        outer.put("phoneId", phoneId);
        outer.put("issuedAtMs", issuedAtMs);
        outer.put("expiresAtMs", expiresAtMs);
        outer.put("ttlSeconds", 300);
        outer.put("e2ee", e2ee);
        outer.put("phoneSignature", phoneSigB64);
        outer.put("pairingProof", grant);
        if (displaySummary != null && !displaySummary.isEmpty()) {
            // Local/UI convenience hint; never trusted by the car (car only executes decrypted inner payload).
            outer.put("clientHint", displaySummary);
        }
        return outer;
    }

    /**
     * Verifies ECDSA signature against a locally trusted paired phonePublicKey, enforces the
     * 5-minute TTL window, decrypts the E2EE payload with carPrivateKey, and cross-checks inner
     * envelope bindings.
     */
    public static JSONObject verifyAndDecryptOnCar(
            JSONObject outer,
            String expectedCarId,
            PrivateKey carPrivateKey,
            PublicKey trustedPhonePublicKey,
            long nowMs) throws GeneralSecurityException, JSONException {
        if (outer == null) throw new GeneralSecurityException("Null command envelope");
        String commandId = outer.optString("commandId", "");
        String carId = outer.optString("carId", "");
        String phoneId = outer.optString("phoneId", "");
        long issuedAtMs = outer.optLong("issuedAtMs", 0L);
        long expiresAtMs = outer.optLong("expiresAtMs", 0L);
        String phoneSig = outer.optString("phoneSignature", "");
        JSONObject e2ee = outer.optJSONObject("e2ee");

        if (commandId.isEmpty() || !expectedCarId.equals(carId) || phoneId.isEmpty() || e2ee == null) {
            throw new GeneralSecurityException("Envelope header mismatch or missing fields");
        }
        if (trustedPhonePublicKey == null) {
            throw new GeneralSecurityException("Unknown or untrusted phoneId: " + phoneId);
        }
        if (expiresAtMs <= issuedAtMs || (expiresAtMs - issuedAtMs) > (COMMAND_TTL_MS + 5000L)) {
            throw new GeneralSecurityException("Envelope TTL exceeds 5-minute maximum");
        }
        if (nowMs > expiresAtMs + MAX_CLOCK_SKEW_MS) {
            throw new GeneralSecurityException("Remote command expired (>5 minutes old)");
        }
        if (issuedAtMs > nowMs + MAX_CLOCK_SKEW_MS) {
            throw new GeneralSecurityException("Envelope issuedAtMs is too far in the future");
        }

        CryptoBox.EncryptedBox box = new CryptoBox.EncryptedBox(
                e2ee.optString("ephemeralPubKey", ""),
                e2ee.optString("iv", ""),
                e2ee.optString("ciphertext", ""),
                e2ee.optString("mac", ""));

        String canonical = buildEnvelopeSigningCanonical(commandId, carId, phoneId, issuedAtMs, expiresAtMs, box);
        if (!CryptoBox.verifyEcdsaSha256B64(trustedPhonePublicKey, canonical, phoneSig)) {
            throw new GeneralSecurityException("ECDSA signature verification failed for phoneId=" + phoneId);
        }

        String aad = buildEnvelopeAad(commandId, carId, phoneId, issuedAtMs, expiresAtMs);
        String plainJson = CryptoBox.decryptWithPrivateKey(carPrivateKey, commandId, aad, box);
        JSONObject inner = new JSONObject(plainJson);

        if (!commandId.equals(inner.optString("commandId"))
                || !expectedCarId.equals(inner.optString("carId"))
                || !phoneId.equals(inner.optString("phoneId"))
                || issuedAtMs != inner.optLong("issuedAtMs")
                || expiresAtMs != inner.optLong("expiresAtMs")) {
            throw new GeneralSecurityException("Inner decrypted payload does not match signed outer header");
        }
        return inner;
    }

    public static String buildCancelCanonical(String commandId, String carId, String phoneId, long timestampMs) {
        return "GEN5W-CANCEL-V1|" + commandId + "|" + carId + "|" + phoneId + "|" + timestampMs;
    }

    public static String buildCarPollCanonical(String carId, long timestampMs, String nonce) {
        return "GEN5W-POLL-V1|" + carId + "|" + timestampMs + "|" + nonce;
    }

    public static String buildCarAckCanonical(
            String commandId,
            String carId,
            boolean ok,
            long executedAtMs,
            String resultHashHex) {
        return "GEN5W-ACK-V1|" + commandId + "|" + carId + "|" + (ok ? "1" : "0") + "|" + executedAtMs + "|" + resultHashHex;
    }

    public static String buildCarRegisterCanonical(String carId, String carPubSpkiB64, long timestampMs) {
        return "GEN5W-CAR-REG-V1|" + carId + "|" + CryptoBox.deriveKeyFingerprintHex(carPubSpkiB64) + "|" + timestampMs;
    }

    /**
     * Normalizes user-entered relay server URLs:
     * - Empty / blank -> "" (Bluetooth-only mode)
     * - Bare domain (e.g. "remote.example.com") -> "https://remote.example.com"
     * - "http://..." -> upgraded to "https://..."
     * - Trailing slashes stripped cleanly.
     */
    public static String normalizeServerUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        while (s.regionMatches(true, 0, "https://", 0, 8) || s.regionMatches(true, 0, "http://", 0, 7)) {
            if (s.regionMatches(true, 0, "https://", 0, 8)) {
                s = s.substring(8).trim();
            } else {
                s = s.substring(7).trim();
            }
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty()) return "";
        return "https://" + s;
    }
}
