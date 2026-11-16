package com.donnydey.openlounge.olcall

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.olwrtc.VideoSink
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Android client of the olcall Go/Pion server for the tgcalls engine (phones only).
 *
 *   OL WebSocket (wss://<droplet>/v1/call)  <->  this class  <->  JNI  <->  tgcalls::Instance (InstanceV2Impl)
 *
 * Signaling blobs are shuttled opaquely (base64 "blob" frames); the SERVER
 * terminates them (olcall/translator) and relays media to the other leg, which
 * may be another Android phone or an iPhone (each leg is independent, so the two
 * phones may even run different tgcalls versions). See docs/PROTOCOL.md.
 *
 * Threading: JNI callbacks (tgcalls StaticThreads) and OkHttp callbacks only
 * enqueue onto [worker]; no blocking work on engine threads. Listener callbacks
 * run on [worker]; post to the main thread before touching views.
 */
class OlTgcallsEngine(
    private val serverUrl: String,
    private val joinToken: String,
    private val crumbs: OlBreadcrumbs,
    private val listener: Listener,
    /** Start with the camera on (video call). Can be toggled later with [setVideoEnabled]. */
    private val startWithVideo: Boolean = false,
    /** org.olwrtc.VideoSink for the far side's video (e.g. SurfaceViewRenderer) or null. */
    private val remoteVideoSink: VideoSink? = null,
    /** org.olwrtc.VideoSink for the local camera preview or null. */
    private val localVideoSink: VideoSink? = null,
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build(),
    /**
     * Optional relay list from the OL web/JS layer (CallMedia.iceServers, same JSON
     * shape). Used only if the server's joined frame carries none; see [OlRtcServers].
     */
    private val appIceServers: JSONArray? = null,
    /** Durable call log (OlCallLog): every state transition + engine events, uploaded to the OL backend. */
    private val callLog: OlCallLog? = null,
) {
    interface Listener {
        fun onState(state: State)
        /** Verbatim ADM open report (JSON); contains native statuses per attempt. */
        fun onAudioOpenReport(json: String)
        /** Far side's media state as tgcalls reports it (relayed by the server). */
        fun onRemoteMedia(audioActive: Boolean, video: RemoteVideo)
        fun onStats(json: JSONObject)
        fun onEnded(reason: String)
    }

    /** tgcalls::State ordinal order (Instance.h). */
    enum class State { WaitInit, WaitInitAck, Established, Failed, Reconnecting }

    /** tgcalls::VideoState ordinal order (VideoCaptureInterface.h). */
    enum class RemoteVideo { Inactive, Paused, Active }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ol-tgcalls").apply { isDaemon = true } }
    private var ws: WebSocket? = null
    private var handle = 0L
    /** Read on JNI/OkHttp threads too (after-end gating below), written only on [worker]. */
    @Volatile private var ended = false
    private val wsSeq = AtomicLong()
    val traceId: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    /** Logging only (t5614u): current network type, set by OlCallSession's ConnectivityManager callback. */
    @Volatile var netType: String = "unknown"
    @Volatile private var connectedAtMs = 0L
    private var resumeKey: String? = null
    private var resumeCallId = ""
    private var resumeLeg = ""
    private var resuming = false
    private var resumeAttempts = 0
    private var resumeStartedAtMs = 0L
    private var resumeSocket: WebSocket? = null
    private val queuedWhileResuming = mutableListOf<JSONObject>()
    private var needsMediaReset = false
    private var resetInFlight = false
    private var resetCount = 0
    private var lastResetAtMs = 0L
    private var resumedAtMs = 0L
    private var flowing = false
    private var localMuted = false
    private var localVideoOn = startWithVideo
    private var joinDevice = ""
    private var joinOs = ""
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ol-tgcalls-sched").apply { isDaemon = true } }
    private var resumeRetry: ScheduledFuture<*>? = null
    private var resumeWatch: ScheduledFuture<*>? = null
    private var silenceWatch: ScheduledFuture<*>? = null
    private var resetAckWatch: ScheduledFuture<*>? = null
    private var pathSettle: ScheduledFuture<*>? = null
    private var reconnectWatch: ScheduledFuture<*>? = null
    private var resetRetry: ScheduledFuture<*>? = null
    @Volatile private var lastNativeState: State? = null
    private var pathFrom: String? = null

    /** idle/joining/offering/answering/connected/failed; one log line per transition (OlCallStates). */
    val states = OlCallStates { line ->
        Log.i("olcall", line)
        crumbs.event("state", line, if (" -> failed " in line) "error" else "info")
    }

    init { callLog?.attach(states) }

    /** The tgcalls version this phone joins with: the newest one both this build and the server speak. */
    val version: String = OlVersions.pick(nativeVersions().toList())

    fun start(device: String, os: String) {
        crumbs.traceId = traceId
        joinDevice = device
        joinOs = os
        val req = Request.Builder().url(serverUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(JSONObject().put("t", "join").put("token", joinToken).put("traceId", traceId)
                    .put("surface", "tgcalls").put("platform", "android").put("device", device).put("os", os)
                    .put("engine", ENGINE).put("version", version))
                states.setState(OlCallStates.State.joining, "join-sent")
                crumbs.event("ws", "open")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = worker.execute { onFrame(JSONObject(text)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = worker.execute {
                // After our own end (local hangup / bye) the server drops the socket without a close frame, which
                // OkHttp reports as EOFException: that is the teardown, not a call failure (1372bded, Nov 12).
                if (ended) { crumbs.event("ws", "closed-after-end:${t.javaClass.simpleName}"); return@execute }
                crumbs.event("ws", "failure:${t.javaClass.simpleName}", "error")
                onSocketLost(webSocket, "failure", t.javaClass.simpleName, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = worker.execute {
                if (ended) return@execute
                onSocketLost(webSocket, "closed", reason.take(60), code)
            }
        })
    }

    /** t5450: why hangup() ran (page close, page call:ended, replaced, ...); logged on the "ended" event. */
    @Volatile private var hangupCause: String? = null

    /** t5450: the far side's video has rendered nothing for a while: the server PLIs the other leg's sender. */
    fun requestKeyframe(reason: String) = worker.execute {
        if (!ended) send(JSONObject().put("t", "keyframe").put("reason", reason))
    }


    private fun createNative(f: JSONObject, initial: Boolean) {
        val key = Base64.decode(f.getString("key"), Base64.NO_WRAP)
        val outgoing = f.getBoolean("isOutgoing")
        val (rtc, source) = OlRtcServers.select(f.optJSONArray("iceServers"), appIceServers)
        crumbs.event("tg", "rtc-servers:source=$source:" + OlRtcServers.summary(rtc))
        callLog?.let { l -> l.turnServers = rtc.count { it.isTurn }
            runCatching { l.event("rtc_servers", detail = "source=$source", extra = mapOf("turnServers" to rtc.count { it.isTurn }, "stunServers" to rtc.count { !it.isTurn })) } }
        val video = if (initial) startWithVideo else localVideoOn
        if (handle != 0L && !initial) { nativeStop(handle); handle = 0L }
        handle = nativeCreate(version, key, outgoing, "{}", video, remoteVideoSink, localVideoSink,
            rtc.map { it.host }.toTypedArray(), rtc.map { it.port }.toIntArray(),
            rtc.map { it.login }.toTypedArray(), rtc.map { it.password }.toTypedArray(),
            rtc.map { (if (it.isTurn) 1 else 0) or (if (it.isTcp) 2 else 0) }.toIntArray())
        key.fill(0)
        if (handle == 0L) {
            resetInFlight = false
            end("native-create-failed")
            return
        }
        if (localMuted) nativeSetMuted(handle, true)
        if (!initial) {
            if (localVideoOn) nativeSetVideoEnabled(handle, true, localVideoSink)
            resetAckWatch?.cancel(false)
            crumbs.event("tg", "media-reset:engine-started")
        }
    }

    private fun onSocketLost(socket: WebSocket, how: String, why: String, code: Int?) {
        if (ended) return
        // A stale socket from a handover or from starting a resume attempt.
        if (resuming && socket !== resumeSocket && socket !== ws) return
        if (socket === resumeSocket) resumeSocket = null
        if (socket === ws) ws = null
        logWsLost(how, why, code)
        val connected = states.state == OlCallStates.State.connected
        if (OlSignalingResume.shouldResume(connected, resumeKey != null, ended)) {
            if (!resuming) beginResume()
            else scheduleResumeAttempt()
        } else if (!resuming) {
            end(if (how == "closed") "ws-closed:${code ?: 0}" else "ws-failure")
        }
    }

    private fun beginResume() {
        if (ended || resumeKey == null) return
        if (!resuming) {
            resuming = true
            resumeAttempts = 0
            resumeStartedAtMs = android.os.SystemClock.elapsedRealtime()
            crumbs.event("ws", "resume:start", "warning")
            runCatching { callLog?.event("ws_resume", "start", extra = mapOf("netType" to netType)) }
        }
        scheduleResumeAttempt()
    }

    private fun scheduleResumeAttempt() {
        if (ended || !resuming) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - resumeStartedAtMs
        val delay = OlSignalingResume.delayMs(resumeAttempts)
        if (elapsed + delay >= OlSignalingResume.WINDOW_MS) {
            crumbs.event("ws", "resume:gave-up:attempts=$resumeAttempts", "error")
            runCatching { callLog?.event("ws_resume", "gave_up", "attempts=$resumeAttempts",
                mapOf("outcome" to "failed", "gapMs" to elapsed, "netType" to netType)) }
            resuming = false
            end("ws-failure")
            return
        }
        resumeRetry?.cancel(false)
        resumeRetry = scheduler.schedule({ worker.execute { resumeAttempt() } }, delay, TimeUnit.MILLISECONDS)
    }

    private fun resumeAttempt() {
        if (ended || !resuming || resumeKey == null) return
        resumeAttempts++
        val key = resumeKey ?: return
        val frame = JSONObject().put("t", "resume").put("callId", resumeCallId).put("leg", resumeLeg)
            .put("resume", key).put("traceId", traceId)
        val req = Request.Builder().url(serverUrl).build()
        val sock = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                worker.execute {
                    frame.put("seq", wsSeq.incrementAndGet())
                    webSocket.send(frame.toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = worker.execute {
                if (webSocket === resumeSocket || ws == null) {
                    // First successful resumed frame takes over the socket.
                    if (JSONObject(text).optString("t") == "resumed") {
                        ws = webSocket
                        resumeSocket = null
                    }
                    onFrame(JSONObject(text))
                } else onFrame(JSONObject(text))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = worker.execute {
                onSocketLost(webSocket, "failure", t.javaClass.simpleName, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = worker.execute {
                if (!ended) onSocketLost(webSocket, "closed", reason.take(60), code)
            }
        })
        resumeSocket = sock
        resumeWatch?.cancel(false)
        resumeWatch = scheduler.schedule({
            worker.execute {
                if (!ended && resuming && ws == null) {
                    sock.cancel()
                    resumeSocket = null
                    scheduleResumeAttempt()
                }
            }
        }, OlSignalingResume.ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun onSignalingResumed() {
        flowing = false
        resumedAtMs = android.os.SystemClock.elapsedRealtime()
        if (needsMediaReset && states.state == OlCallStates.State.connected && ws != null) {
            beginMediaReset("after-resume")
        } else {
            armSilenceAfterResume()
        }
    }

    private fun armSilenceAfterResume() {
        silenceWatch?.cancel(false)
        silenceWatch = scheduler.schedule({
            worker.execute {
                val now = android.os.SystemClock.elapsedRealtime()
                if (OlMediaReset.shouldResetOnSilence(resumedAtMs, flowing, states.state == OlCallStates.State.connected,
                        resetInFlight, ended, resetCount, lastResetAtMs, now)) {
                    beginMediaReset("silence-after-resume")
                }
            }
        }, OlMediaReset.SILENCE_AFTER_RESUME_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Session: the default network changed mid-call (wifi <-> cellular). Nov 15 night (68fd0a81, 8ecc19a2): the
     * Pixel's default moved to cellular while Wi-Fi stayed up; Android lingered Wi-Fi 30 s and then killed every
     * socket on it (SocketException exactly 30 s after net change, both calls). So we do not wait for that: after
     * the flip settles we move signaling to the new default network (resume on a fresh socket) and restart the
     * engine there, while the old path still carries media.
     */
    fun onNetworkPathChange(from: String, to: String) = worker.execute {
        if (ended) return@execute
        if (pathFrom == null) pathFrom = from
        pathSettle?.cancel(false)
        pathSettle = scheduler.schedule({ worker.execute { settlePathChange(to) } },
            OlMediaReset.PATH_SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun settlePathChange(to: String) {
        val from = pathFrom ?: return
        pathFrom = null
        if (ended || !OlMediaReset.pathChange(from, to)) return
        val connected = states.state == OlCallStates.State.connected
        if (!connected) return
        crumbs.event("net", "handover:$from->$to", "warning")
        needsMediaReset = true
        if (resumeKey != null && !resuming) {
            val old = ws
            ws = null
            beginResume() // resume socket opens on the new default network; onSignalingResumed restarts media
            old?.cancel()
        } else if (ws != null && !resuming) {
            beginMediaReset("path:$from->$to")
        }
    }

    fun markMediaFlowing() = worker.execute {
        if (ended || flowing) return@execute
        flowing = true
        silenceWatch?.cancel(false)
        resumedAtMs = 0L
    }

    private fun beginMediaReset(reason: String) {
        if (ended || resetInFlight || ws == null || resuming) {
            needsMediaReset = true
            return
        }
        resetInFlight = true
        needsMediaReset = false
        resetCount++
        lastResetAtMs = android.os.SystemClock.elapsedRealtime()
        flowing = false
        resumedAtMs = 0L
        silenceWatch?.cancel(false)
        crumbs.event("tg", "media-reset:$reason", "warning")
        runCatching { callLog?.event("media_reset", reason, extra = mapOf("count" to resetCount, "netType" to netType)) }
        if (handle != 0L) { nativeStop(handle); handle = 0L }
        send(JSONObject().put("t", "reset-leg"))
        resetAckWatch?.cancel(false)
        resetAckWatch = scheduler.schedule({
            worker.execute {
                if (resetInFlight && !ended) {
                    resetInFlight = false
                    crumbs.event("tg", "media-reset:timeout", "error")
                    if (handle == 0L) scheduleResetRetry("retry-timeout")
                }
            }
        }, 5_000, TimeUnit.MILLISECONDS)
    }

    private var resetRetries = 0
    private fun scheduleResetRetry(reason: String) {
        if (ended || resetRetries >= 20) return
        resetRetries++
        resetRetry?.cancel(false)
        resetRetry = scheduler.schedule({ worker.execute {
            if (!ended && !resetInFlight && handle == 0L) beginMediaReset(reason)
        } }, 2_500, TimeUnit.MILLISECONDS)
    }

    /** tgcalls reported Reconnecting mid-call: if it is still there after RECONNECTING_RESET_MS, restart the engine. */
    private fun armReconnectWatch() {
        reconnectWatch?.cancel(false)
        reconnectWatch = scheduler.schedule({ worker.execute {
            val now = android.os.SystemClock.elapsedRealtime()
            if (ended || lastNativeState != State.Reconnecting) return@execute
            if (OlMediaReset.allowed(states.state == OlCallStates.State.connected, resetInFlight, ended, resetCount, lastResetAtMs, now)) {
                beginMediaReset("tg-reconnecting")
            } else if (!ended && states.state == OlCallStates.State.connected) armReconnectWatch()
        } }, OlMediaReset.RECONNECTING_RESET_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Logging only historically; now also used when we do resume. Records the network type and
     * how long we had been up when the signaling socket went away.
     */
    private fun logWsLost(how: String, why: String, code: Int?) {
        val up = if (connectedAtMs > 0) android.os.SystemClock.elapsedRealtime() - connectedAtMs else -1L
        val willResume = OlSignalingResume.shouldResume(states.state == OlCallStates.State.connected, resumeKey != null, ended)
        runCatching { callLog?.event("ws_closed", how, why, buildMap {
            put("netType", netType); put("connected", states.state == OlCallStates.State.connected)
            put("msSinceConnected", up); put("resume", if (willResume) "starting" else "unsupported"); code?.let { put("code", it) } }) }
    }

    /** App-layer facts (network type changes, fps) into this call's log. */
    fun logEvent(name: String, reason: String? = null, extra: Map<String, Any> = emptyMap()) {
        runCatching { callLog?.event(name, reason, null, extra + mapOf("netType" to netType)) }
    }

    private fun send(o: JSONObject) {
        o.put("seq", wsSeq.incrementAndGet())
        if (ws == null && resuming) {
            val t = o.optString("t")
            if (t == "blob" || t == "keyframe") queuedWhileResuming.add(o)
            return
        }
        ws?.send(o.toString())
    }

    private fun onFrame(f: JSONObject) {
        if (ended) return
        when (f.optString("t")) {
            "joined" -> {
                crumbs.callId = f.optString("callId"); crumbs.leg = f.optString("leg")
                states.callId = crumbs.callId
                resumeKey = f.optString("resume").ifEmpty { null }
                resumeCallId = f.optString("callId")
                resumeLeg = f.optString("leg")
                val outgoing = f.getBoolean("isOutgoing")
                states.setState(if (outgoing) OlCallStates.State.offering else OlCallStates.State.answering, "joined")
                crumbs.event("tg", "joined:outgoing=$outgoing:version=$version")
                createNative(f, initial = true)
            }
            "resumed" -> {
                resumeWatch?.cancel(false)
                resuming = false
                val gap = android.os.SystemClock.elapsedRealtime() - resumeStartedAtMs
                crumbs.event("ws", "resumed:attempts=$resumeAttempts:gapMs=$gap")
                runCatching { callLog?.event("ws_resume", "resumed", "attempts=$resumeAttempts",
                    mapOf("outcome" to "resumed", "gapMs" to gap, "netType" to netType)) }
                val q = queuedWhileResuming.toList(); queuedWhileResuming.clear()
                q.forEach { send(it) }
                onSignalingResumed()
            }
            "leg-reset" -> { resetRetry?.cancel(false); createNative(f, initial = false) }
            // Server: this leg's ICE failed (no packets); it keeps the call open for a reset-leg (60, Nov 15).
            "media-lost" -> {
                crumbs.event("tg", "server-media-lost:" + f.optString("reason"), "warning")
                if (!resetInFlight) beginMediaReset("server-media-lost") else needsMediaReset = true
            }
            "blob" -> {
                states.blobReceived()
                val b = Base64.decode(f.getString("b64"), Base64.NO_WRAP)
                crumbs.blob("recv", "tg.blob", "signaling", b)
                if (handle != 0L) nativeReceiveSignaling(handle, b)
            }
            "stats" -> f.optJSONObject("stats")?.let { listener.onStats(it) }
            "bye" -> end("bye:" + f.optString("reason"))
            "error" -> {
                val e = f.optString("error")
                if (e == "reset-limited" || e == "reset-unsupported" || e == "translator") {
                    crumbs.event("tg", "media-reset:$e", "warning")
                    resetAckWatch?.cancel(false)
                    resetInFlight = false
                    // The engine was already stopped for the reset: never sit with no engine. Retry shortly.
                    if (e != "reset-unsupported" && handle == 0L) scheduleResetRetry("retry-$e")
                } else if (e == "unauthorized" && resuming) {
                    crumbs.event("ws", "resume:refused", "error")
                    resuming = false
                    end("ws-failure")
                } else {
                    crumbs.event("ws", "server-error:$e", "error")
                    runCatching { callLog?.event("server_error", OlCallLogTags.tag("server-error:$e"), e.take(60)) }
                    end("server-error")
                }
            }
        }
    }

    fun setMuted(muted: Boolean) = worker.execute { localMuted = muted; if (handle != 0L) nativeSetMuted(handle, muted) }

    /** Camera on/off. The first "on" in an audio call adds a video channel (tgcalls renegotiates). */
    fun setVideoEnabled(enabled: Boolean) = worker.execute { localVideoOn = enabled; if (handle != 0L) nativeSetVideoEnabled(handle, enabled, localVideoSink) }

    fun switchCamera(front: Boolean) = worker.execute { if (handle != 0L) nativeSwitchCamera(handle, front) }

    /** [cause]: which app path asked for the hangup (t5450: every automatic end gets its own reason). */
    fun hangup(cause: String = "app") = worker.execute {
        if (ended) return@execute
        hangupCause = cause
        crumbs.event("call", "hangup-cause:$cause")
        send(JSONObject().put("t", "hangup"))
        end("local-hangup")
    }

    private fun end(reason: String) {
        if (ended) return
        ended = true
        resuming = false
        resetInFlight = false
        resumeRetry?.cancel(false)
        resumeWatch?.cancel(false)
        silenceWatch?.cancel(false)
        resetAckWatch?.cancel(false)
        pathSettle?.cancel(false); reconnectWatch?.cancel(false); resetRetry?.cancel(false)
        queuedWhileResuming.clear()
        // Normal ends (local hangup, far side's bye) are not failures; everything else before Established is.
        val normal = reason == "local-hangup" || reason.startsWith("bye:")
        if (states.state != OlCallStates.State.connected && !normal) states.fail(reason)
        if (handle != 0L) { nativeStop(handle); handle = 0L }
        ws?.close(1000, null); ws = null
        crumbs.event("call", "ended:$reason")
        runCatching { callLog?.event("ended", if (normal) "normal" else if (states.state == OlCallStates.State.connected) OlCallLogTags.tag("after-connect:$reason") else null,
            reason, mapOf("connected" to (states.state == OlCallStates.State.connected), "blobsSent" to states.blobsSent, "blobsReceived" to states.blobsReceived,
                "cause" to (hangupCause ?: reason))) }
        crumbs.flush()
        listener.onEnded(reason)
    }

    // ---- called from JNI (tgcalls threads): enqueue only ----
    @Suppress("unused")
    private fun onNativeSignalingData(data: ByteArray) {
        states.blobSent()
        crumbs.blob("send", "tg.blob", "signaling", data)
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        worker.execute { if (!ended) send(JSONObject().put("t", "blob").put("b64", b64)) }
    }

    @Suppress("unused")
    private fun onNativeState(state: Int) {
        val s = State.values().getOrElse(state) { State.Failed }
        // nativeStop() in end() tears the transport down and tgcalls reports Reconnecting on the way out
        // (72a8f1e3, Nov 12: "Reconnecting" at the same ms as ended:bye:leg-closed). The call is already over:
        // keep it as a teardown note, never as a live state for the call log or the call screen.
        if (ended) { crumbs.event("tg.state", "after-end:${s.name}"); return }
        if (resetInFlight && s == State.Failed) { crumbs.event("tg.state", "during-reset:${s.name}"); return }
        if (s == State.Established) { states.setState(OlCallStates.State.connected, "tg-established")
            if (connectedAtMs == 0L) connectedAtMs = android.os.SystemClock.elapsedRealtime()
            resetInFlight = false }
        lastNativeState = s
        val midCall = states.state == OlCallStates.State.connected
        if (s == State.Established) worker.execute { reconnectWatch?.cancel(false); resetRetries = 0 }
        if (midCall && s == State.Reconnecting) worker.execute { if (!resetInFlight) armReconnectWatch() }
        if (midCall && s == State.Failed) {
            // Mid-call engine failure: restart it (new translator) instead of failing the call (60).
            crumbs.event("tg.state", "Failed:restarting", "error")
            worker.execute { if (!ended) { if (handle != 0L && !resetInFlight) beginMediaReset("tg-failed") else needsMediaReset = true } }
            return
        }
        if (s == State.Failed) states.fail("tg-failed")
        crumbs.event("tg.state", s.name, if (s == State.Failed) "error" else "info")
        worker.execute { listener.onState(s) }
    }

    @Suppress("unused")
    private fun onNativeRemoteMedia(audioState: Int, videoState: Int) {
        // tgcalls::AudioState { Muted, Active }; tgcalls::VideoState { Inactive, Paused, Active }
        val v = RemoteVideo.values().getOrElse(videoState) { RemoteVideo.Inactive }
        worker.execute { listener.onRemoteMedia(audioState == 1, v) }
    }

    @Suppress("unused")
    private fun onNativeAudioOpenReport(json: String) {
        val ok = JSONObject(json).optInt("ok") == 1
        crumbs.event("adm", "open-report:" + json, if (ok) "info" else "error") // report holds codes/rates only
        if (!ok) runCatching { callLog?.event("audio_session", "audio_session_handoff_failure", "adm-open-report:" + json) }
        worker.execute { listener.onAudioOpenReport(json) }
    }

    private external fun nativeCreate(
        version: String, key: ByteArray, isOutgoing: Boolean, customParametersJson: String,
        video: Boolean, remoteSink: VideoSink?, localSink: VideoSink?,
        // Descriptor.rtcServers, parallel arrays; flags bit0 = TURN, bit1 = TCP
        rtcHosts: Array<String>, rtcPorts: IntArray, rtcLogins: Array<String>, rtcPasswords: Array<String>, rtcFlags: IntArray,
    ): Long
    private external fun nativeReceiveSignaling(handle: Long, data: ByteArray)
    private external fun nativeSetMuted(handle: Long, muted: Boolean)
    private external fun nativeSetVideoEnabled(handle: Long, enabled: Boolean, localSink: VideoSink?)
    private external fun nativeSwitchCamera(handle: Long, front: Boolean)
    private external fun nativeStop(handle: Long)

    companion object {
        const val ENGINE = "libtgcalls-android/olcall"

        @JvmStatic private external fun nativeVersions(): Array<String>

        init { System.loadLibrary("olcall_tgcalls") } // tgcalls + WebRTC + libolaudio_adm.a + ol_tgcalls_jni.cpp
    }
}
