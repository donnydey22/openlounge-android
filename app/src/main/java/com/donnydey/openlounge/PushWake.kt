package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ol-call-wake: FCM call_incoming only WAKES the app (Telegram PushListenerController + VoIPPreNotificationService).
 * 1. keep the process up briefly (PushWakeService, phoneCall FGS), 2. reconnect the server's raw WebSocket with the
 * existing stored login, 3. send "received" (call:ack) for the call id, 4. ring only once the server confirms it is
 * still ringing — or anyway if no confirmation came within [FALLBACK_MS]. The socket closes as soon as that is done
 * (or after [HOLD_MS]); it never answers, declines or joins media — the page/olcall paths own those.
 */
object PushWake {
    private const val TAG = "OLCalls"
    const val FALLBACK_MS = 2_000L
    const val HOLD_MS = 10_000L

    private val seen = PushWakePolicy.SeenCalls()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private fun mark(ev: String, detail: String) {
        Log.i(TAG, "push-wake $ev $detail")
        try { CallDiskLog.mark(ev, detail) } catch (_: Throwable) { }
    }

    /** FCM worker thread. Blocks at most [FALLBACK_MS] (inside FCM's onMessageReceived budget). */
    @JvmStatic fun onPush(ctx: Context, d: Map<String, String>) {
        val app = ctx.applicationContext
        val receivedAt = System.currentTimeMillis()
        val callId = d["callId"] ?: return
        if (!callId.matches(Regex(CallApi.CALL_ID_RE))) return
        if (!seen.first(callId, receivedAt)) { mark("wake-dup-push", "callId=$callId"); return }
        mark("wake-push", "callId=$callId sentAt=${d["sentAt"] ?: "-"}")
        PushWakeService.start(app, callId)
        val token = CallApi.freshAccessToken(app)
        val s = Session(app, callId, d, receivedAt)
        sessions[callId] = s
        if (token == null) {
            s.decide(PushWakePolicy.Verdict.NO_SOCKET, "no_session")
            return
        }
        val ws = CallApi.origin(app).replace(Regex("^http"), "ws") + "/ws"
        s.open(ws, token)
        if (!s.latch.await(FALLBACK_MS, TimeUnit.MILLISECONDS)) {
            mark("wake-fallback-ring", "callId=$callId after=${FALLBACK_MS}ms")
            s.ring("fallback") // keep the socket so the late ack still flips the caller to "Ringing…"
        }
        main.postDelayed({ s.close("hold") }, HOLD_MS)
    }

    /** Ring ended here (accept / decline / call_cancel): stop any wake for this call; never ring it later. */
    @JvmStatic fun release(callId: String?) {
        if (callId == null) return
        sessions[callId]?.let { it.dropped = true; it.close("released") }
    }

    private class Session(val ctx: Context, val callId: String, val d: Map<String, String>, val receivedAt: Long) {
        val latch = CountDownLatch(1)
        @Volatile var dropped = false
        @Volatile private var rang = false
        @Volatile private var closed = false
        @Volatile private var ws: WebSocket? = null
        private var openedAt = 0L

        fun open(url: String, token: String) {
            openedAt = System.currentTimeMillis()
            ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    mark("wake-socket-open", "callId=$callId ms=${System.currentTimeMillis() - openedAt}")
                    webSocket.send(PushWakePolicy.resumeFrame(token))
                    webSocket.send(PushWakePolicy.ackFrame(callId)) // processed in order after the resume
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val v = PushWakePolicy.onFrame(text, callId) ?: return
                    decide(v, "server")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closed) return
                    mark("wake-socket-fail", "callId=$callId ${t.javaClass.simpleName}")
                    decide(PushWakePolicy.Verdict.NO_SOCKET, "socket_failed")
                }
            })
        }

        @Synchronized fun decide(v: PushWakePolicy.Verdict, why: String) {
            mark("wake-verdict", "callId=$callId verdict=$v why=$why ms=${System.currentTimeMillis() - receivedAt}")
            when (v) {
                PushWakePolicy.Verdict.RING -> { ring("confirmed"); close("confirmed") }
                PushWakePolicy.Verdict.NO_SOCKET -> { ring(why); close(why) }
                PushWakePolicy.Verdict.DROP -> {
                    dropped = true
                    if (rang) CallNotifications.cancel(ctx, callId) // a fallback ring for a call that is over
                    close("dropped")
                }
            }
            latch.countDown()
        }

        @Synchronized fun ring(why: String) {
            if (rang || dropped) return
            rang = true
            val shown = CallNotifications.onIncoming(ctx, d, receivedAt)
            mark("wake-ring", "callId=$callId why=$why shown=$shown ms=${System.currentTimeMillis() - receivedAt}")
        }

        fun close(why: String) {
            if (closed) return
            closed = true
            latch.countDown()
            sessions.remove(callId, this)
            ws?.close(1000, "wake done")
            PushWakeService.release(ctx, callId)
            mark("wake-close", "callId=$callId why=$why")
        }
    }
}
