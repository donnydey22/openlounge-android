package com.donnydey.openlounge;

import java.util.Collection;

/**
 * Pure route selection for an active voice/video call. External two-way devices take precedence because their
 * microphone and speaker are a matched pair; otherwise the in-call speaker toggle selects speaker or earpiece.
 * Android-specific device discovery stays in MainActivity so this policy remains JVM-testable.
 */
final class CallAudioRoute {
    enum Device { SPEAKER, EARPIECE, WIRED, USB, BLUETOOTH }

    private CallAudioRoute() {}

    static Device choose(boolean speakerRequested, Collection<Device> available) {
        if (available == null || available.isEmpty()) return null;
        // A call must not be split across an external microphone and handset speaker. Prefer communication-capable
        // Bluetooth, then USB/wired headsets, before the built-in controls.
        if (available.contains(Device.BLUETOOTH)) return Device.BLUETOOTH;
        if (available.contains(Device.USB)) return Device.USB;
        if (available.contains(Device.WIRED)) return Device.WIRED;
        if (speakerRequested && available.contains(Device.SPEAKER)) return Device.SPEAKER;
        if (available.contains(Device.EARPIECE)) return Device.EARPIECE;
        return available.contains(Device.SPEAKER) ? Device.SPEAKER : null;
    }

    static boolean usesLegacyBluetoothSco(Device device) {
        return device == Device.BLUETOOTH;
    }
}
