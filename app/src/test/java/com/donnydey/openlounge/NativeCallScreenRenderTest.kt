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

    @Test fun audioCallFlow() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        val s = NativeCallScreen(act, root, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean { sent += detailJson; return true }
            override fun speaker(on: Boolean) {}
            override fun camera(on: Boolean, facing: String) {}
        })
        s.onPageCall("call:outgoing", JSONObject().put("callId", "t1").put("name", "Ada Lovelace").put("video", false))
        var t = texts(root); snap(root, "1-calling")
        assertTrue(t.toString(), t.containsAll(listOf("AL", "Ada Lovelace", "Calling…", "Phone", "Mute", "End call")))
        assertTrue("no Video button on voice calls", !t.contains("Video"))
        s.onPageCall("call:answering", JSONObject().put("callId", "t1"))
        t = texts(root); snap(root, "2-connecting"); assertTrue(t.toString(), t.contains("Connecting…"))
        s.onPageCall("call:connected", JSONObject().put("callId", "t1"))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        t = texts(root); snap(root, "3-active"); assertTrue(t.toString(), t.any { Regex("^\\d+:\\d\\d$").matches(it) })
        findClickable(root, "Phone")!!.performClick(); t = texts(root); assertTrue(t.contains("Speaker")); snap(root, "4-speaker")
        findClickable(root, "End call")!!.performClick()
        assertEquals("call:hangup", JSONObject(sent.last()).getString("type"))
        s.onPageCall("call:ended", JSONObject().put("callId", "t1"))
        assertTrue("dismissed on ENDED", texts(root).none { it == "End call" })
    }
}
