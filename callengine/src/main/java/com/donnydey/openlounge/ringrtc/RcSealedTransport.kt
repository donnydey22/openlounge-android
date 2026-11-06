package com.donnydey.openlounge.ringrtc

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

typealias RcRequest = (String, JSONObject, (Boolean, JSONObject?, String?) -> Unit) -> Unit

/**
 * RingRTC session-model signaling: rtc:signal payloads from RtcCallSession are E2E-sealed (OLX3DH-v1) and sent as
 * rc:msg {callId, dev, envs:{peerDev: env}}; inbound rc:msg is opened and handed to [onSignal]. Caller fans out to all
 * peer devices until one replies, then locks (Signal multi-device). Mirrors web SealedSignaling.ts exactly.
 * Crypto runs on one serial worker (ordering = ratchet order); callbacks return on the main looper.
 */
class RcSealedTransport(
    private val keys: OlKeyStore, private val callId: String, private val isCaller: Boolean,
    private val request: RcRequest, private val onSignal: (JSONObject) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "ol-rc-seal") }
    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<String, OlSession>()
    private var locked: String? = null
    private val pending = ArrayList<Pair<JSONObject, (Boolean, JSONObject?, String?) -> Unit>>()
    @Volatile private var closed = false
    private val me by lazy { keys.identity() }
    private val myDev by lazy { keys.deviceId() }
    @Volatile private var initFailed: String? = null

    private var initDone = !isCaller
    init { if (isCaller) fetchKeys(0) }

    // Never wait on the crypto executor for a callback queued onto that same executor.
    private fun fetchKeys(attempt: Int) {
        if (closed) return
        request("rc:keys:fetch", JSONObject().put("callId", callId)) { ok, res, err ->
            if (closed) return@request
            io.execute {
                if (closed) return@execute
                val arr = res?.optJSONArray("bundles")
                val code = res?.optString("error") ?: err
                if ((!ok || arr == null || arr.length() == 0) && code == "no_keys" && attempt < 20) {
                    main.postDelayed({ fetchKeys(attempt + 1) }, 250)
                    return@execute
                }
                try {
                    if (!ok || arr == null || arr.length() == 0) throw IllegalStateException("rc:keys:fetch $code")
                    for (i in 0 until arr.length()) runCatching { val b = OlBundle.from(arr.getJSONObject(i)); sessions[b.deviceId] = OlSession.initiate(me, b) }
                        .onFailure { Log.w(TAG, "bundle reject: ${it.message}") }
                    if (sessions.isEmpty()) throw IllegalStateException("no valid peer device bundle")
                } catch (t: Throwable) { initFailed = t.message ?: "init"; Log.w(TAG, "initiate: $initFailed") }
                initDone = true
                flushPendingLocked()
            }
        }
    }

    /** RtcCallSession's request seam: rtc:signal is sealed, everything else (rtc:iceConfig) passes through. */
    fun send(ev: String, data: JSONObject, cb: (Boolean, JSONObject?, String?) -> Unit) {
        if (ev != "rtc:signal") return request(ev, data, cb)
        io.execute {
            if (closed) return@execute
            initFailed?.let { f -> main.post { cb(false, null, "signaling:$f") }; return@execute }
            if (!initDone || sessions.isEmpty()) { pending.add(data to cb); if (!isCaller) main.post { cb(true, null, null) }; return@execute }
            sealAndSend(data, cb)
        }
    }

    private fun flushPendingLocked() {
        val p = pending.toList(); pending.clear()
        p.forEach { (d, cb) -> if (initFailed != null) main.post { cb(false, null, "signaling:$initFailed") } else sealAndSend(d, if (isCaller) cb else { _, _, _ -> }) }
    }

    private fun sealAndSend(data: JSONObject, cb: (Boolean, JSONObject?, String?) -> Unit) {
        val plain = data.toString().toByteArray()
        val envs = JSONObject()
        for ((dev, s) in sessions) if (locked == null || dev == locked) envs.put(dev, s.seal(plain))
        request("rc:msg", JSONObject().put("callId", callId).put("dev", myDev).put("envs", envs), cb)
    }

    /** inbound server evt rc:msg (via page relay op "rcMsg" or native CallSignalClient) */
    fun onRcMsg(m: JSONObject) = io.execute {
        if (closed || m.optString("callId") != callId) return@execute
        val dev = m.optString("dev").takeIf { it.isNotEmpty() } ?: return@execute
        val env = m.optJSONObject("envs")?.optJSONObject(myDev) ?: return@execute
        if (locked != null && locked != dev) return@execute
        try {
            var s = sessions[dev]
            if (s == null) {
                if (isCaller) return@execute
                val pre = env.optJSONObject("pre") ?: throw SecurityException("first message lacks prekey header")
                s = OlSession.respond(me, keys.spkPriv(pre.getInt("spkId")), keys.takeOpk(if (pre.has("opkId")) pre.getInt("opkId") else null), pre)
                sessions[dev] = s
            }
            val inner = JSONObject(String(s.open(env)))
            if (locked == null) { locked = dev; s.confirmed(); sessions.keys.retainAll(setOf(dev)); flushPendingLocked() }
            main.post { if (!closed) onSignal(inner) }
        } catch (t: Throwable) { Log.w(TAG, "open failed: ${t.message}") }
    }

    fun close() { closed = true; io.execute { sessions.clear(); pending.clear() }; io.shutdown() }

    companion object {
        private const val TAG = "OLRc"
        /** publish this device's bundle (call on signal-socket connect / login) */
        fun publish(keys: OlKeyStore, request: RcRequest) = Executors.newSingleThreadExecutor().apply {
            execute { runCatching { keys.publishable() }.onSuccess { request("rc:keys:publish", it) { ok, _, e -> if (!ok) Log.w(TAG, "publish failed $e") } } }
            shutdown() }
    }
}
