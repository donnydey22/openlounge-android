package com.donnydey.openlounge;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * 0.3.0 haptics bridge: window.OpenLoungeNative.haptics(type), types light | medium | heavy | success | warning |
 * error | selection (contract: /workspace/native/HAPTICS_BRIDGE.md). Unknown types do nothing. Throttled to one
 * effect per 60 ms, like iOS. Uses the system's predefined effects (they follow the user's vibration-intensity
 * setting); falls back to View.performHapticFeedback, then to short one-shot vibrations on old devices.
 */
final class NativeHaptics {
    private static long lastAt = 0;

    private NativeHaptics() {}

    static boolean isKnown(String t) {
        return "light".equals(t) || "medium".equals(t) || "heavy".equals(t) || "success".equals(t)
                || "warning".equals(t) || "error".equals(t) || "selection".equals(t);
    }

    /** Must run on the UI thread. */
    static void play(Context ctx, View view, String type) {
        if (!isKnown(type)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAt < 60) return;
        lastAt = now;
        try {
            if (view != null && Build.VERSION.SDK_INT >= 30 && playViewFeedback(view, type)) return;
            Vibrator v = vibrator(ctx);
            if (v == null || !v.hasVibrator()) {
                if (view != null) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(effect29(type));
            } else if (Build.VERSION.SDK_INT >= 26) {
                long[] w = waveform(type);
                v.vibrate(w.length == 2 ? VibrationEffect.createOneShot(w[1], VibrationEffect.DEFAULT_AMPLITUDE)
                        : VibrationEffect.createWaveform(w, -1));
            } else {
                v.vibrate(waveform(type), -1);
            }
        } catch (Exception ignored) {
            // haptics are decoration: never crash the app over them
        }
    }

    /** API 30+: the system haptic vocabulary (CONFIRM / REJECT exist from 30). Returns false if not performed. */
    private static boolean playViewFeedback(View view, String type) {
        int c;
        switch (type) {
            case "light": c = HapticFeedbackConstants.CLOCK_TICK; break;
            case "medium": c = HapticFeedbackConstants.VIRTUAL_KEY; break;
            case "heavy": c = HapticFeedbackConstants.LONG_PRESS; break;
            case "success": c = HapticFeedbackConstants.CONFIRM; break;
            case "error": c = HapticFeedbackConstants.REJECT; break;
            case "selection": c = HapticFeedbackConstants.TEXT_HANDLE_MOVE; break;
            default: return false; // warning → double-click vibration below
        }
        return view.performHapticFeedback(c);
    }

    private static VibrationEffect effect29(String type) {
        switch (type) {
            case "light":
            case "selection": return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case "medium":
            case "success": return VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            case "heavy": return VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
            case "warning": return VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK);
            default: return VibrationEffect.createWaveform(waveform(type), -1); // error
        }
    }

    /** {delay, on, off, on, …} in ms for devices without predefined effects. */
    private static long[] waveform(String type) {
        switch (type) {
            case "light":
            case "selection": return new long[]{0, 10};
            case "medium": return new long[]{0, 20};
            case "heavy": return new long[]{0, 35};
            case "success": return new long[]{0, 15, 60, 25};
            case "warning": return new long[]{0, 25, 80, 25};
            default: return new long[]{0, 30, 60, 30, 60, 40}; // error
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator vibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager m = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return m != null ? m.getDefaultVibrator() : null;
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
