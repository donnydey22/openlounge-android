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
        return choose(null, speakerRequested, available);
    }

    /**
     * Route picker (Oct 23): an explicit user pick (Speaker / Phone / a headset) wins while that device is available —
     * FaceTime/WhatsApp behaviour. MainActivity clears the pick when a device connects or disconnects, so automatic
     * routing (headset first) takes over again.
     */
    static Device choose(Device userPick, boolean speakerRequested, Collection<Device> available) {
        if (available == null || available.isEmpty()) return null;
        if (userPick != null && available.contains(userPick)) return userPick;
        // A call must not be split across an external microphone and handset speaker. Prefer communication-capable
        // Bluetooth, then USB/wired headsets, before the built-in controls.
        if (available.contains(Device.BLUETOOTH)) return Device.BLUETOOTH;
        if (available.contains(Device.USB)) return Device.USB;
        if (available.contains(Device.WIRED)) return Device.WIRED;
        if (speakerRequested && available.contains(Device.SPEAKER)) return Device.SPEAKER;
        if (available.contains(Device.EARPIECE)) return Device.EARPIECE;
        return available.contains(Device.SPEAKER) ? Device.SPEAKER : null;
    }

    /** Route kind string shared with the page (src/lib/calls/audioRoute.ts AudioRouteKind). */
    static String kind(Device d) {
        if (d == null) return "system";
        switch (d) {
            case SPEAKER: return "speaker";
            case EARPIECE: return "earpiece";
            case WIRED: return "wired";
            case USB: return "usb";
            default: return "bluetooth";
        }
    }

    static Device fromKind(String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "speaker": return Device.SPEAKER;
            case "earpiece": return Device.EARPIECE;
            case "wired": return Device.WIRED;
            case "usb": return Device.USB;
            case "bluetooth": return Device.BLUETOOTH;
            default: return null;
        }
    }

    /** Page route id: built-ins by kind, external devices by kind + product name (matches audioRoute.ts routeFromNative). */
    static String id(Device d, String name) {
        if (d == Device.SPEAKER || d == Device.EARPIECE) return kind(d);
        return kind(d) + ":" + (name == null ? "" : name);
    }

    static boolean usesLegacyBluetoothSco(Device device) {
        return device == Device.BLUETOOTH;
    }
}
