package com.donnydey.openlounge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Native call host (Claude step 5): one Activity, four phases
 * (incoming / outgoing / active / ended). No WebView child.
 *
 * IncomingCallActivity remains the FSI trampoline for lock-screen rings and forwards here
 * after Accept. This cut wires chrome controls to AudioManager + MainActivity.dispatchNativeCall
 * / NativeCallMedia / CallApi so the host is usable without the page painting the call.
 */
class CallActivity : Activity() {
    companion object {
        const val EXTRA_PHASE = "ol_call_phase"
        const val PHASE_INCOMING = "incoming"
        const val PHASE_OUTGOING = "outgoing"
        const val PHASE_ACTIVE = "active"
        const val PHASE_ENDED = "ended"

        @JvmStatic
        @JvmOverloads
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
    private var callId: String? = null
    private var peerName: String = ""
    private var peerPhoto: String = ""
    private var video = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val r = FrameLayout(this).also { it.setBackgroundColor(0xFF0B1220.toInt()); root = it }
        setContentView(r)
        screen = NativeCallScreen(this, r, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean {
                // Prefer live MainActivity page bridge; fall back to native hangup/mute paths.
                if (MainActivity.dispatchNativeCall(detailJson)) return true
                return handleLocalCallAction(detailJson)
            }
            override fun speaker(on: Boolean) {
                val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
                try {
                    if (Build.VERSION.SDK_INT >= 31) {
                        // Communication device pick is owned by MainActivity CallAudioRoute when alive;
                        // here use speakerphone as the durable fallback for the standalone host.
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = on
                    } else {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = on
                    }
                } catch (_: RuntimeException) {}
            }
            override fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit) {
                if (on) {
                    OlCallPermissionPrompt.ensure(this@CallActivity, OlCallPermissions.Action.TOGGLE_VIDEO_ON) { ok ->
                        if (!ok) { done(false); return@ensure }
                        CallMediaCoordinator.handle("""{"op":"camera","on":true,"facing":"$facing"}""") { granted, _, _ -> done(granted) }
                    }
                } else {
                    CallMediaCoordinator.handle("""{"op":"camera","on":false,"facing":"$facing"}""") { granted, _, _ -> done(granted) }
                }
            }
            override fun revealAppWhenMinimized() {
                // This host has no WebView. Hand the green bar to MainActivity over the real app; do not hang up.
                val id = callId ?: return
                startActivity(
                    Intent(this@CallActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_MINIBAR_CALL_ID, id)
                        .putExtra(MainActivity.EXTRA_MINIBAR_NAME, peerName)
                        .putExtra(MainActivity.EXTRA_MINIBAR_PHOTO, peerPhoto)
                        .putExtra(MainActivity.EXTRA_MINIBAR_VIDEO, video)
                )
                finish()
            }
        })
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        OlCallPermissionPrompt.attach(this)
    }

    override fun onPause() {
        OlCallPermissionPrompt.detach(this)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!OlCallPermissionPrompt.onResult(requestCode, permissions, grantResults))
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun applyIntent(i: Intent) {
        val id = i.getStringExtra(CallNotifications.EXTRA_CALL_ID) ?: return
        callId = id
        peerName = i.getStringExtra(CallNotifications.EXTRA_CALLER).orEmpty()
        peerPhoto = i.getStringExtra(CallNotifications.EXTRA_PHOTO).orEmpty()
        video = i.getBooleanExtra(CallNotifications.EXTRA_VIDEO, false)
        val phase = i.getStringExtra(EXTRA_PHASE) ?: PHASE_INCOMING
        val type = when (phase) {
            PHASE_INCOMING -> "call:incoming"
            PHASE_OUTGOING -> "call:outgoing"
            PHASE_ACTIVE -> "call:connected"
            PHASE_ENDED -> "call:ended"
            else -> "call:answering"
        }
        val m = JSONObject()
            .put("callId", id).put("name", peerName).put("photo", peerPhoto).put("video", video)
        screen?.onPageCall(type, m)
        if (phase == PHASE_ENDED) {
            window.decorView.postDelayed({ finish() }, 900)
        }
    }

    /** Standalone host path when MainActivity's page bridge is not up. */
    private fun handleLocalCallAction(detailJson: String): Boolean {
        return try {
            val m = JSONObject(detailJson)
            when (m.optString("type")) {
                "call:hangup" -> {
                    val id = callId ?: return false
                    Thread {
                        try {
                            // Best-effort: decline token path covers unanswered; hangup via page if alive.
                            CallApi.decline(applicationContext, id)
                        } catch (_: Throwable) {}
                        runOnUiThread {
                            CallMediaCoordinator.closeAll()
                            CallService.stop(this)
                            CallNotifications.cancel(this, id)
                            screen?.onPageCall("call:ended", JSONObject().put("callId", id))
                            finish()
                        }
                    }.start()
                    true
                }
                "call:mute" -> {
                    val muted = m.optBoolean("muted")
                    CallMediaCoordinator.handle("""{"op":"mute","muted":$muted}""") { _, _, _ -> }
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}
