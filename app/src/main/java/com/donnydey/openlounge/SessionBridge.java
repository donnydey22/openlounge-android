package com.donnydey.openlounge;

import android.content.Context;
import android.net.Uri;
import android.webkit.JavascriptInterface;

/**
 * window.OpenLoungeNative — the durable native token store the web app mirrors its session into
 * (see src/lib/session.ts for the contract: getSession() → string|null, setSession(string), clearSession()).
 *
 * Since 0.2.4 the value is encrypted at rest with an Android Keystore AES-GCM key (SecureSessionStore); a
 * plaintext session left by 0.2.3 or earlier is migrated on first use and the plaintext copy deleted. Writes are
 * commit()ed, so a rotated refresh token is on disk before the call returns: it survives app kills, WebView
 * storage eviction and origin changes (openlounge.app vs *.up.railway.app have separate localStorage).
 *
 * Only pages from Open Lounge's own origins may use it: the WebView's current origin is tracked on the
 * UI thread (setCurrentUrl) and checked on every call (JavascriptInterface methods run on a binder thread,
 * so store access is synchronized).
 */
public final class SessionBridge {
    public static final String JS_NAME = "OpenLoungeNative";

    /** Activity-side actions for the extra bridge methods (push, share, contacts, haptics). */
    public interface Host {
        String pushToken();
        void share(String text, String url);
        void requestContacts();
        String contactsPermission();
        void openAppSettings();
        void requestNotificationPermission();
        void haptic(String type);
    }

    private final SecureSessionStore store;
    private volatile String currentUrl = "";
    private final Host host;
    private static final Host NOOP = new Host() {
        public String pushToken() { return ""; }
        public void share(String t, String u) {}
        public void requestContacts() {}
        public String contactsPermission() { return "denied"; }
        public void openAppSettings() {}
        public void requestNotificationPermission() {}
        public void haptic(String type) {}
    };

    public SessionBridge(Context ctx) {
        this(ctx, null);
    }

    public SessionBridge(Context ctx, Host host) {
        store = new SecureSessionStore(ctx);
        this.host = host != null ? host : NOOP;
    }

    /** Called from the UI thread whenever the WebView navigates. */
    public void setCurrentUrl(String url) {
        currentUrl = url == null ? "" : url;
    }

    private boolean trusted() {
        return Prefs.isTrustedOrigin(Uri.parse(currentUrl));
    }

    @JavascriptInterface
    public String getSession() {
        if (!trusted()) return null;
        synchronized (store) {
            return store.get();
        }
    }

    @JavascriptInterface
    public void setSession(String value) {
        if (!trusted() || value == null || value.length() < 20 || value.length() > 8192) return;
        synchronized (store) {
            store.put(value);
        }
    }

    @JavascriptInterface
    public void clearSession() {
        if (!trusted()) return;
        synchronized (store) {
            store.clear();
        }
    }

    // ---- 0.2.5: push token, share sheet, contacts (opt-in), haptics ----

    @JavascriptInterface
    public String getPlatform() { return "android"; }

    @JavascriptInterface
    public String getPushToken() { return trusted() ? host.pushToken() : ""; }

    @JavascriptInterface
    public void share(String text, String url) {
        if (trusted()) host.share(text == null ? "" : text, url == null ? "" : url);
    }

    @JavascriptInterface
    public void requestContacts() { if (trusted()) host.requestContacts(); }

    @JavascriptInterface
    public String contactsPermission() { return trusted() ? host.contactsPermission() : "denied"; }

    @JavascriptInterface
    public void openAppSettings() { if (trusted()) host.openAppSettings(); }

    @JavascriptInterface
    public void requestNotificationPermission() { if (trusted()) host.requestNotificationPermission(); }

    @JavascriptInterface
    public void haptic(String type) { if (trusted()) host.haptic(type == null ? "light" : type); }

    // ---- native-side helpers (not exposed to JS) ----

    public boolean hasStoredSession() {
        synchronized (store) {
            String v = store.get();
            return v != null && v.length() >= 20;
        }
    }

    /** Seed the store from a migrated value (only if empty). */
    public void seedIfEmpty(String value) {
        if (value == null || value.length() < 20) return;
        synchronized (store) {
            String v = store.get();
            if (v != null && v.length() >= 20) return;
            store.put(value);
        }
    }
}
