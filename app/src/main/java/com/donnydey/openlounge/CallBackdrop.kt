package com.donnydey.openlounge

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * First-design call backdrop (101e00a .bgPhoto, same parameters as iOS CallBackdrop):
 * the profile photo cover-fills the screen at 1.15×, Gaussian blur σ = 36 dp, saturation 1.3,
 * brightness −0.15, over the #0B1220 stage; the top→bottom shade (25% / 5% / 55% black) is a
 * separate view on top, as on iOS.
 *
 * This is a software pre-blur of a downscaled bitmap, not RenderEffect, so every Android
 * version (minSdk 24) and Robolectric paint the same pixels. Edges fade to the stage the way
 * SwiftUI's non-opaque blur does past the zoomed image.
 */
object CallBackdrop {
    const val BLUR_DP = 36f
    const val ZOOM = 1.15f
    const val SATURATION = 1.3f
    const val BRIGHTNESS = -0.15f
    const val STAGE = 0xFF0B1220.toInt()
    /** Working-space σ in px; the blurred bitmap is this coarse and upscaled with filtering. */
    private const val WORK_SIGMA = 9f

    private class Holder(val src: Bitmap) { var w = 0; var h = 0 }

    /** Paint [src] as the blurred backdrop of [view]; re-renders when the view size changes. */
    @JvmStatic
    fun attach(view: ImageView, src: Bitmap) {
        val first = view.tag !is Holder
        val hold = Holder(src)
        view.tag = hold
        view.scaleX = 1f; view.scaleY = 1f
        view.clearColorFilter()
        if (android.os.Build.VERSION.SDK_INT >= 31) view.setRenderEffect(null)
        view.scaleType = ImageView.ScaleType.FIT_XY
        if (first) view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val h = v.tag as? Holder ?: return@addOnLayoutChangeListener
            if (r - l > 0 && b - t > 0 && (h.w != r - l || h.h != b - t)) paint(v as ImageView, h, r - l, b - t)
        }
        val dm = view.resources.displayMetrics
        val p = view.parent as? View
        val w = view.width.takeIf { it > 0 } ?: p?.width?.takeIf { it > 0 } ?: dm.widthPixels
        val h = view.height.takeIf { it > 0 } ?: p?.height?.takeIf { it > 0 } ?: dm.heightPixels
        paint(view, hold, w, h)
    }

    private fun paint(view: ImageView, h: Holder, w: Int, hh: Int) {
        h.w = w; h.h = hh
        view.setImageBitmap(render(h.src, w, hh, view.resources.displayMetrics.density))
    }

    /** Backdrop for a [widthPx]×[heightPx] screen (returned smaller; draw it stretched with filtering). */
    @JvmStatic
    fun render(src: Bitmap, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val sigmaPx = BLUR_DP * density
        val k = maxOf(1f, sigmaPx / WORK_SIGMA)               // screen px per working px
        val w = maxOf(1, ceil(widthPx / k).toInt())
        val h = maxOf(1, ceil(heightPx / k).toInt())
        val sigma = sigmaPx / k
        val pad = ceil(sigma * 3).toInt()
        val bw = w + pad * 2
        val bh = h + pad * 2
        // Photo cover-fit to the screen, then zoomed 1.15× about the center (SwiftUI scaledToFill + scaleEffect).
        val sw = src.width; val sh = src.height
        val px = IntArray(sw * sh); src.getPixels(px, 0, sw, 0, 0, sw, sh)
        val cover = maxOf(widthPx.toFloat() / sw, heightPx.toFloat() / sh) * ZOOM // screen px per src px
        val imgW = sw * cover; val imgH = sh * cover
        val x0 = (widthPx - imgW) / 2f; val y0 = (heightPx - imgH) / 2f
        val r = FloatArray(bw * bh); val g = FloatArray(bw * bh); val b = FloatArray(bw * bh); val a = FloatArray(bw * bh)
        for (y in 0 until bh) {
            val sy = ((y - pad + 0.5f) * k - y0) / cover
            if (sy < 0f || sy >= sh) continue
            for (x in 0 until bw) {
                val sx = ((x - pad + 0.5f) * k - x0) / cover
                if (sx < 0f || sx >= sw) continue
                val c = sample(px, sw, sh, sx, sy)
                val i = y * bw + x
                r[i] = ((c shr 16) and 0xFF) / 255f; g[i] = ((c shr 8) and 0xFF) / 255f; b[i] = (c and 0xFF) / 255f; a[i] = 1f
            }
        }
        val kern = kernel(sigma)
        blur(r, g, b, a, bw, bh, kern)
        val sr = ((STAGE shr 16) and 0xFF) / 255f; val sg = ((STAGE shr 8) and 0xFF) / 255f; val sb = (STAGE and 0xFF) / 255f
        val s = SATURATION
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y + pad) * bw + (x + pad)
            val al = a[i].coerceIn(0f, 1f)
            var cr = 0f; var cg = 0f; var cb = 0f
            if (al > 1e-4f) {
                val ur = r[i] / al; val ug = g[i] / al; val ub = b[i] / al
                // CSS/SVG saturate() matrix, then additive brightness (SwiftUI .brightness).
                cr = ((0.213f + 0.787f * s) * ur + (0.715f - 0.715f * s) * ug + (0.072f - 0.072f * s) * ub + BRIGHTNESS).coerceIn(0f, 1f)
                cg = ((0.213f - 0.213f * s) * ur + (0.715f + 0.285f * s) * ug + (0.072f - 0.072f * s) * ub + BRIGHTNESS).coerceIn(0f, 1f)
                cb = ((0.213f - 0.213f * s) * ur + (0.715f - 0.715f * s) * ug + (0.072f + 0.928f * s) * ub + BRIGHTNESS).coerceIn(0f, 1f)
            }
            val fr = cr * al + sr * (1 - al); val fg = cg * al + sg * (1 - al); val fb = cb * al + sb * (1 - al)
            out[y * w + x] = (0xFF shl 24) or ((fr * 255f).roundToInt() shl 16) or ((fg * 255f).roundToInt() shl 8) or (fb * 255f).roundToInt()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun sample(px: IntArray, sw: Int, sh: Int, fx: Float, fy: Float): Int {
        // Box-average a small footprint so a large photo does not alias before the blur.
        val ix = fx.toInt().coerceIn(0, sw - 1); val iy = fy.toInt().coerceIn(0, sh - 1)
        val x1 = minOf(sw - 1, ix + 1); val y1 = minOf(sh - 1, iy + 1)
        var rr = 0; var gg = 0; var bb = 0
        for (c in intArrayOf(px[iy * sw + ix], px[iy * sw + x1], px[y1 * sw + ix], px[y1 * sw + x1])) {
            rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF
        }
        return (0xFF shl 24) or ((rr / 4) shl 16) or ((gg / 4) shl 8) or (bb / 4)
    }

    private fun kernel(sigma: Float): FloatArray {
        val rad = ceil(sigma * 3).toInt()
        val k = FloatArray(rad * 2 + 1)
        var sum = 0f
        for (i in -rad..rad) { val v = exp(-(i * i) / (2f * sigma * sigma)); k[i + rad] = v; sum += v }
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Separable Gaussian on premultiplied channels; outside the buffer counts as transparent. */
    private fun blur(r: FloatArray, g: FloatArray, b: FloatArray, a: FloatArray, w: Int, h: Int, k: FloatArray) {
        val rad = k.size / 2
        val chans = arrayOf(r, g, b, a)
        val tmp = FloatArray(maxOf(w, h))
        for (ch in chans) {
            for (y in 0 until h) {
                val o = y * w
                for (x in 0 until w) { var acc = 0f; for (j in -rad..rad) { val xx = x + j; if (xx in 0 until w) acc += ch[o + xx] * k[j + rad] }; tmp[x] = acc }
                System.arraycopy(tmp, 0, ch, o, w)
            }
            for (x in 0 until w) {
                for (y in 0 until h) { var acc = 0f; for (j in -rad..rad) { val yy = y + j; if (yy in 0 until h) acc += ch[yy * w + x] * k[j + rad] }; tmp[y] = acc }
                for (y in 0 until h) ch[y * w + x] = tmp[y]
            }
        }
    }
}
