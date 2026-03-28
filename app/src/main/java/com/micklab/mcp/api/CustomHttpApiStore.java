package com.micklab.mcp.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public final class CustomHttpApiStore {
    private static final String PREFERENCES_NAME = "custom_http_api_store";
    private static final String KEY_API_DEFINITIONS = "api_definitions";

    private final SharedPreferences sharedPreferences;

    public CustomHttpApiStore(Context context) {
        sharedPreferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }

    public synchronized List<CustomHttpApiDefinition> loadAll() throws Exception {
        String rawJson = sharedPreferences.getString(KEY_API_DEFINITIONS, "[]");
        JSONArray array = new JSONArray(rawJson != null ? rawJson : "[]");
        List<CustomHttpApiDefinition> definitions = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            definitions.add(CustomHttpApiDefinition.fromJson(array.getJSONObject(index)));
        }
        return definitions;
    }

    public synchronized void saveAll(List<CustomHttpApiDefinition> definitions) {
        JSONArray array = new JSONArray();
        for (CustomHttpApiDefinition definition : definitions) {
            array.put(definition.toJson());
        }
        boolean committed = sharedPreferences.edit()
                .putString(KEY_API_DEFINITIONS, array.toString())
                .commit();
        if (!committed) {
            throw new IllegalStateException("Unable to persist custom API definitions.");
        }
    }
}
