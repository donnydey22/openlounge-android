package com.donnydey.openlounge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;

/**
 * window.OpenLoungeNative — the durable native token store the web app mirrors its session into
 * (see src/lib/session.ts for the contract). Backed by SharedPreferences, written with commit() so a
 * rotated refresh token is on disk before the call returns: it survives app kills, WebView storage
 * eviction and origin changes (openlounge.app vs *.up.railway.app have separate localStorage).
 *
 * Only pages from Open Lounge's own origins may use it: the WebView's current origin is tracked on the
 * UI thread (setCurrentUrl) and checked on every call (JavascriptInterface methods run on a binder thread).
 */
public final class SessionBridge {
    public static final String JS_NAME = "OpenLoungeNative";
    private static final String NAME = "openlounge_session";
    private static final String KEY_VALUE = "session";

    private final SharedPreferences sp;
    private volatile String currentUrl = "";

    public SessionBridge(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
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
        return sp.getString(KEY_VALUE, null);
    }

    @JavascriptInterface
    public void setSession(String value) {
        if (!trusted() || value == null || value.length() < 20 || value.length() > 8192) return;
        sp.edit().putString(KEY_VALUE, value).commit();
    }

    @JavascriptInterface
    public void clearSession() {
        if (!trusted()) return;
        sp.edit().remove(KEY_VALUE).commit();
    }

    // ---- native-side helpers (not exposed to JS) ----

    public boolean hasStoredSession() {
        String v = sp.getString(KEY_VALUE, null);
        return v != null && v.length() >= 20;
    }

    /** Seed the store from a migrated value (only if empty). */
    public void seedIfEmpty(String value) {
        if (value == null || value.length() < 20 || hasStoredSession()) return;
        sp.edit().putString(KEY_VALUE, value).commit();
    }
}
