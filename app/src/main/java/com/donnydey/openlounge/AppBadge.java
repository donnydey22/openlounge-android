package com.donnydey.openlounge;

import android.content.Context;

import me.leolin.shortcutbadger.ShortcutBadger;

/**
 * Numeric app-icon badge (0.2.7). Source of truth is the server's total unread count (unread DMs + unread
 * notifications): it arrives as data.badge on every push, and the web app reports it via
 * window.OpenLoungeNative.setBadge(n) whenever it changes (0 clears). ShortcutBadger covers launchers with a
 * numeric badge API (Samsung One UI, Sony, HTC, Huawei/Honor, ...); on stock Android/Pixel only a dot is shown
 * and the count comes from NotificationCompat.setNumber on the posted notification.
 * Samsung shows the number only when Settings > Notifications > App icon badges > "Show with number" is chosen.
 */
public final class AppBadge {
    private AppBadge() {}

    public static int parse(String s) {
        if (s == null) return -1;
        try {
            int n = Integer.parseInt(s.trim());
            return n < 0 ? -1 : Math.min(n, 9999);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void set(Context ctx, int count) {
        try {
            if (count <= 0) ShortcutBadger.removeCount(ctx.getApplicationContext());
            else ShortcutBadger.applyCount(ctx.getApplicationContext(), Math.min(count, 9999));
        } catch (Throwable ignored) {
            // unsupported launcher
        }
    }
}
