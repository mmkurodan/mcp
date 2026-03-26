package com.micklab.mcp.mcp.tools;

import com.micklab.mcp.mcp.Jsons;

import org.json.JSONArray;
import org.json.JSONObject;

public final class EchoToolHandler implements ToolHandler {
    @Override
    public ToolDefinition definition() {
        JSONObject textProperty = Jsons.objectOf(
                "type", "string",
                "description", "Echo text to verify MCP transport and tool wiring."
        );
        JSONObject schema = Jsons.objectOf(
                "type", "object",
                "properties", Jsons.objectOf("text", textProperty),
                "required", Jsons.arrayOf("text")
        );
        return new ToolDefinition("echo", "Return the provided text.", schema);
    }

    @Override
    public JSONObject handle(JSONObject arguments) {
        String text = arguments.optString("text", "");
        JSONObject structuredContent = Jsons.objectOf("echo", text);
        return ToolResultFactory.text("echo: " + text, structuredContent);
    }
}
