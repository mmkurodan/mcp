package com.micklab.mcp.runtime;

import android.content.Context;

import com.micklab.mcp.security.RuntimeSecurityPolicy;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class NodeRuntimeBridge {
    private static final String[] STARTER_CLASS_CANDIDATES = {
            "com.janeasystems.nodejsmobile.NodeJS",
            "com.janeasystems.nodejsmobile.NodejsMobile",
            "io.nodejs.mobile.NodejsMobile",
            "io.nodejs.NodejsMobile"
    };

    private Context appContext;
    private File nodeRoot;
    private int servicePort = 8766;
    private boolean started;

    public synchronized void configure(Context context, File runtimeRoot) {
        appContext = context.getApplicationContext();
        nodeRoot = runtimeRoot;
        started = false;
    }

    public synchronized JSONObject scrapeTitle(String rawUrl, int requestedTimeoutMs) throws Exception {
        if (nodeRoot == null) {
            throw new IllegalStateException("Node runtime root is not configured.");
        }
        String safeUrl = RuntimeSecurityPolicy.validateExternalHttpUrl(rawUrl);
        int timeoutMs = RuntimeSecurityPolicy.sanitizeTimeoutMs(requestedTimeoutMs);
        ensureStarted();
        JSONObject payload = new JSONObject();
        payload.put("url", safeUrl);
        payload.put("timeoutMs", timeoutMs);
        return postJson("/invoke", payload);
    }

    public synchronized void stop() {
        started = false;
    }

    public synchronized String describe() {
        String starterClass = resolveStarterClassName();
        return "root=" + (nodeRoot == null ? "(not configured)" : nodeRoot.getAbsolutePath())
                + ", servicePort=" + servicePort
                + ", started=" + started
                + ", bundledNativeLib=" + hasBundledNativeLibrary()
                + ", starterClass=" + (starterClass != null ? starterClass : "(missing)");
    }

    private void ensureStarted() throws Exception {
        if (started) {
            return;
        }
        File bootstrap = RuntimeSecurityPolicy.requireWithinRoot(nodeRoot, new File(nodeRoot, "bootstrap.js"));
        if (!bootstrap.isFile()) {
            throw new IllegalStateException("Node bootstrap.js is missing from the bundled asset tree.");
        }
        if (!hasBundledNativeLibrary()) {
            throw new IllegalStateException(
                    "Bundled Node runtime is missing libnode.so. Copy ABI-matched libnode.so files into app/src/main/jniLibs/<abi>/ before building."
            );
        }
        startEmbeddedNode(bootstrap);
        waitForHealth();
        started = true;
    }

    private void startEmbeddedNode(File bootstrap) throws Exception {
        String starterClassName = resolveStarterClassName();
        if (starterClassName == null) {
            throw new IllegalStateException(
                    "Node.js runtime starter classes were not found. Bundle the nodejs-mobile Java wrapper and matching libnode.so files under app/src/main/jniLibs/<abi>/."
            );
        }
        String[] args = new String[]{
                "node",
                bootstrap.getAbsolutePath(),
                Integer.toString(servicePort)
        };
        Class<?> starterClass = Class.forName(starterClassName);
        if (!tryInvokeStarter(starterClass, args)) {
            throw new IllegalStateException("Unable to invoke the bundled Node.js runtime starter.");
        }
    }

    private String resolveStarterClassName() {
        for (String className : STARTER_CLASS_CANDIDATES) {
            try {
                Class.forName(className);
                return className;
            } catch (ClassNotFoundException ignored) {
                // Try the next runtime class candidate.
            }
        }
        return null;
    }

    private boolean hasBundledNativeLibrary() {
        if (appContext == null) {
            return false;
        }
        File nativeLibrary = new File(appContext.getApplicationInfo().nativeLibraryDir, "libnode.so");
        return nativeLibrary.isFile();
    }

    private boolean tryInvokeStarter(Class<?> starterClass, String[] args) throws Exception {
        for (Method method : starterClass.getMethods()) {
            if (!method.getName().toLowerCase(Locale.US).contains("start")) {
                continue;
            }
            Object target = resolveTarget(method, starterClass);
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0].equals(String[].class)) {
                method.invoke(target, (Object) args);
                return true;
            }
            if (parameterTypes.length == 2
                    && Context.class.isAssignableFrom(parameterTypes[0])
                    && parameterTypes[1].equals(String[].class)) {
                method.invoke(target, appContext, (Object) args);
                return true;
            }
        }
        return false;
    }

    private Object resolveTarget(Method method, Class<?> starterClass) throws Exception {
        if (Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        Constructor<?> constructor = starterClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private void waitForHealth() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                JSONObject response = getJson("/health");
                if ("ok".equalsIgnoreCase(response.optString("status"))) {
                    return;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Node runtime did not report healthy startup.", lastFailure);
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
                throw new IllegalStateException("Node bridge returned HTTP " + responseCode + ": " + responseBody);
            }
            return new JSONObject(responseBody);
        } finally {
            connection.disconnect();
        }
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
