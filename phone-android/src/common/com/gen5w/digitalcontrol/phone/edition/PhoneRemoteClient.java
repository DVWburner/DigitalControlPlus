package com.gen5w.digitalcontrol.phone.edition;

import android.os.Handler;
import android.os.Looper;

import com.gen5w.digitalcontrol.phone.PhoneStore;
import com.gen5w.digitalcontrol.shared.ClimateTheme;
import com.gen5w.digitalcontrol.shared.CommandLogEntry;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HTTPS E2EE Remote Relay client for user-configured self-hosted servers.
 * Inactive (zero network requests) unless the user specifies an HTTPS server URL.
 */
final class PhoneRemoteClient implements PhoneEditionBridge.Controller {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_HTTP_BYTES = 262144;

    private final PhoneStore store;
    private final PhoneEditionBridge.Listener listener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<PhoneEditionBridge.PendingRemoteItem> items =
            new CopyOnWriteArrayList<PhoneEditionBridge.PendingRemoteItem>();

    private volatile boolean remoteFallbackActive = true;
    private volatile boolean polling = false;
    private volatile long lastCarCheckInMs = 0L;

    PhoneRemoteClient(PhoneStore store, PhoneEditionBridge.Listener listener) {
        this.store = store;
        this.listener = listener;
        this.lastCarCheckInMs = store.getLastCarRemoteCheckInMs();
        rehydrateActiveQueueFromLog();
    }

    private void rehydrateActiveQueueFromLog() {
        long now = System.currentTimeMillis();
        for (CommandLogEntry e : store.loadCommandLog()) {
            if ("REMOTE".equals(e.transport) && CommandLogEntry.STATE_SENT.equals(e.state) && e.expiresAtMs > now) {
                items.add(new PhoneEditionBridge.PendingRemoteItem(
                        e.id, e.label, "QUEUED", e.expiresAtMs, "Waiting for car Wi-Fi..."));
            }
        }
    }

    @Override
    public boolean isRemoteFallbackActive() {
        return remoteFallbackActive && store.isCarPaired() && store.isRemoteServerConfigured();
    }

    @Override
    public void setRemoteFallbackActive(boolean enabled) {
        this.remoteFallbackActive = enabled;
        notifyQueueChanged();
    }

    @Override
    public boolean isCarWifiRecentlyOnline() {
        long last = getLastCarWifiCheckInMs();
        if (last <= 0L) return false;
        long age = System.currentTimeMillis() - last;
        return age >= -120_000L && age <= PhoneEditionBridge.CAR_WIFI_ONLINE_THRESHOLD_MS;
    }

    @Override
    public long getLastCarWifiCheckInMs() {
        long mem = lastCarCheckInMs;
        return mem > 0L ? mem : store.getLastCarRemoteCheckInMs();
    }

    @Override
    public List<PhoneEditionBridge.PendingRemoteItem> getPendingCommands() {
        long now = System.currentTimeMillis();
        List<PhoneEditionBridge.PendingRemoteItem> out = new ArrayList<PhoneEditionBridge.PendingRemoteItem>();
        for (int i = 0; i < items.size(); i++) {
            PhoneEditionBridge.PendingRemoteItem it = items.get(i);
            if ("QUEUED".equals(it.status) && now >= it.expiresAtMs) {
                PhoneEditionBridge.PendingRemoteItem expired = new PhoneEditionBridge.PendingRemoteItem(
                        it.commandId, it.label, "EXPIRED", it.expiresAtMs, "Timed out after 5 minutes");
                items.set(i, expired);
                store.updateCommandLogState(it.commandId, CommandLogEntry.STATE_TIMED_OUT, it.expiresAtMs, "Timed out after 5 minutes");
                out.add(expired);
            } else {
                out.add(it);
            }
        }
        return out;
    }

    @Override
    public void enqueueRemoteCommand(
            final String action,
            final String arg,
            final ClimateTheme theme,
            final String displayLabel) {
        if (!store.isCarPaired()) {
            postMessage(false, "Pair with your car in-person over Bluetooth first.");
            return;
        }
        if (!store.isRemoteServerConfigured()) {
            postMessage(false, "Specify your HTTPS Remote Server URL in Profile first.");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = store.getRemoteServerUrl();
                    PublicKey carPub = store.getPairedCarPublicKey();
                    PrivateKey phonePriv = store.getPhonePrivateKey();
                    long now = System.currentTimeMillis();

                    JSONObject env = RemoteEnvelope.createSignedEncryptedCommand(
                            store.getPairedCarId(),
                            carPub,
                            store.getPhoneId(),
                            phonePriv,
                            store.getPhonePublicKeySpkiB64(),
                            store.getPairedAtMs(),
                            store.getPairingGrantSigB64(),
                            action,
                            arg,
                            theme == null ? null : theme.toJson(),
                            displayLabel,
                            now);

                    String cmdId = env.getString("commandId");
                    long expMs = env.getLong("expiresAtMs");

                    HttpReply rep = sendHttp("POST", baseUrl + "/api/v1/commands", env, store.getRemoteAccountToken(), null);
                    if (rep.code >= 200 && rep.code < 300) {
                        store.recordCommandSent(cmdId, displayLabel, "REMOTE", now, expMs);
                        items.add(0, new PhoneEditionBridge.PendingRemoteItem(
                                cmdId, displayLabel, "QUEUED", expMs, "Waiting for car Wi-Fi..."));
                        while (items.size() > 10) {
                            items.remove(items.size() - 1);
                        }
                        notifyQueueChanged();
                        postMessage(true, displayLabel + " sent.");
                        startPollingIfNeeded();
                    } else {
                        String err = extractError(rep.body, "Server returned HTTP " + rep.code);
                        postMessage(false, "Queue failed: " + err);
                    }
                } catch (Throwable e) {
                    postMessage(false, "Remote error: " + e.getMessage());
                }
            }
        }, "phone-rem-enqueue");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void cancelRemoteCommand(final String commandId) {
        if (commandId == null || commandId.isEmpty() || !store.isRemoteServerConfigured()) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = store.getRemoteServerUrl();
                    String carId = store.getPairedCarId();
                    String phoneId = store.getPhoneId();
                    long now = System.currentTimeMillis();
                    String canonical = RemoteEnvelope.buildCancelCanonical(commandId, carId, phoneId, now);
                    String sig = CryptoBox.signEcdsaSha256B64(store.getPhonePrivateKey(), canonical);

                    String url = baseUrl + "/api/v1/commands/" + URLEncoder.encode(commandId, "UTF-8")
                            + "?carId=" + URLEncoder.encode(carId, "UTF-8")
                            + "&phoneId=" + URLEncoder.encode(phoneId, "UTF-8")
                            + "&ts=" + now;

                    HttpReply rep = sendHttp("DELETE", url, null, store.getRemoteAccountToken(), sig);
                    if (rep.code >= 200 && rep.code < 300) {
                        store.updateCommandLogState(commandId, CommandLogEntry.STATE_CANCELED, System.currentTimeMillis(), "Canceled by phone");
                        updateLocalItemStatus(commandId, "CANCELED", "Canceled by phone");
                        postMessage(true, "Command canceled.");
                    } else {
                        postMessage(false, "Cancel failed: " + extractError(rep.body, "HTTP " + rep.code));
                    }
                } catch (Throwable e) {
                    postMessage(false, "Cancel error: " + e.getMessage());
                }
            }
        }, "phone-rem-cancel");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public synchronized void startPollingIfNeeded() {
        if (polling || !store.isCarPaired() || !store.isRemoteServerConfigured()) return;
        polling = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (polling && store.isCarPaired() && store.isRemoteServerConfigured()) {
                    pollServerStatusOnce();
                    try {
                        Thread.sleep(hasActiveQueuedItem() ? 3000L : 8000L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                polling = false;
            }
        }, "phone-rem-status");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public synchronized void stopPolling() {
        polling = false;
    }

    private boolean hasActiveQueuedItem() {
        long now = System.currentTimeMillis();
        for (PhoneEditionBridge.PendingRemoteItem it : items) {
            if ("QUEUED".equals(it.status) && now < it.expiresAtMs) return true;
        }
        return false;
    }

    private void pollServerStatusOnce() {
        try {
            if (!store.isRemoteServerConfigured()) return;
            String baseUrl = store.getRemoteServerUrl();
            String carId = store.getPairedCarId();
            String phoneId = store.getPhoneId();
            long now = System.currentTimeMillis();
            String canonical = "GEN5W-STATUS-V1|" + carId + "|" + phoneId + "|" + now;
            String sig = CryptoBox.signEcdsaSha256B64(store.getPhonePrivateKey(), canonical);

            String url = baseUrl + "/api/v1/commands?carId=" + URLEncoder.encode(carId, "UTF-8")
                    + "&phoneId=" + URLEncoder.encode(phoneId, "UTF-8")
                    + "&ts=" + now;

            HttpReply rep = sendHttp("GET", url, null, store.getRemoteAccountToken(), sig);
            if (rep.code < 200 || rep.code >= 300 || rep.body == null || rep.body.isEmpty()) {
                return;
            }
            JSONObject root = new JSONObject(rep.body);
            boolean uiNeedsUpdate = false;

            long topSeen = root.optLong("carLastSeenAtMs", 0L);
            if (topSeen > lastCarCheckInMs) {
                lastCarCheckInMs = topSeen;
                store.setLastCarRemoteCheckInMs(topSeen);
                uiNeedsUpdate = true;
            }

            JSONArray cmds = root.optJSONArray("commands");
            if (cmds != null) {
                for (int i = 0; i < cmds.length(); i++) {
                    JSONObject c = cmds.optJSONObject(i);
                    if (c == null) continue;
                    String cid = c.optString("commandId", "");
                    String status = c.optString("status", "QUEUED");
                    String msg = c.optString("message", "");
                    long execAt = c.optLong("executedAtMs", 0L);
                    long eventTs = execAt > 0L ? execAt : System.currentTimeMillis();

                    String logState = mapServerStatusToLogState(status);
                    if (!CommandLogEntry.STATE_SENT.equals(logState)) {
                        if (store.updateCommandLogState(cid, logState, eventTs, msg)) {
                            uiNeedsUpdate = true;
                            notifyTerminalCommandFeedback(cid, logState);
                        }
                    }
                    if (updateLocalItemStatus(cid, status, msg)) {
                        uiNeedsUpdate = true;
                    }
                }
            }

            JSONObject encTelem = root.optJSONObject("encryptedTelemetry");
            if (encTelem != null) {
                long telemUpdatedAt = encTelem.optLong("updatedAtMs", 0L);
                if (telemUpdatedAt > 0L && telemUpdatedAt != lastCarCheckInMs) {
                    lastCarCheckInMs = telemUpdatedAt;
                    store.setLastCarRemoteCheckInMs(telemUpdatedAt);
                    uiNeedsUpdate = true;
                }
                CryptoBox.EncryptedBox box = new CryptoBox.EncryptedBox(
                        encTelem.optString("ephemeralPubKey", ""),
                        encTelem.optString("iv", ""),
                        encTelem.optString("ciphertext", ""),
                        encTelem.optString("mac", ""));
                String plain = CryptoBox.decryptWithPrivateKey(
                        store.getPhonePrivateKey(),
                        "telemetry|" + carId,
                        "GEN5W-TELEM-V1|" + carId + "|" + phoneId,
                        box);
                JSONObject snapJson = new JSONObject(plain);
                if (telemUpdatedAt > 0L && snapJson.optLong("updatedMs", 0L) <= 0L) {
                    snapJson.put("updatedMs", telemUpdatedAt);
                }
                store.saveCachedVehicleJson(snapJson);
                final VehicleSnapshot snap = VehicleSnapshot.fromJson(snapJson);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onRemoteTelemetryReceived(snap);
                    }
                });
            }

            if (uiNeedsUpdate) {
                notifyQueueChanged();
            }
        } catch (Throwable ignored) {
        }
    }

    private void notifyTerminalCommandFeedback(String commandId, String logState) {
        String label = null;
        for (PhoneEditionBridge.PendingRemoteItem it : items) {
            if (it.commandId.equals(commandId)) {
                label = it.label;
                break;
            }
        }
        if (label == null) {
            for (CommandLogEntry e : store.loadCommandLog()) {
                if (e.id.equals(commandId)) {
                    label = e.label;
                    break;
                }
            }
        }
        if (label == null) return;
        if (CommandLogEntry.STATE_EXECUTED.equals(logState)) {
            postMessage(true, label + " executed.");
        } else if (CommandLogEntry.STATE_TIMED_OUT.equals(logState)) {
            postMessage(false, label + " timed out.");
        } else if (CommandLogEntry.STATE_FAILED.equals(logState)) {
            postMessage(false, label + " failed.");
        }
    }

    private static String mapServerStatusToLogState(String srv) {
        if ("EXECUTED".equalsIgnoreCase(srv)) return CommandLogEntry.STATE_EXECUTED;
        if ("EXPIRED".equalsIgnoreCase(srv)) return CommandLogEntry.STATE_TIMED_OUT;
        if ("CANCELED".equalsIgnoreCase(srv)) return CommandLogEntry.STATE_CANCELED;
        if ("FAILED".equalsIgnoreCase(srv)) return CommandLogEntry.STATE_FAILED;
        return CommandLogEntry.STATE_SENT;
    }

    private boolean updateLocalItemStatus(String commandId, String newStatus, String msg) {
        for (int i = 0; i < items.size(); i++) {
            PhoneEditionBridge.PendingRemoteItem cur = items.get(i);
            if (cur.commandId.equals(commandId)) {
                if (!cur.status.equals(newStatus) || !cur.resultMessage.equals(msg == null ? "" : msg)) {
                    items.set(i, new PhoneEditionBridge.PendingRemoteItem(
                            cur.commandId, cur.label, newStatus, cur.expiresAtMs, msg));
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private void notifyQueueChanged() {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onRemoteQueueChanged();
            }
        });
    }

    private void postMessage(final boolean ok, final String msg) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onRemoteEventMessage(ok, msg);
            }
        });
    }

    private static String extractError(String body, String fallback) {
        if (body == null || body.isEmpty()) return fallback;
        try {
            JSONObject j = new JSONObject(body);
            String e = j.optString("error", j.optString("message", ""));
            return e.isEmpty() ? fallback : e;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static final class HttpReply {
        final int code;
        final String body;

        HttpReply(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static HttpReply sendHttp(
            String method,
            String httpsUrl,
            JSONObject body,
            String bearerToken,
            String phoneSigHeader) throws Exception {
        if (httpsUrl == null || !httpsUrl.startsWith("https://")) {
            throw new SecurityException("HTTPS is required for remote server");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(httpsUrl).openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setDoInput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Gen5WDigitalControlPhone/1.0.5");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            if (phoneSigHeader != null && !phoneSigHeader.isEmpty()) {
                conn.setRequestProperty("X-Gen5W-Phone-Signature", phoneSigHeader);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] raw = body.toString().getBytes(CryptoBox.UTF8);
                conn.setFixedLengthStreamingMode(raw.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(raw);
                    os.flush();
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            return new HttpReply(code, readBounded(is));
        } finally {
            conn.disconnect();
        }
    }

    private static String readBounded(InputStream is) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > MAX_HTTP_BYTES) {
                    throw new SecurityException("Response exceeded limit");
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), CryptoBox.UTF8);
        } finally {
            is.close();
        }
    }
}
