package com.bearinmind.equalizer314.autopreset

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Build
import com.bearinmind.equalizer314.state.EqPreferencesManager

/**
 * Shared logic for loading and persisting the Auto Preset device list.
 * Called from AudioDeviceReceiver (wired/USB) and EqService (Bluetooth).
 */
object AutoPresetManager {

    const val ACTION_DEVICE_CHANGED = "com.bearinmind.equalizer314.AUTO_PRESET_DEVICE_CHANGED"

    fun isEnabled(prefs: EqPreferencesManager): Boolean = prefs.getAutoPresetEnabled()

    fun getDevices(prefs: EqPreferencesManager): MutableList<AutoPresetDevice> {
        val stored = prefs.getAutoPresetDevices()?.toAutoPresetDevices()?.toMutableList()
        if (stored != null) return stored
        // First enable — seed the three always-present singletons
        val seeded = AutoPresetDevice.seeded().toMutableList()
        prefs.saveAutoPresetDevices(seeded.toJsonString())
        return seeded
    }

    fun saveDevices(prefs: EqPreferencesManager, devices: List<AutoPresetDevice>) {
        prefs.saveAutoPresetDevices(devices.toJsonString())
    }

    /**
     * Called when a device connects. Finds or creates its entry, writes the
     * pending preset key (as "ACTION:name"), and returns (action, name) to
     * apply — or null when no preset should be applied (Flat / hidden / unset).
     */
    fun onDeviceConnected(
        prefs: EqPreferencesManager,
        deviceId: String,
        displayName: String,
    ): Pair<PresetAction, String>? {
        val devices = getDevices(prefs)
        val existing = devices.indexOfFirst { it.id == deviceId }
        val device = if (existing >= 0) {
            devices[existing]
        } else {
            val type = typeFromId(deviceId)
            val new = AutoPresetDevice(deviceId, type, displayName)
            devices.add(new)
            saveDevices(prefs, devices)
            new
        }

        if (device.hidden) {
            prefs.clearAutoPresetPending()
            prefs.saveAutoPresetPendingDeviceId(null)
            return null
        }

        val result: Pair<PresetAction, String>? = when (device.presetAction) {
            PresetAction.FLAT, PresetAction.PROMPT -> null
            PresetAction.AUTOEQ, PresetAction.IMPORT ->
                device.presetName.takeIf { it.isNotBlank() }?.let { PresetAction.IMPORT to it }
            PresetAction.SNAPSHOT ->
                device.presetName.takeIf { it.isNotBlank() }?.let { PresetAction.SNAPSHOT to it }
        }

        if (result != null) {
            prefs.saveAutoPresetPending(result.first.name, result.second)
            prefs.saveAutoPresetPendingDeviceId(deviceId)
        } else {
            prefs.clearAutoPresetPending()
            prefs.saveAutoPresetPendingDeviceId(null)
        }
        return result
    }

    fun usbDeviceId(device: UsbDevice): String {
        val product = device.productName?.trim() ?: ""
        return "usb:${device.vendorId}:${device.productId}:$product"
    }

    fun usbDisplayName(device: UsbDevice): String =
        device.productName?.trim()?.takeIf { it.isNotBlank() } ?: "USB DAC"

    fun btDeviceId(address: String): String = "bt:$address"

    fun btDisplayName(context: Context, address: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                adapter?.getRemoteDevice(address)?.name ?: address
            } else {
                @Suppress("DEPRECATION")
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    ?.getRemoteDevice(address)?.name ?: address
            }
        } catch (_: Exception) { address }
    }

    private fun typeFromId(id: String): DeviceType = when {
        id.startsWith("usb:") -> DeviceType.USB
        id.startsWith("bt:") -> DeviceType.BLUETOOTH
        id == "wired_3.5mm" -> DeviceType.WIRED
        id == "usbc_audio" -> DeviceType.USBC
        else -> DeviceType.SPEAKER
    }
}
