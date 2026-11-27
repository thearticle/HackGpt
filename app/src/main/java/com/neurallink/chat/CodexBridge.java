package com.neurallink.chat;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;

final class CodexBridge {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(false)
            .pingInterval(25, TimeUnit.SECONDS)
            .build();

    private final WebView web;
    private final CredentialVault vault;
    private WebSocket socket;

    CodexBridge(WebView web) {
        this.web = web;
        vault = new CredentialVault(web.getContext(), "codex-vault");
    }

    private static final String NOTIFICATION_CHANNEL = "codex-task-status";
    private static final int NOTIFICATION_ID = 4102;

    @JavascriptInterface public void requestTaskNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33
                || !(web.getContext() instanceof android.app.Activity activity)) return;
        web.post(() -> {
            String permission = android.Manifest.permission.POST_NOTIFICATIONS;
            if (activity.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{permission}, NOTIFICATION_ID);
            }
        });
    }

    @JavascriptInterface public void showTaskNotification(String state) {
        web.post(() -> {
            var context = web.getContext();
            var manager = context.getSystemService(android.app.NotificationManager.class);
            if (manager == null) return;
            if ("clear".equals(state)) {
                manager.cancel(NOTIFICATION_ID);
                return;
            }
            if (!manager.areNotificationsEnabled()) return;
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            String title = switch (state == null ? "" : state) {
                case "working" -> "Codex is working";
                case "approval" -> "Codex needs approval";
                case "finished" -> "Codex finished";
                case "failed" -> "Codex task failed";
                case "stopped" -> "Codex task stopped";
                case "unknown" -> "Codex connection lost";
                default -> null;
            };
            if (title == null) return;
            boolean working = "working".equals(state);
            String channelId = working ? NOTIFICATION_CHANNEL + "-progress" : NOTIFICATION_CHANNEL;
            var channel = new android.app.NotificationChannel(channelId,
                    working ? "Codex progress" : "Codex task status", working ? android.app.NotificationManager.IMPORTANCE_LOW : android.app.NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
            var intent = new android.content.Intent(context, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            var open = android.app.PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            var notification = new android.app.Notification.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_neural)
                    .setContentTitle(title)
                    .setContentText("unknown".equals(state) ? "Reconnect to check the task." : "Open HackGpt to view the thread.")
                    .setContentIntent(open)
                    .setOnlyAlertOnce(working)
                    .setOngoing(working)
                    .setAutoCancel(!working)
                    .setVisibility(android.app.Notification.VISIBILITY_PRIVATE);
            manager.notify(NOTIFICATION_ID, notification.build());
        });
    }

    @JavascriptInterface public synchronized String loadConnection() {
        return loadConnectionProfile("codex");
    }

    @JavascriptInterface public synchronized String loadConnectionProfile(String mode) {
        try {
            JSONObject saved = vault.load().optJSONObject(profileIndex(mode));
            if (saved == null) return "{}";
            saved.put("hasToken", !saved.optString("token").isEmpty());
            saved.remove("token");
            return saved.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @JavascriptInterface public synchronized String connect(String endpoint, String token, String project) {
        return connectProfile("codex", endpoint, token, project);
    }

    private int profileIndex(String mode) {
        if ("codex".equals(mode)) return 0;
        if ("antigravity".equals(mode)) return 1;
        throw new IllegalArgumentException("Unknown connection mode");
    }

    @JavascriptInterface public synchronized String connectProfile(String mode, String endpoint, String token, String project) {
        if (endpoint == null || !endpoint.startsWith("wss://")
                || project == null || project.trim().isEmpty()) return "Enter a secure server URL and project folder.";
        HttpUrl url = HttpUrl.parse("https://" + endpoint.substring(6));
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.fragment() != null || url.query() != null) return "Enter a valid secure server URL.";
        try {
            int index = profileIndex(mode);
            JSONArray profiles = vault.load();
            if (token == null || token.isEmpty()) {
                JSONObject saved = profiles.optJSONObject(index);
                if (saved != null && endpoint.equals(saved.optString("endpoint"))) {
                    token = saved.optString("token");
                }
            }
            if (token == null || !token.matches("[!-~]{1,4096}")) {
                return "Enter a connection token for this server.";
            }
            vault.save(profiles.put(index, new JSONObject()
                    .put("endpoint", endpoint).put("token", token).put("project", project)));
        } catch (Exception ignored) {
            return "Could not save the connection on this device.";
        }
        disconnect();
        Request request = new Request.Builder().url(url)
                .header("Authorization", "Bearer " + token).build();
        socket = CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                emit(ws, "open", "");
            }

            @Override public void onMessage(WebSocket ws, String message) {
                emit(ws, "message", message);
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
                emit(ws, "closed", "PC closed the connection.");
            }

            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                emit(ws, "closed", response == null
                        ? "Connection failed. Check the PC, VPN, and TLS certificate."
                        : "Connection rejected (HTTP " + response.code() + ").");
            }
        });
        return null;
    }

    @JavascriptInterface public synchronized boolean send(String message) {
        return socket != null && socket.send(message);
    }

    @JavascriptInterface public synchronized void disconnect() {
        if (socket != null) socket.cancel();
        socket = null;
    }

    private void emit(WebSocket source, String kind, String data) {
        web.post(() -> {
            synchronized (this) {
                if (socket != source) return;
                if ("closed".equals(kind)) socket = null;
                web.evaluateJavascript("window.hackGptCodexEvent("
                        + JSONObject.quote(kind) + "," + JSONObject.quote(data)
                        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")", null);
            }
        });
    }
}
