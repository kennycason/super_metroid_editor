package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.emulator.EmulatorBackend
import com.supermetroid.editor.emulator.EmulatorCapabilities
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.EmulatorRegistry
import com.supermetroid.editor.emulator.FrameHolder
import com.supermetroid.editor.emulator.GameSnapshot
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.RetroArchBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.emulator.SessionState
import com.supermetroid.editor.emulator.StateInfo
import com.supermetroid.editor.emulator.StepResult
import com.supermetroid.editor.emulator.TracePoint
import com.supermetroid.editor.integration.EditorDoorExport
import com.supermetroid.editor.integration.EditorNavGraph
import com.supermetroid.editor.integration.EditorNavNode
import com.supermetroid.editor.integration.EditorRoomExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import javax.imageio.ImageIO
import kotlin.math.max

private const val SCREEN_PIXELS = 256f
private val ACTION_LABELS = listOf("B", "Y", "Select", "Start", "Up", "Down", "Left", "Right", "A", "X", "L", "R")
private val DEFAULT_SAVE_SLOTS = listOf(
    EmulatorSaveSlot("Checkpoint 1", "EditorCheckpoint01"),
    EmulatorSaveSlot("Checkpoint 2", "EditorCheckpoint02"),
    EmulatorSaveSlot("Checkpoint 3", "EditorCheckpoint03"),
    EmulatorSaveSlot("Checkpoint 4", "EditorCheckpoint04"),
)

private val AREA_ORIGINS = mapOf(
    6 to Pair(0f, 0f),    // Ceres
    0 to Pair(0f, 7f),    // Crateria
    1 to Pair(0f, 17f),   // Brinstar
    3 to Pair(18f, 10f),  // Wrecked Ship
    2 to Pair(17f, 20f),  // Norfair
    4 to Pair(33f, 18f),  // Maridia
    5 to Pair(14f, 31f),  // Tourian
)

data class WorldTracePoint(
    val roomId: Int,
    val roomName: String,
    val x: Float,
    val y: Float,
)

data class LocalRoomPoint(
    val x: Float,
    val y: Float,
)

data class EmulatorSaveSlot(
    val label: String,
    val stateName: String,
)

@Serializable
data class SaveSlotMeta(
    val roomName: String? = null,
    val etanks: Int = 0,
    val reserveTanks: Int = 0,
    val missiles: Int = 0,
    val supers: Int = 0,
    val powerBombs: Int = 0,
    val collectedItems: Int = 0,
    val collectedBeams: Int = 0,
    val timestamp: String = "",
)

data class RoomMapOverlay(
    val routeLabel: String? = null,
    val plannedRoute: List<LocalRoomPoint> = emptyList(),
    val liveTrace: List<LocalRoomPoint> = emptyList(),
    val currentPosition: LocalRoomPoint? = null,
    val focusPoint: LocalRoomPoint? = null,
    val startAnchor: LocalRoomPoint? = null,
    val targetAnchor: LocalRoomPoint? = null,
)

data class AreaBounds(
    val minMapX: Int,
    val minMapY: Int,
    val maxMapX: Int,
    val maxMapY: Int,
)

data class LoadedNavGraph(
    val graph: EditorNavGraph,
) {
    val nodesByRoomId: Map<Int, EditorNavNode> = graph.nodes.associateBy { it.roomId }
    val areaBounds: Map<Int, AreaBounds> = graph.nodes
        .groupBy { it.area }
        .mapValues { (_, nodes) ->
            AreaBounds(
                minMapX = nodes.minOf { it.mapX },
                minMapY = nodes.minOf { it.mapY },
                maxMapX = nodes.maxOf { it.mapX + it.widthScreens },
                maxMapY = nodes.maxOf { it.mapY + it.heightScreens },
            )
        }

    fun roomPoint(roomId: Int, pixelX: Int, pixelY: Int): WorldTracePoint? {
        val node = nodesByRoomId[roomId] ?: return null
        val bounds = areaBounds[node.area] ?: return null
        val origin = AREA_ORIGINS[node.area] ?: Pair(node.area * 12f, 0f)
        return WorldTracePoint(
            roomId = roomId,
            roomName = node.name,
            x = origin.first + (node.mapX - bounds.minMapX) + (pixelX / SCREEN_PIXELS),
            y = origin.second + (node.mapY - bounds.minMapY) + (pixelY / SCREEN_PIXELS),
        )
    }

    fun roomCenter(roomId: Int): WorldTracePoint? {
        val node = nodesByRoomId[roomId] ?: return null
        return roomPoint(
            roomId = roomId,
            pixelX = (node.widthScreens * SCREEN_PIXELS / 2f).toInt(),
            pixelY = (node.heightScreens * SCREEN_PIXELS / 2f).toInt(),
        )
    }
}

class EmulatorWorkspaceState(
    private val backendFactory: (() -> EmulatorBackend)? = null,
    private val roomRepository: com.supermetroid.editor.data.RoomRepository = com.supermetroid.editor.data.RoomRepository(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var backend: EmulatorBackend? = null
    var frameHolder: FrameHolder? = null
        private set
    /** True when the audio buffer has headroom (emulator is ahead of real-time playback) */
    val audioHasHeadroom: Boolean
        get() = (backend as? LibretroBackend)?.audioHasHeadroom ?: true
    private var stepInFlight = false
    private val pressedKeys = mutableSetOf<Key>()
    val gamepadManager = com.supermetroid.editor.controller.GamepadManager()
    private var lastFollowedRoomId: Int? = null
    private val roomExportCache = mutableMapOf<Int, EditorRoomExport>()
    private var currentRomPath: String? = null
    private var comboConsumedUntilRelease = false

    var navExportDir by mutableStateOf(AppConfig.load().emulatorNavExportDir)
    var followLiveRoom by mutableStateOf(AppConfig.load().emulatorFollowLiveRoom)
    var requestedControlMode by mutableStateOf("manual")
    var selectedBackendName by mutableStateOf(AppConfig.load().emulatorBackend)

    // RetroArch-specific config
    var retroArchPath by mutableStateOf(
        AppConfig.load().retroArchPath ?: RetroArchBackend.defaultRetroArchPath() ?: ""
    )
    var retroArchCorePath by mutableStateOf(
        AppConfig.load().retroArchCorePath ?: RetroArchBackend.discoverSnesCore() ?: ""
    )
    var retroArchNwaPort by mutableStateOf(AppConfig.load().retroArchNwaPort)

    /** True when using an external emulator backend (no in-editor video). */
    val isExternalBackend: Boolean get() = selectedBackendName == "retroarch"

    var statusMessage by mutableStateOf("Click Play to start the emulator.")
        internal set
    var statusMessageTimestamp by mutableStateOf(0L)
        private set

    internal fun setStatus(msg: String) {
        statusMessage = msg
        statusMessageTimestamp = System.nanoTime()
    }
    var capabilities by mutableStateOf<EmulatorCapabilities?>(null)
        private set
    var session by mutableStateOf(SessionState())
        private set
    var snapshot by mutableStateOf<GameSnapshot?>(null)
        private set
    var frameBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var recordingPath by mutableStateOf<String?>(null)
        private set
    var bridgeRoundTripMs by mutableStateOf(0f)
        private set
    var emulatedFps by mutableStateOf(0f)
        private set
    var viewportFps by mutableStateOf(0f)
        private set
    var lastStepRepeat by mutableStateOf(0)
        private set
    private var lastStepWallClockNanos = 0L
    var sessionBootStateName by mutableStateOf<String?>(null)
        private set
    var lastCheckpointSlotName by mutableStateOf<String?>(null)
        private set
    var isConnected by mutableStateOf(false)
        private set
    var isRunning by mutableStateOf(false)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var audioMuted by mutableStateOf(AppConfig.load().emulatorAudioMuted)
        private set
    var audioVolume by mutableStateOf(AppConfig.load().emulatorAudioVolume)
    var saveSlotIndex by mutableStateOf(0)

    /** Combo action queued by gamepad detection, consumed by the frame loop. */
    var pendingComboAction by mutableStateOf<String?>(null)

    var saveStates by mutableStateOf<List<StateInfo>>(emptyList())
        private set
    var slotMetadata by mutableStateOf<Map<String, SaveSlotMeta>>(emptyMap())
        private set
    var selectedStateName by mutableStateOf<String?>(null)
    var navGraph by mutableStateOf<LoadedNavGraph?>(null)
        private set

    val plannedRouteRoomIds = mutableStateListOf<Int>()
    val saveSlots: List<EmulatorSaveSlot> = DEFAULT_SAVE_SLOTS

    suspend fun loadNavGraph() {
        val navFile = File(navExportDir, "nav_graph.json")
        roomExportCache.clear()
        if (!navFile.isFile) {
            navGraph = null
            setStatus("Nav graph not found at ${navFile.absolutePath}")
            persistConfig()
            if (isConnected) configureBridge()
            return
        }
        navGraph = withContext(Dispatchers.IO) {
            json.decodeFromString(EditorNavGraph.serializer(), navFile.readText())
        }.let(::LoadedNavGraph)
        setStatus("Loaded nav graph (${navGraph?.graph?.nodes?.size ?: 0} rooms)")
        persistConfig()
        if (isConnected) configureBridge()
    }

    suspend fun connectBridge() {
        if (isConnected) return
        isBusy = true
        try {
            persistConfig()
            val b = backendFactory?.invoke()
                ?: EmulatorRegistry.create(selectedBackendName)
            backend = b
            frameHolder = (b as? LibretroBackend)?.frameHolder
            val caps = b.connect()
            capabilities = caps
            configureBridge()
            updateStateDirForProject()
            isConnected = true
            gamepadManager.init()
            setStatus("Connected to ${caps.backendName}")
            refreshInventory()
        } catch (e: Exception) {
            backend = null
            isConnected = false
            setStatus("Connection failed: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    fun disconnectBridge() {
        isRunning = false
        gamepadManager.close()
        backend?.close()
        backend = null
        frameHolder = null
        isConnected = false
        capabilities = null
        session = SessionState()
        snapshot = null
        frameBitmap = null
        bridgeRoundTripMs = 0f
        emulatedFps = 0f
        viewportFps = 0f
        lastStepRepeat = 0
        sessionBootStateName = null
        lastCheckpointSlotName = null
        setStatus("Disconnected")
    }

    suspend fun refreshInventory(silent: Boolean = false) {
        val b = backend ?: return
        isBusy = true
        try {
            val states = b.listStates()
            saveStates = states.sortedBy { it.name.lowercase() }
            // Load metadata for all states
            val metaMap = mutableMapOf<String, SaveSlotMeta>()
            val metaDir = projectStateDir()
            withContext(Dispatchers.IO) {
                for (state in states) {
                    val metaFile = File(metaDir, "${state.name}.meta.json")
                    if (metaFile.isFile) {
                        runCatching {
                            metaMap[state.name] = json.decodeFromString(SaveSlotMeta.serializer(), metaFile.readText())
                        }
                    }
                }
            }
            slotMetadata = metaMap
            if (!silent) setStatus("Inventory refreshed")
        } catch (e: Exception) {
            setStatus("Inventory refresh failed: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    suspend fun startSession() {
        val b = backend ?: return
        val stateName = selectedStateName
        isBusy = true
        try {
            val result = b.startSession(
                SessionConfig(
                    romPath = currentRomPath,
                    stateName = stateName,
                    navExportDir = navExportDir,
                    controlMode = requestedControlMode,
                )
            )
            applyStepResult(result)
            sessionBootStateName = stateName
            isRunning = true
            bridgeRoundTripMs = 0f
            emulatedFps = 0f
            viewportFps = 0f
            lastStepRepeat = 0
            lastStepWallClockNanos = 0L
            setStatus(result.message ?: "Session started: $stateName")
        } catch (e: Exception) {
            setStatus("Failed to start session: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    /** Clear saved state selection so the next startSession boots fresh (no auto-load). */
    fun clearSavedStateSelection() {
        selectedStateName = null
        sessionBootStateName = null
    }

    suspend fun closeSession() {
        val b = backend ?: return
        isRunning = false
        isBusy = true
        try {
            val result = b.closeSession()
            applyStepResult(result)
            if (!session.active) {
                snapshot = null
                frameBitmap = null
                bridgeRoundTripMs = 0f
                emulatedFps = 0f
                viewportFps = 0f
                lastStepRepeat = 0
            }
            setStatus(result.message ?: "Session closed")
        } catch (e: Exception) {
            setStatus("Failed to close session: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    suspend fun refreshSnapshot() {
        val b = backend ?: return
        if (!session.active) return
        if (isBusy || stepInFlight) return
        isBusy = true
        try {
            val snap = b.snapshot()
            applySnapshot(snap)
        } catch (e: Exception) {
            setStatus("Snapshot failed: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    suspend fun stepFrame(
        repeat: Int = 1,
        includeFrame: Boolean = true,
        includeTrace: Boolean = true,
    ) {
        val b = backend ?: return
        if (!session.active || isBusy || stepInFlight) return
        stepInFlight = true
        val previousFrameCounter = session.frameCounter
        val startedAt = System.nanoTime()
        try {
            val result = b.step(
                EmulatorInput(
                    buttons = currentAction(),
                    repeat = repeat,
                    includeFrame = includeFrame,
                    includeTrace = includeTrace,
                )
            )
            applyStepResult(result)
            updateLoopMetrics(
                previousFrameCounter = previousFrameCounter,
                currentFrameCounter = result.session.frameCounter,
                elapsedNanos = System.nanoTime() - startedAt,
                repeat = repeat,
                frameIncluded = includeFrame,
            )
        } catch (e: Exception) {
            setStatus("Step failed: ${e.message}")
            isRunning = false
        } finally {
            stepInFlight = false
        }
    }

    /**
     * Poll the external emulator for a snapshot (used by retroarch polling loop).
     * Unlike stepFrame, this doesn't send input — just reads game state.
     */
    suspend fun pollExternalSnapshot() {
        val b = backend ?: return
        if (!session.active || isBusy) return
        try {
            val snap = b.snapshot()
            snapshot = snap
            if (snap.terminated) {
                // RetroArch disconnected (too many timeouts)
                isRunning = false
                session = SessionState(active = false, paused = true)
                setStatus("RetroArch disconnected (NWA timeout)")
            }
        } catch (e: Exception) {
            setStatus("Poll failed: ${e.message}")
        }
    }

    suspend fun saveQuickState(name: String = "EditorQuickSave") {
        val b = backend ?: return
        if (!session.active) return
        selectedStateName = name
        isBusy = true
        try {
            b.saveState(name)
            // Save metadata alongside state
            val snap = snapshot
            val roomName = snap?.roomName
                ?: snap?.roomId?.let { rid -> runCatching { roomRepository.getRoomById(rid)?.name }.getOrNull() }
                ?: snap?.roomId?.let { rid -> navGraph?.nodesByRoomId?.get(rid)?.name }
                ?: snap?.roomId?.let { "0x${it.toString(16).uppercase()}" }
            val meta = SaveSlotMeta(
                roomName = roomName,
                etanks = (snap?.maxHealth ?: 99).let { (it - 99) / 100 },
                reserveTanks = (snap?.maxReserveEnergy ?: 0) / 100,
                missiles = snap?.maxMissiles ?: 0,
                supers = snap?.maxSuperMissiles ?: 0,
                powerBombs = snap?.maxPowerBombs ?: 0,
                collectedItems = snap?.collectedItems ?: 0,
                collectedBeams = snap?.collectedBeams ?: 0,
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()),
            )
            withContext(Dispatchers.IO) {
                val metaDir = projectStateDir().apply { mkdirs() }
                File(metaDir, "$name.meta.json").writeText(json.encodeToString(SaveSlotMeta.serializer(), meta))
            }
            slotMetadata = slotMetadata + (name to meta)
            val slotNum = name.removePrefix("slot_")
            val room = roomName ?: "?"
            setStatus("Saved $slotNum: $room E${meta.etanks} M${meta.missiles} SM${meta.supers} PB${meta.powerBombs}")
            refreshInventory(silent = true)
        } catch (e: Exception) {
            setStatus("Save failed: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    suspend fun loadSelectedState() {
        val b = backend ?: return
        val stateName = selectedStateName ?: return
        loadStateByName(b, stateName)
    }

    suspend fun loadNamedState(name: String) {
        val b = backend ?: return
        if (!session.active) return
        selectedStateName = name
        loadStateByName(b, name)
    }

    suspend fun resetToSessionStart() {
        val b = backend ?: return
        val stateName = sessionBootStateName ?: selectedStateName ?: return
        selectedStateName = stateName
        loadStateByName(b, stateName)
    }

    suspend fun saveSlot(slot: EmulatorSaveSlot) {
        selectedStateName = slot.stateName
        rememberCheckpointSlot(slot)
        saveQuickState(slot.stateName)
    }

    suspend fun loadSlot(slot: EmulatorSaveSlot) {
        val b = backend ?: return
        if (!hasSavedSlot(slot)) {
            setStatus("${slot.label} is empty")
            return
        }
        selectedStateName = slot.stateName
        rememberCheckpointSlot(slot)
        loadStateByName(b, slot.stateName)
    }

    suspend fun saveSlot(index: Int) {
        val slot = saveSlots.getOrNull(index) ?: return
        saveSlot(slot)
    }

    suspend fun reloadLastCheckpoint() {
        val preferred = saveSlots.firstOrNull { it.stateName == lastCheckpointSlotName && hasSavedSlot(it) }
            ?: saveSlots.firstOrNull { hasSavedSlot(it) }
        if (preferred == null) {
            setStatus("No checkpoint slot saved yet")
            return
        }
        loadSlot(preferred)
    }

    /**
     * Move Samus to a specific pixel position within the current room.
     * Writes X to WRAM 0x0AF6 and Y to WRAM 0x0AFA (16-bit LE each).
     */
    suspend fun moveSamusTo(x: Int, y: Int) {
        val b = backend ?: return
        if (!session.active) {
            setStatus("No active session — start the emulator first")
            return
        }
        b.writeMemory(0x0AF6, byteArrayOf(
            (x and 0xFF).toByte(),
            ((x shr 8) and 0xFF).toByte()
        ))
        b.writeMemory(0x0AFA, byteArrayOf(
            (y and 0xFF).toByte(),
            ((y shr 8) and 0xFF).toByte()
        ))
        setStatus("Moved Samus to ($x, $y)")
    }

    suspend fun setCustomItemObtained(item: CustomItemDef, obtained: Boolean) {
        val b = backend ?: return
        if (!session.active) {
            setStatus("No active session — start the emulator first")
            return
        }
        if (item.bitMask == 0) {
            setStatus("${item.name} has no item bit configured")
            return
        }
        isBusy = true
        try {
            val address = emulatorWramAddress(item.itemWordAddress)
            val currentBytes = b.readMemory(address, 2)
            val current = readLeWord(currentBytes)
            val updated = if (obtained) {
                current or item.bitMask
            } else {
                current and item.bitMask.inv()
            } and 0xFFFF
            b.writeMemory(address, byteArrayOf((updated and 0xFF).toByte(), ((updated shr 8) and 0xFF).toByte()))
            if (item.itemWordAddress == 0x09A4 || item.itemWordAddress == 0x7E09A4) {
                snapshot = snapshot?.copy(collectedItems = updated)
            }
            setStatus("${if (obtained) "Granted" else "Removed"} ${item.name}")
        } catch (e: Exception) {
            setStatus("Failed to update ${item.name}: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    private fun emulatorWramAddress(address: Int): Int =
        if (isExternalBackend && address in 0x0000..0x1FFFF) 0x7E0000 + address else address

    private fun readLeWord(bytes: ByteArray): Int {
        if (bytes.size < 2) return 0
        return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    }

    /**
     * Warp to a room by simulating a door transition.
     * Writes the DoorDef pointer into WRAM 0x078D and sets game_state to 0x06
     * (door transition), causing the SM engine to load the target room.
     */
    suspend fun warpToDoor(doorDefPtr: Int) {
        val b = backend ?: return
        if (!session.active) {
            setStatus("No active session — start the emulator first")
            return
        }
        // Write door_def_ptr (WRAM 0x078D) — 16-bit LE pointer into bank $83
        b.writeMemory(0x078D, byteArrayOf(
            (doorDefPtr and 0xFF).toByte(),
            ((doorDefPtr shr 8) and 0xFF).toByte()
        ))
        // Set game_state (WRAM 0x0998) to 0x06 = door transition
        b.writeMemory(0x0998, byteArrayOf(0x06, 0x00))
        setStatus("Warping to door 0x${doorDefPtr.toString(16).uppercase()}")
    }

    fun hasSavedSlot(slot: EmulatorSaveSlot): Boolean {
        return saveStates.any { it.name == slot.stateName }
    }

    fun isLastCheckpointSlot(slot: EmulatorSaveSlot): Boolean {
        return slot.stateName == lastCheckpointSlotName
    }

    fun checkpointSlotHotkey(slot: EmulatorSaveSlot): String {
        val index = saveSlots.indexOfFirst { it.stateName == slot.stateName }
        return when (index) {
            0 -> "F1"
            1 -> "F2"
            2 -> "F3"
            3 -> "F4"
            else -> ""
        }
    }

    fun checkpointSlotReloadHotkey(slot: EmulatorSaveSlot): String {
        val hotkey = checkpointSlotHotkey(slot)
        return if (hotkey.isBlank()) "" else "Shift+$hotkey"
    }

    fun selectedStateInfo(): StateInfo? {
        val stateName = selectedStateName ?: return null
        return saveStates.firstOrNull { it.name == stateName }
    }

    fun sessionBootStateInfo(): StateInfo? {
        val stateName = sessionBootStateName ?: return null
        return saveStates.firstOrNull { it.name == stateName }
    }

    fun displayPath(path: String?): String? {
        val raw = path?.takeIf { it.isNotBlank() } ?: return null
        val normalized = raw.replace('\\', '/')
        val marker = "/custom_integrations/"
        val markerIndex = normalized.indexOf(marker)
        if (markerIndex >= 0) {
            return normalized.substring(markerIndex + 1)
        }
        return raw
    }

    private fun rememberCheckpointSlot(slot: EmulatorSaveSlot) {
        lastCheckpointSlotName = slot.stateName
    }

    private suspend fun loadStateByName(b: EmulatorBackend, stateName: String) {
        isBusy = true
        try {
            val result = b.loadState(stateName)
            applyStepResult(result)
            setStatus(result.message ?: "Loaded $stateName")
        } catch (e: Exception) {
            setStatus("Load failed: ${e.message}")
        } finally {
            isBusy = false
        }
    }

    suspend fun setRecording(@Suppress("UNUSED_PARAMETER") active: Boolean) {
        // Recording is not supported by the libretro backend
    }

    fun setLoopRunning(running: Boolean) {
        isRunning = running
    }

    fun toggleAudioMute() {
        val b = backend as? LibretroBackend ?: return
        audioMuted = !audioMuted
        b.audioMuted = audioMuted
        persistConfig()
    }

    fun updateAudioVolume(vol: Float) {
        audioVolume = vol.coerceIn(0f, 1f)
        (backend as? LibretroBackend)?.audioVolume = audioVolume
        persistConfig()
    }

    fun slotDisplayText(slotIndex: Int): String {
        val name = "slot_$slotIndex"
        val meta = slotMetadata[name] ?: return "[EMPTY]"
        val room = meta.roomName ?: "???"
        return "$room E${meta.etanks} M${meta.missiles} SM${meta.supers} PB${meta.powerBombs} ${meta.timestamp}"
    }

    fun getSlotMeta(slotIndex: Int): SaveSlotMeta? {
        return slotMetadata["slot_$slotIndex"]
    }

    /** Propagate current audio settings to the backend (call after reconnect). */
    fun propagateAudioState() {
        val b = backend as? LibretroBackend ?: return
        b.audioMuted = audioMuted
        b.audioVolume = audioVolume
    }

    fun updateKey(key: Key, down: Boolean) {
        when {
            down -> pressedKeys.add(key)
            else -> pressedKeys.remove(key)
        }
    }

    fun clearCheckpointModifierKeys() {
        pressedKeys.remove(Key.ShiftLeft)
        pressedKeys.remove(Key.ShiftRight)
    }

    fun currentAction(): List<Int> {
        val action = MutableList(12) { 0 }
        fun on(key: Key) = pressedKeys.contains(key)

        // Keyboard input
        if (on(Key.DirectionRight)) action[7] = 1
        if (on(Key.DirectionLeft)) action[6] = 1
        if (on(Key.DirectionUp)) action[4] = 1
        if (on(Key.DirectionDown)) action[5] = 1

        if (on(Key.Z)) action[0] = 1
        if (on(Key.X)) action[8] = 1
        if (on(Key.A)) action[1] = 1
        if (on(Key.S)) action[9] = 1
        if (on(Key.Q)) action[10] = 1
        if (on(Key.W)) action[11] = 1
        if (on(Key.Enter)) action[3] = 1
        if (on(Key.ShiftLeft) || on(Key.ShiftRight) || on(Key.Tab)) action[2] = 1

        // Gamepad input (merged — either source can activate a button)
        val pad = gamepadManager.poll()
        if (pad != null) {
            // Detect save/load combos before merging buttons.
            // R+Y+SELECT = save, L+Y+SELECT = load,
            // L+R+Y+UP = slot up, L+R+Y+DOWN = slot down
            val pL = pad[10] == 1
            val pR = pad[11] == 1
            val pY = pad[1] == 1
            val pSel = pad[2] == 1
            val pUp = pad[4] == 1
            val pDown = pad[5] == 1

            val comboActive = when {
                pL && pR && pY && pUp -> "slot_up"
                pL && pR && pY && pDown -> "slot_down"
                pR && pY && pSel && !pL -> "save"
                pL && pY && pSel && !pR -> "load"
                else -> null
            }

            if (comboActive != null && !comboConsumedUntilRelease) {
                pendingComboAction = comboActive
                comboConsumedUntilRelease = true
                setStatus(when (comboActive) {
                    "save" -> "Saving slot $saveSlotIndex..."
                    "load" -> "Loading slot $saveSlotIndex..."
                    "slot_up" -> "Slot ${(saveSlotIndex + 1) % 129}"
                    "slot_down" -> "Slot ${(saveSlotIndex - 1 + 129) % 129}"
                    else -> ""
                })
            } else if (comboActive == null) {
                comboConsumedUntilRelease = false
                for (i in action.indices) {
                    if (pad[i] == 1) action[i] = 1
                }
            }
            // While combo is held, suppress all gamepad input to emulator
        }
        // Surface gamepad connect/disconnect events
        gamepadManager.statusEvent?.let {
            setStatus(it)
            gamepadManager.statusEvent = null
        }

        // Cancel conflicting D-pad directions.
        if (action[6] == 1 && action[7] == 1) {
            action[6] = 0
            action[7] = 0
        }
        if (action[4] == 1 && action[5] == 1) {
            action[4] = 0
            action[5] = 0
        }
        return action
    }

    fun activeInputSummary(): String {
        val action = snapshot?.lastAction?.takeIf { it.isNotEmpty() } ?: currentAction()
        return inputSummary(action)
    }

    fun requestedInputSummary(): String {
        val action = snapshot?.lastRequestedAction ?: return "none"
        return inputSummary(action)
    }

    fun preSanitizeInputSummary(): String {
        val action = snapshot?.lastActionPreSanitize ?: return "none"
        return inputSummary(action)
    }

    private fun inputSummary(action: List<Int>): String {
        val active = action.mapIndexedNotNull { index, value ->
            ACTION_LABELS.getOrNull(index)?.takeIf { value != 0 }
        }
        return if (active.isEmpty()) "none" else active.joinToString(" ")
    }

    fun controllerSummary(): String {
        val currentSnapshot = snapshot
        return when {
            currentSnapshot?.controllerConnected == true && !currentSnapshot.controllerName.isNullOrBlank() ->
                currentSnapshot.controllerName!!
            currentSnapshot?.controllerConnected == true -> "connected"
            else -> "none"
        }
    }

    fun appendPlannedRoom(roomId: Int) {
        if (plannedRouteRoomIds.lastOrNull() == roomId) return
        plannedRouteRoomIds.add(roomId)
    }

    fun updateFollowLiveRoom(value: Boolean) {
        followLiveRoom = value
        persistConfig()
    }

    fun updateNavExportDir(value: String) {
        navExportDir = value
    }

    fun updateRomPath(value: String?) {
        currentRomPath = value?.trim()?.takeIf { it.isNotEmpty() }
        updateStateDirForProject()
    }

    /** Set the .smedit project file path so save states are stored alongside it. */
    var projectFilePath: String? = null
        private set

    fun updateProjectFilePath(value: String?) {
        projectFilePath = value?.trim()?.takeIf { it.isNotEmpty() }
        updateStateDirForProject()
    }

    /**
     * Compute a project-specific state directory.
     * Prefers a `_states/` folder next to the .smedit project file (stable across ROM exports).
     * Falls back to ~/.smedit/states/libretro/<slug> if no project file is set.
     */
    internal fun projectStateDir(): File {
        // If we have a project file, store states alongside it
        projectFilePath?.let { path ->
            val projectFile = File(path)
            val statesDir = File(projectFile.parentFile, "${projectFile.nameWithoutExtension}_states")
            return statesDir
        }
        // Fallback: derive from ROM path
        val projectSlug = currentRomPath?.let { File(it).nameWithoutExtension }
            ?.replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
            ?: "default"
        val base = File(System.getProperty("user.home"), ".smedit")
        return File(File(File(base, "states"), "libretro"), projectSlug)
    }

    /** Point save states at a project-specific subdirectory. */
    private fun updateStateDirForProject() {
        val b = backend as? LibretroBackend ?: return
        b.setStateDir(projectStateDir())
    }

    fun clearPlannedRoute() {
        plannedRouteRoomIds.clear()
    }

    fun updateRequestedControlMode(value: String) {
        requestedControlMode = value
    }

    fun removeLastPlannedRoom() {
        if (plannedRouteRoomIds.isNotEmpty()) plannedRouteRoomIds.removeLast()
    }

    fun liveTracePoints(): List<WorldTracePoint> {
        val loaded = navGraph ?: return emptyList()
        val currentSnapshot = snapshot ?: return emptyList()
        return currentSnapshot.trace.mapNotNull { loaded.roomPoint(it.roomId, it.x, it.y) }
    }

    fun centerOfGravity(): WorldTracePoint? {
        val points = liveTracePoints()
        if (points.isEmpty()) return null
        val roomName = points.last().roomName
        return WorldTracePoint(
            roomId = points.last().roomId,
            roomName = roomName,
            x = points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            y = points.sumOf { it.y.toDouble() }.toFloat() / points.size,
        )
    }

    fun plannedRoutePoints(): List<WorldTracePoint> {
        val loaded = navGraph ?: return emptyList()
        return plannedRouteRoomIds.mapNotNull { loaded.roomCenter(it) }
    }

    fun roomMapOverlay(room: RoomInfo?): RoomMapOverlay? {
        val currentRoom = room ?: return null
        val roomExport = roomExport(currentRoom.getRoomIdAsInt()) ?: return null
        val currentSnapshot = snapshot
        val expectedTrace = currentSnapshot?.expectedTrace
            ?.filter { it.roomId == roomExport.roomId }
            ?.map { LocalRoomPoint(x = it.x.toFloat(), y = it.y.toFloat()) }
            .orEmpty()
        val liveTrace = currentSnapshot?.trace
            ?.filter { it.roomId == roomExport.roomId }
            ?.map { LocalRoomPoint(x = it.x.toFloat(), y = it.y.toFloat()) }
            .orEmpty()
        val currentPosition = when {
            currentSnapshot?.doorTransition == true -> null
            currentSnapshot?.roomId == roomExport.roomId -> {
                val samusX = currentSnapshot.samusX
                val samusY = currentSnapshot.samusY
                if (samusX != null && samusY != null) {
                    LocalRoomPoint(samusX.toFloat(), samusY.toFloat())
                } else {
                    null
                }
            }
            liveTrace.isNotEmpty() -> liveTrace.last()
            else -> null
        }
        val plannedRoute = when {
            expectedTrace.isNotEmpty() -> expectedTrace
            else -> landingSiteRoute(roomExport)?.second.orEmpty()
        }
        val routeLabel = when {
            expectedTrace.isNotEmpty() -> currentSnapshot?.expectedTraceLabel ?: "Expected path (sm_landing_site)"
            else -> landingSiteRoute(roomExport)?.first
        }
        val focusPoint = shipPoint(roomExport)
            ?: currentPosition
            ?: plannedRoute.firstOrNull()
        if (plannedRoute.isEmpty() && liveTrace.isEmpty() && currentPosition == null) return null
        return RoomMapOverlay(
            routeLabel = routeLabel,
            plannedRoute = plannedRoute,
            liveTrace = liveTrace,
            currentPosition = currentPosition,
            focusPoint = focusPoint,
            startAnchor = plannedRoute.firstOrNull(),
            targetAnchor = plannedRoute.lastOrNull(),
        )
    }

    fun roomToFollow(rooms: List<RoomInfo>): RoomInfo? {
        if (!followLiveRoom) return null
        val roomId = snapshot?.roomId ?: return null
        if (roomId == lastFollowedRoomId) return null
        val room = rooms.firstOrNull { it.getRoomIdAsInt() == roomId } ?: return null
        lastFollowedRoomId = roomId
        return room
    }

    suspend fun configureBridge() {
        // No-op for libretro backend (no external bridge to configure)
    }

    private suspend fun applyStepResult(result: StepResult) {
        session = result.session
        if (result.states.isNotEmpty()) saveStates = result.states.sortedBy { it.name.lowercase() }
        if (result.recordingPath != null) recordingPath = result.recordingPath
        result.message?.let { setStatus(it) }
        applySnapshot(result.snapshot)
    }

    private suspend fun applySnapshot(incoming: GameSnapshot) {
        // Fast path: in-process libretro backend provides frames via FrameHolder
        val fh = frameHolder
        if (fh != null) {
            val directFrame = fh.latestFrame
            val merged = mergeSnapshot(snapshot, incoming)
            snapshot = merged
            if (directFrame != null) {
                frameBitmap = directFrame
            }
            return
        }

        // Slow path: fallback for backends that use Base64 frames
        val decodedFrame = incoming
            .takeIf { it.frameRgb24Base64 != null }
            ?.let { withContext(Dispatchers.Default) { decodeFrame(it) } }
        val merged = mergeSnapshot(snapshot, incoming)
        snapshot = merged
        if (decodedFrame != null) {
            frameBitmap = decodedFrame
        }
    }

    private fun decodeFrame(snap: GameSnapshot): ImageBitmap? {
        val base64 = snap.frameRgb24Base64 ?: return null
        if (snap.frameWidth <= 0 || snap.frameHeight <= 0) return null
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val width = snap.frameWidth
        val height = snap.frameHeight
        if (looksLikePng(bytes)) {
            return ByteArrayInputStream(bytes).use { stream ->
                ImageIO.read(stream)?.toComposeImageBitmap()
            }
        }

        if (bytes.size < width * height * 3) {
            return ByteArrayInputStream(bytes).use { stream ->
                ImageIO.read(stream)?.toComposeImageBitmap()
            }
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = bytes[idx].toInt() and 0xFF
                val g = bytes[idx + 1].toInt() and 0xFF
                val b = bytes[idx + 2].toInt() and 0xFF
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                idx += 3
            }
        }
        return image.toComposeImageBitmap()
    }

    private fun looksLikePng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        return pngSignature.indices.all { index -> bytes[index] == pngSignature[index] }
    }

    private fun mergeSnapshot(previous: GameSnapshot?, incoming: GameSnapshot): GameSnapshot {
        val expectedTrace = if (incoming.traceIncluded || previous == null) incoming.expectedTrace else previous.expectedTrace
        val trace = if (incoming.traceIncluded || previous == null) incoming.trace else previous.trace
        return incoming.copy(
            expectedTrace = expectedTrace,
            trace = trace,
        )
    }

    private fun updateLoopMetrics(
        previousFrameCounter: Int,
        currentFrameCounter: Int,
        elapsedNanos: Long,
        repeat: Int,
        frameIncluded: Boolean,
    ) {
        if (elapsedNanos <= 0L) return
        lastStepRepeat = repeat
        bridgeRoundTripMs = smoothMetric(bridgeRoundTripMs, elapsedNanos / 1_000_000f)

        // Use wall-clock time between steps for FPS (not step execution time,
        // which would report the burst rate of the CPU rather than actual throughput)
        val now = System.nanoTime()
        val wallClockNanos = now - lastStepWallClockNanos
        lastStepWallClockNanos = now
        if (wallClockNanos <= 0L || wallClockNanos > 1_000_000_000L) return // skip first sample / outliers
        val wallClockSeconds = wallClockNanos / 1_000_000_000f

        val frameDelta = maxOf(0, currentFrameCounter - previousFrameCounter)
        if (frameDelta > 0) {
            emulatedFps = smoothMetric(emulatedFps, frameDelta / wallClockSeconds)
        }
        if (frameIncluded) {
            viewportFps = smoothMetric(viewportFps, 1f / wallClockSeconds)
        }
    }

    private fun smoothMetric(current: Float, sample: Float): Float {
        return if (current <= 0f) sample else (current * 0.75f) + (sample * 0.25f)
    }

    fun persistEmulatorWindowGeometry(x: Float, y: Float, width: Float) {
        AppConfig.update {
            copy(
                emulatorWindowX = x,
                emulatorWindowY = y,
                emulatorWindowWidth = width,
            )
        }
    }

    fun updateRetroArchPath(value: String) {
        retroArchPath = value
        persistConfig()
    }

    fun updateRetroArchNwaPort(value: Int) {
        retroArchNwaPort = value
        persistConfig()
    }

    fun updateRetroArchCorePath(value: String) {
        retroArchCorePath = value
        persistConfig()
    }

    private fun persistConfig() {
        AppConfig.update {
            copy(
                emulatorNavExportDir = navExportDir,
                emulatorFollowLiveRoom = followLiveRoom,
                emulatorBackend = selectedBackendName,
                retroArchPath = this@EmulatorWorkspaceState.retroArchPath.takeIf { it.isNotBlank() },
                retroArchCorePath = this@EmulatorWorkspaceState.retroArchCorePath.takeIf { it.isNotBlank() },
                retroArchNwaPort = this@EmulatorWorkspaceState.retroArchNwaPort,
                emulatorAudioVolume = this@EmulatorWorkspaceState.audioVolume,
                emulatorAudioMuted = this@EmulatorWorkspaceState.audioMuted,
            )
        }
    }

    private fun roomExport(roomId: Int): EditorRoomExport? {
        roomExportCache[roomId]?.let { return it }
        val roomFile = File(navExportDir, "rooms/room_${roomId.toString(16).uppercase().padStart(4, '0')}.json")
        if (!roomFile.isFile) return null
        return runCatching {
            json.decodeFromString(EditorRoomExport.serializer(), roomFile.readText())
        }.getOrNull()?.also { roomExportCache[roomId] = it }
    }

    private fun landingSiteRoute(room: EditorRoomExport): Pair<String, List<LocalRoomPoint>>? {
        if (room.handle != "landingSite") return null
        val ship = shipPoint(room)
            ?: return null
        val targetDoor = room.doors
            .asSequence()
            .filter { it.direction == "Left" && !it.isElevator }
            .filter { it.sourceBlockX != null && it.sourceBlockY != null }
            .sortedByDescending { it.sourceBlockY ?: -1 }
            .firstOrNull()
            ?: return null
        val doorPoint = doorPoint(targetDoor) ?: return null
        return "Ship -> left door" to listOf(ship, doorPoint)
    }

    private fun shipPoint(room: EditorRoomExport): LocalRoomPoint? {
        val ship = room.enemies
            .firstOrNull { it.name == "Samus' Ship" }
            ?: room.enemies.firstOrNull { it.name.startsWith("Samus' Ship") }
            ?: return null
        return LocalRoomPoint(ship.pixelX.toFloat(), ship.pixelY.toFloat())
    }

    private fun doorPoint(door: EditorDoorExport): LocalRoomPoint? {
        val blockX = door.sourceBlockX ?: return null
        val blockY = door.sourceBlockY ?: return null
        return LocalRoomPoint(
            x = blockX * 16f + 8f,
            y = blockY * 16f + 8f,
        )
    }

    internal fun setNavGraphForTest(graph: LoadedNavGraph) {
        navGraph = graph
    }

    internal fun setSnapshotForTest(value: GameSnapshot) {
        snapshot = value
    }

    internal fun setRoomExportForTest(value: EditorRoomExport) {
        roomExportCache[value.roomId] = value
    }
}

internal fun navGraphExtent(graph: LoadedNavGraph): Pair<Float, Float> {
    var maxX = 0f
    var maxY = 0f
    for (node in graph.graph.nodes) {
        val point = graph.roomCenter(node.roomId) ?: continue
        maxX = max(maxX, point.x + node.widthScreens)
        maxY = max(maxY, point.y + node.heightScreens)
    }
    return Pair(maxX, maxY)
}

internal fun tracePointFor(graph: LoadedNavGraph, point: TracePoint): WorldTracePoint? {
    return graph.roomPoint(point.roomId, point.x, point.y)
}
