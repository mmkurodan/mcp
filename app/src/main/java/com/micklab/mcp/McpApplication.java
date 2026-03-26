package com.micklab.mcp;

import android.app.Application;
import android.util.Log;

import com.micklab.mcp.runtime.McpRuntimeBootstrap;

public class McpApplication extends Application {
    private static final String TAG = "McpApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            McpRuntimeBootstrap.getInstance(this).primeNativeLayer();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Native layer priming failed", exception);
        }
    }
}
