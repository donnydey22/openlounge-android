package com.donnydey.openlounge.olcall

import org.json.JSONArray

/**
 * Relay list for the phone engine: standard RTCIceServer JSON -> tgcalls::RtcServer
 * (Descriptor.rtcServers). ONE shared contract for both phones:
 *  - the reference rules live in olcall/tgrtc (Go) and are pinned by the golden
 *    fixture olcall/integration/testdata/rtcservers.json; this class and the iOS
 *    wrapper (item 3) must produce exactly that fixture's "expect" lists;
 *  - the input JSON has the same shape whether it comes from the olcall "joined"
 *    frame (fresh coturn REST creds minted per leg) or from the OL web/JS layer's
 *    CallMedia.iceServers (aud-openlounge src/lib/types.ts TurnIceServer), which
 *    both native shells already receive over their JS bridge.
 *
 * Rules: stun: -> STUN; turn: (udp default, ?transport=tcp) -> TURN with the
 * short-lived username/credential; turns:/stuns:/unknown -> skipped (tgcalls'
 * RtcServer has no TLS flag); username "reflector" (Telegram's own relay) ->
 * skipped; default port 3478; hostnames kept (device DNS resolves them);
 * duplicates dropped, order kept.
 *
 * Direct first / relay fallback: the engine keeps Config.enableP2P = true, so
 * host/srflx candidates are gathered and win on ICE priority; TURN relay
 * candidates are used only when no direct pair works.
 */
object OlRtcServers {
    data class Server(
        val host: String,
        val port: Int,
        val login: String,
        val password: String,
        val isTurn: Boolean,
        val isTcp: Boolean,
    ) {
        /** Safe for logs/breadcrumbs: never includes the credential. */
        override fun toString(): String =
            (if (isTurn) (if (isTcp) "turn-tcp" else "turn-udp") else "stun") + " $host:$port"
    }

    fun fromIceServers(arr: JSONArray?): List<Server> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<Server>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val user = s.optString("username", "")
            val cred = s.opt("credential") as? String ?: ""
            val urls = s.opt("urls")
            val list = when (urls) {
                is JSONArray -> (0 until urls.length()).mapNotNull { urls.opt(it) as? String }
                is String -> listOf(urls)
                else -> emptyList()
            }
            for (u in list) parse(u.trim(), user, cred)?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * The server's joined.iceServers are minted per leg at join time, so they win.
     * The JS-layer list (CallMedia.iceServers) is used only when the joined frame has none.
     */
    fun select(joined: JSONArray?, app: JSONArray?): Pair<List<Server>, String> {
        val j = fromIceServers(joined)
        if (j.isNotEmpty()) return j to "joined"
        val a = fromIceServers(app)
        return a to (if (a.isNotEmpty()) "app" else "none")
    }

    /** Counts only (no hosts or credentials), for breadcrumbs. */
    fun summary(list: List<Server>): String =
        "stun=${list.count { !it.isTurn }} turnUdp=${list.count { it.isTurn && !it.isTcp }} turnTcp=${list.count { it.isTurn && it.isTcp }}"

    internal fun parse(raw: String, user: String, cred: String): Server? {
        val i = raw.indexOf(':')
        if (i <= 0) return null
        val scheme = raw.substring(0, i).lowercase()
        var rest = raw.substring(i + 1)
        var query = ""
        rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }
        var host = rest
        var port = 3478
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end < 0) return null
            host = rest.substring(1, end)
            val p = rest.substring(end + 1)
            if (p.startsWith(":")) port = p.substring(1).toIntOrNull() ?: return null
        } else {
            val c = rest.lastIndexOf(':')
            if (c >= 0) {
                port = rest.substring(c + 1).toIntOrNull() ?: return null
                host = rest.substring(0, c)
            }
        }
        if (host.isEmpty() || port <= 0 || port > 65535) return null
        return when (scheme) {
            "stun" -> Server(host, port, "", "", isTurn = false, isTcp = false)
            "turn" -> {
                if (user.isEmpty() || cred.isEmpty() || user == "reflector") return null
                val tcp = query.split('&').any { it.equals("transport=tcp", ignoreCase = true) }
                Server(host, port, user, cred, isTurn = true, isTcp = tcp)
            }
            else -> null
        }
    }
}
