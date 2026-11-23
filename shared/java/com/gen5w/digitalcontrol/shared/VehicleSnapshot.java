package com.gen5w.digitalcontrol.shared;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable typed snapshot of live vehicle telemetry (EV charge status, SoC, range, 12V,
 * cabin HVAC, seat heating/cooling, steering wheel heat, doors, locks, trunk/hatch, and windows).
 */
public final class VehicleSnapshot {
    public final Map<String, String> values;
    public final long updatedAtMs;

    public VehicleSnapshot(Map<String, String> src, long updatedAtMs) {
        Map<String, String> copy = new LinkedHashMap<String, String>();
        if (src != null) {
            copy.putAll(src);
        }
        this.values = Collections.unmodifiableMap(copy);
        this.updatedAtMs = updatedAtMs;
    }

    public static VehicleSnapshot empty() {
        return new VehicleSnapshot(Collections.<String, String>emptyMap(), 0L);
    }

    public static VehicleSnapshot fromJson(JSONObject root) {
        if (root == null) return empty();
        JSONObject vals = root.optJSONObject("values");
        if (vals == null) vals = root;
        Map<String, String> map = new LinkedHashMap<String, String>();
        Iterator<String> keys = vals.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            String v = vals.optString(k, "");
            if (k != null && !v.isEmpty()) {
                map.put(k, v);
            }
        }
        long ts = root.optLong("updatedMs", System.currentTimeMillis());
        return new VehicleSnapshot(map, ts);
    }

    public String raw(String key) {
        String v = values.get(key);
        return (v == null || v.isEmpty()) ? "--" : v;
    }

    public boolean flag(String key) {
        String v = raw(key);
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "active".equalsIgnoreCase(v);
    }

    public int intVal(String key, int fallback) {
        try {
            String v = raw(key);
            if ("--".equals(v)) return fallback;
            return Math.round(Float.parseFloat(v));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public boolean isCharging() {
        String chg = raw("charging");
        if ("1".equals(chg) || "true".equalsIgnoreCase(chg) || "charging".equalsIgnoreCase(chg)) {
            return true;
        }
        int code = intVal("charging", 0);
        return code > 0;
    }

    public String formatChargeRemain() {
        int mins = intVal("charging.remain_min", -1);
        if (mins <= 0 || mins >= 9999) {
            return isCharging() ? "Calculating..." : "Not actively charging";
        }
        int h = mins / 60;
        int m = mins % 60;
        if (h > 0) {
            return String.format(Locale.US, "%dh %02dm remaining", h, m);
        }
        return String.format(Locale.US, "%dm remaining", m);
    }

    public String formatRange(boolean miles) {
        int km = intVal("hv.range", -1);
        if (km < 0) return "--";
        if (miles) {
            int mi = Math.round(km * 0.621371f);
            return mi + " mi";
        }
        return km + " km";
    }

    public boolean isCelsius() {
        return "C".equalsIgnoreCase(raw("climate.temp_unit"));
    }

    public String formatAmbientTemp(boolean preferFahrenheit) {
        if (preferFahrenheit) {
            String f = raw("outside_temp_f");
            if (!"--".equals(f)) return f + "\u00B0F";
        }
        String c = raw("outside_temp");
        if (!"--".equals(c)) return c + "\u00B0C";
        String f = raw("outside_temp_f");
        return "--".equals(f) ? "--" : (f + "\u00B0F");
    }

    public String buildDoorsSummary() {
        String any = raw("door.any_open");
        if ("--".equals(any)) return "DOORS: --";
        if (!"1".equals(any)) return "ALL DOORS CLOSED";
        StringBuilder sb = new StringBuilder("OPEN:");
        if (flag("door.fl")) sb.append(" FL");
        if (flag("door.fr")) sb.append(" FR");
        if (flag("door.rl")) sb.append(" RL");
        if (flag("door.rr")) sb.append(" RR");
        return sb.length() == 5 ? "DOOR OPEN" : sb.toString();
    }

    public String formatSingleTire(String key) {
        String v = raw(key);
        if ("--".equals(v)) return "--";
        int code = intVal(key, -1);
        if (code < 0 || code == 255) return "--";
        return code == 0 ? "OK" : "LOW";
    }

    public boolean isSingleTireLow(String key) {
        int code = intVal(key, -1);
        return code > 0 && code != 255;
    }

    public String buildTiresSummary() {
        String fl = formatSingleTire("tyre.fl_status");
        String fr = formatSingleTire("tyre.fr_status");
        String rl = formatSingleTire("tyre.rl_status");
        String rr = formatSingleTire("tyre.rr_status");
        if ("--".equals(fl) && "--".equals(fr) && "--".equals(rl) && "--".equals(rr)) {
            return "Waiting for TPMS";
        }
        StringBuilder low = new StringBuilder();
        if ("LOW".equals(fl)) low.append("FL ");
        if ("LOW".equals(fr)) low.append("FR ");
        if ("LOW".equals(rl)) low.append("RL ");
        if ("LOW".equals(rr)) low.append("RR ");
        if (low.length() > 0) {
            return "LOW PRESSURE: " + low.toString().trim();
        }
        return "All 4 Tires OK";
    }

    public String formatVentMode() {
        int m = intVal("climate.vent_mode", 0);
        switch (m) {
            case 1: return "Face";
            case 2: return "Bi-Level";
            case 3: return "Floor";
            case 4: return "Floor + Defrost";
            case 5: return "Defrost";
            default: return "--";
        }
    }

    public boolean isPreconditioningActive() {
        if (flag("hv.conditioning_active")) return true;
        String st = raw("conditioning.state");
        return "active".equalsIgnoreCase(st);
    }

    public boolean isPreconditioningPending() {
        if (isPreconditioningActive()) return false;
        String st = raw("conditioning.state");
        return "requested".equalsIgnoreCase(st) || "waiting".equalsIgnoreCase(st);
    }

    public String formatPreconditioningStatus() {
        if (isPreconditioningActive()) {
            return "RUNNING (Heating Pack)";
        }
        String st = raw("conditioning.state");
        if ("requested".equalsIgnoreCase(st) || "waiting".equalsIgnoreCase(st)) {
            return "STARTING / WAITING";
        }
        if ("stopping".equalsIgnoreCase(st)) {
            return "STOPPING...";
        }
        if ("timed_out".equalsIgnoreCase(st)) {
            return "TIMED OUT";
        }
        if ("ended".equalsIgnoreCase(st)) {
            return "ENDED";
        }
        if ("1".equals(raw("hv.conditioning"))) {
            return "OFF (Disabled in EV Settings)";
        }
        return "OFF";
    }

    public String formatBatteryTemp(boolean preferFahrenheit) {
        String cStr = raw("hv.battery_temp_min");
        if ("--".equals(cStr)) {
            String fOnly = raw("hv.battery_temp_min_f");
            return "--".equals(fOnly) ? "--" : (fOnly + "\u00B0F");
        }
        try {
            float cVal = Float.parseFloat(cStr);
            int cRound = Math.round(cVal);
            int fRound = Math.round(cVal * 1.8f + 32.0f);
            if (preferFahrenheit) {
                return fRound + "\u00B0F (" + cRound + "\u00B0C)";
            }
            return cRound + "\u00B0C (" + fRound + "\u00B0F)";
        } catch (Throwable ignored) {
            return cStr + "\u00B0C";
        }
    }

    public boolean isUtilityModeActive() {
        return intVal("hv.utility_mode", 0) == 2;
    }

    public String formatUtilityModeStatus() {
        int mode = intVal("hv.utility_mode", -1);
        int warn = intVal("hv.utility_warn", -1);
        if (mode == 2) {
            return "ON (Active)";
        }
        if (warn == 2) {
            return "OFF (Conditions Not Met)";
        }
        if (mode == 1) {
            return "OFF";
        }
        return "--";
    }

    public String formatEvReadyStatus() {
        String rawRdy = raw("hv.ready");
        if ("--".equals(rawRdy)) return "--";
        int v = intVal("hv.ready", -1);
        if (v == 1) return "EV READY";
        if (v == 0) return "NOT READY (IG OFF/ACC)";
        return "RAW " + rawRdy;
    }
}
