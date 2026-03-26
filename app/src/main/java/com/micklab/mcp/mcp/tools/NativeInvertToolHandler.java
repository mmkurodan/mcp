package com.micklab.mcp.mcp.tools;

import android.util.Base64;

import com.micklab.mcp.mcp.Jsons;
import com.micklab.mcp.runtime.NativeImageBridge;
import com.micklab.mcp.security.RuntimeSecurityPolicy;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeInvertToolHandler implements ToolHandler {
    private final NativeImageBridge nativeImageBridge;

    public NativeInvertToolHandler(NativeImageBridge nativeImageBridge) {
        this.nativeImageBridge = nativeImageBridge;
    }

    @Override
    public ToolDefinition definition() {
        JSONObject schema = Jsons.objectOf(
                "type", "object",
                "properties", Jsons.objectOf(
                        "width", Jsons.objectOf(
                                "type", "integer",
                                "minimum", 1,
                                "description", "Image width in pixels."
                        ),
                        "height", Jsons.objectOf(
                                "type", "integer",
                                "minimum", 1,
                                "description", "Image height in pixels."
                        ),
                        "pixelsBase64", Jsons.objectOf(
                                "type", "string",
                                "contentEncoding", "base64",
                                "description", "Single-channel grayscale bytes, width * height bytes long."
                        )
                ),
                "required", Jsons.arrayOf("width", "height", "pixelsBase64")
        );
        return new ToolDefinition(
                "native.invert_grayscale",
                "Process a grayscale image buffer with JNI/C++ for low-latency native execution.",
                schema
        );
    }

    @Override
    public JSONObject handle(JSONObject arguments) {
        int width = RuntimeSecurityPolicy.requirePositive("width", Jsons.requireInt(arguments, "width"));
        int height = RuntimeSecurityPolicy.requirePositive("height", Jsons.requireInt(arguments, "height"));
        byte[] inputBytes = Base64.decode(Jsons.requireString(arguments, "pixelsBase64"), Base64.DEFAULT);
        RuntimeSecurityPolicy.requireExactLength(inputBytes, width * height);

        byte[] outputBytes = nativeImageBridge.invertGrayscale(inputBytes, width, height);

        JSONObject structuredContent = Jsons.objectOf(
                "width", width,
                "height", height,
                "pixelsBase64", Base64.encodeToString(outputBytes, Base64.NO_WRAP),
                "nativeRuntime", Jsons.parseObject(nativeImageBridge.runtimeInfo())
        );
        return ToolResultFactory.text(
                "JNI processed a " + width + "x" + height + " grayscale buffer.",
                structuredContent
        );
    }
}
