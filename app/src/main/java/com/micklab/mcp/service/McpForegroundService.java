package com.micklab.mcp.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.micklab.mcp.MainActivity;
import com.micklab.mcp.runtime.McpRuntimeBootstrap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McpForegroundService extends Service {
    private static final String TAG = "McpForegroundService";
    private static final String CHANNEL_ID = "mcp-server";
    private static final int NOTIFICATION_ID = 1201;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Initializing MCP runtime..."));
        executorService.execute(() -> {
            try {
                McpRuntimeBootstrap bootstrap = McpRuntimeBootstrap.getInstance(getApplicationContext());
                bootstrap.start();
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify(
                            NOTIFICATION_ID,
                            buildNotification("Listening on 127.0.0.1:" + bootstrap.getMcpPort())
                    );
                }
            } catch (Exception exception) {
                Log.e(TAG, "MCP foreground service failed to start", exception);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify(
                            NOTIFICATION_ID,
                            buildNotification("Startup failed: " + exception.getMessage())
                    );
                }
                stopSelf();
            }
        });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        McpRuntimeBootstrap.getInstance(getApplicationContext()).stop();
        executorService.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Embedded MCP Server",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Hosts the embedded loopback-only MCP JSON-RPC server.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Embedded MCP Server")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
