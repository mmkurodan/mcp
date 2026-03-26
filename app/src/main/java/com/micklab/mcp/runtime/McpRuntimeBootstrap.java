package com.micklab.mcp.runtime;

import android.content.Context;

import com.micklab.mcp.mcp.server.LoopbackHttpServer;
import com.micklab.mcp.mcp.server.McpJsonRpcServer;
import com.micklab.mcp.mcp.tools.EchoToolHandler;
import com.micklab.mcp.mcp.tools.NativeInvertToolHandler;
import com.micklab.mcp.mcp.tools.NodeScrapeToolHandler;
import com.micklab.mcp.mcp.tools.PythonFetchToolHandler;
import com.micklab.mcp.mcp.tools.ToolRegistry;

import java.io.File;

public final class McpRuntimeBootstrap {
    private static final int MCP_PORT = 8765;

    private static volatile McpRuntimeBootstrap instance;

    private final Context appContext;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final PythonRuntimeBridge pythonRuntimeBridge = new PythonRuntimeBridge();
    private final NodeRuntimeBridge nodeRuntimeBridge = new NodeRuntimeBridge();
    private final NativeImageBridge nativeImageBridge = NativeImageBridge.getInstance();

    private boolean toolsRegistered;
    private File runtimeRoot;
    private LoopbackHttpServer loopbackHttpServer;

    private McpRuntimeBootstrap(Context context) {
        appContext = context.getApplicationContext();
    }

    public static McpRuntimeBootstrap getInstance(Context context) {
        if (instance == null) {
            synchronized (McpRuntimeBootstrap.class) {
                if (instance == null) {
                    instance = new McpRuntimeBootstrap(context);
                }
            }
        }
        return instance;
    }

    public synchronized void primeNativeLayer() {
        nativeImageBridge.load();
    }

    public synchronized void prepareFilesystem() throws Exception {
        runtimeRoot = new File(appContext.getFilesDir(), "mcp-runtime");
        File pythonRoot = new File(runtimeRoot, "python");
        File nodeRoot = new File(runtimeRoot, "node");
        AssetInstaller.syncAssetTree(appContext, "python", pythonRoot);
        AssetInstaller.syncAssetTree(appContext, "node", nodeRoot);
        pythonRuntimeBridge.configure(appContext, pythonRoot);
        nodeRuntimeBridge.configure(appContext, nodeRoot);
        registerToolsIfNeeded();
    }

    public synchronized void start() throws Exception {
        prepareFilesystem();
        primeNativeLayer();
        if (loopbackHttpServer == null) {
            McpJsonRpcServer jsonRpcServer = new McpJsonRpcServer(toolRegistry);
            loopbackHttpServer = new LoopbackHttpServer(
                    jsonRpcServer,
                    MCP_PORT,
                    com.micklab.mcp.security.RuntimeSecurityPolicy.MAX_HTTP_BODY_BYTES
            );
        }
        loopbackHttpServer.start();
    }

    public synchronized void stop() {
        if (loopbackHttpServer != null) {
            loopbackHttpServer.stop();
            loopbackHttpServer = null;
        }
        nodeRuntimeBridge.stop();
    }

    public synchronized boolean isRunning() {
        return loopbackHttpServer != null && loopbackHttpServer.isRunning();
    }

    public int getMcpPort() {
        return MCP_PORT;
    }

    public synchronized String describeState() {
        StringBuilder builder = new StringBuilder();
        builder.append("running=").append(isRunning()).append('\n');
        builder.append("runtimeRoot=")
                .append(runtimeRoot == null ? "(not prepared)" : runtimeRoot.getAbsolutePath())
                .append('\n');
        builder.append("python=").append(pythonRuntimeBridge.describe()).append('\n');
        builder.append("node=").append(nodeRuntimeBridge.describe()).append('\n');
        builder.append("native=").append(nativeImageBridge.describe());
        return builder.toString();
    }

    public synchronized String describeLayout() {
        File predictedRuntimeRoot = runtimeRoot != null
                ? runtimeRoot
                : new File(appContext.getFilesDir(), "mcp-runtime");
        return "app/src/main/assets/python -> "
                + new File(predictedRuntimeRoot, "python").getAbsolutePath()
                + "\napp/src/main/assets/node -> "
                + new File(predictedRuntimeRoot, "node").getAbsolutePath()
                + "\napp/src/main/jniLibs/<abi> -> prebuilt libnode.so / vendor .so"
                + "\napp/src/main/cpp -> libmcp_native.so";
    }

    private void registerToolsIfNeeded() {
        if (toolsRegistered) {
            return;
        }
        toolRegistry.register(new EchoToolHandler());
        toolRegistry.register(new PythonFetchToolHandler(pythonRuntimeBridge));
        toolRegistry.register(new NodeScrapeToolHandler(nodeRuntimeBridge));
        toolRegistry.register(new NativeInvertToolHandler(nativeImageBridge));
        toolsRegistered = true;
    }
}
