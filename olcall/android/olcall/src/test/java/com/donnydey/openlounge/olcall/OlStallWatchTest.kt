package com.donnydey.openlounge.olcall

import org.junit.Assert.assertEquals
import org.junit.Test

class OlStallWatchTest {
    @Test fun firstThenSecondThenRecovered() {
        val w = OlStallWatch()
        assertEquals(OlStallWatch.Action.None, w.tick(0, 5, true))
        assertEquals(OlStallWatch.Action.None, w.tick(1000, 0, true))
        assertEquals(OlStallWatch.Action.First(2000), w.tick(2000, 0, true))
        assertEquals(OlStallWatch.Action.None, w.tick(3000, 0, true))
        assertEquals(OlStallWatch.Action.First(4000), w.tick(4000, 0, true))
        assertEquals(OlStallWatch.Action.Second(5000), w.tick(5000, 0, true))
        assertEquals(OlStallWatch.Action.First(6000), w.tick(6000, 0, true))
        assertEquals(OlStallWatch.Action.Recovered(7000), w.tick(7000, 2, true))
        assertEquals(1, w.stalls)
        assertEquals(1, w.seconds)
    }

    @Test fun notWatchingIsNeverAStall() {
        val w = OlStallWatch()
        w.tick(0, 5, true)
        for (t in 1..20) assertEquals(OlStallWatch.Action.None, w.tick(t * 1000L, 0, false))
        assertEquals(OlStallWatch.Action.None, w.tick(21_000, 0, true)) // re-arms
        assertEquals(OlStallWatch.Action.First(2000), w.tick(23_000, 0, true))
    }

    @Test fun secondIsCapped() {
        val w = OlStallWatch()
        w.tick(0, 1, true)
        var n = 0
        for (t in 1..120) if (w.tick(t * 1000L, 0, true) is OlStallWatch.Action.Second) n++
        assertEquals(2, n)
    }
}
