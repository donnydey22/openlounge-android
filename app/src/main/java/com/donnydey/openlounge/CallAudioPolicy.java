package com.donnydey.openlounge;

/** When the wrapper switches the device into call (communication) audio mode. Pure logic, unit-tested. */
final class CallAudioPolicy {
    private CallAudioPolicy() {}

    /**
     * Only once media is actually flowing. Entering MODE_IN_COMMUNICATION on call:outgoing silenced/attenuated the
     * web ringback (a media-stream audio element) on Android 0.3.6.
     */
    static boolean enterCommunicationModeOn(String bridgeEvent) {
        return "call:connected".equals(bridgeEvent);
    }

    /** Native outgoing ringback (the page skips its own when window.__olNativeRingback is set). */
    static boolean startRingbackOn(String bridgeEvent) {
        return "call:outgoing".equals(bridgeEvent);
    }

    static boolean stopRingbackOn(String bridgeEvent) {
        return "call:connected".equals(bridgeEvent) || "call:ended".equals(bridgeEvent);
    }

    /** incoming ringtone: reset the session to ring shape only when no call is live (ringtone only, never mid-call) */
    public static boolean prepareRingtoneOn(String bridgeEvent, boolean callLive) {
        return "call:ringtonePrepare".equals(bridgeEvent) && !callLive;
    }

    /**
     * Loudness (Oct 16, Donny/Keiko "not loud enough"): MODE_IN_COMMUNICATION plays on STREAM_VOICE_CALL, whose level
     * is separate from media volume and is often left low. On entering a call, raise it to at least 80% of max; never
     * lower a level the user already set higher. Returns the index to set, or -1 to leave it alone.
     */
    static int voiceCallVolumeFloor(int current, int max) {
        if (max <= 0) return -1;
        int floor = (int) Math.ceil(max * 0.8);
        return current < floor ? floor : -1;
    }
}
