package com.gen5w.digitalcontrol.shared;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * User-friendly phone command lifecycle log item tracking commands across Bluetooth and Remote Wi-Fi:
 *   SENT -> EXECUTED | TIMED_OUT | CANCELED | FAILED
 */
public final class CommandLogEntry {
    public static final String STATE_SENT = "SENT";
    public static final String STATE_EXECUTED = "EXECUTED";
    public static final String STATE_TIMED_OUT = "TIMED_OUT";
    public static final String STATE_CANCELED = "CANCELED";
    public static final String STATE_FAILED = "FAILED";

    public final String id;          // commandId or reqId
    public final String label;       // e.g. "Lock Doors", "Warm Cozy 78°F"
    public final String transport;   // "BT" or "REMOTE"
    public final String state;       // SENT, EXECUTED, TIMED_OUT, CANCELED, FAILED
    public final long sentAtMs;
    public final long updatedAtMs;
    public final long expiresAtMs;   // >0 for REMOTE queued items
    public final String detail;      // optional car confirmation / error detail

    public CommandLogEntry(
            String id,
            String label,
            String transport,
            String state,
            long sentAtMs,
            long updatedAtMs,
            long expiresAtMs,
            String detail) {
        this.id = id == null ? "" : id;
        this.label = (label == null || label.isEmpty()) ? "Command" : label;
        this.transport = (transport == null || transport.isEmpty()) ? "BT" : transport;
        this.state = (state == null || state.isEmpty()) ? STATE_SENT : state;
        this.sentAtMs = sentAtMs;
        this.updatedAtMs = updatedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.detail = detail == null ? "" : detail;
    }

    public CommandLogEntry withState(String newState, long nowMs, String newDetail) {
        return new CommandLogEntry(
                id,
                label,
                transport,
                newState,
                sentAtMs,
                nowMs,
                expiresAtMs,
                newDetail == null ? detail : newDetail);
    }

    public boolean isPendingRemote(long nowMs) {
        return "REMOTE".equals(transport) && STATE_SENT.equals(state) && (expiresAtMs <= 0L || nowMs < expiresAtMs);
    }

    /**
     * Builds the primary user-friendly headline:
     *   "<command> sent." / "<command> executed." / "<command> timed out." / "<command> canceled." / "<command> failed."
     */
    public String formatFriendlyHeadline() {
        if (STATE_EXECUTED.equals(state)) {
            return label + " executed.";
        }
        if (STATE_TIMED_OUT.equals(state)) {
            return label + " timed out.";
        }
        if (STATE_CANCELED.equals(state)) {
            return label + " canceled.";
        }
        if (STATE_FAILED.equals(state)) {
            return label + " failed.";
        }
        return label + " sent.";
    }

    public String formatSubline(long nowMs) {
        SimpleDateFormat tf = new SimpleDateFormat("h:mm:ss a", Locale.US);
        String via = "REMOTE".equals(transport) ? "Wi-Fi Server" : "Bluetooth";
        long stamp = STATE_SENT.equals(state) ? sentAtMs : (updatedAtMs > 0L ? updatedAtMs : sentAtMs);
        String timeStr = stamp > 0L ? tf.format(new Date(stamp)) : "";

        if (isPendingRemote(nowMs) && expiresAtMs > nowMs) {
            long remSec = Math.max(0L, (expiresAtMs - nowMs) / 1000L);
            return String.format(Locale.US, "%s \u00B7 %s \u00B7 Waiting (%d:%02d left)",
                    timeStr, via, remSec / 60L, remSec % 60L);
        }
        String base = timeStr + " \u00B7 " + via;
        if (!detail.isEmpty() && !STATE_SENT.equals(state) && !detail.equalsIgnoreCase(label)) {
            return base + " \u00B7 " + detail;
        }
        return base;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("id", id);
        j.put("label", label);
        j.put("transport", transport);
        j.put("state", state);
        j.put("sentAtMs", sentAtMs);
        j.put("updatedAtMs", updatedAtMs);
        j.put("expiresAtMs", expiresAtMs);
        j.put("detail", detail);
        return j;
    }

    public static CommandLogEntry fromJson(JSONObject j) {
        if (j == null) return null;
        return new CommandLogEntry(
                j.optString("id", ""),
                j.optString("label", "Command"),
                j.optString("transport", "BT"),
                j.optString("state", STATE_SENT),
                j.optLong("sentAtMs", 0L),
                j.optLong("updatedAtMs", 0L),
                j.optLong("expiresAtMs", 0L),
                j.optString("detail", ""));
    }

    public static String serializeList(List<CommandLogEntry> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (CommandLogEntry e : list) {
                if (e == null) continue;
                try {
                    arr.put(e.toJson());
                } catch (JSONException ignored) {
                }
            }
        }
        return arr.toString();
    }

    public static List<CommandLogEntry> parseList(String rawJson) {
        List<CommandLogEntry> out = new ArrayList<CommandLogEntry>();
        if (rawJson == null || rawJson.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(rawJson);
            for (int i = 0; i < arr.length(); i++) {
                CommandLogEntry e = fromJson(arr.optJSONObject(i));
                if (e != null && !e.id.isEmpty()) {
                    out.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
