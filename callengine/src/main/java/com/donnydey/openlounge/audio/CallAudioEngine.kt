package com.donnydey.openlounge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sole native call audio engine: one WebRTC AudioDeviceModule (JavaAudioDeviceModule; WebRTC APM does AEC/NS/AGC,
 * resampling, Opus, jitter) feeding one PeerConnectionFactory. Opened ONLY after [CallAudioManager] READY.
 * Capture/playout start when the PeerConnection negotiates audio. Video codecs (HW first) on OlVideo.egl.
 *
 * Library: BSD stock Google libwebrtc m152 AAR (interim; target signalapp/webrtc 8037b). RingRTC (AGPL) = oracle only.
 * Every failure here is DEVICE/CODEC — never NETWORK, never allowRelay.
 */
object CallAudioEngine {
    const val ERR_UNAVAILABLE = -1000
    const val ERR_RECORD = -1001
    const val ERR_TRACK = -1002
    private var initialized = false
    @Volatile var adm: JavaAudioDeviceModule? = null; private set
    @Volatile var factory: PeerConnectionFactory? = null; private set
    @Volatile var lastError: CallFailure? = null; private set
    var onRuntimeError: ((CallFailure) -> Unit)? = null
    private val capCb = AtomicInteger(); private val renCb = AtomicInteger()
    private var appCtx: Context? = null

    @JvmStatic fun init(ctx: Context) { appCtx = ctx.applicationContext }

    @JvmStatic @Synchronized fun available(): Boolean {
        val ctx = appCtx ?: return false
        if (!initialized) initialized = runCatching {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()); true
        }.onFailure { Log.e(TAG, "webrtc init failed", it) }.getOrDefault(false)
        return initialized
    }

    private fun runtime(step: String, code: Int, msg: String) {
        val f = CallFailure(CallFailureDomain.DEVICE, step, code, msg); lastError = f
        Log.e(TAG, f.toJson()); onRuntimeError?.invoke(f)
    }

    /** Build ADM + factory. Device routing follows AudioManager communication device set by CallAudioManager. */
    @JvmStatic @Synchronized fun open(input: CallAudioDevices.Device?, output: CallAudioDevices.Device?, supportedRates: IntArray): CallFailure? {
        if (!available()) return CallFailure(CallFailureDomain.DEVICE, "engine-unavailable", ERR_UNAVAILABLE, "WebRTC not loadable")
        val ctx = appCtx!!
        if (factory != null) return null
        lastError = null; capCb.set(0); renCb.set(0)
        return runCatching {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val a = JavaAudioDeviceModule.builder(ctx)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioAttributes(attrs)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(m: String) = runtime("record-init", ERR_RECORD, m)
                    override fun onWebRtcAudioRecordStartError(c: JavaAudioDeviceModule.AudioRecordStartErrorCode, m: String) = runtime("record-start", ERR_RECORD, "$c $m")
                    override fun onWebRtcAudioRecordError(m: String) = runtime("record", ERR_RECORD, m)
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(m: String) = runtime("playout-init", ERR_TRACK, m)
                    override fun onWebRtcAudioTrackStartError(c: JavaAudioDeviceModule.AudioTrackStartErrorCode, m: String) = runtime("playout-start", ERR_TRACK, "$c $m")
                    override fun onWebRtcAudioTrackError(m: String) = runtime("playout", ERR_TRACK, m)
                })
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() { capCb.incrementAndGet(); Log.i(TAG, "capture started") }
                    override fun onWebRtcAudioRecordStop() { Log.i(TAG, "capture stopped") }
                })
                .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                    override fun onWebRtcAudioTrackStart() { renCb.incrementAndGet(); Log.i(TAG, "playout started") }
                    override fun onWebRtcAudioTrackStop() { Log.i(TAG, "playout stopped") }
                })
                .createAudioDeviceModule()
            adm = a
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(a)
                .setVideoEncoderFactory(com.donnydey.openlounge.rtc.OlVideo.encoderFactory())
                .setVideoDecoderFactory(com.donnydey.openlounge.rtc.OlVideo.decoderFactory())
                .createPeerConnectionFactory()
            Log.i(TAG, "engine-open in=${input?.kind} out=${output?.kind} rates=${supportedRates.joinToString()}")
            null
        }.getOrElse { e -> close(); CallFailure(CallFailureDomain.DEVICE, "engine-open", ERR_UNAVAILABLE, e.toString()) }
    }
    @JvmStatic fun setMute(m: Boolean) { adm?.setMicrophoneMute(m) }
    /** captureStarts, playoutStarts */
    @JvmStatic fun stats(): IntArray = intArrayOf(capCb.get(), renCb.get())
    @JvmStatic @Synchronized fun close() {
        runCatching { factory?.dispose() }; factory = null
        runCatching { adm?.release() }; adm = null
    }
    private const val TAG = "OLCallAudio"
}
