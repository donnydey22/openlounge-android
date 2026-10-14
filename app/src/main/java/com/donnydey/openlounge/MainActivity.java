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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SessionBridge.Host {
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
    private boolean pageLoaded = false;
    private boolean callAudioOn = false;
    private boolean callSpeaker = true;
    private String callId = null;
    private String callName = null;
    private AudioManager callAudioManager;
    private int modeBeforeCall = AudioManager.MODE_NORMAL;
    private Object callAudioFocusRequest;
    private boolean callAudioFocused = false;
    private boolean callDeviceCallbackRegistered = false;
    private final AudioManager.OnAudioFocusChangeListener callFocusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN && callAudioOn) handler.post(this::applyCallRoute);
    };
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
        // cold start: lock before anything from the WebView is visible (the page still loads behind the overlay)
        if (BiometricLock.shouldLock(BiometricLock.isEnabled(this), 0, 0, BiometricLock.LOCK_AFTER_MS)) showLock();

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
        s.setUserAgentString(s.getUserAgentString() + " OpenLoungeAndroid/" + BuildConfig.VERSION_NAME);

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
        Uri b = Uri.parse(base);
        return b.getScheme() + "://" + b.getEncodedAuthority() + p;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String p = openPathFrom(intent);
        if (p == null || webView == null) return;
        String base = Prefs.getServerUrl(this);
        if (!Prefs.isValidUrl(base) || migrating) {
            pendingOpenPath = p;
            return;
        }
        // client-side navigation keeps the socket/session alive; full load as fallback
        String js = "(function(){try{if(window.next&&window.next.router){window.next.router.push(" + JSONObject.quote(p) + ");return 1}}catch(e){}location.assign("
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
        locked = true;
        lockMessage.setText(R.string.lock_locked);
        lockActions.setVisibility(View.GONE);
        lockOverlay.setVisibility(View.VISIBLE);
        if (webView != null) webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void hideLock() {
        locked = false;
        lockOverlay.setVisibility(View.GONE);
        if (webView != null) webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
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
        super.onPause();
        CookieManager.getInstance().flush();
        dispatchNativeVisibility(false);
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
        dispatchNativeVisibility(true);
        scheduleFsiPrompt();
        if (locked) {
            if (!prompting && lockActions.getVisibility() != View.VISIBLE) handler.post(() -> promptUnlock(false));
        } else if (lockOverlay != null) {
            lockOverlay.setVisibility(View.GONE);
        }
    }

    // ---------- 0.3.1: native call ringing / in-call (NATIVE_SPEC.md §4, §5) ----------

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
            case "call:outgoing":
                callId = id;
                callName = m.optString("name", "");
                if (callName.length() > 80) callName = callName.substring(0, 80);
                callAudio(true);
                CallService.start(this, callId, callName, false);
                break;
            case "call:connected":
                if (!id.isEmpty()) {
                    CallNotifications.cancel(this, id); // answered on the page's own ring screen
                    callId = id;
                }
                callAudio(true);
                CallService.start(this, callId, callName, true);
                break;
            case "call:ended":
                if (!id.isEmpty()) CallNotifications.cancel(this, id);
                CallService.stop(this);
                callAudio(false);
                callId = null;
                callName = null;
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
     * Makes a WebView call behave like a normal phone call: communication mode + transient voice focus, and a live
     * route policy that follows Bluetooth/wired devices as they appear or disappear. The old audio mode/focus is
     * restored exactly once when the call ends; this never changes the app-wide route outside a call.
     */
    private void callAudio(boolean on) {
        AudioManager am = callAudioManager;
        if (am == null) callAudioManager = am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (on) {
            if (!callAudioOn) {
                modeBeforeCall = am.getMode();
                callAudioOn = true;
                requestCallAudioFocus(am);
                registerCallDeviceCallback(am);
                try { am.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (RuntimeException ignored) {}
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
            abandonCallAudioFocus(am);
            callSpeaker = true;
        }
    }

    private void requestCallAudioFocus(AudioManager am) {
        if (callAudioFocused) return;
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(callFocusListener)
                        .build();
                callAudioFocusRequest = request;
                result = am.requestAudioFocus(request);
            } else {
                result = am.requestAudioFocus(callFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
            callAudioFocused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (RuntimeException ignored) {
            callAudioFocusRequest = null;
            callAudioFocused = false;
        }
    }

    private void abandonCallAudioFocus(AudioManager am) {
        if (!callAudioFocused) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && callAudioFocusRequest instanceof AudioFocusRequest)
                am.abandonAudioFocusRequest((AudioFocusRequest) callAudioFocusRequest);
            else am.abandonAudioFocus(callFocusListener);
        } catch (RuntimeException ignored) {
        }
        callAudioFocusRequest = null;
        callAudioFocused = false;
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

    @Override
    protected void onDestroy() {
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
        super.onDestroy();
    }
}
