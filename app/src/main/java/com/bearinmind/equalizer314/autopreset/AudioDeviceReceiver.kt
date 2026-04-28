package com.bearinmind.equalizer314.autopreset

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bearinmind.equalizer314.state.EqPreferencesManager

/**
 * Manifest-declared receiver for wired 3.5mm and USB audio device events.
 * Wakes briefly to update the pending preset in SharedPreferences and fires
 * a local broadcast so EqService can apply it live if it is running.
 *
 * Bluetooth events cannot be declared in the manifest on API 26+ and are
 * handled dynamically inside EqService instead.
 */
class AudioDeviceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = EqPreferencesManager(context)
        if (!AutoPresetManager.isEnabled(prefs)) return

        when (intent.action) {
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) { // plugged in
                    AutoPresetManager.onDeviceConnected(prefs, "wired_3.5mm", "Wired 3.5mm")
                    notifyService(context)
                }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null && isAudioDevice(device)) {
                    val id = AutoPresetManager.usbDeviceId(device)
                    val name = AutoPresetManager.usbDisplayName(device)
                    AutoPresetManager.onDeviceConnected(prefs, id, name)
                    notifyService(context)
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                // On detach we just clear the pending preset; EqService handles
                // the live state (active preset stays applied until next connect).
                prefs.saveAutoPresetPending(null)
            }
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        // USB audio class = 0x01
        if (device.deviceClass == 0x01) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 0x01) return true
        }
        return false
    }

    private fun notifyService(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(AutoPresetManager.ACTION_DEVICE_CHANGED))
    }
}
