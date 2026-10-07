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

    private final SecureSessionStore store;
    private volatile String currentUrl = "";

    public SessionBridge(Context ctx) {
        store = new SecureSessionStore(ctx);
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
