package com.donnydey.openlounge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

public final class Prefs {
    private static final String NAME = "openlounge_prefs";
    private static final String KEY_SERVER_URL = "server_url";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getServerUrl(Context ctx) {
        String stored = sp(ctx).getString(KEY_SERVER_URL, null);
        if (!TextUtils.isEmpty(stored)) return stored;
        String fromRes = ctx.getString(R.string.default_server_url);
        return TextUtils.isEmpty(fromRes) ? "" : fromRes.trim();
    }

    public static void setServerUrl(Context ctx, String url) {
        sp(ctx).edit().putString(KEY_SERVER_URL, normalizeUrl(url)).apply();
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        // strip trailing slash for consistency
        while (u.endsWith("/") && u.length() > "https://x".length()) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static boolean isValidUrl(String raw) {
        String u = normalizeUrl(raw);
        if (u.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(u);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && !TextUtils.isEmpty(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
