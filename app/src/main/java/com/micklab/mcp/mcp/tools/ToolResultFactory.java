package com.micklab.mcp.mcp.tools;

import com.micklab.mcp.mcp.Jsons;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolResultFactory {
    private ToolResultFactory() {
    }

    public static JSONObject text(String text, JSONObject structuredContent) {
        JSONObject result = Jsons.object();
        JSONArray content = new JSONArray();
        JSONObject textContent = Jsons.objectOf("type", "text", "text", text);
        content.put(textContent);
        Jsons.put(result, "content", content);
        if (structuredContent != null) {
            Jsons.put(result, "structuredContent", structuredContent);
        }
        Jsons.put(result, "isError", false);
        return result;
    }

    public static JSONObject error(String text, JSONObject structuredContent) {
        JSONObject result = text(text, structuredContent);
        Jsons.put(result, "isError", true);
        return result;
    }
}
