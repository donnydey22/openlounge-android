package com.donnydey.openlounge

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallAvatarTest {
    @Test fun circleCornersAreTransparentAndCenterIsCoverCropped() {
        val src = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        val out = CallAvatar.circle(src, 112)
        assertEquals(112, out.width)
        assertTrue("top-left corner is transparent", Color.alpha(out.getPixel(0, 0)) < 16)
        assertTrue("top-right corner is transparent", Color.alpha(out.getPixel(111, 0)) < 16)
        assertTrue("bottom-left corner is transparent", Color.alpha(out.getPixel(0, 111)) < 16)
        assertTrue("bottom-right corner is transparent", Color.alpha(out.getPixel(111, 111)) < 16)
        assertTrue("center is the cover-cropped photo", Color.alpha(out.getPixel(56, 56)) > 200)
        assertEquals(0xFFFF0000.toInt(), out.getPixel(56, 56))
        assertTrue("top edge beside center is transparent (circle; a squircle would still be opaque here)", Color.alpha(out.getPixel(47, 0)) < 16)
    }
}
