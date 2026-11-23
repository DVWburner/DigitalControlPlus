package com.gen5w.digitalcontrol.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import com.gen5w.digitalcontrol.shared.ClimateTheme;
import com.gen5w.digitalcontrol.shared.CommandLogEntry;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.ProtocolFrames;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional Android Bluetooth RFCOMM companion transport:
 *  - Accepts incoming RFCOMM connections from the Gen5W head unit (for zero-friction pairing when
 *    sitting in the car and tapping "Connect App").
 *  - Actively probes & auto-connects outbound to the paired car's RFCOMM listener whenever the
 *    phone app is open (e.g., from inside the house while the car is charging in the driveway).
 */
public final class PhoneBtClient {
    public interface Listener {
        void onConnectionStateChanged(boolean connected, String statusMessage);
        void onPairingCodeChallenge(String sas4Code, String carName);
        void onPairingSucceeded(String carName);
        void onUntrustedByCar(String reason);
        void onTelemetryUpdated(VehicleSnapshot snapshot);
        void onCommandResult(boolean ok, String message);
    }

    public interface CommandCallback {
        void onResult(boolean ok, String message);
    }

    private final PhoneStore store;
    private final Listener listener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile boolean active = false;
    private volatile boolean connectedAndAuthenticated = false;
    private volatile BluetoothServerSocket phoneRfcommServer;
    private volatile ActiveWire currentWire;
    private final Map<String, CommandCallback> pendingCallbacks = new ConcurrentHashMap<String, CommandCallback>();

    public PhoneBtClient(PhoneStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    public boolean isConnected() {
        return connectedAndAuthenticated && currentWire != null && !currentWire.closed;
    }

    public void start() {
        if (active) return;
        active = true;
        startInboundRfcommServer();
        startAutoConnectLoop();
    }

    public void stop() {
        active = false;
        connectedAndAuthenticated = false;
        closeServerQuietly(phoneRfcommServer);
        phoneRfcommServer = null;
        ActiveWire w = currentWire;
        currentWire = null;
        if (w != null) w.close();
    }

    public List<BluetoothDevice> listBondedDevicesSafe() {
        List<BluetoothDevice> list = new ArrayList<BluetoothDevice>();
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null || !ba.isEnabled()) return list;
            Set<BluetoothDevice> set = ba.getBondedDevices();
            if (set != null) list.addAll(set);
        } catch (SecurityException ignored) {
        } catch (Throwable ignored) {
        }
        return list;
    }

    /**
     * Initiates pairing over Bluetooth with a user-selected bonded car Bluetooth device.
     */
    public void startManualPairWithDevice(final BluetoothDevice device) {
        if (device == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                connectOutboundToDevice(device, true);
            }
        }, "phone-bt-manual-pair");
        t.setDaemon(true);
        t.start();
    }

    public void confirmPendingSasCode() {
        final ActiveWire w = currentWire;
        if (w != null) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    w.sendPhoneSasConfirmation();
                }
            }, "phone-bt-sas-ok");
            t.setDaemon(true);
            t.start();
        }
    }

    public void cancelPendingPairing() {
        ActiveWire w = currentWire;
        if (w != null && !connectedAndAuthenticated) {
            w.close();
        }
    }

    public void sendCommand(final String action, final String arg, final ClimateTheme theme, final CommandCallback cb) {
        sendCommand(action, arg, theme, action, cb);
    }

    public void sendCommand(
            final String action,
            final String arg,
            final ClimateTheme theme,
            final String displayLabel,
            final CommandCallback cb) {
        final ActiveWire w = currentWire;
        if (!isConnected() || w == null) {
            if (cb != null) cb.onResult(false, "Bluetooth is not connected to car");
            return;
        }
        final String reqId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        final long now = System.currentTimeMillis();
        store.recordCommandSent(reqId, displayLabel, "BT", now, now + 15_000L);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                w.sendEncryptedCommandWithId(reqId, action, arg, theme, cb);
            }
        }, "phone-bt-cmd");
        t.setDaemon(true);
        t.start();
    }

    public void syncRemoteServerToCar(final String remoteUrl, final String remoteAccountToken) {
        final ActiveWire w = currentWire;
        if (!isConnected() || w == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                w.sendEncryptedRemoteServerConfig(remoteUrl, remoteAccountToken);
            }
        }, "phone-bt-remcfg");
        t.setDaemon(true);
        t.start();
    }

    private void startInboundRfcommServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (active) {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    if (ba == null || !ba.isEnabled()) {
                        sleepQuiet(3000L);
                        continue;
                    }
                    BluetoothServerSocket ss = null;
                    try {
                        ss = ba.listenUsingRfcommWithServiceRecord(
                                "Gen5WPhoneCompanion", ProtocolFrames.UUID_PHONE_RFCOMM_SERVER);
                        phoneRfcommServer = ss;
                        while (active) {
                            BluetoothSocket sock = ss.accept();
                            if (sock != null) {
                                handleConnectedSocket(sock, !store.isCarPaired());
                            }
                        }
                    } catch (Throwable ignored) {
                        sleepQuiet(2500L);
                    } finally {
                        closeServerQuietly(ss);
                    }
                }
            }
        }, "phone-rfcomm-accept");
        t.setDaemon(true);
        t.start();
    }

    private void startAutoConnectLoop() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (active) {
                    if (!isConnected() && store.isCarPaired()) {
                        postState(false, "Looking for car BT signal...");
                        tryAutoConnectPairedCar();
                    } else if (!isConnected()) {
                        postState(false, "Looking for car BT signal...");
                    }
                    sleepQuiet(isConnected() ? 3500L : 4000L);
                }
            }
        }, "phone-bt-autoconnect");
        t.setDaemon(true);
        t.start();
    }

    private void tryAutoConnectPairedCar() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null || !ba.isEnabled()) return;

        String targetAddr = store.getPairedCarBtAddress();
        if (!targetAddr.isEmpty()) {
            try {
                BluetoothDevice dev = ba.getRemoteDevice(targetAddr);
                if (connectOutboundToDevice(dev, false)) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // Fallback: check bonded car head units matching known Gen5W BT names
        for (BluetoothDevice d : listBondedDevicesSafe()) {
            String n = safeName(d).toLowerCase(Locale.US);
            if (n.contains("ioniq") || n.contains("ev6") || n.contains("hyundai")
                    || n.contains("kia") || n.contains("genesis") || n.contains("gen5w")) {
                if (connectOutboundToDevice(d, false)) {
                    return;
                }
            }
        }
    }

    private boolean connectOutboundToDevice(BluetoothDevice device, boolean wantPairing) {
        if (!active || device == null) return false;
        ActiveWire existing = currentWire;
        if (existing != null && !existing.closed) return true;

        BluetoothSocket sock = null;
        try {
            sock = device.createRfcommSocketToServiceRecord(ProtocolFrames.UUID_CAR_RFCOMM_SERVER);
            sock.connect();
            handleConnectedSocket(sock, wantPairing || !store.isCarPaired());
            return true;
        } catch (Throwable ignored) {
            closeSocketQuietly(sock);
        }
        try {
            sock = device.createInsecureRfcommSocketToServiceRecord(ProtocolFrames.UUID_CAR_RFCOMM_SERVER);
            sock.connect();
            handleConnectedSocket(sock, wantPairing || !store.isCarPaired());
            return true;
        } catch (Throwable ignored2) {
            closeSocketQuietly(sock);
        }
        return false;
    }

    private synchronized void handleConnectedSocket(BluetoothSocket sock, boolean pairFlow) {
        ActiveWire prev = currentWire;
        if (prev != null && !prev.closed) {
            closeSocketQuietly(sock);
            return;
        }
        ActiveWire wire = new ActiveWire(sock, pairFlow);
        currentWire = wire;
        wire.start();
    }

    private final class ActiveWire {
        private final BluetoothSocket sock;
        private final boolean pairMode;
        private final String peerBtAddress;
        volatile boolean closed = false;

        private InputStream in;
        private OutputStream out;
        private KeyPair phoneEphKeyPair;
        private String phoneEphPubB64;
        private String phoneNonce;
        private String carId = "";
        private String carName = "Ioniq 5";
        private String carStaticPubB64 = "";
        private String carNonce = "";
        private String sas4Code = "";
        private byte[] sessionKey64;
        private long txSeq = 1L;
        private long minRxSeq = 1L;

        ActiveWire(BluetoothSocket sock, boolean pairMode) {
            this.sock = sock;
            this.pairMode = pairMode;
            BluetoothDevice remote = sock.getRemoteDevice();
            this.peerBtAddress = (remote != null && remote.getAddress() != null) ? remote.getAddress() : "";
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWire();
                }
            }, "phone-bt-wire");
            t.setDaemon(true);
            t.start();
        }

        private void runWire() {
            try {
                in = sock.getInputStream();
                out = sock.getOutputStream();

                phoneEphKeyPair = CryptoBox.generateEcP256KeyPair();
                phoneEphPubB64 = CryptoBox.encodePublicKeySpkiB64(phoneEphKeyPair.getPublic());
                phoneNonce = CryptoBox.randomHex(16);

                if (pairMode) {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "pair_hello");
                    hello.put("phoneId", store.getPhoneId());
                    hello.put("phoneName", store.getPhoneName());
                    hello.put("platform", "android");
                    hello.put("phoneStaticPub", store.getPhonePublicKeySpkiB64());
                    hello.put("phoneEphPub", phoneEphPubB64);
                    hello.put("phoneNonce", phoneNonce);
                    ProtocolFrames.writeLineFrame(out, hello.toString());
                } else {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "auth_hello");
                    hello.put("phoneId", store.getPhoneId());
                    hello.put("phoneEphPub", phoneEphPubB64);
                    hello.put("phoneNonce", phoneNonce);
                    ProtocolFrames.writeLineFrame(out, hello.toString());
                }

                String line;
                while (active && !closed && (line = ProtocolFrames.readLineFrame(in)) != null) {
                    handleIncoming(new JSONObject(line));
                }
            } catch (Throwable ignored) {
            } finally {
                close();
            }
        }

        private void handleIncoming(JSONObject msg) throws Exception {
            String type = msg.optString("type", "");
            if ("pair_challenge".equals(type)) {
                carId = msg.optString("carId", "");
                carName = msg.optString("carName", "Ioniq 5");
                carStaticPubB64 = msg.optString("carStaticPub", "");
                String carEphPubB64 = msg.optString("carEphPub", "");
                carNonce = msg.optString("carNonce", "");

                PublicKey carEphPub = CryptoBox.decodePublicKeySpkiB64(carEphPubB64);
                sas4Code = CryptoBox.deriveSas4Code(
                        phoneEphKeyPair.getPrivate(),
                        carEphPub,
                        carStaticPubB64,
                        store.getPhonePublicKeySpkiB64(),
                        carEphPubB64,
                        phoneEphPubB64,
                        carNonce,
                        phoneNonce);

                byte[] z = CryptoBox.ecdhSharedSecret32(phoneEphKeyPair.getPrivate(), carEphPub);
                byte[] salt = CryptoBox.sha256((carNonce + "|" + phoneNonce).getBytes(CryptoBox.UTF8));
                sessionKey64 = CryptoBox.hkdfSha256(z, salt, "GEN5W-BT-SESS-V1".getBytes(CryptoBox.UTF8), 64);

                final String code = sas4Code;
                final String cName = carName;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onPairingCodeChallenge(code, cName);
                    }
                });
            } else if ("pair_complete".equals(type)) {
                long pairedAt = msg.optLong("pairedAtMs", System.currentTimeMillis());
                String grantSig = msg.optString("pairingGrantSig", "");
                String remUrl = msg.optString("remoteUrl", "");
                store.savePairedCar(carId, carName, peerBtAddress, carStaticPubB64, pairedAt, grantSig, remUrl);
                connectedAndAuthenticated = true;
                postState(true, "Connected to " + carName + " via Bluetooth");
                final String cName = carName;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onPairingSucceeded(cName);
                    }
                });
            } else if ("auth_challenge".equals(type)) {
                String cId = msg.optString("carId", "");
                String carEphPubB64 = msg.optString("carEphPub", "");
                carNonce = msg.optString("carNonce", "");
                String carSig = msg.optString("carSig", "");

                PublicKey trustedCarPub = store.getPairedCarPublicKey();
                if (trustedCarPub == null || !cId.equals(store.getPairedCarId())) {
                    close();
                    return;
                }
                String expectedCarAuth = "GEN5W-AUTH-CAR|" + store.getPhoneId() + "|" + phoneNonce + "|" + carNonce + "|" + carEphPubB64;
                if (!CryptoBox.verifyEcdsaSha256B64(trustedCarPub, expectedCarAuth, carSig)) {
                    close();
                    return;
                }
                PublicKey carEphPub = CryptoBox.decodePublicKeySpkiB64(carEphPubB64);
                byte[] z = CryptoBox.ecdhSharedSecret32(phoneEphKeyPair.getPrivate(), carEphPub);
                byte[] salt = CryptoBox.sha256((carNonce + "|" + phoneNonce).getBytes(CryptoBox.UTF8));
                sessionKey64 = CryptoBox.hkdfSha256(z, salt, "GEN5W-BT-SESS-V1".getBytes(CryptoBox.UTF8), 64);

                PrivateKey phonePriv = store.getPhonePrivateKey();
                String phoneAuth = "GEN5W-AUTH-PHONE|" + cId + "|" + carNonce + "|" + phoneNonce + "|" + phoneEphPubB64;
                String phoneSig = CryptoBox.signEcdsaSha256B64(phonePriv, phoneAuth);

                JSONObject verify = new JSONObject();
                verify.put("type", "auth_verify");
                verify.put("phoneSig", phoneSig);
                ProtocolFrames.writeLineFrame(out, verify.toString());
            } else if ("auth_ok".equals(type)) {
                String carRemUrl = msg.optString("remoteUrl", "").trim();
                if (carRemUrl.startsWith("https://") && !store.isRemoteServerConfigured()) {
                    store.setRemoteServerUrl(carRemUrl);
                }
                connectedAndAuthenticated = true;
                postState(true, "Connected to " + store.getPairedCarName() + " via Bluetooth");
            } else if ("enc".equals(type)) {
                if (sessionKey64 == null) return;
                String packed = msg.optString("frame", "");
                int dot = packed.indexOf('.');
                long seq = Long.parseLong(packed.substring(0, dot));
                String plain = CryptoBox.decryptSessionFrame(sessionKey64, minRxSeq, packed);
                minRxSeq = seq + 1L;
                handleDecryptedInner(new JSONObject(plain));
            } else if ("pair_error".equals(type) || "auth_error".equals(type) || "protocol_error".equals(type)) {
                final String reason = msg.optString("reason", "Connection refused by car");
                final String code = msg.optString("code", "");
                if ("UNTRUSTED".equals(code)) {
                    store.clearPairedCar();
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onUntrustedByCar(reason);
                        }
                    });
                } else {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onCommandResult(false, reason);
                        }
                    });
                }
                close();
            }
        }

        private void handleDecryptedInner(JSONObject inner) {
            String op = inner.optString("op", "");
            if ("telemetry".equals(op)) {
                store.saveCachedVehicleJson(inner);
                final VehicleSnapshot snap = VehicleSnapshot.fromJson(inner);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onTelemetryUpdated(snap);
                    }
                });
            } else if ("cmd_result".equals(op)) {
                String reqId = inner.optString("reqId", "");
                final boolean ok = inner.optBoolean("ok", false);
                final String message = inner.optString("message", "");
                store.updateCommandLogState(
                        reqId,
                        ok ? CommandLogEntry.STATE_EXECUTED : CommandLogEntry.STATE_FAILED,
                        System.currentTimeMillis(),
                        message);
                JSONObject vals = inner.optJSONObject("values");
                if (vals != null) {
                    try {
                        JSONObject wrap = new JSONObject();
                        wrap.put("values", vals);
                        wrap.put("updatedMs", System.currentTimeMillis());
                        store.saveCachedVehicleJson(wrap);
                        final VehicleSnapshot snap = VehicleSnapshot.fromJson(wrap);
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onTelemetryUpdated(snap);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
                final CommandCallback cb = pendingCallbacks.remove(reqId);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onResult(ok, message);
                        listener.onCommandResult(ok, message);
                    }
                });
            }
        }

        void sendPhoneSasConfirmation() {
            try {
                if (sas4Code.isEmpty() || out == null) return;
                PrivateKey phonePriv = store.getPhonePrivateKey();
                String canonical = "GEN5W-PAIR-CONFIRM|" + sas4Code + "|" + carNonce + "|" + phoneNonce;
                String sig = CryptoBox.signEcdsaSha256B64(phonePriv, canonical);
                JSONObject confirm = new JSONObject();
                confirm.put("type", "pair_phone_confirm");
                confirm.put("sig", sig);
                ProtocolFrames.writeLineFrame(out, confirm.toString());
            } catch (Throwable t) {
                close();
            }
        }

        synchronized void sendEncryptedCommandWithId(
                String reqId, String action, String arg, ClimateTheme theme, CommandCallback cb) {
            try {
                if (sessionKey64 == null || out == null || closed) {
                    store.updateCommandLogState(reqId, CommandLogEntry.STATE_FAILED, System.currentTimeMillis(), "Disconnected");
                    if (cb != null) cb.onResult(false, "Disconnected");
                    return;
                }
                if (cb != null) {
                    pendingCallbacks.put(reqId, cb);
                }
                JSONObject inner = new JSONObject();
                inner.put("op", "cmd");
                inner.put("reqId", reqId);
                inner.put("action", action);
                inner.put("arg", arg == null ? "" : arg);
                if (theme != null) {
                    inner.put("climateTheme", theme.toJson());
                }

                long s = txSeq++;
                String frame = CryptoBox.encryptSessionFrame(sessionKey64, s, inner.toString());
                JSONObject env = new JSONObject();
                env.put("type", "enc");
                env.put("frame", frame);
                ProtocolFrames.writeLineFrame(out, env.toString());
            } catch (Throwable t) {
                store.updateCommandLogState(reqId, CommandLogEntry.STATE_FAILED, System.currentTimeMillis(), t.getMessage());
                if (cb != null) cb.onResult(false, "BT write error: " + t.getMessage());
                close();
            }
        }

        synchronized void sendEncryptedRemoteServerConfig(String remoteUrl, String remoteAccountToken) {
            try {
                if (sessionKey64 == null || out == null || closed) return;
                String reqId = "cfg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                JSONObject inner = new JSONObject();
                inner.put("op", "configure_remote_server");
                inner.put("reqId", reqId);
                inner.put("remoteUrl", remoteUrl == null ? "" : remoteUrl);
                inner.put("remoteAccountToken", remoteAccountToken == null ? "" : remoteAccountToken);

                long s = txSeq++;
                String frame = CryptoBox.encryptSessionFrame(sessionKey64, s, inner.toString());
                JSONObject env = new JSONObject();
                env.put("type", "enc");
                env.put("frame", frame);
                ProtocolFrames.writeLineFrame(out, env.toString());
            } catch (Throwable ignored) {
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            if (currentWire == this) {
                currentWire = null;
                connectedAndAuthenticated = false;
                postState(false, "Looking for car BT signal...");
            }
            closeSocketQuietly(sock);
        }
    }

    private void postState(final boolean conn, final String msg) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnectionStateChanged(conn, msg);
            }
        });
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "" : n;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void closeServerQuietly(BluetoothServerSocket ss) {
        if (ss != null) {
            try { ss.close(); } catch (Throwable ignored) {}
        }
    }

    private static void closeSocketQuietly(BluetoothSocket s) {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }
}
