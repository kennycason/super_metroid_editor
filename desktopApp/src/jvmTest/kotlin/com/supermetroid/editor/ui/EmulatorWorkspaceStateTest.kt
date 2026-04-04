package com.supermetroid.editor.ui

import androidx.compose.ui.input.key.Key
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.emulator.EmulatorBackend
import com.supermetroid.editor.emulator.EmulatorCapabilities
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.GameSnapshot
import com.supermetroid.editor.emulator.ModelInfo
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.emulator.SessionState
import com.supermetroid.editor.emulator.StateInfo
import com.supermetroid.editor.emulator.StepResult
import com.supermetroid.editor.emulator.TracePoint
import com.supermetroid.editor.integration.EditorDoorExport
import com.supermetroid.editor.integration.EditorEnemyExport
import com.supermetroid.editor.integration.EditorNavGraph
import com.supermetroid.editor.integration.EditorNavNode
import com.supermetroid.editor.integration.EditorRoomExport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmulatorWorkspaceStateTest {

    @Test
    fun `currentAction maps keys and clears conflicting directions`() {
        val state = EmulatorWorkspaceState()

        state.updateKey(Key.DirectionLeft, down = true)
        state.updateKey(Key.DirectionRight, down = true)
        state.updateKey(Key.Z, down = true)
        state.updateKey(Key.Enter, down = true)
        state.updateKey(Key.Tab, down = true)

        val action = state.currentAction()

        assertEquals(0, action[6], "left should be cleared when both left and right are pressed")
        assertEquals(0, action[7], "right should be cleared when both left and right are pressed")
        assertEquals(1, action[0], "Z maps to SNES B")
        assertEquals(1, action[3], "Enter maps to Start")
        assertEquals(1, action[2], "Tab maps to Select")
    }

    @Test
    fun `checkpoint slots expose expected hotkeys`() {
        val state = EmulatorWorkspaceState()

        assertEquals("F1", state.checkpointSlotHotkey(state.saveSlots[0]))
        assertEquals("F2", state.checkpointSlotHotkey(state.saveSlots[1]))
        assertEquals("F3", state.checkpointSlotHotkey(state.saveSlots[2]))
        assertEquals("F4", state.checkpointSlotHotkey(state.saveSlots[3]))
        assertEquals("Shift+F1", state.checkpointSlotReloadHotkey(state.saveSlots[0]))
        assertEquals("Shift+F2", state.checkpointSlotReloadHotkey(state.saveSlots[1]))
        assertEquals("Shift+F3", state.checkpointSlotReloadHotkey(state.saveSlots[2]))
        assertEquals("Shift+F4", state.checkpointSlotReloadHotkey(state.saveSlots[3]))
    }

    @Test
    fun `resetToSessionStart reloads the original boot state`() = runBlocking {
        val backend = FakeBackend()
        backend.connectResult = EmulatorCapabilities(
            backendName = "fake",
            supportsFrames = true,
            supportsRecording = true,
        )
        backend.listStatesResult = listOf(
            StateInfo("ZebesStart", "/repo/custom_integrations/SuperMetroid-Snes/ZebesStart.state"),
            StateInfo("EditorCheckpoint02", "/repo/custom_integrations/SuperMetroid-Snes/EditorCheckpoint02.state"),
        )
        backend.startSessionResult = StepResult(
            session = SessionState(active = true, currentState = "ZebesStart", frameCounter = 1),
            snapshot = GameSnapshot(frameCounter = 1),
            message = "started",
        )
        backend.loadStateResult = StepResult(
            session = SessionState(active = true, currentState = "ZebesStart", frameCounter = 2),
            snapshot = GameSnapshot(frameCounter = 2),
            message = "reloaded",
        )

        val state = EmulatorWorkspaceState(backendFactory = { backend })

        state.updateRomPath("/tmp/SuperMetroid.sfc")
        state.connectBridge()
        state.selectedStateName = "ZebesStart"
        state.startSession()
        state.selectedStateName = "EditorCheckpoint02"
        state.resetToSessionStart()

        assertEquals("ZebesStart", state.sessionBootStateName)
        assertEquals("ZebesStart", state.selectedStateName)
        assertEquals("load_state", backend.lastCommand)
        assertEquals("ZebesStart", backend.lastStateName)
        assertEquals(
            "custom_integrations/SuperMetroid-Snes/ZebesStart.state",
            state.displayPath(state.sessionBootStateInfo()?.path),
        )
        assertEquals("/tmp/SuperMetroid.sfc", backend.lastSessionConfig?.romPath)
    }

    @Test
    fun `displayPath handles both unix and windows separators`() {
        val state = EmulatorWorkspaceState()
        assertEquals(
            "custom_integrations/SuperMetroid-Snes/ZebesStart.state",
            state.displayPath("/repo/custom_integrations/SuperMetroid-Snes/ZebesStart.state"),
        )
        assertEquals(
            "custom_integrations/SuperMetroid-Snes/ZebesStart.state",
            state.displayPath("D:\\repo\\custom_integrations\\SuperMetroid-Snes\\ZebesStart.state"),
        )
    }

    @Test
    fun `startSession forwards rom path to backend`() = runBlocking {
        val backend = FakeBackend()
        backend.connectResult = EmulatorCapabilities(backendName = "fake")
        backend.startSessionResult = StepResult(
            session = SessionState(active = true, currentState = "ZebesStart", frameCounter = 1),
            snapshot = GameSnapshot(frameCounter = 1),
        )
        val state = EmulatorWorkspaceState(backendFactory = { backend })

        state.updateRomPath("/tmp/StandaloneSuperMetroid.sfc")
        state.connectBridge()
        state.selectedStateName = "ZebesStart"
        state.startSession()

        assertEquals("/tmp/StandaloneSuperMetroid.sfc", backend.lastSessionConfig?.romPath)
    }

    @Test
    fun `closeSession clears active session state`() = runBlocking {
        val backend = FakeBackend()
        backend.connectResult = EmulatorCapabilities(backendName = "fake")
        backend.startSessionResult = StepResult(
            session = SessionState(active = true, currentState = "ZebesStart", frameCounter = 1),
            snapshot = GameSnapshot(frameCounter = 1),
        )
        backend.closeSessionResult = StepResult(
            session = SessionState(active = false, paused = true),
            message = "closed",
        )
        val state = EmulatorWorkspaceState(backendFactory = { backend })

        state.updateRomPath("/tmp/StandaloneSuperMetroid.sfc")
        state.connectBridge()
        state.selectedStateName = "ZebesStart"
        state.startSession()
        state.closeSession()

        assertEquals("close_session", backend.lastCommand)
        assertFalse(state.session.active)
        assertFalse(state.isRunning)
    }

    @Test
    fun `liveTracePoints convert local room pixels into planner world coordinates`() {
        val state = EmulatorWorkspaceState()
        state.setNavGraphForTest(sampleGraph())
        state.setSnapshotForTest(
            GameSnapshot(
                roomId = 0x91F8,
                trace = listOf(
                    TracePoint(frame = 1, roomId = 0x91F8, x = 128, y = 64),
                    TracePoint(frame = 2, roomId = 0x92FD, x = 64, y = 32),
                ),
            )
        )

        val points = state.liveTracePoints()

        assertEquals(2, points.size)
        assertEquals(0.5f, points[0].x, 0.001f)
        assertEquals(7.25f, points[0].y, 0.001f)
        assertEquals(1.25f, points[1].x, 0.001f)
        assertEquals(8.125f, points[1].y, 0.001f)
    }

    @Test
    fun `centerOfGravity averages live planner trace`() {
        val state = EmulatorWorkspaceState()
        state.setNavGraphForTest(sampleGraph())
        state.setSnapshotForTest(
            GameSnapshot(
                roomId = 0x92FD,
                trace = listOf(
                    TracePoint(frame = 1, roomId = 0x91F8, x = 0, y = 0),
                    TracePoint(frame = 2, roomId = 0x91F8, x = 256, y = 0),
                    TracePoint(frame = 3, roomId = 0x92FD, x = 0, y = 256),
                ),
            )
        )

        val cog = state.centerOfGravity()

        assertNotNull(cog)
        assertEquals((0f + 1f + 1f) / 3f, cog!!.x, 0.001f)
        assertEquals((7f + 7f + 9f) / 3f, cog.y, 0.001f)
        assertTrue(cog.roomName.isNotBlank())
    }

    @Test
    fun `roomMapOverlay anchors Landing Site route from ship to left door`() {
        val state = EmulatorWorkspaceState()
        state.setRoomExportForTest(sampleLandingSiteRoomExport())
        state.setSnapshotForTest(
            GameSnapshot(
                roomId = 0x91F8,
                samusX = 32,
                samusY = 1144,
                expectedTrace = listOf(
                    TracePoint(frame = 1, roomId = 0x91F8, x = 1152, y = 1144),
                    TracePoint(frame = 2, roomId = 0x91F8, x = 896, y = 896),
                    TracePoint(frame = 3, roomId = 0x91F8, x = 640, y = 896),
                    TracePoint(frame = 4, roomId = 0x91F8, x = 640, y = 1152),
                    TracePoint(frame = 5, roomId = 0x91F8, x = 384, y = 1152),
                    TracePoint(frame = 6, roomId = 0x91F8, x = 8, y = 1152),
                ),
                trace = listOf(
                    TracePoint(frame = 1, roomId = 0x91F8, x = 1152, y = 1144),
                    TracePoint(frame = 2, roomId = 0x91F8, x = 768, y = 1144),
                    TracePoint(frame = 3, roomId = 0x91F8, x = 32, y = 1144),
                ),
            )
        )

        val overlay = state.roomMapOverlay(RoomInfo(id = "0x91F8", handle = "landingSite", name = "Landing Site"))

        assertNotNull(overlay)
        assertEquals("Expected path (sm_landing_site)", overlay!!.routeLabel)
        assertEquals(6, overlay.plannedRoute.size)
        assertEquals(1152f, overlay.plannedRoute.first().x, 0.001f)
        assertEquals(1144f, overlay.plannedRoute.first().y, 0.001f)
        assertEquals(8f, overlay.plannedRoute.last().x, 0.001f)
        assertEquals(1152f, overlay.plannedRoute.last().y, 0.001f)
        assertEquals(3, overlay.liveTrace.size)
        val currentPosition = overlay.currentPosition
        assertNotNull(currentPosition)
        assertEquals(32f, currentPosition!!.x, 0.001f)
        assertEquals(1144f, currentPosition.y, 0.001f)
    }

    @Test
    fun `projectStateDir derives slug from ROM filename`() {
        val state = EmulatorWorkspaceState()
        state.updateRomPath("/Users/kenny/roms/Super Metroid (JU) [!].smc")
        val dir = state.projectStateDir()
        assertTrue(dir.path.endsWith("Super_Metroid__JU_____.smc".replace(".smc", "")))
        assertTrue(dir.path.contains(".smedit/states/libretro/"))
    }

    @Test
    fun `projectStateDir defaults when no ROM set`() {
        val state = EmulatorWorkspaceState()
        val dir = state.projectStateDir()
        assertTrue(dir.path.endsWith("/default"))
    }

    @Test
    fun `projectStateDir changes when ROM path changes`() {
        val state = EmulatorWorkspaceState()
        state.updateRomPath("/roms/GameA.smc")
        val dirA = state.projectStateDir()
        state.updateRomPath("/roms/GameB.sfc")
        val dirB = state.projectStateDir()
        assertFalse(dirA.path == dirB.path, "different ROMs should produce different state dirs")
        assertTrue(dirA.path.contains("GameA"))
        assertTrue(dirB.path.contains("GameB"))
    }

    private fun sampleGraph(): LoadedNavGraph {
        return LoadedNavGraph(
            EditorNavGraph(
                nodes = listOf(
                    EditorNavNode(
                        roomId = 0x91F8,
                        roomIdHex = "0x91F8",
                        handle = "landingSite",
                        name = "Landing Site",
                        area = 0,
                        areaName = "Crateria",
                        mapX = 0,
                        mapY = 0,
                        widthScreens = 2,
                        heightScreens = 1,
                    ),
                    EditorNavNode(
                        roomId = 0x92FD,
                        roomIdHex = "0x92FD",
                        handle = "parlor",
                        name = "Parlor and Alcatraz",
                        area = 0,
                        areaName = "Crateria",
                        mapX = 1,
                        mapY = 1,
                        widthScreens = 1,
                        heightScreens = 2,
                    ),
                ),
                edges = emptyList(),
            )
        )
    }

    private fun sampleLandingSiteRoomExport(): EditorRoomExport {
        return EditorRoomExport(
            roomId = 0x91F8,
            roomIdHex = "0x91F8",
            handle = "landingSite",
            name = "Landing Site",
            area = 0,
            areaName = "Crateria",
            mapX = 23,
            mapY = 0,
            widthScreens = 9,
            heightScreens = 5,
            widthBlocks = 144,
            heightBlocks = 80,
            tileset = 0,
            collision = emptyList(),
            bts = emptyList(),
            doors = listOf(
                EditorDoorExport(
                    destRoomId = 0x92FD,
                    destRoomIdHex = "0x92FD",
                    destRoomHandle = "parlorAndAlcatraz",
                    direction = "Left",
                    isElevator = false,
                    screenX = 0,
                    screenY = 4,
                    sourceBlockX = 0,
                    sourceBlockY = 71,
                )
            ),
            items = emptyList(),
            enemies = listOf(
                EditorEnemyExport(
                    id = 0xD07F,
                    idHex = "0xD07F",
                    name = "Samus' Ship",
                    pixelX = 1152,
                    pixelY = 1144,
                    blockX = 72,
                    blockY = 71,
                )
            ),
            plms = emptyList(),
        )
    }

    /** Fake backend implementing the new pluggable EmulatorBackend interface. */
    private class FakeBackend : EmulatorBackend {
        override val name: String = "fake"
        override var isConnected: Boolean = false

        var connectResult = EmulatorCapabilities(backendName = "fake")
        var startSessionResult = StepResult(message = "started")
        var closeSessionResult = StepResult(session = SessionState(active = false, paused = true), message = "closed")
        var loadStateResult = StepResult(message = "loaded")
        var listStatesResult = listOf<StateInfo>()
        var lastCommand: String? = null
        var lastStateName: String? = null
        var lastSessionConfig: SessionConfig? = null

        override suspend fun connect(): EmulatorCapabilities {
            isConnected = true
            return connectResult
        }

        override suspend fun disconnect() {
            isConnected = false
        }

        override suspend fun startSession(config: SessionConfig): StepResult {
            lastCommand = "start_session"
            lastStateName = config.stateName
            lastSessionConfig = config
            return startSessionResult
        }

        override suspend fun closeSession(): StepResult {
            lastCommand = "close_session"
            return closeSessionResult
        }

        override suspend fun step(input: EmulatorInput): StepResult {
            lastCommand = "step"
            return StepResult()
        }

        override suspend fun snapshot(): GameSnapshot {
            lastCommand = "snapshot"
            return GameSnapshot()
        }

        override suspend fun saveState(name: String) {
            lastCommand = "save_state"
            lastStateName = name
        }

        override suspend fun loadState(name: String): StepResult {
            lastCommand = "load_state"
            lastStateName = name
            return loadStateResult
        }

        override suspend fun listStates(): List<StateInfo> {
            lastCommand = "list_states"
            return listStatesResult
        }

        override suspend fun readMemory(address: Int, size: Int): ByteArray = ByteArray(size)
        override suspend fun writeMemory(address: Int, data: ByteArray) {}
        override fun close() { isConnected = false }
    }
}
