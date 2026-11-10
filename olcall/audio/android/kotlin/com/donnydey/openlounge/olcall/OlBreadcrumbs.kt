package com.donnydey.openlounge.olcall

import android.os.Build
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Breadcrumbs on every signaling send/receive callback, same schema as the
 * server (olcall/obs.Breadcrumb): callId, traceId, leg, seq, direction,
 * platform, device model, OS version, engine build, monotonic ns + UTC.
 *
 * Self-hosted only (owner addendum): batches are POSTed to the OL droplet's
 * /v1/crumbs (same origin as the call WebSocket). No Sentry SaaS. Payloads are
 * NEVER recorded: only length + SHA-256 prefix (digest()).
 *
 * Callbacks arrive on tgcalls/OkHttp threads; record() only enqueues on a
 * single-thread executor and returns immediately.
 */
class OlBreadcrumbs(
    private val crumbUrl: String, // e.g. https://calls.openlounge.example/v1/crumbs
    private val engineBuild: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ol-crumbs").apply { isDaemon = true } }
    private val pending = ArrayList<JSONObject>()
    private val sendSeq = AtomicLong()
    private val recvSeq = AtomicLong()
    private val localSeq = AtomicLong()
    @Volatile var callId: String = ""
    @Volatile var traceId: String = ""
    @Volatile var leg: String = ""

    /** Payload-carrying event: only len + sha256 prefix are kept. */
    fun blob(direction: String, category: String, kind: String, payload: ByteArray): Long =
        emit(direction, category, kind, payload.size, digest(payload), "info")

    fun event(category: String, message: String, level: String = "info"): Long =
        emit("local", category, message, 0, null, level)

    private fun emit(direction: String, category: String, message: String, len: Int, sha: String?, level: String): Long {
        val seq = when (direction) {
            "send" -> sendSeq.incrementAndGet()
            "recv" -> recvSeq.incrementAndGet()
            else -> localSeq.incrementAndGet()
        }
        val o = JSONObject()
            .put("ts", Instant.now().toString())
            .put("monoNs", SystemClock.elapsedRealtimeNanos())
            .put("source", "android")
            .put("callId", callId).put("traceId", traceId).put("leg", leg)
            .put("seq", seq).put("direction", direction)
            .put("category", category).put("message", message)
            .put("platform", "android")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("osVersion", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("engine", engineBuild).put("level", level)
        if (sha != null) o.put("len", len).put("sha256", sha)
        exec.execute {
            pending.add(o)
            if (pending.size >= 50 || level == "error") flushLocked()
        }
        return seq
    }

    fun flush() = exec.execute { flushLocked() }

    private fun flushLocked() {
        if (pending.isEmpty()) return
        val batch = JSONObject().put("crumbs", JSONArray(pending.toList()))
        val req = Request.Builder().url(crumbUrl)
            .post(batch.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            http.newCall(req).execute().use { if (it.isSuccessful) pending.clear() }
        } catch (_: Exception) {
            if (pending.size > 2000) pending.subList(0, pending.size - 2000).clear() // bounded
        }
    }

    companion object {
        const val DIGEST_PREFIX = 8
        fun digest(b: ByteArray): String {
            val h = MessageDigest.getInstance("SHA-256").digest(b)
            return h.joinToString("") { "%02x".format(it) }.substring(0, DIGEST_PREFIX)
        }
    }
}
