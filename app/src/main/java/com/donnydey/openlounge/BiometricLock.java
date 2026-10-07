package com.donnydey.openlounge;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/**
 * Opt-in biometric app lock — a convenience layer over the existing session (the session tokens stay where they
 * are, in SecureSessionStore). Nothing is signed out: the lock only gates the UI.
 *
 *  - availability: BiometricManager.canAuthenticate(BIOMETRIC_STRONG | DEVICE_CREDENTIAL) on API 30+.
 *    On API 28–29 that combination is not supported by BiometricPrompt, so BIOMETRIC_WEAK | DEVICE_CREDENTIAL is
 *    used there (androidx.biometric falls back to the keyguard screen on API 24–27).
 *  - no device security at all (no PIN/pattern/password) → unavailable → the setting is off and cannot be enabled;
 *    an earlier "enabled" flag is ignored (never lock someone out of their own app).
 *  - lock triggers: cold start and resume after more than {@link #LOCK_AFTER_MS} in the background.
 */
public final class BiometricLock {
    public static final long LOCK_AFTER_MS = 60_000L; // configurable later
    private static final String NAME = "openlounge_prefs";
    private static final String KEY_ENABLED = "biometric_lock_enabled";

    public interface Result {
        /** status: "success" | "cancelled" | "failed" | "unavailable" */
        void done(@NonNull String status, String message);
    }

    private BiometricLock() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int authenticators() {
        if (Build.VERSION.SDK_INT >= 30)
            return BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        return BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }

    /** True when the device has a strong biometric or at least a screen lock (PIN/pattern/password). */
    public static boolean isAvailable(Context ctx) {
        try {
            int r = BiometricManager.from(ctx).canAuthenticate(authenticators());
            if (r == BiometricManager.BIOMETRIC_SUCCESS) return true;
        } catch (Exception ignored) {
        }
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    public static boolean isEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_ENABLED, false) && isAvailable(ctx);
    }

    public static void setEnabledFlag(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).commit();
    }

    /** Pure decision used by MainActivity (unit-testable). backgroundedAt <= 0 means "cold start". */
    public static boolean shouldLock(boolean enabled, long backgroundedAt, long now, long lockAfterMs) {
        if (!enabled) return false;
        if (backgroundedAt <= 0) return true;
        return now - backgroundedAt > lockAfterMs;
    }

    /**
     * Shows the system prompt. deviceCredentialOnly = the "Use another way" path (PIN / pattern / password).
     */
    public static void prompt(FragmentActivity activity, String title, String subtitle, boolean deviceCredentialOnly, Result cb) {
        if (!isAvailable(activity)) {
            cb.done("unavailable", "No fingerprint, face or screen lock is set up on this device");
            return;
        }
        int auth = authenticators();
        if (deviceCredentialOnly) {
            // DEVICE_CREDENTIAL alone is only accepted by BiometricPrompt on API 30+
            auth = Build.VERSION.SDK_INT >= 30 ? BiometricManager.Authenticators.DEVICE_CREDENTIAL : auth;
        }
        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setConfirmationRequired(false);
        if (subtitle != null) b.setSubtitle(subtitle);
        b.setAllowedAuthenticators(auth); // androidx handles the keyguard fallback on API < 28
        BiometricPrompt p = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        cb.done("success", null);
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        boolean cancelled = code == BiometricPrompt.ERROR_USER_CANCELED
                                || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || code == BiometricPrompt.ERROR_CANCELED;
                        boolean none = code == BiometricPrompt.ERROR_NO_BIOMETRICS
                                || code == BiometricPrompt.ERROR_HW_NOT_PRESENT
                                || code == BiometricPrompt.ERROR_HW_UNAVAILABLE
                                || code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL;
                        cb.done(cancelled ? "cancelled" : none ? "unavailable" : "failed", msg.toString());
                    }
                    // onAuthenticationFailed (one bad finger) is not terminal: the prompt stays up and retries
                });
        try {
            p.authenticate(b.build());
        } catch (Exception e) {
            cb.done("failed", e.getMessage());
        }
    }
}
