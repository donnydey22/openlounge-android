package com.donnydey.openlounge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Native 1:1 call screen (outgoing / connecting / active audio / active video / end), drawn above the WebView.
 *
 * Flow + state: the page's call engine stays the single owner of call:* on openlounge.app (no server or page change).
 * It already posts call:outgoing / call:answering / call:connected / call:ended / call:muted to the app, and accepts
 * call:hangup / call:mute back (openlounge:native-call, CallProvider.tsx). This screen only maps those to Telegram's
 * VoIPService states and draws them like Telegram's VoIPFragment:
 *   call:outgoing  -> STATE_WAITING        (VoIPFragment.updateViewState: status text)  [WhatsApp wording "Calling…"]
 *   call:answering -> STATE_WAIT_INIT      ("Connecting…")
 *   call:connected -> STATE_ESTABLISHED    (showTimer; = engine reported connected, VoIPService.onConnectionStateChanged)
 *   call:ended     -> STATE_ENDED          (no rating -> windowView.finish(): screen removed)
 * Controls row = VoIPFragment.updateButtons: [speaker | flip camera when my video is on] [video] [mute] [end].
 * Refs: DrKLO/Telegram@3de37c8 TMessagesProj/.../ui/VoIPFragment.java, .../messenger/voip/VoIPService.java.
 *
 * Gate (fallback = the page's own call screen underneath, unchanged): shown only for calls the page announced with a
 * callId; a video call keeps it only once native video is up (MainActivity.nativeCallVideo(true)), otherwise the
 * overlay steps aside on call:connected and the web video screen is used as today. Kill switch: Prefs native_call_ui.
 */
class NativeCallScreen(private val act: Activity, private val root: FrameLayout, private val actions: Actions) {
    interface Actions {
        fun pageCall(detailJson: String): Boolean
        fun speaker(on: Boolean)
        fun camera(on: Boolean, facing: String)
    }


    var state: NativeCallFlow.State? = null; private set
    private var callId: String? = null
    private var video = false
    private var nativeVideo = false
    private var muted = false
    private var speakerOn = false
    private var camOn = false
    private var facing = "user"
    private var establishedAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var b0: TextView
    private lateinit var bVideo: TextView
    private lateinit var bMute: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (state != NativeCallFlow.State.ESTABLISHED) return
            val s = (SystemClock.elapsedRealtime() - establishedAt) / 1000
            status.text = NativeCallFlow.timer(s)
            ui.postDelayed(this, 1000)
        }
    }

    /** Page -> app call message (MainActivity.onPageCall). */
    fun onPageCall(type: String, m: JSONObject) {
        val id = m.optString("callId", "")
        when (type) {
            "call:outgoing" -> if (id.isNotEmpty()) { begin(id, m.optString("name", ""), m.optBoolean("video")); set(NativeCallFlow.State.WAITING) }
            "call:answering" -> if (id.isNotEmpty()) { if (callId != id) begin(id, m.optString("name", ""), m.optBoolean("video")); set(NativeCallFlow.State.WAIT_INIT) }
            "call:connected" -> if (view != null && (id.isEmpty() || id == callId)) {
                video = video || m.optBoolean("video")
                set(NativeCallFlow.State.ESTABLISHED)
                // video: the native leg opens right after connect; if native tiles aren't up in 3 s the web video
                // screen underneath owns the call (fallback, as today)
                if (video && !nativeVideo) { val c = callId; ui.postDelayed({ if (callId == c && view != null && !nativeVideo) { hidden = true; view?.visibility = View.GONE } }, 3000) }
            }
            "call:ended" -> if (id.isEmpty() || id == callId) { set(NativeCallFlow.State.ENDED); dismiss() }
            "call:muted" -> { muted = m.optBoolean("muted"); render() }
            "call:audioRoute" -> if (m.has("speaker")) { speakerOn = m.optBoolean("speaker"); render() }
        }
    }

    /** MainActivity.nativeCallVideo: native tiles are up (remote full screen, self-view PiP) -> transparent screen. */
    fun onNativeVideo(on: Boolean) { nativeVideo = on; if (on) { video = true; camOn = true; if (hidden) { hidden = false; view?.visibility = View.VISIBLE } }; render() }
    private var hidden = false

    private fun begin(id: String, peer: String, v: Boolean) {
        dismiss(); callId = id; video = v; camOn = v; muted = false; speakerOn = v; facing = "user"
        if (!Prefs.nativeCallUi(act)) { callId = null; return }
        build(); name.text = peer.ifEmpty { act.getString(R.string.app_name) }
    }

    private fun set(s: NativeCallFlow.State) { if (view == null) return; state = s
        if (s == NativeCallFlow.State.ESTABLISHED && establishedAt == 0L) { establishedAt = SystemClock.elapsedRealtime(); ui.post(tick) }
        render() }

    private fun dismiss() { ui.removeCallbacks(tick); view?.let { root.removeView(it) }; view = null; state = null; callId = null; hidden = false; establishedAt = 0L; nativeVideo = false }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    private fun round(label: String, bg: Int, click: () -> Unit) = TextView(act).apply {
        text = label; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(10), 0, dp(10), 0) }
    }

    private fun build() {
        val v = FrameLayout(act).apply { isClickable = true } // the page under it never gets touches while shown
        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        name = TextView(act).apply { setTextColor(Color.WHITE); textSize = 26f; gravity = Gravity.CENTER; maxLines = 1 }
        status = TextView(act).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER }
        top.addView(name); top.addView(status)
        v.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(96) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val grey = 0x40FFFFFF
        b0 = round("", grey) {
            if (camOn) { facing = if (facing == "user") "environment" else "user"; actions.camera(true, facing) }
            else { speakerOn = !speakerOn; actions.speaker(speakerOn); render() } }
        bVideo = round("", grey) { if (!video) return@round; camOn = !camOn; actions.camera(camOn, facing); render() }
        bMute = round("", grey) { actions.pageCall(JSONObject().put("type", "call:mute").put("muted", !muted).toString()) }
        val end = round("End", 0xFFF4473A.toInt()) { actions.pageCall(JSONObject().put("type", "call:hangup").toString()) }
        row.addView(b0); row.addView(bVideo); row.addView(bMute); row.addView(end)
        v.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(56) })
        root.addView(v, FrameLayout.LayoutParams(-1, -1)); view = v
    }

    private fun render() {
        val v = view ?: return
        v.setBackgroundColor(if (nativeVideo) Color.TRANSPARENT else 0xFF1C2B3A.toInt())
        if (state != NativeCallFlow.State.ESTABLISHED) status.text = NativeCallFlow.statusText(state)
        b0.text = if (camOn) "Flip" else if (speakerOn) "Speaker\non" else "Speaker"
        bVideo.text = if (camOn) "Video\non" else "Video"; bVideo.alpha = if (video) 1f else 0.4f
        bMute.text = if (muted) "Muted" else "Mute"
        b0.isSelected = speakerOn; bMute.isSelected = muted
    }

    val showing: Boolean get() = view != null && !hidden
}
