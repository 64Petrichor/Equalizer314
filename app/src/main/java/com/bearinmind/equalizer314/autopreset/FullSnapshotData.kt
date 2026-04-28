package com.bearinmind.equalizer314.autopreset

import com.bearinmind.equalizer314.audio.DynamicsProcessingManager
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.state.EqStateManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Captures and restores a complete audio configuration: EQ bands, preamp,
 * MBC, and limiter. Serialised to/from JSON and stored under a user-given
 * name in SharedPreferences alongside Auto Preset device mappings.
 */
data class FullSnapshotData(
    val bandsJson: String,
    val preampGain: Float,
    val mbcEnabled: Boolean,
    val mbcBandCount: Int,
    val mbcBands: List<MbcBandSnapshot>,
    val mbcCrossovers: List<Float>,
    val limiterEnabled: Boolean,
    val limiterAttack: Float,
    val limiterRelease: Float,
    val limiterRatio: Float,
    val limiterThreshold: Float,
    val limiterPostGain: Float,
) {
    data class MbcBandSnapshot(
        val enabled: Boolean,
        val attack: Float,
        val release: Float,
        val ratio: Float,
        val threshold: Float,
        val knee: Float,
        val noiseGate: Float,
        val expander: Float,
        val preGain: Float,
        val postGain: Float,
        val range: Float,
    )

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("bands", bandsJson)
        put("preampGain", preampGain.toDouble())
        put("mbcEnabled", mbcEnabled)
        put("mbcBandCount", mbcBandCount)
        put("mbcBands", JSONArray().also { arr ->
            mbcBands.forEach { b ->
                arr.put(JSONObject().apply {
                    put("enabled", b.enabled)
                    put("attack", b.attack.toDouble())
                    put("release", b.release.toDouble())
                    put("ratio", b.ratio.toDouble())
                    put("threshold", b.threshold.toDouble())
                    put("knee", b.knee.toDouble())
                    put("noiseGate", b.noiseGate.toDouble())
                    put("expander", b.expander.toDouble())
                    put("preGain", b.preGain.toDouble())
                    put("postGain", b.postGain.toDouble())
                    put("range", b.range.toDouble())
                })
            }
        })
        put("mbcCrossovers", JSONArray().also { arr -> mbcCrossovers.forEach { arr.put(it.toDouble()) } })
        put("limiterEnabled", limiterEnabled)
        put("limiterAttack", limiterAttack.toDouble())
        put("limiterRelease", limiterRelease.toDouble())
        put("limiterRatio", limiterRatio.toDouble())
        put("limiterThreshold", limiterThreshold.toDouble())
        put("limiterPostGain", limiterPostGain.toDouble())
    }.toString()

    /** Convert MBC bands to the params the DP manager expects. */
    fun mbcBandParams(): List<DynamicsProcessingManager.MbcBandParams> = mbcBands.map { b ->
        DynamicsProcessingManager.MbcBandParams(
            enabled = b.enabled,
            attackMs = b.attack,
            releaseMs = b.release,
            ratio = b.ratio,
            thresholdDb = b.threshold,
            kneeDb = b.knee,
            noiseGateDb = b.noiseGate,
            expanderRatio = b.expander,
            preGainDb = b.preGain,
            postGainDb = b.postGain,
        )
    }

    fun mbcCrossoversArray(): FloatArray = FloatArray(mbcCrossovers.size) { mbcCrossovers[it] }

    /** Parse [bandsJson] into a list of BandSpec for EqStateManager. */
    fun toBandSpecs(): List<EqStateManager.BandSpec> = try {
        val arr = JSONArray(bandsJson)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val ft = try { BiquadFilter.FilterType.valueOf(obj.getString("filterType")) }
                     catch (_: Exception) { BiquadFilter.FilterType.BELL }
            EqStateManager.BandSpec(
                frequency = obj.getDouble("frequency").toFloat(),
                gain      = obj.getDouble("gain").toFloat(),
                q         = obj.getDouble("q"),
                filterType = ft,
                enabled   = obj.optBoolean("enabled", true),
            )
        }
    } catch (_: Exception) { emptyList() }

    companion object {
        fun fromJson(json: String): FullSnapshotData? = try {
            val o = JSONObject(json)
            val bandsArr = o.optJSONArray("mbcBands") ?: JSONArray()
            val bands = (0 until bandsArr.length()).map { i ->
                val b = bandsArr.getJSONObject(i)
                MbcBandSnapshot(
                    enabled   = b.optBoolean("enabled", true),
                    attack    = b.optDouble("attack", 1.0).toFloat(),
                    release   = b.optDouble("release", 100.0).toFloat(),
                    ratio     = b.optDouble("ratio", 2.0).toFloat(),
                    threshold = b.optDouble("threshold", 0.0).toFloat(),
                    knee      = b.optDouble("knee", 8.0).toFloat(),
                    noiseGate = b.optDouble("noiseGate", -60.0).toFloat(),
                    expander  = b.optDouble("expander", 1.0).toFloat(),
                    preGain   = b.optDouble("preGain", 0.0).toFloat(),
                    postGain  = b.optDouble("postGain", 0.0).toFloat(),
                    range     = b.optDouble("range", -12.0).toFloat(),
                )
            }
            val crossArr = o.optJSONArray("mbcCrossovers") ?: JSONArray()
            val crossovers = (0 until crossArr.length()).map { crossArr.getDouble(it).toFloat() }
            FullSnapshotData(
                bandsJson       = o.getString("bands"),
                preampGain      = o.optDouble("preampGain", 0.0).toFloat(),
                mbcEnabled      = o.optBoolean("mbcEnabled", false),
                mbcBandCount    = o.optInt("mbcBandCount", 3),
                mbcBands        = bands,
                mbcCrossovers   = crossovers,
                limiterEnabled  = o.optBoolean("limiterEnabled", false),
                limiterAttack   = o.optDouble("limiterAttack", 0.01).toFloat(),
                limiterRelease  = o.optDouble("limiterRelease", 1.0).toFloat(),
                limiterRatio    = o.optDouble("limiterRatio", 2.0).toFloat(),
                limiterThreshold= o.optDouble("limiterThreshold", 0.0).toFloat(),
                limiterPostGain = o.optDouble("limiterPostGain", 0.0).toFloat(),
            )
        } catch (_: Exception) { null }
    }
}
