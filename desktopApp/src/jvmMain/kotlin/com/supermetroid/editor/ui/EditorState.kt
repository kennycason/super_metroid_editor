package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.EnemyChange
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.SaveStationSpawnChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.TILE_EDIT_LAYER_1
import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.EnemyUpdate
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.procgen.BiomeGenerator
import com.supermetroid.editor.procgen.BiomeGenerationOptions
import com.supermetroid.editor.procgen.BiomeGenerationRect
import com.supermetroid.editor.procgen.BiomeRoomEligibility
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeSafetyMask
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.procgen.LevelGrid
import com.supermetroid.editor.procgen.StructureAlgorithm
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.procgen.WfcOptions
import com.supermetroid.editor.procgen.WfcSample
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.toSigned16
import com.supermetroid.editor.rom.toUnsigned16
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val editorStateLog = KotlinLogging.logger {}

private fun editorLog(message: Any? = "") {
    val text = message?.toString() ?: ""
    when {
        text.startsWith("ERROR") || text.contains(" ERROR:") -> editorStateLog.error { text }
        text.startsWith("WARN") || text.contains(" WARN:") -> editorStateLog.warn { text }
        else -> editorStateLog.info { text }
    }
}

internal fun writePng(filePath: String, pixels: IntArray, w: Int, h: Int): Boolean {
    return try {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        javax.imageio.ImageIO.write(img, "png", java.io.File(filePath))
    } catch (_: Exception) { false }
}

data class FloatingMapSelection(val x: Int, val y: Int)

data class EffectiveSaveStationSpawn(
    val area: Int,
    val saveIndex: Int,
    val roomId: Int,
    val doorPtr: Int,
    val scrollX: Int,
    val scrollY: Int,
    val samusY: Int,
    val samusX: Int,
    val pcOffset: Int?,
    val source: String,
) {
    val samusXSigned: Int get() = samusX.toSigned16()
    val samusYSigned: Int get() = samusY.toSigned16()
}

private data class MapSelectionBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
)

// ─── Editor State ───────────────────────────────────────────────

class EditorState {
    internal var testMode = false

    var brush by mutableStateOf<TileBrush?>(null)
        internal set

    var activeTool by mutableStateOf(EditorTool.SELECT)
    var activeRoomLayer by mutableStateOf(RoomEditLayer.LAYER1)

    /** Map selection rectangle in block coordinates (inclusive). */
    var mapSelStart by mutableStateOf<Pair<Int, Int>?>(null)
    var mapSelEnd by mutableStateOf<Pair<Int, Int>?>(null)
    var floatingSelection by mutableStateOf<FloatingMapSelection?>(null)
        private set

    /** Selection rectangle in tileset: (startCol, startRow, endCol, endRow). */
    var tilesetSelStart by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var tilesetSelEnd by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** Last sampled palette row (set by sample tool). Palette editor reads this. */
    var sampledPaletteRow by mutableStateOf(-1)
    var sampledPaletteCol by mutableStateOf(-1)

    /** Incremented when palette colors change — triggers reactive re-read in pixel editor and tileset grid. */
    var paletteVersion by mutableStateOf(0)

    val undoStack = mutableListOf<EditOperation>()
    val redoStack = mutableListOf<EditOperation>()
    var undoVersion by mutableStateOf(0)
        private set

    private val pendingEdits = mutableListOf<TileEdit>()
    private val pendingPositions = mutableSetOf<Long>()
    private val pendingPlmAdds = mutableListOf<PlmChange>()
    private val pendingPlmRemoves = mutableListOf<PlmChange>()

    var project by mutableStateOf(SmEditProject(romPath = ""))
        private set
    var projectFilePath: String = ""
        private set

    var workingLevelData: ByteArray? = null
        private set
    var originalLevelData: ByteArray? = null
        private set
    var workingBlocksWide: Int = 0
        private set
    var workingBlocksTall: Int = 0
        private set
    var currentRoomId: Int = 0
        private set
    var currentTilesetId by mutableStateOf(0)
        private set
    /** The loaded room's tileset as stored in the ROM (before project overrides). */
    private var romTilesetId: Int = 0
    private var currentBgScrolling: Int = 0
    private var currentArea: Int = 0
    private var currentIncomingDoorPtrs: List<Int> = emptyList()
    private var currentAreaRomSaveEntries: Map<Int, RomParser.Companion.SaveEntry> = emptyMap()
    private var currentAreaSaveEntryCount: Int = 0
    private var vanillaSaveIndicesByArea: Map<Int, Set<Int>> = emptyMap()
    /** Currently active room state index (0 = first conditional, last = default). */
    var currentStateIndex: Int = -1
        private set
    var dirty by mutableStateOf(false)
        private set

    /** Monotonic counter incremented each time any room is edited. */
    private var _editCounter = 0L

    /** Maps roomId → last-edit counter value. Higher = more recently edited. */
    private val _roomEditOrder = mutableMapOf<Int, Long>()
    val roomEditOrder: Map<Int, Long> get() = _roomEditOrder

    fun markDirty() {
        dirty = true
        _roomEditOrder[currentRoomId] = ++_editCounter
    }

    // ── Sprite editor state objects ───────────────────────────────────────

    val enemySprite = EnemySpriteEditorState(
        customGfx = { project.customGfx },
        onDirty = { dirty = true },
    )

    val phantoonSprite = PhantoonSpriteEditorState(
        customGfx = { project.customGfx },
        applyCustomGfx = ::applyCustomGfxToTileGraphics,
        onDirty = { dirty = true },
    )

    val kraidSprite = KraidSpriteEditorState(
        customGfx = { project.customGfx },
        onDirty = { dirty = true },
    )

    /** Incremented on every edit to trigger map re-render + update room edit order. */
    private val _editVersionState = mutableStateOf(0)
    var editVersion: Int
        get() = _editVersionState.value
        private set(value) {
            _editVersionState.value = value
            if (currentRoomId != 0) {
                _roomEditOrder[currentRoomId] = ++_editCounter
            }
        }

    /** Incremented when a new ROM is loaded to force full UI refresh. */
    var romVersion by mutableStateOf(0)
        private set

    /** Mouse position on the map in block coordinates (for cursor preview). -1 = off map. */
    var hoverBlockX by mutableStateOf(-1)
    var hoverBlockY by mutableStateOf(-1)

    /** Target block coordinates to scroll to (set by item click, consumed by MapCanvas). */
    var scrollTargetBlockX by mutableStateOf(-1)
    var scrollTargetBlockY by mutableStateOf(-1)

    /** Tile info at hover position (metatile index, block type). */
    var hoverTileWord by mutableStateOf(0)
        private set

    /** Transient status message shown in the bottom bar (auto-clears). */
    var statusMessage by mutableStateOf("")
        private set
    var statusMessageTimestamp by mutableStateOf(0L)
        private set

    fun postStatus(msg: String) {
        statusMessage = msg
        statusMessageTimestamp = System.currentTimeMillis()
    }

    /** TileGraphics for rendering brush preview. Set when room loads. */
    var tileGraphics: TileGraphics? = null
        private set

    /** Metatile index → most common block type, learned from room data + user edits. */
    var metatileBlockTypePresets: Map<Int, Int> = emptyMap()
        private set

    /** Working PLMs for the current room (includes edits). */
    private val _workingPlms = mutableListOf<RomParser.PlmEntry>()
    val workingPlms: List<RomParser.PlmEntry> get() = _workingPlms
    private var originalPlmCount = 0

    /**
     * PLMs for any room with project-level PLM additions/removals applied.
     * For the active room, return the live working list so unsaved edits are visible immediately.
     */
    fun effectivePlmsForRoom(roomId: Int, romParser: RomParser): List<RomParser.PlmEntry> {
        if (roomId == currentRoomId && workingLevelData != null) {
            return _workingPlms.toList()
        }

        val plms = romParser.getAllPlmEntriesForRoom(roomId).toMutableList()
        val roomEdits = project.rooms[project.roomKey(roomId)] ?: return plms

        for (change in roomEdits.plmChanges) {
            when (change.action) {
                "add" -> plms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> plms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
            }
        }

        return plms
    }

    /** Door entries for the current room (mutable for editing). */
    private val _workingDoors = mutableListOf<RomParser.DoorEntry>()
    var doorEntries: List<RomParser.DoorEntry>
        get() = _workingDoors
        private set(value) { _workingDoors.clear(); _workingDoors.addAll(value) }

    /** Working enemy population for the current room (includes edits). */
    private val _workingEnemies = mutableListOf<RomParser.EnemyEntry>()
    val workingEnemies: List<RomParser.EnemyEntry> get() = _workingEnemies

    /** Working scroll data for the current room. One byte per screen (R/B/G). */
    private var _workingScrolls = IntArray(0)
    val workingScrolls: IntArray get() = _workingScrolls
    private var _originalScrolls = IntArray(0)
    var scrollVersion by mutableStateOf(0)
        private set

    // ─── Tileset editor ─────────────────────────────────────────

    /** The currently-viewed tileset in the tileset editor (independent of loaded room). */
    var editorTilesetId by mutableStateOf(0)
        private set

    /** TileGraphics for the tileset editor view (may differ from room's tileGraphics). */
    var editorTileGraphics by mutableStateOf<TileGraphics?>(null)
        private set

    /** Currently selected metatile index in the tileset editor (-1 = none). */
    var editorSelectedMetatile by mutableStateOf(-1)
        private set

    fun loadEditorTileset(tilesetId: Int, romParser: RomParser): Boolean {
        editorTilesetId = tilesetId
        editorSelectedMetatile = -1
        val tg = TileGraphics(romParser)
        if (!tg.loadTileset(tilesetId)) { editorTileGraphics = null; return false }
        applyCustomGfxToTileGraphics(tg, tilesetId)
        editorTileGraphics = tg
        return true
    }

    /** Apply any project-stored custom graphics, metatile tables, and palette to a TileGraphics instance. */
    internal fun applyCustomGfxToTileGraphics(tg: TileGraphics, tilesetId: Int) {
        val gfxData = project.customGfx
        val varB64 = gfxData.varGfx[tilesetId.toString()]
        if (varB64 != null) {
            try { tg.applyCustomVarGfx(java.util.Base64.getDecoder().decode(varB64)) }
            catch (_: Exception) {}
        }
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try { tg.applyCustomCreGfx(java.util.Base64.getDecoder().decode(creB64)) }
            catch (_: Exception) {}
        }
        val tableB64 = gfxData.tileTables[tilesetId.toString()]
        if (tableB64 != null) {
            try { tg.applyCustomVarTileTable(java.util.Base64.getDecoder().decode(tableB64)) }
            catch (_: Exception) {}
        }
        val creTableB64 = gfxData.creTileTable
        if (creTableB64 != null) {
            try { tg.applyCustomCreTileTable(java.util.Base64.getDecoder().decode(creTableB64)) }
            catch (_: Exception) {}
        }
        applyCustomPaletteToTileGraphics(tg, tilesetId)
    }

    // ─── Palette editing ──────────────────────────────────────────────

    /** Apply any project-stored custom palette to a TileGraphics instance. */
    internal fun applyCustomPaletteToTileGraphics(tg: TileGraphics, tilesetId: Int) {
        val palB64 = project.customGfx.palettes[tilesetId.toString()] ?: return
        try {
            val rawBgr = java.util.Base64.getDecoder().decode(palB64)
            if (rawBgr.size != 256) return  // 8 rows × 16 colors × 2 bytes
            for (row in 0..7) for (col in 0..15) {
                val offset = (row * 16 + col) * 2
                val bgr555 = (rawBgr[offset].toInt() and 0xFF) or
                        ((rawBgr[offset + 1].toInt() and 0xFF) shl 8)
                tg.setPaletteEntry(row, col, bgr555)
            }
        } catch (_: Exception) {}
    }

    /** Save the current TileGraphics palette to the project as a base64 BGR555 override. */
    fun savePaletteOverride(tilesetId: Int) {
        val tg = editorTileGraphics ?: return
        val rawBgr = tg.getRawPaletteData() ?: return
        project.customGfx.palettes[tilesetId.toString()] = java.util.Base64.getEncoder().encodeToString(rawBgr)
        dirty = true
        paletteVersion++
        if (tilesetId == currentTilesetId) {
            tileGraphics?.let {
                applyCustomPaletteToTileGraphics(it, tilesetId)
                _editVersionState.value++
            }
        }
    }

    private fun tilesetPaletteEffectKey(tilesetId: Int) = "tileset:$tilesetId"

    /** Remove the custom palette override, restoring the ROM default. */
    fun resetPaletteOverride(tilesetId: Int): Boolean {
        val removedPalette = project.customGfx.palettes.remove(tilesetId.toString()) != null
        val removedEffect = project.customGfx.paletteEffects.remove(tilesetPaletteEffectKey(tilesetId)) != null
        if (removedPalette || removedEffect) {
            dirty = true
            paletteVersion++
            return true
        }
        return false
    }

    /** Check if the project has a custom palette for the given tileset. */
    fun hasCustomPalette(tilesetId: Int): Boolean =
        project.customGfx.palettes.containsKey(tilesetId.toString()) ||
                project.customGfx.paletteEffects.containsKey(tilesetPaletteEffectKey(tilesetId))

    fun hasCurrentTilesetOverrides(): Boolean =
        hasCustomVarGfx() ||
                hasCustomCreGfx() ||
                hasCustomVarTileTable() ||
                hasCustomCreTileTable() ||
                hasCustomPalette(editorTilesetId)

    fun resetCurrentTilesetOverrides(
        areaTiles: Boolean,
        commonTiles: Boolean,
        palette: Boolean,
        areaMetatiles: Boolean = false,
        commonMetatiles: Boolean = false,
    ): Boolean {
        var changed = false
        val tilesetKey = editorTilesetId.toString()
        if (areaTiles && project.customGfx.varGfx.remove(tilesetKey) != null) {
            changed = true
        }
        if (commonTiles && project.customGfx.creGfx != null) {
            project.customGfx.creGfx = null
            changed = true
        }
        if (areaMetatiles && project.customGfx.tileTables.remove(tilesetKey) != null) {
            changed = true
        }
        if (commonMetatiles && project.customGfx.creTileTable != null) {
            project.customGfx.creTileTable = null
            changed = true
        }
        if (palette) {
            var paletteChanged = false
            if (project.customGfx.palettes.remove(tilesetKey) != null) {
                paletteChanged = true
            }
            if (project.customGfx.paletteEffects.remove(tilesetPaletteEffectKey(editorTilesetId)) != null) {
                paletteChanged = true
            }
            if (paletteChanged) {
                paletteVersion++
                changed = true
            }
        }
        if (changed) dirty = true
        return changed
    }

    // ─── Sprite palette editing (Samus, beams, bosses, enemies) ─────

    /**
     * Read a sprite palette region from ROM, applying any project overrides.
     * Returns BGR555 color array, or null if ROM not loaded.
     */
    fun readSpritePalette(regionId: String, romParser: RomParser): IntArray? {
        val region = com.supermetroid.editor.rom.SpritePalettes.findRegion(regionId) ?: return null
        val romData = romParser.getRomData()
        // Start with ROM data
        val colors = com.supermetroid.editor.rom.SpritePalettes.readColors(romData, region) ?: return null
        // Apply project override if present
        val b64 = project.customGfx.spritePalettes[regionId]
        if (b64 != null) {
            try {
                val overrideBytes = java.util.Base64.getDecoder().decode(b64)
                val overrideColors = com.supermetroid.editor.rom.SpritePalettes.bytesToColors(overrideBytes)
                if (overrideColors.size == colors.size) return overrideColors
            } catch (_: Exception) {}
        }
        return colors
    }

    /** Save a sprite palette override to the project. */
    fun saveSpritePaletteOverride(regionId: String, colors: IntArray) {
        val region = com.supermetroid.editor.rom.SpritePalettes.findRegion(regionId) ?: return
        require(colors.size == region.colorCount)
        val bytes = com.supermetroid.editor.rom.SpritePalettes.colorsToBytes(colors)
        project.customGfx.spritePalettes[regionId] = java.util.Base64.getEncoder().encodeToString(bytes)
        dirty = true
        paletteVersion++
    }

    /** Remove sprite palette override, restoring ROM default. */
    fun resetSpritePaletteOverride(regionId: String): Boolean {
        val removedPalette = project.customGfx.spritePalettes.remove(regionId) != null
        val removedEffect = project.customGfx.paletteEffects.remove(regionId) != null
        if (removedPalette || removedEffect) {
            dirty = true
            paletteVersion++
            return true
        }
        return false
    }

    /** Check if a sprite palette has a project override. */
    fun hasSpritePaletteOverride(regionId: String): Boolean =
        project.customGfx.spritePalettes.containsKey(regionId) ||
                project.customGfx.paletteEffects.containsKey(regionId)

    // ─── Palette effect tracking ───────────────────────────────────

    /** Get the applied effect ID for a palette key, or null if none/custom. */
    fun getPaletteEffect(key: String): String? = project.customGfx.paletteEffects[key]

    /** Set the applied effect ID for a palette key. */
    fun setPaletteEffect(key: String, effectId: String) {
        project.customGfx.paletteEffects[key] = effectId
        dirty = true
    }

    /** Clear the effect tracking for a palette key (e.g. on reset or manual edit). */
    fun clearPaletteEffect(key: String): Boolean {
        val changed = project.customGfx.paletteEffects.remove(key) != null
        if (changed) {
            dirty = true
            paletteVersion++
        }
        return changed
    }

    /**
     * Read the palette for any tileset as BGR555 colors (128 = 8 rows × 16).
     * Applies project override if present unless [includeOverride] is false.
     */
    fun readTilesetPalette(tilesetId: Int, romParser: RomParser, includeOverride: Boolean = true): IntArray? {
        try {
            val romData = romParser.getRomData()
            val tablePC = romParser.snesToPc(0x8FE6A2)
            val entryOffset = tablePC + tilesetId * 9
            if (entryOffset + 9 > romData.size) return null
            val palettePtr = (romData[entryOffset + 6].toInt() and 0xFF) or
                    ((romData[entryOffset + 7].toInt() and 0xFF) shl 8) or
                    ((romData[entryOffset + 8].toInt() and 0xFF) shl 16)
            val raw = romParser.decompressLZ2(palettePtr)
            if (raw.size < 256) return null
            val colors = IntArray(128)
            for (i in 0 until 128) {
                colors[i] = (raw[i * 2].toInt() and 0xFF) or
                        ((raw[i * 2 + 1].toInt() and 0xFF) shl 8)
            }
            val b64 = if (includeOverride) project.customGfx.palettes[tilesetId.toString()] else null
            if (b64 != null) {
                try {
                    val overrideBytes = java.util.Base64.getDecoder().decode(b64)
                    if (overrideBytes.size == 256) {
                        for (i in 0 until 128) {
                            colors[i] = (overrideBytes[i * 2].toInt() and 0xFF) or
                                    ((overrideBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
                        }
                    }
                } catch (_: Exception) {}
            }
            return colors
        } catch (_: Exception) { return null }
    }

    /** Save a tileset palette from BGR555 color array (128 colors). */
    fun saveTilesetPaletteFromColors(tilesetId: Int, colors: IntArray) {
        if (colors.size != 128) return
        val raw = ByteArray(256)
        for (i in 0 until 128) {
            raw[i * 2] = (colors[i] and 0xFF).toByte()
            raw[i * 2 + 1] = ((colors[i] shr 8) and 0xFF).toByte()
        }
        project.customGfx.palettes[tilesetId.toString()] = java.util.Base64.getEncoder().encodeToString(raw)
        dirty = true
        paletteVersion++
        if (tilesetId == editorTilesetId) {
            editorTileGraphics?.let { applyCustomPaletteToTileGraphics(it, tilesetId) }
        }
        if (tilesetId == currentTilesetId) {
            tileGraphics?.let {
                applyCustomPaletteToTileGraphics(it, tilesetId)
                _editVersionState.value++
            }
        }
    }

    fun reloadCurrentRoomTileGraphics(romParser: RomParser): Boolean {
        val tg = TileGraphics(romParser)
        tileGraphics = if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tg
        } else {
            null
        }
        _editVersionState.value++
        return tileGraphics != null
    }

    // ─── Tileset graphics export / import ────────────────────────────

    /**
     * Export an 8x8 tile sheet as a PNG file.
     * @param isCre true = CRE (common), false = URE (area-specific)
     */
    fun exportTileSheet(filePath: String, isCre: Boolean): Boolean {
        val tg = editorTileGraphics ?: return false
        val startTile = if (isCre) tg.getCreOffset() else 0
        val numTiles = if (isCre) tg.getCreTileCount() else tg.getVarTileCount()
        val result = tg.renderTileSheet(startTile, numTiles) ?: return false
        val (pixels, w, h) = result
        return writePng(filePath, pixels, w, h)
    }

    /** Export the palette as a PNG reference image. */
    fun exportPalette(filePath: String): Boolean {
        val tg = editorTileGraphics ?: return false
        val result = tg.renderPaletteImage(16) ?: return false
        val (pixels, w, h) = result
        return writePng(filePath, pixels, w, h)
    }

    /**
     * Import an edited tile sheet PNG and store in the project.
     * @param isCre true = CRE (common), false = URE (area-specific)
     */
    fun importTileSheet(filePath: String, isCre: Boolean): Boolean {
        val tg = editorTileGraphics ?: return false
        val img = try { javax.imageio.ImageIO.read(java.io.File(filePath)) } catch (_: Exception) { return false }
            ?: return false
        val w = img.width; val h = img.height
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)

        val startTile = if (isCre) tg.getCreOffset() else 0
        val numTiles = if (isCre) tg.getCreTileCount() else tg.getVarTileCount()
        val raw4bpp = tg.importTileSheet(pixels, w, startTile, numTiles)

        val b64 = java.util.Base64.getEncoder().encodeToString(raw4bpp)
        if (isCre) {
            project.customGfx.creGfx = b64
            tg.applyCustomCreGfx(raw4bpp)
        } else {
            project.customGfx.varGfx[editorTilesetId.toString()] = b64
            tg.applyCustomVarGfx(raw4bpp)
        }
        dirty = true
        return true
    }

    /** Check whether custom graphics exist for the current tileset. */
    fun hasCustomVarGfx(): Boolean = project.customGfx.varGfx.containsKey(editorTilesetId.toString())
    fun hasCustomCreGfx(): Boolean = project.customGfx.creGfx != null
    fun hasCustomVarTileTable(): Boolean = project.customGfx.tileTables.containsKey(editorTilesetId.toString())
    fun hasCustomCreTileTable(): Boolean = project.customGfx.creTileTable != null

    fun saveCurrentMetatileTableOverride(): Boolean {
        val tg = editorTileGraphics ?: return false
        val selected = editorSelectedMetatile
        if (selected !in 0 until TileGraphics.METATILE_COUNT) return false
        if (!tg.isCreMetatileIndex(selected) && !tg.isVariableMetatileIndex(selected)) return false

        val raw = if (tg.isCreMetatileIndex(selected)) {
            tg.getRawCreTileTable() ?: return false
        } else {
            tg.getRawVarTileTable() ?: return false
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        if (tg.isCreMetatileIndex(selected)) {
            project.customGfx.creTileTable = b64
        } else {
            project.customGfx.tileTables[editorTilesetId.toString()] = b64
        }
        dirty = true
        return true
    }

    fun setCurrentMetatileWords(words: IntArray): Boolean {
        val tg = editorTileGraphics ?: return false
        val selected = editorSelectedMetatile
        if (!tg.isCreMetatileIndex(selected) && !tg.isVariableMetatileIndex(selected)) return false
        if (!tg.setMetatileWords(selected, words)) return false
        return saveCurrentMetatileTableOverride()
    }

    // ── Enemy / Boss sprite graphics ──────────────────────────────────────────

    /**
     * Load the effective ARGB pixels for an enemy sprite.
     * Returns custom project PNG if present, otherwise decodes the embedded resource PNG.
     * @param speciesIdHex e.g. "E4BF"
     */
    // ── Enemy sprite delegates ────────────────────────────────────────────

    fun getEnemySpritePixels(speciesIdHex: String) = enemySprite.getEnemySpritePixels(speciesIdHex)
    fun hasCustomEnemySprite(speciesIdHex: String) = enemySprite.hasCustomEnemySprite(speciesIdHex)
    fun exportEnemySprite(speciesIdHex: String, filePath: String) = enemySprite.exportEnemySprite(speciesIdHex, filePath)
    fun importEnemySprite(speciesIdHex: String, filePath: String) = enemySprite.importEnemySprite(speciesIdHex, filePath)
    fun saveEnemySpritePixels(speciesIdHex: String, pixels: IntArray, w: Int, h: Int) = enemySprite.saveEnemySpritePixels(speciesIdHex, pixels, w, h)
    fun resetEnemySprite(speciesIdHex: String) = enemySprite.resetEnemySprite(speciesIdHex)
    fun loadEnemyTileData(romParser: RomParser, speciesId: Int) = enemySprite.loadEnemyTileData(romParser, speciesId)
    fun applyEnemyTileSheetEdits(romParser: RomParser, speciesId: Int, pixels: IntArray, w: Int, h: Int) = enemySprite.applyEnemyTileSheetEdits(romParser, speciesId, pixels, w, h)
    fun hasCustomEnemyTiles(speciesId: Int) = enemySprite.hasCustomEnemyTiles(speciesId)
    fun resetEnemyTiles(speciesId: Int) = enemySprite.resetEnemyTiles(speciesId)
    fun loadEnemyPalette(romParser: RomParser, speciesId: Int) = enemySprite.loadEnemyPalette(romParser, speciesId)
    fun applyEnemyPalette(speciesId: Int, palette: IntArray) = enemySprite.applyEnemyPalette(speciesId, palette)
    fun hasCustomEnemyPalette(speciesId: Int) = enemySprite.hasCustomEnemyPalette(speciesId)
    fun resetEnemyPalette(speciesId: Int) = enemySprite.resetEnemyPalette(speciesId)

    // ── Phantoon sprite delegates ─────────────────────────────────────────

    fun getPhantoonSpritemap(romParser: RomParser) = phantoonSprite.getSpritemap(romParser)
    fun renderPhantoonComponent(romParser: RomParser, def: com.supermetroid.editor.rom.PhantoonSpritemap.ComponentDef) = phantoonSprite.renderComponent(romParser, def)
    fun applyPhantoonComponentEdits(romParser: RomParser, sprite: com.supermetroid.editor.rom.PhantoonSpritemap.AssembledSprite, editedPixels: IntArray) = phantoonSprite.applyComponentEdits(romParser, sprite, editedPixels)
    fun getPhantoonPalette(romParser: RomParser) = phantoonSprite.getPalette(romParser)
    fun hasCustomPhantoonComponents() = phantoonSprite.hasCustomComponents()
    fun loadPhantoonTileSheet(romParser: RomParser) = phantoonSprite.loadTileSheet(romParser)
    fun getSpriteSheetPalette() = phantoonSprite.getSheetPalette()
    fun applyPhantoonTileSheetEdits(pixels: IntArray, w: Int, h: Int) = phantoonSprite.applyTileSheetEdits(pixels, w, h)
    fun hasCustomPhantoonTileSheet() = phantoonSprite.hasCustomTileSheet()
    fun resetPhantoonTileSheet() = phantoonSprite.resetTileSheet()

    // ── Kraid sprite delegates ────────────────────────────────────────────

    fun getKraidSpritemap(romParser: RomParser) = kraidSprite.getSpritemap(romParser)
    fun renderKraidFullBody(romParser: RomParser) = kraidSprite.renderFullBody(romParser)
    fun renderKraidBodyTilemap(romParser: RomParser, def: com.supermetroid.editor.rom.KraidSpritemap.BodyTilemapDef) = kraidSprite.renderBodyTilemap(romParser, def)
    fun renderKraidBigSprmap(romParser: RomParser, def: com.supermetroid.editor.rom.KraidSpritemap.ComponentDef) = kraidSprite.renderBigSprmap(romParser, def)
    fun getKraidPalette(romParser: RomParser) = kraidSprite.getPalette(romParser)
    fun applyKraidComponentEdits(sprite: com.supermetroid.editor.rom.KraidSpritemap.AssembledSprite, editedPixels: IntArray) = kraidSprite.applyComponentEdits(sprite, editedPixels)
    fun loadKraidTileSheet(romParser: RomParser) = kraidSprite.loadTileSheet(romParser)
    fun getKraidSheetPalette() = kraidSprite.getSheetPalette()
    fun applyKraidTileSheetEdits(pixels: IntArray, w: Int, h: Int) = kraidSprite.applyTileSheetEdits(pixels, w, h)
    fun hasCustomKraidTileSheet() = kraidSprite.hasCustomTileSheet()
    fun resetKraidTileSheet() = kraidSprite.resetTileSheet()


    fun selectEditorMetatile(index: Int) {
        editorSelectedMetatile = index
    }

    /** Get the effective default for a metatile: project override > hardcoded > learned > solid. */
    fun getEffectiveTileDefault(tilesetId: Int, metatileIndex: Int): TileDefault {
        val projectOverride = project.getTileDefault(tilesetId, metatileIndex)
        if (projectOverride != null) return TileDefault(projectOverride.blockType, projectOverride.bts)
        val hardcoded = TilesetDefaults.get(metatileIndex)
        if (hardcoded != null) return hardcoded
        val learned = metatileBlockTypePresets[metatileIndex]
        if (learned != null) return TileDefault(learned)
        return TileDefault(0x0) // air by default (no assumption)
    }

    /** Set a project-level tile default override. */
    fun setTileDefault(tilesetId: Int, metatileIndex: Int, blockType: Int, bts: Int) {
        project.setTileDefault(tilesetId, metatileIndex, blockType, bts)
        dirty = true
    }

    /** Remove a project-level tile default override (revert to hardcoded/learned). */
    fun clearTileDefault(tilesetId: Int, metatileIndex: Int) {
        project.removeTileDefault(tilesetId, metatileIndex)
        dirty = true
    }

    /** Check if there's a project override for this metatile. */
    fun hasProjectOverride(tilesetId: Int, metatileIndex: Int): Boolean =
        project.getTileDefault(tilesetId, metatileIndex) != null

    // ─── Patch management ───────────────────────────────────────

    /** Currently selected patch in the patch editor. */
    var selectedPatchId by mutableStateOf<String?>(null)
        private set

    /** Compose-observable version counter for patch list changes. */
    var patchVersion by mutableStateOf(0)
        private set

    /** Compose-observable version counter for saved music edits. */
    var musicEditVersion by mutableStateOf(0)
        private set

    fun musicEditKey(songSet: Int, playIndex: Int): String = MusicTrackEdit.key(songSet, playIndex)

    fun hasMusicEdit(songSet: Int, playIndex: Int): Boolean =
        project.musicEdits.containsKey(musicEditKey(songSet, playIndex))

    fun getMusicEdit(songSet: Int, playIndex: Int): MusicTrackEdit? =
        project.musicEdits[musicEditKey(songSet, playIndex)]

    fun setMusicEdit(key: String, edit: MusicTrackEdit) {
        project.musicEdits[key] = edit
        dirty = true
        musicEditVersion++
    }

    fun removeMusicEdit(key: String): Boolean {
        val removed = project.musicEdits.remove(key) != null
        if (removed) {
            dirty = true
            musicEditVersion++
        }
        return removed
    }

    fun selectPatch(id: String?) { selectedPatchId = id }

    fun addPatch(name: String, description: String = ""): SmPatch {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val patch = SmPatch(id = id, name = name, description = description)
        project.patches.add(patch)
        dirty = true; patchVersion++
        selectedPatchId = id
        return patch
    }

    fun removePatch(id: String) {
        if (isSystemPatch(id)) return
        project.patches.removeAll { it.id == id }
        if (selectedPatchId == id) selectedPatchId = null
        dirty = true; patchVersion++
    }

    fun togglePatch(id: String) {
        project.patches.find { it.id == id }?.let { it.enabled = !it.enabled }
        dirty = true; patchVersion++
    }

    fun updatePatch(id: String, name: String? = null, description: String? = null, enabled: Boolean? = null) {
        val patch = project.patches.find { it.id == id } ?: return
        if (name != null) patch.name = name
        if (description != null) patch.description = description
        if (enabled != null) patch.enabled = enabled
        dirty = true; patchVersion++
    }

    fun enabledCustomItems(): List<CustomItemDef> =
        project.patches
            .asSequence()
            .filter { it.enabled }
            .flatMap { it.customItems.asSequence() }
            .map { it.copy() }
            .toList()

    data class CustomItemPlmDef(
        val item: CustomItemDef,
        val variant: String,
        val plmId: Int,
    )

    fun enabledCustomItemPlms(): List<CustomItemPlmDef> =
        enabledCustomItems().flatMap { item ->
            buildList {
                item.visiblePlmId?.let { add(CustomItemPlmDef(item, "Visible", it)) }
                item.chozoPlmId?.let { add(CustomItemPlmDef(item, "Chozo", it)) }
                item.hiddenPlmId?.let { add(CustomItemPlmDef(item, "Hidden", it)) }
            }
        }

    fun customItemPlmDef(plmId: Int): CustomItemPlmDef? =
        enabledCustomItemPlms().firstOrNull { it.plmId == plmId }

    fun customItemNameForPlm(plmId: Int): String? =
        customItemPlmDef(plmId)?.let { "${it.item.name} (${it.variant})" }

    fun isCustomItemPlm(plmId: Int): Boolean = customItemPlmDef(plmId) != null

    fun isEditorItemPlm(plmId: Int): Boolean = RomParser.isItemPlm(plmId) || isCustomItemPlm(plmId)

    fun addPatchCustomItem(patchId: String): CustomItemDef? {
        val patch = project.patches.find { it.id == patchId } ?: return null
        val usedIds = patch.customItems.map { it.id }.toSet()
        var suffix = patch.customItems.size + 1
        var id = "custom_item_$suffix"
        while (id in usedIds) {
            suffix++
            id = "custom_item_$suffix"
        }
        val item = CustomItemDef(
            id = id,
            name = "Custom Item $suffix",
            shortLabel = "CI",
            bitMask = nextCustomItemBitMask(),
            iconX = 64,
            iconY = 80,
        )
        patch.customItems.add(item)
        dirty = true; patchVersion++
        return item
    }

    fun removePatchCustomItem(patchId: String, itemId: String) {
        val patch = project.patches.find { it.id == patchId } ?: return
        val removed = patch.customItems.removeAll { it.id == itemId }
        if (removed) {
            dirty = true; patchVersion++
        }
    }

    fun updatePatchCustomItem(
        patchId: String,
        itemId: String,
        name: String? = null,
        shortLabel: String? = null,
        description: String? = null,
        bitMask: Int? = null,
        iconX: Int? = null,
        iconY: Int? = null,
        category: String? = null,
    ) {
        val item = project.patches.find { it.id == patchId }?.customItems?.find { it.id == itemId } ?: return
        if (name != null) item.name = name
        if (shortLabel != null) item.shortLabel = shortLabel.take(4)
        if (description != null) item.description = description
        if (bitMask != null) item.bitMask = bitMask and 0xFFFF
        if (iconX != null) item.iconX = iconX.coerceIn(0, 112)
        if (iconY != null) item.iconY = iconY.coerceIn(0, 112)
        if (category != null) item.category = category
        dirty = true; patchVersion++
    }

    private fun nextCustomItemBitMask(): Int {
        val used = project.patches.flatMap { it.customItems }.map { it.bitMask }.toSet()
        return listOf(0x0010, 0x0040, 0x0080, 0x0400, 0x0800).firstOrNull { it !in used } ?: 0
    }

    fun setPatchWrites(id: String, writes: List<SmPatchWrite>) {
        val patch = project.patches.find { it.id == id } ?: return
        patch.writes.clear()
        patch.writes.addAll(writes.map { PatchWrite(it.offset, it.bytes) })
        dirty = true; patchVersion++
    }

    fun setPatchConfigValue(id: String, value: Int) {
        project.patches.find { it.id == id }?.let {
            it.configValue = value
            if (it.configType != null) it.enabled = true
        }
        dirty = true; patchVersion++
    }

    fun setPatchConfigData(id: String, key: String, value: Int) {
        val patch = project.patches.find { it.id == id } ?: return
        val data = patch.configData ?: mutableMapOf()
        data[key] = value
        patch.configData = data
        if (patch.configType != null) patch.enabled = true
        dirty = true; patchVersion++
    }

    fun normalizedRoomNameOverrideKey(roomIdText: String): String? {
        val trimmed = roomIdText.trim()
        val hex = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
            trimmed.startsWith('$') -> trimmed.drop(1)
            else -> trimmed
        }
        val roomId = hex.toIntOrNull(16)?.takeIf { it in 0x0000..0xFFFF } ?: return null
        return roomId.toString(16).uppercase().padStart(4, '0')
    }

    fun setRoomNameOverride(roomIdText: String, roomName: String, defaultName: String? = null): Boolean {
        val key = normalizedRoomNameOverrideKey(roomIdText) ?: return false
        val cleaned = roomName.replace('\r', ' ').replace('\n', ' ')
        val matchingKeys = project.roomNameOverrides.keys
            .filter { normalizedRoomNameOverrideKey(it) == key }
        val shouldRemove = (defaultName == null && cleaned.isBlank()) ||
            (defaultName != null && cleaned == defaultName)

        var changed = false
        for (existingKey in matchingKeys) {
            if (existingKey != key || shouldRemove) {
                project.roomNameOverrides.remove(existingKey)
                changed = true
            }
        }

        if (!shouldRemove) {
            if (project.roomNameOverrides[key] != cleaned) {
                project.roomNameOverrides[key] = cleaned
                changed = true
            }
        }

        if (changed) {
            dirty = true
            patchVersion++
        }
        return true
    }

    fun removeRoomNameOverride(roomIdText: String): Boolean {
        val key = normalizedRoomNameOverrideKey(roomIdText) ?: return false
        val matchingKeys = project.roomNameOverrides.keys
            .filter { normalizedRoomNameOverrideKey(it) == key }
        if (matchingKeys.isEmpty()) return true
        for (existingKey in matchingKeys) {
            project.roomNameOverrides.remove(existingKey)
        }
        dirty = true
        patchVersion++
        return true
    }

    // ─── Pattern management ──────────────────────────────────────

    var patternVersion by mutableStateOf(0)
        private set
    var selectedPatternId by mutableStateOf<String?>(null)
        private set
    var activePattern by mutableStateOf<TilePattern?>(null)
        private set

    fun selectPattern(id: String?) {
        selectedPatternId = id
        activePattern = if (id != null) project.patterns.find { it.id == id } else null
    }

    fun addPattern(name: String, cols: Int, rows: Int, tilesetId: Int? = null): TilePattern {
        val id = "pattern_${System.currentTimeMillis()}"
        val cells = MutableList<PatternCell?>(rows * cols) { null }
        val pattern = TilePattern(id, name, cols, rows, tilesetId, cells)
        project.patterns.add(pattern)
        dirty = true; patternVersion++
        if (!testMode) PatternLibrary.saveAll(project.patterns)
        return pattern
    }

    fun removePattern(id: String) {
        project.patterns.removeAll { it.id == id }
        if (selectedPatternId == id) selectPattern(null)
        dirty = true; patternVersion++
        if (!testMode) PatternLibrary.saveAll(project.patterns)
    }

    fun updatePatternCell(patternId: String, r: Int, c: Int, cell: PatternCell) {
        project.patterns.find { it.id == patternId }?.setCell(r, c, cell)
        dirty = true; patternVersion++
    }

    fun renamePattern(id: String, name: String) {
        project.patterns.find { it.id == id }?.name = name
        dirty = true; patternVersion++
    }

    /** Convert a pattern to a TileBrush for placement on the map. */
    fun patternToBrush(pattern: TilePattern, hFlip: Boolean = false, vFlip: Boolean = false): TileBrush {
        val skip = mutableSetOf<Long>()
        val tiles = List(pattern.rows) { r ->
            List(pattern.cols) { c ->
                val cell = pattern.getCell(r, c)
                if (cell == null) {
                    skip.add((r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL))
                    0
                } else cell.metatile
            }
        }
        val btOverrides = mutableMapOf<Long, Int>()
        val btsOverrides = mutableMapOf<Long, Int>()
        val flipOverrides = mutableMapOf<Long, Int>()
        val plmOverrides = mutableMapOf<Long, Pair<Int, Int>>()
        for (r in 0 until pattern.rows) for (c in 0 until pattern.cols) {
            val key = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
            val cell = pattern.getCell(r, c) ?: continue
            btOverrides[key] = cell.blockType
            if (cell.bts != 0) btsOverrides[key] = cell.bts
            val flipBits = (if (cell.hFlip) 1 else 0) or (if (cell.vFlip) 2 else 0)
            if (flipBits != 0) flipOverrides[key] = flipBits
            if (cell.plmId != 0) plmOverrides[key] = Pair(cell.plmId, cell.plmParam)
        }
        return TileBrush(tiles, blockType = 0x8, hFlip = hFlip, vFlip = vFlip,
            blockTypeOverrides = btOverrides, btsOverrides = btsOverrides,
            flipOverrides = flipOverrides, plmOverrides = plmOverrides,
            skipCells = skip)
    }

    fun selectAndApplyPattern(id: String) {
        val pattern = project.patterns.find { it.id == id } ?: return
        selectedPatternId = id
        activePattern = pattern
        brush = patternToBrush(pattern)
        activeTool = EditorTool.PAINT
    }

    /** Patterns visible for the current tileset: CRE patterns + current tileset patterns. */
    fun patternsForTileset(tilesetId: Int): List<TilePattern> {
        return project.patterns.filter { it.tilesetId == null || it.tilesetId == tilesetId }
    }

    // ─── Pattern editing (mini room editor for patterns) ────────────

    data class PatternEdit(val r: Int, val c: Int, val old: PatternCell?, val new: PatternCell?)
    data class PatternOperation(val edits: List<PatternEdit>)

    val patternUndoStack = mutableListOf<PatternOperation>()
    val patternRedoStack = mutableListOf<PatternOperation>()
    private val pendingPatEdits = mutableListOf<PatternEdit>()
    private val pendingPatPositions = mutableSetOf<Long>()

    var patternEditVersion by mutableStateOf(0)
        private set
    var patUndoVersion by mutableStateOf(0)
        private set
    var patHoverX by mutableStateOf(-1)
    var patHoverY by mutableStateOf(-1)

    fun loadPatternForEdit(patternId: String) {
        val pat = project.patterns.find { it.id == patternId } ?: return
        selectedPatternId = patternId
        activePattern = pat
        patternUndoStack.clear()
        patternRedoStack.clear()
        patternEditVersion++; patUndoVersion++
    }

    fun resizePattern(patternId: String, newCols: Int, newRows: Int) {
        val pat = project.patterns.find { it.id == patternId } ?: return
        if (newCols == pat.cols && newRows == pat.rows) return
        val newCells = MutableList<PatternCell?>(newRows * newCols) { idx ->
            val r = idx / newCols; val c = idx % newCols
            if (r < pat.rows && c < pat.cols) pat.getCell(r, c) else null
        }
        val idx = project.patterns.indexOfFirst { it.id == patternId }
        if (idx >= 0) {
            project.patterns[idx] = pat.copy(cols = newCols, rows = newRows, cells = newCells)
            activePattern = project.patterns[idx]
        }
        patternUndoStack.clear(); patternRedoStack.clear()
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
    }

    fun patBeginStroke() {
        pendingPatEdits.clear(); pendingPatPositions.clear()
    }

    fun patPaintAt(bx: Int, by: Int): Boolean {
        val pat = activePattern ?: return false
        val b = brush ?: return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= pat.cols || ty >= pat.rows) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPatPositions.contains(key)) continue
                val oldCell = pat.getCell(ty, tx)
                val word = b.blockWordAt(r, c)
                val mt = word and 0x3FF
                val hf = (word shr 10) and 1 != 0
                val vf = (word shr 11) and 1 != 0
                val bt = (word shr 12) and 0xF
                val bts = b.btsAt(r, c)
                val newCell = PatternCell(mt, bt, bts, hf, vf)
                if (oldCell == newCell) continue
                pat.setCell(ty, tx, newCell)
                pendingPatEdits.add(PatternEdit(ty, tx, oldCell, newCell))
                pendingPatPositions.add(key)
                changed = true
            }
        }
        if (changed) patternEditVersion++
        return changed
    }

    fun patEraseAt(bx: Int, by: Int): Boolean {
        val pat = activePattern ?: return false
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return false
        val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
        if (pendingPatPositions.contains(key)) return false
        val oldCell = pat.getCell(by, bx) ?: return false
        pat.setCell(by, bx, null)
        pendingPatEdits.add(PatternEdit(by, bx, oldCell, null))
        pendingPatPositions.add(key)
        patternEditVersion++
        return true
    }

    fun patEndStroke() {
        if (pendingPatEdits.isEmpty()) return
        val op = PatternOperation(pendingPatEdits.toList())
        patternUndoStack.add(op)
        patternRedoStack.clear()
        dirty = true; patternVersion++; patUndoVersion++
        pendingPatEdits.clear(); pendingPatPositions.clear()
    }

    fun patFloodFill(bx: Int, by: Int) {
        val pat = activePattern ?: return
        val b = brush ?: return
        if (b.rows != 1 || b.cols != 1) return
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return
        val targetCell = pat.getCell(by, bx)
        val word = b.blockWordAt(0, 0)
        val mt = word and 0x3FF; val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0; val bt = (word shr 12) and 0xF
        val bts = b.btsAt(0, 0)
        val fillCell = PatternCell(mt, bt, bts, hf, vf)
        if (targetCell == fillCell) return
        val edits = mutableListOf<PatternEdit>()
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            val k = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (k in visited) continue
            if (cx < 0 || cy < 0 || cx >= pat.cols || cy >= pat.rows) continue
            val cell = pat.getCell(cy, cx)
            if (cell != targetCell) continue
            visited.add(k)
            edits.add(PatternEdit(cy, cx, cell, fillCell))
            pat.setCell(cy, cx, fillCell)
            queue.add(Pair(cx - 1, cy)); queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1)); queue.add(Pair(cx, cy + 1))
        }
        if (edits.isNotEmpty()) {
            patternUndoStack.add(PatternOperation(edits))
            patternRedoStack.clear()
            dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
        }
    }

    fun patSampleTile(bx: Int, by: Int) {
        val pat = activePattern ?: return
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return
        val cell = pat.getCell(by, bx) ?: return
        val btsMap = if (cell.bts != 0) mapOf(0L to cell.bts) else emptyMap()
        val flipBits = (if (cell.hFlip) 1 else 0) or (if (cell.vFlip) 2 else 0)
        val flipMap = if (flipBits != 0) mapOf(0L to flipBits) else emptyMap()
        brush = TileBrush(
            tiles = listOf(listOf(cell.metatile)),
            blockType = cell.blockType,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
        tilesetSelStart = Pair(cell.metatile % 32, cell.metatile / 32)
        tilesetSelEnd = tilesetSelStart
        activeTool = EditorTool.PAINT
    }

    fun patUndo() {
        if (patternUndoStack.isEmpty()) return
        val pat = activePattern ?: return
        val op = patternUndoStack.removeAt(patternUndoStack.lastIndex)
        for (edit in op.edits.reversed()) {
            pat.setCell(edit.r, edit.c, edit.old)
        }
        patternRedoStack.add(op)
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
    }

    fun patRedo(): Boolean {
        if (patternRedoStack.isEmpty()) return false
        val pat = activePattern ?: return false
        val op = patternRedoStack.removeAt(patternRedoStack.lastIndex)
        for (edit in op.edits) {
            pat.setCell(edit.r, edit.c, edit.new)
        }
        patternUndoStack.add(op)
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
        return true
    }

    fun patReadCell(bx: Int, by: Int): PatternCell? {
        val pat = activePattern ?: return null
        return pat.getCell(by, bx)
    }

    fun patCellWord(bx: Int, by: Int): Int {
        val cell = patReadCell(bx, by) ?: return 0
        var w = cell.metatile and 0x3FF
        if (cell.hFlip) w = w or (1 shl 10)
        if (cell.vFlip) w = w or (1 shl 11)
        w = w or ((cell.blockType and 0xF) shl 12)
        return w
    }

    fun patSetCellProperties(bx: Int, by: Int, blockType: Int, bts: Int) {
        val pat = activePattern ?: return
        val old = pat.getCell(by, bx) ?: PatternCell(0, blockType = 0)
        val newCell = old.copy(blockType = blockType, bts = bts)
        if (old == newCell) return
        pat.setCell(by, bx, newCell)
        val edit = PatternEdit(bx, by, old, newCell)
        val op = PatternOperation(listOf(edit))
        patternUndoStack.add(op)
        patternRedoStack.clear()
        patUndoVersion++
        dirty = true
        patternEditVersion++
    }

    fun patSetCellPlm(bx: Int, by: Int, plmId: Int, plmParam: Int) {
        val pat = activePattern ?: return
        val old = pat.getCell(by, bx) ?: PatternCell(0, blockType = 0)
        val newCell = old.copy(plmId = plmId, plmParam = plmParam)
        if (old == newCell) return
        pat.setCell(by, bx, newCell)
        val edit = PatternEdit(bx, by, old, newCell)
        val op = PatternOperation(listOf(edit))
        patternUndoStack.add(op)
        patternRedoStack.clear()
        patUndoVersion++
        dirty = true
        patternEditVersion++
    }

    fun patRemoveCellPlm(bx: Int, by: Int) {
        patSetCellPlm(bx, by, 0, 0)
    }

    /** Save the current map selection rectangle as a new pattern. */
    fun saveSelectionAsPattern(name: String, isCre: Boolean = true): TilePattern? {
        val s = mapSelStart ?: return null
        val e = mapSelEnd ?: return null
        val minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val cols = maxX - minX + 1
        val rows = maxY - minY + 1
        val cells = mutableListOf<PatternCell?>()
        for (by in minY..maxY) {
            for (bx in minX..maxX) {
                val word = readBlockWord(bx, by)
                val metatile = word and 0x3FF
                val hFlip = (word shr 10) and 1 != 0
                val vFlip = (word shr 11) and 1 != 0
                val blockType = (word shr 12) and 0xF
                val bts = readBts(bx, by)
                val plmsAtTile = getPlmsAt(bx, by)
                val plm = plmsAtTile.firstOrNull()
                cells.add(PatternCell(metatile, blockType, bts, hFlip, vFlip,
                    plm?.id ?: 0, plm?.param ?: 0))
            }
        }
        val id = "pattern_${System.currentTimeMillis()}"
        val tsId = if (isCre) null else currentTilesetId
        val pat = TilePattern(id, name, cols, rows, tsId, cells)
        project.patterns.add(pat)
        dirty = true; patternVersion++
        mapSelStart = null; mapSelEnd = null
        return pat
    }

    /** Move the current selection preview without touching room tiles. */
    fun shiftSelection(dx: Int, dy: Int) {
        if (floatingSelection == null && mapSelStart != null && mapSelEnd != null) {
            beginFloatingSelectionFromMapSelection()
        }
        val floating = floatingSelection ?: return
        setFloatingSelectionPosition(floating.x + dx, floating.y + dy)
    }

    /**
     * Remove project tile-default overrides for tiles we've fixed in TilesetDefaults.
     * Lets the core config take effect so users don't need to manually clear overrides.
     */
    private fun migrateTileDefaultsToCore() {
        val fixedIndices = setOf(
            69, 70, 71, 72, 73, 89, 90, 91, 92, 95, 100, 101, 102,
            122, 123, 124, 125, 126, 127,
            150, 151, 152, 153, 154, 182, 184, 185, 186,
            187, 214, 215, 216, 217, 218, 219, 220
        )
        val keysToRemove = project.tileDefaults.keys.filter { key ->
            key.split(":").getOrNull(1)?.toIntOrNull() in fixedIndices
        }
        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { project.tileDefaults.remove(it) }
            dirty = true
        }
    }

    /**
     * Seed built-in patterns by extracting tile data from known vanilla ROM rooms.
     * Only adds patterns whose IDs don't already exist in the project.
     */
    fun seedBuiltInPatterns(romParser: RomParser?) {
        val builtInIds = listOf(
            "builtin_door_blue_left", "builtin_door_blue_right",
            "builtin_door_red_left", "builtin_door_red_right",
            "builtin_door_green_left", "builtin_door_green_right",
            "builtin_door_yellow_left", "builtin_door_yellow_right",
            "builtin_save_station", "builtin_energy_refill",
            "builtin_missile_refill", "builtin_chozo_statue"
        )

        // Remove all gate patterns — gates are better placed via BTS directly
        val gatePatternIds = setOf(
            "builtin_left_gate", "builtin_right_gate",
            "builtin_gate_blue_left", "builtin_gate_blue_right",
            "builtin_gate_pink_left", "builtin_gate_pink_right",
            "builtin_gate_green_left", "builtin_gate_green_right",
            "builtin_gate_yellow_left", "builtin_gate_yellow_right"
        )
        project.patterns.removeAll { it.id in gatePatternIds && it.builtIn }

        // Migrate old incorrect door patterns so they get re-seeded correctly.
        val wrongDoorRightIds = setOf(
            "builtin_door_blue_right", "builtin_door_red_right",
            "builtin_door_green_right", "builtin_door_yellow_right"
        )
        val wrongPlmIds = setOf(0xC8A6, 0xC88E, 0xC876, 0xC85E)
        project.patterns.removeAll { pat ->
            pat.id in wrongDoorRightIds && pat.builtIn &&
                pat.cells.any { it != null && it.plmId in wrongPlmIds }
        }

        // Migrate old placeholder station/chozo patterns (wrong dimensions or generic tiles)
        val stationChozoIds = setOf("builtin_energy_refill", "builtin_missile_refill", "builtin_chozo_statue")
        project.patterns.removeAll { pat ->
            pat.id in stationChozoIds && pat.builtIn && (pat.rows != 3 || pat.cols != 3)
        }

        // Migrate save station: updated to use CRE tiles 89/91 instead of 0xFF placeholders
        project.patterns.removeAll { pat ->
            pat.id == "builtin_save_station" && pat.builtIn &&
                (pat.cols != 2 || pat.rows != 5 || pat.cells.firstOrNull()?.metatile != 89)
        }

        // Migrate energy/missile refill patterns: wrong PLM param, non-null bottom corners,
        // or wrong block types (middle/bottom rows must be solid 0x8 to match vanilla)
        val refillIds = setOf("builtin_energy_refill", "builtin_missile_refill")
        project.patterns.removeAll { pat ->
            pat.id in refillIds && pat.builtIn && (
                pat.cells.any { it != null && it.plmParam == 0x8000 } ||
                (pat.cells.size == 9 && pat.cells[6] != null) ||
                (pat.cells.size == 9 && pat.cells[4]?.blockType == 0x0)
            )
        }

        val existing = project.patterns.map { it.id }.toSet()
        if (builtInIds.all { it in existing }) return

        if (romParser == null) return

        fun addBuiltIn(id: String, name: String, cols: Int, rows: Int, cells: List<PatternCell?>,
                       tilesetId: Int? = null, noFlip: Boolean = false) {
            if (id in existing) return
            val pat = TilePattern(id, name, cols, rows, tilesetId, cells.toMutableList(),
                builtIn = true, noFlip = noFlip)
            project.patterns.add(pat)
        }

        // Door helper: 1x4 door transition tiles with door cap PLM on top cell.
        // CRE tiles 0x040 (top/bottom) and 0x060 (middle), block type 0x9,
        // BTS defaults to 0x00 (user must set correct door index per room).
        // Left-side doors have hFlip=true, right-side have hFlip=false.
        fun doorPatternCells(capPlmId: Int, hFlip: Boolean): List<PatternCell> = listOf(
            PatternCell(0x040, blockType = 0x9, bts = 0x00, hFlip = hFlip, plmId = capPlmId, plmParam = 0x0000),
            PatternCell(0x060, blockType = 0x9, bts = 0x00, hFlip = hFlip),
            PatternCell(0x060, blockType = 0x9, bts = 0x00, hFlip = hFlip, vFlip = true),
            PatternCell(0x040, blockType = 0x9, bts = 0x00, hFlip = hFlip, vFlip = true)
        )

        try {
            // ── Doors: all colors, left and right facing ──
            // Door PLM headers are 6 bytes (3 pointers: setup, open, close),
            // so Left→Right offset is +6, NOT +4.
            // Left (on left wall): Blue 0xC8A2, Red 0xC88A, Green 0xC872, Yellow 0xC85A
            // Right (on right wall): Blue 0xC8A8, Red 0xC890, Green 0xC878, Yellow 0xC860
            addBuiltIn("builtin_door_blue_left",   "Door: Blue (Left)",   1, 4, doorPatternCells(0xC8A2, true), noFlip = true)
            addBuiltIn("builtin_door_blue_right",  "Door: Blue (Right)",  1, 4, doorPatternCells(0xC8A8, false), noFlip = true)
            addBuiltIn("builtin_door_red_left",    "Door: Red (Left)",    1, 4, doorPatternCells(0xC88A, true), noFlip = true)
            addBuiltIn("builtin_door_red_right",   "Door: Red (Right)",   1, 4, doorPatternCells(0xC890, false), noFlip = true)
            addBuiltIn("builtin_door_green_left",  "Door: Green (Left)",  1, 4, doorPatternCells(0xC872, true), noFlip = true)
            addBuiltIn("builtin_door_green_right", "Door: Green (Right)", 1, 4, doorPatternCells(0xC878, false), noFlip = true)
            addBuiltIn("builtin_door_yellow_left", "Door: Yellow (Left)", 1, 4, doorPatternCells(0xC85A, true), noFlip = true)
            addBuiltIn("builtin_door_yellow_right","Door: Yellow (Right)", 1, 4, doorPatternCells(0xC860, false), noFlip = true)

            // ── Save Station: 2x5, CRE tiles 89 (solid top/bottom) + 91 (air BG).
            // PLM on bottom-left (row 4, col 0). PLM renders animated graphic at runtime.
            addBuiltIn("builtin_save_station", "Save Station", 2, 5, listOf(
                PatternCell(89, blockType = 0x8, bts = 4),
                PatternCell(89, blockType = 0x8, bts = 4, hFlip = true),
                PatternCell(91, blockType = 0x0),
                PatternCell(91, blockType = 0x0, hFlip = true),
                PatternCell(91, blockType = 0x0),
                PatternCell(91, blockType = 0x0, hFlip = true),
                PatternCell(91, blockType = 0x0),
                PatternCell(91, blockType = 0x0, hFlip = true),
                PatternCell(89, blockType = 0x8, bts = 4, vFlip = true, plmId = 0xB76F, plmParam = 0x8000),
                PatternCell(89, blockType = 0x8, bts = 4, hFlip = true, vFlip = true),
            ))

            // ── Energy Refill: 3x3, CRE tiles matching vanilla station layout ──
            // Bottom corners are null: the PLM draws its own CRE tiles at runtime,
            // and null cells let the room's background show through.
            // Station PLM placement rules (from snesrev/sm decompilation):
            //  - PLM center block gets type 0x8, left/right get type 0xB + BTS at runtime
            //  - Activation requires: Samus NOT at full health, facing station, ran-into-wall pose
            //  - Pixel-exact Y check: samus_y_pos == plm_block_y * 16 + 11
            //    → floor must be EXACTLY 2 blocks below the PLM center
            //  - Middle + bottom rows use blockType 0x8 (solid) to match vanilla layout
            addBuiltIn("builtin_energy_refill", "Energy Refill", 3, 3, listOf(
                PatternCell(0x0A3, blockType = 0x0),
                PatternCell(0x0A4, blockType = 0x0),
                PatternCell(0x0A3, blockType = 0x0, hFlip = true),
                PatternCell(0x0C3, blockType = 0x8),
                PatternCell(0x0C4, blockType = 0x8, plmId = 0xB6DF, plmParam = 0x0000),
                PatternCell(0x0C3, blockType = 0x8, hFlip = true),
                null,
                PatternCell(0x0C2, blockType = 0x8),
                null,
            ))

            // ── Missile Refill: 3x3, CRE tiles matching vanilla station layout ──
            addBuiltIn("builtin_missile_refill", "Missile Refill", 3, 3, listOf(
                PatternCell(0x0A3, blockType = 0x0),
                PatternCell(0x0A7, blockType = 0x0),
                PatternCell(0x0A3, blockType = 0x0, hFlip = true),
                PatternCell(0x0C3, blockType = 0x8),
                PatternCell(0x0C7, blockType = 0x8, plmId = 0xB6EB, plmParam = 0x0000),
                PatternCell(0x0C3, blockType = 0x8, hFlip = true),
                null,
                PatternCell(0x0C2, blockType = 0x8),
                null,
            ))

            // ── Chozo Statue: 3x3, CRE chozo statue tiles ──
            addBuiltIn("builtin_chozo_statue", "Chozo Statue", 3, 3, listOf(
                PatternCell(0x044, blockType = 0x0),
                PatternCell(0x045),
                PatternCell(0x046),
                PatternCell(0x064),
                PatternCell(0x065),
                PatternCell(0x066),
                PatternCell(0x047),
                PatternCell(0x048),
                PatternCell(0x049),
            ))
        } catch (e: Exception) {
            editorLog("Failed to seed some built-in patterns: ${e.message}")
        }

        dirty = true
        patternVersion++
    }

    fun getSelectedPatch(): SmPatch? = project.patches.find { it.id == selectedPatchId }

    /** Find an existing patch by configType, or create and add one. Used by Enemy/Boss tabs. */
    fun findOrCreateConfigPatch(configType: String): SmPatch {
        seedDefaultPatches(forceRefreshBundled = true)
        val existing = project.patches.find { it.configType == configType }
        if (existing != null) return existing
        val patch = SmPatch(
            id = configType,
            name = configType.replace("_", " ").replaceFirstChar { it.uppercase() },
            configType = configType,
            enabled = true,
        )
        project.patches.add(patch)
        markDirty()
        return patch
    }

    private var patchesSeeded = false

    /** Ensure all default patches exist; loads bundled IPS + hardcoded hex demos. Idempotent. */
    fun seedDefaultPatches(forceRefreshBundled: Boolean = false) {
        if (patchesSeeded && !forceRefreshBundled) return
        patchesSeeded = true
        // Remove legacy/duplicate patches from old configs
        val removed = project.patches.removeAll { it.id in LEGACY_PATCH_IDS }
        if (removed) {
            if (selectedPatchId in LEGACY_PATCH_IDS) selectedPatchId = null
            patchVersion++
        }

        val existingIds = project.patches.map { it.id }.toSet()
        var added = 0
        var refreshed = 0

        // Collect patches in desired display order: config → hardcoded → bundled IPS
        val ordered = mutableListOf<SmPatch>()

        // 1. GUI config patches (featured at top)
        for (guiPatch in listOf(BEAM_DAMAGE_PATCH, BOSS_STATS_PATCH, PHANTOON_PATCH, KRAID_PATCH, RIDLEY_PATCH, DRAYGON_PATCH, SPORE_SPAWN_PATCH, CROCOMIRE_PATCH, BOTWOON_PATCH, TORIZO_PATCH, MOTHER_BRAIN_PATCH, ENEMY_STATS_PATCH, ENEMY_DROP_RATE_PATCH, ENEMY_VULNERABILITY_PATCH, SAMUS_PHYSICS_PATCH, BOMBS_PATCH, FANFARE_PATCH, ROOM_NAME_PAUSE_MAP_PATCH, BOSS_DEFEATED_PATCH, CONTROLLER_CONFIG_PATCH, CERES_ESCAPE_PATCH)) {
            if (guiPatch.id !in existingIds) {
                ordered.add(SmPatch(
                    id = guiPatch.id,
                    name = guiPatch.name,
                    description = guiPatch.description,
                    enabled = guiPatch.enabled,
                    writes = mutableListOf(),
                    configType = guiPatch.configType,
                    configValue = guiPatch.configValue
                ))
            }
        }

        // 2. Hardcoded hex-tweak patches (popular ones first via list order)
        for (def in HARDCODED_PATCHES) {
            if (def.id !in existingIds) {
                ordered.add(def.copy(writes = def.writes.toMutableList()))
            }
        }

        // 3. Bundled IPS patches
        try {
            for (patch in PatchRepository.loadBundledPatches()) {
                if (patch.id !in existingIds) {
                    ordered.add(patch)
                } else {
                    // Bundled IPS patches are source-controlled resources. Refresh existing
                    // project copies so iterative bundled patch development is not trapped
                    // behind stale serialized write lists.
                    project.patches.find { it.id == patch.id }?.let {
                        it.name = patch.name
                        it.description = patch.description
                        if (it.customItems != patch.customItems) {
                            it.customItems.clear()
                            it.customItems.addAll(patch.customItems.map { customItem -> customItem.copy() })
                            refreshed++
                        }
                        val oldHash = bytesSha256(it.writes.flatMap { write -> write.bytes })
                        val newHash = bytesSha256(patch.writes.flatMap { write -> write.bytes })
                        if (oldHash != newHash || it.writes.size != patch.writes.size) {
                            it.writes.clear()
                            it.writes.addAll(patch.writes.map { write ->
                                PatchWrite(write.offset, write.bytes.toList())
                            })
                            refreshed++
                            if (patch.id == "bundled_spider_ball") {
                                editorLog("[PATCH-SEED] Refreshed Spider Ball bundled writes: ${patch.writes.size} records, sha256=$newHash")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            editorLog("Failed to load bundled patches: ${e.message}")
        }

        // Insert new patches at position 0 so they appear before any user patches
        if (ordered.isNotEmpty()) {
            project.patches.addAll(0, ordered)
            added = ordered.size
        }

        val autoEnabled = enableConfiguredDedicatedEditorPatches()

        if (added > 0 || refreshed > 0 || autoEnabled > 0) { patchVersion++ }
    }

    private fun enableConfiguredDedicatedEditorPatches(): Int {
        var count = 0
        for (patch in project.patches) {
            if (patch.enabled) continue
            if (patch.configType !in DEDICATED_EDITOR_CONFIG_TYPES) continue
            if (patch.configData.isNullOrEmpty()) continue

            patch.enabled = true
            count++
        }
        if (count > 0) {
            dirty = true
            editorLog("[PATCH-SEED] Enabled $count configured enemy/boss patch(es) for export")
        }
        return count
    }

    // ─── Tile selection ─────────────────────────────────────────

    fun selectMetatile(index: Int, gridCols: Int = 32) {
        tilesetSelStart = Pair(index % gridCols, index / gridCols)
        tilesetSelEnd = tilesetSelStart
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        val eff = getEffectiveTileDefault(currentTilesetId, index)
        brush = TileBrush.single(index, eff.blockType, eff.bts)
    }

    fun beginTilesetDrag(col: Int, row: Int) {
        tilesetSelStart = Pair(col, row)
        tilesetSelEnd = Pair(col, row)
    }

    fun updateTilesetDrag(col: Int, row: Int) {
        tilesetSelEnd = Pair(col, row)
    }

    /** Finalize rectangle selection → build multi-tile brush with per-tile defaults. */
    fun endTilesetDrag(gridCols: Int) {
        val s = tilesetSelStart ?: return
        val e = tilesetSelEnd ?: return
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        val c0 = minOf(s.first, e.first)
        val c1 = maxOf(s.first, e.first)
        val r0 = minOf(s.second, e.second)
        val r1 = maxOf(s.second, e.second)
        val tiles = (r0..r1).map { r ->
            (c0..c1).map { c -> r * gridCols + c }
        }

        val btOverrides = mutableMapOf<Long, Int>()
        val btsOverrides = mutableMapOf<Long, Int>()
        for ((ri, row) in tiles.withIndex()) {
            for ((ci, meta) in row.withIndex()) {
                val key = (ri.toLong() shl 32) or (ci.toLong() and 0xFFFFFFFFL)
                val eff = getEffectiveTileDefault(currentTilesetId, meta)
                btOverrides[key] = eff.blockType
                if (eff.bts != 0) btsOverrides[key] = eff.bts
            }
        }
        val primaryIdx = tiles.first().first()
        val primaryEff = getEffectiveTileDefault(currentTilesetId, primaryIdx)
        val fallbackBlockType = primaryEff.blockType

        brush = TileBrush(
            tiles = tiles,
            blockType = fallbackBlockType,
            blockTypeOverrides = btOverrides,
            btsOverrides = btsOverrides
        )
    }

    fun toggleHFlip() {
        if (activePattern?.noFlip == true) return
        brush = brush?.copy(hFlip = !brush!!.hFlip)
    }
    fun toggleVFlip() {
        if (activePattern?.noFlip == true) return
        brush = brush?.copy(vFlip = !brush!!.vFlip)
    }

    fun flipOrCaptureH() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) beginFloatingSelectionFromMapSelection()
        toggleHFlip()
    }
    fun flipOrCaptureV() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) beginFloatingSelectionFromMapSelection()
        toggleVFlip()
    }
    fun rotateOrCapture() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) beginFloatingSelectionFromMapSelection()
        rotateClockwise()
    }

    fun rotateClockwise() {
        if (activePattern?.noFlip == true) return
        val b = brush ?: return
        val oldTiles = b.tiles
        val oldRowCount = oldTiles.size
        if (oldRowCount == 0) return
        val newTiles = oldTiles[0].indices.map { c ->
            oldTiles.indices.reversed().map { r -> oldTiles[r][c] }
        }

        fun remapKeys(old: Map<Long, Int>): Map<Long, Int> = buildMap {
            for ((key, value) in old) {
                val oldR = (key shr 32).toInt()
                val oldC = (key and 0xFFFFFFFFL).toInt()
                val newR = oldC
                val newC = oldRowCount - 1 - oldR
                put((newR.toLong() shl 32) or (newC.toLong() and 0xFFFFFFFFL), value)
            }
        }

        val newFlipOverrides = buildMap {
            for ((key, value) in b.flipOverrides) {
                val oldR = (key shr 32).toInt()
                val oldC = (key and 0xFFFFFFFFL).toInt()
                val newR = oldC
                val newC = oldRowCount - 1 - oldR
                val newKey = (newR.toLong() shl 32) or (newC.toLong() and 0xFFFFFFFFL)
                val h = value and 1
                val v = (value shr 1) and 1
                put(newKey, v or (h shl 1))
            }
        }

        brush = b.copy(
            tiles = newTiles,
            hFlip = !b.vFlip,
            vFlip = b.hFlip,
            blockTypeOverrides = remapKeys(b.blockTypeOverrides),
            btsOverrides = remapKeys(b.btsOverrides),
            flipOverrides = newFlipOverrides
        )
        clampFloatingSelectionToRoom()
    }

    fun setBlockType(type: Int) { brush = brush?.copy(blockType = type) }

    /** Update hover info when mouse moves on map. */
    fun updateHover(bx: Int, by: Int) {
        hoverBlockX = bx; hoverBlockY = by
        hoverTileWord = if (bx >= 0 && by >= 0) {
            if (activeRoomLayer == RoomEditLayer.LAYER2) readLayer2BlockWord(bx, by) else readBlockWord(bx, by)
        } else 0
    }

    /** Sample (eyedropper): pick the tile at (bx, by) from the map as the current brush. */
    fun sampleTile(bx: Int, by: Int, gridCols: Int = 32) {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val word = if (activeRoomLayer == RoomEditLayer.LAYER2) readLayer2BlockWord(bx, by) else readBlockWord(bx, by)
        val metatileIdx = word and 0x3FF
        val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0
        val bt = if (activeRoomLayer == RoomEditLayer.LAYER2) 0x8 else (word shr 12) and 0xF
        val sampledBts = if (activeRoomLayer == RoomEditLayer.LAYER2) 0 else readBts(bx, by)
        val btsMap = if (sampledBts != 0) mapOf(0L to sampledBts) else emptyMap()
        brush = TileBrush(tiles = listOf(listOf(metatileIdx)), blockType = bt, hFlip = hf, vFlip = vf, btsOverrides = btsMap)
        floatingSelection = null
        tilesetSelStart = Pair(metatileIdx % gridCols, metatileIdx / gridCols)
        tilesetSelEnd = tilesetSelStart
        // Capture palette row from the sampled metatile's first sub-tile
        val tg = editorTileGraphics
        if (tg != null) {
            val palRows = tg.getMetatilePalettes(metatileIdx)
            sampledPaletteRow = palRows.firstOrNull() ?: -1
            // Read the pixel's palette color index at the click position within the metatile
            val px = (bx * 16) % 16  // sub-pixel position defaults to top-left
            val py = (by * 16) % 16
            sampledPaletteCol = tg.readMetatilePixel(metatileIdx, px, py).coerceIn(0, 15)
        }
        activeTool = EditorTool.PAINT
    }

    private fun mapSelectionBounds(): MapSelectionBounds? {
        val s = mapSelStart ?: return null
        val e = mapSelEnd ?: return null
        if (workingBlocksWide <= 0 || workingBlocksTall <= 0) return null
        return MapSelectionBounds(
            minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1),
            minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1),
            maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1),
            maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1),
        )
    }

    private fun brushFromMapSelection(bounds: MapSelectionBounds): TileBrush {
        val rows = mutableListOf<List<Int>>()
        val btsMap = mutableMapOf<Long, Int>()
        val btMap = mutableMapOf<Long, Int>()
        val flipMap = mutableMapOf<Long, Int>()
        for (by in bounds.minY..bounds.maxY) {
            val row = mutableListOf<Int>()
            for (bx in bounds.minX..bounds.maxX) {
                val word = readBlockWord(bx, by)
                row.add(word and 0x3FF)
                val r = by - bounds.minY
                val c = bx - bounds.minX
                val key = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                val tileH = (word shr 10) and 1
                val tileV = (word shr 11) and 1
                if (tileH != 0 || tileV != 0) {
                    flipMap[key] = tileH or (tileV shl 1)
                }
                val bt = (word shr 12) and 0xF
                if (r != 0 || c != 0) btMap[key] = bt
                val bts = readBts(bx, by)
                if (bts != 0) btsMap[key] = bts
            }
            rows.add(row)
        }
        val primaryBt = (readBlockWord(bounds.minX, bounds.minY) shr 12) and 0xF
        return TileBrush(
            tiles = rows,
            blockType = primaryBt,
            blockTypeOverrides = btMap,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
    }

    /** Convert the current map selection rectangle into a paint brush. */
    fun captureMapSelection() {
        val bounds = mapSelectionBounds() ?: return
        if (bounds.minX == bounds.maxX && bounds.minY == bounds.maxY) {
            sampleTile(bounds.minX, bounds.minY)
            return
        }
        brush = brushFromMapSelection(bounds)
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        activeTool = EditorTool.PAINT
    }

    /** Copy the current map selection into the brush without changing map tiles. */
    fun copyMapSelectionToBrush(): Boolean {
        val bounds = mapSelectionBounds() ?: return false
        brush = if (bounds.minX == bounds.maxX && bounds.minY == bounds.maxY) {
            val word = readBlockWord(bounds.minX, bounds.minY)
            val bts = readBts(bounds.minX, bounds.minY)
            val btsMap = if (bts != 0) mapOf(0L to bts) else emptyMap()
            TileBrush(
                tiles = listOf(listOf(word and 0x3FF)),
                blockType = (word shr 12) and 0xF,
                hFlip = (word shr 10) and 1 != 0,
                vFlip = (word shr 11) and 1 != 0,
                btsOverrides = btsMap,
            )
        } else {
            brushFromMapSelection(bounds)
        }
        return true
    }

    /** Lift the current selection into a floating preview. This does not modify room tiles. */
    fun beginFloatingSelectionFromMapSelection(): Boolean {
        val bounds = mapSelectionBounds() ?: return false
        brush = if (bounds.minX == bounds.maxX && bounds.minY == bounds.maxY) {
            val word = readBlockWord(bounds.minX, bounds.minY)
            val bts = readBts(bounds.minX, bounds.minY)
            val btsMap = if (bts != 0) mapOf(0L to bts) else emptyMap()
            TileBrush(
                tiles = listOf(listOf(word and 0x3FF)),
                blockType = (word shr 12) and 0xF,
                hFlip = (word shr 10) and 1 != 0,
                vFlip = (word shr 11) and 1 != 0,
                btsOverrides = btsMap,
            )
        } else {
            brushFromMapSelection(bounds)
        }
        mapSelStart = null
        mapSelEnd = null
        activeTool = EditorTool.SELECT
        setFloatingSelectionPosition(bounds.minX, bounds.minY)
        return true
    }

    fun beginFloatingSelectionFromBrushAt(x: Int, y: Int): Boolean {
        if (brush == null) return false
        mapSelStart = null
        mapSelEnd = null
        activeTool = EditorTool.SELECT
        setFloatingSelectionPosition(x, y)
        return true
    }

    private fun clampedFloatingPosition(x: Int, y: Int, b: TileBrush): FloatingMapSelection {
        if (workingBlocksWide <= 0 || workingBlocksTall <= 0) return FloatingMapSelection(0, 0)
        val maxX = (workingBlocksWide - b.cols).coerceAtLeast(0)
        val maxY = (workingBlocksTall - b.rows).coerceAtLeast(0)
        return FloatingMapSelection(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    private fun clampFloatingSelectionToRoom() {
        val floating = floatingSelection ?: return
        val b = brush ?: return
        floatingSelection = clampedFloatingPosition(floating.x, floating.y, b)
    }

    fun setFloatingSelectionPosition(x: Int, y: Int) {
        val b = brush ?: return
        floatingSelection = clampedFloatingPosition(x, y, b)
    }

    fun commitFloatingSelection(): Boolean {
        val floating = floatingSelection ?: return false
        if (brush == null) return false
        beginStroke()
        val changed = paintAt(floating.x, floating.y)
        endStroke()
        floatingSelection = null
        activeTool = EditorTool.SELECT
        return changed
    }

    fun cancelFloatingSelection(): Boolean {
        if (floatingSelection == null) return false
        floatingSelection = null
        return true
    }

    // ─── Project lifecycle ──────────────────────────────────────

    fun initForRom(romPath: String) {
        projectFilePath = romPath.replaceAfterLast('.', "smedit")
        val file = File(projectFilePath)
        if (file.exists()) {
            try {
                project = ProjectFileService.loadProject(file)
                val enabledPatches = project.patches.filter { it.enabled }
                editorLog("Loaded project: ${file.absolutePath} (${project.rooms.size} rooms, ${project.patches.size} patches)")
                if (enabledPatches.isNotEmpty()) {
                    editorLog("  Enabled patches: ${enabledPatches.joinToString { "'${it.name}'" }}")
                }
            } catch (e: Exception) {
                editorLog("Failed to load project: ${e.message}")
                project = SmEditProject(romPath = romPath)
            }
        } else {
            project = SmEditProject(romPath = romPath)
        }
        resetForLoadedProject(seedPatches = true)
    }

    fun initForReadOnlyRom(romPath: String) {
        projectFilePath = ""
        project = SmEditProject(romPath = romPath)
        resetForLoadedProject(seedPatches = true)
    }

    private fun resetForLoadedProject(seedPatches: Boolean) {
        dirty = false
        patchesSeeded = false
        if (seedPatches) {
            seedDefaultPatches(forceRefreshBundled = true)
        }
        brush = null
        activeTool = EditorTool.SELECT
        tileGraphics = null
        editorTileGraphics = null
        editorSelectedMetatile = -1
        tilesetSelStart = null
        tilesetSelEnd = null
        sampledPaletteRow = -1
        sampledPaletteCol = -1
        workingLevelData = null
        originalLevelData = null
        workingBlocksWide = 0
        workingBlocksTall = 0
        currentRoomId = 0
        currentTilesetId = 0
        romTilesetId = 0
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        activeRoomLayer = RoomEditLayer.LAYER1
        currentBgScrolling = 0
        currentArea = 0
        currentIncomingDoorPtrs = emptyList()
        currentAreaRomSaveEntries = emptyMap()
        currentAreaSaveEntryCount = 0
        vanillaSaveIndicesByArea = emptyMap()
        currentStateIndex = -1
        hoverBlockX = -1
        hoverBlockY = -1
        hoverTileWord = 0
        scrollTargetBlockX = -1
        scrollTargetBlockY = -1
        metatileBlockTypePresets = emptyMap()
        undoStack.clear()
        redoStack.clear()
        undoVersion++
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        _workingPlms.clear()
        originalPlmCount = 0
        _workingDoors.clear()
        _workingEnemies.clear()
        _workingScrolls = IntArray(0)
        _originalScrolls = IntArray(0)
        scrollVersion++
        selectedPatternId = null
        activePattern = null
        patternUndoStack.clear()
        patternRedoStack.clear()
        pendingPatEdits.clear()
        pendingPatPositions.clear()
        patternEditVersion++
        patUndoVersion++

        // Clear cached sprite editor state so it reloads from the new ROM/project
        phantoonSprite.invalidate()
        kraidSprite.invalidate()

        _roomEditOrder.clear()
        _editCounter = 0L
        for ((key, edits) in project.rooms) {
            if (!edits.hasEdits) continue
            val rid = key.toIntOrNull(16) ?: continue
            _roomEditOrder[rid] = ++_editCounter
        }

        romVersion++
        paletteVersion++

        migrateTileDefaultsToCore()

        // Merge in library patterns that aren't already in the project
        val existingIds = project.patterns.map { it.id }.toSet()
        val libraryPatterns = PatternLibrary.loadAllPatterns()
        for (pat in libraryPatterns) {
            if (pat.id !in existingIds) {
                project.patterns.add(pat)
            }
        }
        patternVersion++
    }

    internal fun initTestLevel(blocksWide: Int, blocksTall: Int, includeLayer2: Boolean = false) {
        val totalTiles = blocksWide * blocksTall
        val layer1Bytes = totalTiles * 2
        val data = ByteArray(2 + layer1Bytes + totalTiles + if (includeLayer2) layer1Bytes else 0).also {
            it[0] = (layer1Bytes and 0xFF).toByte()
            it[1] = ((layer1Bytes shr 8) and 0xFF).toByte()
        }
        originalLevelData = data.copyOf()
        workingLevelData = data
        workingBlocksWide = blocksWide
        workingBlocksTall = blocksTall
        currentBgScrolling = 0
        activeRoomLayer = RoomEditLayer.LAYER1
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        undoStack.clear()
        redoStack.clear()
        undoVersion++
    }

    internal fun setBrushForTest(b: TileBrush?) { brush = b }
    internal fun setRoomIdForTest(id: Int) { currentRoomId = id }

    // ─── Working level data ─────────────────────────────────────

    fun loadRoom(roomId: Int, romParser: RomParser, room: com.supermetroid.editor.data.Room) {
        currentRoomId = roomId
        romTilesetId = room.tileset
        // A stored state-data change (e.g. from the biome generator or room
        // properties panel) overrides the ROM tileset for editing/rendering.
        val stateDataChange = project.rooms[project.roomKey(roomId)]?.stateDataChange
        currentTilesetId = stateDataChange?.tileset ?: room.tileset
        currentBgScrolling = stateDataChange?.bgScrolling ?: room.bgScrolling
        currentArea = project.rooms[project.roomKey(roomId)]?.roomHeaderChange?.area ?: room.area
        refreshVanillaSaveIndices(romParser)
        currentIncomingDoorPtrs = romParser.findDoorsLeadingTo(roomId)
            .map { it.doorDefPtr }
            .filter { it != 0 }
            .distinct()
        currentAreaSaveEntryCount = romParser.saveEntryCount(currentArea)
        currentAreaRomSaveEntries = (0 until currentAreaSaveEntryCount.coerceAtMost(0x10))
            .mapNotNull { idx -> romParser.readSaveEntry(currentArea, idx)?.let { idx to it } }
            .toMap()
        if (currentBgScrolling != 0) activeRoomLayer = RoomEditLayer.LAYER1
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tileGraphics = tg
        }
        var levelData = romParser.decompressLZ2(room.levelDataPtr)
        val romWidth = room.width
        val romHeight = room.height

        // Check for a stored resize — if the project has different width/height, resize now
        val hc = project.rooms[project.roomKey(roomId)]?.roomHeaderChange
        val effectiveWidth = hc?.width ?: romWidth
        val effectiveHeight = hc?.height ?: romHeight
        if (effectiveWidth != romWidth || effectiveHeight != romHeight) {
            levelData = resizeLevelData(levelData, romWidth, romHeight, effectiveWidth, effectiveHeight)
        }

        originalLevelData = levelData.copyOf()
        workingLevelData = levelData.copyOf()
        workingBlocksWide = effectiveWidth * 16
        workingBlocksTall = effectiveHeight * 16
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1

        // Build metatile → block type presets by scanning room data
        val typeCounts = mutableMapOf<Int, MutableMap<Int, Int>>()
        for (y in 0 until workingBlocksTall) {
            for (x in 0 until workingBlocksWide) {
                val word = readBlockWord(x, y)
                val meta = word and 0x3FF
                val bt = (word shr 12) and 0xF
                if (bt != 0x0 && bt != 0x5 && bt != 0xD) {
                    typeCounts.getOrPut(meta) { mutableMapOf() }.merge(bt, 1, Int::plus)
                }
            }
        }
        val roomPresets = typeCounts.mapValues { (_, counts) ->
            counts.maxByOrNull { it.value }?.key ?: 0x8
        }
        // Merge with existing presets: prefer non-Solid (non-0x8) types
        val merged = metatileBlockTypePresets.toMutableMap()
        for ((meta, bt) in roomPresets) {
            val existing = merged[meta]
            if (existing == null || (bt != 0x8 && existing == 0x8)) {
                merged[meta] = bt
            }
        }
        metatileBlockTypePresets = merged

        undoStack.clear()
        redoStack.clear()
        undoVersion++
        pendingEdits.clear()
        pendingPositions.clear()

        // Load scroll data for this room (resize if dimensions changed)
        val romScrolls = romParser.parseScrollData(room.roomScrollsPtr, romWidth, romHeight)
        _originalScrolls = if (effectiveWidth != romWidth || effectiveHeight != romHeight) {
            resizeScrollGrid(romScrolls, romWidth, romHeight, effectiveWidth, effectiveHeight)
        } else {
            romScrolls
        }
        _workingScrolls = _originalScrolls.copyOf()
        scrollVersion++

        // Load PLMs for this room from all states so rogue door caps (e.g. in Mother Brain / Tourian escape) are visible
        _workingPlms.clear()
        val plms = romParser.getAllPlmEntriesForRoom(roomId)
        _workingPlms.addAll(plms)
        originalPlmCount = plms.size

        // Parse door entries for this room
        doorEntries = romParser.parseDoorList(room.doorOut)

        // Load enemies for this room
        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(room.enemySetPtr))

        val roomKey = project.roomKey(roomId)
        val savedRoom = project.rooms[roomKey]
        if (savedRoom != null) replaySavedRoomEdits(savedRoom, effectiveWidth, roomKey)
        // Bump render version without marking room as user-edited
        _editVersionState.value++
    }

    private fun replaySavedRoomEdits(savedRoom: RoomEdits, effectiveWidth: Int, roomKey: String) {
        // Replay saved tile edits
        if (savedRoom.operations.isNotEmpty()) {
            var count = 0
            for (op in savedRoom.operations) {
                for (edit in op.edits) {
                    applyTileEdit(edit, useNew = true)
                    count++
                }
                undoStack.add(op)
            }
            undoVersion++
            editorLog("Replayed $count saved edits for room 0x$roomKey")
        }
        // Replay saved PLM changes
        for (change in savedRoom.plmChanges) {
            when (change.action) {
                "add" -> _workingPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> _workingPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
            }
        }
        // Replay saved door changes (last change per index wins)
        for (dc in savedRoom.doorChanges) {
            if (dc.doorIndex in 0 until _workingDoors.size) {
                _workingDoors[dc.doorIndex] = RomParser.DoorEntry(
                    destRoomPtr = dc.destRoomPtr,
                    bitflag = dc.bitflag,
                    doorCapCode = dc.doorCapCode,
                    screenX = dc.screenX,
                    screenY = dc.screenY,
                    distFromDoor = dc.distFromDoor,
                    entryCode = dc.entryCode
                )
            }
        }
        // Replay saved scroll changes (use effective width, not ROM width)
        for (sc in savedRoom.scrollChanges) {
            val idx = sc.screenY * effectiveWidth + sc.screenX
            if (idx in _workingScrolls.indices) _workingScrolls[idx] = sc.newValue
        }
        if (savedRoom.scrollChanges.isNotEmpty()) scrollVersion++
        // Replay saved enemy changes (including extra fields)
        for (ec in savedRoom.enemyChanges) {
            when (ec.action) {
                "add" -> _workingEnemies.add(
                    RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                        ec.extra1, ec.extra2, ec.extra3)
                )
                "remove" -> _workingEnemies.removeAll {
                    it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                }
                "update" -> {
                    val idx = _workingEnemies.indexOfFirst {
                        it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                    }
                    if (idx >= 0) {
                        _workingEnemies[idx] = RomParser.EnemyEntry(
                            ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                            ec.extra1, ec.extra2, ec.extra3
                        )
                    }
                }
            }
        }
    }

    /**
     * Switch to a different room state. Reloads enemies, PLMs, and scrolls
     * from the selected state's data pointers. Level data is NOT reloaded
     * (states typically share level data; if they don't, a full room reload is needed).
     */
    fun switchRoomState(stateIndex: Int, romParser: RomParser) {
        val states = romParser.parseRoomStatesWithData(currentRoomId)
        val state = states.getOrNull(stateIndex) ?: return
        currentStateIndex = stateIndex

        // Reload enemies from this state's enemy set pointer
        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(state.enemySetPtr))

        // Reload PLMs from this state's PLM set pointer
        _workingPlms.clear()
        _workingPlms.addAll(romParser.parsePlmSet(state.plmSetPtr))
        originalPlmCount = _workingPlms.size

        // Reload scroll data from this state's scroll pointer
        val room = romParser.readRoomHeader(currentRoomId) ?: return
        val hc = project.rooms[project.roomKey(currentRoomId)]?.roomHeaderChange
        val w = hc?.width ?: room.width
        val h = hc?.height ?: room.height
        _originalScrolls = romParser.parseScrollData(state.scrollPtr, w, h)
        _workingScrolls = _originalScrolls.copyOf()
        scrollVersion++

        // If level data pointer differs from current, reload it
        val currentLevelPtr = room.levelDataPtr
        if (state.levelDataPtr != currentLevelPtr && state.levelDataPtr != 0) {
            var levelData = romParser.decompressLZ2(state.levelDataPtr)
            val effectiveWidth = hc?.width ?: room.width
            val effectiveHeight = hc?.height ?: room.height
            if (effectiveWidth != room.width || effectiveHeight != room.height) {
                levelData = resizeLevelData(levelData, room.width, room.height, effectiveWidth, effectiveHeight)
            }
            originalLevelData = levelData.copyOf()
            workingLevelData = levelData.copyOf()
            workingBlocksWide = effectiveWidth * 16
            workingBlocksTall = effectiveHeight * 16
        }

        editVersion++
        editorLog("Switched to state $stateIndex: ${state.stateInfo.conditionName} (enemies=${_workingEnemies.size}, PLMs=${_workingPlms.size})")
    }

    fun readBlockWord(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    fun readBts(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset >= data.size) return 0
        return data[btsOffset].toInt() and 0xFF
    }

    private fun embeddedLayer2StartOffset(data: ByteArray): Int? {
        if (currentBgScrolling != 0 || workingBlocksWide <= 0 || workingBlocksTall <= 0 || data.size < 2) return null
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val totalBlocks = workingBlocksWide * workingBlocksTall
        val start = 2 + layer1Size + totalBlocks
        val end = start + totalBlocks * 2
        return if (end <= data.size) start else null
    }

    fun canEditEmbeddedLayer2(): Boolean =
        workingLevelData?.let { embeddedLayer2StartOffset(it) != null } == true

    fun readLayer2BlockWord(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return 0
        val start = embeddedLayer2StartOffset(data) ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = start + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    private fun readOriginalBlockWord(bx: Int, by: Int): Int {
        val data = originalLevelData ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    private fun readOriginalBts(bx: Int, by: Int): Int {
        val data = originalLevelData ?: return 0
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset >= data.size) return 0
        return data[btsOffset].toInt() and 0xFF
    }

    private fun writeBts(bx: Int, by: Int, bts: Int) {
        val data = workingLevelData ?: return
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset < data.size) data[btsOffset] = bts.toByte()
    }

    private fun writeBlockWord(bx: Int, by: Int, word: Int) {
        val data = workingLevelData ?: return
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return
        data[offset] = (word and 0xFF).toByte()
        data[offset + 1] = ((word shr 8) and 0xFF).toByte()
    }

    private fun writeLayer2BlockWord(bx: Int, by: Int, word: Int) {
        val data = workingLevelData ?: return
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val start = embeddedLayer2StartOffset(data) ?: return
        val idx = by * workingBlocksWide + bx
        val offset = start + idx * 2
        if (offset + 1 >= data.size) return
        val layer2Word = word and 0x0FFF
        data[offset] = (layer2Word and 0xFF).toByte()
        data[offset + 1] = ((layer2Word shr 8) and 0xFF).toByte()
    }

    private fun applyTileEdit(edit: TileEdit, useNew: Boolean) {
        val word = if (useNew) edit.newBlockWord else edit.oldBlockWord
        val bts = if (useNew) edit.newBts else edit.oldBts
        if (edit.layer == TILE_EDIT_LAYER_2) {
            writeLayer2BlockWord(edit.blockX, edit.blockY, word)
        } else {
            writeBlockWord(edit.blockX, edit.blockY, word)
            writeBts(edit.blockX, edit.blockY, bts)
        }
    }

    // ─── Paint / Erase / Fill ───────────────────────────────────

    fun beginStroke() {
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
    }

    /** Paint the full brush at map position (bx, by). Returns true if anything changed. */
    fun paintAt(bx: Int, by: Int): Boolean {
        if (activeRoomLayer == RoomEditLayer.LAYER2) return paintLayer2At(bx, by)
        val b = brush ?: return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val cellKey = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                if (cellKey in b.skipCells) continue
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= workingBlocksWide || ty >= workingBlocksTall) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPositions.contains(key)) continue
                val oldWord = readBlockWord(tx, ty)
                val newWord = b.blockWordAt(r, c)
                val oldBts = readBts(tx, ty)
                val newBts = b.btsAt(r, c)
                if (oldWord == newWord && oldBts == newBts) continue
                writeBlockWord(tx, ty, newWord)
                writeBts(tx, ty, newBts)
                pendingEdits.add(TileEdit(tx, ty, oldWord, newWord, oldBts, newBts))
                pendingPositions.add(key)
                changed = true
                // Collect pattern PLMs for grouped undo (applied in endStroke)
                val plm = b.plmAt(r, c)
                if (plm != null && plm.first != 0) {
                    val plmId = plm.first
                    val param = plm.second
                    // Remove existing PLMs at same position with same ID
                    val existing = _workingPlms.filter { it.x == tx && it.y == ty && it.id == plmId }
                    for (old in existing) {
                        _workingPlms.remove(old)
                        val rc = PlmChange("remove", old.id, old.x, old.y, old.param)
                        project.getOrCreateRoom(currentRoomId).plmChanges.add(rc)
                        pendingPlmRemoves.add(rc)
                    }
                    val actualParam = autoAssignParam(plmId, param)
                    _workingPlms.add(RomParser.PlmEntry(plmId, tx, ty, actualParam))
                    val addChange = PlmChange("add", plmId, tx, ty, actualParam)
                    project.getOrCreateRoom(currentRoomId).plmChanges.add(addChange)
                    pendingPlmAdds.add(addChange)
                    if (plmId == 0xB76F) {
                        ensureAutoSaveStationSpawn(tx, ty, actualParam and 0xFF)
                    }
                }
            }
        }
        if (changed) editVersion++
        return changed
    }

    private fun paintLayer2At(bx: Int, by: Int): Boolean {
        val b = brush ?: return false
        if (!canEditEmbeddedLayer2()) return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val cellKey = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                if (cellKey in b.skipCells) continue
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= workingBlocksWide || ty >= workingBlocksTall) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPositions.contains(key)) continue
                val oldWord = readLayer2BlockWord(tx, ty)
                val newWord = b.blockWordAt(r, c) and 0x0FFF
                if (oldWord == newWord) continue
                writeLayer2BlockWord(tx, ty, newWord)
                pendingEdits.add(
                    TileEdit(tx, ty, oldWord, newWord, layer = TILE_EDIT_LAYER_2),
                )
                pendingPositions.add(key)
                changed = true
            }
        }
        if (changed) editVersion++
        return changed
    }

    fun eraseAt(bx: Int, by: Int): Boolean {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
        val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
        if (pendingPositions.contains(key)) return false
        if (activeRoomLayer == RoomEditLayer.LAYER2) {
            if (!canEditEmbeddedLayer2()) return false
            val oldWord = readLayer2BlockWord(bx, by)
            val newWord = 0
            if (oldWord == newWord) return false
            writeLayer2BlockWord(bx, by, newWord)
            pendingEdits.add(TileEdit(bx, by, oldWord, newWord, layer = TILE_EDIT_LAYER_2))
            pendingPositions.add(key)
            editVersion++
            return true
        }
        val oldWord = readBlockWord(bx, by)
        val oldBts = readBts(bx, by)
        val newWord = RomConstants.AIR_TILE_WORD
        val newBts = 0
        if (oldWord == newWord && oldBts == newBts) return false
        writeBlockWord(bx, by, newWord)
        writeBts(bx, by, newBts)
        pendingEdits.add(TileEdit(bx, by, oldWord, newWord, oldBts, newBts))
        pendingPositions.add(key)
        editVersion++
        return true
    }

    /** Set block type and BTS for a single tile (used by right-click properties).
     *  Coalesces consecutive changes on the same tile into one undo entry. */
    fun setTileProperties(bx: Int, by: Int, blockType: Int, bts: Int) {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val oldWord = readBlockWord(bx, by)
        val oldBts = readBts(bx, by)
        val metatile = oldWord and 0x3FF
        val flips = oldWord and 0x0C00
        val newWord = metatile or flips or ((blockType and 0xF) shl 12)
        if (oldWord == newWord && oldBts == bts) return

        writeBlockWord(bx, by, newWord)
        writeBts(bx, by, bts)

        val roomOps = project.getOrCreateRoom(currentRoomId).operations
        val lastOp = undoStack.lastOrNull()
        if (lastOp != null && lastOp.edits.size == 1 &&
            lastOp.edits[0].blockX == bx && lastOp.edits[0].blockY == by &&
            lastOp.description.startsWith("Properties")) {
            val origEdit = lastOp.edits[0]
            val merged = EditOperation("Properties ($bx,$by)", listOf(
                TileEdit(bx, by, origEdit.oldBlockWord, newWord, origEdit.oldBts, bts)
            ))
            undoStack[undoStack.lastIndex] = merged
            if (roomOps.isNotEmpty()) roomOps[roomOps.lastIndex] = merged
        } else {
            val op = EditOperation("Properties ($bx,$by)", listOf(
                TileEdit(bx, by, oldWord, newWord, oldBts, bts)
            ))
            undoStack.add(op)
            roomOps.add(op)
        }
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
        // Learn this metatile → block type mapping for future brush presets
        if (blockType != 0x0) {
            val meta = (readBlockWord(bx, by) and 0x3FF)
            metatileBlockTypePresets = metatileBlockTypePresets + (meta to blockType)
        }
    }

    // ─── PLM editing ────────────────────────────────────────────

    fun getPlmsAt(x: Int, y: Int): List<RomParser.PlmEntry> =
        _workingPlms.filter { plm ->
            if (plm.x == x && plm.y == y) return@filter true
            if (RomParser.doorCapColor(plm.id) != null) {
                if (RomParser.doorCapIsHorizontal(plm.id)) {
                    if (plm.y == y && x in plm.x..(plm.x + 3)) return@filter true
                } else {
                    if (plm.x == x && y in plm.y..(plm.y + 3)) return@filter true
                }
            }
            false
        }

    private fun refreshVanillaSaveIndices(romParser: RomParser) {
        if (vanillaSaveIndicesByArea.isNotEmpty()) return
        val byArea = mutableMapOf<Int, MutableSet<Int>>()
        val repository = RoomRepository()
        for (info in repository.getAllRooms()) {
            val room = romParser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
            val plms = romParser.parsePlmSet(room.plmSetPtr)
            for (plm in plms) {
                if (plm.id == 0xB76F) {
                    byArea.getOrPut(room.area) { mutableSetOf() }.add(plm.param and 0xFF)
                }
            }
        }
        vanillaSaveIndicesByArea = byArea.mapValues { it.value.toSet() }
    }

    private fun saveSpawnOverride(area: Int, saveIndex: Int): SaveStationSpawnChange? {
        project.rooms[project.roomKey(currentRoomId)]?.saveStationSpawns
            ?.lastOrNull { it.area == area && it.saveIndex == saveIndex }
            ?.let { return it }
        return project.rooms.values
            .asSequence()
            .flatMap { it.saveStationSpawns.asSequence() }
            .lastOrNull { it.area == area && it.saveIndex == saveIndex }
    }

    fun effectiveSaveStationSpawn(area: Int, saveIndex: Int, romParser: RomParser): EffectiveSaveStationSpawn? {
        val override = saveSpawnOverride(area, saveIndex)
        val romEntry = romParser.readSaveEntry(area, saveIndex)
        if (override != null) {
            return EffectiveSaveStationSpawn(
                area = override.area,
                saveIndex = override.saveIndex,
                roomId = override.roomId,
                doorPtr = override.doorPtr,
                scrollX = override.scrollX,
                scrollY = override.scrollY,
                samusY = override.samusY,
                samusX = override.samusX,
                pcOffset = romEntry?.pcOffset,
                source = if (override.autoDerived) "Auto" else "Override",
            )
        }
        return romEntry?.let {
            EffectiveSaveStationSpawn(
                area = area,
                saveIndex = saveIndex,
                roomId = it.roomId,
                doorPtr = it.doorPtr,
                scrollX = it.scrollX,
                scrollY = it.scrollY,
                samusY = it.samusY,
                samusX = it.samusX,
                pcOffset = it.pcOffset,
                source = "ROM",
            )
        }
    }

    private fun deriveSaveStationSpawn(x: Int, y: Int, saveIndex: Int): SaveStationSpawnChange {
        val scrollX = (x / 16) * 256
        val scrollY = (y / 16) * 256
        val existing = currentAreaRomSaveEntries[saveIndex]?.takeIf { it.roomId == currentRoomId }
        val doorPtr = existing?.doorPtr
            ?: currentIncomingDoorPtrs.firstOrNull()
            ?: doorEntries.firstOrNull { it.doorDefPtr != 0 }?.doorDefPtr
            ?: 0
        return SaveStationSpawnChange(
            area = currentArea,
            saveIndex = saveIndex,
            roomId = currentRoomId,
            doorPtr = doorPtr,
            scrollX = scrollX.toUnsigned16(),
            scrollY = scrollY.toUnsigned16(),
            samusY = (y * 16 - scrollY - 24).toUnsigned16(),
            samusX = (x * 16 - scrollX - 112).toUnsigned16(),
            autoDerived = true,
        )
    }

    private fun upsertSaveStationSpawn(change: SaveStationSpawnChange) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.saveStationSpawns.removeAll { it.area == change.area && it.saveIndex == change.saveIndex }
        roomEdits.saveStationSpawns.add(change)
        dirty = true
        editVersion++
    }

    private fun ensureAutoSaveStationSpawn(x: Int, y: Int, saveIndex: Int) {
        val existing = saveSpawnOverride(currentArea, saveIndex)
        if (existing != null && !existing.autoDerived) return
        upsertSaveStationSpawn(deriveSaveStationSpawn(x, y, saveIndex))
    }

    private fun cleanupSaveStationSpawnIfUnreferenced(saveIndex: Int) {
        if (_workingPlms.any { it.id == 0xB76F && (it.param and 0xFF) == saveIndex }) return
        val roomEdits = project.rooms[project.roomKey(currentRoomId)] ?: return
        val removed = roomEdits.saveStationSpawns.removeAll {
            it.area == currentArea && it.saveIndex == saveIndex
        }
        if (removed) {
            dirty = true
            editVersion++
        }
    }

    fun resetSaveStationSpawnToAuto(plm: RomParser.PlmEntry) {
        if (plm.id != 0xB76F) return
        upsertSaveStationSpawn(deriveSaveStationSpawn(plm.x, plm.y, plm.param and 0xFF))
    }

    fun updateSaveStationSpawnPosition(area: Int, saveIndex: Int, samusX: Int, samusY: Int, romParser: RomParser) {
        val base = effectiveSaveStationSpawn(area, saveIndex, romParser) ?: return
        upsertSaveStationSpawn(
            SaveStationSpawnChange(
                area = area,
                saveIndex = saveIndex,
                roomId = base.roomId,
                doorPtr = base.doorPtr,
                scrollX = base.scrollX,
                scrollY = base.scrollY,
                samusY = samusY.toUnsigned16(),
                samusX = samusX.toUnsigned16(),
                autoDerived = false,
            ),
        )
    }

    fun updateSaveStationSpawnScroll(area: Int, saveIndex: Int, scrollX: Int, scrollY: Int, romParser: RomParser) {
        val base = effectiveSaveStationSpawn(area, saveIndex, romParser) ?: return
        upsertSaveStationSpawn(
            SaveStationSpawnChange(
                area = area,
                saveIndex = saveIndex,
                roomId = base.roomId,
                doorPtr = base.doorPtr,
                scrollX = scrollX.toUnsigned16(),
                scrollY = scrollY.toUnsigned16(),
                samusY = base.samusY,
                samusX = base.samusX,
                autoDerived = false,
            ),
        )
    }

    fun activeRoomAreaForEditing(): Int = currentArea

    private fun autoAssignParam(plmId: Int, param: Int): Int = when {
        param == 0 && isEditorItemPlm(plmId) -> {
            val usedIndices = mutableSetOf<Int>()
            // Replay add/remove history to find NET used params (not ghost entries)
            for ((_, roomEdits) in project.rooms) {
                val netItems = mutableListOf<Triple<Int, Int, Int>>() // (plmId, xy, param)
                for (change in roomEdits.plmChanges) {
                    val xy = (change.x shl 16) or change.y
                    if (change.action == "add") {
                        netItems.add(Triple(change.plmId, xy, change.param))
                    } else if (change.action == "remove") {
                        netItems.removeAll { it.first == change.plmId && it.second == xy }
                    }
                }
                for ((_, _, p) in netItems) {
                    if (p > 0) usedIndices.add(p)
                }
            }
            // Include vanilla item params from current room
            for (plm in _workingPlms) {
                if (isEditorItemPlm(plm.id) && plm.param > 0) usedIndices.add(plm.param)
            }
            // Vanilla items use 0x00-0x50; search 0x51-0x1FF (431 slots)
            var idx = 0x51
            while (idx in usedIndices && idx <= 0x1FF) idx++
            if (idx > 0x1FF) {
                editorLog("WARN: item collection bit pool exhausted (>431 items)")
                idx = 0x200
            }
            idx
        }
        plmId == 0xB76F && param == 0x8000 -> {
            val usedSaveIndices = mutableSetOf<Int>()
            usedSaveIndices.addAll(vanillaSaveIndicesByArea[currentArea].orEmpty())
            for (roomEdits in project.rooms.values) {
                for (spawn in roomEdits.saveStationSpawns) {
                    if (spawn.area == currentArea) usedSaveIndices.add(spawn.saveIndex)
                }
            }
            for (plm in _workingPlms) {
                if (plm.id == 0xB76F) usedSaveIndices.add(plm.param and 0xFF)
            }
            for ((_, roomEdits) in project.rooms) {
                for (change in roomEdits.plmChanges) {
                    if (change.action == "add" && change.plmId == 0xB76F) usedSaveIndices.add(change.param and 0xFF)
                }
            }
            val maxSaveIndex = (currentAreaSaveEntryCount - 1).coerceIn(0, 0x0F)
            var idx = 0
            while (idx in usedSaveIndices && idx <= maxSaveIndex) idx++
            if (idx > maxSaveIndex) {
                editorLog("WARN: no unused AreaSave slot for area $currentArea; reusing save index $maxSaveIndex")
                idx = maxSaveIndex
            }
            0x8000 or idx
        }
        else -> param
    }

    fun addPlm(plmId: Int, x: Int, y: Int, param: Int) {
        val existing = _workingPlms.filter { it.x == x && it.y == y && it.id == plmId }
        val removedChanges = mutableListOf<PlmChange>()
        for (old in existing) {
            _workingPlms.remove(old)
            val rc = PlmChange("remove", old.id, old.x, old.y, old.param)
            project.getOrCreateRoom(currentRoomId).plmChanges.add(rc)
            removedChanges.add(rc)
        }

        val actualParam = autoAssignParam(plmId, param)
        _workingPlms.add(RomParser.PlmEntry(plmId, x, y, actualParam))
        val addChange = PlmChange("add", plmId, x, y, actualParam)
        project.getOrCreateRoom(currentRoomId).plmChanges.add(addChange)
        if (plmId == 0xB76F) {
            ensureAutoSaveStationSpawn(x, y, actualParam and 0xFF)
        }

        val name = customItemNameForPlm(plmId) ?: RomParser.plmDisplayName(plmId)
        val op = EditOperation("Add $name ($x,$y)", plmAdds = listOf(addChange), plmRemoves = removedChanges)
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    // ─── Custom scroll command management ─────────────────────

    /** Create a new custom scroll command set, returns the command ID. */
    fun createScrollCommand(entries: List<ScrollCommand>): String {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val id = "cmd_${roomEdits.customScrollCommands.size}"
        roomEdits.customScrollCommands[id] = entries.toMutableList()
        dirty = true
        return id
    }

    /** Update an existing custom scroll command set. */
    fun updateScrollCommand(cmdId: String, entries: List<ScrollCommand>) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.customScrollCommands[cmdId] = entries.toMutableList()
        dirty = true
        editVersion++
    }

    /** Get custom scroll commands for a command ID, or null if not found. */
    fun getScrollCommand(cmdId: String): List<ScrollCommand>? {
        val roomEdits = project.rooms[project.roomKey(currentRoomId)] ?: return null
        return roomEdits.customScrollCommands[cmdId]
    }

    /** Add a B703 scroll trigger with a new custom command set. Returns the PLM param (command ID encoded). */
    fun addScrollTriggerWithCommands(x: Int, y: Int, entries: List<ScrollCommand>) {
        val cmdId = createScrollCommand(entries)
        // Use a custom param range starting from 0x0100 for custom commands
        // Format: 0xCC00 | cmdIndex (to distinguish from ROM pointers)
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val cmdIndex = roomEdits.customScrollCommands.keys.indexOf(cmdId)
        val customParam = 0xCC00 or (cmdIndex and 0xFF)
        addPlm(0xB703, x, y, customParam)
    }

    fun removePlm(x: Int, y: Int, plmId: Int) {
        val removed = _workingPlms.filter { it.x == x && it.y == y && it.id == plmId }
        _workingPlms.removeAll { it.x == x && it.y == y && it.id == plmId }
        val changes = removed.map { PlmChange("remove", it.id, it.x, it.y, it.param) }
        for (c in changes) project.getOrCreateRoom(currentRoomId).plmChanges.add(c)
        for (old in removed) {
            if (old.id == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(old.param and 0xFF)
        }

        val name = customItemNameForPlm(plmId) ?: RomParser.plmDisplayName(plmId)
        val op = EditOperation("Remove $name ($x,$y)", plmRemoves = changes)
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    // ─── Enemy editing ────────────────────────────────────────

    fun getEnemiesNear(pixelX: Int, pixelY: Int, radius: Int = 16): List<RomParser.EnemyEntry> =
        _workingEnemies.filter { kotlin.math.abs(it.x - pixelX) < radius && kotlin.math.abs(it.y - pixelY) < radius }

    fun addEnemy(enemyId: Int, pixelX: Int, pixelY: Int, initParam: Int = 0, properties: Int = 0x2800) {
        val entry = RomParser.EnemyEntry(enemyId, pixelX, pixelY, initParam, properties)
        _workingEnemies.add(entry)
        val ec = EnemyChange("add", enemyId, pixelX, pixelY, initParam, properties)
        project.getOrCreateRoom(currentRoomId).enemyChanges.add(ec)

        val name = RomParser.enemyName(enemyId)
        val op = EditOperation("Add $name", enemyAdds = listOf(ec))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    fun removeEnemy(enemy: RomParser.EnemyEntry) {
        _workingEnemies.removeAll { it.id == enemy.id && it.x == enemy.x && it.y == enemy.y }
        val ec = EnemyChange("remove", enemy.id, enemy.x, enemy.y, enemy.initParam, enemy.properties,
            enemy.extra1, enemy.extra2, enemy.extra3, origX = enemy.x, origY = enemy.y)
        project.getOrCreateRoom(currentRoomId).enemyChanges.add(ec)

        val name = RomParser.enemyName(enemy.id)
        val op = EditOperation("Remove $name", enemyRemoves = listOf(ec))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    fun updateEnemy(old: RomParser.EnemyEntry, new: RomParser.EnemyEntry) {
        val idx = _workingEnemies.indexOfFirst { it.id == old.id && it.x == old.x && it.y == old.y }
        if (idx < 0) return
        _workingEnemies[idx] = new
        val newEc = EnemyChange("update", new.id, new.x, new.y, new.initParam, new.properties,
            new.extra1, new.extra2, new.extra3, origX = old.x, origY = old.y)
        project.getOrCreateRoom(currentRoomId).enemyChanges.add(newEc)

        val oldEc = EnemyChange("update", old.id, old.x, old.y, old.initParam, old.properties,
            old.extra1, old.extra2, old.extra3, origX = old.x, origY = old.y)
        val name = RomParser.enemyName(new.id)
        val op = EditOperation("Update $name", enemyUpdates = listOf(EnemyUpdate(oldEc, newEc)))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    // ─── Door editing ──────────────────────────────────────────

    fun updateDoor(index: Int, entry: RomParser.DoorEntry) {
        if (index < 0 || index >= _workingDoors.size) return
        _workingDoors[index] = entry
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val dc = DoorChange(
            doorIndex = index,
            destRoomPtr = entry.destRoomPtr,
            bitflag = entry.bitflag,
            doorCapCode = entry.doorCapCode,
            screenX = entry.screenX,
            screenY = entry.screenY,
            distFromDoor = entry.distFromDoor,
            entryCode = entry.entryCode
        )
        roomEdits.doorChanges.removeAll { it.doorIndex == index }
        roomEdits.doorChanges.add(dc)
        dirty = true
        editVersion++
    }

    // ─── Scroll editing ─────────────────────────────────────────

    fun setScroll(screenX: Int, screenY: Int, newValue: Int, roomWidth: Int) {
        val idx = screenY * roomWidth + screenX
        if (idx !in _workingScrolls.indices) return
        val oldValue = _workingScrolls[idx]
        if (oldValue == newValue) return
        _workingScrolls[idx] = newValue
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.scrollChanges.removeAll { it.screenX == screenX && it.screenY == screenY }
        if (newValue != _originalScrolls[idx]) {
            roomEdits.scrollChanges.add(ScrollChange(screenX, screenY, _originalScrolls[idx], newValue))
        }

        val scrollName = when (newValue) { 0 -> "Red"; 1 -> "Blue"; 2 -> "Green"; else -> "0x${newValue.toString(16)}" }
        val sc = ScrollChange(screenX, screenY, oldValue, newValue)
        val op = EditOperation("Scroll ($screenX,$screenY) → $scrollName", scrollEdits = listOf(sc))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        scrollVersion++
        dirty = true
    }

    // ─── Room resize ─────────────────────────────────────────────

    /**
     * Resize the current room from (oldW x oldH) to (newW x newH) screens.
     * Copies existing tile data, BTS, and L2 into the new dimensions.
     * New columns/rows are filled with empty tiles (air). Truncated areas are discarded.
     */
    fun resizeRoom(
        oldWidth: Int, oldHeight: Int,
        newWidth: Int, newHeight: Int,
    ) {
        val data = workingLevelData ?: return
        if (newWidth == oldWidth && newHeight == oldHeight) return
        if (newWidth !in 1..15 || newHeight !in 1..15) return

        val newData = resizeLevelData(data, oldWidth, oldHeight, newWidth, newHeight)

        // Update working state
        workingLevelData = newData
        originalLevelData = newData.copyOf()
        workingBlocksWide = newWidth * 16
        workingBlocksTall = newHeight * 16

        // Resize scroll data: one byte per screen, default Blue (1)
        val newScrolls = resizeScrollGrid(_workingScrolls, oldWidth, oldHeight, newWidth, newHeight)
        _originalScrolls = newScrolls.copyOf()
        _workingScrolls = newScrolls
        scrollVersion++

        // Update room header with new dimensions
        val change = (project.getOrCreateRoom(currentRoomId).roomHeaderChange ?: RoomHeaderChange())
            .copy(width = newWidth, height = newHeight)
        setRoomHeaderChange(change)

        // Clear tile edit history (no longer valid for new dimensions)
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.operations.clear()
        roomEdits.scrollChanges.clear()
        undoStack.clear()
        redoStack.clear()
        undoVersion++

        // Force re-render
        editVersion++
        dirty = true
    }

    // ─── FX editing ─────────────────────────────────────────────

    fun setFxChange(change: FxChange) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.fxChange = change
        dirty = true
        editVersion++
    }

    // ─── Room header editing (area, map position, scrollers, CRE) ──

    fun setRoomHeaderChange(change: RoomHeaderChange) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.roomHeaderChange = change
        dirty = true
        editVersion++
    }

    fun setRoomHeaderChangeForId(roomAddress: Int, change: RoomHeaderChange) {
        val roomEdits = project.getOrCreateRoom(roomAddress)
        roomEdits.roomHeaderChange = change
        dirty = true
        editVersion++
    }

    /**
     * Return a copy of [room] with any project header changes (width, height,
     * area, etc.) and the state-data tileset override applied.
     */
    fun applyHeaderChanges(room: com.supermetroid.editor.data.Room): com.supermetroid.editor.data.Room {
        val edits = project.rooms[project.roomKey(room.roomId)] ?: return room
        val hc = edits.roomHeaderChange
        val sd = edits.stateDataChange
        val tileset = sd?.tileset ?: room.tileset
        val bgScrolling = sd?.bgScrolling ?: room.bgScrolling
        if (hc == null && tileset == room.tileset && bgScrolling == room.bgScrolling) return room
        return room.copy(
            width = hc?.width ?: room.width,
            height = hc?.height ?: room.height,
            area = hc?.area ?: room.area,
            mapX = hc?.mapX ?: room.mapX,
            mapY = hc?.mapY ?: room.mapY,
            tileset = tileset,
            bgScrolling = bgScrolling,
        )
    }

    // ─── State data editing (tileset, music, BG scrolling) ──────

    fun setStateDataChange(change: StateDataChange) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.stateDataChange = change
        change.bgScrolling?.let { currentBgScrolling = it }
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1
        dirty = true
        editVersion++
    }

    /** Flood fill: replace all connected tiles matching the one at (bx, by) with brush. */
    fun floodFill(bx: Int, by: Int): Boolean {
        val b = brush ?: return false
        if (b.cols != 1 || b.rows != 1) return false  // fill only works with 1×1 brush
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
        if (activeRoomLayer == RoomEditLayer.LAYER2) return floodFillLayer2(bx, by, b)
        val targetWord = readBlockWord(bx, by)
        val newWord = b.blockWordAt(0, 0)
        val newBts = b.btsAt(0, 0)
        if (targetWord == newWord) return false

        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        var changed = false
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            if (cx < 0 || cy < 0 || cx >= workingBlocksWide || cy >= workingBlocksTall) continue
            val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (visited.contains(key)) continue
            visited.add(key)
            if (readBlockWord(cx, cy) != targetWord) continue
            writeBlockWord(cx, cy, newWord)
            val oldBts = readBts(cx, cy)
            writeBts(cx, cy, newBts)
            pendingEdits.add(TileEdit(cx, cy, targetWord, newWord, oldBts, newBts))
            changed = true
            queue.add(Pair(cx - 1, cy))
            queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1))
            queue.add(Pair(cx, cy + 1))
        }
        return changed
    }

    private fun floodFillLayer2(bx: Int, by: Int, b: TileBrush): Boolean {
        if (!canEditEmbeddedLayer2()) return false
        val targetWord = readLayer2BlockWord(bx, by)
        val newWord = b.blockWordAt(0, 0) and 0x0FFF
        if (targetWord == newWord) return false

        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        var changed = false
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            if (cx < 0 || cy < 0 || cx >= workingBlocksWide || cy >= workingBlocksTall) continue
            val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (visited.contains(key)) continue
            visited.add(key)
            if (readLayer2BlockWord(cx, cy) != targetWord) continue
            writeLayer2BlockWord(cx, cy, newWord)
            pendingEdits.add(TileEdit(cx, cy, targetWord, newWord, layer = TILE_EDIT_LAYER_2))
            changed = true
            queue.add(Pair(cx - 1, cy))
            queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1))
            queue.add(Pair(cx, cy + 1))
        }
        return changed
    }

    fun endStroke() {
        if (pendingEdits.isEmpty() && pendingPlmAdds.isEmpty()) return
        val layerLabel = if (pendingEdits.any { it.layer == TILE_EDIT_LAYER_2 }) "Layer 2 " else ""
        val desc = if (floatingSelection != null) "${layerLabel}Place selection ${pendingEdits.size} tile(s)" else when (activeTool) {
            EditorTool.PAINT -> "${layerLabel}Paint ${pendingEdits.size} tile(s)"
            EditorTool.FILL -> "${layerLabel}Fill ${pendingEdits.size} tile(s)"
            EditorTool.ERASE -> "${layerLabel}Erase ${pendingEdits.size} tile(s)"
            EditorTool.SAMPLE -> "Sample"
            EditorTool.SELECT -> "Select"
        }
        pushEditOperation(
            EditOperation(
                desc, pendingEdits.toList(),
                plmAdds = pendingPlmAdds.toList(),
                plmRemoves = pendingPlmRemoves.toList(),
            ),
        )
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
    }

    /**
     * Apply a batch of tile edits as a single undoable operation.
     * Used by the biome generator to replace a whole room in one step.
     */
    fun applyBulkEdits(description: String, edits: List<TileEdit>) {
        if (edits.isEmpty()) return
        for (e in edits) {
            applyTileEdit(e, useNew = true)
        }
        pushEditOperation(EditOperation(description, edits))
    }

    data class BulkBiomeResult(
        val generatedRooms: Int,
        val skippedRooms: Int,
        val changedTiles: Int,
        val manualSkippedRooms: Int = 0,
    )

    /**
     * Run the biome generator on the working room and apply the result as one
     * undoable operation. Returns the number of tiles changed.
     */
    fun generateBiome(
        rules: BiomeRules,
        profile: TilesetProfile,
        seed: Long,
        keepLandingSiteShipClear: Boolean = true,
        romParser: RomParser? = null,
        wfcOptions: WfcOptions = WfcOptions(),
    ): Int {
        val w = workingBlocksWide
        val h = workingBlocksTall
        if (w <= 0 || h <= 0) return 0

        val n = w * h
        val origWords = IntArray(n) { readBlockWord(it % w, it / w) }
        val origBts = IntArray(n) { readBts(it % w, it / w) }
        val options = buildBiomeGenerationOptions(
            keepLandingSiteShipClear,
            rules,
            romParser,
            wfcOptions,
            origWords,
            origBts,
        )
        val result = BiomeGenerator(rules, profile, seed, options).generate(w, h, origWords, origBts)

        val edits = ArrayList<TileEdit>()
        for (i in 0 until n) {
            if (result.words[i] != origWords[i] || result.bts[i] != origBts[i]) {
                edits.add(TileEdit(i % w, i / w, origWords[i], result.words[i], origBts[i], result.bts[i]))
            }
        }
        val scrollEdits = buildGeneratedRoomScrollResetEdits()
        val scrollPlmRemoves = buildScrollPlmRemovals()
        applyGeneratedRoomOperation(
            "Generate biome (${rules.style.displayName}, seed $seed)",
            edits,
            scrollEdits,
            scrollPlmRemoves,
        )
        return edits.size
    }

    fun generateBiomeForAllRooms(
        rules: BiomeRules,
        theme: BiomeTheme,
        seed: Long,
        romParser: RomParser,
        wfcOptions: WfcOptions = WfcOptions(),
        omitSpecialRooms: Boolean = true,
    ): BulkBiomeResult {
        val repository = RoomRepository()
        val roomInfos = repository.getAllRooms()
        val headers = roomInfos
            .mapNotNull { info -> romParser.readRoomHeader(info.getRoomIdAsInt()) }
            .filter { it.levelDataPtr != 0 && it.width > 0 && it.height > 0 }
        val headersById = headers.associateBy { it.roomId }

        prepareBulkTheme(theme, romParser)
        val wfcSampleCache = mutableMapOf<Pair<Int, Int>, List<WfcSample>>()
        var generated = 0
        var skipped = 0
        var manualSkipped = 0
        var changedTiles = 0

        for (roomInfo in roomInfos.sortedBy { it.getRoomIdAsInt() }) {
            val roomId = roomInfo.getRoomIdAsInt()
            val roomKey = project.roomKey(roomId)
            val romRoom = headersById[roomId]
            if (romRoom == null) {
                skipped++
                continue
            }
            if (omitSpecialRooms && shouldSkipBulkBiomeRoom(roomInfo, romRoom)) {
                skipped++
                continue
            }
            if (hasManualBiomeBlockingEdits(project.rooms[roomKey])) {
                skipped++
                manualSkipped++
                continue
            }

            val stripped = stripGeneratedBiomeEdits(project.rooms[roomKey])
            val effectiveRoom = applyHeaderChanges(romRoom)
            val targetTileset = theme.tilesetId ?: project.rooms[roomKey]?.stateDataChange?.tileset ?: effectiveRoom.tileset
            val profile = TilesetProfileCache.getOrLearn(romParser, headers, targetTileset)
            val grids = buildEffectiveRoomGrids(romParser, romRoom, effectiveRoom)
            if (grids == null) {
                skipped++
                continue
            }
            val options = buildBiomeGenerationOptionsForRoom(
                roomId = roomId,
                width = grids.width,
                height = grids.height,
                originalWords = grids.words,
                originalBts = grids.bts,
                rules = rules,
                romParser = romParser,
                wfcOptions = wfcOptions,
                wfcSamples = if (rules.algorithm == StructureAlgorithm.WFC) {
                    wfcSampleCache.getOrPut(roomId to targetTileset) {
                        buildWfcSamples(romParser, roomId, targetTileset)
                    }
                } else {
                    emptyList()
                },
            )
            val roomSeed = seed xor (roomId.toLong() * -7046029254386353131L)
            val generatedLevel = BiomeGenerator(rules, profile, roomSeed, options)
                .generate(grids.width, grids.height, grids.words, grids.bts)

            val edits = ArrayList<TileEdit>()
            for (i in generatedLevel.words.indices) {
                if (generatedLevel.words[i] != grids.words[i] || generatedLevel.bts[i] != grids.bts[i]) {
                    edits.add(
                        TileEdit(
                            i % grids.width,
                            i / grids.width,
                            grids.words[i],
                            generatedLevel.words[i],
                            grids.bts[i],
                            generatedLevel.bts[i],
                        )
                    )
                }
            }
            val scrollEdits = buildGeneratedRoomScrollResetEditsForRoom(romParser, roomId, romRoom, effectiveRoom)
            val scrollPlmRemoves = buildScrollPlmRemovalsForRoom(romParser, roomId)

            val roomEdits = project.getOrCreateRoom(roomId)
            val stateDataBefore = roomEdits.stateDataChange
            val fxBefore = roomEdits.fxChange
            applyBulkBiomeThemeToRoom(roomEdits, romRoom, effectiveRoom, theme)
            val stateDataAfter = roomEdits.stateDataChange
            val fxAfter = roomEdits.fxChange
            val hasGeneratedChanges = edits.isNotEmpty() ||
                scrollEdits.isNotEmpty() ||
                scrollPlmRemoves.isNotEmpty() ||
                stateDataBefore != stateDataAfter ||
                fxBefore != fxAfter ||
                stripped
            if (!hasGeneratedChanges) {
                if (!roomEdits.hasEdits) project.rooms.remove(project.roomKey(roomId))
                continue
            }
            recordGeneratedBiomeOperation(
                roomEdits,
                roomId,
                rules,
                seed,
                edits,
                scrollEdits,
                scrollPlmRemoves,
                stateDataBefore,
                stateDataAfter,
                fxBefore,
                fxAfter,
            )
            if (!roomEdits.hasEdits) project.rooms.remove(project.roomKey(roomId))
            generated++
            changedTiles += edits.size
        }

        val activeRoom = if (currentRoomId != 0) romParser.readRoomHeader(currentRoomId) else null
        if (activeRoom != null) loadRoom(currentRoomId, romParser, activeRoom)
        dirty = true
        editVersion++
        editorLog(
            "Generated biome for $generated rooms ($changedTiles tile edits), skipped $skipped rooms" +
                if (manualSkipped > 0) " ($manualSkipped with manual edits)" else ""
        )
        return BulkBiomeResult(generated, skipped, changedTiles, manualSkipped)
    }

    fun resetGeneratedBiomeRooms(romParser: RomParser): BulkBiomeResult {
        var resetRooms = 0
        var removedTiles = 0
        val keys = project.rooms.keys.toList()
        for (key in keys) {
            val roomEdits = project.rooms[key] ?: continue
            val oldTileCount = roomEdits.operations
                .filter { isGeneratedBiomeOperation(it) }
                .sumOf { it.edits.size }
            if (!stripGeneratedBiomeEdits(roomEdits)) continue
            removedTiles += oldTileCount
            resetRooms++
            if (!roomEdits.hasEdits) project.rooms.remove(key)
        }
        val activeRoom = if (currentRoomId != 0) romParser.readRoomHeader(currentRoomId) else null
        if (activeRoom != null) loadRoom(currentRoomId, romParser, activeRoom)
        if (resetRooms > 0) {
            dirty = true
            editVersion++
        }
        editorLog("Reset generated biome edits in $resetRooms rooms ($removedTiles tile edits removed)")
        return BulkBiomeResult(resetRooms, 0, removedTiles)
    }

    private fun buildEffectiveRoomGrids(romParser: RomParser, romRoom: Room, effectiveRoom: Room): RoomGrids? {
        val data = runCatching { romParser.decompressLZ2(romRoom.levelDataPtr) }.getOrNull() ?: return null
        val effectiveData = if (effectiveRoom.width != romRoom.width || effectiveRoom.height != romRoom.height) {
            resizeLevelData(data, romRoom.width, romRoom.height, effectiveRoom.width, effectiveRoom.height)
        } else {
            data.copyOf()
        }
        val width = effectiveRoom.width * 16
        val height = effectiveRoom.height * 16
        val grid = LevelGrid.parse(effectiveData, width, height) ?: return null
        val words = IntArray(width * height)
        val bts = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                words[i] = grid.word(x, y)
                bts[i] = grid.bts(x, y)
            }
        }
        val roomEdits = project.rooms[project.roomKey(romRoom.roomId)]
        if (roomEdits != null) {
            for (op in roomEdits.operations) {
                for (edit in op.edits) {
                    if (edit.layer != TILE_EDIT_LAYER_1) continue
                    if (edit.blockX !in 0 until width || edit.blockY !in 0 until height) continue
                    val i = edit.blockY * width + edit.blockX
                    words[i] = edit.newBlockWord
                    bts[i] = edit.newBts
                }
            }
        }
        return RoomGrids(width, height, words, bts)
    }

    private fun recordGeneratedBiomeOperation(
        roomEdits: RoomEdits,
        roomId: Int,
        rules: BiomeRules,
        seed: Long,
        edits: List<TileEdit>,
        scrollEdits: List<ScrollChange>,
        scrollPlmRemoves: List<PlmChange>,
        stateDataBefore: StateDataChange?,
        stateDataAfter: StateDataChange?,
        fxBefore: FxChange?,
        fxAfter: FxChange?,
    ) {
        val op = EditOperation(
            "$GENERATED_BIOME_PREFIX ${rules.style.displayName}, seed $seed",
            edits,
            plmRemoves = scrollPlmRemoves,
            scrollEdits = scrollEdits,
            stateDataBefore = stateDataBefore,
            stateDataAfter = stateDataAfter,
            fxBefore = fxBefore,
            fxAfter = fxAfter,
        )
        if (
            edits.isNotEmpty() ||
            scrollEdits.isNotEmpty() ||
            scrollPlmRemoves.isNotEmpty() ||
            stateDataBefore != stateDataAfter ||
            fxBefore != fxAfter
        ) {
            roomEdits.operations.add(op)
        }
        for (sc in scrollEdits) {
            roomEdits.scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
            if (sc.newValue != sc.oldValue) roomEdits.scrollChanges.add(sc)
        }
        for (plm in scrollPlmRemoves) {
            roomEdits.plmChanges.removeAll {
                it.action == plm.action && it.plmId == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param
            }
            roomEdits.plmChanges.add(plm)
        }
        _roomEditOrder[roomId] = ++_editCounter
    }

    private fun prepareBulkTheme(theme: BiomeTheme, romParser: RomParser) {
        val targetTileset = theme.tilesetId ?: return
        val effectId = theme.paletteEffectId ?: return
        val effect = PaletteEffects.findEffect(effectId) ?: return
        val colors = readTilesetPalette(targetTileset, romParser, includeOverride = false) ?: return
        effect.apply(colors)
        saveTilesetPaletteFromColors(targetTileset, colors)
        setPaletteEffect("tileset:$targetTileset", effect.id)
    }

    private fun buildBiomeGenerationOptionsForRoom(
        roomId: Int,
        width: Int,
        height: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        rules: BiomeRules,
        romParser: RomParser?,
        wfcOptions: WfcOptions,
        wfcSamples: List<WfcSample>,
    ): BiomeGenerationOptions {
        val preserveRects = ArrayList<BiomeGenerationRect>()
        val forceAirRects = ArrayList<BiomeGenerationRect>()
        val hardForceAirRects = ArrayList<BiomeGenerationRect>()
        if (roomId == 0x91F8) {
            addLandingSiteShipProtection(preserveRects, forceAirRects)
        }
        addElevatorProtectionForRoom(preserveRects, hardForceAirRects, romParser, roomId, width, height)
        val plms = effectivePlmsForBiomeRoom(roomId, romParser)
        addImportantPlmProtection(preserveRects, hardForceAirRects, width, height, plms)
        preserveRects.addAll(buildDoorCapPreserveRectsForRoom(romParser, roomId, width, height, plms))
        return BiomeGenerationOptions(
            preserveRects = preserveRects,
            forceAirRects = forceAirRects,
            hardForceAirRects = hardForceAirRects,
            protectedCells = BiomeSafetyMask.protectNonPlainMetadata(width, height, originalWords, originalBts, plms),
            wfcSamples = if (rules.algorithm == StructureAlgorithm.WFC) wfcSamples else emptyList(),
            wfcOptions = wfcOptions,
        )
    }

    private fun buildGeneratedRoomScrollResetEditsForRoom(
        romParser: RomParser,
        roomId: Int,
        romRoom: Room,
        effectiveRoom: Room,
    ): List<ScrollChange> {
        val roomWidthScreens = effectiveRoom.width
        val roomHeightScreens = effectiveRoom.height
        if (roomWidthScreens <= 0 || roomHeightScreens <= 0) return emptyList()
        val romScrolls = romParser.parseScrollData(romRoom.roomScrollsPtr, romRoom.width, romRoom.height)
        val original = if (effectiveRoom.width != romRoom.width || effectiveRoom.height != romRoom.height) {
            resizeScrollGrid(romScrolls, romRoom.width, romRoom.height, roomWidthScreens, roomHeightScreens)
        } else {
            romScrolls
        }
        val current = original.copyOf()
        project.rooms[project.roomKey(roomId)]?.scrollChanges.orEmpty().forEach { sc ->
            val idx = sc.screenY * roomWidthScreens + sc.screenX
            if (idx in current.indices) current[idx] = sc.newValue
        }
        val edits = ArrayList<ScrollChange>()
        for (screenY in 0 until roomHeightScreens) {
            for (screenX in 0 until roomWidthScreens) {
                val idx = screenY * roomWidthScreens + screenX
                val target = if (screenY == roomHeightScreens - 1) 0x02 else 0x01
                val old = current.getOrElse(idx) { original.getOrElse(idx) { 1 } }
                if (old != target) {
                    val originalValue = original.getOrElse(idx) { old }
                    edits.add(ScrollChange(screenX, screenY, originalValue, target))
                }
            }
        }
        return edits
    }

    private fun buildScrollPlmRemovalsForRoom(romParser: RomParser, roomId: Int): List<PlmChange> =
        effectivePlmsForBiomeRoom(roomId, romParser)
            .filter { RomParser.isScrollPlm(it.id) }
            .map { PlmChange("remove", it.id, it.x, it.y, it.param) }

    private fun effectivePlmsForBiomeRoom(roomId: Int, romParser: RomParser?): List<RomParser.PlmEntry> {
        romParser ?: return emptyList()
        if (roomId == currentRoomId && workingLevelData != null) return _workingPlms.toList()
        val plms = romParser.getAllPlmEntriesForRoom(roomId).toMutableList()
        val roomEdits = project.rooms[project.roomKey(roomId)] ?: return plms
        for (change in roomEdits.plmChanges) {
            when (change.action) {
                "add" -> plms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> plms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y && it.param == change.param }
            }
        }
        return plms
    }

    private fun buildBiomeGenerationOptions(
        keepLandingSiteShipClear: Boolean,
        rules: BiomeRules,
        romParser: RomParser?,
        wfcOptions: WfcOptions,
        originalWords: IntArray,
        originalBts: IntArray,
    ): BiomeGenerationOptions {
        val preserveRects = ArrayList<BiomeGenerationRect>()
        val forceAirRects = ArrayList<BiomeGenerationRect>()
        val hardForceAirRects = ArrayList<BiomeGenerationRect>()
        if (keepLandingSiteShipClear && currentRoomId == 0x91F8) {
            addLandingSiteShipProtection(preserveRects, forceAirRects)
        }
        addElevatorProtectionForRoom(
            preserveRects,
            hardForceAirRects,
            romParser,
            currentRoomId,
            workingBlocksWide,
            workingBlocksTall,
        )
        addImportantPlmProtection(preserveRects, hardForceAirRects, workingBlocksWide, workingBlocksTall, _workingPlms)
        preserveRects.addAll(
            buildDoorCapPreserveRectsForRoom(
                romParser,
                currentRoomId,
                workingBlocksWide,
                workingBlocksTall,
                _workingPlms,
            )
        )
        val wfcSamples = if (rules.algorithm == StructureAlgorithm.WFC && romParser != null) {
            buildWfcSamples(romParser, currentRoomId, currentTilesetId)
        } else {
            emptyList()
        }
        return BiomeGenerationOptions(
            preserveRects = preserveRects,
            forceAirRects = forceAirRects,
            hardForceAirRects = hardForceAirRects,
            protectedCells = BiomeSafetyMask.protectNonPlainMetadata(
                workingBlocksWide,
                workingBlocksTall,
                originalWords,
                originalBts,
                _workingPlms,
            ),
            wfcSamples = wfcSamples,
            wfcOptions = wfcOptions,
        )
    }

    private fun addImportantPlmProtection(
        preserveRects: MutableList<BiomeGenerationRect>,
        hardForceAirRects: MutableList<BiomeGenerationRect>,
        width: Int,
        height: Int,
        plms: List<RomParser.PlmEntry>,
    ) {
        if (width <= 0 || height <= 0) return
        fun addRect(list: MutableList<BiomeGenerationRect>, rect: BiomeGenerationRect) {
            if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) {
                list.add(rect)
            }
        }
        for (plm in plms) {
            if (!isBiomeAnchorPlm(plm.id)) continue
            val x = plm.x
            val y = plm.y
            if (x !in 0 until width || y !in 0 until height) continue
            addRect(preserveRects, BiomeGenerationRect(x - 1, y - 1, x + 1, y + 1))
            addRect(hardForceAirRects, BiomeGenerationRect(x - 3, y - 4, x + 3, y + 2))
        }
    }

    private fun isBiomeAnchorPlm(plmId: Int): Boolean =
        !RomParser.isScrollPlm(plmId) &&
            !RomParser.isDoorCapPlm(plmId) &&
            (isEditorItemPlm(plmId) || RomParser.isStationPlm(plmId) || RomParser.isGatePlm(plmId))

    private fun buildGeneratedRoomScrollResetEdits(): List<ScrollChange> {
        val roomWidthScreens = workingBlocksWide / 16
        val roomHeightScreens = workingBlocksTall / 16
        if (roomWidthScreens <= 0 || roomHeightScreens <= 0) return emptyList()
        val edits = ArrayList<ScrollChange>()
        for (screenY in 0 until roomHeightScreens) {
            for (screenX in 0 until roomWidthScreens) {
                val idx = screenY * roomWidthScreens + screenX
                if (idx !in _workingScrolls.indices) continue
                val target = if (screenY == roomHeightScreens - 1) 0x02 else 0x01
                val old = _workingScrolls[idx]
                if (old != target) edits.add(ScrollChange(screenX, screenY, old, target))
            }
        }
        return edits
    }

    private fun buildScrollPlmRemovals(): List<PlmChange> =
        _workingPlms
            .filter { RomParser.isScrollPlm(it.id) }
            .map { PlmChange("remove", it.id, it.x, it.y, it.param) }

    private fun applyGeneratedRoomOperation(
        description: String,
        edits: List<TileEdit>,
        scrollEdits: List<ScrollChange>,
        scrollPlmRemoves: List<PlmChange>,
    ) {
        if (edits.isEmpty() && scrollEdits.isEmpty() && scrollPlmRemoves.isEmpty()) return

        for (e in edits) {
            applyTileEdit(e, useNew = true)
        }

        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val roomWidthScreens = workingBlocksWide / 16
        for (sc in scrollEdits) {
            val idx = sc.screenY * roomWidthScreens + sc.screenX
            if (idx !in _workingScrolls.indices) continue
            _workingScrolls[idx] = sc.newValue
            roomEdits.scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
            val original = _originalScrolls.getOrElse(idx) { sc.oldValue }
            if (sc.newValue != original) {
                roomEdits.scrollChanges.add(ScrollChange(sc.screenX, sc.screenY, original, sc.newValue))
            }
        }
        if (scrollEdits.isNotEmpty()) scrollVersion++

        for (plm in scrollPlmRemoves) {
            _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
            roomEdits.plmChanges.add(plm)
        }

        pushEditOperation(
            EditOperation(
                description,
                edits,
                plmRemoves = scrollPlmRemoves,
                scrollEdits = scrollEdits,
            )
        )
    }

    fun resetCurrentRoomToOriginal(romParser: RomParser): Boolean {
        val roomId = currentRoomId
        if (roomId == 0) return false
        val room = romParser.readRoomHeader(roomId) ?: return false
        val roomKey = project.roomKey(roomId)
        val removedEdits = project.rooms.remove(roomKey) != null

        romTilesetId = room.tileset
        currentTilesetId = room.tileset
        currentStateIndex = -1

        val tg = TileGraphics(romParser)
        tileGraphics = if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tg
        } else {
            null
        }

        val levelData = romParser.decompressLZ2(room.levelDataPtr)
        originalLevelData = levelData.copyOf()
        workingLevelData = levelData.copyOf()
        workingBlocksWide = room.width * 16
        workingBlocksTall = room.height * 16

        _originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        _workingScrolls = _originalScrolls.copyOf()

        _workingPlms.clear()
        val plms = romParser.getAllPlmEntriesForRoom(roomId)
        _workingPlms.addAll(plms)
        originalPlmCount = plms.size

        doorEntries = romParser.parseDoorList(room.doorOut)

        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(room.enemySetPtr))

        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        undoStack.clear()
        redoStack.clear()

        undoVersion++
        scrollVersion++
        _editVersionState.value++
        _roomEditOrder.remove(roomId)
        if (removedEdits) dirty = true
        editorLog("Reset room 0x$roomKey to original ROM state")
        return true
    }

    /**
     * Apply a [BiomeTheme] to the loaded room: switch its tileset (persisted
     * as a state-data change for ROM export), install the theme's palette
     * recolor as a tileset palette override, and set liquid/atmosphere FX.
     * Reloads the room's TileGraphics so the canvas shows the new look
     * immediately. Call before [generateBiome] so the generated layout is
     * dressed with the theme tileset's vocabulary.
     */
    fun applyBiomeTheme(theme: BiomeTheme, romParser: RomParser) {
        if (theme.tilesetId == null && theme.paletteEffectId == null && theme.fxType == null) return
        val targetTileset = theme.tilesetId ?: currentTilesetId
        val roomEdits = project.getOrCreateRoom(currentRoomId)

        // Recolor from the vanilla palette so re-applying a theme is stable.
        val effectId = theme.paletteEffectId
        if (effectId != null) {
            val effect = PaletteEffects.findEffect(effectId)
            val colors = readTilesetPalette(targetTileset, romParser, includeOverride = false)
            if (effect != null && colors != null) {
                effect.apply(colors)
                saveTilesetPaletteFromColors(targetTileset, colors)
                setPaletteEffect("tileset:$targetTileset", effect.id)
            }
        }

        if (theme.tilesetId != null) {
            val existing = roomEdits.stateDataChange ?: StateDataChange()
            val change = existing.copy(tileset = targetTileset.takeIf { it != romTilesetId })
            roomEdits.stateDataChange = change.takeIf { it != StateDataChange() }
            currentTilesetId = targetTileset
        }
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tileGraphics = tg
        }

        if (theme.fxType != null) {
            val existing = roomEdits.fxChange ?: FxChange()
            roomEdits.fxChange = if (theme.isLiquid) {
                val heightPx = workingBlocksTall * 16
                val surface = (heightPx * theme.liquidFraction).toInt()
                    .coerceIn(0x20, maxOf(0x20, heightPx - 0x20))
                existing.copy(
                    fxType = theme.fxType,
                    liquidSurfaceStart = surface, liquidSurfaceNew = surface,
                    liquidSpeed = 0, liquidDelay = 0,
                    fxBitA = 0x02, fxBitB = 0x02, fxBitC = 0,
                )
            } else {
                existing.copy(fxType = theme.fxType, liquidSurfaceStart = 0xFFFF, liquidSurfaceNew = 0xFFFF)
            }
        }

        dirty = true
        editVersion++
    }

    private fun pushEditOperation(op: EditOperation) {
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        project.getOrCreateRoom(currentRoomId).operations.add(op)
        dirty = true
        editVersion++
    }

    // ─── Undo / Redo ────────────────────────────────────────────

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val op = undoStack.removeAt(undoStack.lastIndex)
        val roomEdits = project.getOrCreateRoom(currentRoomId)

        // Undo tile edits
        for (edit in op.edits.reversed()) {
            applyTileEdit(edit, useNew = false)
        }
        if (roomEdits.operations.isNotEmpty() && roomEdits.operations.last() == op) {
            roomEdits.operations.removeAt(roomEdits.operations.lastIndex)
        } else if (op.edits.isNotEmpty() && roomEdits.operations.isNotEmpty()) {
            roomEdits.operations.removeAt(roomEdits.operations.lastIndex)
        }

        // Undo PLM adds (reverse = remove them)
        for (plm in op.plmAdds) {
            _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
            roomEdits.plmChanges.add(PlmChange("remove", plm.plmId, plm.x, plm.y, plm.param))
            if (plm.plmId == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(plm.param and 0xFF)
        }
        // Undo PLM removes (reverse = re-add them)
        for (plm in op.plmRemoves) {
            _workingPlms.add(RomParser.PlmEntry(plm.plmId, plm.x, plm.y, plm.param))
            roomEdits.plmChanges.add(PlmChange("add", plm.plmId, plm.x, plm.y, plm.param))
            if (plm.plmId == 0xB76F) ensureAutoSaveStationSpawn(plm.x, plm.y, plm.param and 0xFF)
        }

        // Undo enemy adds
        for (ec in op.enemyAdds) {
            _workingEnemies.removeAll { it.id == ec.enemyId && it.x == ec.x && it.y == ec.y }
            roomEdits.enemyChanges.add(EnemyChange("remove", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3, origX = ec.x, origY = ec.y))
        }
        // Undo enemy removes
        for (ec in op.enemyRemoves) {
            _workingEnemies.add(RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
            roomEdits.enemyChanges.add(EnemyChange("add", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
        }
        // Undo enemy updates (swap back to old)
        for (eu in op.enemyUpdates) {
            val idx = _workingEnemies.indexOfFirst { it.id == eu.new.enemyId && it.x == eu.new.x && it.y == eu.new.y }
            if (idx >= 0) {
                val o = eu.old
                _workingEnemies[idx] = RomParser.EnemyEntry(o.enemyId, o.x, o.y, o.initParam, o.properties, o.extra1, o.extra2, o.extra3)
                roomEdits.enemyChanges.add(EnemyChange("update", o.enemyId, o.x, o.y, o.initParam, o.properties,
                    o.extra1, o.extra2, o.extra3, origX = eu.new.x, origY = eu.new.y))
            }
        }

        // Undo scroll edits
        for (sc in op.scrollEdits) {
            val roomWidthScreens = workingBlocksWide / 16
            val scrollIdx = sc.screenY * roomWidthScreens + sc.screenX
            if (scrollIdx in _workingScrolls.indices) {
                _workingScrolls[scrollIdx] = sc.oldValue
                roomEdits.scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
                if (sc.oldValue != _originalScrolls.getOrElse(scrollIdx) { sc.oldValue }) {
                    roomEdits.scrollChanges.add(ScrollChange(sc.screenX, sc.screenY, _originalScrolls[scrollIdx], sc.oldValue))
                }
                scrollVersion++
            }
        }

        redoStack.add(op)
        undoVersion++
        dirty = true
        editVersion++
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val op = redoStack.removeAt(redoStack.lastIndex)
        val roomEdits = project.getOrCreateRoom(currentRoomId)

        // Redo tile edits
        for (edit in op.edits) {
            applyTileEdit(edit, useNew = true)
        }
        if (op.edits.isNotEmpty()) roomEdits.operations.add(op)

        // Redo PLM adds
        for (plm in op.plmAdds) {
            _workingPlms.add(RomParser.PlmEntry(plm.plmId, plm.x, plm.y, plm.param))
            roomEdits.plmChanges.add(PlmChange("add", plm.plmId, plm.x, plm.y, plm.param))
            if (plm.plmId == 0xB76F) ensureAutoSaveStationSpawn(plm.x, plm.y, plm.param and 0xFF)
        }
        // Redo PLM removes
        for (plm in op.plmRemoves) {
            _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
            roomEdits.plmChanges.add(PlmChange("remove", plm.plmId, plm.x, plm.y, plm.param))
            if (plm.plmId == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(plm.param and 0xFF)
        }

        // Redo enemy adds
        for (ec in op.enemyAdds) {
            _workingEnemies.add(RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
            roomEdits.enemyChanges.add(EnemyChange("add", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
        }
        // Redo enemy removes
        for (ec in op.enemyRemoves) {
            _workingEnemies.removeAll { it.id == ec.enemyId && it.x == ec.x && it.y == ec.y }
            roomEdits.enemyChanges.add(EnemyChange("remove", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3, origX = ec.x, origY = ec.y))
        }
        // Redo enemy updates
        for (eu in op.enemyUpdates) {
            val idx = _workingEnemies.indexOfFirst { it.id == eu.old.enemyId && it.x == eu.old.x && it.y == eu.old.y }
            if (idx >= 0) {
                val n = eu.new
                _workingEnemies[idx] = RomParser.EnemyEntry(n.enemyId, n.x, n.y, n.initParam, n.properties, n.extra1, n.extra2, n.extra3)
                roomEdits.enemyChanges.add(EnemyChange("update", n.enemyId, n.x, n.y, n.initParam, n.properties,
                    n.extra1, n.extra2, n.extra3, origX = eu.old.x, origY = eu.old.y))
            }
        }

        // Redo scroll edits
        for (sc in op.scrollEdits) {
            val roomWidthScreens = workingBlocksWide / 16
            val scrollIdx = sc.screenY * roomWidthScreens + sc.screenX
            if (scrollIdx in _workingScrolls.indices) {
                _workingScrolls[scrollIdx] = sc.newValue
                roomEdits.scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
                if (sc.newValue != _originalScrolls.getOrElse(scrollIdx) { sc.newValue }) {
                    roomEdits.scrollChanges.add(ScrollChange(sc.screenX, sc.screenY, _originalScrolls[scrollIdx], sc.newValue))
                }
                scrollVersion++
            }
        }

        undoStack.add(op)
        undoVersion++
        dirty = true
        editVersion++
        return true
    }

    // ─── Project file I/O ───────────────────────────────────────

    // ─── Room JSON Export/Import ────────────────────────────────
    fun exportRoomToJson(roomId: Int, romParser: RomParser): String {
        val room = romParser.readRoomHeader(roomId) ?: error("Room not found")
        val roomKey = project.roomKey(roomId)
        val hc = project.rooms[roomKey]?.roomHeaderChange
        val effectiveWidth = hc?.width ?: room.width
        val effectiveHeight = hc?.height ?: room.height

        var levelData = romParser.decompressLZ2(room.levelDataPtr)
        if (effectiveWidth != room.width || effectiveHeight != room.height) {
            levelData = resizeLevelData(levelData, room.width, room.height, effectiveWidth, effectiveHeight)
        }
        // Apply tile edits from project
        val wd = workingLevelData
        val useLive = (currentRoomId == roomId && wd != null)
        val dataToExport = if (useLive) wd else levelData

        val scrolls = if (useLive) _workingScrolls.toList()
        else romParser.parseScrollData(room.roomScrollsPtr, effectiveWidth, effectiveHeight).toList()

        val enemies = if (useLive) _workingEnemies.toList()
        else romParser.parseEnemyPopulation(room.enemySetPtr)

        val plms = romParser.getAllPlmEntriesForRoom(roomId)
        val doors = romParser.parseDoorList(room.doorOut)

        val roomName = com.supermetroid.editor.data.RoomRepository().getAllRooms()
            .find { it.getRoomIdAsInt() == roomId }?.name ?: "Room \$${roomKey}"

        val export = com.supermetroid.editor.data.RoomExportData(
            roomId = roomKey,
            roomName = roomName,
            width = effectiveWidth,
            height = effectiveHeight,
            tileset = room.tileset,
            area = hc?.area ?: room.area,
            levelDataBase64 = java.util.Base64.getEncoder().encodeToString(dataToExport),
            scrollData = scrolls,
            enemies = enemies.map { e ->
                com.supermetroid.editor.data.EnemyExport(
                    species = e.id.toString(16).uppercase(),
                    x = e.x, y = e.y, initParam = e.initParam,
                    properties = e.properties, extra1 = e.extra1, extra2 = e.extra2, extra3 = e.extra3
                )
            },
            plms = plms.map { p ->
                com.supermetroid.editor.data.PlmExport(
                    id = p.id.toString(16).uppercase(),
                    x = p.x, y = p.y, param = p.param
                )
            },
            doors = doors.map { d ->
                com.supermetroid.editor.data.DoorExport(
                    destRoom = d.destRoomPtr.toString(16).uppercase(),
                    bitflag = d.bitflag, direction = d.direction,
                    doorCapCode = d.doorCapCode, screenX = d.screenX, screenY = d.screenY,
                    distFromDoor = d.distFromDoor, entryCode = d.entryCode
                )
            },
            musicTrack = room.musicTrack,
        )
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
            com.supermetroid.editor.data.RoomExportData.serializer(), export
        )
    }

    fun saveProject(romParser: RomParser? = null): Boolean {
        val saved = ProjectFileService.saveProject(
            project = project,
            projectFilePath = projectFilePath,
            romParser = romParser,
            savePatternLibrary = !testMode,
            onLog = ::editorLog,
        )
        if (saved) {
            dirty = false
            postStatus("Project saved: $projectFilePath")
        } else {
            postStatus("Save failed")
        }
        return saved
    }

    // ─── Export: patch ROM ──────────────────────────────────────

    fun exportToRom(romParser: RomParser): String? {
        seedDefaultPatches(forceRefreshBundled = true)
        if (project.romPath.isEmpty()) return null
        saveProject(romParser)
        return ProjectFileService.exportToRom(project, romParser, ::editorLog, ::postStatus)
    }

    fun exportToIps(romParser: RomParser): String? {
        seedDefaultPatches(forceRefreshBundled = true)
        if (project.romPath.isEmpty()) return null
        saveProject(romParser)
        return ProjectFileService.exportToIps(project, romParser, ::editorLog, ::postStatus)
    }

}

/**
 * Copy a flat scroll-value grid from one screen dimension to another.
 * New cells default to [default] (1 = Blue/visible). Overlapping cells are preserved.
 */
internal fun resizeScrollGrid(
    source: IntArray,
    oldWidth: Int, oldHeight: Int,
    newWidth: Int, newHeight: Int,
    default: Int = 1,
): IntArray {
    val result = IntArray(newWidth * newHeight) { default }
    for (sy in 0 until minOf(oldHeight, newHeight)) {
        for (sx in 0 until minOf(oldWidth, newWidth)) {
            val oldIdx = sy * oldWidth + sx
            if (oldIdx in source.indices) result[sy * newWidth + sx] = source[oldIdx]
        }
    }
    return result
}

/**
 * Resize level data from (oldW x oldH) screens to (newW x newH) screens.
 * Layout: [2-byte L1 size header][L1 tile words][BTS bytes][L2 tile words (optional)].
 * Existing tiles are preserved where dimensions overlap; new areas are filled with 0 (air).
 */
internal fun resizeLevelData(
    data: ByteArray, oldWidthScreens: Int, oldHeightScreens: Int,
    newWidthScreens: Int, newHeightScreens: Int
): ByteArray {
    val oldBlocksW = oldWidthScreens * 16
    val oldBlocksH = oldHeightScreens * 16
    val newBlocksW = newWidthScreens * 16
    val newBlocksH = newHeightScreens * 16
    val oldTotal = oldBlocksW * oldBlocksH
    val newTotal = newBlocksW * newBlocksH

    val oldL1Size = if (data.size >= 2) (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) else 0
    val btsStart = 2 + oldL1Size
    val hasL2 = data.size >= btsStart + oldTotal + oldTotal * 2

    fun readWord(offset: Int): Int =
        if (offset + 1 < data.size) ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF) else 0
    fun readByte(offset: Int): Int =
        if (offset < data.size) data[offset].toInt() and 0xFF else 0

    val newL1Size = newTotal * 2
    val newDataSize = 2 + newL1Size + newTotal + (if (hasL2) newTotal * 2 else 0)
    val out = ByteArray(newDataSize)
    out[0] = (newL1Size and 0xFF).toByte()
    out[1] = ((newL1Size shr 8) and 0xFF).toByte()

    val copyW = minOf(oldBlocksW, newBlocksW)
    val copyH = minOf(oldBlocksH, newBlocksH)

    // L1 tiles
    for (by in 0 until newBlocksH) for (bx in 0 until newBlocksW) {
        val word = if (bx < copyW && by < copyH) readWord(2 + (by * oldBlocksW + bx) * 2) else RomConstants.AIR_TILE_WORD
        val off = 2 + (by * newBlocksW + bx) * 2
        out[off] = (word and 0xFF).toByte()
        out[off + 1] = ((word shr 8) and 0xFF).toByte()
    }
    // BTS
    val newBtsStart = 2 + newL1Size
    for (by in 0 until newBlocksH) for (bx in 0 until newBlocksW) {
        val bts = if (bx < copyW && by < copyH) readByte(btsStart + by * oldBlocksW + bx) else 0
        out[newBtsStart + by * newBlocksW + bx] = bts.toByte()
    }
    // L2
    if (hasL2) {
        val oldL2Start = btsStart + oldTotal
        val newL2Start = newBtsStart + newTotal
        for (by in 0 until newBlocksH) for (bx in 0 until newBlocksW) {
            val word = if (bx < copyW && by < copyH) readWord(oldL2Start + (by * oldBlocksW + bx) * 2) else 0
            val off = newL2Start + (by * newBlocksW + bx) * 2
            out[off] = (word and 0xFF).toByte()
            out[off + 1] = ((word shr 8) and 0xFF).toByte()
        }
    }
    return out
}
