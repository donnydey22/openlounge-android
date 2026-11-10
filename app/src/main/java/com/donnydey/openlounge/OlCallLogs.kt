package com.donnydey.openlounge

import android.content.Context
import android.os.Build
import com.donnydey.openlounge.olcall.OlCallLog
import com.donnydey.openlounge.olcall.OlCallLogQueue
import com.donnydey.openlounge.olcall.OlCallLogUploader
import com.donnydey.openlounge.olcall.OlTgcallsEngine
import java.io.File

/**
 * App side of the OL call log (t5375): one on-disk queue (filesDir/olcall-log/queue.jsonl, survives kill/restart) and
 * one uploader to <origin>/api/call-log with the app session (or each call's olcall token when signed out/expired).
 * Pending events are re-sent at app start and after every call.
 */
object OlCallLogs {
    @Volatile private var queue: OlCallLogQueue? = null
    @Volatile private var uploader: OlCallLogUploader? = null
    @Volatile private var app: Context? = null

    @JvmStatic fun init(ctx: Context) {
        if (queue != null) return
        val c = ctx.applicationContext
        app = c
        val q = OlCallLogQueue(File(File(c.filesDir, "olcall-log"), "queue.jsonl"))
        queue = q
        uploader = OlCallLogUploader(q, { CallApi.origin(c) + "/api/call-log" }, { CallApi.freshAccessToken(c) })
        uploader?.kick()
    }

    @JvmStatic fun kick() { uploader?.kick() }

    val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    private val device get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val os get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Recorder for one olcall leg; role/callId from the token's payload, else the app's call id. */
    fun recorder(callId: String, token: String?, video: Boolean): OlCallLog? {
        val q = queue ?: return null
        val claims = OlCallLog.claims(token)
        return OlCallLog(q, uploader, OlCallLog.Meta(
            callId = claims?.first ?: callId, role = claims?.second, media = if (video) "video" else "audio",
            device = device, os = os, appVersion = appVersion, engine = OlTgcallsEngine.ENGINE, olcallToken = token?.takeIf { it.isNotEmpty() }))
    }

    /** The call never reached the engine (permission denied, engine unavailable): idle -> failed with its tag. */
    fun failedBeforeStart(callId: String, token: String?, video: Boolean, raw: String) {
        runCatching { recorder(callId, token, video)?.failedBeforeStart(raw) }
    }
}
