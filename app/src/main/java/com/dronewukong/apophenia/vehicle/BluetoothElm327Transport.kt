package com.dronewukong.apophenia.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

class BluetoothElm327Transport(private val context: Context) : ObdTransport {
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        check(Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED) { "Bluetooth Connect permission is required" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("Bluetooth hardware absent")
        runCatching { adapter.cancelDiscovery() }
        val candidate = adapter.getRemoteDevice(address)
            .createRfcommSocketToServiceRecord(SPP_UUID)
        candidate.connect()
        socket = candidate
    }

    override fun command(command: String): String {
        val active = socket ?: error("ELM327 transport is not connected")
        active.outputStream.write((command.trim() + "\r").toByteArray(Charsets.US_ASCII))
        active.outputStream.flush()
        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + COMMAND_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val available = active.inputStream.available()
            if (available <= 0) {
                Thread.sleep(15)
                continue
            }
            repeat(available) {
                val next = active.inputStream.read()
                if (next >= 0) output.write(next)
            }
            if (output.toByteArray().contains('>'.code.toByte())) break
        }
        val response = output.toString(Charsets.US_ASCII.name())
        check(response.contains('>')) { "ELM327 command timed out: $command" }
        return response
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val COMMAND_TIMEOUT_MS = 3_500L
    }
}
