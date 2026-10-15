package com.donnydey.openlounge;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Target SDK 35+ forces edge-to-edge on Android 15+. To look the same on every version (API 24-36), the window is
 * always edge-to-edge and the root view is padded by the system bars, display cutout and keyboard (replaces
 * adjustResize). The page therefore still gets 0 safe-area insets, exactly like 0.2.x.
 */
final class EdgeToEdge {
    private EdgeToEdge() {}

    static void apply(Activity a, View root) {
        WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
        final int pl = root.getPaddingLeft(), pt = root.getPaddingTop(), pr = root.getPaddingRight(), pb = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // 0.3.8: full-bleed WebView (call video behind status/nav bars); the page pads itself with env(safe-area-inset-*)
            // (viewport-fit=cover). Only the keyboard still resizes the WebView.
            v.setPadding(pl, pt, pr, pb + ime.bottom);
            return insets; // let the WebView see the insets (env(safe-area-inset-*))
        });
        ViewCompat.requestApplyInsets(root);
    }
}
