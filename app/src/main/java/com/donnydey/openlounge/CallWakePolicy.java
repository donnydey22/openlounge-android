package com.donnydey.openlounge;

/** Pure wake-lock / audio-focus decisions for a live call. Unit-tested. */
final class CallWakePolicy {
    /** safety cap so a leaked partial wake lock can never hold the CPU forever (re-acquired on each route change) */
    static final long PARTIAL_TIMEOUT_MS = 4L * 60 * 60 * 1000;

    private CallWakePolicy() {}

    /** proximity screen-off only while a call is live and audio is on the handset earpiece (phone at the ear) */
    static boolean proximity(boolean callLive, CallAudioRoute.Device route) {
        return callLive && route == CallAudioRoute.Device.EARPIECE;
    }

    enum FocusAction { NONE, NOTIFY_LOST, NOTIFY_REGAINED }

    /**
     * Audio focus changes during a call. Communication audio is not ducked or paused for transient/duck losses
     * (notification sounds, navigation prompts): we keep media flowing. A permanent loss (e.g. a cellular call
     * answered) is reported to the page so it can show "on hold"/hang up; nothing is torn down natively.
     */
    static FocusAction onFocusChange(int change) {
        switch (change) {
            case -1: return FocusAction.NOTIFY_LOST;      // AUDIOFOCUS_LOSS
            case 1: return FocusAction.NOTIFY_REGAINED;   // AUDIOFOCUS_GAIN
            default: return FocusAction.NONE;             // LOSS_TRANSIENT(-2), LOSS_TRANSIENT_CAN_DUCK(-3)
        }
    }
}
