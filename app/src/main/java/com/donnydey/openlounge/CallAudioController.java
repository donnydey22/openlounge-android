package com.donnydey.openlounge;

/**
 * Owns the small, platform-independent state machine around a native call's audio session.
 *
 * The WebView remains the owner of WebRTC tracks. This controller only establishes Android's
 * communication mode, voice focus, and selected output route while a call is active. In
 * particular, failure to obtain focus must never block the call or its raw microphone track.
 */
final class CallAudioController {
    interface Platform {
        void enterCommunicationMode();
        boolean requestVoiceFocus();
        void applyRoute(boolean speakerRequested);
        void clearRoute();
        void abandonVoiceFocus();
        void restoreNormalMode();
    }

    private final Platform platform;
    private boolean active;
    private boolean focusRequested;

    CallAudioController(Platform platform) {
        this.platform = platform;
    }

    void setActive(boolean shouldBeActive, boolean speakerRequested) {
        if (shouldBeActive) {
            if (!active) {
                platform.enterCommunicationMode();
                active = true;
                focusRequested = platform.requestVoiceFocus();
            }
            // A route update is meaningful while the call is already active too.
            platform.applyRoute(speakerRequested);
            return;
        }

        if (!active) return;
        active = false;
        platform.clearRoute();
        if (focusRequested) {
            platform.abandonVoiceFocus();
            focusRequested = false;
        }
        platform.restoreNormalMode();
    }

    void applyRoute(boolean speakerRequested) {
        if (active) platform.applyRoute(speakerRequested);
    }

    boolean isActive() {
        return active;
    }
}
