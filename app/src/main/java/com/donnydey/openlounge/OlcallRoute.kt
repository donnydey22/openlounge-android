package com.donnydey.openlounge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which engine a "connect" op uses. Pure (no Android calls) so it is unit-tested on the JVM.
 *
 * The olcall/tgcalls engine (the :olcall module) is chosen ONLY when the backend hands out
 * CallMedia { provider: "olcall", url: wss://.../v1/call, token } and the phone is API 27+ (the
 * module's minSdk). Everything else keeps the existing BSD-WebRTC [com.donnydey.openlounge.rtc.RtcCallSession]
 * path untouched. The relay list: olcall's joined frame carries the server's iceServers (primary);
 * CallMedia.iceServers from the shared JS layer is handed over as the fallback ([appIceServers]),
 * and the engine picks between them with OlRtcServers.select.
 */
object OlcallRoute {
    const val MIN_SDK = 27

    /** ABIs libolcall_tgcalls.so is built for (the app also ships x86_64 org.webrtc for emulators; olcall does not). */
    val ABIS = setOf("arm64-v8a", "armeabi-v7a")

    fun eligible(media: JSONObject?, sdkInt: Int, abis: List<String> = ABIS.toList()): Boolean =
        media != null && sdkInt >= MIN_SDK && abis.firstOrNull() in ABIS &&
            media.optString("provider") == "olcall" &&
            media.optString("url").let { it.startsWith("wss://") || it.startsWith("ws://") } &&
            media.optString("token").isNotEmpty()

    /** CallMedia.iceServers (TurnIceServer[] from src/lib/types.ts) or null; never logged with credentials. */
    fun appIceServers(media: JSONObject?): JSONArray? = media?.optJSONArray("iceServers")?.takeIf { it.length() > 0 }
}
