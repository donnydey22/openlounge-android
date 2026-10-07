package com.donnydey.openlounge;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Threads-style wordmark entrance over the WebView.
 * Enter 240ms: "Open Lounge" alpha 0→1, scale 0.96→1, letter-spacing +0.02em→-0.025em (tracking-tight),
 *   ease cubic-bezier(0.2,0,0,1). Hold until WebView onPageFinished (or 1500ms cap from launch).
 * Exit 220ms: alpha 1→0, scale 1→1.06, ease cubic-bezier(0.4,0,1,1). Fast path total ≈460ms.
 * Reduce motion (animator duration scale 0 / remove animations): plain 150ms fade, no scale/tracking.
 */
final class LaunchOverlay {
    static final long ENTER_MS = 240, EXIT_MS = 220, CAP_MS = 1500, REDUCED_MS = 150;
    private static final float TRACK_FROM = 0.02f, TRACK_TO = -0.025f;

    private final View overlay;
    private final TextView word;
    private final boolean reduced;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean entered, ready, done;

    LaunchOverlay(View overlay, TextView word) {
        this.overlay = overlay;
        this.word = word;
        float scale = Settings.Global.getFloat(overlay.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        this.reduced = scale == 0f;
    }

    void start() {
        h.postDelayed(this::markReady, CAP_MS);
        if (reduced) {
            word.setAlpha(1f);
            word.setLetterSpacing(TRACK_TO);
            entered = true;
            return;
        }
        word.setAlpha(0f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(ENTER_MS);
        a.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            word.setAlpha(t);
            float s = 0.96f + 0.04f * t;
            word.setScaleX(s); word.setScaleY(s);
            word.setLetterSpacing(TRACK_FROM + (TRACK_TO - TRACK_FROM) * t);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) { entered = true; maybeExit(); }
        });
        a.start();
    }

    /** Call from onPageFinished / error / setup screen. Safe to call repeatedly. */
    void markReady() { ready = true; maybeExit(); }

    private void maybeExit() {
        if (done || !ready || !entered) return;
        done = true;
        h.removeCallbacksAndMessages(null);
        if (reduced) {
            overlay.animate().alpha(0f).setDuration(REDUCED_MS).withEndAction(this::remove).start();
            return;
        }
        PathInterpolator in = new PathInterpolator(0.4f, 0f, 1f, 1f);
        word.animate().scaleX(1.06f).scaleY(1.06f).setDuration(EXIT_MS).setInterpolator(in).start();
        overlay.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(in).withEndAction(this::remove).start();
    }

    private void remove() {
        overlay.setVisibility(View.GONE);
        if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
    }
}
