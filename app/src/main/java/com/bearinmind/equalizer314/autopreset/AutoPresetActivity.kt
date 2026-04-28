package com.bearinmind.equalizer314.autopreset

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch

class AutoPresetActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private var devices = mutableListOf<AutoPresetDevice>()
    private var showingHidden = false

    private lateinit var deviceListContainer: LinearLayout
    private lateinit var showHiddenToggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_preset)
        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.autoPresetBackButton).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        val masterSwitch = findViewById<MaterialSwitch>(R.id.autoPresetMasterSwitch)
        deviceListContainer = findViewById(R.id.autoPresetDeviceList)
        showHiddenToggle = findViewById(R.id.showHiddenToggle)

        masterSwitch.isChecked = eqPrefs.getAutoPresetEnabled()
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            eqPrefs.saveAutoPresetEnabled(checked)
            if (checked) {
                // Seed defaults on first enable if list is empty
                devices = AutoPresetManager.getDevices(eqPrefs)
                AutoPresetManager.saveDevices(eqPrefs, devices)
            }
            rebuildList()
        }

        devices = AutoPresetManager.getDevices(eqPrefs)
        showHiddenToggle.setOnClickListener {
            showingHidden = !showingHidden
            rebuildList()
        }
        rebuildList()
    }

    private fun rebuildList() {
        deviceListContainer.removeAllViews()
        val hiddenCount = devices.count { it.hidden }

        if (hiddenCount > 0) {
            showHiddenToggle.visibility = View.VISIBLE
            showHiddenToggle.text = if (showingHidden)
                "Hide hidden devices ($hiddenCount)"
            else
                "Show hidden ($hiddenCount)"
        } else {
            showHiddenToggle.visibility = View.GONE
            showingHidden = false
        }

        val visibleDevices = if (showingHidden) devices else devices.filter { !it.hidden }
        for (device in visibleDevices) {
            addDeviceRow(device)
        }
    }

    private fun addDeviceRow(device: AutoPresetDevice) {
        val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, deviceListContainer, false)
        val title = row.findViewById<TextView>(android.R.id.text1)
        val subtitle = row.findViewById<TextView>(android.R.id.text2)

        title.text = device.displayName
        title.setTextColor(if (device.hidden) Color.GRAY else getColor(android.R.color.transparent).let {
            title.currentTextColor
        })
        subtitle.text = presetSummary(device)
        subtitle.alpha = if (device.hidden) 0.5f else 1f

        row.setOnClickListener {
            if (!device.hidden) showDeviceOptions(device)
            else showUnhideOption(device)
        }
        deviceListContainer.addView(row)
    }

    private fun presetSummary(device: AutoPresetDevice): String = when (device.presetAction) {
        PresetAction.FLAT -> "Flat"
        PresetAction.PROMPT -> "Prompt on connect"
        PresetAction.AUTOEQ, PresetAction.IMPORT, PresetAction.SNAPSHOT ->
            device.presetName.takeIf { it.isNotBlank() } ?: "Not set"
    }

    private fun showDeviceOptions(device: AutoPresetDevice) {
        val presets = eqPrefs.getImportedPresets()
        val options = buildList {
            add("Flat")
            if (presets.isNotEmpty()) add("Choose preset…")
            add("Prompt on connect")
            add("Rename")
            add("Hide")
        }
        AlertDialog.Builder(this)
            .setTitle(device.displayName)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Flat" -> { device.presetAction = PresetAction.FLAT; device.presetName = ""; save() }
                    "Choose preset…" -> showPresetPicker(device, presets)
                    "Prompt on connect" -> { device.presetAction = PresetAction.PROMPT; device.presetName = ""; save() }
                    "Rename" -> showRenameDialog(device)
                    "Hide" -> { device.hidden = true; save() }
                }
            }.show()
    }

    private fun showPresetPicker(device: AutoPresetDevice, presets: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Choose preset")
            .setItems(presets.toTypedArray()) { _, which ->
                device.presetAction = PresetAction.IMPORT
                device.presetName = presets[which]
                save()
            }.show()
    }

    private fun showRenameDialog(device: AutoPresetDevice) {
        val input = EditText(this).apply {
            setText(device.displayName)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Rename device")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) { device.displayName = name; save() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUnhideOption(device: AutoPresetDevice) {
        AlertDialog.Builder(this)
            .setTitle(device.displayName)
            .setItems(arrayOf("Unhide")) { _, _ ->
                device.hidden = false
                save()
            }.show()
    }

    private fun save() {
        AutoPresetManager.saveDevices(eqPrefs, devices)
        rebuildList()
    }
}
