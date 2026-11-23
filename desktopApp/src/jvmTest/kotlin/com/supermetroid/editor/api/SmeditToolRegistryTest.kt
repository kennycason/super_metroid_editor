package com.supermetroid.editor.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SmeditToolRegistryTest {
    private val access = FakeWorkspaceAccess()
    private val registry = SmeditToolRegistry(access)

    @Test
    fun `registry publishes transport neutral read and approval backed mutation schemas`() {
        assertEquals(
            setOf(
                "get_workspace_summary", "list_rooms", "get_room", "list_patches", "get_patch",
                "list_room_fx", "get_editor_settings", "search_docs", "read_doc", "search_asm",
                "list_palettes", "get_palette", "list_item_types", "list_room_items",
                "inspect_room_tiles", "find_room_tiles", "get_tileset_metatile",
                "set_room_music", "configure_patch", "set_room_fx", "set_palette_colors", "place_item", "set_room_tiles",
            ),
            registry.definitions.mapTo(mutableSetOf(), SmeditToolDefinition::name),
        )
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("set_room_music")?.effect)
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("configure_patch")?.effect)
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("set_room_fx")?.effect)
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("set_palette_colors")?.effect)
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("place_item")?.effect)
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("set_room_tiles")?.effect)
        assertEquals(
            listOf("update_fields"),
            registry.definition("set_room_fx")!!.inputSchema.getValue("required").jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertTrue(
            "overwrite_audio_sensitive_music" in registry.definition("set_room_music")!!
                .inputSchema.getValue("properties").jsonObject,
        )
        assertEquals(
            setOf("palette_edits"),
            registry.definition("set_palette_colors")!!.inputSchema
                .getValue("properties").jsonObject.keys,
        )
        assertEquals(
            listOf("palette_edits"),
            registry.definition("set_palette_colors")!!.inputSchema
                .getValue("required").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(
            registry.definitions.filterNot {
                it.name in setOf("set_room_music", "configure_patch", "set_room_fx", "set_palette_colors", "place_item", "set_room_tiles")
            }
                .all { it.effect == SmeditToolEffect.READ_ONLY },
        )
        registry.definitions.forEach { definition ->
            assertEquals("object", definition.inputSchema.getValue("type").jsonPrimitive.content)
            assertFalse(definition.inputSchema.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
        }
        val directMutation = registry.execute("set_room_music", buildJsonObject {
            put("music_data", 0x18)
            put("music_track", 5)
        })
        assertTrue(directMutation.isError)
        assertEquals("approval_required", directMutation.errorCode())
    }

    @Test
    fun `patch details expose canonical configuration fields`() {
        val result = registry.execute("get_patch", buildJsonObject { put("patch", "environmental_damage") })

        assertFalse(result.isError)
        val data = result.output.getValue("data").jsonObject
        assertEquals("config_environmental_damage", data.getValue("id").jsonPrimitive.content)
        assertEquals("heat_per_second", data.getValue("configFields").jsonArray.first().jsonObject.getValue("key").jsonPrimitive.content)
    }

    @Test
    fun `room tools normalize ids filter names and cap results`() {
        val listed = registry.execute("list_rooms", buildJsonObject {
            put("query", "brinstar")
            put("limit", 1)
        })
        assertFalse(listed.isError)
        val listedData = listed.output.getValue("data").jsonArray
        assertEquals(1, listedData.size)
        assertEquals("Green Brinstar", listedData.single().jsonObject.getValue("name").jsonPrimitive.content)

        val detail = registry.execute("get_room", buildJsonObject { put("room_id", "\$91f8") })
        assertFalse(detail.isError)
        assertEquals("0x91F8", detail.output.getValue("data").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(
            2,
            detail.output.getValue("data").jsonObject.getValue("sourceStateMusic").jsonArray.size,
        )
        assertTrue(
            detail.output.getValue("data").jsonObject.getValue("hasDistinctSourceMusic").jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            "bg_animate",
            detail.output.getValue("data").jsonObject.getValue("states").jsonArray.single().jsonObject
                .getValue("fxEntries").jsonArray.single().jsonObject.getValue("liquidOptions").jsonArray.single().jsonPrimitive.content,
        )

        val fx = registry.execute("list_room_fx", buildJsonObject {
            put("areas", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("Crateria")) })
        })
        assertFalse(fx.isError)
        assertEquals(1, fx.output.getValue("data").jsonArray.size)

        val invalid = registry.execute("get_room", buildJsonObject { put("room_id", "not-a-room") })
        assertTrue(invalid.isError)
        assertEquals("invalid_room_id", invalid.errorCode())
    }

    @Test
    fun `tile tools expose exact palette provenance and validate coordinates`() {
        val inspected = registry.execute("inspect_room_tiles", Json.parseToJsonElement(
            """{"room_id":"91F8","coordinates":[{"x":4,"y":5,"pixel_x":2,"pixel_y":3}]}""",
        ).jsonObject)
        assertFalse(inspected.isError)
        val tile = inspected.output.getValue("data").jsonObject.getValue("tiles").jsonArray.single().jsonObject
        assertEquals(3, tile.getValue("metatileId").jsonPrimitive.content.toInt())
        assertEquals(1, tile.getValue("metatile").jsonObject.getValue("subtiles").jsonArray.single().jsonObject
            .getValue("paletteRow").jsonPrimitive.content.toInt())

        val found = registry.execute("find_room_tiles", Json.parseToJsonElement(
            """{"room_id":"91F8","palette_rows":[1],"colors_bgr555":[31]}""",
        ).jsonObject)
        assertFalse(found.isError)
        assertEquals(1, found.output.getValue("data").jsonObject.getValue("totalMatches").jsonPrimitive.content.toInt())

        val metatile = registry.execute("get_tileset_metatile", buildJsonObject {
            put("tileset", 0)
            put("metatile_id", 3)
        })
        assertFalse(metatile.isError)

        val invalid = registry.execute("inspect_room_tiles", Json.parseToJsonElement(
            """{"room_id":"91F8","coordinates":[{"x":4,"y":5,"pixel_x":2}]}""",
        ).jsonObject)
        assertTrue(invalid.isError)
        assertEquals("invalid_arguments", invalid.errorCode())
    }

    @Test
    fun `ASM search validation fails closed before accessing workspace`() {
        val blank = registry.execute("search_asm", buildJsonObject { put("query", "  ") })
        assertTrue(blank.isError)
        assertEquals(0, access.searchCount)

        val found = registry.execute("search_asm", buildJsonObject {
            put("query", "Samus")
            put("limit", 500)
        })
        assertFalse(found.isError)
        assertEquals(1, access.searchCount)
        assertEquals(100, access.lastSearchLimit)
    }

    @Test
    fun `documentation tools validate bounded retrieval`() {
        val found = registry.execute("search_docs", buildJsonObject {
            put("query", "room states")
            put("limit", 500)
        })
        assertFalse(found.isError)
        assertEquals(100, access.lastDocsLimit)

        val read = registry.execute("read_doc", buildJsonObject {
            put("path", "rom/write_safety.md")
            put("start_line", 4)
            put("max_lines", 999)
        })
        assertTrue(read.isError)
        assertEquals("invalid_arguments", read.errorCode())

        val validRead = registry.execute("read_doc", buildJsonObject {
            put("path", "rom/write_safety.md")
            put("start_line", 4)
            put("max_lines", 20)
        })
        assertFalse(validRead.isError)
        assertEquals(4, validRead.output.getValue("data").jsonObject.getValue("startLine").jsonPrimitive.content.toInt())
    }

    @Test
    fun `unknown tools return structured errors without invoking access`() {
        val result = registry.execute("write_arbitrary_rom", JsonObject(emptyMap()))
        assertTrue(result.isError)
        assertEquals("unknown_tool", result.errorCode())
        assertNotNull(result.output["error"])
    }

    private fun SmeditToolResult.errorCode(): String =
        output.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private class FakeWorkspaceAccess : SmeditWorkspaceAccess {
        var searchCount = 0
        var lastSearchLimit = 0
        var lastDocsLimit = 0

        override fun workspaceSummary() = SmeditWorkspaceSummary(projectLoaded = true, roomCount = 2)

        override fun rooms() = listOf(
            SmeditRoomSummary("0x91F8", "Landing Site", area = "Crateria"),
            SmeditRoomSummary("0x9AD9", "Green Brinstar", area = "Brinstar"),
            SmeditRoomSummary("0x9E52", "Big Pink", area = "Brinstar"),
        )

        override fun room(roomId: Int): SmeditRoomDetails? = if (roomId == 0x91F8) {
            SmeditRoomDetails(
                id = "0x91F8",
                name = "Landing Site",
                handle = "landing-site",
                area = "Crateria",
                areaIndex = 0,
                mapX = 17,
                mapY = 5,
                widthScreens = 5,
                heightScreens = 2,
                tileset = 0,
                musicData = 6,
                musicTrack = 5,
                stateCount = 1,
                projectStateEditCount = 0,
                edited = false,
                projectOwned = false,
                sourceStateMusic = listOf(
                    SmeditRoomMusicState(0, "Event set", 0x00, 0x00),
                    SmeditRoomMusicState(1, "Standard (default)", 0x06, 0x05),
                ),
                hasDistinctSourceMusic = true,
                states = listOf(
                    SmeditRoomStateDetails(
                        id = "state-1",
                        index = 0,
                        sourceIndex = 0,
                        condition = "Standard (default)",
                        conditionKind = "DEFAULT",
                        defaultState = true,
                        readable = true,
                        projectEdited = false,
                        projectFxEdited = false,
                        fxSourceManaged = false,
                        fxEntries = listOf(
                            SmeditFxEntryDetails(
                                selector = "default",
                                doorSelect = 0,
                                doorSelectHex = "0x0000",
                                liquidSurfaceStart = 0xFFFF,
                                liquidSurfaceNew = 0xFFFF,
                                liquidSpeed = 0,
                                liquidDelay = 0,
                                fxType = 2,
                                fxTypeName = "Lava",
                                fxBitA = 2,
                                fxBitB = 2,
                                fxBitC = 2,
                                liquidOptions = listOf("bg_animate"),
                                paletteFxBitflags = 0,
                                tileAnimBitflags = 0,
                                paletteBlend = 0,
                            ),
                        ),
                    ),
                ),
            )
        } else null

        override fun patches() = listOf(SmeditPatchSummary("fast-doors", "Fast Doors", "", enabled = true))

        override fun patch(identifier: String): SmeditPatchDetails? =
            if (identifier == "environmental_damage") {
                SmeditPatchDetails(
                    id = "config_environmental_damage",
                    name = "Environmental Damage",
                    description = "",
                    enabled = false,
                    configType = "environmental_damage",
                    configurable = true,
                    configFieldCount = 1,
                    configFields = listOf(
                        SmeditPatchConfigField(
                            key = "heat_per_second",
                            label = "Heated Rooms",
                            type = "int",
                            min = 0,
                            max = 9999,
                            currentValue = 15,
                            defaultValue = 15,
                        ),
                    ),
                )
            } else null

        override fun palettes() = listOf(
            SmeditPaletteSummary(
                id = "sprite:boss_kraid_health_bg",
                name = "Kraid (health stages · BG)",
                kind = "sprite",
                category = "bosses",
                description = "Kraid BG2 palettes",
                colorCount = 160,
                rowCount = 10,
                editable = true,
                sourceManaged = false,
                modified = false,
            ),
        )

        override fun palette(identifier: String) = palettes().singleOrNull {
            it.id == identifier
        }?.let { summary ->
            SmeditPaletteDetails(
                id = summary.id,
                name = summary.name,
                kind = summary.kind,
                category = summary.category,
                description = summary.description,
                colorCount = summary.colorCount,
                rowCount = summary.rowCount,
                editable = true,
                sourceManaged = false,
                modified = false,
                colorsBgr555 = List(160) { 0 },
                colorsRgbHex = List(160) { "#000000" },
            )
        }

        override fun itemTypes() = listOf(SmeditItemType("Missile", "Visible", 0xEEDB, "0xEEDB"))

        override fun roomItems(roomId: Int) = if (roomId == 0x91F8) {
            SmeditRoomItemsDetails(
                roomId = "0x91F8",
                roomName = "Landing Site",
                widthBlocks = 80,
                heightBlocks = 32,
                sourceManaged = false,
                states = listOf(SmeditRoomItemState("state-1", 0, "Default", emptyList())),
            )
        } else null

        private fun metatile() = SmeditMetatileDetails(
            tileset = 0,
            metatileId = 3,
            metatileIdHex = "0x003",
            tableSource = "Tileset-local variable metatile table",
            placeholder = false,
            subtiles = listOf(
                SmeditMetatileSubtileDetails(
                    quadrant = "top_left",
                    word = 0x0401,
                    wordHex = "0x0401",
                    tileNumber = 1,
                    tileNumberHex = "0x001",
                    tileSource = "Area",
                    paletteRow = 1,
                    priority = false,
                    hFlip = false,
                    vFlip = false,
                    usedColorIndices = listOf(1),
                    usedColors = listOf(SmeditTilePaletteColor(1, 17, 31, "0x001F", "#FF0000")),
                ),
            ),
        )

        override fun roomTiles(
            roomId: Int,
            stateId: String?,
            coordinates: List<SmeditRoomTileCoordinate>,
        ) = SmeditRoomTilesDetails(
            roomId = "0x91F8",
            roomName = "Landing Site",
            stateId = "state-1",
            stateCondition = "Default",
            layoutResourceId = "level-1",
            sourceManaged = false,
            widthBlocks = 80,
            heightBlocks = 32,
            tileset = 0,
            tilesetPaletteId = "tileset:0",
            hasLayer2 = false,
            tiles = coordinates.map { coordinate ->
                SmeditRoomTileDetails(
                    x = coordinate.x,
                    y = coordinate.y,
                    layer = coordinate.layer,
                    blockWord = 0x8003,
                    blockWordHex = "0x8003",
                    metatileId = 3,
                    metatileIdHex = "0x003",
                    blockType = 8,
                    bts = 0,
                    btsHex = "0x00",
                    hFlip = false,
                    vFlip = false,
                    metatile = metatile(),
                )
            },
        )

        override fun findRoomTiles(
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
        ) = SmeditRoomTileSearchDetails(
            roomId = "0x91F8",
            roomName = "Landing Site",
            stateId = "state-1",
            stateCondition = "Default",
            layoutResourceId = "level-1",
            sourceManaged = false,
            widthBlocks = 80,
            heightBlocks = 32,
            tileset = 0,
            tilesetPaletteId = "tileset:0",
            hasLayer2 = false,
            totalMatches = 1,
            truncated = false,
            matches = listOf(SmeditRoomTileSearchMatch(4, 5, 1, 0x8003, "0x8003", 3, "0x003", 8, 0, false, false, listOf(1), listOf(17), listOf(31))),
        )

        override fun tilesetMetatile(tileset: Int, metatileId: Int) = metatile()

        override fun editorSettings() = SmeditEditorSettingsSummary(
            theme = "DARK",
            fontSize = "MEDIUM",
            emulatorBackend = "libretro",
            emulatorFollowLiveRoom = true,
            roomSidebarTab = "TILESET",
            roomItemNamesVisible = true,
            roomMetaNamesVisible = true,
            roomEnemyNamesVisible = true,
        )

        override fun searchDocs(query: String, limit: Int): List<SmeditDocumentationMatch> {
            lastDocsLimit = limit
            return listOf(SmeditDocumentationMatch("project/room_model.md", 10, "Room states"))
        }

        override fun readDoc(path: String, startLine: Int, maxLines: Int) =
            SmeditDocumentationExcerpt(path, startLine, startLine + maxLines - 1, "Safe writes")

        override fun searchAsm(query: String, limit: Int): List<SmeditAsmSearchMatch> {
            searchCount++
            lastSearchLimit = limit
            return listOf(SmeditAsmSearchMatch("bank_91.asm", "Bank 91", 10, 4, "Samus:"))
        }
    }
}

