package com.bearinmind.equalizer314.audio

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.app.NotificationCompat
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.autopreset.AutoPresetManager
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager

class EqService : Service() {

    companion object {
        private const val TAG = "EqService"
        private const val CHANNEL_ID = "eq_service_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.bearinmind.equalizer314.STOP_EQ"
        const val ACTION_EQ_STOPPED = "com.bearinmind.equalizer314.EQ_STOPPED"

        fun start(context: Context) {
            val intent = Intent(context, EqService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EqService::class.java))
        }
    }

    val dynamicsManager = DynamicsProcessingManager()
    private val binder = EqBinder()
    private lateinit var eqPrefs: EqPreferencesManager

    // Volume change listener
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotification()
        }
    }

    // Auto Preset — local broadcast from AudioDeviceReceiver (USB manifest receiver)
    private val autoPresetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyPendingAutoPreset()
        }
    }

    // Auto Preset — covers wired and Bluetooth device connects when the
    // service is running. ACTION_HEADSET_PLUG cannot be received by manifest
    // receivers on API 26+, and Bluetooth implicit broadcasts are also
    // restricted — AudioDeviceCallback handles both correctly.
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (!AutoPresetManager.isEnabled(eqPrefs)) return
            var anyRegistered = false
            for (info in addedDevices) {
                val (id, name) = deviceInfoToIdAndName(info) ?: continue
                AutoPresetManager.onDeviceConnected(eqPrefs, id, name)
                anyRegistered = true
            }
            if (anyRegistered) applyPendingAutoPreset()
        }
    }

    private fun deviceInfoToIdAndName(info: AudioDeviceInfo): Pair<String, String>? {
        return when (info.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_3.5mm" to "Wired 3.5mm"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                val address = try { info.address?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                val productName = info.productName?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val id = if (!address.isNullOrBlank()) AutoPresetManager.btDeviceId(address)
                          else productName?.let { "bt_named:$it" } ?: return null
                val name = productName ?: address ?: "Bluetooth Device"
                id to name
            }
            // USB devices are handled by the manifest AudioDeviceReceiver which
            // has access to VID/PID via UsbDevice. Skip here to avoid creating
            // a duplicate entry with a different key.
            else -> null
        }
    }

    private fun applyPendingAutoPreset() {
        val presetName = eqPrefs.getAutoPresetPending() ?: return
        eqPrefs.saveAutoPresetPending(null)
        val rawText = eqPrefs.getImportedPresetText(presetName) ?: return
        try {
            val profile = com.bearinmind.equalizer314.autoeq.AutoEqParser.parse(rawText) ?: return
            val eq = ParametricEqualizer()
            eq.clearBands()
            for (f in profile.filters) {
                val filterType = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(f.filterType)
                eq.addBand(f.frequency, f.gain, filterType, f.q.toDouble())
            }
            eq.isEnabled = true
            // Persist so MainActivity.onResume restores the correct UI state.
            val slots = (0 until eq.getBandCount()).toList()
            eqPrefs.saveState(eq, slots)
            eqPrefs.savePreampGain(profile.preampDb)
            eqPrefs.savePresetName(presetName)
            eqPrefs.saveChannelSideEqEnabled(false)
            eqPrefs.clearLeftRightBands()
            dynamicsManager.updateFromEqualizer(eq)
        } catch (_: Exception) { }
    }

    inner class EqBinder : Binder() {
        val service: EqService get() = this@EqService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        eqPrefs = EqPreferencesManager(this)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
        }
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(audioDeviceCallback, null)
        LocalBroadcastManager.getInstance(this).registerReceiver(
            autoPresetReceiver,
            IntentFilter(AutoPresetManager.ACTION_DEVICE_CHANGED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dynamicsManager.stop()
            sendBroadcast(Intent(ACTION_EQ_STOPPED).setPackage(packageName))
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    fun startEq(eq: ParametricEqualizer): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        dynamicsManager.start(eq)
        return dynamicsManager.isActive
    }

    fun updateEq(eq: ParametricEqualizer) {
        dynamicsManager.updateFromEqualizer(eq)
    }

    fun updateEqPerChannel(leftEq: ParametricEqualizer, rightEq: ParametricEqualizer) {
        dynamicsManager.updateFromEqualizers(leftEq, rightEq)
    }

    fun setEqEnabled(enabled: Boolean) {
        dynamicsManager.setEnabled(enabled)
    }

    fun updateMbc(bands: List<DynamicsProcessingManager.MbcBandParams>, crossovers: FloatArray) {
        dynamicsManager.applyMbcBands(bands, crossovers)
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        try { getSystemService(AudioManager::class.java)
            .unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
        LocalBroadcastManager.getInstance(this).unregisterReceiver(autoPresetReceiver)
        dynamicsManager.stop()
        Log.d(TAG, "EqService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System EQ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when system-wide EQ is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, EqService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audioManager = getSystemService(AudioManager::class.java)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = if (maxVol > 0) (currentVol * 100 / maxVol) else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_equalizer)
            .setContentTitle("Equalizer314 Online")
            .setContentText("Volume: $volumePercent%")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_nav_power, "Turn Off", stopPending)
            .build()
    }
}
