package com.gen5w.digitalcontrol;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.gen5w.digitalcontrol.edition.EditionConfig;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App-private trust store (/data/data/<pkg>/files/trust_vault.json).
 *
 * Security & Lifecycle Guarantees:
 *  - Lives exclusively inside app-private storage so `pm uninstall` (triggered automatically
 *    when the HMG-API mod or NEMESIS package is removed) unconditionally destroys the PIN,
 *    the car's EC P-256 identity keypair, and all paired phone trust relationships.
 *  - Additionally binds to PackageInfo.firstInstallTime so even if residual files were ever
 *    restored, a fresh install immediately wipes and resets the vault.
 */
public final class TrustVault {
    private static final String VAULT_FILE = "trust_vault.json";
    private static final int MAX_EXECUTED_RING = 128;
    private static final long PIN_LOCKOUT_MS = 15_000L;

    public static final class TrustedPhone {
        public final String phoneId;
        public final String phoneName;
        public final String platform;
        public final String btAddress;
        public final String publicKeySpkiB64;
        public final long pairedAtMs;
        public volatile long lastSeenAtMs;
        public final String pairingGrantSigB64;

        public TrustedPhone(
                String phoneId,
                String phoneName,
                String platform,
                String btAddress,
                String publicKeySpkiB64,
                long pairedAtMs,
                long lastSeenAtMs,
                String pairingGrantSigB64) {
            this.phoneId = phoneId;
            this.phoneName = phoneName;
            this.platform = platform;
            this.btAddress = btAddress == null ? "" : btAddress;
            this.publicKeySpkiB64 = publicKeySpkiB64;
            this.pairedAtMs = pairedAtMs;
            this.lastSeenAtMs = lastSeenAtMs;
            this.pairingGrantSigB64 = pairingGrantSigB64 == null ? "" : pairingGrantSigB64;
        }

        public JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("phoneId", phoneId);
            j.put("phoneName", phoneName);
            j.put("platform", platform);
            j.put("btAddress", btAddress);
            j.put("publicKeySpkiB64", publicKeySpkiB64);
            j.put("pairedAtMs", pairedAtMs);
            j.put("lastSeenAtMs", lastSeenAtMs);
            j.put("pairingGrantSigB64", pairingGrantSigB64);
            return j;
        }

        public static TrustedPhone fromJson(JSONObject j) {
            return new TrustedPhone(
                    j.optString("phoneId", ""),
                    j.optString("phoneName", "Phone"),
                    j.optString("platform", "unknown"),
                    j.optString("btAddress", ""),
                    j.optString("publicKeySpkiB64", ""),
                    j.optLong("pairedAtMs", 0L),
                    j.optLong("lastSeenAtMs", 0L),
                    j.optString("pairingGrantSigB64", ""));
        }
    }

    private final Context appContext;
    private final File file;
    private final long currentFirstInstallTime;

    private String pinSaltHex = "";
    private String pinHashHex = "";
    private int failedPinAttempts = 0;
    private long lockoutUntilMs = 0L;

    private String installSaltHex = "";
    private String carId = "";
    private String carPubSpkiB64 = "";
    private String carPrivPkcs8B64 = "";
    private String remoteServerUrl = EditionConfig.DEFAULT_REMOTE_URL;
    private String remoteAccountToken = "";
    private volatile long trustRevision = 1L;

    private final Map<String, TrustedPhone> phones = new LinkedHashMap<String, TrustedPhone>();
    private final List<String> executedCommandIds = new ArrayList<String>();

    public TrustVault(Context context) {
        this.appContext = context.getApplicationContext();
        this.file = new File(appContext.getFilesDir(), VAULT_FILE);
        this.currentFirstInstallTime = queryFirstInstallTime(appContext);
        loadLocked();
    }

    private static long queryFirstInstallTime(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi == null ? 0L : pi.firstInstallTime;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public synchronized boolean isPinConfigured() {
        return !pinHashHex.isEmpty() && !pinSaltHex.isEmpty() && !carPrivPkcs8B64.isEmpty();
    }

    public synchronized long getRemainingLockoutMs() {
        long rem = lockoutUntilMs - System.currentTimeMillis();
        return Math.max(0L, rem);
    }

    public static boolean isValidPinFormat(String pin) {
        if (pin == null) return false;
        String s = pin.trim();
        if (s.length() < 4 || s.length() > 8) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Initializes the PIN on first use and generates a fresh Car EC P-256 identity keypair.
     */
    public synchronized void setupInitialPin(String newPin, String hardwareHint) throws Exception {
        if (!isValidPinFormat(newPin)) {
            throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        }
        byte[] salt = CryptoBox.randomBytes(16);
        byte[] hash = CryptoBox.pbkdf2HmacSha256(newPin.trim(), salt, CryptoBox.PIN_PBKDF2_ITERATIONS, 32);
        this.pinSaltHex = CryptoBox.toHex(salt);
        this.pinHashHex = CryptoBox.toHex(hash);
        this.failedPinAttempts = 0;
        this.lockoutUntilMs = 0L;

        if (carPrivPkcs8B64.isEmpty() || carId.isEmpty()) {
            this.installSaltHex = CryptoBox.randomHex(32);
            this.carId = CryptoBox.derivePseudonymousCarId(installSaltHex, hardwareHint);
            KeyPair kp = CryptoBox.generateEcP256KeyPair();
            this.carPubSpkiB64 = CryptoBox.encodePublicKeySpkiB64(kp.getPublic());
            this.carPrivPkcs8B64 = CryptoBox.encodePrivateKeyPkcs8B64(kp.getPrivate());
        }
        this.trustRevision++;
        saveLocked();
    }

    public synchronized boolean verifyPin(String candidatePin) {
        if (!isPinConfigured() || candidatePin == null) return false;
        if (getRemainingLockoutMs() > 0L) return false;
        try {
            byte[] salt = CryptoBox.fromHex(pinSaltHex);
            byte[] expected = CryptoBox.fromHex(pinHashHex);
            byte[] actual = CryptoBox.pbkdf2HmacSha256(
                    candidatePin.trim(), salt, CryptoBox.PIN_PBKDF2_ITERATIONS, 32);
            if (CryptoBox.constantTimeEquals(expected, actual)) {
                failedPinAttempts = 0;
                lockoutUntilMs = 0L;
                saveLocked();
                return true;
            }
        } catch (Throwable ignored) {
        }
        failedPinAttempts++;
        if (failedPinAttempts >= 5) {
            lockoutUntilMs = System.currentTimeMillis() + PIN_LOCKOUT_MS;
            failedPinAttempts = 0;
        }
        saveLocked();
        return false;
    }

    public synchronized void resetAllAndClearPin() {
        pinSaltHex = "";
        pinHashHex = "";
        failedPinAttempts = 0;
        lockoutUntilMs = 0L;
        installSaltHex = "";
        carId = "";
        carPubSpkiB64 = "";
        carPrivPkcs8B64 = "";
        remoteAccountToken = "";
        phones.clear();
        executedCommandIds.clear();
        trustRevision++;
        if (file.exists()) {
            file.delete();
        }
    }

    public synchronized String getCarId() {
        return carId;
    }

    public synchronized String getCarPublicKeySpkiB64() {
        return carPubSpkiB64;
    }

    public synchronized PrivateKey getCarPrivateKey() throws Exception {
        if (carPrivPkcs8B64.isEmpty()) return null;
        return CryptoBox.decodePrivateKeyPkcs8B64(carPrivPkcs8B64);
    }

    public synchronized PublicKey getCarPublicKey() throws Exception {
        if (carPubSpkiB64.isEmpty()) return null;
        return CryptoBox.decodePublicKeySpkiB64(carPubSpkiB64);
    }

    public synchronized TrustedPhone addOrUpdateTrustedPhone(
            String phoneId,
            String phoneName,
            String platform,
            String btAddress,
            String phonePubSpkiB64) throws Exception {
        long now = System.currentTimeMillis();
        PrivateKey carPriv = getCarPrivateKey();
        if (carPriv == null) {
            throw new IllegalStateException("Car identity keypair is not initialized");
        }
        String grantSig = RemoteEnvelope.signPairingGrant(
                carPriv, carId, phoneId, phonePubSpkiB64, now);
        TrustedPhone tp = new TrustedPhone(
                phoneId,
                phoneName == null || phoneName.trim().isEmpty() ? "Companion Phone" : phoneName.trim(),
                platform == null ? "phone" : platform,
                btAddress,
                phonePubSpkiB64,
                now,
                now,
                grantSig);
        phones.put(phoneId, tp);
        trustRevision++;
        saveLocked();
        return tp;
    }

    public synchronized TrustedPhone getTrustedPhone(String phoneId) {
        return phoneId == null ? null : phones.get(phoneId);
    }

    public synchronized PublicKey getTrustedPhonePublicKey(String phoneId) {
        TrustedPhone tp = getTrustedPhone(phoneId);
        if (tp == null) return null;
        try {
            return CryptoBox.decodePublicKeySpkiB64(tp.publicKeySpkiB64);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public synchronized void touchPhoneSeen(String phoneId) {
        TrustedPhone tp = phones.get(phoneId);
        if (tp != null) {
            tp.lastSeenAtMs = System.currentTimeMillis();
            saveLocked();
        }
    }

    public synchronized boolean revokePhone(String phoneId) {
        if (phones.remove(phoneId) != null) {
            trustRevision++;
            saveLocked();
            return true;
        }
        return false;
    }

    public synchronized List<TrustedPhone> listTrustedPhones() {
        return new ArrayList<TrustedPhone>(phones.values());
    }

    public synchronized long getTrustRevision() {
        return trustRevision;
    }

    public synchronized boolean wasCommandExecuted(String commandId) {
        return commandId != null && executedCommandIds.contains(commandId);
    }

    public synchronized void recordExecutedCommand(String commandId) {
        if (commandId == null || commandId.isEmpty()) return;
        if (!executedCommandIds.contains(commandId)) {
            executedCommandIds.add(commandId);
            while (executedCommandIds.size() > MAX_EXECUTED_RING) {
                executedCommandIds.remove(0);
            }
            saveLocked();
        }
    }

    public synchronized boolean isRemoteServerConfigured() {
        return remoteServerUrl != null && remoteServerUrl.startsWith("https://");
    }

    public synchronized String getRemoteServerUrl() {
        return remoteServerUrl == null ? "" : remoteServerUrl;
    }

    public synchronized void setRemoteServerUrl(String url) {
        String normalized = RemoteEnvelope.normalizeServerUrl(url);
        this.remoteServerUrl = normalized;
        this.trustRevision++;
        saveLocked();
    }

    public synchronized String getRemoteAccountToken() {
        return remoteAccountToken;
    }

    public synchronized void setRemoteAccountToken(String token) {
        this.remoteAccountToken = token == null ? "" : token.trim();
        this.trustRevision++;
        saveLocked();
    }

    private void loadLocked() {
        if (!file.isFile()) return;
        try {
            String raw = readUtf8(file);
            JSONObject j = new JSONObject(raw);
            long savedInstallTime = j.optLong("firstInstallTime", -1L);
            if (currentFirstInstallTime > 0L && savedInstallTime > 0L && savedInstallTime != currentFirstInstallTime) {
                resetAllAndClearPin();
                return;
            }
            pinSaltHex = j.optString("pinSaltHex", "");
            pinHashHex = j.optString("pinHashHex", "");
            failedPinAttempts = j.optInt("failedPinAttempts", 0);
            lockoutUntilMs = j.optLong("lockoutUntilMs", 0L);
            installSaltHex = j.optString("installSaltHex", "");
            carId = j.optString("carId", "");
            carPubSpkiB64 = j.optString("carPubSpkiB64", "");
            carPrivPkcs8B64 = j.optString("carPrivPkcs8B64", "");
            remoteServerUrl = j.optString("remoteServerUrl", EditionConfig.DEFAULT_REMOTE_URL);
            remoteAccountToken = j.optString("remoteAccountToken", "");
            trustRevision = j.optLong("trustRevision", 1L);

            phones.clear();
            JSONArray arr = j.optJSONArray("phones");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject pObj = arr.optJSONObject(i);
                    if (pObj != null) {
                        TrustedPhone tp = TrustedPhone.fromJson(pObj);
                        if (!tp.phoneId.isEmpty() && !tp.publicKeySpkiB64.isEmpty()) {
                            phones.put(tp.phoneId, tp);
                        }
                    }
                }
            }
            executedCommandIds.clear();
            JSONArray execArr = j.optJSONArray("executedCommandIds");
            if (execArr != null) {
                for (int i = 0; i < execArr.length(); i++) {
                    String cid = execArr.optString(i, "");
                    if (!cid.isEmpty()) executedCommandIds.add(cid);
                }
            }
        } catch (Throwable ignored) {
            resetAllAndClearPin();
        }
    }

    private void saveLocked() {
        try {
            JSONObject j = new JSONObject();
            j.put("firstInstallTime", currentFirstInstallTime);
            j.put("pinSaltHex", pinSaltHex);
            j.put("pinHashHex", pinHashHex);
            j.put("failedPinAttempts", failedPinAttempts);
            j.put("lockoutUntilMs", lockoutUntilMs);
            j.put("installSaltHex", installSaltHex);
            j.put("carId", carId);
            j.put("carPubSpkiB64", carPubSpkiB64);
            j.put("carPrivPkcs8B64", carPrivPkcs8B64);
            j.put("remoteServerUrl", remoteServerUrl);
            j.put("remoteAccountToken", remoteAccountToken);
            j.put("trustRevision", trustRevision);

            JSONArray arr = new JSONArray();
            for (TrustedPhone tp : phones.values()) {
                arr.put(tp.toJson());
            }
            j.put("phones", arr);

            JSONArray execArr = new JSONArray();
            for (String cid : executedCommandIds) {
                execArr.put(cid);
            }
            j.put("executedCommandIds", execArr);

            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp, false);
            try {
                fos.write(j.toString().getBytes(CryptoBox.UTF8));
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(file)) {
                FileOutputStream direct = new FileOutputStream(file, false);
                try {
                    direct.write(j.toString().getBytes(CryptoBox.UTF8));
                    direct.getFD().sync();
                } finally {
                    direct.close();
                }
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readUtf8(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(65536L, f.length()));
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), CryptoBox.UTF8);
        } finally {
            fis.close();
        }
    }
}
