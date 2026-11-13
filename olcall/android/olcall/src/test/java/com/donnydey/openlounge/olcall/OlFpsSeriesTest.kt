package com.donnydey.openlounge.olcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OlFpsSeriesTest {
    @Test fun batchesOfFive() {
        val f = OlFpsSeries()
        for (i in 1..4) assertNull(f.add(i, 30))
        val b = f.add(5, 30)!!
        assertEquals(listOf(1, 2, 3, 4, 5), b.first)
        assertEquals(listOf(30, 30, 30, 30, 30), b.second)
        f.add(9, 1)
        assertEquals(listOf(9), f.flush()!!.first)
        assertNull(f.flush())
    }

    @Test fun statsAndRuns() {
        val s = listOf(25, 15, 15, 15, 30, 10, 26)
        val st = OlFpsSeries.stats(s)!!
        assertEquals(7, st.n); assertEquals(10, st.min); assertEquals(30, st.max); assertEquals(15, st.p50)
        assertEquals(19.4, st.avg, 0.001)
        assertEquals(3, OlFpsSeries.longestBelow(s, 20))
        assertNull(OlFpsSeries.stats(emptyList()))
    }

    @Test fun summaryShape() {
        val f = OlFpsSeries()
        repeat(12) { f.add(12, 24) }
        val m = f.summary()
        assertEquals(12, m["inBelow20MaxRunSec"]); assertEquals(0, m["outBelow20MaxRunSec"])
        @Suppress("UNCHECKED_CAST") assertEquals(12, (m["in"] as Map<String, Any>)["p50"])
    }

    @Test fun capsRetainedSamples() {
        val f = OlFpsSeries(maxSamples = 3)
        repeat(10) { f.add(1, 1) }
        assertEquals(3, f.inSamples.size)
    }
}
