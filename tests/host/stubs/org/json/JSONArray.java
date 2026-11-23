package org.json;

import hmgapi.engine.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JSONArray {
    final List<Object> list;

    public JSONArray() {
        this.list = new ArrayList<Object>();
    }

    public JSONArray(String json) throws JSONException {
        try {
            Object p = Json.parse(json);
            if (!(p instanceof List)) throw new JSONException("Not a JSON array");
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) p;
            this.list = l;
        } catch (Throwable t) {
            throw new JSONException("Invalid JSON array: " + t.getMessage(), t);
        }
    }

    JSONArray(List<Object> list) {
        this.list = list;
    }

    public JSONArray put(Object value) {
        list.add(JSONObject.unwrap(value));
        return this;
    }

    public int length() {
        return list.size();
    }

    public Object opt(int index) {
        return (index >= 0 && index < list.size()) ? list.get(index) : null;
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        return v == null ? fallback : String.valueOf(v);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    @Override
    public String toString() {
        return Json.write(list);
    }
}
