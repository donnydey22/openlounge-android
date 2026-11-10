package com.donnydey.openlounge.olcall

/**
 * The one place an olcall call changes state, and the one log line for it.
 *
 * States (same names as iOS): idle, joining, offering, answering, connected, failed.
 *   idle      -> joining    WebSocket opened, join {token,...} sent
 *   joining   -> offering   joined, isOutgoing = true  (this phone's tgcalls sends the first blobs)
 *   joining   -> answering  joined, isOutgoing = false (this phone waits for the far side's blobs)
 *   offering/answering -> connected   tgcalls reports Established
 *   any (not failed) -> failed        reason says WHERE signaling broke
 *
 * Log line (identical format to iOS):  olcall state <from> -> <to> reason=<r> t=<ms> call=<id>
 *   t = ms since the call object started; call = olcall callId from "joined" ("-" before it).
 *   Reasons are fixed strings or server/close codes only. Never tokens, keys, blobs or TURN credentials.
 *
 * Failure diagnosis (failed transition reason = "<where>:<cause>"):
 *   offer-never-sent      offering, this phone never emitted a tgcalls blob
 *   answer-never-arrived  offering, blobs sent but none ever came back from the far side
 *   answer-never-sent / offer-never-arrived   same two checks seen from the answering phone
 *   ice-stalled           blobs went both ways but tgcalls never reached Established
 *   join-never-acked      no "joined" before the failure (token/server/WebSocket problem)
 */
class OlCallStates(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sink: (String) -> Unit = { android.util.Log.i("olcall", it) },
) {
    enum class State { idle, joining, offering, answering, connected, failed }

    @Volatile var state: State = State.idle
        private set
    @Volatile var callId: String = ""
    var blobsSent: Int = 0
        private set
    var blobsReceived: Int = 0
        private set
    /** Every emitted line, in order (tests / diagnostics). */
    val lines: MutableList<String> = ArrayList()

    private val started = nowMs()

    companion object {
        fun line(from: State, to: State, reason: String, ms: Long, callId: String): String {
            val id = if (callId.isEmpty()) "-" else callId
            return "olcall state ${from.name} -> ${to.name} reason=$reason t=$ms call=$id"
        }
    }

    @Synchronized fun blobSent() { blobsSent += 1 }
    @Synchronized fun blobReceived() { blobsReceived += 1 }

    /** The only state setter. Same-state and post-failure transitions are ignored (failed is terminal). */
    @Synchronized fun setState(to: State, reason: String): String? {
        if (to == state || state == State.failed) return null
        val ms = nowMs() - started
        val l = line(from = state, to = to, reason = reason, ms = ms, callId = callId)
        state = to
        lines.add(l)
        sink(l)
        return l
    }

    /** Where signaling broke, for the failed transition. [cause] = close/server reason (e.g. ws-failure, tg-failed). */
    @Synchronized fun diagnose(cause: String): String {
        val at = when (state) {
            State.idle, State.joining -> "join-never-acked"
            State.offering -> when {
                blobsSent == 0 -> "offer-never-sent"
                blobsReceived == 0 -> "answer-never-arrived"
                else -> "ice-stalled"
            }
            State.answering -> when {
                blobsReceived == 0 -> "offer-never-arrived"
                blobsSent == 0 -> "answer-never-sent"
                else -> "ice-stalled"
            }
            State.connected, State.failed -> "after-connect"
        }
        return "$at:$cause"
    }

    /** failed with the diagnosed reason (no-op once already failed). */
    @Synchronized fun fail(cause: String): String? = setState(State.failed, diagnose(cause))
}
