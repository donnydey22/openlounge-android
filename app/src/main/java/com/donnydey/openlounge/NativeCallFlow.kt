package com.donnydey.openlounge

import com.donnydey.openlounge.olcall.OlCallLifecycle
import com.donnydey.openlounge.olcall.OlCallLifecycle.Reason
import com.donnydey.openlounge.olcall.OlCallLifecycle.State

/**
 * BaoTime binding (Donny, Nov 19 2026): the native call screen is driven ONLY by the call server's seven-state
 * lifecycle (olcall/server/states.go, {t:"call:state"}). No page call:* message ever moves the screen; page messages
 * carry metadata only (name, photo, video). The server is the single writer; this maps its snapshot to a surface.
 *
 *   idle        -> no surface
 *   ringing     -> RINGING     "Ringing…"   (one Ringing state; the callee's ring surface is the incoming screen)
 *   answering   -> ANSWERING   "Connecting…"
 *   joining_url -> JOINING     "Joining…"   (link door)
 *   connecting  -> CONNECTING  "Connecting…" (fast pulse)
 *   in_room     -> ACTIVE      timer        (InRoom internally, the active layout on screen)
 *   terminal    -> FAILED (reason failed: Failed screen with Call again) or ENDED (reason text)
 */
object NativeCallFlow {
    enum class Screen { RINGING, ANSWERING, JOINING, CONNECTING, ACTIVE, ENDED, FAILED }

    /** BaoTime state colours, same values as web calls.module.css tint_*. */
    enum class Tone(val from: Int, val to: Int) {
        INITIATING(0xFF4338CA.toInt(), 0xFF7C3AED.toInt()),
        CONNECTED(0xFF0284C7.toInt(), 0xFF059669.toInt()),
        FAILED(0xFF7F1D1D.toInt(), 0xFFB91C1C.toInt()),
        ENDED(0xFF1E293B.toInt(), 0xFF334155.toInt()),
    }

    @JvmStatic fun screenFor(l: OlCallLifecycle): Screen? = when (l.state) {
        State.Idle -> null
        State.Ringing -> Screen.RINGING
        State.Answering -> Screen.ANSWERING
        State.JoiningUrl -> Screen.JOINING
        State.Connecting -> Screen.CONNECTING
        State.InRoom -> Screen.ACTIVE
        State.Terminal -> if (l.reason == Reason.Failed) Screen.FAILED else Screen.ENDED
    }

    @JvmStatic fun statusText(s: Screen?, reason: Reason? = null): String = when (s) {
        Screen.RINGING -> "Ringing…"
        Screen.ANSWERING, Screen.CONNECTING -> "Connecting…"
        Screen.JOINING -> "Joining…"
        Screen.FAILED -> "Call failed"
        Screen.ENDED -> endedText(reason)
        Screen.ACTIVE, null -> ""
    }

    @JvmStatic fun endedText(r: Reason?): String = when (r) {
        Reason.Declined -> "Declined"
        Reason.Missed -> "No answer"
        Reason.Expired -> "Link expired"
        Reason.CancelledByConfig -> "Call cancelled"
        Reason.Failed -> "Call failed"
        Reason.Disconnected, Reason.Completed, null -> "Call ended"
    }

    @JvmStatic fun tone(s: Screen?): Tone = when (s) {
        Screen.ACTIVE -> Tone.CONNECTED
        Screen.FAILED -> Tone.FAILED
        Screen.ENDED -> Tone.ENDED
        else -> Tone.INITIATING
    }

    /** Avatar rings: slow pulse while ringing, fast pulse while answering / joining / connecting, none otherwise. */
    @JvmStatic fun pulseMs(s: Screen?): Long = when (s) {
        Screen.RINGING -> 2400L
        Screen.ANSWERING, Screen.JOINING, Screen.CONNECTING -> 1100L
        else -> 0L
    }

    @JvmStatic fun timer(sec: Long): String = String.format("%d:%02d", sec / 60, sec % 60)
}
