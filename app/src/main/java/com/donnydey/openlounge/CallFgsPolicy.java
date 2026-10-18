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

    /** fallback after a failed startForeground: drop microphone, keep phoneCall; 0 = give up */
    static int fallback(int failedTypes) {
        return (failedTypes & MICROPHONE) != 0 ? (failedTypes & ~MICROPHONE) : 0;
    }
}
