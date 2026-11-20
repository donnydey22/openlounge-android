package com.donnydey.openlounge;

/**
 * Which foreground-service types the call service may claim. Pure logic, unit-tested.
 * Android 14+ throws (SecurityException / ForegroundServiceStartNotAllowedException) when a "microphone" FGS is
 * started while the app is not visible and no user action/exemption applies, which previously made CallService call
 * stopSelf() and lose the whole call FGS. phoneCall (MANAGE_OWN_CALLS) is always claimed; microphone only when the
 * permission is granted and the start comes from a visible activity or a user tap. If the combined start still
 * fails, the service retries with phoneCall only instead of dying.
 */
final class CallFgsPolicy {
    static final int PHONE_CALL = 4;   // ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    static final int MICROPHONE = 128; // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    private CallFgsPolicy() {}

    static int types(int sdk, boolean micGranted, boolean userVisibleStart) {
        if (sdk < 29) return 0;
        int t = PHONE_CALL;
        if (sdk >= 30 && micGranted && (sdk < 34 || userVisibleStart)) t |= MICROPHONE;
        return t;
    }

    /**
     * Re-start the call service (startForegroundService + startForeground) for this request? Not while the call
     * FGS is already up and the app is in the background: Android 12+ refuses a background FGS start, and on 14+ a
     * background re-start re-evaluates while-in-use eligibility, so startForeground lost the microphone type the
     * visible start had granted (call:connected or a link rejoin after a network change while the app was away
     * silenced the mic). The running FGS keeps its types; only its notification is refreshed.
     */
    static boolean restart(boolean fgsUp, boolean appVisible) {
        return !fgsUp || appVisible;
    }

    /** fallback after a failed startForeground: drop microphone, keep phoneCall; 0 = give up */
    static int fallback(int failedTypes) {
        return (failedTypes & MICROPHONE) != 0 ? (failedTypes & ~MICROPHONE) : 0;
    }
}
