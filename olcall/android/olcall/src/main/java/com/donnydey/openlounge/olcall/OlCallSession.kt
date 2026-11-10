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
import org.olwrtc.VideoFrame
import org.olwrtc.VideoSink
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
    /** Durable call log for this leg (OlCallLog); null = not logged. */
    private val callLog: OlCallLog? = null,
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
        /** Where call audio plays: speaker / earpiece / wired / usb / bluetooth (OlAudioRouting). */
        val audioRoute: String = if (isVideoCall) "speaker" else "earpiece",
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
    private val crumbs = crumbs

    // ---- t5450 video health: counting sinks in front of the renderers, 5 s stats, frozen-frame watchdog
    private class CountingSink(private val target: VideoSink) : VideoSink {
        @Volatile var frames = 0
        @Volatile var rotation = 0
        @Volatile var size = ""
        override fun onFrame(frame: VideoFrame) {
            frames++
            rotation = frame.rotation
            size = "${frame.rotatedWidth}x${frame.rotatedHeight}"
            target.onFrame(frame)
        }
        fun take(): Int { val n = frames; frames = 0; return n }
    }
    private var remoteCount: CountingSink? = null
    private var localCount: CountingSink? = null
    private val remoteWatch = OlStallWatch()
    private val localWatch = OlStallWatch()
    private var window = IntArray(3) // remote frames, local frames, ticks
    private var cameraRestarts = 0

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
        remoteCount = remote?.let { CountingSink(it) }
        localCount = local?.let { CountingSink(it) }
        engine = OlTgcallsEngine(url, token, crumbs, engineListener, startWithVideo = video,
            remoteVideoSink = remoteCount, localVideoSink = localCount, appIceServers = appIceServers, callLog = callLog)
        engine.start(device = Build.MODEL, os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        tick()
        main.postDelayed({ videoTick() }, 1000)
        push()
    }

    fun setMuted(muted: Boolean) { engine.setMuted(muted); update { it.copy(muted = muted) } }

    fun setCameraEnabled(on: Boolean) {
        engine.setVideoEnabled(on); update { it.copy(cameraOn = on, isVideoCall = it.isVideoCall || on) }
        if (on && !routeVideo) { routeVideo = true; main.post { applyRoute() } }
    }

    fun switchCamera() {
        val front = !state.frontCamera
        engine.switchCamera(front)
        local?.setMirror(front)
        update { it.copy(frontCamera = front) }
    }

    /** Speaker / Phone toggle (explicit pick). A connected headset still takes "Phone". */
    fun setSpeaker(on: Boolean) {
        speakerPick = on
        applyRoute()
    }

    fun hangup(cause: String = "app") = engine.hangup(cause)

    private fun videoTick() {
        val st = state
        if (st.phase == Phase.Ended) return
        val r = remoteCount?.take() ?: 0
        val l = localCount?.take() ?: 0
        window[0] += r; window[1] += l; window[2]++
        if (window[2] >= 5 && (st.cameraOn || st.remoteVideoOn || window[0] + window[1] > 0)) {
            val t = window[2]
            crumbs.event("video", "stats:remoteFps=${window[0] / t}:localFps=${window[1] / t}:remoteOn=${st.remoteVideoOn}:cameraOn=${st.cameraOn}" +
                ":remote=${remoteCount?.size ?: "-"}@${remoteCount?.rotation ?: 0}:local=${localCount?.size ?: "-"}@${localCount?.rotation ?: 0}" +
                ":stalls=${remoteWatch.stalls}/${localWatch.stalls}:cameraRestarts=$cameraRestarts")
            window = IntArray(3)
        } else if (window[2] >= 5) window = IntArray(3)
        val now = SystemClock.elapsedRealtime()
        val inCall = st.phase == Phase.InCall
        when (val a = remoteWatch.tick(now, r, inCall && st.remoteVideoOn && remoteCount != null)) {
            is OlStallWatch.Action.First -> { crumbs.event("video", "stall:remote:${a.stalledMs}ms:keyframe-request", "warning"); engine.requestKeyframe("stall-${a.stalledMs}ms") }
            is OlStallWatch.Action.Second -> { crumbs.event("video", "stall:remote:${a.stalledMs}ms:keyframe-request", "warning"); engine.requestKeyframe("stall-${a.stalledMs}ms") }
            is OlStallWatch.Action.Recovered -> crumbs.event("video", "recovered:remote:after=${a.stalledMs}ms")
            OlStallWatch.Action.None -> {}
        }
        when (val a = localWatch.tick(now, l, inCall && st.cameraOn && localCount != null)) {
            is OlStallWatch.Action.First -> crumbs.event("video", "stall:capture:${a.stalledMs}ms", "warning")
            is OlStallWatch.Action.Second -> {
                // the camera stopped delivering frames (capturer stall, camera taken away): restart only the camera
                cameraRestarts++
                crumbs.event("video", "stall:capture:${a.stalledMs}ms:camera-restart", "warning")
                engine.setVideoEnabled(false)
                main.postDelayed({ if (state.phase != Phase.Ended && state.cameraOn) engine.setVideoEnabled(true) }, 300)
            }
            is OlStallWatch.Action.Recovered -> crumbs.event("video", "recovered:capture:after=${a.stalledMs}ms")
            OlStallWatch.Action.None -> {}
        }
        main.postDelayed({ videoTick() }, 1000)
    }

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

    // ---------------------------------------------------------------- audio route (headset first, OL defaults)
    private var speakerPick: Boolean? = null
    private var routeVideo = video
    private var scoOn = false
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { main.post { applyRoute() } }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { main.post { applyRoute() } }
    }

    private fun kindOf(type: Int): OlAudioRouting.Kind? = when (type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OlAudioRouting.Kind.SPEAKER
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OlAudioRouting.Kind.EARPIECE
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OlAudioRouting.Kind.WIRED
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> OlAudioRouting.Kind.USB
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OlAudioRouting.Kind.BLUETOOTH
        else -> if (Build.VERSION.SDK_INT >= 31 && type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) OlAudioRouting.Kind.BLUETOOTH else null
    }

    private fun applyRoute() {
        if (state.phase == Phase.Ended) return
        runCatching {
            val chosen: OlAudioRouting.Kind
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = audio.availableCommunicationDevices
                val byKind = devs.mapNotNull { d -> kindOf(d.type)?.let { it to d } }.toMap()
                chosen = OlAudioRouting.choose(byKind.keys, routeVideo, speakerPick)
                byKind[chosen]?.let { audio.setCommunicationDevice(it) }
            } else {
                val kinds = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { kindOf(it.type) }.toMutableSet()
                kinds += OlAudioRouting.Kind.SPEAKER
                if (app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) kinds += OlAudioRouting.Kind.EARPIECE
                chosen = OlAudioRouting.choose(kinds, routeVideo, speakerPick)
                @Suppress("DEPRECATION")
                if (chosen == OlAudioRouting.Kind.BLUETOOTH) {
                    audio.isSpeakerphoneOn = false
                    if (!scoOn) { audio.startBluetoothSco(); audio.isBluetoothScoOn = true; scoOn = true }
                } else {
                    if (scoOn) { audio.isBluetoothScoOn = false; audio.stopBluetoothSco(); scoOn = false }
                    audio.isSpeakerphoneOn = chosen == OlAudioRouting.Kind.SPEAKER
                }
            }
            val label = OlAudioRouting.label(chosen)
            if (label != state.audioRoute || (chosen == OlAudioRouting.Kind.SPEAKER) != state.speakerOn)
                update { it.copy(audioRoute = label, speakerOn = chosen == OlAudioRouting.Kind.SPEAKER) }
        }
    }

    private fun startAudio() {
        savedMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        // headset first, else video calls on the speaker and audio calls on the earpiece (OL behaviour)
        applyRoute()
        runCatching { audio.registerAudioDeviceCallback(deviceCallback, main) }
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
        runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            if (scoOn) { audio.isBluetoothScoOn = false; audio.stopBluetoothSco(); scoOn = false }
        }
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
