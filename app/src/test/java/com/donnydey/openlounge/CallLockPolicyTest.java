package com.donnydey.openlounge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallLockPolicyTest {
    @Test public void visibleOnlyWhenForegroundAndUnlocked() {
        assertTrue(CallLockPolicy.shouldReportVisible(true, false));
        assertFalse(CallLockPolicy.shouldReportVisible(true, true));
        assertFalse(CallLockPolicy.shouldReportVisible(false, false));
        assertFalse(CallLockPolicy.shouldReportVisible(false, true));
    }

    @Test public void callAcceptPaths() {
        assertFalse(CallLockPolicy.isCallAcceptPath(null));
        assertFalse(CallLockPolicy.isCallAcceptPath("/messages/u_1"));
        assertTrue(CallLockPolicy.isCallAcceptPath("/call/abc123"));
        assertTrue(CallLockPolicy.isCallAcceptPath("/call/abc123?accept=1"));
        assertTrue(CallLockPolicy.isCallAcceptPath("/call/abc123?foo=1&accept=1"));
        assertFalse(CallLockPolicy.isCallAcceptPath("/callback"));
    }
}
