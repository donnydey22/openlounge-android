package com.donnydey.openlounge.rtc

import android.util.Log
import com.donnydey.openlounge.audio.CallAudioEngine
import org.json.JSONArray
import org.webrtc.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One PeerConnection on the CallAudioEngine factory (BSD libwebrtc m152 Java API). Contract config: iceTransportPolicy
 * all, bundle max-bundle, rtcpMux require, unified plan; one Opus audio track. Async results are queued and drained
 * by RtcCallSession via [nativeTake] (same string protocol as before):
 * "sdp\n<type>\n<sdp>" | "cand\n<mid>\n<idx>\n<cand>" | "cand-end" | "ice\n<state>" | "remote-set" | "error\n<domain>\n<step>\n<code>"
 */
object OlPeerConnection {
    const val ERR_UNAVAILABLE = -1000
    private val q = ConcurrentLinkedQueue<String>()
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null

    fun load(): Boolean = CallAudioEngine.factory != null

    private fun servers(json: String): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.opt("urls"); val urls = ArrayList<String>()
            if (u is JSONArray) for (j in 0 until u.length()) urls.add(u.getString(j)) else if (u is String) urls.add(u)
            if (urls.isEmpty()) continue
            out.add(PeerConnection.IceServer.builder(urls).setUsername(o.optString("username", ""))
                .setPassword(o.optString("credential", "")).createIceServer())
        }
        return out
    }
    private fun config(json: String) = PeerConnection.RTCConfiguration(servers(json)).apply {
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) { q.add("cand\n${c.sdpMid}\n${c.sdpMLineIndex}\n${c.sdp}") }
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) q.add("cand-end") }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { q.add("ice\n" + s.name.lowercase()) }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) { Log.i(TAG, "remote track ${r.track()?.kind()}") }
    }

    private fun err(domain: String, step: String, msg: String?) { Log.e(TAG, "$step: $msg"); q.add("error\n$domain\n$step\n-1") }

    @JvmStatic fun nativeCreate(iceServersJson: String): Int {
        val f = CallAudioEngine.factory ?: return ERR_UNAVAILABLE
        nativeClose()
        val p = f.createPeerConnection(config(iceServersJson), observer) ?: return -2
        val src = f.createAudioSource(MediaConstraints()); val t = f.createAudioTrack("ol-mic", src)
        p.addTrack(t, listOf("ol")) ?: run { p.dispose(); return -3 }
        pc = p; source = src; track = t
        return 0
    }
    @JvmStatic fun nativeSetConfiguration(iceServersJson: String): Int = if (pc?.setConfiguration(config(iceServersJson)) == true) 0 else -1

    private fun sdpObs(onCreate: ((SessionDescription) -> Unit)?, onSet: (() -> Unit)?, step: String, codecDomain: Boolean) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { onCreate?.invoke(d) }
        override fun onSetSuccess() { onSet?.invoke() }
        // SDP create/apply failures are CODEC (local): never network, never relay.
        override fun onCreateFailure(e: String?) = err(if (codecDomain) "codec" else "session", step, e)
        override fun onSetFailure(e: String?) = err(if (codecDomain) "codec" else "session", step, e)
    }
    private fun createThenSet(offer: Boolean, restart: Boolean) {
        val p = pc ?: return
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val type = if (offer) "offer" else "answer"
        val obs = sdpObs({ d -> p.setLocalDescription(sdpObs(null, { q.add("sdp\n$type\n${d.description}") }, "set-local", true), d) }, null, "create-$type", true)
        if (offer) p.createOffer(obs, mc) else p.createAnswer(obs, mc)
    }
    @JvmStatic fun nativeCreateOffer(iceRestart: Boolean): Int { pc ?: return ERR_UNAVAILABLE; createThenSet(true, iceRestart); return 0 }
    @JvmStatic fun nativeCreateAnswer(): Int { pc ?: return ERR_UNAVAILABLE; createThenSet(false, false); return 0 }
    @JvmStatic fun nativeSetRemote(type: String, sdp: String): Int {
        val p = pc ?: return ERR_UNAVAILABLE
        val t = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        p.setRemoteDescription(sdpObs(null, { q.add("remote-set") }, "set-remote", true), SessionDescription(t, sdp)); return 0
    }
    @JvmStatic fun nativeAddCandidate(cand: String, mid: String, idx: Int): Int = if (pc?.addIceCandidate(IceCandidate(mid, idx, cand)) == true) 0 else -1
    @JvmStatic fun nativeSetMute(muted: Boolean) { track?.setEnabled(!muted) }
    @JvmStatic fun nativeTake(): String? = q.poll()
    @JvmStatic fun nativeClose() {
        runCatching { pc?.close(); pc?.dispose() }; pc = null
        runCatching { track?.dispose() }; track = null
        runCatching { source?.dispose() }; source = null
        q.clear()
    }
    private const val TAG = "OLRtcPC"
}
