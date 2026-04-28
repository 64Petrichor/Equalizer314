package com.bearinmind.equalizer314.autopreset

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.AutoEqActivity
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch

class AutoPresetActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private var devices = mutableListOf<AutoPresetDevice>()
    private var showingHidden = false
    private var pendingPresetDevice: AutoPresetDevice? = null

    private lateinit var deviceListContainer: LinearLayout
    private lateinit var showHiddenToggle: TextView

    private val autoEqLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val device = pendingPresetDevice ?: return@registerForActivityResult
            val presetName = eqPrefs.getPresetName()
            if (presetName.isNotBlank() && presetName != "Flat"
                && eqPrefs.getImportedPresetText(presetName) != null) {
                device.presetAction = PresetAction.IMPORT
                device.presetName = presetName
                save()
            } else {
                Toast.makeText(this, "No preset was selected", Toast.LENGTH_SHORT).show()
            }
            pendingPresetDevice = null
        }
    }

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
            deviceListContainer.addView(buildDeviceRow(device))
        }
    }

    private fun buildDeviceRow(device: AutoPresetDevice): View {
        val alpha = if (device.hidden) 0.45f else 1f

        // Outer row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                dpToPx(16), dpToPx(12),
                dpToPx(8),  dpToPx(12)
            )
            background = android.util.TypedValue().let { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                getDrawable(tv.resourceId)
            }
            isClickable = true
            isFocusable = true
        }

        // Text block (name + subtitle)
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.alpha = alpha
        }

        val nameView = TextView(this).apply {
            text = device.displayName
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this@AutoPresetActivity,
                    com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt()
                )
            )
        }

        val subtitleView = TextView(this).apply {
            text = presetSummary(device)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this@AutoPresetActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()
                )
            )
            setPadding(0, dpToPx(2), 0, 0)
        }

        textBlock.addView(nameView)
        textBlock.addView(subtitleView)
        row.addView(textBlock)

        // Rename icon
        val renameBtn = iconButton(R.drawable.ic_edit, "Rename").apply {
            this.alpha = alpha
            setOnClickListener { showRenameDialog(device) }
        }
        row.addView(renameBtn)

        // Hide / unhide icon
        val hideIcon = if (device.hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off
        val hideDesc = if (device.hidden) "Unhide" else "Hide"
        val hideBtn = iconButton(hideIcon, hideDesc).apply {
            this.alpha = alpha
            setOnClickListener {
                if (device.hidden) {
                    device.hidden = false
                } else {
                    device.hidden = true
                }
                save()
            }
        }
        row.addView(hideBtn)

        // Tapping the row body opens the preset option menu
        row.setOnClickListener {
            if (!device.hidden) showPresetOptions(device)
        }

        return row
    }

    private fun iconButton(iconRes: Int, contentDesc: String): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setImageResource(iconRes)
            contentDescription = contentDesc
            background = android.util.TypedValue().let { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                getDrawable(tv.resourceId)
            }
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    this@AutoPresetActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()
                )
            )
        }
    }

    private fun showPresetOptions(device: AutoPresetDevice) {
        val options = arrayOf(
            "No preset",
            "Choose preset",
            "Save current EQ as snapshot",
            "Prompt with each connect",
        )
        AlertDialog.Builder(this)
            .setTitle(device.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { device.presetAction = PresetAction.FLAT; device.presetName = ""; save() }
                    1 -> launchAutoEqForDevice(device)
                    2 -> chooseCurrentPreset(device)
                    3 -> { device.presetAction = PresetAction.PROMPT; device.presetName = ""; save() }
                }
            }.show()
    }

    private fun launchAutoEqForDevice(device: AutoPresetDevice) {
        pendingPresetDevice = device
        autoEqLauncher.launch(Intent(this, AutoEqActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun chooseCurrentPreset(device: AutoPresetDevice) {
        val input = android.widget.EditText(this).apply {
            hint = "Snapshot name"
            selectAll()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name this snapshot")
            .setMessage("Saves the current EQ, preamp, MBC, and limiter settings.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(200)
                if (name.isBlank()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val snapshot = buildSnapshot()
                eqPrefs.saveFullSnapshot(name, snapshot.toJson())
                device.presetAction = PresetAction.SNAPSHOT
                device.presetName = name
                save()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildSnapshot(): FullSnapshotData {
        val bandCount = eqPrefs.getMbcBandCount()
        val mbcBands = (0 until bandCount).map { i ->
            FullSnapshotData.MbcBandSnapshot(
                enabled   = eqPrefs.getMbcBandEnabled(i),
                attack    = eqPrefs.getMbcBandAttack(i),
                release   = eqPrefs.getMbcBandRelease(i),
                ratio     = eqPrefs.getMbcBandRatio(i),
                threshold = eqPrefs.getMbcBandThreshold(i),
                knee      = eqPrefs.getMbcBandKnee(i),
                noiseGate = eqPrefs.getMbcBandNoiseGate(i),
                expander  = eqPrefs.getMbcBandExpander(i),
                preGain   = eqPrefs.getMbcBandPreGain(i),
                postGain  = eqPrefs.getMbcBandPostGain(i),
                range     = eqPrefs.getMbcBandRange(i),
            )
        }
        val crossovers = (0 until (bandCount - 1)).map { i ->
            eqPrefs.getMbcCrossover(i, 1000f)
        }
        return FullSnapshotData(
            bandsJson        = eqPrefs.getBandsJson(),
            preampGain       = eqPrefs.getPreampGain(),
            mbcEnabled       = eqPrefs.getMbcEnabled(),
            mbcBandCount     = bandCount,
            mbcBands         = mbcBands,
            mbcCrossovers    = crossovers,
            limiterEnabled   = eqPrefs.getLimiterEnabled(),
            limiterAttack    = eqPrefs.getLimiterAttack(),
            limiterRelease   = eqPrefs.getLimiterRelease(),
            limiterRatio     = eqPrefs.getLimiterRatio(),
            limiterThreshold = eqPrefs.getLimiterThreshold(),
            limiterPostGain  = eqPrefs.getLimiterPostGain(),
        )
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
                val name = input.text.toString().trim().take(200)
                if (name.isNotBlank()) { device.displayName = name; save() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun presetSummary(device: AutoPresetDevice): String = when (device.presetAction) {
        PresetAction.FLAT -> "No preset"
        PresetAction.PROMPT -> "Prompt with each connect"
        PresetAction.AUTOEQ, PresetAction.IMPORT, PresetAction.SNAPSHOT ->
            device.presetName.takeIf { it.isNotBlank() } ?: "Not set"
    }

    private fun save() {
        AutoPresetManager.saveDevices(eqPrefs, devices)
        rebuildList()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
