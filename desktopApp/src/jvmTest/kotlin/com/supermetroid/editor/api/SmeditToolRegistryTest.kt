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
                "get_editor_settings", "search_docs", "read_doc", "search_asm", "set_room_music", "configure_patch",
            ),
            registry.definitions.mapTo(mutableSetOf(), SmeditToolDefinition::name),
        )
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("set_room_music")?.effect)
        assertEquals(SmeditToolEffect.MUTATION, registry.definition("configure_patch")?.effect)
        assertTrue(
            registry.definitions.filterNot { it.name in setOf("set_room_music", "configure_patch") }
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

        val invalid = registry.execute("get_room", buildJsonObject { put("room_id", "not-a-room") })
        assertTrue(invalid.isError)
        assertEquals("invalid_room_id", invalid.errorCode())
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

