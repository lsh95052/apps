package com.audiophile.proeqplayer

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.util.Log

data class EqPreset(
    val name: String,
    val description: String,
    val preampDb: Float,
    val bandGainsDb: List<Float> // 10 bands: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
)

/**
 * 專業級 10 頻段等化器與動態處理引擎
 * 採用 Android API 28+ 原生浮點 DSP `DynamicsProcessing`，具備：
 * 1. Pre-amp 前級數位衰減/增益 (-15dB ~ +15dB)，防止拉高 EQ 頻段時發生數位削波 (Clipping / 爆音)
 * 2. ISO 標準 10 頻段獨立增益控制 (-15dB ~ +15dB)
 * 3. 輸出軟限幅器 (Limiter) 保護聽力與單體
 * 4. 針對平板振膜與動圈耳機設計的發燒級曲線預設集
 */
class ProEqualizerEngine {

    companion object {
        private const val TAG = "ProEqualizerEngine"
        val ISO_10_FREQUENCIES = listOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
        val BAND_LABELS = listOf(
            "31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"
        )

        val BUILTIN_PRESETS = listOf(
            EqPreset(
                name = "Flat (Bit-Perfect 原音直通)",
                description = "全頻段 0dB 無染直出，適合監聽與測試線材/耳擴底聲",
                preampDb = 0.0f,
                bandGainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            ),
            EqPreset(
                name = "Planar Harman (平板耳機極低頻補償)",
                description = "針對開放式平板耳機 (如 HE400se / Deva Pro) 補償 Sub-bass 下潛並柔化 2kHz-4kHz 齒音區",
                preampDb = -4.5f,
                bandGainsDb = listOf(4.5f, 3.5f, 2.0f, 0.5f, 0.0f, -0.5f, 1.5f, -1.0f, 1.0f, 2.0f)
            ),
            EqPreset(
                name = "Closed Woodie Warmth (封閉式木碗收斂)",
                description = "針對封閉式動圈耳機 (如 FT1) 稍微收斂 125-250Hz 駐波感，提升人聲通透度與空氣感",
                preampDb = -2.5f,
                bandGainsDb = listOf(1.0f, 0.0f, -2.0f, -2.5f, 0.0f, 1.5f, 2.0f, 1.5f, 1.0f, 2.5f)
            ),
            EqPreset(
                name = "Vocal Intimate (人聲貼耳毒音)",
                description = "突出中頻基音與泛音細節，適合J-Pop、ACG女聲與原聲爵士",
                preampDb = -3.0f,
                bandGainsDb = listOf(-1.0f, -0.5f, 0.5f, 2.0f, 3.0f, 2.5f, 1.5f, -1.0f, 0.5f, 1.0f)
            ),
            EqPreset(
                name = "Classical & Soundstage (交響大編制音場)",
                description = "強化極低頻廳堂共鳴與 8k-16kHz 泛音延伸，拉開橫向與縱向音場層次",
                preampDb = -3.5f,
                bandGainsDb = listOf(3.0f, 2.5f, 1.0f, -0.5f, -1.0f, 0.0f, 1.0f, 2.0f, 3.0f, 3.5f)
            ),
            EqPreset(
                name = "V-Shape Dynamic (搖滾/電子爽快感)",
                description = "強化低頻衝擊力與高頻銅鈸清脆度，適合搖滾與電子舞曲",
                preampDb = -4.5f,
                bandGainsDb = listOf(4.5f, 4.0f, 2.0f, -1.0f, -2.0f, -1.0f, 1.5f, 3.0f, 4.0f, 3.5f)
            )
        )
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var fallbackEqualizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var currentSessionId: Int = 0

    var isEnabled: Boolean = true
        private set

    var preampDb: Float = 0.0f
        private set

    var limiterEnabled: Boolean = true
        private set

    var bassBoostStrength: Int = 0 // 0 .. 1000
        private set

    private val currentBandGains = FloatArray(10) { 0f }

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        release()
        currentSessionId = audioSessionId

        try {
            val builder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // Stereo channels
                true, // Pre-EQ enabled
                10,   // 10 bands Pre-EQ
                false, // MBC disabled for pure phase clarity
                0,
                false, // Post-EQ disabled
                0,
                true   // Limiter enabled (prevents harsh digital clipping)
            )
            val config = builder.build()

            // Initialize 10 Pre-EQ bands with cutoff frequencies
            val cutoffFreqs = floatArrayOf(45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f)
            for (ch in 0 until 2) {
                for (b in 0 until 10) {
                    val band = DynamicsProcessing.EqBand(true, cutoffFreqs[b], currentBandGains[b])
                    config.setPreEqBandByChannelIndex(ch, b, band)
                }
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled,
                    true,
                    0,
                    1.0f,   // attackTime ms
                    60.0f,  // releaseTime ms
                    10.0f,  // ratio 10:1
                    -0.5f,  // threshold -0.5 dBFS
                    0.0f    // postGain dB
                )
                config.setLimiterByChannelIndex(ch, limiter)
            }

            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply {
                enabled = isEnabled
            }
            applyPreampAndBands()
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing init failed, falling back to standard Equalizer: ${e.message}")
            try {
                fallbackEqualizer = Equalizer(0, audioSessionId).apply {
                    enabled = isEnabled
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback Equalizer also failed: ${ex.message}")
            }
        }

        try {
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = isEnabled && bassBoostStrength > 0
                if (strengthSupported) {
                    setStrength(bassBoostStrength.toShort())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost init failed: ${e.message}")
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        isEnabled = enabled
        dynamicsProcessing?.enabled = enabled
        fallbackEqualizer?.enabled = enabled
        bassBoost?.enabled = enabled && bassBoostStrength > 0
    }

    fun setPreamp(db: Float) {
        preampDb = db.coerceIn(-15f, 15f)
        applyPreampAndBands()
    }

    fun setBandGain(bandIndex: Int, db: Float) {
        if (bandIndex !in 0 until 10) return
        currentBandGains[bandIndex] = db.coerceIn(-15f, 15f)
        applyPreampAndBands()
    }

    fun setAllBands(preamp: Float, gains: List<Float>) {
        preampDb = preamp.coerceIn(-15f, 15f)
        for (i in 0 until minOf(10, gains.size)) {
            currentBandGains[i] = gains[i].coerceIn(-15f, 15f)
        }
        applyPreampAndBands()
    }

    fun setLimiter(enabled: Boolean) {
        limiterEnabled = enabled
        val dp = dynamicsProcessing ?: return
        try {
            val limiter = DynamicsProcessing.Limiter(
                enabled,
                true,
                0,
                1.0f,
                60.0f,
                10.0f,
                -0.5f,
                0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update limiter: ${e.message}")
        }
    }

    fun setBassBoost(strength: Int) {
        bassBoostStrength = strength.coerceIn(0, 1000)
        bassBoost?.let { bb ->
            try {
                bb.enabled = isEnabled && bassBoostStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(bassBoostStrength.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set BassBoost: ${e.message}")
            }
        }
    }

    fun getBandGains(): List<Float> = currentBandGains.toList()

    /**
     * 計算建議的 Auto Pre-amp (自動防爆音前級衰減值)
     * 取最大正增益的負值作為建議前級衰減，避免拉高特定頻段時超出 0 dBFS 產生破音
     */
    fun getRecommendedPreampDb(): Float {
        val maxBoost = currentBandGains.maxOrNull() ?: 0f
        return if (maxBoost > 0f) -(maxBoost) else 0f
    }

    private fun applyPreampAndBands() {
        val dp = dynamicsProcessing
        if (dp != null) {
            try {
                dp.setInputGainAllChannelsTo(preampDb)
                val cutoffFreqs = floatArrayOf(45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f)
                for (b in 0 until 10) {
                    val band = DynamicsProcessing.EqBand(true, cutoffFreqs[b], currentBandGains[b])
                    dp.setPreEqBandAllChannelsTo(b, band)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying DynamicsProcessing bands: ${e.message}")
            }
            return
        }

        // Fallback to standard 5-band Equalizer if DynamicsProcessing isn't supported on custom ROM
        val eq = fallbackEqualizer ?: return
        try {
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()
            for (b in 0 until numBands) {
                val mappedIdx = (b * 10 / numBands).coerceIn(0, 9)
                val totalDb = (currentBandGains[mappedIdx] + preampDb).coerceIn(-15f, 15f)
                val millibels = (totalDb * 100).toInt().coerceIn(minLevel, maxLevel).toShort()
                eq.setBandLevel(b.toShort(), millibels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying fallback Equalizer: ${e.message}")
        }
    }

    fun release() {
        try { dynamicsProcessing?.release() } catch (_: Exception) {}
        try { fallbackEqualizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        dynamicsProcessing = null
        fallbackEqualizer = null
        bassBoost = null
        currentSessionId = 0
    }
}
