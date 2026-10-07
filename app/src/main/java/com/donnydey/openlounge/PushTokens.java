package com.donnydey.openlounge;

import android.content.Context;

import com.google.firebase.messaging.FirebaseMessaging;

/** Holds the current FCM token (SharedPreferences) and notifies the visible activity when it changes. */
public final class PushTokens {
    public interface Listener { void onToken(String token); }

    private static final String PREFS = "openlounge_push";
    private static volatile Listener listener;

    private PushTokens() {}

    public static void setListener(Listener l) { listener = l; }

    public static String get(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fcm_token", "");
    }

    public static void save(Context ctx, String token) {
        if (token == null || token.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("fcm_token", token).apply();
        Listener l = listener;
        if (l != null) l.onToken(token);
    }

    /** Ask FCM for the token (needs Google Play services; silently does nothing without it). */
    public static void refresh(Context ctx) {
        try {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t -> save(ctx.getApplicationContext(), t));
        } catch (Exception ignored) {
        }
    }
}
