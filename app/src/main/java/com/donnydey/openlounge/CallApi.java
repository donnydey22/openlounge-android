package com.donnydey.openlounge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Device → server call endpoints (NATIVE_SPEC.md §2) for when the WebView can't do it: Decline from the
 * notification / full-screen ring screen. Authenticated with the short-lived access token (1 h) the web app mirrors
 * into the encrypted session store ({"access","accessExp"} in the session JSON). No native refresh: refresh tokens
 * rotate, and a native refresh racing the page's would sign the user out. Without a fresh token the decline falls
 * back to the page (openlounge:native-call call:decline) and otherwise the caller's ring simply times out.
 * 0.3.2: the ring push carries a per-call decline token (server/calls/declineToken.ts: scoped to this call + callee,
 * expires with the ring, single-use). It's kept per call id in private prefs (survives a process kill between the
 * ring and the tap) and sent as X-Call-Decline-Token, so Decline works even when the 1-hour session has expired.
 */
final class CallApi {
    private static final String TAG = "OLCalls";
    static final String CALL_ID_RE = "[A-Za-z0-9_-]{1,64}";
    static final String DECLINE_TOKEN_RE = "v1\\.[A-Za-z0-9_-]{8,500}\\.[A-Za-z0-9_-]{20,100}";
    private static final String PREFS = "ol_call_decline";
    private static final String KEY_DEBUG_ORIGIN = "debug_api_origin";

    private CallApi() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Keep the ring push's decline token for this call until its expiry (+ the server's grace). */
    static void rememberDeclineToken(Context ctx, String callId, String token, long expiresAt) {
        if (callId == null || !callId.matches(CALL_ID_RE) || token == null || !token.matches(DECLINE_TOKEN_RE)) return;
        SharedPreferences p = sp(ctx);
        SharedPreferences.Editor e = p.edit();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) { // prune old calls
            if (!en.getKey().startsWith("t:")) continue;
            Object v = en.getValue();
            long exp = v instanceof String ? CallNotifications.parseLong(((String) v).split(" ", 2)[0], 0) : 0;
            if (exp < now) e.remove(en.getKey());
        }
        e.putString("t:" + callId, (expiresAt + 30_000) + " " + token).apply();
    }

    /** The stored decline token for this call, or null (none / expired). */
    static String declineToken(Context ctx, String callId) {
        if (callId == null) return null;
        String v = sp(ctx).getString("t:" + callId, null);
        if (v == null) return null;
        String[] parts = v.split(" ", 2);
        if (parts.length != 2 || CallNotifications.parseLong(parts[0], 0) < System.currentTimeMillis()) return null;
        return parts[1];
    }

    static void forgetDeclineToken(Context ctx, String callId) {
        if (callId != null) sp(ctx).edit().remove("t:" + callId).apply();
    }

    /** DEBUG builds only: emulator tests point the native decline at a local test server (http://10.0.2.2:port). */
    static void setDebugOrigin(Context ctx, String origin) {
        if (!BuildConfig.DEBUG) return;
        if (origin == null || origin.isEmpty()) sp(ctx).edit().remove(KEY_DEBUG_ORIGIN).apply();
        else if (origin.matches("https?://(10\\.0\\.2\\.2|127\\.0\\.0\\.1|localhost)(:\\d{1,5})?")) sp(ctx).edit().putString(KEY_DEBUG_ORIGIN, origin).apply();
    }

    static String origin(Context ctx) {
        if (BuildConfig.DEBUG) {
            String dbg = sp(ctx).getString(KEY_DEBUG_ORIGIN, null);
            if (dbg != null) return dbg;
        }
        String u = Prefs.normalizeUrl(Prefs.getServerUrl(ctx));
        Uri uri = Uri.parse(u);
        return Prefs.isTrustedOrigin(uri) ? uri.getScheme() + "://" + uri.getEncodedAuthority() : "https://openlounge.app";
    }

    /** Fresh access token (valid ≥ 10 s) from the stored session, or null. */
    static String freshAccessToken(Context ctx) {
        String raw;
        synchronized (CallApi.class) {
            try {
                raw = new SecureSessionStore(ctx.getApplicationContext()).get();
            } catch (Throwable t) {
                return null;
            }
        }
        if (raw == null || !raw.startsWith("{")) return null;
        try {
            JSONObject o = new JSONObject(raw);
            String t = o.optString("access", "");
            long exp = o.optLong("accessExp", 0);
            return (!t.isEmpty() && exp > System.currentTimeMillis() + 10_000) ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Blocking: call from a background thread. Returns the HTTP status (0 = not sent / network error).
     * Sends the per-call decline token (works with no session) and, when there is one, the fresh access token too
     * (the server falls back to the session if the token is refused). 409 = already declined/answered: done either way.
     */
    static int decline(Context ctx, String callId) {
        if (callId == null || !callId.matches(CALL_ID_RE)) return 0;
        String declineToken = declineToken(ctx, callId);
        String token = freshAccessToken(ctx);
        if (token == null && declineToken == null) {
            Log.i(TAG, "decline not sent natively: no decline token and no fresh access token");
            return 0;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(origin(ctx) + "/api/calls/" + callId + "/decline").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setDoOutput(true);
            if (declineToken != null) c.setRequestProperty("X-Call-Decline-Token", declineToken);
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int st = c.getResponseCode();
            Log.i(TAG, "decline → " + st + (declineToken != null ? " (decline token" : " (session") + (token != null && declineToken != null ? " + session)" : ")"));
            if (st != 0 && st != 429 && st < 500) forgetDeclineToken(ctx, callId); // single-use: never resend it
            return st;
        } catch (Exception e) {
            Log.w(TAG, "decline failed: " + e.getClass().getSimpleName());
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
