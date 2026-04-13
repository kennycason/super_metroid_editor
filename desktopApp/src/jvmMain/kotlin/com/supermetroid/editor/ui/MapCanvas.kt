package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.event.MouseEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import javax.imageio.ImageIO

private object EnemySpriteCache {
    private val cache = mutableMapOf<String, BufferedImage?>()

    fun get(hexId: String): BufferedImage? {
        return cache.getOrPut(hexId) {
            val stream = EnemySpriteCache::class.java.getResourceAsStream("/enemies/$hexId.png")
            stream?.use { ImageIO.read(it) }
        }
    }
}

/**
 * Shot block (type 0xC) BTS classification.
 * BTS is an index into the PLM table at $94:9EA6. Each entry selects a PLM
 * whose setup routine determines which weapons can break the block.
 *
 * Vanilla SM supports BTS 0x00-0x0B only:
 *   BTS 0x00-0x03: Any weapon breakable (beam/missile/bomb), visible, various sizes
 *   BTS 0x04-0x07: Hidden shot blocks (look solid, any-weapon-breakable when revealed)
 *   BTS 0x08-0x09: Power bomb only (reform / permanent)
 *   BTS 0x0A-0x0B: Super missile only (reform / permanent)
 *   BTS 0x0C-0x0F: Map to PLM $B62F (no-op stub) — NOT functional in vanilla SM.
 *                   Some ROM hacks patch the table to add missile-only blocks here.
 *   BTS 0x40-0x4F: Door cap PLMs (managed by door PLMs, not user-editable)
 */
internal fun shotBlockCategory(bts: Int): ShotCategory = when (bts) {
    0x00, 0x01, 0x02, 0x03 -> ShotCategory.BEAM
    0x04, 0x05, 0x06, 0x07 -> ShotCategory.HIDDEN
    0x08, 0x09 -> ShotCategory.PB
    0x0A, 0x0B -> ShotCategory.SUPER
    in 0x40..0x4F -> ShotCategory.DOOR
    else -> ShotCategory.BEAM
}

internal enum class ShotCategory { BEAM, SUPER, PB, HIDDEN, DOOR }

/**
 * Named BTS options for block types that have well-known sub-types.
 * Values from SMILE RF documentation / Super Metroid Mod Manual.
 */
internal fun btsOptionsForBlockType(blockType: Int): List<Pair<Int, String>> = when (blockType) {
    0x1 -> listOf(
        // Square shapes (special collision handling, shapes 0–4) + common variants
        0x00 to "Half solid: bottom",
        0x01 to "Half solid: side",
        0x02 to "Three-quarter solid",
        0x03 to "Quarter solid",
        0x04 to "Fully solid",
        0x07 to "Flat half (alt)",
        0x13 to "Passthrough (air)",
        // 45° floor (2-tile standard pair)
        0x14 to "45° floor (tile 1/2)",
        0x15 to "45° floor (tile 2/2)",
        // 45° floor (2-tile smooth pair)
        0x16 to "45° floor smooth (tile 1/2)",
        0x17 to "45° floor smooth (tile 2/2)",
        // Gentle floor (3-tile)
        0x18 to "Gentle floor (tile 1/3)",
        0x19 to "Gentle floor (tile 2/3)",
        0x1A to "Gentle floor (tile 3/3)",
        // Steep floor
        0x12 to "Steep floor (1 tile)",
        0x1B to "Steep floor (tile 1/2)",
        0x1C to "Steep floor (tile 2/2)",
        0x1D to "Steep floor (tile 1/3)",
        0x1E to "Steep floor (tile 2/3)",
        0x1F to "Steep floor (tile 3/3)",
        // Square ceiling
        0x80 to "Half solid ceiling: top",
        0x82 to "Three-quarter ceiling",
        0x83 to "Quarter ceiling",
        0x87 to "Flat half ceiling (alt)",
        0x93 to "Passthrough ceiling (air)",
        // 45° ceiling (2-tile standard pair)
        0x94 to "45° ceiling (tile 1/2)",
        0x95 to "45° ceiling (tile 2/2)",
        // 45° ceiling (2-tile smooth pair)
        0x96 to "45° ceiling smooth (tile 1/2)",
        0x97 to "45° ceiling smooth (tile 2/2)",
        // Gentle ceiling (3-tile)
        0x98 to "Gentle ceiling (tile 1/3)",
        0x99 to "Gentle ceiling (tile 2/3)",
        0x9A to "Gentle ceiling (tile 3/3)",
        // Steep ceiling
        0x92 to "Steep ceiling (1 tile)",
        0x9B to "Steep ceiling (tile 1/2)",
        0x9C to "Steep ceiling (tile 2/2)",
        0x9D to "Steep ceiling (tile 1/3)",
        0x9E to "Steep ceiling (tile 2/3)",
        0x9F to "Steep ceiling (tile 3/3)",
        // Uncommon/special shapes
        0x05 to "Valley (shallow V-trough)",
        0x06 to "Valley (deep V-trough)",
        0x0E to "Staircase (4-step)",
        0x0F to "Smooth staircase (8-step)",
        0x10 to "Fully solid (table)",
        0x11 to "Plateau (overshoot)",
    )
    0x2 -> listOf(
        0x00 to "Air (X-Ray safe)",
        0x02 to "Spike (low damage, passthrough)",
        0x0C to "Morph Lock (custom patch)",
        0x0D to "Morph Unlock (custom patch)",
    )
    0x3 -> listOf(
        0x08 to "Conveyor Right",
        0x09 to "Conveyor Left",
        0x82 to "Quicksand (Maridia)",
        0x85 to "Sandfall (Maridia)",
    )
    0x4 -> listOf(
        0x00 to "Shootable Air (reform, 1x1)",
        0x01 to "Shootable Air (reform, 2x1)",
        0x02 to "Shootable Air (reform, 1x2)",
        0x03 to "Shootable Air (reform, 2x2)",
        0x04 to "Shootable Air (permanent, 1x1)",
        0x05 to "Shootable Air (permanent, 2x1)",
        0x06 to "Shootable Air (permanent, 1x2)",
        0x07 to "Shootable Air (permanent, 2x2)",
    )
    0x7 -> listOf(
        0x00 to "Bomb Air (reform, 1x1)",
        0x01 to "Bomb Air (reform, 2x1)",
        0x02 to "Bomb Air (reform, 1x2)",
        0x03 to "Bomb Air (reform, 2x2)",
        0x04 to "Bomb Air (permanent, 1x1)",
        0x05 to "Bomb Air (permanent, 2x1)",
        0x06 to "Bomb Air (permanent, 1x2)",
        0x07 to "Bomb Air (permanent, 2x2)",
    )
    0xA -> listOf(
        0x00 to "Spike (normal, \$003C dmg)",
        0x01 to "Spike (weak, \$0010 dmg)",
        0x03 to "Spike (weak variant, \$0010 dmg)",
        0x0E to "Invisible Bridge (solid, X-Ray reveals)",
        0x0F to "Enemy-break Block",
    )
    0xB -> listOf(
        0x00 to "Crumble (reform, 1x1)",
        0x01 to "Crumble (reform, 2x1)",
        0x02 to "Crumble (reform, 1x2)",
        0x03 to "Crumble (reform, 2x2)",
        0x04 to "Crumble (permanent, 1x1)",
        0x05 to "Crumble (permanent, 2x1)",
        0x06 to "Crumble (permanent, 1x2)",
        0x07 to "Crumble (permanent, 2x2)",
        0x0B to "Enemy-Solid (air for Samus)",
        0x0E to "Speed Booster (reform)",
        0x0F to "Speed Booster (permanent)",
        0x10 to "Enemy-Solid (no X-Ray)",
    )
    0xC -> listOf(
        0x00 to "Any Weapon (reform, 1x1)",
        0x01 to "Any Weapon (reform, 2x1)",
        0x02 to "Any Weapon (reform, 1x2)",
        0x03 to "Any Weapon (reform, 2x2)",
        0x04 to "Hidden (reform, 1x1)",
        0x05 to "Hidden (reform, 2x1)",
        0x06 to "Hidden (reform, 1x2)",
        0x07 to "Hidden (reform, 2x2)",
        0x08 to "Power Bomb (reform)",
        0x09 to "Power Bomb (permanent)",
        0x0A to "Super Missile (reform)",
        0x0B to "Super Missile (permanent)",
    )
    0xE -> listOf(
        0x00 to "Grapple",
        0x01 to "Crumble Grapple (reform)",
        0x02 to "Crumble Grapple (permanent)",
    )
    0xF -> listOf(
        0x00 to "Bomb Block (reform, 1x1)",
        0x01 to "Bomb Block (reform, 2x1)",
        0x02 to "Bomb Block (reform, 1x2)",
        0x03 to "Bomb Block (reform, 2x2)",
        0x04 to "Bomb Block (permanent, 1x1)",
        0x05 to "Bomb Block (permanent, 2x1)",
        0x06 to "Bomb Block (permanent, 1x2)",
        0x07 to "Bomb Block (permanent, 2x2)",
    )
    else -> emptyList()
}

internal val blockTypeNames = mapOf(
    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x6 to "Unused", 0x7 to "Air (Bomb)",
    0x8 to "Solid", 0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
)
internal fun blockTypeName(type: Int): String = blockTypeNames[type] ?: "0x${type.toString(16).uppercase()}"

// ─── Slope Grid Picker ──────────────────────────────────────────────────

private data class SlopeGroup(val label: String, val entries: List<Int>)

private val SLOPE_GRID_GROUPS = listOf(
    SlopeGroup("Square", listOf(0x00, 0x01, 0x02, 0x03, 0x04, -1, 0x07, 0x13)),
    SlopeGroup("45° Floor", listOf(0x14, 0x15, -1, 0x16, 0x17)),
    SlopeGroup("Gentle Floor", listOf(0x18, 0x19, 0x1A)),
    SlopeGroup("Steep Floor", listOf(0x12, -1, 0x1B, 0x1C, -1, 0x1D, 0x1E, 0x1F)),
    SlopeGroup("Square Ceiling", listOf(0x80, 0x82, 0x83, -1, 0x87, 0x93)),
    SlopeGroup("45° Ceiling", listOf(0x94, 0x95, -1, 0x96, 0x97)),
    SlopeGroup("Gentle Ceiling", listOf(0x98, 0x99, 0x9A)),
    SlopeGroup("Steep Ceiling", listOf(0x92, -1, 0x9B, 0x9C, -1, 0x9D, 0x9E, 0x9F)),
    SlopeGroup("Other", listOf(0x05, 0x06, -1, 0x0E, 0x0F, 0x10, 0x11)),
)

private val SLOPE_BTS_NAMES: Map<Int, String> by lazy {
    btsOptionsForBlockType(0x1).associate { it.first to it.second }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SlopeGridPicker(
    selectedBts: Int,
    onSelect: (Int) -> Unit,
    onHoverBts: (Int?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cellSize = 26.dp
    val slopeColor = Color(0xFFEE7700)
    val selectedBorder = Color(0xFF44AAFF)
    val separatorColor = MaterialTheme.colorScheme.outlineVariant
    var hoveredBts by remember { mutableStateOf(-1) }

    val xFlip = (selectedBts and 0x40) != 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (group in SLOPE_GRID_GROUPS) {
            Text(group.label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (bts in group.entries) {
                    if (bts == -1) {
                        Box(Modifier.width(4.dp).height(cellSize).background(separatorColor))
                        continue
                    }
                    val effectiveBts = if (xFlip) bts or 0x40 else bts
                    val isSelected = effectiveBts == selectedBts
                    val isHovered = bts == hoveredBts
                    Box(
                        modifier = Modifier
                            .size(cellSize)
                            .background(
                                when {
                                    isSelected -> selectedBorder.copy(alpha = 0.2f)
                                    isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                    else -> Color.Transparent
                                },
                                MaterialTheme.shapes.extraSmall
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) selectedBorder
                                        else if (isHovered) MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .clickable { onSelect(effectiveBts) }
                            .onPointerEvent(PointerEventType.Enter) {
                                hoveredBts = bts
                                onHoverBts(effectiveBts)
                            }
                            .onPointerEvent(PointerEventType.Exit) {
                                if (hoveredBts == bts) {
                                    hoveredBts = -1
                                    onHoverBts(null)
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                            drawSlopeCell(effectiveBts, slopeColor)
                        }
                        Text(
                            "0x${effectiveBts.toString(16).uppercase().padStart(2, '0')}",
                            fontSize = 7.sp,
                            color = Color.Black,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 1.dp)
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 2.dp, top = 4.dp)
        ) {
            Checkbox(
                checked = xFlip,
                onCheckedChange = { flip ->
                    val newBts = if (flip) selectedBts or 0x40 else selectedBts and 0x40.inv()
                    onSelect(newBts)
                },
                modifier = Modifier.size(18.dp)
            )
            Text("X-Flip", fontSize = 9.sp)
        }
    }
}

private fun DrawScope.drawSlopeCell(bts: Int, color: Color) {
    val s = size.width
    val shape = bts and 0x1F
    val isCeiling = (bts and 0x80) != 0
    val xFlip = (bts and 0x40) != 0

    if (shape >= SLOPE_HEIGHTS.size) return
    val heights = SLOPE_HEIGHTS[shape]
    if (heights.all { it == 0 }) {
        drawRect(color.copy(alpha = 0.5f))
        drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
        return
    }

    val path = androidx.compose.ui.graphics.Path()
    val scale = s / 16f

    if (!isCeiling) {
        path.moveTo(0f, s)
        for (screenX in 0 until 16) {
            val col = if (xFlip) screenX else (15 - screenX)
            val h = heights[col].coerceIn(0, 16)
            path.lineTo(screenX * scale, s - h * scale)
        }
        path.lineTo(s, s)
        path.close()
    } else {
        path.moveTo(0f, 0f)
        for (screenX in 0 until 16) {
            val col = if (xFlip) screenX else (15 - screenX)
            val h = heights[col].coerceIn(0, 16)
            path.lineTo(screenX * scale, h * scale)
        }
        path.lineTo(s, 0f)
        path.close()
    }

    drawPath(path, color.copy(alpha = 0.5f))
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
}

enum class TileOverlay(val label: String, val shortLabel: String, val color: Long) {
    // Block types (from level data bits 12-15)
    SOLID("Solid", "S", 0xCC4488FF),       // blue
    SLOPE("Slope", "/", 0xCCEE7700),       // orange
    DOOR("Door", "D", 0xCC6080B0),         // gray-blue (casing color varies by PLM in render)
    SPIKE("Spike", "!", 0xCCFF4444),       // red
    BOMB("Bomb", "B", 0xCCAA44DD),         // purple
    CRUMBLE("Crumble", "C", 0xCCBB5522),   // brown/rust
    GRAPPLE("Grapple", "G", 0xCC00AA88),   // teal
    SPEED("Speed Booster", "~", 0xCC66AAFF),   // light blue (type 0xB + BTS 0x0E/0x0F)
    TREADMILL("Treadmill", "T", 0xCC44CCCC),   // cyan (type 0x3)
    // Shot blocks by break method (block type 0xC + BTS)
    SHOT_BEAM("Shot (Beam)", "Xb", 0xCCFFDD00),    // yellow: beam/missile/bomb
    SHOT_SUPER("Shot (Super)", "Xs", 0xCC00CC44),   // green: super missile required
    SHOT_PB("Shot (PB)", "Xp", 0xCCCC44AA),         // magenta: power bomb
    @Deprecated("BTS 0x0C-0x0D are non-functional in vanilla SM", level = DeprecationLevel.HIDDEN)
    SHOT_MISSILE("Shot (Missile)", "Xm", 0xCCFF8844), // NOT functional in vanilla SM
    // Items/powerups (from PLM data; drawn when we have item positions)
    ITEMS("Items", "I", 0xCCFFCC00),       // gold/yellow
    // Enemies (from enemy population data in bank $A1)
    ENEMIES("Enemies", "E", 0xCCFF6644),   // orange-red
    // Scroll PLMs (B703, B63B, B647 — runtime scroll triggers)
    SCROLL_PLMS("Scroll Triggers", "St", 0xCCFF8040),  // orange
    // Per-screen scroll colors (Red/Blue/Green)
    SCROLLS("Scroll Colors", "Sc", 0x60FFFFFF),
    // Liquid level (water/lava/acid from FX data)
    LIQUID("Liquid Level", "~", 0x443388FF),
    // Layer 3 visual (fog, rain, spores, heat shimmer)
    LAYER3("Layer 3", "L3", 0x60AACCFF),
    // Lighten: brighten dark rooms (Fireflea, etc.)
    LIGHTEN("Lighten", "L", 0x00FFFFFF),
}

/** Shared tile-meta icon: black fill, colored 2px border, centered white letter (matches map). */
@Composable
private fun TileMetaIcon(
    overlay: TileOverlay,
    sizeDp: Dp,
    borderDp: Dp,
    fontSize: TextUnit
) {
    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(Color.Black, RectangleShape)
            .border(borderDp, Color(overlay.color.toInt()), RectangleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = overlay.shortLabel,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MapCanvas(
    room: RoomInfo?,
    romParser: RomParser?,
    editorState: EditorState? = null,
    rooms: List<RoomInfo> = emptyList(),
    samusPosition: Pair<Float, Float>? = null,
    onRoomSelected: ((RoomInfo) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val zoomState = remember { mutableStateOf(1f) }
    val zoomLevel = zoomState.value
    var showGrid by remember { mutableStateOf(true) }
    var showShortChargeRuler by remember { mutableStateOf(false) }
    var shortChargeStutters by remember { mutableStateOf(0) }
    var shortChargeTaps by remember { mutableStateOf(0) }
    var tileMetaExpanded by remember { mutableStateOf(false) }
    val overlayToggles = remember { mutableStateMapOf<TileOverlay, Boolean>(
        TileOverlay.ITEMS to true,
        TileOverlay.ENEMIES to true,
    ) }
    val overlayCount = overlayToggles.values.count { it }
    
    val mapFocusReq = remember { FocusRequester() }
    Card(
        modifier = modifier,
        shape = RectangleShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()
            .focusRequester(mapFocusReq)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && editorState != null) {
                    when (keyEvent.key) {
                        Key.H -> { editorState.flipOrCaptureH(); true }
                        Key.V -> { if (!keyEvent.isCtrlPressed && !keyEvent.isMetaPressed) { editorState.flipOrCaptureV(); true } else false }
                        Key.R -> { editorState.rotateOrCapture(); true }
                        Key.DirectionUp -> {
                            if (onRoomSelected != null && rooms.isNotEmpty()) {
                                val currentIdx = rooms.indexOfFirst { it.handle == room?.handle }
                                val newIdx = if (currentIdx > 0) currentIdx - 1 else rooms.lastIndex
                                if (newIdx in rooms.indices) onRoomSelected(rooms[newIdx])
                                true
                            } else false
                        }
                        Key.DirectionDown -> {
                            if (onRoomSelected != null && rooms.isNotEmpty()) {
                                val currentIdx = rooms.indexOfFirst { it.handle == room?.handle }
                                val newIdx = if (currentIdx < rooms.lastIndex) currentIdx + 1 else 0
                                if (newIdx in rooms.indices) onRoomSelected(rooms[newIdx])
                                true
                            } else false
                        }
                        Key.Z -> {
                            if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                if (keyEvent.isShiftPressed) editorState.redo() else editorState.undo()
                                true
                            } else false
                        }
                        Key.Y -> {
                            if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                editorState.redo(); true
                            } else false
                        }
                        Key.S -> {
                            if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                editorState.saveProject(romParser); true
                            } else { editorState.activeTool = EditorTool.SELECT; true }
                        }
                        Key.P -> {
                            if (editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                editorState.captureMapSelection()
                            } else {
                                editorState.activeTool = EditorTool.PAINT
                            }; true
                        }
                        Key.F -> { editorState.activeTool = EditorTool.FILL; true }
                        Key.E -> { editorState.activeTool = EditorTool.ERASE; true }
                        Key.I -> { editorState.activeTool = EditorTool.SAMPLE; true }
                        Key.Enter -> {
                            if (editorState.activeTool == EditorTool.SELECT && editorState.mapSelStart != null) {
                                editorState.captureMapSelection(); true
                            } else false
                        }
                        Key.Escape -> {
                            if (editorState.activeTool == EditorTool.SELECT && editorState.mapSelStart != null) {
                                editorState.mapSelStart = null; editorState.mapSelEnd = null; true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
        ) {
            if (room != null && romParser != null) {
                val rv = editorState?.romVersion ?: 0
                var isLoading by remember(room.id, romParser, rv) { mutableStateOf(true) }
                var errorMessage by remember(room.id, romParser, rv) { mutableStateOf<String?>(null) }
                var renderData by remember(room.id, romParser, rv) { mutableStateOf<RoomRenderData?>(null) }
                
                LaunchedEffect(room.id, romParser, rv) {
                    isLoading = true
                    errorMessage = null
                    renderData = null
                    try {
                        val roomId = room.getRoomIdAsInt()
                        val roomHeader = romParser.readRoomHeader(roomId)
                        if (roomHeader != null) {
                            // Load working level data for editing
                            editorState?.loadRoom(roomId, romParser, roomHeader)
                            renderData = MapRenderer(romParser).renderRoom(roomHeader)
                            if (renderData == null) errorMessage = "Failed to render"
                        } else {
                            errorMessage = "Room header not found"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Error: ${e.message}"
                        e.printStackTrace()
                    } finally {
                        isLoading = false
                    }
                }
                
                val editVersion = editorState?.editVersion ?: 0
                
                // ─── Compact toolbar ─────────────────────────────
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Zoom
                    Text("${(zoomLevel * 100).toInt()}%", fontSize = 10.sp,
                        modifier = Modifier.width(32.dp).alignByBaseline())
                    Slider(
                        value = zoomLevel,
                        onValueChange = { zoomState.value = it; mapFocusReq.requestFocus() },
                        valueRange = 0.25f..4f,
                        steps = 14,
                        modifier = Modifier.width(80.dp).height(28.dp)
                    )
                    
                    // Grid toggle
                    FilterChip(
                        selected = showGrid,
                        onClick = { showGrid = !showGrid; mapFocusReq.requestFocus() },
                        label = { Text("Grid", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    // Short Charge Ruler toggle
                    FilterChip(
                        selected = showShortChargeRuler,
                        onClick = { showShortChargeRuler = !showShortChargeRuler; mapFocusReq.requestFocus() },
                        label = { Text("Ruler", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    if (showShortChargeRuler) {
                        StutterSelector(
                            selectedStutters = shortChargeStutters,
                            onStuttersChanged = { shortChargeStutters = it; mapFocusReq.requestFocus() }
                        )
                        TapSelector(
                            selectedTaps = shortChargeTaps,
                            onTapsChanged = { shortChargeTaps = it; mapFocusReq.requestFocus() }
                        )
                    }

                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                    
                    // Tile Meta multi-select dropdown (trigger: icon like map + label, same color as map square)
                    val firstOverlay = overlayToggles.entries.firstOrNull { it.value }?.key
                    val triggerBg = firstOverlay?.let { Color(it.color.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant
                    val triggerFg = if (overlayCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    Box {
                        Surface(
                            modifier = Modifier
                                .height(28.dp)
                                .clickable { tileMetaExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = triggerBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (firstOverlay != null) {
                                    TileMetaIcon(overlay = firstOverlay, sizeDp = 16.dp, borderDp = 2.dp, fontSize = 9.sp)
                                }
                                Text(
                                    text = if (overlayCount > 0) "Tile Meta ($overlayCount)" else "Tile Meta",
                                    fontSize = 12.sp,
                                    color = triggerFg
                                )
                                Text(
                                    text = "▼",
                                    fontSize = 8.sp,
                                    color = triggerFg.copy(alpha = 0.8f)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = tileMetaExpanded,
                            onDismissRequest = { tileMetaExpanded = false; mapFocusReq.requestFocus() }
                        ) {
                            TileOverlay.values().forEach { overlay ->
                                val isOn = overlayToggles[overlay] ?: false
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Checkbox(
                                                checked = isOn,
                                                onCheckedChange = null
                                            )
                                            TileMetaIcon(overlay = overlay, sizeDp = 14.dp, borderDp = 2.dp, fontSize = 7.sp)
                                            Text(overlay.label, fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        overlayToggles[overlay] = !isOn
                                    }
                                )
                            }
                        }
                    }
                    
                    // ─── Editor tools ─────────────────────────────
                    if (editorState != null) {
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        // Tool selection with icons
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SELECT,
                            onClick = { editorState.activeTool = EditorTool.SELECT; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Default.SelectAll, contentDescription = "Select (Q)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.PAINT,
                            onClick = {
                                if (editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                    editorState.captureMapSelection()
                                } else {
                                    editorState.activeTool = EditorTool.PAINT
                                }
                                mapFocusReq.requestFocus()
                            },
                            label = { Icon(Icons.Default.Brush, contentDescription = "Paint (D)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.FILL,
                            onClick = { editorState.activeTool = EditorTool.FILL; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Default.FormatColorFill, contentDescription = "Fill (G)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.ERASE,
                            onClick = { editorState.activeTool = EditorTool.ERASE; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Outlined.Delete, contentDescription = "Erase (E)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SAMPLE,
                            onClick = { editorState.activeTool = EditorTool.SAMPLE; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Default.Colorize, contentDescription = "Sample (I)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        // Undo / Redo with icons
                        @Suppress("UNUSED_VARIABLE") val uv = editorState.undoVersion
                        IconButton(
                            onClick = { editorState.undo(); mapFocusReq.requestFocus() },
                            enabled = editorState.undoStack.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.redo(); mapFocusReq.requestFocus() },
                            enabled = editorState.redoStack.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }

                        
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Flip / Rotate buttons
                        IconButton(
                            onClick = { editorState.flipOrCaptureH(); mapFocusReq.requestFocus() },
                            enabled = editorState.brush != null,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "H-Flip (H)",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.brush?.hFlip == true) MaterialTheme.colorScheme.primary
                                       else if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.flipOrCaptureV(); mapFocusReq.requestFocus() },
                            enabled = editorState.brush != null,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "V-Flip (V)",
                                modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 90f),
                                tint = if (editorState.brush?.vFlip == true) MaterialTheme.colorScheme.primary
                                       else if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.rotateOrCapture(); mapFocusReq.requestFocus() },
                            enabled = editorState.brush != null,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate (R)",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }

                        // ─── Save Picture ────────────────────────────────
                        if (renderData != null) {
                            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                            val coroutineScopeForSave = rememberCoroutineScope()
                            IconButton(
                                onClick = {
                                    coroutineScopeForSave.launch {
                                        val roomName = room?.name?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "room"
                                        val chooser = javax.swing.JFileChooser().apply {
                                            dialogTitle = "Save Map as PNG"
                                            selectedFile = java.io.File("$roomName.png")
                                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PNG Image", "png")
                                        }
                                        if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                            var file = chooser.selectedFile
                                            if (!file.name.endsWith(".png", ignoreCase = true)) file = java.io.File(file.path + ".png")
                                            val rd = renderData!!
                                            val es = editorState
                                            val rh = room?.let { romParser.readRoomHeader(it.getRoomIdAsInt()) }
                                            val rWidthScreens = rh?.width ?: 0
                                            val rHeightScreens = rh?.height ?: 0
                                            val scrollDataForSave = rh?.let { romParser.parseScrollData(it.roomScrollsPtr, it.width, it.height) }
                                            val activeOvs = overlayToggles.filter { it.value }.keys
                                            val img = if (es != null && es.workingLevelData != null && rh != null) {
                                                val edited = MapRenderer(romParser, es.tileGraphics).renderRoomFromLevelData(rh, es.workingLevelData!!, es.workingPlms, es.workingEnemies)
                                                if (edited != null) buildCompositeImage(edited, activeOvs, showGrid, scrollDataForSave, rWidthScreens, rHeightScreens)
                                                else buildCompositeImage(rd, activeOvs, showGrid, scrollDataForSave, rWidthScreens, rHeightScreens)
                                            } else {
                                                buildCompositeImage(rd, activeOvs, showGrid, scrollDataForSave, rWidthScreens, rHeightScreens)
                                            }
                                            ImageIO.write(img, "PNG", file)
                                        }
                                    }
                                    mapFocusReq.requestFocus()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.SaveAlt,
                                    contentDescription = "Save map as PNG",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                }
                
                // ─── Map Display ─────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EditorColors.romBackground),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Loading...", color = Color.White)
                            }
                        }
                        errorMessage != null -> {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                        renderData != null -> {
                            val data = renderData!!
                            val activeOverlays = overlayToggles.filter { it.value }.keys

                            val roomHeader = remember(room) { room?.let { romParser.readRoomHeader(it.getRoomIdAsInt()) } }
                            val scrollVer = editorState?.scrollVersion ?: 0
                            val scrollDataForOverlay = remember(scrollVer, roomHeader) {
                                val ws = editorState?.workingScrolls
                                if (ws != null && ws.isNotEmpty()) ws.copyOf()
                                else roomHeader?.let { rh -> romParser.parseScrollData(rh.roomScrollsPtr, rh.width, rh.height) }
                            }
                            val rWidthScreens = roomHeader?.width ?: 0
                            val rHeightScreens = roomHeader?.height ?: 0

                            // Layer 3 FX overlay data
                            val layer3Data = remember(roomHeader, activeOverlays.contains(TileOverlay.LAYER3)) {
                                if (!activeOverlays.contains(TileOverlay.LAYER3) || roomHeader == null) null
                                else {
                                    val fxEntries = romParser.parseFxEntries(roomHeader.fxPtr)
                                    val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
                                    val fxType = defaultFx?.fxType ?: 0
                                    if (fxType == 0) null
                                    else romParser.renderLayer3Image(fxType, layer3Palette(fxType))
                                }
                            }

                            val compositeImage = remember(data, activeOverlays.toSet(), showGrid, scrollDataForOverlay?.contentHashCode(), layer3Data?.contentHashCode()) {
                                buildCompositeImage(data, activeOverlays, showGrid, scrollDataForOverlay, rWidthScreens, rHeightScreens,
                                    layer3Pixels = layer3Data)
                            }
                            
                            val hScrollState = rememberScrollState()
                            val vScrollState = rememberScrollState()
                            val coroutineScope = rememberCoroutineScope()
                            var isDragging by remember { mutableStateOf(false) }
                            var lastDragX by remember { mutableStateOf(0f) }
                            var lastDragY by remember { mutableStateOf(0f) }
                            
                            var isPainting by remember { mutableStateOf(false) }
                            
                            // Right-click properties popup state
                            var propsBlockX by remember { mutableStateOf(-1) }
                            var propsBlockY by remember { mutableStateOf(-1) }
                            var propsExpanded by remember { mutableStateOf(false) }
                            var propsBlockType by remember { mutableStateOf(0) }
                            var propsBts by remember { mutableStateOf(0) }
                            var propsMetatile by remember { mutableStateOf(0) }

                            // Right-click context menu state
                            var contextMenuExpanded by remember { mutableStateOf(false) }
                            var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
                            var showSavePatternDialog by remember { mutableStateOf(false) }
                            val density = LocalDensity.current.density
                            
                            fun pointerToBlock(posX: Float, posY: Float): Pair<Int, Int> {
                                // Pointer & scroll are in physical pixels; layout uses dp.
                                // Tile display size in pointer units = 16 * zoom * density.
                                val tilePx = 16f * zoomLevel * density
                                return Pair(
                                    ((posX + hScrollState.value) / tilePx).toInt(),
                                    ((posY + vScrollState.value) / tilePx).toInt()
                                )
                            }
                            
                            // Re-render from working data (reacts to editVersion from EditorState)
                            val compositeForEdit = remember(data, editVersion, activeOverlays.toSet(), showGrid, scrollDataForOverlay?.contentHashCode(), scrollVer, layer3Data?.contentHashCode()) {
                                val es = editorState
                                if (es != null && es.workingLevelData != null) {
                                    val rh = roomHeader
                                    if (rh != null) {
                                        val r = MapRenderer(romParser, es.tileGraphics).renderRoomFromLevelData(rh, es.workingLevelData!!, es.workingPlms, es.workingEnemies)
                                        if (r != null) return@remember buildCompositeImage(r, activeOverlays, showGrid, scrollDataForOverlay, rWidthScreens, rHeightScreens, layer3Pixels = layer3Data)
                                    }
                                }
                                compositeImage
                            }
                            val editBitmap = remember(compositeForEdit) { compositeForEdit.toComposeImageBitmap() }
                            
                            LaunchedEffect(Unit) { mapFocusReq.requestFocus() }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (editorState != null)
                                            Modifier.pointerHoverIcon(PixelEditorCursors.forEditorTool(editorState.activeTool))
                                        else Modifier
                                    )
                                    .onPointerEvent(PointerEventType.Scroll) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        val isZoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                                        val sd = event.changes.first().scrollDelta
                                        if (isZoom) {
                                            val mousePos = event.changes.first().position
                                            val contentXBefore = (hScrollState.value + mousePos.x) / zoomLevel
                                            val contentYBefore = (vScrollState.value + mousePos.y) / zoomLevel
                                            val newZoom = (zoomLevel * if (sd.y < 0) EditorColors.ZOOM_FACTOR else 1f / EditorColors.ZOOM_FACTOR).coerceIn(0.25f, 4f)
                                            zoomState.value = newZoom
                                            coroutineScope.launch {
                                                val newScrollX = (contentXBefore * newZoom - mousePos.x).toInt().coerceAtLeast(0)
                                                val newScrollY = (contentYBefore * newZoom - mousePos.y).toInt().coerceAtLeast(0)
                                                hScrollState.scrollTo(newScrollX)
                                                vScrollState.scrollTo(newScrollY)
                                            }
                                        } else coroutineScope.launch {
                                            val pan = resolvePanScrollDelta(
                                                rawX = sd.x,
                                                rawY = sd.y,
                                                shiftPressed = ne?.isShiftDown == true
                                            )
                                            hScrollState.scrollTo((hScrollState.value + pan.x).toInt().coerceIn(0, hScrollState.maxValue))
                                            vScrollState.scrollTo((vScrollState.value + pan.y).toInt().coerceIn(0, vScrollState.maxValue))
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        mapFocusReq.requestFocus()
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne != null && ne.button == MouseEvent.BUTTON2) {
                                            isDragging = true; val p = event.changes.first().position; lastDragX = p.x; lastDragY = p.y
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON3 && editorState != null) {
                                            val pos = event.changes.first().position
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            val ss = editorState.mapSelStart
                                            val se = editorState.mapSelEnd
                                            val hasMultiSel = ss != null && se != null &&
                                                (kotlin.math.abs(ss.first - se.first) > 0 || kotlin.math.abs(ss.second - se.second) > 0)

                                            if (hasMultiSel) {
                                                contextMenuOffset = DpOffset((pos.x / density).dp, (pos.y / density).dp)
                                                contextMenuExpanded = true
                                            } else {
                                                if (bx in 0 until data.blocksWide && by in 0 until data.blocksTall) {
                                                    val word = editorState.readBlockWord(bx, by)
                                                    propsBlockX = bx; propsBlockY = by
                                                    propsMetatile = word and 0x3FF
                                                    propsBlockType = (word shr 12) and 0xF
                                                    propsBts = editorState.readBts(bx, by)
                                                    propsExpanded = true
                                                }
                                            }
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON1 && editorState != null) {
                                            val (bx, by) = pointerToBlock(event.changes.first().position.x, event.changes.first().position.y)
                                            when (editorState.activeTool) {
                                                EditorTool.SELECT -> {
                                                    editorState.mapSelStart = Pair(bx, by)
                                                    editorState.mapSelEnd = Pair(bx, by)
                                                    isPainting = true
                                                }
                                                EditorTool.PAINT -> if (editorState.brush != null) {
                                                    isPainting = true; editorState.beginStroke(); editorState.paintAt(bx, by)
                                                }
                                                EditorTool.FILL -> if (editorState.brush != null) {
                                                    editorState.beginStroke(); editorState.floodFill(bx, by); editorState.endStroke()
                                                }
                                                EditorTool.ERASE -> {
                                                    isPainting = true; editorState.beginStroke(); editorState.eraseAt(bx, by)
                                                }
                                                EditorTool.SAMPLE -> {
                                                    editorState.sampleTile(bx, by)
                                                }
                                            }
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Release) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne == null || ne.button == MouseEvent.BUTTON2) isDragging = false
                                        if (isPainting && editorState?.activeTool == EditorTool.SELECT) {
                                            isPainting = false
                                            val ss = editorState?.mapSelStart
                                            val se = editorState?.mapSelEnd
                                            if (ss != null && se != null && ss == se && editorState != null) {
                                                val bx = ss.first; val by = ss.second
                                                if (bx in 0 until data.blocksWide && by in 0 until data.blocksTall) {
                                                    val word = editorState.readBlockWord(bx, by)
                                                    propsBlockX = bx; propsBlockY = by
                                                    propsMetatile = word and 0x3FF
                                                    propsBlockType = (word shr 12) and 0xF
                                                    propsBts = editorState.readBts(bx, by)
                                                    propsExpanded = true
                                                }
                                            }
                                        } else if (isPainting) {
                                            isPainting = false; editorState?.endStroke()
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Move) { event ->
                                        val pos = event.changes.first().position
                                        if (editorState != null) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            editorState.updateHover(bx, by)
                                        }
                                        if (isDragging) {
                                            val dx = lastDragX - pos.x; val dy = lastDragY - pos.y; lastDragX = pos.x; lastDragY = pos.y
                                            coroutineScope.launch {
                                                hScrollState.scrollTo((hScrollState.value + dx.toInt()).coerceIn(0, hScrollState.maxValue))
                                                vScrollState.scrollTo((vScrollState.value + dy.toInt()).coerceIn(0, vScrollState.maxValue))
                                            }
                                        }
                                        if (isPainting && editorState != null) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            when (editorState.activeTool) {
                                                EditorTool.SELECT -> editorState.mapSelEnd = Pair(bx, by)
                                                EditorTool.PAINT -> editorState.paintAt(bx, by)
                                                EditorTool.ERASE -> editorState.eraseAt(bx, by)
                                                else -> {}
                                            }
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Enter) {
                                        mapFocusReq.requestFocus()
                                    }
                                    .onPointerEvent(PointerEventType.Exit) {
                                        if (editorState != null) { editorState.hoverBlockX = -1; editorState.hoverBlockY = -1 }
                                    }
                                    .horizontalScroll(hScrollState)
                                    .verticalScroll(vScrollState)
                            ) {
                                // Map image + cursor preview overlay
                                Box {
                                    Image(
                                        bitmap = editBitmap,
                                        contentDescription = room.name,
                                        modifier = Modifier
                                            .requiredWidth((data.width * zoomLevel).dp)
                                            .requiredHeight((data.height * zoomLevel).dp),
                                        contentScale = ContentScale.FillBounds
                                    )
                                    // Liquid level overlay (water/lava/acid)
                                    if (roomHeader != null && overlayToggles[TileOverlay.LIQUID] == true) {
                                        val fxEntries = remember(roomHeader.fxPtr) { romParser.parseFxEntries(roomHeader.fxPtr) }
                                        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
                                        // Apply editor FX override if present
                                        val editorFxChange = editorState?.project?.rooms?.get(
                                            editorState.project.roomKey(roomHeader.roomId)
                                        )?.fxChange
                                        val effectiveFxType = editorFxChange?.fxType ?: defaultFx?.fxType ?: 0
                                        val effectiveLiquidStart = editorFxChange?.liquidSurfaceStart ?: defaultFx?.liquidSurfaceStart ?: 0xFFFF
                                        if (effectiveLiquidStart != 0xFFFF) {
                                            val liquidY = effectiveLiquidStart
                                            val liquidColor = when (liquidPhysicsIndex(effectiveFxType)) {
                                                1 -> Color(0x44FF4400)     // lava (orange-red) — Varia protects
                                                2 -> Color(0x44CCCC00)    // acid (yellow) — ignores suits
                                                3 -> Color(0x443388FF)     // water (blue) — no damage
                                                else -> Color(0x443388FF)  // unknown — default blue
                                            }
                                            Canvas(
                                                modifier = Modifier
                                                    .requiredWidth((data.width * zoomLevel).dp)
                                                    .requiredHeight((data.height * zoomLevel).dp)
                                            ) {
                                                val scaleY = size.height / data.height
                                                val surfacePixelY = liquidY.toFloat() * scaleY
                                                if (surfacePixelY < size.height) {
                                                    drawRect(
                                                        color = liquidColor,
                                                        topLeft = androidx.compose.ui.geometry.Offset(0f, surfacePixelY),
                                                        size = androidx.compose.ui.geometry.Size(size.width, size.height - surfacePixelY)
                                                    )
                                                    drawLine(
                                                        color = liquidColor.copy(alpha = 0.8f),
                                                        start = androidx.compose.ui.geometry.Offset(0f, surfacePixelY),
                                                        end = androidx.compose.ui.geometry.Offset(size.width, surfacePixelY),
                                                        strokeWidth = 2f
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // Ghost cursor preview: render actual tile graphics
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.brush != null &&
                                        editorState.activeTool == EditorTool.PAINT) {
                                        val hx = editorState.hoverBlockX
                                        val hy = editorState.hoverBlockY
                                        val b = editorState.brush!!
                                        val tg = editorState.tileGraphics
                                        // Build a preview image of the brush at the hover position
                                        val previewBitmap = remember(b, hx, hy, tg) {
                                            if (tg == null) null
                                            else {
                                                val pw = b.cols * 16; val ph = b.rows * 16
                                                val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
                                                val bgFill = 0xFF000000.toInt()
                                                for (r in 0 until b.rows) {
                                                    for (c in 0 until b.cols) {
                                                        val ck = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                                                        if (ck in b.skipCells) continue
                                                        val idx = b.tiles.getOrNull(r)?.getOrNull(c) ?: continue
                                                        val pixels = tg.renderMetatile(idx) ?: continue
                                                        val dc = if (b.hFlip) (b.cols - 1 - c) else c
                                                        val dr = if (b.vFlip) (b.rows - 1 - r) else r
                                                        val effH = b.tileHFlip(r, c)
                                                        val effV = b.tileVFlip(r, c)
                                                        for (ty in 0 until 16) for (tx in 0 until 16) {
                                                            val sx = if (effH) 15 - tx else tx
                                                            val sy = if (effV) 15 - ty else ty
                                                            val argb = pixels[sy * 16 + sx]
                                                            img.setRGB(dc * 16 + tx, dr * 16 + ty, if (argb != 0) argb else bgFill)
                                                        }
                                                    }
                                                }
                                                for (y in 0 until ph) for (x in 0 until pw) {
                                                    val p = img.getRGB(x, y)
                                                    if (p != 0) img.setRGB(x, y, (p and 0x00FFFFFF) or 0x99000000.toInt())
                                                }
                                                img.toComposeImageBitmap()
                                            }
                                        }
                                        if (previewBitmap != null) {
                                            val tileSize = 16f * zoomLevel
                                            val offX = hx * tileSize
                                            val offY = hy * tileSize
                                            Image(
                                                bitmap = previewBitmap,
                                                contentDescription = "Brush preview",
                                                modifier = Modifier
                                                    .offset(x = (offX).dp, y = (offY).dp)
                                                    .requiredWidth((b.cols * 16 * zoomLevel).dp)
                                                    .requiredHeight((b.rows * 16 * zoomLevel).dp),
                                                contentScale = ContentScale.FillBounds
                                            )
                                        }
                                    }
                                    // Select mode cursor: crosshair outline
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.SELECT
                                        && editorState.mapSelStart == null) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.5f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileW, editorState.hoverBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 1.5f,
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                                                )
                                            )
                                        }
                                    }
                                    // Erase cursor: red X
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.ERASE) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            val x0 = editorState.hoverBlockX * tileW
                                            val y0 = editorState.hoverBlockY * tileH
                                            drawRect(
                                                color = Color.Red.copy(alpha = 0.15f),
                                                topLeft = androidx.compose.ui.geometry.Offset(x0, y0),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                            )
                                            drawRect(
                                                color = Color.Red.copy(alpha = 0.6f),
                                                topLeft = androidx.compose.ui.geometry.Offset(x0, y0),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                                            )
                                            val pad = tileW * 0.25f
                                            drawLine(Color.Red.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x0 + pad, y0 + pad), androidx.compose.ui.geometry.Offset(x0 + tileW - pad, y0 + tileH - pad), strokeWidth = 1.5f)
                                            drawLine(Color.Red.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x0 + tileW - pad, y0 + pad), androidx.compose.ui.geometry.Offset(x0 + pad, y0 + tileH - pad), strokeWidth = 1.5f)
                                        }
                                    }
                                    // Sample cursor: outline
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.SAMPLE) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.Cyan.copy(alpha = 0.4f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileW, editorState.hoverBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                    // Right-click selection border
                                    if (propsExpanded && propsBlockX >= 0) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.Yellow.copy(alpha = 0.7f),
                                                topLeft = androidx.compose.ui.geometry.Offset(propsBlockX * tileW, propsBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                    // Map selection rectangle (dotted-style)
                                    if (editorState != null && editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                        val sel0 = editorState.mapSelStart!!
                                        val sel1 = editorState.mapSelEnd!!
                                        val minBx = minOf(sel0.first, sel1.first)
                                        val minBy = minOf(sel0.second, sel1.second)
                                        val maxBx = maxOf(sel0.first, sel1.first)
                                        val maxBy = maxOf(sel0.second, sel1.second)
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            val rx = minBx * tileW
                                            val ry = minBy * tileH
                                            val rw = (maxBx - minBx + 1) * tileW
                                            val rh = (maxBy - minBy + 1) * tileH
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.15f),
                                                topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                size = androidx.compose.ui.geometry.Size(rw, rh)
                                            )
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.9f),
                                                topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                size = androidx.compose.ui.geometry.Size(rw, rh),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 2f,
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                                )
                                            )
                                        }
                                    }
                                    // Samus position marker (emulator overlay)
                                    if (samusPosition != null) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val scaleX = size.width / data.width
                                            val scaleY = size.height / data.height
                                            val cx = samusPosition.first * scaleX
                                            val cy = samusPosition.second * scaleY
                                            val markerSize = 12f
                                            // Filled rectangle
                                            drawRect(
                                                color = Color.Green.copy(alpha = 0.35f),
                                                topLeft = androidx.compose.ui.geometry.Offset(cx - markerSize, cy - markerSize),
                                                size = androidx.compose.ui.geometry.Size(markerSize * 2, markerSize * 2),
                                            )
                                            // Border
                                            drawRect(
                                                color = Color.Green.copy(alpha = 0.9f),
                                                topLeft = androidx.compose.ui.geometry.Offset(cx - markerSize, cy - markerSize),
                                                size = androidx.compose.ui.geometry.Size(markerSize * 2, markerSize * 2),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                                            )
                                            // Crosshair lines
                                            drawLine(Color.Green.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(cx - markerSize * 1.5f, cy), androidx.compose.ui.geometry.Offset(cx + markerSize * 1.5f, cy), strokeWidth = 1.5f)
                                            drawLine(Color.Green.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(cx, cy - markerSize * 1.5f), androidx.compose.ui.geometry.Offset(cx, cy + markerSize * 1.5f), strokeWidth = 1.5f)
                                        }
                                    }
                                }
                            }

                            // ─── Right-click context menu (multi-tile selection only) ──────
                            DropdownMenu(
                                expanded = contextMenuExpanded,
                                onDismissRequest = { contextMenuExpanded = false },
                                offset = contextMenuOffset
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Paint", fontSize = 11.sp) },
                                    onClick = {
                                        contextMenuExpanded = false
                                        editorState?.captureMapSelection()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save Selection as Pattern", fontSize = 11.sp) },
                                    onClick = {
                                        contextMenuExpanded = false
                                        showSavePatternDialog = true
                                    }
                                )
                            }

                            // Save-as-pattern dialog
                            if (showSavePatternDialog && editorState != null) {
                                var patName by remember { mutableStateOf("") }
                                AlertDialog(
                                    onDismissRequest = { showSavePatternDialog = false },
                                    title = { Text("Save Selection as Pattern", fontSize = 14.sp) },
                                    text = {
                                        AppOutlinedTextField(
                                            value = patName,
                                            onValueChange = { patName = it },
                                            label = "Pattern name",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            val n = patName.ifBlank { "Selection" }
                                            val pat = editorState.saveSelectionAsPattern(n)
                                            if (pat != null) editorState.loadPatternForEdit(pat.id)
                                            showSavePatternDialog = false
                                        }) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSavePatternDialog = false }) { Text("Cancel") }
                                    }
                                )
                            }

                            // ─── Right-click tile properties panel (floating, non-modal) ──────
                            if (propsExpanded && editorState != null) {
                                val editableBlockTypes = listOf(
                                    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
                                    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x6 to "Unused",
                                    0x7 to "Air (Bomb)", 0x8 to "Solid", 0x9 to "Door", 0xA to "Spike",
                                    0xB to "Crumble", 0xC to "Shot Block", 0xD to "V-Extend",
                                    0xE to "Grapple", 0xF to "Bomb Block"
                                )
                                val propsTypeName = blockTypeName(propsBlockType)
                                val btsOptions = btsOptionsForBlockType(propsBlockType)

                                Card(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .width(260.dp)
                                        .heightIn(max = 600.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        // Header
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "($propsBlockX, $propsBlockY) #$propsMetatile 0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
                                                fontSize = 11.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                            Text(
                                                "✕",
                                                modifier = Modifier
                                                    .clickable { propsExpanded = false; mapFocusReq.requestFocus() }
                                                    .padding(4.dp),
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // ── Block Type selector ──
                                        Text("Block Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        var btExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp)
                                                    .clickable { btExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        "0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
                                                        fontSize = 11.sp,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text("▾", fontSize = 10.sp)
                                                }
                                            }
                                            DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                                                for ((typeVal, typeName) in editableBlockTypes) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                RadioButton(selected = propsBlockType == typeVal, onClick = null, modifier = Modifier.size(16.dp))
                                                                Text("0x${typeVal.toString(16).uppercase()} $typeName", fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            btExpanded = false
                                                            if (typeVal != propsBlockType) {
                                                                propsBlockType = typeVal
                                                                propsBts = 0
                                                                editorState.setTileProperties(propsBlockX, propsBlockY, typeVal, 0)
                                                            }
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        var hoveredSlopeBts by remember { mutableStateOf<Int?>(null) }
                                        if (propsBlockType == 0x1) {
                                            val displayBts = hoveredSlopeBts ?: propsBts
                                            val displayName = SLOPE_BTS_NAMES[displayBts and 0x40.inv()]
                                                ?: SLOPE_BTS_NAMES[displayBts]
                                            if (displayName != null) {
                                                val flipLabel = if (displayBts and 0x40 != 0) " [X-Flipped]" else ""
                                                Text(
                                                    "0x${displayBts.toString(16).uppercase().padStart(2, '0')} $displayName$flipLabel",
                                                    fontSize = 9.sp,
                                                    color = if (hoveredSlopeBts != null) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(start = 2.dp)
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }
                                        }

                                        // ── Sub Type (BTS) ──
                                        val btsLabel = when (propsBlockType) {
                                            0x9 -> "Door Connection Index"
                                            0x1 -> "Slope Shape"
                                            else -> "Sub Type (BTS)"
                                        }
                                        Text(btsLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(2.dp))

                                        if (propsBlockType == 0x1) {
                                            SlopeGridPicker(
                                                selectedBts = propsBts,
                                                onSelect = { btsVal ->
                                                    if (btsVal != propsBts) {
                                                        propsBts = btsVal
                                                        editorState.setTileProperties(propsBlockX, propsBlockY, propsBlockType, btsVal)
                                                    }
                                                },
                                                onHoverBts = { hoveredSlopeBts = it }
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                        } else if (btsOptions.isNotEmpty()) {
                                            var btsDropExpanded by remember { mutableStateOf(false) }
                                            val btsName = btsOptions.firstOrNull { it.first == propsBts }?.second
                                                ?: "Custom (0x${propsBts.toString(16).uppercase().padStart(2, '0')})"
                                            Box {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(32.dp)
                                                        .clickable { btsDropExpanded = true },
                                                    shape = MaterialTheme.shapes.small,
                                                    color = MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(btsName, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                                        Text("▾", fontSize = 10.sp)
                                                    }
                                                }
                                                DropdownMenu(expanded = btsDropExpanded, onDismissRequest = { btsDropExpanded = false }) {
                                                    for ((btsVal, btsOptName) in btsOptions) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                    RadioButton(selected = propsBts == btsVal, onClick = null, modifier = Modifier.size(16.dp))
                                                                    Text("0x${btsVal.toString(16).uppercase().padStart(2, '0')} $btsOptName", fontSize = 11.sp)
                                                                }
                                                            },
                                                            onClick = {
                                                                btsDropExpanded = false
                                                                if (btsVal != propsBts) {
                                                                    propsBts = btsVal
                                                                    editorState.setTileProperties(propsBlockX, propsBlockY, propsBlockType, btsVal)
                                                                }
                                                            },
                                                            modifier = Modifier.height(28.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }

                                        // Raw BTS hex input (BasicTextField so typed text is visible, same fix as room search)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(if (btsOptions.isNotEmpty()) "Raw:" else "BTS:", fontSize = 10.sp)
                                            var rawText by remember(propsBlockX, propsBlockY, propsBts) {
                                                mutableStateOf(propsBts.toString(16).uppercase().padStart(2, '0'))
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .width(80.dp)
                                                    .height(32.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            ) {
                                                BasicTextField(
                                                    value = rawText,
                                                    onValueChange = { s ->
                                                        val filtered = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(2)
                                                        rawText = filtered
                                                        val v = filtered.toIntOrNull(16)
                                                        if (v != null && v in 0..255 && v != propsBts) {
                                                            editorState.setTileProperties(propsBlockX, propsBlockY, propsBlockType, v)
                                                        }
                                                    },
                                                    singleLine = true,
                                                    textStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }

                                        // ── Door Connection Info (when block type = Door) ──
                                        if (propsBlockType == 0x9) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Divider()
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Door Connection", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.height(2.dp))

                                            val allDoors = editorState.doorEntries
                                            val roomIdToName = remember(rooms) {
                                                rooms.associate {
                                                    it.getRoomIdAsInt() to it.name
                                                }
                                            }
                                            val currentDoor = allDoors.getOrNull(propsBts)

                                            if (allDoors.isEmpty()) {
                                                Text("No door entries found", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            } else if (currentDoor == null) {
                                                Text("Door index #$propsBts not found (${allDoors.size} doors available)",
                                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                                            } else {
                                                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

                                                // Helper: 0x00–0xFF dropdown
                                                @Composable
                                                fun ByteDropdown(label: String, value: Int, onValueChange: (Int) -> Unit) {
                                                    var expanded by remember { mutableStateOf(false) }
                                                    val hexStr = "0x${value.toString(16).uppercase().padStart(2, '0')} ($value)"
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                        Text(label, fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                        Box(modifier = Modifier.weight(1f)) {
                                                            Surface(
                                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                                    .clickable { expanded = true },
                                                                shape = MaterialTheme.shapes.small,
                                                                color = MaterialTheme.colorScheme.surfaceVariant
                                                            ) {
                                                                Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                                                    verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(hexStr, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                                                    Text("▾", fontSize = 9.sp)
                                                                }
                                                            }
                                                            DropdownMenu(
                                                                expanded = expanded,
                                                                onDismissRequest = { expanded = false },
                                                                modifier = Modifier.requiredSizeIn(maxHeight = 300.dp)
                                                            ) {
                                                                for (v in 0..0xFF) {
                                                                    DropdownMenuItem(
                                                                        text = {
                                                                            Text(
                                                                                "0x${v.toString(16).uppercase().padStart(2, '0')} ($v)",
                                                                                fontSize = 10.sp,
                                                                                fontWeight = if (v == value) FontWeight.Bold else FontWeight.Normal
                                                                            )
                                                                        },
                                                                        onClick = {
                                                                            expanded = false
                                                                            if (v != value) onValueChange(v)
                                                                        },
                                                                        modifier = Modifier.height(24.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                // Destination room dropdown
                                                var destDropExpanded by remember { mutableStateOf(false) }
                                                var destRoomSearch by remember { mutableStateOf("") }
                                                val destName = roomIdToName[currentDoor.destRoomPtr]
                                                    ?: "0x${currentDoor.destRoomPtr.toString(16).uppercase()}"
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Destination:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        Surface(
                                                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                                                .clickable { destDropExpanded = true },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(destName, fontSize = 9.sp, modifier = Modifier.weight(1f))
                                                                Text("▾", fontSize = 9.sp)
                                                            }
                                                        }
                                                        DropdownMenu(
                                                            expanded = destDropExpanded,
                                                            onDismissRequest = { destDropExpanded = false; destRoomSearch = "" },
                                                            modifier = Modifier.width(220.dp).requiredSizeIn(maxHeight = 400.dp)
                                                        ) {
                                            AppTextInput(
                                                value = destRoomSearch,
                                                onValueChange = { destRoomSearch = it },
                                                placeholder = "Search…",
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    .fillMaxWidth()
                                            )
                                                            val filteredRooms = if (destRoomSearch.isBlank()) rooms
                                                                else rooms.filter { it.name.contains(destRoomSearch, ignoreCase = true) ||
                                                                    it.id.contains(destRoomSearch, ignoreCase = true) }
                                                            for (r in filteredRooms) {
                                                                val rid = r.getRoomIdAsInt()
                                                                DropdownMenuItem(
                                                                    text = { Text(r.name, fontSize = 10.sp,
                                                                        fontWeight = if (rid == currentDoor.destRoomPtr) FontWeight.Bold else FontWeight.Normal) },
                                                                    onClick = {
                                                                        destDropExpanded = false
                                                                        destRoomSearch = ""
                                                                        if (rid != currentDoor.destRoomPtr) {
                                                                            val derivedCap = romParser?.deriveDoorCapPosition(
                                                                                rid, currentDoor.direction,
                                                                                currentDoor.screenX, currentDoor.screenY
                                                                            )
                                                                            val match = romParser?.findVanillaDoorMatch(
                                                                                rid, currentDoor.direction,
                                                                                currentDoor.screenX, currentDoor.screenY
                                                                            )
                                                                            editorState.updateDoor(propsBts,
                                                                                currentDoor.copy(
                                                                                    destRoomPtr = rid,
                                                                                    entryCode = match?.entryCode ?: 0,
                                                                                    doorCapCode = derivedCap ?: match?.doorCapCode ?: currentDoor.doorCapCode
                                                                                ))
                                                                        }
                                                                    },
                                                                    modifier = Modifier.height(26.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Direction dropdown
                                                var dirDropExpanded by remember { mutableStateOf(false) }
                                                val dirNames = listOf("Right", "Left", "Down", "Up")
                                                val currentDir = currentDoor.direction and 0x03
                                                val isBubble = (currentDoor.direction and 0x04) != 0
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Direction:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        Surface(
                                                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                                                .clickable { dirDropExpanded = true },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                val bubbleTag = if (isBubble) " (closing)" else ""
                                                                Text("${dirNames.getOrElse(currentDir) { "?" }}$bubbleTag", fontSize = 9.sp, modifier = Modifier.weight(1f))
                                                                Text("▾", fontSize = 9.sp)
                                                            }
                                                        }
                                                        DropdownMenu(
                                                            expanded = dirDropExpanded,
                                                            onDismissRequest = { dirDropExpanded = false }
                                                        ) {
                                                            for ((di, dn) in dirNames.withIndex()) {
                                                                DropdownMenuItem(
                                                                    text = { Text(dn, fontSize = 10.sp) },
                                                                    onClick = {
                                                                        dirDropExpanded = false
                                                                        val newDir = di + (if (isBubble) 4 else 0)
                                                                        val newBitflag = (newDir shl 8) or (currentDoor.bitflag and 0xFF)
                                                                        val derivedCap = romParser?.deriveDoorCapPosition(
                                                                            currentDoor.destRoomPtr, newDir,
                                                                            currentDoor.screenX, currentDoor.screenY
                                                                        )
                                                                        editorState.updateDoor(propsBts, currentDoor.copy(
                                                                            bitflag = newBitflag,
                                                                            doorCapCode = derivedCap ?: currentDoor.doorCapCode
                                                                        ))
                                                                    },
                                                                    modifier = Modifier.height(26.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Screen X (0x00–0xFF)
                                                ByteDropdown("Screen X:", currentDoor.screenX) { v ->
                                                    val derivedCap = romParser?.deriveDoorCapPosition(
                                                        currentDoor.destRoomPtr, currentDoor.direction, v, currentDoor.screenY)
                                                    val match = romParser?.findVanillaDoorMatch(
                                                        currentDoor.destRoomPtr, currentDoor.direction, v, currentDoor.screenY)
                                                    editorState.updateDoor(propsBts, currentDoor.copy(
                                                        screenX = v,
                                                        entryCode = match?.entryCode ?: currentDoor.entryCode,
                                                        doorCapCode = derivedCap ?: match?.doorCapCode ?: currentDoor.doorCapCode
                                                    ))
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Screen Y (0x00–0xFF)
                                                ByteDropdown("Screen Y:", currentDoor.screenY) { v ->
                                                    val derivedCap = romParser?.deriveDoorCapPosition(
                                                        currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, v)
                                                    val match = romParser?.findVanillaDoorMatch(
                                                        currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, v)
                                                    editorState.updateDoor(propsBts, currentDoor.copy(
                                                        screenY = v,
                                                        entryCode = match?.entryCode ?: currentDoor.entryCode,
                                                        doorCapCode = derivedCap ?: match?.doorCapCode ?: currentDoor.doorCapCode
                                                    ))
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Distance from door (16-bit, keep as text)
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Distance:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    var distText by remember(currentDoor) {
                                                        mutableStateOf("0x${currentDoor.distFromDoor.toString(16).uppercase().padStart(4, '0')}")
                                                    }
                                                    AppTextInput(
                                                        value = distText,
                                                        onValueChange = { v ->
                                                            distText = v
                                                            v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                                                editorState.updateDoor(propsBts, currentDoor.copy(distFromDoor = it))
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        fontSize = 10.sp, monospace = true
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Elevator + Closing door toggles
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(
                                                        checked = currentDoor.isElevator,
                                                        onCheckedChange = { checked ->
                                                            val newFlags = if (checked) currentDoor.bitflag or 0x80 else currentDoor.bitflag and 0x7F
                                                            editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newFlags))
                                                        },
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text("Elevator", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Checkbox(
                                                        checked = isBubble,
                                                        onCheckedChange = { checked ->
                                                            val dir = currentDoor.direction and 0x03
                                                            val newDir = dir + (if (checked) 4 else 0)
                                                            val newBitflag = (newDir shl 8) or (currentDoor.bitflag and 0xFF)
                                                            editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newBitflag))
                                                        },
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text("Closing door", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Door cap position with auto-derive
                                                val autoCap = remember(currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, currentDoor.screenY) {
                                                    romParser?.deriveDoorCapPosition(
                                                        currentDoor.destRoomPtr, currentDoor.direction,
                                                        currentDoor.screenX, currentDoor.screenY
                                                    )
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Door Cap:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    var capText by remember(currentDoor) {
                                                        mutableStateOf("0x${currentDoor.doorCapCode.toString(16).uppercase().padStart(4, '0')}")
                                                    }
                                                    AppTextInput(
                                                        value = capText,
                                                        onValueChange = { v ->
                                                            capText = v
                                                            v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                                                editorState.updateDoor(propsBts, currentDoor.copy(doorCapCode = it))
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        fontSize = 10.sp, monospace = true
                                                    )
                                                    if (autoCap != null) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Surface(
                                                            modifier = Modifier.height(24.dp)
                                                                .clickable {
                                                                    editorState.updateDoor(propsBts, currentDoor.copy(doorCapCode = autoCap))
                                                                },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = if (currentDoor.doorCapCode == autoCap) MaterialTheme.colorScheme.primaryContainer
                                                                    else MaterialTheme.colorScheme.tertiaryContainer
                                                        ) {
                                                            Text(
                                                                "Auto",
                                                                fontSize = 8.sp,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (autoCap != null && currentDoor.doorCapCode != autoCap) {
                                                    val capX = autoCap and 0xFF
                                                    val capY = (autoCap shr 8) and 0xFF
                                                    Text(
                                                        "Suggested: 0x${autoCap.toString(16).uppercase().padStart(4, '0')} ($capX, $capY)",
                                                        fontSize = 8.sp,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        modifier = Modifier.padding(start = 72.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Entry ASM:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    var asmText by remember(currentDoor) {
                                                        mutableStateOf("0x${currentDoor.entryCode.toString(16).uppercase().padStart(4, '0')}")
                                                    }
                                                    AppTextInput(
                                                        value = asmText,
                                                        onValueChange = { v ->
                                                            asmText = v
                                                            v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                                                editorState.updateDoor(propsBts, currentDoor.copy(entryCode = it))
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        fontSize = 10.sp, monospace = true
                                                    )
                                                }
                                            }
                                        }

                                        // ── Items / PLMs at this tile ──
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Divider()
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Items / PLMs", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        val plmsHere = editorState.getPlmsAt(propsBlockX, propsBlockY)
                                        val itemPlms = plmsHere.filter { RomParser.isItemPlm(it.id) }
                                        val otherPlms = plmsHere.filter { !RomParser.isItemPlm(it.id) }

                                        if (itemPlms.isEmpty() && otherPlms.isEmpty()) {
                                            Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        for (plm in itemPlms) {
                                            val iName = RomParser.itemNameForPlm(plm.id) ?: "PLM 0x${plm.id.toString(16)}"
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(iName, fontSize = 10.sp)
                                                    Text(
                                                        "bit: 0x${plm.param.toString(16).uppercase().padStart(2, '0')}",
                                                        fontSize = 8.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    "✕",
                                                    modifier = Modifier
                                                        .clickable { editorState.removePlm(plm.x, plm.y, plm.id) }
                                                        .padding(horizontal = 4.dp),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        for (plm in otherPlms) {
                                            val pName = RomParser.plmDisplayName(plm.id, plm.param)
                                            val canRemove = RomParser.isStationPlm(plm.id) || RomParser.isGatePlm(plm.id)
                                                    || RomParser.doorCapColor(plm.id) != null || RomParser.isScrollPlm(plm.id)
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(pName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    if (RomParser.isScrollPlm(plm.id) && plm.id == 0xB703 && romParser != null) {
                                                        val rw = roomHeader?.width ?: 0
                                                        if (rw > 0) {
                                                            val cmds = RomParser.decodeScrollCommands(
                                                                romParser,
                                                                plm.param, rw
                                                            )
                                                            for ((screenIdx, _, scrollVal) in cmds) {
                                                                Text(
                                                                    "  ${RomParser.formatScrollCommand(screenIdx, scrollVal, rw)}",
                                                                    fontSize = 8.sp,
                                                                    color = MaterialTheme.colorScheme.outline
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                if (canRemove) {
                                                    Text(
                                                        "✕",
                                                        modifier = Modifier
                                                            .clickable { editorState.removePlm(plm.x, plm.y, plm.id) }
                                                            .padding(horizontal = 4.dp),
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }

                                        // Add Item button + dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addItemExpanded by remember { mutableStateOf(false) }
                                        var addItemStyle by remember { mutableStateOf(0) }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addItemExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Item", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addItemExpanded,
                                                onDismissRequest = { addItemExpanded = false }
                                            ) {
                                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    listOf("Visible" to 0, "Chozo" to 1, "Hidden" to 2).forEach { (label, idx) ->
                                                        FilterChip(
                                                            selected = addItemStyle == idx,
                                                            onClick = { addItemStyle = idx },
                                                            label = { Text(label, fontSize = 9.sp) },
                                                            modifier = Modifier.height(24.dp)
                                                        )
                                                    }
                                                }
                                                Divider()
                                                for (item in RomParser.ITEM_DEFS) {
                                                    val plmId = when (addItemStyle) {
                                                        1 -> item.chozoId
                                                        2 -> item.hiddenId
                                                        else -> item.visibleId
                                                    }
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(item.shortLabel, fontSize = 9.sp,
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                                Text(item.name, fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            addItemExpanded = false
                                                            editorState.addPlm(plmId, propsBlockX, propsBlockY, 0)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Add Station button + dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addStationExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addStationExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Station / Gate", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addStationExpanded,
                                                onDismissRequest = { addStationExpanded = false }
                                            ) {
                                                Text("Stations", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                for (station in RomParser.STATION_PLMS) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(station.shortLabel, fontSize = 9.sp,
                                                                    color = MaterialTheme.colorScheme.secondary,
                                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                                Text(station.name, fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            addStationExpanded = false
                                                            editorState.addPlm(station.plmId, propsBlockX, propsBlockY, station.defaultParam)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                                Divider()
                                                Text("Gates", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                for (gate in RomParser.GATE_PLMS) {
                                                    DropdownMenuItem(
                                                        text = { Text(gate.name, fontSize = 11.sp) },
                                                        onClick = {
                                                            addStationExpanded = false
                                                            editorState.addPlm(gate.plmId, propsBlockX, propsBlockY, gate.param)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Add Door Cap button + dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addDoorCapExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addDoorCapExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Door Cap", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addDoorCapExpanded,
                                                onDismissRequest = { addDoorCapExpanded = false }
                                            ) {
                                                val doorColors = listOf("Blue", "Red", "Green", "Yellow", "Grey")
                                                for (color in doorColors) {
                                                    val caps = RomParser.DOOR_CAP_PLMS.filter { it.color == color }
                                                    Text(color, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                    for (cap in caps) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                    verticalAlignment = Alignment.CenterVertically) {
                                                                    val dotColor = when (cap.color) {
                                                                        "Blue" -> Color(0xFF3880D0)
                                                                        "Red" -> Color(0xFFD05050)
                                                                        "Green" -> Color(0xFF40C048)
                                                                        "Yellow" -> Color(0xFFD8C830)
                                                                        else -> Color(0xFF808088)
                                                                    }
                                                                    Box(Modifier.size(10.dp).background(dotColor, MaterialTheme.shapes.extraSmall))
                                                                    Text("${cap.direction}", fontSize = 11.sp)
                                                                }
                                                            },
                                                            onClick = {
                                                                addDoorCapExpanded = false
                                                                editorState.addPlm(cap.plmId, propsBlockX, propsBlockY, 0x0000)
                                                            },
                                                            modifier = Modifier.height(28.dp)
                                                        )
                                                    }
                                                    if (color != doorColors.last()) Divider()
                                                }
                                            }
                                        }

                                        // Add Scroll Trigger button + dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addScrollExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addScrollExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = Color(0xFFFF8040).copy(alpha = 0.2f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Scroll Trigger", fontSize = 10.sp,
                                                        color = Color(0xFFFF8040))
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addScrollExpanded,
                                                onDismissRequest = { addScrollExpanded = false }
                                            ) {
                                                val existingScrollCmds = editorState.workingPlms
                                                    ?.filter { it.id == 0xB703 }
                                                    ?.map { it.param }
                                                    ?.distinct()
                                                    ?.sorted()
                                                    ?: emptyList()
                                                if (existingScrollCmds.isNotEmpty()) {
                                                    Text("Reuse command from this room:", fontSize = 9.sp,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold)
                                                    for (cmdPtr in existingScrollCmds) {
                                                        val rw = roomHeader?.width ?: 1
                                                        val cmdLines = if (romParser != null && rw > 0) {
                                                            RomParser.decodeScrollCommands(romParser, cmdPtr, rw)
                                                                .map { (sIdx, _, sv) -> RomParser.formatScrollCommand(sIdx, sv, rw) }
                                                        } else emptyList()
                                                        val itemHeight = (28 + cmdLines.size * 14).coerceAtMost(80)
                                                        DropdownMenuItem(
                                                            text = {
                                                                Column {
                                                                    for (line in cmdLines) {
                                                                        Text(line, fontSize = 9.sp)
                                                                    }
                                                                    Text("ptr \$${cmdPtr.toString(16).uppercase().padStart(4, '0')}",
                                                                        fontSize = 7.sp,
                                                                        color = MaterialTheme.colorScheme.outline)
                                                                }
                                                            },
                                                            onClick = {
                                                                addScrollExpanded = false
                                                                editorState.addPlm(0xB703, propsBlockX, propsBlockY, cmdPtr)
                                                            },
                                                            modifier = Modifier.heightIn(min = itemHeight.dp)
                                                        )
                                                    }
                                                    Divider()
                                                }
                                                Text("Treadmill extensions:", fontSize = 9.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold)
                                                Text("Widens an adjacent trigger's hitbox",
                                                    fontSize = 7.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
                                                    color = MaterialTheme.colorScheme.outline)
                                                for ((plmId, label) in listOf(
                                                    0xB63B to "→ Extend Right",
                                                    0xB63F to "← Extend Left",
                                                    0xB647 to "↑ Extend Up",
                                                    0xB643 to "↓ Extend Down"
                                                )) {
                                                    DropdownMenuItem(
                                                        text = { Text(label, fontSize = 10.sp) },
                                                        onClick = {
                                                            addScrollExpanded = false
                                                            editorState.addPlm(plmId, propsBlockX, propsBlockY, 0x8000)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // ─── Enemies at/near this tile ───
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Divider()
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Enemies", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        val tileCenterX = propsBlockX * 16 + 8
                                        val tileCenterY = propsBlockY * 16 + 8
                                        val enemiesHere = editorState.getEnemiesNear(tileCenterX, tileCenterY, radius = 16)

                                        if (enemiesHere.isEmpty()) {
                                            Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        for (enemy in enemiesHere) {
                                            val eName = RomParser.enemyName(enemy.id)
                                            var editing by remember { mutableStateOf(false) }
                                            if (!editing) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Text(eName, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                            Text(
                                                                "0x${enemy.id.toString(16).uppercase().padStart(4, '0')}",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }
                                                        Text(
                                                            "pos: (${enemy.x}, ${enemy.y})  prop: 0x${enemy.properties.toString(16).uppercase().padStart(4, '0')}",
                                                            fontSize = 8.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        "✎",
                                                        modifier = Modifier
                                                            .clickable { editing = true }
                                                            .padding(horizontal = 4.dp),
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        "✕",
                                                        modifier = Modifier
                                                            .clickable { editorState.removeEnemy(enemy) }
                                                            .padding(horizontal = 4.dp),
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            } else {
                                                var editX by remember { mutableStateOf(enemy.x.toString()) }
                                                var editY by remember { mutableStateOf(enemy.y.toString()) }
                                                var editProps by remember { mutableStateOf(enemy.properties) }
                                                var editInitParam by remember { mutableStateOf(enemy.initParam.toString(16).uppercase().padStart(4, '0')) }
                                                var editExtra1 by remember { mutableStateOf(enemy.extra1.toString(16).uppercase().padStart(4, '0')) }
                                                var editExtra2 by remember { mutableStateOf(enemy.extra2.toString(16).uppercase().padStart(4, '0')) }
                                                var editExtra3 by remember { mutableStateOf(enemy.extra3.toString(16).uppercase().padStart(4, '0')) }
                                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                                    Text(eName, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    Text("ID: 0x${enemy.id.toString(16).uppercase().padStart(4, '0')}",
                                                        fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    // Position
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text("X:", fontSize = 9.sp)
                                                        AppTextInput(
                                                            value = editX, onValueChange = { editX = it },
                                                            modifier = Modifier.width(60.dp),
                                                            fontSize = 10.sp, monospace = true
                                                        )
                                                        Text("Y:", fontSize = 9.sp)
                                                        AppTextInput(
                                                            value = editY, onValueChange = { editY = it },
                                                            modifier = Modifier.width(60.dp),
                                                            fontSize = 10.sp, monospace = true
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    // Property flag checkboxes (from SMILE enemy editor)
                                                    Text("Enemy Data Flags", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    val flagDefs = listOf(
                                                        0x0100 to "Invisible",
                                                        0x0200 to "Move Off-Screen",
                                                        0x0400 to "Platform",
                                                        0x0800 to "Non-Responsive",
                                                        0x1000 to "Respawn",
                                                        0x2000 to "Block Plasma",
                                                    )
                                                    for ((bit, label) in flagDefs) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.fillMaxWidth().height(22.dp)
                                                        ) {
                                                            Checkbox(
                                                                checked = (editProps and bit) != 0,
                                                                onCheckedChange = { checked ->
                                                                    editProps = if (checked) editProps or bit else editProps and bit.inv()
                                                                },
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Text(label, fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    // Extended fields
                                                    @Composable
                                                    fun HexField(label: String, value: String, onValueChange: (String) -> Unit) {
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.width(60.dp))
                                                            AppTextInput(
                                                                value = value, onValueChange = onValueChange,
                                                                modifier = Modifier.weight(1f),
                                                                fontSize = 9.sp, monospace = true, height = 28.dp
                                                            )
                                                        }
                                                    }
                                                    HexField("Tilemaps:", editInitParam) { editInitParam = it }
                                                    HexField("Graphics:", editExtra1) { editExtra1 = it }
                                                    HexField("Speed:", editExtra2) { editExtra2 = it }
                                                    HexField("Speed 2:", editExtra3) { editExtra3 = it }

                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Surface(
                                                            modifier = Modifier.height(24.dp).clickable {
                                                                val nx = editX.toIntOrNull() ?: enemy.x
                                                                val ny = editY.toIntOrNull() ?: enemy.y
                                                                val nInit = editInitParam.removePrefix("0x").removePrefix("0X")
                                                                    .toIntOrNull(16) ?: enemy.initParam
                                                                val nE1 = editExtra1.removePrefix("0x").removePrefix("0X")
                                                                    .toIntOrNull(16) ?: enemy.extra1
                                                                val nE2 = editExtra2.removePrefix("0x").removePrefix("0X")
                                                                    .toIntOrNull(16) ?: enemy.extra2
                                                                val nE3 = editExtra3.removePrefix("0x").removePrefix("0X")
                                                                    .toIntOrNull(16) ?: enemy.extra3
                                                                editorState.updateEnemy(
                                                                    enemy,
                                                                    RomParser.EnemyEntry(enemy.id, nx, ny, nInit, editProps, nE1, nE2, nE3)
                                                                )
                                                                editing = false
                                                            },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.primaryContainer
                                                        ) {
                                                            Text("Save", fontSize = 9.sp,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                        }
                                                        Surface(
                                                            modifier = Modifier.height(24.dp).clickable { editing = false },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Text("Cancel", fontSize = 9.sp,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Add Enemy button + searchable dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addEnemyExpanded by remember { mutableStateOf(false) }
                                        var enemySearch by remember { mutableStateOf("") }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addEnemyExpanded = true; enemySearch = "" },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Enemy", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addEnemyExpanded,
                                                onDismissRequest = { addEnemyExpanded = false },
                                                modifier = Modifier.requiredSizeIn(maxHeight = 400.dp, maxWidth = 250.dp)
                                            ) {
                                                AppTextInput(
                                                    value = enemySearch,
                                                    onValueChange = { enemySearch = it },
                                                    placeholder = "Search enemies…",
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                                val filtered = remember(enemySearch) {
                                                    val q = enemySearch.trim().lowercase()
                                                    if (q.isEmpty()) RomParser.ENEMY_CATALOG
                                                    else RomParser.ENEMY_CATALOG.filter { (id, name) ->
                                                        name.lowercase().contains(q) ||
                                                            id.toString(16).contains(q, ignoreCase = true)
                                                    }
                                                }
                                                for ((enemyId, enemyName) in filtered) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(
                                                                    enemyId.toString(16).uppercase().padStart(4, '0'),
                                                                    fontSize = 8.sp,
                                                                    color = MaterialTheme.colorScheme.tertiary,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                Text(enemyName, fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            addEnemyExpanded = false
                                                            val pixelX = propsBlockX * 16
                                                            val pixelY = propsBlockY * 16
                                                            editorState.addEnemy(enemyId, pixelX, pixelY)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                                if (filtered.isEmpty()) {
                                                    Text("No matches", fontSize = 10.sp,
                                                        modifier = Modifier.padding(8.dp),
                                                        color = MaterialTheme.colorScheme.outline)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Short Charge Ruler — floating centered overlay
                            if (showShortChargeRuler) {
                                Box(modifier = Modifier.align(Alignment.Center)) {
                                    ShortChargeRuler(
                                        stutters = shortChargeStutters,
                                        selectedTaps = shortChargeTaps,
                                        zoomLevel = zoomLevel
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (room == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a room from the list", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Load a ROM file first", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Per-column solid heights from the ROM slope table (kAlignYPos_Tab0) at $94:8B2B.
 * 32 shapes x 16 columns. ROM uses col 0 = left edge, but our renderer
 * indexes right-to-left (col 0 = right screen edge) to match tile graphic orientation.
 * Shapes 0-4 use separate "square" collision code; their entries here are visual
 * approximations (shape 4 = fully solid but ROM table has zeros).
 * Values >16 indicate overshoot into adjacent tiles; drawing code clamps to 0-16.
 */
internal val SLOPE_HEIGHTS = arrayOf(
    intArrayOf( 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8), // 0x00 half solid bottom
    intArrayOf(16,16,16,16,16,16,16,16, 0, 0, 0, 0, 0, 0, 0, 0), // 0x01 half solid side
    intArrayOf(16,16,16,16,16,16,16,16, 8, 8, 8, 8, 8, 8, 8, 8), // 0x02 three-quarter
    intArrayOf( 8, 8, 8, 8, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0), // 0x03 quarter
    intArrayOf(16,16,16,16,16,16,16,16,16,16,16,16,16,16,16,16), // 0x04 fully solid (visual override)
    intArrayOf(16,15,14,13,12,11,10, 9, 9,10,11,12,13,14,15,16), // 0x05 shallow V-trough
    intArrayOf(16,14,12,10, 8, 6, 4, 2, 2, 4, 6, 8,10,12,14,16), // 0x06 deep V-trough
    intArrayOf( 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8), // 0x07 half solid (dup of 0x00)
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x08 unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x09 unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0A unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0B unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0C unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0D unused
    intArrayOf(12,12,12,12, 8, 8, 8, 8, 4, 4, 4, 4, 0, 0, 0, 0), // 0x0E staircase (4-step)
    intArrayOf(14,14,12,12,10,10, 8, 8, 6, 6, 4, 4, 2, 2, 0, 0), // 0x0F smooth staircase
    intArrayOf(16,16,16,16,16,16,16,16,16,16,16,16,16,16,16,16), // 0x10 fully solid
    intArrayOf(20,20,20,20,20,20,20,20,20,20,20,20,20,16,16,16), // 0x11 plateau (overshoot)
    intArrayOf(16,15,14,13,12,11,10, 9, 8, 7, 6, 5, 4, 3, 2, 1), // 0x12 steep 1-tile
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x13 unused
    intArrayOf( 8, 7, 6, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0), // 0x14 45° tile 1/2
    intArrayOf(16,16,16,16,16,16,16,16,16,15,14,13,12,11,10, 9), // 0x15 45° tile 2/2
    intArrayOf( 8, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1), // 0x16 45° smooth tile 1/2
    intArrayOf(16,16,15,15,14,14,13,13,12,12,11,11,10,10, 9, 9), // 0x17 45° smooth tile 2/2
    intArrayOf( 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 1, 1, 1), // 0x18 gentle tile 1/3
    intArrayOf(11,11,10,10,10, 9, 9, 9, 8, 8, 8, 7, 7, 7, 6, 6), // 0x19 gentle tile 2/3
    intArrayOf(16,16,16,15,15,15,14,14,14,13,13,13,12,12,12,11), // 0x1A gentle tile 3/3
    intArrayOf(16,14,12,10, 8, 6, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0), // 0x1B steep tile 1/2
    intArrayOf(20,20,20,20,20,20,20,20,16,14,12,10, 8, 6, 4, 2), // 0x1C steep tile 2/2
    intArrayOf(16,13,10, 7, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x1D steep tile 1/3
    intArrayOf(20,20,20,20,20,20,14,11, 8, 5, 2, 0, 0, 0, 0, 0), // 0x1E steep tile 2/3
    intArrayOf(20,20,20,20,20,20,20,20,20,20,20,15,12, 9, 6, 3), // 0x1F steep tile 3/3
)

/**
 * SM engine liquid physics index: kSamusFxHandlers[(fxType & 0xF) >> 1]
 * 0 = none, 1 = lava, 2 = acid, 3 = water
 */
internal fun liquidPhysicsIndex(fxType: Int): Int = (fxType and 0xF) shr 1

/**
 * Return a 4-color ARGB palette for Layer 3 rendering based on fxType.
 * Color 0 = transparent, colors 1-3 = increasing intensity.
 */
/**
 * Layer 3 palette for static preview. L3 tiles in SM are mostly pixel value 3
 * (background fill) with scattered 1s and 2s (features like rain drops, fog wisps).
 * Color 3 = base tint, Colors 1-2 = brighter feature highlights.
 */
internal fun layer3Palette(fxType: Int): IntArray = when (fxType) {
    0x02 -> // Lava — fiery orange surface
        intArrayOf(0x00000000, 0xD0FF8020.toInt(), 0xA0FF6010.toInt(), 0x50FF4000.toInt())
    0x04 -> // Acid — toxic yellow-green surface
        intArrayOf(0x00000000, 0xC0B0FF00.toInt(), 0x9070B000.toInt(), 0x40406000)
    0x06 -> // Water — cool blue surface
        intArrayOf(0x00000000, 0xC0A0D0FF.toInt(), 0x906080C0.toInt(), 0x40304080)
    0x08 -> // Spores — green particles
        intArrayOf(0x00000000, 0xD0A0FFA0.toInt(), 0xA060C060.toInt(), 0x30103010)
    0x0A -> // Rain — blue-white drops
        intArrayOf(0x00000000, 0xD0C0D8FF.toInt(), 0xA08090C0.toInt(), 0x30102030)
    0x0C, 0x0E, 0x10 -> // Fog / Haze / Dense Fog — warm haze tint
        intArrayOf(0x00000000, 0xA0FF8040.toInt(), 0x80C06030.toInt(), 0x40804020.toInt())
    0x16 -> // Firefleas — yellow glows
        intArrayOf(0x00000000, 0xD0FFFF60.toInt(), 0xA0C0C040.toInt(), 0x30101000)
    0x1C -> // Heat Shimmer — orange shift
        intArrayOf(0x00000000, 0xA0FF8040.toInt(), 0x80C06030.toInt(), 0x40804020.toInt())
    0x24 -> // Fireflea FX (darken)
        intArrayOf(0x00000000, 0x50000000, 0x70000000, 0x90000000.toInt())
    0x26 -> // 4 Statues
        intArrayOf(0x00000000, 0xB0E0C080.toInt(), 0x90B09060.toInt(), 0x50604020)
    0x28, 0x2A -> // Ceres Elevator / Ceres Ridley
        intArrayOf(0x00000000, 0x50000000, 0x70000000, 0xA0000000.toInt())
    0x2C -> // Haze (dark)
        intArrayOf(0x00000000, 0x80000000.toInt(), 0xC0000000.toInt(), 0xF0000000.toInt())
    else -> // Default: subtle white tint
        intArrayOf(0x00000000, 0x80FFFFFF.toInt(), 0x60FFFFFF, 0x30FFFFFF)
}

internal fun richOverlayLabel(overlay: TileOverlay, bts: Int): String = when (overlay) {
    TileOverlay.DOOR -> "D$bts"
    TileOverlay.SHOT_BEAM -> when {
        bts in 0x04..0x07 -> "X?"
        else -> "Xb"
    }
    TileOverlay.SHOT_SUPER -> if (bts == 0x0B) "Xs!" else "Xs"
    TileOverlay.SHOT_PB -> if (bts == 0x09) "Xp!" else "Xp"
    TileOverlay.CRUMBLE -> when {
        bts in 0x04..0x07 -> "C!"
        bts == 0x0B -> "CE"
        else -> "C"
    }
    TileOverlay.BOMB -> if (bts in 0x04..0x07) "B!" else "B"
    else -> overlay.shortLabel
}

/**
 * Draw a speed booster arrow overlay — a right-pointing chevron/arrow
 * resembling the in-game speed booster visual.
 */
private fun drawSpeedBoosterOverlay(g2: java.awt.Graphics2D, x: Int, y: Int, size: Int, color: java.awt.Color) {
    // Background
    g2.color = java.awt.Color(0, 0, 0, 200)
    g2.fillRect(x, y, size, size)

    // Arrow: right-pointing chevron like the speed booster icon
    val m = size / 8f  // unit
    val cx = x + size / 2f
    val cy = y + size / 2f

    // Double chevron arrow (two >>)
    val arrowXs1 = floatArrayOf(cx - 2 * m, cx, cx - 2 * m)
    val arrowYs1 = floatArrayOf(cy - 3 * m, cy, cy + 3 * m)
    val arrowXs2 = floatArrayOf(cx + 0.5f * m, cx + 2.5f * m, cx + 0.5f * m)
    val arrowYs2 = floatArrayOf(cy - 3 * m, cy, cy + 3 * m)

    g2.stroke = java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    g2.color = color
    g2.drawPolyline(arrowXs1.map { it.toInt() }.toIntArray(), arrowYs1.map { it.toInt() }.toIntArray(), 3)
    g2.drawPolyline(arrowXs2.map { it.toInt() }.toIntArray(), arrowYs2.map { it.toInt() }.toIntArray(), 3)

    // Colored border
    g2.stroke = java.awt.BasicStroke(1f)
    g2.drawRect(x, y, size - 1, size - 1)
}

/**
 * Draw the actual collision profile for a slope tile, matching SMILE's overlay style.
 *
 * Uses the ROM height table to draw the exact solid area polygon per tile.
 * BTS bit 6 (0x40) is the collision engine's X-flip flag ($94:87C0).
 * BTS bit 7 (0x80) selects ceiling vs floor.
 * Shapes with all-zero heights (passthrough/air slopes) draw an orange square.
 */
private fun drawSlopeOverlay(g2: java.awt.Graphics2D, px: Int, py: Int, bts: Int, color: java.awt.Color) {
    val s = 16
    val shape = bts and 0x1F
    val isCeiling = (bts and 0x80) != 0
    val xFlip = (bts and 0x40) != 0

    val heights = SLOPE_HEIGHTS[shape]
    if (heights.all { it == 0 }) {
        val bg = java.awt.Color(color.red, color.green, color.blue, 80)
        val border = java.awt.Color(color.red, color.green, color.blue, 200)
        g2.color = bg
        g2.fillRect(px, py, s, s)
        g2.color = border
        g2.stroke = java.awt.BasicStroke(1.5f)
        g2.drawRect(px, py, s, s)
        g2.stroke = java.awt.BasicStroke(1f)
        return
    }

    val bg = java.awt.Color(color.red, color.green, color.blue, 80)
    val border = java.awt.Color(color.red, color.green, color.blue, 200)

    val xPts = mutableListOf<Int>()
    val yPts = mutableListOf<Int>()

    // Build the height profile for each pixel column
    val profile = IntArray(s) { screenX ->
        val col = if (xFlip) screenX else (s - 1 - screenX)
        heights[col].coerceIn(0, s)
    }

    if (!isCeiling) {
        // Find first and last column with height > 0 to avoid baseline overshoot
        val first = profile.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        val last = profile.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: (s - 1)
        xPts.add(px + first); yPts.add(py + s)
        for (screenX in first..last) {
            xPts.add(px + screenX); yPts.add(py + s - profile[screenX])
        }
        xPts.add(px + last); yPts.add(py + s)
    } else {
        val first = profile.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        val last = profile.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: (s - 1)
        xPts.add(px + first); yPts.add(py)
        for (screenX in first..last) {
            xPts.add(px + screenX); yPts.add(py + profile[screenX])
        }
        xPts.add(px + last); yPts.add(py)
    }

    g2.color = bg
    g2.fillPolygon(xPts.toIntArray(), yPts.toIntArray(), xPts.size)
    g2.color = border
    g2.stroke = java.awt.BasicStroke(1.5f)
    g2.drawPolygon(xPts.toIntArray(), yPts.toIntArray(), xPts.size)
    g2.stroke = java.awt.BasicStroke(1f)
}

private const val SCREEN_PX = 16 * 16  // 256 — one screen in pixels

/** Enemy IDs whose sprites should be horizontally flipped when initParam != 0. */
private val ENEMY_IDS_FLIP_BY_INIT_PARAM = setOf(
    0xE6FF, // Fune
    0xE73F, // Namihe
    0xD47F, // Ripper
    0xD2FF, // Geruta
    0xD33F, 0xE67F, // Holtz
    0xD63F, 0xD89F, // Waver
    0xDD3F, // Sova
)

private fun buildCompositeImage(
    data: RoomRenderData,
    activeOverlays: Set<TileOverlay>,
    showGrid: Boolean,
    scrollData: IntArray? = null,
    roomWidthScreens: Int = 0,
    roomHeightScreens: Int = 0,
    layer3Pixels: IntArray? = null,
    layer3Width: Int = 256,
    layer3Height: Int = 264
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    val pixels = if (activeOverlays.contains(TileOverlay.LIGHTEN)) {
        // Brighten dark rooms by scaling RGB values (3x, clamped to 255)
        IntArray(data.pixels.size) { i ->
            val argb = data.pixels[i]
            val a = argb ushr 24
            val r = minOf(((argb shr 16) and 0xFF) * 3, 255)
            val g = minOf(((argb shr 8) and 0xFF) * 3, 255)
            val b = minOf((argb and 0xFF) * 3, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    } else {
        data.pixels
    }
    img.setRGB(0, 0, data.width, data.height, pixels, 0, data.width)

    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

    // Layer 3 overlay: tile the L3 image across the room
    if (activeOverlays.contains(TileOverlay.LAYER3) && layer3Pixels != null && layer3Width > 0 && layer3Height > 0) {
        val l3Img = BufferedImage(layer3Width, layer3Height, BufferedImage.TYPE_INT_ARGB)
        l3Img.setRGB(0, 0, layer3Width, layer3Height, layer3Pixels, 0, layer3Width)
        val oldComposite = g.composite
        g.composite = java.awt.AlphaComposite.SrcOver
        for (ty in 0 until data.height step layer3Height) {
            for (tx in 0 until data.width step layer3Width) {
                g.drawImage(l3Img, tx, ty, null)
            }
        }
        g.composite = oldComposite
    }

    // Draw per-screen scroll color overlay
    if (activeOverlays.contains(TileOverlay.SCROLLS) && scrollData != null && roomWidthScreens > 0) {
        val scrollColors = arrayOf(
            java.awt.Color(200, 40, 40, 40),   // Red (hidden)
            java.awt.Color(40, 80, 200, 40),    // Blue (explorable)
            java.awt.Color(40, 160, 50, 40),    // Green (PLM-gated)
        )
        val scrollBorderColors = arrayOf(
            java.awt.Color(200, 40, 40, 120),
            java.awt.Color(40, 80, 200, 120),
            java.awt.Color(40, 160, 50, 120),
        )
        val scrollLabels = arrayOf("RED", "BLUE", "GREEN")
        val g2 = g as java.awt.Graphics2D
        g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 12)
        for (sy in 0 until roomHeightScreens) {
            for (sx in 0 until roomWidthScreens) {
                val idx = sy * roomWidthScreens + sx
                val scrollVal = scrollData.getOrElse(idx) { 0x01 }.coerceIn(0, 2)
                val px = sx * SCREEN_PX
                val py = sy * SCREEN_PX
                g2.color = scrollColors[scrollVal]
                g2.fillRect(px, py, SCREEN_PX, SCREEN_PX)
                g2.color = scrollBorderColors[scrollVal]
                g2.stroke = java.awt.BasicStroke(2f)
                g2.drawRect(px + 1, py + 1, SCREEN_PX - 3, SCREEN_PX - 3)
                g2.stroke = java.awt.BasicStroke(1f)
                val fm = g2.fontMetrics
                val label = scrollLabels[scrollVal]
                val tw = fm.stringWidth(label)
                g2.color = java.awt.Color(255, 255, 255, 100)
                g2.drawString(label, px + (SCREEN_PX - tw) / 2, py + 16)
            }
        }
    }

    // Draw screen grid when toggle is on (one line every 256 px)
    if (showGrid) {
        g.color = java.awt.Color(255, 255, 255, 0x30)
        var x = 0
        while (x <= data.width) {
            g.drawLine(x, 0, x, data.height)
            x += SCREEN_PX
        }
        var y = 0
        while (y <= data.height) {
            g.drawLine(0, y, data.width, y)
            y += SCREEN_PX
        }
    }
    
    if (activeOverlays.isEmpty()) {
        g.dispose()
        return img
    }
    
    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall
    
    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) {
        g.dispose()
        return img
    }
    val btsData = data.btsData
    val itemBlocks = data.itemBlocks
    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            val idx = by * blocksWide + bx
            if (idx >= data.blockTypes.size) continue
            
            val blockType = data.blockTypes[idx]
            val bts = if (idx < btsData.size) btsData[idx].toInt() and 0xFF else 0
            val px = bx * 16
            val py = by * 16
            
            val matchingOverlays = mutableListOf<TileOverlay>()
            if (activeOverlays.contains(TileOverlay.SOLID) && blockType == 0x8) matchingOverlays.add(TileOverlay.SOLID)
            if (activeOverlays.contains(TileOverlay.SLOPE) && blockType == 0x1) matchingOverlays.add(TileOverlay.SLOPE)
            if (activeOverlays.contains(TileOverlay.DOOR) && blockType == 0x9) matchingOverlays.add(TileOverlay.DOOR)
            if (activeOverlays.contains(TileOverlay.SPIKE) && blockType == 0xA) matchingOverlays.add(TileOverlay.SPIKE)
            if (activeOverlays.contains(TileOverlay.BOMB) && blockType == 0xF) matchingOverlays.add(TileOverlay.BOMB)
            if (blockType == 0xC) {
                when (shotBlockCategory(bts)) {
                    ShotCategory.BEAM -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.SUPER -> if (activeOverlays.contains(TileOverlay.SHOT_SUPER)) matchingOverlays.add(TileOverlay.SHOT_SUPER)
                    ShotCategory.PB -> if (activeOverlays.contains(TileOverlay.SHOT_PB)) matchingOverlays.add(TileOverlay.SHOT_PB)
                    ShotCategory.HIDDEN -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.DOOR -> {}
                }
            }
            if (blockType == 0xB) {
                val isSpeedBts = bts == 0x0E || bts == 0x0F
                if (isSpeedBts && activeOverlays.contains(TileOverlay.SPEED)) matchingOverlays.add(TileOverlay.SPEED)
                else if (!isSpeedBts && activeOverlays.contains(TileOverlay.CRUMBLE)) matchingOverlays.add(TileOverlay.CRUMBLE)
            }
            if (activeOverlays.contains(TileOverlay.GRAPPLE) && blockType == 0xE) matchingOverlays.add(TileOverlay.GRAPPLE)
            if (activeOverlays.contains(TileOverlay.TREADMILL) && blockType == 0x3) matchingOverlays.add(TileOverlay.TREADMILL)
            if (activeOverlays.contains(TileOverlay.ITEMS) && itemBlocks.contains(idx)) matchingOverlays.add(TileOverlay.ITEMS)
            
            // Overlay icons: 1/4 tile size (8×8 in a 16×16 tile), bottom-right quadrant
            val iconSize = 8
            var iconX = px + 16 - iconSize
            val iconY = py + 16 - iconSize

            val g2 = g as java.awt.Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 7)

            for (overlay in matchingOverlays) {
                val color = java.awt.Color(
                    ((overlay.color shr 16) and 0xFF).toInt(),
                    ((overlay.color shr 8) and 0xFF).toInt(),
                    (overlay.color and 0xFF).toInt(),
                    ((overlay.color shr 24) and 0xFF).toInt()
                )

                if (overlay == TileOverlay.SLOPE) {
                    drawSlopeOverlay(g2, px, py, bts, color)
                } else if (overlay == TileOverlay.SPEED) {
                    drawSpeedBoosterOverlay(g2, iconX, iconY, iconSize, color)
                    iconX -= (iconSize + 1)
                } else {
                    val label = richOverlayLabel(overlay, bts)
                    val fm = g2.fontMetrics
                    val labelW = fm.stringWidth(label)
                    val cellW = maxOf(iconSize, labelW + 2)

                    g2.color = java.awt.Color(0, 0, 0, 200)
                    g2.fillRect(iconX + iconSize - cellW, iconY, cellW, iconSize)
                    g2.color = color
                    g2.stroke = java.awt.BasicStroke(1.5f)
                    g2.drawRect(iconX + iconSize - cellW + 1, iconY + 1, cellW - 2, iconSize - 2)
                    g2.stroke = java.awt.BasicStroke(1f)
                    g2.color = java.awt.Color.WHITE
                    g2.drawString(label, iconX + iconSize - cellW + (cellW - labelW) / 2,
                        iconY + (iconSize + fm.ascent - fm.descent) / 2)
                    iconX -= (cellW + 1)
                }
            }
        }
    }
    
    // Draw item / station / gate / door cap labels (positioned at PLM block coordinates)
    if (activeOverlays.contains(TileOverlay.ITEMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val itemColor = java.awt.Color(0xFF, 0xCC, 0x00)       // gold
        val stationColor = java.awt.Color(0x44, 0xCC, 0xFF)    // cyan
        val gateColor = java.awt.Color(0xCC, 0x66, 0xFF)       // purple
        val doorCapColor = java.awt.Color(0x60, 0x80, 0xB0)    // gray-blue
        for (plm in data.plmEntries) {
            val isItem = RomParser.isItemPlm(plm.id)
            val isStation = RomParser.isStationPlm(plm.id)
            val isGate = RomParser.isGatePlm(plm.id)
            val isDoorCap = RomParser.doorCapColor(plm.id) != null
            if (!isItem && !isStation && !isGate && !isDoorCap) continue
            val name = when {
                isItem -> RomParser.ITEM_DEFS.find { it.chozoId == plm.id || it.visibleId == plm.id || it.hiddenId == plm.id }?.name ?: continue
                isStation -> RomParser.stationNameForPlm(plm.id) ?: continue
                isDoorCap -> RomParser.doorCapDisplayName(plm.id) ?: continue
                else -> RomParser.gateNameForPlm(plm.id, plm.param) ?: continue
            }
            val badgeBorder = when {
                isStation -> stationColor
                isGate -> gateColor
                isDoorCap -> doorCapColor
                else -> itemColor
            }
            val horiz = isDoorCap && RomParser.doorCapIsHorizontal(plm.id)
            val cx = if (horiz) plm.x * 16 + 32 else plm.x * 16 + 8
            val cy = if (horiz) plm.y * 16 + 8 else plm.y * 16 + 32
            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = cx - badgeW / 2
            val by = cy - badgeH / 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = badgeBorder
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw scroll PLM badges (positioned at PLM block coordinates)
    if (activeOverlays.contains(TileOverlay.SCROLL_PLMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val scrollBadgeColor = java.awt.Color(0xFF, 0x80, 0x40)
        for (plm in data.plmEntries) {
            if (!RomParser.isScrollPlm(plm.id)) continue
            val name = RomParser.scrollPlmName(plm.id) ?: continue
            val cx = plm.x * 16 + 8
            val cy = plm.y * 16 + 8
            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = cx - badgeW / 2
            val by = cy - badgeH / 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = scrollBadgeColor
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw enemy sprites / markers (positioned at enemy pixel coordinates)
    if (activeOverlays.contains(TileOverlay.ENEMIES) && data.enemyEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val markerColor = java.awt.Color(0xFF, 0x66, 0x44)
        for (enemy in data.enemyEntries) {
            val name = RomParser.enemyName(enemy.id)
            val ex = enemy.x
            val ey = enemy.y
            if (ex < 0 || ex >= data.width || ey < 0 || ey >= data.height) continue

            val hexId = enemy.id.toString(16).uppercase().padStart(4, '0')
            val sprite = EnemySpriteCache.get(hexId)
            if (sprite != null) {
                val sx = ex - sprite.width / 2
                val sy = ey - sprite.height / 2
                // Flip sprite horizontally when initParam indicates right-facing
                val flipH = enemy.initParam != 0 && enemy.id in ENEMY_IDS_FLIP_BY_INIT_PARAM
                if (flipH) {
                    g2.drawImage(sprite, sx + sprite.width, sy, sx, sy + sprite.height,
                        0, 0, sprite.width, sprite.height, null)
                } else {
                    g2.drawImage(sprite, sx, sy, null)
                }
            } else {
                val diamondSize = 6
                val dx = intArrayOf(ex, ex + diamondSize, ex, ex - diamondSize)
                val dy = intArrayOf(ey - diamondSize, ey, ey + diamondSize, ey)
                g2.color = java.awt.Color(0xFF, 0x44, 0x22, 180)
                g2.fillPolygon(dx, dy, 4)
                g2.color = markerColor
                g2.stroke = java.awt.BasicStroke(1.5f)
                g2.drawPolygon(dx, dy, 4)
                g2.stroke = java.awt.BasicStroke(1f)
            }

            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = ex - badgeW / 2
            val spriteH = sprite?.height ?: 12
            val by = ey - spriteH / 2 - badgeH - 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = markerColor
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    g.dispose()
    return img
}
