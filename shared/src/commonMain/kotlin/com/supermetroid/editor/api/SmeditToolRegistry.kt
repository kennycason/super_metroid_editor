package com.supermetroid.editor.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class SmeditToolEffect { READ_ONLY, MUTATION }

data class SmeditToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: SmeditToolEffect,
)

data class SmeditToolResult(
    val output: JsonObject,
    val isError: Boolean,
) {
    fun outputJson(): String = output.toString()
}

@Serializable
data class SmeditWorkspaceSummary(
    val projectLoaded: Boolean,
    val projectName: String? = null,
    val romFileName: String? = null,
    val projectFilePath: String? = null,
    val readOnly: Boolean = false,
    val dirty: Boolean = false,
    val roomCount: Int = 0,
    val editedRoomCount: Int = 0,
    val patchCount: Int = 0,
    val enabledPatchCount: Int = 0,
    val asmWorkspaceEnabled: Boolean = false,
    val asmSourceAvailable: Boolean = false,
    val asmUnsavedFileCount: Int = 0,
    val selectedRoomId: String? = null,
    val selectedRoomName: String? = null,
)

@Serializable
data class SmeditRoomSummary(
    val id: String,
    val name: String,
    val handle: String? = null,
    val area: String? = null,
    val widthScreens: Int? = null,
    val heightScreens: Int? = null,
    val musicData: Int? = null,
    val musicTrack: Int? = null,
    val edited: Boolean = false,
    val projectOwned: Boolean = false,
)

@Serializable
data class SmeditRoomMusicState(
    val index: Int,
    val condition: String,
    val musicData: Int,
    val musicTrack: Int,
)

@Serializable
data class SmeditFxEntryDetails(
    val selector: String,
    /** True when no stored default record exists and this effective None record is synthesized for inspection. */
    val synthesized: Boolean = false,
    val doorSelect: Int,
    val doorSelectHex: String,
    val sourceDoor: String? = null,
    val liquidSurfaceStart: Int,
    val liquidSurfaceNew: Int,
    val liquidSpeed: Int,
    val liquidDelay: Int,
    val fxType: Int,
    val fxTypeName: String,
    val fxBitA: Int,
    val fxBitB: Int,
    val fxBitC: Int,
    val liquidOptions: List<String> = emptyList(),
    val unknownLiquidOptionMask: Int = 0,
    val paletteFxBitflags: Int,
    val tileAnimBitflags: Int,
    val paletteBlend: Int,
)

@Serializable
data class SmeditRoomStateDetails(
    val id: String,
    val index: Int,
    val sourceIndex: Int? = null,
    val condition: String,
    val conditionKind: String,
    val defaultState: Boolean,
    val readable: Boolean,
    val projectEdited: Boolean,
    val projectFxEdited: Boolean,
    val fxSourceManaged: Boolean,
    val fxReadable: Boolean = true,
    val runtimeIndexedFx: Boolean = false,
    val tileset: Int? = null,
    val musicData: Int? = null,
    val musicTrack: Int? = null,
    val bgScrolling: Int? = null,
    val levelDataPointer: Int? = null,
    val fxPointer: Int? = null,
    val enemySetPointer: Int? = null,
    val enemyGraphicsPointer: Int? = null,
    val roomScrollsPointer: Int? = null,
    val xraySpecialCasingPointer: Int? = null,
    val mainAsmPointer: Int? = null,
    val plmSetPointer: Int? = null,
    val backgroundPointer: Int? = null,
    val setupAsmPointer: Int? = null,
    val fxEntries: List<SmeditFxEntryDetails> = emptyList(),
)

@Serializable
data class SmeditRoomFxDetails(
    val id: String,
    val name: String,
    val area: String,
    val states: List<SmeditRoomStateDetails>,
)

@Serializable
data class SmeditRoomDetails(
    val id: String,
    val name: String,
    val handle: String,
    val comment: String? = null,
    val area: String,
    val areaIndex: Int,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
    val tileset: Int,
    val musicData: Int,
    val musicTrack: Int,
    val stateCount: Int,
    val projectStateEditCount: Int,
    val edited: Boolean,
    val projectOwned: Boolean,
    /** Music bytes in each source ROM state, before project-level overrides are applied. */
    val sourceStateMusic: List<SmeditRoomMusicState> = emptyList(),
    /** True when the source states intentionally use more than one music pair. */
    val hasDistinctSourceMusic: Boolean = false,
    /** True when this project already owns music fields on one or more individual states. */
    val hasStateMusicEdits: Boolean = false,
    /** Ordered effective runtime states, including all default and door-specific FX records. */
    val states: List<SmeditRoomStateDetails> = emptyList(),
)

@Serializable
data class SmeditPatchSummary(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val configType: String? = null,
    val configurable: Boolean = false,
)

@Serializable
data class SmeditPatchConfigChoice(
    val label: String,
    val value: Int,
)

@Serializable
data class SmeditPatchConfigField(
    val key: String,
    val label: String,
    val type: String,
    val min: Int,
    val max: Int,
    val currentValue: Int? = null,
    val defaultValue: Int? = null,
    val description: String = "",
    val category: String? = null,
    val unit: String = "",
    val choices: List<SmeditPatchConfigChoice> = emptyList(),
)

@Serializable
data class SmeditPatchDetails(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val configType: String? = null,
    val configValue: Int? = null,
    val configData: Map<String, Int> = emptyMap(),
    val configurable: Boolean = false,
    val configFieldCount: Int = 0,
    val configFields: List<SmeditPatchConfigField> = emptyList(),
)

@Serializable
data class SmeditPaletteSummary(
    val id: String,
    val name: String,
    val kind: String,
    val category: String,
    val description: String,
    val colorCount: Int,
    val rowCount: Int,
    val editable: Boolean,
    val sourceManaged: Boolean,
    val modified: Boolean,
    val affectedPaletteIds: List<String> = emptyList(),
    /** Semantically related palettes that are not implicitly changed. */
    val relatedPaletteIds: List<String> = emptyList(),
    val usage: String? = null,
    val message: String? = null,
)

@Serializable
data class SmeditPaletteDetails(
    val id: String,
    val name: String,
    val kind: String,
    val category: String,
    val description: String,
    val colorCount: Int,
    val rowCount: Int,
    val editable: Boolean,
    val sourceManaged: Boolean,
    val modified: Boolean,
    val affectedPaletteIds: List<String> = emptyList(),
    val relatedPaletteIds: List<String> = emptyList(),
    val usage: String? = null,
    val message: String? = null,
    /** Native SNES BGR555 words, ordered by row then column. */
    val colorsBgr555: List<Int> = emptyList(),
    /** The same colors as provider-friendly #RRGGBB strings. */
    val colorsRgbHex: List<String> = emptyList(),
)

@Serializable
data class SmeditItemType(
    val name: String,
    val variant: String,
    val plmId: Int,
    val plmIdHex: String,
    val custom: Boolean = false,
)

@Serializable
data class SmeditPlacedItem(
    val name: String,
    val plmId: Int,
    val plmIdHex: String,
    val x: Int,
    val y: Int,
    val parameter: Int,
    val parameterHex: String,
)

@Serializable
data class SmeditRoomItemState(
    val id: String,
    val index: Int,
    val condition: String,
    val items: List<SmeditPlacedItem>,
)

@Serializable
data class SmeditRoomItemsDetails(
    val roomId: String,
    val roomName: String,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val sourceManaged: Boolean,
    val states: List<SmeditRoomItemState>,
)

@Serializable
data class SmeditRoomTileCoordinate(
    val x: Int,
    val y: Int,
    val layer: Int = 1,
    val pixelX: Int? = null,
    val pixelY: Int? = null,
)

@Serializable
data class SmeditTilePaletteColor(
    val colorIndex: Int,
    val paletteIndex: Int,
    val bgr555: Int,
    val bgr555Hex: String,
    val rgbHex: String,
)

@Serializable
data class SmeditMetatileSubtileDetails(
    val quadrant: String,
    val word: Int,
    val wordHex: String,
    val tileNumber: Int,
    val tileNumberHex: String,
    val tileSource: String,
    val paletteRow: Int,
    val priority: Boolean,
    val hFlip: Boolean,
    val vFlip: Boolean,
    val usedColorIndices: List<Int>,
    val usedColors: List<SmeditTilePaletteColor>,
)

@Serializable
data class SmeditMetatileDetails(
    val tileset: Int,
    val metatileId: Int,
    val metatileIdHex: String,
    val tableSource: String,
    val placeholder: Boolean,
    val subtiles: List<SmeditMetatileSubtileDetails>,
)

@Serializable
data class SmeditSampledTilePixel(
    val requestedPixelX: Int,
    val requestedPixelY: Int,
    val sourcePixelX: Int,
    val sourcePixelY: Int,
    val quadrant: String,
    val tileNumber: Int,
    val paletteRow: Int,
    val color: SmeditTilePaletteColor,
)

@Serializable
data class SmeditRoomTileDetails(
    val x: Int,
    val y: Int,
    val layer: Int,
    val blockWord: Int,
    val blockWordHex: String,
    val metatileId: Int,
    val metatileIdHex: String,
    val blockType: Int? = null,
    val bts: Int? = null,
    val btsHex: String? = null,
    val hFlip: Boolean,
    val vFlip: Boolean,
    val metatile: SmeditMetatileDetails,
    val sampledPixel: SmeditSampledTilePixel? = null,
)

@Serializable
data class SmeditRoomTilesDetails(
    val roomId: String,
    val roomName: String,
    val stateId: String,
    val stateCondition: String,
    val layoutResourceId: String,
    val sourceManaged: Boolean,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val tileset: Int,
    val tilesetPaletteId: String,
    val hasLayer2: Boolean,
    val tiles: List<SmeditRoomTileDetails>,
)

@Serializable
data class SmeditRoomTileSearchMatch(
    val x: Int,
    val y: Int,
    val layer: Int,
    val blockWord: Int,
    val blockWordHex: String,
    val metatileId: Int,
    val metatileIdHex: String,
    val blockType: Int? = null,
    val bts: Int? = null,
    val hFlip: Boolean,
    val vFlip: Boolean,
    val paletteRows: List<Int>,
    val usedPaletteIndices: List<Int>,
    val usedColorsBgr555: List<Int>,
)

@Serializable
data class SmeditRoomTileSearchDetails(
    val roomId: String,
    val roomName: String,
    val stateId: String,
    val stateCondition: String,
    val layoutResourceId: String,
    val sourceManaged: Boolean,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val tileset: Int,
    val tilesetPaletteId: String,
    val hasLayer2: Boolean,
    val totalMatches: Int,
    val truncated: Boolean,
    val matches: List<SmeditRoomTileSearchMatch>,
)

@Serializable
data class SmeditEditorSettingsSummary(
    val theme: String,
    val fontSize: String,
    val emulatorBackend: String,
    val emulatorFollowLiveRoom: Boolean,
    val roomSidebarTab: String,
    val roomItemNamesVisible: Boolean,
    val roomMetaNamesVisible: Boolean,
    val roomEnemyNamesVisible: Boolean,
    val aiSelectedProvider: String? = null,
    val aiSelectedModel: String? = null,
)

@Serializable
data class SmeditAsmSearchMatch(
    val fileId: String,
    val displayName: String,
    val line: Int,
    val column: Int,
    val text: String,
)

@Serializable
data class SmeditDocumentationMatch(
    val path: String,
    val line: Int,
    val text: String,
)

@Serializable
data class SmeditDocumentationExcerpt(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/** Semantic workspace reads implemented by desktop today and reusable by MCP/CLI adapters later. */
interface SmeditWorkspaceAccess {
    fun workspaceSummary(): SmeditWorkspaceSummary
    fun rooms(): List<SmeditRoomSummary>
    fun room(roomId: Int): SmeditRoomDetails?
    fun patches(): List<SmeditPatchSummary>
    fun patch(identifier: String): SmeditPatchDetails?
    fun palettes(): List<SmeditPaletteSummary> = emptyList()
    fun palette(identifier: String): SmeditPaletteDetails? = null
    fun itemTypes(): List<SmeditItemType> = emptyList()
    fun roomItems(roomId: Int): SmeditRoomItemsDetails? = null
    fun roomTiles(
        roomId: Int,
        stateId: String?,
        coordinates: List<SmeditRoomTileCoordinate>,
    ): SmeditRoomTilesDetails? = null
    fun findRoomTiles(
        roomId: Int,
        stateId: String?,
        layer: Int,
        metatileIds: Set<Int>,
        paletteRows: Set<Int>,
        colorIndices: Set<Int>,
        colorsBgr555: Set<Int>,
        blockTypes: Set<Int>,
        btsValues: Set<Int>,
        limit: Int,
    ): SmeditRoomTileSearchDetails? = null
    fun tilesetMetatile(tileset: Int, metatileId: Int): SmeditMetatileDetails? = null
    fun editorSettings(): SmeditEditorSettingsSummary
    fun searchDocs(query: String, limit: Int): List<SmeditDocumentationMatch> = emptyList()
    fun readDoc(path: String, startLine: Int, maxLines: Int): SmeditDocumentationExcerpt? = null
    fun searchAsm(query: String, limit: Int): List<SmeditAsmSearchMatch>
}

/**
 * Provider- and transport-neutral semantic tool registry. OpenAI, Claude, MCP,
 * CLI, and future web adapters should expose these definitions rather than
 * reaching into EditorState independently.
 */
class SmeditToolRegistry(private val access: SmeditWorkspaceAccess) {
    val definitions: List<SmeditToolDefinition> = listOf(
        SmeditToolDefinition(
            name = "get_workspace_summary",
            description = "Describe the currently open SMEDIT project or ROM, edit state, selected room, patches, and ASM workspace.",
            inputSchema = objectSchema(),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_rooms",
            description = "List rooms in the current ROM hack. Optionally filter by room name, hexadecimal room id, handle, or area.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Optional case-insensitive room filter."),
                    "limit" to integerSchema("Maximum rooms to return, from 1 through 100."),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_room",
            description = "Get effective metadata for one room, including dimensions, map position, every runtime state's condition and state fields, and every effective default or door-specific FX record.",
            inputSchema = objectSchema(
                properties = mapOf("room_id" to stringSchema("Room id in hexadecimal, such as 91F8, 0x91F8, or \$91F8.")),
                required = listOf("room_id"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_room_fx",
            description = "Inspect effective FX for every state of matching rooms in one call. Results preserve ordered door-specific records. Native liquid options are flow_left=0x01, bg_animate=0x02 (vertical BG2 shimmer), bg_liquid=0x04 (horizontal BG2 shimmer), large_tide=0x40, and small_tide=0x80. Tides are not required for BG shimmer.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Optional case-insensitive room id, name, handle, or area filter."),
                    "areas" to stringArraySchema("Optional case-insensitive area names, such as Crateria."),
                    "room_ids" to stringArraySchema("Optional hexadecimal room ids."),
                    "limit" to integerSchema("Maximum rooms to return, from 1 through 100."),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_patches",
            description = "List project patches and whether they are enabled. Optionally return only enabled or disabled patches.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "status" to enumSchema("Patch status filter.", listOf("all", "enabled", "disabled")),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_patch",
            description = "Get one patch and its provider-neutral configuration schema, including canonical field keys, ranges, defaults, choices, and current values.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "patch" to stringSchema("Patch id, config type, or exact patch name."),
                    "field_query" to stringSchema("Optional case-insensitive field key or label filter."),
                    "limit" to integerSchema("Maximum configuration fields to return, from 1 through 100."),
                ),
                required = listOf("patch"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_palettes",
            description = "List editable tileset and sprite palettes, including runtime roles and related boss tables. Use get_palette before proposing exact color changes. Kraid's assembled full-health preview uses its health-stage BG and OAM palettes, not only the base Kraid palette.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Optional case-insensitive id, name, description, kind, or category filter."),
                    "kind" to enumSchema("Optional palette kind.", listOf("all", "tileset", "sprite")),
                    "limit" to integerSchema("Maximum palettes to return, from 1 through 100."),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_palette",
            description = "Read one palette's current ordered colors in SNES BGR555 and #RRGGBB form, plus runtime usage, related palettes, source ownership, and shared-resource safety information.",
            inputSchema = objectSchema(
                properties = mapOf("palette" to stringSchema("Stable palette id from list_palettes, or an exact unique palette name.")),
                required = listOf("palette"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_item_types",
            description = "List vanilla and currently enabled custom item PLM variants that can be placed in rooms.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Optional case-insensitive item, variant, or PLM id filter."),
                    "limit" to integerSchema("Maximum item variants to return, from 1 through 100."),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_room_items",
            description = "Inspect effective item placements for every state of one room. Coordinates are zero-based 16x16 room-block coordinates.",
            inputSchema = objectSchema(
                properties = mapOf("room_id" to stringSchema("Room id in hexadecimal, such as 91F8, 0x91F8, or $91F8.")),
                required = listOf("room_id"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "inspect_room_tiles",
            description = "Decode exact effective room blocks for one state. Returns block word/type/BTS, room flips, all four 8x8 metatile words, tile sources, palette rows, used color indices, and current project BGR555/RGB colors. Optional pixel_x/pixel_y samples the exact rendered color within a 16x16 block. Use this instead of guessing which palette ramp a visible tile uses.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "room_id" to stringSchema("Room id in hexadecimal."),
                    "state_id" to stringSchema("Optional stable state id. Omit to inspect the default state."),
                    "coordinates" to roomTileCoordinatesSchema(),
                ),
                required = listOf("room_id", "coordinates"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "find_room_tiles",
            description = "Search every effective block in one room state by metatile id, palette row, used 4bpp color index, exact current BGR555 color, block type, or BTS. Filters from different categories are combined with AND. Use the returned coordinates with inspect_room_tiles for full decoding.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "room_id" to stringSchema("Room id in hexadecimal."),
                    "state_id" to stringSchema("Optional stable state id. Omit to search the default state."),
                    "layer" to integerSchema("Room layer: 1 or 2.", 1, 2),
                    "metatile_ids" to integerArraySchema("Optional metatile ids from 0 through 1023.", 0, 0x3FF),
                    "palette_rows" to integerArraySchema("Optional palette rows from 0 through 7.", 0, 7),
                    "color_indices" to integerArraySchema("Optional used 4bpp color indices from 0 through 15.", 0, 15),
                    "colors_bgr555" to integerArraySchema("Optional exact current SNES BGR555 colors from 0 through 32767.", 0, 0x7FFF),
                    "block_types" to integerArraySchema("Optional Layer 1 block types from 0 through 15.", 0, 15),
                    "bts_values" to integerArraySchema("Optional Layer 1 BTS bytes from 0 through 255.", 0, 255),
                    "limit" to integerSchema("Maximum returned coordinates, from 1 through 100.", 1, 100),
                ),
                required = listOf("room_id"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_tileset_metatile",
            description = "Decode one tileset metatile definition, including all four 8x8 tile words, source tables, palette rows, used color indices, and exact current project colors. Tileset metatiles are shared assets; this read does not imply that changing one room block changes the definition.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "tileset" to integerSchema("Tileset id from 0 through 28.", 0, 28),
                    "metatile_id" to integerSchema("Metatile id from 0 through 1023.", 0, 0x3FF),
                ),
                required = listOf("tileset", "metatile_id"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_editor_settings",
            description = "Read non-secret global SMEDIT preferences. API credentials are never exposed.",
            inputSchema = objectSchema(),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "search_docs",
            description = "Search SMEDIT's curated ROM, editor, boss, sound, validation, and ASM-workflow documentation. Use this for implementation constraints and engine behavior instead of guessing.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Non-empty documentation search terms."),
                    "limit" to integerSchema("Maximum matches to return, from 1 through 100."),
                ),
                required = listOf("query"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "read_doc",
            description = "Read a bounded line range from a SMEDIT documentation file returned by search_docs. Paths are restricted to the curated docs tree.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "path" to stringSchema("Documentation path returned by search_docs, such as rom/write_safety.md."),
                    "start_line" to integerSchema("First one-based line to read, from 1 through 100000.", 1, 100000),
                    "max_lines" to integerSchema("Maximum lines to return, from 1 through 200.", 1, 200),
                ),
                required = listOf("path"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "search_asm",
            description = "Search the loaded ASM workspace, including live unsaved editor buffers, for literal source text.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Non-empty literal source text to find."),
                    "limit" to integerSchema("Maximum matches to return, from 1 through 100."),
                ),
                required = listOf("query"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "set_room_music",
            description = "Stage a room-music change for explicit user approval. This never applies the change itself. Empty room_ids means every room. By default SMEDIT protects rooms whose runtime states use distinct music, rooms with state-specific project music edits, and all of Tourian because its Metroid/boss/cutscene sounds depend on native song instruments. The two safety classes require separate explicit overrides.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "music_data" to integerSchema("Music data/song-set byte, from 0 through 255.", 0, 255),
                    "music_track" to integerSchema("Music track/play-index byte, from 0 through 255.", 0, 255),
                    "room_ids" to stringArraySchema("Optional hexadecimal room ids. An empty array targets all rooms."),
                    "exclude_room_ids" to stringArraySchema("Optional hexadecimal room ids to exclude."),
                    "exclude_areas" to stringArraySchema("Optional case-insensitive area names to exclude, such as Ceres."),
                    "overwrite_special_state_music" to booleanSchema(
                        "Explicit opt-in to overwrite distinct boss/event/state-specific music. Defaults to false.",
                    ),
                    "overwrite_audio_sensitive_music" to booleanSchema(
                        "Explicit opt-in to overwrite Tourian's song-dependent music. This can glitch Metroid/boss/cutscene sounds or deadlock door transitions. Defaults to false.",
                    ),
                ),
                required = listOf("music_data", "music_track"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "set_room_state_fields",
            description = "Stage exact scalar changes to one stable room state for explicit approval. Use get_room first. Only fields named in update_fields are changed; sibling states and unmentioned fields are preserved. Music changes in Tourian require overwrite_audio_sensitive_music=true because native enemy, boss, and cutscene sounds depend on the Tourian song instruments.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "room_id" to stringSchema("Target room id in hexadecimal."),
                    "state_id" to stringSchema("Exact stable state id returned by get_room."),
                    "update_fields" to enumArraySchema(
                        "Exact state fields to modify. List only fields intentionally requested.",
                        listOf("tileset", "music_data", "music_track", "bg_scrolling"),
                    ),
                    "tileset" to integerSchema("Optional tileset id from 0 through 28.", 0, 28),
                    "music_data" to integerSchema("Optional music data/song-set byte from 0 through 255.", 0, 255),
                    "music_track" to integerSchema("Optional music track/play-index byte from 0 through 255.", 0, 255),
                    "bg_scrolling" to integerSchema("Optional Layer 2 scrolling word from 0 through 65535.", 0, 0xFFFF),
                    "overwrite_audio_sensitive_music" to booleanSchema(
                        "Explicit opt-in to change music in Tourian. This can glitch Metroid/boss/cutscene sounds or deadlock door transitions. Defaults to false.",
                    ),
                ),
                required = listOf("room_id", "state_id", "update_fields"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "configure_patch",
            description = "Stage enabling, disabling, or configuring one existing patch for explicit user approval. Use get_patch first and send canonical configuration keys. This never applies the change itself.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "patch" to stringSchema("Patch id, config type, or exact patch name."),
                    "enabled" to booleanSchema("Optional requested enabled state."),
                    "values" to integerMapSchema("Optional configuration values keyed by canonical get_patch field keys."),
                ),
                required = listOf("patch"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "set_room_fx",
            description = "Stage partial default-FX changes for explicit approval, preserving door-specific variants and every field not named in update_fields. Empty room_ids and areas target all rooms. Conditional boss/event states, ASM-managed FX, unreadable graphs, and runtime-indexed Mother Brain FX are protected by default. Inspect with list_room_fx when selecting exact states. For an invisible lava heat shimmer set update_fields=[\"fx_type\",\"liquid_surface_start\",\"liquid_surface_new\",\"liquid_options\"], fx_type=2, both heights=65535, and liquid_options=[\"bg_animate\"]; do not add a tide.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "update_fields" to enumArraySchema(
                        "Exact FX fields to modify. List only fields intentionally requested; supplied values for fields absent from this list are ignored.",
                        listOf(
                            "fx_type", "liquid_surface_start", "liquid_surface_new", "liquid_speed", "liquid_delay",
                            "fx_bit_a", "fx_bit_b", "fx_bit_c", "liquid_options", "palette_fx_bitflags",
                            "tile_anim_bitflags", "palette_blend",
                        ),
                    ),
                    "room_ids" to stringArraySchema("Optional hexadecimal room ids."),
                    "areas" to stringArraySchema("Optional case-insensitive area names."),
                    "exclude_room_ids" to stringArraySchema("Optional hexadecimal room ids to exclude."),
                    "exclude_areas" to stringArraySchema("Optional case-insensitive area names to exclude."),
                    "state_ids" to stringArraySchema("Optional stable state ids from get_room/list_room_fx. State ids repeat by room and filter within every selected room."),
                    "state_targets" to stringArraySchema("Optional exact room/state pairs such as 0x91F8:state-2. Use this instead of state_ids for a reviewed cross-room subset."),
                    "only_if_fx_types" to integerArraySchema("Optional compare-and-set filter of current native FX types; 0 means None.", 0, 0x2C),
                    "include_conditional_states" to booleanSchema("Explicit opt-in to edit non-default boss/event/door/equipment states. Defaults to false."),
                    "fx_type" to integerSchema("Optional native FX dispatch offset: even values from 0 through 44; 2=Lava, 4=Acid, 6=Water.", 0, 0x2C),
                    "liquid_surface_start" to integerSchema("Optional starting liquid height word; 65535 keeps the visible liquid off-screen.", 0, 0xFFFF),
                    "liquid_surface_new" to integerSchema("Optional target liquid height word; 65535 keeps the visible liquid off-screen.", 0, 0xFFFF),
                    "liquid_speed" to integerSchema("Optional signed 16-bit/8.8 speed stored as an unsigned word.", 0, 0xFFFF),
                    "liquid_delay" to integerSchema("Optional movement delay byte.", 0, 0xFF),
                    "fx_bit_a" to integerSchema("Optional native rendering-control byte.", 0, 0xFF),
                    "fx_bit_b" to integerSchema("Optional native layer-priority byte.", 0, 0xFF),
                    "fx_bit_c" to integerSchema("Optional raw liquid-options byte. If liquid_options is also provided, the semantic liquid_options list takes precedence.", 0, 0xFF),
                    "liquid_options" to enumArraySchema(
                        "Optional exact semantic liquid-options list. Takes precedence over fx_bit_c; unspecified options are cleared.",
                        listOf("flow_left", "bg_animate", "bg_liquid", "large_tide", "small_tide"),
                    ),
                    "palette_fx_bitflags" to integerSchema("Optional palette-FX flags byte.", 0, 0xFF),
                    "tile_anim_bitflags" to integerSchema("Optional animated-tile flags byte.", 0, 0xFF),
                    "palette_blend" to integerSchema("Optional palette-blend byte.", 0, 0xFF),
                ),
                required = listOf("update_fields"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "set_palette_colors",
            description = "Stage exact partial color changes to one or more tileset or sprite palettes in one atomic approval. Use get_palette first. Always send palette_edits, including for one palette. Include exactly one entry per unique palette id and combine every row/index for that palette in its changes array. SMEDIT defensively merges repeated entries when their indices do not conflict. Omit an already-correct target, or include its current requested colors and SMEDIT will treat it as satisfied without writing it. Each index is row * 16 + column; unchanged indices are preserved. Sprite and boss preview caches refresh after approval.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "palette_edits" to paletteEditsSchema(),
                ),
                required = listOf("palette_edits"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "place_item",
            description = "Stage placement of one item at zero-based 16x16 room-block coordinates for explicit approval. Use list_item_types and list_room_items first. all_states targets every current runtime state; single_state requires an exact stable state_id.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "room_id" to stringSchema("Target room id in hexadecimal."),
                    "plm_id" to integerSchema("Item PLM id returned by list_item_types.", 1, 0xFFFF),
                    "x" to integerSchema("Zero-based room-block X coordinate.", 0, 255),
                    "y" to integerSchema("Zero-based room-block Y coordinate.", 0, 255),
                    "state_scope" to enumSchema("Whether the item belongs to every state or one state.", listOf("all_states", "single_state")),
                    "state_id" to stringSchema("Exact stable state id from get_room/list_room_items; required for single_state."),
                    "item_parameter" to integerSchema("Optional explicit item collection-bit parameter. Omit to allocate a safe unused project value.", 1, 0x1FF),
                ),
                required = listOf("room_id", "plm_id", "x", "y", "state_scope"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "set_room_tiles",
            description = "Stage exact room block changes for explicit approval. Unspecified metatile, block type, BTS, and room-flip fields are preserved independently in each targeted state. all_states edits every distinct layout resource; single_state safely forks a shared layout before editing it. This changes room layout blocks, not the globally shared tileset metatile definition. ASM-source-owned layouts are rejected until source-backed tile writes are available.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "room_id" to stringSchema("Target room id in hexadecimal."),
                    "state_scope" to enumSchema("Whether to edit every state or exactly one state.", listOf("all_states", "single_state")),
                    "state_id" to stringSchema("Stable state id; required for single_state."),
                    "tile_edits" to roomTileEditsSchema(),
                ),
                required = listOf("room_id", "state_scope", "tile_edits"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
    )

    private val definitionsByName = definitions.associateBy(SmeditToolDefinition::name)
    private val json = Json { encodeDefaults = true }

    fun definition(name: String): SmeditToolDefinition? = definitionsByName[name]

    fun execute(name: String, arguments: JsonObject): SmeditToolResult {
        val definition = definitionsByName[name]
            ?: return failure("unknown_tool", "SMEDIT does not expose a tool named '$name'.")
        if (definition.effect != SmeditToolEffect.READ_ONLY) {
            return failure("approval_required", "Mutation tools require a separately approved action plan.")
        }
        return try {
            when (name) {
                "get_workspace_summary" -> success(json.encodeToJsonElement(SmeditWorkspaceSummary.serializer(), access.workspaceSummary()))
                "list_rooms" -> {
                    val query = arguments.string("query")?.trim().orEmpty()
                    val limit = arguments.limit()
                    val rooms = access.rooms().asSequence().filter { room ->
                        query.isEmpty() || listOf(room.id, room.name, room.handle.orEmpty(), room.area.orEmpty()).any {
                            it.contains(query, ignoreCase = true)
                        }
                    }.take(limit).toList()
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditRoomSummary.serializer()), rooms))
                }
                "get_room" -> {
                    val rawId = arguments.string("room_id")
                        ?: return failure("invalid_arguments", "room_id is required.")
                    val roomId = parseRoomId(rawId)
                        ?: return failure("invalid_room_id", "'$rawId' is not a 16-bit hexadecimal room id.")
                    val room = access.room(roomId)
                        ?: return failure("room_not_found", "No readable room exists at ${formatRoomId(roomId)}.")
                    success(json.encodeToJsonElement(SmeditRoomDetails.serializer(), room))
                }
                "list_room_fx" -> {
                    val query = arguments.string("query")?.trim().orEmpty()
                    val areas = arguments.stringList("areas")
                        ?: return failure("invalid_arguments", "areas must be an array of strings.")
                    val requestedIds = arguments.stringList("room_ids")
                        ?: return failure("invalid_arguments", "room_ids must be an array of hexadecimal room ids.")
                    val ids = requestedIds.map { raw ->
                        parseRoomId(raw) ?: return failure("invalid_room_id", "'$raw' is not a 16-bit hexadecimal room id.")
                    }.toSet()
                    val rooms = access.rooms().asSequence().filter { room ->
                        val roomId = parseRoomId(room.id)
                        (ids.isEmpty() || roomId in ids) &&
                            (areas.isEmpty() || areas.any { it.equals(room.area, ignoreCase = true) }) &&
                            (query.isEmpty() || listOf(room.id, room.name, room.handle.orEmpty(), room.area.orEmpty()).any {
                                it.contains(query, ignoreCase = true)
                            })
                    }.mapNotNull { summary ->
                        parseRoomId(summary.id)?.let(access::room)?.let { room ->
                            SmeditRoomFxDetails(room.id, room.name, room.area, room.states)
                        }
                    }.take(arguments.limit(default = 100)).toList()
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditRoomFxDetails.serializer()), rooms))
                }
                "list_patches" -> {
                    val status = arguments.string("status")?.lowercase() ?: "all"
                    if (status !in setOf("all", "enabled", "disabled")) {
                        return failure("invalid_arguments", "status must be all, enabled, or disabled.")
                    }
                    val patches = access.patches().filter {
                        status == "all" || (status == "enabled") == it.enabled
                    }
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditPatchSummary.serializer()), patches))
                }
                "get_patch" -> {
                    val identifier = arguments.string("patch")?.trim()?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "patch is required.")
                    val patch = access.patch(identifier)
                        ?: return failure("patch_not_found", "No patch uniquely matches '$identifier'.")
                    val query = arguments.string("field_query")?.trim().orEmpty()
                    val fields = patch.configFields.asSequence().filter { field ->
                        query.isEmpty() || field.key.contains(query, ignoreCase = true) ||
                            field.label.contains(query, ignoreCase = true)
                    }.take(arguments.limit()).toList()
                    success(json.encodeToJsonElement(SmeditPatchDetails.serializer(), patch.copy(configFields = fields)))
                }
                "list_palettes" -> {
                    val query = arguments.string("query")?.trim().orEmpty()
                    val kind = arguments.string("kind")?.lowercase() ?: "all"
                    if (kind !in setOf("all", "tileset", "sprite")) {
                        return failure("invalid_arguments", "kind must be all, tileset, or sprite.")
                    }
                    val palettes = access.palettes().asSequence().filter { palette ->
                        (kind == "all" || palette.kind == kind) &&
                            (query.isEmpty() || listOf(
                                palette.id, palette.name, palette.description, palette.kind, palette.category,
                            ).any { it.contains(query, ignoreCase = true) })
                    }.take(arguments.limit(default = 100)).toList()
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditPaletteSummary.serializer()), palettes))
                }
                "get_palette" -> {
                    val identifier = arguments.string("palette")?.trim()?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "palette is required.")
                    val palette = access.palette(identifier)
                        ?: return failure("palette_not_found", "No palette uniquely matches '$identifier'.")
                    success(json.encodeToJsonElement(SmeditPaletteDetails.serializer(), palette))
                }
                "list_item_types" -> {
                    val query = arguments.string("query")?.trim().orEmpty()
                    val types = access.itemTypes().asSequence().filter { item ->
                        query.isEmpty() || listOf(item.name, item.variant, item.plmIdHex).any {
                            it.contains(query, ignoreCase = true)
                        }
                    }.take(arguments.limit(default = 100)).toList()
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditItemType.serializer()), types))
                }
                "list_room_items" -> {
                    val rawId = arguments.string("room_id")
                        ?: return failure("invalid_arguments", "room_id is required.")
                    val roomId = parseRoomId(rawId)
                        ?: return failure("invalid_room_id", "'$rawId' is not a 16-bit hexadecimal room id.")
                    val details = access.roomItems(roomId)
                        ?: return failure("room_not_found", "No readable room exists at ${formatRoomId(roomId)}.")
                    success(json.encodeToJsonElement(SmeditRoomItemsDetails.serializer(), details))
                }
                "inspect_room_tiles" -> {
                    val rawId = arguments.string("room_id")
                        ?: return failure("invalid_arguments", "room_id is required.")
                    val roomId = parseRoomId(rawId)
                        ?: return failure("invalid_room_id", "'$rawId' is not a 16-bit hexadecimal room id.")
                    val stateId = arguments.string("state_id")?.trim()?.takeIf(String::isNotEmpty)
                    val coordinates = arguments.roomTileCoordinates()
                        ?: return failure(
                            "invalid_arguments",
                            "coordinates must contain 1 through 64 objects with x/y block coordinates, optional layer 1/2, and an optional complete pixel_x/pixel_y pair from 0 through 15.",
                        )
                    val details = access.roomTiles(roomId, stateId, coordinates)
                        ?: return failure("room_tiles_unavailable", "The requested room state or tile data is not readable.")
                    success(json.encodeToJsonElement(SmeditRoomTilesDetails.serializer(), details))
                }
                "find_room_tiles" -> {
                    val rawId = arguments.string("room_id")
                        ?: return failure("invalid_arguments", "room_id is required.")
                    val roomId = parseRoomId(rawId)
                        ?: return failure("invalid_room_id", "'$rawId' is not a 16-bit hexadecimal room id.")
                    val stateId = arguments.string("state_id")?.trim()?.takeIf(String::isNotEmpty)
                    val layer = arguments.optionalInt("layer") ?: 1
                    if (layer !in 1..2) return failure("invalid_arguments", "layer must be 1 or 2.")
                    val metatileIds = arguments.intSet("metatile_ids", 0, 0x3FF)
                        ?: return failure("invalid_arguments", "metatile_ids must contain integers from 0 through 1023.")
                    val paletteRows = arguments.intSet("palette_rows", 0, 7)
                        ?: return failure("invalid_arguments", "palette_rows must contain integers from 0 through 7.")
                    val colorIndices = arguments.intSet("color_indices", 0, 15)
                        ?: return failure("invalid_arguments", "color_indices must contain integers from 0 through 15.")
                    val colorsBgr555 = arguments.intSet("colors_bgr555", 0, 0x7FFF)
                        ?: return failure("invalid_arguments", "colors_bgr555 must contain integers from 0 through 32767.")
                    val blockTypes = arguments.intSet("block_types", 0, 15)
                        ?: return failure("invalid_arguments", "block_types must contain integers from 0 through 15.")
                    val btsValues = arguments.intSet("bts_values", 0, 255)
                        ?: return failure("invalid_arguments", "bts_values must contain integers from 0 through 255.")
                    val details = access.findRoomTiles(
                        roomId = roomId,
                        stateId = stateId,
                        layer = layer,
                        metatileIds = metatileIds,
                        paletteRows = paletteRows,
                        colorIndices = colorIndices,
                        colorsBgr555 = colorsBgr555,
                        blockTypes = blockTypes,
                        btsValues = btsValues,
                        limit = arguments.limit(),
                    ) ?: return failure("room_tiles_unavailable", "The requested room state or layer is not readable.")
                    success(json.encodeToJsonElement(SmeditRoomTileSearchDetails.serializer(), details))
                }
                "get_tileset_metatile" -> {
                    val tileset = arguments.optionalInt("tileset")
                        ?.takeIf { it in 0 until 29 }
                        ?: return failure("invalid_arguments", "tileset must be an integer from 0 through 28.")
                    val metatileId = arguments.optionalInt("metatile_id")
                        ?.takeIf { it in 0..0x3FF }
                        ?: return failure("invalid_arguments", "metatile_id must be an integer from 0 through 1023.")
                    val details = access.tilesetMetatile(tileset, metatileId)
                        ?: return failure("metatile_unavailable", "Tileset $tileset metatile $metatileId is not readable.")
                    success(json.encodeToJsonElement(SmeditMetatileDetails.serializer(), details))
                }
                "get_editor_settings" -> success(
                    json.encodeToJsonElement(SmeditEditorSettingsSummary.serializer(), access.editorSettings()),
                )
                "search_docs" -> {
                    val query = arguments.string("query")?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "query must not be blank.")
                    val matches = access.searchDocs(query, arguments.limit())
                    success(
                        json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(SmeditDocumentationMatch.serializer()),
                            matches,
                        ),
                    )
                }
                "read_doc" -> {
                    val path = arguments.string("path")?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "path must not be blank.")
                    val startLine = arguments.positiveInt("start_line", default = 1, maximum = 100000)
                        ?: return failure("invalid_arguments", "start_line must be an integer from 1 through 100000.")
                    val maxLines = arguments.positiveInt("max_lines", default = 120, maximum = 200)
                        ?: return failure("invalid_arguments", "max_lines must be an integer from 1 through 200.")
                    val excerpt = access.readDoc(path, startLine, maxLines)
                        ?: return failure("doc_not_found", "No readable SMEDIT documentation file exists at '$path'.")
                    success(json.encodeToJsonElement(SmeditDocumentationExcerpt.serializer(), excerpt))
                }
                "search_asm" -> {
                    val query = arguments.string("query")?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "query must not be blank.")
                    val matches = access.searchAsm(query, arguments.limit())
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditAsmSearchMatch.serializer()), matches))
                }
                else -> failure("unknown_tool", "SMEDIT does not expose a tool named '$name'.")
            }
        } catch (problem: Exception) {
            failure("tool_failed", problem.message ?: "The SMEDIT tool failed.")
        }
    }

    private fun success(data: JsonElement) = SmeditToolResult(
        buildJsonObject {
            put("ok", true)
            put("data", data)
        },
        isError = false,
    )

    private fun failure(code: String, message: String) = SmeditToolResult(
        buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        },
        isError = true,
    )

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringList(name: String): List<String>? {
        val value = get(name) ?: return emptyList()
        return (value as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.contentOrNull ?: return null
        }
    }

    private fun JsonObject.limit(default: Int = 25): Int =
        (get("limit") as? JsonPrimitive)?.intOrNull?.coerceIn(1, 100) ?: default

    private fun JsonObject.positiveInt(name: String, default: Int, maximum: Int): Int? {
        val value = get(name) ?: return default
        return (value as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..maximum }
    }

    private fun JsonObject.optionalInt(name: String): Int? =
        (get(name) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.intSet(name: String, minimum: Int, maximum: Int): Set<Int>? {
        val value = get(name) ?: return emptySet()
        return (value as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.intOrNull?.takeIf { it in minimum..maximum } ?: return null
        }?.toSet()
    }

    private fun JsonObject.roomTileCoordinates(): List<SmeditRoomTileCoordinate>? {
        val values = get("coordinates") as? JsonArray ?: return null
        if (values.size !in 1..64) return null
        return values.map { element ->
            val coordinate = element as? JsonObject ?: return null
            val x = (coordinate["x"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
            val y = (coordinate["y"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
            val layer = (coordinate["layer"] as? JsonPrimitive)?.intOrNull ?: 1
            if (layer !in 1..2) return null
            val pixelX = (coordinate["pixel_x"] as? JsonPrimitive)?.intOrNull
            val pixelY = (coordinate["pixel_y"] as? JsonPrimitive)?.intOrNull
            if ((pixelX == null) != (pixelY == null) || pixelX != null && (pixelX !in 0..15 || pixelY !in 0..15)) return null
            SmeditRoomTileCoordinate(x, y, layer, pixelX, pixelY)
        }
    }

    private fun parseRoomId(value: String): Int? {
        val normalized = value.trim().removePrefix("0x").removePrefix("0X").removePrefix("\$")
        return normalized.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
    }

    private fun formatRoomId(roomId: Int): String = "$" + roomId.toString(16).uppercase().padStart(4, '0')

    companion object {
        private fun objectSchema(
            properties: Map<String, JsonObject> = emptyMap(),
            required: List<String> = emptyList(),
        ) = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }

        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun integerSchema(description: String, minimum: Int = 1, maximum: Int = 100) = buildJsonObject {
            put("type", "integer")
            put("description", description)
            put("minimum", minimum)
            put("maximum", maximum)
        }

        private fun stringArraySchema(description: String) = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        }

        private fun paletteChangesSchema() = buildJsonObject {
            put("type", "array")
            put("description", "One or more exact palette entries. Include changes from every relevant row in this one array. color must be #RRGGBB.")
            put("minItems", 1)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("index", integerSchema("Zero-based color index, equal to row * 16 + column.", 0, 4095))
                    put("color", stringSchema("Six-digit RGB color such as #FF8000."))
                })
                put("required", JsonArray(listOf("index", "color").map(::JsonPrimitive)))
                put("additionalProperties", false)
            })
        }

        private fun paletteEditsSchema() = buildJsonObject {
            put("type", "array")
            put("description", "One or more palette edits to stage atomically. Use exactly one entry per unique palette id; SMEDIT merges non-conflicting duplicates defensively.")
            put("minItems", 1)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("palette", stringSchema("Stable palette id from list_palettes."))
                    put("changes", paletteChangesSchema())
                })
                put("required", JsonArray(listOf("palette", "changes").map(::JsonPrimitive)))
                put("additionalProperties", false)
            })
        }

        private fun roomTileCoordinatesSchema() = buildJsonObject {
            put("type", "array")
            put("description", "One through 64 zero-based room-block coordinates to decode.")
            put("minItems", 1)
            put("maxItems", 64)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("x", integerSchema("Zero-based room-block X coordinate.", 0, 255))
                    put("y", integerSchema("Zero-based room-block Y coordinate.", 0, 255))
                    put("layer", integerSchema("Room layer: 1 or 2. Defaults to 1.", 1, 2))
                    put("pixel_x", integerSchema("Optional rendered pixel X within the 16x16 block.", 0, 15))
                    put("pixel_y", integerSchema("Optional rendered pixel Y within the 16x16 block.", 0, 15))
                })
                put("required", JsonArray(listOf("x", "y").map(::JsonPrimitive)))
                put("additionalProperties", false)
            })
        }

        private fun roomTileEditsSchema() = buildJsonObject {
            put("type", "array")
            put("description", "One through 256 explicit block edits. At least one of block_word, metatile_id, block_type, bts, h_flip, or v_flip must be supplied per entry.")
            put("minItems", 1)
            put("maxItems", 256)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("x", integerSchema("Zero-based room-block X coordinate.", 0, 255))
                    put("y", integerSchema("Zero-based room-block Y coordinate.", 0, 255))
                    put("layer", integerSchema("Room layer: 1 or 2. Defaults to 1.", 1, 2))
                    put("block_word", integerSchema("Optional exact 16-bit block word. Cannot be combined with metatile_id, block_type, h_flip, or v_flip.", 0, 0xFFFF))
                    put("metatile_id", integerSchema("Optional metatile id from 0 through 1023.", 0, 0x3FF))
                    put("block_type", integerSchema("Optional Layer 1 collision block type from 0 through 15.", 0, 15))
                    put("bts", integerSchema("Optional Layer 1 BTS byte from 0 through 255.", 0, 255))
                    put("h_flip", booleanSchema("Optional room-block horizontal flip."))
                    put("v_flip", booleanSchema("Optional room-block vertical flip."))
                })
                put("required", JsonArray(listOf("x", "y").map(::JsonPrimitive)))
                put("additionalProperties", false)
            })
        }

        private fun integerArraySchema(description: String, minimum: Int, maximum: Int) = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject {
                put("type", "integer")
                put("minimum", minimum)
                put("maximum", maximum)
            })
        }

        private fun enumArraySchema(description: String, values: List<String>) = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(values.map(::JsonPrimitive)))
            })
        }

        private fun enumSchema(description: String, values: List<String>) = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        }

        private fun booleanSchema(description: String) = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

        private fun integerMapSchema(description: String) = buildJsonObject {
            put("type", "object")
            put("description", description)
            put("additionalProperties", buildJsonObject { put("type", "integer") })
        }
    }
}
