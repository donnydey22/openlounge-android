package com.donnydey.openlounge

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.crow_misia.mediasoup.Consumer
import io.github.crow_misia.mediasoup.Device
import io.github.crow_misia.mediasoup.MediasoupClient
import io.github.crow_misia.mediasoup.Producer
import io.github.crow_misia.mediasoup.RecvTransport
import io.github.crow_misia.mediasoup.SendTransport
import io.github.crow_misia.mediasoup.Transport
import io.github.crow_misia.webrtc.log.LogHandler
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.3.16 native call media (Android) — same contract as openlounge-ios src/nativeMedia.ts.
 *
 * The app, not the WebView, owns the microphone, the mediasoup send/recv transports (libmediasoupclient), remote
 * audio playout (JavaAudioDeviceModule, USAGE_VOICE_COMMUNICATION, hardware AEC/NS) and the call audio mode/focus.
 * The page is UI + signaling only: every ms:* request is relayed over its authenticated socket
 * (window.__olNativeMsReq → OpenLoungeNative.nativeMsReply).
 *
 * Page → app ops (OpenLoungeNativeMedia.nativeMedia(cmd) → Promise): prewarm | connect{callId,iceServers,tcpOnly,rebuild?} |
 * connectLiveKit{callId,url,token,iceServers} | status | consumerAvailable | mute | restartIce | ladder | stats | close.
 * App → page: CustomEvent 'openlounge:native-media' {state:'connected'|'disconnected'|'failed', reconnecting?, attempt?}.
 *
 * Reconnect (Donny 23:23): on any transport loss the app keeps retrying on its own — ICE restart, then a full
 * transport rebuild on the same nativeConn — with backoff (0.5 s → 15 s cap), reporting 'disconnected'
 * (page shows Reconnecting… and walks its UDP → TCP 40443 → LiveKit ladder). The app never ends a call by itself:
 * media is torn down only by op 'close' or the page/native call:ended (hangup, or peer gone past the server grace).
 * A page reload keeps everything running; 'connect' on the same call + path re-attaches.
 */
object NativeCallMedia {
    private const val TAG = "OLNativeMedia"
    private const val RELAY_TIMEOUT_MS = 9_000L
    private const val CONNECT_TIMEOUT_MS = 12_000L

    /** UI-thread hooks implemented by MainActivity (relay + events + audio route). */
    interface Host {
        /** run window.__olNativeMsReq(id, event, dataJson) in the page; false if no page is up */
        fun relay(id: String, event: String, dataJson: String): Boolean
        fun emit(detailJson: String)
        /** enter/leave MODE_IN_COMMUNICATION + route policy (existing MainActivity.callAudio) */
        fun nativeCallAudio(on: Boolean)
    }

    @JvmStatic @Volatile var host: Host? = null
    private lateinit var app: Application
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ol-native-media").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val seq = AtomicLong()
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var session: MsSession? = null
    private var lk: NativeLiveKit? = null
    private var focus: AudioFocusRequest? = null

    @JvmStatic fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext as Application
    }

    // ---------------------------------------------------------------- bridge entry points
    /** page → app command; [done] gets (ok, resultJson, error) on any thread */
    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val cmd = try { JSONObject(cmdJson) } catch (e: Exception) { return done(false, null, "bad command") }
        exec.execute {
            try { done(true, run(cmd)?.toString() ?: "null", null) }
            catch (e: Throwable) { Log.w(TAG, "op ${cmd.optString("op")} failed: $e"); done(false, null, e.message ?: e.toString()) }
        }
    }

    /** page's answer to an app-originated ms:* request */
    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {
        val f = pending.remove(id) ?: return
        if (!ok) { f.completeExceptionally(RuntimeException(error ?: "relay failed")); return }
        f.complete(try { JSONObject(resultJson ?: "{}") } catch (e: Exception) { JSONObject() })
    }

    @JvmStatic fun onNetworkChange() = exec.execute { session?.restartIceAll("network") }

    /** native call:ended (hangup / declined / server end) */
    @JvmStatic fun closeAll() = exec.execute { closeAllInner(true) }

    @JvmStatic fun isActive(): Boolean = session != null || lk != null

    /** idle (no call media): fully stop the native audio engine — ADM, mic source and factory released, so nothing
     *  keeps the device in voice-call audio shape when the next ringtone plays. Rebuilt on the next prewarm/connect. */
    @JvmStatic fun releaseIdle() = exec.execute { releaseIdleInner() }

    private fun releaseIdleInner() {
        if (session != null || lk != null) return
        runCatching { preTrack?.dispose() }; preTrack = null
        runCatching { audioSource?.dispose() }; audioSource = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { adm?.release() }; adm = null
    }

    // ---------------------------------------------------------------- ops (media thread)
    private fun run(cmd: JSONObject): Any? {
        when (cmd.optString("op")) {
            "prewarm" -> { prewarm(); return true }
            "connect" -> {
                val callId = cmd.optString("callId")
                require(callId.isNotEmpty()) { "callId required" }
                val tcpOnly = cmd.optBoolean("tcpOnly", false)
                val ice = cmd.optJSONArray("iceServers") ?: JSONArray()
                val cur = session
                when (NativeMediaPolicy.connectAction(cur != null && !cur.closed, cur?.callId, cur?.tcpOnly ?: false, callId, tcpOnly, cmd.optBoolean("rebuild", false))) {
                    NativeMediaPolicy.ConnectAction.ATTACH -> {
                        // page reload on the same call + path: re-attach, nothing is rebuilt
                        cur!!.iceServers = ice
                        if (!cur.connected) cur.awaitConnected(CONNECT_TIMEOUT_MS)
                        return JSONObject().put("attached", true)
                    }
                    NativeMediaPolicy.ConnectAction.REBUILD_IN_PLACE -> {
                        // outage recovery asked by the page: fresh transports now (iOS 071d489 parity), same nativeConn + mic
                        cur!!.iceServers = ice
                        cur.rebuildNow()
                        return JSONObject().put("attached", false)
                    }
                    NativeMediaPolicy.ConnectAction.NEW_SESSION -> {}
                }
                cur?.close(); session = null
                lk?.close(); lk = null
                val s = MsSession(callId, ice, tcpOnly)
                session = s
                try { s.connect(); } catch (e: Throwable) {
                    if (session === s) { s.close(); session = null }
                    throw e
                }
                return JSONObject().put("attached", false)
            }
            "connectLiveKit" -> {
                val callId = cmd.optString("callId")
                val cur = lk
                if (cur != null && cur.callId == callId && !cur.closed) return JSONObject().put("attached", true)
                session?.close(); session = null
                cur?.close()
                ensureFactory()
                val l = NativeLiveKit(app, callId) { emitState(it) }
                lk = l
                audioOn(true)
                try { l.connectBlocking(cmd.optString("url"), cmd.optString("token"), cmd.optJSONArray("iceServers") ?: JSONArray(), CONNECT_TIMEOUT_MS) }
                catch (e: Throwable) { if (lk === l) { l.close(); lk = null }; throw e }
                return JSONObject().put("attached", false)
            }
            "status" -> {
                session?.let { return JSONObject().put("callId", it.callId).put("kind", if (it.tcpOnly) "mediasoup-tcp" else "mediasoup").put("connected", it.connected) }
                lk?.let { return JSONObject().put("callId", it.callId).put("kind", "livekit").put("connected", it.connected) }
                return null
            }
            "consumerAvailable" -> { session?.syncConsumers(); return true }
            "mute" -> { val m = cmd.optBoolean("muted"); session?.setMuted(m); lk?.setMuted(m); return true }
            "restartIce" -> { session?.restartIceAll("page"); return true }
            "ladder" -> return session?.ladder(cmd.optInt("audioBps", 48_000)) ?: false
            "stats" -> return session?.stats() ?: JSONArray()
            "close" -> { closeAllInner(false); return true }
        }
        throw IllegalArgumentException("unknown op")
    }

    // ---------------------------------------------------------------- WebRTC stack
    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        MediasoupClient.initialize(app, object : LogHandler {
            override fun log(priority: Int, tag: String?, t: Throwable?, message: String?, vararg args: Any?) {}
        })
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val a = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(attrs)
            .setUseStereoInput(false).setUseStereoOutput(false)
            .createAudioDeviceModule()
        adm = a
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(a).createPeerConnectionFactory()
        factory = f
        return f
    }

    private var preTrack: AudioTrack? = null

    /** Call / Answer tapped: load the stack and open the mic before the invite, so connect is pure network work. */
    private fun prewarm() {
        val f = ensureFactory()
        if (preTrack == null) preTrack = newMicTrack(f)
    }

    private fun newMicTrack(f: PeerConnectionFactory): AudioTrack {
        val src = audioSource ?: f.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }).also { audioSource = it }
        return f.createAudioTrack("ol-mic-" + seq.incrementAndGet(), src)
    }

    private fun takeMic(f: PeerConnectionFactory): AudioTrack {
        val t = preTrack
        preTrack = null
        if (t != null && runCatching { t.state() == org.webrtc.MediaStreamTrack.State.LIVE }.getOrDefault(false)) return t
        runCatching { t?.dispose() }
        return newMicTrack(f)
    }

    /** voice-call audio focus + communication mode, taken when media starts (WebView no longer owns playback) */
    private fun audioOn(on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (on) {
            if (focus == null && Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                runCatching { am.requestAudioFocus(r) }
                focus = r
            }
            main.post { host?.nativeCallAudio(true) }
        } else {
            focus?.let { r -> if (Build.VERSION.SDK_INT >= 26) runCatching { am.abandonAudioFocusRequest(r) } }
            focus = null
        }
    }

    private fun closeAllInner(dropPrewarm: Boolean) {
        session?.close(); session = null
        lk?.close(); lk = null
        if (dropPrewarm) { runCatching { preTrack?.dispose() }; preTrack = null }
        audioOn(false)
        pending.values.forEach { it.completeExceptionally(RuntimeException("native media closed")) }
        pending.clear()
        if (dropPrewarm) releaseIdleInner()
    }

    private fun emitState(detail: JSONObject) {
        val s = detail.toString()
        main.post { host?.emit(s) }
    }

    /** app → page ms:* request, blocking (called on the media thread or libmediasoupclient's signaling thread) */
    private fun request(event: String, data: JSONObject): JSONObject {
        val id = "m" + seq.incrementAndGet()
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        val h = host
        val posted = CompletableFuture<Boolean>()
        main.post { posted.complete(h?.relay(id, event, data.toString()) == true) }
        try {
            if (!posted.get(2, TimeUnit.SECONDS)) throw RuntimeException("no page relay")
            return f.get(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            pending.remove(id)
        }
    }

    // ---------------------------------------------------------------- one mediasoup media session
    private class MsSession(val callId: String, @Volatile var iceServers: JSONArray, val tcpOnly: Boolean) {
        /** stable server-side SFU connection id: a page reload (new socket) re-attaches instead of tearing down */
        val nativeConn = "n_" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e12).toLong(), 36)
        @Volatile var closed = false
        @Volatile var connected = false
        private var device: Device? = null
        private var send: SendTransport? = null
        private var recv: RecvTransport? = null
        private var producer: Producer? = null
        private var mic: AudioTrack? = null
        private var muted = false
        private val consumers = HashMap<String, Consumer>()
        private var connectedWaiter = CompletableFuture<Boolean>()
        private var recovery: ScheduledFuture<*>? = null
        private var attempt = 0
        private var gen = 0

        private fun r(event: String, extra: JSONObject = JSONObject()): JSONObject {
            extra.put("callId", callId).put("nativeConn", nativeConn)
            return request(event, extra)
        }

        private fun check() { if (closed) throw IllegalStateException("native media closed") }

        fun connect() {
            build(firstTime = true)
            awaitConnected(CONNECT_TIMEOUT_MS)
            audioOn(true)
        }

        fun awaitConnected(ms: Long) {
            try { connectedWaiter.get(ms, TimeUnit.MILLISECONDS) }
            catch (e: java.util.concurrent.TimeoutException) { throw RuntimeException("native media connect timed out") }
            catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            check()
        }

        /** join + device + send/recv transports + mic producer + consumers (fresh transports on every rebuild) */
        private fun build(firstTime: Boolean) {
            val f = ensureFactory()
            teardownTransports()
            val myGen = ++gen
            val joined = r("ms:join")
            if (!joined.optBoolean("ok") || !joined.has("routerRtpCapabilities")) throw RuntimeException("Mediasoup admission failed")
            check()
            val d = device ?: f.let { Device(it).also { dev -> dev.load(joined.getJSONObject("routerRtpCapabilities").toString(), null); device = dev } }
            val extra = { dir: String -> JSONObject().put("direction", dir).apply { if (tcpOnly) put("tcpOnly", true) } }
            val so = r("ms:transport:create", extra("send"))
            val ro = r("ms:transport:create", extra("recv"))
            val st = so.optJSONObject("transport"); val rt = ro.optJSONObject("transport")
            if (!so.optBoolean("ok") || st == null || !ro.optBoolean("ok") || rt == null) throw RuntimeException("Mediasoup transport setup failed")
            check()
            val cfg = rtcConfig()
            val listener = TransportListener(myGen)
            send = d.createSendTransport(listener, st.getString("id"), st.get("iceParameters").toString(), st.get("iceCandidates").toString(),
                st.get("dtlsParameters").toString(), null, cfg, null)
            recv = d.createRecvTransport(listener, rt.getString("id"), rt.get("iceParameters").toString(), rt.get("iceCandidates").toString(),
                rt.get("dtlsParameters").toString(), null, cfg, null)
            if (mic == null || firstTime) mic = takeMic(f)
            mic!!.setEnabled(!muted)
            val enc = RtpParameters.Encoding(null, true, null).apply { maxBitrateBps = 48_000 }
            producer = send!!.produce(object : Producer.Listener { override fun onTransportClose(producer: Producer) {} }, mic!!, listOf(enc),
                JSONObject().put("opusStereo", false).put("opusDtx", false).put("opusFec", true).put("opusMaxPlaybackRate", 48000).toString(),
                null, JSONObject().put("source", "mic").put("native", true).put("platform", "android").toString())
            check()
            syncConsumers()
        }

        private fun rtcConfig(): PeerConnection.RTCConfiguration {
            val list = ArrayList<PeerConnection.IceServer>()
            for (i in 0 until iceServers.length()) {
                val o = iceServers.optJSONObject(i) ?: continue
                val urls = when (val u = o.opt("urls")) { is JSONArray -> (0 until u.length()).map { u.getString(it) }; is String -> listOf(u); else -> emptyList() }
                if (urls.isEmpty()) continue
                val b = PeerConnection.IceServer.builder(urls)
                o.optString("username").takeIf { it.isNotEmpty() }?.let { b.setUsername(it) }
                o.optString("credential").takeIf { it.isNotEmpty() }?.let { b.setPassword(it) }
                list.add(b.createIceServer())
            }
            return PeerConnection.RTCConfiguration(list).apply {
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
        }

        private inner class TransportListener(val g: Int) : SendTransport.Listener, RecvTransport.Listener {
            override fun onConnect(transport: Transport, dtlsParameters: String) {
                val dir = if (transport === send) "send" else "recv"
                val x = r("ms:transport:connect", JSONObject().put("direction", dir).put("transportId", transport.id).put("dtlsParameters", JSONObject(dtlsParameters)))
                if (!x.optBoolean("ok")) throw RuntimeException("Mediasoup DTLS rejected")
            }
            override fun onProduce(transport: Transport, kind: String, rtpParameters: String, appData: String?): String {
                val x = r("ms:produce", JSONObject().put("transportId", transport.id).put("kind", kind)
                    .put("rtpParameters", JSONObject(rtpParameters)).put("appData", if (appData != null) JSONObject(appData) else JSONObject()))
                return x.optString("id").takeIf { x.optBoolean("ok") && it.isNotEmpty() } ?: throw RuntimeException("Mediasoup producer rejected")
            }
            override fun onProduceData(transport: Transport, sctpStreamParameters: String, label: String, protocol: String, appData: String?): String =
                throw UnsupportedOperationException()
            override fun onConnectionStateChange(transport: Transport, newState: String) {
                // complete a pending connect right here: connect()/rebuildNow() block the media thread while they
                // wait, so a waiter that only completed on that thread would sit until the timeout
                if (NativeMediaPolicy.isUp(newState) && g == gen && !closed) {
                    if (NativeMediaPolicy.transportsUp(send?.connectionState?.toState(), recv?.connectionState?.toState(), consumers.isNotEmpty()))
                        connectedWaiter.let { if (!it.isDone) it.complete(true) }
                }
                exec.execute { onTransportState(g, newState) }
            }
        }

        private fun onTransportState(g: Int, state: String) {
            if (closed || g != gen) return
            if (NativeMediaPolicy.isUp(state)) {
                if (NativeMediaPolicy.transportsUp(send?.connectionState?.toState(), recv?.connectionState?.toState(), consumers.isEmpty().not())) onConnected()
                return
            }
            if (NativeMediaPolicy.isLoss(state)) {
                // 'failed' never ends the call — it only makes recovery skip straight to a rebuild
                startRecovery(rebuildNow = state != "disconnected")
            }
        }

        private fun onConnected() {
            recovery?.cancel(false); recovery = null
            attempt = 0
            val first = !connected
            connected = true
            if (!connectedWaiter.isDone) connectedWaiter.complete(true)
            emitState(JSONObject().put("state", "connected"))
            if (first) Log.i(TAG, "connected callId=$callId tcpOnly=$tcpOnly")
        }

        private fun startRecovery(rebuildNow: Boolean) {
            if (closed || recovery != null) return
            if (!connected) return // still in the initial connect: the page's connect timeout / ladder owns this
            connected = false
            connectedWaiter = CompletableFuture()
            emitState(JSONObject().put("state", "disconnected").put("reconnecting", true).put("attempt", 0))
            if (rebuildNow) attempt = NativeMediaPolicy.ICE_RESTARTS_BEFORE_REBUILD
            scheduleRecovery()
        }

        /** page-driven rebuild: drop any queued attempt, fresh transports now, wait for them (bounded) */
        fun rebuildNow() {
            check()
            recovery?.cancel(false); recovery = null
            if (connected) {
                connected = false
                emitState(JSONObject().put("state", "disconnected").put("reconnecting", true).put("attempt", 0))
            }
            if (connectedWaiter.isDone) connectedWaiter = CompletableFuture()
            attempt = NativeMediaPolicy.ICE_RESTARTS_BEFORE_REBUILD + 1
            try {
                build(firstTime = false)
                awaitConnected(CONNECT_TIMEOUT_MS)
            } catch (e: Throwable) {
                // keep the persistent loop going: the session stays and keeps rebuilding with backoff
                if (!closed && !connected && recovery == null) scheduleRecovery()
                throw e
            }
        }

        private fun scheduleRecovery() {
            if (closed) return
            val delay = NativeMediaPolicy.backoffMs(attempt)
            recovery = exec.schedule({ recover() }, delay, TimeUnit.MILLISECONDS)
        }

        /** keeps going until connected or the call is closed: ICE restart ×2, then full transport rebuilds */
        private fun recover() {
            recovery = null
            if (closed || connected) return
            attempt++
            emitState(JSONObject().put("state", "disconnected").put("reconnecting", true).put("attempt", attempt))
            try {
                if (NativeMediaPolicy.recoveryStep(attempt) == NativeMediaPolicy.RecoveryStep.ICE_RESTART) restartIceAll("recovery")
                else { build(firstTime = false); }
            } catch (e: Throwable) {
                Log.w(TAG, "recovery attempt $attempt failed: ${e.message}")
            }
            if (!closed && !connected) {
                // re-arm: the next attempt only runs if this one hasn't produced 'connected' by then
                recovery = exec.schedule({ recover() }, NativeMediaPolicy.backoffMs(attempt), TimeUnit.MILLISECONDS)
            }
        }

        fun restartIceAll(why: String) {
            for ((dir, t) in listOf("send" to send, "recv" to recv)) {
                if (t == null || closed) continue
                try {
                    val x = r("ms:transport:restartIce", JSONObject().put("direction", dir).put("transportId", t.id))
                    if (x.optBoolean("ok") && x.has("iceParameters")) t.restartIce(x.get("iceParameters").toString())
                } catch (e: Throwable) { Log.w(TAG, "restartIce($why) $dir: ${e.message}") }
            }
        }

        fun syncConsumers() {
            val rv = recv ?: return
            val d = device ?: return
            if (closed) return
            val x = r("ms:consume", JSONObject().put("transportId", rv.id).put("rtpCapabilities", JSONObject(d.rtpCapabilities)))
            if (!x.optBoolean("ok")) throw RuntimeException("Mediasoup consumer setup failed")
            val list = x.optJSONArray("consumers") ?: JSONArray()
            for (i in 0 until list.length()) {
                val p = list.getJSONObject(i)
                val id = p.optString("id")
                // audio-only native mode: remote video stays off (the page shows the avatar)
                if (closed || consumers.containsKey(id) || p.optString("kind") != "audio") continue
                val c = rv.consume(object : Consumer.Listener { override fun onTransportClose(consumer: Consumer) {} },
                    id, p.optString("producerId"), "audio", p.optJSONObject("rtpParameters")?.toString(), p.optJSONObject("appData")?.toString())
                (c.track as? AudioTrack)?.setEnabled(true) // plays through the ADM (voice-communication stream)
                consumers[id] = c
                runCatching { r("ms:consumer:resume", JSONObject().put("consumerId", id)) }
                emitState(JSONObject().put("state", "remote-audio"))
            }
        }

        fun setMuted(m: Boolean) { muted = m; mic?.setEnabled(!m) }

        fun ladder(bps: Int): Boolean {
            val sender = producer?.rtpSender ?: return false
            val params = sender.parameters
            params.encodings.forEach { it.maxBitrateBps = bps }
            return sender.setParameters(params)
        }

        fun stats(): JSONArray = JSONArray().apply {
            runCatching { put(JSONArray(send?.stats ?: "[]")) }
            runCatching { put(JSONArray(recv?.stats ?: "[]")) }
        }

        private fun teardownTransports() {
            consumers.values.forEach { runCatching { it.close() } }
            consumers.clear()
            runCatching { producer?.close() }; producer = null
            runCatching { send?.close() }; send = null
            runCatching { recv?.close() }; recv = null
        }

        fun close() {
            if (closed) return
            closed = true
            recovery?.cancel(false); recovery = null
            teardownTransports()
            runCatching { device?.dispose() }; device = null
            runCatching { mic?.dispose() }; mic = null
            if (!connectedWaiter.isDone) connectedWaiter.completeExceptionally(IllegalStateException("native media closed"))
        }
    }
}

private fun PeerConnection.IceConnectionState.toState(): String = name.lowercase()
