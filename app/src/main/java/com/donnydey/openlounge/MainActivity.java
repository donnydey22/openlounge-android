package com.donnydey.openlounge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private LinearLayout setupPanel;
    private LinearLayout loadingPanel;
    private LinearLayout errorPanel;
    private TextView errorText;
    private ImageButton gearButton;
    private TextInputEditText urlInput;

    private SessionBridge sessionBridge;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** one-time 0.2.1 → 0.2.3 move from the railway.app origin to openlounge.app (see migrateLegacyOrigin) */
    private boolean migrating = false;
    private int loadRetries = 0;
    private final Runnable retryLoad = this::loadConfiguredUrl;

    private PermissionRequest pendingWebPerm;
    private ValueCallback<Uri[]> fileCallback;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Boolean v : result.values()) {
                    if (v == null || !v) {
                        granted = false;
                        break;
                    }
                }
                if (pendingWebPerm != null) {
                    if (granted) {
                        pendingWebPerm.grant(pendingWebPerm.getResources());
                    } else {
                        pendingWebPerm.deny();
                    }
                    pendingWebPerm = null;
                }
            });

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadConfiguredUrl();
                }
            });

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(
                        result.getResultCode(), result.getData());
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupPanel = findViewById(R.id.setup_panel);
        loadingPanel = findViewById(R.id.loading_panel);
        errorPanel = findViewById(R.id.error_panel);
        errorText = findViewById(R.id.error_text);
        gearButton = findViewById(R.id.btn_gear);
        urlInput = findViewById(R.id.url_input);

        findViewById(R.id.btn_save).setOnClickListener(v -> saveFromSetup());
        findViewById(R.id.btn_retry).setOnClickListener(v -> loadConfiguredUrl());
        findViewById(R.id.btn_change_url).setOnClickListener(v -> openSettings());
        gearButton.setOnClickListener(v -> openSettings());

        setupWebView();

        String url = Prefs.getServerUrl(this);
        if (Prefs.isValidUrl(url)) {
            loadConfiguredUrl();
        } else {
            showSetup();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        // Durable native token store mirrored by src/lib/session.ts (window.OpenLoungeNative)
        sessionBridge = new SessionBridge(this);
        webView.addJavascriptInterface(sessionBridge, SessionBridge.JS_NAME);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Always fetch fresh HTML/JS so "Let's go" fixes ship to the WebView.
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " OpenLoungeAndroid/" + BuildConfig.VERSION_NAME);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // Let the web app control its own theme via Settings toggle
            WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_OFF);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                sessionBridge.setCurrentUrl(url);
                if (!migrating) showLoading();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                sessionBridge.setCurrentUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (migrating) {
                    if (url != null && url.startsWith(Prefs.LEGACY_RAILWAY_URL)) readLegacyOriginSession();
                    return;
                }
                loadRetries = 0;
                showWeb();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                if (migrating) {
                    // can't reach the old origin right now: carry on, try the migration again next launch
                    finishMigration(false);
                    return;
                }
                // No raw network errors on screen: a calm message and a quiet automatic retry.
                showError(getString(R.string.offline_message));
                handler.removeCallbacks(retryLoad);
                long delay = Math.min(30_000L, 2_000L << Math.min(loadRetries++, 4));
                handler.postDelayed(retryLoad, delay);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
                if (web && (Prefs.isTrustedOrigin(uri) || isConfiguredHost(uri))) {
                    return false; // stays in the app
                }
                // everything else (other sites, tel:, mailto:, intents) opens outside the app
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // no-op; loading panel covers initial load
            }
        });
    }

    private void handleWebPermission(PermissionRequest request) {
        List<String> androidPerms = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                androidPerms.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                androidPerms.add(Manifest.permission.CAMERA);
            }
        }
        if (androidPerms.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        List<String> needed = new ArrayList<>();
        for (String p : androidPerms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingWebPerm = request;
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void saveFromSetup() {
        String raw = urlInput.getText() != null ? urlInput.getText().toString() : "";
        if (!Prefs.isValidUrl(raw)) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setServerUrl(this, raw);
        loadConfiguredUrl();
    }

    private boolean isConfiguredHost(Uri uri) {
        Uri conf = Uri.parse(Prefs.getServerUrl(this));
        return conf.getHost() != null && conf.getHost().equalsIgnoreCase(uri.getHost());
    }

    private void loadConfiguredUrl() {
        handler.removeCallbacks(retryLoad);
        webView.clearCache(true); // HTTP cache only — localStorage, cookies and the token store are kept
        CookieManager.getInstance().flush();
        String url = Prefs.getServerUrl(this);
        if (!Prefs.isValidUrl(url)) {
            showSetup();
            return;
        }
        if (!Prefs.originMigrated(this) && !sessionBridge.hasStoredSession()
                && !url.startsWith(Prefs.LEGACY_RAILWAY_URL)) {
            migrateLegacyOrigin();
            return;
        }
        ensureMicPermissionThen(() -> {
            showLoading();
            sessionBridge.setCurrentUrl(url);
            webView.loadUrl(url);
        });
    }

    /**
     * 0.2.1 and earlier opened https://openlounge-production.up.railway.app, so a signed-in user's token lives in
     * THAT origin's localStorage. 0.2.3 opens https://openlounge.app (separate localStorage). Before the first
     * load, open a tiny same-origin page on the old origin (/api/health, plain JSON), read the stored session
     * from its localStorage and seed the native token store, which the web app adopts on launch. Nobody is
     * signed out by the update.
     */
    private void migrateLegacyOrigin() {
        migrating = true;
        showLoading();
        String legacy = Prefs.LEGACY_RAILWAY_URL + "/api/health";
        sessionBridge.setCurrentUrl(legacy);
        webView.loadUrl(legacy);
        handler.postDelayed(() -> {
            if (migrating) finishMigration(false);
        }, 8_000);
    }

    private static final String READ_LEGACY_JS =
            "(function(){try{var g=function(k){try{return localStorage.getItem(k)}catch(e){return null}};"
                    + "var r=g('openlounge:session')||g('lounge:session')||g('clubroom:session');if(!r)return '';"
                    + "var a=null;try{a=JSON.parse(g('openlounge:access')||'null')}catch(e){}"
                    + "return JSON.stringify({v:1,refresh:r,access:(a&&a.t)||null,accessExp:(a&&a.exp)||0,"
                    + "uid:g('openlounge:uid'),savedAt:Number(g('openlounge:saved'))||0})}catch(e){return ''}})()";

    private void readLegacyOriginSession() {
        webView.evaluateJavascript(READ_LEGACY_JS, value -> {
            try {
                // evaluateJavascript hands back a JSON-encoded JS value
                String decoded = value == null || "null".equals(value) ? "" : new JSONArray("[" + value + "]").getString(0);
                if (decoded.length() >= 20) sessionBridge.seedIfEmpty(decoded);
            } catch (Exception ignored) {
            }
            finishMigration(true);
        });
    }

    private void finishMigration(boolean done) {
        if (!migrating) return;
        migrating = false;
        if (done) Prefs.setOriginMigrated(this);
        webView.stopLoading();
        loadConfiguredUrlAfterMigration();
    }

    private void loadConfiguredUrlAfterMigration() {
        String url = Prefs.getServerUrl(this);
        ensureMicPermissionThen(() -> {
            showLoading();
            sessionBridge.setCurrentUrl(url);
            webView.loadUrl(url);
        });
    }

    private void ensureMicPermissionThen(Runnable next) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            next.run();
            return;
        }
        // Ask early so LiveKit getUserMedia is less likely to fail later
        permissionLauncher.launch(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
        });
        // Still proceed; WebChromeClient will re-request if needed
        next.run();
    }

    private void openSettings() {
        settingsLauncher.launch(new Intent(this, SettingsActivity.class));
    }

    private void showSetup() {
        setupPanel.setVisibility(View.VISIBLE);
        loadingPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        gearButton.setVisibility(View.GONE);
        urlInput.setText(Prefs.getServerUrl(this));
    }

    private void showLoading() {
        setupPanel.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        gearButton.setVisibility(View.GONE);
    }

    private void showWeb() {
        setupPanel.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        gearButton.setVisibility(View.GONE); // no developer chrome over the app
    }

    private void showError(String msg) {
        setupPanel.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        gearButton.setVisibility(View.VISIBLE);
        errorText.setText(msg);
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Tell the page the app went to the background / came back (a WebView often keeps reporting "visible"). */
    private void dispatchNativeVisibility(boolean visible) {
        if (webView == null || migrating) return;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('openlounge:native-visibility',{detail:{visible:"
                + visible + "}}))", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        dispatchNativeVisibility(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        dispatchNativeVisibility(true);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            ((FrameLayout) findViewById(R.id.root)).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
