package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.pjsip.CallSignalClient
import com.donnydey.openlounge.pjsip.PjsipEngine
import com.donnydey.openlounge.audio.CallAudioEngine
import com.donnydey.openlounge.audio.CallAudioManager
import com.donnydey.openlounge.audio.CallFailure
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native call media — PJSIP only (WebRTC removed, pjsip-rebuild). Same host surface as before so MainActivity /
 * SessionBridge stay unchanged; the WebView never captures or plays call audio. SDP/SRTP signaling over the
 * existing TLS WebSocket (M4): 'open' starts the SRTP audio leg to the SFU PlainTransport; 'close' tears down fully.
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
    @JvmStatic fun init(ctx: Context) { appCtx = ctx.applicationContext; CallDiskLog.init(ctx) }
    @Volatile private var videoOn = false

    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val op = runCatching { JSONObject(cmdJson).optString("op") }.getOrDefault("")
        CallDiskLog.mark("op", op)
        when (op) {
            "close" -> { closeAll(); done(true, "true", null) }
            // page shim forwards server evt rtc:signal {callId,gen,seq,kind,sdp?,candidate?,from}
            "rtcSignal" -> { val m = JSONObject(cmdJson).optJSONObject("data"); main.post { if (m != null) rtc?.onSignal(m) }; done(true, "true", null) }
            "prewarm" -> main.post { done(true, JSONObject().put("engine", "pjsip").put("start", PjsipEngine.start()).toString(), null) } // pjsua on main only (was JavaBridge thread -> unregistered pjlib thread)
            "open" -> main.post {
                val c = JSONObject(cmdJson)
                val leg = Leg(c.getString("ip"), c.getInt("port"), c.getString("txKey"), c.getString("rxKey"),
                    c.optLong("ssrc", 1111L), c.optInt("txPt", 111), c.optInt("rxPt", 111))
                startLeg(leg) { r ->
                    if (r == 0) { active = true; host?.nativeCallAudio(true); done(true, JSONObject().put("engine", "pjsip").toString(), null) }
                    else done(false, null, localFailure ?: "pjsip audio leg start failed: $r")
                }
            }
            // Option B: the page's existing authenticated socket runs call:* (start/accept/decline/hangup + push);
            // at answer the call screen sends {op:"connect", callId} with the ms:* relay installed -> SFU audio leg.
            "connect" -> { val c = JSONObject(cmdJson); val id = c.optString("callId"); if (id.isEmpty()) return done(false, null, "callId required")
                val wantVideo = c.optBoolean("video") && c.optBoolean("nativeVideo")
                if (callId != id) { soundGeneration++; localFailure = null }
                localFailure?.let { return done(false, null, it) }
                callId = id; p2pCfg = null; direct = false
                val media = c.optJSONObject("media")
                if (USE_RTC_PC && (media == null || media.optString("provider") == "p2p")) return startRtc(id, c.optBoolean("caller"), media, done)
                CallDiskLog.begin(id); CallDiskLog.mark("connect", "video=$wantVideo pageDirect=${c.optBoolean("direct")} -> server route")
                // ONE path per call (Donny 9:10/9:12 AM Nov 5 2026; b107/46 scope): the server route (SFU) for EVERY
                // pairing, phone<->phone included. The page's "direct" hint is logged and ignored; no direct leg is layered on.
                host?.emit(JSONObject().put("type", "pjsip:calllog").put("callId", id).put("lines", JSONArray(CallDiskLog.tailJson())).toString())
                requestSfu(id) { r, err ->
                    if (r != 0) return@requestSfu done(false, null, err ?: "pjsip start $r")
                    // connected means audio: resolve only once RTP flows both ways on the leg
                    awaitTwoWay { ok, why ->
                        if (!ok) return@awaitTwoWay fail("two-way", why) { _, e -> done(false, null, e) }
                        done(true, JSONObject().put("engine", "pjsip").put("video", wantVideo).put("path", "server").toString(), null)
                        if (wantVideo) requestVideo(id, true) } } }
            // camera on/off/flip; the first "on" in an audio call upgrades it (opens the video leg)
            "camera" -> { val c = JSONObject(cmdJson); val on = c.optBoolean("on"); val face = c.optString("facing", "user")
                val id = callId
                if (direct) { main.post { if (on) startDirectVideo(true, face) else NativeCallVideo.camera(false, face) { _, _ -> } }; return done(true, "true", null) }
                if (on && !videoOn && id != null) { requestVideo(id, true, face); return done(true, "true", null) }
                main.post { NativeCallVideo.camera(on, face) { ok, e -> done(ok, ok.toString(), e) } } }
            // the other phone's call:p2p message (page forwards it from the server socket)
            "p2p" -> { CallDiskLog.mark("p2p-ignored"); done(true, "true", null) } // no direct leg in this build
            "mute" -> { val m = runCatching { JSONObject(cmdJson).optBoolean("muted") }.getOrDefault(false)
                rtc?.let { it.setMute(m); CallAudioEngine.setMute(m); muted = m; return done(true, "true", null) }
                muted = m; if (p2pState != P2P_OFF) runCatching { PjsipEngine.nativeP2pSetMute(m) }
                if (direct) return done(true, "true", null)
                main.post { val r = runCatching { CallAudioEngine.setMute(m); PjsipEngine.nativeSfuSetMute(m) }.getOrDefault(-1); done(r >= 0, (r >= 0).toString(), if (r < 0) "mute failed" else null) } }
            "end" -> { callId = null; closeAll(); done(true, "true", null) }
            else -> done(false, null, "unknown op: $op")
        }
    }

    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {
        val cb = pending.remove(id) ?: return
        val res = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val srvErr = res?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" }
        val okReal = ok && srvErr == null // e.g. plain:produce "need 3 ssrcs" arrived as ok=true
        CallDiskLog.mark("rpc-reply", "id=$id ok=$ok okReal=$okReal err=${error ?: srvErr ?: ""}")
        cb(okReal, res, error ?: srvErr)
    }
    private data class Leg(val ip: String, val port: Int, val tx: String, val rx: String, val ssrc: Long, val txPt: Int, val rxPt: Int)
    @Volatile private var leg: Leg? = null
    // Call-audio lifecycle (signal-identical-calling): CallAudioManager (focus → mode applied → route, async, own thread)
    // then CallAudioEngine (WebRTC ADM) opens devices. Replaces awaitCommMode main-thread poll, PJSIP openSound retries
    // and 420003/-201 coercion. Every failure here is typed local — never relay/TURN/ICE restart.
    private var soundGeneration = 0
    @Volatile private var localFailure: String? = null
    private fun localError(f: CallFailure): String {
        localFailure = "native-media-error:" + f.toJson()
        CallDiskLog.mark("local-audio-failure", f.toJson())
        host?.emit(JSONObject().put("type", "pjsip:error").put("step", f.step).put("status", f.code)
            .put("domain", f.domain.name.lowercase()).put("networkRelated", false).put("model", android.os.Build.MODEL).toString())
        return localFailure!!
    }
    private fun openCallAudio(path: String, done: (CallFailure?) -> Unit) {
        val ctx = appCtx ?: return done(CallFailure(com.donnydey.openlounge.audio.CallFailureDomain.SESSION, path, -1, "no context"))
        val generation = soundGeneration
        val mgr = CallAudioManager.get(ctx); mgr.tracer = { e, d -> CallDiskLog.mark(e, d) }
        mgr.prepare(videoOn) { prep ->
            main.post {
                if (generation != soundGeneration) return@post done(CallFailure(com.donnydey.openlounge.audio.CallFailureDomain.SESSION, "cancelled", 0, "call ended"))
                if (prep != null) return@post done(prep)
                val out = mgr.devices.choose(videoOn)
                val inp = mgr.devices.inputs.firstOrNull { it.kind == out?.kind } ?: mgr.devices.inputs.firstOrNull()
                done(CallAudioEngine.open(inp, out, inp?.sampleRates ?: IntArray(0)))
            }
        }
    }
    // ---- P2P PeerConnection path (contract v1, rtc:signal / rtc:iceConfig over the page's OL socket via host.relay).
    // USE_RTC_PC=false restores the legacy PJSIP ol_sfu/ol_p2p media path for safe cutover. With the flag on, new calls
    // never start ol_sfu/ol_p2p audio. Until libwebrtc lands the call fails with a typed DEVICE error (no relay).
    @JvmField var USE_RTC_PC = true
    @Volatile private var rtc: com.donnydey.openlounge.rtc.RtcCallSession? = null
    private fun startRtc(id: String, caller: Boolean, media: JSONObject?, done: (Boolean, String?, String?) -> Unit) {
        CallDiskLog.mark("connect-rtc", "caller=$caller turn=${media?.optJSONArray("iceServers")?.length() ?: 0}")
        main.post { openCallAudio("rtc") { f ->
            if (f != null) return@openCallAudio done(false, null, localError(f))
            val sess = com.donnydey.openlounge.rtc.RtcCallSession(id, caller, media,
                request = { ev, data, cb -> rpc(ev, data, cb) },
                onConnected = { active = true; done(true, JSONObject().put("engine", "webrtc").put("path", "p2p").toString(), null) },
                onFailure = { fl -> CallDiskLog.mark("rtc-failure", fl.toJson()); done(false, null, if (fl.relayEligible) "network:" + fl.toJson() else localError(fl)) })
            rtc = sess; sess.start()
        } }
    }

    private fun startLeg(l: Leg, done: (Int) -> Unit) {
        CallDiskLog.mark("engine-start")
        val s0 = PjsipEngine.start() // transport leg only; no PJSIP sound device
        if (s0 != 0) { localError(CallFailure(com.donnydey.openlounge.audio.CallFailureDomain.SESSION, "transport-start", s0, "transport start $s0")); done(s0); return }
        openCallAudio("startLeg") { f ->
            if (f != null) { localError(f); done(f.code.takeIf { it != 0 } ?: -1) }
            else { leg = l; done(PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, true)) }
        }
    }

    @Volatile private var signal: CallSignalClient? = null
    @Volatile private var creds: Pair<String, String>? = null // in-memory only; never logged
    private fun connectSignal() { val (u, t) = creds ?: return; signal?.close()
        signal = CallSignalClient(u, t).also { sc ->
            sc.onMessage = { m -> main.post { onSignal(m) } }
            sc.onUiEvent = { ty, m -> host?.emit(JSONObject(m.toString()).put("type", ty).toString()) }
            sc.connect() } }
    /** FCM call_incoming woke us: reopen the signal socket so call-signal re-delivers the ring (connected()). */
    @JvmStatic fun onPushWake() = main.post { connectSignal() }

    /** Linphone cut: native owns accept signaling — load WS URL + access token without the WebView. */
    @JvmStatic fun ensureSignalCreds(ctx: Context): Boolean {
        val token = CallApi.freshAccessToken(ctx) ?: return false
        val origin = CallApi.origin(ctx)
        val ws = origin.replace(Regex("^http"), "ws") + "/ws"
        creds = ws to token
        return true
    }

    /** Answer from IncomingCallActivity / Telecom — no /call/id?accept=1 WebView path. */
    @JvmStatic fun acceptIncoming(ctx: Context, callId: String): Boolean {
        if (callId.isEmpty()) return false
        if (!ensureSignalCreds(ctx)) {
            CallDiskLog.mark("accept-native-fail", "no token")
            return false
        }
        main.post {
            connectSignal()
            val ok = signal?.accept(callId) == true
            CallDiskLog.mark("accept-native", "callId=$callId ok=$ok")
            // UI: show connecting state
            host?.emit(JSONObject().put("type", "call:answering").put("callId", callId).toString())
            this.callId = callId
        }
        return true
    }
    @Volatile private var callId: String? = null
    private var txKey = ""
    private fun newKey(): String { val b = ByteArray(30); java.security.SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }

    // SFU PlainTransport RPCs ride the page's authenticated socket: host.relay(id, "ms:...", data) -> onMsReply.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Boolean, JSONObject?, String?) -> Unit>()
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private const val RPC_TIMEOUT_MS = 8_000L
    private const val CONSUME_WAIT_MS = 20_000L
    /** ms:plain:consume every 500 ms until a consumer of [kind] comes back (peer produced) or the deadline passes. */
    private fun consumeUntil(d: () -> JSONObject, kind: String, deadline: Long, cb: (Boolean, JSONObject?, String?) -> Unit) {
        rpc("ms:plain:consume", d()) { ok, res, e ->
            if (!ok) return@rpc cb(false, null, e)
            val arr = res?.optJSONArray("consumers")
            for (i in 0 until (arr?.length() ?: 0)) { val c = arr!!.optJSONObject(i); if (c?.optString("kind") == kind) return@rpc cb(true, c, null) }
            if (callId == null) return@rpc cb(false, null, "ended")
            if (android.os.SystemClock.elapsedRealtime() > deadline) return@rpc cb(false, null, "peer $kind never produced")
            main.postDelayed({ consumeUntil(d, kind, deadline, cb) }, 500L)
        }
    }
    private const val TWO_WAY_TIMEOUT_MS = 15_000L
    /** "Connected" = audio both ways on the leg: >=10 RTP packets received AND sent (ol_sfu stats), else fail. */
    private fun awaitTwoWay(cb: (Boolean, String?) -> Unit) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun check() {
            if (!active) return cb(false, "leg closed")
            val st = runCatching { PjsipEngine.nativeSfuStats() }.getOrNull()
            if (st != null && st[0] >= 10 && (st[2] >= 10 || muted)) { // muted during setup: conf disconnected, rx alone proves the path
                CallDiskLog.mark("two-way", "rx=${st[0]} tx=${st[2]}")
                host?.emit(JSONObject().put("type", "pjsip:two-way").put("src", "rtp-stats").put("rx", st[0]).put("tx", st[2]).toString())
                return cb(true, null) }
            if (android.os.SystemClock.elapsedRealtime() - t0 > TWO_WAY_TIMEOUT_MS) {
                CallDiskLog.mark("two-way-timeout", "rx=${st?.get(0)} tx=${st?.get(2)}")
                return cb(false, "no two-way audio rx=${st?.get(0)} tx=${st?.get(2)}") }
            main.postDelayed({ check() }, 100L)
        }
        main.post { check() }
    }
    private fun rpc(event: String, data: JSONObject, cb: (Boolean, JSONObject?, String?) -> Unit) {
        val id = "pj${seq.incrementAndGet()}"; pending[id] = cb
        CallDiskLog.mark("rpc", "id=$id $event")
        if (host?.relay(id, event, data.toString()) != true) { pending.remove(id); cb(false, null, "no relay"); return }
        main.postDelayed({ pending.remove(id)?.let { CallDiskLog.mark("rpc-timeout", "id=$id $event"); it(false, null, "timeout $event") } }, RPC_TIMEOUT_MS)
    }
    private fun fail(step: String, e: String?, result: (Int, String?) -> Unit = { _, _ -> }) { CallDiskLog.mark("fail", "$step: $e"); result(-1, "$step: $e"); host?.emit(JSONObject().put("type", "pjsip:error").put("step", step).put("error", e ?: "").toString()) }

    /** join -> plain:create (SFU ip/port/key) -> plain:connect (our key) -> plain:produce (our ssrc) -> plain:consume -> start leg. */
    private fun requestSfu(id: String, result: (Int, String?) -> Unit = { _, _ -> }) {
        localFailure?.let { result(-1, it); return }
        txKey = newKey(); val ssrc = 100000L + java.security.SecureRandom().nextInt(1_000_000)
        val d = { JSONObject().put("callId", id) }
        rpc("ms:join", d()) { ok, _, e -> if (!ok) return@rpc fail("join", e, result)
          rpc("ms:plain:create", d()) { ok1, c, e1 -> if (!ok1 || c == null) return@rpc fail("create", e1, result)
            val ip = c.optString("ip"); val port = c.optInt("port"); val rx = c.optJSONObject("srtpParameters")?.optString("keyBase64").orEmpty()
            rpc("ms:plain:connect", d().put("srtpParameters", JSONObject().put("cryptoSuite", "AES_CM_128_HMAC_SHA1_80").put("keyBase64", txKey))) { ok2, _, e2 ->
              if (!ok2) return@rpc fail("connect", e2, result)
              rpc("ms:plain:produce", d().put("kind", "audio").put("ssrc", ssrc).put("payloadType", 111)) { ok3, _, e3 ->
                if (!ok3) return@rpc fail("produce", e3, result)
                // The SFU returns only producers that already exist (plain.mjs plain:consume); a later producer is only
                // announced as ms:consumer:available, which no native client handled -> the first phone to join never
                // consumed the other side = one-way audio. Poll consume until the peer's audio consumer exists, then
                // start the leg with its real payload type.
                consumeUntil(d, "audio", android.os.SystemClock.elapsedRealtime() + CONSUME_WAIT_MS) { ok4, cons, e4 ->
                  if (!ok4) return@consumeUntil fail("consume", e4, result)
                  val rxPt = cons?.optInt("payloadType", 111) ?: 111
                  CallDiskLog.mark("consume", "audio pt=$rxPt")
                  main.post { startLeg(Leg(ip, port, txKey, rx, ssrc, 111, rxPt)) { r ->
                    if (r == 0) { active = true; host?.nativeCallAudio(true) }; result(r, if (r != 0) localFailure ?: "leg start $r" else null)
                    host?.emit(JSONObject().put("type", "pjsip:audio").put("start", r).toString()) } } } } } } }
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
                consumeUntil(d, "video", android.os.SystemClock.elapsedRealtime() + CONSUME_WAIT_MS) { ok4, _, e4 -> if (!ok4) CallDiskLog.mark("video-consume", e4 ?: "") } // remote video arrives on the same leg; the SFU PLIs for a keyframe
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
            /**
             * Reuse connect.iceServers turn:/turns: URLs. Offer UDP + TCP + TLS:443 together
             * (Signal/Linphone/Jitsi), not a single preferred UDP URL. Encoded for ol_p2p as
             * "host:port|udp,host:port|tcp,host:443|tls".
             */
            fun from(c: JSONObject): P2pCfg? {
                val arr = c.optJSONArray("iceServers") ?: return null
                val ents = linkedSetOf<String>() // "host:port|transport"
                var user = ""; var pass = ""; var stunHost = ""
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val urls = s.optJSONArray("urls")?.let { a -> List(a.length()) { a.optString(it) } }
                        ?: listOf(s.optString("urls"))
                    for (u in urls) {
                        val turns = u.startsWith("turns:", ignoreCase = true)
                        val isTurn = u.startsWith("turn:", ignoreCase = true)
                        if (!turns && !isTurn) {
                            Regex("^stun[s]?:([^?]*)", RegexOption.IGNORE_CASE).find(u)?.let {
                                if (stunHost.isEmpty()) stunHost = it.groupValues[1]
                            }
                            continue
                        }
                        var rest = u.substringAfter(':')
                        val transport = when {
                            turns -> "tls"
                            Regex("transport=tcp", RegexOption.IGNORE_CASE).containsMatchIn(rest) -> "tcp"
                            Regex("transport=udp", RegexOption.IGNORE_CASE).containsMatchIn(rest) -> "udp"
                            else -> "udp"
                        }
                        rest = rest.substringBefore('?')
                        if (rest.isEmpty()) continue
                        val hp = if (turns && !rest.contains(':')) "$rest:443" else rest
                        ents.add("$hp|$transport")
                        if (user.isEmpty()) { user = s.optString("username"); pass = s.optString("credential") }
                        if (stunHost.isEmpty()) stunHost = hp
                    }
                }
                if (ents.isEmpty()) return null
                val primary = ents.first().substringBefore('|').let { if (it.contains(':')) it else "$it:3478" }
                val host = primary.substringBeforeLast(':')
                val basePort = primary.substringAfterLast(':').toIntOrNull() ?: 3478
                fun has(t: String) = ents.any { it.endsWith("|$t") }
                if (!has("udp")) ents.add("$host:$basePort|udp")
                if (!has("tcp")) ents.add("$host:$basePort|tcp")
                if (!has("tls")) ents.add("$host:443|tls")
                val turnMulti = ents.take(3).joinToString(",")
                CallDiskLog.mark("turn-multi", turnMulti)
                return P2pCfg(
                    c.optBoolean("p2pOfferer"), stunHost.ifEmpty { primary }, turnMulti, user, pass,
                    tcp = ents.any { it.endsWith("|tcp") || it.endsWith("|tls") },
                    relayOnly = c.optBoolean("relayOnly")
                )
            }
        }
        fun withRelayOnly(): P2pCfg = copy(relayOnly = true, tcp = true)
    }
    private const val P2P_OFF = 0; private const val P2P_TRYING = 1; private const val P2P_LIVE = 2; private const val P2P_FAILED = 3
    @Volatile private var p2pState = P2P_OFF
    private var p2pCfg: P2pCfg? = null
    private var p2pPendingOffer: String? = null
    private var p2pGen = 0
    @Volatile private var muted = false
    private var relayRetryUsed = false
    private const val P2P_TIMEOUT_MS = 15_000L
    private const val P2P_RELAY_RETRY_MS = 28_000L // Signal/LiveKit/Jitsi: ~25-30s relay-only before hangup

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
    private fun armTimeout(budgetMs: Long = P2P_TIMEOUT_MS) { val g = ++p2pGen; main.postDelayed({ if (g == p2pGen && p2pState == P2P_TRYING) p2pFail("timeout") }, budgetMs) }
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
        if (localFailure != null) return // never ICE/TURN-retry a sound/engine failure
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
        // First-connect miss: one relay-only retry with ~28s budget (Signal/LiveKit/Jitsi) before hangup.
        if (directDone != null && !relayRetryUsed && localFailure == null) {
            val base = p2pCfg ?: run {
                val d = directDone; directDone = null
                d?.invoke(false, null, "direct: $why"); return
            }
            relayRetryUsed = true
            p2pCfg = base.withRelayOnly()
            CallDiskLog.mark("relay-only-retry", "why=$why budget=$P2P_RELAY_RETRY_MS")
            host?.emit(JSONObject().put("type", "pjsip:p2p").put("state", "relay-retry").put("why", why).toString())
            p2pState = P2P_TRYING
            beginP2p(p2pCfg!!)
            armTimeout(P2P_RELAY_RETRY_MS) // replaces the 15s armed inside beginP2p
            return
        }
        val d = directDone; directDone = null
        if (d != null) { d(false, null, "direct: $why"); return }
        val g = p2pGen; main.postDelayed({ if (g == p2pGen && direct && p2pCfg != null && p2pState == P2P_FAILED) restartP2p(true) }, 1_000L)
    }

    // ---- Direct (phone<->phone) single path
    @Volatile private var direct = false
    private var directDone: ((Boolean, String?, String?) -> Unit)? = null
    private var directWantVideo = false
    private fun startDirect(wantVideo: Boolean, done: (Boolean, String?, String?) -> Unit) {
        if (p2pCfg == null) return done(false, null, "direct: no relay config")
        val s0 = PjsipEngine.start(); if (s0 != 0) return done(false, null, "direct: transport start $s0")
        openCallAudio("startDirect") { f ->
            if (f != null) { done(false, null, localError(f)); return@openCallAudio }
            directDone = done; directWantVideo = wantVideo; relayRetryUsed = false
            startP2p()
        }
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
    private fun stopP2p() { direct = false; directDone?.invoke(false, null, "ended"); directDone = null; videoP2p = false; relayRetryUsed = false; p2pGen++; p2pState = P2P_OFF; p2pCfg = null; p2pPendingOffer = null; runCatching { PjsipEngine.nativeP2pStop() } }

    @JvmStatic fun closeAll() = main.post { rtc?.close(); rtc = null; soundGeneration++; localFailure = null; CallDiskLog.mark("close-all"); stopP2p(); if (videoOn) { videoOn = false; NativeCallVideo.stop(); host?.nativeCallVideo(false) }; leg = null; runCatching { PjsipEngine.nativeSfuStop() }; PjsipEngine.teardown(); CallAudioEngine.close(); appCtx?.let { CallAudioManager.get(it).release() }; if (active) { active = false; host?.nativeCallAudio(false) } }
    @JvmStatic fun isActive(): Boolean = active
    @JvmStatic fun releaseIdle() { main.post { CallDiskLog.mark("release-idle"); PjsipEngine.teardown() } }
}
