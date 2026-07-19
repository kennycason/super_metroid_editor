package com.supermetroid.editor.emulator

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.Executors

private val libretroBackendLog = KotlinLogging.logger {}

/**
 * EmulatorBackend implementation using an in-process libretro SNES core.
 * Video frames pass directly via [frameHolder] — no Base64, no TCP.
 * All libretro calls run on a single dedicated thread.
 */
class LibretroBackend(
    private val audioEnabledOverride: Boolean? = null,
    stateDirOverride: File? = null,
) : EmulatorBackend {

    override val name: String = "libretro"
    override var isConnected: Boolean = false
        private set

    val frameHolder = FrameHolder()

    var audioMuted: Boolean
        get() = audio?.muted ?: false
        set(value) { audio?.muted = value }

    var audioVolume: Float
        get() = audio?.volume ?: 1.0f
        set(value) { audio?.volume = value }

    /** True when audio buffer has room — emulator is running ahead of real-time */
    val audioHasHeadroom: Boolean
        get() = audio?.hasHeadroom() ?: true

    private var core: LibretroCore? = null
    private var audio: LibretroAudioOutput? = null
    private val emuThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "libretro-emu").apply { isDaemon = true }
    }

    private var sessionActive = false
    private var frameCounter = 0
    private var currentRomPath: String? = null
    private var frameImage: BufferedImage? = null
    private var lastSaveRamHash: Int? = null
    private var lastSaveRamPersistFrame = 0
    private var lastPersistedSaveRamValidSlots = 0
    private var lastSkippedSaveRamHash: Int? = null

    private var stateDir: File = (stateDirOverride
        ?: File(File(File(System.getProperty("user.home"), ".smedit"), "states"), "libretro")).apply { mkdirs() }

    /** Update the save state directory (e.g. when switching projects). */
    fun setStateDir(dir: File) {
        val nextDir = dir.absoluteFile
        if (sessionActive && nextDir.path != stateDir.absoluteFile.path) {
            persistSaveRamIfChangedSync()
        }
        stateDir = nextDir.apply { mkdirs() }
        core?.let { c ->
            runCatching {
                emuThread.submit { c.setSaveDirectory(stateDir.absolutePath) }.get()
            }
        }
    }

    // ── EmulatorBackend interface ──────────────────────────────────────────

    override suspend fun connect(): EmulatorCapabilities {
        val settings = AppConfig.load()
        val corePath = LibretroCoreDiscovery.findCore(settings.libretroCorePath)
            ?: throw IllegalStateException(
                "No SNES libretro core found. Run: ./gradlew buildLibretroCore\n" +
                    "Or set SMEDIT_LIBRETRO_CORE=/path/to/snes9x_libretro${LibretroCoreDiscovery.coreExtension}"
            )

        val audioEnabled = audioEnabledOverride ?: settings.libretroAudioEnabled
        val c = LibretroCore(corePath)
        var startedAudio: LibretroAudioOutput? = null
        try {
            onEmuThread { c.init() }
            if (audioEnabled) {
                startedAudio = LibretroAudioOutput().also { it.start() }
            }
            val sysInfo = onEmuThread { c.getSystemInfo() }
            core = c
            audio = startedAudio
            isConnected = true
            return EmulatorCapabilities(
                backendName = "libretro (${sysInfo.getLibraryName() ?: "unknown"})",
                supportsFrames = true,
                supportsMemoryAccess = true,
                supportsSaveStates = true,
            )
        } catch (e: Exception) {
            runCatching { startedAudio?.close() }
            runCatching { onEmuThread { c.close() } }
            throw e
        }
    }

    override suspend fun disconnect() {
        persistSaveRamIfChanged()
        sessionActive = false
        currentRomPath = null
        runCatching { onEmuThread { core?.close() } }
        core = null
        audio?.close()
        audio = null
        frameHolder.clear()
        frameImage = null
        isConnected = false
    }

    override suspend fun startSession(config: SessionConfig): StepResult {
        val c = core ?: throw IllegalStateException("Not connected")
        val romPath = config.romPath
            ?: throw IllegalArgumentException("romPath is required for libretro backend")
        persistSaveRamIfChanged()
        val initialState = config.stateName
            ?.let { stateName ->
                val stateFile = File(stateDir, "$stateName.state")
                if (stateFile.isFile) {
                    withContext(Dispatchers.IO) { stateFile.readBytes() }
                } else {
                    null
                }
            }
        val persistedSaveRam = readPersistedSaveRam()
        val saveRamToLoad = persistedSaveRam?.takeIf(SuperMetroidSaveRam::isLoadable)
        lastSaveRamHash = saveRamToLoad?.contentHashCode()
        var loadedPersistedSaveRam = false

        val capture = onEmuThread {
            val loaded = c.loadGame(romPath)
            check(loaded) { "Failed to load ROM: $romPath" }
            if (initialState != null) {
                c.unserializeState(initialState)
            }
            if (saveRamToLoad != null) {
                loadedPersistedSaveRam = c.writeSaveRam(saveRamToLoad)
            }
            c.run()
            captureStep(c, includeFrame = true)
        }

        currentRomPath = romPath
        frameCounter = 1
        lastSaveRamPersistFrame = frameCounter
        sessionActive = true
        pushFrame(capture.snapshot.frame)
        audio?.writeSamples(capture.audioSamples)
        if (saveRamToLoad == null) {
            persistSaveRamIfChanged()
        }

        val message = if (loadedPersistedSaveRam) "Session started (battery save loaded)" else "Session started"
        return buildStepResult(capture.snapshot, message)
    }

    override suspend fun closeSession(): StepResult {
        persistSaveRamIfChanged()
        sessionActive = false
        onEmuThread { core?.unloadGame() }
        lastSaveRamHash = null
        frameHolder.clear()
        frameImage = null
        return StepResult(
            session = SessionState(active = false, paused = true, frameCounter = frameCounter),
            message = "Session closed",
        )
    }

    override suspend fun step(input: EmulatorInput): StepResult {
        val c = core ?: throw IllegalStateException("Not connected")
        if (!sessionActive) throw IllegalStateException("No active session")
        val repeat = input.repeat.coerceAtLeast(1)

        val capture = onEmuThread {
            c.setInput(0, input.buttons)
            repeat(repeat) {
                c.run()
            }
            captureStep(c, includeFrame = input.includeFrame)
        }
        frameCounter += repeat
        maybePersistSaveRam()

        pushFrame(capture.snapshot.frame)
        audio?.writeSamples(capture.audioSamples)

        return buildStepResult(capture.snapshot)
    }

    override suspend fun snapshot(): GameSnapshot {
        val c = core ?: throw IllegalStateException("Not connected")
        return buildSnapshot(onEmuThread { c.captureRuntimeSnapshot() })
    }

    override suspend fun saveState(name: String) {
        val c = core ?: throw IllegalStateException("Not connected")
        val data = onEmuThread { c.serializeState() }
        withContext(Dispatchers.IO) {
            stateDir.mkdirs()
            File(stateDir, "$name.state").writeBytes(data)
        }
        persistSaveRamIfChanged()
    }

    override suspend fun loadState(name: String): StepResult {
        val c = core ?: throw IllegalStateException("Not connected")
        val stateFile = File(stateDir, "$name.state")
        if (!stateFile.isFile) throw IllegalStateException("State not found: $name")

        persistSaveRamIfChanged()
        val data = withContext(Dispatchers.IO) { stateFile.readBytes() }
        val persistedSaveRam = readPersistedSaveRam()
        val saveRamToLoad = persistedSaveRam?.takeIf(SuperMetroidSaveRam::isLoadable)
        lastSaveRamHash = saveRamToLoad?.contentHashCode()
        val romPath = currentRomPath ?: throw IllegalStateException("No ROM loaded for libretro state restore")
        val capture = onEmuThread {
            if (!c.isGameLoaded()) {
                val loaded = c.loadGame(romPath)
                check(loaded) { "Failed to load ROM: $romPath" }
            }
            c.unserializeState(data)
            if (saveRamToLoad != null) {
                c.writeSaveRam(saveRamToLoad)
            }
            c.run()
            captureStep(c, includeFrame = true)
        }
        frameCounter++
        lastSaveRamPersistFrame = frameCounter
        sessionActive = true
        pushFrame(capture.snapshot.frame)
        audio?.writeSamples(capture.audioSamples)
        if (saveRamToLoad == null) {
            persistSaveRamIfChanged()
        }

        return buildStepResult(capture.snapshot, "Loaded $name")
    }

    override suspend fun listStates(): List<StateInfo> {
        return withContext(Dispatchers.IO) {
            stateDir.listFiles { f -> f.extension == "state" }
                ?.map { StateInfo(name = it.nameWithoutExtension, path = it.absolutePath) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }
    }

    override suspend fun readMemory(address: Int, size: Int): ByteArray {
        val c = core ?: throw IllegalStateException("Not connected")
        return onEmuThread { c.readWram(address, size) }
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) {
        val c = core ?: throw IllegalStateException("Not connected")
        onEmuThread { c.writeWram(address, data) }
    }

    override fun close() {
        persistSaveRamIfChangedSync()
        sessionActive = false
        currentRomPath = null
        core?.let { c ->
            try { emuThread.submit { c.close() }.get() } catch (_: Exception) {}
        }
        core = null
        audio?.close()
        audio = null
        frameHolder.clear()
        frameImage = null
        isConnected = false
        emuThread.shutdown()
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private data class StepCapture(
        val snapshot: LibretroCore.RuntimeSnapshot,
        val audioSamples: ShortArray,
    )

    private fun captureStep(c: LibretroCore, includeFrame: Boolean): StepCapture {
        return StepCapture(
            snapshot = c.captureRuntimeSnapshot(includeFrame = includeFrame),
            audioSamples = c.drainAudio(),
        )
    }

    private fun pushFrame(frame: LibretroCore.FrameSnapshot?) {
        if (frame == null || frame.width <= 0 || frame.height <= 0 || frame.pixels.isEmpty()) return

        val image = frameImage
            ?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).also { frameImage = it }
        val raster = (image.raster.dataBuffer as DataBufferInt).data
        System.arraycopy(frame.pixels, 0, raster, 0, frame.pixels.size)
        frameHolder.pushFrame(image.toComposeImageBitmap())
    }

    private fun buildSnapshot(snapshot: LibretroCore.RuntimeSnapshot): GameSnapshot {
        val wram = snapshot.wram
        return GameSnapshot(
            frameCounter = frameCounter,
            roomId = wram?.readWord(0x079B),
            gameState = wram?.readWord(0x0998),
            health = wram?.readWord(0x09C2),
            maxHealth = wram?.readWord(0x09C4),
            missiles = wram?.readWord(0x09C6),
            maxMissiles = wram?.readWord(0x09C8),
            superMissiles = wram?.readWord(0x09CA),
            maxSuperMissiles = wram?.readWord(0x09CC),
            powerBombs = wram?.readWord(0x09CE),
            maxPowerBombs = wram?.readWord(0x09D0),
            reserveEnergy = wram?.readWord(0x09D4),
            maxReserveEnergy = wram?.readWord(0x09D6),
            collectedItems = wram?.readWord(0x09A4) ?: 0,
            collectedBeams = wram?.readWord(0x09A8) ?: 0,
            samusX = wram?.readWord(0x0AF6),
            samusY = wram?.readWord(0x0AFA),
            doorTransition = wram?.readWord(0x0998)?.let { it in 0x09..0x0B } ?: false,
            frameWidth = snapshot.frameWidth,
            frameHeight = snapshot.frameHeight,
        )
    }

    private fun buildStepResult(snapshot: LibretroCore.RuntimeSnapshot, message: String? = null): StepResult {
        return StepResult(
            session = SessionState(
                active = sessionActive,
                paused = !sessionActive,
                frameCounter = frameCounter,
            ),
            snapshot = buildSnapshot(snapshot),
            message = message,
        )
    }

    private suspend fun readPersistedSaveRam(): ByteArray? {
        return withContext(Dispatchers.IO) {
            val file = saveRamFile()
            if (!file.isFile) {
                lastPersistedSaveRamValidSlots = 0
                return@withContext null
            }
            val data = file.readBytes().takeIf { it.isNotEmpty() }
            val validSlots = data?.let(SuperMetroidSaveRam::validSlotCount) ?: 0
            lastPersistedSaveRamValidSlots = validSlots
            if (data != null) {
                if (validSlots > 0) {
                    libretroBackendLog.info {
                        "[SRAM] Found persisted battery save: ${file.absolutePath} (${data.size} bytes, validSlots=$validSlots)"
                    }
                } else {
                    libretroBackendLog.info {
                        "[SRAM] Ignoring persisted battery save with no valid Super Metroid slots: " +
                            "${file.absolutePath} (${data.size} bytes)"
                    }
                }
            }
            data
        }
    }

    private suspend fun maybePersistSaveRam() {
        if (frameCounter - lastSaveRamPersistFrame < SAVE_RAM_AUTOSAVE_INTERVAL_FRAMES) return
        lastSaveRamPersistFrame = frameCounter
        persistSaveRamIfChanged()
    }

    private suspend fun persistSaveRamIfChanged() {
        val c = core ?: return
        val data = runCatching { onEmuThread { c.readSaveRam() } }.getOrNull()
            ?.takeIf(::shouldPersistSaveRam)
            ?: return
        persistSaveRamBytesIfChanged(data)
    }

    private fun persistSaveRamIfChangedSync() {
        val c = core ?: return
        val data = try {
            emuThread.submit<ByteArray> { c.readSaveRam() }.get()
        } catch (_: Exception) {
            null
        }?.takeIf(::shouldPersistSaveRam) ?: return
        persistSaveRamBytesIfChangedSync(data)
    }

    private suspend fun persistSaveRamBytesIfChanged(data: ByteArray) {
        val hash = data.contentHashCode()
        if (hash == lastSaveRamHash) return
        withContext(Dispatchers.IO) {
            stateDir.mkdirs()
            saveRamFile().writeBytes(data)
        }
        lastSaveRamHash = hash
        lastPersistedSaveRamValidSlots = SuperMetroidSaveRam.validSlotCount(data)
        lastSkippedSaveRamHash = null
        libretroBackendLog.info {
            "[SRAM] Persisted battery save: ${saveRamFile().absolutePath} (${data.size} bytes, " +
                "validSlots=$lastPersistedSaveRamValidSlots)"
        }
    }

    private fun persistSaveRamBytesIfChangedSync(data: ByteArray) {
        val hash = data.contentHashCode()
        if (hash == lastSaveRamHash) return
        stateDir.mkdirs()
        saveRamFile().writeBytes(data)
        lastSaveRamHash = hash
        lastPersistedSaveRamValidSlots = SuperMetroidSaveRam.validSlotCount(data)
        lastSkippedSaveRamHash = null
        libretroBackendLog.info {
            "[SRAM] Persisted battery save: ${saveRamFile().absolutePath} (${data.size} bytes, " +
                "validSlots=$lastPersistedSaveRamValidSlots)"
        }
    }

    private fun saveRamFile(): File = File(stateDir, SAVE_RAM_FILE_NAME)

    private fun shouldPersistSaveRam(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val validSlots = SuperMetroidSaveRam.validSlotCount(data)
        if (validSlots > 0) return true

        val hash = data.contentHashCode()
        if (hash != lastSkippedSaveRamHash) {
            val reason = if (lastPersistedSaveRamValidSlots > 0) {
                "keeping existing persisted save with validSlots=$lastPersistedSaveRamValidSlots"
            } else {
                "no persisted valid save exists yet"
            }
            libretroBackendLog.info {
                "[SRAM] Skipped battery save write: runtime SRAM has no valid Super Metroid slots ($reason)"
            }
            lastSkippedSaveRamHash = hash
        }
        return false
    }

    private suspend fun <T> onEmuThread(action: () -> T): T {
        return withContext(Dispatchers.IO) {
            emuThread.submit<T> { action() }.get()
        }
    }

    companion object {
        private const val SAVE_RAM_FILE_NAME = "battery.srm"
        private const val SAVE_RAM_AUTOSAVE_INTERVAL_FRAMES = 120

        // Read a little-endian 16-bit word from a byte array
        private fun ByteArray.readWord(offset: Int): Int {
            if (offset + 1 >= size) return 0
            return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
        }
    }
}

internal object SuperMetroidSaveRam {
    private const val SAVE_RAM_SIZE = 0x2000
    private const val SLOT_COUNT = 3
    private const val SLOT_SIZE = 0x65C
    private const val SLOT_DATA_START = 0x10
    private const val PRIMARY_CHECKSUM_START = 0x00
    private const val PRIMARY_COMPLEMENT_START = 0x08
    private const val REDUNDANT_CHECKSUM_START = 0x1FF0
    private const val REDUNDANT_COMPLEMENT_START = 0x1FF8

    fun isLoadable(data: ByteArray): Boolean = validSlotCount(data) > 0

    fun validSlotCount(data: ByteArray): Int {
        if (data.size < SAVE_RAM_SIZE) return 0
        return (0 until SLOT_COUNT).count { slot -> hasValidSlot(data, slot) }
    }

    fun checksumForSlot(data: ByteArray, slot: Int): Pair<Int, Int> {
        require(slot in 0 until SLOT_COUNT) { "slot must be in 0 until $SLOT_COUNT" }
        val base = SLOT_DATA_START + SLOT_SIZE * slot
        require(data.size >= base + SLOT_SIZE) { "data is too small for slot $slot" }

        var high = 0
        var low = 0
        var offset = 0
        while (offset < SLOT_SIZE) {
            high += data[base + offset].unsigned()
            if (high > 0xFF) {
                high = high and 0xFF
                low++
            }
            low += data[base + offset + 1].unsigned()
            if (low > 0xFF) {
                low = low and 0xFF
            }
            offset += 2
        }
        return high to low
    }

    private fun hasValidSlot(data: ByteArray, slot: Int): Boolean {
        val base = SLOT_DATA_START + SLOT_SIZE * slot
        if (data.size < base + SLOT_SIZE) return false
        if (!hasSlotPayload(data, base)) return false

        val (high, low) = checksumForSlot(data, slot)
        val complementHigh = high xor 0xFF
        val complementLow = low xor 0xFF
        val primaryMatches = matchesChecksum(
            data = data,
            checksumOffset = PRIMARY_CHECKSUM_START + slot * 2,
            complementOffset = PRIMARY_COMPLEMENT_START + slot * 2,
            high = high,
            low = low,
            complementHigh = complementHigh,
            complementLow = complementLow,
        )
        val redundantMatches = matchesChecksum(
            data = data,
            checksumOffset = REDUNDANT_CHECKSUM_START + slot * 2,
            complementOffset = REDUNDANT_COMPLEMENT_START + slot * 2,
            high = high,
            low = low,
            complementHigh = complementHigh,
            complementLow = complementLow,
        )
        return primaryMatches && redundantMatches
    }

    private fun matchesChecksum(
        data: ByteArray,
        checksumOffset: Int,
        complementOffset: Int,
        high: Int,
        low: Int,
        complementHigh: Int,
        complementLow: Int,
    ): Boolean {
        if (checksumOffset + 1 >= data.size || complementOffset + 1 >= data.size) return false
        return data[checksumOffset].unsigned() == high &&
            data[checksumOffset + 1].unsigned() == low &&
            data[complementOffset].unsigned() == complementHigh &&
            data[complementOffset + 1].unsigned() == complementLow
    }

    private fun hasSlotPayload(data: ByteArray, base: Int): Boolean {
        for (offset in 0 until SLOT_SIZE) {
            if (data[base + offset].toInt() != 0) return true
        }
        return false
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
