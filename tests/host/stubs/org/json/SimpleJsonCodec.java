package org.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained Java 8 JSON parser and serializer for the host JVM test harness.
 */
final class SimpleJsonCodec {
    private final String src;
    private int pos;

    private SimpleJsonCodec(String src) {
        this.src = src == null ? "" : src;
        this.pos = 0;
    }

    static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    static Object parse(String json) {
        SimpleJsonCodec p = new SimpleJsonCodec(json);
        Object val = p.readValue();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("Trailing characters at pos " + p.pos);
        }
        return val;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
            else break;
        }
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = src.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' && src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (c == 'f' && src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (c == 'n' && src.startsWith("null", pos)) { pos += 4; return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw new IllegalArgumentException("Unexpected char '" + c + "' at pos " + pos);
    }

    private Map<String, Object> readObject() {
        pos++; // '{'
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw new IllegalArgumentException("Expected key string at pos " + pos);
            }
            String key = readString();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new IllegalArgumentException("Expected ':' at pos " + pos);
            }
            pos++;
            Object val = readValue();
            map.put(key, val);
            skipWs();
            if (pos >= src.length()) throw new IllegalArgumentException("Unterminated object");
            char next = src.charAt(pos++);
            if (next == '}') return map;
            if (next != ',') throw new IllegalArgumentException("Expected ',' or '}' at pos " + (pos - 1));
        }
    }

    private List<Object> readArray() {
        pos++; // '['
        List<Object> list = new ArrayList<Object>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            if (pos >= src.length()) throw new IllegalArgumentException("Unterminated array");
            char next = src.charAt(pos++);
            if (next == ']') return list;
            if (next != ',') throw new IllegalArgumentException("Expected ',' or ']' at pos " + (pos - 1));
        }
    }

    private String readString() {
        pos++; // opening '"'
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new IllegalArgumentException("Unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw new IllegalArgumentException("Bad unicode escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Number readNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        boolean isFloat = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        }
        String tok = src.substring(start, pos);
        return isFloat ? (Number) Double.valueOf(Double.parseDouble(tok)) : Long.valueOf(Long.parseLong(tok));
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null || value == JSONObject.NULL) {
            sb.append("null");
        } else if (value instanceof JSONObject) {
            writeValue(sb, ((JSONObject) value).map);
        } else if (value instanceof JSONArray) {
            writeValue(sb, ((JSONArray) value).list);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            sb.append('{');
            Iterator<Map.Entry<Object, Object>> it = ((Map<Object, Object>) value).entrySet().iterator();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<Object, Object> e = it.next();
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            List<Object> l = (List<Object>) value;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, l.get(i));
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }
}
