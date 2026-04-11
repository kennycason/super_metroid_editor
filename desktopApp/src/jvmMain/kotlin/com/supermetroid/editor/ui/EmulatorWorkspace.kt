package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

private const val TARGET_EMU_FPS = 60.0
private const val MAX_STEP_REPEAT = 4
private const val FRAME_REFRESH_INTERVAL = 2
private const val TRACE_REFRESH_INTERVAL = 10
private const val FRAME_DURATION_NANOS = (1_000_000_000.0 / TARGET_EMU_FPS).toLong()
private const val EXTERNAL_POLL_INTERVAL_MS = 100L

private data class PlannerRoomLayout(
    val roomId: Int,
    val roomName: String,
    val areaName: String,
    val worldLeft: Float,
    val worldTop: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private data class PlannerCanvasLayout(
    val rooms: Map<Int, PlannerRoomLayout>,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

@Composable
fun EmulatorWorkspace(
    room: RoomInfo?,
    rooms: List<RoomInfo>,
    romParser: RomParser?,
    editorState: EditorState,
    workspaceState: EmulatorWorkspaceState,
    onRoomSelected: (RoomInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val roomOverlay = room?.let { workspaceState.roomMapOverlay(it) }

    LaunchedEffect(Unit) {
        if (workspaceState.navGraph == null) {
            workspaceState.loadNavGraph()
        }
    }

    LaunchedEffect(editorState.project.romPath) {
        val romPath = editorState.project.romPath.takeIf { it.isNotBlank() }
        workspaceState.updateRomPath(romPath)

        // If emulator is running and we got a new ROM, restart the session
        if (workspaceState.session.active && romPath != null) {
            workspaceState.disconnectBridge()
            workspaceState.clearSavedStateSelection()
            workspaceState.connectBridge()
            if (workspaceState.isConnected) {
                val rp = romParser
                if (rp != null) {
                    val patchedPath = editorState.exportToRom(rp)
                    if (patchedPath != null) {
                        workspaceState.updateRomPath(patchedPath)
                    }
                }
                workspaceState.startSession()
                workspaceState.setLoopRunning(true)
            }
        }
    }

    LaunchedEffect(workspaceState.snapshot?.roomId, workspaceState.followLiveRoom) {
        workspaceState.roomToFollow(rooms)?.let(onRoomSelected)
    }

    LaunchedEffect(workspaceState.isRunning, workspaceState.session.active, workspaceState.isExternalBackend) {
        if (workspaceState.isExternalBackend) {
            // External backend (RetroArch): poll snapshot every ~100ms
            while (workspaceState.isRunning && workspaceState.session.active) {
                workspaceState.pollExternalSnapshot()
                delay(EXTERNAL_POLL_INTERVAL_MS)
            }
        } else {
            // Embedded backend (libretro): run at 60fps frame loop
            var tick = 0L
            var pendingFrames = 0.0
            var lastWallClockNanos = System.nanoTime()
            val warmupTicks = 5L
            while (workspaceState.isRunning && workspaceState.session.active) {
                val now = System.nanoTime()
                val elapsedNanos = now - lastWallClockNanos
                lastWallClockNanos = now
                val effectiveNanos = if (tick < warmupTicks) minOf(elapsedNanos, FRAME_DURATION_NANOS) else elapsedNanos
                pendingFrames += effectiveNanos.toDouble() / FRAME_DURATION_NANOS.toDouble()

                // Audio-aware pacing: yield when audio buffer is nearly full
                if (!workspaceState.audioHasHeadroom && pendingFrames < 2.0) {
                    delay(2L)
                    continue
                }

                if (pendingFrames < 1.0) {
                    val waitMs = ceil(((1.0 - pendingFrames) * FRAME_DURATION_NANOS) / 1_000_000.0).toLong().coerceAtLeast(1L)
                    delay(waitMs)
                    continue
                }
                val repeat = pendingFrames.toInt().coerceIn(1, MAX_STEP_REPEAT)
                pendingFrames = (pendingFrames - repeat).coerceAtMost(MAX_STEP_REPEAT.toDouble())
                workspaceState.stepFrame(
                    repeat = repeat,
                    includeFrame = tick % FRAME_REFRESH_INTERVAL == 0L,
                    includeTrace = tick % TRACE_REFRESH_INTERVAL == 0L,
                )
                tick += 1
                if (pendingFrames > MAX_STEP_REPEAT * 2) {
                    pendingFrames = MAX_STEP_REPEAT.toDouble()
                }
            }
        }
    }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Text("Landing Site Room Map", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    roomOverlay?.routeLabel?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    val snap = workspaceState.snapshot
                    val sx = snap?.samusX
                    val sy = snap?.samusY
                    val samusPos = if (sx != null && sy != null
                        && snap.roomId != null && room != null && snap.roomId == room.getRoomIdAsInt()
                        && !snap.doorTransition
                    ) {
                        Pair(sx.toFloat(), sy.toFloat())
                    } else null

                    MapCanvas(
                        room = room,
                        romParser = romParser,
                        editorState = editorState,
                        rooms = rooms,
                        samusPosition = samusPos,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .width(475.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EmulatorControlCard(workspaceState = workspaceState, onAction = { action ->
                        scope.launch {
                            when (action) {
                                "play" -> {
                                    if (!workspaceState.isConnected) workspaceState.connectBridge()
                                    if (workspaceState.isConnected && !workspaceState.session.active) {
                                        // Build patched ROM so the emulator runs the edited version
                                        val rp = romParser
                                        if (rp != null) {
                                            val patchedPath = editorState.exportToRom(rp)
                                            if (patchedPath != null) {
                                                workspaceState.updateRomPath(patchedPath)
                                            }
                                        }
                                        workspaceState.startSession()
                                        workspaceState.setLoopRunning(true)
                                    }
                                }
                                "load_nav" -> workspaceState.loadNavGraph()
                                "connect" -> workspaceState.connectBridge()
                                "disconnect" -> workspaceState.disconnectBridge()
                                "refresh" -> workspaceState.refreshInventory()
                                "start" -> workspaceState.startSession()
                                "close" -> workspaceState.closeSession()
                                "snapshot" -> workspaceState.refreshSnapshot()
                                "step" -> workspaceState.stepFrame()
                                "save" -> workspaceState.saveQuickState()
                                "load_state" -> workspaceState.loadSelectedState()
                                "record_on" -> workspaceState.setRecording(true)
                                "record_off" -> workspaceState.setRecording(false)
                                "sync_config" -> workspaceState.configureBridge()
                                "toggle_mute" -> workspaceState.toggleAudioMute()
                            }
                        }
                    })
                    ItemTrackerPanel(snapshot = workspaceState.snapshot)
                    if (!workspaceState.isExternalBackend) {
                        InventoryCard(
                            title = "States",
                            subtitle = "${workspaceState.saveStates.size} available",
                            selected = workspaceState.selectedStateName,
                            items = workspaceState.saveStates.map { it.name },
                            onSelect = { workspaceState.selectedStateName = it },
                        )
                    }
                    PlannerSummaryCard(
                        workspaceState = workspaceState,
                        rooms = rooms,
                        onClear = { workspaceState.clearPlannedRoute() },
                        onUndo = { workspaceState.removeLastPlannedRoom() },
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 1.dp,
                    ) {
                        GlobalWorldPlanner(
                            workspaceState = workspaceState,
                            rooms = rooms,
                            onRoomSelected = { selected ->
                                workspaceState.appendPlannedRoom(selected.getRoomIdAsInt())
                                onRoomSelected(selected)
                            },
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Live Emulator", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    EmulatorSessionToolbar(workspaceState = workspaceState)
                    Spacer(Modifier.height(8.dp))
                    EmulatorViewport(workspaceState = workspaceState)
                    Spacer(Modifier.height(8.dp))
                    EmulatorCheckpointCard(workspaceState = workspaceState)
                    Spacer(Modifier.height(8.dp))
                    SnapshotHud(workspaceState = workspaceState)
                }
            }
        }
    }
}

@Composable
private fun EmulatorControlCard(
    workspaceState: EmulatorWorkspaceState,
    onAction: (String) -> Unit,
) {
    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (workspaceState.isExternalBackend) "External Emulator" else "Embedded Emulator",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                "Backend: ${workspaceState.selectedBackendName}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                workspaceState.statusMessage,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!workspaceState.isExternalBackend) {
                AppTextInput(
                    value = workspaceState.navExportDir,
                    onValueChange = { workspaceState.updateNavExportDir(it) },
                    placeholder = "super_metroid_editor/export/sm_nav",
                    modifier = Modifier.fillMaxWidth(),
                    monospace = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction("load_nav") }, enabled = !workspaceState.isBusy) {
                        Text("Load Graph", fontSize = 12.sp)
                    }
                    if (!workspaceState.isConnected) {
                        Button(onClick = { onAction("connect") }, enabled = !workspaceState.isBusy) {
                            Text("Connect", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(onClick = { onAction("disconnect") }, enabled = !workspaceState.isBusy) {
                            Text("Disconnect", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(onClick = { onAction("refresh") }, enabled = workspaceState.isConnected && !workspaceState.isBusy) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onAction("sync_config") }, enabled = workspaceState.isConnected && !workspaceState.isBusy) {
                        Text("Sync", fontSize = 12.sp)
                    }
                }
                Divider()
            }

            if (!workspaceState.isExternalBackend) {
                Text(
                    "Control mode: ${workspaceState.requestedControlMode}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("manual", "watch", "agent").forEach { mode ->
                        val selected = workspaceState.requestedControlMode == mode
                        OutlinedButton(
                            onClick = { workspaceState.updateRequestedControlMode(mode) },
                            enabled = !workspaceState.isBusy,
                        ) {
                            Text(
                                if (selected) "[${mode.uppercase()}]" else mode.replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                Text(
                    "Selected state: ${workspaceState.selectedStateName ?: "none"}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (workspaceState.isExternalBackend && workspaceState.session.active) {
                val snap = workspaceState.snapshot
                val rid = snap?.roomId
                if (rid != null) {
                    Text(
                        "Room: 0x${rid.toString(16).uppercase()}  HP: ${snap.health ?: 0}  Pos: (${snap.samusX ?: 0}, ${snap.samusY ?: 0})",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (!workspaceState.isExternalBackend) {
                Text(
                    "Session: ${if (workspaceState.session.active) "active" else "idle"}  frame=${workspaceState.session.frameCounter}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!workspaceState.isExternalBackend) {
                workspaceState.recordingPath?.let {
                    Text("Recording: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction("play") },
                    enabled = !workspaceState.session.active && !workspaceState.isBusy,
                ) {
                    Text("Play", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { onAction("close") },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                ) {
                    Text("Close", fontSize = 12.sp)
                }
                if (!workspaceState.isExternalBackend) {
                    OutlinedButton(
                        onClick = { workspaceState.setLoopRunning(!workspaceState.isRunning) },
                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                    ) {
                        Text(if (workspaceState.isRunning) "Pause" else "Resume", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onAction("step") },
                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                    ) {
                        Text("Step", fontSize = 12.sp)
                    }
                }
            }
            if (!workspaceState.isExternalBackend) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onAction("snapshot") },
                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                    ) {
                        Text("Refresh HUD", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onAction("save") },
                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                    ) {
                        Text("Save Named", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onAction("load_state") },
                        enabled = workspaceState.session.active && workspaceState.selectedStateName != null && !workspaceState.isBusy,
                    ) {
                        Text("Reload Named", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onAction(if (workspaceState.session.recording) "record_off" else "record_on") },
                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                    ) {
                        Text(if (workspaceState.session.recording) "Stop Rec" else "Record", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onAction("toggle_mute") },
                        enabled = workspaceState.isConnected,
                    ) {
                        Text(if (workspaceState.audioMuted) "Unmute" else "Mute", fontSize = 12.sp)
                    }
                }
            }
            Text(
                if (workspaceState.isExternalBackend)
                    "External RetroArch via NWA. Enable Network Commands in RetroArch (Settings > Network). Editor syncs room & position."
                else
                    "In-process SNES emulator via libretro. Click Play to start, then focus the viewport for keyboard input.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InventoryCard(
    title: String,
    subtitle: String,
    selected: String?,
    items: List<String>,
    onSelect: (String) -> Unit,
) {
    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (items.isEmpty()) {
                    Text("None discovered yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items.forEach { item ->
                    val isSelected = selected != null && (item == selected || item.startsWith("$selected "))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable { onSelect(item) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item,
                            fontSize = 11.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannerSummaryCard(
    workspaceState: EmulatorWorkspaceState,
    rooms: List<RoomInfo>,
    onClear: () -> Unit,
    onUndo: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Planner Route", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Click rooms on the global map to append a route. The line overlay is intentionally coarse for planning, not micro-pathing.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = workspaceState.plannedRouteRoomIds.isNotEmpty()) {
                    Text("Undo", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onClear, enabled = workspaceState.plannedRouteRoomIds.isNotEmpty()) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (workspaceState.plannedRouteRoomIds.isEmpty()) {
                    Text("No planned rooms yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                workspaceState.plannedRouteRoomIds.forEachIndexed { index, roomId ->
                    val room = rooms.firstOrNull { it.getRoomIdAsInt() == roomId }
                    Text(
                        "${index + 1}. ${room?.name ?: "0x${roomId.toString(16).uppercase()}"}",
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmulatorSessionToolbar(workspaceState: EmulatorWorkspaceState) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val selectedInfo = workspaceState.selectedStateInfo()
    val bootInfo = workspaceState.sessionBootStateInfo()
    val sessionStateLabel = when {
        !workspaceState.session.active -> "idle"
        workspaceState.isRunning -> "running"
        else -> "paused"
    }
    val resetButtonLabel = workspaceState.sessionBootStateName
        ?.takeIf { it.length <= 12 }
        ?.let { "Reset $it" }
        ?: "Reset Start"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { scope.launch { workspaceState.resetToSessionStart() } },
                    enabled = workspaceState.session.active && workspaceState.sessionBootStateName != null && !workspaceState.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(resetButtonLabel, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { workspaceState.setLoopRunning(!workspaceState.isRunning) },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (workspaceState.isRunning) "Pause" else "Resume", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { scope.launch { workspaceState.saveQuickState() } },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save Quick", fontSize = 11.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                            .clickable { expanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedInfo?.name ?: "Select save state",
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.requiredSizeIn(maxHeight = 360.dp),
                    ) {
                        workspaceState.saveStates.forEach { state ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        state.name,
                                        fontSize = 11.sp,
                                        fontWeight = if (state.name == workspaceState.selectedStateName) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    workspaceState.selectedStateName = state.name
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { scope.launch { workspaceState.loadSelectedState() } },
                    enabled = workspaceState.session.active && selectedInfo != null && !workspaceState.isBusy,
                ) {
                    Text("Reload Selected", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { scope.launch { workspaceState.refreshInventory() } },
                    enabled = workspaceState.isConnected && !workspaceState.isBusy,
                ) {
                    Text("Refresh", fontSize = 11.sp)
                }
            }

            Text(
                "Selected: ${selectedInfo?.name ?: "none"}  ·  ${workspaceState.displayPath(selectedInfo?.path) ?: "no file selected"}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Boot reset target: ${bootInfo?.name ?: workspaceState.sessionBootStateName ?: "none"}  ·  ${workspaceState.displayPath(bootInfo?.path) ?: "start the session to lock this"}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Session ${sessionStateLabel}  ·  frame ${workspaceState.session.frameCounter}  ·  ${workspaceState.controllerSummary()}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmulatorViewport(workspaceState: EmulatorWorkspaceState) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(workspaceState.session.active, workspaceState.frameBitmap) {
        if (workspaceState.session.active && workspaceState.frameBitmap != null) {
            delay(16)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(EditorColors.emulatorPanelBg)
                    .border(1.dp, EditorColors.emulatorBorder, RoundedCornerShape(8.dp))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (event.key == Key.Spacebar) {
                                    workspaceState.setLoopRunning(!workspaceState.isRunning)
                                    true
                                } else if (event.key == Key.F1) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[0])
                                        } else {
                                            workspaceState.saveSlot(0)
                                        }
                                    }
                                    true
                                } else if (event.key == Key.F2) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[1])
                                        } else {
                                            workspaceState.saveSlot(1)
                                        }
                                    }
                                    true
                                } else if (event.key == Key.F3) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[2])
                                        } else {
                                            workspaceState.saveSlot(2)
                                        }
                                    }
                                    true
                                } else if (event.key == Key.F4) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[3])
                                        } else {
                                            workspaceState.saveSlot(3)
                                        }
                                    }
                                    true
                                } else if (event.key == Key.F5) {
                                    scope.launch { workspaceState.saveQuickState() }
                                    true
                                } else if (event.key == Key.Grave) {
                                    scope.launch { workspaceState.reloadLastCheckpoint() }
                                    true
                                } else {
                                    workspaceState.updateKey(event.key, down = true)
                                    false
                                }
                            }
                            KeyEventType.KeyUp -> {
                                workspaceState.updateKey(event.key, down = false)
                                false
                            }
                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = workspaceState.frameBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Emulator frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No frame yet", color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Click Play to start. Focus this panel for keyboard input.\nArrows + Z/X/A/S/Q/W + Shift/Tab + Enter | F1-F4 save | Shift+F1-F4 reload | ` reload last",
                            color = EditorColors.emulatorText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                ViewportStatusOverlay(
                    workspaceState = workspaceState,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
}
        }
    }
}

@Composable
private fun ViewportStatusOverlay(
    workspaceState: EmulatorWorkspaceState,
    modifier: Modifier = Modifier,
) {
    val snapshot = workspaceState.snapshot
    Column(
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "emu ${workspaceState.emulatedFps.toInt()} fps  frame ${workspaceState.viewportFps.toInt()} fps  rtt ${workspaceState.bridgeRoundTripMs.toInt()} ms",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "controller ${workspaceState.controllerSummary()}  source ${snapshot?.lastActionSource ?: "manual"}  applied ${workspaceState.activeInputSummary()}",
            color = EditorColors.emulatorText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "checkpoints F1-F4 save  Shift+F1-F4 reload  ` reload-last",
            color = EditorColors.emulatorText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "route ${snapshot?.expectedTraceLabel ?: "none"}  ${(((snapshot?.pathCompletion ?: 0f) * 100f).toInt())}%  err=${snapshot?.pathErrorPx?.toInt() ?: "-"}px  rec=${snapshot?.recordedFrames ?: 0}",
            color = EditorColors.emulatorText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "room ${snapshot?.roomName ?: "?"}  x=${snapshot?.samusX ?: "?"} y=${snapshot?.samusY ?: "?"}  trace=${snapshot?.trace?.size ?: 0}",
            color = EditorColors.emulatorText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (snapshot?.doorTransition == true) {
            Text(
                "door transition active",
                color = Color(0xFFFFC857),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun EmulatorCheckpointCard(workspaceState: EmulatorWorkspaceState) {
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Checkpoint Slots", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(
                "F1-F4 save directly to slots. Shift+F1-F4 reload the matching slot. Backquote reloads the last-used checkpoint instantly.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            workspaceState.saveSlots.chunked(2).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowSlots.forEach { slot ->
                        val hasState = workspaceState.hasSavedSlot(slot)
                        val isLast = workspaceState.isLastCheckpointSlot(slot)
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (hasState) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    buildString {
                                        append(slot.label)
                                        val saveHotkey = workspaceState.checkpointSlotHotkey(slot)
                                        if (saveHotkey.isNotBlank()) {
                                            append(" · ")
                                            append(saveHotkey)
                                            append(" save")
                                        }
                                        val reloadHotkey = workspaceState.checkpointSlotReloadHotkey(slot)
                                        if (reloadHotkey.isNotBlank()) {
                                            append(" · ")
                                            append(reloadHotkey)
                                            append(" reload")
                                        }
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (hasState) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    when {
                                        isLast && hasState -> "Last-used reload target"
                                        hasState -> "Ready to reload"
                                        else -> "Empty"
                                    },
                                    fontSize = 10.sp,
                                    color = if (hasState) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { scope.launch { workspaceState.saveSlot(slot) } },
                                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                                    ) {
                                        Text("Save", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { scope.launch { workspaceState.loadSlot(slot) } },
                                        enabled = workspaceState.session.active && hasState && !workspaceState.isBusy,
                                    ) {
                                        Text("Reload", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (rowSlots.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotHud(workspaceState: EmulatorWorkspaceState) {
    val snapshot = workspaceState.snapshot
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("HUD / Telemetry", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (snapshot == null) {
            Text("No live snapshot yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Text("Room: ${snapshot.roomName ?: "?"}  (${snapshot.roomId?.let { "0x${it.toString(16).uppercase()}" } ?: "?"})", fontSize = 11.sp)
        Text("Area: ${snapshot.areaName ?: "?"}   GameState: ${snapshot.gameState ?: "?"}   Door: ${if (snapshot.doorTransition) "transition" else "stable"}", fontSize = 11.sp)
        Text("Samus: x=${snapshot.samusX ?: "?"} y=${snapshot.samusY ?: "?"}   Health: ${snapshot.health ?: "?"}", fontSize = 11.sp)
        Text("Plan: ${snapshot.expectedTraceLabel ?: "none"}   Source: ${snapshot.expectedTraceSource ?: "n/a"}", fontSize = 11.sp)
        Text(
            "Path: ${(snapshot.pathCompletion * 100f).toInt()}%   max ${(snapshot.pathProgressMax * 100f).toInt()}%   err ${snapshot.pathErrorPx?.toInt() ?: "-"}px   best ${snapshot.pathBestErrorPx?.toInt() ?: "-"}px",
            fontSize = 11.sp,
        )
        Text("Trace points: ${snapshot.trace.size}   Recording: ${if (workspaceState.session.recording) "on" else "off"}   Recorded frames: ${snapshot.recordedFrames}", fontSize = 11.sp)
        Text("Controller: ${workspaceState.controllerSummary()}   Source: ${snapshot.lastActionSource}", fontSize = 11.sp)
        Text("Requested: ${workspaceState.requestedInputSummary()}", fontSize = 11.sp)
        Text("Pre-sanitize: ${workspaceState.preSanitizeInputSummary()}", fontSize = 11.sp)
        Text("Applied: ${workspaceState.activeInputSummary()}   Model action: ${snapshot.lastModelActionIndex ?: "-"}", fontSize = 11.sp)
        Text(
            "Emu FPS: ${workspaceState.emulatedFps.toInt()}   View FPS: ${workspaceState.viewportFps.toInt()}   RTT: ${workspaceState.bridgeRoundTripMs.toInt()} ms   Repeat: ${workspaceState.lastStepRepeat}",
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun GlobalWorldPlanner(
    workspaceState: EmulatorWorkspaceState,
    rooms: List<RoomInfo>,
    onRoomSelected: (RoomInfo) -> Unit,
) {
    val graph = workspaceState.navGraph
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(graph, canvasSize) {
        if (graph == null || canvasSize.width == 0 || canvasSize.height == 0) {
            null
        } else {
            buildPlannerLayout(graph, canvasSize.width.toFloat(), canvasSize.height.toFloat())
        }
    }
    val trace = remember(graph, workspaceState.snapshot) { workspaceState.liveTracePoints() }
    val route = remember(graph, workspaceState.plannedRouteRoomIds.size, workspaceState.plannedRouteRoomIds.toList()) {
        workspaceState.plannedRoutePoints()
    }
    val cog = remember(graph, workspaceState.snapshot) { workspaceState.centerOfGravity() }

    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text("Global World Planner", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { canvasSize = it }
                .pointerInput(layout?.rooms) {
                    detectTapGestures { tap ->
                        val hit = layout?.rooms?.values?.firstOrNull { info ->
                            tap.x >= info.left &&
                                tap.x <= info.left + info.width &&
                                tap.y >= info.top &&
                                tap.y <= info.top + info.height
                        } ?: return@detectTapGestures
                        val room = rooms.firstOrNull { it.getRoomIdAsInt() == hit.roomId } ?: return@detectTapGestures
                        onRoomSelected(room)
                    }
                },
        ) {
            if (graph == null || layout == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Load nav_graph.json to render the planner",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPlannerAreas(graph, layout)
                    drawPlannerEdges(graph, layout)
                    drawRoute(route, layout)
                    drawTrace(trace, layout)
                    drawRooms(layout, currentRoomId = workspaceState.snapshot?.roomId)
                    drawCenterOfGravity(cog, layout)
                }
            }
        }
    }
}

private fun buildPlannerLayout(
    graph: LoadedNavGraph,
    width: Float,
    height: Float,
): PlannerCanvasLayout {
    val extent = navGraphExtent(graph)
    val usableWidth = maxOf(width - 28f, 10f)
    val usableHeight = maxOf(height - 28f, 10f)
    val scale = min(usableWidth / maxOf(extent.first, 1f), usableHeight / maxOf(extent.second, 1f))
    val offsetX = 14f
    val offsetY = 14f
    val rooms = graph.graph.nodes.associate { node ->
        val topLeft = graph.roomPoint(node.roomId, 0, 0) ?: graph.roomCenter(node.roomId)!!
        node.roomId to PlannerRoomLayout(
            roomId = node.roomId,
            roomName = node.name,
            areaName = node.areaName,
            worldLeft = topLeft.x,
            worldTop = topLeft.y,
            left = offsetX + topLeft.x * scale,
            top = offsetY + topLeft.y * scale,
            width = node.widthScreens * scale,
            height = node.heightScreens * scale,
        )
    }
    return PlannerCanvasLayout(
        rooms = rooms,
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlannerAreas(
    graph: LoadedNavGraph,
    layout: PlannerCanvasLayout,
) {
    val colors = listOf(
        Color(0x1F4F7CAC),
        Color(0x1F6B9E5F),
        Color(0x1FAE6B46),
        Color(0x1F8D5D9F),
        Color(0x1F329C9C),
        Color(0x1FA58D36),
        Color(0x1F666666),
    )
    graph.graph.nodes.groupBy { it.area }.forEach { (area, nodes) ->
        val rects = nodes.mapNotNull { layout.rooms[it.roomId] }
        if (rects.isEmpty()) return@forEach
        val left = rects.minOf { it.left } - 12f
        val top = rects.minOf { it.top } - 18f
        val right = rects.maxOf { it.left + it.width } + 12f
        val bottom = rects.maxOf { it.top + it.height } + 12f
        drawRoundRect(
            color = colors[area % colors.size],
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(14f, 14f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlannerEdges(
    graph: LoadedNavGraph,
    layout: PlannerCanvasLayout,
) {
    graph.graph.edges.forEach { edge ->
        val from = layout.rooms[edge.fromRoomId] ?: return@forEach
        val to = layout.rooms[edge.toRoomId] ?: return@forEach
        drawLine(
            color = Color(0x557A8791),
            start = Offset(from.left + from.width / 2f, from.top + from.height / 2f),
            end = Offset(to.left + to.width / 2f, to.top + to.height / 2f),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoute(
    route: List<WorldTracePoint>,
    layout: PlannerCanvasLayout,
) {
    if (route.size < 2) return
    for (i in 0 until route.lastIndex) {
        val start = route[i]
        val end = route[i + 1]
        if (start.roomId != end.roomId) continue
        drawLine(
            color = Color(0xFFFFA726),
            start = layoutPoint(start, layout),
            end = layoutPoint(end, layout),
            strokeWidth = 3f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrace(
    trace: List<WorldTracePoint>,
    layout: PlannerCanvasLayout,
) {
    if (trace.size < 2) return
    for (i in 0 until trace.lastIndex) {
        if (trace[i].roomId != trace[i + 1].roomId) continue
        val t = i / maxOf(trace.size - 1f, 1f)
        val color = Color(
            red = 0.15f + (0.8f * t),
            green = 0.8f - (0.45f * t),
            blue = 0.95f - (0.65f * t),
            alpha = 0.9f,
        )
        drawLine(
            color = color,
            start = layoutPoint(trace[i], layout),
            end = layoutPoint(trace[i + 1], layout),
            strokeWidth = 2f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRooms(
    layout: PlannerCanvasLayout,
    currentRoomId: Int?,
) {
    layout.rooms.values.forEach { room ->
        val isCurrent = room.roomId == currentRoomId
        drawRoundRect(
            color = if (isCurrent) Color(0xFF121E28) else Color(0xFF1F2931),
            topLeft = Offset(room.left, room.top),
            size = Size(room.width, room.height),
            cornerRadius = CornerRadius(6f, 6f),
        )
        drawRoundRect(
            color = if (isCurrent) Color(0xFF7DE1D1) else Color(0xFF52636F),
            topLeft = Offset(room.left, room.top),
            size = Size(room.width, room.height),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = if (isCurrent) 3f else 1.5f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenterOfGravity(
    cog: WorldTracePoint?,
    layout: PlannerCanvasLayout,
) {
    cog ?: return
    val point = layoutPoint(cog, layout)
    drawCircle(Color.Black, radius = 8f, center = point)
    drawCircle(Color.White, radius = 5f, center = point)
}

private fun layoutPoint(
    point: WorldTracePoint,
    layout: PlannerCanvasLayout,
): Offset {
    return Offset(
        x = layout.offsetX + point.x * layout.scale,
        y = layout.offsetY + point.y * layout.scale,
    )
}
