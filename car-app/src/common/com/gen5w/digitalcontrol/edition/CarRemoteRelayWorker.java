package com.gen5w.digitalcontrol.edition;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.gen5w.digitalcontrol.DaemonClient;
import com.gen5w.digitalcontrol.TrustVault;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Opportunistic HTTPS relay worker for user-configured self-hosted remote servers.
 *
 * Out of the box (when no server URL is configured), stays completely idle with zero network calls.
 * When the owner configures an HTTPS server URL, opportunistically polls for E2EE signed commands
 * whenever Wi-Fi, Bluetooth PAN, Ethernet, or USB internet is available.
 */
final class CarRemoteRelayWorker {
    private static final int CONNECT_TIMEOUT_MS = 4500;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_HTTP_BYTES = 262144;

    private final Context appContext;
    private final TrustVault vault;
    private final Object wakeLock = new Object();

    private volatile boolean running = false;
    private volatile long lastSyncedTrustRevision = -1L;
    private volatile String statusSummary = "Disabled (Bluetooth-only — no server configured)";
    private int consecutiveNetFailures = 0;

    CarRemoteRelayWorker(Context context, TrustVault vault) {
        this.appContext = context.getApplicationContext();
        this.vault = vault;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "digctl-remote-relay");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    void notifyTrustUpdated() {
        lastSyncedTrustRevision = -1L;
        consecutiveNetFailures = 0;
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    void notifyConnectivityChanged() {
        consecutiveNetFailures = 0;
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    String getStatusSummary() {
        return statusSummary;
    }

    private void loop() {
        while (running) {
            long sleepMs = 6000L;
            try {
                if (!vault.isRemoteServerConfigured()) {
                    statusSummary = "Disabled (Bluetooth-only — specify an HTTPS server URL to enable)";
                    sleepMs = 15000L;
                } else if (!vault.isPinConfigured()) {
                    statusSummary = "Idle — Set car PIN first";
                    sleepMs = 10000L;
                } else {
                    List<TrustVault.TrustedPhone> paired = vault.listTrustedPhones();
                    if (paired.isEmpty()) {
                        statusSummary = "Idle — Pair a phone over BT first";
                        sleepMs = 10000L;
                    } else if (!hasCandidateInternetRoute()) {
                        statusSummary = "Offline — Waiting for Wi-Fi / BT Hotspot";
                        sleepMs = 12000L;
                    } else {
                        String baseUrl = vault.getRemoteServerUrl();
                        long curRev = vault.getTrustRevision();
                        if (curRev != lastSyncedTrustRevision) {
                            if (syncCarEnrollment(baseUrl, paired)) {
                                lastSyncedTrustRevision = curRev;
                            }
                        }
                        boolean pollOk = pollAndExecuteCommands(baseUrl, paired);
                        if (pollOk) {
                            consecutiveNetFailures = 0;
                            sleepMs = 3500L;
                        } else {
                            consecutiveNetFailures = Math.min(6, consecutiveNetFailures + 1);
                            sleepMs = Math.min(60000L, 4000L * (1L << Math.min(4, consecutiveNetFailures)));
                        }
                    }
                }
            } catch (Throwable t) {
                consecutiveNetFailures = Math.min(6, consecutiveNetFailures + 1);
                statusSummary = "Relay waiting (" + t.getClass().getSimpleName() + ")";
                sleepMs = Math.min(60000L, 5000L * consecutiveNetFailures);
            }

            synchronized (wakeLock) {
                if (!running) break;
                try {
                    wakeLock.wait(sleepMs);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private boolean hasCandidateInternetRoute() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs != null) {
                for (NetworkInterface nif : Collections.list(ifs)) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    String name = nif.getName();
                    if (name == null) continue;
                    if (name.startsWith("wlan") || name.startsWith("bnep")
                            || name.startsWith("eth") || name.startsWith("usb")
                            || name.startsWith("rndis") || name.startsWith("bt-pan")) {
                        if (nif.getInetAddresses().hasMoreElements()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    int type = ni.getType();
                    if (type == ConnectivityManager.TYPE_WIFI
                            || type == ConnectivityManager.TYPE_BLUETOOTH
                            || type == ConnectivityManager.TYPE_ETHERNET) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean syncCarEnrollment(String baseUrl, List<TrustVault.TrustedPhone> paired) {
        try {
            String carId = vault.getCarId();
            String carPubB64 = vault.getCarPublicKeySpkiB64();
            PrivateKey carPriv = vault.getCarPrivateKey();
            if (carId.isEmpty() || carPubB64.isEmpty() || carPriv == null) return false;

            long now = System.currentTimeMillis();
            String regCanonical = RemoteEnvelope.buildCarRegisterCanonical(carId, carPubB64, now);
            String carSig = CryptoBox.signEcdsaSha256B64(carPriv, regCanonical);

            JSONArray phonesArr = new JSONArray();
            for (TrustVault.TrustedPhone tp : paired) {
                JSONObject p = new JSONObject();
                p.put("phoneId", tp.phoneId);
                p.put("phoneName", tp.phoneName);
                p.put("platform", tp.platform);
                p.put("phonePublicKey", tp.publicKeySpkiB64);
                p.put("pairedAtMs", tp.pairedAtMs);
                p.put("carGrantSignature", tp.pairingGrantSigB64);
                phonesArr.put(p);
            }

            JSONObject body = new JSONObject();
            body.put("carId", carId);
            body.put("carPublicKey", carPubB64);
            body.put("timestampMs", now);
            body.put("carSignature", carSig);
            body.put("authorizedPhones", phonesArr);

            HttpReply rep = postJson(baseUrl + "/api/v1/cars/register", body, vault.getRemoteAccountToken(), 8000);
            return rep.code >= 200 && rep.code < 300;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean pollAndExecuteCommands(String baseUrl, List<TrustVault.TrustedPhone> paired) {
        try {
            String carId = vault.getCarId();
            PrivateKey carPriv = vault.getCarPrivateKey();
            if (carId.isEmpty() || carPriv == null) return false;

            long now = System.currentTimeMillis();
            String nonce = CryptoBox.randomHex(12);
            String canonical = RemoteEnvelope.buildCarPollCanonical(carId, now, nonce);
            String carSig = CryptoBox.signEcdsaSha256B64(carPriv, canonical);

            JSONObject pollBody = new JSONObject();
            pollBody.put("carId", carId);
            pollBody.put("timestampMs", now);
            pollBody.put("nonce", nonce);
            pollBody.put("waitSeconds", 12);
            pollBody.put("carSignature", carSig);

            JSONObject vehSnap = DaemonClient.queryVehicleJson();
            if (vehSnap != null && vehSnap.optBoolean("ok", false)) {
                String plainTelemetry = vehSnap.toString();
                JSONObject encryptedByPhone = new JSONObject();
                for (TrustVault.TrustedPhone tp : paired) {
                    try {
                        PublicKey pPub = CryptoBox.decodePublicKeySpkiB64(tp.publicKeySpkiB64);
                        CryptoBox.EncryptedBox tBox = CryptoBox.encryptToPublicKey(
                                pPub,
                                "telemetry|" + carId,
                                "GEN5W-TELEM-V1|" + carId + "|" + tp.phoneId,
                                plainTelemetry);
                        JSONObject bObj = new JSONObject();
                        bObj.put("ephemeralPubKey", tBox.ephemeralPubSpkiB64);
                        bObj.put("iv", tBox.ivB64);
                        bObj.put("ciphertext", tBox.ciphertextB64);
                        bObj.put("mac", tBox.macB64);
                        bObj.put("updatedAtMs", now);
                        encryptedByPhone.put(tp.phoneId, bObj);
                    } catch (Throwable ignored) {
                    }
                }
                pollBody.put("encryptedTelemetry", encryptedByPhone);
            }

            HttpReply rep = postJson(baseUrl + "/api/v1/car/poll", pollBody, vault.getRemoteAccountToken(), READ_TIMEOUT_MS);
            if (rep.code == 404) {
                lastSyncedTrustRevision = -1L;
            }
            if (rep.code < 200 || rep.code >= 300 || rep.body == null) {
                statusSummary = "Server HTTP " + rep.code + " (" + baseUrl + ")";
                return false;
            }

            JSONObject resp = new JSONObject(rep.body);
            JSONArray commands = resp.optJSONArray("commands");
            int count = commands == null ? 0 : commands.length();
            statusSummary = "Connected to " + baseUrl.replace("https://", "") + " (queue: " + count + ")";

            if (commands != null) {
                for (int i = 0; i < count; i++) {
                    JSONObject env = commands.optJSONObject(i);
                    if (env != null) {
                        processOneEnvelope(baseUrl, carId, carPriv, env);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            statusSummary = "Waiting for internet (" + t.getClass().getSimpleName() + ")";
            return false;
        }
    }

    private void processOneEnvelope(String baseUrl, String carId, PrivateKey carPriv, JSONObject outer) {
        String commandId = outer.optString("commandId", "");
        String phoneId = outer.optString("phoneId", "");
        if (commandId.isEmpty()) return;

        if (vault.wasCommandExecuted(commandId)) {
            sendSignedAck(baseUrl, carId, carPriv, commandId, true, "Already executed (deduplicated)");
            return;
        }

        PublicKey phonePub = vault.getTrustedPhonePublicKey(phoneId);
        JSONObject inner;
        try {
            inner = RemoteEnvelope.verifyAndDecryptOnCar(
                    outer, carId, carPriv, phonePub, System.currentTimeMillis());
        } catch (Throwable secErr) {
            sendSignedAck(baseUrl, carId, carPriv, commandId, false, "Security rejected: " + secErr.getMessage());
            return;
        }

        String action = inner.optString("action", "").trim();
        String arg = inner.optString("arg", "");
        JSONObject themeObj = inner.optJSONObject("climateTheme");
        if ("apply_climate_theme".equals(action) && themeObj != null) {
            arg = themeObj.toString();
        }

        DaemonClient.CmdResult execRes = DaemonClient.sendVehicleCommand(action, arg, "remote");
        vault.recordExecutedCommand(commandId);
        vault.touchPhoneSeen(phoneId);
        statusSummary = (execRes.ok ? "Executed remote: " : "Refused remote: ") + execRes.message;
        sendSignedAck(baseUrl, carId, carPriv, commandId, execRes.ok, execRes.message);
    }

    private void sendSignedAck(
            String baseUrl,
            String carId,
            PrivateKey carPriv,
            String commandId,
            boolean ok,
            String resultMessage) {
        try {
            long now = System.currentTimeMillis();
            String msg = resultMessage == null ? "" : resultMessage;
            String msgHash = CryptoBox.sha256Hex(msg.getBytes(CryptoBox.UTF8));
            String canonical = RemoteEnvelope.buildCarAckCanonical(commandId, carId, ok, now, msgHash);
            String sig = CryptoBox.signEcdsaSha256B64(carPriv, canonical);

            JSONObject body = new JSONObject();
            body.put("commandId", commandId);
            body.put("carId", carId);
            body.put("ok", ok);
            body.put("status", ok ? "EXECUTED" : "FAILED");
            body.put("message", msg);
            body.put("executedAtMs", now);
            body.put("carSignature", sig);

            postJson(baseUrl + "/api/v1/car/ack", body, vault.getRemoteAccountToken(), 7000);
        } catch (Throwable ignored) {
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

    private static HttpReply postJson(String httpsUrl, JSONObject payload, String bearerToken, int readTimeoutMs)
            throws Exception {
        if (httpsUrl == null || !httpsUrl.startsWith("https://")) {
            throw new SecurityException("Refusing non-HTTPS URL");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(httpsUrl).openConnection();
        try {
            RemoteTlsHelper.configure(conn);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Gen5WDigitalControlCar/1.0.3");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            byte[] outBytes = payload.toString().getBytes(CryptoBox.UTF8);
            conn.setFixedLengthStreamingMode(outBytes.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(outBytes);
                os.flush();
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String respText = readBoundedUtf8(is);
            return new HttpReply(code, respText);
        } finally {
            conn.disconnect();
        }
    }

    private static String readBoundedUtf8(InputStream is) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > MAX_HTTP_BYTES) {
                    throw new SecurityException("HTTP response exceeded MAX_HTTP_BYTES");
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), CryptoBox.UTF8);
        } finally {
            is.close();
        }
    }
}
