package com.micklab.mcp.mcp.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    public synchronized void register(ToolHandler handler) {
        handlers.put(handler.definition().getName(), handler);
    }

    public synchronized JSONArray listTools() {
        JSONArray tools = new JSONArray();
        for (ToolHandler handler : handlers.values()) {
            tools.put(handler.definition().toJson());
        }
        return tools;
    }

    public synchronized JSONObject call(String toolName, JSONObject arguments) throws Exception {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return handler.handle(arguments != null ? arguments : new JSONObject());
    }
}
