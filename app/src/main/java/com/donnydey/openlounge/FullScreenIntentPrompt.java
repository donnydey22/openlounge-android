package com.donnydey.openlounge;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 0.3.2 — Android 14+ only grants USE_FULL_SCREEN_INTENT automatically to calling/alarm apps; when it's off, an
 * incoming call only shows as a heads-up notification (it still rings) instead of lighting up the lock screen.
 * Once (ever), when notifications are on but the full-screen permission is off, explain it and offer the system
 * page (Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT). A standard Material dialog: width-capped on tablets,
 * wraps and scrolls on small phones and in landscape. Dismissible (Not now / back / tap outside).
 */
final class FullScreenIntentPrompt {
    private static final String TAG = "OLCalls";
    private static final String PREFS = "ol_fsi_prompt";
    private static final String KEY_SHOWN = "shown";

    private FullScreenIntentPrompt() {}

    /** true when the full-screen permission is missing on Android 14+ (always false below 14: granted at install) */
    static boolean missing(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return false;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && !nm.canUseFullScreenIntent();
    }

    static boolean shouldShow(Context ctx) {
        if (!missing(ctx)) return false;
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false; // no ring at all yet: not the moment
        return !prefs(ctx).getBoolean(KEY_SHOWN, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Shows the one-time prompt if it applies. Returns the dialog (or null). */
    static AlertDialog maybeShow(Activity a) {
        if (a == null || a.isFinishing() || !shouldShow(a)) return null;
        prefs(a).edit().putBoolean(KEY_SHOWN, true).apply(); // one time, whatever the answer
        Log.i(TAG, "full-screen intent prompt shown");
        return new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.fsi_prompt_title)
                .setMessage(R.string.fsi_prompt_body)
                .setPositiveButton(R.string.fsi_prompt_open, (d, w) -> openSetting(a))
                .setNegativeButton(R.string.fsi_prompt_later, (d, w) -> Log.i(TAG, "full-screen intent prompt dismissed"))
                .setCancelable(true)
                .show();
    }

    static void openSetting(Activity a) {
        Uri pkg = Uri.parse("package:" + a.getPackageName());
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg));
                Log.i(TAG, "opened full-screen intent setting");
                return;
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // some OEM builds don't expose the page: fall back to the app's notification settings
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
        } catch (ActivityNotFoundException e) {
            try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); } catch (Exception ignored) {}
        }
    }
}
