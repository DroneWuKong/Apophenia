package com.dronewukong.apophenia.audio

import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ContextSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioFeatureSummary(
    val loudnessCurveDbfs: List<Double>,
    val silenceRatio: Double,
    val onsetCount: Int,
    val humDbfs: Double,
    val voiceDbfs: Double,
    val rfiHighDbfs: Double,
    val broadbandDbfs: Double
)

object AudioFeatureExtractor {
    fun summarize(pcm16le: ByteArray, sampleRateHz: Int): AudioFeatureSummary {
        val samples = shorts(pcm16le)
        if (samples.isEmpty()) return AudioFeatureSummary(emptyList(), 1.0, 0, FLOOR_DB, FLOOR_DB, FLOOR_DB, FLOOR_DB)
        val frames = samples.asList().chunked(sampleRateHz.coerceAtLeast(1))
        val curve = frames.map { frame -> dbfs(frame.sumOf { it * it.toDouble() } / frame.size) }
        val silence = curve.count { it <= -50.0 }.toDouble() / curve.size.coerceAtLeast(1)
        val onsets = curve.zipWithNext().count { (a, b) -> b - a >= 6.0 && b > -45.0 }
        val spectrum = bandPowers(samples, sampleRateHz)
        return AudioFeatureSummary(curve, silence, onsets, spectrum[0], spectrum[1], spectrum[2], spectrum[3])
    }

    fun contextSamples(
        eventId: Long,
        eventAtMs: Long,
        captureId: String,
        phase: ContextPhase,
        pcm16le: ByteArray,
        sampleRateHz: Int
    ): List<ContextSample> {
        val summary = summarize(pcm16le, sampleRateHz)
        val durationMs = pcm16le.size * 1_000L / (sampleRateHz * 2L)
        val phaseStart = if (phase == ContextPhase.PRE) eventAtMs - durationMs else eventAtMs
        val metadata = "derived_from_encrypted_audio=true;descriptive_only=true;sample_rate_hz=$sampleRateHz"
        fun row(timestampMs: Long, metric: String, value: Double, unit: String) = ContextSample(
            timestampMs = timestampMs, observationId = eventId, source = "audio_derived",
            metric = metric, value = value, unit = unit, metadata = metadata,
            captureId = captureId, phase = phase
        )
        return buildList {
            summary.loudnessCurveDbfs.forEachIndexed { index, value ->
                add(row(phaseStart + index * 1_000L, "audio_loudness_dbfs", value, "dBFS"))
            }
            add(row(phaseStart, "audio_silence_ratio", summary.silenceRatio, "ratio"))
            add(row(phaseStart, "audio_onset_count", summary.onsetCount.toDouble(), "count"))
            add(row(phaseStart, "audio_band_hum_dbfs", summary.humDbfs, "dBFS"))
            add(row(phaseStart, "audio_band_voice_dbfs", summary.voiceDbfs, "dBFS"))
            add(row(phaseStart, "audio_band_rfi_high_dbfs", summary.rfiHighDbfs, "dBFS"))
            add(row(phaseStart, "audio_band_broadband_dbfs", summary.broadbandDbfs, "dBFS"))
        }
    }

    private fun shorts(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2) { index ->
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt()
        ((high shl 8) or low).toShort()
    }

    private fun dbfs(meanSquare: Double): Double {
        val normalizedRms = sqrt(meanSquare.coerceAtLeast(0.0)) / Short.MAX_VALUE
        return (20.0 * log10(normalizedRms.coerceAtLeast(1e-12))).coerceAtLeast(FLOOR_DB)
    }

    private fun bandPowers(samples: ShortArray, sampleRateHz: Int): DoubleArray {
        val size = minOf(512, samples.size)
        if (size < 64) return DoubleArray(4) { FLOOR_DB }
        val windowed = DoubleArray(size) { index ->
            val value = samples[index] / Short.MAX_VALUE.toDouble()
            value * (0.5 - 0.5 * cos(2.0 * PI * index / (size - 1)))
        }
        val effectiveRate = sampleRateHz.toDouble()
        val powers = DoubleArray(4)
        for (bin in 1 until size / 2) {
            var real = 0.0
            var imaginary = 0.0
            for (n in windowed.indices) {
                val angle = -2.0 * PI * bin * n / size
                real += windowed[n] * cos(angle)
                imaginary += windowed[n] * sin(angle)
            }
            val power = (real * real + imaginary * imaginary) / (size * size)
            val hz = bin * effectiveRate / size
            powers[3] += power
            when (hz) {
                in 40.0..120.0 -> powers[0] += power
                in 300.0..3_400.0 -> powers[1] += power
                in 3_400.0..(effectiveRate / 2.0) -> powers[2] += power
            }
        }
        return DoubleArray(4) { index -> (10.0 * log10(powers[index].coerceAtLeast(1e-12))).coerceAtLeast(FLOOR_DB) }
    }

    private const val FLOOR_DB = -120.0
}
