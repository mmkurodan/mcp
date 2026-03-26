package com.micklab.mcp.mcp.server;

import com.micklab.mcp.mcp.Jsons;
import com.micklab.mcp.mcp.protocol.JsonRpcRequest;
import com.micklab.mcp.mcp.tools.ToolRegistry;
import com.micklab.mcp.mcp.tools.ToolResultFactory;

import org.json.JSONObject;

public final class McpJsonRpcServer {
    private static final String SUPPORTED_PROTOCOL_VERSION = "2026-03-26";

    private final ToolRegistry toolRegistry;

    public McpJsonRpcServer(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public JSONObject handle(JSONObject rawRequest) {
        JsonRpcRequest request;
        try {
            request = JsonRpcRequest.fromJson(rawRequest);
        } catch (Exception exception) {
            return errorResponse(null, -32600, "Invalid Request", exception.getMessage());
        }

        try {
            switch (request.getMethod()) {
                case "initialize":
                    return successResponse(request.getId(), buildInitializeResult());
                case "notifications/initialized":
                    return null;
                case "ping":
                    return successResponse(request.getId(), Jsons.objectOf("ok", true));
                case "tools/list":
                    return successResponse(
                            request.getId(),
                            Jsons.objectOf("tools", toolRegistry.listTools())
                    );
                case "tools/call":
                    return handleToolCall(request);
                default:
                    return errorResponse(
                            request.getId(),
                            -32601,
                            "Method not found",
                            request.getMethod()
                    );
            }
        } catch (Exception exception) {
            return errorResponse(
                    request.getId(),
                    -32000,
                    "Server error",
                    exception.getMessage()
            );
        }
    }

    private JSONObject handleToolCall(JsonRpcRequest request) {
        JSONObject params = request.getParams();
        String toolName = params.optString("name", "");
        JSONObject arguments = params.optJSONObject("arguments");
        if (toolName.isEmpty()) {
            return errorResponse(request.getId(), -32602, "Invalid params", "params.name is required");
        }
        try {
            JSONObject toolResult = toolRegistry.call(toolName, arguments);
            return successResponse(request.getId(), toolResult);
        } catch (Exception exception) {
            JSONObject errorResult = ToolResultFactory.error(
                    "Tool " + toolName + " failed: " + exception.getMessage(),
                    Jsons.objectOf(
                            "tool", toolName,
                            "exception", exception.getClass().getSimpleName()
                    )
            );
            return successResponse(request.getId(), errorResult);
        }
    }

    private JSONObject buildInitializeResult() {
        return Jsons.objectOf(
                "protocolVersion", SUPPORTED_PROTOCOL_VERSION,
                "capabilities", Jsons.objectOf(
                        "tools", Jsons.objectOf("listChanged", false)
                ),
                "serverInfo", Jsons.objectOf(
                        "name", "android-embedded-mcp",
                        "version", "1.0.0"
                ),
                "instructions", "Use tools/list then tools/call against the loopback-only /rpc endpoint."
        );
    }

    private JSONObject successResponse(Object id, JSONObject result) {
        return Jsons.objectOf(
                "jsonrpc", "2.0",
                "id", id != null ? id : JSONObject.NULL,
                "result", result
        );
    }

    private JSONObject errorResponse(Object id, int code, String message, String data) {
        return Jsons.objectOf(
                "jsonrpc", "2.0",
                "id", id != null ? id : JSONObject.NULL,
                "error", Jsons.objectOf(
                        "code", code,
                        "message", message,
                        "data", data != null ? data : JSONObject.NULL
                )
        );
    }
}
