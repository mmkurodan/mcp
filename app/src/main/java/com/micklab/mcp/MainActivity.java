package com.micklab.mcp;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.micklab.mcp.runtime.McpRuntimeBootstrap;
import com.micklab.mcp.service.McpForegroundService;

public class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestNotificationPermissionIfNeeded();

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(16);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView titleView = new TextView(this);
        titleView.setText("Android Embedded MCP Server");
        titleView.setTextSize(24);
        root.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("Loopback-only JSON-RPC endpoint with Python, Node, and JNI tool templates.");
        subtitleView.setPadding(0, dpToPx(8), 0, dpToPx(16));
        root.addView(subtitleView);

        Button startButton = new Button(this);
        startButton.setText("Start Foreground MCP Service");
        startButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, McpForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            refreshStatus("Starting embedded MCP service...");
            statusView.postDelayed(this::refreshStatus, 750L);
        });
        root.addView(startButton);

        Button stopButton = new Button(this);
        stopButton.setText("Stop Foreground MCP Service");
        stopButton.setOnClickListener(view -> {
            stopService(new Intent(this, McpForegroundService.class));
            refreshStatus("Stopping embedded MCP service...");
            statusView.postDelayed(this::refreshStatus, 500L);
        });
        root.addView(stopButton);

        statusView = new TextView(this);
        statusView.setPadding(0, dpToPx(16), 0, 0);
        root.addView(statusView);

        setContentView(scrollView);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS
                );
            }
        }
    }

    private void refreshStatus() {
        refreshStatus(null);
    }

    private void refreshStatus(String prefix) {
        McpRuntimeBootstrap bootstrap = McpRuntimeBootstrap.getInstance(getApplicationContext());
        StringBuilder builder = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            builder.append(prefix).append("\n\n");
        }
        builder.append("Port: 127.0.0.1:").append(bootstrap.getMcpPort()).append("\n\n");
        builder.append("Runtime state:\n").append(bootstrap.describeState()).append("\n\n");
        builder.append("Bundled layout:\n").append(bootstrap.describeLayout()).append("\n\n");
        builder.append("JSON-RPC endpoint:\nPOST http://127.0.0.1:")
                .append(bootstrap.getMcpPort())
                .append("/rpc");
        statusView.setText(builder.toString());
    }

    private int dpToPx(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
