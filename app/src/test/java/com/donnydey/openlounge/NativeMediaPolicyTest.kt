package com.donnydey.openlounge

import com.donnydey.openlounge.NativeMediaPolicy.ConnectAction.ATTACH
import com.donnydey.openlounge.NativeMediaPolicy.ConnectAction.NEW_SESSION
import com.donnydey.openlounge.NativeMediaPolicy.ConnectAction.REBUILD_IN_PLACE
import com.donnydey.openlounge.NativeMediaPolicy.RecoveryStep.ICE_RESTART
import com.donnydey.openlounge.NativeMediaPolicy.RecoveryStep.REBUILD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NativeCallMedia decisions (connect / recovery ladder / transport health), iOS 071d489 parity. */
class NativeMediaPolicyTest {
    @Test fun reloadOnSameCallAndPathReattaches() =
        assertEquals(ATTACH, NativeMediaPolicy.connectAction(true, "c1", false, "c1", false, rebuild = false))

    @Test fun rebuildFlagBuildsFreshTransportsInsteadOfAttaching() =
        assertEquals(REBUILD_IN_PLACE, NativeMediaPolicy.connectAction(true, "c1", false, "c1", false, rebuild = true))

    @Test fun pathChangeOrOtherCallOrNoSessionIsANewSession() {
        assertEquals(NEW_SESSION, NativeMediaPolicy.connectAction(true, "c1", false, "c1", true, false))
        assertEquals(NEW_SESSION, NativeMediaPolicy.connectAction(true, "c1", false, "c2", false, true))
        assertEquals(NEW_SESSION, NativeMediaPolicy.connectAction(false, "c1", false, "c1", false, true))
        assertEquals(NEW_SESSION, NativeMediaPolicy.connectAction(false, null, false, "c1", false, false))
    }

    @Test fun ladderIsTwoIceRestartsThenRebuildsForever() {
        assertEquals(ICE_RESTART, NativeMediaPolicy.recoveryStep(1))
        assertEquals(ICE_RESTART, NativeMediaPolicy.recoveryStep(2))
        for (a in 3..500) assertEquals(REBUILD, NativeMediaPolicy.recoveryStep(a))
    }

    @Test fun failedSkipsStraightToRebuildDisconnectedTriesIceFirst() {
        assertEquals(0, NativeMediaPolicy.startAttempt("disconnected"))
        assertEquals(REBUILD, NativeMediaPolicy.recoveryStep(NativeMediaPolicy.startAttempt("failed") + 1))
        assertEquals(REBUILD, NativeMediaPolicy.recoveryStep(NativeMediaPolicy.startAttempt("closed") + 1))
    }

    @Test fun backoffGrowsAndCapsAt15sNeverGivesUp() {
        assertEquals(500L, NativeMediaPolicy.backoffMs(0))
        assertEquals(1_000L, NativeMediaPolicy.backoffMs(1))
        assertEquals(15_000L, NativeMediaPolicy.backoffMs(5))
        assertEquals(15_000L, NativeMediaPolicy.backoffMs(10_000))
        assertEquals(500L, NativeMediaPolicy.backoffMs(-3))
    }

    @Test fun transportsUpNeedsSendAndRecvOnceReceiving() {
        assertTrue(NativeMediaPolicy.transportsUp("connected", "new", hasConsumers = false))
        assertFalse(NativeMediaPolicy.transportsUp("connected", "checking", hasConsumers = true))
        assertTrue(NativeMediaPolicy.transportsUp("completed", "connected", hasConsumers = true))
        assertFalse(NativeMediaPolicy.transportsUp(null, "connected", hasConsumers = true))
        assertFalse(NativeMediaPolicy.transportsUp("disconnected", "connected", hasConsumers = false))
    }

    @Test fun lossStates() {
        listOf("disconnected", "failed", "closed").forEach { assertTrue(it, NativeMediaPolicy.isLoss(it)) }
        listOf("new", "checking", "connected", "completed").forEach { assertFalse(it, NativeMediaPolicy.isLoss(it)) }
    }

    @Test fun liveKitOnlyOverTls() {
        assertTrue(NativeMediaPolicy.validLiveKitUrl("wss://lk.example"))
        assertTrue(NativeMediaPolicy.validLiveKitUrl("https://lk.example"))
        assertFalse(NativeMediaPolicy.validLiveKitUrl("ws://lk.example"))
        assertFalse(NativeMediaPolicy.validLiveKitUrl("javascript:alert(1)"))
        assertFalse(NativeMediaPolicy.validLiveKitUrl(null))
    }
}
