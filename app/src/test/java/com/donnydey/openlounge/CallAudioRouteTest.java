package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

public class CallAudioRouteTest {
    @Test public void noDevicesHasNoForcedRoute() {
        assertNull(CallAudioRoute.choose(true, EnumSet.noneOf(CallAudioRoute.Device.class)));
    }

    @Test public void builtInToggleSelectsSpeakerOrEarpiece() {
        EnumSet<CallAudioRoute.Device> builtIn = EnumSet.of(CallAudioRoute.Device.SPEAKER, CallAudioRoute.Device.EARPIECE);
        assertEquals(CallAudioRoute.Device.SPEAKER, CallAudioRoute.choose(true, builtIn));
        assertEquals(CallAudioRoute.Device.EARPIECE, CallAudioRoute.choose(false, builtIn));
    }

    @Test public void externalTwoWayDevicesTakePriorityAndBluetoothUsesLegacySco() {
        EnumSet<CallAudioRoute.Device> all = EnumSet.of(CallAudioRoute.Device.SPEAKER, CallAudioRoute.Device.EARPIECE,
                CallAudioRoute.Device.WIRED, CallAudioRoute.Device.USB, CallAudioRoute.Device.BLUETOOTH);
        assertEquals(CallAudioRoute.Device.BLUETOOTH, CallAudioRoute.choose(true, all));
        assertEquals(CallAudioRoute.Device.BLUETOOTH, CallAudioRoute.choose(false, all));
        assertTrue(CallAudioRoute.usesLegacyBluetoothSco(CallAudioRoute.Device.BLUETOOTH));
        assertFalse(CallAudioRoute.usesLegacyBluetoothSco(CallAudioRoute.Device.WIRED));
    }

    @Test public void usbAndWiredFallbacksPreserveDuplexRouting() {
        assertEquals(CallAudioRoute.Device.USB, CallAudioRoute.choose(true,
                EnumSet.of(CallAudioRoute.Device.SPEAKER, CallAudioRoute.Device.USB)));
        assertEquals(CallAudioRoute.Device.WIRED, CallAudioRoute.choose(false,
                EnumSet.of(CallAudioRoute.Device.EARPIECE, CallAudioRoute.Device.WIRED)));
    }
}
