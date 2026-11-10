package com.donnydey.openlounge.olcall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.olwrtc.ContextUtils
import org.olwrtc.EglBase
import org.olwrtc.RendererCommon
import org.olwrtc.SurfaceViewRenderer
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.voip.VideoCapturerDevice

/**
 * One OL phone call (audio or video) on the tgcalls engine, for the EXISTING
 * Open Lounge call screen. This class renders nothing itself: the original OL
 * call UI (peer avatar in the squircle frame, name, timer, mute / camera /
 * speaker / hang-up controls) stays exactly as it is in the app repo and is
 * driven through [OlCallUi.render]. Video is drawn into two SurfaceViewRenderers
 * the screen already lays out (remote full-bleed, local preview); call
 * [attachRenderers] with them before [start].
 *
 * Lifecycle: the app's existing ringing flow (OL backend call:start / accept)
 * stays unchanged; on accept the backend hands each party an olcall join token
 * (docs/PROTOCOL.md "Integration with the existing OL WS"). Then:
 *   OlCallKit.init(appContext)            // once, Application.onCreate
 *   val s = OlCallSession(ctx, url, token, peer, video = true, ui = screen)
 *   s.attachRenderers(remoteView, localView)
 *   s.start()
 *   ... s.setMuted / s.setCameraEnabled / s.switchCamera / s.setSpeaker ...
 *   s.hangup()
 */
class OlCallSession(
    context: Context,
    serverUrl: String,
    joinToken: String,
    private val peer: Peer,
    private val video: Boolean,
    private val ui: OlCallUi,
    crumbs: OlBreadcrumbs = OlBreadcrumbs(OlCallKit.crumbUrlFor(serverUrl), OlTgcallsEngine.ENGINE),
    /** CallMedia.iceServers from the OL JS layer (optional; the server's joined frame wins). */
    private val appIceServers: org.json.JSONArray? = null,
) {
    data class Peer(val userId: String, val displayName: String, val avatarUrl: String?)

    enum class Phase { Connecting, InCall, Reconnecting, Ended }

    /** Everything the existing call screen needs to draw itself. */
    data class UiState(
        val peer: Peer,
        val phase: Phase,
        val isVideoCall: Boolean,
        val elapsedSec: Long,
        val muted: Boolean,
        val cameraOn: Boolean,
        val frontCamera: Boolean,
        val speakerOn: Boolean,
        val remoteMuted: Boolean,
        val remoteVideoOn: Boolean,
        /** Non-null once ended: "local-hangup", "bye:...", "ws-failure", "tg-failed", ... */
        val endReason: String? = null,
        /** Verbatim native audio-open status when the microphone could not be opened. */
        val audioError: String? = null,
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var connectedAt = 0L
    private var remote: SurfaceViewRenderer? = null
    private var local: SurfaceViewRenderer? = null

    @Volatile private var state = UiState(
        peer = peer, phase = Phase.Connecting, isVideoCall = video, elapsedSec = 0, muted = false,
        cameraOn = video, frontCamera = true, speakerOn = video, remoteMuted = false, remoteVideoOn = false,
    )

    private val engineListener = object : OlTgcallsEngine.Listener {
        override fun onState(s: OlTgcallsEngine.State) = update {
            when (s) {
                OlTgcallsEngine.State.Established -> {
                    if (connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime()
                    it.copy(phase = Phase.InCall)
                }
                OlTgcallsEngine.State.Reconnecting -> if (connectedAt == 0L) it else it.copy(phase = Phase.Reconnecting)
                OlTgcallsEngine.State.Failed -> { main.post { finish("tg-failed") }; it }
                else -> it
            }
        }
        override fun onAudioOpenReport(json: String) {
            if (JSONObject(json).optInt("ok") != 1) update { it.copy(audioError = json) }
        }
        override fun onRemoteMedia(audioActive: Boolean, video: OlTgcallsEngine.RemoteVideo) = update {
            it.copy(remoteMuted = !audioActive, remoteVideoOn = video == OlTgcallsEngine.RemoteVideo.Active)
        }
        override fun onStats(json: JSONObject) {}
        override fun onEnded(reason: String) { main.post { finish(reason) } }
    }

    private lateinit var engine: OlTgcallsEngine
    private val engineArgs = Triple(serverUrl, joinToken, crumbs)

    /** Remote = far side's video (full screen); local = camera preview. Either may be null for audio calls. */
    fun attachRenderers(remoteView: SurfaceViewRenderer?, localView: SurfaceViewRenderer?) {
        val egl: EglBase.Context = VideoCapturerDevice.getSharedEGLContext()
        remoteView?.apply { init(egl, null); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); setEnableHardwareScaler(true) }
        localView?.apply { init(egl, null); setMirror(true); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); setZOrderMediaOverlay(true) }
        remote = remoteView
        local = localView
    }

    fun start() {
        startAudio()
        val (url, token, crumbs) = engineArgs
        engine = OlTgcallsEngine(url, token, crumbs, engineListener, startWithVideo = video,
            remoteVideoSink = remote, localVideoSink = local, appIceServers = appIceServers)
        engine.start(device = Build.MODEL, os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        tick()
        push()
    }

    fun setMuted(muted: Boolean) { engine.setMuted(muted); update { it.copy(muted = muted) } }

    fun setCameraEnabled(on: Boolean) { engine.setVideoEnabled(on); update { it.copy(cameraOn = on, isVideoCall = it.isVideoCall || on) } }

    fun switchCamera() {
        val front = !state.frontCamera
        engine.switchCamera(front)
        local?.setMirror(front)
        update { it.copy(frontCamera = front) }
    }

    fun setSpeaker(on: Boolean) {
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = on
        update { it.copy(speakerOn = on) }
    }

    fun hangup() = engine.hangup()

    // ---------------------------------------------------------------- internals

    private fun update(f: (UiState) -> UiState) {
        synchronized(this) { state = f(state) }
        push()
    }

    private fun push() {
        val s = state
        main.post { ui.render(s) }
    }

    private fun tick() {
        if (state.phase == Phase.Ended) return
        if (connectedAt != 0L) {
            val sec = (SystemClock.elapsedRealtime() - connectedAt) / 1000
            if (sec != state.elapsedSec) update { it.copy(elapsedSec = sec) }
        }
        main.postDelayed({ tick() }, 500)
    }

    private fun finish(reason: String) {
        if (state.phase == Phase.Ended) return
        update { it.copy(phase = Phase.Ended, endReason = reason) }
        stopAudio()
        remote?.release(); local?.release()
        remote = null; local = null
    }

    private fun startAudio() {
        savedMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = video // video calls default to speaker, audio calls to earpiece (OL behaviour)
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            audio.requestAudioFocus(req)
            focus = req
        }
    }

    private fun stopAudio() {
        if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = savedMode
    }
}

/** Implemented by the existing OL call screen (Activity/Fragment/Compose host). Called on the main thread. */
fun interface OlCallUi {
    fun render(state: OlCallSession.UiState)
}

/** Process-wide setup. Call once from Application.onCreate. */
object OlCallKit {
    fun init(appContext: Context) {
        ContextUtils.initialize(appContext.applicationContext)
        // Telegram's org.olwrtc (OrientationHelper) reads this; OL shim in libwebrtc-java.jar.
        ApplicationLoader.applicationContext = appContext.applicationContext
    }

    /** Breadcrumbs go to the same self-hosted olcall server: wss://host/v1/call -> https://host/v1/crumbs. */
    fun crumbUrlFor(callUrl: String): String {
        val u = android.net.Uri.parse(callUrl)
        val scheme = if (u.scheme == "ws") "http" else "https"
        return u.buildUpon().scheme(scheme).path("/v1/crumbs").clearQuery().build().toString()
    }
}
