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
    private static final String LATEST_RELEASE = "https://api.github.com/repos/thearticle/HackGpt/releases/latest";
    private static final String RELEASE_DOWNLOADS = "https://github.com/thearticle/HackGpt/releases/download/";
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
    private volatile String availableUpdate;

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
                    if (!"app-update".equals(method)) readVault();
                    var input = new JSONObject(data);
                    Object result = switch (method) {
                        case "providers" -> list();
                        case "app-update" -> checkAppUpdate(request);
                        case "connect" -> connect(input, request);
                        case "provider-details" -> providerDetails(input.getString("connection"), input.optString("model"), request);
                        case "kenari-balance" -> kenariBalance(input.getString("connection"), request);
                        case "nara-balance" -> naraBalance(input.getString("connection"), request);
                        case "routr-quota" -> routrQuota(input.getString("connection"), input.getString("model"), request);
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

    @JavascriptInterface public void openAppUpdate(String tag) {
        if (closed || tag == null || !tag.equals(availableUpdate)) return;
        web.post(() -> {
            if (!closed && tag.equals(availableUpdate)) activity.openUpdateDownload(downloadUrl(tag));
        });
    }

    private Object checkAppUpdate(Request request) throws Exception {
        String installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        var connection = (HttpsURLConnection) URI.create(LATEST_RELEASE).toURL().openConnection();
        request.assign(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "HackGpt-Android");
        if (connection.getResponseCode() != 200) return JSONObject.NULL;
        var release = readJson(connection);
        availableUpdate = updateTag(release, installed);
        return availableUpdate == null ? JSONObject.NULL : new JSONObject().put("tag", availableUpdate);
    }

    private static String updateTag(JSONObject release, String installed) {
        String tag = release.optString("tag_name");
        if (release.optBoolean("draft") || release.optBoolean("prerelease") || !tag.startsWith("v")
                || !isNewerVersion(tag.substring(1), installed)) return null;
        var assets = release.optJSONArray("assets");
        String expectedName = "HackGpt-" + tag + ".apk";
        for (int i = 0; assets != null && i < assets.length(); i++) {
            var asset = assets.optJSONObject(i);
            if (asset != null && expectedName.equals(asset.optString("name"))
                    && "uploaded".equals(asset.optString("state")) && asset.optLong("size") > 0
                    && downloadUrl(tag).equals(asset.optString("browser_download_url"))) return tag;
        }
        return null;
    }

    private static boolean isNewerVersion(String candidate, String installed) {
        String versionPattern = "(?:0|[1-9][0-9]{0,8})(?:\\.(?:0|[1-9][0-9]{0,8})){2}";
        if (installed == null || !candidate.matches(versionPattern) || !installed.matches(versionPattern)) return false;
        String[] next = candidate.split("\\.");
        String[] current = installed.split("\\.");
        for (int i = 0; i < next.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(next[i]), Integer.parseInt(current[i]));
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    private static String downloadUrl(String tag) {
        return RELEASE_DOWNLOADS + tag + "/HackGpt-" + tag + ".apk";
    }

    @JavascriptInterface public float fullScreenTopInset() {
        return activity.fullScreenTopInset();
    }

    @JavascriptInterface public void saveChatExport(String name, String content, boolean archive) {
        if (closed || content == null || content.length() > 8_000_000
                || !ChatMarkdownArchive.validName(name, archive ? ".zip" : ".md")) return;
        activity.runOnUiThread(() -> {
            if (!closed) activity.saveExport(name, content, archive ? "application/zip" : "text/markdown");
        });
    }

    @JavascriptInterface public void saveReplyImage(String name, String mimeType, String base64) {
        if (closed || mimeType == null || base64 == null || base64.isEmpty() || base64.length() > RESPONSE_LIMIT) return;
        String extension = switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> null;
        };
        if (extension == null || !ChatMarkdownArchive.validName(name, extension)) return;
        activity.runOnUiThread(() -> {
            if (!closed) activity.saveExport(name, base64, mimeType);
        });
    }

    @JavascriptInterface public void playSendHaptic() {
        activity.runOnUiThread(() -> {
            if (!closed) web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        });
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

    @JavascriptInterface public void copyMessageText(String text) {
        if (text == null || text.isEmpty()) return;
        activity.runOnUiThread(() -> {
            if (closed) return;
            var clipboard = activity.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HackGpt message", text));
        });
    }

    @JavascriptInterface public void dismissComposerKeyboard() {
        activity.runOnUiThread(() -> {
            if (!closed) activity.dismissComposerKeyboard();
        });
    }

    @JavascriptInterface public void setAppearanceTheme(String theme) {
        web.post(() -> {
            if (!closed) activity.applyTheme(theme);
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
        if (connection.has("factoryTarget")) result.put("factoryTarget", connection.getJSONObject("factoryTarget"));
        synchronized (this) {
            return result.put("saved", saved.containsKey(connection.getString("id")));
        }
    }

    private static final String DEMO_ID = "builtin-kenari-demo";
    private static final String KENARI_BASE = "https://kenari.id/v1";
    private static final String GEMINI_METADATA_BASE = "https://generativelanguage.googleapis.com/v1beta";
    private static final String ROUTR_ORIGIN = "https://api.routr.cloud";
    private static final String ROUTR_BASE = ROUTR_ORIGIN + "/v1";
    private static final String NARA_ORIGIN = "https://router.bynara.id";
    private static final String NARA_BASE = NARA_ORIGIN + "/v1";
    private static final String[][] DEMO_MODELS = {
            {"beam-1-0:free", "Beam 1.0 Free"},
            {"ernie-5-5-flash-preview:free", "ERNIE 5.5 Flash Preview"},
            {"hy4-flash:free", "Hy4 Flash"},
            {"lfm-3-1-7b:free", "LFM3 1.7B"},
            {"nemotron-3-5-lightning:free", "Nemotron 3.5 Lightning"},
            {"nemotron-3-5-super-vl:free", "Nemotron 3.5 Super VL"}
    };
    private JSONObject demoProvider;
    private boolean demoLoaded;

    private static JSONObject demoModel(String id, String name) throws Exception {
        return new JSONObject().put("id", id).put("name", name)
                .put("input", new JSONObject().put("image", false).put("pdf", false));
    }

    private static JSONArray demoModels() throws Exception {
        var models = new JSONArray();
        for (var entry : DEMO_MODELS) models.put(demoModel(entry[0], entry[1]));
        return models;
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
                                .put("models", demoModels());
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

    private JSONObject routrQuota(String id, String model, Request request) throws Exception {
        var provider = find(id);
        if (provider == null || !ROUTR_BASE.equals(provider.getString("base"))) {
            throw new Failure("Connect a Routr provider first.");
        }
        var account = new JSONObject().put("base", ROUTR_ORIGIN).put("key", provider.getString("key"));
        var quotas = readJson(open(account, "/api/me/model-quotas", null, request)).optJSONArray("quotas");
        var result = new JSONObject();
        for (int i = 0; quotas != null && i < quotas.length(); i++) {
            var quota = quotas.optJSONObject(i);
            if (quota != null && model.equals(quota.optString("model_sku"))) {
                return result.put("balance", quota.opt("available_credits"));
            }
        }
        return result;
    }

    private JSONObject naraBalance(String id, Request request) throws Exception {
        var provider = find(id);
        if (provider == null || !NARA_BASE.equals(provider.getString("base"))) {
            throw new Failure("Connect a NaraRouter provider first.");
        }
        var account = new JSONObject().put("base", NARA_ORIGIN).put("key", provider.getString("key"));
        var body = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "get_balance").put("arguments", new JSONObject()));
        var response = readJson(open(account, "/mcp", body, request));
        var result = response.optJSONObject("result");
        if (result == null || result.optBoolean("isError")) throw new Failure("NaraRouter balance is unavailable.");
        Object amount = naraAvailable(result.optJSONObject("structuredContent"));
        var content = result.optJSONArray("content");
        for (int i = 0; amount == null && content != null && i < content.length(); i++) {
            var part = content.optJSONObject(i);
            if (part == null || !"text".equals(part.optString("type"))) continue;
            try {
                amount = naraAvailable(new JSONObject(part.optString("text")));
            } catch (org.json.JSONException ignored) {
                // Only structured balance values are displayed.
            }
        }
        return new JSONObject().put("balance", amount);
    }

    private static Object naraAvailable(JSONObject data) {
        if (data == null) return null;
        for (String key : new String[]{"available", "availableCredits", "available_credits"}) {
            Object value = data.opt(key);
            if (value instanceof Number || value instanceof String) return value;
        }
        return null;
    }

    private JSONObject providerDetails(String id, String selectedModel, Request request) throws Exception {
        if (DEMO_ID.equals(id)) throw new Failure("Billing is unavailable for Demo.");
        var provider = find(id);
        if (provider == null) throw new Failure("Connect the provider first.");
        String base = provider.getString("base");
        if ((GEMINI_METADATA_BASE + "/openai").equals(base) && selectedModel.matches("(?:models/)?[A-Za-z0-9._-]{1,200}")) {
            String modelId = selectedModel.replaceFirst("^models/", "");
            var connection = new JSONObject().put("base", GEMINI_METADATA_BASE).put("key", provider.getString("key"));
            var model = readJson(open(connection, "/models/" + modelId, null, request));
            var details = new JSONObject().put("id", selectedModel);
            for (String field : new String[]{"inputTokenLimit", "outputTokenLimit"}) {
                if (model.has(field)) details.put(field, model.get(field));
            }
            return new JSONObject().put("kind", "gemini").put("models", new JSONArray().put(details));
        }
        String kind = switch (base) {
            case ROUTR_BASE -> "routr";
            case KENARI_BASE -> "kenari";
            case "https://apimurah.com/v1" -> "apimurah";
            case "https://openrouter.ai/api/v1" -> "openrouter";
            default -> "generic";
        };
        var result = new JSONObject().put("kind", kind);
        var routes = switch (kind) {
            case "routr" -> new String[]{ROUTR_ORIGIN, "/api/me/pricing", "models"};
            case "openrouter" -> new String[]{base, "/models/user", "data"};
            default -> new String[]{base, "/models", "data"};
        };
        var connection = new JSONObject().put("base", routes[0]).put("key", provider.getString("key"));
        try {
            var catalog = readJson(open(connection, routes[1], null, request)).optJSONArray(routes[2]);
            if (catalog != null) {
                var models = new JSONArray();
                for (int i = 0; i < catalog.length(); i++) {
                    var model = catalog.optJSONObject(i);
                    if (model == null) continue;
                    String modelId = model.optString("id", model.optString("model_sku", model.optString("model")));
                    if (modelId.isEmpty() || !selectedModel.isEmpty() && !selectedModel.equals(modelId)) continue;
                    var details = new JSONObject();
                    details.put("id", modelId);
                    for (String field : new String[]{"context_length", "context_limit", "context_window", "input_token_limit",
                            "output_token_limit", "max_output_tokens", "top_provider", "endpoints", "architecture",
                            "input_modalities", "output_modalities", "pricing_lines",
                            "pricing", "input_rate", "cache_rate", "output_rate", "min_charge_credits",
                            "per_request_floor_credits"}) {
                        if (model.has(field)) details.put(field, model.get(field));
                    }
                    models.put(details);
                }
                result.put("models", models);
            }
        } catch (Exception ignored) {
            // Some API keys provide chat access without catalog access.
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
            case "factory" -> "https://api.factory.ai/api/v0";
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
            case "factory" -> "Factory.ai";
            default -> url.getHost();
        };
        var provider = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
                .put("type", type).put("base", base).put("key", key);
        JSONArray catalog;
        if ("factory".equals(type)) {
            var target = input.getJSONObject("factoryTarget");
            String kind = target.optString("kind");
            if (!"environment".equals(kind) && !"computer".equals(kind)) {
                throw new Failure("Choose a Factory environment or computer.");
            }
            String targetId = factoryIdentifier(target.optString("id"));
            factoryJson(provider, "/" + kind + "s/" + targetId, null, request);
            provider.put("factoryTarget", new JSONObject().put("kind", kind).put("id", targetId));
            catalog = new JSONArray().put(new JSONObject().put("id", "droid").put("name", "Droid"));
        } else {
            String listing = "openrouter".equals(type) ? "/models/user" : "/models";
            catalog = readJson(open(provider, listing, null, request)).optJSONArray("data");
        }
        if (catalog == null) throw new Failure("The provider returned an unsupported model catalog.");
        var models = new LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < catalog.length(); i++) {
            var model = catalog.optJSONObject(i);
            if (model == null || !(model.opt("id") instanceof String id) || id.isEmpty() || id.length() > 300) continue;
            var architecture = model.optJSONObject("architecture");
            var outputs = architecture == null ? null : architecture.optJSONArray("output_modalities");
            if (outputs == null) outputs = model.optJSONArray("output_modalities");
            boolean textOutput = outputs == null || contains(outputs, "text");
            boolean imageOutput = outputs != null && contains(outputs, "image");
            var endpoints = model.optJSONArray("endpoints");
            if (KENARI_BASE.equals(base) && endpoints != null
                    && !contains(endpoints, "chat") && !contains(endpoints, "images")) continue;
            boolean imageOnly = imageOutput && !textOutput || (endpoints != null
                    && contains(endpoints, "images") && !contains(endpoints, "chat"));
            String imageEndpoint = imageEndpoint(type, base, id, imageOnly);
            if (outputs != null && !contains(outputs, "text")
                    && !imageOutput && imageEndpoint == null) continue;
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
            if ("factory".equals(type)) support.put("pdf", false).put("image", false);
            var entry = new JSONObject().put("id", id).put("name", model.optString("name", id)).put("input", support);
            if (imageEndpoint != null) {
                entry.put("imageOnly", true);
                entry.put("input", new JSONObject().put("image", false).put("pdf", false).put("text", false));
            } else if (imageOutput) {
                var requested = new JSONArray();
                if (textOutput) requested.put("text");
                entry.put("modalities", requested.put("image"));
            }
            models.put(id, entry);
        }
        if (models.isEmpty()) throw new Failure("No supported models were returned.");
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

    private static String imageEndpoint(String type, String base, String id, boolean imageOnly) {
        if ("openrouter".equals(type) && imageOnly) return "/images";
        if ("openai".equals(type) && id.startsWith("gpt-image-")) return "/images/generations";
        // Saved Kenari catalogs from older builds did not retain image endpoint metadata.
        if (KENARI_BASE.equals(base) && (imageOnly || id.startsWith("nano-banana-"))) return "/images/generations";
        return null;
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
        if (model == null && DEMO_MODELS[0][0].equals(input.optString("model"))
                && KENARI_BASE.equals(provider.getString("base"))) {
            synchronized (this) {
                if (saved.containsKey(provider.getString("id"))) {
                    model = demoModel(DEMO_MODELS[0][0], DEMO_MODELS[0][1]);
                }
            }
        }
        if (model == null) throw new Failure("Select a connected model.");
        String type = provider.getString("type");
        if ("factory".equals(type)) {
            if (!streaming || input.optBoolean("textOnly")) {
                throw new Failure("Open a chat and send a message to start Droid.");
            }
            return factoryChat(provider, input, id, request, start);
        }
        var modalities = model.optJSONArray("modalities");
        boolean imageOnly = model.optBoolean("imageOnly") || (modalities != null && !contains(modalities, "text"));
        String imageEndpoint = imageEndpoint(type, provider.getString("base"), model.getString("id"), imageOnly);
        boolean textOnly = input.optBoolean("textOnly");
        if (textOnly && (imageOnly || imageEndpoint != null)) throw new Failure("This model does not support text replies.");
        if (imageEndpoint != null) return generateImage(provider, model, input, id, request, start, streaming, imageEndpoint);
        var messages = AttachmentParts.encode(input.optJSONArray("messages"), model.optJSONObject("input"), type);
        boolean streamUpstream = streaming && modalities == null;
        var payload = new JSONObject().put("model", model.getString("id"))
                .put("messages", messages).put("stream", streamUpstream);
        if (modalities != null) payload.put("modalities", textOnly ? new JSONArray().put("text") : modalities);
        if (streamUpstream && "openai".equals(type)) {
            payload.put("stream_options", new JSONObject().put("include_usage", true));
        }
        var connection = open(provider, "/chat/completions", payload, request);
        if (!streaming) {
            var value = readJson(connection);
            var choice = choice(value);
            var message = choice == null ? null : choice.optJSONObject("message");
            if (modalities != null) {
                var reader = new ReplyReader(null, start);
                reader.consume(value, message);
                if (reader.imageCount > 0) return new JSONObject().put("reply", "Image generation succeeded.");
            }
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

    private static String factoryIdentifier(String value) throws Failure {
        if (!value.matches("[A-Za-z0-9_-]{1,200}")) throw new Failure("Invalid Factory target or session ID.");
        return value;
    }

    private static JSONObject factoryJson(JSONObject provider, String path, JSONObject body, Request request)
            throws Exception {
        try {
            return readJson(open(provider, path, body, request));
        } finally {
            request.release();
        }
    }

    private Object factoryChat(JSONObject provider, JSONObject input, String id, Request request, long start)
            throws Exception {
        var messages = input.getJSONArray("messages");
        var last = messages.optJSONObject(messages.length() - 1);
        if (last == null || !"user".equals(last.optString("role")) || last.optString("content").trim().isEmpty()) {
            throw new Failure("Enter a text message for Droid.");
        }
        for (int i = 0; i < messages.length(); i++) {
            var files = messages.getJSONObject(i).optJSONArray("files");
            if (files != null && files.length() > 0) throw new Failure("Droid currently accepts text messages only.");
        }
        String sessionId = input.optString("factorySession");
        var selected = provider.getJSONObject("factoryTarget");
        String kind = selected.getString("kind");
        var target = sessionId.isEmpty()
                ? new JSONObject().put("kind", kind).put(kind + "Id", selected.getString("id"))
                : new JSONObject().put("kind", "session").put("sessionId", factoryIdentifier(sessionId));
        String prompt = last.getString("content");
        if (sessionId.isEmpty() && messages.length() > 1) {
            var history = new JSONArray();
            for (int i = 0; i < messages.length() - 1; i++) {
                var message = messages.getJSONObject(i);
                history.put(new JSONObject().put("role", message.optString("role"))
                        .put("content", message.optString("content")));
            }
            prompt = "Conversation context for reference:\n" + history + "\n\nCurrent message:\n" + prompt;
        }
        var started = factoryJson(provider, "/droid/execute",
                new JSONObject().put("prompt", prompt).put("target", target), request);
        sessionId = factoryIdentifier(started.optString("sessionId"));
        String computerId = factoryIdentifier(started.optString("computerId"));
        String promptId = factoryIdentifier(started.optString("messageId"));
        String path = "/sessions/" + sessionId;
        String query = "?computerId=" + computerId;
        long deadline = SystemClock.elapsedRealtime() + 300_000;
        try {
            while (!request.cancelled && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(2500);
                try {
                    var session = factoryJson(provider, path + query, null, request);
                    if (!"idle".equals(session.optString("status"))) continue;
                    String reply = factoryReply(provider, path + "/messages" + query + "&limit=100", promptId, request);
                    if (reply.trim().isEmpty()) continue;
                    for (int offset = 0; offset < reply.length();) {
                        int end = Math.min(offset + 8_000, reply.length());
                        if (end < reply.length() && Character.isHighSurrogate(reply.charAt(end - 1))) end--;
                        emit(id, "event", new JSONObject().put("type", "delta").put("text", reply.substring(offset, end)));
                        offset = end;
                    }
                    return new JSONObject().put("factorySession", sessionId)
                            .put("factoryConnection", provider.getString("id"))
                            .put("durationMs", SystemClock.elapsedRealtime() - start);
                } catch (Failure error) {
                    if (error.status != 404) throw error;
                }
            }
            if (request.cancelled) return JSONObject.NULL;
            throw new Failure("Droid did not finish in time. Check the session in Factory.");
        } finally {
            if (request.cancelled) {
                try {
                    factoryJson(provider, path + "/interrupt", new JSONObject(), new Request());
                } catch (Exception ignored) {
                    // Factory may deny interruption or have already closed the session.
                }
            }
        }
    }

    private static String factoryReply(JSONObject provider, String path, String promptId, Request request)
            throws Exception {
        String cursor = "";
        boolean seenPrompt = false;
        var reply = new StringBuilder();
        for (int page = 0; page < 20; page++) {
            var result = factoryJson(provider, cursor.isEmpty() ? path : path + "&cursor="
                    + android.net.Uri.encode(cursor), null, request);
            var messages = result.getJSONArray("messages");
            for (int i = 0; i < messages.length(); i++) {
                var message = messages.getJSONObject(i);
                if (promptId.equals(message.optString("id"))) {
                    seenPrompt = true;
                    continue;
                }
                if (seenPrompt && "user".equals(message.optString("role"))) return reply.toString();
                if (!seenPrompt || !"assistant".equals(message.optString("role"))) continue;
                var content = message.getJSONArray("content");
                for (int j = 0; j < content.length(); j++) {
                    var part = content.getJSONObject(j);
                    String value = part.optString("text");
                    if (!"text".equals(part.optString("type")) || value.trim().isEmpty()) continue;
                    if (reply.length() > 0) reply.append("\n\n");
                    reply.append(value);
                }
            }
            var pagination = result.optJSONObject("pagination");
            if (pagination == null || !pagination.optBoolean("hasMore")) break;
            cursor = pagination.optString("nextCursor", "");
            if (cursor.isEmpty()) break;
        }
        return reply.toString();
    }

    private Object generateImage(JSONObject provider, JSONObject model, JSONObject input, String id, Request request,
                                 long start, boolean streaming, String endpoint) throws Exception {
        var messages = input.getJSONArray("messages");
        var last = messages.optJSONObject(messages.length() - 1);
        if (last == null || !"user".equals(last.optString("role")) || last.optString("content").trim().isEmpty()) {
            throw new Failure("Enter a prompt for the image.");
        }
        var files = last.optJSONArray("files");
        if (files != null && files.length() != 0) throw new Failure("This image model accepts text prompts only.");
        var payload = new JSONObject().put("model", model.getString("id")).put("prompt", last.getString("content"));
        var reader = new ReplyReader(streaming ? id : null, start);
        var result = readJson(open(provider, endpoint, payload, request));
        reader.consume(result, null);
        reader.consumeImages(result.optJSONArray("data"));
        if (reader.imageCount == 0) throw new Failure("The model returned no supported images.");
        return streaming ? reader.stats(false) : new JSONObject().put("reply", "Image generation succeeded.");
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
        private Object imageQuality;

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
            if (event.opt("quality") instanceof String value && value.length() <= 32) imageQuality = value;
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
                if (part == null) continue;
                var image = part.optJSONObject("image_url");
                String url = image == null ? part.optString("url", part.optString("image_url")) : image.optString("url");
                String base64 = part.optString("b64_json");
                if (!base64.isEmpty()) url = "data:" + part.optString("media_type", "image/png") + ";base64," + base64;
                if (!url.startsWith("https://") && !url.matches("(?s)^data:image/(png|jpeg|gif|webp);base64,.*")) continue;
                imageCount++;
                // WebView bridge calls must stay small even when the provider returns a large image.
                for (int offset = 0; id != null && offset < url.length(); offset += 65_536) {
                    int end = Math.min(offset + 65_536, url.length());
                    event(end == url.length() ? "image" : "image-chunk", url.substring(offset, end));
                }
            }
        }

        private void event(String type, String text) throws Exception {
            if (id == null) return;
            var event = new JSONObject().put("type", type);
            if (text != null) event.put("text", text);
            emit(id, "event", event);
        }

        JSONObject stats(boolean streaming) throws Exception {
            var stats = new JSONObject().put("durationMs", SystemClock.elapsedRealtime() - start);
            if (imageCount > 0 && imageQuality != null) stats.put("imageQuality", imageQuality);
            if (streaming && firstText >= 0) stats.put("firstTokenMs", firstText - start);
            if (usage != null) {
                putCount(stats, "inputTokens", usage.opt("prompt_tokens") != null ? usage.opt("prompt_tokens") : usage.opt("input_tokens"));
                putCount(stats, "outputTokens", usage.opt("completion_tokens") != null ? usage.opt("completion_tokens") : usage.opt("output_tokens"));
                putCount(stats, "totalTokens", usage.opt("total_tokens"));
                putCount(stats, "costMicroIdr", usage.opt("cost_micro_idr"));
                if (usage.opt("cost") instanceof Number cost && cost.doubleValue() >= 0) stats.put("reportedCost", cost);
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
        boolean imageRequest = endpoint.startsWith("/images");
        boolean kenariImage = imageRequest && KENARI_BASE.equals(provider.getString("base"));
        connection.setReadTimeout(body == null ? 20_000 : kenariImage || "/droid/execute".equals(endpoint)
                ? 300_000 : imageRequest ? 180_000 : 90_000);
        if (GEMINI_METADATA_BASE.equals(provider.getString("base"))) {
            connection.setRequestProperty("x-goog-api-key", provider.getString("key"));
        } else {
            connection.setRequestProperty("Authorization", "Bearer " + provider.getString("key"));
        }
        connection.setRequestProperty("Accept", "/mcp".equals(endpoint) || (body != null && body.optBoolean("stream"))
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
            case 402 -> "The provider requires a balance or credits for this request (HTTP 402).";
            case 429 -> "Provider rate limit or quota reached (HTTP 429).";
            default -> "The provider returned HTTP " + status + ". Check the model, endpoint, and account access.";
        }, status);
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
        final int status;

        Failure(String message) {
            this(message, 0);
        }

        Failure(String message, int status) {
            super(message);
            this.status = status;
        }
    }
}
