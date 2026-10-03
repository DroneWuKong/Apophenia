package com.dronewukong.apophenia.mavlink

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface MavlinkEndpoint {
    val label: String
    data class Udp(val port: Int = 14550) : MavlinkEndpoint { override val label = "udp:$port" }
    data class Tcp(val host: String, val port: Int = 5760) : MavlinkEndpoint { override val label = "tcp:$host:$port" }
    data class Usb(val deviceId: Int, val baud: Int = 57_600) : MavlinkEndpoint { override val label = "usb:$deviceId:$baud" }
    data object Simulation : MavlinkEndpoint { override val label = "simulation" }
}

data class UsbMavlinkDevice(val deviceId: Int, val displayName: String)

interface MavlinkTransport : Closeable {
    fun readLoop(onBytes: (ByteArray) -> Unit)
}

class TcpMavlinkTransport(private val host: String, private val port: Int) : MavlinkTransport {
    private var socket: Socket? = null

    override fun readLoop(onBytes: (ByteArray) -> Unit) {
        val connected = Socket().also {
            it.connect(InetSocketAddress(host, port), 5_000)
            it.tcpNoDelay = true
            socket = it
        }
        val buffer = ByteArray(4096)
        connected.getInputStream().use { input ->
            while (!connected.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) onBytes(buffer.copyOf(count))
            }
        }
    }

    override fun close() { runCatching { socket?.close() }; socket = null }
}

class UdpMavlinkTransport(private val port: Int) : MavlinkTransport {
    private var socket: DatagramSocket? = null

    override fun readLoop(onBytes: (ByteArray) -> Unit) {
        val listener = DatagramSocket(null).also {
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            socket = it
        }
        val buffer = ByteArray(8192)
        while (!listener.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            listener.receive(packet)
            if (packet.length > 0) onBytes(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
        }
    }

    override fun close() { socket?.close(); socket = null }
}

/** Raw USB bulk/CDC reader suitable for class-compliant SiK radios and serial adapters. */
class UsbSerialMavlinkTransport(
    context: Context,
    private val deviceId: Int,
    private val baud: Int
) : MavlinkTransport {
    private val manager = context.applicationContext.getSystemService(UsbManager::class.java)
        ?: error("USB manager unavailable")
    private var connection: UsbDeviceConnection? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()

    override fun readLoop(onBytes: (ByteArray) -> Unit) {
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: error("USB device is no longer attached")
        check(manager.hasPermission(device)) { "USB permission has not been granted" }
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        val candidate = interfaces
            .firstOrNull { intf -> (0 until intf.endpointCount).any { endpoint ->
                intf.getEndpoint(endpoint).direction == UsbConstants.USB_DIR_IN &&
                    intf.getEndpoint(endpoint).type == UsbConstants.USB_ENDPOINT_XFER_BULK
            } } ?: error("USB device exposes no bulk input endpoint")
        val endpoint = bulkInput(candidate) ?: error("USB bulk input endpoint unavailable")
        val opened = manager.openDevice(device) ?: error("Android could not open the USB device")
        check(opened.claimInterface(candidate, true)) { "Android could not claim the USB interface" }
        connection = opened
        claimedInterfaces += candidate
        val controlInterface = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        if (controlInterface != null && controlInterface != candidate && opened.claimInterface(controlInterface, true)) {
            claimedInterfaces += controlInterface
        }
        if (controlInterface != null || candidate.interfaceClass == UsbConstants.USB_CLASS_COMM || device.deviceClass == UsbConstants.USB_CLASS_COMM) {
            configureCdc(opened, controlInterface?.id ?: candidate.id, baud)
        }
        val buffer = ByteArray(maxOf(512, endpoint.maxPacketSize * 8))
        while (connection != null) {
            val count = opened.bulkTransfer(endpoint, buffer, buffer.size, 1_000)
            if (count > 0) onBytes(buffer.copyOf(count))
        }
    }

    override fun close() {
        val opened = connection
        connection = null
        if (opened != null) claimedInterfaces.forEach { intf -> runCatching { opened.releaseInterface(intf) } }
        claimedInterfaces.clear()
        runCatching { opened?.close() }
    }

    private fun bulkInput(intf: UsbInterface): UsbEndpoint? =
        (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }

    private fun configureCdc(connection: UsbDeviceConnection, interfaceId: Int, baud: Int) {
        val coding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(baud).put(0).put(0).put(8).array()
        // USB recipient value 0x01 means interface; Android's UsbConstants does not expose it.
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01
        connection.controlTransfer(requestType, 0x20, 0, interfaceId, coding, coding.size, 1_000)
        connection.controlTransfer(requestType, 0x22, 0x03, interfaceId, null, 0, 1_000)
    }
}
