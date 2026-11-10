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
    private var ended = false
    private val wsSeq = AtomicLong()
    val traceId: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    /** idle/joining/offering/answering/connected/failed; one log line per transition (OlCallStates). */
    val states = OlCallStates { line ->
        Log.i("olcall", line)
        crumbs.event("state", line, if (" -> failed " in line) "error" else "info")
    }

    /** The tgcalls version this phone joins with: the newest one both this build and the server speak. */
    val version: String = OlVersions.pick(nativeVersions().toList())

    fun start(device: String, os: String) {
        crumbs.traceId = traceId
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
                crumbs.event("ws", "failure:${t.javaClass.simpleName}", "error")
                end("ws-failure")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = worker.execute { end("ws-closed:$code") }
        })
    }

    private fun send(o: JSONObject) {
        o.put("seq", wsSeq.incrementAndGet())
        ws?.send(o.toString())
    }

    private fun onFrame(f: JSONObject) {
        if (ended) return
        when (f.optString("t")) {
            "joined" -> {
                crumbs.callId = f.optString("callId"); crumbs.leg = f.optString("leg")
                states.callId = crumbs.callId
                val key = Base64.decode(f.getString("key"), Base64.NO_WRAP)
                val outgoing = f.getBoolean("isOutgoing")
                states.setState(if (outgoing) OlCallStates.State.offering else OlCallStates.State.answering, "joined")
                crumbs.event("tg", "joined:outgoing=$outgoing:version=$version")
                // Relay list: coturn STUN + TURN UDP/TCP with the short-lived REST login.
                // enableP2P stays on in the JNI, so direct host/srflx pairs are tried first
                // and the TURN relay is only the fallback. Credentials are never logged.
                val (rtc, source) = OlRtcServers.select(f.optJSONArray("iceServers"), appIceServers)
                crumbs.event("tg", "rtc-servers:source=$source:" + OlRtcServers.summary(rtc))
                handle = nativeCreate(version, key, outgoing, "{}", startWithVideo, remoteVideoSink, localVideoSink,
                    rtc.map { it.host }.toTypedArray(), rtc.map { it.port }.toIntArray(),
                    rtc.map { it.login }.toTypedArray(), rtc.map { it.password }.toTypedArray(),
                    rtc.map { (if (it.isTurn) 1 else 0) or (if (it.isTcp) 2 else 0) }.toIntArray())
                key.fill(0) // the JNI copied it; tgcalls owns the only copy from here on
                if (handle == 0L) end("native-create-failed")
            }
            "blob" -> {
                states.blobReceived()
                val b = Base64.decode(f.getString("b64"), Base64.NO_WRAP)
                crumbs.blob("recv", "tg.blob", "signaling", b)
                if (handle != 0L) nativeReceiveSignaling(handle, b)
            }
            "stats" -> f.optJSONObject("stats")?.let { listener.onStats(it) }
            "bye" -> end("bye:" + f.optString("reason"))
            "error" -> { crumbs.event("ws", "server-error:" + f.optString("error"), "error"); end("server-error") }
        }
    }

    fun setMuted(muted: Boolean) = worker.execute { if (handle != 0L) nativeSetMuted(handle, muted) }

    /** Camera on/off. The first "on" in an audio call adds a video channel (tgcalls renegotiates). */
    fun setVideoEnabled(enabled: Boolean) = worker.execute { if (handle != 0L) nativeSetVideoEnabled(handle, enabled, localVideoSink) }

    fun switchCamera(front: Boolean) = worker.execute { if (handle != 0L) nativeSwitchCamera(handle, front) }

    fun hangup() = worker.execute {
        if (ended) return@execute
        send(JSONObject().put("t", "hangup"))
        end("local-hangup")
    }

    private fun end(reason: String) {
        if (ended) return
        ended = true
        // Normal ends (local hangup, far side's bye) are not failures; everything else before Established is.
        val normal = reason == "local-hangup" || reason.startsWith("bye:")
        if (states.state != OlCallStates.State.connected && !normal) states.fail(reason)
        if (handle != 0L) { nativeStop(handle); handle = 0L }
        ws?.close(1000, null); ws = null
        crumbs.event("call", "ended:$reason")
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
        if (s == State.Established) states.setState(OlCallStates.State.connected, "tg-established")
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
