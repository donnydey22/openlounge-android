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
}
