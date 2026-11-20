package com.neurallink.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;

public final class MainActivity extends Activity {
    private static final String APP_URL = "https://appassets.androidplatform.net/";
    private static final int PICK_FILES = 144;
    private static final int PICK_FILES_FALLBACK = 145;
    private FrameLayout root;
    private WebView web;
    private ProviderBridge bridge;
    private String fileRequest;
    private int fileRequestCode;
    private volatile boolean fullScreen;
    private android.window.OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(this);
        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int bars = fullScreen
                        ? WindowInsets.Type.navigationBars() | WindowInsets.Type.captionBar()
                        : WindowInsets.Type.systemBars();
                var bounds = insets.getInsets(bars | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom);
                return insets;
            });
        }
        var settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (APP_URL.equals(url.toString())) return false;
                if (request.isForMainFrame() && request.hasGesture() && "https".equals(url.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE));
                    } catch (ActivityNotFoundException ignored) {
                        // Keep the chat open if no browser can handle the link.
                    }
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && APP_URL.equals(request.getUrl().toString())
                        && "GET".equals(request.getMethod())) {
                    try {
                        var headers = new HashMap<String, String>();
                        headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self' 'unsafe-inline'; "
                                + "style-src 'unsafe-inline'; img-src data: blob:; base-uri 'none'; form-action 'none'");
                        headers.put("X-Content-Type-Options", "nosniff");
                        return new WebResourceResponse("text/html", "UTF-8", 200, "OK",
                                headers, getAssets().open("index.html"));
                    } catch (IOException ignored) {
                        // Return a local error without requesting the virtual origin over the network.
                    }
                }
                if (!request.isForMainFrame() && "GET".equals(request.getMethod())
                        && (APP_URL + "markdown-it.min.js").equals(request.getUrl().toString())) {
                    try {
                        return new WebResourceResponse("application/javascript", "UTF-8",
                                getAssets().open("markdown-it.min.js"));
                    } catch (IOException ignored) {
                        // Fall back to plain-text chat if the bundled script is unavailable.
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        bridge = new ProviderBridge(this, web);
        web.addJavascriptInterface(bridge, "HackGptAndroid");
        setContentView(root);
        applyTheme(false);
        fullScreen = getPreferences(MODE_PRIVATE).getBoolean("full_screen", false);
        if (fullScreen) applyStatusBarVisibility();
        root.post(root::requestApplyInsets);
        web.loadUrl(APP_URL);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> handleBack(() -> {
                if (isTaskRoot()) moveTaskToBack(true);
                else finishAfterTransition();
            });
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    boolean isFullScreen() {
        return fullScreen;
    }

    void setFullScreen(boolean enabled) {
        fullScreen = enabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("full_screen", enabled).apply();
        applyStatusBarVisibility();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullScreen) applyStatusBarVisibility();
    }

    private void applyStatusBarVisibility() {
        if (Build.VERSION.SDK_INT >= 30) {
            var controller = root.getWindowInsetsController();
            if (controller == null) return;
            int status = WindowInsets.Type.statusBars();
            if (fullScreen) {
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(status);
            } else {
                controller.show(status);
            }
        } else {
            int flag = android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
            if (fullScreen) getWindow().addFlags(flag);
            else getWindow().clearFlags(flag);
        }
        root.requestApplyInsets();
    }

    void applyTheme(boolean light) {
        int color = light ? Color.WHITE : Color.rgb(8, 13, 8);
        root.setBackgroundColor(color);
        web.setBackgroundColor(color);
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            var controller = root.getWindowInsetsController();
            if (controller != null) {
                int flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? flags : 0, flags);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(light
                    ? android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        // Legacy fallback. Android 13 and later use the platform dispatcher above.
        handleBack(super::onBackPressed);
    }

    private void handleBack(Runnable fallback) {
        web.evaluateJavascript("window.hackGptHandleBack && window.hackGptHandleBack()", result -> {
            if (!"true".equals(result)) fallback.run();
        });
    }

    private void pickFiles(String requestId) {
        if (fileRequest != null) {
            bridge.filesFailed(requestId, "A file picker is already open.");
            return;
        }
        fileRequest = requestId;
        fileRequestCode = PICK_FILES;
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_FILES);
        } catch (ActivityNotFoundException unavailable) {
            try {
                fileRequestCode = PICK_FILES_FALLBACK;
                startActivityForResult(intent.setAction(Intent.ACTION_GET_CONTENT), fileRequestCode);
            } catch (ActivityNotFoundException | SecurityException error) {
                fileRequest = null;
                bridge.filesFailed(requestId, "No system file picker is available.");
            }
        } catch (SecurityException error) {
            fileRequest = null;
            bridge.filesFailed(requestId, "Android could not open the file picker.");
        }
    }

    void launchFilePicker(String requestId) {
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing()) pickFiles(requestId);
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != fileRequestCode || fileRequest == null) return;
        String requestId = fileRequest;
        fileRequest = null;
        if (result != RESULT_OK || data == null) {
            bridge.filesPicked(requestId, new Uri[0]);
            return;
        }
        var clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 4) {
            bridge.filesFailed(requestId, "Choose up to 4 files.");
            return;
        }
        var uris = new Uri[clip == null ? 1 : clip.getItemCount()];
        for (int index = 0; index < uris.length; index++) {
            Uri uri = clip == null ? data.getData() : clip.getItemAt(index).getUri();
            if (uri == null || !"content".equals(uri.getScheme())) {
                bridge.filesFailed(requestId, "The picker did not return a readable Android document.");
                return;
            }
            uris[index] = uri;
        }
        bridge.filesPicked(requestId, uris);
    }


    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (bridge != null) bridge.close();
        if (web != null) {
            web.removeJavascriptInterface("HackGptAndroid");
            web.destroy();
        }
        super.onDestroy();
    }
}
