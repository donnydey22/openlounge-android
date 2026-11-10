package com.donnydey.openlounge.olcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OlVersionsTest {
    @Test fun picksNewestCommon() {
        // pinned tgcalls (Meta::Versions order is not guaranteed)
        assertEquals("13.0.0", OlVersions.pick(listOf("7.0.0", "8.0.0", "9.0.0", "11.0.0", "12.0.0", "13.0.0", "14.0.0")))
        // an older Android tgcalls without 12/13
        assertEquals("9.0.0", OlVersions.pick(listOf("2.7.7", "5.0.0", "7.0.0", "8.0.0", "9.0.0")))
    }

    @Test fun skipsVersionsTheServerDoesNotTerminate() {
        // 11.0.0 (ReferenceImpl) and 14.0.0 (CompatImpl) are PeerConnection engines the translator does not speak
        assertEquals("7.0.0", OlVersions.pick(listOf("7.0.0", "11.0.0", "14.0.0")))
    }

    @Test fun failsLoudlyWithNoCommonVersion() {
        assertThrows(IllegalStateException::class.java) { OlVersions.pick(listOf("5.0.0", "11.0.0")) }
    }

    @Test fun serverListMatchesGoMatrix() {
        // olcall/cmd/olcall-tgpeer -versions default
        assertEquals(listOf("13.0.0", "12.0.0", "9.0.0", "8.0.0", "7.0.0"), OlVersions.SERVER)
    }
}
