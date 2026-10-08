package com.donnydey.openlounge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;

/**
 * 0.3.0 in-app browser (contract: /workspace/native/BROWSER_BRIDGE.md): external http(s) links open in a Chrome
 * Custom Tab over the app (user's default browser if it supports Custom Tabs, else a normal browser intent);
 * other schemes (mailto:, tel:, sms:, intent apps) go to the system. Open Lounge's own hosts never come here.
 */
final class InAppBrowser {
    private InAppBrowser() {}

    static boolean isWeb(Uri u) {
        String s = u == null ? null : u.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
    }

    static void open(Activity a, Uri u) {
        if (u == null) return;
        if (!isWeb(u)) {
            if ("intent".equalsIgnoreCase(u.getScheme()) || "javascript".equalsIgnoreCase(u.getScheme())
                    || "file".equalsIgnoreCase(u.getScheme()) || "content".equalsIgnoreCase(u.getScheme())) return;
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
            return;
        }
        try {
            CustomTabColorSchemeParams dark = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(Color.parseColor("#0B0B0D")).setNavigationBarColor(Color.parseColor("#0B0B0D")).build();
            CustomTabColorSchemeParams light = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(Color.parseColor("#F4F3EE")).setNavigationBarColor(Color.parseColor("#F4F3EE")).build();
            new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                    .setUrlBarHidingEnabled(true)
                    .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, dark)
                    .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, light)
                    .build()
                    .launchUrl(a, u);
        } catch (Exception e) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        }
    }
}
