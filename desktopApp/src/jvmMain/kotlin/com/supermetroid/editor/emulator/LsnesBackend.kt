package com.supermetroid.editor.emulator

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.supermetroid.editor.data.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO

private val lsnesLog = KotlinLogging.logger {}

/**
 * Popup-free lsnes rr2-beta25 adapter.
 *
 * SMEDIT talks to a user-installed headless worker (optional TAS extra). A
 * bundled Lua adapter provides deterministic frame stepping, screenshots, WRAM,
 * save states, movie playback, and additional Lua scripts through a file mailbox.
 */
class LsnesBackend(
    private val executableOverride: File? = null,
    stageRootOverride: File? = null,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val processStarter: (ProcessBuilder) -> Process = { it.start() },
) : EmulatorBackend, FrameProvidingBackend, StateDirectoryBackend {

    override val name: String = EmulatorBackendIds.LSNES_B25
    override var isConnected: Boolean = false
        private set
    override val frameHolder = FrameHolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val rpcLock = Any()
    private val stageRoot = stageRootOverride
        ?: File(File(System.getProperty("user.home"), ".smedit"), "lsnes/sessions")
    private var stateDir = File(File(System.getProperty("user.home"), ".smedit"), "states/lsnes-b25")
    private var executable: File? = null
    private var process: Process? = null
    private var sessionDir: File? = null
    private var mailboxDir: File? = null
    private var lastWram = ByteArray(WRAM_SIZE)
    private var frameCounter = 0
    private var sessionActive = false
    private var requestCounter = 0L

    override fun setStateDir(dir: File) {
        stateDir = dir.absoluteFile.apply { mkdirs() }
    }

    override suspend fun connect(): EmulatorCapabilities = withContext(Dispatchers.IO) {
        val configured = AppConfig.load().lsnesPath
        val worker = executableOverride ?: LsnesDiscovery.findWorker(configured)?.let(::File)
            ?: throw IllegalStateException(LsnesDiscovery.MISSING_EXTRA_MESSAGE)
        require(worker.isFile) { "lsnes b25 worker not found: ${worker.absolutePath}" }
        require(LsnesDiscovery.isWorkerExecutable(worker)) {
            "Refusing to launch '${worker.name}': stock lsnes opens windows. " +
                "Select the popup-free smedit-lsnes-worker build."
        }
        ensureWorkerLuaAvailable()
        executable = worker.absoluteFile
        isConnected = true
        EmulatorCapabilities(
            backendName = "lsnes rr2-beta25 (headless)",
            supportsFrames = true,
            supportsMemoryAccess = true,
            supportsSaveStates = true,
            supportsHostInput = true,
            supportsMoviePlayback = true,
            supportsLuaScripting = true,
        )
    }

    override suspend fun disconnect() {
        closeProcess(graceful = true)
        isConnected = false
    }

    override suspend fun startSession(config: SessionConfig): StepResult = withContext(Dispatchers.IO) {
        check(isConnected) { "Not connected" }
        val worker = executable ?: error("lsnes worker was not resolved")
        val rom = config.romPath?.let(::File)
            ?: throw IllegalArgumentException("romPath is required for lsnes b25")
        require(rom.isFile) { "ROM not found: ${rom.absolutePath}" }
        val movie = config.moviePath?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        if (movie != null) require(movie.isFile) { "TAS movie not found: ${movie.absolutePath}" }

        closeProcess(graceful = true)
        val runDir = File(stageRoot, "run-${UUID.randomUUID()}").apply { mkdirs() }
        val mailbox = File(runDir, "mailbox").apply { mkdirs() }
        File(mailbox, "outbox").mkdirs()
        val lua = extractWorkerLua(runDir)
        sessionDir = runDir
        mailboxDir = mailbox
        requestCounter = 0L
        frameCounter = 0
        lastWram = ByteArray(WRAM_SIZE)
        frameHolder.clear()

        // Live boot: --rom= (construct_rom_nofile ignores --rom-a= without --rom-type=).
        // TASVideos LSMV: --rom-a= plus a positional movie present before core construction.
        val command = mutableListOf(worker.absolutePath)
        if (movie != null) {
            command += "--rom-a=${rom.absolutePath}"
            command += "--lua=${lua.absolutePath}"
            command += movie.absolutePath
        } else {
            command += "--rom=${rom.absolutePath}"
            command += "--lua=${lua.absolutePath}"
        }

        val logFile = File(runDir, "lsnes-worker.log")
        val builder = ProcessBuilder(command)
            .directory(worker.parentFile)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
        builder.environment().apply {
            put(WORKER_DIR_ENV, mailbox.absolutePath)
            put("SMEDIT_LSNES_HEADLESS", "1")
            put("XDG_CONFIG_HOME", File(runDir, "config").absolutePath)
            put("APPDATA", File(runDir, "config").absolutePath)
            remove("DISPLAY")
            remove("WAYLAND_DISPLAY")
            remove("SDL_VIDEODRIVER")
            remove("SDL_AUDIODRIVER")
        }

        lsnesLog.info { "Starting popup-free lsnes b25 worker for ${rom.name}" }
        try {
            process = processStarter(builder)
            waitForMarker(File(mailbox, "outbox/done"), "startup")
            File(mailbox, "outbox/done").delete()

            var reply = rpcBlocking(command = "hello", includeFrame = true, includeWram = true)
            for (scriptPath in config.luaScriptPaths.filter { it.isNotBlank() }) {
                val script = File(scriptPath)
                require(script.isFile) { "Lua script not found: ${script.absolutePath}" }
                reply = rpcBlocking(
                    command = "load_script",
                    path = script.absolutePath,
                    includeFrame = false,
                    includeWram = true,
                )
            }
            val stateName = config.stateName?.trim().orEmpty()
            if (movie == null && stateName.isNotEmpty()) {
                val state = File(stateDir, "$stateName.lsmv")
                if (state.isFile) {
                    reply = rpcBlocking(command = "load_state", path = state.absolutePath)
                }
            }
            sessionActive = true
            buildStepResult(
                reply,
                if (movie == null) "lsnes b25 session started" else "Playing ${movie.name} (readonly)",
            )
        } catch (error: Exception) {
            closeProcessBlocking(graceful = false, deleteSessionDir = false)
            throw error
        }
    }

    override suspend fun closeSession(): StepResult {
        closeProcess(graceful = true)
        return StepResult(
            session = SessionState(active = false, paused = true, frameCounter = frameCounter),
            message = "lsnes b25 session closed",
        )
    }

    override suspend fun step(input: EmulatorInput): StepResult = withContext(Dispatchers.IO) {
        check(sessionActive) { "No active lsnes session" }
        val reply = rpcBlocking(
            command = "step",
            action = input.buttons,
            applyButtons = input.applyButtons,
            repeatFrames = input.repeat.coerceAtLeast(1),
            includeFrame = input.includeFrame,
            includeWram = true,
        )
        buildStepResult(reply)
    }

    override suspend fun snapshot(): GameSnapshot = withContext(Dispatchers.IO) {
        check(sessionActive) { "No active lsnes session" }
        val reply = rpcBlocking(command = "snapshot", includeFrame = true, includeWram = true)
        buildSnapshot(reply)
    }

    override suspend fun saveState(name: String) {
        withContext(Dispatchers.IO) {
            check(sessionActive) { "No active lsnes session" }
            stateDir.mkdirs()
            rpcBlocking(
                command = "save_state",
                path = File(stateDir, "$name.lsmv").absolutePath,
                includeFrame = false,
                includeWram = false,
            )
        }
    }

    override suspend fun loadState(name: String): StepResult = withContext(Dispatchers.IO) {
        check(sessionActive) { "No active lsnes session" }
        val state = File(stateDir, "$name.lsmv")
        require(state.isFile) { "State not found: $name" }
        val reply = rpcBlocking(command = "load_state", path = state.absolutePath)
        buildStepResult(reply, "Loaded $name")
    }

    override suspend fun listStates(): List<StateInfo> = withContext(Dispatchers.IO) {
        stateDir.listFiles { file -> file.isFile && file.extension.equals("lsmv", ignoreCase = true) }
            ?.map { StateInfo(it.nameWithoutExtension, it.absolutePath) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    override suspend fun readMemory(address: Int, size: Int): ByteArray = withContext(Dispatchers.IO) {
        require(size >= 0) { "size must be non-negative" }
        val offset = normalizeWramAddress(address)
        require(offset >= 0 && offset + size <= lastWram.size) { "WRAM range out of bounds" }
        lastWram.copyOfRange(offset, offset + size)
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) {
        withContext(Dispatchers.IO) {
            check(sessionActive) { "No active lsnes session" }
            val offset = normalizeWramAddress(address)
            require(offset >= 0 && offset + data.size <= WRAM_SIZE) { "WRAM range out of bounds" }
            rpcBlocking(command = "write_memory", address = offset, data = data, includeFrame = false)
        }
    }

    override fun close() {
        closeProcessBlocking(graceful = true)
        isConnected = false
    }

    private suspend fun closeProcess(graceful: Boolean) = withContext(Dispatchers.IO) {
        closeProcessBlocking(graceful)
    }

    private fun closeProcessBlocking(graceful: Boolean, deleteSessionDir: Boolean = true) {
        val activeProcess = process
        if (graceful && sessionActive && activeProcess?.isAlive == true) {
            runCatching {
                rpcBlocking(command = "quit", includeFrame = false, includeWram = false)
            }
        }
        sessionActive = false
        process = null
        if (activeProcess != null && activeProcess.isAlive) {
            val descendants = activeProcess.toHandle().descendants().toList()
            descendants.forEach { it.destroy() }
            activeProcess.destroy()
            runCatching { activeProcess.waitFor(PROCESS_EXIT_GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS) }
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (activeProcess.isAlive) activeProcess.destroyForcibly()
        }
        frameHolder.clear()
        mailboxDir = null
        val runDir = sessionDir
        sessionDir = null
        if (deleteSessionDir && runDir != null) {
            runCatching { runDir.deleteRecursively() }
        }
    }

    private fun rpcBlocking(
        command: String,
        action: List<Int>? = null,
        applyButtons: Boolean = true,
        path: String? = null,
        address: Int? = null,
        data: ByteArray? = null,
        repeatFrames: Int? = null,
        includeFrame: Boolean = true,
        includeWram: Boolean = true,
    ): LsnesReply = synchronized(rpcLock) {
        val mailbox = mailboxDir ?: error("lsnes mailbox is not active")
        val outbox = File(mailbox, "outbox")
        val done = File(outbox, "done")
        val replyFile = File(outbox, "reply.json")
        val frameFile = File(outbox, "frame.png")
        val wramFile = File(outbox, "wram.bin")
        done.delete()
        replyFile.delete()
        if (includeFrame) frameFile.delete()
        if (includeWram) wramFile.delete()

        requestCounter++
        val request = buildJsonObject {
            put("id", JsonPrimitive(requestCounter.toString()))
            put("cmd", JsonPrimitive(command))
            put("includeFrame", JsonPrimitive(includeFrame))
            put("includeWram", JsonPrimitive(includeWram))
            put("applyButtons", JsonPrimitive(applyButtons))
            path?.let { put("path", JsonPrimitive(it)) }
            address?.let { put("address", JsonPrimitive(it)) }
            repeatFrames?.let { put("repeat", JsonPrimitive(it)) }
            action?.let { values ->
                put("action", buildJsonArray {
                    repeat(12) { add(JsonPrimitive(values.getOrElse(it) { 0 })) }
                })
            }
            data?.let { bytes ->
                put("data", buildJsonArray { bytes.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } })
            }
        }
        val inbox = File(mailbox, "inbox.json")
        val temporary = File(mailbox, "inbox.json.tmp")
        temporary.writeText(request.toString())
        try {
            Files.move(
                temporary.toPath(), inbox.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), inbox.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        waitForMarker(done, command)
        val header = json.parseToJsonElement(replyFile.readText()).jsonObject
        if (header["ok"]?.jsonPrimitive?.booleanOrNull == false) {
            throw IllegalStateException(header["error"]?.jsonPrimitive?.contentOrNull ?: "lsnes $command failed")
        }
        val replyId = header["id"]?.jsonPrimitive?.contentOrNull
        check(replyId == requestCounter.toString()) {
            "Stale lsnes reply: expected $requestCounter, got $replyId"
        }
        val wram = if (includeWram && wramFile.isFile) wramFile.readBytes() else null
        if (wram != null) {
            require(wram.size == WRAM_SIZE) { "lsnes returned ${wram.size} WRAM bytes; expected $WRAM_SIZE" }
            lastWram = wram
        }
        if (includeFrame && frameFile.isFile) {
            ImageIO.read(frameFile)?.let { frameHolder.pushFrame(it.toComposeImageBitmap()) }
        }
        frameCounter = header["frame"]?.jsonPrimitive?.intOrNull ?: frameCounter
        LsnesReply(frame = frameCounter, header = header, wram = wram ?: lastWram)
    }

    private fun waitForMarker(marker: File, operation: String) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (marker.isFile) return
            val activeProcess = process
            if (activeProcess != null && !activeProcess.isAlive) {
                val logPath = sessionDir?.let { File(it, "lsnes-worker.log").absolutePath }
                throw IllegalStateException(
                    "lsnes b25 worker exited during $operation (code ${activeProcess.exitValue()}); log: $logPath"
                )
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        val logPath = sessionDir?.let { File(it, "lsnes-worker.log").absolutePath }
        throw IllegalStateException("lsnes b25 worker timed out during $operation; log: $logPath")
    }

    private fun buildStepResult(reply: LsnesReply, message: String? = null): StepResult = StepResult(
        session = SessionState(active = sessionActive, paused = !sessionActive, frameCounter = reply.frame),
        snapshot = buildSnapshot(reply),
        message = message,
    )

    private fun buildSnapshot(reply: LsnesReply): GameSnapshot {
        val wram = reply.wram
        val gameState = wram.readWord(0x0998)
        return GameSnapshot(
            frameCounter = reply.frame,
            roomId = wram.readWord(0x079B),
            gameState = gameState,
            health = wram.readWord(0x09C2),
            maxHealth = wram.readWord(0x09C4),
            missiles = wram.readWord(0x09C6),
            maxMissiles = wram.readWord(0x09C8),
            superMissiles = wram.readWord(0x09CA),
            maxSuperMissiles = wram.readWord(0x09CC),
            powerBombs = wram.readWord(0x09CE),
            maxPowerBombs = wram.readWord(0x09D0),
            reserveEnergy = wram.readWord(0x09D6),
            maxReserveEnergy = wram.readWord(0x09D4),
            collectedItems = wram.readWord(0x09A4),
            collectedBeams = wram.readWord(0x09A8),
            samusX = wram.readWord(0x0AF6),
            samusY = wram.readWord(0x0AFA),
            doorTransition = gameState in 0x09..0x0B,
            frameWidth = reply.header["width"]?.jsonPrimitive?.intOrNull ?: 256,
            frameHeight = reply.header["height"]?.jsonPrimitive?.intOrNull ?: 224,
            lastAction = reply.header["action"].asIntList(),
        )
    }

    private fun JsonPrimitive?.asInt(): Int? = this?.intOrNull

    private fun kotlinx.serialization.json.JsonElement?.asIntList(): List<Int> =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive).asInt() }.orEmpty()

    private fun normalizeWramAddress(address: Int): Int = when (address) {
        in WRAM_BASE until WRAM_BASE + WRAM_SIZE -> address - WRAM_BASE
        else -> address
    }

    private fun ByteArray.readWord(offset: Int): Int {
        if (offset < 0 || offset + 1 >= size) return 0
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ensureWorkerLuaAvailable() {
        check(javaClass.classLoader.getResource("lsnes/lsnes_worker.lua") != null) {
            "Bundled lsnes worker Lua is missing"
        }
    }

    private fun extractWorkerLua(runDir: File): File {
        val target = File(runDir, "lsnes_worker.lua")
        javaClass.classLoader.getResourceAsStream("lsnes/lsnes_worker.lua").use { input ->
            checkNotNull(input) { "Bundled lsnes worker Lua is missing" }
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private fun nullDevice(): File =
        if (System.getProperty("os.name", "").lowercase().contains("win")) File("NUL") else File("/dev/null")

    private data class LsnesReply(
        val frame: Int,
        val header: JsonObject,
        val wram: ByteArray,
    )

    companion object {
        private const val WORKER_DIR_ENV = "LSNES_WORKER_DIR"
        private const val WRAM_BASE = 0x7E0000
        private const val WRAM_SIZE = 0x2000
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val POLL_INTERVAL_MILLIS = 2L
        private const val PROCESS_EXIT_GRACE_MILLIS = 2_000L
    }
}
