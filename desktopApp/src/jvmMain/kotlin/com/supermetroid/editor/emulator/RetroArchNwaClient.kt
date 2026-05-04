package com.supermetroid.editor.emulator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP client for RetroArch's Network Command interface.
 * Uses READ_CORE_MEMORY / WRITE_CORE_MEMORY commands over UDP port 55355 (default).
 *
 * These are the newer, cross-core-compatible commands that accept full SNES bus
 * addresses (e.g. 0x7E0998) prefixed with "0x". Unlike the older READ_CORE_RAM
 * command, these work correctly with all libretro cores (bsnes, snes9x, etc.).
 */
class RetroArchNwaClient(
    private val host: String = "localhost",
    private val port: Int = 55355,
    private val timeoutMs: Int = 5000,
) {
    private var socket: DatagramSocket? = null
    private val address = InetAddress.getByName(host)
    private val udpMutex = Mutex()

    val isConnected: Boolean
        get() {
            val s = socket
            return s != null && !s.isClosed
        }

    fun connect() {
        socket?.close()
        socket = DatagramSocket().apply { soTimeout = timeoutMs }
    }

    /** Close and reopen the UDP socket. Clears any pending ICMP errors. */
    fun reconnect() {
        connect()
    }

    fun disconnect() {
        socket?.close()
        socket = null
    }

    /**
     * Read [size] bytes from SNES core memory at [address].
     * [address] is a full SNES bus address (e.g. 0x7E0998).
     * Uses READ_CORE_MEMORY which works across all libretro cores.
     */
    suspend fun readMemory(address: Int, size: Int): ByteArray = withContext(Dispatchers.IO) {
        val sock = socket ?: throw IllegalStateException("Not connected")
        val command = "READ_CORE_MEMORY 0x${address.toString(16).uppercase()} $size"

        udpMutex.withLock {
            val sendData = command.toByteArray()
            sock.send(DatagramPacket(sendData, sendData.size, this@RetroArchNwaClient.address, port))

            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)

            val response = String(packet.data, 0, packet.length).trim()
            parseReadResponse(response, size)
        }
    }

    /**
     * Write [data] bytes to SNES core memory at [address].
     * [address] is a full SNES bus address (e.g. 0x7E0998).
     * Uses WRITE_CORE_MEMORY which works across all libretro cores.
     */
    suspend fun writeMemory(address: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sock = socket ?: throw IllegalStateException("Not connected")
        val hexData = data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val command = "WRITE_CORE_MEMORY 0x${address.toString(16).uppercase()} $hexData"

        udpMutex.withLock {
            val sendData = command.toByteArray()
            sock.send(DatagramPacket(sendData, sendData.size, this@RetroArchNwaClient.address, port))

            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)

            val response = String(packet.data, 0, packet.length).trim()
            response.startsWith("WRITE_CORE_MEMORY")
        }
    }

    /**
     * Send a simple probe to verify RetroArch is listening and a core is loaded.
     */
    suspend fun probe(): Boolean {
        return try {
            readMemory(GAME_STATE_ADDR, 2)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseReadResponse(response: String, expectedSize: Int): ByteArray {
        val parts = response.trim().split(" ")
        if (parts.size < 3 || parts[0] != "READ_CORE_MEMORY") {
            if (parts[0] == "WRITE_CORE_MEMORY") {
                return ByteArray(expectedSize)
            }
            throw IllegalArgumentException("Unexpected response: $response")
        }
        if (parts[2] == "-1") {
            throw IllegalStateException("NWA: memory not ready (response: $response)")
        }
        // READ_CORE_MEMORY returns hex as continuous string(s) — join then chunk by 2
        val hexData = parts.drop(2).joinToString("")
        val bytes = hexData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.size != expectedSize) {
            System.err.println("[NWA] Expected $expectedSize bytes, got ${bytes.size}")
        }
        return bytes
    }

    companion object {
        const val DEFAULT_PORT = 55355
        /** SNES WRAM address for Super Metroid game state. */
        private const val GAME_STATE_ADDR = 0x7E0998
    }
}
