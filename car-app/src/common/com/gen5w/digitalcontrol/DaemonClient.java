package com.gen5w.digitalcontrol;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.gen5w.digitalcontrol.edition.EditionConfig;
import com.gen5w.digitalcontrol.shared.ProtocolFrames;

import org.json.JSONObject;

/**
 * LocalSocket client communicating with the root ControlDaemon service.
 */
public final class DaemonClient {
    private DaemonClient() {}

    public static final class CmdResult {
        public final boolean ok;
        public final String message;
        public final JSONObject values;

        public CmdResult(boolean ok, String message, JSONObject values) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.values = values;
        }
    }

    public static JSONObject queryVehicleJson() {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "vehicle");
            return call(req, 2000);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static JSONObject queryDaemonInfo() {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "info");
            return call(req, 1500);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static CmdResult sendVehicleCommand(String action, String arg, String source) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "cmd");
            req.put("action", action == null ? "" : action);
            req.put("arg", arg == null ? "" : arg);
            req.put("source", source == null ? "phone" : source);
            JSONObject resp = call(req, 5200);
            if (resp == null) {
                return new CmdResult(false, "ControlDaemon IPC not reachable", null);
            }
            return new CmdResult(
                    resp.optBoolean("ok", false),
                    resp.optString("message", ""),
                    resp.optJSONObject("values"));
        } catch (Throwable t) {
            return new CmdResult(false, "IPC error: " + t.getMessage(), null);
        }
    }

    private static JSONObject call(JSONObject req, int timeoutMs) throws Exception {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(
                    EditionConfig.IPC_SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(timeoutMs);
            ProtocolFrames.writeLineFrame(sock.getOutputStream(), req.toString());
            String line = ProtocolFrames.readLineFrame(sock.getInputStream());
            return line == null ? null : new JSONObject(line);
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
