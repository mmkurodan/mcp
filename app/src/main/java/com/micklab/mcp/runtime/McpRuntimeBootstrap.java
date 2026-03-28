package com.micklab.mcp.runtime;

import android.content.Context;

import com.micklab.mcp.api.CustomHttpApiDefinition;
import com.micklab.mcp.api.CustomHttpApiStore;
import com.micklab.mcp.api.CustomHttpApiToolHandler;
import com.micklab.mcp.mcp.server.LoopbackHttpServer;
import com.micklab.mcp.mcp.server.McpJsonRpcServer;
import com.micklab.mcp.mcp.tools.EchoToolHandler;
import com.micklab.mcp.mcp.tools.NativeInvertToolHandler;
import com.micklab.mcp.mcp.tools.NodeScrapeToolHandler;
import com.micklab.mcp.mcp.tools.PythonFetchToolHandler;
import com.micklab.mcp.mcp.tools.ToolRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class McpRuntimeBootstrap {
    private static final int MCP_PORT = 8765;
    private static final Set<String> RESERVED_TOOL_NAMES = new LinkedHashSet<>(Arrays.asList(
            "echo",
            "python.fetch_url",
            "node.scrape_title",
            "native.invert_grayscale"
    ));

    private static volatile McpRuntimeBootstrap instance;

    private final Context appContext;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final PythonRuntimeBridge pythonRuntimeBridge = new PythonRuntimeBridge();
    private final NodeRuntimeBridge nodeRuntimeBridge = new NodeRuntimeBridge();
    private final NativeImageBridge nativeImageBridge = NativeImageBridge.getInstance();
    private final CustomHttpApiStore customApiStore;
    private final Set<String> customToolNames = new LinkedHashSet<>();

    private boolean toolsRegistered;
    private File runtimeRoot;
    private LoopbackHttpServer loopbackHttpServer;

    private McpRuntimeBootstrap(Context context) {
        appContext = context.getApplicationContext();
        customApiStore = new CustomHttpApiStore(appContext);
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
        registerBuiltinToolsIfNeeded();
        syncCustomApiTools(loadCustomApis());
    }

    public synchronized void start() throws Exception {
        prepareFilesystem();
        primeNativeLayer();
        nodeRuntimeBridge.start();
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

    public int getNodePort() {
        return nodeRuntimeBridge.getServicePort();
    }

    public synchronized List<CustomHttpApiDefinition> loadCustomApis() throws Exception {
        List<CustomHttpApiDefinition> definitions = new ArrayList<>(customApiStore.loadAll());
        validateCustomApiDefinitions(definitions);
        return definitions;
    }

    public synchronized void saveCustomApis(List<CustomHttpApiDefinition> definitions) throws Exception {
        List<CustomHttpApiDefinition> safeDefinitions = new ArrayList<>(definitions);
        validateCustomApiDefinitions(safeDefinitions);
        customApiStore.saveAll(safeDefinitions);
        registerBuiltinToolsIfNeeded();
        syncCustomApiTools(safeDefinitions);
    }

    public synchronized String describeState() {
        StringBuilder builder = new StringBuilder();
        builder.append("running=").append(isRunning()).append('\n');
        builder.append("customApis=").append(customToolNames.size()).append('\n');
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
        return "Python runtime: Chaquopy bundles CPython and pip packages at build time.\n"
                + "app/src/main/assets/python -> "
                + new File(predictedRuntimeRoot, "python").getAbsolutePath()
                + "\nNode project assets: app/src/main/assets/node/index.js + package.json + node_modules -> "
                + new File(predictedRuntimeRoot, "node").getAbsolutePath()
                + "\nNode native runtime: app/src/main/jniLibs/<abi>/libnode.so is packaged into the APK and loaded with System.loadLibrary(\"node\")."
                + "\nNode native headers: app/libnode/include/node/node.h enables the JNI bridge to node::Start(argc, argv)."
                + "\nForeground service host: app/src/main/java/com/micklab/mcp/service/McpForegroundService starts the bundled Node singleton before exposing MCP."
                + "\nJNI native code: app/src/main/cpp -> libmcp_native.so + libmcp_node.so"
                + "\nProcess note: Node.js Mobile can only be started once per app process, so service restarts reattach to the warm singleton.";
    }

    private void registerBuiltinToolsIfNeeded() {
        if (toolsRegistered) {
            return;
        }
        toolRegistry.register(new EchoToolHandler());
        toolRegistry.register(new PythonFetchToolHandler(pythonRuntimeBridge));
        toolRegistry.register(new NodeScrapeToolHandler(nodeRuntimeBridge));
        toolRegistry.register(new NativeInvertToolHandler(nativeImageBridge));
        toolsRegistered = true;
    }

    private void syncCustomApiTools(List<CustomHttpApiDefinition> definitions) {
        for (String toolName : customToolNames) {
            toolRegistry.unregister(toolName);
        }
        customToolNames.clear();
        for (CustomHttpApiDefinition definition : definitions) {
            toolRegistry.register(new CustomHttpApiToolHandler(definition));
            customToolNames.add(definition.getToolName());
        }
    }

    private void validateCustomApiDefinitions(List<CustomHttpApiDefinition> definitions) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> toolNames = new LinkedHashSet<>();
        for (CustomHttpApiDefinition definition : definitions) {
            if (!ids.add(definition.getId())) {
                throw new IllegalArgumentException("Duplicate custom API id: " + definition.getId());
            }
            if (RESERVED_TOOL_NAMES.contains(definition.getToolName())) {
                throw new IllegalArgumentException(
                        "Tool name is reserved by a built-in tool: " + definition.getToolName()
                );
            }
            if (!toolNames.add(definition.getToolName())) {
                throw new IllegalArgumentException(
                        "Duplicate custom API tool name: " + definition.getToolName()
                );
            }
        }
    }
}
