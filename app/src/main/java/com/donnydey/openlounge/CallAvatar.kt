package com.donnydey.openlounge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

/**
 * Call-screen avatar: 112dp circle (Donny's live iPhone design, Nov 2026), cover-cropped.
 * Robolectric's clipToOutline often no-ops, so the bitmap itself is clipped.
 */
object CallAvatar {
    const val SIZE_DP = 112
    const val CORNER_DP = 47

    @JvmStatic
    fun cornerPx(sizePx: Int): Float = sizePx * CORNER_DP / SIZE_DP.toFloat()

    @JvmStatic
    fun applyOutline(view: View, cornerPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                // Avatars are circles (the cornerPx argument is kept so existing callers compile).
                outline.setOval(0, 0, v.width.coerceAtLeast(0), v.height.coerceAtLeast(0))
            }
        }
        view.clipToOutline = true
    }

    /** Center-crop [src] into a [sizePx] squircle with transparent corners. */
    @JvmStatic
    fun squircle(src: Bitmap, sizePx: Int, cornerPx: Float = cornerPx(sizePx)): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val scale = maxOf(sizePx.toFloat() / src.width, sizePx.toFloat() / src.height)
        val dx = (sizePx - src.width * scale) / 2f
        val dy = (sizePx - src.height * scale) / 2f
        val r = cornerPx.coerceAtMost(sizePx / 2f)
        val r2 = r * r
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                val xf = x + 0.5f
                val yf = y + 0.5f
                val inside = when {
                    xf >= r && xf <= sizePx - r -> yf >= 0f && yf <= sizePx
                    yf >= r && yf <= sizePx - r -> xf >= 0f && xf <= sizePx
                    else -> {
                        val cx = if (xf < r) r else sizePx - r
                        val cy = if (yf < r) r else sizePx - r
                        val ddx = xf - cx
                        val ddy = yf - cy
                        ddx * ddx + ddy * ddy <= r2
                    }
                }
                if (inside) {
                    val sx = ((xf - dx) / scale).toInt().coerceIn(0, src.width - 1)
                    val sy = ((yf - dy) / scale).toInt().coerceIn(0, src.height - 1)
                    out.setPixel(x, y, src.getPixel(sx, sy))
                }
            }
        }
        return out
    }

    /** Center-crop [src] into a [sizePx] circle with transparent corners. */
    @JvmStatic
    fun circle(src: Bitmap, sizePx: Int): Bitmap = squircle(src, sizePx, sizePx / 2f)

    /** Cover-crop [src] into a rounded rectangle (used for the 100×150 PiP, 18dp corners). */
    @JvmStatic
    fun rounded(src: Bitmap, widthPx: Int, heightPx: Int, cornerPx: Float): Bitmap {
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val scale = maxOf(widthPx.toFloat() / src.width, heightPx.toFloat() / src.height)
        val dx = (widthPx - src.width * scale) / 2f
        val dy = (heightPx - src.height * scale) / 2f
        val r = cornerPx.coerceAtMost(minOf(widthPx, heightPx) / 2f)
        val r2 = r * r
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                val xf = x + 0.5f
                val yf = y + 0.5f
                val inside = when {
                    xf >= r && xf <= widthPx - r -> yf >= 0f && yf <= heightPx
                    yf >= r && yf <= heightPx - r -> xf >= 0f && xf <= widthPx
                    else -> {
                        val cx = if (xf < r) r else widthPx - r
                        val cy = if (yf < r) r else heightPx - r
                        val ddx = xf - cx
                        val ddy = yf - cy
                        ddx * ddx + ddy * ddy <= r2
                    }
                }
                if (inside) {
                    val sx = ((xf - dx) / scale).toInt().coerceIn(0, src.width - 1)
                    val sy = ((yf - dy) / scale).toInt().coerceIn(0, src.height - 1)
                    out.setPixel(x, y, src.getPixel(sx, sy))
                }
            }
        }
        return out
    }

    @JvmStatic
    fun setPhoto(view: ImageView, src: Bitmap) {
        val lp = view.layoutParams?.width ?: 0
        val size = when {
            view.width > 0 -> view.width
            lp > 0 -> lp
            view.measuredWidth > 0 -> view.measuredWidth
            else -> src.width
        }
        view.setImageBitmap(circle(src, size))
        view.scaleType = ImageView.ScaleType.FIT_XY
        view.alpha = 1f
        applyOutline(view, size / 2f)
    }
}
