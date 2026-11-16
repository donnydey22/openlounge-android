package com.donnydey.openlounge.olcall

/** When the phone should restart its tgcalls engine mid-call. Mirrors iOS OLMediaReset. */
object OlMediaReset {
    const val SILENCE_AFTER_RESUME_MS = 3_000L
    const val COOLDOWN_MS = 3_000L
    // Dual-connected phones flap Wi-Fi <-> cellular; every flip needs its own restart (Nov 15 night, 60).
    const val MAX_PER_CALL = 30
    /** tgcalls stuck in Reconnecting this long mid-call: restart the engine instead of waiting on it. */
    const val RECONNECTING_RESET_MS = 4_000L
    /** Debounce for default-network flips before moving the signaling socket. */
    const val PATH_SETTLE_MS = 1_500L

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
