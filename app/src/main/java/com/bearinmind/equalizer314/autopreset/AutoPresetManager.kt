package com.bearinmind.equalizer314.autopreset

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.os.Build
import com.bearinmind.equalizer314.state.EqPreferencesManager

/**
 * Shared logic for loading and persisting the Auto Preset device list.
 * Also owns device-detection helpers used by both EqService and MainActivity.
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

    /** Ensures [deviceId] exists in the device list without writing a pending preset. */
    fun registerDevice(prefs: EqPreferencesManager, deviceId: String, displayName: String) {
        val devices = getDevices(prefs)
        if (devices.none { it.id == deviceId }) {
            devices.add(AutoPresetDevice(deviceId, typeFromId(deviceId), displayName))
            saveDevices(prefs, devices)
        }
    }

    /**
     * Called when a device becomes the active output. Finds or creates its entry,
     * atomically writes the pending preset key, and returns (action, name) to
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
            val new = AutoPresetDevice(deviceId, typeFromId(deviceId), displayName)
            devices.add(new)
            saveDevices(prefs, devices)
            new
        }

        if (device.hidden) {
            prefs.clearAutoPresetPendingFull()
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
            prefs.saveAutoPresetPendingFull(result.first.name, result.second, deviceId)
        } else {
            prefs.clearAutoPresetPendingFull()
        }
        return result
    }

    /**
     * Maps an [AudioDeviceInfo] to an (id, displayName) pair. Returns null for
     * types that cannot be identified (e.g. built-in mic, unknown peripherals).
     * USB audio is resolved against [UsbManager] to obtain VID/PID.
     */
    fun deviceInfoToIdAndName(context: Context, info: AudioDeviceInfo): Pair<String, String>? {
        return when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                val address = try { info.address?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                val productName = info.productName?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val id = if (!address.isNullOrBlank()) btDeviceId(address)
                          else productName?.let { "bt_named:$it" } ?: return null
                id to (productName ?: address ?: "Bluetooth Device")
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_3.5mm" to "Wired 3.5mm"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> {
                val productName = info.productName?.toString()?.trim()
                resolveUsbDevice(context, productName)
            }
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker" to "Phone Speaker"
            else -> null
        }
    }

    /**
     * Returns (id, displayName) for the highest-priority currently connected
     * output device: BT > USB audio > wired > built-in speaker.
     */
    fun pickActiveDevice(context: Context, outputs: Array<AudioDeviceInfo>): Pair<String, String>? {
        // 1. Bluetooth — highest priority for media audio
        outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }?.let { return deviceInfoToIdAndName(context, it) }

        // 2. USB audio — matched via UsbManager to get VID/PID
        outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }?.let { info ->
            val productName = info.productName?.toString()?.trim()
            resolveUsbDevice(context, productName)?.let { return it }
        }

        // 3. Wired headphones / headset
        if (outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }) return "wired_3.5mm" to "Wired 3.5mm"

        // 4. Built-in speaker — always present as fallback
        if (outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
            return "speaker" to "Phone Speaker"
        }

        return null
    }

    private fun resolveUsbDevice(context: Context, productName: String?): Pair<String, String>? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val match = usbManager.deviceList.values.firstOrNull { ud ->
            isAudioUsbDevice(ud) &&
            (productName == null || ud.productName?.trim() == productName)
        } ?: return null
        return usbDeviceId(match) to usbDisplayName(match)
    }

    private fun isAudioUsbDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 0x01) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 0x01) return true
        }
        return false
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
