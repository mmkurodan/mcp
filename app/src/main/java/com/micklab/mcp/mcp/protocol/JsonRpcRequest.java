package com.micklab.mcp.mcp.protocol;

import org.json.JSONException;
import org.json.JSONObject;

public final class JsonRpcRequest {
    private final Object id;
    private final String method;
    private final JSONObject params;

    private JsonRpcRequest(Object id, String method, JSONObject params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }

    public static JsonRpcRequest fromJson(JSONObject jsonObject) throws JSONException {
        if (!"2.0".equals(jsonObject.optString("jsonrpc"))) {
            throw new JSONException("jsonrpc must be 2.0");
        }
        String method = jsonObject.optString("method", null);
        if (method == null || method.isEmpty()) {
            throw new JSONException("method is required");
        }
        Object id = jsonObject.has("id") ? jsonObject.opt("id") : null;
        if (id == JSONObject.NULL) {
            id = null;
        }
        Object paramsObject = jsonObject.opt("params");
        JSONObject params = paramsObject instanceof JSONObject ? (JSONObject) paramsObject : new JSONObject();
        return new JsonRpcRequest(id, method, params);
    }

    public Object getId() {
        return id;
    }

    public String getMethod() {
        return method;
    }

    public JSONObject getParams() {
        return params;
    }

    public boolean isNotification() {
        return id == null;
    }
}
