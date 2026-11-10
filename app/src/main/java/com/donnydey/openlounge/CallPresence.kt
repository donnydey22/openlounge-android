package com.donnydey.openlounge

import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

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

    const val FAIL_MSG = "Can't connect the call on this network. Try another Wi-Fi or mobile data."

    /** Read-only peek of the page call-screen profile photo, if it already painted one. */
    const val PEEK_PHOTO_JS =
        "(function(){try{var img=document.querySelector('[data-testid=\"call-screen\"] img')||document.querySelector('[data-testid=\"incoming-call\"] img');var s=img&&img.src||'';if(s.indexOf('http')===0)return s;return '';}catch(e){return '';}})()"

    @JvmStatic
    fun isFailure(reason: String?, text: String?): Boolean {
        if (reason == "failed") return true
        val t = text?.trim().orEmpty()
        return t.contains("Can't connect the call on this network") || t.contains("Try another Wi-Fi")
    }

    @JvmStatic
    fun failureText(text: String?): String {
        val t = text?.trim().orEmpty()
        return t.ifEmpty { FAIL_MSG }
    }

    @JvmStatic
    fun messageFromEnd(m: JSONObject): String? {
        for (k in arrayOf("endedText", "error", "message", "text")) {
            val t = m.optString(k, "").trim()
            if (t.isNotEmpty()) return t
        }
        return null
    }

    /** https/http profile URLs, or a file:// under this app's cache/files (tests / local decode). */
    @JvmStatic
    fun allowedPhotoUrl(url: String?, cacheDir: File, filesDir: File): Boolean {
        if (url.isNullOrBlank() || url.length > 2048) return false
        if (url.startsWith("https://") || url.startsWith("http://")) return true
        if (!url.startsWith("file:")) return false
        return try {
            val path = java.net.URI(url).path ?: return false
            val canon = File(path).canonicalPath
            canon.startsWith(cacheDir.canonicalPath + "/") || canon.startsWith(filesDir.canonicalPath + "/") ||
                canon == cacheDir.canonicalPath || canon == filesDir.canonicalPath
        } catch (_: Exception) {
            false
        }
    }
}
