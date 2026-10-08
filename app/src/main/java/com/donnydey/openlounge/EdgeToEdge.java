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
            v.setPadding(pl + bars.left, pt + bars.top, pr + bars.right, pb + Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
