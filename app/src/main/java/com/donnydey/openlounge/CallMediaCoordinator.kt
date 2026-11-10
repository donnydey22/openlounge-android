package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.signal.CallSignalClient
import org.json.JSONObject

/**
 * Native call media: ONE engine, olcall. Every 1:1 audio/video call is a tgcalls instance (the :olcall module) whose
 * signaling blobs ride wss://turn.openlounge.app/v1/call to the Go/Pion server, with the coturn relay list the server
 * (or Railway's CallMedia.iceServers) hands out. There is no other engine in this app: no BSD/Stream WebRTC
 * PeerConnection, no RingRTC/X3DH rc:* session, no p2p leg, no fallback. A device or a CallMedia the olcall engine
 * cannot run (API < 27, non-arm ABI, provider != "olcall", no token) fails the connect with "olcall-unavailable".
 * Same host surface as before, so MainActivity / SessionBridge stay unchanged; the WebView never captures call media.
 */
object CallMediaCoordinator {
    interface Host {
        fun relay(id: String, event: String, dataJson: String): Boolean
        fun emit(detailJson: String)
        fun nativeCallAudio(on: Boolean)
        /** show/hide the native remote + local video tiles under the (transparent) WebView */
        fun nativeCallVideo(on: Boolean) {}
        /** t5456: first inbound media for [id]; the call screen's timer may start (MainActivity gates call:connected on it) */
        fun callMediaFlowing(id: String) {}
    }
    /** t5456: id of the call whose media is flowing (main thread). */
    @JvmStatic @Volatile var mediaFlowingId: String? = null
        private set

    @JvmStatic @Volatile var host: Host? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var appCtx: Context? = null
    @JvmStatic fun init(ctx: Context) { appCtx = ctx.applicationContext; CallDiskLog.init(ctx); runCatching { OlCallLogs.init(ctx) } }
    @Volatile private var videoOn = false
    @Volatile private var muted = false
    @Volatile private var callId: String? = null
    /** in-call route pick from the call screen: null = automatic (headset first, else video->speaker / audio->earpiece) */
    @Volatile private var speakerPick: Boolean? = null

    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val c = runCatching { JSONObject(cmdJson) }.getOrNull() ?: return done(false, null, "bad command")
        val op = c.optString("op")
        CallDiskLog.mark("op", op)
        when (op) {
            "close" -> { closeAll("page-close"); done(true, "true", null) }
            // legacy p2p page ops (rc:* sealed session, rtc:* ICE/SDP): the p2p engine is gone and the server refuses
            // these events (p2p_removed); acknowledged and ignored so an old page build never errors.
            "signalConnected", "rcMsg", "rtcSignal" -> done(true, "true", null)
            "prewarm" -> done(true, JSONObject().put("engine", "olcall")
                .put("available", OlcallRoute.deviceSupported(android.os.Build.VERSION.SDK_INT, android.os.Build.SUPPORTED_ABIS.toList())).toString(), null)
            "connect" -> {
                val id = c.optString("callId"); if (id.isEmpty()) return done(false, null, "callId required")
                callId = id
                val media = c.optJSONObject("media")
                val wantVideo = c.optBoolean("video")
                val action = if (wantVideo) OlCallPermissions.Action.START_OR_ACCEPT_VIDEO
                    else OlCallPermissions.Action.START_OR_ACCEPT_AUDIO
                // mic (audio) or mic+camera in one combined request (video), only at this connect tap; denial is silent
                ensurePerms(action) { ok ->
                    if (!ok) { OlCallLogs.failedBeforeStart(id, media?.optString("token"), wantVideo, "permission-denied"); return@ensurePerms done(false, null, "permission-denied") }
                    if (callId != id) return@ensurePerms done(false, null, "cancelled")
                    if (!OlcallRoute.eligible(media, android.os.Build.VERSION.SDK_INT, android.os.Build.SUPPORTED_ABIS.toList())) {
                        CallDiskLog.mark("olcall-unavailable", "sdk=${android.os.Build.VERSION.SDK_INT} provider=${media?.optString("provider") ?: ""}")
                        OlCallLogs.failedBeforeStart(id, media?.optString("token"), wantVideo, "olcall-unavailable")
                        return@ensurePerms done(false, null, "olcall-unavailable")
                    }
                    startOlcall(id, media!!, wantVideo, c.optString("peerId"), done)
                }
            }
            "camera" -> {
                val on = c.optBoolean("on"); val face = c.optString("facing", "user")
                if (!on) main.post { camera(false, face, done) }
                else ensurePerms(OlCallPermissions.Action.TOGGLE_VIDEO_ON) { ok ->
                    // denied camera mid-call: leave the call on audio, no rationale / no camera-unavailable toast
                    if (!ok) return@ensurePerms done(false, null, "permission-denied")
                    camera(true, face, done)
                }
            }
            "mute" -> { val m = c.optBoolean("muted"); muted = m; olcall?.setMuted(m); done(true, "true", null) }
            "speaker" -> { setSpeaker(c.optBoolean("on")); done(true, "true", null) }
            "restartIce" -> done(true, "true", null) // tgcalls handles network changes itself
            "end" -> { closeAll("page-end"); done(true, "true", null) }
            else -> done(false, null, "unknown op: $op")
        }
    }

    private fun camera(on: Boolean, face: String, done: (Boolean, String?, String?) -> Unit) {
        val oc = olcall ?: return done(false, null, "no call")
        oc.camera(on, face)
        if (on && !videoOn) { videoOn = true; host?.nativeCallVideo(true) }
        done(true, "true", null)
    }

    /** Speaker / Phone toggle on the call screen (explicit pick; a connected headset still wins). */
    @JvmStatic fun setSpeaker(on: Boolean) = main.post {
        speakerPick = on
        CallDiskLog.mark("route-pick", if (on) "speaker" else "earpiece")
        olcall?.setSpeaker(on)
    }

    /** No page relay requests remain (rc:* keys/sealed messages were p2p-only); late replies are dropped. */
    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {
        CallDiskLog.mark("rpc-reply-ignored", "id=$id ok=$ok")
    }

    // ---- olcall (self-hosted Pion olcall + coturn, tgcalls engine from the :olcall module)
    @Volatile private var olcall: OlcallCall? = null
    private fun startOlcall(id: String, media: JSONObject, wantVideo: Boolean, peerId: String, done: (Boolean, String?, String?) -> Unit) {
        CallDiskLog.begin(id)
        // counts only: the relay list's credentials are never logged
        CallDiskLog.mark("connect-olcall", "video=$wantVideo appIce=${OlcallRoute.appIceServers(media)?.length() ?: 0}")
        main.post {
            val ctx = appCtx ?: return@post done(false, null, "no context")
            if (callId != id) return@post done(false, null, "cancelled")
            olcall?.let { it.hangup("replaced"); olcall = null; NativeCallVideo.endOlcall() }
            var answered = false
            val oc = OlcallCall(ctx, id, media, wantVideo, peerId,
                onConnected = {
                    active = true; host?.nativeCallAudio(true)
                    if (muted) olcall?.setMuted(true)
                    speakerPick?.let { olcall?.setSpeaker(it) }
                    host?.emit(JSONObject().put("state", "connected").put("callId", id).toString())
                    if (!answered) { answered = true
                        done(true, JSONObject().put("engine", "tgcalls").put("path", "olcall").put("video", wantVideo).toString(), null) }
                },
                onMediaFlowing = {
                    CallDiskLog.mark("media-flowing", "callId=$id")
                    mediaFlowingId = id
                    host?.callMediaFlowing(id)
                },
                onRemoteVideo = {
                    if (!videoOn) { videoOn = true; host?.nativeCallVideo(true) }
                    host?.emit(JSONObject().put("state", "remote-video").put("callId", id).put("on", true).toString())
                },
                onRoute = { route, speaker ->
                    host?.emit(JSONObject().put("type", "call:audioRoute").put("callId", id).put("route", route).put("speaker", speaker).toString())
                },
                onEnded = { reason, wasConnected ->
                    CallDiskLog.mark("olcall-ended", "reason=$reason connected=$wasConnected")
                    OlCallLogs.kick()
                    if (olcall != null && callId == id) {
                        val normal = reason == "local-hangup" || reason.startsWith("bye")
                        if (!normal) host?.emit(JSONObject().put("state", "failed").put("callId", id).toString())
                        if (!answered) { answered = true; done(false, null, "network:olcall:$reason") }
                    }
                })
            olcall = oc
            oc.start()
            if (wantVideo && !videoOn) { videoOn = true; host?.nativeCallVideo(true) }
        }
    }

    // ---- native call-signal socket (ring / accept without the WebView)
    @Volatile private var signal: CallSignalClient? = null
    @Volatile private var creds: Pair<String, String>? = null // in-memory only; never logged
    private fun connectSignal() { val (u, t) = creds ?: return; signal?.close()
        signal = CallSignalClient(u, t).also { sc ->
            sc.onMessage = { m -> main.post { onSignal(m) } }
            sc.onUiEvent = { ty, m -> host?.emit(JSONObject(m.toString()).put("type", ty).toString()) }
            sc.connect() } }
    /** FCM call_incoming woke us: reopen the signal socket so call-signal re-delivers the ring. */
    @JvmStatic fun onPushWake() = main.post { connectSignal() }

    @JvmStatic fun ensureSignalCreds(ctx: Context): Boolean {
        val token = CallApi.freshAccessToken(ctx) ?: return false
        val ws = CallApi.origin(ctx).replace(Regex("^http"), "ws") + "/ws"
        creds = ws to token
        return true
    }

    /** Answer from IncomingCallActivity / Telecom — no /call/id?accept=1 WebView path. */
    @JvmStatic @JvmOverloads fun acceptIncoming(ctx: Context, callId: String, video: Boolean = false): Boolean {
        if (callId.isEmpty()) return false
        val action = if (video) OlCallPermissions.Action.START_OR_ACCEPT_VIDEO
            else OlCallPermissions.Action.START_OR_ACCEPT_AUDIO
        if (OlCallPermissions.decide(action, OlCallPermissionPrompt.granted(ctx)) != OlCallPermissions.Decision.PROCEED) {
            CallDiskLog.mark("accept-native-fail", "permission")
            return false
        }
        if (!ensureSignalCreds(ctx)) { CallDiskLog.mark("accept-native-fail", "no token"); return false }
        main.post {
            connectSignal()
            val ok = signal?.accept(callId) == true
            CallDiskLog.mark("accept-native", "callId=$callId ok=$ok")
            host?.emit(JSONObject().put("type", "call:answering").put("callId", callId).toString())
            this.callId = callId
        }
        return true
    }
    private fun onSignal(m: JSONObject) { if (m.optString("t") == "call:ended") closeAll("signal-call-ended") }

    private fun ensurePerms(action: OlCallPermissions.Action, then: (Boolean) -> Unit) {
        main.post {
            val ctx = appCtx ?: return@post then(false)
            OlCallPermissionPrompt.ensure(action, ctx, then)
        }
    }

    /** Wi-Fi <-> cellular: tgcalls re-gathers and re-checks candidates on its own (no app-level ICE restart). */
    @JvmStatic fun onNetworkChange() { CallDiskLog.mark("network-change") }

    /** [cause] names the path that ended the call (t5450), so an automatic end never reads like a tap. */
    @JvmStatic @JvmOverloads fun closeAll(cause: String = "app") = main.post {
        olcall?.let { it.hangup(cause); olcall = null; NativeCallVideo.endOlcall() }
        callId = null; muted = false; speakerPick = null; mediaFlowingId = null; CallDiskLog.mark("close-all", "cause=$cause")
        if (videoOn) { videoOn = false; host?.nativeCallVideo(false) }
        if (active) { active = false; host?.nativeCallAudio(false) }
    }
    @JvmStatic fun isActive(): Boolean = active
    /** Nothing is kept warm between calls; kept for MainActivity's idle hook. */
    @JvmStatic fun releaseIdle() { CallDiskLog.mark("release-idle") }
}
