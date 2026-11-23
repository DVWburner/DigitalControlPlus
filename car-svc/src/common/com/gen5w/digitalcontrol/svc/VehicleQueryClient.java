package com.gen5w.digitalcontrol.svc;

import hmgapi.client.HmgApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Queries HMG-API vehicle readings while respecting hmgapid's MAX_ARGS=16 protocol cap
 * ("vehicle" + at most 14 reading keys per IPC request) and quarantining unsupported keys.
 */
final class VehicleQueryClient {
    static final int MAX_KEYS_PER_CALL = 14;
    static final int CALL_TIMEOUT_MS = 3500;
    static final long REJECT_QUARANTINE_MS = 60_000L;

    static final String[] CORE_KEYS = new String[] {
            "hv.soc", "hv.health", "hv.ready", "hv.conditioning", "hv.conditioning_active",
            "hv.battery_temp_min", "conditioning.state",
            "charging", "battery12v.soc", "outside_temp", "outside_temp_f",
            "gear.park", "parking_brake", "speed", "moving", "odometer",
            "wheel_heat", "defogger",
            "seat_heat.driver", "seat_heat.passenger", "seat_heat.rear_left", "seat_heat.rear_right",
            "seat_vent.driver", "seat_vent.passenger"
    };

    private static final Set<String> HMG_DECLARED_KEYS = new HashSet<String>(Arrays.asList(CORE_KEYS));

    static final class BatchResult {
        final Map<String, String> values;
        final boolean anySuccess;
        final String errorNote;

        BatchResult(Map<String, String> values, boolean anySuccess, String errorNote) {
            this.values = values;
            this.anySuccess = anySuccess;
            this.errorNote = errorNote;
        }
    }

    private final Map<String, Long> quarantinedUntilMonoMs = new ConcurrentHashMap<String, Long>();

    private static long monoMs() {
        return System.nanoTime() / 1_000_000L;
    }

    BatchResult query(Collection<String> requestedKeys) {
        long now = monoMs();
        List<String> active = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (requestedKeys != null) {
            for (String raw : requestedKeys) {
                if (raw == null) continue;
                String k = raw.trim();
                if (!HMG_DECLARED_KEYS.contains(k) || !seen.add(k)) continue;
                Long until = quarantinedUntilMonoMs.get(k);
                if (until != null) {
                    if ((until.longValue() - now) > 0L) continue;
                    quarantinedUntilMonoMs.remove(k);
                }
                active.add(k);
            }
        }
        Map<String, String> merged = new LinkedHashMap<String, String>();
        if (active.isEmpty()) {
            return new BatchResult(merged, false, "");
        }
        StringBuilder errNote = new StringBuilder();
        boolean anyOk = false;
        for (int i = 0; i < active.size(); i += MAX_KEYS_PER_CALL) {
            int end = Math.min(active.size(), i + MAX_KEYS_PER_CALL);
            List<String> chunk = active.subList(i, end);
            if (queryChunkWithIsolation(chunk, merged, errNote)) {
                anyOk = true;
            }
        }
        return new BatchResult(merged, anyOk && !merged.isEmpty(), errNote.toString());
    }

    private boolean queryChunkWithIsolation(List<String> chunk, Map<String, String> out, StringBuilder errNote) {
        HmgApi.Result r = invokeVehicle(chunk, errNote);
        if (r == null) return false;
        if (r.code == 0) {
            Map<String, String> parsed = parseVehicleLines(r.text);
            if (!parsed.isEmpty()) {
                out.putAll(parsed);
                return true;
            }
            return false;
        }
        if (chunk.size() == 1) {
            if (r.code == 13 || r.code == 2) {
                quarantinedUntilMonoMs.put(chunk.get(0), Long.valueOf(monoMs() + REJECT_QUARANTINE_MS));
            }
            return false;
        }
        if (r.code == 13 || r.code == 2) {
            boolean gotAny = false;
            for (String key : chunk) {
                List<String> single = new ArrayList<String>(1);
                single.add(key);
                HmgApi.Result kr = invokeVehicle(single, errNote);
                if (kr == null) continue;
                if (kr.code == 0) {
                    Map<String, String> parsed = parseVehicleLines(kr.text);
                    if (!parsed.isEmpty()) {
                        out.putAll(parsed);
                        gotAny = true;
                    }
                } else if (kr.code == 13 || kr.code == 2) {
                    quarantinedUntilMonoMs.put(key, Long.valueOf(monoMs() + REJECT_QUARANTINE_MS));
                }
            }
            return gotAny;
        }
        return false;
    }

    private static HmgApi.Result invokeVehicle(List<String> keys, StringBuilder errNote) {
        try {
            HmgApi api = HmgApi.connect().timeout(CALL_TIMEOUT_MS);
            String[] args = new String[keys.size() + 1];
            args[0] = "vehicle";
            for (int i = 0; i < keys.size(); i++) {
                args[i + 1] = keys.get(i);
            }
            return api.call(args);
        } catch (Throwable t) {
            if (errNote.length() < 120) {
                if (errNote.length() > 0) errNote.append("; ");
                errNote.append(t.getMessage());
            }
            return null;
        }
    }

    private static Map<String, String> parseVehicleLines(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null || text.isEmpty()) return out;
        String[] lines = text.split("\n");
        for (String line : lines) {
            int sp = line.indexOf(' ');
            if (sp > 0) {
                String k = line.substring(0, sp).trim();
                String v = line.substring(sp + 1).trim();
                if (!k.isEmpty() && !v.isEmpty() && !"-".equals(v)) {
                    out.put(k, v);
                }
            }
        }
        return out;
    }
}
