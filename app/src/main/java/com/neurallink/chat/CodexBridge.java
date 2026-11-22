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

    @JavascriptInterface public synchronized String loadConnection() {
        try {
            JSONObject saved = vault.load().optJSONObject(0);
            if (saved == null) return "{}";
            saved.put("hasToken", !saved.optString("token").isEmpty());
            saved.remove("token");
            return saved.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @JavascriptInterface public synchronized String connect(String endpoint, String token, String project) {
        if (endpoint == null || !endpoint.startsWith("wss://")
                || project == null || project.trim().isEmpty()) return "Enter a secure server URL and project folder.";
        HttpUrl url = HttpUrl.parse("https://" + endpoint.substring(6));
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.fragment() != null || url.query() != null) return "Enter a valid secure server URL.";
        try {
            if (token == null || token.isEmpty()) {
                JSONObject saved = vault.load().optJSONObject(0);
                if (saved != null && endpoint.equals(saved.optString("endpoint"))) {
                    token = saved.optString("token");
                }
            }
            if (token == null || !token.matches("[!-~]{1,4096}")) {
                return "Enter a connection token for this server.";
            }
            vault.save(new JSONArray().put(new JSONObject()
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
