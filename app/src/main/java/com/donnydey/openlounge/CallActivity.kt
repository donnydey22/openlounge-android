package com.donnydey.openlounge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Native call host (Claude step 5 first cut): one Activity, four phases
 * (incoming / outgoing / active / ended). No WebView child.
 *
 * IncomingCallActivity remains the FSI trampoline for lock-screen rings and forwards here.
 * Full CallController + surface ownership moves in a later cut; this cut establishes the host
 * and keeps NativeCallScreen chrome as the phase renderer inside the host root.
 */
class CallActivity : Activity() {
    companion object {
        const val EXTRA_PHASE = "ol_call_phase"
        const val PHASE_INCOMING = "incoming"
        const val PHASE_OUTGOING = "outgoing"
        const val PHASE_ACTIVE = "active"
        const val PHASE_ENDED = "ended"

        @JvmStatic
        fun intent(
            ctx: Context,
            callId: String,
            name: String,
            photo: String,
            video: Boolean,
            phase: String,
            expiresAt: Long = 0L,
        ): Intent = Intent(ctx, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(CallNotifications.EXTRA_CALL_ID, callId)
            .putExtra(CallNotifications.EXTRA_CALLER, name)
            .putExtra(CallNotifications.EXTRA_PHOTO, photo)
            .putExtra(CallNotifications.EXTRA_VIDEO, video)
            .putExtra(CallNotifications.EXTRA_EXPIRES, expiresAt)
            .putExtra(EXTRA_PHASE, phase)
    }

    private var root: FrameLayout? = null
    private var screen: NativeCallScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val r = FrameLayout(this).also { it.setBackgroundColor(0xFF0B1220.toInt()); root = it }
        setContentView(r)
        screen = NativeCallScreen(this, r, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean = false // first cut: host owns chrome; signaling stays elsewhere
            override fun speaker(on: Boolean) { /* first cut: route via CallService/MainActivity path later */ }
            override fun camera(on: Boolean, facing: String) {
                NativeCallMedia.handle("""{"op":"camera","on":$on,"facing":"$facing"}""") { _, _, _ -> }
            }
        })
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(i: Intent) {
        val id = i.getStringExtra(CallNotifications.EXTRA_CALL_ID) ?: return
        val name = i.getStringExtra(CallNotifications.EXTRA_CALLER).orEmpty()
        val photo = i.getStringExtra(CallNotifications.EXTRA_PHOTO).orEmpty()
        val video = i.getBooleanExtra(CallNotifications.EXTRA_VIDEO, false)
        val phase = i.getStringExtra(EXTRA_PHASE) ?: PHASE_INCOMING
        val type = when (phase) {
            PHASE_INCOMING -> "call:incoming"
            PHASE_OUTGOING -> "call:outgoing"
            PHASE_ACTIVE -> "call:connected"
            PHASE_ENDED -> "call:ended"
            else -> "call:answering"
        }
        val m = org.json.JSONObject()
            .put("callId", id).put("name", name).put("photo", photo).put("video", video)
        screen?.onPageCall(type, m)
        if (phase == PHASE_INCOMING) {
            // Accept / Decline still available via IncomingCallActivity trampoline + notifications for this cut.
        }
    }
}
