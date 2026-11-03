package com.donnydey.openlounge.pjsip

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Matrix harness: the real :callengine native engine (ol_sfu.c) on the emulator, connecting straight to a CI mediasoup
 *  PlainTransport. Params come from tests/pjsip-matrix/run-phone.mjs (PHONE=android) via `am instrument -e`. */
@RunWith(AndroidJUnit4::class)
class SfuLegTest {
    @Test fun audioLeg() {
        val a = InstrumentationRegistry.getArguments(); val ins = InstrumentationRegistry.getInstrumentation()
        val ip = a.getString("ip") ?: return // not invoked by the matrix runner -> no-op
        assertEquals(0, PjsipEngine.start())
        val r = PjsipEngine.nativeSfuAudioStart(ip, a.getString("port")!!.toInt(), a.getString("tx")!!, a.getString("rx")!!,
            a.getString("ssrc")!!.toLong(), a.getString("txPt")!!.toInt(), a.getString("rxPt")!!.toInt(), 440, true)
        val secs = a.getString("seconds")!!.toInt(); var sum = 0L; var n = 0; var max = 0
        if (r == 0) for (i in 0 until secs * 10) { Thread.sleep(100); val s = PjsipEngine.nativeSfuStats(); if (i > 20) { sum += s[3]; n++; if (s[3] > max) max = s[3] } }
        val s = if (r == 0) PjsipEngine.nativeSfuStats() else intArrayOf(0, 0, 0, 0)
        if (r == 0) PjsipEngine.nativeSfuStop()
        val json = if (r != 0) "{\"error\":$r}" else "{\"rxPkt\":${s[0]},\"rxLost\":${s[1]},\"txPkt\":${s[2]},\"avgLevel\":${if (n > 0) sum.toDouble() / n else 0.0},\"maxLevel\":$max}"
        ins.sendStatus(0, Bundle().apply { putString("olresult", json) })
        assertEquals(0, r)
    }
}
