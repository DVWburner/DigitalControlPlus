package com.gen5w.digitalcontrol;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.gen5w.digitalcontrol.edition.EditionConfig;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.ProtocolFrames;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent in-car companion service managing:
 *  1. TrustVault (first-use PIN, paired phones, car EC P-256 identity keypair — wiped on uninstall).
 *  2. Recognition of phones already connected to Gen5W Bluetooth (HFP / A2DP / ACL) + outbound connect.
 *  3. Bidirectional Classic Bluetooth RFCOMM server & client (Android phones).
 *  4. BLE Central scanner + GATT client (iOS CBPeripheralManager & BLE phones on Android 4.4.2).
 *  5. Bluetooth PAN (bnep0) local socket server when BT hotspot interface is active.
 *  6. 4-character SAS visual confirmation pairing + mutual ECDSA authenticated encrypted sessions.
 *  7. Private Edition opportunistic remote relay hook (no-op in Public Edition).
 */
public final class CarLinkService extends Service {
    private static final long PAIRING_WINDOW_DURATION_MS = 120_000L; // 2 minutes after PIN unlock

    public static final class BtPhoneCandidate {
        public final String name;
        public final String address;
        public final boolean profileConnected;
        public final boolean alreadyTrusted;

        BtPhoneCandidate(String name, String address, boolean profileConnected, boolean alreadyTrusted) {
            this.name = (name == null || name.trim().isEmpty()) ? address : name.trim();
            this.address = address;
            this.profileConnected = profileConnected;
            this.alreadyTrusted = alreadyTrusted;
        }
    }

    public static final class PendingPairingPrompt {
        public final String sessionId;
        public final String phoneId;
        public final String phoneName;
        public final String platform;
        public final String btAddress;
        public final String sas4Code;
        public final boolean phoneConfirmed;
        public final boolean carApproved;

        PendingPairingPrompt(
                String sessionId,
                String phoneId,
                String phoneName,
                String platform,
                String btAddress,
                String sas4Code,
                boolean phoneConfirmed,
                boolean carApproved) {
            this.sessionId = sessionId;
            this.phoneId = phoneId;
            this.phoneName = phoneName;
            this.platform = platform;
            this.btAddress = btAddress;
            this.sas4Code = sas4Code;
            this.phoneConfirmed = phoneConfirmed;
            this.carApproved = carApproved;
        }
    }

    public interface UiListener {
        void onStateChanged();
    }

    public final class LocalBinder extends Binder {
        public CarLinkService getService() {
            return CarLinkService.this;
        }
    }

    private interface PeerChannel {
        void sendFrame(String jsonLine) throws IOException;
        void close();
        String getPeerAddress();
        String getTransportLabel();
    }

    private final IBinder localBinder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<UiListener> uiListeners = new CopyOnWriteArrayList<UiListener>();
    private final CopyOnWriteArrayList<ProtocolSession> activeSessions = new CopyOnWriteArrayList<ProtocolSession>();

    private TrustVault vault;
    private EditionConfig.RemoteBridgeHandle remoteBridge;

    private BluetoothProfile headsetProxy;
    private BluetoothProfile a2dpProxy;
    private volatile boolean running = false;
    private volatile BluetoothServerSocket rfcommSecureServer;
    private volatile BluetoothServerSocket rfcommInsecureServer;
    private volatile ServerSocket bnepServerSocket;
    private volatile String boundBnepIp = "";

    private volatile boolean bleScanning = false;
    private final Map<String, Long> recentOutboundAttemptMs = new LinkedHashMap<String, Long>();

    private volatile long pairingWindowUntilMs = 0L;
    private volatile ProtocolSession pendingPairingSession;
    private volatile String lastActivityNote = "Ready";

    private final BroadcastReceiver btAndNetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev != null) {
                    attemptOutboundConnectToPhoneAsync(dev, false);
                }
                notifyUiChanged();
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act)
                    || BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                notifyUiChanged();
            } else if ("android.net.conn.CONNECTIVITY_CHANGE".equals(act)) {
                if (remoteBridge != null) {
                    remoteBridge.notifyConnectivityChanged();
                }
                notifyUiChanged();
            }
        }
    };

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            if (device == null || !running) return;
            String addr = device.getAddress();
            if (addr == null) return;
            long now = System.currentTimeMillis();
            synchronized (recentOutboundAttemptMs) {
                Long prev = recentOutboundAttemptMs.get("BLE:" + addr);
                if (prev != null && (now - prev.longValue()) < 8000L) {
                    return;
                }
                recentOutboundAttemptMs.put("BLE:" + addr, Long.valueOf(now));
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    connectBleGattDevice(device);
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        vault = new TrustVault(this);
        running = true;

        bindBluetoothProfileProxies();
        registerSystemReceivers();
        startRfcommAcceptThreads();
        startBleCentralScannerLoop();
        startBnepPanAcceptLoop();
        startLiveTelemetryBroadcastLoop();

        remoteBridge = EditionConfig.startRemoteBridgeIfEnabled(this, vault);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return localBinder;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopBleScanSafe();
        closeQuietly(rfcommSecureServer);
        closeQuietly(rfcommInsecureServer);
        closeServerSocketQuietly(bnepServerSocket);
        for (ProtocolSession s : activeSessions) {
            s.close();
        }
        activeSessions.clear();
        if (remoteBridge != null) {
            remoteBridge.stop();
        }
        try {
            unregisterReceiver(btAndNetReceiver);
        } catch (Throwable ignored) {
        }
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba != null) {
            if (headsetProxy != null) ba.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy);
            if (a2dpProxy != null) ba.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
        }
        super.onDestroy();
    }

    public void addUiListener(UiListener l) {
        if (l != null && !uiListeners.contains(l)) {
            uiListeners.add(l);
        }
    }

    public void removeUiListener(UiListener l) {
        uiListeners.remove(l);
    }

    private void notifyUiChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (UiListener l : uiListeners) {
                    try {
                        l.onStateChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    public TrustVault getVault() {
        return vault;
    }

    public String getRemoteStatusSummary() {
        return remoteBridge == null ? "Disabled" : remoteBridge.getStatusSummary();
    }

    public String getLastActivityNote() {
        return lastActivityNote;
    }

    public int getAuthenticatedBtSessionCount() {
        int n = 0;
        for (ProtocolSession s : activeSessions) {
            if (s.authenticated) n++;
        }
        return n;
    }

    public List<String> getConnectedCompanionSummaries() {
        List<String> list = new ArrayList<String>();
        for (ProtocolSession s : activeSessions) {
            if (s.authenticated) {
                list.add(s.peerPhoneName + " (" + s.channel.getTransportLabel() + ")");
            }
        }
        return list;
    }

    public boolean isPairingWindowOpen() {
        return vault.isPinConfigured() && System.currentTimeMillis() < pairingWindowUntilMs;
    }

    public long getPairingWindowRemainingSec() {
        long rem = pairingWindowUntilMs - System.currentTimeMillis();
        return rem <= 0L ? 0L : ((rem + 999L) / 1000L);
    }

    /**
     * Opens a 120-second pairing window after the car owner unlocks with their PIN,
     * and immediately attempts to connect to any already-connected Bluetooth phone!
     */
    public void openPairingWindowAndProbeConnectedPhones() {
        if (!vault.isPinConfigured()) return;
        pairingWindowUntilMs = System.currentTimeMillis() + PAIRING_WINDOW_DURATION_MS;
        lastActivityNote = "Pairing Mode open — probing connected Bluetooth phone(s)...";
        notifyUiChanged();
        probeConnectedBluetoothPhonesNow();
    }

    public void closePairingWindow() {
        pairingWindowUntilMs = 0L;
        ProtocolSession p = pendingPairingSession;
        if (p != null && !p.authenticated) {
            p.sendErrorAndClose("pair_error", "Pairing canceled on car screen.");
            pendingPairingSession = null;
        }
        notifyUiChanged();
    }

    public void probeConnectedBluetoothPhonesNow() {
        List<BtPhoneCandidate> candidates = listBluetoothPhones();
        for (BtPhoneCandidate c : candidates) {
            if (c.profileConnected) {
                connectToAddressNow(c.address);
            }
        }
        triggerImmediateBleBurst();
    }

    public void connectToAddressNow(final String btAddress) {
        if (btAddress == null || btAddress.isEmpty()) return;
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null) return;
        try {
            BluetoothDevice dev = ba.getRemoteDevice(btAddress);
            attemptOutboundConnectToPhoneAsync(dev, true);
        } catch (Throwable ignored) {
        }
    }

    public PendingPairingPrompt getPendingPairingPrompt() {
        ProtocolSession s = pendingPairingSession;
        if (s == null || s.closed || s.authenticated || s.sas4Code == null) {
            return null;
        }
        return new PendingPairingPrompt(
                s.sessionId,
                s.peerPhoneId,
                s.peerPhoneName,
                s.peerPlatform,
                s.channel.getPeerAddress(),
                s.sas4Code,
                s.phoneConfirmedSas,
                s.carApprovedSas);
    }

    public void approvePendingPairingOnCar(String sessionId) {
        ProtocolSession s = pendingPairingSession;
        if (s != null && s.sessionId.equals(sessionId)) {
            s.approveOnCarScreen();
            notifyUiChanged();
        }
    }

    public void rejectPendingPairingOnCar(String sessionId) {
        ProtocolSession s = pendingPairingSession;
        if (s != null && s.sessionId.equals(sessionId)) {
            pendingPairingSession = null;
            s.sendErrorAndClose("pair_error", "Pairing rejected on car screen.");
            lastActivityNote = "Rejected pairing request";
            notifyUiChanged();
        }
    }

    public void revokeTrustedPhone(String phoneId) {
        if (vault.revokePhone(phoneId)) {
            for (ProtocolSession s : activeSessions) {
                if (phoneId.equals(s.peerPhoneId)) {
                    s.close();
                }
            }
            if (remoteBridge != null) {
                remoteBridge.notifyTrustUpdated();
            }
            lastActivityNote = "Revoked phone trust";
            notifyUiChanged();
        }
    }

    public void resetVaultAndDisconnectAll() {
        pairingWindowUntilMs = 0L;
        pendingPairingSession = null;
        vault.resetAllAndClearPin();
        for (ProtocolSession s : activeSessions) {
            s.close();
        }
        if (remoteBridge != null) {
            remoteBridge.notifyTrustUpdated();
        }
        lastActivityNote = "PIN & all phone trust relationships reset";
        notifyUiChanged();
    }

    public void notifyTrustVaultChanged() {
        if (remoteBridge != null) {
            remoteBridge.notifyTrustUpdated();
        }
        notifyUiChanged();
    }

    /**
     * Lists Bluetooth phones known to the Gen5W head unit, prioritizing phones currently
     * connected over HFP/A2DP/ACL.
     */
    public List<BtPhoneCandidate> listBluetoothPhones() {
        Map<String, BtPhoneCandidate> map = new LinkedHashMap<String, BtPhoneCandidate>();
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null) return new ArrayList<BtPhoneCandidate>();

        List<TrustVault.TrustedPhone> trusted = vault.listTrustedPhones();
        try {
            List<BluetoothDevice> activeDevs = new ArrayList<BluetoothDevice>();
            if (headsetProxy != null) {
                activeDevs.addAll(headsetProxy.getConnectedDevices());
            }
            if (a2dpProxy != null) {
                for (BluetoothDevice d : a2dpProxy.getConnectedDevices()) {
                    if (!activeDevs.contains(d)) activeDevs.add(d);
                }
            }
            for (BluetoothDevice d : activeDevs) {
                String addr = d.getAddress();
                if (addr == null) continue;
                map.put(addr, new BtPhoneCandidate(d.getName(), addr, true, isAddressTrusted(addr, trusted)));
            }

            Set<BluetoothDevice> bonded = ba.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String addr = d.getAddress();
                    if (addr == null || map.containsKey(addr)) continue;
                    boolean aclConn = invokeIsConnectedReflect(d);
                    map.put(addr, new BtPhoneCandidate(d.getName(), addr, aclConn, isAddressTrusted(addr, trusted)));
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<BtPhoneCandidate>(map.values());
    }

    private static boolean isAddressTrusted(String addr, List<TrustVault.TrustedPhone> trusted) {
        for (TrustVault.TrustedPhone tp : trusted) {
            if (addr.equalsIgnoreCase(tp.btAddress)) return true;
        }
        return false;
    }

    private static boolean invokeIsConnectedReflect(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            Object res = m.invoke(d);
            return res instanceof Boolean && ((Boolean) res).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void bindBluetoothProfileProxies() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null) return;
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HEADSET) headsetProxy = proxy;
                else if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy;
                notifyUiChanged();
                // Auto-probe already connected trusted phones on startup
                for (BluetoothDevice d : proxy.getConnectedDevices()) {
                    attemptOutboundConnectToPhoneAsync(d, false);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HEADSET) headsetProxy = null;
                else if (profile == BluetoothProfile.A2DP) a2dpProxy = null;
                notifyUiChanged();
            }
        };
        try {
            ba.getProfileProxy(this, listener, BluetoothProfile.HEADSET);
            ba.getProfileProxy(this, listener, BluetoothProfile.A2DP);
        } catch (Throwable ignored) {
        }
    }

    private void registerSystemReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(btAndNetReceiver, f);
    }

    // ---- 1. Classic Bluetooth RFCOMM Server & Outbound Client ----

    private void startRfcommAcceptThreads() {
        startOneRfcommListener(true);
        startOneRfcommListener(false);
    }

    private void startOneRfcommListener(final boolean secure) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    if (ba == null || !ba.isEnabled()) {
                        sleepQuiet(3000L);
                        continue;
                    }
                    BluetoothServerSocket ss = null;
                    try {
                        ss = secure
                                ? ba.listenUsingRfcommWithServiceRecord("Gen5WDigitalControl", ProtocolFrames.UUID_CAR_RFCOMM_SERVER)
                                : ba.listenUsingInsecureRfcommWithServiceRecord("Gen5WDigitalControlInsecure", ProtocolFrames.UUID_CAR_RFCOMM_SERVER);
                        if (secure) rfcommSecureServer = ss;
                        else rfcommInsecureServer = ss;

                        while (running) {
                            BluetoothSocket client = ss.accept();
                            if (client != null) {
                                attachStreamSocketSession(client);
                            }
                        }
                    } catch (Throwable ignored) {
                        sleepQuiet(2500L);
                    } finally {
                        closeQuietly(ss);
                    }
                }
            }
        }, secure ? "digctl-rfcomm-sec" : "digctl-rfcomm-insec");
        t.setDaemon(true);
        t.start();
    }

    private void attemptOutboundConnectToPhoneAsync(final BluetoothDevice device, final boolean forceEvenIfUntrusted) {
        if (device == null || !running || !vault.isPinConfigured()) return;
        final String addr = device.getAddress();
        if (addr == null) return;

        // Only auto-connect outbound if either Pairing Mode is open, user tapped Connect, or this BT address is already trusted
        if (!forceEvenIfUntrusted && !isPairingWindowOpen() && !isAddressTrusted(addr, vault.listTrustedPhones())) {
            return;
        }
        for (ProtocolSession s : activeSessions) {
            if (addr.equalsIgnoreCase(s.channel.getPeerAddress())) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        synchronized (recentOutboundAttemptMs) {
            Long prev = recentOutboundAttemptMs.get("RFCOMM:" + addr);
            if (!forceEvenIfUntrusted && prev != null && (now - prev.longValue()) < 6000L) {
                return;
            }
            recentOutboundAttemptMs.put("RFCOMM:" + addr, Long.valueOf(now));
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothSocket sock = null;
                try {
                    sock = device.createRfcommSocketToServiceRecord(ProtocolFrames.UUID_PHONE_RFCOMM_SERVER);
                    sock.connect();
                    attachStreamSocketSession(sock);
                    return;
                } catch (Throwable ignored) {
                    closeBluetoothSocketQuietly(sock);
                }
                try {
                    sock = device.createInsecureRfcommSocketToServiceRecord(ProtocolFrames.UUID_PHONE_RFCOMM_SERVER);
                    sock.connect();
                    attachStreamSocketSession(sock);
                } catch (Throwable ignored2) {
                    closeBluetoothSocketQuietly(sock);
                }
            }
        }, "digctl-rfcomm-out");
        t.setDaemon(true);
        t.start();
    }

    private void attachStreamSocketSession(final BluetoothSocket sock) {
        final String addr = sock.getRemoteDevice() != null ? sock.getRemoteDevice().getAddress() : "BT";
        try {
            final InputStream in = sock.getInputStream();
            final OutputStream out = sock.getOutputStream();
            PeerChannel ch = new PeerChannel() {
                @Override
                public void sendFrame(String jsonLine) throws IOException {
                    ProtocolFrames.writeLineFrame(out, jsonLine);
                }

                @Override
                public void close() {
                    closeBluetoothSocketQuietly(sock);
                }

                @Override
                public String getPeerAddress() {
                    return addr;
                }

                @Override
                public String getTransportLabel() {
                    return "BT RFCOMM";
                }
            };
            final ProtocolSession session = new ProtocolSession(ch);
            activeSessions.add(session);
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while (running && !session.closed && (line = ProtocolFrames.readLineFrame(in)) != null) {
                            session.onIncomingFrame(line);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        session.close();
                    }
                }
            }, "digctl-rfcomm-session");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            closeBluetoothSocketQuietly(sock);
        }
    }

    // ---- 2. BLE Central Scanner + GATT Client (for iOS CoreBluetooth & BLE Phones) ----

    private void startBleCentralScannerLoop() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    boolean wantScan = ba != null && ba.isEnabled() && vault.isPinConfigured()
                            && (isPairingWindowOpen() || !vault.listTrustedPhones().isEmpty());
                    if (wantScan) {
                        startBleScanSafe(ba);
                        sleepQuiet(4000L);
                        stopBleScanSafe();
                        sleepQuiet(isPairingWindowOpen() ? 1500L : 4500L);
                    } else {
                        stopBleScanSafe();
                        sleepQuiet(5000L);
                    }
                }
            }
        }, "digctl-ble-scan");
        t.setDaemon(true);
        t.start();
    }

    private void triggerImmediateBleBurst() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba != null && ba.isEnabled()) {
            startBleScanSafe(ba);
        }
    }

    @SuppressWarnings("deprecation")
    private synchronized void startBleScanSafe(BluetoothAdapter ba) {
        if (bleScanning) return;
        try {
            bleScanning = ba.startLeScan(new UUID[] { ProtocolFrames.UUID_BLE_SERVICE }, leScanCallback);
        } catch (Throwable ignored) {
            bleScanning = false;
        }
    }

    @SuppressWarnings("deprecation")
    private synchronized void stopBleScanSafe() {
        if (!bleScanning) return;
        bleScanning = false;
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null) ba.stopLeScan(leScanCallback);
        } catch (Throwable ignored) {
        }
    }

    private void connectBleGattDevice(final BluetoothDevice device) {
        final String addr = device.getAddress();
        for (ProtocolSession s : activeSessions) {
            if (addr != null && addr.equalsIgnoreCase(s.channel.getPeerAddress())) {
                return;
            }
        }
        try {
            final BleGattChannel gattChannel = new BleGattChannel(device);
            gattChannel.connect(this);
        } catch (Throwable ignored) {
        }
    }

    private final class BleGattChannel extends BluetoothGattCallback implements PeerChannel {
        private final BluetoothDevice device;
        private final ProtocolFrames.BleReassembler reassembler = new ProtocolFrames.BleReassembler();
        private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<byte[]>(512);
        private final AtomicInteger msgSeq = new AtomicInteger(1);
        private volatile BluetoothGatt gatt;
        private volatile BluetoothGattCharacteristic rxCharOnPhone;
        private volatile ProtocolSession session;
        private volatile boolean closed = false;
        private final Object writeDoneLock = new Object();
        private volatile boolean writePending = false;

        BleGattChannel(BluetoothDevice device) {
            this.device = device;
        }

        void connect(Context ctx) {
            this.gatt = device.connectGatt(ctx, false, this);
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    pumpWrites();
                }
            }, "digctl-ble-writer");
            writer.setDaemon(true);
            writer.start();
        }

        private void pumpWrites() {
            while (running && !closed) {
                try {
                    byte[] pkt = writeQueue.poll(2, TimeUnit.SECONDS);
                    if (pkt == null) continue;
                    BluetoothGatt g = gatt;
                    BluetoothGattCharacteristic ch = rxCharOnPhone;
                    if (g == null || ch == null) continue;
                    synchronized (writeDoneLock) {
                        writePending = true;
                        ch.setValue(pkt);
                        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        if (g.writeCharacteristic(ch)) {
                            long deadline = System.currentTimeMillis() + 1500L;
                            while (writePending && System.currentTimeMillis() < deadline) {
                                writeDoneLock.wait(250L);
                            }
                        }
                        writePending = false;
                    }
                } catch (InterruptedException ignored) {
                    break;
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.discoverServices();
                } catch (Throwable ignored) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                close();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                close();
                return;
            }
            BluetoothGattService svc = g.getService(ProtocolFrames.UUID_BLE_SERVICE);
            if (svc == null) {
                close();
                return;
            }
            BluetoothGattCharacteristic carToPhone = svc.getCharacteristic(ProtocolFrames.UUID_BLE_CHAR_CAR_TO_PHONE);
            BluetoothGattCharacteristic phoneToCar = svc.getCharacteristic(ProtocolFrames.UUID_BLE_CHAR_PHONE_TO_CAR);
            if (carToPhone == null || phoneToCar == null) {
                close();
                return;
            }
            this.rxCharOnPhone = carToPhone;
            g.setCharacteristicNotification(phoneToCar, true);
            BluetoothGattDescriptor cccd = phoneToCar.getDescriptor(ProtocolFrames.UUID_BLE_CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(cccd);
            }
            ProtocolSession s = new ProtocolSession(this);
            this.session = s;
            activeSessions.add(s);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            // Notify phone peripheral that GATT channel is ready for pair_hello or auth_hello
            try {
                JSONObject ready = new JSONObject();
                ready.put("type", "car_ready");
                ready.put("edition", EditionConfig.REMOTE_ENABLED ? "private" : "public");
                sendFrame(ready.toString());
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writeDoneLock) {
                writePending = false;
                writeDoneLock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] val = characteristic.getValue();
            final String complete = reassembler.acceptPacket(val);
            final ProtocolSession s = session;
            if (complete != null && s != null) {
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        s.onIncomingFrame(complete);
                    }
                }, "digctl-ble-rx");
                worker.setDaemon(true);
                worker.start();
            }
        }

        @Override
        public void sendFrame(String jsonLine) throws IOException {
            if (closed) throw new IOException("BLE channel closed");
            List<byte[]> chunks = ProtocolFrames.chunkForBle(
                    jsonLine, msgSeq.getAndIncrement(), ProtocolFrames.DEFAULT_BLE_MTU_PAYLOAD);
            for (byte[] c : chunks) {
                if (!writeQueue.offer(c)) {
                    throw new IOException("BLE write queue full");
                }
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            writeQueue.clear();
            ProtocolSession s = session;
            if (s != null && !s.closed) {
                s.close();
            }
            BluetoothGatt g = gatt;
            gatt = null;
            if (g != null) {
                try { g.disconnect(); } catch (Throwable ignored) {}
                try { g.close(); } catch (Throwable ignored) {}
            }
        }

        @Override
        public String getPeerAddress() {
            return device.getAddress() == null ? "BLE" : device.getAddress();
        }

        @Override
        public String getTransportLabel() {
            return "Bluetooth LE";
        }
    }

    // ---- 3. Bluetooth PAN (bnep0) Local Interface Listener ----

    private void startBnepPanAcceptLoop() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    InetAddress bnepAddr = findBnepIpv4Address();
                    String ip = bnepAddr == null ? "" : bnepAddr.getHostAddress();
                    if (bnepAddr == null) {
                        if (bnepServerSocket != null) {
                            closeServerSocketQuietly(bnepServerSocket);
                            bnepServerSocket = null;
                            boundBnepIp = "";
                        }
                        sleepQuiet(5000L);
                        continue;
                    }
                    if (!ip.equals(boundBnepIp) || bnepServerSocket == null || bnepServerSocket.isClosed()) {
                        closeServerSocketQuietly(bnepServerSocket);
                        try {
                            ServerSocket ss = new ServerSocket();
                            ss.setReuseAddress(true);
                            ss.bind(new InetSocketAddress(bnepAddr, ProtocolFrames.BT_PAN_TCP_PORT));
                            bnepServerSocket = ss;
                            boundBnepIp = ip;
                            acceptBnepClientsAsync(ss);
                        } catch (Throwable ignored) {
                        }
                    }
                    sleepQuiet(5000L);
                }
            }
        }, "digctl-bnep-watch");
        t.setDaemon(true);
        t.start();
    }

    private void acceptBnepClientsAsync(final ServerSocket ss) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && !ss.isClosed()) {
                    try {
                        final Socket client = ss.accept();
                        client.setSoTimeout(30000);
                        final String peer = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "BT-PAN";
                        final InputStream in = client.getInputStream();
                        final OutputStream out = client.getOutputStream();
                        PeerChannel ch = new PeerChannel() {
                            @Override
                            public void sendFrame(String jsonLine) throws IOException {
                                ProtocolFrames.writeLineFrame(out, jsonLine);
                            }

                            @Override
                            public void close() {
                                try { client.close(); } catch (Throwable ignored) {}
                            }

                            @Override
                            public String getPeerAddress() {
                                return peer;
                            }

                            @Override
                            public String getTransportLabel() {
                                return "BT PAN";
                            }
                        };
                        final ProtocolSession session = new ProtocolSession(ch);
                        activeSessions.add(session);
                        Thread reader = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String line;
                                    while (running && !session.closed && (line = ProtocolFrames.readLineFrame(in)) != null) {
                                        session.onIncomingFrame(line);
                                    }
                                } catch (Throwable ignored) {
                                } finally {
                                    session.close();
                                }
                            }
                        }, "digctl-bnep-client");
                        reader.setDaemon(true);
                        reader.start();
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        }, "digctl-bnep-accept");
        t.setDaemon(true);
        t.start();
    }

    private static InetAddress findBnepIpv4Address() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            for (NetworkInterface nif : Collections.list(ifs)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String n = nif.getName();
                if (n != null && (n.startsWith("bnep") || n.startsWith("bt-pan"))) {
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (addr instanceof Inet4Address) {
                            return addr;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---- 4. Live Telemetry Push Loop to Connected Authenticated Phones ----

    private void startLiveTelemetryBroadcastLoop() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    if (getAuthenticatedBtSessionCount() > 0) {
                        JSONObject veh = DaemonClient.queryVehicleJson();
                        if (veh != null) {
                            for (ProtocolSession s : activeSessions) {
                                if (s.authenticated && !s.closed) {
                                    s.pushEncryptedTelemetry(veh);
                                }
                            }
                        }
                        sleepQuiet(1500L);
                    } else {
                        sleepQuiet(2500L);
                    }
                }
            }
        }, "digctl-bt-telem");
        t.setDaemon(true);
        t.start();
    }

    // ---- 5. Unified Cryptographic Pairing & Session State Machine ----

    private final class ProtocolSession {
        final String sessionId = "sess_" + CryptoBox.randomHex(6);
        final PeerChannel channel;

        volatile boolean closed = false;
        volatile boolean authenticated = false;

        String peerPhoneId = "";
        String peerPhoneName = "Phone";
        String peerPlatform = "phone";
        String phoneStaticPubB64 = "";
        String phoneEphPubB64 = "";
        String phoneNonce = "";
        String carNonce = "";
        KeyPair carEphKeyPair;
        String sas4Code;
        volatile boolean phoneConfirmedSas = false;
        volatile boolean carApprovedSas = false;

        byte[] sessionKey64;
        long txSeq = 1L;
        long minRxSeq = 1L;

        ProtocolSession(PeerChannel channel) {
            this.channel = channel;
        }

        synchronized void onIncomingFrame(String rawJson) {
            if (closed || rawJson == null || rawJson.trim().isEmpty()) return;
            try {
                JSONObject msg = new JSONObject(rawJson);
                String type = msg.optString("type", "");
                if ("pair_hello".equals(type)) {
                    handlePairHello(msg);
                } else if ("pair_phone_confirm".equals(type)) {
                    handlePairPhoneConfirm(msg);
                } else if ("auth_hello".equals(type)) {
                    handleAuthHello(msg);
                } else if ("auth_verify".equals(type)) {
                    handleAuthVerify(msg);
                } else if ("enc".equals(type)) {
                    handleEncryptedSessionFrame(msg);
                } else if ("ping".equals(type)) {
                    JSONObject pong = new JSONObject();
                    pong.put("type", "pong");
                    channel.sendFrame(pong.toString());
                }
            } catch (Throwable t) {
                sendErrorAndClose("protocol_error", "Session error: " + t.getMessage());
            }
        }

        private void handlePairHello(JSONObject msg) throws Exception {
            if (!vault.isPinConfigured()) {
                sendErrorAndClose("pair_error", "Open Digital Control+ in your car and create a security PIN first.");
                return;
            }
            if (!isPairingWindowOpen()) {
                sendErrorAndClose("pair_error", "Unlock Pairing Mode with your PIN on the car screen first.");
                return;
            }

            peerPhoneId = msg.optString("phoneId", "").trim();
            peerPhoneName = msg.optString("phoneName", "Phone").trim();
            peerPlatform = msg.optString("platform", "phone").trim();
            phoneStaticPubB64 = msg.optString("phoneStaticPub", "").trim();
            phoneEphPubB64 = msg.optString("phoneEphPub", "").trim();
            phoneNonce = msg.optString("phoneNonce", "").trim();

            if (peerPhoneId.isEmpty() || phoneStaticPubB64.isEmpty() || phoneEphPubB64.isEmpty() || phoneNonce.isEmpty()) {
                sendErrorAndClose("pair_error", "Incomplete pairing hello");
                return;
            }
            PublicKey phoneEphPub = CryptoBox.decodePublicKeySpkiB64(phoneEphPubB64);
            CryptoBox.decodePublicKeySpkiB64(phoneStaticPubB64); // validate SPKI format

            carEphKeyPair = CryptoBox.generateEcP256KeyPair();
            String carEphPubB64 = CryptoBox.encodePublicKeySpkiB64(carEphKeyPair.getPublic());
            carNonce = CryptoBox.randomHex(16);
            String carStaticPubB64 = vault.getCarPublicKeySpkiB64();

            sas4Code = CryptoBox.deriveSas4Code(
                    carEphKeyPair.getPrivate(),
                    phoneEphPub,
                    carStaticPubB64,
                    phoneStaticPubB64,
                    carEphPubB64,
                    phoneEphPubB64,
                    carNonce,
                    phoneNonce);

            pendingPairingSession = this;
            lastActivityNote = "Confirm 4-char code [" + sas4Code + "] for " + peerPhoneName;
            notifyUiChanged();

            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            String carBtName = (ba != null && ba.getName() != null && !ba.getName().isEmpty()) ? ba.getName() : "Ioniq 5";

            JSONObject ch = new JSONObject();
            ch.put("type", "pair_challenge");
            ch.put("carId", vault.getCarId());
            ch.put("carName", carBtName);
            ch.put("carStaticPub", carStaticPubB64);
            ch.put("carEphPub", carEphPubB64);
            ch.put("carNonce", carNonce);
            ch.put("remoteSupported", EditionConfig.REMOTE_ENABLED);
            channel.sendFrame(ch.toString());
        }

        private void handlePairPhoneConfirm(JSONObject msg) throws Exception {
            if (sas4Code == null) {
                sendErrorAndClose("pair_error", "No active pairing challenge");
                return;
            }
            String sigB64 = msg.optString("sig", "");
            PublicKey phoneStaticPub = CryptoBox.decodePublicKeySpkiB64(phoneStaticPubB64);
            String expectedCanonical = "GEN5W-PAIR-CONFIRM|" + sas4Code + "|" + carNonce + "|" + phoneNonce;
            if (!CryptoBox.verifyEcdsaSha256B64(phoneStaticPub, expectedCanonical, sigB64)) {
                sendErrorAndClose("pair_error", "Phone pairing signature invalid");
                return;
            }
            phoneConfirmedSas = true;
            notifyUiChanged();
            maybeFinalizePairingLocked();
        }

        synchronized void approveOnCarScreen() {
            carApprovedSas = true;
            try {
                maybeFinalizePairingLocked();
            } catch (Throwable t) {
                sendErrorAndClose("pair_error", "Failed finalizing pairing: " + t.getMessage());
            }
        }

        private void maybeFinalizePairingLocked() throws Exception {
            if (!phoneConfirmedSas || !carApprovedSas || closed) return;

            TrustVault.TrustedPhone tp = vault.addOrUpdateTrustedPhone(
                    peerPhoneId,
                    peerPhoneName,
                    peerPlatform,
                    channel.getPeerAddress(),
                    phoneStaticPubB64);

            PublicKey phoneEphPub = CryptoBox.decodePublicKeySpkiB64(phoneEphPubB64);
            byte[] z = CryptoBox.ecdhSharedSecret32(carEphKeyPair.getPrivate(), phoneEphPub);
            byte[] salt = CryptoBox.sha256((carNonce + "|" + phoneNonce).getBytes(CryptoBox.UTF8));
            sessionKey64 = CryptoBox.hkdfSha256(z, salt, "GEN5W-BT-SESS-V1".getBytes(CryptoBox.UTF8), 64);
            authenticated = true;
            pendingPairingSession = null;
            pairingWindowUntilMs = 0L;

            JSONObject done = new JSONObject();
            done.put("type", "pair_complete");
            done.put("ok", true);
            done.put("carId", vault.getCarId());
            done.put("carStaticPub", vault.getCarPublicKeySpkiB64());
            done.put("pairedAtMs", tp.pairedAtMs);
            done.put("pairingGrantSig", tp.pairingGrantSigB64);
            done.put("remoteUrl", EditionConfig.REMOTE_ENABLED ? vault.getRemoteServerUrl() : "");
            channel.sendFrame(done.toString());

            lastActivityNote = "Paired & connected: " + peerPhoneName;
            if (remoteBridge != null) {
                remoteBridge.notifyTrustUpdated();
            }
            notifyUiChanged();

            JSONObject snap = DaemonClient.queryVehicleJson();
            if (snap != null) {
                pushEncryptedTelemetry(snap);
            }
        }

        private void handleAuthHello(JSONObject msg) throws Exception {
            if (!vault.isPinConfigured()) {
                sendErrorAndClose("auth_error", "Car PIN was reset. Please re-pair in car.");
                return;
            }
            peerPhoneId = msg.optString("phoneId", "").trim();
            phoneEphPubB64 = msg.optString("phoneEphPub", "").trim();
            phoneNonce = msg.optString("phoneNonce", "").trim();

            TrustVault.TrustedPhone tp = vault.getTrustedPhone(peerPhoneId);
            if (tp == null) {
                JSONObject untrusted = new JSONObject();
                untrusted.put("type", "auth_error");
                untrusted.put("code", "UNTRUSTED");
                untrusted.put("reason", "Phone is not trusted by this car (or app was reinstalled). Please pair again.");
                channel.sendFrame(untrusted.toString());
                close();
                return;
            }
            peerPhoneName = tp.phoneName;
            peerPlatform = tp.platform;

            PublicKey phoneEphPub = CryptoBox.decodePublicKeySpkiB64(phoneEphPubB64);
            carEphKeyPair = CryptoBox.generateEcP256KeyPair();
            String carEphPubB64 = CryptoBox.encodePublicKeySpkiB64(carEphKeyPair.getPublic());
            carNonce = CryptoBox.randomHex(16);

            byte[] z = CryptoBox.ecdhSharedSecret32(carEphKeyPair.getPrivate(), phoneEphPub);
            byte[] salt = CryptoBox.sha256((carNonce + "|" + phoneNonce).getBytes(CryptoBox.UTF8));
            sessionKey64 = CryptoBox.hkdfSha256(z, salt, "GEN5W-BT-SESS-V1".getBytes(CryptoBox.UTF8), 64);

            PrivateKey carPriv = vault.getCarPrivateKey();
            String carAuthCanonical = "GEN5W-AUTH-CAR|" + peerPhoneId + "|" + phoneNonce + "|" + carNonce + "|" + carEphPubB64;
            String carSigB64 = CryptoBox.signEcdsaSha256B64(carPriv, carAuthCanonical);

            JSONObject ch = new JSONObject();
            ch.put("type", "auth_challenge");
            ch.put("carId", vault.getCarId());
            ch.put("carEphPub", carEphPubB64);
            ch.put("carNonce", carNonce);
            ch.put("carSig", carSigB64);
            channel.sendFrame(ch.toString());
        }

        private void handleAuthVerify(JSONObject msg) throws Exception {
            if (sessionKey64 == null || peerPhoneId.isEmpty()) {
                sendErrorAndClose("auth_error", "Missing auth challenge");
                return;
            }
            PublicKey phonePub = vault.getTrustedPhonePublicKey(peerPhoneId);
            if (phonePub == null) {
                sendErrorAndClose("auth_error", "Phone revoked");
                return;
            }
            String phoneSig = msg.optString("phoneSig", "");
            String expected = "GEN5W-AUTH-PHONE|" + vault.getCarId() + "|" + carNonce + "|" + phoneNonce + "|" + phoneEphPubB64;
            if (!CryptoBox.verifyEcdsaSha256B64(phonePub, expected, phoneSig)) {
                sendErrorAndClose("auth_error", "Phone signature verification failed");
                return;
            }
            authenticated = true;
            vault.touchPhoneSeen(peerPhoneId);
            lastActivityNote = "Connected: " + peerPhoneName + " (" + channel.getTransportLabel() + ")";
            notifyUiChanged();

            JSONObject ok = new JSONObject();
            ok.put("type", "auth_ok");
            ok.put("remoteUrl", vault.getRemoteServerUrl());
            channel.sendFrame(ok.toString());

            JSONObject snap = DaemonClient.queryVehicleJson();
            if (snap != null) {
                pushEncryptedTelemetry(snap);
            }
        }

        private void handleEncryptedSessionFrame(JSONObject wrapper) throws Exception {
            if (!authenticated || sessionKey64 == null) {
                sendErrorAndClose("auth_error", "Unauthenticated encrypted frame");
                return;
            }
            String packed = wrapper.optString("frame", "");
            int dot = packed.indexOf('.');
            long seq = Long.parseLong(packed.substring(0, dot));
            String plain = CryptoBox.decryptSessionFrame(sessionKey64, minRxSeq, packed);
            minRxSeq = seq + 1L;

            JSONObject inner = new JSONObject(plain);
            String op = inner.optString("op", "");
            String reqId = inner.optString("reqId", "");

            if ("get_telemetry".equals(op)) {
                JSONObject snap = DaemonClient.queryVehicleJson();
                if (snap != null) pushEncryptedTelemetry(snap);
                return;
            }

            if ("configure_remote_server".equals(op)) {
                String newUrl = inner.optString("remoteUrl", "").trim();
                String newTok = inner.optString("remoteAccountToken", "").trim();
                boolean okCfg = newUrl.isEmpty() || newUrl.startsWith("https://");
                String msgCfg;
                if (okCfg) {
                    vault.setRemoteServerUrl(newUrl);
                    vault.setRemoteAccountToken(newTok);
                    notifyTrustVaultChanged();
                    msgCfg = newUrl.isEmpty()
                            ? "Cleared remote server on car (Bluetooth-only mode)"
                            : ("Synced remote server to car: " + newUrl.replace("https://", ""));
                    lastActivityNote = peerPhoneName + ": " + msgCfg;
                    notifyUiChanged();
                } else {
                    msgCfg = "HTTPS URL required for remote server";
                }
                JSONObject reply = new JSONObject();
                reply.put("op", "cmd_result");
                reply.put("reqId", reqId);
                reply.put("ok", okCfg);
                reply.put("message", msgCfg);
                sendEncryptedInner(reply);
                return;
            }

            if ("cmd".equals(op)) {
                String action = inner.optString("action", "").trim();
                String arg = inner.optString("arg", "");
                JSONObject themeObj = inner.optJSONObject("climateTheme");
                if ("apply_climate_theme".equals(action) && themeObj != null) {
                    arg = themeObj.toString();
                }
                DaemonClient.CmdResult res = DaemonClient.sendVehicleCommand(action, arg, "bt");
                vault.touchPhoneSeen(peerPhoneId);
                lastActivityNote = peerPhoneName + ": " + res.message;
                notifyUiChanged();

                JSONObject reply = new JSONObject();
                reply.put("op", "cmd_result");
                reply.put("reqId", reqId);
                reply.put("ok", res.ok);
                reply.put("message", res.message);
                if (res.values != null) {
                    reply.put("values", res.values);
                }
                sendEncryptedInner(reply);
            }
        }

        synchronized void pushEncryptedTelemetry(JSONObject snap) {
            if (!authenticated || closed || sessionKey64 == null) return;
            try {
                JSONObject payload = new JSONObject();
                payload.put("op", "telemetry");
                payload.put("values", snap.optJSONObject("values") != null ? snap.optJSONObject("values") : new JSONObject());
                payload.put("updatedMs", snap.optLong("updatedMs", System.currentTimeMillis()));
                sendEncryptedInner(payload);
            } catch (Throwable ignored) {
                close();
            }
        }

        private synchronized void sendEncryptedInner(JSONObject inner) throws Exception {
            long s = txSeq++;
            String frame = CryptoBox.encryptSessionFrame(sessionKey64, s, inner.toString());
            JSONObject env = new JSONObject();
            env.put("type", "enc");
            env.put("frame", frame);
            channel.sendFrame(env.toString());
        }

        void sendErrorAndClose(String type, String reason) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", type);
                err.put("reason", reason);
                channel.sendFrame(err.toString());
            } catch (Throwable ignored) {
            }
            close();
        }

        void close() {
            if (closed) return;
            closed = true;
            activeSessions.remove(this);
            if (pendingPairingSession == this) {
                pendingPairingSession = null;
            }
            channel.close();
            notifyUiChanged();
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void closeQuietly(BluetoothServerSocket ss) {
        if (ss != null) {
            try { ss.close(); } catch (Throwable ignored) {}
        }
    }

    private static void closeBluetoothSocketQuietly(BluetoothSocket s) {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static void closeServerSocketQuietly(ServerSocket ss) {
        if (ss != null) {
            try { ss.close(); } catch (Throwable ignored) {}
        }
    }
}
