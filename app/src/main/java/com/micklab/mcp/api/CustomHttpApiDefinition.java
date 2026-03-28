package com.micklab.mcp.api;

import android.net.Uri;

import com.micklab.mcp.security.RuntimeSecurityPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class CustomHttpApiDefinition {
    private static final String[] SUPPORTED_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"};
    private static final Set<String> SUPPORTED_METHOD_SET =
            new LinkedHashSet<>(Arrays.asList(SUPPORTED_METHODS));

    private final String id;
    private final String toolName;
    private final String description;
    private final String method;
    private final String url;
    private final int timeoutMs;
    private final JSONObject defaultHeaders;
    private final boolean hasDefaultBody;
    private final Object defaultBody;

    private CustomHttpApiDefinition(
            String id,
            String toolName,
            String description,
            String method,
            String url,
            int timeoutMs,
            JSONObject defaultHeaders,
            boolean hasDefaultBody,
            Object defaultBody
    ) {
        this.id = id;
        this.toolName = toolName;
        this.description = description;
        this.method = method;
        this.url = url;
        this.timeoutMs = timeoutMs;
        this.defaultHeaders = copyJsonObject(defaultHeaders);
        this.hasDefaultBody = hasDefaultBody;
        this.defaultBody = hasDefaultBody ? copyJsonValue(defaultBody) : null;
    }

    public static CustomHttpApiDefinition create(
            String id,
            String rawToolName,
            String rawDescription,
            String rawMethod,
            String rawUrl,
            int timeoutMs,
            String rawHeadersJson,
            String rawBodyJson
    ) throws Exception {
        String normalizedId = requireNonBlank(id, "API id");
        String normalizedMethod = normalizeMethod(rawMethod);
        String safeUrl = RuntimeSecurityPolicy.validateExternalHttpUrl(requireNonBlank(rawUrl, "API URL"));
        String normalizedToolName = normalizeToolName(rawToolName, normalizedMethod, safeUrl, rawDescription);
        JSONObject headers = parseOptionalObject(rawHeadersJson, "default headers");
        ParsedBody parsedBody = parseOptionalBody(rawBodyJson);
        String description = normalizeDescription(rawDescription, normalizedMethod, safeUrl);
        int safeTimeoutMs = RuntimeSecurityPolicy.sanitizeTimeoutMs(timeoutMs);
        return new CustomHttpApiDefinition(
                normalizedId,
                normalizedToolName,
                description,
                normalizedMethod,
                safeUrl,
                safeTimeoutMs,
                headers,
                parsedBody.hasBody,
                parsedBody.body
        );
    }

    public static CustomHttpApiDefinition fromJson(JSONObject rawObject) throws Exception {
        String id = requireNonBlank(rawObject.optString("id", ""), "API id");
        String toolName = normalizeToolName(rawObject.optString("toolName", ""), "GET", rawObject.optString("url", ""), rawObject.optString("description", ""));
        String description = rawObject.optString("description", "").trim();
        String method = normalizeMethod(rawObject.optString("method", "GET"));
        String safeUrl = RuntimeSecurityPolicy.validateExternalHttpUrl(
                requireNonBlank(rawObject.optString("url", ""), "API URL")
        );
        JSONObject headers = rawObject.optJSONObject("defaultHeaders");
        int safeTimeoutMs = RuntimeSecurityPolicy.sanitizeTimeoutMs(rawObject.optInt("timeoutMs", 3_000));
        boolean hasBody = rawObject.has("defaultBody") && !rawObject.isNull("defaultBody");
        Object body = hasBody ? rawObject.get("defaultBody") : null;
        return new CustomHttpApiDefinition(
                id,
                toolName,
                normalizeDescription(description, method, safeUrl),
                method,
                safeUrl,
                safeTimeoutMs,
                headers != null ? headers : new JSONObject(),
                hasBody,
                body
        );
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        put(object, "id", id);
        put(object, "toolName", toolName);
        put(object, "description", description);
        put(object, "method", method);
        put(object, "url", url);
        put(object, "timeoutMs", timeoutMs);
        put(object, "defaultHeaders", copyJsonObject(defaultHeaders));
        if (hasDefaultBody) {
            put(object, "defaultBody", copyJsonValue(defaultBody));
        }
        return object;
    }

    public static String[] supportedMethods() {
        return SUPPORTED_METHODS.clone();
    }

    public static boolean supportsRequestBody(String method) {
        String normalizedMethod = normalizeMethod(method);
        return !"GET".equals(normalizedMethod) && !"DELETE".equals(normalizedMethod);
    }

    public String getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public String getDescription() {
        return description;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public JSONObject getDefaultHeadersCopy() {
        return copyJsonObject(defaultHeaders);
    }

    public boolean hasDefaultBody() {
        return hasDefaultBody;
    }

    public Object getDefaultBodyCopy() {
        if (!hasDefaultBody) {
            throw new IllegalStateException("This API definition does not have a default body.");
        }
        return copyJsonValue(defaultBody);
    }

    public String getDefaultHeadersText() {
        return defaultHeaders.length() == 0 ? "" : prettyPrintJson(defaultHeaders);
    }

    public String getDefaultBodyText() {
        if (!hasDefaultBody) {
            return "";
        }
        if (defaultBody instanceof JSONObject) {
            return prettyPrintJson((JSONObject) defaultBody);
        }
        if (defaultBody instanceof JSONArray) {
            return prettyPrintJson((JSONArray) defaultBody);
        }
        return jsonValueToString(defaultBody);
    }

    static JSONObject copyJsonObject(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(source.toString());
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to copy JSON object.", exception);
        }
    }

    static Object copyJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value == JSONObject.NULL) {
            return JSONObject.NULL;
        }
        try {
            Object copied = new JSONTokener(jsonValueToString(value)).nextValue();
            return copied != null ? copied : JSONObject.NULL;
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to copy JSON value.", exception);
        }
    }

    static String jsonValueToString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            return ((JSONObject) value).toString();
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).toString();
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return JSONObject.quote(String.valueOf(value));
    }

    private static String prettyPrintJson(JSONObject object) {
        try {
            return object.toString(2);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to render JSON object.", exception);
        }
    }

    private static String prettyPrintJson(JSONArray array) {
        try {
            return array.toString(2);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to render JSON array.", exception);
        }
    }

    private static JSONObject parseOptionalObject(String rawJson, String label) throws Exception {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return new JSONObject();
        }
        Object parsed;
        try {
            parsed = new JSONTokener(rawJson).nextValue();
        } catch (JSONException exception) {
            throw new IllegalArgumentException(label + " must be valid JSON.", exception);
        }
        if (!(parsed instanceof JSONObject)) {
            throw new IllegalArgumentException(label + " must be a JSON object.");
        }
        return (JSONObject) parsed;
    }

    private static ParsedBody parseOptionalBody(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return new ParsedBody(false, null);
        }
        try {
            Object parsed = new JSONTokener(rawJson).nextValue();
            return new ParsedBody(true, parsed != null ? parsed : JSONObject.NULL);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("default body must be valid JSON.", exception);
        }
    }

    private static String normalizeMethod(String rawMethod) {
        String normalized = requireNonBlank(rawMethod, "HTTP method").toUpperCase(Locale.US);
        if (!SUPPORTED_METHOD_SET.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + rawMethod);
        }
        return normalized;
    }

    private static String normalizeToolName(
            String rawToolName,
            String method,
            String url,
            String description
    ) {
        String candidate = rawToolName == null ? "" : rawToolName.trim();
        if (candidate.isEmpty()) {
            candidate = buildSuggestedToolName(method, url, description);
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < candidate.length(); index++) {
            char current = Character.toLowerCase(candidate.charAt(index));
            if (Character.isLetterOrDigit(current)) {
                builder.append(current);
            } else if (current == '.' || current == '_' || current == '-') {
                if (builder.length() == 0 || builder.charAt(builder.length() - 1) == current) {
                    continue;
                }
                builder.append(current);
            } else if (builder.length() == 0 || builder.charAt(builder.length() - 1) == '_') {
                continue;
            } else {
                builder.append('_');
            }
        }
        while (builder.length() > 0) {
            char last = builder.charAt(builder.length() - 1);
            if (last == '.' || last == '_' || last == '-') {
                builder.deleteCharAt(builder.length() - 1);
                continue;
            }
            break;
        }
        if (builder.length() == 0) {
            throw new IllegalArgumentException("Tool name must contain at least one letter or digit.");
        }
        if (Character.isDigit(builder.charAt(0))) {
            builder.insert(0, "api.");
        } else if (builder.indexOf(".") == -1) {
            builder.insert(0, "api.");
        }
        return builder.toString();
    }

    private static String buildSuggestedToolName(String method, String url, String description) {
        Uri uri = Uri.parse(url);
        StringBuilder builder = new StringBuilder();
        builder.append("api.");
        if (uri.getHost() != null && !uri.getHost().isEmpty()) {
            builder.append(uri.getHost());
        } else {
            builder.append(method.toLowerCase(Locale.US));
        }
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null && !lastSegment.isEmpty()) {
            builder.append('.').append(lastSegment);
        } else if (description != null && !description.trim().isEmpty()) {
            builder.append('.').append(description.trim());
        } else {
            builder.append('.').append(method.toLowerCase(Locale.US));
        }
        return builder.toString();
    }

    private static String normalizeDescription(String rawDescription, String method, String url) {
        String description = rawDescription == null ? "" : rawDescription.trim();
        if (!description.isEmpty()) {
            return description;
        }
        return "Invoke the saved " + method + " endpoint at " + url + ".";
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.trim();
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to encode JSON field: " + key, exception);
        }
    }

    private static final class ParsedBody {
        private final boolean hasBody;
        private final Object body;

        private ParsedBody(boolean hasBody, Object body) {
            this.hasBody = hasBody;
            this.body = body;
        }
    }
}
