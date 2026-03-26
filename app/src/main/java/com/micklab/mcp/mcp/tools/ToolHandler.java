package com.micklab.mcp.mcp.tools;

import org.json.JSONObject;

public interface ToolHandler {
    ToolDefinition definition();

    JSONObject handle(JSONObject arguments) throws Exception;
}
