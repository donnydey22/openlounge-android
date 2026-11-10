package com.donnydey.openlounge

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Call-screen presence (read-only). Matches the Avatar component at 101e00a as the
 * first call UI later wired it (efde870 PresenceAvatar): emerald-400 online, amber-400
 * away, hidden when offline or unknown. No server or presence-protocol changes.
 *
 * Sources the app already has:
 *  - a "presence" / "online" field on a call:* page message, if the page included one
 *  - the page's live call-screen [data-presence] attribute, read via evaluateJavascript
 */
object CallPresence {
    const val ONLINE = "online"
    const val AWAY = "away"
    const val COLOR_ONLINE = 0xFF34D399.toInt() // emerald-400
    const val COLOR_AWAY = 0xFFFBBF24.toInt()   // amber-400
    const val RING = 0xFF0B1220.toInt()         // call-stage ring, 3dp

    /** Read-only query of the page's already-painted call Avatar presence, if any. */
    const val PEEK_JS =
        "(function(){try{var el=document.querySelector('[data-testid=\"call-screen\"] [data-presence]')||document.querySelector('[data-testid=\"incoming-call\"] [data-presence]');if(!el)return '';return el.getAttribute('data-presence')||'offline';}catch(e){return '';}})()"

    /** "online" / "away", or null when offline / unknown / unparseable. */
    @JvmStatic
    fun of(raw: String?): String? {
        var s = raw?.trim() ?: return null
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            s = rawFromJs(s)
        }
        return when (s.lowercase()) {
            ONLINE, "true", "1" -> ONLINE
            AWAY -> AWAY
            else -> null
        }
    }

    @JvmStatic
    fun color(status: String): Int = if (status == AWAY) COLOR_AWAY else COLOR_ONLINE

    @JvmStatic
    fun hasField(m: JSONObject): Boolean = m.has("presence") || m.has("online")

    @JvmStatic
    fun fromMessage(m: JSONObject): String? {
        if (m.has("presence")) return of(m.optString("presence"))
        if (m.has("online")) {
            val v = m.opt("online")
            if (v is Boolean) return if (v) ONLINE else null
            return of(v?.toString())
        }
        return null
    }

    /**
     * Unwrap a WebView evaluateJavascript result. Empty string means the page had no
     * data-presence node (keep the last known value). A real token such as "offline"
     * should go through [of].
     */
    @JvmStatic
    fun rawFromJs(json: String?): String {
        if (json.isNullOrBlank() || json == "null") return ""
        return try {
            JSONTokener(json).nextValue()?.toString() ?: ""
        } catch (_: Exception) {
            json.trim().trim('"')
        }
    }
}
