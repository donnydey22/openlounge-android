package com.donnydey.openlounge

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkJoinPolicyTest {
    @Test fun linkJoinStartsMicFgsBeforeConnect() {
        assertTrue(LinkJoinPolicy.micFgsBeforeConnect(JSONObject().put("op", "connect").put("link", true)))
    }

    @Test fun ringCallLeavesFgsToOutgoingAndAccept() {
        assertFalse(LinkJoinPolicy.micFgsBeforeConnect(JSONObject().put("op", "connect")))
        assertFalse(LinkJoinPolicy.micFgsBeforeConnect(JSONObject().put("op", "connect").put("link", false)))
    }
}
