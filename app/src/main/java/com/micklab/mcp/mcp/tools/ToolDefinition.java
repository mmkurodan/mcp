package com.micklab.mcp.mcp.tools;

import com.micklab.mcp.mcp.Jsons;

import org.json.JSONObject;

public final class ToolDefinition {
    private final String name;
    private final String description;
    private final JSONObject inputSchema;

    public ToolDefinition(String name, String description, JSONObject inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public JSONObject toJson() {
        return Jsons.objectOf(
                "name", name,
                "description", description,
                "inputSchema", inputSchema
        );
    }
}
