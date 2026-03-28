package com.micklab.mcp.runtime;

import android.content.Context;

import com.micklab.mcp.security.RuntimeSecurityPolicy;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class PythonRuntimeBridge {
    private Context appContext;
    private File pythonRoot;
    private boolean sysPathPrepared;

    public synchronized void configure(Context context, File runtimeRoot) {
        appContext = context.getApplicationContext();
        pythonRoot = runtimeRoot;
        sysPathPrepared = false;
    }

    public synchronized JSONObject fetchUrl(String rawUrl, int requestedTimeoutMs, JSONObject headers)
            throws Exception {
        if (pythonRoot == null) {
            throw new IllegalStateException("Python runtime root is not configured.");
        }
        String safeUrl = RuntimeSecurityPolicy.validateExternalHttpUrl(rawUrl);
        int timeoutMs = RuntimeSecurityPolicy.sanitizeTimeoutMs(requestedTimeoutMs);
        Object python = getPythonInstance();
        ensurePythonPath(python);

        Object bootstrapModule = getModule(python, "bootstrap");
        callPyAttr(bootstrapModule, "initialize", pythonRoot.getAbsolutePath());

        JSONObject payload = new JSONObject();
        payload.put("url", safeUrl);
        payload.put("timeout_ms", timeoutMs);
        payload.put("headers", headers != null ? headers : new JSONObject());

        Object serviceModule = getModule(python, "mcp_services.http_fetcher");
        Object response = callPyAttr(serviceModule, "invoke_json", payload.toString());
        return new JSONObject(response.toString());
    }

    public synchronized String describe() {
        return "root=" + (pythonRoot == null ? "(not configured)" : pythonRoot.getAbsolutePath())
                + ", chaquopyAvailable=" + isChaquopyOnClasspath()
                + ", chaquopyStarted=" + isChaquopyStarted();
    }

    public synchronized boolean isChaquopyBundled() {
        return isChaquopyOnClasspath();
    }

    private Object getPythonInstance() throws Exception {
        try {
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");
            Boolean started = (Boolean) pythonClass.getMethod("isStarted").invoke(null);
            if (!started) {
                Class<?> androidPlatformClass =
                        Class.forName("com.chaquo.python.android.AndroidPlatform");
                Object platform = androidPlatformClass
                        .getConstructor(Context.class)
                        .newInstance(appContext);
                Method startMethod = findStaticOneArgMethod(pythonClass, "start");
                if (startMethod == null) {
                    throw new IllegalStateException("Unable to locate Python.start(...)");
                }
                startMethod.invoke(null, platform);
            }
            return pythonClass.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "Chaquopy is not on the classpath. Add the Chaquopy runtime and bundle "
                            + "pure Python packages under app/src/main/assets/python/site-packages.",
                    exception
            );
        }
    }

    private void ensurePythonPath(Object python) throws Exception {
        if (sysPathPrepared) {
            return;
        }
        File sitePackagesRoot = new File(pythonRoot, "site-packages");
        Object sysModule = getModule(python, "sys");
        Method getMethod = sysModule.getClass().getMethod("get", String.class);
        Object pathObject = getMethod.invoke(sysModule, "path");
        callPyAttr(pathObject, "insert", 0, sitePackagesRoot.getAbsolutePath());
        callPyAttr(pathObject, "insert", 0, pythonRoot.getAbsolutePath());
        sysPathPrepared = true;
    }

    private Object getModule(Object python, String moduleName) throws Exception {
        Method getModuleMethod = python.getClass().getMethod("getModule", String.class);
        return getModuleMethod.invoke(python, moduleName);
    }

    private Object callPyAttr(Object pyObject, String name, Object... args) throws Exception {
        Method callAttrMethod = pyObject.getClass().getMethod("callAttr", String.class, Object[].class);
        return callAttrMethod.invoke(pyObject, name, (Object) args);
    }

    private Method findStaticOneArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!method.getName().equals(name)) {
                continue;
            }
            if (method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    private boolean isChaquopyOnClasspath() {
        try {
            Class.forName("com.chaquo.python.Python");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isChaquopyStarted() {
        try {
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");
            return (Boolean) pythonClass.getMethod("isStarted").invoke(null);
        } catch (Exception ignored) {
            return false;
        }
    }
}
