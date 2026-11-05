package com.donnydey.openlounge;

import static org.junit.Assert.*;
import org.junit.Test;

public class NativeCallFlowTest {
    @Test public void mapsPageMessagesToTelegramStates() {
        assertEquals(NativeCallFlow.State.WAITING, NativeCallFlow.stateFor("call:outgoing"));
        assertEquals(NativeCallFlow.State.WAIT_INIT, NativeCallFlow.stateFor("call:answering"));
        assertEquals(NativeCallFlow.State.ESTABLISHED, NativeCallFlow.stateFor("call:connected"));
        assertEquals(NativeCallFlow.State.ENDED, NativeCallFlow.stateFor("call:ended"));
        assertNull(NativeCallFlow.stateFor("call:stats"));
        assertNull(NativeCallFlow.stateFor("call:muted"));
    }
    @Test public void statusAndTimer() {
        assertEquals("Calling…", NativeCallFlow.statusText(NativeCallFlow.State.WAITING));
        assertEquals("Connecting…", NativeCallFlow.statusText(NativeCallFlow.State.WAIT_INIT));
        assertEquals("", NativeCallFlow.statusText(NativeCallFlow.State.ESTABLISHED));
        assertEquals("0:07", NativeCallFlow.timer(7));
        assertEquals("12:05", NativeCallFlow.timer(725));
    }
    @Test public void firstSlotFollowsVoipFragment() {
        assertTrue(NativeCallFlow.firstButtonIsFlip(true));
        assertFalse(NativeCallFlow.firstButtonIsFlip(false));
    }
}
