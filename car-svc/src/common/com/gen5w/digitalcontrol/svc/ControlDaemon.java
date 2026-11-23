package com.gen5w.digitalcontrol.svc;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;

import com.gen5w.digitalcontrol.shared.ProtocolFrames;

import hmgapi.client.HmgApi;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Root Dalvik service started by hmgapid (user=root, start=android, restart=true).
 * Responsibilities:
 *  1. Exposes UID-verified LocalServerSocket IPC to the companion in-car APK (CarLinkService & MainActivity).
 *  2. Polls Gen5W direct Binder + HMG-API vehicle readings on demand and while active.
 *  3. Executes vehicle controls and multi-step Climate Theme macros safely with timeout isolation.
 *  4. Keeps CarLinkService running in the background while the head unit is on (e.g., while charging in driveway).
 *  5. Tracks APK firstInstallTime and purges any service-side state if the app was removed and re-installed.
 */
public final class ControlDaemon {
    private static final long SERVICE_WATCHDOG_MS = 8_000L;
    private static final long ACTIVE_POLL_MS = 650L;
    private static final long IDLE_POLL_MS = 3_500L;
    private static final long DEMAND_WINDOW_MS = 12_000L;
    private static final long CAR_CMD_TIMEOUT_MS = 4_500L;

    private final File dataDir;
    private final File modDir;
    private final PmHelper pmHelper = new PmHelper();
    private final VehicleQueryClient hmgClient = new VehicleQueryClient();
    private final DirectCarServiceBridge directCar = new DirectCarServiceBridge();

    private final Object pollWakeLock = new Object();
    private final Object snapLock = new Object();
    private final Map<String, String> hmgValues = new LinkedHashMap<String, String>();
    private final Map<String, String> directValues = new LinkedHashMap<String, String>();
    private final Map<String, String> mergedValues = new LinkedHashMap<String, String>();
    private volatile JSONObject cachedVehicleJson;
    private volatile long lastDemandElapsedMs = 0L;
    private volatile boolean running = true;
    private volatile LocalServerSocket serverSocket;

    private final ExecutorService ipcPool = new ThreadPoolExecutor(
            2, 6, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(32),
            new NamedDaemonFactory("digctl-ipc-"));

    private final ExecutorService cmdPool = new ThreadPoolExecutor(
            1, 4, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new NamedDaemonFactory("digctl-cmd-"));

    private static final Object LIBCORE_OS;
    private static final Method LIBCORE_OS_CLOSE;
    static {
        Object osObj = null;
        Method closeMethod = null;
        try {
            Class<?> libcoreCls = Class.forName("libcore.io.Libcore");
            osObj = libcoreCls.getField("os").get(null);
            if (osObj != null) {
                closeMethod = osObj.getClass().getMethod("close", FileDescriptor.class);
            }
        } catch (Throwable ignored) {
        }
        LIBCORE_OS = osObj;
        LIBCORE_OS_CLOSE = closeMethod;
    }

    public static void main(String[] args) {
        String dataPath = System.getenv("HMGAPI_DATA");
        if (dataPath == null || dataPath.trim().isEmpty()) {
            dataPath = "/data/hmgapi/data/" + SvcEditionConfig.MOD_ID;
        }
        String modDirPath = System.getenv("HMGAPI_MOD_DIR");
        File modDir = modDirPath == null ? null : new File(modDirPath);
        ControlDaemon daemon = new ControlDaemon(new File(dataPath), modDir);
        daemon.run();
    }

    private ControlDaemon(File dataDir, File modDir) {
        this.dataDir = dataDir;
        this.modDir = modDir;
        DiagLogger.init(dataDir);
    }

    private void run() {
        DiagLogger.logEvent("DAEMON-START", "mod=" + SvcEditionConfig.MOD_ID
                + " pkg=" + SvcEditionConfig.APP_PACKAGE
                + " remoteEdition=" + SvcEditionConfig.REMOTE_EDITION);

        Runtime.getRuntime().addShutdownHook(new Thread("digctl-shutdown") {
            @Override
            public void run() {
                running = false;
                closeServerQuietly();
            }
        });

        startIpcServer();
        syncModApkIfNeededAsync();
        startTelemetryThreads();

        try {
            while (running) {
                verifyInstallAnchorAndStartLinkService();
                Thread.sleep(SERVICE_WATCHDOG_MS);
            }
        } catch (InterruptedException ignored) {
        } finally {
            closeServerQuietly();
        }
    }

    private void verifyInstallAnchorAndStartLinkService() {
        if (isModDisabled()) return;
        if (!"1".equals(pmHelper.getSystemProp("sys.boot_completed", "0"))) return;

        long currentFirstInstall = pmHelper.getPackageFirstInstallTime(SvcEditionConfig.APP_PACKAGE);
        if (currentFirstInstall <= 0L) {
            return;
        }
        File stampFile = new File(dataDir, "apk_install_time.stamp");
        long recorded = readStampLong(stampFile);
        if (recorded > 0L && recorded != currentFirstInstall) {
            // App was uninstalled and re-installed! Purge any residual daemon logs/state.
            DiagLogger.purgeLogs();
            DiagLogger.logEvent("REINSTALL-DETECTED", "APK firstInstallTime changed ("
                    + recorded + " -> " + currentFirstInstall + "); service state reset.");
        }
        if (recorded != currentFirstInstall) {
            writeStampLong(stampFile, currentFirstInstall);
        }
        pmHelper.ensureLinkServiceStarted(SvcEditionConfig.APP_PACKAGE);
    }

    private void syncModApkIfNeededAsync() {
        if (modDir == null) return;
        final File apk = new File(modDir, SvcEditionConfig.APK_REL_PATH);
        if (!apk.isFile()) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int installed = pmHelper.getPackageVersionCode(SvcEditionConfig.APP_PACKAGE);
                    if (installed >= 0 && installed < SvcEditionConfig.EXPECTED_APP_VERSION_CODE) {
                        DiagLogger.logEvent("APK-SYNC", "Updating APK " + installed
                                + " -> " + SvcEditionConfig.EXPECTED_APP_VERSION_CODE);
                        pmHelper.installApkReplace(apk);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "digctl-apksync");
        t.setDaemon(true);
        t.start();
    }

    private void startTelemetryThreads() {
        Thread hmgThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        VehicleQueryClient.BatchResult res = hmgClient.query(Arrays.asList(VehicleQueryClient.CORE_KEYS));
                        if (res.anySuccess && !res.values.isEmpty()) {
                            synchronized (snapLock) {
                                hmgValues.putAll(res.values);
                                rebuildCachedJsonLocked();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    long now = SystemClock.elapsedRealtime();
                    long sleep = (now - lastDemandElapsedMs) <= DEMAND_WINDOW_MS ? 1500L : 5000L;
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }, "digctl-hmg-poll");
        hmgThread.setDaemon(true);
        hmgThread.start();

        Thread binderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    long start = SystemClock.elapsedRealtime();
                    try {
                        Map<String, String> fresh = new LinkedHashMap<String, String>();
                        directCar.pollAll(fresh);
                        synchronized (snapLock) {
                            if (!fresh.isEmpty()) {
                                if (!fresh.containsKey("conditioning.state")) {
                                    directValues.remove("conditioning.state");
                                }
                                directValues.putAll(fresh);
                            }
                            rebuildCachedJsonLocked();
                        }
                    } catch (Throwable t) {
                        DiagLogger.logEvent("POLL-ERR", String.valueOf(t));
                    }
                    long now = SystemClock.elapsedRealtime();
                    long targetInterval = (now - lastDemandElapsedMs) <= DEMAND_WINDOW_MS ? ACTIVE_POLL_MS : IDLE_POLL_MS;
                    long wait = Math.max(100L, targetInterval - (now - start));
                    synchronized (pollWakeLock) {
                        if (!running) break;
                        try {
                            pollWakeLock.wait(wait);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        }, "digctl-binder-poll");
        binderThread.setDaemon(true);
        binderThread.start();
    }

    private void rebuildCachedJsonLocked() {
        mergedValues.clear();
        for (Map.Entry<String, String> e : hmgValues.entrySet()) {
            if (isNonEmptyReading(e.getValue())) {
                mergedValues.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : directValues.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (!isNonEmptyReading(v)) continue;
            if (("hv.soc".equals(k) || "odometer".equals(k)) && isNonEmptyReading(mergedValues.get(k))) {
                continue;
            }
            mergedValues.put(k, v);
        }
        try {
            JSONObject vals = new JSONObject();
            for (Map.Entry<String, String> e : mergedValues.entrySet()) {
                vals.put(e.getKey(), e.getValue());
            }
            JSONObject root = new JSONObject();
            root.put("ok", vals.length() > 0);
            root.put("values", vals);
            root.put("updatedMs", System.currentTimeMillis());
            cachedVehicleJson = root;
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNonEmptyReading(String v) {
        return v != null && !v.isEmpty() && !"-".equals(v);
    }

    private void startIpcServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new LocalServerSocket(SvcEditionConfig.IPC_SOCKET_NAME);
                    DiagLogger.logEvent("IPC-READY", "@" + SvcEditionConfig.IPC_SOCKET_NAME);
                } catch (IOException e) {
                    DiagLogger.logEvent("IPC-BIND-FAIL", String.valueOf(e));
                    return;
                }
                while (running) {
                    LocalSocket client = null;
                    try {
                        client = serverSocket.accept();
                        final LocalSocket accepted = client;
                        client = null;
                        ipcPool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(accepted);
                            }
                        });
                    } catch (Throwable t) {
                        closeClientSocketQuietly(client);
                        if (!running) break;
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        }, "digctl-ipc-accept");
        t.setDaemon(true);
        t.start();
    }

    private void handleClient(LocalSocket client) {
        try {
            Credentials creds = client.getPeerCredentials();
            int peerUid = creds == null ? -1 : creds.getUid();
            int allowedUid = pmHelper.getPackageUid(SvcEditionConfig.APP_PACKAGE);
            if (peerUid != 0 && (allowedUid < 0 || peerUid != allowedUid)) {
                DiagLogger.logEvent("IPC-DENIED", "uid=" + peerUid + " expected=" + allowedUid);
                return;
            }
            client.setSoTimeout(5500);
            String reqLine = ProtocolFrames.readLineFrame(client.getInputStream());
            JSONObject resp = dispatchIpcRequest(reqLine);
            ProtocolFrames.writeLineFrame(client.getOutputStream(), resp.toString());
        } catch (Throwable t) {
            DiagLogger.logEvent("IPC-ERR", String.valueOf(t));
        } finally {
            closeClientSocketQuietly(client);
        }
    }

    private JSONObject dispatchIpcRequest(String rawLine) throws Exception {
        if (rawLine == null || rawLine.trim().isEmpty()) {
            JSONObject err = new JSONObject();
            err.put("ok", false);
            err.put("message", "Empty IPC request");
            return err;
        }
        JSONObject req = new JSONObject(rawLine);
        String op = req.optString("op", "vehicle");
        if ("vehicle".equals(op)) {
            synchronized (pollWakeLock) {
                lastDemandElapsedMs = SystemClock.elapsedRealtime();
                pollWakeLock.notifyAll();
            }
            JSONObject snap = cachedVehicleJson;
            if (snap != null) return snap;
            JSONObject empty = new JSONObject();
            empty.put("ok", false);
            empty.put("values", new JSONObject());
            empty.put("updatedMs", 0L);
            return empty;
        }
        if ("info".equals(op)) {
            JSONObject info = new JSONObject();
            info.put("ok", true);
            info.put("edition", SvcEditionConfig.REMOTE_EDITION ? "private" : "public");
            info.put("btAllowed", isBluetoothControlAllowed());
            info.put("hardwareHint", buildHardwareHint());
            return info;
        }
        if ("cmd".equals(op)) {
            final String action = req.optString("action", "").trim();
            final String arg = req.optString("arg", "");
            final String source = req.optString("source", "phone");
            if ("bt".equals(source) && !isBluetoothControlAllowed()) {
                JSONObject denied = new JSONObject();
                denied.put("ok", false);
                denied.put("message", "Refused: Bluetooth control is turned OFF in HMG-API settings");
                return denied;
            }
            return executeVehicleCommandBounded(action, arg, source);
        }
        JSONObject unk = new JSONObject();
        unk.put("ok", false);
        unk.put("message", "Unknown IPC op: " + op);
        return unk;
    }

    private JSONObject executeVehicleCommandBounded(final String action, final String arg, final String source) throws Exception {
        final Map<String, String> snap;
        synchronized (snapLock) {
            snap = new LinkedHashMap<String, String>(mergedValues);
        }
        Future<DirectCarServiceBridge.CommandReply> fut = cmdPool.submit(new Callable<DirectCarServiceBridge.CommandReply>() {
            @Override
            public DirectCarServiceBridge.CommandReply call() {
                return directCar.executeCommand(action, arg, snap);
            }
        });
        DirectCarServiceBridge.CommandReply reply;
        try {
            reply = fut.get(CAR_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            fut.cancel(true);
            reply = new DirectCarServiceBridge.CommandReply(false, "Timed out executing " + action);
        }
        synchronized (snapLock) {
            for (String immKey : new String[] { "hv.utility_mode", "hv.utility_warn", "hv.ready", "conditioning.state" }) {
                String immVal = snap.get(immKey);
                if (isNonEmptyReading(immVal)) {
                    directValues.put(immKey, immVal);
                }
            }
            rebuildCachedJsonLocked();
        }
        synchronized (pollWakeLock) {
            lastDemandElapsedMs = SystemClock.elapsedRealtime();
            pollWakeLock.notifyAll();
        }
        if (reply.ok && shouldShowCommandToast() && !"ui_silent".equals(source)) {
            pmHelper.showToast("Digital Control+: " + reply.message);
        }
        JSONObject out = new JSONObject();
        out.put("ok", reply.ok);
        out.put("message", reply.message);
        JSONObject cached = cachedVehicleJson;
        if (cached != null && cached.optJSONObject("values") != null) {
            out.put("values", cached.optJSONObject("values"));
        }
        return out;
    }

    private String buildHardwareHint() {
        String serial = pmHelper.getSystemProp("ro.boot.serialno", pmHelper.getSystemProp("ro.serialno", ""));
        String btAddr = pmHelper.getSystemProp("ro.boot.btaddr", pmHelper.getSystemProp("ro.btaddr", ""));
        String model = pmHelper.getSystemProp("ro.build.product2", "");
        return serial + "|" + btAddr + "|" + model;
    }

    private boolean isModDisabled() {
        return modDir != null && new File(modDir, ".disabled").exists();
    }

    private boolean isBluetoothControlAllowed() {
        try {
            JSONObject s = HmgApi.connect().timeout(500).settings();
            return s == null || s.optBoolean("allow_bluetooth_control", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean shouldShowCommandToast() {
        try {
            JSONObject s = HmgApi.connect().timeout(500).settings();
            return s == null || s.optBoolean("show_command_toasts", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static long readStampLong(File f) {
        if (f == null || !f.isFile()) return -1L;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] buf = new byte[64];
            int n = fis.read(buf);
            if (n <= 0) return -1L;
            return Long.parseLong(new String(buf, 0, n, "UTF-8").trim());
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void writeStampLong(File f, long val) {
        if (f == null) return;
        FileOutputStream fos = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            fos = new FileOutputStream(f, false);
            fos.write((val + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void closeClientSocketQuietly(LocalSocket s) {
        if (s == null) return;
        try {
            FileDescriptor fd = s.getFileDescriptor();
            if (fd != null && fd.valid() && LIBCORE_OS != null && LIBCORE_OS_CLOSE != null) {
                LIBCORE_OS_CLOSE.invoke(LIBCORE_OS, fd);
            }
        } catch (Throwable ignored) {
        }
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    private void closeServerQuietly() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class NamedDaemonFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        NamedDaemonFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
