package com.micklab.mcp.runtime;

import android.content.Context;

import com.micklab.mcp.security.RuntimeSecurityPolicy;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class NodeRuntimeBridge {
    private Context appContext;
    private File nodeRoot;
    private int servicePort = 8766;
    private boolean runtimeHealthy;
    private String lastHealthSummary = "(no health probe yet)";
    private String lastError = "(not started)";
    private long lastHealthyAtMs;
    private long lastStartAttemptAtMs;

    public synchronized void configure(Context context, File runtimeRoot) {
        appContext = context.getApplicationContext();
        nodeRoot = runtimeRoot;
    }

    public synchronized int getServicePort() {
        return servicePort;
    }

    public synchronized void start() throws Exception {
        ensureStarted();
    }

    public synchronized JSONObject scrapeTitle(String rawUrl, int requestedTimeoutMs) throws Exception {
        if (nodeRoot == null) {
            throw new IllegalStateException("Node runtime root is not configured.");
        }
        String safeUrl = RuntimeSecurityPolicy.validateExternalHttpUrl(rawUrl);
        int timeoutMs = RuntimeSecurityPolicy.sanitizeTimeoutMs(requestedTimeoutMs);
        JSONObject payload = new JSONObject();
        payload.put("url", safeUrl);
        payload.put("timeoutMs", timeoutMs);
        return callTool("node.scrape_title", payload);
    }

    public synchronized void stop() {
        runtimeHealthy = runtimeHealthy && NodeJS.isThreadAlive();
    }

    public synchronized String describe() {
        return "root=" + (nodeRoot == null ? "(not configured)" : nodeRoot.getAbsolutePath())
                + ", entryScript=" + describeEntryScript()
                + ", servicePort=" + servicePort
                + ", processSingleton=true"
                + ", bundledNativeLib=" + hasBundledNativeLibrary()
                + ", nativeStarter=" + sanitizeForStatus(NodeJS.describeNativeBridge())
                + ", startRequested=" + NodeJS.hasStarted()
                + ", threadAlive=" + NodeJS.isThreadAlive()
                + ", healthy=" + runtimeHealthy
                + ", lastStartAttemptAtMs=" + (lastStartAttemptAtMs == 0L ? "(never)" : lastStartAttemptAtMs)
                + ", lastHealthyAtMs=" + (lastHealthyAtMs == 0L ? "(never)" : lastHealthyAtMs)
                + ", lastHealth=" + sanitizeForStatus(lastHealthSummary)
                + ", lastError=" + sanitizeForStatus(lastError)
                + ", nodeState=" + sanitizeForStatus(NodeJS.describeState());
    }

    private void ensureStarted() throws Exception {
        File entryScript = requireNodeFile(
                "index.js",
                "Node entry script index.js is missing from the bundled asset tree."
        );
        requireNodeFile(
                "package.json",
                "Node package.json is missing from the bundled asset tree."
        );
        if (runtimeHealthy && NodeJS.isThreadAlive()) {
            return;
        }
        if (!hasBundledNativeLibrary()) {
            throw new IllegalStateException(
                    "Bundled Node runtime is missing libnode.so. Commit ABI-matched libnode.so files into "
                            + "app/src/main/jniLibs/<abi>/ before building the APK."
            );
        }
        if (NodeJS.hasStarted()) {
            if (NodeJS.isThreadAlive()) {
                waitForHealth();
                return;
            }
            lastError = "Embedded Node.js runtime already ran in this process and cannot be restarted. "
                    + NodeJS.describeState();
            throw new IllegalStateException(lastError);
        }
        String[] args = new String[]{
                "node",
                entryScript.getAbsolutePath(),
                Integer.toString(servicePort),
                nodeRoot.getAbsolutePath()
        };
        try {
            lastStartAttemptAtMs = System.currentTimeMillis();
            lastError = null;
            NodeJS.startWithArguments(args);
        } catch (RuntimeException | UnsatisfiedLinkError exception) {
            lastError = renderException(exception);
            throw new IllegalStateException(
                    "Unable to start bundled Node.js runtime. " + lastError,
                    exception
            );
        }
        waitForHealth();
    }

    private File requireNodeFile(String relativePath, String missingMessage) throws Exception {
        File resolvedFile = RuntimeSecurityPolicy.requireWithinRoot(nodeRoot, new File(nodeRoot, relativePath));
        if (!resolvedFile.isFile()) {
            throw new IllegalStateException(missingMessage);
        }
        return resolvedFile;
    }

    private String describeEntryScript() {
        return nodeRoot == null ? "(not prepared)" : new File(nodeRoot, "index.js").getAbsolutePath();
    }

    private boolean hasBundledNativeLibrary() {
        if (appContext == null) {
            return false;
        }
        File nativeLibrary = new File(appContext.getApplicationInfo().nativeLibraryDir, "libnode.so");
        return nativeLibrary.isFile();
    }

    private void waitForHealth() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                JSONObject response = getJson("/health");
                String status = response.optString("status");
                if ("ok".equalsIgnoreCase(status) || "healthy".equalsIgnoreCase(status)) {
                    runtimeHealthy = true;
                    lastHealthyAtMs = System.currentTimeMillis();
                    lastHealthSummary = response.toString();
                    lastError = null;
                    return;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            if (!NodeJS.isThreadAlive() && NodeJS.getLastError() != null) {
                break;
            }
            Thread.sleep(250L);
        }
        runtimeHealthy = false;
        lastError = NodeJS.getLastError() != null ? NodeJS.getLastError() : NodeJS.describeState();
        throw new IllegalStateException("Node runtime did not report healthy startup. " + lastError, lastFailure);
    }

    private JSONObject callTool(String toolName, JSONObject arguments) throws Exception {
        ensureStarted();
        JSONObject params = new JSONObject();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : new JSONObject());
        return postJsonRpc("tools/call", params);
    }

    private JSONObject postJsonRpc(String method, JSONObject params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", "android-node-bridge");
        request.put("method", method);
        request.put("params", params != null ? params : new JSONObject());

        JSONObject response = postJson("/rpc", request);
        if (response.has("error")) {
            JSONObject error = response.optJSONObject("error");
            if (error != null) {
                throw new IllegalStateException(
                        "Node JSON-RPC error " + error.optInt("code") + ": " + error.optString("message")
                );
            }
            throw new IllegalStateException("Node JSON-RPC error: " + response.opt("error"));
        }

        Object result = response.opt("result");
        if (result instanceof JSONObject) {
            return (JSONObject) result;
        }
        throw new IllegalStateException("Node JSON-RPC result was not an object: " + result);
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + servicePort + path
        ).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(500);
            connection.setReadTimeout(1_000);
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("Node bridge health check failed: HTTP " + responseCode);
            }
            try (BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream())) {
                return new JSONObject(readFully(inputStream));
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject postJson(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + servicePort + path
        ).openConnection();
        byte[] requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(1_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBytes);
            }
            int responseCode = connection.getResponseCode();
            BufferedInputStream inputStream = new BufferedInputStream(
                    responseCode >= 200 && responseCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream()
            );
            if (inputStream == null) {
                throw new IllegalStateException("Node bridge returned HTTP " + responseCode);
            }
            String responseBody = readFully(inputStream);
            if (responseCode < 200 || responseCode >= 300) {
                runtimeHealthy = false;
                lastError = "Node bridge returned HTTP " + responseCode + ": " + responseBody;
                throw new IllegalStateException("Node bridge returned HTTP " + responseCode + ": " + responseBody);
            }
            return new JSONObject(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private String renderException(Throwable throwable) {
        return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }

    private String sanitizeForStatus(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return "(none)";
        }
        return rawValue.replace('\n', ' ');
    }

    private String readFully(BufferedInputStream inputStream) throws Exception {
        try (BufferedInputStream ignored = inputStream;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }
}
