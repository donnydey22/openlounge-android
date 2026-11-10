package com.donnydey.openlounge

import kotlin.math.hypot
import kotlin.math.ln

/**
 * Self-view picture-in-picture placement, the Telegram rules (same math as iOS CallPip):
 *  - four corner slots, default bottom-right (Telegram-iOS 279f89e VideoContainerView.swift:237);
 *  - slot frame = corner of the inset area (VideoContainerView.swift:610-619);
 *  - insets 12 left/right, top 60 while controls show (0 hidden), bottom = control row + 12
 *    (PrivateCallScreen.swift:1013-1017);
 *  - on release the centre is projected along the fling velocity, pos - v / (1000 ln 0.99), and the PiP snaps to
 *    the nearest inset corner (VideoContainerView.swift:428-464).
 * Size and corner radius stay the first-design 100x150 / 18 (web 101e00a; Telegram-iOS also uses 18,
 * VideoContainerView.swift:771-776).
 */
object CallPip {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    const val WIDTH = 100
    const val HEIGHT = 150
    const val RADIUS = 18
    const val SIDE = 12
    const val TOP_CONTROLS = 60

    data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Telegram minimizedVideoInsets (dp or px, caller's unit). */
    fun insets(controlsShown: Boolean, safeTop: Float, rowReserve: Float, unit: Float): Insets = Insets(
        SIDE * unit,
        safeTop + if (controlsShown) TOP_CONTROLS * unit else 0f,
        SIDE * unit,
        (if (controlsShown) rowReserve else 0f) + SIDE * unit,
    )

    /** Top-left of the PiP in [corner]. */
    fun origin(corner: Corner, w: Float, h: Float, pw: Float, ph: Float, i: Insets): Pair<Float, Float> = when (corner) {
        Corner.TOP_LEFT -> i.left to i.top
        Corner.TOP_RIGHT -> (w - i.right - pw) to i.top
        Corner.BOTTOM_LEFT -> i.left to (h - i.bottom - ph)
        Corner.BOTTOM_RIGHT -> (w - i.right - pw) to (h - i.bottom - ph)
    }

    /** Released with centre (cx, cy) and velocity (vx, vy) per second: the corner it snaps to. */
    fun snap(cx: Float, cy: Float, vx: Float, vy: Float, w: Float, h: Float, i: Insets): Corner {
        val k = 1000.0 * ln(0.99)
        val ex = cx - vx / k
        val ey = cy - vy / k
        return Corner.values().minByOrNull { c ->
            val px = if (c == Corner.TOP_LEFT || c == Corner.BOTTOM_LEFT) i.left else w - i.right
            val py = if (c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT) i.top else h - i.bottom
            hypot(ex - px, ey - py)
        }!!
    }
}
