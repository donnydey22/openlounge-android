package com.donnydey.openlounge.olcall

/** When and how fast to re-open the olcall signaling socket after it drops mid-call. Mirrors iOS OLSignalingResume. */
object OlSignalingResume {
    const val WINDOW_MS = 36_000L // server grace is 40 s (night of Nov 15)
    const val ATTEMPT_TIMEOUT_MS = 4_000L

    fun shouldResume(connected: Boolean, hasKey: Boolean, ended: Boolean): Boolean =
        connected && hasKey && !ended

    /** 250, 500, 1000, 2000, 2000… ms before attempt n (0-based). */
    fun delayMs(attempt: Int): Long = minOf((250L * (1L shl maxOf(0, attempt).coerceAtMost(10))), 2_000L)
}
