package com.donnydey.openlounge;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/**
 * Wake locks for the length of a call: PARTIAL (CPU stays awake when the screen goes off, capped by a timeout) and
 * PROXIMITY_SCREEN_OFF (screen off at the ear, only on the earpiece route). Both released on call end. Uses the
 * existing WAKE_LOCK permission.
 */
final class CallWakeLocks {
    private static final String TAG = "OLCalls";
    private final PowerManager pm;
    private PowerManager.WakeLock partial;
    private PowerManager.WakeLock prox;
    private boolean live;

    CallWakeLocks(Context ctx) {
        pm = (PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE);
    }

    void start() {
        live = true;
        if (pm == null) return;
        try {
            if (partial == null) {
                partial = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenLounge:call");
                partial.setReferenceCounted(false);
            }
            partial.acquire(CallWakePolicy.PARTIAL_TIMEOUT_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "partial wake lock failed: " + e.getClass().getSimpleName());
        }
    }

    void route(CallAudioRoute.Device selected) {
        if (pm == null) return;
        boolean want = CallWakePolicy.proximity(live, selected);
        try {
            if (want) {
                if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
                if (prox == null) {
                    prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "OpenLounge:callProximity");
                    prox.setReferenceCounted(false);
                }
                if (!prox.isHeld()) prox.acquire(CallWakePolicy.PARTIAL_TIMEOUT_MS);
            } else if (prox != null && prox.isHeld()) {
                prox.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "proximity wake lock failed: " + e.getClass().getSimpleName());
        }
    }

    void stop() {
        live = false;
        try { if (prox != null && prox.isHeld()) prox.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY); } catch (RuntimeException ignored) {}
        try { if (partial != null && partial.isHeld()) partial.release(); } catch (RuntimeException ignored) {}
    }
}
