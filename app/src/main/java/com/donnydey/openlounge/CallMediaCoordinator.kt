package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.audio.CallAudioEngine
import com.donnydey.openlounge.audio.CallAudioManager
import com.donnydey.openlounge.audio.CallFailure
import com.donnydey.openlounge.audio.CallFailureDomain
import com.donnydey.openlounge.ringrtc.OlKeyStore
import com.donnydey.openlounge.ringrtc.RcCallEvent
import com.donnydey.openlounge.ringrtc.RcCallManager
import com.donnydey.openlounge.ringrtc.RcHangup
import com.donnydey.openlounge.ringrtc.RcSealedTransport
import com.donnydey.openlounge.rtc.OlVideo
import com.donnydey.openlounge.rtc.RtcCallSession
import com.donnydey.openlounge.signal.CallSignalClient
import org.json.JSONObject

/**
 * Native call media — ONE engine (RingRTC hard cut): a BSD libwebrtc PeerConnection per call ([RtcCallSession]) whose
 * signaling is E2E-sealed OLX3DH-v1 rc:* ([RcSealedTransport]) and relayed by the page over its authenticated socket.
 * Audio and camera video share that PC ([OlVideo]). No PJSIP, no SFU leg, no direct/relay ladder, no fallback path.
 * Same host surface as before, so MainActivity / SessionBridge stay unchanged; the WebView never captures call media.
 */
object CallMediaCoordinator {
    interface Host {
        fun relay(id: String, event: String, dataJson: String): Boolean
        fun emit(detailJson: String)
        fun nativeCallAudio(on: Boolean)
        /** show/hide the native remote + local video tiles under the (transparent) WebView */
        fun nativeCallVideo(on: Boolean) {}
    }

    @JvmStatic @Volatile var host: Host? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var appCtx: Context? = null
    @JvmStatic fun init(ctx: Context) { appCtx = ctx.applicationContext; CallDiskLog.init(ctx); CallAudioEngine.init(ctx); OlVideo.init(ctx) }
    @Volatile private var videoOn = false
    @Volatile private var muted = false
    @Volatile private var callId: String? = null

    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val c = runCatching { JSONObject(cmdJson) }.getOrNull() ?: return done(false, null, "bad command")
        val op = c.optString("op")
        CallDiskLog.mark("op", op)
        when (op) {
            "close" -> { closeAll(); done(true, "true", null) }
            // the page's realtime socket (re)connected: publish this device's OLX3DH-v1 keys now, before any call
            "signalConnected" -> { main.post { publishKeys(force = true) }; done(true, "true", null) }
            // server evt rc:msg (E2E-sealed) -> this device's OLX3DH-v1 session -> RtcCallSession
            "rcMsg" -> { c.optJSONObject("data")?.let { m -> main.post { rcTransport?.onRcMsg(m) } }; done(true, "true", null) }
            "rtcSignal" -> { c.optJSONObject("data")?.let { m -> main.post { rtc?.onSignal(m) } }; done(true, "true", null) }
            "prewarm" -> done(true, JSONObject().put("engine", "webrtc").put("available", CallAudioEngine.available()).toString(), null)
            "connect" -> {
                val id = c.optString("callId"); if (id.isEmpty()) return done(false, null, "callId required")
                if (callId != id) { soundGeneration++; localFailure = null }
                localFailure?.let { return done(false, null, it) }
                callId = id
                val media = c.optJSONObject("media")
                val wantVideo = c.optBoolean("video")
                val action = if (wantVideo) OlCallPermissions.Action.START_OR_ACCEPT_VIDEO
                    else OlCallPermissions.Action.START_OR_ACCEPT_AUDIO
                // mic (audio) or mic+camera in one combined request (video), only at this connect tap; denial is silent
                ensurePerms(action) { ok ->
                    if (!ok) return@ensurePerms done(false, null, "permission-denied")
                    if (callId != id) return@ensurePerms done(false, null, "cancelled")
                    // olcall/tgcalls only when the backend says so (CallMedia.provider "olcall") on API 27+; else unchanged
                    if (OlcallRoute.eligible(media, android.os.Build.VERSION.SDK_INT, android.os.Build.SUPPORTED_ABIS.toList()))
                        startOlcall(id, c.optBoolean("caller"), media!!, wantVideo, c.optString("peerId"), done)
                    else startRtc(id, c.optBoolean("caller"), media, wantVideo, done)
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
            "mute" -> { val m = c.optBoolean("muted"); muted = m; rtc?.setMute(m); olcall?.setMuted(m); CallAudioEngine.setMute(m); done(true, "true", null) }
            "restartIce" -> { main.post { rtc?.networkChanged() }; done(true, "true", null) } // tgcalls handles network changes itself
            "end" -> { closeAll(); done(true, "true", null) }
            else -> done(false, null, "unknown op: $op")
        }
    }

    private fun camera(on: Boolean, face: String, done: (Boolean, String?, String?) -> Unit) {
        olcall?.let { oc ->
            oc.camera(on, face)
            if (on && !videoOn) { videoOn = true; host?.nativeCallVideo(true) }
            return done(true, "true", null)
        }
        OlVideo.camera(on, face) { ok, e ->
            if (ok && on && !videoOn) { videoOn = true; host?.nativeCallVideo(true) }
            if (!ok) host?.emit(JSONObject().put("state", "camera-unavailable").put("callId", callId ?: "").toString())
            done(ok, ok.toString(), e)
        }
    }

    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {
        val cb = pending.remove(id) ?: return
        val res = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val srvErr = res?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" }
        val okReal = ok && srvErr == null
        CallDiskLog.mark("rpc-reply", "id=$id ok=$ok okReal=$okReal err=${error ?: srvErr ?: ""}")
        cb(okReal, res, error ?: srvErr)
    }

    // Call-audio lifecycle: CallAudioManager (focus -> mode -> route) then CallAudioEngine (WebRTC ADM). Every failure
    // here is typed local — never relay/TURN/ICE restart.
    private var soundGeneration = 0
    @Volatile private var localFailure: String? = null
    private fun localError(f: CallFailure): String {
        localFailure = "native-media-error:" + f.toJson()
        CallDiskLog.mark("local-audio-failure", f.toJson())
        host?.emit(JSONObject().put("type", "rtc:error").put("step", f.step).put("status", f.code)
            .put("domain", f.domain.name.lowercase()).put("networkRelated", false).put("model", android.os.Build.MODEL).toString())
        return localFailure!!
    }
    private fun openCallAudio(path: String, done: (CallFailure?) -> Unit) {
        val ctx = appCtx ?: return done(CallFailure(CallFailureDomain.SESSION, path, -1, "no context"))
        val generation = soundGeneration
        val mgr = CallAudioManager.get(ctx); mgr.tracer = { e, d -> CallDiskLog.mark(e, d) }
        mgr.prepare(videoOn) { prep ->
            main.post {
                if (generation != soundGeneration) return@post done(CallFailure(CallFailureDomain.SESSION, "cancelled", 0, "call ended"))
                if (prep != null) return@post done(prep)
                val out = mgr.devices.choose(videoOn)
                val inp = mgr.devices.inputs.firstOrNull { it.kind == out?.kind } ?: mgr.devices.inputs.firstOrNull()
                done(CallAudioEngine.open(inp, out, inp?.sampleRates ?: IntArray(0)))
            }
        }
    }

    // ---- RingRTC session model: keys + sealed transport + the one PeerConnection
    @Volatile private var rtc: RtcCallSession? = null
    @Volatile private var rcTransport: RcSealedTransport? = null
    private const val KEYS_NONE = 0; private const val KEYS_PUBLISHING = 1; private const val KEYS_PUBLISHED = 2
    @Volatile private var keysState = KEYS_NONE
    private val keyWaiters = ArrayList<() -> Unit>()
    private val rcKeys by lazy { OlKeyStore(appCtx!!) }
    /**
     * Publish this device's OLX3DH-v1 bundle when a signal socket connects (native call-signal open, or the page's
     * realtime socket via op "signalConnected") — not lazily on the first call op. That removes the race where a peer
     * fetched our bundle (rc:keys:fetch) before we had published it. force = republish on every reconnect (the server
     * may have restarted); a call that starts while publishing waits for it ([withKeys]).
     */
    private fun publishKeys(force: Boolean = false) {
        if (appCtx == null || host == null) return
        if (keysState == KEYS_PUBLISHING || (keysState == KEYS_PUBLISHED && !force)) return
        keysState = KEYS_PUBLISHING
        CallDiskLog.mark("rc-publish", "force=$force")
        RcSealedTransport.publish(rcKeys) { ev, d, cb -> main.post { rpc(ev, d) { ok, r, e ->
            main.post {
                keysState = if (ok) KEYS_PUBLISHED else KEYS_NONE
                CallDiskLog.mark("rc-publish-result", "ok=$ok ${e ?: ""}")
                val w = keyWaiters.toList(); keyWaiters.clear(); w.forEach { it() }
            }
            cb(ok, r, e) } } }
    }
    /** run [then] once our keys are published (or the attempt finished: a failed publish still lets the call try) */
    private fun withKeys(then: () -> Unit) {
        if (keysState == KEYS_PUBLISHED) return then()
        keyWaiters.add(then)
        publishKeys()
        if (keysState == KEYS_NONE) { val w = keyWaiters.toList(); keyWaiters.clear(); w.forEach { it() } } // no relay yet
    }

    private fun startRtc(id: String, caller: Boolean, media: JSONObject?, wantVideo: Boolean, done: (Boolean, String?, String?) -> Unit) {
        CallDiskLog.begin(id)
        CallDiskLog.mark("connect-rtc", "caller=$caller video=$wantVideo turn=${media?.optJSONArray("iceServers")?.length() ?: 0}")
        main.post { withKeys { openCallAudio("rtc") { f ->
            if (f != null) return@openCallAudio done(false, null, localError(f))
            if (callId != id) return@openCallAudio done(false, null, "cancelled")
            rcTransport?.close()
            RcCallManager.begin(id, caller)
            RcCallManager.connecting(id)
            lateinit var sess: RtcCallSession
            val tx = RcSealedTransport(rcKeys, id, caller,
                request = { ev, data, cb -> main.post { rpc(ev, data, cb) } }, onSignal = { m -> sess.onSignal(m) })
            rcTransport = tx
            OlVideo.onFirstRemoteFrame = {
                if (!videoOn) { videoOn = true; host?.nativeCallVideo(true) }
                host?.emit(JSONObject().put("state", "remote-video").put("callId", id).put("on", true).toString())
            }
            sess = RtcCallSession(id, caller, media,
                request = { ev, data, cb -> tx.send(ev, data, cb) },
                onConnected = {
                    active = true; host?.nativeCallAudio(true)
                    if (muted) sess.setMute(true)
                    RcCallManager.connected(id)
                    host?.emit(JSONObject().put("state", "connected").put("callId", id).toString())
                    done(true, JSONObject().put("engine", "webrtc").put("path", "p2p").put("video", wantVideo).toString(), null)
                },
                onFailure = { fl -> CallDiskLog.mark("rtc-failure", fl.toJson())
                    RcCallManager.end(id, RcHangup.FAILED,
                        if (fl.relayEligible) RcCallEvent.ENDED_CONNECTION_FAILURE else RcCallEvent.ENDED_DEVICE_FAILURE, fl.code.toString())
                    host?.emit(JSONObject().put("state", "failed").put("callId", id).toString())
                    done(false, null, if (fl.relayEligible) "network:" + fl.toJson() else localError(fl)) })
            rtc = sess; sess.start()
            if (wantVideo) camera(true, "user") { _, _, _ -> }
        } } }
    }

    // ---- olcall (self-hosted Pion olcall + coturn, tgcalls engine from the :olcall module)
    @Volatile private var olcall: OlcallCall? = null
    private fun startOlcall(id: String, caller: Boolean, media: JSONObject, wantVideo: Boolean, peerId: String, done: (Boolean, String?, String?) -> Unit) {
        CallDiskLog.begin(id)
        // counts only: the relay list's credentials are never logged
        CallDiskLog.mark("connect-olcall", "video=$wantVideo appIce=${OlcallRoute.appIceServers(media)?.length() ?: 0}")
        main.post {
            val ctx = appCtx ?: return@post done(false, null, "no context")
            if (callId != id) return@post done(false, null, "cancelled")
            RcCallManager.begin(id, caller); RcCallManager.connecting(id)
            var answered = false
            val oc = OlcallCall(ctx, id, media, wantVideo, peerId,
                onConnected = {
                    active = true; host?.nativeCallAudio(true)
                    if (muted) olcall?.setMuted(true)
                    RcCallManager.connected(id)
                    host?.emit(JSONObject().put("state", "connected").put("callId", id).toString())
                    if (!answered) { answered = true
                        done(true, JSONObject().put("engine", "tgcalls").put("path", "olcall").put("video", wantVideo).toString(), null) }
                },
                onRemoteVideo = {
                    if (!videoOn) { videoOn = true; host?.nativeCallVideo(true) }
                    host?.emit(JSONObject().put("state", "remote-video").put("callId", id).put("on", true).toString())
                },
                onEnded = { reason, wasConnected ->
                    CallDiskLog.mark("olcall-ended", "reason=$reason connected=$wasConnected")
                    if (olcall != null && callId == id) {
                    val normal = reason == "local-hangup" || reason.startsWith("bye")
                    RcCallManager.end(id, if (normal) RcHangup.NORMAL else RcHangup.FAILED,
                        if (normal) RcCallEvent.LOCAL_HANGUP else RcCallEvent.ENDED_CONNECTION_FAILURE, reason)
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
            sc.onConnected = { main.post { publishKeys(force = true) } }
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
    private fun onSignal(m: JSONObject) { if (m.optString("t") == "call:ended") closeAll() }

    // rc:* / rtc:* requests ride the page's authenticated socket: host.relay(id, event, data) -> onMsReply.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Boolean, JSONObject?, String?) -> Unit>()
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private const val RPC_TIMEOUT_MS = 8_000L
    private fun rpc(event: String, data: JSONObject, cb: (Boolean, JSONObject?, String?) -> Unit) {
        val id = "rc${seq.incrementAndGet()}"; pending[id] = cb
        CallDiskLog.mark("rpc", "id=$id $event")
        if (host?.relay(id, event, data.toString()) != true) { pending.remove(id); cb(false, null, "no relay"); return }
        main.postDelayed({ pending.remove(id)?.let { CallDiskLog.mark("rpc-timeout", "id=$id $event"); it(false, null, "timeout $event") } }, RPC_TIMEOUT_MS)
    }

    /** Network changed (Wi-Fi <-> cellular): fresh ICE on the same PC (caller restarts; callee asks). */
    private fun ensurePerms(action: OlCallPermissions.Action, then: (Boolean) -> Unit) {
        main.post {
            val ctx = appCtx ?: return@post then(false)
            OlCallPermissionPrompt.ensure(action, ctx, then)
        }
    }

    @JvmStatic fun onNetworkChange() = main.post { rtc?.networkChanged() }

    @JvmStatic fun closeAll() = main.post {
        rtc?.close(); rtc = null; rcTransport?.close(); rcTransport = null
        olcall?.let { it.hangup(); olcall = null; NativeCallVideo.endOlcall() }
        callId?.let { RcCallManager.end(it, RcHangup.NORMAL, RcCallEvent.LOCAL_HANGUP) }; callId = null
        soundGeneration++; localFailure = null; muted = false; CallDiskLog.mark("close-all")
        OlVideo.end(); if (videoOn) { videoOn = false; host?.nativeCallVideo(false) }
        CallAudioEngine.close(); appCtx?.let { CallAudioManager.get(it).release() }
        if (active) { active = false; host?.nativeCallAudio(false) }
    }
    @JvmStatic fun isActive(): Boolean = active
    /** Nothing is kept warm between calls any more (no PJSIP endpoint); kept for MainActivity's idle hook. */
    @JvmStatic fun releaseIdle() { CallDiskLog.mark("release-idle") }
}
