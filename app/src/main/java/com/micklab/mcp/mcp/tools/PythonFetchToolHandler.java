package com.micklab.mcp.mcp.tools;

import com.micklab.mcp.mcp.Jsons;
import com.micklab.mcp.runtime.PythonRuntimeBridge;

import org.json.JSONArray;
import org.json.JSONObject;

public final class PythonFetchToolHandler implements ToolHandler {
    private final PythonRuntimeBridge pythonRuntimeBridge;

    public PythonFetchToolHandler(PythonRuntimeBridge pythonRuntimeBridge) {
        this.pythonRuntimeBridge = pythonRuntimeBridge;
    }

    @Override
    public ToolDefinition definition() {
        JSONObject schema = Jsons.objectOf(
                "type", "object",
                "properties", Jsons.objectOf(
                        "url", Jsons.objectOf(
                                "type", "string",
                                "format", "uri",
                                "description", "Remote HTTP or HTTPS URL to fetch with requests."
                        ),
                        "timeoutMs", Jsons.objectOf(
                                "type", "integer",
                                "minimum", 250,
                                "maximum", 15000,
                                "description", "Request timeout in milliseconds."
                        ),
                        "headers", Jsons.objectOf(
                                "type", "object",
                                "description", "Optional outbound HTTP headers."
                        )
                ),
                "required", Jsons.arrayOf("url")
        );
        return new ToolDefinition(
                "python.fetch_url",
                "Fetch a remote page through the bundled Python requests + pydantic service.",
                schema
        );
    }

    @Override
    public JSONObject handle(JSONObject arguments) throws Exception {
        JSONObject response = pythonRuntimeBridge.fetchUrl(
                Jsons.requireString(arguments, "url"),
                arguments.optInt("timeoutMs", 3_000),
                arguments.optJSONObject("headers")
        );
        return ToolResultFactory.text(
                "Python fetched " + response.optInt("status_code") + " from " + response.optString("url"),
                response
        );
    }
}
