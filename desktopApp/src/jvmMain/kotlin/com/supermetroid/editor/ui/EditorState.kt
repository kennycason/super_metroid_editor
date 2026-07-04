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
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.EnemyUpdate
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.procgen.BiomeGenerator
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TileGraphics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

private val editorStateLog = KotlinLogging.logger {}

private fun editorLog(message: Any? = "") {
    val text = message?.toString() ?: ""
    when {
        text.startsWith("ERROR") || text.contains(" ERROR:") -> editorStateLog.error { text }
        text.startsWith("WARN") || text.contains(" WARN:") -> editorStateLog.warn { text }
        else -> editorStateLog.info { text }
    }
}

private fun ByteArray.hexAt(offset: Int, count: Int): String {
    if (offset < 0 || offset >= size) return "<out-of-range>"
    val end = (offset + count).coerceAtMost(size)
    return (offset until end).joinToString(" ") { (this[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
}

private fun bytesSha256(bytes: List<Int>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (b in bytes) digest.update((b and 0xFF).toByte())
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

private val NORMAL_ENEMY_GFX_VRAM_DESTINATIONS = listOf(1, 2, 3, 7)

internal fun selectEnemyGfxVramDestination(
    existingEntries: List<RomParser.EnemyGfxEntry>,
    vanillaDestinations: List<Int>,
): Int? {
    val usedRows = existingEntries.map { it.paletteIndex and 0xFF }.toSet()
    val vanillaNormal = vanillaDestinations
        .filter { (it and 0xFF) in NORMAL_ENEMY_GFX_VRAM_DESTINATIONS }
        .distinct()

    return vanillaNormal.firstOrNull { (it and 0xFF) !in usedRows }
        ?: NORMAL_ENEMY_GFX_VRAM_DESTINATIONS.firstOrNull { it !in usedRows }
        ?: vanillaDestinations.distinct().firstOrNull()
}

internal fun collectVanillaEnemyGfxDestinations(
    romParser: RomParser,
    roomRepository: RoomRepository = RoomRepository(),
): Map<Int, List<Int>> {
    val bySpecies = linkedMapOf<Int, MutableList<Int>>()
    val seenGfxPtrs = mutableSetOf<Int>()

    for (roomInfo in roomRepository.getAllRooms()) {
        val roomId = roomInfo.getRoomIdAsInt()
        val stateGfxPtrs = romParser.parseRoomStatesWithData(roomId)
            .map { it.enemyGfxPtr }
            .distinct()
        for (gfxPtr in stateGfxPtrs) {
            if (gfxPtr == 0 || gfxPtr == 0xFFFF || !seenGfxPtrs.add(gfxPtr)) continue
            for (entry in romParser.parseEnemyGfxSet(gfxPtr)) {
                val destinations = bySpecies.getOrPut(entry.speciesId) { mutableListOf() }
                if (entry.paletteIndex !in destinations) destinations.add(entry.paletteIndex)
            }
        }
    }

    return bySpecies.mapValues { it.value.toList() }
}

internal data class DoorScrollWrite(
    val scrollValue: Int,
    val addressLowByte: Int,
)

private const val MOTHER_BRAIN_ROOM_ID = 0xDD58
private const val NMI_FLAG_BG2_ENEMY_VRAM_TRANSFER_ADDR = 0x0E1E
private const val ENEMY_BG2_TILEMAP_SIZE_ADDR = 0x179A

internal fun shouldClearEnemyBg2TransferOnDoor(sourceRoomId: Int, destRoomId: Int): Boolean =
    sourceRoomId == MOTHER_BRAIN_ROOM_ID && destRoomId != MOTHER_BRAIN_ROOM_ID

internal fun parseDoorScrollWrites(
    romParser: RomParser,
    entryCode: Int,
    maxBytes: Int = 80,
): List<DoorScrollWrite> {
    if (entryCode == 0 || entryCode == 0xFFFF) return emptyList()
    val pc = runCatching { romParser.snesToPc(0x8F0000 or entryCode) }.getOrNull() ?: return emptyList()
    val writes = mutableListOf<DoorScrollWrite>()
    var i = 0
    while (i < maxBytes) {
        val b = romParser.readByteAt(pc + i)
        if (b == 0x60 || b == 0x6B) break
        if (b == 0xA9 && i + 5 < maxBytes) {
            val value = romParser.readByteAt(pc + i + 1)
            val next = romParser.readByteAt(pc + i + 2)
            if (next == 0x8F) {
                val lo = romParser.readByteAt(pc + i + 3)
                val hi = romParser.readByteAt(pc + i + 4)
                val bank = romParser.readByteAt(pc + i + 5)
                if (hi == 0xCD && bank == 0x7E && lo in 0x20..0x7F) {
                    writes.add(DoorScrollWrite(value, lo))
                }
                i += 6
                continue
            }
        }
        if ((b == 0x08 || b == 0x28) && i + 1 <= maxBytes) {
            i++
            continue
        }
        if ((b == 0xE2 || b == 0xC2) && i + 1 < maxBytes) {
            i += 2
            continue
        }
        i++
    }
    return writes
}

internal fun buildDoorAsmClearingEnemyBg2Transfer(scrollWrites: List<DoorScrollWrite>): ByteArray {
    val bytes = mutableListOf<Int>()
    fun addWordAddress(address: Int) {
        bytes.add(address and 0xFF)
        bytes.add((address shr 8) and 0xFF)
        bytes.add(0x7E)
    }

    bytes.add(0x08) // PHP
    bytes.add(0xC2) // REP #$20
    bytes.add(0x20)
    bytes.add(0xA9) // LDA #$0000
    bytes.add(0x00)
    bytes.add(0x00)
    bytes.add(0x8F) // STA $7E0E1E
    addWordAddress(NMI_FLAG_BG2_ENEMY_VRAM_TRANSFER_ADDR)
    bytes.add(0x8F) // STA $7E179A
    addWordAddress(ENEMY_BG2_TILEMAP_SIZE_ADDR)
    bytes.add(0xE2) // SEP #$20
    bytes.add(0x20)
    for (write in scrollWrites) {
        bytes.add(0xA9) // LDA #imm8
        bytes.add(write.scrollValue and 0xFF)
        bytes.add(0x8F) // STA long
        bytes.add(write.addressLowByte and 0xFF)
        bytes.add(0xCD)
        bytes.add(0x7E)
    }
    bytes.add(0x28) // PLP
    bytes.add(0x60) // RTS
    return bytes.map { it.toByte() }.toByteArray()
}

internal data class DoorDependentBgTransfer(
    val doorDefPtr: Int,
    val srcAddr: Int,
    val vramDst: Int,
    val size: Int,
)

private fun bgDataCommandSize(command: Int): Int? = when (command) {
    0x0000 -> 2
    0x0002, 0x0008 -> 9
    0x0004 -> 7
    0x0006, 0x000A, 0x000C -> 2
    0x000E -> 11
    else -> null
}

internal fun readDoorEntryAtDoorDefPtr(
    romParser: RomParser,
    doorDefPtr: Int,
): RomParser.DoorEntry? {
    if (doorDefPtr < 0x8000 || doorDefPtr == 0xFFFF) return null
    val pc = runCatching { romParser.snesToPc(RomConstants.BANK_FX or doorDefPtr) }.getOrNull() ?: return null
    val destRoom = romParser.readUInt16At(pc)
    if (destRoom < 0x8000 || destRoom == 0xFFFF) return null
    return RomParser.DoorEntry(
        destRoomPtr = destRoom,
        bitflag = romParser.readUInt16At(pc + 2),
        doorCapCode = romParser.readUInt16At(pc + 4),
        screenX = romParser.readByteAt(pc + 6),
        screenY = romParser.readByteAt(pc + 7),
        distFromDoor = romParser.readUInt16At(pc + 8),
        entryCode = romParser.readUInt16At(pc + 10),
        doorDefPtr = doorDefPtr,
    )
}

internal fun parseDoorDependentBgTransfers(
    romParser: RomParser,
    bgDataPtr: Int,
    maxCommands: Int = 64,
): List<DoorDependentBgTransfer> {
    if (bgDataPtr == 0 || bgDataPtr == 0xFFFF) return emptyList()
    val pc = runCatching { romParser.snesToPc(RomConstants.BANK_ROOM_DATA or bgDataPtr) }.getOrNull()
        ?: return emptyList()
    val transfers = mutableListOf<DoorDependentBgTransfer>()
    var offset = pc
    repeat(maxCommands) {
        val command = romParser.readUInt16At(offset)
        if (command == 0x0000) return transfers
        val size = bgDataCommandSize(command) ?: return transfers
        if (command == 0x000E) {
            val srcAddr = romParser.readByteAt(offset + 4) or
                (romParser.readByteAt(offset + 5) shl 8) or
                (romParser.readByteAt(offset + 6) shl 16)
            transfers.add(
                DoorDependentBgTransfer(
                    doorDefPtr = romParser.readUInt16At(offset + 2),
                    srcAddr = srcAddr,
                    vramDst = romParser.readUInt16At(offset + 7),
                    size = romParser.readUInt16At(offset + 9),
                )
            )
        }
        offset += size
    }
    return transfers
}

private fun sameDoorDependentBgEntrance(a: RomParser.DoorEntry, b: RomParser.DoorEntry): Boolean =
    a.destRoomPtr == b.destRoomPtr &&
        (a.bitflag and 0xFF00) == (b.bitflag and 0xFF00) &&
        a.doorCapCode == b.doorCapCode &&
        a.screenX == b.screenX &&
        a.screenY == b.screenY

internal fun findMatchingDoorDependentBgTransfer(
    romParser: RomParser,
    bgDataPtr: Int,
    newDoor: RomParser.DoorEntry,
): DoorDependentBgTransfer? {
    val transfers = parseDoorDependentBgTransfers(romParser, bgDataPtr)
    if (transfers.any { it.doorDefPtr == newDoor.doorDefPtr }) return null
    return transfers.firstOrNull { transfer ->
        val templateDoor = readDoorEntryAtDoorDefPtr(romParser, transfer.doorDefPtr) ?: return@firstOrNull false
        sameDoorDependentBgEntrance(templateDoor, newDoor)
    }
}

internal fun buildBgDataWithClonedDoorDependentTransfer(
    romParser: RomParser,
    bgDataPtr: Int,
    newDoorDefPtr: Int,
    template: DoorDependentBgTransfer,
    maxCommands: Int = 64,
): ByteArray? {
    if (bgDataPtr == 0 || bgDataPtr == 0xFFFF) return null
    val pc = runCatching { romParser.snesToPc(RomConstants.BANK_ROOM_DATA or bgDataPtr) }.getOrNull()
        ?: return null
    var offset = pc
    repeat(maxCommands) {
        val command = romParser.readUInt16At(offset)
        val size = bgDataCommandSize(command) ?: return null
        if (command == 0x0000) {
            val bytes = mutableListOf<Int>()
            for (i in pc until offset) bytes.add(romParser.readByteAt(i))
            bytes.addAll(
                listOf(
                    0x0E, 0x00,
                    newDoorDefPtr and 0xFF, (newDoorDefPtr shr 8) and 0xFF,
                    template.srcAddr and 0xFF, (template.srcAddr shr 8) and 0xFF, (template.srcAddr shr 16) and 0xFF,
                    template.vramDst and 0xFF, (template.vramDst shr 8) and 0xFF,
                    template.size and 0xFF, (template.size shr 8) and 0xFF,
                    0x00, 0x00,
                )
            )
            return bytes.map { it.toByte() }.toByteArray()
        }
        offset += size
    }
    return null
}

data class FloatingMapSelection(val x: Int, val y: Int)

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
        val removedPalette = project.customGfx.palettes.remove(tilesetId.toString()) != null
        val removedEffect = project.customGfx.paletteEffects.remove("tileset:$tilesetId") != null
        if (removedPalette || removedEffect) {
            dirty = true
            paletteVersion++
        }
    }

    /** Check if the project has a custom palette for the given tileset. */
    fun hasCustomPalette(tilesetId: Int): Boolean =
        project.customGfx.palettes.containsKey(tilesetId.toString())

    fun hasCurrentTilesetOverrides(): Boolean =
        hasCustomVarGfx() ||
                hasCustomCreGfx() ||
                hasCustomPalette(editorTilesetId) ||
                project.customGfx.paletteEffects.containsKey("tileset:$editorTilesetId")

    fun resetCurrentTilesetOverrides(
        areaTiles: Boolean,
        commonTiles: Boolean,
        palette: Boolean,
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
        if (palette) {
            var paletteChanged = false
            if (project.customGfx.palettes.remove(tilesetKey) != null) {
                paletteChanged = true
            }
            if (project.customGfx.paletteEffects.remove("tileset:$tilesetKey") != null) {
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
    fun resetSpritePaletteOverride(regionId: String) {
        project.customGfx.spritePalettes.remove(regionId)
        dirty = true
        paletteVersion++
    }

    /** Check if a sprite palette has a project override. */
    fun hasSpritePaletteOverride(regionId: String): Boolean =
        project.customGfx.spritePalettes.containsKey(regionId)

    // ─── Palette effect tracking ───────────────────────────────────

    /** Get the applied effect ID for a palette key, or null if none/custom. */
    fun getPaletteEffect(key: String): String? = project.customGfx.paletteEffects[key]

    /** Set the applied effect ID for a palette key. */
    fun setPaletteEffect(key: String, effectId: String) {
        project.customGfx.paletteEffects[key] = effectId
        dirty = true
    }

    /** Clear the effect tracking for a palette key (e.g. on reset or manual edit). */
    fun clearPaletteEffect(key: String) {
        project.customGfx.paletteEffects.remove(key)
        dirty = true
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

    // ─── Enemy palette editing ──────────────────────────────────────

    private fun enemyPalKey(speciesId: Int) = "enemy_pal:${speciesId.toString(16).uppercase()}"

    /** Load enemy palette: project override if present, else ROM. */
    fun loadEnemyPalette(romParser: com.supermetroid.editor.rom.RomParser, speciesId: Int): IntArray? {
        val b64 = project.customGfx.spritePalettes[enemyPalKey(speciesId)]
        if (b64 != null) {
            try {
                val raw = java.util.Base64.getDecoder().decode(b64)
                return enemyPalBytesToArgb(raw)
            } catch (_: Exception) { /* fall through */ }
        }
        return com.supermetroid.editor.rom.EnemySpriteGraphics.readEnemyPalette(romParser, speciesId)
    }

    /** Save an edited enemy palette (16 ARGB colors) to the project. */
    fun applyEnemyPalette(speciesId: Int, palette: IntArray) {
        val raw = enemyPalArgbToBytes(palette)
        project.customGfx.spritePalettes[enemyPalKey(speciesId)] =
            java.util.Base64.getEncoder().encodeToString(raw)
        dirty = true
    }

    fun hasCustomEnemyPalette(speciesId: Int): Boolean =
        project.customGfx.spritePalettes.containsKey(enemyPalKey(speciesId))

    fun resetEnemyPalette(speciesId: Int) {
        project.customGfx.spritePalettes.remove(enemyPalKey(speciesId))
        dirty = true
    }

    /** Convert 32-byte BGR555 palette to 16-entry ARGB array. */
    private fun enemyPalBytesToArgb(raw: ByteArray): IntArray {
        val pal = IntArray(16)
        pal[0] = 0x00000000
        for (i in 1 until 16) {
            val lo = raw[i * 2].toInt() and 0xFF
            val hi = raw[i * 2 + 1].toInt() and 0xFF
            val bgr = lo or (hi shl 8)
            pal[i] = com.supermetroid.editor.rom.EnemySpriteGraphics.snesColorToArgb(bgr)
        }
        return pal
    }

    /** Convert 16-entry ARGB array to 32-byte BGR555 palette. */
    private fun enemyPalArgbToBytes(palette: IntArray): ByteArray {
        val raw = ByteArray(32)
        for (i in 0 until 16) {
            val argb = palette[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val bgr555 = ((b shr 3) shl 10) or ((g shr 3) shl 5) or (r shr 3)
            raw[i * 2] = (bgr555 and 0xFF).toByte()
            raw[i * 2 + 1] = ((bgr555 shr 8) and 0xFF).toByte()
        }
        return raw
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
        editorLog("[SPRITE] loadPhantoonTileSheet: loaded ${gfx.getTileCount()} tiles, palette=${palette.size} colors, spriteSheetGfx set")

        return gfx.renderSheet(palette)
    }

    /** Palette for the last loaded tile sheet (used by UI for colour swatches). */
    fun getSpriteSheetPalette(): IntArray? = spriteSheetPalette

    /**
     * Encode ARGB pixel edits back to raw 4bpp tile blocks and store in the project.
     * Must be called after [loadPhantoonTileSheet] to have the gfx session available.
     */
    fun applyPhantoonTileSheetEdits(pixels: IntArray, w: Int, h: Int) {
        editorLog("[SPRITE] applyPhantoonTileSheetEdits called (${w}x${h}, ${pixels.size} px)")
        val gfx = spriteSheetGfx
        if (gfx == null) { editorLog("[SPRITE] ABORT: spriteSheetGfx is null — was loadPhantoonTileSheet() called first?"); return }
        val palette = spriteSheetPalette
        if (palette == null) { editorLog("[SPRITE] ABORT: spriteSheetPalette is null"); return }
        gfx.importFromArgb(pixels, w, h, palette)
        val rawBlocks = gfx.getRawBlocks()
        if (rawBlocks == null) { editorLog("[SPRITE] ABORT: getRawBlocks() returned null after importFromArgb"); return }
        for ((i, raw) in rawBlocks.withIndex()) {
            val b64 = java.util.Base64.getEncoder().encodeToString(raw)
            project.customGfx.spriteTileBlocks["phantoon:$i"] = b64
            editorLog("[SPRITE] Stored phantoon:$i — ${raw.size} raw bytes, ${b64.length} b64 chars")
        }
        dirty = true
        editorLog("[SPRITE] applyPhantoonTileSheetEdits DONE — ${rawBlocks.size} blocks stored, spriteTileBlocks keys=${project.customGfx.spriteTileBlocks.keys}")
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
        for (guiPatch in listOf(BEAM_DAMAGE_PATCH, BOSS_STATS_PATCH, PHANTOON_PATCH, ENEMY_STATS_PATCH, ENEMY_DROP_RATE_PATCH, ENEMY_VULNERABILITY_PATCH, SAMUS_PHYSICS_PATCH, ROOM_NAME_PAUSE_MAP_PATCH, BOSS_DEFEATED_PATCH, CONTROLLER_CONFIG_PATCH, CERES_ESCAPE_PATCH)) {
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

        if (added > 0 || refreshed > 0) { patchVersion++ }
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
                project = json.decodeFromString(SmEditProject.serializer(), file.readText())
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
        dirty = false
        patchesSeeded = false
        seedDefaultPatches(forceRefreshBundled = true)
        tileGraphics = null
        workingLevelData = null
        originalLevelData = null
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null

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
        currentTilesetId = project.rooms[project.roomKey(roomId)]?.stateDataChange?.tileset ?: room.tileset
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
        if (effectiveWidth != romWidth || effectiveHeight != romHeight) {
            val newScreenCount = effectiveWidth * effectiveHeight
            val resized = IntArray(newScreenCount) { 1 } // default Blue (visible)
            for (sy in 0 until minOf(romHeight, effectiveHeight)) {
                for (sx in 0 until minOf(romWidth, effectiveWidth)) {
                    val oldIdx = sy * romWidth + sx
                    val newIdx = sy * effectiveWidth + sx
                    if (oldIdx in romScrolls.indices) resized[newIdx] = romScrolls[oldIdx]
                }
            }
            _originalScrolls = resized
        } else {
            _originalScrolls = romScrolls
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
        // Bump render version without marking room as user-edited
        _editVersionState.value++
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
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
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
        val newScreenCount = newWidth * newHeight
        val newScrolls = IntArray(newScreenCount) { 1 } // default to Blue (visible)
        for (sy in 0 until minOf(oldHeight, newHeight)) {
            for (sx in 0 until minOf(oldWidth, newWidth)) {
                val oldIdx = sy * oldWidth + sx
                val newIdx = sy * newWidth + sx
                if (oldIdx in _workingScrolls.indices) {
                    newScrolls[newIdx] = _workingScrolls[oldIdx]
                }
            }
        }
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
        val tileset = edits.stateDataChange?.tileset ?: room.tileset
        if (hc == null && tileset == room.tileset) return room
        return room.copy(
            width = hc?.width ?: room.width,
            height = hc?.height ?: room.height,
            area = hc?.area ?: room.area,
            mapX = hc?.mapX ?: room.mapX,
            mapY = hc?.mapY ?: room.mapY,
            tileset = tileset,
        )
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
        if (pendingEdits.isEmpty() && pendingPlmAdds.isEmpty()) return
        val desc = if (floatingSelection != null) "Place selection ${pendingEdits.size} tile(s)" else when (activeTool) {
            EditorTool.PAINT -> "Paint ${pendingEdits.size} tile(s)"
            EditorTool.FILL -> "Fill ${pendingEdits.size} tile(s)"
            EditorTool.ERASE -> "Erase ${pendingEdits.size} tile(s)"
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
            writeBlockWord(e.blockX, e.blockY, e.newBlockWord)
            writeBts(e.blockX, e.blockY, e.newBts)
        }
        pushEditOperation(EditOperation(description, edits))
    }

    /**
     * Run the biome generator on the working room and apply the result as one
     * undoable operation. Returns the number of tiles changed.
     */
    fun generateBiome(rules: BiomeRules, profile: TilesetProfile, seed: Long): Int {
        val w = workingBlocksWide
        val h = workingBlocksTall
        if (w <= 0 || h <= 0) return 0

        val n = w * h
        val origWords = IntArray(n) { readBlockWord(it % w, it / w) }
        val origBts = IntArray(n) { readBts(it % w, it / w) }
        val result = BiomeGenerator(rules, profile, seed).generate(w, h, origWords, origBts)

        val edits = ArrayList<TileEdit>()
        for (i in 0 until n) {
            if (result.words[i] != origWords[i] || result.bts[i] != origBts[i]) {
                edits.add(TileEdit(i % w, i / w, origWords[i], result.words[i], origBts[i], result.bts[i]))
            }
        }
        applyBulkEdits("Generate biome (${rules.style.displayName}, seed $seed)", edits)
        return edits.size
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
        if (projectFilePath.isEmpty()) return false
        return try {
            // Prune rooms with no actual edits before saving
            val emptyKeys = project.rooms.entries.filter { !it.value.hasEdits }.map { it.key }
            for (k in emptyKeys) project.rooms.remove(k)

            File(projectFilePath).writeText(json.encodeToString(SmEditProject.serializer(), project))
            dirty = false
            editorLog("Project saved: $projectFilePath")
            postStatus("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(it) }
            if (!testMode) PatternLibrary.saveAll(project.patterns)
            true
        } catch (e: Exception) { editorLog("Save failed: ${e.message}"); postStatus("Save failed: ${e.message}"); false }
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
                    if (writePng(out.absolutePath, pixels, w, h)) editorLog("Exported $out")
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
                        if (writePng(out.absolutePath, pixels, w, h)) editorLog("Exported $out")
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

    private fun applyMusicEditsToRom(romData: ByteArray): Int {
        if (project.musicEdits.isEmpty()) return 0

        var patched = 0
        val editsBySongSet = project.musicEdits.toSortedMap().entries.groupBy { it.value.songSet }.toSortedMap()
        for ((songSet, edits) in editsBySongSet) {
            val exportParser = RomParser(romData)
            val baseBlocks = SpcData.parseTransferBlocks(romData, exportParser.snesToPc(0xCF8000))
            val baseRam = SpcData.buildInitialSpcRam(exportParser)
            val songBlocks = SpcData.findSongSetTransferData(exportParser, songSet)
            val originalSpcRam = baseRam.copyOf()
            SpcData.applyTransferBlocks(originalSpcRam, songBlocks)
            val accumulatedWrites = SpcWriteAccumulator(songSet)
            val knownPlayIndexes = SpcData.KNOWN_TRACKS
                .filter { it.songSet == songSet }
                .mapTo(mutableSetOf()) { it.playIndex }
            edits.mapTo(knownPlayIndexes) { it.value.playIndex }
            val editedPlayIndexes = edits.mapTo(mutableSetOf()) { it.value.playIndex }
            val protectedPlayIndexes = knownPlayIndexes - editedPlayIndexes
            val occupiedSequenceRanges = MusicSequenceBudget.buildProtectedSpcOccupancy(
                baseBlocks = baseBlocks,
                songBlocks = songBlocks,
                spcRam = originalSpcRam,
                protectedPlayIndexes = protectedPlayIndexes
            ).toMutableList()

            for ((key, edit) in edits) {
                val originalSong = NspcSequence.parse(originalSpcRam, edit.playIndex)
                val editedSong = MusicEditConversion.toSong(edit)
                val hasNoteDelta = PianoRollPreviewLogic.deltaOverlayPlan(editedSong, originalSong)?.hasDelta
                    ?: editedSong.isModified

                val relocatedSequence = if (hasNoteDelta) {
                    MusicSequenceBudget.encodeRelocated(
                        song = editedSong,
                        playIndex = edit.playIndex,
                        spcRam = originalSpcRam,
                        occupiedRanges = occupiedSequenceRanges,
                        key = key
                    ).also { patch ->
                        if (patch.fit.trimmed) {
                            editorLog(
                                "WARN [EXPORT] Music edit '$key' ${edit.trackName}: " +
                                    "trimmed ${patch.fit.removedNotes} notes and ${patch.fit.removedCommands} commands after tick " +
                                    "${patch.fit.cutoffTick} to fit ${patch.fit.encodedBytes}/${patch.fit.budgetBytes} sequence bytes"
                            )
                        }
                    }
                } else {
                    null
                }
                val relocationRange = relocatedSequence?.allocation
                val sequenceWrites = relocatedSequence?.writes ?: emptyMap()
                validateMusicSequenceWrites(
                    key = key,
                    playIndex = edit.playIndex,
                    writes = sequenceWrites,
                    allocatedRange = relocationRange
                )
                if (relocationRange != null) {
                    occupiedSequenceRanges += relocationRange
                }

                val originalInstruments = NspcRenderer.readInstrumentTable(originalSpcRam)
                val editedInstruments = MusicEditConversion.toInstrumentEntries(edit)
                val instrumentWrites = if (editedInstruments.isNotEmpty()) {
                    PianoRollPreviewLogic.instrumentPatchWrites(editedInstruments, originalInstruments)
                } else {
                    emptyMap()
                }

                if (sequenceWrites.isEmpty() && instrumentWrites.isEmpty()) {
                    editorLog("[EXPORT] Music edit $key has no note/instrument delta; skipping")
                    continue
                }

                val owner = "music edit '$key' (${edit.trackName})"
                mergeSpcWrites(accumulatedWrites, owner, sequenceWrites, rejectAnyOverlap = true)
                mergeSpcWrites(accumulatedWrites, owner, instrumentWrites, rejectAnyOverlap = false)
                val spcWrites = sequenceWrites + instrumentWrites
                val totalBytes = spcWrites.values.sumOf { it.size }

                patched++
                editorLog(
                    "[EXPORT] Music edit '$key' ${edit.trackName}: ${spcWrites.size} SPC write records, " +
                        "$totalBytes payload bytes, noteDelta=$hasNoteDelta, instrumentWrites=${instrumentWrites.size}"
                )
            }

            if (accumulatedWrites.isNotEmpty()) {
                val chainBytes = writeRelocatedSongSetTransferChain(romData, songSet, accumulatedWrites.toWriteMap())
                editorLog(
                    "[EXPORT] SongSet 0x${songSet.toString(16).padStart(2, '0')}: relocated " +
                        "${edits.size} music edit(s) into $chainBytes-byte transfer chain"
                )
            }
        }
        return patched
    }

    private data class SpcWriteAccumulator(
        val songSet: Int,
        val bytesByAddr: java.util.TreeMap<Int, Int> = java.util.TreeMap(),
        val ownersByAddr: MutableMap<Int, String> = mutableMapOf()
    ) {
        fun isNotEmpty(): Boolean = bytesByAddr.isNotEmpty()

        fun toWriteMap(): Map<Int, ByteArray> {
            val writes = linkedMapOf<Int, ByteArray>()
            var runStart = -1
            val runBytes = mutableListOf<Int>()
            var previousAddr = -1

            fun flushRun() {
                if (runStart >= 0) {
                    writes[runStart] = ByteArray(runBytes.size) { runBytes[it].toByte() }
                    runBytes.clear()
                    runStart = -1
                }
            }

            for ((addr, value) in bytesByAddr) {
                if (runStart < 0 || addr != previousAddr + 1) {
                    flushRun()
                    runStart = addr
                }
                runBytes += value
                previousAddr = addr
            }
            flushRun()
            return writes
        }
    }

    private fun validateMusicSequenceWrites(
        key: String,
        playIndex: Int,
        writes: Map<Int, ByteArray>,
        allocatedRange: MusicSequenceBudget.SpcRange?
    ) {
        val songTableEntry = MusicSequenceBudget.SONG_TABLE_BASE + playIndex * 2
        for ((addr, data) in writes) {
            val dest = addr and 0xFFFF
            val endExclusive = dest + data.size
            require(data.isNotEmpty()) { "music edit $key produced an empty sequence write at 0x${dest.toString(16)}" }
            if (dest == songTableEntry && data.size == 2) continue
            require(dest >= MusicSequenceBudget.SONG_TABLE_BASE && endExclusive <= MusicSequenceBudget.SEQUENCE_EXPORT_MAX) {
                "music edit $key produced unsafe sequence SPC write " +
                    "0x${dest.toString(16).padStart(4, '0')}.." +
                    "0x${(endExclusive - 1).toString(16).padStart(4, '0')}"
            }
            if (allocatedRange != null) {
                require(dest >= allocatedRange.start && endExclusive <= allocatedRange.endExclusive) {
                    "music edit $key re-encode escaped relocated allocation " +
                        "0x${allocatedRange.start.toString(16).padStart(4, '0')}.." +
                        "0x${(allocatedRange.endExclusive - 1).toString(16).padStart(4, '0')} with write " +
                        "0x${dest.toString(16).padStart(4, '0')}.." +
                        "0x${(endExclusive - 1).toString(16).padStart(4, '0')}"
                }
            }
        }
    }

    private fun mergeSpcWrites(
        accumulator: SpcWriteAccumulator,
        owner: String,
        writes: Map<Int, ByteArray>,
        rejectAnyOverlap: Boolean
    ) {
        val songSetLabel = "0x${accumulator.songSet.toString(16).padStart(2, '0')}"
        for ((addr, data) in writes) {
            val dest = addr and 0xFFFF
            require(data.isNotEmpty()) { "$owner produced an empty SPC write at 0x${dest.toString(16)}" }
            require(dest + data.size <= RomConstants.SPC_RAM_SIZE) {
                "$owner produced out-of-bounds SPC write " +
                    "0x${dest.toString(16).padStart(4, '0')}.." +
                    "0x${(dest + data.size - 1).toString(16).padStart(4, '0')}"
            }
            for (i in data.indices) {
                val spcAddr = dest + i
                val value = data[i].toInt() and 0xFF
                val existing = accumulator.bytesByAddr[spcAddr]
                val previousOwner = accumulator.ownersByAddr[spcAddr]
                if (existing != null && previousOwner != owner) {
                    require(!rejectAnyOverlap) {
                        "music edits for songSet $songSetLabel overlap at SPC " +
                            "0x${spcAddr.toString(16).padStart(4, '0')} ($previousOwner vs $owner)"
                    }
                    require(existing == value) {
                        "music edits for songSet $songSetLabel write different values at SPC " +
                            "0x${spcAddr.toString(16).padStart(4, '0')} ($previousOwner vs $owner)"
                    }
                } else if (existing != null && existing != value) {
                    require(false) {
                        "$owner produced conflicting SPC bytes at 0x${spcAddr.toString(16).padStart(4, '0')}"
                    }
                }
                accumulator.bytesByAddr[spcAddr] = value
                accumulator.ownersByAddr[spcAddr] = owner
            }
        }
    }

    private fun writeRelocatedSongSetTransferChain(
        romData: ByteArray,
        songSet: Int,
        spcWrites: Map<Int, ByteArray>
    ): Int {
        require(spcWrites.isNotEmpty()) { "music export has no SPC writes for songSet 0x${songSet.toString(16)}" }

        val parser = RomParser(romData)
        val pointerEntryPc = SpcData.findSongSetPointerEntryPc(parser, songSet)
        require(pointerEntryPc >= 0) {
            "songSet 0x${songSet.toString(16)} has no writable transfer pointer table entry"
        }

        val originalPointer = SpcData.readSongSetPointer(parser, songSet)
        val originalBlocks = SpcData.findSongSetTransferData(parser, songSet)
        require(originalBlocks.isNotEmpty()) {
            "songSet 0x${songSet.toString(16)} has no transfer blocks to relocate"
        }

        val patchBlocks = buildSpcPatchBlocks(spcWrites)

        val relocatedChain = serializeTransferChain(originalBlocks + patchBlocks)
        val writePc = findMusicTransferFreeSpace(parser, romData, relocatedChain.size)
        relocatedChain.copyInto(romData, writePc)

        val relocatedPointer = parser.pcToSnes(writePc)
        writeRomU24(romData, pointerEntryPc, relocatedPointer)

        editorLog(
            "[EXPORT] Relocated songSet 0x${songSet.toString(16).padStart(2, '0')} transfer chain " +
                "\$${originalPointer.toString(16).uppercase().padStart(6, '0')} -> " +
                "\$${relocatedPointer.toString(16).uppercase().padStart(6, '0')} " +
                "(${originalBlocks.size} original + ${patchBlocks.size} patch blocks)"
        )
        return relocatedChain.size
    }

    private fun buildSpcPatchBlocks(spcWrites: Map<Int, ByteArray>): List<SpcData.TransferBlock> {
        val bytesByAddr = sortedMapOf<Int, Int>()
        for ((addr, data) in spcWrites) {
            val dest = addr and 0xFFFF
            require(data.isNotEmpty()) { "empty SPC write at 0x${dest.toString(16)}" }
            require(data.size <= 0xFFFF) { "SPC write at 0x${dest.toString(16)} is too large (${data.size} bytes)" }
            require(dest + data.size <= RomConstants.SPC_RAM_SIZE) {
                "SPC write 0x${dest.toString(16)}..0x${(dest + data.size).toString(16)} exceeds SPC RAM"
            }
            for (i in data.indices) {
                bytesByAddr[dest + i] = data[i].toInt() and 0xFF
            }
        }
        if (bytesByAddr.isEmpty()) return emptyList()

        val blocks = mutableListOf<SpcData.TransferBlock>()
        var runStart = -1
        val runBytes = mutableListOf<Int>()
        var previousAddr = -1
        for ((addr, value) in bytesByAddr) {
            if (runStart < 0 || addr != previousAddr + 1) {
                if (runStart >= 0) {
                    blocks += SpcData.TransferBlock(runStart, ByteArray(runBytes.size) { runBytes[it].toByte() })
                    runBytes.clear()
                }
                runStart = addr
            }
            runBytes += value
            previousAddr = addr
        }
        if (runStart >= 0) {
            blocks += SpcData.TransferBlock(runStart, ByteArray(runBytes.size) { runBytes[it].toByte() })
        }
        return blocks
    }

    private fun serializeTransferChain(blocks: List<SpcData.TransferBlock>): ByteArray {
        val out = java.io.ByteArrayOutputStream(blocks.sumOf { 4 + it.data.size } + 2)
        for (block in blocks) {
            require(block.data.size <= 0xFFFF) {
                "transfer block at 0x${block.destAddr.toString(16)} is too large (${block.data.size} bytes)"
            }
            writeStreamU16(out, block.data.size)
            writeStreamU16(out, block.destAddr and 0xFFFF)
            out.write(block.data)
        }
        writeStreamU16(out, 0)
        return out.toByteArray()
    }

    private fun writeStreamU16(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeRomU24(romData: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset + 2 < romData.size) {
            "out-of-bounds ROM pointer write at 0x${offset.toString(16)}"
        }
        romData[offset] = (value and 0xFF).toByte()
        romData[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        romData[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun findMusicTransferFreeSpace(
        parser: RomParser,
        romData: ByteArray,
        requiredBytes: Int
    ): Int {
        require(requiredBytes > 0) { "music transfer chain must not be empty" }
        val preferredBanks = listOf(0xB8, 0xCE, 0x85, 0x83, 0x89)
        val fallbackBanks = (0x80..0xFF).filterNot { it in preferredBanks }
        for (bank in preferredBanks + fallbackBanks) {
            val bankStart = parser.snesToPc((bank shl 16) or 0x8000)
            val bankEndExclusive = parser.snesToPc((bank shl 16) or 0xFFFF) + 1
            if (bankStart < 0 || bankEndExclusive > romData.size || bankStart >= bankEndExclusive) continue

            var freeStart = bankEndExclusive
            while (freeStart > bankStart && romData[freeStart - 1] == 0xFF.toByte()) {
                freeStart--
            }
            if (freeStart >= bankEndExclusive) continue

            val alignedStart = ((freeStart + 0x0F) / 0x10) * 0x10
            if (alignedStart + requiredBytes <= bankEndExclusive) {
                return alignedStart
            }
        }

        error(
            "not enough contiguous free ROM space for $requiredBytes-byte relocated SPC transfer chain"
        )
    }

    fun exportToRom(romParser: RomParser): String? {
        seedDefaultPatches(forceRefreshBundled = true)
        val romPath = project.romPath
        if (romPath.isEmpty()) return null
        saveProject(romParser)
        editorLog("[EXPORT] Starting export — romPath=$romPath, romSize=${romParser.getRomData().size}")
        editorLog("[EXPORT] Project spriteTileBlocks keys: ${project.customGfx.spriteTileBlocks.keys}")
        val romData = romParser.getRomData().copyOf()
        val roomsPatched = mutableSetOf<String>()

        // Apply patches FIRST so free-space scanners see any code/data
        // that patches write into otherwise-empty banks (e.g. skip_intro
        // writes custom ASM into bank $A1 free space).
        var patchesApplied = 0
        val enabledCount = project.patches.count { it.enabled }
        val disabledCount = project.patches.size - enabledCount
        val deferredGeneratedPatches = mutableListOf<SmPatch>()
        editorLog("[EXPORT] Patches: $enabledCount enabled, $disabledCount disabled (${project.patches.size} total)")
        for (patch in project.patches) {
            if (!patch.enabled) continue
            editorLog("[EXPORT] Applying patch: '${patch.name}' [${patch.id}] configType=${patch.configType ?: "hex"}")
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
                editorLog("[EXPORT]   Ceres timer: ${mins}m${secs}s")
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
                editorLog("[EXPORT]   Beam damage: $beamCount beams modified")
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
                editorLog("[EXPORT]   Boss stats: $fieldCount fields modified")
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
                editorLog("[EXPORT]   Phantoon behavior: $fieldCount fields modified")
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
                // AI routine pointers and graphics fields
                val aiFields = listOf(
                    "_initAi" to 0x12, "_mainAi" to 0x16, "_touchAi" to 0x30,
                    "_shotAi" to 0x32, "_hurtAi" to 0x1C, "_frozenAi" to 0x1E,
                    "_grappleAi" to 0x1A, "_deathAnim" to 0x22,
                    "_extraGfx" to 0x18, "_pbVuln" to 0x28,
                )
                for (e in ENEMY_DEFS) {
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    for ((suffix, offset) in aiFields) {
                        val value = data["${e.key}$suffix"] ?: continue
                        val pc = romParser.snesToPc(snesAddr) + offset
                        if (pc + 1 < romData.size) {
                            romData[pc] = (value and 0xFF).toByte()
                            romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                            modCount++
                        }
                    }
                }
                editorLog("[EXPORT]   Enemy stats: $modCount values modified (HP/DMG + AI/GFX)")
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
                editorLog("[EXPORT]   Enemy drop rates: $modCount values modified")
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
                editorLog("[EXPORT]   Enemy vulnerabilities: $modCount values modified")
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
                editorLog("[EXPORT]   Samus physics: $modCount values modified")
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
                editorLog("[EXPORT]   Controller config: $slotCount buttons remapped")
            } else if (patch.configType == RoomNamePauseMapPatch.CONFIG_TYPE) {
                deferredGeneratedPatches.add(patch)
                editorLog("[EXPORT]   (deferred until fixed patch writes are applied)")
            } else if (patch.configType == "boss_defeated" || patch.configType == "hyper_beam") {
                editorLog("[EXPORT]   (deferred to combined per-frame hook)")
            } else {
                val totalBytes = patch.writes.sumOf { it.bytes.size }
                for (write in patch.writes) {
                    val off = write.offset.toInt()
                    for ((i, b) in write.bytes.withIndex()) {
                        if (off + i < romData.size) romData[off + i] = b.toByte()
                    }
                }
                editorLog("[EXPORT]   Hex writes: ${patch.writes.size} records, $totalBytes bytes")
                if (patch.id == "bundled_spider_ball") {
                    val flatHash = bytesSha256(patch.writes.flatMap { it.bytes })
                    editorLog(
                        "[EXPORT]   Spider Ball proof: records=${patch.writes.size}, bytes=$totalBytes, sha256=$flatHash, " +
                            "movePtr@0x82353=${romData.hexAt(0x82353, 2)}, " +
                            "posePtr@0x8801C=${romData.hexAt(0x8801C, 2)}, " +
                            "code@0x87800=${romData.hexAt(0x87800, 12)}, " +
                            "guard@0x880BE=${romData.hexAt(0x880BE, 12)}, " +
                            "plm@0x27200=${romData.hexAt(0x27200, 12)}"
                    )
                }
            }
            patchesApplied++
        }

        for (patch in deferredGeneratedPatches) {
            try {
                val result = RoomNamePauseMapPatch.install(
                    romData = romData,
                    snesToPc = romParser::snesToPc,
                    pcToSnes = romParser::pcToSnes,
                    rooms = RoomRepository().getAllRooms(),
                    overrides = project.roomNameOverrides,
                    alignment = RoomNamePauseMapPatch.RoomNameAlignment.fromConfig(
                        patch.configData?.get(RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY)
                    ),
                )
                editorLog(
                    "[EXPORT]   Generated '${patch.name}': ${result.roomCount} room names, " +
                        "${result.payloadSize} bytes at SNES $" +
                        result.allocation.snesAddress.toString(16).uppercase().padStart(6, '0')
                )
            } catch (e: Exception) {
                val message = "Export failed: ${patch.name} could not be written safely (${e.message})"
                editorLog("ERROR: $message")
                postStatus(message)
                return null
            }
        }

        val musicPatched = try {
            applyMusicEditsToRom(romData)
        } catch (e: Exception) {
            val message = "Export failed: music edit could not be written safely (${e.message})"
            editorLog("ERROR: $message")
            postStatus(message)
            return null
        }
        if (musicPatched > 0) editorLog("[EXPORT] Patched $musicPatched music track edit(s)")

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
                editorLog("[EXPORT] Per-frame hook active: bosses=${enabledBosses.ifEmpty { "none" }}, hyperBeam=$hyperBeam, infiniteBlueSuit=$infiniteBlueSuit")
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
                editorLog("[EXPORT]   Per-frame hook: ${code.size} bytes at \$DF:F040, hook at \$82:896E")
            } else {
                editorLog("[EXPORT] Per-frame hook: not needed (no boss flags, hyper beam, or blue suit)")
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
        editorLog("[EXPORT] B4 free space: raw=0x${rawGfxFreePtr.toString(16)}, guarded=0x${gfxFreePtr.toString(16)} (+2 terminator guard)")

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

        val vanillaEnemyGfxDestinationsBySpecies by lazy {
            collectVanillaEnemyGfxDestinations(romParser)
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
            val hasCustomScrollCmds = roomEdits.customScrollCommands.isNotEmpty()
            if (!hasTileEdits && !hasPlmEdits && !hasDoorEdits && !hasEnemyEdits &&
                !hasScrollEdits && !hasFxEdits && !hasStateEdits && !hasHeaderEdits &&
                !hasCustomScrollCmds) continue
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue

            // Patch tile data — apply edits to ALL states' level data so that
            // non-default states (boss-dead, escape, etc.) also reflect tile changes.
            // Without this, rooms with multiple states show the original layout when
            // a non-default state is active, causing phantom door blocks/caps.
            // ─── Room resize export ─────────────────────────────────────
            // When a room is resized, the export handles 5 things:
            //  1. Level data — resized (L1/BTS/L2), recompressed, auto-relocated if too large
            //  2. Scroll data — resized array relocated to free space in $8F, all state pointers updated
            //  3. Scroll PLM commands — screen indices remapped from old width to new width
            //     (in-place; these are room-specific data, not shared)
            //  4. Door entry ASM — generates brand new 65816 routines with remapped scroll writes,
            //     written to free space. Original shared routines are never touched.
            //     Door entry pointers updated to the new routines.
            //  5. Room header — width/height patched
            // Determine effective dimensions (accounting for resize)
            val hc = roomEdits.roomHeaderChange
            val effectiveWidth = hc?.width ?: room.width
            val effectiveHeight = hc?.height ?: room.height
            val isResized = effectiveWidth != room.width || effectiveHeight != room.height

            if ((hasTileEdits || isResized) && room.levelDataPtr != 0) {
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val bw = effectiveWidth * 16

                // Group states by their level data pointer
                val ptrToStates = mutableMapOf<Int, MutableList<Int>>()
                for (stateOffset in allStateOffsets) {
                    val lvlPtr = (romData[stateOffset].toInt() and 0xFF) or
                            ((romData[stateOffset + 1].toInt() and 0xFF) shl 8) or
                            ((romData[stateOffset + 2].toInt() and 0xFF) shl 16)
                    if (lvlPtr != 0) ptrToStates.getOrPut(lvlPtr) { mutableListOf() }.add(stateOffset)
                }

                if (ptrToStates.size > 1) {
                    editorLog("Room 0x$roomKey: ${ptrToStates.size} distinct level data pointers across ${allStateOffsets.size} states — applying edits to ALL")
                }

                for ((lvlPtr, statesForPtr) in ptrToStates) {
                    val (originalData, origSize) = romParser.decompressLZ2WithSize(lvlPtr)
                    // Apply resize to ROM data if dimensions changed
                    val editedData = if (isResized) {
                        resizeLevelData(originalData, room.width, room.height, effectiveWidth, effectiveHeight)
                    } else {
                        originalData.copyOf()
                    }
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
                        editorLog("ERROR: LZ5 round-trip FAILED for room 0x$roomKey lvlPtr=\$${lvlPtr.toString(16)}! Size: orig=${editedData.size} rt=${roundTripped.size}, first diff at byte $diffIdx")
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
                                editorLog("Room 0x$roomKey: relocated level data \$${lvlPtr.toString(16)} to \$${tryBank.toString(16).uppercase()}:${(newSnes and 0xFFFF).toString(16).uppercase()} (${compressed.size} bytes, updated ${statesForPtr.size} state(s))")
                                relocated = true
                                break
                            }
                        }
                        if (!relocated) {
                            editorLog("WARN: Room 0x$roomKey lvlPtr=\$${lvlPtr.toString(16)} compressed ${compressed.size} > orig $origSize and no free space — skipped")
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
                        if (isEditorItemPlm(plm.id)) {
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
                        editorLog("Room 0x$roomKey: relocated PLM set 0x${psd.plmSetPtr.toString(16)} to 0x${newSnes.toString(16)} (updated $updatedStates states)")
                    } else {
                        editorLog("WARN: Room 0x$roomKey no free space for expanded PLM set 0x${psd.plmSetPtr.toString(16)} — skipped")
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
                        editorLog("  PLM: $name (0x${plm.id.toString(16)}) at (${plm.x},${plm.y}) param=0x${plm.param.toString(16)}")
                    }
                    romData[offset] = 0; romData[offset + 1] = 0
                    if (writePc == plmPc) {
                        for (i in offset + 2 until plmPc + psd.originalSize) romData[i] = 0
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Write custom scroll command data to free space in $8F and resolve PLM params
            if (hasCustomScrollCmds) {
                val cmdIdToAddr = mutableMapOf<String, Int>() // cmdId → SNES 16-bit ptr
                for ((cmdId, commands) in roomEdits.customScrollCommands) {
                    if (commands.isEmpty()) continue
                    val dataSize = commands.size * 2 + 1 // pairs + terminator
                    if (freePtr + dataSize <= bank8FEnd) {
                        for (cmd in commands) {
                            romData[freePtr++] = cmd.screenIndex.toByte()
                            romData[freePtr++] = cmd.scrollValue.toByte()
                        }
                        romData[freePtr++] = 0x80.toByte() // terminator
                        val snesPtr = romParser.pcToSnes(freePtr - dataSize) and 0xFFFF
                        cmdIdToAddr[cmdId] = snesPtr
                        editorLog("Room 0x$roomKey: wrote custom scroll command '$cmdId' (${commands.size} entries) at \$8F:${snesPtr.toString(16).uppercase()}")
                    }
                }
                // Patch PLM params: replace 0xCC00|idx with actual ROM address
                // Scan the PLM data we just wrote to ROM for custom params
                if (cmdIdToAddr.isNotEmpty()) {
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    for (stateOffset in allStateOffsets) {
                        val plmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                            ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                        if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                        val plmPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or plmPtr)
                        var off = plmPc
                        while (off + 5 < romData.size) {
                            val plmId = (romData[off].toInt() and 0xFF) or ((romData[off + 1].toInt() and 0xFF) shl 8)
                            if (plmId == 0) break
                            val paramOff = off + 4
                            val param = (romData[paramOff].toInt() and 0xFF) or ((romData[paramOff + 1].toInt() and 0xFF) shl 8)
                            if (plmId == 0xB703 && (param and 0xFF00) == 0xCC00) {
                                val cmdIdx = param and 0xFF
                                val cmdId = "cmd_$cmdIdx"
                                val addr = cmdIdToAddr[cmdId]
                                if (addr != null) {
                                    romData[paramOff] = (addr and 0xFF).toByte()
                                    romData[paramOff + 1] = ((addr shr 8) and 0xFF).toByte()
                                }
                            }
                            off += 6
                        }
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
                    // Read vanilla door for comparison (from original ROM, not patched copy)
                    val vanillaDestPtr = romParser.readUInt16At(entryPc)
                    val vanillaOrient = romParser.readByteAt(entryPc + 3)
                    val vanillaCapX = romParser.readByteAt(entryPc + 4)
                    val vanillaCapY = romParser.readByteAt(entryPc + 5)
                    val crossArea = if (dc.bitflag and 0x40 != 0) " CROSS-AREA" else ""
                    editorLog("Room 0x$roomKey door $doorIndex: orient=$orientation($dirName$capStr) cap=($capX,$capY) dest=0x${dc.destRoomPtr.toString(16)} entry=0x${dc.entryCode.toString(16)} bitflag=0x${dc.bitflag.toString(16)}$crossArea")
                    editorLog("  vanilla: dest=0x${vanillaDestPtr.toString(16)} orient=$vanillaOrient cap=($vanillaCapX,$vanillaCapY) bitflag=0x${romParser.readUInt16At(entryPc + 2).toString(16)}")

                    var finalCapCode = dc.doorCapCode
                    var finalOrientation = orientation
                    val destRoom = romParser.readRoomHeader(dc.destRoomPtr)
                    if (destRoom != null) {
                        val maxX = destRoom.width * 16
                        val maxY = destRoom.height * 16
                        if (capX >= maxX || capY >= maxY) {
                            // Cap position is out of bounds — try to auto-derive a valid one
                            editorLog("WARN: Room 0x$roomKey door $doorIndex cap position ($capX,$capY) " +
                                "is OUT OF BOUNDS for dest room 0x${dc.destRoomPtr.toString(16)} " +
                                "(${destRoom.width}x${destRoom.height} screens = ${maxX}x${maxY} blocks). " +
                                "screenX=${dc.screenX} screenY=${dc.screenY} orient=$orientation bitflag=0x${dc.bitflag.toString(16)}")
                            val derived = romParser.deriveDoorCapPosition(
                                dc.destRoomPtr, orientation and 3, dc.screenX, dc.screenY
                            )
                            if (derived != null) {
                                finalCapCode = derived
                                val newCapX = derived and 0xFF
                                val newCapY = (derived shr 8) and 0xFF
                                editorLog("  FIX: auto-derived valid cap → ($newCapX,$newCapY)")
                            } else {
                                // Cannot derive — clear cap flag (bit 2) to prevent corrupt PLM
                                finalOrientation = orientation and 0xFB.toInt()
                                editorLog("  FIX: could not derive cap, cleared cap flag (orient $orientation → $finalOrientation)")
                            }
                        }
                    } else {
                        editorLog("WARN: Room 0x$roomKey door $doorIndex dest 0x${dc.destRoomPtr.toString(16)} — could not read dest room header!")
                    }

                    // Auto-fix cross-area flag if source and dest are in different areas
                    var finalBitflag = dc.bitflag
                    if (destRoom != null) {
                        if (room.area != destRoom.area) {
                            if (finalBitflag and 0x40 == 0) {
                                finalBitflag = finalBitflag or 0x40
                                editorLog("  FIX: auto-set cross-area flag (area ${room.area} → ${destRoom.area})")
                            }
                        }
                    }

                    var finalEntryCode = dc.entryCode
                    if (shouldClearEnemyBg2TransferOnDoor(roomId, dc.destRoomPtr)) {
                        val scrollWrites = parseDoorScrollWrites(romParser, dc.entryCode)
                        if (dc.entryCode == 0 || scrollWrites.isNotEmpty()) {
                            val asm = buildDoorAsmClearingEnemyBg2Transfer(scrollWrites)
                            if (freePtr + asm.size <= bank8FEnd) {
                                val newPc = freePtr
                                for (byte in asm) romData[freePtr++] = byte
                                finalEntryCode = romParser.pcToSnes(newPc) and 0xFFFF
                                val preserved = if (scrollWrites.isNotEmpty()) {
                                    ", preserved ${scrollWrites.size} scroll write(s)"
                                } else {
                                    ""
                                }
                                editorLog("  FIX: generated arrival ASM to clear stale enemy BG2 transfer flag$preserved " +
                                    "(was \$8F:${dc.entryCode.toString(16).uppercase()}, now \$8F:${finalEntryCode.toString(16).uppercase()})")
                            } else {
                                editorLog("WARN: Room 0x$roomKey door $doorIndex: no free space for enemy BG2 cleanup door ASM (${asm.size} bytes)")
                            }
                        } else {
                            editorLog("WARN: Room 0x$roomKey door $doorIndex: preserves custom entry ASM " +
                                "\$8F:${dc.entryCode.toString(16).uppercase()}; could not safely add enemy BG2 cleanup")
                        }
                    }

                    val doorListPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or room.doorOut)
                    val doorDefPtr = romParser.readUInt16At(doorListPc + doorIndex * 2)
                    if (doorDefPtr >= 0x8000 && destRoom != null) {
                        val bgDoor = RomParser.DoorEntry(
                            destRoomPtr = dc.destRoomPtr,
                            bitflag = finalBitflag,
                            doorCapCode = finalCapCode,
                            screenX = dc.screenX,
                            screenY = dc.screenY,
                            distFromDoor = dc.distFromDoor,
                            entryCode = finalEntryCode,
                            doorDefPtr = doorDefPtr,
                        )
                        val bgParser = RomParser(romData)
                        val destStateOffsets = bgParser.findAllStateDataOffsets(dc.destRoomPtr)
                        val distinctBgPtrs = destStateOffsets
                            .map { stateOffset ->
                                (romData[stateOffset + 22].toInt() and 0xFF) or
                                    ((romData[stateOffset + 23].toInt() and 0xFF) shl 8)
                            }
                            .filter { it != 0 && it != 0xFFFF }
                            .distinct()
                        for (oldBgPtr in distinctBgPtrs) {
                            val currentBgParser = RomParser(romData)
                            val template = findMatchingDoorDependentBgTransfer(currentBgParser, oldBgPtr, bgDoor)
                                ?: continue
                            val newBgData = buildBgDataWithClonedDoorDependentTransfer(
                                currentBgParser,
                                oldBgPtr,
                                doorDefPtr,
                                template,
                            ) ?: continue
                            if (freePtr + newBgData.size > bank8FEnd) {
                                editorLog("WARN: Room 0x$roomKey door $doorIndex: no free space to clone door-dependent BG data " +
                                    "for dest 0x${dc.destRoomPtr.toString(16)} (${newBgData.size} bytes)")
                                continue
                            }
                            val newPc = freePtr
                            for (byte in newBgData) romData[freePtr++] = byte
                            val newBgPtr = romParser.pcToSnes(newPc) and 0xFFFF
                            for (stateOffset in destStateOffsets) {
                                val stateBgPtr = (romData[stateOffset + 22].toInt() and 0xFF) or
                                    ((romData[stateOffset + 23].toInt() and 0xFF) shl 8)
                                if (stateBgPtr == oldBgPtr) {
                                    romData[stateOffset + 22] = (newBgPtr and 0xFF).toByte()
                                    romData[stateOffset + 23] = ((newBgPtr shr 8) and 0xFF).toByte()
                                }
                            }
                            roomsPatched.add(dc.destRoomPtr.toString(16).uppercase())
                            editorLog("  FIX: cloned door-dependent BG transfer for dest room " +
                                "0x${dc.destRoomPtr.toString(16)} door \$83:${doorDefPtr.toString(16).uppercase()} " +
                                "from template door \$83:${template.doorDefPtr.toString(16).uppercase()} " +
                                "(bg \$8F:${oldBgPtr.toString(16).uppercase()} → \$8F:${newBgPtr.toString(16).uppercase()})")
                        }
                    }

                    romData[entryPc] = (dc.destRoomPtr and 0xFF).toByte()
                    romData[entryPc + 1] = ((dc.destRoomPtr shr 8) and 0xFF).toByte()
                    romData[entryPc + 2] = (finalBitflag and 0xFF).toByte()
                    romData[entryPc + 3] = finalOrientation.toByte()
                    romData[entryPc + 4] = (finalCapCode and 0xFF).toByte()
                    romData[entryPc + 5] = ((finalCapCode shr 8) and 0xFF).toByte()
                    romData[entryPc + 6] = (dc.screenX and 0xFF).toByte()
                    romData[entryPc + 7] = (dc.screenY and 0xFF).toByte()
                    romData[entryPc + 8] = (dc.distFromDoor and 0xFF).toByte()
                    romData[entryPc + 9] = ((dc.distFromDoor shr 8) and 0xFF).toByte()
                    romData[entryPc + 10] = (finalEntryCode and 0xFF).toByte()
                    romData[entryPc + 11] = ((finalEntryCode shr 8) and 0xFF).toByte()
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
                    editorLog("Room 0x$roomKey: relocated enemy set to 0x${newSnes.toString(16)}")
                } else {
                    editorLog("WARN: Room 0x$roomKey no free space for expanded enemy set — skipped enemy patch")
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
                    editorLog("Room 0x$roomKey: skipped ${skippedVanilla.size} vanilla species from GFX set " +
                            "(${skippedVanilla.joinToString { "0x${it.toString(16)}" }})")
                }
                if (neededSpecies.isEmpty()) return@gfxPatch

                val newEntries = gfxEntries.toMutableList()
                for (specId in neededSpecies) {
                    if (newEntries.size >= 4) {
                        editorLog("WARN: Room 0x$roomKey GFX set already has ${newEntries.size} entries (SNES max 4) — skipping species 0x${specId.toString(16)}")
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
                        editorLog("WARN: Room 0x$roomKey: skipping species 0x${specId.toString(16)} from GFX set — HP=0 (invalid species ID)")
                        continue
                    }
                    val vramDestination = selectEnemyGfxVramDestination(
                        existingEntries = newEntries,
                        vanillaDestinations = vanillaEnemyGfxDestinationsBySpecies[specId].orEmpty(),
                    )
                    if (vramDestination == null) {
                        editorLog("WARN: Room 0x$roomKey: skipping species 0x${specId.toString(16)} from GFX set — no safe VRAM destination available")
                        continue
                    }
                    newEntries.add(RomParser.EnemyGfxEntry(specId, vramDestination))
                    editorLog("Room 0x$roomKey: added species 0x${specId.toString(16)} to GFX set (vramDst=0x${vramDestination.toString(16)})")
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
                    editorLog("Room 0x$roomKey: relocated GFX set to 0x${newGfxSnes.toString(16)}")
                } else {
                    editorLog("WARN: Room 0x$roomKey no free space in bank \$B4 for expanded GFX set")
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
            if ((hasScrollEdits || isResized) && room.roomScrollsPtr > 1) {
                val originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
                // Build scroll array at effective dimensions
                val modifiedScrolls = if (isResized) {
                    val resized = IntArray(effectiveWidth * effectiveHeight) { 1 }
                    for (sy in 0 until minOf(room.height, effectiveHeight))
                        for (sx in 0 until minOf(room.width, effectiveWidth)) {
                            val oldIdx = sy * room.width + sx
                            val newIdx = sy * effectiveWidth + sx
                            if (oldIdx in originalScrolls.indices) resized[newIdx] = originalScrolls[oldIdx]
                        }
                    resized
                } else {
                    originalScrolls.copyOf()
                }
                for (sc in roomEdits.scrollChanges) {
                    val idx = sc.screenY * effectiveWidth + sc.screenX
                    if (idx in modifiedScrolls.indices) modifiedScrolls[idx] = sc.newValue
                }
                val scrollPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or room.roomScrollsPtr)
                if (modifiedScrolls.size <= originalScrolls.size) {
                    // Fits in-place — write and zero any leftover
                    for (i in modifiedScrolls.indices) {
                        if (scrollPc + i < romData.size) romData[scrollPc + i] = modifiedScrolls[i].toByte()
                    }
                    for (i in modifiedScrolls.size until originalScrolls.size) {
                        if (scrollPc + i < romData.size) romData[scrollPc + i] = 0.toByte()
                    }
                } else {
                    // Scroll data grew — MUST relocate to avoid corrupting adjacent data.
                    // Find free space at end of bank $8F by scanning backwards from bank end.
                    val bank8F = (RomConstants.BANK_ROOM_DATA shr 16) and 0xFF
                    val bankEnd = romParser.snesToPc((bank8F shl 16) or 0xFFFF) + 1
                    val bankStart = romParser.snesToPc((bank8F shl 16) or 0x8000)
                    var freePtr = bankEnd
                    while (freePtr > bankStart && (romData[freePtr - 1].toInt() and 0xFF) == 0xFF) freePtr--
                    freePtr++ // first free byte
                    if (freePtr + modifiedScrolls.size <= bankEnd) {
                        // Write scroll data at free space
                        for (i in modifiedScrolls.indices) romData[freePtr + i] = modifiedScrolls[i].toByte()
                        val newSnesPtr = romParser.pcToSnes(freePtr) and 0xFFFF // 16-bit offset within bank
                        // Zero out old scroll data
                        for (i in originalScrolls.indices) {
                            if (scrollPc + i < romData.size) romData[scrollPc + i] = 0xFF.toByte()
                        }
                        // Update scroll pointer in ALL states for this room
                        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                        for (stateOff in allStateOffsets) {
                            val scrollPtrOff = stateOff + 14
                            romData[scrollPtrOff] = (newSnesPtr and 0xFF).toByte()
                            romData[scrollPtrOff + 1] = ((newSnesPtr shr 8) and 0xFF).toByte()
                        }
                        editorLog("Room 0x$roomKey: relocated scroll data \$${room.roomScrollsPtr.toString(16)} to \$8F:${newSnesPtr.toString(16).uppercase()} (${modifiedScrolls.size} bytes, updated ${allStateOffsets.size} state(s))")
                    } else {
                        editorLog("WARN: Room 0x$roomKey: no free space in bank \$8F for expanded scroll data (${modifiedScrolls.size} bytes) — writing in-place (WILL CORRUPT adjacent data!)")
                        for (i in modifiedScrolls.indices) {
                            if (scrollPc + i < romData.size) romData[scrollPc + i] = modifiedScrolls[i].toByte()
                        }
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Step 3: Remap scroll PLM command screen indices (old width → new width)
            if (isResized && effectiveWidth != room.width) {
                val allPlms = romParser.getAllPlmEntriesForRoom(roomId)
                val scrollTriggerPlms = allPlms.filter { it.id == 0xB703 }
                val remappedPtrs = mutableSetOf<Int>()
                for (plm in scrollTriggerPlms) {
                    val cmdPtr = plm.param and 0xFFFF
                    if (cmdPtr == 0 || cmdPtr in remappedPtrs) continue
                    remappedPtrs.add(cmdPtr)
                    val snesAddr = 0x8F0000 or cmdPtr
                    val pc = romParser.snesToPc(snesAddr)
                    var offset = 0
                    var remapped = 0
                    while (offset < 256) {
                        val screenIdx = romData[pc + offset].toInt() and 0xFF
                        if (screenIdx >= 0x80) break // terminator
                        // Convert: old flat index → (col, row) → new flat index
                        val col = screenIdx % room.width
                        val row = screenIdx / room.width
                        if (row < effectiveHeight && col < effectiveWidth) {
                            val newIdx = row * effectiveWidth + col
                            romData[pc + offset] = newIdx.toByte()
                            if (newIdx != screenIdx) remapped++
                        }
                        offset += 2
                    }
                    if (remapped > 0) {
                        editorLog("Room 0x$roomKey: remapped $remapped screen indices in scroll command at \$8F:${cmdPtr.toString(16).uppercase()} (width ${ room.width}→$effectiveWidth)")
                    }
                }
            }

            // Step 4: Generate new door entry ASM with remapped scroll indices.
            // Door entry ASM sets initial scroll state when Samus enters through a door.
            // These routines are often SHARED across multiple rooms, so patching in-place
            // would corrupt other rooms. Instead we: read scroll writes from the original,
            // generate a new routine with remapped indices, write to free space, and
            // update the door entry pointer.
            if (isResized && effectiveWidth != room.width) {
                val incomingDoors = romParser.findDoorsLeadingTo(roomId)
                val generatedAsmPtrs = mutableMapOf<Int, Int>() // old entryCode → new SNES ptr
                for (door in incomingDoors) {
                    if (door.entryCode == 0 || door.entryCode == 0xFFFF) continue
                    if (door.entryCode in generatedAsmPtrs) continue

                    // Read scroll writes from original ASM
                    val origPc = romParser.snesToPc(0x8F0000 or door.entryCode)
                    data class ScrollWrite(val scrollValue: Int, val screenIdx: Int)
                    val writes = mutableListOf<ScrollWrite>()
                    var i = 0
                    while (i < 60) {
                        val b = romParser.readByteAt(origPc + i)
                        if (b == 0x6B) break // RTL
                        if (b == 0xA9 && i + 5 < 60) {
                            val imm = romParser.readByteAt(origPc + i + 1)
                            val next = romParser.readByteAt(origPc + i + 2)
                            if (next == 0x8F) {
                                val lo = romParser.readByteAt(origPc + i + 3)
                                val hi = romParser.readByteAt(origPc + i + 4)
                                val bank = romParser.readByteAt(origPc + i + 5)
                                if (hi == 0xCD && bank == 0x7E && lo in 0x20..0x7F) {
                                    writes.add(ScrollWrite(imm, lo - 0x20))
                                }
                                i += 6; continue
                            }
                        }
                        if ((b == 0xE2 || b == 0xC2) && i + 1 < 60) { i += 2; continue }
                        i++
                    }
                    if (writes.isEmpty()) continue

                    // Generate new ASM: SEP #$20; [LDA #val; STA $7ECD{20+newIdx}]...; RTL
                    // Each write = 6 bytes (A9 xx 8F ll CD 7E). Header = 2 (E2 20). Footer = 1 (6B).
                    val asmSize = 2 + writes.size * 6 + 1
                    if (freePtr + asmSize > bank8FEnd) {
                        editorLog("WARN: Room 0x$roomKey: no free space for door ASM generation (${asmSize} bytes)")
                        continue
                    }
                    val newPc = freePtr
                    romData[freePtr++] = 0xE2.toByte() // SEP
                    romData[freePtr++] = 0x20.toByte() // #$20
                    for (w in writes) {
                        val col = w.screenIdx % room.width
                        val row = w.screenIdx / room.width
                        val newIdx = if (row < effectiveHeight && col < effectiveWidth)
                            row * effectiveWidth + col else w.screenIdx
                        romData[freePtr++] = 0xA9.toByte() // LDA #imm8
                        romData[freePtr++] = w.scrollValue.toByte()
                        romData[freePtr++] = 0x8F.toByte() // STA long
                        romData[freePtr++] = (0x20 + newIdx).toByte()
                        romData[freePtr++] = 0xCD.toByte()
                        romData[freePtr++] = 0x7E.toByte()
                    }
                    romData[freePtr++] = 0x6B.toByte() // RTL
                    val newSnesPtr = romParser.pcToSnes(newPc) and 0xFFFF
                    generatedAsmPtrs[door.entryCode] = newSnesPtr
                    editorLog("Room 0x$roomKey: generated new door ASM at \$8F:${newSnesPtr.toString(16).uppercase()} (${writes.size} scroll writes, was \$8F:${door.entryCode.toString(16).uppercase()})")
                }

                // Update door entry entryCode pointers in ROM
                if (generatedAsmPtrs.isNotEmpty()) {
                    val allRooms = com.supermetroid.editor.data.RoomRepository().getAllRooms()
                    for (info in allRooms) {
                        val srcId = info.getRoomIdAsInt()
                        val srcRoom = romParser.readRoomHeader(srcId) ?: continue
                        if (srcRoom.doorOut == 0) continue
                        val doors = romParser.parseDoorList(srcRoom.doorOut)
                        for ((di, d) in doors.withIndex()) {
                            if (d.destRoomPtr == roomId && d.entryCode in generatedAsmPtrs) {
                                val entryPc = romParser.doorEntryPcOffset(srcRoom.doorOut, di) ?: continue
                                val newPtr = generatedAsmPtrs[d.entryCode]!!
                                romData[entryPc + 10] = (newPtr and 0xFF).toByte()
                                romData[entryPc + 11] = ((newPtr shr 8) and 0xFF).toByte()
                            }
                        }
                    }
                }
            }

            // Patch FX data — apply to ALL states' FX pointers, not just default
            if (hasFxEdits) {
                val fx = roomEdits.fxChange!!
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val patchedFxPtrs = mutableSetOf<Int>()
                for (stateOffset in allStateOffsets) {
                    val stateFxPtr = romParser.readUInt16At(stateOffset + 6)
                    if (stateFxPtr == 0 || stateFxPtr == 0xFFFF || stateFxPtr in patchedFxPtrs) continue
                    patchedFxPtrs.add(stateFxPtr)
                    val fxEntries = romParser.parseFxEntries(stateFxPtr)
                    if (fxEntries.isEmpty()) continue
                    val fxSnesAddr = RomConstants.BANK_FX or stateFxPtr
                    var fxPc = romParser.snesToPc(fxSnesAddr)
                    for (entry in fxEntries) {
                        if (entry.doorSelect == 0) {
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
                }
                if (patchedFxPtrs.isNotEmpty()) {
                    editorLog("Room 0x$roomKey: patched FX for ${patchedFxPtrs.size} state(s)")
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
                editorLog("Room 0x$roomKey: patched room header")
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
                    editorLog("Patched CRE graphics in-place (${compressed.size}/$origSize bytes)")
                } else {
                    editorLog("WARN: Compressed CRE gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { editorLog("WARN: CRE gfx patch failed: ${e.message}") }
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
                    editorLog("Patched tileset $tsId variable gfx in-place (${compressed.size}/$origSize bytes)")
                } else {
                    editorLog("WARN: Compressed tileset $tsId gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { editorLog("WARN: Tileset $tsId gfx patch failed: ${e.message}") }
        }

        // Custom palette overrides per tileset (raw BGR555 → LZ5 compress → write in-place)
        for ((tsIdStr, palB64) in gfxData.palettes) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawPal = java.util.Base64.getDecoder().decode(palB64)
                if (rawPal.size != 256) { editorLog("WARN: Palette $tsId has ${rawPal.size} bytes (expected 256) — skipped"); continue }
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
                    editorLog("Patched tileset $tsId palette in-place (${compressed.size}/$origSize bytes)")
                } else {
                    editorLog("WARN: Compressed tileset $tsId palette (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { editorLog("WARN: Tileset $tsId palette patch failed: ${e.message}") }
        }

        // Apply sprite palette overrides (Samus, beams, bosses, enemies — raw BGR555, no compression)
        for ((regionId, palB64) in gfxData.spritePalettes) {
            val region = com.supermetroid.editor.rom.SpritePalettes.findRegion(regionId) ?: continue
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(palB64)
                val colors = com.supermetroid.editor.rom.SpritePalettes.bytesToColors(rawBytes)
                if (colors.size == region.colorCount) {
                    com.supermetroid.editor.rom.SpritePalettes.writeColors(romData, region, colors)
                    gfxPatched++
                    editorLog("Patched sprite palette '${region.name}' (${region.byteSize} bytes at 0x${region.offset.toString(16)})")
                }
            } catch (e: Exception) { editorLog("WARN: Sprite palette '$regionId' patch failed: ${e.message}") }
        }

        // Apply Phantoon sprite tile patches (raw 4bpp → LZ5 compress → write to $B7)
        editorLog("[EXPORT] Phantoon sprite blocks: spriteTileBlocks.keys=${gfxData.spriteTileBlocks.keys}, size=${gfxData.spriteTileBlocks.size}")
        for ((i, block) in com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val b64 = gfxData.spriteTileBlocks["phantoon:$i"]
            if (b64 == null) {
                editorLog("[EXPORT] Phantoon block $i: NO DATA in spriteTileBlocks (key 'phantoon:$i' not found)")
                continue
            }
            editorLog("[EXPORT] Phantoon block $i: found ${b64.length} b64 chars")
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                editorLog("[EXPORT] Phantoon block $i: decoded to ${rawBytes.size} raw bytes")
                val compressed = lz5Compress(rawBytes)
                editorLog("[EXPORT] Phantoon block $i: compressed to ${compressed.size} bytes")
                val (_, origSize) = romParser.decompressLZ2WithSize(block.snesAddress)
                editorLog("[EXPORT] Phantoon block $i: original compressed size=$origSize, fits=${compressed.size <= origSize}")
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                    for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    gfxPatched++
                    editorLog("[EXPORT] Patched Phantoon sprite tile block $i: ${compressed.size}/$origSize bytes at PC=0x${block.pcAddress.toString(16)}")
                } else {
                    editorLog("[EXPORT] WARN: Phantoon sprite block $i compressed size ${compressed.size} exceeds original $origSize — skipped")
                }
            } catch (e: Exception) {
                editorLog("[EXPORT] WARN: Phantoon sprite block $i patch failed: ${e.message}")
                editorStateLog.error(e) { "[EXPORT] Phantoon sprite block $i patch failed" }
            }
        }

        // Apply Kraid sprite tile patches (raw 4bpp → LZ5 compress → write to $B9)
        for ((i, block) in com.supermetroid.editor.rom.EnemySpriteGraphics.KRAID_BLOCKS.withIndex()) {
            val b64 = gfxData.spriteTileBlocks["kraid:$i"]
            if (b64 == null) continue
            editorLog("[EXPORT] Kraid block $i: found ${b64.length} b64 chars")
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                val compressed = lz5Compress(rawBytes)
                val (_, origSize) = romParser.decompressLZ2WithSize(block.snesAddress)
                editorLog("[EXPORT] Kraid block $i: ${compressed.size}/$origSize bytes")
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                    for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    gfxPatched++
                    editorLog("[EXPORT] Patched Kraid sprite tile block $i at PC=0x${block.pcAddress.toString(16)}")
                } else {
                    editorLog("[EXPORT] WARN: Kraid sprite block $i compressed size ${compressed.size} exceeds original $origSize — skipped")
                }
            } catch (e: Exception) {
                editorLog("[EXPORT] WARN: Kraid sprite block $i patch failed: ${e.message}")
            }
        }

        // Apply generic enemy sprite tile patches (raw 4bpp, uncompressed, write in-place)
        for ((key, b64) in gfxData.spriteTileBlocks) {
            if (!key.startsWith("enemy:")) continue
            val speciesHex = key.removePrefix("enemy:")
            val speciesId = speciesHex.toIntOrNull(16) ?: continue
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                val block = com.supermetroid.editor.rom.EnemySpriteGraphics.readGraphicsBlock(romParser, speciesId)
                val stats = com.supermetroid.editor.rom.EnemySpriteGraphics.readSpeciesStats(romParser, speciesId)
                if (block == null || stats == null) {
                    editorLog("[EXPORT] WARN: Enemy $speciesHex: could not resolve GRAPHADR or stats — skipped")
                    continue
                }
                val tileDataSize = stats.first
                if (rawBytes.size != tileDataSize) {
                    editorLog("[EXPORT] WARN: Enemy $speciesHex: raw size ${rawBytes.size} != expected tileDataSize $tileDataSize — skipped")
                    continue
                }
                if (block.pcAddress + tileDataSize > romData.size) {
                    editorLog("[EXPORT] WARN: Enemy $speciesHex: write would exceed ROM bounds — skipped")
                    continue
                }
                System.arraycopy(rawBytes, 0, romData, block.pcAddress, rawBytes.size)
                gfxPatched++
                editorLog("[EXPORT] Patched enemy $speciesHex sprite tiles: ${rawBytes.size} bytes at PC=0x${block.pcAddress.toString(16)} (SNES \$${block.snesAddress.toString(16).uppercase()})")
            } catch (e: Exception) {
                editorLog("[EXPORT] WARN: Enemy $speciesHex sprite patch failed: ${e.message}")
            }
        }

        // Apply enemy palette patches (32 bytes BGR555 at palPtr address)
        for ((key, b64) in gfxData.spritePalettes) {
            if (!key.startsWith("enemy_pal:")) continue
            val speciesHex = key.removePrefix("enemy_pal:")
            val speciesId = speciesHex.toIntOrNull(16) ?: continue
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                if (rawBytes.size != 32) {
                    editorLog("[EXPORT] WARN: Enemy palette $speciesHex: expected 32 bytes, got ${rawBytes.size} — skipped")
                    continue
                }
                val rom = romParser.getRomData()
                val headerPc = romParser.snesToPc(com.supermetroid.editor.rom.RomConstants.BANK_ENEMY_AI or speciesId)
                if (headerPc < 0 || headerPc + 0x0D > rom.size) {
                    editorLog("[EXPORT] WARN: Enemy palette $speciesHex: invalid species header — skipped")
                    continue
                }
                val palPtr = com.supermetroid.editor.rom.readU16(rom, headerPc + 2)
                val aiBank = com.supermetroid.editor.rom.readU8(rom, headerPc + 0x0C)
                val palSnes = (aiBank shl 16) or (palPtr and 0xFFFF)
                val palPc = romParser.snesToPc(palSnes)
                if (palPc < 0 || palPc + 32 > romData.size) {
                    editorLog("[EXPORT] WARN: Enemy palette $speciesHex: palette address out of bounds — skipped")
                    continue
                }
                System.arraycopy(rawBytes, 0, romData, palPc, 32)
                gfxPatched++
                editorLog("[EXPORT] Patched enemy $speciesHex palette: 32 bytes at PC=0x${palPc.toString(16)} (SNES \$${palSnes.toString(16).uppercase()})")
            } catch (e: Exception) {
                editorLog("[EXPORT] WARN: Enemy palette $speciesHex patch failed: ${e.message}")
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
            editorLog("Minimap area $area: patched ${edits.size} tiles")
        }

        // ─── Text edits ────────────────────────────────────────────
        var textPatched = 0
        val allText = if (project.textEdits.isNotEmpty()) TextData.readAllText(romParser.getRomData()) else emptyList()
        for ((id, newText) in project.textEdits) {
            val entry = allText.find { it.id == id } ?: continue
            val patched = when (entry.category) {
                com.supermetroid.editor.rom.TextCategory.AREA_NAME -> TextData.encodeAreaName(newText, entry.rawBytes)
                com.supermetroid.editor.rom.TextCategory.ESCAPE_TEXT -> TextData.encodeEscapeText(newText, entry.rawBytes)
                com.supermetroid.editor.rom.TextCategory.UI_MESSAGE -> TextData.encodeUiMessage(newText, entry.rawBytes)
                com.supermetroid.editor.rom.TextCategory.ITEM_NAME -> TextData.encodeUiMessage(newText, entry.rawBytes)
                com.supermetroid.editor.rom.TextCategory.INTRO_STORY -> TextData.encodeGreenText(newText, entry.rawBytes)
            }
            for (i in patched.indices) {
                if (entry.pcOffset + i < romData.size) {
                    romData[entry.pcOffset + i] = patched[i]
                }
            }
            textPatched++
        }
        if (textPatched > 0) editorLog("[EXPORT] Patched $textPatched text entries")

        // ─── Custom ASM embedding ─────────────────────────────────────
        // Write custom code bytes to free space in bank $A0 and update
        // the species header pointer to the new address.
        var asmPatched = 0
        for ((key, entry) in project.customAsm) {
            val parts = key.split(":")
            if (parts.size != 2) continue
            val speciesId = parts[0].toIntOrNull(16) ?: continue
            val fieldName = parts[1]

            val headerOffset = when (fieldName) {
                "initAi" -> 0x12; "mainAi" -> 0x16; "touchAi" -> 0x30
                "shotAi" -> 0x32; "hurtAi" -> 0x1C; "frozenAi" -> 0x1E
                "grappleAi" -> 0x1A; "deathAnim" -> 0x22
                else -> continue
            }

            val codeBytes = entry.hexBytes.trim().split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()
            if (codeBytes.isEmpty()) continue

            // Find free space in bank $A0 (scan backwards from end)
            val bankStart = romParser.snesToPc(0xA08000)
            val bankEnd = romParser.snesToPc(0xA0FFFF) + 1
            var freePtr = bankEnd
            while (freePtr > bankStart && romData[freePtr - 1] == 0xFF.toByte()) freePtr--
            freePtr++ // leave 1 byte gap

            if (freePtr + codeBytes.size > bankEnd) {
                editorLog("[EXPORT] WARN: Not enough free space in bank \$A0 for custom ASM ($key)")
                continue
            }

            // Write code bytes
            System.arraycopy(codeBytes, 0, romData, freePtr, codeBytes.size)

            // Calculate SNES address
            val newSnesPtr = 0x8000 + (freePtr - bankStart)

            // Update species header pointer
            val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (headerPc + headerOffset + 1 < romData.size) {
                romData[headerPc + headerOffset] = (newSnesPtr and 0xFF).toByte()
                romData[headerPc + headerOffset + 1] = ((newSnesPtr shr 8) and 0xFF).toByte()
            }

            asmPatched++
            val label = entry.label.ifEmpty { fieldName }
            editorLog("[EXPORT] Custom ASM: $label → \$A0:${newSnesPtr.toString(16).uppercase()} (${codeBytes.size} bytes) for species \$${parts[0]}")
        }
        if (asmPatched > 0) editorLog("[EXPORT] Embedded $asmPatched custom ASM routine(s)")

        if (roomsPatched.isEmpty() && patchesApplied == 0 && musicPatched == 0 && gfxPatched == 0 && minimapPatched == 0 && textPatched == 0 && asmPatched == 0) {
            val orig = File(romPath)
            val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
            out.writeBytes(romData)
            editorLog("Exported (vanilla copy, no edits): ${out.absolutePath}")
            return out.absolutePath
        }

        // ─── Export verification pass ───────────────────────────────
        // Re-read all modified data from the export copy and validate.
        editorLog("\n=== Export Verification ===")
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
                editorLog("Room 0x$roomKey: ${allStateOffsets.size} states, ${distinctLevelPtrs.size} distinct level ptrs, ${distinctPlmPtrs.size} distinct PLM ptrs")
                for (info in stateInfos) editorLog(info)
            }

            // Verify each distinct level data pointer decompresses correctly
            for (lvlPtr in distinctLevelPtrs) {
                if (lvlPtr == 0) continue
                try {
                    val decompressed = exportParser.decompressLZ2(lvlPtr)
                    if (decompressed.isEmpty()) {
                        editorLog("  ERROR: level data at \$${lvlPtr.toString(16)} decompressed to 0 bytes!")
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
                        editorLog("  level data \$${lvlPtr.toString(16)}: $doorBlockCount door blocks (type 9)")
                    }
                } catch (e: Exception) {
                    editorLog("  ERROR: failed to decompress level data at \$${lvlPtr.toString(16)}: ${e.message}")
                    verifyErrors++
                }
            }

            // Verify each distinct PLM set is properly terminated
            for (plmPtr in distinctPlmPtrs) {
                if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                val plms = exportParser.parsePlmSet(plmPtr)
                val doorCaps = plms.filter { RomParser.doorCapColor(it.id) != null }
                if (doorCaps.isNotEmpty()) {
                    editorLog("  PLM set \$${plmPtr.toString(16)}: ${plms.size} entries, ${doorCaps.size} door cap(s):")
                    for (dc in doorCaps) {
                        val name = RomParser.doorCapDisplayName(dc.id) ?: "Unknown"
                        editorLog("    $name at (${dc.x},${dc.y}) param=0x${dc.param.toString(16)}")
                    }
                }
            }
        }
        if (verifyErrors > 0) {
            editorLog("EXPORT VERIFICATION: $verifyErrors error(s) found!")
        } else {
            editorLog("EXPORT VERIFICATION: all checks passed")
        }
        editorLog("=== End Verification ===\n")

        val orig = File(romPath)
        val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
        out.writeBytes(romData)
        val msg = "Exported ROM: ${out.absolutePath} (${roomsPatched.size} rooms, $patchesApplied patches, $musicPatched music, $gfxPatched gfx)"
        editorLog(msg)
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
            editorLog("[IPS] ROM size mismatch: ${original.size} vs ${patched.size}")
            return null
        }

        val ipsData = buildIpsPatch(original, patched)
        val orig = File(romPath)
        val ipsFile = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.ips")
        ipsFile.writeBytes(ipsData)
        val msg = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        editorLog(msg)
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
