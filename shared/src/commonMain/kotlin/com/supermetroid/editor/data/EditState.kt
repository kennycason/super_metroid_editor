package com.supermetroid.editor.data

import kotlinx.serialization.Serializable

const val TILE_EDIT_LAYER_1 = 1
const val TILE_EDIT_LAYER_2 = 2

/**
 * A single tile edit: records what one block changed from/to.
 * The block word is the full 16-bit value (metatile index + flip + block type).
 * Layer 1 edits also carry BTS; embedded Layer 2 edits ignore BTS and store
 * only metatile + flip bits in the block word.
 */
@Serializable
data class TileEdit(
    val blockX: Int,
    val blockY: Int,
    val oldBlockWord: Int,   // previous 16-bit block word
    val newBlockWord: Int,   // new 16-bit block word
    val oldBts: Int = 0,     // previous BTS byte
    val newBts: Int = 0,     // new BTS byte
    val layer: Int = TILE_EDIT_LAYER_1
)

/**
 * A single undoable operation: one or more tile edits applied together,
 * optionally combined with PLM and enemy changes.
 */
@Serializable
data class EditOperation(
    val description: String,
    val edits: List<TileEdit> = emptyList(),
    val plmAdds: List<PlmChange> = emptyList(),
    val plmRemoves: List<PlmChange> = emptyList(),
    val enemyAdds: List<EnemyChange> = emptyList(),
    val enemyRemoves: List<EnemyChange> = emptyList(),
    val enemyUpdates: List<EnemyUpdate> = emptyList(),
    val scrollEdits: List<ScrollChange> = emptyList(),
    val stateDataBefore: StateDataChange? = null,
    val stateDataAfter: StateDataChange? = null,
    val fxBefore: FxChange? = null,
    val fxAfter: FxChange? = null,
)

@Serializable
data class EnemyUpdate(
    val old: EnemyChange,
    val new: EnemyChange
)

/**
 * A PLM (Post Load Modification) change: add or remove a PLM entry.
 * Used for item placement and other PLM edits.
 */
@Serializable
data class PlmChange(
    val action: String,      // "add" or "remove"
    val plmId: Int,
    val x: Int,
    val y: Int,
    val param: Int = 0
)

/**
 * A custom scroll command: sets a screen's scroll value when a scroll trigger PLM fires.
 * Used to build command data for new B703 PLMs.
 */
@Serializable
data class ScrollCommand(
    val screenIndex: Int,    // flat index: screenY * roomWidth + screenX
    val scrollValue: Int     // 0=Red, 1=Blue, 2=Green
)

/**
 * Override for one AreaSave table entry. Save station PLMs reference these by
 * area + saveIndex. Coordinates are stored as raw 16-bit values, matching ROM
 * encoding; Samus X may intentionally be negative in two's-complement form.
 */
@Serializable
data class SaveStationSpawnChange(
    val area: Int,
    val saveIndex: Int,
    val roomId: Int,
    val doorPtr: Int,
    val scrollX: Int,
    val scrollY: Int,
    val samusY: Int,
    val samusX: Int,
    val autoDerived: Boolean = false,
)

/**
 * A door property change: modify one field of a door entry.
 */
@Serializable
data class DoorChange(
    val doorIndex: Int,
    val destRoomPtr: Int,
    val bitflag: Int,
    val doorCapCode: Int,
    val screenX: Int,
    val screenY: Int,
    val distFromDoor: Int,
    val entryCode: Int
)

/**
 * An enemy population change: add, remove, or update an enemy entry.
 * Coordinates are in pixels (same units as the ROM's enemy population data).
 * extra1/extra2/extra3 are the 3 trailing 16-bit fields per entry that must
 * be preserved to avoid crashes (graphics hint, speed, speed2).
 */
@Serializable
data class EnemyChange(
    val action: String,       // "add", "remove", or "update"
    val enemyId: Int,
    val x: Int,
    val y: Int,
    val initParam: Int = 0,
    val properties: Int = 0,
    val extra1: Int = 0,
    val extra2: Int = 0,
    val extra3: Int = 0,
    val origX: Int = 0,       // for "remove"/"update": match the original position
    val origY: Int = 0
)

/**
 * A room scroll change: set a single screen's scroll value.
 * Values: 0x00=Red (hidden), 0x01=Blue (explorable), 0x02=Green (show floor).
 */
@Serializable
data class ScrollChange(
    val screenX: Int,
    val screenY: Int,
    val oldValue: Int,
    val newValue: Int
)

/**
 * An FX field change: modify one or more fields of the default FX entry.
 * Only non-null fields are applied on export.
 */
@Serializable
data class FxChange(
    val fxType: Int? = null,
    val liquidSurfaceStart: Int? = null,
    val liquidSurfaceNew: Int? = null,
    val liquidSpeed: Int? = null,
    val liquidDelay: Int? = null,
    val fxBitA: Int? = null,
    val fxBitB: Int? = null,
    val fxBitC: Int? = null,
    val paletteFxBitflags: Int? = null,
    val tileAnimBitflags: Int? = null,
    val paletteBlend: Int? = null
)

/**
 * A state data field change: modify header-level room properties.
 * Only non-null fields are applied on export.
 */
@Serializable
data class StateDataChange(
    val tileset: Int? = null,
    val musicData: Int? = null,
    val musicTrack: Int? = null,
    val bgScrolling: Int? = null
)

/**
 * Room header change: modify the 11-byte room header in bank $8F.
 * Only non-null fields are applied on export. Field names match the Room data class.
 *
 * Header layout (relative to room PC offset):
 *   Byte 0: index, 1: area, 2: mapX, 3: mapY, 4: width, 5: height,
 *   6: upScroller, 7: downScroller, 8: creBitflag, 9-10: doorOut (LE word)
 */
@Serializable
data class RoomHeaderChange(
    val index: Int? = null,          // Byte 0: room index (0-255)
    val area: Int? = null,           // Byte 1: 0-6 (Crateria, Brinstar, Norfair, Wrecked Ship, Maridia, Tourian, Ceres)
    val mapX: Int? = null,           // Byte 2: 0-63 minimap X position
    val mapY: Int? = null,           // Byte 3: 0-31 minimap Y position
    val width: Int? = null,          // Byte 4: room width in screens (1-15)
    val height: Int? = null,         // Byte 5: room height in screens (1-15)
    val upScroller: Int? = null,     // Byte 6: screen-edge up scroller threshold (0x70 default, 0x90 grapple)
    val downScroller: Int? = null,   // Byte 7: screen-edge down scroller threshold (0xA0 default)
    val creBitflag: Int? = null,     // Byte 8: 0x00=no CRE, 0x01=CRE used, 0x02=has BG, 0x05=CRE+BG
    val doorOut: Int? = null,        // Bytes 9-10: door out pointer (16-bit, within bank $8F)
)

/**
 * Per-room edit state: all operations applied to a specific room.
 */
@Serializable
data class RoomEdits(
    val roomId: Int,             // e.g. 0x91F8
    val operations: MutableList<EditOperation> = mutableListOf(),
    val plmChanges: MutableList<PlmChange> = mutableListOf(),
    val doorChanges: MutableList<DoorChange> = mutableListOf(),
    val enemyChanges: MutableList<EnemyChange> = mutableListOf(),
    val scrollChanges: MutableList<ScrollChange> = mutableListOf(),
    var fxChange: FxChange? = null,
    var stateDataChange: StateDataChange? = null,
    var roomHeaderChange: RoomHeaderChange? = null,
    /** Custom scroll command data keyed by a unique command ID (e.g. "cmd_0", "cmd_1").
     *  Each entry is a list of (screenIndex, scrollValue) pairs.
     *  On export, each is written to free space in $8F and the PLM param is set to the address. */
    val customScrollCommands: MutableMap<String, MutableList<ScrollCommand>> = mutableMapOf(),
    val saveStationSpawns: MutableList<SaveStationSpawnChange> = mutableListOf(),
) {
    val hasEdits: Boolean get() =
        operations.isNotEmpty() || plmChanges.isNotEmpty() || doorChanges.isNotEmpty() ||
        enemyChanges.isNotEmpty() || scrollChanges.isNotEmpty() || fxChange != null ||
        stateDataChange != null || roomHeaderChange != null || customScrollCommands.isNotEmpty() ||
        saveStationSpawns.isNotEmpty()
}

/**
 * Per-metatile default override: block type + BTS for a specific tileset.
 * Key format in the project map: "tilesetId:metatileIndex" (e.g., "12:82").
 */
@Serializable
data class TileDefaultOverride(
    val blockType: Int,
    val bts: Int = 0
)

/**
 * A single patch write operation: write bytes at an unheadered ROM PC offset.
 * Equivalent to one IPS record.
 */
@Serializable
data class PatchWrite(
    val offset: Long,         // unheadered PC offset (not SNES address)
    val bytes: List<Int>      // byte values 0x00-0xFF
)

/**
 * Patch-declared item metadata. This lets IPS/config patches expose new
 * inventory bits to editor UI without adding them to the vanilla PLM catalog.
 */
@Serializable
data class CustomItemDef(
    val id: String,
    var name: String,
    var shortLabel: String,
    var description: String = "",
    var source: String = "patch",
    var itemWordAddress: Int = 0x09A4,
    var bitMask: Int = 0,
    var iconX: Int = 0,
    var iconY: Int = 0,
    var category: String = "Major",
    var visiblePlmId: Int? = null,
    var chozoPlmId: Int? = null,
    var hiddenPlmId: Int? = null
)

/**
 * A named, toggleable patch: a collection of write operations.
 * Config patches (configType != null) use a GUI to set parameters; writes may be
 * computed at export time from configValue instead of stored in writes.
 */
@Serializable
data class SmPatch(
    val id: String,
    var name: String,
    var description: String = "",
    var enabled: Boolean = false,
    val writes: MutableList<PatchWrite> = mutableListOf(),
    var configType: String? = null,
    var configValue: Int? = null,
    var configData: MutableMap<String, Int>? = null,
    val customItems: MutableList<CustomItemDef> = mutableListOf()
)

/**
 * Custom tileset graphics data (base64-encoded raw 4bpp bytes).
 * URE (area-specific) keyed by tileset ID; CRE (common) is shared.
 * Enemy/boss sprite overrides are stored as base64-encoded PNG bytes keyed by species ID hex string (e.g. "E4BF").
 */
@Serializable
data class TilesetGfxData(
    val varGfx: MutableMap<String, String> = mutableMapOf(),         // key = tilesetId, value = base64 raw 4bpp
    var creGfx: String? = null,                                       // base64 raw 4bpp, shared
    val tileTables: MutableMap<String, String> = mutableMapOf(),      // key = tilesetId, value = base64 raw variable metatile table
    var creTileTable: String? = null,                                  // base64 raw CRE metatile table, shared
    val enemyGfx: MutableMap<String, String> = mutableMapOf(),       // key = speciesId hex, value = base64 PNG bytes
    val spriteTileBlocks: MutableMap<String, String> = mutableMapOf(), // key = "boss:N" (e.g. "phantoon:0"), value = base64 raw 4bpp
    val palettes: MutableMap<String, String> = mutableMapOf(),        // key = tilesetId, value = base64 BGR555 (256 bytes raw)
    val spritePalettes: MutableMap<String, String> = mutableMapOf(),  // key = regionId (e.g. "samus_power"), value = base64 BGR555
    val paletteEffects: MutableMap<String, String> = mutableMapOf()  // key = regionId or "tileset:N", value = effectId
)

/**
 * A single cell in a tile pattern: metatile index + block type + BTS + per-tile flips.
 * Encodes to the same 16-bit block word format the ROM uses.
 */
@Serializable
data class PatternCell(
    val metatile: Int,         // 0-1023 metatile index
    val blockType: Int = 0x8,  // upper 4 bits of block word
    val bts: Int = 0,
    val hFlip: Boolean = false,
    val vFlip: Boolean = false,
    val plmId: Int = 0,        // PLM ID (item/station/gate); 0 = none
    val plmParam: Int = 0      // PLM parameter byte
)

/**
 * A reusable tile pattern: a named rectangular grid of [PatternCell]s.
 * CRE patterns (tilesetId == null) use only common tiles (640-1023) and apply
 * to all tilesets. URE patterns are specific to a tileset.
 */
@Serializable
data class TilePattern(
    val id: String,
    var name: String,
    val cols: Int,
    val rows: Int,
    val tilesetId: Int? = null,  // null = CRE (shared), otherwise tileset-specific
    val cells: MutableList<PatternCell?> = mutableListOf(),  // row-major: cells[row * cols + col]; null = empty
    var builtIn: Boolean = false,
    val noFlip: Boolean = false   // directional patterns (gates, doors) can't be flipped/rotated
) {
    fun getCell(r: Int, c: Int): PatternCell? {
        val idx = r * cols + c
        return if (idx in cells.indices) cells[idx] else null
    }

    fun setCell(r: Int, c: Int, cell: PatternCell?) {
        val idx = r * cols + c
        while (cells.size <= idx) cells.add(null)
        cells[idx] = cell
    }
}

/**
 * A single minimap tile edit: position + tile word value.
 */
@Serializable
data class MinimapTileEdit(
    val x: Int,
    val y: Int,
    val tileWord: Int,
)

/**
 * Custom ASM code to be embedded in the ROM at export time.
 * The bytes are written to free space in the appropriate bank,
 * and the species header pointer is updated to the new address.
 */
@Serializable
data class CustomAsmEntry(
    val hexBytes: String,
    val sourceAsm: String = "",
    val label: String = "",
)

/**
 * Serializable N-SPC note edit used by the sound editor.
 */
@Serializable
data class MusicNoteEdit(
    val tick: Int,
    val duration: Int,
    val noteValue: Int,
    val velocity: Int = 15,
    val quantize: Int = 7,
    val instrument: Int = 0,
)

/**
 * Serializable N-SPC control command edit.
 */
@Serializable
data class MusicCommandEdit(
    val tick: Int,
    val command: Int,
    val params: List<Int> = emptyList(),
)

/**
 * One editable N-SPC channel in a saved music track edit.
 */
@Serializable
data class MusicChannelEdit(
    val notes: MutableList<MusicNoteEdit> = mutableListOf(),
    val commands: MutableList<MusicCommandEdit> = mutableListOf(),
)

/**
 * Saved instrument table entry. These are SPC RAM instrument table values,
 * not BRR sample bytes.
 */
@Serializable
data class MusicInstrumentEdit(
    val index: Int,
    val tableAddr: Int,
    val srcn: Int,
    val adsr1: Int,
    val adsr2: Int,
    val gain: Int,
    val pitchAdj: Int,
)

@Serializable
data class MusicTransferBlockEdit(
    val destAddr: Int,
    val dataBase64: String,
)

@Serializable
data class MusicNativePayloadEdit(
    val formatLabel: String = "",
    val sourceFileName: String = "",
    val sourcePlayIndex: Int = -1,
    val blocks: MutableList<MusicTransferBlockEdit> = mutableListOf(),
)

/**
 * A saved piano-roll edit for one song set/play index.
 */
@Serializable
data class MusicTrackEdit(
    val songSet: Int,
    val playIndex: Int,
    val trackName: String = "",
    val tempo: Int,
    val channels: MutableList<MusicChannelEdit> = mutableListOf(),
    val instruments: MutableList<MusicInstrumentEdit> = mutableListOf(),
    val nativePayload: MusicNativePayloadEdit? = null,
) {
    companion object {
        fun key(songSet: Int, playIndex: Int): String =
            "${songSet.toString(16).uppercase().padStart(2, '0')}:${playIndex.toString(16).uppercase().padStart(2, '0')}"
    }
}

/**
 * The .smedit project file. JSON-serializable.
 * Keys are hex room IDs (as strings), values are the list of edit operations.
 */
@Serializable
data class SmEditProject(
    val romPath: String,
    val rooms: MutableMap<String, RoomEdits> = mutableMapOf(),  // key = "91F8"
    val tileDefaults: MutableMap<String, TileDefaultOverride> = mutableMapOf(), // key = "tilesetId:metatileIndex"
    val patches: MutableList<SmPatch> = mutableListOf(),
    val customGfx: TilesetGfxData = TilesetGfxData(),
    val patterns: MutableList<TilePattern> = mutableListOf(),
    val minimapEdits: MutableMap<String, MutableList<MinimapTileEdit>> = mutableMapOf(), // key = area index "0"-"6"
    val textEdits: MutableMap<String, String> = mutableMapOf(), // key = text entry id (e.g. "area_0", "ceres_escape")
    val roomNameOverrides: MutableMap<String, String> = mutableMapOf(), // key = room id hex (e.g. "91F8")
    val customAsm: MutableMap<String, CustomAsmEntry> = mutableMapOf(), // key = "speciesHex:fieldName" (e.g. "DCFF:shotAi")
    val musicEdits: MutableMap<String, MusicTrackEdit> = mutableMapOf(), // key = MusicTrackEdit.key(songSet, playIndex)
    var versionMajor: Int = 1,
    var versionMinor: Int = 0,
    var buildName: String = "",
) {
    fun roomKey(roomId: Int): String = roomId.toString(16).uppercase().padStart(4, '0')

    fun getOrCreateRoom(roomId: Int): RoomEdits {
        val key = roomKey(roomId)
        return rooms.getOrPut(key) { RoomEdits(roomId) }
    }

    fun tileDefaultKey(tilesetId: Int, metatileIndex: Int): String = "$tilesetId:$metatileIndex"

    fun getTileDefault(tilesetId: Int, metatileIndex: Int): TileDefaultOverride? =
        tileDefaults[tileDefaultKey(tilesetId, metatileIndex)]

    fun setTileDefault(tilesetId: Int, metatileIndex: Int, blockType: Int, bts: Int) {
        tileDefaults[tileDefaultKey(tilesetId, metatileIndex)] = TileDefaultOverride(blockType, bts)
    }

    fun removeTileDefault(tilesetId: Int, metatileIndex: Int) {
        tileDefaults.remove(tileDefaultKey(tilesetId, metatileIndex))
    }
}
