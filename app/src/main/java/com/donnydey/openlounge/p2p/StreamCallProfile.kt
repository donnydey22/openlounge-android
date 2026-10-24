package com.donnydey.openlounge.p2p

/**
 * Keiko's Android lane (LOCKED plan, Donny Oct 24 2026 5:05/5:07 AM MST): a faithful clone of ChatSwap 167's
 * CallMediaProfile (WebRtcBaseActivity.kt:245-520), on Stream WebRTC Android 1.3.10 (m125) and Open Lounge's own
 * servers. ChatSwap is read-only; nothing here is copied from its credentials.
 *
 * Changes vs ChatSwap (all from Donny's steers):
 *  - ICE servers come from the call-signal service (short-lived HMAC TURN logins, ≤10 min), never hardcoded.
 *  - UDP first: direct/STUN, then TURN UDP 3478, TURN TCP 3478, TURNS 443 (Oct 23 order, kept Oct 24 5:41 AM).
 *  - Audio = best call 3ae8ff34: Opus FEC on, DTX off, mono, 48 kHz, 48 kbps cap, ptime 20; voice priority high.
 *  - Ladder 48/32/24 kbps, 24 kbps floor; down after 2 bad s (>5 % loss or >450 ms RTT), up after 8 good s.
 *  - Every drop → fast ICE restart on UDP to the same peer path (backoff 1 s → 30 s), relay only after that;
 *    while on relay, probe UDP and migrate back in place (no teardown, audio keeps flowing).
 * Pure JVM logic only in this file so it is unit-testable without a device.
 */
object StreamCallProfile {
    const val STREAM_WEBRTC_ANDROID = "io.getstream:stream-webrtc-android:1.3.10"

    // ChatSwap capture defaults (CallMediaProfile.kt)
    const val VIDEO_WIDTH = 640
    const val VIDEO_HEIGHT = 480
    const val VIDEO_FPS = 24

    // 3ae8ff34 audio
    const val OPUS_MAX_AVG_BPS = 48_000
    const val VOICE_FLOOR_BPS = 24_000
    val VOICE_LADDER_BPS = intArrayOf(48_000, 32_000, 24_000)
    const val ADAPT_DOWN_AFTER_S = 2
    const val ADAPT_UP_AFTER_S = 8
    const val ADAPT_LOSS_PERCENT = 5.0
    const val ADAPT_RTT_MS = 450.0
    const val MAX_TURN_TTL_S = 600

    /** UDP-first rank of an ICE url: STUN/direct 0, TURN UDP 1, TURN TCP 2, TURNS 3. */
    fun urlRank(url: String): Int = when {
        url.startsWith("stun:") -> 0
        url.startsWith("turns:") -> 3
        url.startsWith("turn:") && url.contains("transport=tcp") -> 2
        url.startsWith("turn:") -> 1
        else -> 4
    }

    data class Ice(val urls: List<String>, val username: String? = null, val credential: String? = null)

    /** Flatten + order so every lane tries UDP before any TCP/TLS relay (stable within a rank). */
    fun orderUdpFirst(servers: List<Ice>): List<Ice> =
        servers.flatMap { s -> s.urls.map { Ice(listOf(it), s.username, s.credential) } }.sortedBy { urlRank(it.urls[0]) }

    /** TURN usernames are `<unix-expiry>:<id>` (coturn use-auth-secret). Seconds of life left, null if not REST form. */
    fun turnSecondsLeft(username: String?, nowS: Long): Long? = username?.substringBefore(':')?.toLongOrNull()?.minus(nowS)

    /** Opus fmtp per 3ae8ff34 (FEC on, DTX off, mono, 48 kbps cap, ptime 20). Idempotent. */
    fun mungeOpus(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000/2", RegexOption.IGNORE_CASE).find(sdp)?.groupValues?.get(1) ?: return sdp
        val want = linkedMapOf("minptime" to "10", "useinbandfec" to "1", "usedtx" to "0", "stereo" to "0", "sprop-stereo" to "0",
            "maxaveragebitrate" to OPUS_MAX_AVG_BPS.toString(), "maxplaybackrate" to "48000", "ptime" to "20")
        val nl = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(nl).toMutableList()
        val i = lines.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
        if (i >= 0) {
            val have = linkedMapOf<String, String>()
            lines[i].substringAfter(' ').split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                have[it.substringBefore('=')] = it.substringAfter('=', "")
            }
            have.putAll(want)
            lines[i] = "a=fmtp:$pt " + have.entries.joinToString(";") { "${it.key}=${it.value}" }
        } else {
            val r = lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
            lines.add(r + 1, "a=fmtp:$pt " + want.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
        if (lines.none { it.startsWith("a=ptime:") }) {
            val m = lines.indexOfFirst { it.startsWith("m=audio") }
            if (m >= 0) lines.add(m + 1, "a=ptime:20")
        }
        return lines.joinToString(nl)
    }

    /** Voice ladder (netAdapt parity): 2 bad seconds → down a rung, 8 good → up; never below the 24 kbps floor. */
    class VoiceLadder {
        var level = 0; private set
        private var bad = 0
        private var good = 0
        val bps get() = maxOf(VOICE_LADDER_BPS[level], VOICE_FLOOR_BPS)
        /** returns the new bitrate when it changed, else null */
        fun push(lossPercent: Double?, rttMs: Double?): Int? {
            val isBad = (lossPercent ?: 0.0) > ADAPT_LOSS_PERCENT || (rttMs ?: 0.0) > ADAPT_RTT_MS
            if (isBad) { bad++; good = 0 } else { good++; bad = 0 }
            if (bad >= ADAPT_DOWN_AFTER_S && level < VOICE_LADDER_BPS.size - 1) { level++; bad = 0; return bps }
            if (good >= ADAPT_UP_AFTER_S && level > 0) { level--; good = 0; return bps }
            return null
        }
    }

    /** Every-drop ICE restart with backoff (mirrors web iceRestartBackoff.ts): 1 s base, doubles without recovery, 30 s cap. */
    class IceRestartBackoff(private val base: Long = 1_000, private val max: Long = 30_000) {
        private var last = Long.MIN_VALUE / 2
        var gapMs = base; private set
        private var pending = false
        fun shouldRestart(nowMs: Long): Boolean {
            if (nowMs - last < gapMs) return false
            if (pending) gapMs = minOf(gapMs * 2, max)
            last = nowMs; pending = true
            return true
        }
        fun onConnected() { pending = false; gapMs = base }
    }

    /** Relay → UDP return probe schedule (web udpProbe.ts parity): never gives up, caps at the last delay. */
    val UDP_PROBE_DELAYS_MS = longArrayOf(3_000, 5_000, 10_000, 15_000, 30_000, 60_000)
    fun udpProbeDelay(attempt: Int): Long = UDP_PROBE_DELAYS_MS[minOf(maxOf(attempt, 0), UDP_PROBE_DELAYS_MS.size - 1)]

    /** selected candidate pair is a relay (TURN) → we are off direct UDP and should probe back. */
    fun isRelayed(localCandidateType: String?, remoteCandidateType: String?) =
        localCandidateType == "relay" || remoteCandidateType == "relay"
}
