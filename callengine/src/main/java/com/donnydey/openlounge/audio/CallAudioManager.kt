package com.donnydey.openlounge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Sole owner of call audio focus / mode / route (clean-room; oracle Signal SignalAudioManager + FullSignalAudioManagerApi31,
 * RingRTC 2.74.0). Serialized on its own thread. Ordering requirement from 4cabb4a preserved WITHOUT a blocking poll:
 * focus → request MODE_IN_COMMUNICATION → observe applied mode (OnModeChangedListener on API31+, non-blocking re-check
 * on a scheduled tick below) → route → ready callback → only then may [CallAudioEngine] open devices.
 * Rooms keep their own (non-call) audio path in MainActivity; this class never touches it.
 */
class CallAudioManager private constructor(ctx: Context) {
    enum class State { IDLE, PREPARING, READY, FAILED }
    fun interface Ready { fun onResult(failure: CallFailure?) }

    private val app = ctx.applicationContext
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val thread = HandlerThread("ol-call-audio").apply { start() }
    private val h = Handler(thread.looper)
    val devices = CallAudioDevices(app, h)

    @Volatile var state = State.IDLE; private set
    @Volatile private var generation = 0
    private var modeBefore = AudioManager.MODE_NORMAL
    private var focusReq: AudioFocusRequest? = null
    private var modeListener: Any? = null
    private var video = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        trace("focus-change", "change=$change")
        if (change == AudioManager.AUDIOFOCUS_GAIN && state == State.READY) h.post { applyRoute() }
    }

    /** Begin preparing call audio for [callGen]. [ready] fires once, after focus+mode are applied (or a typed failure). */
    fun prepare(video: Boolean, ready: Ready) = h.post {
        val g = ++generation; this.video = video; state = State.PREPARING
        trace("prepare", "gen=$g video=$video api=${Build.VERSION.SDK_INT}")
        if (!requestFocus()) trace("focus-denied", "continuing; Signal retries focus on gain")
        modeBefore = am.mode
        try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (e: RuntimeException) {
            return@post finish(g, ready, CallFailure(CallFailureDomain.SESSION, "set-mode", -1, e.toString()))
        }
        devices.onChanged = { if (state == State.READY) applyRoute() }
        devices.start()
        awaitMode(g, SystemClock.elapsedRealtime(), ready)
    }

    /** Non-blocking: re-checks on the audio thread every 20 ms (no UI-thread sleep), bounded 1.5 s. */
    private fun awaitMode(g: Int, t0: Long, ready: Ready) {
        if (g != generation) return ready.onResult(CallFailure(CallFailureDomain.SESSION, "cancelled", 0, "call ended during prepare"))
        val ms = SystemClock.elapsedRealtime() - t0
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            trace("audio-mode-applied", "ms=$ms"); applyRoute(); return finish(g, ready, null)
        }
        if (ms > 1500) {
            // Some OEMs report mode late; Signal proceeds. Log as degraded, not as a failure, and never as network.
            trace("audio-mode-late", "mode=${am.mode} ms=$ms"); applyRoute(); return finish(g, ready, null)
        }
        h.postDelayed({ awaitMode(g, t0, ready) }, 20)
    }

    private fun finish(g: Int, ready: Ready, f: CallFailure?) {
        if (g != generation) return
        state = if (f == null) State.READY else State.FAILED
        trace("prepare-done", f?.toJson() ?: "ok"); ready.onResult(f)
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, h).build()
        focusReq = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun select(kind: CallAudioDevices.Kind?) = h.post { devices.userPick = kind; applyRoute() }

    @Suppress("DEPRECATION")
    private fun applyRoute() {
        val target = devices.choose(video) ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull { CallAudioDevices.kindOf(it.type) == target.kind }
            if (dev != null) runCatching { am.setCommunicationDevice(dev) }
        } else {
            val bt = target.kind == CallAudioDevices.Kind.BLUETOOTH_SCO
            if (bt) runCatching { am.startBluetoothSco(); am.isBluetoothScoOn = true }
            else runCatching { if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() } }
            am.isSpeakerphoneOn = target.kind == CallAudioDevices.Kind.SPEAKER
        }
        trace("route", "target=${target.kind} id=${target.id}")
    }

    /** Release everything; cancels any pending prepare (generation bump). */
    @Suppress("DEPRECATION")
    fun release() = h.post {
        generation++
        if (state == State.IDLE) return@post
        trace("release", "")
        devices.stop(); devices.onChanged = null
        runCatching { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else { am.isBluetoothScoOn = false; am.stopBluetoothSco() } }
        runCatching { am.isSpeakerphoneOn = false }
        runCatching { am.mode = modeBefore }
        focusReq?.let { runCatching { am.abandonAudioFocusRequest(it) } }; focusReq = null
        state = State.IDLE
    }

    var tracer: ((String, String) -> Unit)? = null
    private fun trace(ev: String, d: String) { Log.i("OLCallAudio", "$ev $d"); tracer?.invoke(ev, d) }

    companion object {
        @Volatile private var inst: CallAudioManager? = null
        @JvmStatic fun get(ctx: Context): CallAudioManager = inst ?: synchronized(this) { inst ?: CallAudioManager(ctx).also { inst = it } }
    }
}
