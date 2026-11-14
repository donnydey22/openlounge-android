package com.donnydey.openlounge.olcall

/** When the phone should restart its tgcalls engine mid-call. Mirrors iOS OLMediaReset. */
object OlMediaReset {
    const val SILENCE_AFTER_RESUME_MS = 3_000L
    const val COOLDOWN_MS = 8_000L
    const val MAX_PER_CALL = 3

    fun pathChange(from: String, to: String): Boolean =
        from != to && from != "none" && to != "none" && from.isNotEmpty() && to.isNotEmpty()

    fun allowed(connected: Boolean, inFlight: Boolean, ended: Boolean, count: Int, lastAt: Long, now: Long): Boolean =
        connected && !inFlight && !ended && count < MAX_PER_CALL && (lastAt == 0L || now - lastAt >= COOLDOWN_MS)

    fun shouldResetOnPathChange(from: String?, to: String, connected: Boolean, inFlight: Boolean,
                                ended: Boolean, count: Int, lastAt: Long, now: Long): Boolean {
        if (from == null || !pathChange(from, to)) return false
        return allowed(connected, inFlight, ended, count, lastAt, now)
    }

    fun shouldResetOnSilence(resumedAt: Long, flowing: Boolean, connected: Boolean, inFlight: Boolean,
                             ended: Boolean, count: Int, lastAt: Long, now: Long): Boolean {
        if (resumedAt == 0L || flowing || now - resumedAt < SILENCE_AFTER_RESUME_MS) return false
        return allowed(connected, inFlight, ended, count, lastAt, now)
    }
}
