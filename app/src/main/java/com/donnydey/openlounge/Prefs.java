package com.donnydey.openlounge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

public final class Prefs {
    private static final String NAME = "openlounge_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_ORIGIN_MIGRATED = "origin_migrated_v023";

    /** The address 0.2.1 and earlier opened by default. Same server/app as openlounge.app, different origin. */
    public static final String LEGACY_RAILWAY_URL = "https://openlounge-production.up.railway.app";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getServerUrl(Context ctx) {
        String stored = sp(ctx).getString(KEY_SERVER_URL, null);
        if (!TextUtils.isEmpty(stored)) {
            // the railway.app address was only ever the default: everyone moves to the canonical domain
            if (normalizeUrl(stored).equalsIgnoreCase(LEGACY_RAILWAY_URL)) return defaultUrl(ctx);
            return stored;
        }
        return defaultUrl(ctx);
    }

    public static String defaultUrl(Context ctx) {
        String fromRes = ctx.getString(R.string.default_server_url);
        return TextUtils.isEmpty(fromRes) ? "" : fromRes.trim();
    }

    public static void setServerUrl(Context ctx, String url) {
        sp(ctx).edit().putString(KEY_SERVER_URL, normalizeUrl(url)).apply();
    }

    public static boolean originMigrated(Context ctx) {
        return sp(ctx).getBoolean(KEY_ORIGIN_MIGRATED, false);
    }

    public static void setOriginMigrated(Context ctx) {
        sp(ctx).edit().putBoolean(KEY_ORIGIN_MIGRATED, true).commit();
    }

    /** Open Lounge's own origins (the token bridge only answers these; other links open in the browser). */
    public static boolean isTrustedOrigin(Uri uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"https".equals(scheme)) return false;
        return host.equals("openlounge.app")
                || host.equals("www.openlounge.app")
                || host.equals("openlounge-production.up.railway.app");
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
