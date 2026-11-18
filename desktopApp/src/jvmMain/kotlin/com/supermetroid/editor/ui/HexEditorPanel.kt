package com.supermetroid.editor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.AppConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HEX_FONT = FontFamily.Monospace
private const val TITLE_BAR_HEIGHT = 24
// Hex grid: 64dp address + 16 * 26dp cells + 8dp padding + 14dp scrollbar = 502dp
private const val HEX_GRID_MIN_WIDTH = 502f
private const val DEFAULT_NAV_WIDTH = 270f
private const val MIN_NAV_WIDTH = 120f
private const val MAX_NAV_WIDTH = 400f
private const val DEFAULT_WIDTH = 778f // HEX_GRID_MIN_WIDTH(502) + DEFAULT_NAV_WIDTH(270) + divider(6)
private const val DEFAULT_HEIGHT = 480f
private const val MIN_HEIGHT = 180f

// Highlight colors that work on the dark charcoal theme
private val HIGHLIGHT_BG = Color(0xFF2E4A2E)
private val SELECTED_BG = Color(0xFF3A5050)
private val EDITING_BG = Color(0xFF4A3A20)

/**
 * Floating, draggable, resizable hex/memory editor panel.
 *
 * Shows a scrollable hex grid of emulator WRAM, a collapsible left-nav of
 * bookmarked Super Metroid addresses, and a detail pane showing the selected
 * address value in hex / decimal / binary. Supports inline hex editing.
 */
@Composable
fun HexEditorPanel(
    state: HexEditorState,
    emulatorWorkspaceState: EmulatorWorkspaceState,
    containerSizePx: IntSize,
    density: Float,
    onClose: () -> Unit,
    onAddWatch: (AsmDebugWatchDraft) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val fs = LocalEditorTheme.current.fontSize.value
    val colors = MaterialTheme.colorScheme

    // Load persisted geometry (-1 = use defaults)
    val savedConfig = remember { AppConfig.load() }
    val initWidth = savedConfig.hexEditorWindowWidth.takeIf { it > 0f } ?: DEFAULT_WIDTH
    val initHeight = savedConfig.hexEditorWindowHeight.takeIf { it > 0f } ?: DEFAULT_HEIGHT
    val initNavW = savedConfig.hexEditorNavWidth.takeIf { it > 0f } ?: DEFAULT_NAV_WIDTH
    val initX = (savedConfig.hexEditorWindowX.takeIf { it >= 0f }
        ?: ((containerSizePx.width / density) - initWidth - 12f).coerceAtLeast(8f)) * density
    val initY = (savedConfig.hexEditorWindowY.takeIf { it >= 0f } ?: 8f) * density

    var offsetX by remember { mutableStateOf(initX) }
    var offsetY by remember { mutableStateOf(initY) }
    var windowWidth by remember { mutableStateOf(initWidth) }
    var windowHeight by remember { mutableStateOf(initHeight) }
    var navWidthDp by remember { mutableStateOf(initNavW) }

    // Persist geometry after changes settle
    LaunchedEffect(offsetX, offsetY, windowWidth, windowHeight, navWidthDp) {
        delay(400)
        val safeDensity = density.coerceAtLeast(0.01f)
        AppConfig.update {
            copy(
                hexEditorWindowX = offsetX / safeDensity,
                hexEditorWindowY = offsetY / safeDensity,
                hexEditorWindowWidth = windowWidth,
                hexEditorWindowHeight = windowHeight,
                hexEditorNavWidth = navWidthDp,
            )
        }
    }

    // Editing state: which cell is being edited
    var editingAddress by remember { mutableStateOf<Int?>(null) }

    // Cancel editing when emulator disconnects
    if (!emulatorWorkspaceState.isMemoryAccessible) {
        editingAddress = null
    }

    // Auto-refresh
    LaunchedEffect(state.autoRefresh, state.baseAddress, emulatorWorkspaceState.isMemoryAccessible) {
        while (state.autoRefresh && emulatorWorkspaceState.isMemoryAccessible) {
            state.refresh(emulatorWorkspaceState)
            delay(100)
        }
    }

    LaunchedEffect(Unit) {
        state.refresh(emulatorWorkspaceState)
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(windowWidth.dp)
            .height(windowHeight.dp),
        shape = RoundedCornerShape(10.dp),
        color = colors.surface,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Draggable title bar ──
                HexEditorTitleBar(
                    state = state,
                    emulatorWorkspaceState = emulatorWorkspaceState,
                    onClose = onClose,
                    onDrag = { dragAmount ->
                        val safeDensity = density.coerceAtLeast(0.01f)
                        val maxX = (containerSizePx.width - windowWidth * safeDensity).coerceAtLeast(0f)
                        val maxY = (containerSizePx.height - windowHeight * safeDensity).coerceAtLeast(0f)
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                    },
                )

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // ── Left nav (collapsible, resizable) ──
                    if (state.navExpanded) {
                        BookmarkNav(
                            state = state,
                            onNavigate = { bookmark ->
                                state.goToBookmark(bookmark)
                                editingAddress = null
                                scope.launch { state.refresh(emulatorWorkspaceState) }
                            },
                            modifier = Modifier.width(navWidthDp.dp).fillMaxHeight(),
                        )
                        DraggableDividerVertical(
                            onDelta = { dx ->
                                navWidthDp = (navWidthDp + dx).coerceIn(
                                    MIN_NAV_WIDTH,
                                    (windowWidth - HEX_GRID_MIN_WIDTH).coerceAtMost(MAX_NAV_WIDTH),
                                )
                            },
                            width = 6.dp,
                        )
                    }

                    // ── Hex grid ──
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        HexGrid(
                            state = state,
                            editingAddress = editingAddress,
                            canEdit = emulatorWorkspaceState.isMemoryAccessible,
                            onCellClick = { addr ->
                                state.selectedAddress = addr
                                state.highlightAddress = addr
                                state.highlightSize = 1
                                if (emulatorWorkspaceState.isMemoryAccessible) {
                                    editingAddress = addr
                                }
                            },
                            onCellEdited = { addr, value ->
                                scope.launch {
                                    state.writeByte(emulatorWorkspaceState, addr, value)
                                    state.refresh(emulatorWorkspaceState)
                                }
                                // Auto-advance to next cell
                                val nextAddr = addr + 1
                                state.selectedAddress = nextAddr
                                state.highlightAddress = nextAddr
                                state.highlightSize = 1
                                editingAddress = nextAddr
                            },
                            onEditCancelled = { editingAddress = null },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )

                        // ── Detail pane ──
                        state.selectedAddress?.let { addr ->
                            Divider(color = colors.outlineVariant)
                            SelectedAddressDetail(state, addr, onAddWatch)
                        }
                    }
                }

                // ── Status bar ──
                if (state.statusMessage.isNotEmpty()) {
                    Text(
                        state.statusMessage,
                        fontSize = fs.statusBar,
                        fontFamily = HEX_FONT,
                        color = if ("error" in state.statusMessage.lowercase()) colors.error
                            else colors.secondary,
                        modifier = Modifier.fillMaxWidth()
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── Resize handle (bottom-right corner) ──
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerInput(containerSizePx, density) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val safeDensity = density.coerceAtLeast(0.01f)
                            val navExtra = if (state.navExpanded) navWidthDp + 6f else 0f
                            val minW = HEX_GRID_MIN_WIDTH + navExtra
                            windowWidth = (windowWidth + dragAmount.x / safeDensity)
                                .coerceIn(minW, (containerSizePx.width / safeDensity) - (offsetX / safeDensity))
                            windowHeight = (windowHeight + dragAmount.y / safeDensity)
                                .coerceIn(MIN_HEIGHT, (containerSizePx.height / safeDensity) - (offsetY / safeDensity))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\u2921",
                    fontSize = 20.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }

}

// ── Title bar (draggable) ───────────────────────────────────────────────────

@Composable
private fun HexEditorTitleBar(
    state: HexEditorState,
    emulatorWorkspaceState: EmulatorWorkspaceState,
    onClose: () -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fs = LocalEditorTheme.current.fontSize.value
    val colors = MaterialTheme.colorScheme
    val currentOnDrag by rememberUpdatedState(onDrag)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT.dp)
            .background(colors.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Toggle nav
        IconButton(
            onClick = { state.navExpanded = !state.navExpanded },
            modifier = Modifier.size(20.dp),
        ) {
            Icon(
                if (state.navExpanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                contentDescription = "Toggle bookmark nav",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }

        Text(
            "HEX",
            fontSize = fs.body,
            fontWeight = FontWeight.SemiBold,
            fontFamily = HEX_FONT,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )

        Spacer(Modifier.width(8.dp))

        // Go-to address field
        OutlinedTextField(
            value = state.goToAddressText,
            onValueChange = { state.goToAddressText = it },
            modifier = Modifier.widthIn(max = 100.dp).height(28.dp),
            textStyle = TextStyle(fontSize = fs.detail, fontFamily = HEX_FONT, color = colors.onSurface),
            placeholder = { Text("\$0000", fontSize = fs.detail, color = colors.onSurfaceVariant.copy(alpha = 0.5f)) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedIndicatorColor = colors.outline,
                unfocusedIndicatorColor = colors.outlineVariant,
            ),
        )
        IconButton(
            onClick = {
                if (state.goToAddressFromText()) {
                    scope.launch { state.refresh(emulatorWorkspaceState) }
                }
            },
            modifier = Modifier.size(20.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = "Go to address",
                tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }

        Spacer(Modifier.weight(1f))

        // Auto-refresh toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.autoRefresh,
                onCheckedChange = { state.autoRefresh = it },
                modifier = Modifier.size(18.dp),
            )
            Text("Live", fontSize = fs.statusBar, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp))
        }

        Spacer(Modifier.width(4.dp))

        // Manual refresh
        IconButton(
            onClick = { scope.launch { state.refresh(emulatorWorkspaceState) } },
            modifier = Modifier.size(20.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }

        // Page nav
        IconButton(
            onClick = { state.pageUp(); scope.launch { state.refresh(emulatorWorkspaceState) } },
            modifier = Modifier.size(20.dp),
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Page up",
                tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        IconButton(
            onClick = { state.pageDown(); scope.launch { state.refresh(emulatorWorkspaceState) } },
            modifier = Modifier.size(20.dp),
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Page down",
                tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }

        // Close
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(20.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close hex editor",
                tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

// ── Bookmark nav ────────────────────────────────────────────────────────────

@Composable
private fun BookmarkNav(
    state: HexEditorState,
    onNavigate: (MemoryBookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val fs = LocalEditorTheme.current.fontSize.value
    val colors = MaterialTheme.colorScheme
    var expandedCategories by remember {
        mutableStateOf(state.bookmarksByCategory().keys.toSet())
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .verticalScroll(scrollState)
                .padding(4.dp),
        ) {
            Text(
                "Bookmarks",
                fontSize = fs.detail,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
            )

            state.bookmarksByCategory().forEach { (category, bookmarks) ->
                val expanded = category in expandedCategories
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedCategories = if (expanded) expandedCategories - category
                                else expandedCategories + category
                        }
                        .padding(vertical = 2.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        category,
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }

                if (expanded) {
                    bookmarks.forEach { bookmark ->
                        val isHighlighted = state.highlightAddress == (bookmark.address and 0x1FFFF)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isHighlighted) colors.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onNavigate(bookmark) }
                                .padding(start = 20.dp, top = 1.dp, bottom = 1.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "\$${"%04X".format(bookmark.address)}",
                                fontSize = fs.statusBar,
                                fontFamily = HEX_FONT,
                                color = colors.secondary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                bookmark.label,
                                fontSize = fs.statusBar,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

// ── Hex grid ────────────────────────────────────────────────────────────────

@Composable
private fun HexGrid(
    state: HexEditorState,
    editingAddress: Int?,
    canEdit: Boolean,
    onCellClick: (Int) -> Unit,
    onCellEdited: (address: Int, value: Byte) -> Unit,
    onEditCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val fs = LocalEditorTheme.current.fontSize.value
    val colors = MaterialTheme.colorScheme
    val headerColor = colors.onSurfaceVariant.copy(alpha = 0.6f)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(4.dp),
        ) {
            // Column header row
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text(
                    "Address",
                    fontSize = fs.statusBar,
                    fontFamily = HEX_FONT,
                    fontWeight = FontWeight.Bold,
                    color = headerColor,
                    modifier = Modifier.width(64.dp),
                )
                for (col in 0 until state.bytesPerRow) {
                    Text(
                        "%02X".format(col),
                        fontSize = fs.statusBar,
                        fontFamily = HEX_FONT,
                        fontWeight = FontWeight.Bold,
                        color = headerColor,
                        modifier = Modifier.width(26.dp),
                    )
                }
            }

            Divider(color = colors.outlineVariant)

            // Data rows
            val totalRows = (state.memoryBlock.size + state.bytesPerRow - 1) / state.bytesPerRow
            for (row in 0 until totalRows) {
                val rowAddr = state.baseAddress + row * state.bytesPerRow
                val rowBg = if (row % 2 == 0) colors.surface else colors.surfaceVariant.copy(alpha = 0.3f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .padding(vertical = 1.dp),
                ) {
                    // Address label
                    Text(
                        "\$${"%04X".format(rowAddr)}",
                        fontSize = fs.statusBar,
                        fontFamily = HEX_FONT,
                        color = colors.secondary,
                        modifier = Modifier.width(64.dp),
                    )

                    // Hex bytes
                    for (col in 0 until state.bytesPerRow) {
                        val addr = rowAddr + col
                        val byteIndex = row * state.bytesPerRow + col
                        val byteVal = if (byteIndex in state.memoryBlock.indices) {
                            state.memoryBlock[byteIndex]
                        } else null

                        val isHighlighted = state.highlightAddress?.let { hlAddr ->
                            addr >= hlAddr && addr < hlAddr + state.highlightSize
                        } ?: false
                        val isSelected = state.selectedAddress == addr
                        val isEditing = editingAddress == addr && canEdit

                        val cellBg = when {
                            isEditing -> EDITING_BG
                            isSelected -> SELECTED_BG
                            isHighlighted -> HIGHLIGHT_BG
                            else -> Color.Transparent
                        }

                        if (isEditing) {
                            HexEditCell(
                                currentValue = byteVal,
                                fontSize = fs.statusBar,
                                cellBg = cellBg,
                                onCommit = { newVal -> onCellEdited(addr, newVal) },
                                onCancel = onEditCancelled,
                            )
                        } else {
                            val cellColor = when {
                                byteVal == null -> colors.onSurface.copy(alpha = 0.25f)
                                isSelected -> colors.onPrimaryContainer
                                isHighlighted -> colors.secondary
                                else -> colors.onSurface
                            }
                            Text(
                                text = byteVal?.let { "%02X".format(it.toInt() and 0xFF) } ?: "--",
                                fontSize = fs.statusBar,
                                fontFamily = HEX_FONT,
                                color = cellColor,
                                modifier = Modifier
                                    .width(26.dp)
                                    .background(cellBg, RoundedCornerShape(2.dp))
                                    .pointerInput(addr) {
                                        detectTapGestures { onCellClick(addr) }
                                    }
                                    .padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

// ── Hex value dropdown ──────────────────────────────────────────────────────

/** Pre-built table of all 256 byte values for the dropdown. */
private data class HexEntry(val value: Int, val hex: String, val dec: String, val bin: String)

private val HEX_ENTRIES: List<HexEntry> = (0..255).map { v ->
    val bits = Integer.toBinaryString(v).padStart(8, '0')
    HexEntry(
        value = v,
        hex = "\$${"%02X".format(v)}",
        dec = "$v",
        bin = "${bits.substring(0, 4)} ${bits.substring(4)}",
    )
}

/**
 * A hex cell that shows the current value and opens a dropdown on click.
 * The dropdown lists all 256 byte values ($00–$FF) with hex, decimal, and binary columns.
 * Selecting a value writes it immediately to emulator memory.
 */
@Composable
private fun HexEditCell(
    currentValue: Byte?,
    fontSize: androidx.compose.ui.unit.TextUnit,
    cellBg: Color,
    onCommit: (Byte) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) } // open immediately
    val currentInt = currentValue?.toInt()?.and(0xFF) ?: 0
    val scrollState = rememberScrollState()

    Box {
        // The cell text (shows current value, same format as non-editing cells)
        Text(
            text = "%02X".format(currentInt),
            fontSize = fontSize,
            fontFamily = HEX_FONT,
            color = Color(0xFFFFCC44),
            maxLines = 1,
            modifier = Modifier
                .width(26.dp)
                .background(cellBg, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 2.dp),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onCancel() },
            modifier = Modifier.heightIn(max = 320.dp).width(220.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Hex", fontSize = fontSize, fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant, modifier = Modifier.width(40.dp))
                Text("Dec", fontSize = fontSize, fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant, modifier = Modifier.width(36.dp))
                Text("Binary", fontSize = fontSize, fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant, modifier = Modifier.width(72.dp))
            }
            Divider(color = colors.outlineVariant)

            // Scrollable list of 256 entries
            Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(scrollState)) {
                HEX_ENTRIES.forEach { entry ->
                    val isSelected = entry.value == currentInt
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(entry.hex, fontSize = fontSize, fontFamily = HEX_FONT,
                                    color = if (isSelected) colors.primary else colors.onSurface,
                                    modifier = Modifier.width(40.dp))
                                Text(entry.dec, fontSize = fontSize, fontFamily = HEX_FONT,
                                    color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.width(36.dp))
                                Text(entry.bin, fontSize = fontSize, fontFamily = HEX_FONT,
                                    color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.width(72.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onCommit(entry.value.toByte())
                        },
                        modifier = Modifier
                            .background(if (isSelected) colors.primaryContainer else Color.Transparent)
                            .height(24.dp),
                    )
                }
            }
        }

        // Scroll to current value when dropdown opens
        LaunchedEffect(expanded) {
            if (expanded) {
                // Each item ~24dp, scroll to center the current value
                scrollState.scrollTo((currentInt * 24 - 120).coerceAtLeast(0))
            }
        }
    }
}

// ── Detail pane ─────────────────────────────────────────────────────────────

@Composable
private fun SelectedAddressDetail(
    state: HexEditorState,
    address: Int,
    onAddWatch: (AsmDebugWatchDraft) -> Unit,
) {
    val byteVal = state.byteAt(address)
    val wordVal = state.wordAt(address)
    val fs = LocalEditorTheme.current.fontSize.value
    val colors = MaterialTheme.colorScheme
    val bookmark = state.bookmarks.firstOrNull { (it.address and 0x1FFFF) == address }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailItem("Address", "\$${"%04X".format(address)}", fs, colors)

            if (byteVal != null) {
                val b = byteVal.toInt() and 0xFF
                DetailItem("Hex (byte)", "\$${"%02X".format(b)}", fs, colors)
                DetailItem("Dec", "$b", fs, colors)
                DetailItem("Bin", formatBinary8(b), fs, colors)
            }

            if (wordVal != null) {
                DetailItem("Word (16-bit LE)", "\$${"%04X".format(wordVal)} = $wordVal", fs, colors)
            }

            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    val busAddress = wramDebugAddress(address) ?: return@TextButton
                    val width = AsmDebugWatchWidth.nearest(state.highlightSize.coerceIn(1, 4))
                    val expression = "\$${busAddress.toString(16).uppercase().padStart(6, '0')}"
                    onAddWatch(
                        AsmDebugWatchDraft(
                            name = bookmark?.label.orEmpty(),
                            expression = expression,
                            resolvedAddress = busAddress,
                            width = width,
                        ),
                    )
                },
            ) { Text("Add Watch…", fontSize = fs.statusBar) }
        }

        if (bookmark != null) {
            Text(
                "${bookmark.label}: ${bookmark.description}",
                fontSize = fs.statusBar,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String, fs: FontSize, colors: androidx.compose.material3.ColorScheme) {
    Column {
        Text(label, fontSize = fs.statusBar, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
        Text(
            value,
            fontSize = fs.detail,
            fontFamily = HEX_FONT,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
    }
}

private fun formatBinary8(value: Int): String {
    val bits = Integer.toBinaryString(value and 0xFF).padStart(8, '0')
    return "${bits.substring(0, 4)} ${bits.substring(4)}"
}
