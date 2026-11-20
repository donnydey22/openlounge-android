package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CallFgsPolicyTest {
    @Test public void preQ_noTypes() { assertEquals(0, CallFgsPolicy.types(28, true, true)); }
    @Test public void q_phoneOnly() { assertEquals(CallFgsPolicy.PHONE_CALL, CallFgsPolicy.types(29, true, true)); }
    @Test public void r_micWhenGranted() { assertEquals(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE, CallFgsPolicy.types(30, true, false)); }
    @Test public void noMicPermission() { assertEquals(CallFgsPolicy.PHONE_CALL, CallFgsPolicy.types(34, false, true)); }
    @Test public void u_backgroundStartNoMic() { assertEquals(CallFgsPolicy.PHONE_CALL, CallFgsPolicy.types(34, true, false)); }
    @Test public void u_visibleStartMic() { assertEquals(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE, CallFgsPolicy.types(35, true, true)); }
    @Test public void fallbackDropsMic() { assertEquals(CallFgsPolicy.PHONE_CALL, CallFgsPolicy.fallback(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE)); }
    @Test public void fallbackGivesUp() { assertEquals(0, CallFgsPolicy.fallback(CallFgsPolicy.PHONE_CALL)); }
    // background re-start of the running call FGS (call:connected / link rejoin while away) would drop the mic type
    @Test public void runningFgs_background_noRestart() { assertEquals(false, CallFgsPolicy.restart(true, false)); }
    @Test public void runningFgs_visible_restart() { assertEquals(true, CallFgsPolicy.restart(true, true)); }
    @Test public void noFgs_start() { assertEquals(true, CallFgsPolicy.restart(false, false)); assertEquals(true, CallFgsPolicy.restart(false, true)); }
    // t6107u keep the camera alive: camera type only with the camera on, and on 14+ only from a visible start
    @Test public void u_cameraOnVisible() { assertEquals(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE | CallFgsPolicy.CAMERA, CallFgsPolicy.types(34, true, true, true)); }
    @Test public void u_cameraOnBackground_none() { assertEquals(CallFgsPolicy.PHONE_CALL, CallFgsPolicy.types(34, true, false, true)); }
    @Test public void cameraOff_noCameraType() { assertEquals(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE, CallFgsPolicy.types(34, true, true, false)); }
    @Test public void r_cameraOn() { assertEquals(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE | CallFgsPolicy.CAMERA, CallFgsPolicy.types(30, true, false, true)); }
    @Test public void q_noCameraType() { assertEquals(CallFgsPolicy.PHONE_CALL, CallFgsPolicy.types(29, true, true, true)); }
    @Test public void fallbackDropsCameraFirst() { assertEquals(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE, CallFgsPolicy.fallback(CallFgsPolicy.PHONE_CALL | CallFgsPolicy.MICROPHONE | CallFgsPolicy.CAMERA)); }
}
