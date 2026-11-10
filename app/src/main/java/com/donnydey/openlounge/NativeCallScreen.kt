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
 * Placement (first UI 101e00a): [Speaker] [Video] [Mute] [End call], plus Flip when the local camera is on
 * so a video call can still switch lenses (the first commit listed flip as an in-call control).
 * Look = the first Open Lounge call screen (web CallOverlay.tsx / calls.module.css at 101e00a, 2026-10-07 MST).
 * Refs: DrKLO/Telegram@3de37c8 TMessagesProj/.../ui/VoIPFragment.java, .../messenger/voip/VoIPService.java.
 *
 * Native-UI first cut (Claude step 5): this screen owns all four phases — never hands the call paint back to the
 * WebView. Missing native video tiles keep the avatar + "Connecting…" chrome; no 3 s web-video fallback.
 * Kill switch: Prefs native_call_ui.
 */
class NativeCallScreen(private val act: Activity, private val root: FrameLayout, private val actions: Actions) {
    interface Actions {
        fun pageCall(detailJson: String): Boolean
        fun speaker(on: Boolean)
        fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit)
        /** Read-only peek of the page's existing call-screen presence (data-presence). */
        fun peekCallPresence(done: (String?) -> Unit) { done(null) }
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
            "call:incoming" -> if (id.isNotEmpty()) { incoming = id to m.optString("name", ""); incomingPhoto = id to m.optString("photo", ""); if (CallPresence.hasField(m)) incomingPresence = id to CallPresence.fromMessage(m) }
            "call:outgoing" -> if (id.isNotEmpty()) { begin(id, m.optString("name", ""), m.optBoolean("video"), m.optString("photo", ""), if (CallPresence.hasField(m)) CallPresence.fromMessage(m) else null); set(NativeCallFlow.State.WAITING) }
            "call:answering" -> if (id.isNotEmpty()) { if (callId != id) begin(id, m.optString("name", "").ifEmpty { incoming?.takeIf { it.first == id }?.second.orEmpty() }, m.optBoolean("video"), m.optString("photo", "").ifEmpty { incomingPhoto?.takeIf { it.first == id }?.second.orEmpty() }, if (CallPresence.hasField(m)) CallPresence.fromMessage(m) else incomingPresence?.takeIf { it.first == id }?.second); set(NativeCallFlow.State.WAIT_INIT) }
            "call:connected" -> if (view != null && (id.isEmpty() || id == callId)) {
                video = video || m.optBoolean("video")
                set(NativeCallFlow.State.ESTABLISHED)
                // Native-UI first cut: never hide for WebView. Stay visible; avatar/status cover until native tiles up.
                if (hidden) { hidden = false; view?.visibility = View.VISIBLE }
            }
            "call:ended" -> if (id.isEmpty() || id == callId) { set(NativeCallFlow.State.ENDED); dismiss() }
            "call:muted" -> { muted = m.optBoolean("muted"); render() }
            "call:audioRoute" -> if (m.has("speaker")) { speakerOn = m.optBoolean("speaker"); render() }
        }
        if (view != null && CallPresence.hasField(m) && (id.isEmpty() || id == callId)) applyPresence(CallPresence.fromMessage(m))
    }

    /** MainActivity.nativeCallVideo: native tiles are up (remote full screen, self-view PiP) -> transparent screen. */
    fun onNativeVideo(on: Boolean) { nativeVideo = on; if (on) { video = true; camOn = true; if (hidden) { hidden = false; view?.visibility = View.VISIBLE } }; render() }
    private var hidden = false
    private var incoming: Pair<String, String>? = null
    private var incomingPhoto: Pair<String, String>? = null
    private var incomingPresence: Pair<String, String?>? = null

    private fun begin(id: String, peer: String, v: Boolean, photo: String = "", presence: String? = null) {
        dismiss(); callId = id; video = v; camOn = v; muted = false; speakerOn = v; facing = "user"
        if (!Prefs.nativeCallUi(act)) { callId = null; return }
        build(); name.text = peer.ifEmpty { act.getString(R.string.app_name) }; loadPhoto(id, photo)
        applyPresence(presence ?: incomingPresence?.takeIf { it.first == id }?.second)
        startPresencePeek()
    }

    private fun set(s: NativeCallFlow.State) { if (view == null) return; state = s
        if (s == NativeCallFlow.State.ESTABLISHED && establishedAt == 0L) { establishedAt = SystemClock.elapsedRealtime(); ui.post(tick) }
        render() }

    private fun dismiss() { ui.removeCallbacks(tick); ui.removeCallbacks(hideCtl); ui.removeCallbacks(presenceTick); view?.let { root.removeView(it) }; view = null; state = null; callId = null; hidden = false; establishedAt = 0L; nativeVideo = false; minimized = false }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    // Open Lounge call screen look (web CallOverlay.tsx / calls.module.css / Avatar.tsx): #0b1220 stage + shade,
    // xl initials avatar, 28sp semibold name, 15sp white/80 status, RoundBtn 56dp white/15 (active = white, zinc-900
    // icon), End call 56dp red-500, 12sp white/85 label under each button. lucide icons rendered to ol_call_*.png.
    private class Btn(val box: LinearLayout, val disc: FrameLayout, val icon: android.widget.ImageView, val label: TextView)
    private fun btn(danger: Boolean, click: () -> Unit): Btn {
        val size = dp(56)
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
    private lateinit var presenceDot: View

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
            ui.post { if (callId == id && view != null) { photoView.setImageBitmap(bmp); bgPhoto.setImageBitmap(bmp); bgPhoto.animate().alpha(1f).setDuration(200).start(); photoView.animate().alpha(1f).setDuration(200).withEndAction { avatar.text = "" }.start() } }
        }.start()
    }
    private lateinit var bFirst: Btn
    private lateinit var bFlip: Btn
    private val rings = ArrayList<View>()
    private lateinit var top: LinearLayout
    private lateinit var bg: FrameLayout
    private lateinit var bgPhoto: android.widget.ImageView
    private lateinit var bVid: Btn
    private lateinit var bMic: Btn

    private fun build() {
        val v = FrameLayout(act).apply { isClickable = true } // the page under it never gets touches while shown
        bg = FrameLayout(act)
        val dm = act.resources.displayMetrics; val vmax = maxOf(dm.widthPixels, dm.heightPixels)
        fun blob(color: Int, gravity: Int, dx: Float, dy: Float): View = View(act).apply {
            background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = vmax * 0.35f; colors = intArrayOf((0x8C shl 24) or (color and 0xFFFFFF), color and 0xFFFFFF) }
            layoutParams = FrameLayout.LayoutParams((vmax * 0.7).toInt(), (vmax * 0.7).toInt(), gravity).apply { if (gravity and Gravity.START == Gravity.START) { leftMargin = (-vmax * 0.25).toInt(); topMargin = (-vmax * 0.2).toInt() } else { rightMargin = (-vmax * 0.3).toInt(); bottomMargin = (-vmax * 0.25).toInt() } }
            animate().translationX(dx * vmax).translationY(dy * vmax).setDuration(14000).start()
        }
        bg.addView(blob(0xFF64748B.toInt(), Gravity.TOP or Gravity.START, 0.18f, 0.14f))
        bg.addView(blob(0xFF6366F1.toInt(), Gravity.BOTTOM or Gravity.END, -0.16f, -0.12f))
        bgPhoto = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f; scaleX = 1.15f; scaleY = 1.15f
            if (android.os.Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(36).toFloat(), dp(36).toFloat(), android.graphics.Shader.TileMode.CLAMP))
            setColorFilter(0x73000000) }
        bg.addView(bgPhoto, FrameLayout.LayoutParams(-1, -1))
        v.addView(bg, FrameLayout.LayoutParams(-1, -1))
        v.addView(View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0x0D000000, 0x8C000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), 0) }
        avatar = TextView(act).apply { setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        // squircle (web avatar-shape.ts, ~42% corner equivalent), clipped so the photo takes the same shape
        avatarBox = FrameLayout(act).apply { background = GradientDrawable().apply { cornerRadius = dp(47).toFloat(); setColor(0xFF64748B.toInt()) }; clipToOutline = true }
        photoView = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        avatarBox.addView(avatar, FrameLayout.LayoutParams(-1, -1)); avatarBox.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        name = TextView(act).apply { setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD); letterSpacing = -0.01f }
        status = TextView(act).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        val ringBox = FrameLayout(act).apply { clipChildren = false }
        rings.clear()
        repeat(3) { i -> val rv = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), 0x59FFFFFF) }; alpha = 0f }
            rings.add(rv); ringBox.addView(rv, FrameLayout.LayoutParams(dp(112), dp(112))) }
        ringBox.addView(avatarBox, FrameLayout.LayoutParams(dp(112), dp(112)))
        // 101e00a Avatar presence / later call-screen PresenceAvatar: 16dp + 3dp stage ring, bottom-right 13% inset
        presenceDot = View(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CallPresence.COLOR_ONLINE); setStroke(dp(3), CallPresence.RING) }
            visibility = View.GONE
        }
        ringBox.addView(presenceDot, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(4); bottomMargin = dp(4) })
        top.clipChildren = false; v.clipChildren = false
        top.addView(ringBox, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(12) })
        top.addView(name, LinearLayout.LayoutParams(-1, -2)); top.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        v.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { bottomMargin = dp(80) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER or Gravity.BOTTOM; setPadding(dp(16), 0, dp(16), 0) }
        bFirst = btn(false) { speakerOn = !speakerOn; actions.speaker(speakerOn); render() }
        bFlip = btn(false) { facing = if (facing == "user") "environment" else "user"; actions.camera(true, facing) { } }
        bVid = btn(false) {
            val want = !camOn
            if (!want) { camOn = false; actions.camera(false, facing) { }; render(); return@btn }
            // camera permission is requested here (toggle on), not up front; a denial leaves the button off
            actions.camera(true, facing) { ok -> if (ok) { camOn = true; render() } }
        }
        bMic = btn(false) { actions.pageCall(JSONObject().put("type", "call:mute").put("muted", !muted).toString()) }
        val end = btn(true) { actions.pageCall(JSONObject().put("type", "call:hangup").toString()) }
        end.label.text = "End call"; end.icon.setImageResource(R.drawable.ol_call_phone_off)
        row.addView(bFirst.box); row.addView(bFlip.box); row.addView(bVid.box); row.addView(bMic.box); row.addView(end.box)
        v.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(40) })
        this.row = row
        // minimize (chevron, first UI) — UI-only: collapses to the green top bar; does not hang up
        minBtn = FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1AFFFFFF) }
            setOnClickListener { setMinimized(true) }
            contentDescription = "Minimize call"
        }
        minBtn.addView(android.widget.ImageView(act).apply { setImageResource(R.drawable.ol_call_chevron_down); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        v.addView(minBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply { topMargin = dp(16); leftMargin = dp(12) })
        miniBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF10B981.toInt())
            visibility = View.GONE
            setOnClickListener { setMinimized(false) }
        }
        miniDot = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        miniLabel = TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniBar.addView(miniDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        miniBar.addView(miniLabel, LinearLayout.LayoutParams(-2, -2))
        v.addView(miniBar, FrameLayout.LayoutParams(-1, dp(28), Gravity.TOP))
        v.setOnClickListener { if (nativeVideo) pokeControls() }
        root.addView(v, FrameLayout.LayoutParams(-1, -1)); view = v
    }

    private lateinit var row: LinearLayout
    private lateinit var minBtn: FrameLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniDot: View
    private lateinit var miniLabel: TextView
    private var minimized = false
    private var showCtl = true
    private val hideCtl = Runnable { showCtl = false; view?.let { applyChrome(it) } }

    private fun setMinimized(on: Boolean) {
        minimized = on
        if (on) showCtl = true
        view?.let { applyChrome(it) }
    }

    private fun pokeControls() {
        showCtl = true
        ui.removeCallbacks(hideCtl)
        if (nativeVideo) ui.postDelayed(hideCtl, 4000)
        view?.let { applyChrome(it) }
    }

    private fun applyChrome(v: View) {
        val incomingOrEnded = state == NativeCallFlow.State.ENDED
        if (minimized && !incomingOrEnded) {
            miniBar.visibility = View.VISIBLE
            miniLabel.text = "${name.text}  ${status.text}  · Tap to return"
            row.visibility = View.GONE
            minBtn.visibility = View.GONE
            top.visibility = View.GONE
            pulseMiniDot()
            return
        }
        miniBar.visibility = View.GONE
        val hide = nativeVideo && !showCtl
        row.visibility = if (hide) View.GONE else View.VISIBLE
        minBtn.visibility = if (hide || incomingOrEnded) View.GONE else View.VISIBLE
        top.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        if (nativeVideo && showCtl) {
            ui.removeCallbacks(hideCtl)
            ui.postDelayed(hideCtl, 4000)
        }
    }

    private fun pulseMiniDot() {
        if (miniBar.visibility != View.VISIBLE) return
        miniDot.animate().alpha(0.35f).setDuration(600).withEndAction {
            miniDot.animate().alpha(1f).setDuration(600).withEndAction { pulseMiniDot() }.start()
        }.start()
    }

    private fun render() {
        val v = view ?: return
        v.setBackgroundColor(if (nativeVideo) Color.TRANSPARENT else 0xFF0B1220.toInt())
        avatarBox.visibility = if (nativeVideo) View.GONE else View.VISIBLE
        bg.visibility = if (nativeVideo) View.GONE else View.VISIBLE
        (avatarBox.parent as View).visibility = avatarBox.visibility
        val ringing = state == NativeCallFlow.State.WAITING
        rings.forEachIndexed { i, rv -> if (ringing && rv.tag == null) { rv.tag = 1; pulse(rv, i * 800L) } else if (!ringing) { rv.tag = null; rv.animate().cancel(); rv.alpha = 0f } }
        name.textSize = if (nativeVideo) 20f else 28f
        (top.layoutParams as FrameLayout.LayoutParams).apply { gravity = if (nativeVideo) Gravity.TOP else Gravity.CENTER; topMargin = if (nativeVideo) dp(64) else 0; bottomMargin = if (nativeVideo) 0 else dp(80) }
        top.requestLayout()
        if (photoView.alpha < 1f) avatar.text = initials(name.text?.toString().orEmpty())
        if (state != NativeCallFlow.State.ESTABLISHED) status.text = NativeCallFlow.statusText(state)
        // First UI always offers Video (audio→video upgrade). Flip is shown once the local camera is on.
        bFlip.box.visibility = if (camOn) View.VISIBLE else View.GONE
        bFlip.set("Flip", R.drawable.ol_call_refresh_cw)
        bFirst.set(if (speakerOn) "Speaker" else "Phone", if (speakerOn) R.drawable.ol_call_volume2 else R.drawable.ol_call_ear, active = speakerOn)
        bVid.box.visibility = View.VISIBLE
        bVid.set(if (camOn) "Stop video" else "Video", if (camOn) R.drawable.ol_call_video else R.drawable.ol_call_video_off, active = camOn)
        bMic.set(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ol_call_mic_off else R.drawable.ol_call_mic, active = muted)
        applyChrome(v)
    }

    private val presenceTick = object : Runnable {
        override fun run() {
            if (view == null) return
            actions.peekCallPresence { raw ->
                val t = CallPresence.rawFromJs(raw).ifBlank { raw?.trim().orEmpty() }
                if (t.isNotEmpty()) applyPresence(CallPresence.of(t))
                if (view != null) ui.postDelayed(this, 2000)
            }
        }
    }

    private fun startPresencePeek() {
        ui.removeCallbacks(presenceTick)
        ui.post(presenceTick)
    }

    private fun applyPresence(status: String?) {
        if (view == null || !::presenceDot.isInitialized) return
        val s = CallPresence.of(status)
        if (s == null) {
            presenceDot.visibility = View.GONE
            presenceDot.contentDescription = null
        } else {
            (presenceDot.background as GradientDrawable).apply { setColor(CallPresence.color(s)); setStroke(dp(3), CallPresence.RING) }
            presenceDot.visibility = View.VISIBLE
            presenceDot.contentDescription = s
        }
    }

    private fun pulse(rv: View, delay: Long) {
        if (rv.tag == null || view == null) return
        rv.scaleX = 1f; rv.scaleY = 1f; rv.alpha = 0.8f
        rv.animate().setStartDelay(delay).scaleX(1.9f).scaleY(1.9f).alpha(0f).setDuration(2400)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.6f, 0.35f, 1f)).withEndAction { pulse(rv, 0) }.start()
    }

    val showing: Boolean get() = view != null && !hidden
}
