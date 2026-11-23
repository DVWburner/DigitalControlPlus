package com.gen5w.digitalcontrol.svc;

import android.content.pm.PackageInfo;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * KitKat IPackageManager reflection + root process command helper for Gen5W.
 */
final class PmHelper {
    private static final int USER_OWNER = 0;

    private Object pm;
    private Method mGetPackageInfo;
    private Method mGetPackageUid;

    static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private synchronized Object ensurePm() throws Exception {
        if (pm != null) return pm;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "package");
        if (binder == null) {
            throw new IllegalStateException("PackageManager binder unavailable");
        }
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        pm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
        mGetPackageInfo = ipm.getMethod("getPackageInfo", String.class, int.class, int.class);
        mGetPackageUid = ipm.getMethod("getPackageUid", String.class, int.class);
        return pm;
    }

    PackageInfo getPackageInfo(String pkg) {
        try {
            Object s = ensurePm();
            Object obj = mGetPackageInfo.invoke(s, pkg, 0, USER_OWNER);
            return obj instanceof PackageInfo ? (PackageInfo) obj : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    int getPackageUid(String pkg) {
        try {
            Object s = ensurePm();
            Object uid = mGetPackageUid.invoke(s, pkg, USER_OWNER);
            return uid instanceof Integer ? ((Integer) uid).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    int getPackageVersionCode(String pkg) {
        PackageInfo pi = getPackageInfo(pkg);
        return pi == null ? -1 : pi.versionCode;
    }

    long getPackageFirstInstallTime(String pkg) {
        PackageInfo pi = getPackageInfo(pkg);
        return pi == null ? -1L : pi.firstInstallTime;
    }

    boolean installApkReplace(File apkFile) {
        if (apkFile == null || !apkFile.isFile()) return false;
        CommandResult res = runCommandCapture(new String[] {
                "/system/bin/pm", "install", "-r", apkFile.getAbsolutePath()
        }, 30000L);
        return res.exitCode == 0 && (res.output.contains("Success") || !res.output.contains("Failure"));
    }

    void ensureLinkServiceStarted(String appPackage) {
        runCommandCapture(new String[] {
                "/system/bin/am", "startservice",
                "-n", appPackage + "/com.gen5w.digitalcontrol.CarLinkService"
        }, 2500L);
    }

    void showToast(String message) {
        if (message == null || message.isEmpty()) return;
        runCommandCapture(new String[] {
                "/system/bin/am", "broadcast",
                "-a", "com.hkmc.intent.action.TOAST",
                "-e", "com.hkmc.intent.extra.TOAST_MESSAGE", message,
                "--ez", "com.hkmc.intent.extra.TOAST_POPUPSHOWING", "true"
        }, 2000L);
    }

    String getSystemProp(String key, String defVal) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, defVal);
            return v instanceof String ? (String) v : defVal;
        } catch (Throwable ignored) {
            return defVal;
        }
    }

    static CommandResult runCommandCapture(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final InputStream is = p.getInputStream();
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (out.length() < 4096) {
                                if (out.length() > 0) out.append('\n');
                                out.append(line);
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        closeQuietly(br);
                    }
                }
            }, "pm-cmd-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMs);
            Integer exit = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exit = Integer.valueOf(p.exitValue());
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(40L);
                }
            }
            if (exit == null) {
                p.destroy();
                return new CommandResult(-1, "timeout");
            }
            reader.join(300L);
            return new CommandResult(exit.intValue(), out.toString().trim());
        } catch (Throwable t) {
            return new CommandResult(-1, String.valueOf(t.getMessage()));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
