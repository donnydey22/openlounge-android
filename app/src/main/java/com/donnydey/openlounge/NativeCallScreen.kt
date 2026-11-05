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
 * Placement = VoIPFragment.updateButtons: [speaker | flip camera when my video is on] [video] [mute] [end].
 * Look = Open Lounge's own call screen (web CallOverlay.tsx), not Telegram's.
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
            "call:incoming" -> if (id.isNotEmpty()) { incoming = id to m.optString("name", ""); incomingPhoto = id to m.optString("photo", "") }
            "call:outgoing" -> if (id.isNotEmpty()) { begin(id, m.optString("name", ""), m.optBoolean("video"), m.optString("photo", "")); set(NativeCallFlow.State.WAITING) }
            "call:answering" -> if (id.isNotEmpty()) { if (callId != id) begin(id, m.optString("name", "").ifEmpty { incoming?.takeIf { it.first == id }?.second.orEmpty() }, m.optBoolean("video"), m.optString("photo", "").ifEmpty { incomingPhoto?.takeIf { it.first == id }?.second.orEmpty() }); set(NativeCallFlow.State.WAIT_INIT) }
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
    private var incoming: Pair<String, String>? = null
    private var incomingPhoto: Pair<String, String>? = null

    private fun begin(id: String, peer: String, v: Boolean, photo: String = "") {
        dismiss(); callId = id; video = v; camOn = v; muted = false; speakerOn = v; facing = "user"
        if (!Prefs.nativeCallUi(act)) { callId = null; return }
        build(); name.text = peer.ifEmpty { act.getString(R.string.app_name) }; loadPhoto(id, photo)
    }

    private fun set(s: NativeCallFlow.State) { if (view == null) return; state = s
        if (s == NativeCallFlow.State.ESTABLISHED && establishedAt == 0L) { establishedAt = SystemClock.elapsedRealtime(); ui.post(tick) }
        render() }

    private fun dismiss() { ui.removeCallbacks(tick); view?.let { root.removeView(it) }; view = null; state = null; callId = null; hidden = false; establishedAt = 0L; nativeVideo = false }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    // Open Lounge call screen look (web CallOverlay.tsx / calls.module.css / Avatar.tsx): #0b1220 stage + shade,
    // xl initials avatar, 28sp semibold name, 15sp white/80 status, RoundBtn 56dp white/15 (active = white, zinc-900
    // icon), End call 56dp red-500, 12sp white/85 label under each button. lucide icons rendered to ol_call_*.png.
    private class Btn(val box: LinearLayout, val disc: FrameLayout, val icon: android.widget.ImageView, val label: TextView)
    private fun btn(big: Boolean, danger: Boolean, click: () -> Unit): Btn {
        val size = dp(if (big) 68 else 56)
        val disc = FrameLayout(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (danger) 0xFFEF4444.toInt() else 0x26FFFFFF) } }
        val icon = android.widget.ImageView(act)
        disc.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        val label = TextView(act).apply { setTextColor(0xD9FFFFFF.toInt()); textSize = 12f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); gravity = Gravity.CENTER; maxLines = 1 }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { click() } }
        box.addView(disc, LinearLayout.LayoutParams(size, size))
        box.addView(label, LinearLayout.LayoutParams(dp(76), -2).apply { topMargin = dp(6) })
        box.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        return Btn(box, disc, icon, label)
    }
    private fun Btn.set(text: String, iconRes: Int, active: Boolean = false, enabled: Boolean = true) {
        label.text = text; icon.setImageResource(iconRes)
        icon.setColorFilter(if (active) 0xFF18181B.toInt() else Color.WHITE)
        (disc.background as GradientDrawable).setColor(if (active) Color.WHITE else 0x26FFFFFF)
        box.alpha = if (enabled) 1f else 0.4f; box.isEnabled = enabled
    }

    private fun initials(n: String): String {
        val parts = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && Character.isLetterOrDigit(it.codePointAt(0)) }
        if (parts.isEmpty()) return n.trim().take(1).ifEmpty { "?" }
        if (parts.size == 1) return parts[0].take(2).uppercase()
        return (parts.first().take(1) + parts.last().take(1)).uppercase()
    }

    private lateinit var avatar: TextView
    private lateinit var avatarBox: FrameLayout
    private lateinit var photoView: android.widget.ImageView

    /** web AvatarPhoto.tsx: initials stay underneath, the photo fades in once decoded, a broken URL keeps the initials.
     *  http(s) only, like server/calls/index.ts voipPayload callerPhoto. */
    private fun loadPhoto(id: String, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        Thread {
            val bmp = try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }.also { c.disconnect() }
            } catch (_: Throwable) { null } ?: return@Thread
            ui.post { if (callId == id && view != null) { photoView.setImageBitmap(bmp); photoView.animate().alpha(1f).setDuration(200).withEndAction { avatar.text = "" }.start() } }
        }.start()
    }
    private lateinit var bFirst: Btn
    private lateinit var bVid: Btn
    private lateinit var bMic: Btn

    private fun build() {
        val v = FrameLayout(act).apply { isClickable = true } // the page under it never gets touches while shown
        v.addView(View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0x0D000000, 0x8C000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), 0) }
        avatar = TextView(act).apply { setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        // squircle (web avatar-shape.ts, ~42% corner equivalent), clipped so the photo takes the same shape
        avatarBox = FrameLayout(act).apply { background = GradientDrawable().apply { cornerRadius = dp(47).toFloat(); setColor(0xFF64748B.toInt()) }; clipToOutline = true }
        photoView = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        avatarBox.addView(avatar, FrameLayout.LayoutParams(-1, -1)); avatarBox.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        name = TextView(act).apply { setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD); letterSpacing = -0.01f }
        status = TextView(act).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        top.addView(avatarBox, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(12) })
        top.addView(name, LinearLayout.LayoutParams(-1, -2)); top.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        v.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { bottomMargin = dp(80) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER or Gravity.BOTTOM; setPadding(dp(16), 0, dp(16), 0) }
        bFirst = btn(false, false) {
            if (camOn) { facing = if (facing == "user") "environment" else "user"; actions.camera(true, facing) }
            else { speakerOn = !speakerOn; actions.speaker(speakerOn); render() } }
        bVid = btn(false, false) { if (!video) return@btn; camOn = !camOn; actions.camera(camOn, facing); render() }
        bMic = btn(false, false) { actions.pageCall(JSONObject().put("type", "call:mute").put("muted", !muted).toString()) }
        val end = btn(false, true) { actions.pageCall(JSONObject().put("type", "call:hangup").toString()) }
        end.label.text = "End call"; end.icon.setImageResource(R.drawable.ol_call_phone_off)
        row.addView(bFirst.box); row.addView(bVid.box); row.addView(bMic.box); row.addView(end.box)
        v.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(40) })
        root.addView(v, FrameLayout.LayoutParams(-1, -1)); view = v
    }

    private fun render() {
        val v = view ?: return
        v.setBackgroundColor(if (nativeVideo) Color.TRANSPARENT else 0xFF0B1220.toInt())
        avatarBox.visibility = if (nativeVideo) View.GONE else View.VISIBLE
        if (photoView.alpha < 1f) avatar.text = initials(name.text?.toString().orEmpty())
        if (state != NativeCallFlow.State.ESTABLISHED) status.text = NativeCallFlow.statusText(state)
        if (camOn) bFirst.set("Flip", R.drawable.ol_call_refresh_cw)
        else bFirst.set(if (speakerOn) "Speaker" else "Phone", if (speakerOn) R.drawable.ol_call_volume2 else R.drawable.ol_call_ear, active = speakerOn)
        bVid.box.visibility = if (video) View.VISIBLE else View.GONE // web: native voice calls keep their audio-only policy
        bVid.set(if (camOn) "Stop video" else "Video", if (camOn) R.drawable.ol_call_video else R.drawable.ol_call_video_off, active = camOn)
        bMic.set(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ol_call_mic_off else R.drawable.ol_call_mic, active = muted)
    }

    val showing: Boolean get() = view != null && !hidden
}
