package com.bearinmind.equalizer314.autopreset

import org.json.JSONArray
import org.json.JSONObject

enum class DeviceType { USB, BLUETOOTH, WIRED, USBC, SPEAKER }

enum class PresetAction { FLAT, AUTOEQ, IMPORT, SNAPSHOT, PROMPT }

data class AutoPresetDevice(
    val id: String,
    val type: DeviceType,
    var displayName: String,
    var hidden: Boolean = false,
    var presetAction: PresetAction = PresetAction.FLAT,
    var presetName: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("displayName", displayName)
        put("hidden", hidden)
        put("presetAction", presetAction.name)
        put("presetName", presetName)
    }

    companion object {
        fun fromJson(obj: JSONObject): AutoPresetDevice? = try {
            AutoPresetDevice(
                id = obj.getString("id"),
                type = DeviceType.valueOf(obj.getString("type")),
                displayName = obj.getString("displayName"),
                hidden = obj.optBoolean("hidden", false),
                presetAction = PresetAction.valueOf(obj.optString("presetAction", "FLAT")),
                presetName = obj.optString("presetName", "")
            )
        } catch (_: Exception) { null }

        val SEEDED_IDS = setOf("wired_3.5mm", "usbc_audio", "speaker")

        fun seeded(): List<AutoPresetDevice> = listOf(
            AutoPresetDevice("wired_3.5mm", DeviceType.WIRED, "Wired 3.5mm"),
            AutoPresetDevice("usbc_audio", DeviceType.USBC, "USB-C Audio"),
            AutoPresetDevice("speaker", DeviceType.SPEAKER, "Built-in Speaker")
        )
    }
}

fun List<AutoPresetDevice>.toJsonString(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun String.toAutoPresetDevices(): List<AutoPresetDevice> {
    return try {
        val arr = JSONArray(this)
        (0 until arr.length()).mapNotNull { AutoPresetDevice.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
}
