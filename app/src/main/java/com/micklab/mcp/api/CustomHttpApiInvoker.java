package com.micklab.mcp.api;

import android.net.Uri;

import com.micklab.mcp.mcp.Jsons;
import com.micklab.mcp.security.RuntimeSecurityPolicy;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class CustomHttpApiInvoker {
    private static final Object NO_BODY = new Object();
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String USER_AGENT = "Android-Embedded-MCP/1.0";

    private CustomHttpApiInvoker() {
    }

    public static JSONObject invoke(CustomHttpApiDefinition definition, JSONObject arguments) throws Exception {
        JSONObject safeArguments = arguments != null ? arguments : new JSONObject();
        JSONObject requestHeaders = definition.getDefaultHeadersCopy();
        mergeObjectValues(requestHeaders, safeArguments.optJSONObject("headers"));
        putHeaderIfAbsent(requestHeaders, "User-Agent", USER_AGENT);

        String requestUrl = buildRequestUrl(definition.getUrl(), safeArguments.optJSONObject("query"));
        int timeoutMs = RuntimeSecurityPolicy.sanitizeTimeoutMs(
                safeArguments.optInt("timeoutMs", definition.getTimeoutMs())
        );
        Object requestBody = resolveRequestBody(definition, safeArguments);
        if (requestBody != NO_BODY && !CustomHttpApiDefinition.supportsRequestBody(definition.getMethod())) {
            throw new IllegalArgumentException(
                    definition.getMethod() + " requests cannot include a JSON request body."
            );
        }
        if (requestBody != NO_BODY) {
            putHeaderIfAbsent(requestHeaders, "Content-Type", "application/json; charset=utf-8");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
        try {
            connection.setRequestMethod(definition.getMethod());
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            applyHeaders(connection, requestHeaders);
            if (requestBody != NO_BODY) {
                byte[] requestBytes = CustomHttpApiDefinition
                        .jsonValueToString(requestBody)
                        .getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(requestBytes);
                }
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            ReadResult readResult = responseStream == null
                    ? new ReadResult("", false)
                    : readBody(responseStream);

            JSONObject structuredContent = Jsons.objectOf(
                    "tool", definition.getToolName(),
                    "description", definition.getDescription(),
                    "method", definition.getMethod(),
                    "url", requestUrl,
                    "statusCode", responseCode,
                    "contentType", connection.getContentType() != null
                            ? connection.getContentType()
                            : "application/octet-stream",
                    "successful", responseCode >= 200 && responseCode < 300,
                    "requestHeaders", requestHeaders,
                    "responseHeaders", collectResponseHeaders(connection),
                    "bodyText", readResult.text,
                    "bodyTruncated", readResult.truncated
            );
            if (safeArguments.has("query")) {
                structuredContent.put(
                        "query",
                        CustomHttpApiDefinition.copyJsonValue(safeArguments.get("query"))
                );
            }
            if (requestBody != NO_BODY) {
                structuredContent.put("requestBody", CustomHttpApiDefinition.copyJsonValue(requestBody));
            }
            Object parsedBody = parseJsonIfPossible(readResult.text);
            if (parsedBody != null) {
                structuredContent.put("bodyJson", parsedBody);
            }
            return structuredContent;
        } finally {
            connection.disconnect();
        }
    }

    private static String buildRequestUrl(String baseUrl, JSONObject query) throws Exception {
        String safeBaseUrl = RuntimeSecurityPolicy.validateExternalHttpUrl(baseUrl);
        if (query == null || query.length() == 0) {
            return safeBaseUrl;
        }
        Uri.Builder builder = Uri.parse(safeBaseUrl).buildUpon();
        Iterator<String> keys = query.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = query.get(key);
            builder.appendQueryParameter(key, value == JSONObject.NULL ? "" : String.valueOf(value));
        }
        return RuntimeSecurityPolicy.validateExternalHttpUrl(builder.build().toString());
    }

    private static Object resolveRequestBody(
            CustomHttpApiDefinition definition,
            JSONObject arguments
    ) throws Exception {
        if (arguments.has("body")) {
            return CustomHttpApiDefinition.copyJsonValue(arguments.get("body"));
        }
        if (definition.hasDefaultBody()) {
            return definition.getDefaultBodyCopy();
        }
        return NO_BODY;
    }

    private static void mergeObjectValues(JSONObject target, JSONObject source) throws Exception {
        if (source == null) {
            return;
        }
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, source.get(key));
        }
    }

    private static void putHeaderIfAbsent(JSONObject headers, String name, String value) throws Exception {
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (name.equalsIgnoreCase(key)) {
                return;
            }
        }
        headers.put(name, value);
    }

    private static void applyHeaders(HttpURLConnection connection, JSONObject headers) {
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = headers.opt(key);
            connection.setRequestProperty(key, value == JSONObject.NULL ? "" : String.valueOf(value));
        }
    }

    private static JSONObject collectResponseHeaders(HttpURLConnection connection) {
        JSONObject responseHeaders = new JSONObject();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            try {
                responseHeaders.put(entry.getKey(), joinHeaderValues(entry.getValue()));
            } catch (JSONException exception) {
                throw new IllegalStateException("Unable to encode response header: " + entry.getKey(), exception);
            }
        }
        return responseHeaders;
    }

    private static String joinHeaderValues(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    private static ReadResult readBody(InputStream inputStream) throws Exception {
        try (InputStream ignored = inputStream;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            int remaining = MAX_RESPONSE_BYTES;
            boolean truncated = false;
            while ((read = inputStream.read(buffer)) != -1) {
                if (read > remaining) {
                    outputStream.write(buffer, 0, remaining);
                    truncated = true;
                    remaining = 0;
                    break;
                }
                outputStream.write(buffer, 0, read);
                remaining -= read;
                if (remaining == 0) {
                    truncated = inputStream.read() != -1;
                    break;
                }
            }
            return new ReadResult(outputStream.toString(StandardCharsets.UTF_8.name()), truncated);
        }
    }

    private static Object parseJsonIfPossible(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONTokener(rawText).nextValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class ReadResult {
        private final String text;
        private final boolean truncated;

        private ReadResult(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
