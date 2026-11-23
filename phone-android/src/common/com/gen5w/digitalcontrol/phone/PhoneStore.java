package com.gen5w.digitalcontrol.phone;

import android.app.backup.BackupManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.gen5w.digitalcontrol.phone.edition.PhoneEditionBridge;
import com.gen5w.digitalcontrol.shared.ClimateTheme;
import com.gen5w.digitalcontrol.shared.CommandLogEntry;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.KeyBackupBundle;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local storage on the Android companion phone for:
 *  - Phone's EC P-256 identity keypair & unique phoneId
 *  - Paired car identity (carId, carStaticPub, pairingGrantSig, carBtAddress, carName)
 *  - User's custom Climate Themes
 *  - Google App Backup (Auto Backup) runtime inclusion toggle + encrypted .g5wkey backup/restore
 *  - Optional remote server URL & account Bearer token (used only in Private Edition)
 */
public final class PhoneStore {
    // Included in Google App Backup via @xml/backup_rules
    private static final String PREFS_CLOUD_SYNCED = "gen5w_phone_store_v1";
    // Excluded from Google App Backup via @xml/backup_rules when user opts out of cloud key backup
    private static final String PREFS_DEVICE_ONLY = "gen5w_phone_local_only_v1";
    private static final String LOCAL_BACKUP_SUBDIR = "backups";
    private static final String LOCAL_BACKUP_FILENAME = "gen5w-security-key.g5wkey";

    private final Context appContext;
    private final SharedPreferences prefs;
    private final SharedPreferences localOnlyPrefs;

    public PhoneStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_CLOUD_SYNCED, Context.MODE_PRIVATE);
        this.localOnlyPrefs = appContext.getSharedPreferences(PREFS_DEVICE_ONLY, Context.MODE_PRIVATE);
        ensurePhoneIdentity();
    }

    private SharedPreferences secretStore() {
        return isCloudBackupEnabled() ? prefs : localOnlyPrefs;
    }

    private String readSecret(String key) {
        String v = secretStore().getString(key, "");
        if (!v.isEmpty()) return v;
        SharedPreferences other = isCloudBackupEnabled() ? localOnlyPrefs : prefs;
        return other.getString(key, "");
    }

    private void writeSecret(String key, String value) {
        String safe = value == null ? "" : value;
        if (isCloudBackupEnabled()) {
            prefs.edit().putString(key, safe).apply();
            localOnlyPrefs.edit().remove(key).apply();
        } else {
            localOnlyPrefs.edit().putString(key, safe).apply();
            prefs.edit().remove(key).apply();
        }
    }

    public synchronized boolean isCloudBackupEnabled() {
        return prefs.getBoolean("cloud_backup_enabled", true);
    }

    public synchronized void setCloudBackupEnabled(boolean enabled) {
        String priv = readSecret("phone_priv_b64");
        String grant = readSecret("pairing_grant_sig");
        String remTok = readSecret("remote_account_token");

        prefs.edit().putBoolean("cloud_backup_enabled", enabled).commit();
        if (enabled) {
            SharedPreferences.Editor ed = prefs.edit().putString("phone_priv_b64", priv);
            if (!grant.isEmpty()) ed.putString("pairing_grant_sig", grant);
            if (!remTok.isEmpty()) ed.putString("remote_account_token", remTok);
            ed.commit();
            localOnlyPrefs.edit()
                    .remove("phone_priv_b64")
                    .remove("pairing_grant_sig")
                    .remove("remote_account_token")
                    .commit();
        } else {
            SharedPreferences.Editor led = localOnlyPrefs.edit().putString("phone_priv_b64", priv);
            if (!grant.isEmpty()) led.putString("pairing_grant_sig", grant);
            if (!remTok.isEmpty()) led.putString("remote_account_token", remTok);
            led.commit();
            prefs.edit()
                    .remove("phone_priv_b64")
                    .remove("pairing_grant_sig")
                    .remove("remote_account_token")
                    .commit();
        }
        notifyGoogleBackupChanged();
    }

    private void notifyGoogleBackupChanged() {
        try {
            BackupManager.dataChanged(appContext.getPackageName());
        } catch (Throwable ignored) {
        }
    }

    private synchronized void ensurePhoneIdentity() {
        String existingPriv = readSecret("phone_priv_b64");
        if (!existingPriv.isEmpty() && !prefs.getString("phone_id", "").isEmpty()) {
            return;
        }
        try {
            KeyPair kp = CryptoBox.generateEcP256KeyPair();
            String privB64 = CryptoBox.encodePrivateKeyPkcs8B64(kp.getPrivate());
            prefs.edit()
                    .putString("phone_id", "ph_" + CryptoBox.randomHex(10))
                    .putString("phone_pub_b64", CryptoBox.encodePublicKeySpkiB64(kp.getPublic()))
                    .commit();
            writeSecret("phone_priv_b64", privB64);
            notifyGoogleBackupChanged();
        } catch (Throwable t) {
            throw new IllegalStateException("Failed generating phone EC P-256 keypair", t);
        }
    }

    public String getPhoneId() {
        return prefs.getString("phone_id", "");
    }

    public String getPhoneName() {
        String custom = prefs.getString("phone_name", "");
        if (!custom.isEmpty()) return custom;
        String m = Build.MODEL == null ? "Android Phone" : Build.MODEL.trim();
        return m.isEmpty() ? "Android Phone" : m;
    }

    public String getPhonePublicKeySpkiB64() {
        return prefs.getString("phone_pub_b64", "");
    }

    public String getKeyFingerprintShort() {
        String pub = getPhonePublicKeySpkiB64();
        if (pub.isEmpty()) return "----";
        String hex = CryptoBox.deriveKeyFingerprintHex(pub).toUpperCase(Locale.US);
        if (hex.length() < 12) return hex;
        return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12);
    }

    public PrivateKey getPhonePrivateKey() throws Exception {
        return CryptoBox.decodePrivateKeyPkcs8B64(readSecret("phone_priv_b64"));
    }

    public boolean isCarPaired() {
        return !getPairedCarId().isEmpty() && !getPairedCarPublicKeySpkiB64().isEmpty();
    }

    public String getPairedCarId() {
        return prefs.getString("car_id", "");
    }

    public String getPairedCarName() {
        return prefs.getString("car_name", "Ioniq 5");
    }

    public String getPairedCarBtAddress() {
        return prefs.getString("car_bt_address", "");
    }

    public String getPairedCarPublicKeySpkiB64() {
        return prefs.getString("car_pub_b64", "");
    }

    public PublicKey getPairedCarPublicKey() throws Exception {
        String b64 = getPairedCarPublicKeySpkiB64();
        return b64.isEmpty() ? null : CryptoBox.decodePublicKeySpkiB64(b64);
    }

    public long getPairedAtMs() {
        return prefs.getLong("paired_at_ms", 0L);
    }

    public String getPairingGrantSigB64() {
        return readSecret("pairing_grant_sig");
    }

    public synchronized void savePairedCar(
            String carId,
            String carName,
            String btAddress,
            String carPubB64,
            long pairedAtMs,
            String pairingGrantSig,
            String remoteUrl) {
        SharedPreferences.Editor ed = prefs.edit()
                .putString("car_id", carId == null ? "" : carId)
                .putString("car_name", (carName == null || carName.isEmpty()) ? "Ioniq 5" : carName)
                .putString("car_bt_address", btAddress == null ? "" : btAddress)
                .putString("car_pub_b64", carPubB64 == null ? "" : carPubB64)
                .putLong("paired_at_ms", pairedAtMs);
        String normRemote = RemoteEnvelope.normalizeServerUrl(remoteUrl);
        if (PhoneEditionBridge.REMOTE_ENABLED && !normRemote.isEmpty()) {
            ed.putString("remote_url", normRemote);
        }
        ed.commit();
        writeSecret("pairing_grant_sig", pairingGrantSig == null ? "" : pairingGrantSig);
        notifyGoogleBackupChanged();
    }

    public synchronized void clearPairedCar() {
        prefs.edit()
                .remove("car_id")
                .remove("car_name")
                .remove("car_bt_address")
                .remove("car_pub_b64")
                .remove("paired_at_ms")
                .remove("cached_vehicle_json")
                .commit();
        prefs.edit().remove("pairing_grant_sig").commit();
        localOnlyPrefs.edit().remove("pairing_grant_sig").commit();
        notifyGoogleBackupChanged();
    }

    public List<ClimateTheme> loadClimateThemes() {
        return ClimateTheme.parseList(prefs.getString("climate_themes_json", ""));
    }

    public void saveClimateThemes(List<ClimateTheme> list) {
        prefs.edit().putString("climate_themes_json", ClimateTheme.serializeList(list)).apply();
        notifyGoogleBackupChanged();
    }

    public void upsertClimateTheme(ClimateTheme theme) {
        List<ClimateTheme> list = new ArrayList<ClimateTheme>(loadClimateThemes());
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(theme.id)) {
                list.set(i, theme);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(theme);
        }
        saveClimateThemes(list);
    }

    public void deleteClimateTheme(String themeId) {
        List<ClimateTheme> list = new ArrayList<ClimateTheme>(loadClimateThemes());
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(themeId)) {
                list.remove(i);
            }
        }
        saveClimateThemes(list);
    }

    public void saveCachedVehicleJson(JSONObject json) {
        if (json != null) {
            prefs.edit().putString("cached_vehicle_json", json.toString()).apply();
        }
    }

    public VehicleSnapshot loadCachedVehicleSnapshot() {
        String raw = prefs.getString("cached_vehicle_json", "");
        if (raw.isEmpty()) return VehicleSnapshot.empty();
        try {
            return VehicleSnapshot.fromJson(new JSONObject(raw));
        } catch (Throwable ignored) {
            return VehicleSnapshot.empty();
        }
    }

    public boolean isRemoteServerConfigured() {
        String u = getRemoteServerUrl();
        return u != null && u.startsWith("https://");
    }

    public String getRemoteServerUrl() {
        return prefs.getString("remote_url", PhoneEditionBridge.DEFAULT_REMOTE_URL);
    }

    public void setRemoteServerUrl(String url) {
        if (!PhoneEditionBridge.REMOTE_ENABLED) return;
        String normalized = RemoteEnvelope.normalizeServerUrl(url);
        if (normalized.isEmpty()) {
            prefs.edit().remove("remote_url").apply();
            notifyGoogleBackupChanged();
            return;
        }
        prefs.edit().putString("remote_url", normalized).apply();
        notifyGoogleBackupChanged();
    }

    public String getRemoteAccountToken() {
        return readSecret("remote_account_token");
    }

    public void setRemoteAccountToken(String token) {
        if (PhoneEditionBridge.REMOTE_ENABLED) {
            writeSecret("remote_account_token", token == null ? "" : token.trim());
            notifyGoogleBackupChanged();
        }
    }

    public boolean isUseMiles() {
        return prefs.getBoolean("use_miles", true);
    }

    public void setUseMiles(boolean miles) {
        prefs.edit().putBoolean("use_miles", miles).apply();
        notifyGoogleBackupChanged();
    }

    // ---- Remote Car Wi-Fi Check-In & Persistent Command Log ----

    public long getLastCarRemoteCheckInMs() {
        return localOnlyPrefs.getLong("last_car_remote_checkin_ms", 0L);
    }

    public void setLastCarRemoteCheckInMs(long tsMs) {
        if (tsMs > 0L) {
            localOnlyPrefs.edit().putLong("last_car_remote_checkin_ms", tsMs).apply();
        }
    }

    public synchronized List<CommandLogEntry> loadCommandLog() {
        List<CommandLogEntry> list = CommandLogEntry.parseList(localOnlyPrefs.getString("command_log_v1", ""));
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            CommandLogEntry e = list.get(i);
            if (CommandLogEntry.STATE_SENT.equals(e.state) && e.expiresAtMs > 0L && now >= e.expiresAtMs) {
                list.set(i, e.withState(CommandLogEntry.STATE_TIMED_OUT, e.expiresAtMs, "Timed out after 5 minutes"));
                changed = true;
            }
        }
        if (changed) {
            localOnlyPrefs.edit().putString("command_log_v1", CommandLogEntry.serializeList(list)).apply();
        }
        return list;
    }

    public synchronized void recordCommandSent(String id, String label, String transport, long sentAtMs, long expiresAtMs) {
        List<CommandLogEntry> list = new ArrayList<CommandLogEntry>(loadCommandLog());
        list.add(0, new CommandLogEntry(
                id, label, transport, CommandLogEntry.STATE_SENT, sentAtMs, sentAtMs, expiresAtMs, ""));
        while (list.size() > 60) {
            list.remove(list.size() - 1);
        }
        localOnlyPrefs.edit().putString("command_log_v1", CommandLogEntry.serializeList(list)).apply();
    }

    public synchronized boolean updateCommandLogState(String id, String newState, long nowMs, String detail) {
        if (id == null || id.isEmpty()) return false;
        List<CommandLogEntry> list = new ArrayList<CommandLogEntry>(loadCommandLog());
        for (int i = 0; i < list.size(); i++) {
            CommandLogEntry cur = list.get(i);
            if (id.equals(cur.id)) {
                String safeDet = detail == null ? "" : detail;
                if (!cur.state.equals(newState) || !cur.detail.equals(safeDet)) {
                    list.set(i, cur.withState(newState, nowMs, safeDet));
                    localOnlyPrefs.edit().putString("command_log_v1", CommandLogEntry.serializeList(list)).apply();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public synchronized void clearCommandLog() {
        localOnlyPrefs.edit().remove("command_log_v1").apply();
    }

    // ---- Cross-Platform Passphrase Backup & Restore ----

    public synchronized KeyBackupBundle buildBackupBundle() throws Exception {
        KeyBackupBundle b = new KeyBackupBundle();
        b.phoneId = getPhoneId();
        b.phoneName = getPhoneName();
        byte[] scalar32 = CryptoBox.extractEcPrivateScalar32(getPhonePrivateKey());
        b.phonePrivRawB64 = CryptoBox.base64Encode(scalar32);
        b.phonePubSpkiB64 = getPhonePublicKeySpkiB64();

        b.carId = getPairedCarId();
        b.carName = getPairedCarName();
        b.carBtAddress = getPairedCarBtAddress();
        b.carPubSpkiB64 = getPairedCarPublicKeySpkiB64();
        b.pairedAtMs = getPairedAtMs();
        b.pairingGrantSigB64 = getPairingGrantSigB64();
        b.useMiles = isUseMiles();
        b.climateThemes = new ArrayList<ClimateTheme>(loadClimateThemes());

        if (PhoneEditionBridge.REMOTE_ENABLED) {
            b.remoteUrl = getRemoteServerUrl();
            b.remoteAccountToken = getRemoteAccountToken();
        }
        return b;
    }

    public synchronized void restoreBackupBundle(KeyBackupBundle b) throws Exception {
        PrivateKey priv = b.reconstructVerifiedPrivateKey();
        String privPkcs8B64 = CryptoBox.encodePrivateKeyPkcs8B64(priv);

        SharedPreferences.Editor ed = prefs.edit()
                .putString("phone_id", b.phoneId)
                .putString("phone_pub_b64", b.phonePubSpkiB64)
                .putBoolean("use_miles", b.useMiles);
        if (!b.phoneName.isEmpty()) {
            ed.putString("phone_name", b.phoneName);
        }
        if (!b.carId.isEmpty() && !b.carPubSpkiB64.isEmpty()) {
            ed.putString("car_id", b.carId)
                    .putString("car_name", b.carName.isEmpty() ? "Ioniq 5" : b.carName)
                    .putString("car_bt_address", b.carBtAddress)
                    .putString("car_pub_b64", b.carPubSpkiB64)
                    .putLong("paired_at_ms", b.pairedAtMs);
        } else {
            ed.remove("car_id")
                    .remove("car_name")
                    .remove("car_bt_address")
                    .remove("car_pub_b64")
                    .remove("paired_at_ms");
        }
        if (b.climateThemes != null && !b.climateThemes.isEmpty()) {
            ed.putString("climate_themes_json", ClimateTheme.serializeList(b.climateThemes));
        }
        if (PhoneEditionBridge.REMOTE_ENABLED && b.remoteUrl.startsWith("https://")) {
            ed.putString("remote_url", b.remoteUrl);
        }
        ed.commit();

        writeSecret("phone_priv_b64", privPkcs8B64);
        writeSecret("pairing_grant_sig", b.pairingGrantSigB64);
        if (PhoneEditionBridge.REMOTE_ENABLED && !b.remoteAccountToken.isEmpty()) {
            writeSecret("remote_account_token", b.remoteAccountToken);
        }
        notifyGoogleBackupChanged();
    }

    // ---- On-Phone Local Encrypted Snapshot File Helpers ----

    private File getLocalBackupFile() {
        File dir = new File(appContext.getFilesDir(), LOCAL_BACKUP_SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, LOCAL_BACKUP_FILENAME);
    }

    public boolean hasLocalDeviceBackupFile() {
        File f = getLocalBackupFile();
        return f.isFile() && f.length() > 0L;
    }

    public long getLocalDeviceBackupTimestampMs() {
        File f = getLocalBackupFile();
        return f.isFile() ? f.lastModified() : 0L;
    }

    public void saveLocalDeviceBackupFile(String encryptedJson) throws Exception {
        File f = getLocalBackupFile();
        FileOutputStream fos = new FileOutputStream(f, false);
        try {
            fos.write(encryptedJson.getBytes(CryptoBox.UTF8));
            fos.flush();
        } finally {
            fos.close();
        }
    }

    public String readLocalDeviceBackupFile() throws Exception {
        File f = getLocalBackupFile();
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(256L, f.length()));
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), CryptoBox.UTF8);
        } finally {
            fis.close();
        }
    }
}
