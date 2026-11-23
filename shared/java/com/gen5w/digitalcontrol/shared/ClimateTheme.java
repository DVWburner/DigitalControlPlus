package com.gen5w.digitalcontrol.shared;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * User-created "Climate Theme" preset combining cabin temperature, blower speed, A/C & Heat flags,
 * defrost, vent mode, steering wheel heat, and multi-zone seat heat/ventilation into one atomic preset.
 */
public final class ClimateTheme {
    public String id;
    public String name;
    public int accentColor;
    public boolean celsius;
    public String driverTemp;      // e.g. "69", "78", "21.5", "LO", "HI"
    public String passengerTemp;   // e.g. "69", "SYNC"
    public boolean syncZones;
    public int fanSpeed;           // 1..8 (or 0 if autoLevel > 0)
    public int autoLevel;          // 0=Manual fan, 1=Auto Low, 2=Auto Med, 3=Auto High
    public boolean acOn;
    public boolean heatOn;
    public boolean recirculate;
    public int ventMode;           // 0=Unchanged/Auto, 1=Face, 2=Bi-Level, 3=Floor, 4=Floor+Defrost
    public boolean frontDefrost;
    public boolean rearDefrost;
    public boolean wheelHeat;
    public int driverSeatHeat;     // 0..3
    public int passengerSeatHeat;  // 0..3
    public int rearLeftSeatHeat;   // 0..3
    public int rearRightSeatHeat;  // 0..3
    public int driverSeatVent;     // 0..3
    public int passengerSeatVent;  // 0..3

    public ClimateTheme() {
        this.id = "theme_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        this.name = "My Climate Preset";
        this.accentColor = 0xFF0EA5E9;
        this.celsius = false;
        this.driverTemp = "70";
        this.passengerTemp = "70";
        this.syncZones = true;
        this.fanSpeed = 3;
        this.autoLevel = 0;
        this.acOn = true;
        this.heatOn = false;
        this.recirculate = false;
        this.ventMode = 1;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("id", id == null || id.isEmpty() ? ("theme_" + CryptoBox.randomHex(5)) : id);
        j.put("name", name == null || name.trim().isEmpty() ? "Climate Preset" : name.trim());
        j.put("accentColor", accentColor);
        j.put("celsius", celsius);
        j.put("driverTemp", driverTemp == null ? "70" : driverTemp.trim());
        j.put("passengerTemp", passengerTemp == null ? "70" : passengerTemp.trim());
        j.put("syncZones", syncZones);
        j.put("fanSpeed", clamp(fanSpeed, 1, 8));
        j.put("autoLevel", clamp(autoLevel, 0, 3));
        j.put("acOn", acOn);
        j.put("heatOn", heatOn);
        j.put("recirculate", recirculate);
        j.put("ventMode", clamp(ventMode, 0, 5));
        j.put("frontDefrost", frontDefrost);
        j.put("rearDefrost", rearDefrost);
        j.put("wheelHeat", wheelHeat);
        j.put("driverSeatHeat", clamp(driverSeatHeat, 0, 3));
        j.put("passengerSeatHeat", clamp(passengerSeatHeat, 0, 3));
        j.put("rearLeftSeatHeat", clamp(rearLeftSeatHeat, 0, 3));
        j.put("rearRightSeatHeat", clamp(rearRightSeatHeat, 0, 3));
        j.put("driverSeatVent", clamp(driverSeatVent, 0, 3));
        j.put("passengerSeatVent", clamp(passengerSeatVent, 0, 3));
        return j;
    }

    public static ClimateTheme fromJson(JSONObject j) {
        ClimateTheme t = new ClimateTheme();
        if (j == null) return t;
        t.id = j.optString("id", t.id);
        t.name = j.optString("name", "Climate Preset");
        t.accentColor = j.optInt("accentColor", 0xFF0EA5E9);
        t.celsius = j.optBoolean("celsius", false);
        t.driverTemp = j.optString("driverTemp", t.celsius ? "21" : "70");
        t.passengerTemp = j.optString("passengerTemp", t.driverTemp);
        t.syncZones = j.optBoolean("syncZones", true);
        t.fanSpeed = clamp(j.optInt("fanSpeed", 3), 1, 8);
        t.autoLevel = clamp(j.optInt("autoLevel", 0), 0, 3);
        t.acOn = j.optBoolean("acOn", true);
        t.heatOn = j.optBoolean("heatOn", false);
        t.recirculate = j.optBoolean("recirculate", false);
        t.ventMode = clamp(j.optInt("ventMode", 1), 0, 5);
        t.frontDefrost = j.optBoolean("frontDefrost", false);
        t.rearDefrost = j.optBoolean("rearDefrost", false);
        t.wheelHeat = j.optBoolean("wheelHeat", false);
        t.driverSeatHeat = clamp(j.optInt("driverSeatHeat", 0), 0, 3);
        t.passengerSeatHeat = clamp(j.optInt("passengerSeatHeat", 0), 0, 3);
        t.rearLeftSeatHeat = clamp(j.optInt("rearLeftSeatHeat", 0), 0, 3);
        t.rearRightSeatHeat = clamp(j.optInt("rearRightSeatHeat", 0), 0, 3);
        t.driverSeatVent = clamp(j.optInt("driverSeatVent", 0), 0, 3);
        t.passengerSeatVent = clamp(j.optInt("passengerSeatVent", 0), 0, 3);
        return t;
    }

    public String buildSummaryLine() {
        StringBuilder sb = new StringBuilder();
        String unit = celsius ? "\u00B0C" : "\u00B0F";
        String dt = driverTemp == null ? "70" : driverTemp.toUpperCase(Locale.US);
        if ("LO".equals(dt) || "HI".equals(dt)) {
            sb.append(dt);
        } else {
            sb.append(dt).append(unit);
        }
        if (!syncZones && passengerTemp != null && !passengerTemp.isEmpty()) {
            sb.append('/').append(passengerTemp).append(unit);
        }
        if (autoLevel > 0) {
            sb.append(" · AUTO ").append(autoLevel);
        } else {
            sb.append(" · Fan ").append(fanSpeed);
        }
        if (acOn) sb.append(" · A/C");
        if (heatOn) sb.append(" · Heat");
        if (wheelHeat) sb.append(" · Wheel");
        if (driverSeatHeat > 0 || passengerSeatHeat > 0) {
            sb.append(" · SeatHeat ").append(Math.max(driverSeatHeat, passengerSeatHeat));
        } else if (driverSeatVent > 0 || passengerSeatVent > 0) {
            sb.append(" · SeatCool ").append(Math.max(driverSeatVent, passengerSeatVent));
        }
        if (frontDefrost || rearDefrost) {
            sb.append(" · Defrost");
        }
        return sb.toString();
    }

    public static List<ClimateTheme> createStarterPresets() {
        List<ClimateTheme> list = new ArrayList<ClimateTheme>();

        ClimateTheme cool69 = new ClimateTheme();
        cool69.id = "preset_cool_69";
        cool69.name = "Cool Breeze 69\u00B0F";
        cool69.accentColor = 0xFF0EA5E9;
        cool69.celsius = false;
        cool69.driverTemp = "69";
        cool69.passengerTemp = "69";
        cool69.syncZones = true;
        cool69.fanSpeed = 2;
        cool69.autoLevel = 0;
        cool69.acOn = true;
        cool69.heatOn = false;
        cool69.ventMode = 1;
        cool69.driverSeatVent = 1;
        list.add(cool69);

        ClimateTheme cozy78 = new ClimateTheme();
        cozy78.id = "preset_cozy_78";
        cozy78.name = "Warm Cozy 78\u00B0F";
        cozy78.accentColor = 0xFFF97316;
        cozy78.celsius = false;
        cozy78.driverTemp = "78";
        cozy78.passengerTemp = "78";
        cozy78.syncZones = true;
        cozy78.fanSpeed = 4;
        cozy78.autoLevel = 0;
        cozy78.acOn = true;
        cozy78.heatOn = true;
        cozy78.ventMode = 3; // Floor
        cozy78.wheelHeat = true;
        cozy78.driverSeatHeat = 2;
        cozy78.passengerSeatHeat = 2;
        list.add(cozy78);

        ClimateTheme frost = new ClimateTheme();
        frost.id = "preset_defrost_82";
        frost.name = "Morning Defrost";
        frost.accentColor = 0xFFEF4444;
        frost.celsius = false;
        frost.driverTemp = "82";
        frost.passengerTemp = "82";
        frost.syncZones = true;
        frost.fanSpeed = 6;
        frost.autoLevel = 0;
        frost.acOn = true;
        frost.heatOn = true;
        frost.ventMode = 4; // Floor + Defrost
        frost.frontDefrost = true;
        frost.rearDefrost = true;
        frost.wheelHeat = true;
        frost.driverSeatHeat = 3;
        frost.passengerSeatHeat = 2;
        list.add(frost);

        return list;
    }

    public static String serializeList(List<ClimateTheme> themes) {
        JSONArray arr = new JSONArray();
        if (themes != null) {
            for (ClimateTheme t : themes) {
                if (t == null) continue;
                try {
                    arr.put(t.toJson());
                } catch (JSONException ignored) {
                }
            }
        }
        return arr.toString();
    }

    public static List<ClimateTheme> parseList(String rawJson) {
        List<ClimateTheme> out = new ArrayList<ClimateTheme>();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return createStarterPresets();
        }
        try {
            JSONArray arr = new JSONArray(rawJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(fromJson(o));
                }
            }
        } catch (Throwable ignored) {
            return createStarterPresets();
        }
        return out.isEmpty() ? createStarterPresets() : out;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
