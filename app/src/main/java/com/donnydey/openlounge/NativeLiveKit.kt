package com.donnydey.openlounge

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.PeerConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * Emergency LiveKit path, still native (audio only). LiveKit ships its own prefixed WebRTC (livekit.org.webrtc), so
 * it coexists with the mediasoup stack. Audio mode/focus stay with NativeCallMedia/MainActivity (NoAudioHandler).
 * The SDK resumes/reconnects itself; if the room still drops, this keeps rejoining with backoff until the page closes
 * it — it never ends the call on its own.
 */
class NativeLiveKit(private val ctx: Context, val callId: String, private val emit: (JSONObject) -> Unit) {
    @Volatile var closed = false
    @Volatile var connected = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var room: Room? = null
    private var muted = false

    fun connectBlocking(url: String, token: String, ice: JSONArray, timeoutMs: Long) {
        require(url.startsWith("wss://") || url.startsWith("https://")) { "bad LiveKit url" }
        val servers = parseIce(ice)
        runBlocking { withTimeout(timeoutMs) { join(url, token, servers) } }
        connected = true
        emit(JSONObject().put("state", "connected"))
    }

    private suspend fun join(url: String, token: String, servers: List<PeerConnection.IceServer>) {
        val r = LiveKit.create(ctx, overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler())))
        room = r
        scope.launch {
            r.events.collect { e ->
                if (closed || room !== r) return@collect
                when (e) {
                    is RoomEvent.Reconnecting -> { connected = false; emit(JSONObject().put("state", "disconnected").put("reconnecting", true)) }
                    is RoomEvent.Reconnected -> { connected = true; emit(JSONObject().put("state", "connected")) }
                    is RoomEvent.TrackSubscribed -> emit(JSONObject().put("state", "remote-audio"))
                    is RoomEvent.Disconnected -> { connected = false; rejoin(url, token, servers) }
                    else -> {}
                }
            }
        }
        r.connect(url, token, ConnectOptions(audio = false, video = false, iceServers = servers.ifEmpty { null }))
        r.localParticipant.setMicrophoneEnabled(!muted)
    }

    private fun rejoin(url: String, token: String, servers: List<PeerConnection.IceServer>) {
        scope.launch {
            var attempt = 0
            while (!closed && !connected) {
                attempt++
                emit(JSONObject().put("state", "disconnected").put("reconnecting", true).put("attempt", attempt))
                delay(minOf(15_000L, 500L shl minOf(attempt - 1, 5)))
                if (closed) break
                try {
                    room?.let { runCatching { it.disconnect(); it.release() } }
                    join(url, token, servers)
                    connected = true
                    emit(JSONObject().put("state", "connected"))
                } catch (e: Throwable) { /* keep going until the page closes us or switches path */ }
            }
        }
    }

    fun setMuted(m: Boolean) {
        muted = m
        val r = room ?: return
        scope.launch { runCatching { r.localParticipant.setMicrophoneEnabled(!m) } }
    }

    fun close() {
        if (closed) return
        closed = true
        connected = false
        room?.let { runCatching { it.disconnect(); it.release() } }
        room = null
        scope.cancel()
    }

    private fun parseIce(a: JSONArray): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val urls = when (val u = o.opt("urls")) { is JSONArray -> (0 until u.length()).map { u.getString(it) }; is String -> listOf(u); else -> emptyList() }
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            o.optString("username").takeIf { it.isNotEmpty() }?.let { b.setUsername(it) }
            o.optString("credential").takeIf { it.isNotEmpty() }?.let { b.setPassword(it) }
            out.add(b.createIceServer())
        }
        return out
    }
}
