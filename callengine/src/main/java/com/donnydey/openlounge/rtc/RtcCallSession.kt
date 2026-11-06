package com.donnydey.openlounge.rtc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.donnydey.openlounge.audio.CallFailure
import com.donnydey.openlounge.audio.CallFailureDomain
import org.json.JSONObject

/**
 * One P2P call over OL rtc:signal (contract v1). Caller offers; callee sends ready after its PC + mic track exist and
 * answers the offer. Network failure = ICE disconnected (2.5 s grace) or failed → caller ICE restart with fresh
 * rtc:iceConfig, backoff 0/1/2/4/8 s, give up after 45 s. Local/signaling failures never restart ICE or force relay.
 */
class RtcCallSession(
    private val callId: String, private val isCaller: Boolean, media: JSONObject?,
    /** sends one OL socket request {ev,data}; cb(ok, resultJson|error) */
    private val request: (String, JSONObject, (Boolean, JSONObject?, String?) -> Unit) -> Unit,
    private val onConnected: () -> Unit, private val onFailure: (CallFailure) -> Unit,
) {
    private val h = Handler(Looper.getMainLooper())
    private var closed = false
    private var connectedOnce = false
    private var restartStart = 0L; private var restartTry = 0
    private var localOffer: String? = null
    private var iceServers = RtcSignaling.iceServersFrom(media)
    val sig = RtcSignaling(callId, isCaller) { data, cb ->
        request("rtc:signal", data) { ok, res, err ->
            val code = res?.optString("error")?.takeIf { it.isNotEmpty() } ?: err
            if (!ok && code != "replayed" && code != "stale_generation") Log.w(TAG, "signal ${data.optString("kind")} err=$code")
            cb(ok || code == "replayed", code) }
    }

    fun start() {
        val r = OlPeerConnection.takeIf { it.load() }?.let { runCatching { it.nativeCreate(iceServers) }.getOrDefault(OlPeerConnection.ERR_UNAVAILABLE) }
            ?: OlPeerConnection.ERR_UNAVAILABLE
        if (r != 0) return fail(CallFailure(CallFailureDomain.DEVICE, "pc-create", r, "WebRTC PeerConnection unavailable ($r)"))
        if (isCaller) runCatching { OlPeerConnection.nativeCreateOffer(false) } else sig.ready()
        poll()
    }

    /** Inbound evt rtc:signal from the OL socket. */
    fun onSignal(raw: JSONObject) {
        if (closed) return
        val m = sig.accept(raw) ?: return
        when (m.optString("kind")) {
            "ready" -> if (isCaller) localOffer?.let { sig.offer(it) } ?: runCatching { OlPeerConnection.nativeCreateOffer(false) }
            "offer" -> if (!isCaller) OlPeerConnection.nativeSetRemote("offer", m.optString("sdp"))
            "answer" -> if (isCaller) OlPeerConnection.nativeSetRemote("answer", m.optString("sdp"))
            "candidate" -> { val c = m.optJSONObject("candidate"); if (sig.remoteSet) addCand(c) else sig.bufferCandidate(c) }
            "restart" -> if (isCaller) iceRestart()
            "bye" -> close()
        }
    }

    private fun addCand(c: JSONObject?) { if (c != null) OlPeerConnection.nativeAddCandidate(c.optString("candidate"), c.optString("sdpMid"), c.optInt("sdpMLineIndex")) }

    private fun poll() {
        if (closed) return
        while (true) {
            val ev = runCatching { OlPeerConnection.nativeTake() }.getOrNull() ?: break
            val p = ev.split('\n', limit = 5)
            when (p[0]) {
                "sdp" -> if (p[1] == "offer") { localOffer = p[2]; sig.offer(p[2]) } else sig.answer(p[2])
                "remote-set" -> { sig.remoteSet = true; sig.drainCandidates().forEach { addCand(it) }; if (!isCaller) OlPeerConnection.nativeCreateAnswer() }
                "cand" -> sig.candidate(JSONObject().put("sdpMid", p[1]).put("sdpMLineIndex", p[2].toInt()).put("candidate", p[3]))
                "cand-end" -> sig.candidate(null)
                "ice" -> onIce(p[1])
                "error" -> return fail(CallFailure(CallFailureDomain.valueOf(p[1].uppercase()), p.getOrElse(2) { "pc" }, p.getOrElse(3) { "-1" }.toIntOrNull() ?: -1, ev))
            }
        }
        h.postDelayed({ poll() }, 20)
    }

    private var graceGen = 0
    private fun onIce(state: String) {
        Log.i(TAG, "ice $state")
        when (state) {
            "connected", "completed" -> { graceGen++; restartTry = 0; restartStart = 0; if (!connectedOnce) { connectedOnce = true; onConnected() } }
            "disconnected" -> { val g = ++graceGen; h.postDelayed({ if (g == graceGen) networkLoss("disconnected") }, 2500) }
            "failed" -> networkLoss("failed")
        }
    }
    private fun networkLoss(why: String) {
        if (closed) return
        if (!isCaller) { sig.restart(); return } // callee asks; caller performs
        iceRestart()
    }
    private fun iceRestart() {
        val now = SystemClock.elapsedRealtime(); if (restartStart == 0L) restartStart = now
        if (now - restartStart > 45_000) return fail(CallFailure(CallFailureDomain.NETWORK, "ice", -1, "ICE did not recover in 45 s"))
        val delay = longArrayOf(0, 1000, 2000, 4000, 8000)[minOf(restartTry++, 4)]
        h.postDelayed({
            request("rtc:iceConfig", JSONObject().put("callId", callId)) { ok, res, _ ->
                res?.optJSONObject("config")?.optJSONArray("iceServers")?.let { iceServers = it.toString(); OlPeerConnection.nativeSetConfiguration(iceServers) }
                if (!ok) Log.w(TAG, "iceConfig refresh failed; restarting with current creds")
                sig.bumpGen()
                OlPeerConnection.nativeCreateOffer(true)
            } }, delay)
    }

    fun setMute(m: Boolean) = runCatching { OlPeerConnection.nativeSetMute(m) }
    private fun fail(f: CallFailure) { if (closed) return; onFailure(f); close() }
    fun close() { if (closed) return; closed = true; runCatching { sig.bye() }; runCatching { OlPeerConnection.nativeClose() }; h.removeCallbacksAndMessages(null) }

    companion object { const val TAG = "OLRtcCall" }
}
