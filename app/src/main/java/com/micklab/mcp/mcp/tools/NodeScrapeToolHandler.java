package com.micklab.mcp.mcp.tools;

import com.micklab.mcp.mcp.Jsons;
import com.micklab.mcp.runtime.NodeRuntimeBridge;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NodeScrapeToolHandler implements ToolHandler {
    private final NodeRuntimeBridge nodeRuntimeBridge;

    public NodeScrapeToolHandler(NodeRuntimeBridge nodeRuntimeBridge) {
        this.nodeRuntimeBridge = nodeRuntimeBridge;
    }

    @Override
    public ToolDefinition definition() {
        JSONObject schema = Jsons.objectOf(
                "type", "object",
                "properties", Jsons.objectOf(
                        "url", Jsons.objectOf(
                                "type", "string",
                                "format", "uri",
                                "description", "Remote HTTP or HTTPS URL to parse with axios + cheerio."
                        ),
                        "timeoutMs", Jsons.objectOf(
                                "type", "integer",
                                "minimum", 250,
                                "maximum", 15000,
                                "description", "Outbound fetch timeout in milliseconds."
                        )
                ),
                "required", Jsons.arrayOf("url")
        );
        return new ToolDefinition(
                "node.scrape_title",
                "Extract the first document title and anchor preview through the bundled Node service.",
                schema
        );
    }

    @Override
    public JSONObject handle(JSONObject arguments) throws Exception {
        JSONObject response = nodeRuntimeBridge.scrapeTitle(
                Jsons.requireString(arguments, "url"),
                arguments.optInt("timeoutMs", 3_000)
        );
        return ToolResultFactory.text(
                "Node extracted \"" + response.optString("title") + "\" from " + response.optString("url"),
                response
        );
    }
}
