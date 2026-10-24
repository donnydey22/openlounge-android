package com.donnydey.openlounge

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.donnydey.openlounge.p2p.StreamCallProfile
import com.donnydey.openlounge.p2p.StreamCallSession
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.VideoTrack

/**
 * Keiko's Android call layer on Stream WebRTC Android 1.3.10 (Donny, Oct 24 2026 5:50 AM MST). Replaces the
 * libmediasoup-android (crow-misia org.webrtc) native lane and the native LiveKit path, which clashed with Stream's
 * org.webrtc. Structure mirrors ChatSwap's Kotlin call layer: one session object (StreamCallSession ≈
 * WebRtcBaseActivity's engine), signaling events relayed through the call-signal service (offer / answer / ice).
 *
 * UDP layer first (Donny 5:50 AM): direct UDP (host/srflx via STUN on the droplet) → TURN UDP 3478 → TCP 3478 →
 * TURNS 443; ICE restart on every drop with backoff; probe back to UDP while on relay (StreamCallProfile).
 *
 * Page → app ops (OpenLoungeNativeMedia.nativeMedia(cmd)): prewarm | p2pStart{callId, role:'caller'|'callee', video,
 * iceServers?} | p2pSignal{type:'offer'|'answer'|'ice', sdp?, candidate?} | mute | restartIce | status | close.
 * App → page: CustomEvent 'openlounge:native-media' {state…} and {type:'signal', callId, t, sdp?, candidate?} which the
 * page forwards to call-signal. The old mediasoup ops (connect / connectLiveKit) are rejected, so the page keeps the
 * WebView media path for mediasoup calls. Rooms never use this bridge (WebView only).
 */
object NativeCallMedia {
    private const val TAG = "OLNativeMedia"

    interface Host {
        /** kept for bridge compatibility; nothing is relayed through it any more */
        fun relay(id: String, event: String, dataJson: String): Boolean
        fun emit(detailJson: String)
        fun nativeCallAudio(on: Boolean)
    }

    @JvmStatic @Volatile var host: Host? = null
    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private var session: StreamCallSession? = null
    private var callId: String? = null
    private var connected = false
    private var relayed = false
    private var focus: AudioFocusRequest? = null

    @JvmStatic fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext as Application
    }

    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val cmd = try { JSONObject(cmdJson) } catch (e: Exception) { return done(false, null, "bad command") }
        main.post {
            try { done(true, run(cmd)?.toString() ?: "null", null) }
            catch (e: Throwable) { Log.w(TAG, "op ${cmd.optString("op")} failed: $e"); done(false, null, e.message ?: e.toString()) }
        }
    }

    /** no app-originated requests remain (mediasoup relay removed) */
    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {}

    @JvmStatic fun onNetworkChange() = main.post { session?.restartIceNow("network") }

    @JvmStatic fun closeAll() = main.post { closeInner("call-ended") }

    @JvmStatic fun isActive(): Boolean = session != null

    @JvmStatic fun releaseIdle() {}

    /** Default ICE for the lane when the page sends none: UDP first on the droplet (StreamCallProfile.defaultIce). */
    fun iceFrom(arr: JSONArray?): List<StreamCallProfile.Ice> {
        if (arr == null || arr.length() == 0) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.opt("urls")
            val urls = when (u) { is JSONArray -> (0 until u.length()).map { u.optString(it) }; is String -> listOf(u); else -> emptyList() }
            StreamCallProfile.Ice(urls, o.optString("username").ifEmpty { null }, o.optString("credential").ifEmpty { null })
        }
    }

    private fun run(cmd: JSONObject): Any? {
        when (cmd.optString("op")) {
            "prewarm" -> return true
            "connect", "connectLiveKit" -> throw IllegalStateException("native mediasoup/livekit removed: Stream WebRTC P2P lane (p2pStart)")
            "p2pStart" -> {
                val id = cmd.optString("callId"); require(id.isNotEmpty()) { "callId required" }
                if (session != null && callId == id) return JSONObject().put("attached", true)
                closeInner("replaced")
                val ice = iceFrom(cmd.optJSONArray("iceServers")).ifEmpty { StreamCallProfile.defaultIce() }
                callId = id
                audioOn(true)
                val s = StreamCallSession(app, id, cmd.optBoolean("video", false), ice, object : StreamCallSession.Signal {
                    override fun send(type: String, sdp: String?, candidate: IceCandidate?, iceRestart: Boolean) {
                        val d = JSONObject().put("type", "signal").put("callId", id).put("t", type)
                        sdp?.let { d.put("sdp", it) }
                        if (iceRestart) d.put("iceRestart", true)
                        candidate?.let { d.put("candidate", JSONObject().put("candidate", it.sdp).put("sdpMid", it.sdpMid).put("sdpMLineIndex", it.sdpMLineIndex)) }
                        emit(d)
                    }
                }, object : StreamCallSession.Events {
                    override fun onConnected() { connected = true; emit(JSONObject().put("state", "connected")) }
                    override fun onReconnecting() { connected = false; emit(JSONObject().put("state", "disconnected").put("reconnecting", true)) }
                    override fun onPath(relayed: Boolean) { this@NativeCallMedia.relayed = relayed; emit(JSONObject().put("type", "path").put("relayed", relayed)) }
                    override fun onRemoteVideo(track: VideoTrack) { emit(JSONObject().put("type", "remoteVideo").put("on", true)) }
                    override fun onEnded(reason: String) { emit(JSONObject().put("state", "closed").put("reason", reason)) }
                })
                session = s
                if (cmd.optString("role") == "caller") s.offer()
                return JSONObject().put("attached", false)
            }
            "p2pSignal" -> {
                val s = session ?: throw IllegalStateException("no p2p session")
                when (cmd.optString("type")) {
                    "offer" -> s.onRemoteOffer(cmd.getString("sdp"))
                    "answer" -> s.onRemoteAnswer(cmd.getString("sdp"))
                    "ice" -> cmd.optJSONObject("candidate")?.let { c -> s.onRemoteIce(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))) }
                    else -> throw IllegalArgumentException("bad signal")
                }
                return true
            }
            "mute" -> { session?.setMuted(cmd.optBoolean("muted")); return true }
            "restartIce" -> { session?.restartIceNow("page"); return true }
            "status" -> return session?.let { JSONObject().put("callId", callId).put("kind", "stream-p2p").put("connected", connected).put("relayed", relayed) }
            "close" -> { closeInner("page"); return true }
        }
        throw IllegalArgumentException("unknown op")
    }

    private fun closeInner(reason: String) {
        session?.close(reason); session = null; callId = null; connected = false; relayed = false
        audioOn(false)
    }

    private fun emit(d: JSONObject) { val s = d.toString(); main.post { host?.emit(s) } }

    private fun audioOn(on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (on) {
            if (focus == null && Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { change ->
                        when (CallWakePolicy.onFocusChange(change)) {
                            CallWakePolicy.FocusAction.NOTIFY_LOST -> emit(JSONObject().put("type", "audioFocus").put("state", "lost"))
                            CallWakePolicy.FocusAction.NOTIFY_REGAINED -> emit(JSONObject().put("type", "audioFocus").put("state", "gained"))
                            else -> {}
                        }
                    }.build()
                runCatching { am.requestAudioFocus(r) }
                focus = r
            }
            host?.nativeCallAudio(true)
        } else {
            focus?.let { r -> if (Build.VERSION.SDK_INT >= 26) runCatching { am.abandonAudioFocusRequest(r) } }
            focus = null
        }
    }
}
