package com.micklab.mcp.mcp;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Jsons {
    private Jsons() {
    }

    public static JSONObject object() {
        return new JSONObject();
    }

    public static JSONObject objectOf(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("objectOf requires an even number of arguments.");
        }
        JSONObject object = new JSONObject();
        for (int index = 0; index < keyValues.length; index += 2) {
            put(object, String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return object;
    }

    public static JSONArray arrayOf(Object... values) {
        JSONArray array = new JSONArray();
        for (Object value : values) {
            array.put(value);
        }
        return array;
    }

    public static JSONObject parseObject(String rawJson) {
        try {
            return new JSONObject(rawJson);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to parse JSON object.", exception);
        }
    }

    public static String requireString(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException("Missing or invalid string field: " + key);
        }
        return (String) value;
    }

    public static int requireInt(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid integer field: " + key, exception);
            }
        }
        throw new IllegalArgumentException("Missing or invalid integer field: " + key);
    }

    public static JSONObject put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
            return object;
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to encode JSON field: " + key, exception);
        }
    }
}
