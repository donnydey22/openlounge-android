package com.donnydey.openlounge.audio

import android.util.Log

/**
 * Sole native call audio engine: WebRTC AudioDeviceModule (AEC/NS/AGC, resampling, Opus, jitter) via ol_rtc_audio.cc.
 * Opens capture/playout ONLY after [CallAudioManager] reports READY. Backend/rate ladder (native rate → alternate
 * backend → 16 kHz only if supported) is an OL requirement behind parity validation, not a claimed Signal default.
 * No PJSIP sound device, bridge, or conference graph.
 *
 * License: links signalapp/webrtc (BSD-3) when built with OL_HAVE_WEBRTC. RingRTC (AGPL) is a behavior oracle only.
 */
object CallAudioEngine {
    private var loaded = false
    @Synchronized private fun load(): Boolean {
        if (!loaded) loaded = runCatching { System.loadLibrary("olrtcaudio"); true }.getOrDefault(false)
        return loaded
    }
    @JvmStatic private external fun nativeAvailable(): Boolean
    /** returns 0 or a negative engine code; [rates] tried in order with full cleanup between attempts. */
    @JvmStatic private external fun nativeOpen(inputId: Int, outputId: Int, rates: IntArray): Int
    @JvmStatic private external fun nativeActualRate(): Int
    @JvmStatic private external fun nativeSetMute(muted: Boolean)
    @JvmStatic private external fun nativeStats(): IntArray // captureCallbacks, renderCallbacks
    @JvmStatic private external fun nativeClose()

    const val ERR_UNAVAILABLE = -1000

    @JvmStatic fun available(): Boolean = load() && runCatching { nativeAvailable() }.getOrDefault(false)

    /** Open with explicit device IDs. Any failure is DEVICE/CODEC — never NETWORK. */
    @JvmStatic fun open(input: CallAudioDevices.Device?, output: CallAudioDevices.Device?, supportedRates: IntArray): CallFailure? {
        if (!available()) return CallFailure(CallFailureDomain.DEVICE, "engine-unavailable", ERR_UNAVAILABLE,
            "WebRTC ADM not linked (build without OL_HAVE_WEBRTC)")
        val ladder = buildList { add(48000); add(44100); if (16000 in supportedRates || supportedRates.isEmpty()) add(16000) }.distinct()
        val r = nativeOpen(input?.id ?: -1, output?.id ?: -1, ladder.toIntArray())
        Log.i("OLCallAudio", "engine-open status=$r actualRate=${if (r == 0) nativeActualRate() else 0} in=${input?.kind} out=${output?.kind}")
        return if (r == 0) null else CallFailure(CallFailureDomain.DEVICE, "engine-open", r, "webrtc adm open failed $r")
    }
    @JvmStatic fun setMute(m: Boolean) { if (loaded) runCatching { nativeSetMute(m) } }
    @JvmStatic fun stats(): IntArray = if (loaded) runCatching { nativeStats() }.getOrDefault(intArrayOf(0, 0)) else intArrayOf(0, 0)
    @JvmStatic fun close() { if (loaded) runCatching { nativeClose() } }
}
