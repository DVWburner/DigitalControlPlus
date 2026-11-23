package com.gen5w.digitalcontrol.svc;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bounded diagnostic event logger in DIGITALCTL_DATA (/data/nemesis/digital-control, rotates automatically at 256 KB).
 */
final class DiagLogger {
    private static final long MAX_LOG_BYTES = 256L * 1024L;
    private static volatile File logFile;
    private static final Object LOCK = new Object();

    private DiagLogger() {}

    static void init(File dataDir) {
        if (dataDir == null) return;
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        logFile = new File(dataDir, "digital-control.log");
    }

    static void purgeLogs() {
        synchronized (LOCK) {
            if (logFile == null) return;
            File bak = new File(logFile.getAbsolutePath() + ".1");
            if (bak.exists()) bak.delete();
            if (logFile.exists()) logFile.delete();
        }
    }

    static void logEvent(String tag, String detail) {
        appendLine("[" + tag + "] " + (detail == null ? "" : detail));
    }

    static void logCarAction(String stage, String detail) {
        appendLine("[CAR-ACT " + stage + "] " + (detail == null ? "" : detail));
    }

    private static void appendLine(String msg) {
        File f = logFile;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String full = ts + " " + msg;
        System.out.println(full);
        if (f == null) return;
        synchronized (LOCK) {
            try {
                if (f.exists() && f.length() > MAX_LOG_BYTES) {
                    File prev = new File(f.getAbsolutePath() + ".1");
                    if (prev.exists()) prev.delete();
                    f.renameTo(prev);
                }
                FileOutputStream fos = new FileOutputStream(f, true);
                try {
                    fos.write((full + "\n").getBytes("UTF-8"));
                } finally {
                    fos.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
