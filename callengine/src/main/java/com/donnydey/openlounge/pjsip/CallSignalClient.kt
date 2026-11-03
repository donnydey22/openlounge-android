package com.donnydey.openlounge.pjsip
// Native call signaling over the existing TLS WebSocket (call-signal), same protocol as iOS/browser:
// call:new{to,kind,route} / call:accept / call:reject / call:leave / offer|answer|ice{sdp with a=crypto} / sfu:plain.
// No JS on this path; the app UI only gets onUiEvent callbacks.
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CallSignalClient(private val baseUrl: String, private val token: String) {
    var onMessage: ((JSONObject) -> Unit)? = null
    var onUiEvent: ((String, JSONObject) -> Unit)? = null
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val ack = AtomicInteger()

    fun connect() {
        val sep = if (baseUrl.contains('?')) '&' else '?'
        ws = http.newWebSocket(Request.Builder().url("$baseUrl${sep}token=$token").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                onMessage?.invoke(m)
                val t = m.optString("t"); if (t in UI_EVENTS) onUiEvent?.invoke(t, m)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onUiEvent?.invoke("signal:disconnected", JSONObject()) }
        })
    }
    fun close() { ws?.close(1000, null); ws = null }
    fun call(to: String, video: Boolean, route: String) = send(JSONObject().put("t", "call:new").put("to", to).put("kind", if (video) "video" else "audio").put("route", route))
    fun accept(callId: String) = send(JSONObject().put("t", "call:accept").put("callId", callId))
    fun reject(callId: String) = send(JSONObject().put("t", "call:reject").put("callId", callId))
    fun leave(callId: String) = send(JSONObject().put("t", "call:leave").put("callId", callId))
    fun sdp(type: String, callId: String, sdp: String) = send(JSONObject().put("t", type).put("callId", callId).put("sdp", sdp))
    fun sfuPlain(callId: String, sdp: String) = send(JSONObject().put("t", "sfu:plain").put("callId", callId).put("sdp", sdp))
    private fun send(m: JSONObject) = ws?.send(m.put("ack", ack.incrementAndGet()).toString()) ?: false

    companion object {
        val UI_EVENTS = setOf("call:incoming", "call:accepted", "call:ended", "call:busy")
        private val CRYPTO = Regex("""a=crypto:\d+ AES_CM_128_HMAC_SHA1_80 inline:([A-Za-z0-9+/=]{40})""")
        /** Baseline SDES key from SDP (keys only in a=crypto). */
        fun cryptoKey(sdp: String): String? = CRYPTO.find(sdp)?.groupValues?.get(1)
    }
}
