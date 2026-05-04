package com.supermetroid.editor.emulator

import com.supermetroid.editor.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmulatorBackend that connects to an external RetroArch instance via NWA (UDP).
 *
 * Flow: connect() opens the UDP socket. startSession() launches RetroArch
 * with the (patched) ROM + SNES core, waits for NWA memory to become
 * readable, then starts polling.
 */
class RetroArchBackend : EmulatorBackend {

    override val name: String = "retroarch"
    override var isConnected: Boolean = false
        private set

    private var nwa: RetroArchNwaClient? = null
    private var sessionActive = false
    private var frameCounter = 0
    private var retroArchProcess: Process? = null
    private var consecutiveFailures = 0
    private var lastSuccessfulPollFrame = 0

    override suspend fun connect(): EmulatorCapabilities {
        val settings = AppConfig.load()
        val port = settings.retroArchNwaPort
        val client = RetroArchNwaClient(port = port)
        client.connect()
        nwa = client
        isConnected = true
        return EmulatorCapabilities(
            backendName = "RetroArch (NWA)",
            supportsFrames = false,
            supportsMemoryAccess = true,
            supportsSaveStates = false,
        )
    }

    override suspend fun disconnect() {
        sessionActive = false
        nwa?.disconnect()
        nwa = null
        isConnected = false
    }

    override suspend fun startSession(config: SessionConfig): StepResult {
        val client = nwa ?: throw IllegalStateException("Not connected")
        frameCounter = 1

        val settings = AppConfig.load()
        val retroArchExec = settings.retroArchPath
        val romPath = config.romPath
        val corePath = settings.retroArchCorePath
            ?.takeIf { it.isNotBlank() }
            ?: discoverSnesCore()

        if (retroArchExec != null && romPath != null) {
            launchRetroArch(retroArchExec, romPath, corePath)
            // Give RetroArch a moment to start binding its NWA port
            delay(2000)
            // Recreate the UDP socket — the old one may have received ICMP
            // port-unreachable errors that poison future receives on macOS
            client.reconnect()
        }

        // Try to wait for NWA memory — but don't block session start if it's slow.
        // RetroArch can take a long time to map memory when freshly launched.
        val reachable = waitForNwaMemory(client, maxAttempts = 10)
        sessionActive = true

        val snap = pollSnapshot(client)
        val message = if (reachable) {
            "Connected to RetroArch (NWA)"
        } else {
            "RetroArch session started — waiting for memory map to initialize..."
        }
        return StepResult(
            session = SessionState(active = true, frameCounter = frameCounter),
            snapshot = snap,
            message = message,
        )
    }

    override suspend fun closeSession(): StepResult {
        sessionActive = false
        killRetroArch()
        return StepResult(
            session = SessionState(active = false, paused = true, frameCounter = frameCounter),
            message = "Session closed",
        )
    }

    override suspend fun step(input: EmulatorInput): StepResult {
        val client = nwa ?: throw IllegalStateException("Not connected")
        if (!sessionActive) throw IllegalStateException("No active session")
        frameCounter++
        val snap = pollSnapshot(client)
        return StepResult(
            session = SessionState(active = true, frameCounter = frameCounter),
            snapshot = snap,
        )
    }

    override suspend fun snapshot(): GameSnapshot {
        val client = nwa ?: throw IllegalStateException("Not connected")
        frameCounter++
        return pollSnapshot(client)
    }

    override suspend fun saveState(name: String) {
        throw UnsupportedOperationException("Save states not supported via NWA. Use RetroArch's built-in hotkeys.")
    }

    override suspend fun loadState(name: String): StepResult {
        throw UnsupportedOperationException("Load states not supported via NWA. Use RetroArch's built-in hotkeys.")
    }

    override suspend fun listStates(): List<StateInfo> = emptyList()

    override suspend fun readMemory(address: Int, size: Int): ByteArray {
        val client = nwa ?: throw IllegalStateException("Not connected")
        return client.readMemory(address, size)
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) {
        val client = nwa ?: throw IllegalStateException("Not connected")
        client.writeMemory(address, data)
    }

    override fun close() {
        sessionActive = false
        killRetroArch()
        nwa?.disconnect()
        nwa = null
        isConnected = false
    }

    /**
     * Kill any existing RetroArch processes so the NWA port is free for our new instance.
     * Without this, stale RetroArch processes from previous sessions receive our NWA
     * reads and return old game data.
     */
    private suspend fun killExistingRetroArch() = withContext(Dispatchers.IO) {
        val os = System.getProperty("os.name", "").lowercase()
        val cmd = when {
            os.contains("mac") || os.contains("linux") -> arrayOf("pkill", "-f", "RetroArch")
            os.contains("win") -> arrayOf("taskkill", "/F", "/IM", "retroarch.exe")
            else -> return@withContext
        }
        try {
            val proc = Runtime.getRuntime().exec(cmd)
            proc.waitFor()
            if (proc.exitValue() == 0) {
                println("[RetroArch] Killed existing RetroArch process(es)")
                // Give OS time to release the UDP port
                kotlinx.coroutines.delay(500)
            }
        } catch (e: Exception) {
            // pkill returns non-zero if no processes matched — that's fine
        }
    }

    private fun killRetroArch() {
        retroArchProcess?.let { proc ->
            if (proc.isAlive) {
                println("[RetroArch] Terminating RetroArch process")
                proc.destroy()
            }
        }
        retroArchProcess = null
    }

    // ── Snapshot polling ────────────────────────────────────────────────────

    private suspend fun pollSnapshot(client: RetroArchNwaClient): GameSnapshot {
        return try {
            val roomIdBytes = client.readMemory(ROOM_ID, 2)
            val gameStateBytes = client.readMemory(GAME_STATE, 2)
            val healthBytes = client.readMemory(HEALTH, 2)
            val maxHealthBytes = client.readMemory(MAX_HEALTH, 2)
            val samusXBytes = client.readMemory(SAMUS_X, 2)
            val samusYBytes = client.readMemory(SAMUS_Y, 2)
            val itemsBytes = client.readMemory(COLLECTED_ITEMS, 2)
            val beamsBytes = client.readMemory(COLLECTED_BEAMS, 2)
            val missilesBytes = client.readMemory(MISSILES, 2)
            val maxMissilesBytes = client.readMemory(MAX_MISSILES, 2)
            val supersBytes = client.readMemory(SUPER_MISSILES, 2)
            val maxSupersBytes = client.readMemory(MAX_SUPER_MISSILES, 2)
            val pbBytes = client.readMemory(POWER_BOMBS, 2)
            val maxPbBytes = client.readMemory(MAX_POWER_BOMBS, 2)
            val reserveBytes = client.readMemory(RESERVE_ENERGY, 2)
            val maxReserveBytes = client.readMemory(MAX_RESERVE_ENERGY, 2)

            val gameState = readWord(gameStateBytes)
            val roomId = readWord(roomIdBytes)
            val hp = readWord(healthBytes)
            val sx = readWord(samusXBytes)
            val sy = readWord(samusYBytes)

            val wasFirstSuccess = lastSuccessfulPollFrame == 0
            if (consecutiveFailures > 0) {
                println("[RetroArch] NWA recovered after $consecutiveFailures failures")
            }
            consecutiveFailures = 0
            lastSuccessfulPollFrame = frameCounter

            // Log first successful poll and then periodically (~5 seconds)
            if (wasFirstSuccess || frameCounter % 50 == 0) {
                println("[RetroArch] Poll OK: room=0x${roomId.toString(16).uppercase()} hp=$hp pos=($sx,$sy) items=0x${readWord(itemsBytes).toString(16)} beams=0x${readWord(beamsBytes).toString(16)}")
            }

            GameSnapshot(
                frameCounter = frameCounter,
                roomId = roomId,
                gameState = gameState,
                health = hp,
                maxHealth = readWord(maxHealthBytes),
                samusX = sx,
                samusY = sy,
                collectedItems = readWord(itemsBytes),
                collectedBeams = readWord(beamsBytes),
                missiles = readWord(missilesBytes),
                maxMissiles = readWord(maxMissilesBytes),
                superMissiles = readWord(supersBytes),
                maxSuperMissiles = readWord(maxSupersBytes),
                powerBombs = readWord(pbBytes),
                maxPowerBombs = readWord(maxPbBytes),
                reserveEnergy = readWord(reserveBytes),
                maxReserveEnergy = readWord(maxReserveBytes),
                doorTransition = gameState in 0x06..0x0B,
            )
        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures <= 3 || consecutiveFailures % 10 == 0) {
                System.err.println("[RetroArch] Snapshot poll failed ($consecutiveFailures): ${e.message}")
            }
            // After 10 consecutive failures (~1 second), mark session as terminated
            if (consecutiveFailures >= 10) {
                return GameSnapshot(frameCounter = frameCounter, terminated = true)
            }
            GameSnapshot(frameCounter = frameCounter)
        }
    }

    // ── RetroArch launcher ──────────────────────────────────────────────────

    private suspend fun launchRetroArch(execPath: String, romPath: String, corePath: String?) {
        retroArchProcess?.let { proc ->
            if (proc.isAlive) return
        }

        // Kill any existing RetroArch processes that may be holding the NWA port
        killExistingRetroArch()

        withContext(Dispatchers.IO) {
            val execFile = File(execPath)
            if (!execFile.exists()) {
                throw IllegalStateException("RetroArch not found at: $execPath")
            }

            val cmd = mutableListOf(execPath)
            if (corePath != null) {
                val coreFile = File(corePath)
                if (coreFile.exists()) {
                    cmd.add("-L")
                    cmd.add(corePath)
                    println("[RetroArch] Using core: $corePath")
                } else {
                    System.err.println("[RetroArch] Core not found: $corePath (launching without -L)")
                }
            } else {
                System.err.println("[RetroArch] No SNES core found. RetroArch may not load the ROM automatically.")
            }
            cmd.add(romPath)

            println("[RetroArch] Launching: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd)
            pb.inheritIO()
            retroArchProcess = pb.start()
        }
    }

    /**
     * Wait for NWA memory reads to actually return data (not "no memory map").
     * RetroArch responds to NWA immediately but the memory map isn't ready
     * until the core fully loads the ROM.
     */
    private suspend fun waitForNwaMemory(client: RetroArchNwaClient, maxAttempts: Int = 10): Boolean {
        for (attempt in 1..maxAttempts) {
            try {
                val result = client.readMemory(GAME_STATE, 2)
                if (result.size == 2) {
                    println("[RetroArch] NWA memory accessible after attempt $attempt")
                    return true
                }
            } catch (e: Exception) {
                println("[RetroArch] NWA probe attempt $attempt: ${e.javaClass.simpleName}: ${e.message}")
                // If we got a PortUnreachable or similar socket error, recreate the socket
                if (e is java.net.PortUnreachableException || e is java.net.SocketException) {
                    client.reconnect()
                }
            }
            delay(1000)
        }
        return false
    }

    companion object {
        private const val ROOM_ID = 0x7E079B
        private const val GAME_STATE = 0x7E0998
        private const val HEALTH = 0x7E09C2
        private const val MAX_HEALTH = 0x7E09C4
        private const val MISSILES = 0x7E09C6
        private const val MAX_MISSILES = 0x7E09C8
        private const val SUPER_MISSILES = 0x7E09CA
        private const val MAX_SUPER_MISSILES = 0x7E09CC
        private const val POWER_BOMBS = 0x7E09CE
        private const val MAX_POWER_BOMBS = 0x7E09D0
        private const val RESERVE_ENERGY = 0x7E09D6
        private const val MAX_RESERVE_ENERGY = 0x7E09D4
        private const val COLLECTED_ITEMS = 0x7E09A4
        private const val COLLECTED_BEAMS = 0x7E09A8
        private const val SAMUS_X = 0x7E0AF6
        private const val SAMUS_Y = 0x7E0AFA

        private fun readWord(bytes: ByteArray): Int {
            if (bytes.size < 2) return 0
            return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        }

        fun defaultRetroArchPath(): String? {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("mac") -> "/Applications/RetroArch.app/Contents/MacOS/RetroArch"
                os.contains("win") -> "C:\\RetroArch-Win64\\retroarch.exe"
                os.contains("linux") -> "/usr/bin/retroarch"
                else -> null
            }
        }

        /**
         * Auto-discover a SNES libretro core in the standard RetroArch cores directory.
         * Prefers snes9x, falls back to bsnes.
         */
        fun discoverSnesCore(): String? {
            val os = System.getProperty("os.name", "").lowercase()
            val coresDir = when {
                os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support/RetroArch/cores")
                os.contains("win") -> File("C:\\RetroArch-Win64\\cores")
                os.contains("linux") -> File(System.getProperty("user.home"), ".config/retroarch/cores")
                else -> return null
            }
            if (!coresDir.isDirectory) return null

            val ext = when {
                os.contains("mac") -> ".dylib"
                os.contains("win") -> ".dll"
                else -> ".so"
            }

            // Prefer bsnes (NWA works reliably), then snes9x as fallback
            val candidates = listOf("bsnes_mercury_balanced_libretro", "bsnes_mercury_accuracy_libretro", "bsnes_libretro", "snes9x_libretro")
            for (name in candidates) {
                val coreFile = File(coresDir, "$name$ext")
                if (coreFile.exists()) return coreFile.absolutePath
            }
            return null
        }
    }
}
