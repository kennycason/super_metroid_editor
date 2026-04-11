package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.RoomInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.supermetroid.editor.rom.RomParser
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SNES_ASPECT = 4f / 3f // CRT display aspect ratio

// SM collected items bit flags ($7E:09A4)
private const val ITEM_VARIA_SUIT = 0x0001
private const val ITEM_SPRING_BALL = 0x0002
private const val ITEM_MORPH_BALL = 0x0004
private const val ITEM_SCREW_ATTACK = 0x0008
private const val ITEM_GRAVITY_SUIT = 0x0020
private const val ITEM_HI_JUMP = 0x0100
private const val ITEM_SPACE_JUMP = 0x0200
private const val ITEM_SPEED_BOOSTER = 0x2000
private const val ITEM_GRAPPLE = 0x4000
private const val ITEM_XRAY = 0x8000

// SM collected beams bit flags ($7E:09A8)
private const val BEAM_WAVE = 0x0001
private const val BEAM_ICE = 0x0002
private const val BEAM_SPAZER = 0x0004
private const val BEAM_PLASMA = 0x0008
private const val BEAM_CHARGE = 0x1000

private const val TARGET_EMU_FPS = 60.0
private const val MAX_STEP_REPEAT = 4
private const val FRAME_REFRESH_INTERVAL = 2
private const val TRACE_REFRESH_INTERVAL = 10
private const val FRAME_DURATION_NANOS = (1_000_000_000.0 / TARGET_EMU_FPS).toLong()
private const val CONTROL_BAR_HEIGHT = 36
private const val TITLE_BAR_HEIGHT = 24
private const val MIN_WIDTH = 280f
private const val MAX_WIDTH = 900f

@Composable
fun FloatingEmulatorWindow(
    workspaceState: EmulatorWorkspaceState,
    editorState: EditorState,
    romParser: RomParser?,
    rooms: List<RoomInfo> = emptyList(),
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Draggable position (persisted)
    val savedConfig = remember { AppConfig.load() }
    var offsetX by remember { mutableStateOf(savedConfig.emulatorWindowX) }
    var offsetY by remember { mutableStateOf(savedConfig.emulatorWindowY) }

    // Resizable width (height derived from aspect ratio, persisted)
    var windowWidth by remember { mutableStateOf(savedConfig.emulatorWindowWidth) }

    // Fast forward state
    var fastForwarding by remember { mutableStateOf(false) }

    // Save/load slot dropdown state
    var showSaveMenu by remember { mutableStateOf(false) }

    // Dev tools panel state
    var showDevTools by remember { mutableStateOf(false) }

    // Derived height: title bar + video (proportional) + control bar
    val videoHeight = windowWidth / SNES_ASPECT
    val totalHeight = TITLE_BAR_HEIGHT + videoHeight + CONTROL_BAR_HEIGHT

    // Auto-set ROM path from editor — restart emulator if a session is active
    LaunchedEffect(editorState.project.romPath) {
        val romPath = editorState.project.romPath.takeIf { it.isNotBlank() }
        workspaceState.updateRomPath(romPath)

        // If emulator is running and we got a new ROM, restart the session
        if (workspaceState.session.active && romPath != null) {
            workspaceState.disconnectBridge()
            // Clear stale state so we don't auto-load an old ROM's save state
            workspaceState.clearSavedStateSelection()
            workspaceState.connectBridge()
            workspaceState.propagateAudioState()
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

    // Emulator frame stepping loop
    LaunchedEffect(workspaceState.isRunning, workspaceState.session.active, workspaceState.isExternalBackend) {
        if (workspaceState.isExternalBackend) {
            // External backend: poll snapshot every ~100ms
            while (workspaceState.isRunning && workspaceState.session.active) {
                workspaceState.pollExternalSnapshot()
                delay(100L)
            }
        } else {
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

                // Audio-aware pacing: if audio buffer is nearly full, the emulator
                // is running ahead of real-time. Yield to let audio drain.
                if (!fastForwarding && !workspaceState.audioHasHeadroom && pendingFrames < 2.0) {
                    delay(2L)
                    continue
                }

                if (pendingFrames < 1.0) {
                    val waitMs = ceil(((1.0 - pendingFrames) * FRAME_DURATION_NANOS) / 1_000_000.0).toLong().coerceAtLeast(1L)
                    delay(waitMs)
                    continue
                }
                val baseRepeat = pendingFrames.toInt().coerceIn(1, MAX_STEP_REPEAT)
                val repeat = if (fastForwarding) (baseRepeat * 4).coerceAtMost(16) else baseRepeat
                pendingFrames = (pendingFrames - baseRepeat).coerceAtMost(MAX_STEP_REPEAT.toDouble())
                workspaceState.stepFrame(
                    repeat = repeat,
                    includeFrame = tick % FRAME_REFRESH_INTERVAL == 0L,
                    includeTrace = tick % TRACE_REFRESH_INTERVAL == 0L,
                )
                workspaceState.pendingComboAction?.let { combo ->
                    workspaceState.pendingComboAction = null
                    val ws = workspaceState
                    when (combo) {
                        "save" -> ws.saveQuickState("slot_${ws.saveSlotIndex}")
                        "load" -> ws.loadNamedState("slot_${ws.saveSlotIndex}")
                        "slot_up" -> ws.saveSlotIndex = (ws.saveSlotIndex + 1) % 129
                        "slot_down" -> ws.saveSlotIndex = (ws.saveSlotIndex - 1 + 129) % 129
                    }
                }
                tick += 1
                if (pendingFrames > MAX_STEP_REPEAT * 2) {
                    pendingFrames = MAX_STEP_REPEAT.toDouble()
                }
            }
        }
    }

    // Auto-focus when a frame appears
    LaunchedEffect(workspaceState.session.active, workspaceState.frameBitmap) {
        if (workspaceState.session.active && workspaceState.frameBitmap != null) {
            delay(16)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(windowWidth.dp)
            .height(totalHeight.dp),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Title bar (draggable) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TITLE_BAR_HEIGHT.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                },
                                onDragEnd = {
                                    workspaceState.persistEmulatorWindowGeometry(offsetX, offsetY, windowWidth)
                                },
                            )
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Emulator",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (workspaceState.session.active) {
                            Text(
                                "${workspaceState.emulatedFps.toInt()} fps",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (workspaceState.gamepadManager.isConnected) {
                            Icon(
                                Icons.Default.Gamepad,
                                contentDescription = "Gamepad connected",
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF4CAF50),
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close emulator",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── Video viewport / Item tracker ──
                if (workspaceState.isExternalBackend) {
                    // External emulator: show item tracker + live status
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(EditorColors.emulatorPanelBg)
                            .padding(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Status line
                            val snap = workspaceState.snapshot
                            val rid = snap?.roomId
                            Text(
                                workspaceState.statusMessage,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (rid != null) {
                                Text(
                                    "Room: 0x${rid.toString(16).uppercase()}  HP: ${snap.health ?: 0}/${snap.maxHealth ?: 0}  Pos: (${snap.samusX ?: 0}, ${snap.samusY ?: 0})",
                                    color = EditorColors.emulatorText,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            // Item tracker icons
                            ItemTrackerPanel(
                                snapshot = workspaceState.snapshot,
                            )
                        }
                    }
                } else {
                    // Embedded emulator: video viewport (keyboard-focusable)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(EditorColors.emulatorPanelBg)
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
                                                } else workspaceState.saveSlot(0)
                                            }
                                            true
                                        } else if (event.key == Key.F2) {
                                            scope.launch {
                                                if (event.isShiftPressed) {
                                                    workspaceState.clearCheckpointModifierKeys()
                                                    workspaceState.loadSlot(workspaceState.saveSlots[1])
                                                } else workspaceState.saveSlot(1)
                                            }
                                            true
                                        } else if (event.key == Key.F3) {
                                            scope.launch {
                                                if (event.isShiftPressed) {
                                                    workspaceState.clearCheckpointModifierKeys()
                                                    workspaceState.loadSlot(workspaceState.saveSlots[2])
                                                } else workspaceState.saveSlot(2)
                                            }
                                            true
                                        } else if (event.key == Key.F4) {
                                            scope.launch {
                                                if (event.isShiftPressed) {
                                                    workspaceState.clearCheckpointModifierKeys()
                                                    workspaceState.loadSlot(workspaceState.saveSlots[3])
                                                } else workspaceState.saveSlot(3)
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
                                contentScale = ContentScale.FillBounds,
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                            ) {
                                Text(
                                    workspaceState.statusMessage,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Arrows + Z/X/A/S/Q/W | F1-F4 save/load",
                                    color = EditorColors.emulatorText,
                                    fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Controller: R+Y+Sel save | L+Y+Sel load | L+R+Y+\u2191\u2193 slot",
                                    color = EditorColors.emulatorText,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                // ── Control bar ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CONTROL_BAR_HEIGHT.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clipToBounds()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val btnShape = RoundedCornerShape(6.dp)

                    // Play / Pause
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (!workspaceState.isConnected) workspaceState.connectBridge()
                                workspaceState.propagateAudioState()
                                if (workspaceState.isConnected && !workspaceState.session.active) {
                                    val rp = romParser
                                    if (rp != null) {
                                        val patchedPath = editorState.exportToRom(rp)
                                        if (patchedPath != null) {
                                            workspaceState.updateRomPath(patchedPath)
                                        }
                                    }
                                    // Don't auto-load save state on fresh start
                                    workspaceState.clearSavedStateSelection()
                                    workspaceState.startSession()
                                    workspaceState.setLoopRunning(true)
                                } else if (workspaceState.session.active) {
                                    workspaceState.setLoopRunning(!workspaceState.isRunning)
                                }
                            }
                        },
                        enabled = !workspaceState.isBusy,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(btnShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    ) {
                        Icon(
                            if (workspaceState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (workspaceState.isRunning) "Pause" else "Play",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (!workspaceState.isExternalBackend) {
                        Spacer(Modifier.width(4.dp))

                        // Fast forward
                        IconButton(
                            onClick = { fastForwarding = !fastForwarding },
                            enabled = workspaceState.session.active && !workspaceState.isBusy,
                            modifier = Modifier.size(30.dp).clip(btnShape),
                        ) {
                            Icon(
                                Icons.Default.FastForward, "Fast forward",
                                Modifier.size(18.dp),
                                tint = if (fastForwarding) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // Restart (rebuild patched ROM + restart session — fresh, no save state)
                    IconButton(
                        onClick = {
                            scope.launch {
                                workspaceState.disconnectBridge()
                                workspaceState.clearSavedStateSelection()
                                workspaceState.connectBridge()
                                workspaceState.propagateAudioState()
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
                        },
                        enabled = workspaceState.session.active && !workspaceState.isBusy,
                        modifier = Modifier.size(30.dp).clip(btnShape),
                    ) {
                        Icon(Icons.Default.Refresh, "Restart with latest patches", Modifier.size(18.dp))
                    }

                    if (!workspaceState.isExternalBackend) {
                        Spacer(Modifier.width(4.dp))

                        Text(
                            "|",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )

                        Spacer(Modifier.width(4.dp))

                        // SAVE text button
                        Surface(
                            onClick = {
                                scope.launch { workspaceState.saveQuickState("slot_${workspaceState.saveSlotIndex}") }
                            },
                            enabled = workspaceState.session.active && !workspaceState.isBusy,
                            color = Color.Transparent,
                            shape = btnShape,
                        ) {
                            Text(
                                "SAVE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }

                        Spacer(Modifier.width(2.dp))

                        // LOAD text button
                        Surface(
                            onClick = {
                                scope.launch { workspaceState.loadNamedState("slot_${workspaceState.saveSlotIndex}") }
                            },
                            enabled = workspaceState.session.active && !workspaceState.isBusy,
                            color = Color.Transparent,
                            shape = btnShape,
                        ) {
                            Text(
                                "LOAD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }

                        Spacer(Modifier.width(2.dp))

                        // Slot number + dropdown with metadata
                        Box {
                            Surface(
                                onClick = { showSaveMenu = !showSaveMenu },
                                color = Color.Transparent,
                                shape = btnShape,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                ) {
                                    Text(
                                        "${workspaceState.saveSlotIndex}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        Icons.Default.ArrowDropDown, "Select slot",
                                        Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showSaveMenu,
                                onDismissRequest = { showSaveMenu = false },
                                modifier = Modifier.widthIn(min = 520.dp),
                            ) {
                                for (i in 0..128) {
                                    val meta = workspaceState.getSlotMeta(i)
                                    DropdownMenuItem(
                                        text = {
                                            if (meta == null) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        "$i",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.width(24.dp),
                                                    )
                                                    Text(
                                                        "[EMPTY]",
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                    )
                                                }
                                            } else {
                                                SaveSlotRow(slotIndex = i, meta = meta)
                                            }
                                        },
                                        onClick = { workspaceState.saveSlotIndex = i; showSaveMenu = false },
                                        modifier = Modifier.height(if (meta != null) 36.dp else 28.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(2.dp))

                        // Mute toggle
                        IconButton(
                            onClick = { workspaceState.toggleAudioMute() },
                            enabled = workspaceState.isConnected,
                            modifier = Modifier.size(24.dp).clip(btnShape),
                        ) {
                            Icon(
                                if (workspaceState.audioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Toggle mute",
                                modifier = Modifier.size(14.dp),
                            )
                        }

                        // Volume slider
                        Slider(
                            value = if (workspaceState.audioMuted) 0f else workspaceState.audioVolume,
                            onValueChange = { v ->
                                if (workspaceState.audioMuted && v > 0f) {
                                    // Unmute when dragging slider up from muted
                                    workspaceState.toggleAudioMute()
                                }
                                workspaceState.updateAudioVolume(v)
                            },
                            modifier = Modifier.width(60.dp).height(20.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            ),
                        )

                        Spacer(Modifier.width(2.dp))

                        Text(
                            "|",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )

                        Spacer(Modifier.width(2.dp))

                        // Dev tools toggle + popup
                        Box {
                            IconButton(
                                onClick = { showDevTools = !showDevTools },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(btnShape)
                                    .background(
                                        if (showDevTools) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    ),
                            ) {
                                Text(
                                    "⚙",
                                    fontSize = 18.sp,
                                    color = if (showDevTools) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showDevTools,
                                onDismissRequest = { showDevTools = false },
                                modifier = Modifier.widthIn(min = windowWidth.coerceAtMost(500f).dp),
                            ) {
                                FloatingDevToolsPanel(
                                    workspaceState = workspaceState,
                                    rooms = rooms,
                                    romParser = romParser,
                                )
                            }
                        }
                    }
                }
            }

            // ── Resize handle (bottom-right corner) ──
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                windowWidth = (windowWidth + dragAmount.x).coerceIn(MIN_WIDTH, MAX_WIDTH)
                            },
                            onDragEnd = {
                                workspaceState.persistEmulatorWindowGeometry(offsetX, offsetY, windowWidth)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\u2921",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ── Save slot rich display composables ─────────────────────────────────────

/** Sprite coordinates in item_sprites.png (16x16 tiles) — matches ItemTrackerPanel */
private data class MiniSpriteCoord(val x: Int, val y: Int)

private val MINI_ETANK_SPRITE = MiniSpriteCoord(64, 0)
private val MINI_RESERVE_SPRITE = MiniSpriteCoord(96, 16)
private val MINI_MISSILE_SPRITE = MiniSpriteCoord(0, 16)
private val MINI_SUPER_SPRITE = MiniSpriteCoord(32, 16)
private val MINI_PB_SPRITE = MiniSpriteCoord(64, 16)

private data class MiniItemEntry(val sprite: MiniSpriteCoord, val bit: Int, val isBeam: Boolean = false)

private val MINI_ITEM_SPRITES = listOf(
    MiniItemEntry(MiniSpriteCoord(0, 80), ITEM_VARIA_SUIT),       // Varia
    MiniItemEntry(MiniSpriteCoord(32, 80), ITEM_GRAVITY_SUIT),    // Gravity
    MiniItemEntry(MiniSpriteCoord(0, 0), ITEM_MORPH_BALL),        // Morph
    MiniItemEntry(MiniSpriteCoord(32, 0), 0x1000),                // Bombs
    MiniItemEntry(MiniSpriteCoord(0, 48), ITEM_SPRING_BALL),      // Spring
    MiniItemEntry(MiniSpriteCoord(0, 32), ITEM_HI_JUMP),          // HiJump
    MiniItemEntry(MiniSpriteCoord(32, 48), ITEM_SPACE_JUMP),      // Space
    MiniItemEntry(MiniSpriteCoord(32, 32), ITEM_SPEED_BOOSTER),   // Speed
    MiniItemEntry(MiniSpriteCoord(64, 48), ITEM_SCREW_ATTACK),    // Screw
    MiniItemEntry(MiniSpriteCoord(64, 32), ITEM_GRAPPLE),         // Grapple
    MiniItemEntry(MiniSpriteCoord(96, 32), ITEM_XRAY),            // XRay
    MiniItemEntry(MiniSpriteCoord(96, 48), BEAM_CHARGE, isBeam = true),  // Charge
    MiniItemEntry(MiniSpriteCoord(32, 64), BEAM_WAVE, isBeam = true),    // Wave
    MiniItemEntry(MiniSpriteCoord(64, 64), BEAM_ICE, isBeam = true),     // Ice
    MiniItemEntry(MiniSpriteCoord(0, 64), BEAM_SPAZER, isBeam = true),   // Spazer
    MiniItemEntry(MiniSpriteCoord(96, 64), BEAM_PLASMA, isBeam = true),  // Plasma
)

/** Full row for a populated save slot in the dropdown. */
@Composable
private fun SaveSlotRow(slotIndex: Int, meta: SaveSlotMeta) {
    val itemBitmap = remember {
        try {
            useResource("item_sprites.png") { loadImageBitmap(it) }
        } catch (_: Exception) { null }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Slot number
        Text(
            "$slotIndex",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )

        // Room name
        Text(
            meta.roomName ?: "???",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )

        // Mini e-tank grid — only draw filled tanks, compact layout
        if (meta.etanks > 0) {
            MiniEtankGrid(etanks = meta.etanks)
        }

        // Reserve tanks
        if (meta.reserveTanks > 0) {
            MiniReserveTanks(count = meta.reserveTanks)
        }

        // Ammo with sprite icons — only show if > 0
        if (itemBitmap != null) {
            if (meta.missiles > 0) {
                MiniAmmoWithIcon(itemBitmap, MINI_MISSILE_SPRITE, meta.missiles)
            }
            if (meta.supers > 0) {
                MiniAmmoWithIcon(itemBitmap, MINI_SUPER_SPRITE, meta.supers)
            }
            if (meta.powerBombs > 0) {
                MiniAmmoWithIcon(itemBitmap, MINI_PB_SPRITE, meta.powerBombs)
            }

            // Item powerup icons — only obtained
            val items = meta.collectedItems
            val beams = meta.collectedBeams
            for (entry in MINI_ITEM_SPRITES) {
                val bits = if (entry.isBeam) beams else items
                if (bits and entry.bit != 0) {
                    MiniSpriteIcon(itemBitmap, entry.sprite)
                }
            }
        }
    }
}

/**
 * Mini e-tank grid: 2 rows x up to 10 columns, only filled tanks drawn.
 * Bright SM yellow, compact sizing based on actual count.
 */
@Composable
private fun MiniEtankGrid(etanks: Int) {
    val cols = if (etanks > 10) 10 else etanks.coerceAtLeast(1)
    val rows = if (etanks > 10) 2 else 1
    val actualSecondRow = if (etanks > 10) etanks - 10 else 0
    val cellSize = 6f
    val gap = 1.5f
    val totalW = cols * cellSize + (cols - 1) * gap
    val totalH = rows * cellSize + (rows - 1) * gap

    val filledColor = Color(0xFFFFE040) // bright SM energy yellow

    Canvas(modifier = Modifier.size(width = totalW.dp, height = totalH.dp)) {
        val cellPx = cellSize.dp.toPx()
        val gapPx = gap.dp.toPx()
        // First row
        val firstRowCount = if (etanks > 10) 10 else etanks
        for (col in 0 until firstRowCount) {
            drawRect(
                color = filledColor,
                topLeft = Offset(col * (cellPx + gapPx), 0f),
                size = Size(cellPx, cellPx),
            )
        }
        // Second row (if needed)
        for (col in 0 until actualSecondRow) {
            drawRect(
                color = filledColor,
                topLeft = Offset(col * (cellPx + gapPx), cellPx + gapPx),
                size = Size(cellPx, cellPx),
            )
        }
    }
}

/** Reserve tank indicators - bright cyan squares. */
@Composable
private fun MiniReserveTanks(count: Int) {
    val cellSize = 6f
    val gap = 1.5f
    val totalW = count * cellSize + (count - 1).coerceAtLeast(0) * gap
    val reserveColor = Color(0xFF40E0FF) // bright cyan

    Canvas(modifier = Modifier.size(width = totalW.dp, height = cellSize.dp)) {
        val cellPx = cellSize.dp.toPx()
        val gapPx = gap.dp.toPx()
        for (i in 0 until count) {
            drawRect(
                color = reserveColor,
                topLeft = Offset(i * (cellPx + gapPx), 0f),
                size = Size(cellPx, cellPx),
            )
        }
    }
}

/** Ammo icon with sprite + count. */
@Composable
private fun MiniAmmoWithIcon(bitmap: ImageBitmap, sprite: MiniSpriteCoord, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiniSpriteIcon(bitmap, sprite)
        Text(
            "$count",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** Draw a single 14x14dp sprite from the item_sprites.png sheet. */
@Composable
private fun MiniSpriteIcon(bitmap: ImageBitmap, sprite: MiniSpriteCoord, sizeDp: Int = 14) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(sprite.x, sprite.y),
            srcSize = IntSize(16, 16),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

// ── Dev Tools Panel ─────────────────────────────────────────────────────────

@Composable
private fun FloatingDevToolsPanel(
    workspaceState: EmulatorWorkspaceState,
    rooms: List<RoomInfo>,
    romParser: RomParser?,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var selectedDoorIndex by remember { mutableStateOf(0) }
    var doorExpanded by remember { mutableStateOf(false) }

    val filteredRooms = remember(searchQuery, rooms) {
        if (searchQuery.isBlank()) rooms
        else rooms.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.id.contains(searchQuery, ignoreCase = true)
        }.take(50)
    }

    val doors = remember(selectedRoom, romParser) {
        val rp = romParser ?: return@remember emptyList()
        val room = selectedRoom ?: return@remember emptyList()
        val header = rp.readRoomHeader(room.getRoomIdAsInt()) ?: return@remember emptyList()
        rp.parseDoorList(header.doorOut)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Room Warp", fontWeight = FontWeight.Bold, fontSize = 12.sp)

        // Room search
        AppTextInput(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search rooms...",
            modifier = Modifier.fillMaxWidth(),
        )

        // Room list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        ) {
            filteredRooms.forEach { room ->
                val isSelected = selectedRoom?.id == room.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable {
                            selectedRoom = room
                            selectedDoorIndex = 0
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        room.name.ifBlank { room.id },
                        fontSize = 10.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        room.id,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Door selection + warp
        if (doors.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { doorExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        val door = doors.getOrNull(selectedDoorIndex)
                        val destName = if (door != null) {
                            val destRoom = rooms.firstOrNull { it.getRoomIdAsInt() == door.destRoomPtr }
                            "#$selectedDoorIndex ${door.directionName} → ${destRoom?.name ?: "0x${door.destRoomPtr.toString(16)}"}"
                        } else "Select door"
                        Text(
                            destName,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = doorExpanded,
                        onDismissRequest = { doorExpanded = false },
                    ) {
                        doors.forEachIndexed { index, door ->
                            val destRoom = rooms.firstOrNull { it.getRoomIdAsInt() == door.destRoomPtr }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "#$index ${door.directionName} → ${destRoom?.name ?: "0x${door.destRoomPtr.toString(16).uppercase()}"}",
                                        fontSize = 10.sp,
                                    )
                                },
                                onClick = {
                                    selectedDoorIndex = index
                                    doorExpanded = false
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val dp = doors.getOrNull(selectedDoorIndex)?.doorDefPtr ?: return@Button
                        scope.launch { workspaceState.warpToDoor(dp) }
                    },
                    enabled = workspaceState.session.active && doors.getOrNull(selectedDoorIndex) != null,
                ) {
                    Text("Warp", fontSize = 11.sp)
                }
            }
        }
    }
}
