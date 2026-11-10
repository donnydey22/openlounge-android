package com.donnydey.openlounge

import android.graphics.Typeface

/** Shared first-design call metrics (dp), the same numbers as iOS OLMetric / CallScreen. */
object CallChrome {
    const val TOP_BAR = 56          // minimize / close bar
    const val ROW_BOTTOM = 24       // bottom row inset
    const val ROW_HEIGHT = 76       // 56 disc + 6 gap + 14 label
    const val ROW_RESERVE = ROW_BOTTOM + ROW_HEIGHT
    const val FAIL_RESERVE = ROW_RESERVE  // failure keeps the row space empty so the avatar does not jump
    const val ROW_PAD = 16
    /** Centering the block between the bar and the row = lifting it (ROW_RESERVE - TOP_BAR) / 2 from the screen center. */
    const val CENTER_LIFT = (ROW_RESERVE - TOP_BAR) / 2

    /** 600 weight, like SwiftUI .semibold (falls back to medium before API 28). */
    @JvmStatic
    fun semibold(): Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/**
 * The page's own CallOverlay (openlounge.app) always renders under the native call screen. With the
 * native call UI on, hide every non-incoming state of it (outgoing, connecting, active, ended) so the
 * only call screen anyone sees is the first-design native one — including the few seconds after hang-up
 * and while minimized (the page's full-screen call view used to show under the green bar).
 * The DOM stays in place, so the read-only photo / presence peeks still work. Incoming stays on the
 * page until it posts call:incoming in the foreground (web change, not deployed).
 */
object CallWebOverlay {
    const val HIDE_JS = "(function(){try{var id='ol-native-call-ui';function add(){if(document.getElementById(id))return;" +
        "var s=document.createElement('style');s.id=id;s.textContent='[data-testid=\"call-screen\"]:not([data-ui=\"incoming\"])" +
        "{visibility:hidden!important;pointer-events:none!important}';(document.head||document.documentElement).appendChild(s);}" +
        "add();if(!window.__olHideCallUi){window.__olHideCallUi=1;new MutationObserver(add).observe(document.documentElement,{childList:true,subtree:true});}" +
        "}catch(e){}})()"
}
