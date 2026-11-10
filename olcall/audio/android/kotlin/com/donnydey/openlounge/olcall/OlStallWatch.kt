package com.donnydey.openlounge.olcall

/**
 * Frozen-video detector (t5450), same rules as the iPhone's OLVideoWatchdog. Pure logic, unit-tested.
 * Fed about once a second with the frames a sink saw since the last tick:
 *   watching + no frame for [firstAfterMs]  -> First  (remote: ask the server for a keyframe; local: log capture stall)
 *   still none at [secondAfterMs]           -> Second (local: restart the camera; at most [maxSecond] per call)
 *   frames again                            -> Recovered (with the stall length)
 * Not watching (camera off, far side's video off, not connected) is never a stall. Audio is never touched.
 */
class OlStallWatch(
    private val firstAfterMs: Long = 2_000,
    private val secondAfterMs: Long = 5_000,
    private val maxSecond: Int = 2,
) {
    sealed class Action {
        object None : Action()
        data class First(val stalledMs: Long) : Action()
        data class Second(val stalledMs: Long) : Action()
        data class Recovered(val stalledMs: Long) : Action()
    }

    private var lastFrameAt = -1L
    private var lastFirstAt = -1L
    private var lastSecondAt = -1L
    private var stalled = false
    var stalls = 0; private set
    var seconds = 0; private set

    fun tick(nowMs: Long, frames: Int, watching: Boolean): Action {
        if (!watching) { lastFrameAt = -1; stalled = false; return Action.None }
        if (frames > 0) {
            val since = lastFrameAt
            lastFrameAt = nowMs
            if (stalled && since >= 0) { stalled = false; return Action.Recovered(nowMs - since) }
            stalled = false
            return Action.None
        }
        if (lastFrameAt < 0) { lastFrameAt = nowMs; return Action.None }
        val gap = nowMs - lastFrameAt
        if (gap >= secondAfterMs && seconds < maxSecond && (lastSecondAt < 0 || nowMs - lastSecondAt >= secondAfterMs)) {
            if (!stalled) { stalled = true; stalls++ }
            seconds++; lastSecondAt = nowMs
            return Action.Second(gap)
        }
        if (gap >= firstAfterMs && (lastFirstAt < 0 || nowMs - lastFirstAt >= firstAfterMs)) {
            if (!stalled) { stalled = true; stalls++ }
            lastFirstAt = nowMs
            return Action.First(gap)
        }
        return Action.None
    }
}
