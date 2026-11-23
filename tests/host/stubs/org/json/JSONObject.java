package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JSONObject {
    public static final Object NULL = new Object();
    final Map<String, Object> map;

    public JSONObject() {
        this.map = new LinkedHashMap<String, Object>();
    }

    public JSONObject(String json) throws JSONException {
        try {
            this.map = SimpleJsonCodec.parseObject(json);
        } catch (Throwable t) {
            throw new JSONException("Invalid JSON object: " + t.getMessage(), t);
        }
    }

    JSONObject(Map<String, Object> map) {
        this.map = map;
    }

    static Object unwrap(Object v) {
        if (v instanceof JSONObject) return ((JSONObject) v).map;
        if (v instanceof JSONArray) return ((JSONArray) v).list;
        return v;
    }

    public Iterator<String> keys() {
        return map.keySet().iterator();
    }

    public int length() {
        return map.size();
    }

    public JSONObject put(String name, Object value) throws JSONException {
        map.put(name, unwrap(value));
        return this;
    }

    public JSONObject put(String name, int value) throws JSONException {
        map.put(name, Integer.valueOf(value));
        return this;
    }

    public JSONObject put(String name, long value) throws JSONException {
        map.put(name, Long.valueOf(value));
        return this;
    }

    public JSONObject put(String name, boolean value) throws JSONException {
        map.put(name, Boolean.valueOf(value));
        return this;
    }

    public String getString(String name) throws JSONException {
        Object v = map.get(name);
        if (v == null) throw new JSONException("Missing key: " + name);
        return String.valueOf(v);
    }

    public long getLong(String name) throws JSONException {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        throw new JSONException("Not a long: " + name);
    }

    public String optString(String name) {
        return optString(name, "");
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return v == null ? fallback : String.valueOf(v);
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long optLong(String name) {
        return optLong(name, 0L);
    }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) {
            if ("true".equalsIgnoreCase((String) v)) return true;
            if ("false".equalsIgnoreCase((String) v)) return false;
        }
        return fallback;
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return null;
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public Object remove(String name) {
        return map.remove(name);
    }

    @Override
    public String toString() {
        return SimpleJsonCodec.write(map);
    }
}
