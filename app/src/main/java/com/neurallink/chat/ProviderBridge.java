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
                        case "provider-details" -> providerDetails(input.getString("connection"), request);
                        case "kenari-balance" -> kenariBalance(input.getString("connection"), request);
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

    @JavascriptInterface public boolean isFullScreen() {
        return activity.isFullScreen();
    }

    @JavascriptInterface public void playCreditsHaptic() {
        activity.runOnUiThread(() -> {
            if (!closed) web.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        });
    }

    @JavascriptInterface public void setFullScreen(boolean enabled) {
        web.post(() -> {
            if (!closed) activity.setFullScreen(enabled);
        });
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
                    emit(id, "error", error instanceof Failure ? error.getMessage()
                            : error instanceof SecurityException ? "Android denied access to this file. Choose it again from the file picker."
                            : "Could not read the selected files.");
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
        var demo = find(DEMO_ID);
        if (demo != null) profiles.put(publicProvider(demo).put("builtInDemo", true));
        return profiles;
    }

    private JSONObject publicProvider(JSONObject connection) throws Exception {
        var result = new JSONObject();
        for (String field : new String[]{"id", "name", "type", "base", "models"}) result.put(field, connection.get(field));
        synchronized (this) {
            return result.put("saved", saved.containsKey(connection.getString("id")));
        }
    }

    private static final String DEMO_ID = "builtin-kenari-demo";
    private static final String KENARI_BASE = "https://kenari.id/v1";
    private static final String DEMO_BEAM = "beam-1-0:free";
    private JSONObject demoProvider;
    private boolean demoLoaded;

    private static JSONObject demoBeamModel() throws Exception {
        return new JSONObject().put("id", DEMO_BEAM).put("name", "Beam 1.0 Free")
                .put("input", new JSONObject().put("image", false).put("pdf", false));
    }

    private synchronized JSONObject find(String id) {
        if (DEMO_ID.equals(id)) {
            if (!demoLoaded) {
                demoLoaded = true;
                try (var reader = new BufferedReader(new InputStreamReader(
                        activity.getAssets().open("demo-kenari.key"), StandardCharsets.US_ASCII))) {
                    String key = reader.readLine();
                    if (key != null && key.matches("kn-[A-Za-z0-9_-]{1,4096}")) {
                        demoProvider = new JSONObject().put("id", DEMO_ID).put("name", "Demo")
                                .put("type", "custom").put("base", KENARI_BASE).put("key", key)
                                .put("models", new JSONArray().put(demoBeamModel()));
                    }
                } catch (Exception ignored) {
                    // Standard builds omit the private demo asset.
                }
            }
            return demoProvider;
        }
        return providers.get(id);
    }

    private JSONObject kenariBalance(String id, Request request) throws Exception {
        if (DEMO_ID.equals(id)) throw new Failure("Demo has no balance display.");
        var provider = find(id);
        if (provider == null || !KENARI_BASE.equals(provider.getString("base"))) {
            throw new Failure("Connect a Kenari provider first.");
        }
        var account = readJson(open(provider, "/account/balance", null, request));
        return new JSONObject().put("balance", account.opt("available_micro_idr"));
    }

    private JSONObject providerDetails(String id, Request request) throws Exception {
        if (DEMO_ID.equals(id)) throw new Failure("Billing is unavailable for Demo.");
        var provider = find(id);
        if (provider == null) throw new Failure("Connect the provider first.");
        String base = provider.getString("base");
        String kind = switch (base) {
            case "https://api.routr.cloud/v1" -> "routr";
            case KENARI_BASE -> "kenari";
            case "https://apimurah.com/v1" -> "apimurah";
            case "https://openrouter.ai/api/v1" -> "openrouter";
            default -> "generic";
        };
        var result = new JSONObject().put("kind", kind);
        var routes = switch (kind) {
            case "routr" -> new String[]{"https://api.routr.cloud", "/api/me/pricing", "/api/me/wallet", "models", "available_credits"};
            case "kenari" -> new String[]{base, "/models", "/account/balance", "data", "available_micro_idr"};
            case "openrouter" -> new String[]{base, "/models/user", "/key", "data", "limit_remaining"};
            default -> new String[]{base, "/models", "", "data", ""};
        };
        var connection = new JSONObject().put("base", routes[0]).put("key", provider.getString("key"));
        try {
            var catalog = readJson(open(connection, routes[1], null, request)).optJSONArray(routes[3]);
            if (catalog != null) {
                var models = new JSONArray();
                for (int i = 0; i < catalog.length(); i++) {
                    var model = catalog.optJSONObject(i);
                    if (model == null || !(model.opt("id") instanceof String)) continue;
                    var details = new JSONObject();
                    for (String field : new String[]{"id", "context_length", "context_limit", "input_token_limit",
                            "pricing", "input_rate", "cache_rate", "output_rate", "min_charge_credits",
                            "per_request_floor_credits"}) {
                        if (model.has(field)) details.put(field, model.get(field));
                    }
                    models.put(details);
                }
                result.put("models", models);
            }
        } catch (Exception ignored) {
            // Account information can still be available when catalog access fails.
        } finally {
            request.release();
        }
        if (!routes[2].isEmpty() && !request.cancelled) {
            try {
                var account = readJson(open(connection, routes[2], null, request));
                if ("openrouter".equals(kind)) account = account.optJSONObject("data");
                if (account != null && account.has(routes[4])) result.put("balance", account.get(routes[4]));
            } catch (Exception ignored) {
                // Some API keys can read the model catalog but cannot read balances.
            }
        }
        return result;
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
        if (key.isEmpty() && previous != null && !DEMO_ID.equals(previous.optString("id")) && base.equals(previous.optString("base"))
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
            if (outputs != null && !contains(outputs, "text")
                    && !("openrouter".equals(type) && contains(outputs, "image"))) continue;
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
            var entry = new JSONObject().put("id", id).put("name", model.optString("name", id)).put("input", support);
            if ("openrouter".equals(type) && outputs != null && contains(outputs, "image")) {
                var requested = new JSONArray();
                if (contains(outputs, "text")) requested.put("text");
                entry.put("modalities", requested.put("image"));
            }
            models.put(id, entry);
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
        if (model == null && DEMO_BEAM.equals(input.optString("model"))
                && KENARI_BASE.equals(provider.getString("base"))) {
            synchronized (this) {
                if (saved.containsKey(provider.getString("id"))) model = demoBeamModel();
            }
        }
        if (model == null) throw new Failure("Select a connected model.");
        String type = provider.getString("type");
        var messages = AttachmentParts.encode(input.optJSONArray("messages"), model.optJSONObject("input"), type);
        var payload = new JSONObject().put("model", model.getString("id"))
                .put("messages", messages).put("stream", streaming);
        if (streaming && model.has("modalities")) payload.put("modalities", model.getJSONArray("modalities"));
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
        if (!reader.hasText && reader.imageCount == 0) throw new Failure("The model returned no supported text or images.");
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
        private int imageCount;
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
            consumeImages(content.optJSONArray("images"));
            consumeImages(content.optJSONArray("content"));
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

        private void consumeImages(JSONArray parts) throws Exception {
            for (int i = 0; parts != null && i < parts.length() && imageCount < 4; i++) {
                var part = parts.optJSONObject(i);
                var image = part == null ? null : part.optJSONObject("image_url");
                String url = image == null ? "" : image.optString("url");
                if (!url.startsWith("https://") && !url.matches("(?s)^data:image/(png|jpeg|gif|webp);base64,.*")) continue;
                imageCount++;
                event("image", url);
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
