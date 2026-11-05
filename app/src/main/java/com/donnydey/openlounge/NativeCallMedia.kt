package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.pjsip.CallSignalClient
import com.donnydey.openlounge.pjsip.PjsipEngine
import org.json.JSONObject

/**
 * Native call media — PJSIP only (WebRTC removed, pjsip-rebuild). Same host surface as before so MainActivity /
 * SessionBridge stay unchanged; the WebView never captures or plays call audio. SDP/SRTP signaling over the
 * existing TLS WebSocket (M4): 'open' starts the SRTP audio leg to the SFU PlainTransport; 'close' tears down fully.
 */
object NativeCallMedia {
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
    @JvmStatic fun init(ctx: Context) { appCtx = ctx.applicationContext }
    @Volatile private var videoOn = false

    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val op = runCatching { JSONObject(cmdJson).optString("op") }.getOrDefault("")
        when (op) {
            "close" -> { closeAll(); done(true, "true", null) }
            "prewarm" -> done(true, JSONObject().put("engine", "pjsip").put("start", PjsipEngine.start()).toString(), null)
            "open" -> main.post {
                val c = JSONObject(cmdJson)
                val leg = Leg(c.getString("ip"), c.getInt("port"), c.getString("txKey"), c.getString("rxKey"),
                    c.optLong("ssrc", 1111L), c.optInt("txPt", 111), c.optInt("rxPt", 111))
                val r = startLeg(leg)
                if (r == 0) { active = true; host?.nativeCallAudio(true); done(true, JSONObject().put("engine", "pjsip").toString(), null) }
                else done(false, null, "pjsip audio leg start failed: $r")
            }
            // Option B: the page's existing authenticated socket runs call:* (start/accept/decline/hangup + push);
            // at answer the call screen sends {op:"connect", callId} with the ms:* relay installed -> SFU audio leg.
            "connect" -> { val c = JSONObject(cmdJson); val id = c.optString("callId"); if (id.isEmpty()) return done(false, null, "callId required")
                val wantVideo = c.optBoolean("video") && c.optBoolean("nativeVideo")
                callId = id; p2pCfg = P2pCfg.from(c); direct = c.optBoolean("direct")
                // ONE path per call (Donny, 9:10 AM Nov 5 2026): phone<->phone = the direct ICE link only (our coturn is
                // just one ICE candidate); phone<->browser = the SFU only. No parallel route, no switchover.
                if (direct) { main.post { startDirect(wantVideo, done) }; return }
                requestSfu(id) { r, err ->
                    if (r != 0) return@requestSfu done(false, null, err ?: "pjsip start $r")
                    done(true, JSONObject().put("engine", "pjsip").put("video", wantVideo).toString(), null)
                    if (wantVideo) requestVideo(id, true) } }
            // camera on/off/flip; the first "on" in an audio call upgrades it (opens the video leg)
            "camera" -> { val c = JSONObject(cmdJson); val on = c.optBoolean("on"); val face = c.optString("facing", "user")
                val id = callId
                if (direct) { main.post { if (on) startDirectVideo(true, face) else NativeCallVideo.camera(false, face) { _, _ -> } }; return done(true, "true", null) }
                if (on && !videoOn && id != null) { requestVideo(id, true, face); return done(true, "true", null) }
                main.post { NativeCallVideo.camera(on, face) { ok, e -> done(ok, ok.toString(), e) } } }
            // the other phone's call:p2p message (page forwards it from the server socket)
            "p2p" -> { val c = JSONObject(cmdJson); main.post { onP2pMsg(c.optString("callId"), c.optJSONObject("msg")) }; done(true, "true", null) }
            "mute" -> { val m = runCatching { JSONObject(cmdJson).optBoolean("muted") }.getOrDefault(false)
                muted = m; if (p2pState != P2P_OFF) runCatching { PjsipEngine.nativeP2pSetMute(m) }
                if (direct) return done(true, "true", null)
                val r = runCatching { PjsipEngine.nativeSfuSetMute(m) }.getOrDefault(-1); done(r >= 0, (r >= 0).toString(), if (r < 0) "mute failed" else null) }
            "end" -> { callId = null; closeAll(); done(true, "true", null) }
            else -> done(false, null, "unknown op: $op")
        }
    }

    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {
        val cb = pending.remove(id) ?: return
        cb(ok, resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }, error)
    }
    private data class Leg(val ip: String, val port: Int, val tx: String, val rx: String, val ssrc: Long, val txPt: Int, val rxPt: Int)
    @Volatile private var leg: Leg? = null
    private fun startLeg(l: Leg): Int { PjsipEngine.start(); PjsipEngine.openSound(); leg = l
        return PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, false) }

    @Volatile private var signal: CallSignalClient? = null
    @Volatile private var creds: Pair<String, String>? = null // in-memory only; never logged
    private fun connectSignal() { val (u, t) = creds ?: return; signal?.close()
        signal = CallSignalClient(u, t).also { sc ->
            sc.onMessage = { m -> main.post { onSignal(m) } }
            sc.onUiEvent = { ty, m -> host?.emit(JSONObject(m.toString()).put("type", ty).toString()) }
            sc.connect() } }
    /** FCM call_incoming woke us: reopen the signal socket so call-signal re-delivers the ring (connected()). */
    @JvmStatic fun onPushWake() = main.post { connectSignal() }
    @Volatile private var callId: String? = null
    private var txKey = ""
    private fun newKey(): String { val b = ByteArray(30); java.security.SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }

    // SFU PlainTransport RPCs ride the page's authenticated socket: host.relay(id, "ms:...", data) -> onMsReply.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Boolean, JSONObject?, String?) -> Unit>()
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private fun rpc(event: String, data: JSONObject, cb: (Boolean, JSONObject?, String?) -> Unit) {
        val id = "pj${seq.incrementAndGet()}"; pending[id] = cb
        if (host?.relay(id, event, data.toString()) != true) { pending.remove(id); cb(false, null, "no relay") }
    }
    private fun fail(step: String, e: String?, result: (Int, String?) -> Unit = { _, _ -> }) { result(-1, "$step: $e"); host?.emit(JSONObject().put("type", "pjsip:error").put("step", step).put("error", e ?: "").toString()) }

    /** join -> plain:create (SFU ip/port/key) -> plain:connect (our key) -> plain:produce (our ssrc) -> plain:consume -> start leg. */
    private fun requestSfu(id: String, result: (Int, String?) -> Unit = { _, _ -> }) {
        txKey = newKey(); val ssrc = 100000L + java.security.SecureRandom().nextInt(1_000_000)
        val d = { JSONObject().put("callId", id) }
        rpc("ms:join", d()) { ok, _, e -> if (!ok) return@rpc fail("join", e, result)
          rpc("ms:plain:create", d()) { ok1, c, e1 -> if (!ok1 || c == null) return@rpc fail("create", e1, result)
            val ip = c.optString("ip"); val port = c.optInt("port"); val rx = c.optJSONObject("srtpParameters")?.optString("keyBase64").orEmpty()
            rpc("ms:plain:connect", d().put("srtpParameters", JSONObject().put("cryptoSuite", "AES_CM_128_HMAC_SHA1_80").put("keyBase64", txKey))) { ok2, _, e2 ->
              if (!ok2) return@rpc fail("connect", e2, result)
              rpc("ms:plain:produce", d().put("kind", "audio").put("ssrc", ssrc).put("payloadType", 111)) { ok3, _, e3 ->
                if (!ok3) return@rpc fail("produce", e3, result)
                rpc("ms:plain:consume", d()) { _, cons, _ ->
                  val rxPt = cons?.optJSONArray("consumers")?.optJSONObject(0)?.optInt("payloadType", 111) ?: 111
                  main.post { val r = startLeg(Leg(ip, port, txKey, rx, ssrc, 111, rxPt))
                    if (r == 0) { active = true; host?.nativeCallAudio(true) }; result(r, null)
                    host?.emit(JSONObject().put("type", "pjsip:audio").put("start", r).toString()) } } } } } }
    }

    /** Video leg (ol_sfu_video.c): own "video" PlainTransport + SRTP key, 2 simulcast SSRCs, H.264 PT 102. */
    private fun requestVideo(id: String, camOn: Boolean, face: String = "user") {
        if (videoOn) return; videoOn = true
        val vKey = newKey(); val base = 300000L + java.security.SecureRandom().nextInt(1_000_000)
        val ssrcs = LongArray(NativeCallVideo.LAYERS.size) { base + it * 2 }
        val d = { JSONObject().put("callId", id).put("purpose", "video") }
        val vfail = { step: String, e: String? -> videoOn = false; fail("video-$step", e) }
        rpc("ms:plain:create", d()) { ok, c, e -> if (!ok || c == null) return@rpc vfail("create", e)
          val ip = c.optString("ip"); val port = c.optInt("port"); val rx = c.optJSONObject("srtpParameters")?.optString("keyBase64").orEmpty()
          rpc("ms:plain:connect", d().put("srtpParameters", JSONObject().put("cryptoSuite", "AES_CM_128_HMAC_SHA1_80").put("keyBase64", vKey))) { ok2, _, e2 ->
            if (!ok2) return@rpc vfail("connect", e2)
            main.post {
              val r = appCtx?.let { NativeCallVideo.start(it, ip, port, vKey, rx, ssrcs, 102) } ?: -1
              if (r != 0) return@post vfail("start", "ol_sfu_video_start $r")
              host?.nativeCallVideo(true)
              NativeCallVideo.onFirstRemoteFrame = { host?.emit(JSONObject().put("state", "remote-video").put("type", "pjsip:video").toString()) }
              val ssrcArr = org.json.JSONArray().apply { ssrcs.forEach { put(it) } }
              rpc("ms:plain:produce", d().put("kind", "video").put("ssrcs", ssrcArr).put("payloadType", 102).put("codec", "h264").put("platform", "android")) { ok3, _, e3 ->
                if (!ok3) return@rpc vfail("produce", e3)
                if (camOn) main.post { NativeCallVideo.camera(true, face) { cok, ce -> if (!cok) fail("camera", ce) } }
                rpc("ms:plain:consume", d()) { _, _, _ -> } // remote video arrives on the same leg; the SFU PLIs for a keyframe
              } } } }
    }

    private fun onSignal(m: JSONObject) {
        when (m.optString("t")) {
            "call:accepted" -> { val id = m.optString("callId"); callId = id; requestSfu(id) }
            "call:ended" -> { callId = null; closeAll() }
        }
    }

    /** Network changed (Wi-Fi <-> cellular): re-bind the RTP socket to the new interface, same SRTP keys/SSRC.
     *  If audio was on the direct leg, it goes back to the server route first, then the direct leg regathers
     *  (tgcalls NativeNetworkingImpl.cpp:635-637) and audio switches again only once it is proven both ways. */
    @JvmStatic fun onNetworkChange() = main.post {
        if (direct) { if (p2pCfg != null && p2pState != P2P_OFF) restartP2p(true); return@post } // same single path, fresh ICE
        val l = leg ?: return@post
        if (!active) return@post
        if (p2pState == P2P_LIVE) runCatching { PjsipEngine.nativeP2pActivate(false) }
        if (videoP2p) { videoP2p = false; runCatching { PjsipEngine.nativeP2pVideoLive(false) } }
        PjsipEngine.nativeSfuStop()
        val r = PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, false)
        if (muted) runCatching { PjsipEngine.nativeSfuSetMute(true) }
        host?.emit(JSONObject().put("type", "pjsip:netchange").put("restart", r).toString())
        if (p2pState != P2P_OFF && p2pState != P2P_FAILED) restartP2p(true)
    }

    // ---- Phone-to-phone direct leg (ol_p2p.c). WhatsApp: direct first, relay fallback; Telegram tgcalls 38dcea1
    // v2/NativeNetworkingImpl.cpp:576-640: one ICE agent with host + STUN + our TURN (UDP, TCP). The server route
    // stays up until the direct leg carries RTP both ways; then audio switches and the server route is dropped.
    private data class P2pCfg(val offerer: Boolean, val stun: String, val turn: String, val user: String, val pass: String, val tcp: Boolean, val relayOnly: Boolean) {
        companion object {
            /** reuse the relay logins the server already hands out in connect.iceServers (turn:/turns: urls) */
            fun from(c: JSONObject): P2pCfg? {
                val arr = c.optJSONArray("iceServers") ?: return null
                var turn = ""; var tcp = false; var user = ""; var pass = ""
                for (i in 0 until arr.length()) { val s = arr.optJSONObject(i) ?: continue
                    val urls = s.optJSONArray("urls")?.let { a -> List(a.length()) { a.optString(it) } } ?: listOf(s.optString("urls"))
                    for (u in urls) { val m = Regex("^turn:([^?]+)(\\?transport=(udp|tcp))?$").find(u) ?: continue
                        val isTcp = m.groupValues[3] == "tcp" || c.optBoolean("tcpOnly")
                        if (turn.isEmpty() || (!isTcp && tcp)) { turn = m.groupValues[1]; tcp = isTcp; user = s.optString("username"); pass = s.optString("credential") } } }
                if (turn.isEmpty()) return null
                return P2pCfg(c.optBoolean("p2pOfferer"), turn, turn, user, pass, tcp, c.optBoolean("relayOnly"))
            }
        }
    }
    private const val P2P_OFF = 0; private const val P2P_TRYING = 1; private const val P2P_LIVE = 2; private const val P2P_FAILED = 3
    @Volatile private var p2pState = P2P_OFF
    private var p2pCfg: P2pCfg? = null
    private var p2pPendingOffer: String? = null
    private var p2pGen = 0
    @Volatile private var muted = false
    private const val P2P_TIMEOUT_MS = 15_000L

    private fun startP2p() {
        val cfg = p2pCfg ?: return
        if (!cfg.offerer && p2pPendingOffer == null) { p2pState = P2P_TRYING; armTimeout(); return } // callee waits for the offer
        beginP2p(cfg)
    }
    private fun beginP2p(cfg: P2pCfg) {
        val r = runCatching { PjsipEngine.nativeP2pBegin(cfg.offerer, cfg.stun, cfg.turn, cfg.user, cfg.pass, cfg.tcp, cfg.relayOnly, 0, false) }.getOrDefault(-99)
        if (r != 0) return p2pFail("begin $r")
        if (direct) runCatching { PjsipEngine.nativeP2pActivate(true) } // the only path: wired to mic/speaker from the start
        p2pState = P2P_TRYING; if (muted) runCatching { PjsipEngine.nativeP2pSetMute(true) }
        p2pPendingOffer?.let { p2pPendingOffer = null; PjsipEngine.nativeP2pRemote(it) }
        armTimeout(); poll(p2pGen)
    }
    private fun armTimeout() { val g = ++p2pGen; main.postDelayed({ if (g == p2pGen && p2pState == P2P_TRYING) p2pFail("timeout") }, P2P_TIMEOUT_MS) }
    /** ICE events come from pjsua threads through a queue (olpjsip.c); drained here on the main thread. */
    private fun poll(g: Int) {
        if (g != p2pGen || p2pState == P2P_OFF || p2pState == P2P_FAILED) return
        while (true) { val ev = runCatching { PjsipEngine.nativeP2pTake() }.getOrNull() ?: break
            val text = ev.substringAfter('\n')
            when (ev[0]) {
                '1' -> sendP2p(JSONObject().put("sdp", text).put("offer", p2pCfg?.offerer == true))
                '2' -> { host?.emit(JSONObject().put("type", "pjsip:p2p").put("state", "ice").toString()); if (direct) directUp() }
                '3' -> return p2pFail(text)
            } }
        if (!direct && p2pState == P2P_TRYING) maybeSwitch()
        if (!direct && p2pState == P2P_LIVE && videoOn && !videoP2p) maybeSwitchVideo()
        main.postDelayed({ poll(g) }, if (p2pState == P2P_LIVE) 500L else 50L)
        if (p2pState == P2P_LIVE) watchLive()
    }
    /** "Connected" = audio flowing both ways: packets received from and sent to the other phone on the direct leg. */
    private fun maybeSwitch() {
        val st = runCatching { PjsipEngine.nativeP2pStats() }.getOrNull() ?: return
        if (st[0] < 10 || st[2] < 10) return
        PjsipEngine.nativeP2pActivate(true); p2pState = P2P_LIVE; lastRx = st[0]; lastRxAt = android.os.SystemClock.elapsedRealtime()
        runCatching { PjsipEngine.nativeSfuStop() } // drop the server route; leg kept for fallback / network change
        host?.emit(JSONObject().put("type", "pjsip:p2p").put("state", "direct").toString())
    }
    /** Video follows the same rule (tgcalls bundles it on the audio transport, InstanceV2Impl.cpp:460/:1135): the top
     *  layer is sent on both routes until H.264 packets flow both ways on the direct leg, then the server video send stops. */
    @Volatile private var videoP2p = false
    private fun maybeSwitchVideo() {
        val v = runCatching { PjsipEngine.nativeP2pVideoStats() }.getOrNull() ?: return
        if (v[0] < 10 || v[1] < 10) return
        if (PjsipEngine.nativeP2pVideoLive(true) == 0) { videoP2p = true
            host?.emit(JSONObject().put("type", "pjsip:p2p").put("state", "direct-video").toString()) }
    }
    private var lastRx = 0; private var lastRxAt = 0L
    /** direct leg went silent (no RTP for 3 s): back to the server route, then retry the direct leg */
    private fun watchLive() {
        val st = runCatching { PjsipEngine.nativeP2pStats() }.getOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (st[0] != lastRx) { lastRx = st[0]; lastRxAt = now; return }
        if (now - lastRxAt > 3_000) { backToServer("rx stall"); restartP2p(true) }
    }
    private fun backToServer(why: String) {
        if (direct) return // no server route on a phone<->phone call
        if (videoP2p) { videoP2p = false; runCatching { PjsipEngine.nativeP2pVideoLive(false) } }
        if (p2pState == P2P_LIVE) { runCatching { PjsipEngine.nativeP2pActivate(false) }
            leg?.let { l -> PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, false); if (muted) runCatching { PjsipEngine.nativeSfuSetMute(true) } } }
        host?.emit(JSONObject().put("type", "pjsip:p2p").put("state", "server").put("why", why).toString())
    }
    private fun restartP2p(tellPeer: Boolean) {
        val cfg = p2pCfg ?: return
        p2pState = P2P_TRYING
        if (cfg.offerer) { if (runCatching { PjsipEngine.nativeP2pRestart() }.getOrDefault(-1) != 0) return p2pFail("restart")
            if (direct) runCatching { PjsipEngine.nativeP2pActivate(true) }; armTimeout(); poll(p2pGen) }
        else { runCatching { PjsipEngine.nativeP2pStop() }; armTimeout(); if (tellPeer) sendP2p(JSONObject().put("restart", true)) }
    }
    private fun p2pFail(why: String) {
        backToServer(why); p2pGen++; p2pState = P2P_FAILED; runCatching { PjsipEngine.nativeP2pStop() }
        if (!direct) return
        host?.emit(JSONObject().put("type", "pjsip:error").put("step", "direct").put("error", why).toString())
        val d = directDone; directDone = null
        if (d != null) { d(false, null, "direct: $why"); return } // first connect failed: the page decides (end/retry)
        // mid-call: keep retrying the same single path until the call ends (stopP2p clears p2pCfg / bumps the gen)
        val g = p2pGen; main.postDelayed({ if (g == p2pGen && direct && p2pCfg != null && p2pState == P2P_FAILED) restartP2p(true) }, 1_000L)
    }

    // ---- Direct (phone<->phone) single path
    @Volatile private var direct = false
    private var directDone: ((Boolean, String?, String?) -> Unit)? = null
    private var directWantVideo = false
    private fun startDirect(wantVideo: Boolean, done: (Boolean, String?, String?) -> Unit) {
        if (p2pCfg == null) return done(false, null, "direct: no relay config")
        PjsipEngine.start(); PjsipEngine.openSound()
        directDone = done; directWantVideo = wantVideo
        startP2p()
    }
    /** ICE connected (ol_p2p ev 2): the call's one path is up. */
    private fun directUp() {
        p2pState = P2P_LIVE; runCatching { PjsipEngine.nativeP2pActivate(true) }
        lastRx = runCatching { PjsipEngine.nativeP2pStats()[0] }.getOrDefault(0); lastRxAt = android.os.SystemClock.elapsedRealtime()
        if (!active) { active = true; host?.nativeCallAudio(true) }
        // receive side always ready (the peer may turn its camera on); our camera only if asked
        startDirectVideo(directWantVideo && directDone != null)
        if (videoOn) { videoP2p = runCatching { PjsipEngine.nativeP2pVideoLive(true) }.getOrDefault(-1) == 0 }
        val d = directDone; directDone = null
        d?.invoke(true, JSONObject().put("engine", "pjsip").put("path", "direct").put("video", directWantVideo).toString(), null)
    }
    private fun startDirectVideo(camOn: Boolean, face: String = "user") {
        if (!videoOn) {
            val c = appCtx ?: return
            val base = 300000L + java.security.SecureRandom().nextInt(1_000_000)
            val r = NativeCallVideo.start(c, "", 0, "", "", LongArray(NativeCallVideo.LAYERS.size) { base + it * 2 }, 102)
            if (r != 0) return fail("video-start", "direct $r")
            videoOn = true
            NativeCallVideo.onFirstRemoteFrame = { main.post { host?.nativeCallVideo(true) }; host?.emit(JSONObject().put("state", "remote-video").put("type", "pjsip:video").toString()) }
            if (p2pState == P2P_LIVE) videoP2p = runCatching { PjsipEngine.nativeP2pVideoLive(true) }.getOrDefault(-1) == 0
        }
        if (camOn) { host?.nativeCallVideo(true); NativeCallVideo.camera(true, face) { ok, e -> if (!ok) fail("camera", e) } }
    }
    private fun sendP2p(msg: JSONObject) { val id = callId ?: return
        rpc("call:p2p", JSONObject().put("callId", id).put("msg", msg)) { ok, _, e -> if (!ok) main.post { p2pFail("send ${e ?: ""}") } } }
    private fun onP2pMsg(id: String, msg: JSONObject?) {
        if (msg == null || (callId != null && id != callId)) return
        val cfg = p2pCfg ?: run { msg.optString("sdp").takeIf { it.isNotEmpty() }?.let { p2pPendingOffer = it }; return } // offer before connect
        if (msg.optBoolean("restart")) { if (cfg.offerer && p2pState != P2P_OFF) { if (p2pState == P2P_LIVE) backToServer("peer network change"); restartP2p(false) }; return }
        val sdp = msg.optString("sdp"); if (sdp.isEmpty()) return
        if (cfg.offerer) { if (p2pState == P2P_TRYING) { val r = PjsipEngine.nativeP2pRemote(sdp); if (r != 0) p2pFail("answer $r") }; return }
        // callee: a (new) offer. After a restart by the caller, go back to the server route and answer afresh.
        if (p2pState == P2P_LIVE) backToServer("peer restart")
        if (p2pState == P2P_OFF) { p2pPendingOffer = sdp; return } // connect not reached yet; startP2p picks it up
        p2pPendingOffer = sdp; beginP2p(cfg)
    }
    private fun stopP2p() { direct = false; directDone?.invoke(false, null, "ended"); directDone = null; videoP2p = false; p2pGen++; p2pState = P2P_OFF; p2pCfg = null; p2pPendingOffer = null; runCatching { PjsipEngine.nativeP2pStop() } }

    @JvmStatic fun closeAll() = main.post { stopP2p(); if (videoOn) { videoOn = false; NativeCallVideo.stop(); host?.nativeCallVideo(false) }; leg = null; runCatching { PjsipEngine.nativeSfuStop() }; PjsipEngine.teardown(); if (active) { active = false; host?.nativeCallAudio(false) } }
    @JvmStatic fun isActive(): Boolean = active
    @JvmStatic fun releaseIdle() { PjsipEngine.teardown() }
}
