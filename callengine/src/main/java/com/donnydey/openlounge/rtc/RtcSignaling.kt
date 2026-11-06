package com.donnydey.openlounge.rtc

import org.json.JSONObject

/**
 * rtc:signal state per OL contract v1 (/workspace/builds/signal-rebuild-rtc-contract.md).
 * gen: call-scoped, starts 0, bumped on ICE restart; lower inbound gen ignored; higher gen resets replay state.
 * seq: strictly increasing per sender within a gen, reset on new gen; duplicates are benign (replayed).
 * Remote candidates buffered until remote description is set. Pure logic — unit-testable without WebRTC.
 */
class RtcSignaling(val callId: String, val isCaller: Boolean, private val send: (JSONObject, (Boolean, String?) -> Unit) -> Unit) {
    var gen = 0; private set
    private var txSeq = 0
    private var rxSeq = -1
    var remoteSet = false
    private val pendingCands = ArrayList<JSONObject?>()

    fun bumpGen() { gen++; txSeq = 0; rxSeq = -1; remoteSet = false; pendingCands.clear() }

    private fun base(kind: String) = JSONObject().put("callId", callId).put("gen", gen).put("seq", txSeq++).put("kind", kind)
    fun ready(cb: (Boolean, String?) -> Unit = { _, _ -> }) = send(base("ready"), cb)
    fun offer(sdp: String, cb: (Boolean, String?) -> Unit = { _, _ -> }) = send(base("offer").put("sdp", sdp), cb)
    fun answer(sdp: String, cb: (Boolean, String?) -> Unit = { _, _ -> }) = send(base("answer").put("sdp", sdp), cb)
    fun candidate(c: JSONObject?) = send(base("candidate").put("candidate", c ?: JSONObject.NULL)) { _, _ -> }
    fun restart() { bumpGen(); send(base("restart")) { _, _ -> } }
    fun bye() = send(base("bye")) { _, _ -> }

    /** Returns the message if it should be processed, null if stale/duplicate/foreign. Applies gen rules. */
    fun accept(m: JSONObject): JSONObject? {
        if (m.optString("callId") != callId) return null
        val from = m.optString("from"); if (from == (if (isCaller) "caller" else "callee")) return null
        val g = m.optInt("gen", -1); val s = m.optInt("seq", -1)
        if (g < gen) return null
        if (g > gen) { gen = g; txSeq = 0; rxSeq = -1; remoteSet = false; pendingCands.clear() }
        if (s <= rxSeq) return null
        rxSeq = s; return m
    }
    fun bufferCandidate(c: JSONObject?) { pendingCands.add(c) }
    fun drainCandidates(): List<JSONObject?> = pendingCands.toList().also { pendingCands.clear() }

    companion object {
        /** Signaling error codes from the contract — all SIGNALING domain, never ICE restart/relay. */
        val CODES = setOf("bad_request", "too_large", "rate_limited", "unknown_call", "not_participant", "not_active", "stale_generation", "replayed")
        /** iceServers JSON + contract STUN. */
        fun iceServersFrom(media: JSONObject?): String {
            val arr = org.json.JSONArray().put(JSONObject().put("urls", org.json.JSONArray().put("stun:turn.openlounge.app:13478")))
            media?.optJSONArray("iceServers")?.let { for (i in 0 until it.length()) arr.put(it.get(i)) }
            return arr.toString()
        }
    }
}
