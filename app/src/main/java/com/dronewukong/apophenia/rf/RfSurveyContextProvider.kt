package com.dronewukong.apophenia.rf

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class RfSurveyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1234,
    val centerFrequencyHz: Long = 915_000_000L,
    val sampleRateHz: Int = 1_024_000,
    val windowMs: Int = 250,
    val retentionDays: Int = 14
) {
    init {
        require(host.isNotBlank())
        require(port in 1..65_535)
        require(centerFrequencyHz in 24_000_000L..1_766_000_000L)
        require(sampleRateHz in 225_001..3_200_000)
        require(windowMs in 50..2_000)
        require(retentionDays in 1..3_650)
    }
}

object RfSurveySettings {
    private const val PREFS = "rf_survey"
    fun load(context: Context): RfSurveyConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            RfSurveyConfig(
                host = p.getString("host", "127.0.0.1").orEmpty(),
                port = p.getInt("port", 1234),
                centerFrequencyHz = p.getLong("center_hz", 915_000_000L),
                sampleRateHz = p.getInt("sample_rate_hz", 1_024_000),
                windowMs = p.getInt("window_ms", 250),
                retentionDays = p.getInt("retention_days", 14)
            )
        }.getOrDefault(RfSurveyConfig())
    }

    fun save(context: Context, config: RfSurveyConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", config.host).putInt("port", config.port)
            .putLong("center_hz", config.centerFrequencyHz).putInt("sample_rate_hz", config.sampleRateHz)
            .putInt("window_ms", config.windowMs).putInt("retention_days", config.retentionDays)
            .apply()
    }
}

data class RfSpectrumSummary(val rmsDbfs: Double, val peakDbfs: Double, val peakOffsetHz: Double)

object RfSpectrumAnalyzer {
    fun summarize(iq: ByteArray, sampleRateHz: Int, fftSize: Int = 256): RfSpectrumSummary? {
        val points = minOf(iq.size / 2, fftSize)
        if (points < 32) return null
        val iValues = DoubleArray(points) { (iq[it * 2].toInt() and 0xff) / 127.5 - 1.0 }
        val qValues = DoubleArray(points) { (iq[it * 2 + 1].toInt() and 0xff) / 127.5 - 1.0 }
        val meanI = iValues.average()
        val meanQ = qValues.average()
        var energy = 0.0
        for (n in 0 until points) {
            iValues[n] -= meanI
            qValues[n] -= meanQ
            energy += iValues[n] * iValues[n] + qValues[n] * qValues[n]
        }
        val rms = sqrt(energy / points).coerceAtLeast(1e-12)
        var bestBin = 0
        var bestMagnitude = 0.0
        for (bin in 0 until points) {
            var real = 0.0
            var imaginary = 0.0
            for (n in 0 until points) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * n / (points - 1))
                val angle = -2.0 * PI * bin * n / points
                val i = iValues[n] * window
                val q = qValues[n] * window
                real += i * cos(angle) - q * sin(angle)
                imaginary += i * sin(angle) + q * cos(angle)
            }
            val magnitude = sqrt(real * real + imaginary * imaginary) / points
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestBin = bin
            }
        }
        val signedBin = if (bestBin <= points / 2) bestBin else bestBin - points
        return RfSpectrumSummary(
            rmsDbfs = 20.0 * log10(rms),
            peakDbfs = 20.0 * log10(bestMagnitude.coerceAtLeast(1e-12)),
            peakOffsetHz = signedBin * sampleRateHz.toDouble() / points
        )
    }
}

interface RfSurveyTransport {
    fun capture(config: RfSurveyConfig): ByteArray
}

internal class RtlTcpTransport : RfSurveyTransport {
    override fun capture(config: RfSurveyConfig): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val header = ByteArray(12)
            readFully(input, header)
            require(header.copyOfRange(0, 4).decodeToString() == "RTL0") { "Endpoint did not provide an rtl_tcp RTL0 header" }
            val commands = DataOutputStream(socket.getOutputStream())
            command(commands, 1, config.centerFrequencyHz.toInt())
            command(commands, 2, config.sampleRateHz)
            command(commands, 3, 0)
            commands.flush()

            val byteLimit = minOf(MAX_IQ_BYTES.toLong(), config.sampleRateHz * 2L * config.windowMs / 1_000L).toInt()
            val deadline = System.nanoTime() + config.windowMs * 1_000_000L
            val output = ByteArrayOutputStream(byteLimit)
            val buffer = ByteArray(16_384)
            while (output.size() < byteLimit && System.nanoTime() < deadline) {
                val read = runCatching { input.read(buffer, 0, minOf(buffer.size, byteLimit - output.size())) }.getOrElse { break }
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray().also { require(it.size >= 64) { "rtl_tcp returned no bounded IQ window" } }
        }
    }

    private fun command(output: DataOutputStream, command: Int, value: Int) {
        output.writeByte(command)
        output.writeInt(value)
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            require(read > 0) { "rtl_tcp closed before its header" }
            offset += read
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_500
        const val READ_TIMEOUT_MS = 750
        const val MAX_IQ_BYTES = 4 * 1_024 * 1_024
    }
}

class RfSurveyContextProvider(
    private val context: Context,
    private val transport: RfSurveyTransport = RtlTcpTransport()
) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        if (!HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE)) return emptyList()
        val config = RfSurveySettings.load(context)
        val now = System.currentTimeMillis()
        val iq = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) simulationIq(config.sampleRateHz) else transport.capture(config)
        val evenIq = if (iq.size % 2 == 0) iq else iq.copyOf(iq.size - 1)
        if (evenIq.isEmpty()) return emptyList()
        val summary = RfSpectrumAnalyzer.summarize(evenIq, config.sampleRateHz) ?: return emptyList()
        val artifact = RfIqStore(context).persist(evenIq, now, config)
        val captureId = when {
            isControl -> "control:rf_survey:$now"
            observationId != null -> "event:$observationId:rf_survey:$now"
            else -> "rf_survey:$now"
        }
        val metadata = "center_hz=${config.centerFrequencyHz};sample_rate_hz=${config.sampleRateHz};window_ms=${config.windowMs};iq_file_id=${artifact.id};sha256=${artifact.sha256};retention_days=${config.retentionDays};bounded=true;transport=${if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) "simulation" else "rtl_tcp"}"
        fun row(metric: String, value: Double, unit: String) = ContextSample(
            timestampMs = now, observationId = observationId, isControl = isControl,
            source = "rf_survey", metric = metric, value = value, unit = unit,
            metadata = metadata, captureId = captureId
        )
        return listOf(
            row("rf_center_frequency_hz", config.centerFrequencyHz.toDouble(), "Hz"),
            row("rf_sample_rate_hz", config.sampleRateHz.toDouble(), "samples/s"),
            row("rf_iq_bytes", evenIq.size.toDouble(), "bytes"),
            row("rf_rms_dbfs", summary.rmsDbfs, "dBFS"),
            row("rf_peak_dbfs", summary.peakDbfs, "dBFS"),
            row("rf_peak_offset_hz", summary.peakOffsetHz, "Hz")
        )
    }

    private fun simulationIq(sampleRateHz: Int): ByteArray {
        val points = minOf(sampleRateHz / 20, 16_384)
        val output = ByteArray(points * 2)
        repeat(points) { n ->
            val angle = 2.0 * PI * 0.125 * n
            output[n * 2] = (127.5 + 60.0 * cos(angle)).toInt().coerceIn(0, 255).toByte()
            output[n * 2 + 1] = (127.5 + 60.0 * sin(angle)).toInt().coerceIn(0, 255).toByte()
        }
        return output
    }
}

private data class RfIqArtifact(val id: String, val sha256: String)

private class RfIqStore(private val context: Context) {
    fun persist(iq: ByteArray, now: Long, config: RfSurveyConfig): RfIqArtifact {
        val directory = java.io.File(context.filesDir, "rf-survey").apply { mkdirs() }
        val cutoff = now - config.retentionDays * 86_400_000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val id = "rf-${now}-${UUID.randomUUID()}"
        java.io.File(directory, "$id.iq").writeBytes(iq)
        val sha = MessageDigest.getInstance("SHA-256").digest(iq).joinToString("") { "%02x".format(it) }
        return RfIqArtifact(id, sha)
    }
}
