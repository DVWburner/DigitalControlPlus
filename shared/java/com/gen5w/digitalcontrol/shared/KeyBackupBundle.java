package com.gen5w.digitalcontrol.shared;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-platform passphrase-encrypted Security Key & Vehicle Pairing backup container (.g5wkey).
 *
 * Interoperable byte-for-byte between the Android and iOS companion apps using:
 *   - 32-byte raw big-endian EC P-256 private scalar (phonePrivRawB64) + X.509 SPKI public key
 *   - PBKDF2-HMAC-SHA256 (100,000 iterations) -> AES-256-CBC-PKCS7 + HMAC-SHA256 (EtM with AAD)
 */
public final class KeyBackupBundle {
    public static final String FORMAT_ID = "GEN5W-KEY-BACKUP-V1";
    public static final int DEFAULT_KDF_ITERATIONS = 100_000;

    public String phoneId = "";
    public String phoneName = "";
    public String phonePrivRawB64 = "";
    public String phonePubSpkiB64 = "";

    public String carId = "";
    public String carName = "";
    public String carBtAddress = "";
    public String carPubSpkiB64 = "";
    public long pairedAtMs = 0L;
    public String pairingGrantSigB64 = "";

    public boolean useMiles = true;
    public List<ClimateTheme> climateThemes = new ArrayList<ClimateTheme>();

    // Optional Private Edition fields (blank in Public Edition)
    public String remoteUrl = "";
    public String remoteAccountToken = "";

    public PrivateKey reconstructVerifiedPrivateKey() throws GeneralSecurityException {
        PublicKey pub = CryptoBox.decodePublicKeySpkiB64(phonePubSpkiB64);
        byte[] raw32 = CryptoBox.base64Decode(phonePrivRawB64);
        return CryptoBox.decodeEcPrivateKeyFromScalar32(raw32, pub);
    }

    public JSONObject toPlainJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("v", 1);
        j.put("phoneId", safe(phoneId));
        j.put("phoneName", safe(phoneName));
        j.put("phonePrivRawB64", safe(phonePrivRawB64));
        j.put("phonePubSpkiB64", safe(phonePubSpkiB64));
        j.put("carId", safe(carId));
        j.put("carName", safe(carName));
        j.put("carBtAddress", safe(carBtAddress));
        j.put("carPubSpkiB64", safe(carPubSpkiB64));
        j.put("pairedAtMs", pairedAtMs);
        j.put("pairingGrantSigB64", safe(pairingGrantSigB64));
        j.put("useMiles", useMiles);

        JSONArray themesArr = new JSONArray();
        if (climateThemes != null) {
            for (ClimateTheme t : climateThemes) {
                if (t != null) {
                    themesArr.put(t.toJson());
                }
            }
        }
        j.put("climateThemes", themesArr);

        if (!safe(remoteUrl).isEmpty()) {
            j.put("remoteUrl", safe(remoteUrl));
        }
        if (!safe(remoteAccountToken).isEmpty()) {
            j.put("remoteAccountToken", safe(remoteAccountToken));
        }
        return j;
    }

    public static KeyBackupBundle fromPlainJson(JSONObject j) throws GeneralSecurityException, JSONException {
        if (j == null) {
            throw new GeneralSecurityException("Empty decrypted backup payload");
        }
        KeyBackupBundle b = new KeyBackupBundle();
        b.phoneId = j.optString("phoneId", "").trim();
        b.phoneName = j.optString("phoneName", "").trim();
        b.phonePrivRawB64 = j.optString("phonePrivRawB64", "").trim();
        b.phonePubSpkiB64 = j.optString("phonePubSpkiB64", "").trim();
        if (b.phoneId.isEmpty() || b.phonePrivRawB64.isEmpty() || b.phonePubSpkiB64.isEmpty()) {
            throw new GeneralSecurityException("Backup is missing phone identity key");
        }
        // Cryptographically verify scalar <-> public key before accepting
        b.reconstructVerifiedPrivateKey();

        b.carId = j.optString("carId", "").trim();
        b.carName = j.optString("carName", "").trim();
        b.carBtAddress = j.optString("carBtAddress", "").trim();
        b.carPubSpkiB64 = j.optString("carPubSpkiB64", "").trim();
        b.pairedAtMs = j.optLong("pairedAtMs", 0L);
        b.pairingGrantSigB64 = j.optString("pairingGrantSigB64", "").trim();
        if (!b.carPubSpkiB64.isEmpty()) {
            CryptoBox.decodePublicKeySpkiB64(b.carPubSpkiB64);
        }

        b.useMiles = j.optBoolean("useMiles", true);
        b.remoteUrl = j.optString("remoteUrl", "").trim();
        b.remoteAccountToken = j.optString("remoteAccountToken", "").trim();

        JSONArray arr = j.optJSONArray("climateThemes");
        List<ClimateTheme> list = new ArrayList<ClimateTheme>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) {
                    list.add(ClimateTheme.fromJson(item));
                }
            }
        }
        b.climateThemes = list;
        return b;
    }

    public static String exportEncryptedJson(KeyBackupBundle bundle, String passphrase)
            throws GeneralSecurityException, JSONException {
        return exportEncryptedJson(bundle, passphrase, DEFAULT_KDF_ITERATIONS);
    }

    public static String exportEncryptedJson(KeyBackupBundle bundle, String passphrase, int iterations)
            throws GeneralSecurityException, JSONException {
        if (bundle == null) {
            throw new GeneralSecurityException("Null backup bundle");
        }
        bundle.reconstructVerifiedPrivateKey();
        String plainJson = bundle.toPlainJson().toString();
        CryptoBox.PassphraseBox box = CryptoBox.encryptWithPassphrase(
                passphrase, FORMAT_ID, iterations, plainJson);

        JSONObject env = new JSONObject();
        env.put("format", FORMAT_ID);
        env.put("kdf", "PBKDF2-HMAC-SHA256");
        env.put("iter", box.iterations);
        env.put("salt", box.saltB64);
        env.put("iv", box.ivB64);
        env.put("ct", box.ciphertextB64);
        env.put("mac", box.macB64);
        env.put("createdAtMs", System.currentTimeMillis());
        if (!safe(bundle.carName).isEmpty()) {
            env.put("hintCarName", safe(bundle.carName));
        }
        return env.toString();
    }

    public static KeyBackupBundle importEncryptedJson(String encryptedFileText, String passphrase)
            throws GeneralSecurityException, JSONException {
        if (encryptedFileText == null || encryptedFileText.trim().isEmpty()) {
            throw new GeneralSecurityException("Backup file is empty");
        }
        JSONObject env = new JSONObject(encryptedFileText.trim());
        String fmt = env.optString("format", "");
        if (!FORMAT_ID.equals(fmt)) {
            throw new GeneralSecurityException("Unrecognized backup file format");
        }
        int iter = env.optInt("iter", 0);
        CryptoBox.PassphraseBox box = new CryptoBox.PassphraseBox(
                iter,
                env.optString("salt", ""),
                env.optString("iv", ""),
                env.optString("ct", ""),
                env.optString("mac", ""));
        String plainJson = CryptoBox.decryptWithPassphrase(passphrase, FORMAT_ID, box);
        return fromPlainJson(new JSONObject(plainJson));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
