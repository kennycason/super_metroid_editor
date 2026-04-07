package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.EnemyChange
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.EnemyUpdate
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.serialization.json.Json
import java.io.File



// ─── Editor State ───────────────────────────────────────────────

class EditorState {
    var brush by mutableStateOf<TileBrush?>(null)
        internal set

    var activeTool by mutableStateOf(EditorTool.PAINT)

    /** Map selection rectangle in block coordinates (inclusive). */
    var mapSelStart by mutableStateOf<Pair<Int, Int>?>(null)
    var mapSelEnd by mutableStateOf<Pair<Int, Int>?>(null)

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
    var currentTilesetId: Int = 0
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
    var editorTileGraphics: TileGraphics? = null
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

    /** Apply any project-stored custom graphics and palette to a TileGraphics instance. */
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
    }

    /** Remove the custom palette override, restoring the ROM default. */
    fun resetPaletteOverride(tilesetId: Int) {
        project.customGfx.palettes.remove(tilesetId.toString())
        dirty = true
    }

    /** Check if the project has a custom palette for the given tileset. */
    fun hasCustomPalette(tilesetId: Int): Boolean =
        project.customGfx.palettes.containsKey(tilesetId.toString())

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

    // ── Enemy / Boss sprite graphics ──────────────────────────────────────────

    /**
     * Load the effective ARGB pixels for an enemy sprite.
     * Returns custom project PNG if present, otherwise decodes the embedded resource PNG.
     * @param speciesIdHex e.g. "E4BF"
     */
    fun getEnemySpritePixels(speciesIdHex: String): Pair<IntArray, Pair<Int,Int>>? {
        val customB64 = project.customGfx.enemyGfx[speciesIdHex]
        val img: java.awt.image.BufferedImage? = if (customB64 != null) {
            try {
                val bytes = java.util.Base64.getDecoder().decode(customB64)
                javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            } catch (_: Exception) { null }
        } else {
            try {
                val stream = javaClass.getResourceAsStream("/enemies/$speciesIdHex.png")
                stream?.let { javax.imageio.ImageIO.read(it) }
            } catch (_: Exception) { null }
        }
        img ?: return null
        val w = img.width; val h = img.height
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)
        return Pair(pixels, Pair(w, h))
    }

    /** Whether a custom (imported/edited) sprite exists for this species ID. */
    fun hasCustomEnemySprite(speciesIdHex: String): Boolean =
        project.customGfx.enemyGfx.containsKey(speciesIdHex)

    /**
     * Export the current enemy sprite (custom or default resource) as a PNG.
     */
    fun exportEnemySprite(speciesIdHex: String, filePath: String): Boolean {
        val (pixels, dims) = getEnemySpritePixels(speciesIdHex) ?: return false
        return writePng(filePath, pixels, dims.first, dims.second)
    }

    /**
     * Import a PNG from disk as a custom enemy sprite override.
     */
    fun importEnemySprite(speciesIdHex: String, filePath: String): Boolean {
        return try {
            val bytes = java.io.File(filePath).readBytes()
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            project.customGfx.enemyGfx[speciesIdHex] = b64
            dirty = true
            true
        } catch (_: Exception) { false }
    }

    /**
     * Persist in-editor pixel changes to the project for an enemy sprite.
     * @param pixels ARGB pixel array
     */
    fun saveEnemySpritePixels(speciesIdHex: String, pixels: IntArray, w: Int, h: Int): Boolean {
        return try {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, pixels, 0, w)
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", baos)
            val b64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
            project.customGfx.enemyGfx[speciesIdHex] = b64
            dirty = true
            true
        } catch (_: Exception) { false }
    }

    /** Remove custom sprite override, reverting to built-in resource PNG. */
    fun resetEnemySprite(speciesIdHex: String) {
        project.customGfx.enemyGfx.remove(speciesIdHex)
        dirty = true
    }

    // ── Enemy tile-sheet editing (raw 4bpp) ──────────────────────────────────

    /**
     * Load the effective tile data for an enemy species.
     * Returns custom project data if present, otherwise raw ROM data.
     */
    fun loadEnemyTileData(romParser: com.supermetroid.editor.rom.RomParser, speciesId: Int): ByteArray? {
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        val customB64 = project.customGfx.spriteTileBlocks[key]
        if (customB64 != null) {
            try {
                return java.util.Base64.getDecoder().decode(customB64)
            } catch (_: Exception) { /* fall through to ROM */ }
        }
        return com.supermetroid.editor.rom.EnemySpriteGraphics.loadEnemyTileData(romParser, speciesId)
    }

    /**
     * Apply pixel edits to an enemy's tile sheet. Converts ARGB pixels back to
     * raw 4bpp tile data and stores in the project.
     */
    fun applyEnemyTileSheetEdits(
        romParser: com.supermetroid.editor.rom.RomParser,
        speciesId: Int,
        pixels: IntArray,
        w: Int,
        h: Int
    ) {
        val palette = com.supermetroid.editor.rom.EnemySpriteGraphics.readEnemyPalette(romParser, speciesId) ?: return
        val tileData = loadEnemyTileData(romParser, speciesId) ?: return
        val gfx = com.supermetroid.editor.rom.EnemySpriteGraphics(romParser)
        gfx.loadFromRaw(listOf(tileData))
        gfx.importFromArgb(pixels, w, h, palette, 16)
        val rawBlocks = gfx.getRawBlocks() ?: return
        val raw = rawBlocks.firstOrNull() ?: return
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        project.customGfx.spriteTileBlocks[key] = java.util.Base64.getEncoder().encodeToString(raw)
        dirty = true
    }

    /** Whether the project has custom tile data for this enemy. */
    fun hasCustomEnemyTiles(speciesId: Int): Boolean {
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        return project.customGfx.spriteTileBlocks.containsKey(key)
    }

    /** Reset custom enemy tile data to ROM defaults. */
    fun resetEnemyTiles(speciesId: Int) {
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        project.customGfx.spriteTileBlocks.remove(key)
        dirty = true
    }

    // ── Boss sprite tile-sheet editing ───────────────────────────────────────

    /**
     * Cached EnemySpriteGraphics for the currently open tile-sheet session.
     * Populated by [loadPhantoonTileSheet]; reset on new session.
     */
    private var spriteSheetGfx: com.supermetroid.editor.rom.EnemySpriteGraphics? = null
    private var spriteSheetPalette: IntArray? = null

    // ── Phantoon assembled-sprite editing (BG2 tilemap path) ─────────────

    private var phantoonSpritemap: com.supermetroid.editor.rom.PhantoonSpritemap? = null

    /**
     * Get or create the cached PhantoonSpritemap.
     * Applies any project-stored custom var GFX for the Phantoon room tileset.
     */
    fun getPhantoonSpritemap(romParser: com.supermetroid.editor.rom.RomParser): com.supermetroid.editor.rom.PhantoonSpritemap? {
        phantoonSpritemap?.let { return it }
        val sm = com.supermetroid.editor.rom.PhantoonSpritemap(romParser)
        if (!sm.load()) return null
        applyCustomGfxToTileGraphics(sm.getTileGraphics(), sm.getTilesetId())
        phantoonSpritemap = sm
        return sm
    }

    fun renderPhantoonComponent(
        romParser: com.supermetroid.editor.rom.RomParser,
        def: com.supermetroid.editor.rom.PhantoonSpritemap.ComponentDef
    ): com.supermetroid.editor.rom.PhantoonSpritemap.AssembledSprite? {
        return getPhantoonSpritemap(romParser)?.renderComponent(def)
    }

    fun applyPhantoonComponentEdits(
        romParser: com.supermetroid.editor.rom.RomParser,
        sprite: com.supermetroid.editor.rom.PhantoonSpritemap.AssembledSprite,
        editedPixels: IntArray
    ) {
        val sm = getPhantoonSpritemap(romParser) ?: return
        val tg = sm.getTileGraphics()
        sm.applyEdits(sprite, editedPixels, tg)
        val rawVarGfx = tg.getRawVarGfx() ?: return
        val b64 = java.util.Base64.getEncoder().encodeToString(rawVarGfx)
        project.customGfx.varGfx[sm.getTilesetId().toString()] = b64
        dirty = true
        phantoonSpritemap = null
    }

    fun getPhantoonPalette(romParser: com.supermetroid.editor.rom.RomParser): IntArray? {
        return getPhantoonSpritemap(romParser)?.getPalette()
    }

    fun hasCustomPhantoonComponents(): Boolean {
        val sm = phantoonSpritemap ?: return false
        return project.customGfx.varGfx.containsKey(sm.getTilesetId().toString())
    }

    /**
     * Load Phantoon's sprite tile sheet as an ARGB grid.
     * Uses project-stored custom tile data if present, otherwise decompresses from ROM.
     * @return (pixels, width, height, palette16) or null on failure
     */
    fun loadPhantoonTileSheet(
        romParser: com.supermetroid.editor.rom.RomParser
    ): Triple<IntArray, Int, Int>? {
        val gfx = com.supermetroid.editor.rom.EnemySpriteGraphics(romParser)

        // Build a map of custom block overrides (blockIndex → raw 4bpp bytes)
        val customOverrides = mutableMapOf<Int, ByteArray>()
        for ((i, _) in com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val b64 = project.customGfx.spriteTileBlocks["phantoon:$i"] ?: continue
            try { customOverrides[i] = java.util.Base64.getDecoder().decode(b64) } catch (_: Exception) {}
        }

        val loaded = if (customOverrides.isEmpty()) {
            gfx.load(com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS)
        } else {
            gfx.loadWithOverrides(
                com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS,
                customOverrides
            )
        }
        if (!loaded) return null

        val palette = com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_PALETTE

        spriteSheetGfx = gfx
        spriteSheetPalette = palette
        println("[SPRITE] loadPhantoonTileSheet: loaded ${gfx.getTileCount()} tiles, palette=${palette.size} colors, spriteSheetGfx set")

        return gfx.renderSheet(palette)
    }

    /** Palette for the last loaded tile sheet (used by UI for colour swatches). */
    fun getSpriteSheetPalette(): IntArray? = spriteSheetPalette

    /**
     * Encode ARGB pixel edits back to raw 4bpp tile blocks and store in the project.
     * Must be called after [loadPhantoonTileSheet] to have the gfx session available.
     */
    fun applyPhantoonTileSheetEdits(pixels: IntArray, w: Int, h: Int) {
        println("[SPRITE] applyPhantoonTileSheetEdits called (${w}x${h}, ${pixels.size} px)")
        val gfx = spriteSheetGfx
        if (gfx == null) { println("[SPRITE] ABORT: spriteSheetGfx is null — was loadPhantoonTileSheet() called first?"); return }
        val palette = spriteSheetPalette
        if (palette == null) { println("[SPRITE] ABORT: spriteSheetPalette is null"); return }
        gfx.importFromArgb(pixels, w, h, palette)
        val rawBlocks = gfx.getRawBlocks()
        if (rawBlocks == null) { println("[SPRITE] ABORT: getRawBlocks() returned null after importFromArgb"); return }
        for ((i, raw) in rawBlocks.withIndex()) {
            val b64 = java.util.Base64.getEncoder().encodeToString(raw)
            project.customGfx.spriteTileBlocks["phantoon:$i"] = b64
            println("[SPRITE] Stored phantoon:$i — ${raw.size} raw bytes, ${b64.length} b64 chars")
        }
        dirty = true
        println("[SPRITE] applyPhantoonTileSheetEdits DONE — ${rawBlocks.size} blocks stored, spriteTileBlocks keys=${project.customGfx.spriteTileBlocks.keys}")
    }

    /** Whether the project has custom Phantoon tile block data. */
    fun hasCustomPhantoonTileSheet(): Boolean =
        com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS.indices.any { i ->
            project.customGfx.spriteTileBlocks.containsKey("phantoon:$i")
        }

    /** Remove all custom Phantoon tile-sheet overrides. */
    fun resetPhantoonTileSheet() {
        com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS.indices.forEach { i ->
            project.customGfx.spriteTileBlocks.remove("phantoon:$i")
        }
        spriteSheetGfx = null
        spriteSheetPalette = null
        dirty = true
    }

    // ── Kraid sprite editing ────────────────────────────────────────

    private var kraidSpritemap: com.supermetroid.editor.rom.KraidSpritemap? = null
    private var kraidSheetGfx: com.supermetroid.editor.rom.EnemySpriteGraphics? = null
    private var kraidSheetPalette: IntArray? = null

    fun getKraidSpritemap(romParser: com.supermetroid.editor.rom.RomParser): com.supermetroid.editor.rom.KraidSpritemap? {
        kraidSpritemap?.let { return it }
        val sm = com.supermetroid.editor.rom.KraidSpritemap(romParser)
        val b64 = project.customGfx.spriteTileBlocks["kraid:0"]
        val loaded = if (b64 != null) {
            try {
                val custom = java.util.Base64.getDecoder().decode(b64)
                sm.loadWithCustomTiles(custom)
            } catch (_: Exception) { sm.load() }
        } else {
            sm.load()
        }
        if (!loaded) return null
        kraidSpritemap = sm
        return sm
    }

    fun renderKraidFullBody(romParser: com.supermetroid.editor.rom.RomParser): com.supermetroid.editor.rom.KraidSpritemap.AssembledSprite? {
        return getKraidSpritemap(romParser)?.renderFullBody()
    }

    fun renderKraidBodyTilemap(
        romParser: com.supermetroid.editor.rom.RomParser,
        def: com.supermetroid.editor.rom.KraidSpritemap.BodyTilemapDef
    ): com.supermetroid.editor.rom.KraidSpritemap.AssembledSprite? {
        return getKraidSpritemap(romParser)?.renderBodyTilemap(def)
    }

    fun renderKraidBigSprmap(
        romParser: com.supermetroid.editor.rom.RomParser,
        def: com.supermetroid.editor.rom.KraidSpritemap.ComponentDef
    ): com.supermetroid.editor.rom.KraidSpritemap.AssembledSprite? {
        return getKraidSpritemap(romParser)?.renderBigSprmap(def)
    }

    fun getKraidPalette(romParser: com.supermetroid.editor.rom.RomParser): IntArray? {
        return getKraidSpritemap(romParser)?.getPalette()
    }

    fun applyKraidComponentEdits(
        sprite: com.supermetroid.editor.rom.KraidSpritemap.AssembledSprite,
        editedPixels: IntArray
    ) {
        val sm = kraidSpritemap ?: return
        sm.applyEdits(sprite, editedPixels)
        val tiles = sm.getTileData() ?: return
        val b64 = java.util.Base64.getEncoder().encodeToString(tiles)
        project.customGfx.spriteTileBlocks["kraid:0"] = b64
        dirty = true
        kraidSpritemap = null
    }

    fun loadKraidTileSheet(
        romParser: com.supermetroid.editor.rom.RomParser
    ): Triple<IntArray, Int, Int>? {
        val gfx = com.supermetroid.editor.rom.EnemySpriteGraphics(romParser)
        val b64 = project.customGfx.spriteTileBlocks["kraid:0"]
        val loaded = if (b64 != null) {
            try {
                val custom = java.util.Base64.getDecoder().decode(b64)
                gfx.loadFromRaw(listOf(custom))
                true
            } catch (_: Exception) { false }
        } else {
            false
        }
        if (!loaded) {
            if (!gfx.load(com.supermetroid.editor.rom.EnemySpriteGraphics.KRAID_BLOCKS)) return null
        }

        val sm = getKraidSpritemap(romParser) ?: return null
        val palette = sm.getPalette() ?: return null
        kraidSheetGfx = gfx
        kraidSheetPalette = palette
        val result = gfx.renderSheet(palette, cols = 16) ?: return null
        return Triple(result.first, result.second, result.third)
    }

    fun getKraidSheetPalette(): IntArray? = kraidSheetPalette

    fun applyKraidTileSheetEdits(pixels: IntArray, w: Int, h: Int) {
        val gfx = kraidSheetGfx ?: return
        val palette = kraidSheetPalette ?: return
        gfx.importFromArgb(pixels, w, h, palette, cols = 16)
        val rawBlocks = gfx.getRawBlocks() ?: return
        for ((i, raw) in rawBlocks.withIndex()) {
            val b64 = java.util.Base64.getEncoder().encodeToString(raw)
            project.customGfx.spriteTileBlocks["kraid:$i"] = b64
        }
        dirty = true
        kraidSpritemap = null
    }

    fun hasCustomKraidTileSheet(): Boolean =
        project.customGfx.spriteTileBlocks.containsKey("kraid:0")

    fun resetKraidTileSheet() {
        project.customGfx.spriteTileBlocks.remove("kraid:0")
        kraidSheetGfx = null
        kraidSheetPalette = null
        kraidSpritemap = null
        dirty = true
    }

    private fun writePng(filePath: String, pixels: IntArray, w: Int, h: Int): Boolean {
        return try {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, pixels, 0, w)
            javax.imageio.ImageIO.write(img, "png", java.io.File(filePath))
        } catch (_: Exception) { false }
    }

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

    fun setPatchWrites(id: String, writes: List<SmPatchWrite>) {
        val patch = project.patches.find { it.id == id } ?: return
        patch.writes.clear()
        patch.writes.addAll(writes.map { PatchWrite(it.offset, it.bytes) })
        dirty = true; patchVersion++
    }

    fun setPatchConfigValue(id: String, value: Int) {
        project.patches.find { it.id == id }?.let { it.configValue = value }
        dirty = true; patchVersion++
    }

    fun setPatchConfigData(id: String, key: String, value: Int) {
        val patch = project.patches.find { it.id == id } ?: return
        val data = patch.configData ?: mutableMapOf()
        data[key] = value
        patch.configData = data
        dirty = true; patchVersion++
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
        PatternLibrary.saveAll(project.patterns)
        return pattern
    }

    fun removePattern(id: String) {
        project.patterns.removeAll { it.id == id }
        if (selectedPatternId == id) selectPattern(null)
        dirty = true; patternVersion++
        PatternLibrary.saveAll(project.patterns)
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
            "builtin_left_gate", "builtin_right_gate",
            "builtin_gate_blue_left", "builtin_gate_blue_right",
            "builtin_gate_pink_left", "builtin_gate_pink_right",
            "builtin_gate_green_left", "builtin_gate_green_right",
            "builtin_gate_yellow_left", "builtin_gate_yellow_right",
            "builtin_door_blue_left", "builtin_door_blue_right",
            "builtin_door_red_left", "builtin_door_red_right",
            "builtin_door_green_left", "builtin_door_green_right",
            "builtin_door_yellow_left", "builtin_door_yellow_right",
            "builtin_save_station", "builtin_energy_refill",
            "builtin_missile_refill", "builtin_chozo_statue"
        )

        // Migrate old incorrect gate/door patterns so they get re-seeded correctly.
        val brokenPatternIds = setOf("builtin_left_gate", "builtin_right_gate")
        val wrongDoorRightIds = setOf(
            "builtin_door_blue_right", "builtin_door_red_right",
            "builtin_door_green_right", "builtin_door_yellow_right"
        )
        val wrongPlmIds = setOf(0xC8A6, 0xC88E, 0xC876, 0xC85E)
        project.patterns.removeAll { pat ->
            (pat.id in brokenPatternIds && pat.builtIn &&
                pat.cells.any { it != null && it.blockType == 0x9 && it.bts == 0x40 }) ||
            (pat.id in wrongDoorRightIds && pat.builtIn &&
                pat.cells.any { it != null && it.plmId in wrongPlmIds })
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

        // Gate helper: 1x4 gate cap with PLM on top cell.
        // The gate PLM (0xC836) extends 4 tiles down from placement and
        // handles all visuals/interaction. Tiles are solid CRE gate caps
        // so the gate blocks passage in the editor preview.
        fun gatePatternCells(plmParam: Int, hFlip: Boolean): List<PatternCell> = listOf(
            PatternCell(0x342, blockType = 0x8, hFlip = hFlip, plmId = 0xC836, plmParam = plmParam),
            PatternCell(0x343, blockType = 0x8, hFlip = hFlip),
            PatternCell(0x343, blockType = 0x8, hFlip = hFlip),
            PatternCell(0x342, blockType = 0x8, hFlip = hFlip, vFlip = true)
        )

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
            // ── Gates: all colors, left and right facing (noFlip: directional PLMs) ──
            addBuiltIn("builtin_gate_blue_left",   "Gate: Blue (Left)",   1, 4, gatePatternCells(0x00, false), noFlip = true)
            addBuiltIn("builtin_gate_blue_right",  "Gate: Blue (Right)",  1, 4, gatePatternCells(0x02, true), noFlip = true)
            addBuiltIn("builtin_gate_pink_left",   "Gate: Pink (Left)",   1, 4, gatePatternCells(0x04, false), noFlip = true)
            addBuiltIn("builtin_gate_pink_right",  "Gate: Pink (Right)",  1, 4, gatePatternCells(0x06, true), noFlip = true)
            addBuiltIn("builtin_gate_green_left",  "Gate: Green (Left)",  1, 4, gatePatternCells(0x08, false), noFlip = true)
            addBuiltIn("builtin_gate_green_right", "Gate: Green (Right)", 1, 4, gatePatternCells(0x0A, true), noFlip = true)
            addBuiltIn("builtin_gate_yellow_left", "Gate: Yellow (Left)", 1, 4, gatePatternCells(0x0C, false), noFlip = true)
            addBuiltIn("builtin_gate_yellow_right","Gate: Yellow (Right)", 1, 4, gatePatternCells(0x0E, true), noFlip = true)

            // Legacy generic gate patterns (kept for backward compat)
            addBuiltIn("builtin_left_gate", "Left Gate (Blue)", 1, 4, gatePatternCells(0x00, false), noFlip = true)
            addBuiltIn("builtin_right_gate", "Right Gate (Blue)", 1, 4, gatePatternCells(0x02, true), noFlip = true)

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
            println("Failed to seed some built-in patterns: ${e.message}")
        }

        dirty = true
        patternVersion++
    }

    fun getSelectedPatch(): SmPatch? = project.patches.find { it.id == selectedPatchId }

    private var patchesSeeded = false

    /** Ensure all default patches exist; loads bundled IPS + hardcoded hex demos. Idempotent. */
    fun seedDefaultPatches() {
        if (patchesSeeded) return
        patchesSeeded = true
        // Remove legacy/duplicate patches from old configs
        val removed = project.patches.removeAll { it.id in LEGACY_PATCH_IDS }
        if (removed) {
            if (selectedPatchId in LEGACY_PATCH_IDS) selectedPatchId = null
            patchVersion++
        }

        val existingIds = project.patches.map { it.id }.toSet()
        var added = 0

        // Collect patches in desired display order: config → hardcoded → bundled IPS
        val ordered = mutableListOf<SmPatch>()

        // 1. GUI config patches (featured at top)
        for (guiPatch in listOf(BEAM_DAMAGE_PATCH, BOSS_STATS_PATCH, PHANTOON_PATCH, ENEMY_STATS_PATCH, ENEMY_DROP_RATE_PATCH, ENEMY_VULNERABILITY_PATCH, SAMUS_PHYSICS_PATCH, BOSS_DEFEATED_PATCH, CONTROLLER_CONFIG_PATCH, CERES_ESCAPE_PATCH)) {
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
                }
            }
        } catch (e: Exception) {
            println("Failed to load bundled patches: ${e.message}")
        }

        // Insert new patches at position 0 so they appear before any user patches
        if (ordered.isNotEmpty()) {
            project.patches.addAll(0, ordered)
            added = ordered.size
        }

        if (added > 0) { patchVersion++ }
    }

    // ─── Tile selection ─────────────────────────────────────────

    fun selectMetatile(index: Int, gridCols: Int = 32) {
        tilesetSelStart = Pair(index % gridCols, index / gridCols)
        tilesetSelEnd = tilesetSelStart
        mapSelStart = null
        mapSelEnd = null
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
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) captureMapSelection()
        toggleHFlip()
    }
    fun flipOrCaptureV() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) captureMapSelection()
        toggleVFlip()
    }
    fun rotateOrCapture() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) captureMapSelection()
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
    }

    fun setBlockType(type: Int) { brush = brush?.copy(blockType = type) }

    /** Update hover info when mouse moves on map. */
    fun updateHover(bx: Int, by: Int) {
        hoverBlockX = bx; hoverBlockY = by
        hoverTileWord = if (bx >= 0 && by >= 0) readBlockWord(bx, by) else 0
    }

    /** Sample (eyedropper): pick the tile at (bx, by) from the map as the current brush. */
    fun sampleTile(bx: Int, by: Int, gridCols: Int = 32) {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val word = readBlockWord(bx, by)
        val metatileIdx = word and 0x3FF
        val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0
        val bt = (word shr 12) and 0xF
        val sampledBts = readBts(bx, by)
        val btsMap = if (sampledBts != 0) mapOf(0L to sampledBts) else emptyMap()
        brush = TileBrush(tiles = listOf(listOf(metatileIdx)), blockType = bt, hFlip = hf, vFlip = vf, btsOverrides = btsMap)
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

    /** Convert the current map selection rectangle into a multi-tile brush. */
    fun captureMapSelection() {
        val s = mapSelStart ?: return
        val e = mapSelEnd ?: return
        val minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        if (minX == maxX && minY == maxY) {
            sampleTile(minX, minY)
            return
        }
        val rows = mutableListOf<List<Int>>()
        val btsMap = mutableMapOf<Long, Int>()
        val btMap = mutableMapOf<Long, Int>()
        val flipMap = mutableMapOf<Long, Int>()
        for (by in minY..maxY) {
            val row = mutableListOf<Int>()
            for (bx in minX..maxX) {
                val word = readBlockWord(bx, by)
                row.add(word and 0x3FF)
                val r = by - minY
                val c = bx - minX
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
        val primaryBt = (readBlockWord(minX, minY) shr 12) and 0xF
        brush = TileBrush(
            tiles = rows,
            blockType = primaryBt,
            blockTypeOverrides = btMap,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
        mapSelStart = null
        mapSelEnd = null
        activeTool = EditorTool.PAINT
    }

    // ─── Project lifecycle ──────────────────────────────────────

    fun initForRom(romPath: String) {
        projectFilePath = romPath.replaceAfterLast('.', "smedit")
        val file = File(projectFilePath)
        if (file.exists()) {
            try {
                project = json.decodeFromString(SmEditProject.serializer(), file.readText())
                val enabledPatches = project.patches.filter { it.enabled }
                println("Loaded project: ${file.absolutePath} (${project.rooms.size} rooms, ${project.patches.size} patches)")
                if (enabledPatches.isNotEmpty()) {
                    println("  Enabled patches: ${enabledPatches.joinToString { "'${it.name}'" }}")
                }
            } catch (e: Exception) {
                println("Failed to load project: ${e.message}")
                project = SmEditProject(romPath = romPath)
            }
        } else {
            project = SmEditProject(romPath = romPath)
        }
        dirty = false
        patchesSeeded = false
        tileGraphics = null
        workingLevelData = null
        originalLevelData = null

        // Clear cached sprite editor state so it reloads from the new ROM/project
        spriteSheetGfx = null
        spriteSheetPalette = null
        phantoonSpritemap = null
        kraidSpritemap = null
        kraidSheetGfx = null
        kraidSheetPalette = null

        _roomEditOrder.clear()
        _editCounter = 0L
        for ((key, edits) in project.rooms) {
            if (!edits.hasEdits) continue
            val rid = key.toIntOrNull(16) ?: continue
            _roomEditOrder[rid] = ++_editCounter
        }

        romVersion++

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

    internal fun initTestLevel(blocksWide: Int, blocksTall: Int) {
        val totalTiles = blocksWide * blocksTall
        val layer1Bytes = totalTiles * 2
        val data = ByteArray(2 + layer1Bytes + totalTiles).also {
            it[0] = (layer1Bytes and 0xFF).toByte()
            it[1] = ((layer1Bytes shr 8) and 0xFF).toByte()
        }
        originalLevelData = data.copyOf()
        workingLevelData = data
        workingBlocksWide = blocksWide
        workingBlocksTall = blocksTall
        undoStack.clear()
        redoStack.clear()
        undoVersion++
    }

    internal fun setBrushForTest(b: TileBrush?) { brush = b }
    internal fun setRoomIdForTest(id: Int) { currentRoomId = id }

    // ─── Working level data ─────────────────────────────────────

    fun loadRoom(roomId: Int, romParser: RomParser, room: com.supermetroid.editor.data.Room) {
        currentRoomId = roomId
        currentTilesetId = room.tileset
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(room.tileset)) {
            applyCustomGfxToTileGraphics(tg, room.tileset)
            tileGraphics = tg
        }
        val levelData = romParser.decompressLZ2(room.levelDataPtr)
        originalLevelData = levelData.copyOf()
        workingLevelData = levelData.copyOf()
        workingBlocksWide = room.width * 16
        workingBlocksTall = room.height * 16

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

        // Load scroll data for this room
        _originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
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
        if (savedRoom != null) {
            // Replay saved tile edits
            if (savedRoom.operations.isNotEmpty()) {
                var count = 0
                for (op in savedRoom.operations) {
                    for (edit in op.edits) {
                        writeBlockWord(edit.blockX, edit.blockY, edit.newBlockWord)
                        writeBts(edit.blockX, edit.blockY, edit.newBts)
                        count++
                    }
                    undoStack.add(op)
                }
                undoVersion++
                println("Replayed $count saved edits for room 0x$roomKey")
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
            // Replay saved scroll changes
            for (sc in savedRoom.scrollChanges) {
                val idx = sc.screenY * room.width + sc.screenX
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
        // Bump render version without marking room as user-edited
        _editVersionState.value++
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

    // ─── Paint / Erase / Fill ───────────────────────────────────

    fun beginStroke() {
        pendingEdits.clear()
        pendingPositions.clear()
    }

    /** Paint the full brush at map position (bx, by). Returns true if anything changed. */
    fun paintAt(bx: Int, by: Int): Boolean {
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
                // Apply pattern PLMs if present
                val plm = b.plmAt(r, c)
                if (plm != null && plm.first != 0) {
                    addPlm(plm.first, tx, ty, plm.second)
                }
            }
        }
        if (changed) editVersion++
        return changed
    }

    fun eraseAt(bx: Int, by: Int): Boolean {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
        val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
        if (pendingPositions.contains(key)) return false
        val oldWord = readBlockWord(bx, by)
        val oldBts = readBts(bx, by)
        val origWord = readOriginalBlockWord(bx, by)
        val origBts = readOriginalBts(bx, by)
        if (oldWord == origWord && oldBts == origBts) return false
        writeBlockWord(bx, by, origWord)
        writeBts(bx, by, origBts)
        pendingEdits.add(TileEdit(bx, by, oldWord, origWord, oldBts, origBts))
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

    private fun autoAssignParam(plmId: Int, param: Int): Int = when {
        param == 0 && RomParser.isItemPlm(plmId) -> {
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
                if (RomParser.isItemPlm(plm.id) && plm.param > 0) usedIndices.add(plm.param)
            }
            // Vanilla items use 0x00-0x50; search 0x51-0x1FF (431 slots)
            var idx = 0x51
            while (idx in usedIndices && idx <= 0x1FF) idx++
            if (idx > 0x1FF) {
                println("WARN: item collection bit pool exhausted (>431 items)")
                idx = 0x200
            }
            idx
        }
        plmId == 0xB76F && param == 0x8000 -> {
            val usedSaveIndices = mutableSetOf<Int>()
            for (plm in _workingPlms) {
                if (plm.id == 0xB76F) usedSaveIndices.add(plm.param and 0xFF)
            }
            for ((_, roomEdits) in project.rooms) {
                for (change in roomEdits.plmChanges) {
                    if (change.action == "add" && change.plmId == 0xB76F) usedSaveIndices.add(change.param and 0xFF)
                }
            }
            var idx = 0
            while (idx in usedSaveIndices && idx <= 0x0F) idx++
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

        val name = RomParser.plmDisplayName(plmId)
        val op = EditOperation("Add $name ($x,$y)", plmAdds = listOf(addChange), plmRemoves = removedChanges)
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    fun removePlm(x: Int, y: Int, plmId: Int) {
        val removed = _workingPlms.filter { it.x == x && it.y == y && it.id == plmId }
        _workingPlms.removeAll { it.x == x && it.y == y && it.id == plmId }
        val changes = removed.map { PlmChange("remove", it.id, it.x, it.y, it.param) }
        for (c in changes) project.getOrCreateRoom(currentRoomId).plmChanges.add(c)

        val name = RomParser.plmDisplayName(plmId)
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

    // ─── State data editing (tileset, music, BG scrolling) ──────

    fun setStateDataChange(change: StateDataChange) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.stateDataChange = change
        dirty = true
        editVersion++
    }

    /** Flood fill: replace all connected tiles matching the one at (bx, by) with brush. */
    fun floodFill(bx: Int, by: Int): Boolean {
        val b = brush ?: return false
        if (b.cols != 1 || b.rows != 1) return false  // fill only works with 1×1 brush
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
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

    fun endStroke() {
        if (pendingEdits.isEmpty()) return
        val desc = when (activeTool) {
            EditorTool.PAINT -> "Paint ${pendingEdits.size} tile(s)"
            EditorTool.FILL -> "Fill ${pendingEdits.size} tile(s)"
            EditorTool.ERASE -> "Erase ${pendingEdits.size} tile(s)"
            EditorTool.SAMPLE -> "Sample"
            EditorTool.SELECT -> "Select"
        }
        val op = EditOperation(desc, pendingEdits.toList())
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        project.getOrCreateRoom(currentRoomId).operations.add(op)
        dirty = true
        editVersion++
        pendingEdits.clear()
        pendingPositions.clear()
    }

    // ─── Undo / Redo ────────────────────────────────────────────

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val op = undoStack.removeAt(undoStack.lastIndex)
        val roomEdits = project.getOrCreateRoom(currentRoomId)

        // Undo tile edits
        for (edit in op.edits.reversed()) {
            writeBlockWord(edit.blockX, edit.blockY, edit.oldBlockWord)
            writeBts(edit.blockX, edit.blockY, edit.oldBts)
        }
        if (op.edits.isNotEmpty() && roomEdits.operations.isNotEmpty())
            roomEdits.operations.removeAt(roomEdits.operations.lastIndex)

        // Undo PLM adds (reverse = remove them)
        for (plm in op.plmAdds) {
            _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
            roomEdits.plmChanges.add(PlmChange("remove", plm.plmId, plm.x, plm.y, plm.param))
        }
        // Undo PLM removes (reverse = re-add them)
        for (plm in op.plmRemoves) {
            _workingPlms.add(RomParser.PlmEntry(plm.plmId, plm.x, plm.y, plm.param))
            roomEdits.plmChanges.add(PlmChange("add", plm.plmId, plm.x, plm.y, plm.param))
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
            writeBlockWord(edit.blockX, edit.blockY, edit.newBlockWord)
            writeBts(edit.blockX, edit.blockY, edit.newBts)
        }
        if (op.edits.isNotEmpty()) roomEdits.operations.add(op)

        // Redo PLM adds
        for (plm in op.plmAdds) {
            _workingPlms.add(RomParser.PlmEntry(plm.plmId, plm.x, plm.y, plm.param))
            roomEdits.plmChanges.add(PlmChange("add", plm.plmId, plm.x, plm.y, plm.param))
        }
        // Redo PLM removes
        for (plm in op.plmRemoves) {
            _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
            roomEdits.plmChanges.add(PlmChange("remove", plm.plmId, plm.x, plm.y, plm.param))
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

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * Save project to .smedit file. When romParser is provided, also export
     * custom tileset graphics as PNGs to a project-specific folder
     * ({romBase}_smedit/) so different ROMs don't overwrite each other.
     */
    fun saveProject(romParser: RomParser? = null): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            // Prune rooms with no actual edits before saving
            val emptyKeys = project.rooms.entries.filter { !it.value.hasEdits }.map { it.key }
            for (k in emptyKeys) project.rooms.remove(k)

            File(projectFilePath).writeText(json.encodeToString(SmEditProject.serializer(), project))
            dirty = false
            println("Project saved: $projectFilePath")
            postStatus("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(it) }
            PatternLibrary.saveAll(project.patterns)
            true
        } catch (e: Exception) { println("Save failed: ${e.message}"); postStatus("Save failed: ${e.message}"); false }
    }

    /** Export custom tileset graphics as PNGs to project folder. Folder is per-ROM so projects don't overwrite each other. */
    private fun exportCustomGfxPngs(romParser: RomParser) {
        val gfx = project.customGfx
        val hasVar = gfx.varGfx.isNotEmpty()
        val hasCre = gfx.creGfx != null
        if (!hasVar && !hasCre) return
        val projectFile = File(projectFilePath)
        val folder = File(projectFile.parentFile, "${projectFile.nameWithoutExtension}_smedit")
        folder.mkdirs()
        val tg = TileGraphics(romParser)
        for ((tilesetId, b64) in gfx.varGfx) {
            if (!tg.loadTileset(tilesetId.toIntOrNull() ?: continue)) continue
            try {
                tg.applyCustomVarGfx(java.util.Base64.getDecoder().decode(b64))
                val result = tg.renderTileSheet(0, tg.getVarTileCount())
                if (result != null) {
                    val (pixels, w, h) = result
                    val out = File(folder, "ure_$tilesetId.png")
                    if (writePng(out.absolutePath, pixels, w, h)) println("Exported $out")
                }
            } catch (_: Exception) {}
        }
        if (hasCre && gfx.creGfx != null) {
            if (tg.loadTileset(0)) {
                try {
                    tg.applyCustomCreGfx(java.util.Base64.getDecoder().decode(gfx.creGfx))
                    val result = tg.renderTileSheet(tg.getCreOffset(), tg.getCreTileCount())
                    if (result != null) {
                        val (pixels, w, h) = result
                        val out = File(folder, "cre.png")
                        if (writePng(out.absolutePath, pixels, w, h)) println("Exported $out")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ─── Export: patch ROM ──────────────────────────────────────

    /**
     * Build the export filename suffix from version and build name.
     * Examples: "v1.0", "MyHack-v2.1", "Kaizo-v1.0"
     */
    private fun exportSuffix(): String {
        val version = "v${project.versionMajor}.${project.versionMinor}"
        val build = project.buildName.trim()
        return if (build.isNotEmpty()) "$build-$version" else version
    }

    fun exportToRom(romParser: RomParser): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null
        saveProject(romParser)
        println("[EXPORT] Starting export — romPath=$romPath, romSize=${romParser.getRomData().size}")
        println("[EXPORT] Project spriteTileBlocks keys: ${project.customGfx.spriteTileBlocks.keys}")
        val romData = romParser.getRomData().copyOf()
        val roomsPatched = mutableSetOf<String>()

        // Apply patches FIRST so free-space scanners see any code/data
        // that patches write into otherwise-empty banks (e.g. skip_intro
        // writes custom ASM into bank $A1 free space).
        var patchesApplied = 0
        val enabledCount = project.patches.count { it.enabled }
        val disabledCount = project.patches.size - enabledCount
        println("[EXPORT] Patches: $enabledCount enabled, $disabledCount disabled (${project.patches.size} total)")
        for (patch in project.patches) {
            if (!patch.enabled) continue
            println("[EXPORT] Applying patch: '${patch.name}' [${patch.id}] configType=${patch.configType ?: "hex"}")
            if (patch.configType == "ceres_escape_seconds") {
                val totalSecs = (patch.configValue ?: 60).coerceIn(15, 600)
                val mins = totalSecs / 60
                val secs = totalSecs % 60
                val secsBcd = ((secs / 10) shl 4) or (secs % 10)
                val minsBcd = ((mins / 10) shl 4) or (mins % 10)
                val off = romParser.snesToPc(CERES_TIMER_OPERAND_SNES)
                if (off + 1 < romData.size) {
                    romData[off] = secsBcd.toByte()
                    romData[off + 1] = minsBcd.toByte()
                }
                println("[EXPORT]   Ceres timer: ${mins}m${secs}s")
            } else if (patch.configType == "beam_damage") {
                val data = patch.configData ?: continue
                var beamCount = 0
                for (beam in ALL_BEAMS) {
                    val dmg = data[beam.key] ?: continue
                    val charged = dmg * 3
                    val pcUncharged = romParser.snesToPc(beam.snesAddress)
                    if (pcUncharged + 1 < romData.size) {
                        romData[pcUncharged] = (dmg and 0xFF).toByte()
                        romData[pcUncharged + 1] = ((dmg shr 8) and 0xFF).toByte()
                    }
                    val pcCharged = romParser.snesToPc(beam.chargedSnesAddress)
                    if (pcCharged + 1 < romData.size) {
                        romData[pcCharged] = (charged and 0xFF).toByte()
                        romData[pcCharged + 1] = ((charged shr 8) and 0xFF).toByte()
                    }
                    beamCount++
                }
                println("[EXPORT]   Beam damage: $beamCount beams modified")
            } else if (patch.configType == "boss_stats") {
                val data = patch.configData ?: continue
                var fieldCount = 0
                for (field in ALL_BOSS_FIELDS) {
                    val value = data[field.key] ?: continue
                    val pc = romParser.snesToPc(field.snesAddress) + field.offset
                    if (pc + 1 < romData.size) {
                        romData[pc] = (value and 0xFF).toByte()
                        romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    fieldCount++
                }
                println("[EXPORT]   Boss stats: $fieldCount fields modified")
            } else if (patch.configType == "phantoon") {
                val data = patch.configData ?: continue
                var fieldCount = 0
                for (field in ALL_PHANTOON_FIELDS) {
                    val value = data[field.key] ?: continue
                    val pc = romParser.snesToPc(field.snesAddress)
                    if (pc + 1 < romData.size) {
                        romData[pc] = (value and 0xFF).toByte()
                        romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    fieldCount++
                }
                println("[EXPORT]   Phantoon behavior: $fieldCount fields modified")
            } else if (patch.configType == "enemy_stats") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (e in ENEMY_DEFS) {
                    val hp = data["${e.key}_hp"]
                    val dmg = data["${e.key}_dmg"]
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    if (hp != null) {
                        val pc = romParser.snesToPc(snesAddr) + 4
                        if (pc + 1 < romData.size) {
                            romData[pc] = (hp and 0xFF).toByte()
                            romData[pc + 1] = ((hp shr 8) and 0xFF).toByte()
                        }
                        modCount++
                    }
                    if (dmg != null) {
                        val pc = romParser.snesToPc(snesAddr) + 6
                        if (pc + 1 < romData.size) {
                            romData[pc] = (dmg and 0xFF).toByte()
                            romData[pc + 1] = ((dmg shr 8) and 0xFF).toByte()
                        }
                        modCount++
                    }
                }
                println("[EXPORT]   Enemy stats: $modCount values modified")
            } else if (patch.configType == "enemy_drops") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (e in ENEMY_DEFS) {
                    // Resolve drop table pointer: species header +$3A → bank $B4
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    val headerPc = romParser.snesToPc(snesAddr)
                    if (headerPc + 0x3C > romData.size) continue
                    val ptr = (romData[headerPc + 0x3A].toInt() and 0xFF) or
                            ((romData[headerPc + 0x3B].toInt() and 0xFF) shl 8)
                    if (ptr == 0 || ptr == 0xFFFF) continue
                    val dropPc = romParser.snesToPc(0xB40000 or ptr)
                    if (dropPc + 6 > romData.size) continue
                    for (i in 0..5) {
                        val value = data["${e.key}_drop$i"] ?: continue
                        romData[dropPc + i] = (value and 0xFF).toByte()
                        modCount++
                    }
                }
                println("[EXPORT]   Enemy drop rates: $modCount values modified")
            } else if (patch.configType == "enemy_vuln") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (e in ENEMY_DEFS) {
                    // Resolve resistance table pointer: species header +$3C → bank $B4
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    val headerPc = romParser.snesToPc(snesAddr)
                    if (headerPc + 0x3E > romData.size) continue
                    val ptr = (romData[headerPc + 0x3C].toInt() and 0xFF) or
                            ((romData[headerPc + 0x3D].toInt() and 0xFF) shl 8)
                    if (ptr == 0 || ptr == 0xFFFF) continue
                    val resPc = romParser.snesToPc(0xB40000 or ptr)
                    if (resPc + 22 > romData.size) continue
                    for (i in 0..21) {
                        val value = data["${e.key}_vuln$i"] ?: continue
                        romData[resPc + i] = (value and 0xFF).toByte()
                        modCount++
                    }
                }
                println("[EXPORT]   Enemy vulnerabilities: $modCount values modified")
            } else if (patch.configType == "samus_physics") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (field in ALL_PHYSICS_FIELDS) {
                    val value = data[field.key] ?: continue
                    val pc = field.pcOffset
                    if (pc < romData.size) {
                        romData[pc] = (value and 0xFF).toByte()
                    }
                    modCount++
                }
                println("[EXPORT]   Samus physics: $modCount values modified")
            } else if (patch.configType == "controller_config") {
                val data = patch.configData ?: continue
                var slotCount = 0
                for (slot in CONTROLLER_SLOTS) {
                    val value = data[slot.key] ?: continue
                    val off = CONTROLLER_TABLE_PC + slot.tableIndex * 2
                    if (off + 1 < romData.size) {
                        romData[off] = (value and 0xFF).toByte()
                        romData[off + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    slotCount++
                }
                println("[EXPORT]   Controller config: $slotCount buttons remapped")
            } else if (patch.configType == "boss_defeated" || patch.configType == "hyper_beam") {
                println("[EXPORT]   (deferred to combined per-frame hook)")
            } else {
                val totalBytes = patch.writes.sumOf { it.bytes.size }
                for (write in patch.writes) {
                    val off = write.offset.toInt()
                    for ((i, b) in write.bytes.withIndex()) {
                        if (off + i < romData.size) romData[off + i] = b.toByte()
                    }
                }
                println("[EXPORT]   Hex writes: ${patch.writes.size} records, $totalBytes bytes")
            }
            patchesApplied++
        }

        // Combined per-frame hook: boss-defeated + hyper beam + infinite blue suit
        // Writes a single routine at $DF:F040 (PC $2FF040) and hooks $82:896E.
        run {
            val enabledBosses = mutableSetOf<String>()
            var hyperBeam = false
            var infiniteBlueSuit = false
            for (patch in project.patches) {
                if (!patch.enabled) continue
                if (patch.configType == "boss_defeated") {
                    val data = patch.configData ?: continue
                    enabledBosses.addAll(data.filter { it.value != 0 }.keys)
                }
                if (patch.configType == "hyper_beam") hyperBeam = true
                if (patch.id == "bundled_infinite_blue_suit") infiniteBlueSuit = true
            }
            if (enabledBosses.isNotEmpty() || hyperBeam || infiniteBlueSuit) {
                println("[EXPORT] Per-frame hook active: bosses=${enabledBosses.ifEmpty { "none" }}, hyperBeam=$hyperBeam, infiniteBlueSuit=$infiniteBlueSuit")
                val code = mutableListOf<Int>()
                // Chain to original: JSL $8289EF
                code.addAll(listOf(0x22, 0xEF, 0x89, 0x82))
                code.add(0x08) // PHP
                code.addAll(listOf(0xC2, 0x20)) // REP #$20

                // Skip flag-setting in Mother Brain's room ($8F:DD58).
                // MB's AI uses event flags at $D820-$D821 for its multi-phase
                // state machine (MB1→MB2→Baby Metroid→escape). Force-ORing
                // boss/Tourian event bits every frame prevents these transitions.
                // Flags are already in WRAM from prior rooms, so skipping here is safe.
                code.addAll(listOf(0xAD, 0x9B, 0x07))         // LDA $079B (room_ptr)
                code.addAll(listOf(0xC9, 0x58, 0xDD))         // CMP #$DD58
                // BEQ to the PLP;RTL at the end — offset will be patched below
                val beqPos = code.size
                code.addAll(listOf(0xF0, 0x00))                // BEQ .done (placeholder)

                // Boss flags + associated event flags (long addressing for WRAM from bank $DF)
                if (enabledBosses.isNotEmpty()) {
                    val byAddr = mutableMapOf<Int, Int>()
                    for (flag in BOSS_FLAG_DEFS) {
                        if (flag.key in enabledBosses) {
                            byAddr[flag.wramAddr] = (byAddr[flag.wramAddr] ?: 0) or flag.bit
                        }
                    }

                    // Per-boss golden-statue events ($7E:D820-D821 event bitfield)
                    val bossStatueEvents = mapOf(
                        "phantoon" to (0xD820 to 0x40), // Event 0x06
                        "ridley"   to (0xD820 to 0x80), // Event 0x07
                        "draygon"  to (0xD821 to 0x01), // Event 0x08
                        "kraid"    to (0xD821 to 0x02), // Event 0x09
                    )
                    for ((boss, addrBit) in bossStatueEvents) {
                        if (boss in enabledBosses) {
                            byAddr[addrBit.first] = (byAddr[addrBit.first] ?: 0) or addrBit.second
                        }
                    }
                    val mainBosses = setOf("kraid", "phantoon", "ridley", "draygon")
                    if (mainBosses.all { it in enabledBosses }) {
                        byAddr[0xD821] = (byAddr[0xD821] ?: 0) or 0x04 // Event 0x0A: Path to Tourian open
                    }

                    for ((addr, bits) in byAddr) {
                        code.addAll(listOf(0xAF, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))
                        code.addAll(listOf(0x09, bits and 0xFF, 0x00))
                        code.addAll(listOf(0x8F, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))
                    }
                }

                // Hyper beam (long addressing: STA $7E:0A76)
                if (hyperBeam) {
                    code.addAll(listOf(0xA9, 0x00, 0x80))             // LDA #$8000
                    code.addAll(listOf(0x8F, 0x76, 0x0A, 0x7E))      // STA $7E0A76
                }

                // Infinite blue suit: force dash counter to $0400 every frame
                if (infiniteBlueSuit) {
                    code.addAll(listOf(0xA9, 0x00, 0x04))             // LDA #$0400
                    code.addAll(listOf(0x8F, 0x3E, 0x0B, 0x7E))      // STA $7E0B3E
                }

                code.add(0x28) // PLP
                code.add(0x6B) // RTL

                // Patch the BEQ offset to jump to PLP (skip the flag-setting body)
                val plpPos = code.size - 2  // position of PLP
                val branchOffset = plpPos - (beqPos + 2)  // +2 for the BEQ instruction size
                if (branchOffset in 0..127) {
                    code[beqPos + 1] = branchOffset
                }

                // Write payload at PC $2FF040
                for ((i, b) in code.withIndex()) {
                    val addr = 0x2FF040 + i
                    if (addr < romData.size) romData[addr] = b.toByte()
                }
                // Hook $82:896E (PC $1096E): JSL $DFF040
                val hook = listOf(0x22, 0x40, 0xF0, 0xDF)
                for ((i, b) in hook.withIndex()) {
                    val addr = 0x1096E + i
                    if (addr < romData.size) romData[addr] = b.toByte()
                }
                println("[EXPORT]   Per-frame hook: ${code.size} bytes at \$DF:F040, hook at \$82:896E")
            } else {
                println("[EXPORT] Per-frame hook: not needed (no boss flags, hyper beam, or blue suit)")
            }
        }

        // Free space allocator for bank $8F (PLM sets live here).
        // Scanned AFTER patches so we don't hand out space a patch already uses.
        val bank8FEnd = romParser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = romParser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            freePtr--
        }
        freePtr++

        // Free space allocator for bank $A1 (enemy population sets).
        val bankA1End = romParser.snesToPc(0xA1FFFF) + 1
        val bankA1Start = romParser.snesToPc(0xA18000)
        var enemyFreePtr = bankA1End
        while (enemyFreePtr > bankA1Start) {
            val b = romData[enemyFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            enemyFreePtr--
        }
        enemyFreePtr++

        // Free space allocator for bank $B4 (enemy GFX sets).
        // Must be computed ONCE and incremented — rescanning per-room fails
        // because written GFX data contains 0xFF (species IDs, FFFF terminator)
        // and 0x00 (palette high bytes), which a rescan treats as free space,
        // causing subsequent rooms to overwrite earlier relocated GFX sets.
        //
        // The backward scan also absorbs any FFFF terminator at the boundary
        // (both bytes are 0xFF). We skip forward +2 to preserve it — otherwise
        // the last vanilla GFX set loses its terminator and the engine reads
        // garbage entries past it, corrupting VRAM and palettes.
        val bankB4End = romParser.snesToPc(0xB4FFFF) + 1
        val bankB4Start = romParser.snesToPc(0xB48000)
        var gfxFreePtr = bankB4End
        while (gfxFreePtr > bankB4Start) {
            val b = romData[gfxFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            gfxFreePtr--
        }
        gfxFreePtr++
        val rawGfxFreePtr = gfxFreePtr
        // The scan absorbed any FFFF terminator at the boundary; skip past it.
        // Cost: at most 2 wasted bytes if there was no terminator.
        if (gfxFreePtr + 2 <= bankB4End) gfxFreePtr += 2
        println("[EXPORT] B4 free space: raw=0x${rawGfxFreePtr.toString(16)}, guarded=0x${gfxFreePtr.toString(16)} (+2 terminator guard)")

        // Free space tracker for level data banks ($C0-$CE).
        // Each bank is scanned from the end to find trailing 0xFF padding.
        val levelBankFree = mutableMapOf<Int, Int>()  // bank -> next free PC offset
        fun getLevelBankFreePtr(bank: Int): Int {
            return levelBankFree.getOrPut(bank) {
                val bankEnd = romParser.snesToPc((bank shl 16) or 0xFFFF) + 1
                val bankStart = romParser.snesToPc((bank shl 16) or 0x8000)
                var ptr = bankEnd
                while (ptr > bankStart) {
                    if ((romData[ptr - 1].toInt() and 0xFF) != 0xFF) break
                    ptr--
                }
                ptr + 1
            }
        }

        for ((roomKey, roomEdits) in project.rooms) {
            val hasTileEdits = roomEdits.operations.isNotEmpty()
            val hasPlmEdits = roomEdits.plmChanges.isNotEmpty()
            val hasDoorEdits = roomEdits.doorChanges.isNotEmpty()
            val hasEnemyEdits = roomEdits.enemyChanges.isNotEmpty()
            val hasScrollEdits = roomEdits.scrollChanges.isNotEmpty()
            val hasFxEdits = roomEdits.fxChange != null
            val hasStateEdits = roomEdits.stateDataChange != null
            val hasHeaderEdits = roomEdits.roomHeaderChange != null
            if (!hasTileEdits && !hasPlmEdits && !hasDoorEdits && !hasEnemyEdits &&
                !hasScrollEdits && !hasFxEdits && !hasStateEdits && !hasHeaderEdits) continue
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue

            // Patch tile data — apply edits to ALL states' level data so that
            // non-default states (boss-dead, escape, etc.) also reflect tile changes.
            // Without this, rooms with multiple states show the original layout when
            // a non-default state is active, causing phantom door blocks/caps.
            if (hasTileEdits && room.levelDataPtr != 0) {
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val bw = room.width * 16

                // Group states by their level data pointer
                val ptrToStates = mutableMapOf<Int, MutableList<Int>>()
                for (stateOffset in allStateOffsets) {
                    val lvlPtr = (romData[stateOffset].toInt() and 0xFF) or
                            ((romData[stateOffset + 1].toInt() and 0xFF) shl 8) or
                            ((romData[stateOffset + 2].toInt() and 0xFF) shl 16)
                    if (lvlPtr != 0) ptrToStates.getOrPut(lvlPtr) { mutableListOf() }.add(stateOffset)
                }

                if (ptrToStates.size > 1) {
                    println("Room 0x$roomKey: ${ptrToStates.size} distinct level data pointers across ${allStateOffsets.size} states — applying edits to ALL")
                }

                for ((lvlPtr, statesForPtr) in ptrToStates) {
                    val (originalData, origSize) = romParser.decompressLZ2WithSize(lvlPtr)
                    val editedData = originalData.copyOf()
                    val layer1Size = (editedData[0].toInt() and 0xFF) or ((editedData[1].toInt() and 0xFF) shl 8)
                    for (op in roomEdits.operations) for (edit in op.edits) {
                        val idx = edit.blockY * bw + edit.blockX; val off = 2 + idx * 2
                        if (off + 1 < editedData.size) {
                            editedData[off] = (edit.newBlockWord and 0xFF).toByte()
                            editedData[off + 1] = ((edit.newBlockWord shr 8) and 0xFF).toByte()
                        }
                        val btsOff = 2 + layer1Size + idx
                        if (btsOff < editedData.size) editedData[btsOff] = edit.newBts.toByte()
                    }
                    val compressed = lz5Compress(editedData)

                    // LZ5 round-trip verification
                    val roundTripped = LZ5Compressor.decompress(compressed)
                    if (!roundTripped.contentEquals(editedData)) {
                        val diffIdx = editedData.indices.firstOrNull { roundTripped.getOrNull(it) != editedData[it] }
                        println("ERROR: LZ5 round-trip FAILED for room 0x$roomKey lvlPtr=\$${lvlPtr.toString(16)}! Size: orig=${editedData.size} rt=${roundTripped.size}, first diff at byte $diffIdx")
                    }

                    val pcOff = romParser.snesToPc(lvlPtr)
                    if (compressed.size <= origSize) {
                        System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
                        for (i in compressed.size until origSize) romData[pcOff + i] = 0xFF.toByte()
                    } else {
                        val origBank = (lvlPtr shr 16) and 0xFF
                        val banksToTry = listOf(origBank) +
                                (0xCE downTo 0xC0).filter { it != origBank }
                        var relocated = false
                        for (tryBank in banksToTry) {
                            val bEnd = romParser.snesToPc((tryBank shl 16) or 0xFFFF) + 1
                            val freeStart = getLevelBankFreePtr(tryBank)
                            if (freeStart + compressed.size <= bEnd) {
                                System.arraycopy(compressed, 0, romData, freeStart, compressed.size)
                                val newSnes = romParser.pcToSnes(freeStart)
                                levelBankFree[tryBank] = freeStart + compressed.size
                                for (stateOffset in statesForPtr) {
                                    romData[stateOffset] = (newSnes and 0xFF).toByte()
                                    romData[stateOffset + 1] = ((newSnes shr 8) and 0xFF).toByte()
                                    romData[stateOffset + 2] = ((newSnes shr 16) and 0xFF).toByte()
                                }
                                for (i in pcOff until pcOff + origSize) romData[i] = 0xFF.toByte()
                                println("Room 0x$roomKey: relocated level data \$${lvlPtr.toString(16)} to \$${tryBank.toString(16).uppercase()}:${(newSnes and 0xFFFF).toString(16).uppercase()} (${compressed.size} bytes, updated ${statesForPtr.size} state(s))")
                                relocated = true
                                break
                            }
                        }
                        if (!relocated) {
                            println("WARN: Room 0x$roomKey lvlPtr=\$${lvlPtr.toString(16)} compressed ${compressed.size} > orig $origSize and no free space — skipped")
                        }
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Patch PLM sets — rooms can have multiple state conditions (E629, E612, E5E6, etc.)
            // each pointing to a DIFFERENT PLM set. We must apply user changes to every
            // distinct PLM set so items/stations appear regardless of which state is active.
            if (hasPlmEdits) {
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val distinctPlmPtrs = mutableSetOf<Int>()
                for (stateOffset in allStateOffsets) {
                    val plmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                            ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                    if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                    distinctPlmPtrs.add(plmPtr)
                }

                data class PlmSetData(val plmSetPtr: Int, val originalSize: Int, val plms: MutableList<RomParser.PlmEntry>)
                val plmSets = mutableListOf<PlmSetData>()

                for (plmSetPtr in distinctPlmPtrs) {
                    val originalPlms = romParser.parsePlmSet(plmSetPtr)
                    val modifiedPlms = originalPlms.toMutableList()
                    for (change in roomEdits.plmChanges) {
                        when (change.action) {
                            "add" -> modifiedPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                            "remove" -> modifiedPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                        }
                    }
                    val seen = mutableSetOf<Long>()
                    val deduped = mutableListOf<RomParser.PlmEntry>()
                    for (plm in modifiedPlms.reversed()) {
                        val key = (plm.x.toLong() shl 16) or plm.y.toLong()
                        if (RomParser.isItemPlm(plm.id)) {
                            if (key in seen) continue
                            seen.add(key)
                        }
                        deduped.add(plm)
                    }
                    deduped.reverse()
                    plmSets.add(PlmSetData(plmSetPtr, originalPlms.size * 6 + 2, deduped))
                }

                // Write all PLM sets to ROM
                for (psd in plmSets) {
                    val newSize = psd.plms.size * 6 + 2
                    val plmPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or psd.plmSetPtr)

                    val writePc: Int
                    if (newSize <= psd.originalSize) {
                        writePc = plmPc
                    } else if (freePtr + newSize <= bank8FEnd) {
                        writePc = freePtr
                        freePtr += newSize
                        val newSnes = romParser.pcToSnes(writePc)
                        val newPtr = newSnes and 0xFFFF
                        var updatedStates = 0
                        for (stateOffset in allStateOffsets) {
                            val existingPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                                    ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                            if (existingPtr == psd.plmSetPtr) {
                                romData[stateOffset + 20] = (newPtr and 0xFF).toByte()
                                romData[stateOffset + 21] = ((newPtr shr 8) and 0xFF).toByte()
                                updatedStates++
                            }
                        }
                        println("Room 0x$roomKey: relocated PLM set 0x${psd.plmSetPtr.toString(16)} to 0x${newSnes.toString(16)} (updated $updatedStates states)")
                    } else {
                        println("WARN: Room 0x$roomKey no free space for expanded PLM set 0x${psd.plmSetPtr.toString(16)} — skipped")
                        continue
                    }

                    var offset = writePc
                    for (plm in psd.plms) {
                        romData[offset] = (plm.id and 0xFF).toByte()
                        romData[offset + 1] = ((plm.id shr 8) and 0xFF).toByte()
                        romData[offset + 2] = plm.x.toByte()
                        romData[offset + 3] = plm.y.toByte()
                        romData[offset + 4] = (plm.param and 0xFF).toByte()
                        romData[offset + 5] = ((plm.param shr 8) and 0xFF).toByte()
                        offset += 6
                        val name = RomParser.plmDisplayName(plm.id, plm.param)
                        println("  PLM: $name (0x${plm.id.toString(16)}) at (${plm.x},${plm.y}) param=0x${plm.param.toString(16)}")
                    }
                    romData[offset] = 0; romData[offset + 1] = 0
                    if (writePc == plmPc) {
                        for (i in offset + 2 until plmPc + psd.originalSize) romData[i] = 0
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Patch door entries (last change per index wins)
            // The full 12-byte door definition is written as-is from the user's
            // DoorChange, including the orientation byte with cap flag (bit 2).
            // doorCapCode and entryCode are auto-set from vanilla when the
            // destination changes, so the cap flag is safe to preserve.
            if (hasDoorEdits && room.doorOut != 0 && room.doorOut != 0xFFFF) {
                val byIndex = roomEdits.doorChanges.groupBy { it.doorIndex }
                for ((doorIndex, changes) in byIndex) {
                    val dc = changes.last()
                    val entryPc = romParser.doorEntryPcOffset(room.doorOut, doorIndex) ?: continue
                    if (entryPc + 11 >= romData.size) continue

                    val orientation = (dc.bitflag shr 8) and 0xFF
                    val dirName = arrayOf("Right","Left","Down","Up")[orientation and 3]
                    val capStr = if (orientation and 0x04 != 0) " +cap" else ""
                    val capX = dc.doorCapCode and 0xFF
                    val capY = (dc.doorCapCode shr 8) and 0xFF
                    println("Room 0x$roomKey door $doorIndex: orient=$orientation($dirName$capStr) cap=($capX,$capY) entry=0x${dc.entryCode.toString(16)}")

                    val destRoom = romParser.readRoomHeader(dc.destRoomPtr)
                    if (destRoom != null) {
                        val maxX = destRoom.width * 16
                        val maxY = destRoom.height * 16
                        if (capX >= maxX || capY >= maxY) {
                            println("WARN: Room 0x$roomKey door $doorIndex cap position ($capX,$capY) " +
                                "is out of bounds for destination room 0x${dc.destRoomPtr.toString(16)} " +
                                "(${destRoom.width}x${destRoom.height} screens = ${maxX}x${maxY} blocks). " +
                                "This will spawn a corrupt closing door PLM!")
                        }
                    }

                    romData[entryPc] = (dc.destRoomPtr and 0xFF).toByte()
                    romData[entryPc + 1] = ((dc.destRoomPtr shr 8) and 0xFF).toByte()
                    romData[entryPc + 2] = (dc.bitflag and 0xFF).toByte()
                    romData[entryPc + 3] = orientation.toByte()
                    romData[entryPc + 4] = (dc.doorCapCode and 0xFF).toByte()
                    romData[entryPc + 5] = ((dc.doorCapCode shr 8) and 0xFF).toByte()
                    romData[entryPc + 6] = (dc.screenX and 0xFF).toByte()
                    romData[entryPc + 7] = (dc.screenY and 0xFF).toByte()
                    romData[entryPc + 8] = (dc.distFromDoor and 0xFF).toByte()
                    romData[entryPc + 9] = ((dc.distFromDoor shr 8) and 0xFF).toByte()
                    romData[entryPc + 10] = (dc.entryCode and 0xFF).toByte()
                    romData[entryPc + 11] = ((dc.entryCode shr 8) and 0xFF).toByte()
                }
                roomsPatched.add(roomKey)
            }

            // Patch enemy population
            if (hasEnemyEdits && room.enemySetPtr != 0 && room.enemySetPtr != 0xFFFF) run enemyPatch@{
                val originalEnemies = romParser.parseEnemyPopulation(room.enemySetPtr)
                val originalSet = originalEnemies.toSet()
                val modified = originalEnemies.toMutableList()
                for (ec in roomEdits.enemyChanges) {
                    when (ec.action) {
                        "add" -> modified.add(
                            RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3)
                        )
                        "remove" -> modified.removeAll {
                            it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                        }
                        "update" -> {
                            val idx = modified.indexOfFirst {
                                it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                            }
                            if (idx >= 0) modified[idx] = RomParser.EnemyEntry(
                                ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3
                            )
                        }
                    }
                }
                val enemyPc = romParser.snesToPc(0xA10000 or room.enemySetPtr)
                val killCountPc = enemyPc + originalEnemies.size * 16 + 2
                val killCount = if (killCountPc < romData.size) romData[killCountPc] else 0
                // +3 = FFFF terminator (2) + kill count byte (1)
                val originalSize = originalEnemies.size * 16 + 3
                val newSize = modified.size * 16 + 3

                val writePc: Int
                if (newSize <= originalSize) {
                    writePc = enemyPc
                } else if (enemyFreePtr + newSize <= bankA1End) {
                    writePc = enemyFreePtr
                    enemyFreePtr += newSize
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    val newSnes = romParser.pcToSnes(writePc)
                    val newPtr = newSnes and 0xFFFF
                    for (stateOffset in allStateOffsets) {
                        val existingPtr = (romData[stateOffset + 8].toInt() and 0xFF) or
                                ((romData[stateOffset + 9].toInt() and 0xFF) shl 8)
                        if (existingPtr == room.enemySetPtr) {
                            romData[stateOffset + 8] = (newPtr and 0xFF).toByte()
                            romData[stateOffset + 9] = ((newPtr shr 8) and 0xFF).toByte()
                        }
                    }
                    println("Room 0x$roomKey: relocated enemy set to 0x${newSnes.toString(16)}")
                } else {
                    println("WARN: Room 0x$roomKey no free space for expanded enemy set — skipped enemy patch")
                    return@enemyPatch
                }

                fun writeU16(offset: Int, value: Int) {
                    romData[offset] = (value and 0xFF).toByte()
                    romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
                }

                val originalSpeciesIds = originalEnemies.map { it.id }.toSet()
                var off = writePc
                for (e in modified) {
                    writeU16(off, e.id)
                    writeU16(off + 2, e.x)
                    writeU16(off + 4, e.y)
                    writeU16(off + 6, e.initParam)
                    // Only force 0x2000 (spritemap init) for truly NEW species not
                    // present in the vanilla population. If the user modified an
                    // existing enemy's properties, respect their exact value.
                    val props = if (e in originalSet || e.id in originalSpeciesIds) e.properties
                                else (e.properties or 0x2000)
                    writeU16(off + 8, props)
                    writeU16(off + 10, e.extra1)
                    writeU16(off + 12, e.extra2)
                    writeU16(off + 14, e.extra3)
                    off += 16
                }
                writeU16(off, 0xFFFF)
                off += 2
                romData[off] = killCount
                off++
                if (writePc == enemyPc) {
                    while (off < enemyPc + originalSize) { romData[off] = 0; off++ }
                }
                roomsPatched.add(roomKey)
            }

            // Patch enemy GFX set (bank $B4) — add GFX entries only for
            // species the USER is adding, not vanilla species that already work
            // without entries (e.g. Elevator, special entities). Adding unneeded
            // GFX entries causes ProcessEnemyTilesets to load tile data to VRAM
            // that the room wasn't designed for, corrupting the VRAM layout.
            if (hasEnemyEdits && room.enemyGfxPtr != 0 && room.enemyGfxPtr != 0xFFFF) run gfxPatch@{
                val gfxEntries = romParser.parseEnemyGfxSet(room.enemyGfxPtr)
                val existingSpecies = gfxEntries.map { it.speciesId }.toSet()

                val vanillaPopulation = romParser.parseEnemyPopulation(room.enemySetPtr)
                val vanillaSpecies = vanillaPopulation.map { it.id }.toSet()

                val finalPopulation = vanillaPopulation.toMutableList()
                for (ec in roomEdits.enemyChanges) {
                    when (ec.action) {
                        "add" -> finalPopulation.add(RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties))
                        "remove" -> finalPopulation.removeAll { it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY }
                    }
                }
                val finalSpecies = finalPopulation.map { it.id }.toSet()
                // Only add GFX entries for species that are (a) genuinely new
                // (not in vanilla population), (b) still present after removes,
                // and (c) not already covered by the existing GFX set.
                val neededSpecies = (finalSpecies - vanillaSpecies).filter { it !in existingSpecies }
                val skippedVanilla = (finalSpecies intersect vanillaSpecies) - existingSpecies
                if (skippedVanilla.isNotEmpty()) {
                    println("Room 0x$roomKey: skipped ${skippedVanilla.size} vanilla species from GFX set " +
                            "(${skippedVanilla.joinToString { "0x${it.toString(16)}" }})")
                }
                if (neededSpecies.isEmpty()) return@gfxPatch

                val maxPalIdx = gfxEntries.maxOfOrNull { it.paletteIndex and 0xFF } ?: 0
                val newEntries = gfxEntries.toMutableList()
                var nextPal = maxPalIdx + 1
                for (specId in neededSpecies) {
                    if (newEntries.size >= 4) {
                        println("WARN: Room 0x$roomKey GFX set already has ${newEntries.size} entries (SNES max 4) — skipping species 0x${specId.toString(16)}")
                        continue
                    }
                    // Validate species: read HP from DNA at offset +4.
                    // Species with HP=0 are likely invalid (mid-structure reads from
                    // wrong species IDs) and would cause ProcessEnemyTilesets to load
                    // garbage tile data into VRAM, corrupting the room's graphics.
                    val specPc = romParser.snesToPc(0xA00000 or specId)
                    val specHp = if (specPc + 6 < romData.size) {
                        (romData[specPc + 4].toInt() and 0xFF) or ((romData[specPc + 5].toInt() and 0xFF) shl 8)
                    } else 0
                    if (specHp == 0) {
                        println("WARN: Room 0x$roomKey: skipping species 0x${specId.toString(16)} from GFX set — HP=0 (invalid species ID)")
                        continue
                    }
                    newEntries.add(RomParser.EnemyGfxEntry(specId, nextPal))
                    println("Room 0x$roomKey: added species 0x${specId.toString(16)} to GFX set (palette=$nextPal)")
                    nextPal++
                }
                if (newEntries.size == gfxEntries.size) return@gfxPatch

                val gfxPc = romParser.snesToPc(0xB40000 or room.enemyGfxPtr)
                val originalGfxSize = gfxEntries.size * 4 + 2
                val newGfxSize = newEntries.size * 4 + 2

                val writeGfxPc: Int
                if (newGfxSize <= originalGfxSize) {
                    writeGfxPc = gfxPc
                } else if (gfxFreePtr + newGfxSize <= bankB4End) {
                    writeGfxPc = gfxFreePtr
                    gfxFreePtr += newGfxSize
                    val newGfxSnes = romParser.pcToSnes(writeGfxPc)
                    val newGfxOffset = newGfxSnes and 0xFFFF
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    for (stateOffset in allStateOffsets) {
                        val existingPtr = (romData[stateOffset + 10].toInt() and 0xFF) or
                                ((romData[stateOffset + 11].toInt() and 0xFF) shl 8)
                        if (existingPtr == room.enemyGfxPtr) {
                            romData[stateOffset + 10] = (newGfxOffset and 0xFF).toByte()
                            romData[stateOffset + 11] = ((newGfxOffset shr 8) and 0xFF).toByte()
                        }
                    }
                    println("Room 0x$roomKey: relocated GFX set to 0x${newGfxSnes.toString(16)}")
                } else {
                    println("WARN: Room 0x$roomKey no free space in bank \$B4 for expanded GFX set")
                    return@gfxPatch
                }

                var goff = writeGfxPc
                for (ge in newEntries) {
                    romData[goff] = (ge.speciesId and 0xFF).toByte()
                    romData[goff + 1] = ((ge.speciesId shr 8) and 0xFF).toByte()
                    romData[goff + 2] = (ge.paletteIndex and 0xFF).toByte()
                    romData[goff + 3] = ((ge.paletteIndex shr 8) and 0xFF).toByte()
                    goff += 4
                }
                romData[goff] = 0xFF.toByte()
                romData[goff + 1] = 0xFF.toByte()
            }

            // Patch scroll data
            if (hasScrollEdits && room.roomScrollsPtr > 1) {
                val originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
                val modifiedScrolls = originalScrolls.copyOf()
                for (sc in roomEdits.scrollChanges) {
                    val idx = sc.screenY * room.width + sc.screenX
                    if (idx in modifiedScrolls.indices) modifiedScrolls[idx] = sc.newValue
                }
                val scrollPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or room.roomScrollsPtr)
                for (i in modifiedScrolls.indices) {
                    if (scrollPc + i < romData.size) romData[scrollPc + i] = modifiedScrolls[i].toByte()
                }
                roomsPatched.add(roomKey)
            }

            // Patch FX data
            if (hasFxEdits && room.fxPtr != 0 && room.fxPtr != 0xFFFF) {
                val fx = roomEdits.fxChange!!
                val fxEntries = romParser.parseFxEntries(room.fxPtr)
                if (fxEntries.isNotEmpty()) {
                    val fxSnesAddr = RomConstants.BANK_FX or room.fxPtr
                    var fxPc = romParser.snesToPc(fxSnesAddr)
                    // Find the default FX entry (doorSelect == 0) — it's always the last one
                    for (entry in fxEntries) {
                        if (entry.doorSelect == 0) {
                            // fxPc points to this entry
                            fx.liquidSurfaceStart?.let { v ->
                                romData[fxPc + 2] = (v and 0xFF).toByte()
                                romData[fxPc + 3] = ((v shr 8) and 0xFF).toByte()
                            }
                            fx.liquidSurfaceNew?.let { v ->
                                romData[fxPc + 4] = (v and 0xFF).toByte()
                                romData[fxPc + 5] = ((v shr 8) and 0xFF).toByte()
                            }
                            fx.liquidSpeed?.let { v ->
                                romData[fxPc + 6] = (v and 0xFF).toByte()
                                romData[fxPc + 7] = ((v shr 8) and 0xFF).toByte()
                            }
                            fx.liquidDelay?.let { v -> romData[fxPc + 8] = v.toByte() }
                            fx.fxType?.let { v -> romData[fxPc + 9] = v.toByte() }
                            fx.fxBitA?.let { v -> romData[fxPc + 10] = v.toByte() }
                            fx.fxBitB?.let { v -> romData[fxPc + 11] = v.toByte() }
                            fx.fxBitC?.let { v -> romData[fxPc + 12] = v.toByte() }
                            fx.paletteFxBitflags?.let { v -> romData[fxPc + 13] = v.toByte() }
                            fx.tileAnimBitflags?.let { v -> romData[fxPc + 14] = v.toByte() }
                            fx.paletteBlend?.let { v -> romData[fxPc + 15] = v.toByte() }
                            break
                        }
                        fxPc += 16
                    }
                    roomsPatched.add(roomKey)
                }
            }

            // Patch room header fields (area, map position, scrollers, CRE)
            if (hasHeaderEdits) {
                val hc = roomEdits.roomHeaderChange!!
                val headerPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)
                hc.index?.let { romData[headerPc] = it.toByte() }
                hc.area?.let { romData[headerPc + 1] = it.toByte() }
                hc.mapX?.let { romData[headerPc + 2] = it.toByte() }
                hc.mapY?.let { romData[headerPc + 3] = it.toByte() }
                hc.width?.let { romData[headerPc + 4] = it.toByte() }
                hc.height?.let { romData[headerPc + 5] = it.toByte() }
                hc.upScroller?.let { romData[headerPc + 6] = it.toByte() }
                hc.downScroller?.let { romData[headerPc + 7] = it.toByte() }
                hc.creBitflag?.let { romData[headerPc + 8] = it.toByte() }
                hc.doorOut?.let {
                    romData[headerPc + 9] = (it and 0xFF).toByte()
                    romData[headerPc + 10] = ((it shr 8) and 0xFF).toByte()
                }
                println("Room 0x$roomKey: patched room header")
            }

            // Patch state data fields (tileset, music, BG scrolling)
            if (hasStateEdits) {
                val sd = roomEdits.stateDataChange!!
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                for (stateOffset in allStateOffsets) {
                    sd.tileset?.let { v ->
                        romData[stateOffset + 3] = v.toByte()
                    }
                    sd.musicData?.let { v ->
                        romData[stateOffset + 4] = v.toByte()
                    }
                    sd.musicTrack?.let { v ->
                        romData[stateOffset + 5] = v.toByte()
                    }
                    sd.bgScrolling?.let { v ->
                        romData[stateOffset + 12] = (v and 0xFF).toByte()
                        romData[stateOffset + 13] = ((v shr 8) and 0xFF).toByte()
                    }
                }
                roomsPatched.add(roomKey)
            }
        }
        // Apply custom tileset graphics
        var gfxPatched = 0
        val gfxData = project.customGfx

        // Custom CRE graphics (shared, always at $B9:8000)
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try {
                val rawCre = java.util.Base64.getDecoder().decode(creB64)
                val compressed = lz5Compress(rawCre)
                val crePc = romParser.snesToPc(TileGraphics.CRE_GFX_SNES)
                val (_, origSize) = romParser.decompressLZ2WithSize(TileGraphics.CRE_GFX_SNES)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, crePc, compressed.size)
                    for (i in compressed.size until origSize) romData[crePc + i] = 0xFF.toByte()
                    gfxPatched++
                    println("Patched CRE graphics in-place (${compressed.size}/$origSize bytes)")
                } else {
                    println("WARN: Compressed CRE gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { println("WARN: CRE gfx patch failed: ${e.message}") }
        }

        // Custom variable (URE) graphics per tileset
        val tablePC = romParser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        for ((tsIdStr, varB64) in gfxData.varGfx) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawVar = java.util.Base64.getDecoder().decode(varB64)
                val compressed = lz5Compress(rawVar)
                val entryOffset = tablePC + tsId * 9
                val gfxSnes = (romData[entryOffset + 3].toInt() and 0xFF) or
                        ((romData[entryOffset + 4].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 5].toInt() and 0xFF) shl 16)
                val gfxPc = romParser.snesToPc(gfxSnes)
                val (_, origSize) = romParser.decompressLZ2WithSize(gfxSnes)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, gfxPc, compressed.size)
                    for (i in compressed.size until origSize) romData[gfxPc + i] = 0xFF.toByte()
                    gfxPatched++
                    println("Patched tileset $tsId variable gfx in-place (${compressed.size}/$origSize bytes)")
                } else {
                    println("WARN: Compressed tileset $tsId gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { println("WARN: Tileset $tsId gfx patch failed: ${e.message}") }
        }

        // Custom palette overrides per tileset (raw BGR555 → LZ5 compress → write in-place)
        for ((tsIdStr, palB64) in gfxData.palettes) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawPal = java.util.Base64.getDecoder().decode(palB64)
                if (rawPal.size != 256) { println("WARN: Palette $tsId has ${rawPal.size} bytes (expected 256) — skipped"); continue }
                val compressed = lz5Compress(rawPal)
                val entryOffset = tablePC + tsId * 9
                val palSnes = (romData[entryOffset + 6].toInt() and 0xFF) or
                        ((romData[entryOffset + 7].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 8].toInt() and 0xFF) shl 16)
                val palPc = romParser.snesToPc(palSnes)
                val (_, origSize) = romParser.decompressLZ2WithSize(palSnes)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, palPc, compressed.size)
                    for (i in compressed.size until origSize) romData[palPc + i] = 0xFF.toByte()
                    gfxPatched++
                    println("Patched tileset $tsId palette in-place (${compressed.size}/$origSize bytes)")
                } else {
                    println("WARN: Compressed tileset $tsId palette (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { println("WARN: Tileset $tsId palette patch failed: ${e.message}") }
        }

        // Apply Phantoon sprite tile patches (raw 4bpp → LZ5 compress → write to $B7)
        println("[EXPORT] Phantoon sprite blocks: spriteTileBlocks.keys=${gfxData.spriteTileBlocks.keys}, size=${gfxData.spriteTileBlocks.size}")
        for ((i, block) in com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val b64 = gfxData.spriteTileBlocks["phantoon:$i"]
            if (b64 == null) {
                println("[EXPORT] Phantoon block $i: NO DATA in spriteTileBlocks (key 'phantoon:$i' not found)")
                continue
            }
            println("[EXPORT] Phantoon block $i: found ${b64.length} b64 chars")
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                println("[EXPORT] Phantoon block $i: decoded to ${rawBytes.size} raw bytes")
                val compressed = lz5Compress(rawBytes)
                println("[EXPORT] Phantoon block $i: compressed to ${compressed.size} bytes")
                val (_, origSize) = romParser.decompressLZ2WithSize(block.snesAddress)
                println("[EXPORT] Phantoon block $i: original compressed size=$origSize, fits=${compressed.size <= origSize}")
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                    for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    gfxPatched++
                    println("[EXPORT] Patched Phantoon sprite tile block $i: ${compressed.size}/$origSize bytes at PC=0x${block.pcAddress.toString(16)}")
                } else {
                    println("[EXPORT] WARN: Phantoon sprite block $i compressed size ${compressed.size} exceeds original $origSize — skipped")
                }
            } catch (e: Exception) {
                println("[EXPORT] WARN: Phantoon sprite block $i patch failed: ${e.message}")
                e.printStackTrace()
            }
        }

        // Apply Kraid sprite tile patches (raw 4bpp → LZ5 compress → write to $B9)
        for ((i, block) in com.supermetroid.editor.rom.EnemySpriteGraphics.KRAID_BLOCKS.withIndex()) {
            val b64 = gfxData.spriteTileBlocks["kraid:$i"]
            if (b64 == null) continue
            println("[EXPORT] Kraid block $i: found ${b64.length} b64 chars")
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                val compressed = lz5Compress(rawBytes)
                val (_, origSize) = romParser.decompressLZ2WithSize(block.snesAddress)
                println("[EXPORT] Kraid block $i: ${compressed.size}/$origSize bytes")
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                    for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    gfxPatched++
                    println("[EXPORT] Patched Kraid sprite tile block $i at PC=0x${block.pcAddress.toString(16)}")
                } else {
                    println("[EXPORT] WARN: Kraid sprite block $i compressed size ${compressed.size} exceeds original $origSize — skipped")
                }
            } catch (e: Exception) {
                println("[EXPORT] WARN: Kraid sprite block $i patch failed: ${e.message}")
            }
        }

        // Apply minimap tile edits
        var minimapPatched = 0
        for ((areaKey, edits) in project.minimapEdits) {
            val area = areaKey.toIntOrNull() ?: continue
            if (area !in 0 until com.supermetroid.editor.rom.MinimapData.NUM_AREAS) continue
            val baseline = romParser.readMinimapTiles(area)
            var patched = baseline
            for (edit in edits) {
                patched = patched.withTile(edit.x, edit.y, edit.tileWord)
            }
            for ((offset, byte) in romParser.writeMinimapTiles(patched)) {
                romData[offset] = byte
            }
            minimapPatched += edits.size
            println("Minimap area $area: patched ${edits.size} tiles")
        }

        if (roomsPatched.isEmpty() && patchesApplied == 0 && gfxPatched == 0 && minimapPatched == 0) {
            val orig = File(romPath)
            val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
            out.writeBytes(romData)
            println("Exported (vanilla copy, no edits): ${out.absolutePath}")
            return out.absolutePath
        }

        // ─── Export verification pass ───────────────────────────────
        // Re-read all modified data from the export copy and validate.
        println("\n=== Export Verification ===")
        var verifyErrors = 0
        val exportParser = RomParser(romData)
        for (roomKey in roomsPatched) {
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue
            val allStateOffsets = romParser.findAllStateDataOffsets(roomId)

            // Collect per-state data from the EXPORT copy
            val stateInfos = mutableListOf<String>()
            val distinctLevelPtrs = mutableSetOf<Int>()
            val distinctPlmPtrs = mutableSetOf<Int>()
            for ((si, stateOffset) in allStateOffsets.withIndex()) {
                val lvlPtr = (romData[stateOffset].toInt() and 0xFF) or
                        ((romData[stateOffset + 1].toInt() and 0xFF) shl 8) or
                        ((romData[stateOffset + 2].toInt() and 0xFF) shl 16)
                val plmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                        ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                distinctLevelPtrs.add(lvlPtr)
                distinctPlmPtrs.add(plmPtr)
                stateInfos.add("  state[$si] levelData=\$${lvlPtr.toString(16)} plmSet=\$${plmPtr.toString(16)}")
            }

            if (allStateOffsets.size > 1 || distinctLevelPtrs.size > 1 || distinctPlmPtrs.size > 1) {
                println("Room 0x$roomKey: ${allStateOffsets.size} states, ${distinctLevelPtrs.size} distinct level ptrs, ${distinctPlmPtrs.size} distinct PLM ptrs")
                for (info in stateInfos) println(info)
            }

            // Verify each distinct level data pointer decompresses correctly
            for (lvlPtr in distinctLevelPtrs) {
                if (lvlPtr == 0) continue
                try {
                    val decompressed = exportParser.decompressLZ2(lvlPtr)
                    if (decompressed.isEmpty()) {
                        println("  ERROR: level data at \$${lvlPtr.toString(16)} decompressed to 0 bytes!")
                        verifyErrors++
                    }
                    // Check for door blocks (type 9) and report them
                    val blockCount = room.width * 16 * room.height * 16
                    val l1size = if (decompressed.size >= 2) (decompressed[0].toInt() and 0xFF) or ((decompressed[1].toInt() and 0xFF) shl 8) else 0
                    var doorBlockCount = 0
                    for (bi in 0 until minOf(blockCount, l1size / 2)) {
                        val off = 2 + bi * 2
                        if (off + 1 >= decompressed.size) break
                        val word = (decompressed[off].toInt() and 0xFF) or ((decompressed[off + 1].toInt() and 0xFF) shl 8)
                        if ((word shr 12) and 0xF == 9) doorBlockCount++
                    }
                    if (doorBlockCount > 0) {
                        println("  level data \$${lvlPtr.toString(16)}: $doorBlockCount door blocks (type 9)")
                    }
                } catch (e: Exception) {
                    println("  ERROR: failed to decompress level data at \$${lvlPtr.toString(16)}: ${e.message}")
                    verifyErrors++
                }
            }

            // Verify each distinct PLM set is properly terminated
            for (plmPtr in distinctPlmPtrs) {
                if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                val plms = exportParser.parsePlmSet(plmPtr)
                val doorCaps = plms.filter { RomParser.doorCapColor(it.id) != null }
                if (doorCaps.isNotEmpty()) {
                    println("  PLM set \$${plmPtr.toString(16)}: ${plms.size} entries, ${doorCaps.size} door cap(s):")
                    for (dc in doorCaps) {
                        val name = RomParser.doorCapDisplayName(dc.id) ?: "Unknown"
                        println("    $name at (${dc.x},${dc.y}) param=0x${dc.param.toString(16)}")
                    }
                }
            }
        }
        if (verifyErrors > 0) {
            println("EXPORT VERIFICATION: $verifyErrors error(s) found!")
        } else {
            println("EXPORT VERIFICATION: all checks passed")
        }
        println("=== End Verification ===\n")

        val orig = File(romPath)
        val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
        out.writeBytes(romData)
        val msg = "Exported ROM: ${out.absolutePath} (${roomsPatched.size} rooms, $patchesApplied patches, $gfxPatched gfx)"
        println(msg)
        postStatus(msg)
        return out.absolutePath
    }

    /**
     * Export an IPS patch by diffing the patched ROM against the original.
     * Reuses [exportToRom] to build the patched data, then generates IPS records
     * for every changed byte range.
     */
    fun exportToIps(romParser: RomParser): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null

        val original = romParser.getRomData()
        val smcPath = exportToRom(romParser) ?: return null
        val patched = File(smcPath).readBytes()

        if (original.size != patched.size) {
            println("[IPS] ROM size mismatch: ${original.size} vs ${patched.size}")
            return null
        }

        val ipsData = buildIpsPatch(original, patched)
        val orig = File(romPath)
        val ipsFile = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.ips")
        ipsFile.writeBytes(ipsData)
        val msg = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        println(msg)
        postStatus(msg)
        return ipsFile.absolutePath
    }

    private fun buildIpsPatch(original: ByteArray, patched: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("PATCH".toByteArray(Charsets.US_ASCII))

        var i = 0
        val len = minOf(original.size, patched.size)
        while (i < len) {
            if (original[i] != patched[i]) {
                val start = i
                while (i < len && original[i] != patched[i] && (i - start) < 0xFFFF) i++
                val size = i - start

                // IPS record: 3-byte offset, 2-byte size, data
                out.write((start shr 16) and 0xFF)
                out.write((start shr 8) and 0xFF)
                out.write(start and 0xFF)
                out.write((size shr 8) and 0xFF)
                out.write(size and 0xFF)
                out.write(patched, start, size)
            } else {
                i++
            }
        }

        out.write("EOF".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun lz5Compress(data: ByteArray) = LZ5Compressor.compress(data)
}
