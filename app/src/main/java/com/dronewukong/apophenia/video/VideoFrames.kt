package com.dronewukong.apophenia.video

import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ContextSample
import kotlin.math.abs

data class VideoFrame(
    val streamId: String,
    val lensTag: String,
    val timestampMs: Long,
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val luma: ByteArray
)

class VideoFrameRing(private val maxFrames: Int) {
    private val frames = ArrayDeque<VideoFrame>()

    @Synchronized fun add(frame: VideoFrame) {
        frames += frame
        while (frames.size > maxFrames) frames.removeFirst()
    }

    @Synchronized fun snapshot(): List<VideoFrame> = frames.map { it.copy(jpeg = it.jpeg.copyOf(), luma = it.luma.copyOf()) }
    @Synchronized fun clear() = frames.clear()
}

object VideoFeatureExtractor {
    fun contextSamples(
        eventId: Long,
        captureId: String,
        phase: ContextPhase,
        frames: List<VideoFrame>
    ): List<ContextSample> {
        if (frames.isEmpty()) return emptyList()
        var previous: VideoFrame? = null
        return buildList {
            frames.forEach { frame ->
                val brightness = frame.luma.asSequence().map { it.toInt() and 0xff }.average()
                val prior = previous
                val motion = if (prior == null || prior.luma.size != frame.luma.size) 0.0 else {
                    frame.luma.indices.sumOf { index -> abs((frame.luma[index].toInt() and 0xff) - (prior.luma[index].toInt() and 0xff)).toDouble() } /
                        (frame.luma.size * 255.0)
                }
                val priorBrightness = prior?.luma?.asSequence()?.map { it.toInt() and 0xff }?.average()
                val flicker = priorBrightness?.let { abs(brightness - it) / 255.0 } ?: 0.0
                val pwmBanding = rowBanding(frame)
                val sceneChange = motion >= 0.28 || flicker >= 0.25
                val metadata = "stream=${token(frame.streamId)};lens=${token(frame.lensTag)};derived_from_encrypted_video=true;descriptive_only=true;pwm_frequency_not_estimated=true"
                fun row(metric: String, value: Double, unit: String) = ContextSample(
                    timestampMs = frame.timestampMs, observationId = eventId, source = "video_derived",
                    metric = metric, value = value, unit = unit, metadata = metadata,
                    captureId = captureId, phase = phase
                )
                add(row("video_brightness_mean", brightness, "0-255"))
                add(row("video_motion_energy", motion, "ratio"))
                add(row("video_flicker_delta", flicker, "ratio"))
                add(row("video_pwm_banding_score", pwmBanding, "ratio"))
                add(row("video_scene_change", if (sceneChange) 1.0 else 0.0, "bool"))
                add(row("video_pwm_frequency_observable", 0.0, "bool"))
                previous = frame
            }
        }
    }

    private fun rowBanding(frame: VideoFrame): Double {
        if (frame.width <= 0 || frame.height < 4 || frame.luma.size < frame.width * frame.height) return 0.0
        val rowMeans = DoubleArray(frame.height) { row ->
            var sum = 0L
            val start = row * frame.width
            repeat(frame.width) { column -> sum += frame.luma[start + column].toInt() and 0xff }
            sum.toDouble() / frame.width
        }
        var delta = 0.0
        for (index in 1 until rowMeans.size) delta += abs(rowMeans[index] - rowMeans[index - 1])
        return delta / ((rowMeans.size - 1) * 255.0)
    }

    private fun token(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-.") it else '_' }.joinToString("").take(80)
}
