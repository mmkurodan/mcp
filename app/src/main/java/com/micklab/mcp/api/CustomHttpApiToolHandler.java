package com.micklab.mcp.api;

import com.micklab.mcp.mcp.Jsons;
import com.micklab.mcp.mcp.tools.ToolDefinition;
import com.micklab.mcp.mcp.tools.ToolHandler;
import com.micklab.mcp.mcp.tools.ToolResultFactory;

import org.json.JSONObject;

public final class CustomHttpApiToolHandler implements ToolHandler {
    private final CustomHttpApiDefinition apiDefinition;

    public CustomHttpApiToolHandler(CustomHttpApiDefinition apiDefinition) {
        this.apiDefinition = apiDefinition;
    }

    @Override
    public ToolDefinition definition() {
        JSONObject schema = Jsons.objectOf(
                "type", "object",
                "properties", Jsons.objectOf(
                        "query", Jsons.objectOf(
                                "type", "object",
                                "description", "Optional query string parameters appended to the saved URL."
                        ),
                        "headers", Jsons.objectOf(
                                "type", "object",
                                "description", "Optional HTTP headers merged over the saved defaults."
                        ),
                        "body", Jsons.objectOf(
                                "description", "Optional JSON body sent with POST, PUT, or PATCH requests."
                        ),
                        "timeoutMs", Jsons.objectOf(
                                "type", "integer",
                                "minimum", 250,
                                "maximum", 15000,
                                "description", "Per-call timeout override in milliseconds."
                        )
                )
        );
        return new ToolDefinition(apiDefinition.getToolName(), apiDefinition.getDescription(), schema);
    }

    @Override
    public JSONObject handle(JSONObject arguments) throws Exception {
        JSONObject response = CustomHttpApiInvoker.invoke(apiDefinition, arguments);
        return ToolResultFactory.text(
                "Custom API " + apiDefinition.getToolName()
                        + " returned HTTP " + response.optInt("statusCode") + ".",
                response
        );
    }
}
