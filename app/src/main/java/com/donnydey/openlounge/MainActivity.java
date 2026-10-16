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
import androidx.core.splashscreen.SplashScreen;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.view.HapticFeedbackConstants;
import android.content.res.Configuration;
import android.graphics.Color;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SessionBridge.Host, NativeCallMedia.Host {
    private NativePhoneAuth phoneAuth;

    @Override
    public void phoneAuthStart(String reqId, String e164) {
        if (phoneAuth == null) phoneAuth = new NativePhoneAuth(this, webView);
        phoneAuth.start(reqId, e164);
    }

    @Override
    public void phoneAuthConfirm(String reqId, String code) {
        if (phoneAuth == null) phoneAuth = new NativePhoneAuth(this, webView);
        phoneAuth.confirm(reqId, code);
    }

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
    // 0.3.1 calls: the live activity (for notification Hang up / Decline fallback) + call audio state
    private static WeakReference<MainActivity> live = new WeakReference<>(null);
    private volatile boolean pageLoaded = false;
    /** Main activity is resumed; only then may an FCM payload be exposed to the already-visible WebView. */
    private volatile boolean activityForeground = false;
    private boolean callAudioOn = false;
    private boolean callSpeaker = true;
    private String callId = null;
    private String callName = null;
    private AudioManager callAudioManager;
    private int modeBeforeCall = AudioManager.MODE_NORMAL;
    private boolean callDeviceCallbackRegistered = false;
    private final AudioDeviceCallback callDeviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            handler.post(() -> { if (callAudioOn) applyCallRoute(); });
        }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            handler.post(() -> { if (callAudioOn) applyCallRoute(); });
        }
    };
    private int loadRetries = 0;
    private final Runnable retryLoad = this::loadConfiguredUrl;

    private PermissionRequest pendingWebPerm;
    private ValueCallback<Uri[]> fileCallback;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (java.util.Map.Entry<String, Boolean> e : result.entrySet()) {
                    // Bluetooth is optional: denying it must never block the microphone/camera.
                    if (Manifest.permission.BLUETOOTH_CONNECT.equals(e.getKey())) continue;
                    Boolean v = e.getValue();
                    if (v == null || !v) {
                        granted = false;
                        break;
                    }
                }
                if (callAudioOn) applyCallRoute();
                if (pendingWebPerm != null) {
                    if (granted) {
                        pendingWebPerm.grant(pendingWebPerm.getResources());
                    } else {
                        pendingWebPerm.deny();
                    }
                    pendingWebPerm = null;
                }
            });

    /** deep link from a tapped notification, applied once the WebView is ready */
    private String pendingOpenPath = null;

    private final ActivityResultLauncher<String> contactsPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) readContactsAsync();
                else deliverContacts("{\"granted\":false}");
            });

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

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

    private LaunchOverlay launchOverlay;

    // ---- opt-in biometric app lock (BiometricLock) ----
    private View lockOverlay;
    private View lockActions;
    private TextView lockMessage;
    private boolean locked = false;
    /** Set when opening /call/…?accept=1 so showLock is skipped until the call connects. */
    private boolean unlockForCall = false;
    private boolean prompting = false;
    /** elapsedRealtime when the app went to the background; 0 = cold start */
    private long backgroundedAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Static native splash = plain brand background (no icon); the wordmark animation is LaunchOverlay.
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setOnExitAnimationListener(v -> v.remove()); // hand off instantly: overlay has the same bg
        super.onCreate(savedInstanceState);
        live = new WeakReference<>(this);
        setContentView(R.layout.activity_main);
        watchNetworkHandoff();
        EdgeToEdge.apply(this, findViewById(R.id.root)); // 0.3.0: target SDK 36 (edge-to-edge enforced on Android 15+)
        // 0.3.0: predictive back (default on for target 36) never calls onBackPressed(); use the dispatcher
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (locked) { moveTaskToBack(true); return; }
                if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        pendingOpenPath = openPathFrom(getIntent());
        OpenLoungeMessagingService.ensureChannels(this);
        PushTokens.setListener(token -> handler.post(() -> {
            if (webView != null && !migrating)
                webView.evaluateJavascript("window.__olPushToken&&window.__olPushToken(" + JSONObject.quote(token) + ")", null);
        }));
        PushTokens.refresh(this);
        launchOverlay = new LaunchOverlay(findViewById(R.id.launch_overlay), findViewById(R.id.launch_word));
        launchOverlay.start();

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

        lockOverlay = findViewById(R.id.lock_overlay);
        lockActions = findViewById(R.id.lock_actions);
        lockMessage = findViewById(R.id.lock_message);
        findViewById(R.id.btn_unlock).setOnClickListener(v -> promptUnlock(false));
        findViewById(R.id.btn_unlock_other).setOnClickListener(v -> promptUnlock(true));
        // cold start: lock before anything from the WebView is visible (the page still loads behind the overlay).
        // Incoming-call Accept deep links bypass the lock so the user can join immediately.
        if (CallLockPolicy.isCallAcceptPath(pendingOpenPath)) {
            unlockForCall = true;
        } else if (BiometricLock.shouldLock(BiometricLock.isEnabled(this), 0, 0, BiometricLock.LOCK_AFTER_MS)) {
            showLock();
        }

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
        sessionBridge = new SessionBridge(this, this);
        // 0.3.16 native call media: process-wide (survives page reloads); this activity is its page relay
        NativeCallMedia.init(this);
        NativeCallMedia.setHost(this);
        // native feel: theme-colored background (no white flash before the page paints), no overscroll glow
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO;
        webView.setBackgroundColor(Color.parseColor(dark ? "#0b0b0d" : "#f4f3ee"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(sessionBridge, SessionBridge.JS_NAME);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                java.util.Set<String> origins = new java.util.HashSet<>(java.util.Arrays.asList(
                        "https://openlounge.app", "https://www.openlounge.app", Prefs.LEGACY_RAILWAY_URL));
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, SessionBridge.BIOMETRICS_SHIM, origins);
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, SessionBridge.CALLS_FLAG, origins);
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, SessionBridge.NATIVE_MEDIA_SHIM, origins);
            } catch (Exception ignored) {
            }
        }

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
        s.setUserAgentString(s.getUserAgentString()
                + " OpenLounge/android/" + BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")"
                + " OpenLoungeAndroid/" + BuildConfig.VERSION_NAME);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // Let the web app control its own theme via Settings toggle
            WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_OFF);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                sessionBridge.setCurrentUrl(url);
                pageLoaded = false;
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
                view.evaluateJavascript(SessionBridge.BIOMETRICS_SHIM, null); // no-op if already defined
                view.evaluateJavascript(SessionBridge.CALLS_FLAG, null);
                view.evaluateJavascript(SessionBridge.NATIVE_MEDIA_SHIM, null);
                pageLoaded = true;
                showWeb();
                launchOverlay.markReady();
                scheduleFsiPrompt();
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
                // 0.3.0: other sites open in a Chrome Custom Tab over the app; tel:, mailto:, … go to the system
                InAppBrowser.open(MainActivity.this, uri);
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
        // only our own site may use the mic/camera
        if (request.getOrigin() == null || !isConfiguredHost(request.getOrigin())) {
            request.deny();
            return;
        }
        List<String> androidPerms = new ArrayList<>();
        boolean wantsRoomAudio = false;
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                androidPerms.add(Manifest.permission.RECORD_AUDIO);
                // Headset mics (BT SCO / LE audio) need BLUETOOTH_CONNECT at runtime on Android 12+.
                if (Build.VERSION.SDK_INT >= 31) androidPerms.add(Manifest.permission.BLUETOOTH_CONNECT);
                wantsRoomAudio = true;
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                androidPerms.add(Manifest.permission.CAMERA);
            }
        }
        if (wantsRoomAudio && callId == null) roomAudio(true);
        wantsRoomAudio = false;
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
            String target = withPendingPath(url);
            sessionBridge.setCurrentUrl(target);
            webView.loadUrl(target);
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
            String target = withPendingPath(url);
            sessionBridge.setCurrentUrl(target);
            webView.loadUrl(target);
        });
    }

    private void ensureMicPermissionThen(Runnable next) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            next.run();
            return;
        }
        // Store compliance (Play User Data / Apple 5.1.1(iv)): no mic/camera prompt at launch. The OS prompt is shown
        // in context, when the user first speaks / joins a call (WebChromeClient.onPermissionRequest → handleWebPermission).
        next.run();
    }

    private void openSettings() {
        settingsLauncher.launch(new Intent(this, SettingsActivity.class));
    }

    private void showSetup() {
        if (launchOverlay != null) launchOverlay.markReady();
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
        if (launchOverlay != null) launchOverlay.markReady();
        setupPanel.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        gearButton.setVisibility(View.VISIBLE);
        errorText.setText(msg);
    }

    // ---------- 0.2.5: notifications deep link, push token, share, contacts, haptics ----------

    private static String openPathFrom(Intent i) {
        if (i == null) return null;
        Uri data = i.getData();
        if (data != null && "openlounge".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            // OAuth return from the Custom Tab → the web app's /auth/complete (it redeems the code with its PKCE verifier)
            String q = data.getEncodedQuery();
            return (q == null || q.length() > 2000) ? "/auth/complete" : "/auth/complete?" + q;
        }
        String p = i.getStringExtra(OpenLoungeMessagingService.EXTRA_OPEN_PATH);
        // relative in-app paths only ("/room/x", "/messages/u_1") — never another origin
        if (p == null || !p.startsWith("/") || p.startsWith("//") || p.contains("\\") || p.length() > 300) return null;
        return p;
    }

    private String withPendingPath(String base) {
        String p = pendingOpenPath;
        pendingOpenPath = null;
        if (p == null) return base;
        bypassLockForCallPath(p);
        Uri b = Uri.parse(base);
        return b.getScheme() + "://" + b.getEncodedAuthority() + p;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String p = openPathFrom(intent);
        if (p == null || webView == null) return;
        bypassLockForCallPath(p);
        String base = Prefs.getServerUrl(this);
        if (!Prefs.isValidUrl(base) || migrating) {
            pendingOpenPath = p;
            return;
        }
        // client-side navigation keeps the socket/session alive; full load as fallback
        // Accept on a warm page: accept in place (no navigation/reboot; same as iOS 0.3.11) — web __olAcceptCall
        String acceptId = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/call/([A-Za-z0-9-]{8,64})\\?accept=1").matcher(p);
        if (m.find()) acceptId = m.group(1);
        String pre = acceptId == null ? "" : "try{if(window.__olAcceptCall&&window.__olAcceptCall(" + JSONObject.quote(acceptId) + "))return 1}catch(e){}";
        String js = "(function(){" + pre + "try{if(window.next&&window.next.router){window.next.router.push(" + JSONObject.quote(p) + ");return 1}}catch(e){}location.assign("
                + JSONObject.quote(p) + ");return 1})()";
        webView.evaluateJavascript(js, null);
    }

    @Override
    public String pushToken() {
        return PushTokens.get(this);
    }

    @Override
    public void share(String text, String url) {
        handler.post(() -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, (text + " " + url).trim());
            startActivity(Intent.createChooser(send, getString(R.string.share_title)));
        });
    }

    @Override
    public String contactsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                ? "granted"
                : (getSharedPreferences("ol_perm", MODE_PRIVATE).getBoolean("contactsAsked", false)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) ? "denied" : "prompt";
    }

    /** The web app shows its explainer first; only then calls this, which shows the OS prompt if needed. */
    @Override
    public void requestContacts() {
        handler.post(() -> {
            if ("granted".equals(contactsPermission())) readContactsAsync();
            else {
                getSharedPreferences("ol_perm", MODE_PRIVATE).edit().putBoolean("contactsAsked", true).apply();
                contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS); // if permanently denied this returns false at once → page shows Open Settings
            }
        });
    }

    private void readContactsAsync() {
        new Thread(() -> {
            String country = countryIso();
            Map<String, List<String>> byName = new LinkedHashMap<>();
            try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, null)) {
                while (c != null && c.moveToNext()) { // no cap: the page batches matching
                    String name = c.getString(0);
                    String num = c.getString(1);
                    if (num == null) continue;
                    String e164 = PhoneNumberUtils.formatNumberToE164(num, country);
                    List<String> l = byName.get(name == null ? "" : name);
                    if (l == null) byName.put(name == null ? "" : name, l = new ArrayList<>());
                    l.add(e164 != null ? e164 : num);
                }
            } catch (Exception ignored) {
            }
            try {
                JSONArray arr = new JSONArray();
                for (Map.Entry<String, List<String>> e : byName.entrySet())
                    arr.put(new JSONObject().put("name", e.getKey()).put("numbers", new JSONArray(e.getValue())));
                deliverContacts(new JSONObject().put("granted", true).put("country", country).put("accessPrivileges", "all").put("contacts", arr).toString());
            } catch (Exception e) {
                deliverContacts("{\"granted\":false}");
            }
        }).start();
    }

    private String countryIso() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String iso = tm != null ? tm.getSimCountryIso() : null;
            if (iso != null && iso.length() == 2) return iso.toUpperCase(Locale.US);
        } catch (Exception ignored) {
        }
        String c = Locale.getDefault().getCountry();
        return c == null || c.isEmpty() ? "US" : c.toUpperCase(Locale.US);
    }

    /** Contacts stay on the device side of the bridge; the page hashes numbers before anything is uploaded. */
    private void deliverContacts(String json) {
        handler.post(() -> {
            if (webView != null) webView.evaluateJavascript("window.__olContacts&&window.__olContacts(" + JSONObject.quote(json) + ")", null);
        });
    }

    @Override
    public void openAppSettings() {
        handler.post(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }

    @Override
    public void requestNotificationPermission() {
        handler.post(() -> {
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            PushTokens.refresh(this);
        });
    }

    @Override
    public void openAuth(String url) {
        handler.post(() -> {
            Uri u = Uri.parse(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || !(Prefs.isTrustedOrigin(u) || isConfiguredHost(u))
                    || u.getPath() == null || !u.getPath().matches("/(api/)?auth/(google|apple|link)/.*")) return;
            try {
                new androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, u);
            } catch (Exception e) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void setBadge(int count) {
        AppBadge.set(getApplicationContext(), count);
        if (count == 0) {
            // everything read: drop the DM notifications still in the shade
            try { androidx.core.app.NotificationManagerCompat.from(this).cancelAll(); } catch (Exception ignored) {}
        }
    }

    // ---------- biometric app lock ----------

    private void showLock() {
        if (unlockForCall) return; // Accept /call/…?accept=1 is in flight — never cover the call UI
        locked = true;
        lockMessage.setText(R.string.lock_locked);
        lockActions.setVisibility(View.GONE);
        lockOverlay.setVisibility(View.VISIBLE);
        if (webView != null) webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dispatchNativeLock(true);
        dispatchNativeVisibility(false); // presence:visible false → server sends FCM CallStyle ring
    }

    private void hideLock() {
        locked = false;
        unlockForCall = false;
        lockOverlay.setVisibility(View.GONE);
        if (webView != null) webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        dispatchNativeLock(false);
        if (activityForeground) dispatchNativeVisibility(true);
    }

    /** Tell the page the App Lock overlay is up/down (openlounge:native-lock). */
    private void dispatchNativeLock(boolean lockedNow) {
        if (webView == null || migrating) return;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('openlounge:native-lock',{detail:{locked:"
                + lockedNow + "}}))", null);
    }

    /** Clear App Lock for an incoming-call deep link so Accept can join immediately. */
    private void bypassLockForCallPath(String path) {
        if (!CallLockPolicy.isCallAcceptPath(path)) return;
        unlockForCall = true;
        if (locked || (lockOverlay != null && lockOverlay.getVisibility() == View.VISIBLE)) hideLock();
    }

    private void promptUnlock(boolean otherWay) {
        if (prompting) return;
        if (!BiometricLock.isAvailable(this)) { // device security was removed: never lock the user out
            BiometricLock.setEnabledFlag(this, false);
            hideLock();
            return;
        }
        prompting = true;
        lockActions.setVisibility(View.GONE);
        BiometricLock.prompt(this, getString(R.string.lock_prompt_title), null, otherWay, (status, msg) -> {
            prompting = false;
            if ("success".equals(status)) {
                hideLock();
            } else if ("unavailable".equals(status) && !BiometricLock.isAvailable(this)) {
                BiometricLock.setEnabledFlag(this, false);
                hideLock();
            } else {
                // cancelled / failed: stay locked, offer retry and "Use another way" (device PIN / pattern / password)
                lockMessage.setText("cancelled".equals(status) ? getString(R.string.lock_locked) : getString(R.string.lock_failed));
                lockActions.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public boolean biometricsAvailable() {
        return BiometricLock.isAvailable(this);
    }

    @Override
    public boolean biometricsEnabled() {
        return BiometricLock.isEnabled(this);
    }

    @Override
    public void biometricsSetEnabled(String reqId, boolean on) {
        handler.post(() -> {
            if (!on) {
                BiometricLock.setEnabledFlag(this, false);
                deliverBio(reqId, false);
                return;
            }
            if (prompting) { deliverBio(reqId, BiometricLock.isEnabled(this)); return; }
            prompting = true;
            // turning it on requires a successful prompt first
            BiometricLock.prompt(this, getString(R.string.lock_prompt_enable), null, false, (status, msg) -> {
                prompting = false;
                boolean ok = "success".equals(status);
                if (ok) BiometricLock.setEnabledFlag(this, true);
                deliverBio(reqId, BiometricLock.isEnabled(this));
            });
        });
    }

    @Override
    public void biometricsAuthenticate(String reqId) {
        handler.post(() -> {
            if (prompting || !BiometricLock.isAvailable(this) || !BiometricLock.isEnabled(this)) { deliverBio(reqId, false); return; }
            prompting = true;
            BiometricLock.prompt(this, getString(R.string.lock_prompt_title), null, false, (status, msg) -> {
                prompting = false;
                deliverBio(reqId, "success".equals(status));
            });
        });
    }

    private void deliverBio(String reqId, boolean enabled) {
        if (webView != null)
            webView.evaluateJavascript("window.__olBio&&window.__olBio(" + JSONObject.quote(reqId) + ",'" + enabled + "')", null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!prompting) backgroundedAt = android.os.SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (backgroundedAt > 0 && !locked && BiometricLock.shouldLock(BiometricLock.isEnabled(this), backgroundedAt,
                android.os.SystemClock.elapsedRealtime(), BiometricLock.LOCK_AFTER_MS)) {
            showLock();
        }
        backgroundedAt = 0;
    }

    @Override
    public void haptic(String type) {
        // legacy 0.2.x names (raiseHand/joinStage/sendDm) map onto the 0.3.0 vocabulary
        String t = "joinStage".equals(type) ? "success" : ("raiseHand".equals(type) || "sendDm".equals(type)) ? "light" : type;
        haptics(t);
    }

    @Override
    public void haptics(String type) {
        handler.post(() -> NativeHaptics.play(this, webView, type));
    }

    @Override
    public void openBrowser(String url) {
        handler.post(() -> {
            Uri u = Uri.parse(url);
            if (InAppBrowser.isWeb(u) && (Prefs.isTrustedOrigin(u) || isConfiguredHost(u))) {
                if (webView != null) webView.loadUrl(u.toString()); // openlounge.app stays in the app
            } else {
                InAppBrowser.open(this, u);
            }
        });
    }

    /** Tell the page the app went to the background / came back (a WebView often keeps reporting "visible"). */
    private void dispatchNativeVisibility(boolean visible) {
        if (webView == null || migrating) return;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('openlounge:native-visibility',{detail:{visible:"
                + visible + "}}))", null);
    }

    @Override
    protected void onPause() {
        activityForeground = false;
        super.onPause();
        CookieManager.getInstance().flush();
        dispatchNativeVisibility(CallLockPolicy.shouldReportVisible(false, locked));
        // privacy: cover the page in the recents thumbnail while the lock is on (unless our own prompt is up)
        if (!prompting && !locked && BiometricLock.isEnabled(this)) lockOverlay.setVisibility(View.VISIBLE);
    }

    // ---------- 0.3.2: Android 14+ full-screen call permission, one-time prompt (FullScreenIntentPrompt) ----------
    private androidx.appcompat.app.AlertDialog fsiDialog;
    private final Runnable fsiCheck = () -> {
        if (isFinishing() || locked || prompting || !pageLoaded || (fsiDialog != null && fsiDialog.isShowing())) return;
        if (!hasWindowFocus()) return; // something else (permission sheet, Custom Tab) is in front: next resume
        fsiDialog = FullScreenIntentPrompt.maybeShow(this);
    };

    private void scheduleFsiPrompt() {
        if (Build.VERSION.SDK_INT < 34) return;
        handler.removeCallbacks(fsiCheck);
        handler.postDelayed(fsiCheck, 3_000); // after the launch animation / first paint
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityForeground = true;
        dispatchNativeVisibility(CallLockPolicy.shouldReportVisible(true, locked));
        if (locked) dispatchNativeLock(true);
        scheduleFsiPrompt();
        if (locked) {
            if (!prompting && lockActions.getVisibility() != View.VISIBLE) handler.post(() -> promptUnlock(false));
        } else if (lockOverlay != null) {
            lockOverlay.setVisibility(View.GONE);
        }
    }

    // ---------- 0.3.1: native call ringing / in-call (NATIVE_SPEC.md §4, §5) ----------

    /** Native FCM → page only while the already-loaded WebView is visibly foregrounded. */
    static boolean dispatchForegroundNotification(ForegroundNotification notice) {
        MainActivity a = live.get();
        if (notice == null || a == null || a.isFinishing() || !a.activityForeground || !a.pageLoaded) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return a.forwardForegroundNotification(notice);
        final boolean[] forwarded = {false};
        CountDownLatch settled = new CountDownLatch(1);
        a.handler.post(() -> {
            try {
                forwarded[0] = a.forwardForegroundNotification(notice);
            } finally {
                settled.countDown();
            }
        });
        try {
            // The FCM callback has only a short budget. Wait briefly for the UI-thread decision so a busy/navigating
            // WebView falls back to the durable system notification instead of silently dropping the event.
            return settled.await(150, TimeUnit.MILLISECONDS) && forwarded[0];
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean forwardForegroundNotification(ForegroundNotification notice) {
        if (webView == null || migrating || !activityForeground || !pageLoaded) return false;
        try {
            JSONObject d = new JSONObject();
            d.put("type", notice.type).put("title", notice.title).put("body", notice.body).put("tag", notice.tag)
                    .put("url", notice.url).put("channel", notice.channel).put("clear", notice.clear);
            if (!notice.badge.isEmpty()) d.put("badge", notice.badge);
            if (!notice.sentAt.isEmpty()) d.put("sentAt", notice.sentAt);
            if (!notice.peerId.isEmpty()) d.put("peerId", notice.peerId);
            if (!notice.groupId.isEmpty()) d.put("groupId", notice.groupId);
            if (!notice.roomId.isEmpty()) d.put("roomId", notice.roomId);
            webView.evaluateJavascript("(function(){try{window.dispatchEvent(new CustomEvent('openlounge:native-notification',{detail:"
                    + d.toString() + "}))}catch(e){}})()", null);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Native → page: openlounge:native-call {type: call:hangup | call:decline | …}. False if no page is up. */
    static boolean dispatchNativeCall(String detailJson) {
        MainActivity a = live.get();
        if (a == null || a.isFinishing()) return false;
        a.handler.post(() -> {
            if (a.webView == null || a.migrating || !a.pageLoaded) return;
            a.webView.evaluateJavascript("(function(){try{window.dispatchEvent(new CustomEvent('openlounge:native-call',{detail:"
                    + detailJson + "}))}catch(e){}})()", null);
        });
        return a.pageLoaded;
    }

    // ---- 0.3.16 native call media host (NativeCallMedia.Host + SessionBridge.Host.nativeMediaResolve) ----
    @Override
    public void nativeMediaResolve(String reqId, boolean ok, String resultJson, String error) {
        String r = resultJson == null || resultJson.isEmpty() ? "null" : resultJson;
        String js = "(function(){try{window.__olNativeMediaResolve&&window.__olNativeMediaResolve(" + JSONObject.quote(reqId) + ","
                + ok + "," + r + "," + JSONObject.quote(error == null ? "" : error) + ")}catch(e){}})()";
        handler.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
    }

    @Override
    public boolean relay(String id, String event, String dataJson) {
        if (webView == null || migrating || !pageLoaded) return false;
        webView.evaluateJavascript("(function(){try{window.__olNativeMsReq(" + JSONObject.quote(id) + "," + JSONObject.quote(event) + ","
                + dataJson + ")}catch(e){try{window.OpenLoungeNative.nativeMsReply(" + JSONObject.quote(id) + ",false,'null','no relay')}catch(_){}}})()", null);
        return true;
    }

    @Override
    public void emit(String detailJson) {
        if (webView == null || !pageLoaded) return;
        webView.evaluateJavascript("(function(){try{window.dispatchEvent(new CustomEvent('openlounge:native-media',{detail:"
                + detailJson + "}))}catch(e){}})()", null);
    }

    @Override
    public void nativeCallAudio(boolean on) {
        // media is live → communication mode + route policy now (call:ended / onDestroy still switch it off)
        if (on) callAudio(true);
    }

    @Override
    public void callMessage(String json) {
        handler.post(() -> onPageCall(json));
    }

    private void onPageCall(String json) {
        JSONObject m;
        try {
            m = new JSONObject(json);
        } catch (Exception e) {
            return;
        }
        String type = m.optString("type", "");
        String id = m.optString("callId", "");
        if (!id.isEmpty() && !id.matches(CallApi.CALL_ID_RE)) return;
        switch (type) {
            case "call:incoming": {
                // Page got call:incoming over WS while App Lock / backgrounded — surface IncomingCallActivity
                // over the lock (FCM may still be in flight). Same UI as CallNotifications.onIncoming.
                if (id.isEmpty()) break;
                String name = m.optString("name", getString(R.string.app_name));
                if (name.length() > 80) name = name.substring(0, 80);
                String photo = m.optString("photo", null);
                boolean video = m.optBoolean("video", false);
                long expiresAt = m.optLong("expiresAt", System.currentTimeMillis() + 45_000L);
                if (expiresAt <= System.currentTimeMillis()) break;
                CallNotifications.show(this, id, name, photo, video, expiresAt);
                break;
            }
            case "call:outgoing":
                callId = id;
                callName = m.optString("name", "");
                if (callName.length() > 80) callName = callName.substring(0, 80);
                // Do NOT enter MODE_IN_COMMUNICATION yet: the page's ringback is a normal media <audio> element and
                // communication mode re-routes/attenuates it (earpiece, voice stream) so the caller hears silence.
                // Call mode + route are applied on call:connected (CallAudioPolicy).
                if (CallAudioPolicy.enterCommunicationModeOn(type)) callAudio(true);
                CallService.start(this, callId, callName, false);
                if (CallAudioPolicy.startRingbackOn(type)) ringback(true);
                break;
            case "call:connected":
                if (CallAudioPolicy.stopRingbackOn(type)) ringback(false);
                // a call taking over from a room keeps the same audio session
                if (!id.isEmpty()) {
                    CallNotifications.cancel(this, id); // answered on the page's own ring screen
                    callId = id;
                }
                if (CallAudioPolicy.enterCommunicationModeOn(type)) callAudio(true);
                CallService.start(this, callId, callName, true);
                break;
            case "call:ended":
                if (CallAudioPolicy.stopRingbackOn(type)) ringback(false);
                NativeCallMedia.closeAll(); // the only native-media teardown besides the page's op 'close'
                if (!id.isEmpty()) CallNotifications.cancel(this, id);
                CallService.stop(this);
                callAudio(false);
                callId = null;
                callName = null;
                unlockForCall = false; // re-enable App Lock after the call
                break;
            case "call:ringtonePrepare":
                // the page is about to play the incoming ringtone: no live call → no left-over call-mode session
                // (communication mode / voice stream made the ring thin and quiet) and the native engine stopped
                if (CallAudioPolicy.prepareRingtoneOn(type, callId != null || roomAudioLive)) {
                    NativeCallMedia.releaseIdle();
                    callAudio(false);
                    AudioManager ram = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (ram != null) try { ram.setMode(AudioManager.MODE_NORMAL); } catch (RuntimeException ignored) {}
                }
                break;
            case "room:joined":
                if (callId == null) roomAudio(true);
                break;
            case "room:left":
                roomAudio(false);
                break;
            case "call:audioRoute":
                if (m.has("speaker")) {
                    callSpeaker = m.optBoolean("speaker", true);
                    if (callAudioOn) applyCallRoute();
                }
                break;
            default:
                break; // call:muted has no system UI on Android
        }
    }

    /**
     * Makes a WebView call behave like a normal phone call: communication mode plus a route policy that follows
     * Bluetooth/wired devices as they appear or disappear. The WebView/WebRTC media pipeline owns audio focus: it
     * requests it when remote playback actually begins and handles a loss/re-gain with its live media state. Taking
     * transient focus here at the outgoing-ring stage could leave an Android 12+ WebView's later remote player faded
     * or muted, so the wrapper deliberately manages the route but does not compete for playback focus.
     */
    private android.media.ToneGenerator ringbackTone;

    /** Outgoing ringback on the media stream (not communication mode, so the loudspeaker plays it at normal level). */
    private void ringback(boolean on) {
        if (on) {
            if (ringbackTone != null) return;
            try {
                ringbackTone = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                ringbackTone.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE); // loops until stopped
            } catch (RuntimeException e) {
                ringbackTone = null;
            }
        } else if (ringbackTone != null) {
            try { ringbackTone.stopTone(); ringbackTone.release(); } catch (RuntimeException ignored) {}
            ringbackTone = null;
        }
    }

    private void callAudio(boolean on) {
        AudioManager am = callAudioManager;
        if (am == null) callAudioManager = am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (on) {
            if (!callAudioOn) {
                modeBeforeCall = am.getMode();
                callAudioOn = true;
                registerCallDeviceCallback(am);
                try { am.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (RuntimeException ignored) {}
                try {
                    int target = CallAudioPolicy.voiceCallVolumeFloor(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL), am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
                    if (target >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0);
                } catch (RuntimeException ignored) {}
                setVolumeControlStream(AudioManager.STREAM_VOICE_CALL); // hardware keys adjust the call level
            }
            applyCallRoute();
        } else if (callAudioOn) {
            callAudioOn = false;
            unregisterCallDeviceCallback(am);
            try {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else clearLegacySco(am);
            } catch (RuntimeException ignored) {}
            try { am.setSpeakerphoneOn(false); } catch (RuntimeException ignored) {}
            try { am.setMode(modeBeforeCall); } catch (RuntimeException ignored) {}
            setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
            callSpeaker = true;
        }
    }

    /**
     * Rooms get the same headset policy as calls: communication mode so a wired / USB-C / Bluetooth headset's mic
     * and speaker are used together, falling back to the loudspeaker when the headset is removed.
     */
    /** a live room owns communication mode; the ringtone prep must not reset it */
    private boolean roomAudioLive;

    private void roomAudio(boolean on) {
        roomAudioLive = on;
        if (on) {
            if (callAudioOn) return;
            callSpeaker = true;
            callAudio(true);
        } else if (callId == null) {
            callAudio(false);
        }
    }

    private void registerCallDeviceCallback(AudioManager am) {
        if (callDeviceCallbackRegistered) return;
        try {
            am.registerAudioDeviceCallback(callDeviceCallback, handler);
            callDeviceCallbackRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void unregisterCallDeviceCallback(AudioManager am) {
        if (!callDeviceCallbackRegistered) return;
        try {
            am.unregisterAudioDeviceCallback(callDeviceCallback);
        } catch (RuntimeException ignored) {
        }
        callDeviceCallbackRegistered = false;
    }

    private static CallAudioRoute.Device routeType(AudioDeviceInfo d) {
        if (d == null) return null;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return CallAudioRoute.Device.SPEAKER;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return CallAudioRoute.Device.EARPIECE;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return CallAudioRoute.Device.WIRED;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return CallAudioRoute.Device.USB;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return CallAudioRoute.Device.BLUETOOTH;
            default: return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void applyCallRoute() {
        AudioManager am = callAudioManager;
        if (am == null || !callAudioOn) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                EnumMap<CallAudioRoute.Device, AudioDeviceInfo> devices = new EnumMap<>(CallAudioRoute.Device.class);
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    CallAudioRoute.Device type = routeType(d);
                    if (type != null) devices.put(type, d);
                }
                CallAudioRoute.Device selected = CallAudioRoute.choose(callSpeaker, devices.keySet());
                AudioDeviceInfo target = selected == null ? null : devices.get(selected);
                if (target != null) am.setCommunicationDevice(target);
                else am.clearCommunicationDevice();
                return;
            }

            EnumSet<CallAudioRoute.Device> devices = EnumSet.noneOf(CallAudioRoute.Device.class);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                CallAudioRoute.Device type = routeType(d);
                if (type != null) devices.add(type);
            }
            CallAudioRoute.Device selected = CallAudioRoute.choose(callSpeaker, devices);
            if (CallAudioRoute.usesLegacyBluetoothSco(selected)) {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                am.setSpeakerphoneOn(false);
            } else {
                clearLegacySco(am);
                am.setSpeakerphoneOn(selected == CallAudioRoute.Device.SPEAKER);
            }
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void clearLegacySco(AudioManager am) {
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
        } catch (RuntimeException ignored) {
        }
    }

    // Wi-Fi <-> cellular handoff: tell the page so a live call restarts ICE in place on the new interface
    // (native-only call adaptation, web netAdapt.ts). WebView's navigator.connection "change" is unreliable here.
    private ConnectivityManager.NetworkCallback netCallback;
    private String lastNetType = null;
    private void watchNetworkHandoff() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null || android.os.Build.VERSION.SDK_INT < 24) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    String t = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet" : "other";
                    String key = t + ":" + n;
                    if (key.equals(lastNetType)) return;
                    boolean first = lastNetType == null;
                    lastNetType = key;
                    if (first) return;
                    handler.post(() -> {
                        NativeCallMedia.onNetworkChange(); // app-owned media: fresh ICE now, even mid page reload
                        if (webView != null) webView.evaluateJavascript("try{window.__olNetworkChanged&&window.__olNetworkChanged('" + key + "')}catch(e){}", null);
                    });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (netCallback != null) {
            try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback); } catch (RuntimeException ignored) {}
            netCallback = null;
        }
        activityForeground = false;
        if (NativeCallMedia.getHost() == this) NativeCallMedia.setHost(null);
        if (live.get() == this) live = new WeakReference<>(null);
        if (isFinishing()) {
            CallService.stop(this);
            callAudio(false);
        }
        handler.removeCallbacksAndMessages(null);
        if (fsiDialog != null && fsiDialog.isShowing()) fsiDialog.dismiss();
        if (webView != null) {
            ((FrameLayout) findViewById(R.id.root)).removeView(webView);
            webView.destroy();
        }
        ringback(false);
        super.onDestroy();
    }
}
