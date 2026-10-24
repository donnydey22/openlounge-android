package com.donnydey.openlounge.p2p

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

/**
 * Keiko's Android lane: 1:1 P2P call on Stream WebRTC Android 1.3.10, cloned from ChatSwap's WebRtcBaseActivity
 * (setup order audio → video → PeerConnection → join → offer/answer; UNIFIED_PLAN, MAXBUNDLE, rtcpMux REQUIRE,
 * ICE ALL, GATHER_CONTINUALLY; front camera 640x480@24, MAINTAIN_RESOLUTION) with Open Lounge's steers in
 * [StreamCallProfile]. Signaling is the call-signal service (offer / answer / ice, peer-only). Not wired into
 * MainActivity yet: inert until a later, approved change routes Keiko's calls here.
 */
class StreamCallSession(
    private val ctx: Context,
    private val callId: String,
    private val video: Boolean,
    iceServers: List<StreamCallProfile.Ice>,
    private val signal: Signal,
    private val events: Events,
) {
    interface Signal {
        fun send(type: String, sdp: String? = null, candidate: IceCandidate? = null, iceRestart: Boolean = false)
    }
    interface Events {
        fun onConnected() {}
        fun onReconnecting() {}
        fun onPath(relayed: Boolean) {}
        fun onRemoteVideo(track: VideoTrack) {}
        fun onEnded(reason: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    var localVideo: VideoTrack? = null; private set
    private val restarts = StreamCallProfile.IceRestartBackoff()
    private val ladder = StreamCallProfile.VoiceLadder()
    private var relayed = false
    private var probeAttempt = 0
    private var closed = false
    private var restartPending: Runnable? = null

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val servers = StreamCallProfile.orderUdpFirst(iceServers).map { s ->
            PeerConnection.IceServer.builder(s.urls).apply { s.username?.let { setUsername(it) }; s.credential?.let { setPassword(it) } }.createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            // consent freshness / keepalive stays WebRTC's default (STUN binding checks every few seconds)
        }
        pc = factory.createPeerConnection(cfg, Obs())
        // setup order (ChatSwap): audio FIRST, then video
        audioSource = factory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        })
        audioTrack = factory.createAudioTrack("ol_audio", audioSource).also { it.setEnabled(true) }
        audioSender = pc?.addTrack(audioTrack, listOf("ol_stream"))
        audioSender?.let { s ->
            val p = s.parameters
            p.encodings.forEach { it.maxBitrateBps = StreamCallProfile.OPUS_MAX_AVG_BPS; it.bitratePriority = 4.0; it.networkPriority = 3 /* high */ }
            s.parameters = p
        }
        if (video) startVideo()
    }

    private fun startVideo() {
        val en = if (Camera2Enumerator.isSupported(ctx)) Camera2Enumerator(ctx) else Camera1Enumerator(true)
        capturer = en.deviceNames.firstOrNull { en.isFrontFacing(it) }?.let { en.createCapturer(it, null) }
        videoSource = factory.createVideoSource(false)
        capturer?.initialize(SurfaceTextureHelper.create("OlCapture", egl.eglBaseContext), ctx, videoSource?.capturerObserver)
        capturer?.startCapture(StreamCallProfile.VIDEO_WIDTH, StreamCallProfile.VIDEO_HEIGHT, StreamCallProfile.VIDEO_FPS)
        localVideo = factory.createVideoTrack("ol_video", videoSource)
        val vs = pc?.addTrack(localVideo, listOf("ol_video_stream"))
        vs?.let { s -> runCatching { val p = s.parameters; p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION; s.parameters = p } }
    }

    private fun sdpConstraints(restart: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
        if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
    }

    /** caller side, after call:accepted */
    fun offer(iceRestart: Boolean = false) {
        val peer = pc ?: return
        peer.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                val local = SessionDescription(d.type, StreamCallProfile.mungeOpus(d.description))
                peer.setLocalDescription(object : Sdp() { override fun onSetSuccess() { signal.send("offer", local.description, iceRestart = iceRestart) } }, local)
            }
        }, sdpConstraints(iceRestart))
    }

    fun onRemoteOffer(sdp: String) {
        val peer = pc ?: return
        peer.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                peer.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(d: SessionDescription) {
                        val local = SessionDescription(d.type, StreamCallProfile.mungeOpus(d.description))
                        peer.setLocalDescription(object : Sdp() { override fun onSetSuccess() { signal.send("answer", local.description) } }, local)
                    }
                }, sdpConstraints(false))
            }
        }, SessionDescription(SessionDescription.Type.OFFER, StreamCallProfile.mungeOpus(sdp)))
    }

    fun onRemoteAnswer(sdp: String) {
        pc?.setRemoteDescription(Sdp(), SessionDescription(SessionDescription.Type.ANSWER, StreamCallProfile.mungeOpus(sdp)))
    }

    fun onRemoteIce(c: IceCandidate) { pc?.addIceCandidate(c) }

    /** Every drop: fast ICE restart on the same peer path (UDP first), backoff-limited so it can't loop. */
    private fun restartIce(reason: String) {
        if (closed) return
        val now = System.currentTimeMillis()
        if (restarts.shouldRestart(now)) {
            Log.w(TAG, "ice restart ($reason) gap=${restarts.gapMs}")
            offer(iceRestart = true)
        } else if (restartPending == null) {
            restartPending = Runnable { restartPending = null; if (!connected) restartIce("$reason-retry") }.also { main.postDelayed(it, restarts.gapMs) }
        }
    }

    private var connected = false
    private inner class Obs : PeerConnection.Observer {
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            main.post {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        connected = true; restarts.onConnected(); events.onConnected(); startStatsLoop()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> {
                        connected = false; events.onReconnecting(); restartIce(s.name.lowercase())
                    }
                    PeerConnection.IceConnectionState.CLOSED -> events.onEnded("closed")
                    else -> {}
                }
            }
        }
        override fun onIceCandidate(c: IceCandidate) { signal.send("ice", candidate = c) }
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            main.post { if (track is VideoTrack) { track.setEnabled(true); events.onRemoteVideo(track) } else track?.setEnabled(true) }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(r: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>) {}
    }

    /** 1 s stats: voice ladder (48/32/24, 24k floor) + relay detection → in-place UDP probe (ICE restart, no teardown). */
    private var statsRunning = false
    private var nextProbeAt = 0L
    private fun startStatsLoop() {
        if (statsRunning) return
        statsRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (closed) return
                pc?.getStats { report -> main.post { onStats(report) } }
                main.postDelayed(this, 1_000)
            }
        }
        main.postDelayed(tick, 1_000)
    }

    private fun onStats(r: RTCStatsReport) {
        val pair = r.statsMap.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
        val local = pair?.let { r.statsMap[it.members["localCandidateId"] as? String] }
        val remote = pair?.let { r.statsMap[it.members["remoteCandidateId"] as? String] }
        val nowRelayed = StreamCallProfile.isRelayed(local?.members?.get("candidateType") as? String, remote?.members?.get("candidateType") as? String)
        if (nowRelayed != relayed) { relayed = nowRelayed; probeAttempt = 0; nextProbeAt = System.currentTimeMillis() + StreamCallProfile.udpProbeDelay(0); events.onPath(relayed) }
        if (relayed && connected && System.currentTimeMillis() >= nextProbeAt) {
            // probe UDP in place: ICE restart keeps the same tracks/senders, so audio keeps flowing over the relay meanwhile
            offer(iceRestart = true)
            nextProbeAt = System.currentTimeMillis() + StreamCallProfile.udpProbeDelay(++probeAttempt)
        }
        val rttS = pair?.members?.get("currentRoundTripTime") as? Double
        val inbound = r.statsMap.values.firstOrNull { it.type == "remote-inbound-rtp" && it.members["kind"] == "audio" }
        val loss = (inbound?.members?.get("fractionLost") as? Double)?.times(100)
        ladder.push(loss, rttS?.times(1000))?.let { bps ->
            audioSender?.let { s -> val p = s.parameters; p.encodings.forEach { it.maxBitrateBps = bps }; s.parameters = p }
        }
    }

    fun setMuted(m: Boolean) { audioTrack?.setEnabled(!m) }

    /** page / network-change request: same backoff-limited every-drop restart */
    fun restartIceNow(why: String) = restartIce(why)

    fun close(reason: String = "hangup") {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); videoSource?.dispose(); localVideo?.dispose()
        pc?.close(); pc?.dispose(); pc = null
        audioTrack?.dispose(); audioSource?.dispose()
        factory.dispose(); egl.release()
        events.onEnded(reason)
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.e(TAG, "sdp create: $e") }
        override fun onSetFailure(e: String?) { Log.e(TAG, "sdp set: $e") }
    }

    companion object { private const val TAG = "OlStreamCall" }
}
