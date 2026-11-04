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
    }

    @JvmStatic @Volatile var host: Host? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false

    @JvmStatic fun init(ctx: Context) {}

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
            "connect" -> { val id = JSONObject(cmdJson).optString("callId"); if (id.isEmpty()) return done(false, null, "callId required")
                callId = id; requestSfu(id) { r, err -> if (r == 0) done(true, JSONObject().put("engine", "pjsip").toString(), null) else done(false, null, err ?: "pjsip start $r") } }
            "mute" -> { val m = runCatching { JSONObject(cmdJson).optBoolean("muted") }.getOrDefault(false)
                val r = runCatching { PjsipEngine.nativeSfuSetMute(m) }.getOrDefault(-1); done(r >= 0, (r >= 0).toString(), if (r < 0) "mute failed" else null) }
            "end" -> { callId = null; closeAll(); done(true, "true", null) }
            "stats" -> { val s = runCatching { PjsipEngine.nativeSfuStats() }.getOrNull()
                done(true, JSONObject().put("rxPkt", s?.getOrNull(0)).put("rxLost", s?.getOrNull(1)).put("txPkt", s?.getOrNull(2)).toString(), null) }
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

    private fun onSignal(m: JSONObject) {
        when (m.optString("t")) {
            "call:accepted" -> { val id = m.optString("callId"); callId = id; requestSfu(id) }
            "call:ended" -> { callId = null; closeAll() }
        }
    }

    /** Network changed (Wi-Fi <-> cellular): re-bind the RTP socket to the new interface, same SRTP keys/SSRC. */
    @JvmStatic fun onNetworkChange() = main.post {
        val l = leg ?: return@post
        if (!active) return@post
        PjsipEngine.nativeSfuStop()
        val r = PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, false)
        host?.emit(JSONObject().put("type", "pjsip:netchange").put("restart", r).toString())
    }
    @JvmStatic fun closeAll() = main.post { leg = null; runCatching { PjsipEngine.nativeSfuStop() }; PjsipEngine.teardown(); if (active) { active = false; host?.nativeCallAudio(false) } }
    @JvmStatic fun isActive(): Boolean = active
    @JvmStatic fun releaseIdle() { PjsipEngine.teardown() }
}
