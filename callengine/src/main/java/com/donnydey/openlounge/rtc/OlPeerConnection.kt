package com.donnydey.openlounge.rtc

/**
 * Thin JNI over libwebrtc PeerConnection (signalapp/webrtc 8037b, BSD-3) in cpp/ol_rtc_pc.cc.
 * Audio m-line Opus, one audio track sourced from the CallAudioEngine ADM. Config per contract:
 * iceTransportPolicy all, bundle max-bundle, rtcpMux require. Async results are drained with [take].
 * Without OL_HAVE_WEBRTC every call returns ERR_UNAVAILABLE (typed DEVICE failure upstream; never relay).
 */
object OlPeerConnection {
    const val ERR_UNAVAILABLE = -1000
    private var loaded = false
    @Synchronized fun load(): Boolean { if (!loaded) loaded = runCatching { System.loadLibrary("olrtcaudio"); true }.getOrDefault(false); return loaded }
    @JvmStatic external fun nativeCreate(iceServersJson: String): Int          // 0 ok
    @JvmStatic external fun nativeSetConfiguration(iceServersJson: String): Int
    @JvmStatic external fun nativeCreateOffer(iceRestart: Boolean): Int         // -> ev "sdp"
    @JvmStatic external fun nativeCreateAnswer(): Int                           // -> ev "sdp"
    @JvmStatic external fun nativeSetRemote(type: String, sdp: String): Int     // -> ev "remote-set"
    @JvmStatic external fun nativeAddCandidate(cand: String, mid: String, idx: Int): Int
    @JvmStatic external fun nativeSetMute(muted: Boolean)
    /** "sdp\n<type>\n<sdp>" | "cand\n<mid>\n<idx>\n<cand>" | "cand-end" | "ice\n<state>" | "remote-set" | "error\n<step>\n<code>" | null */
    @JvmStatic external fun nativeTake(): String?
    @JvmStatic external fun nativeClose()
    fun available(): Boolean = load() && runCatching { nativeCreate("probe") != ERR_UNAVAILABLE }.getOrDefault(false).also { if (it) runCatching { nativeClose() } }
}
