package com.donnydey.openlounge.olcall

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Open Lounge call log (Donny t5375): every call's six-state machine (idle, joining, offering, answering, connected,
 * failed) with timestamps, a failure tag on every failed transition, and the device/OS/app/media/role of this leg.
 *
 * Events go to an on-disk queue first ([OlCallLogQueue], app-private file, fsync'd), so they survive the app being
 * killed or the phone restarting, then [OlCallLogUploader] posts them to the OL backend (POST <origin>/api/call-log,
 * session bearer, or the call's own olcall join token). Server: server/calls/callLog.ts (Postgres call_events, 90 days).
 *
 * Never recorded: join tokens (the token rides in a local-only "_tok" field used for upload auth and is stripped
 * before sending), keys, signaling blobs, SDP, TURN usernames/passwords.
 */
object OlCallLogTags {
    /** Donny's six failure tags. */
    val FAILURE = listOf("offer_never_sent", "answer_timeout", "connection_stalled", "ice_failure", "audio_session_handoff_failure", "turn_relay_failure")
    /** Not engine failures; kept as their own tags. */
    val EXTRA = listOf("permission_denied", "engine_unavailable", "join_rejected", "cancelled")
    private val ALL = (FAILURE + EXTRA).toSet()

    /** Raw OlCallStates.diagnose()/close reason -> tag. Same table as server/calls/callLog.ts failureTag and iOS. */
    fun tag(raw: String?, turnServers: Int? = null, relayOnly: Boolean = false): String {
        val r = (raw ?: "").lowercase().trim()
        if (r in ALL) return r
        fun has(re: String) = Regex(re).containsMatchIn(r)
        if (has("audio[-_ ]?session|audio[-_ ]?open|adm[-_:]|avaudiosession|audio[-_ ]?unit|mic(rophone)?[-_ ]?(open|unavailable)|audio-focus")) return "audio_session_handoff_failure"
        if (has("permission")) return "permission_denied"
        if (has("olcall-unavailable|engine[-_ ]?unavailable|native-create|media_unavailable|olcall_unavailable")) return "engine_unavailable"
        if (has("cancel")) return "cancelled"
        if (has("no[-_ ]?turn|turn[-_ ]?(alloc|relay|cred|auth|fail|401|403|438)|relay[-_ ]?(fail|alloc)|allocation")) return "turn_relay_failure"
        if (has("unauthori[sz]ed|join[-_ ]?rejected|bad[-_ ]?token|server[-_ ]?full|slot taken")) return "join_rejected"
        if (r.startsWith("offer-never-sent") || r.startsWith("offer-never-arrived") || r.contains("offer_never_sent")) return "offer_never_sent"
        if (r.startsWith("answer-never-arrived") || r.startsWith("answer-never-sent") || has("answer[-_ ]?time|join-timeout|no[-_ ]?answer")) return "answer_timeout"
        if (has("ice") || has("tg-failed|dtls")) {
            if (turnServers == 0 || relayOnly) return "turn_relay_failure"
            return if (r.startsWith("ice-stalled:ws")) "connection_stalled" else "ice_failure"
        }
        return "connection_stalled"
    }
}

/** Append-only JSONL queue in app-private storage. One event per line; bounded; survives process death. */
class OlCallLogQueue(private val file: File, private val maxEvents: Int = 5000) {
    init { file.parentFile?.mkdirs() }

    @Synchronized fun append(events: List<JSONObject>) {
        if (events.isEmpty()) return
        runCatching {
            FileOutputStream(file, true).use { out ->
                out.write(events.joinToString("") { it.toString() + "\n" }.toByteArray())
                out.fd.sync()
            }
            if (file.length() > 4L * 1024 * 1024 || size() > maxEvents) trimTo(maxEvents)
        }
    }

    @Synchronized fun all(): List<JSONObject> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { l -> l.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } }
    }.getOrDefault(emptyList())

    @Synchronized fun size(): Int = all().size

    @Synchronized fun remove(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        rewrite(all().filter { it.optString("eventId") !in eventIds })
    }

    @Synchronized private fun trimTo(n: Int) { val a = all(); if (a.size > n) rewrite(a.takeLast(n)) }

    private fun rewrite(keep: List<JSONObject>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(keep.joinToString("") { it.toString() + "\n" }.toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

/**
 * Posts queued events. One single-thread executor; never blocks a call thread. Retries with backoff while offline;
 * an event is removed from the queue only after the server answered 200 for it (or 401 for a token that can never
 * authenticate again, so the queue cannot grow forever).
 */
class OlCallLogUploader(
    private val queue: OlCallLogQueue,
    /** e.g. https://openlounge.app/api/call-log */
    private val endpoint: () -> String,
    /** fresh app session access token or null */
    private val bearer: () -> String?,
    /** (url, bearer or null, json body) -> HTTP status (or -1 on network error). */
    private val transport: (String, String?, String) -> Int = OkHttpTransport,
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ol-calllog").apply { isDaemon = true } }
    @Volatile private var backoffS = 0L
    @Volatile private var scheduled = false

    fun kick() { exec.execute { flushNow() } }

    /** Upload everything queued; returns how many events were accepted. Runs on the caller's thread (tests) or [exec]. */
    fun flushNow(): Int {
        var accepted = 0
        var failed = false
        val events = queue.all()
        if (events.isEmpty()) return 0
        val b = runCatching { bearer() }.getOrNull()
        val groups: Map<String?, List<JSONObject>> = if (b != null) mapOf(null to events) else events.groupBy { it.optString("_tok").ifEmpty { null } }
        for ((tok, evs) in groups) {
            if (b == null && tok == null) continue // nothing to authenticate with yet; stays queued until signed in
            for (chunk in evs.chunked(200)) {
                val body = JSONObject().put("events", JSONArray(chunk.map { e -> JSONObject(e.toString()).apply { remove("_tok") } }))
                if (b == null && tok != null) body.put("olcallToken", tok)
                val code = runCatching { transport(endpoint(), b, body.toString()) }.getOrDefault(-1)
                when {
                    code == 200 -> { queue.remove(chunk.map { it.optString("eventId") }.toSet()); accepted += chunk.size }
                    code == 401 && b == null -> queue.remove(chunk.map { it.optString("eventId") }.toSet()) // token dead for good
                    code == 400 || code == 413 -> queue.remove(chunk.map { it.optString("eventId") }.toSet()) // malformed: never retry
                    else -> failed = true
                }
            }
        }
        if (failed) scheduleRetry() else backoffS = 0
        return accepted
    }

    private fun scheduleRetry() {
        if (scheduled) return
        backoffS = when (backoffS) { 0L -> 5L; 5L -> 30L; 30L -> 120L; else -> 600L }
        scheduled = true
        exec.schedule({ scheduled = false; flushNow() }, backoffS, TimeUnit.SECONDS)
    }

    companion object {
        private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }
        val OkHttpTransport: (String, String?, String) -> Int = { url, bearer, json ->
            val rb = okhttp3.Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
            if (bearer != null) rb.header("Authorization", "Bearer $bearer")
            http.newCall(rb.build()).execute().use { it.code }
        }
    }
}

/**
 * One leg's recorder. Built by the app for each call with what it knows (role/callId come from the olcall token's
 * payload, which is plain base64url JSON; only its signature is secret-backed), attached to the engine's OlCallStates.
 */
class OlCallLog(
    private val queue: OlCallLogQueue,
    private val uploader: OlCallLogUploader?,
    val meta: Meta,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class Meta(
        val callId: String, val role: String?, val media: String, val device: String, val os: String,
        val appVersion: String, val engine: String, val olcallToken: String?, val platform: String = "android",
    )

    @Volatile var turnServers: Int? = null
    @Volatile var path: String? = null
    @Volatile private var audioFailed = false

    private fun base(kind: String): JSONObject = JSONObject()
        .put("eventId", UUID.randomUUID().toString()).put("callId", meta.callId).put("source", "android")
        .put("platform", meta.platform).put("device", meta.device).put("os", meta.os).put("appVersion", meta.appVersion)
        .put("engine", meta.engine).put("media", meta.media).put("kind", kind).put("ts", nowMs())
        .apply { meta.role?.let { put("role", it) }; meta.olcallToken?.let { put("_tok", it) } }

    /** One OlCallStates transition. A failed transition gets its tag in "reason" and the raw diagnosis in "detail". */
    fun state(from: OlCallStates.State, to: OlCallStates.State, reason: String, tMs: Long): JSONObject {
        val e = base("state").put("from", from.name).put("to", to.name).put("t", tMs)
        if (to == OlCallStates.State.failed) {
            val tag = if (audioFailed && !reason.contains("permission")) "audio_session_handoff_failure" else OlCallLogTags.tag(reason, turnServers)
            e.put("reason", tag).put("detail", reason)
        } else e.put("reason", reason)
        e.put("extra", extra())
        queue.append(listOf(e))
        if (to == OlCallStates.State.failed || to == OlCallStates.State.connected) uploader?.kick()
        return e
    }

    /** Non-state event (audio session, relay list, end). */
    fun event(name: String, reason: String? = null, detail: String? = null, extra: Map<String, Any> = emptyMap()): JSONObject {
        if (name == "audio_session" && reason == "audio_session_handoff_failure") audioFailed = true
        val e = base("event").put("event", name)
        reason?.let { e.put("reason", it) }; detail?.let { e.put("detail", it.take(240)) }
        val x = extra(); for ((k, v) in extra) x.put(k, v); e.put("extra", x)
        queue.append(listOf(e))
        if (name == "ended") uploader?.kick()
        return e
    }

    /** A call that failed before the engine existed (permission denied, engine unavailable): idle -> failed. */
    fun failedBeforeStart(raw: String) {
        val e = base("state").put("from", "idle").put("to", "failed").put("t", 0).put("reason", OlCallLogTags.tag(raw)).put("detail", raw).put("extra", extra())
        queue.append(listOf(e)); uploader?.kick()
    }

    private fun extra() = JSONObject().apply { turnServers?.let { put("turnServers", it) }; path?.let { put("path", it) } }

    fun attach(states: OlCallStates) { states.onTransition = { f, t, r, ms -> state(f, t, r, ms) } }

    companion object {
        /** callId + role from an olcall token's payload (no verification needed: only labels this phone's own log). */
        fun claims(token: String?): Pair<String, String>? = runCatching {
            val p1 = token!!.substringBefore('.')
            val json = String(java.util.Base64.getUrlDecoder().decode(p1.padEnd((p1.length + 3) / 4 * 4, '=')))
            val o = JSONObject(json)
            o.getString("callId") to o.getString("role")
        }.getOrNull()
    }
}
