package com.donnydey.openlounge.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Clean-room device inventory (oracle: Signal SignalAudioManager / FullSignalAudioManagerApi31 behavior, RingRTC 2.74.0).
 * Enumerates real input and output capabilities with OS device IDs; never assumes index 0; output-only headphones are
 * never treated as microphones; no model-specific exceptions. Hotplug reconciles and preserves a valid selection.
 */
class CallAudioDevices(ctx: Context, private val handler: Handler) {
    enum class Kind { EARPIECE, SPEAKER, WIRED_HEADSET, WIRED_HEADPHONES, USB, BLUETOOTH_SCO, BLE, HEARING_AID, OTHER }
    data class Device(val id: Int, val kind: Kind, val name: String, val isInput: Boolean, val isOutput: Boolean,
                      val sampleRates: IntArray, val channelCounts: IntArray)

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var inputs: List<Device> = emptyList(); private set
    @Volatile var outputs: List<Device> = emptyList(); private set
    /** Explicit user selection (output kind); null = policy default. */
    @Volatile var userPick: Kind? = null
    var onChanged: (() -> Unit)? = null

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            refresh(); if (userPick != null && outputs.none { it.kind == userPick }) userPick = null
        }
    }

    fun start() { refresh(); am.registerAudioDeviceCallback(cb, handler) }
    fun stop() { runCatching { am.unregisterAudioDeviceCallback(cb) }; userPick = null }

    fun refresh() {
        inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { map(it, input = true) }
        outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { map(it, input = false) }
        onChanged?.invoke()
    }

    private fun map(d: AudioDeviceInfo, input: Boolean): Device? {
        val k = kindOf(d.type) ?: return null
        if (input && k == Kind.WIRED_HEADPHONES) return null // headphones have no mic
        return Device(d.id, k, d.productName?.toString() ?: "", d.isSource, d.isSink, d.sampleRates, d.channelCounts)
    }

    /** Preferred output for a call: explicit pick > BT > wired/USB > earpiece (or speaker for video). */
    fun choose(video: Boolean): Device? {
        userPick?.let { p -> outputs.firstOrNull { it.kind == p }?.let { return it } }
        val order = listOf(Kind.BLUETOOTH_SCO, Kind.BLE, Kind.HEARING_AID, Kind.WIRED_HEADSET, Kind.USB, Kind.WIRED_HEADPHONES) +
            if (video) listOf(Kind.SPEAKER, Kind.EARPIECE) else listOf(Kind.EARPIECE, Kind.SPEAKER)
        for (k in order) outputs.firstOrNull { it.kind == k }?.let { return it }
        return outputs.firstOrNull()
    }

    /** API31+: communication devices are a distinct list; legacy API26–30 uses SCO/speakerphone. */
    fun communicationDevices(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices else emptyList()

    companion object {
        @JvmStatic fun kindOf(type: Int): Kind? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Kind.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> Kind.EARPIECE // built-in mic pairs with earpiece/speaker
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.WIRED_HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Kind.WIRED_HEADPHONES
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> Kind.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Kind.BLUETOOTH_SCO
            26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */ -> Kind.BLE
            AudioDeviceInfo.TYPE_HEARING_AID -> Kind.HEARING_AID
            else -> null
        }
    }
}
