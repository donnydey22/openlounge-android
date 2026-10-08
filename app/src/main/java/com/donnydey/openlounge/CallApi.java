package com.donnydey.openlounge;

import android.content.Context;
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
 */
final class CallApi {
    private static final String TAG = "OLCalls";
    static final String CALL_ID_RE = "[A-Za-z0-9_-]{1,64}";

    private CallApi() {}

    static String origin(Context ctx) {
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

    /** Blocking: call from a background thread. Returns the HTTP status (0 = not sent / network error). */
    static int decline(Context ctx, String callId) {
        if (callId == null || !callId.matches(CALL_ID_RE)) return 0;
        String token = freshAccessToken(ctx);
        if (token == null) {
            Log.i(TAG, "decline not sent natively: no fresh access token");
            return 0;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(origin(ctx) + "/api/calls/" + callId + "/decline").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int st = c.getResponseCode();
            Log.i(TAG, "decline → " + st);
            return st;
        } catch (Exception e) {
            Log.w(TAG, "decline failed: " + e.getClass().getSimpleName());
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
