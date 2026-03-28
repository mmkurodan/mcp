package com.micklab.mcp.runtime;

import java.util.Arrays;

public final class NodeJS {
    private static final Object LOCK = new Object();

    private static boolean starterLibraryLoaded;
    private static boolean nodeLibraryLoaded;
    private static boolean startRequested;
    private static String[] launchArguments = new String[0];
    private static Thread runtimeThread;
    private static Integer exitCode;
    private static String lastError;
    private static long startedAtEpochMs;

    private NodeJS() {
    }

    public static void startWithArguments(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            throw new IllegalArgumentException("Node.js launch arguments are required.");
        }
        synchronized (LOCK) {
            loadLibraries();
            if (runtimeThread != null && runtimeThread.isAlive()) {
                return;
            }
            if (startRequested) {
                throw new IllegalStateException(
                        "Embedded Node.js runtime already ran in this process and cannot be restarted. "
                                + describeStateLocked()
                );
            }
            launchArguments = Arrays.copyOf(arguments, arguments.length);
            startRequested = true;
            exitCode = null;
            lastError = null;
            startedAtEpochMs = System.currentTimeMillis();
            runtimeThread = new Thread(() -> runNodeMain(launchArguments), "embedded-node-runtime");
            runtimeThread.setDaemon(true);
            runtimeThread.start();
        }
    }

    public static void loadLibraries() {
        synchronized (LOCK) {
            ensureNodeLibraryLoaded();
            ensureStarterLibraryLoaded();
        }
    }

    public static boolean hasStarted() {
        synchronized (LOCK) {
            return startRequested;
        }
    }

    public static boolean isThreadAlive() {
        synchronized (LOCK) {
            return runtimeThread != null && runtimeThread.isAlive();
        }
    }

    public static Integer getExitCode() {
        synchronized (LOCK) {
            return exitCode;
        }
    }

    public static String getLastError() {
        synchronized (LOCK) {
            return lastError;
        }
    }

    public static long getStartedAtEpochMs() {
        synchronized (LOCK) {
            return startedAtEpochMs;
        }
    }

    public static String describeState() {
        synchronized (LOCK) {
            return describeStateLocked();
        }
    }

    public static String describeNativeBridge() {
        synchronized (LOCK) {
            try {
                ensureStarterLibraryLoaded();
                return nativeRuntimeInfo();
            } catch (Throwable throwable) {
                return "starterLibraryLoaded=false, details="
                        + throwable.getClass().getSimpleName()
                        + ": "
                        + throwable.getMessage();
            }
        }
    }

    private static String describeStateLocked() {
        return "starterLibraryLoaded=" + starterLibraryLoaded
                + ", nodeLibraryLoaded=" + nodeLibraryLoaded
                + ", startRequested=" + startRequested
                + ", threadAlive=" + (runtimeThread != null && runtimeThread.isAlive())
                + ", startedAtEpochMs=" + startedAtEpochMs
                + ", exitCode=" + (exitCode == null ? "(running or not exited)" : exitCode)
                + ", lastError=" + (lastError == null ? "(none)" : lastError);
    }

    private static void ensureStarterLibraryLoaded() {
        if (starterLibraryLoaded) {
            return;
        }
        System.loadLibrary("mcp_node");
        starterLibraryLoaded = true;
    }

    private static void ensureNodeLibraryLoaded() {
        if (nodeLibraryLoaded) {
            return;
        }
        System.loadLibrary("node");
        nodeLibraryLoaded = true;
    }

    private static void runNodeMain(String[] arguments) {
        try {
            int result = nativeStartWithArguments(arguments);
            synchronized (LOCK) {
                exitCode = result;
                if (result != 0 && lastError == null) {
                    lastError = "Node.js runtime exited with code " + result + ".";
                }
            }
        } catch (Throwable throwable) {
            synchronized (LOCK) {
                lastError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            }
        }
    }

    private static native int nativeStartWithArguments(String[] arguments);

    private static native String nativeRuntimeInfo();
}
