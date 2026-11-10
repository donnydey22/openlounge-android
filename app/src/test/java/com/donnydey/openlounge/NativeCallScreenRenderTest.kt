package com.donnydey.openlounge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the native call screen (Open Lounge web look, Telegram flow/placement) per state and checks the controls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NativeCallScreenRenderTest {
    private val sent = mutableListOf<String>()
    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView && v.visibility == View.VISIBLE && isShown(v)) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
    private fun isShown(v: View): Boolean { var p: View? = v; while (p != null) { if (p.visibility != View.VISIBLE) return false; p = p.parent as? View }; return true }
    private fun snap(root: FrameLayout, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        val b = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); root.draw(Canvas(b))
        File("build/ncu-shots").apply { mkdirs() }.resolve("$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun findClickable(v: View, label: String): View? {
        if (v is ViewGroup) { for (i in 0 until v.childCount) { val c = v.getChildAt(i); if (c is TextView && c.text == label && v.hasOnClickListeners()) return v; findClickable(c, label)?.let { return it } } }
        return null
    }
    private fun findDesc(v: View, d: String): View? {
        if (v.contentDescription == d) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findDesc(v.getChildAt(i), d)?.let { return it }
        return null
    }
    private fun discWidth(root: View, label: String): Int {
        val box = findClickable(root, label) ?: return -1
        if (box is ViewGroup && box.childCount > 0) return box.getChildAt(0).width
        return box.width
    }

    @Test fun audioCallFlow() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        // Stand-in for the real app (WebView) so minimized is a green bar over the app, not a blank stage.
        val app = android.widget.TextView(act).apply {
            text = "Open Lounge hallway"
            setBackgroundColor(0xFF111827.toInt())
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
        }
        root.addView(app, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val s = NativeCallScreen(act, root, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean { sent += detailJson; return true }
            override fun speaker(on: Boolean) {}
            override fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit) { done(true) }
        })
        s.onPageCall("call:outgoing", JSONObject().put("callId", "t1").put("name", "Ada Lovelace").put("video", false).put("presence", "online"))
        var t = texts(root); snap(root, "1-calling")
        assertTrue(t.toString(), t.containsAll(listOf("AL", "Ada Lovelace", "Calling…", "Phone", "Video", "Mute", "End call")))
        assertTrue("first UI offers Video on voice calls (audio→video upgrade)", t.contains("Video"))
        assertTrue("Flip stays hidden until the local camera is on", !t.contains("Flip"))
        assertTrue("online presence dot on the calling avatar", findDesc(root, "online") != null)
        val sizes = listOf("Phone", "Video", "Mute", "End call").map { discWidth(root, it) }
        assertTrue("even in-call discs: $sizes", sizes.all { it == sizes[0] && it > 0 })
        val expect = Math.round(56f * act.resources.displayMetrics.density)
        assertEquals("56dp in-call discs", expect, sizes[0])
        s.onPageCall("call:answering", JSONObject().put("callId", "t1"))
        t = texts(root); snap(root, "2-connecting")
        assertTrue(t.toString(), t.contains("Connecting…"))
        assertTrue("connecting keeps the same bottom icon row", t.containsAll(listOf("Phone", "Video", "Mute", "End call")))
        s.onPageCall("call:connected", JSONObject().put("callId", "t1"))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        t = texts(root); snap(root, "3-active"); assertTrue(t.toString(), t.any { Regex("^\\d+:\\d\\d$").matches(it) })
        findClickable(root, "Phone")!!.performClick(); t = texts(root); assertTrue(t.contains("Speaker")); snap(root, "4-speaker")
        findDesc(root, "Minimize call")!!.performClick()
        t = texts(root); assertTrue("minimized bar shows the peer and Tap to return", t.any { it.contains("Ada Lovelace") && it.contains("Tap to return") })
        assertTrue("controls hidden while minimized", !t.contains("End call"))
        assertTrue("real app stays visible under the bar (no blank stage)", t.contains("Open Lounge hallway"))
        snap(root, "5-minimized")
        fun findBar(v: View): View? {
            if (v is android.widget.TextView && v.text.toString().contains("Tap to return") && (v.parent as? View)?.hasOnClickListeners() == true) return v.parent as View
            if (v is ViewGroup) for (i in 0 until v.childCount) findBar(v.getChildAt(i))?.let { return it }
            return null
        }
        findBar(root)!!.performClick()
        t = texts(root); assertTrue("restored controls", t.contains("End call")); snap(root, "6-restored")
        findClickable(root, "End call")!!.performClick()
        assertEquals("call:hangup", JSONObject(sent.last()).getString("type"))
        s.onPageCall("call:ended", JSONObject().put("callId", "t1"))
        assertTrue("dismissed on ENDED", texts(root).none { it == "End call" })
    }
}
