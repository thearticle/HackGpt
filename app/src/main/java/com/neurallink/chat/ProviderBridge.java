package com.neurallink.chat;

import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.HttpsURLConnection;

final class ProviderBridge {
    private static final int RESPONSE_LIMIT = 8_000_000;
    private final MainActivity activity;
    private final WebView web;
    private final CredentialVault vault;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> providers = new LinkedHashMap<>();
    private final Map<String, JSONObject> saved = new LinkedHashMap<>();
    private boolean vaultReady;
    private boolean vaultLoaded;
    private volatile boolean closed;

    ProviderBridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.vault = new CredentialVault(activity);
    }

    private synchronized void readVault() {
        if (vaultLoaded) return;
        try {
            JSONArray entries = vault.load();
            for (int i = 0; i < entries.length(); i++) {
                var item = entries.getJSONObject(i);
                saved.put(item.getString("id"), item);
            }
            providers.putAll(saved);
            vaultReady = true;
        } catch (Exception ignored) {
            saved.clear();
            vaultReady = false;
        }
        vaultLoaded = true;
    }

    @JavascriptInterface public void request(String id, String method, String data) {
        if (closed || id == null || method == null || data == null || !id.matches("[0-9]{1,16}") || data.length() > 12_000_000 || requests.size() >= 12) {
            emit(id, "error", "The request is too large or the app is busy.");
            return;
        }
        if ("pickFiles".equals(method)) {
            activity.launchFilePicker(id);
            return;
        }
        var request = new Request();
        if (requests.putIfAbsent(id, request) != null) return;
        try {
            workers.execute(() -> {
                try {
                    if (request.cancelled) return;
                    readVault();
                    var input = new JSONObject(data);
                    Object result = switch (method) {
                        case "providers" -> list();
                        case "connect" -> connect(input, request);
                        case "save" -> save(input.getString("connection"), true);
                        case "disconnect" -> save(input.getString("connection"), false);
                        case "chat", "chat/stream" -> chat(input, id, request, method.endsWith("/stream"));
                        default -> throw new Failure("Unsupported request.");
                    };
                    if (!request.cancelled) emit(id, "result", result);
                } catch (Exception error) {
                    if (!request.cancelled) emit(id, "error", error instanceof Failure
                            ? error.getMessage() : error instanceof java.net.SocketTimeoutException
                            ? "The provider timed out." : "The provider request could not be completed.");
                } finally {
                    requests.remove(id, request);
                    request.release();
                }
            });
        } catch (RejectedExecutionException error) {
            requests.remove(id, request);
        }
    }

    @JavascriptInterface public void setLightTheme(boolean light) {
        web.post(() -> {
            if (!closed) activity.applyTheme(light);
        });
    }

    @JavascriptInterface public void cancel(String id) {
        var request = requests.get(id);
        if (request != null) request.cancel();
    }

    void filesPicked(String id, android.net.Uri[] uris) {
        if (closed) return;
        try {
            workers.execute(() -> {
                try {
                    emit(id, "result", PickedFiles.read(activity.getContentResolver(), uris));
                } catch (Exception error) {
                    emit(id, "error", error instanceof Failure ? error.getMessage() : "Could not read the selected files.");
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The activity has already closed.
        }
    }

    void filesFailed(String id, String message) {
        emit(id, "error", message);
    }


    void close() {
        closed = true;
        for (var request : requests.values()) request.cancel();
        workers.shutdownNow();
    }

    private void emit(String id, String kind, Object payload) {
        if (closed) return;
        try {
            var event = new JSONObject().put("kind", kind).put("payload", payload == null ? JSONObject.NULL : payload);
            String script = "window.hackGptNativeEvent(" + JSONObject.quote(id) + ","
                    + JSONObject.quote(event.toString()) + ")";
            web.post(() -> {
                if (!closed) web.evaluateJavascript(script, null);
            });
        } catch (Exception ignored) {
            // The bridge only emits simple JSON from the app or configured provider.
        }
    }

    private synchronized JSONArray list() throws Exception {
        var profiles = new JSONArray();
        for (var connection : providers.values()) profiles.put(publicProvider(connection));
        return profiles;
    }

    private JSONObject publicProvider(JSONObject connection) throws Exception {
        var result = new JSONObject();
        for (String field : new String[]{"id", "name", "type", "base", "models"}) result.put(field, connection.get(field));
        synchronized (this) {
            return result.put("saved", saved.containsKey(connection.getString("id")));
        }
    }

    private synchronized JSONObject find(String id) {
        return providers.get(id);
    }

    private synchronized Object save(String id, boolean persist) throws Exception {
        var profile = providers.get(id);
        if (persist && profile == null) throw new Failure("Enter an API key before saving the provider.");
        if (persist != saved.containsKey(id)) {
            if (!vaultReady) throw new Failure("The Android credential vault could not be opened.");
            var next = new LinkedHashMap<>(saved);
            if (persist) next.put(id, profile);
            else next.remove(id);
            try {
                vault.save(new JSONArray(next.values()));
            } catch (Exception ignored) {
                throw new Failure("Could not update the Android credential vault.");
            }
            saved.clear();
            saved.putAll(next);
        }
        if (persist) return publicProvider(profile);
        providers.remove(id);
        return JSONObject.NULL;
    }

    private JSONObject connect(JSONObject input, Request request) throws Exception {
        String type = input.optString("type");
        String base = switch (type) {
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "custom" -> input.optString("base").trim();
            default -> throw new Failure("Choose a provider.");
        };
        URI url;
        try {
            url = URI.create(base);
        } catch (IllegalArgumentException error) {
            throw new Failure("Enter a valid HTTPS API base URL.");
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new Failure("Enter an HTTPS API base URL without credentials or query parameters.");
        }
        base = base.replaceAll("/+$", "");
        String key = input.optString("key").trim();
        var previous = find(input.optString("connection"));
        if (key.isEmpty() && previous != null && base.equals(previous.optString("base"))
                && type.equals(previous.optString("type"))) key = previous.getString("key");
        if (!key.matches("[!-~]{1,4096}")) throw new Failure("Enter a valid API key.");
        String name = switch (type) {
            case "openrouter" -> "OpenRouter";
            case "openai" -> "OpenAI";
            case "gemini" -> "Gemini";
            default -> url.getHost();
        };
        var provider = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
                .put("type", type).put("base", base).put("key", key);
        String listing = "openrouter".equals(type) ? "/models/user" : "/models";
        JSONArray catalog = readJson(open(provider, listing, null, request)).optJSONArray("data");
        if (catalog == null) throw new Failure("The provider returned an unsupported model catalog.");
        var models = new LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < catalog.length(); i++) {
            var model = catalog.optJSONObject(i);
            if (model == null || !(model.opt("id") instanceof String id) || id.isEmpty() || id.length() > 300) continue;
            var architecture = model.optJSONObject("architecture");
            var outputs = architecture == null ? null : architecture.optJSONArray("output_modalities");
            if (outputs != null && !contains(outputs, "text")) continue;
            var modalities = architecture == null ? null : architecture.optJSONArray("input_modalities");
            if (modalities == null) modalities = model.optJSONArray("input_modalities");
            var support = new JSONObject();
            if (modalities != null) {
                boolean image = contains(modalities, "image");
                boolean pdf = contains(modalities, "file") || contains(modalities, "pdf")
                        || (("openai".equals(type) || "openrouter".equals(type)) && image)
                        || ("openrouter".equals(type) && contains(modalities, "text"));
                support.put("image", image).put("pdf", pdf);
            }
            if ("gemini".equals(type)) support.put("pdf", false);
            models.put(id, new JSONObject().put("id", id).put("name", model.optString("name", id)).put("input", support));
        }
        if (models.isEmpty()) throw new Failure("No text models were returned.");
        provider.put("models", new JSONArray(models.values()));
        synchronized (this) {
            if (providers.size() >= 64) throw new Failure("Disconnect an unused provider before adding another.");
            providers.put(provider.getString("id"), provider);
        }
        return publicProvider(provider);
    }

    private static boolean contains(JSONArray values, String target) {
        for (int i = 0; i < values.length(); i++) if (target.equals(values.optString(i))) return true;
        return false;
    }

    private Object chat(JSONObject input, String id, Request request, boolean streaming) throws Exception {
        long start = SystemClock.elapsedRealtime();
        var provider = find(input.optString("connection"));
        if (provider == null) throw new Failure("The provider is disconnected. Open Settings to connect it.");
        var models = provider.getJSONArray("models");
        JSONObject model = null;
        for (int i = 0; i < models.length(); i++) {
            var item = models.getJSONObject(i);
            if (item.getString("id").equals(input.optString("model"))) {
                model = item;
                break;
            }
        }
        if (model == null) throw new Failure("Select a connected model.");
        String type = provider.getString("type");
        var messages = AttachmentParts.encode(input.optJSONArray("messages"), model.optJSONObject("input"), type);
        var payload = new JSONObject().put("model", model.getString("id"))
                .put("messages", messages).put("stream", streaming);
        if (streaming && "openai".equals(type)) {
            payload.put("stream_options", new JSONObject().put("include_usage", true));
        }
        var connection = open(provider, "/chat/completions", payload, request);
        if (!streaming) {
            var value = readJson(connection);
            var choice = choice(value);
            var message = choice == null ? null : choice.optJSONObject("message");
            String reply = message != null && message.opt("content") instanceof String text ? text : "";
            if (reply.trim().isEmpty()) throw new Failure("This model did not return text.");
            return new JSONObject().put("reply", reply);
        }
        var reader = new ReplyReader(id, start);
        String contentType = connection.getContentType();
        boolean isSse = contentType != null && contentType.contains("text/event-stream");
        if (isSse) {
            try (var stream = new BufferedReader(new InputStreamReader(
                    new LimitedStream(connection.getInputStream()), StandardCharsets.UTF_8))) {
                StringBuilder pending = new StringBuilder();
                String line;
                while (!request.cancelled && (line = stream.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (pending.length() != 0) pending.append('\n');
                        pending.append(line.substring(5).trim());
                    } else if (line.isEmpty() && pending.length() != 0) {
                        if (reader.consumeEvent(pending.toString())) break;
                        pending.setLength(0);
                    }
                }
                if (!reader.finished && pending.length() != 0) reader.consumeEvent(pending.toString());
            }
            if (!request.cancelled && !reader.finished) throw new Failure("The provider stream ended before completing the reply.");
        } else {
            var value = readJson(connection);
            var choice = choice(value);
            reader.consume(value, choice == null ? null : choice.optJSONObject("message"));
        }
        if (request.cancelled) return JSONObject.NULL;
        if (!reader.hasText) throw new Failure("This model did not return text. Try another text-chat model.");
        return reader.stats(isSse);
    }

    private static JSONObject choice(JSONObject event) {
        JSONArray choices = event.optJSONArray("choices");
        return choices == null ? null : choices.optJSONObject(0);
    }

    private final class ReplyReader {
        private final String id;
        private final long start;
        private long firstActivity = -1;
        private long firstText = -1;
        private long lastActivity;
        private boolean thinking;
        private boolean hasText;
        private boolean finished;
        private JSONObject usage;

        ReplyReader(String id, long start) {
            this.id = id;
            this.start = start;
        }

        boolean consumeEvent(String data) throws Exception {
            if ("[DONE]".equals(data.trim())) {
                finished = true;
                return true;
            }
            var value = new JSONObject(data);
            var choice = choice(value);
            consume(value, choice == null ? null : choice.optJSONObject("delta"));
            if (choice != null && !choice.isNull("finish_reason")) finished = true;
            return false;
        }

        private boolean hasField(JSONObject object, String field) {
            Object value = object.opt(field);
            return value != null && value != JSONObject.NULL && !"".equals(value);
        }

        void consume(JSONObject event, JSONObject content) throws Exception {
            if (hasField(event, "error")) throw new Failure("The provider reported an error while generating.");
            var reportedUsage = event.optJSONObject("usage");
            if (reportedUsage != null) usage = reportedUsage;
            if (content == null) return;
            Object delta = content.opt("content");
            if (!(delta instanceof String)) delta = content.opt("refusal");
            String text = delta instanceof String string ? string : "";
            var details = content.optJSONArray("reasoning_details");
            boolean reasoning = hasField(content, "reasoning") || hasField(content, "reasoning_content")
                    || (details != null && details.length() > 0);
            if (reasoning || !text.isEmpty()) {
                lastActivity = SystemClock.elapsedRealtime();
                if (firstActivity < 0) firstActivity = lastActivity;
            }
            if (reasoning && !thinking) {
                thinking = true;
                event("thinking", null);
            }
            for (int i = 0; details != null && i < details.length(); i++) {
                var detail = details.optJSONObject(i);
                if (detail != null && "reasoning.summary".equals(detail.optString("type"))
                        && detail.opt("summary") instanceof String summary) event("summary", summary);
            }
            if (!text.isEmpty()) {
                if (firstText < 0) firstText = SystemClock.elapsedRealtime();
                hasText |= !text.trim().isEmpty();
                event("delta", text);
            }
        }

        private void event(String type, String text) throws Exception {
            var event = new JSONObject().put("type", type);
            if (text != null) event.put("text", text);
            emit(id, "event", event);
        }

        JSONObject stats(boolean streaming) throws Exception {
            var stats = new JSONObject().put("durationMs", SystemClock.elapsedRealtime() - start);
            if (streaming && firstText >= 0) stats.put("firstTokenMs", firstText - start);
            if (usage != null) {
                putCount(stats, "inputTokens", usage.opt("prompt_tokens"));
                putCount(stats, "outputTokens", usage.opt("completion_tokens"));
                var extra = usage.optJSONObject("completion_tokens_details");
                if (extra != null) putCount(stats, "reasoningTokens", extra.opt("reasoning_tokens"));
                long amount = stats.optLong("outputTokens", 0);
                long duration = lastActivity - firstActivity;
                if (streaming && amount > 0 && duration >= 100) {
                    stats.put("tokensPerSecond", Math.round(amount * 10_000.0 / duration) / 10.0);
                }
            }
            return stats;
        }

        private void putCount(JSONObject target, String name, Object value) throws Exception {
            if (value instanceof Number count && count.longValue() >= 0) target.put(name, count.longValue());
        }
    }

    private static HttpsURLConnection open(JSONObject provider, String endpoint, JSONObject body, Request request)
            throws Exception {
        var url = URI.create(provider.getString("base") + endpoint).toURL();
        var connection = (HttpsURLConnection) url.openConnection();
        request.assign(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(body == null ? 20_000 : 90_000);
        connection.setRequestProperty("Authorization", "Bearer " + provider.getString("key"));
        connection.setRequestProperty("Accept", body != null && body.optBoolean("stream")
                ? "text/event-stream, application/json" : "application/json");
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (var output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) return connection;
        throw new Failure(switch (status) {
            case 401, 403 -> "The provider rejected the API key or denied access (HTTP " + status + ").";
            case 429 -> "Provider rate limit or quota reached (HTTP 429).";
            default -> "The provider returned HTTP " + status + ". Check the model, endpoint, and account access.";
        });
    }

    private static JSONObject readJson(HttpsURLConnection connection) throws Exception {
        try (var stream = new LimitedStream(connection.getInputStream());
                var output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) output.write(chunk, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static final class LimitedStream extends FilterInputStream {
        private int size;

        LimitedStream(InputStream input) {
            super(input);
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1) count(1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int result = in.read(bytes, offset, length);
            if (result > 0) count(result);
            return result;
        }

        private void count(int length) throws IOException {
            size += length;
            if (size > RESPONSE_LIMIT) throw new Failure("The provider response is too large.");
        }
    }

    private static final class Request {
        private volatile boolean cancelled;
        private HttpsURLConnection connection;

        synchronized void assign(HttpsURLConnection value) throws IOException {
            if (cancelled) {
                value.disconnect();
                throw new IOException("Request stopped");
            }
            connection = value;
        }

        synchronized void cancel() {
            cancelled = true;
            release();
        }

        synchronized void release() {
            if (connection != null) connection.disconnect();
            connection = null;
        }
    }

    static final class Failure extends IOException {
        Failure(String message) {
            super(message);
        }
    }
}
