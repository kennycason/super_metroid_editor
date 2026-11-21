package com.supermetroid.editor.api

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.RoomStateResourceLinks
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.ui.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SmeditMutationControllerTest {
    @Test
    fun `planning resolves exclusions and counts without changing the project`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())

        val planning = controller.plan("set_room_music", musicArguments(excludeAreas = listOf("ceres")))

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals("approval_required", planning.result.output.getValue("status").jsonPrimitive.content)
        assertEquals(2, planning.plan?.affectedRoomCount)
        assertEquals(5, planning.plan?.affectedStateCount)
        assertEquals(listOf("ceres"), planning.plan?.excludedAreas)
        assertTrue(editor.project.rooms.isEmpty(), "Planning must not create room edits")
        assertFalse(editor.dirty)
    }

    @Test
    fun `bulk music protects distinct runtime state music unless explicitly overridden`() {
        val editor = editor()
        val protectedController = DesktopSmeditMutationController(
            editor,
            FakeWorkspace(distinctMusicRoomIds = setOf(0x91F8)),
        )

        val protectedPlan = protectedController.plan("set_room_music", musicArguments()).plan!!

        assertEquals(2, protectedPlan.affectedRoomCount)
        assertEquals(listOf("0x91F8"), protectedPlan.protectedRoomIds)
        assertEquals(listOf("Landing Site (0x91F8)"), protectedPlan.protectedRoomPreview)
        protectedController.approve(protectedPlan.id)
        assertFalse(editor.project.rooms.containsKey("91F8"))
        assertTrue(editor.project.rooms.containsKey("9AD9"))

        val overrideEditor = editor()
        val overrideController = DesktopSmeditMutationController(
            overrideEditor,
            FakeWorkspace(distinctMusicRoomIds = setOf(0x91F8)),
        )
        val overridePlan = overrideController.plan(
            "set_room_music",
            musicArguments(overwriteSpecialStateMusic = true),
        ).plan!!

        assertEquals(3, overridePlan.affectedRoomCount)
        assertTrue(overridePlan.protectedRoomIds.isEmpty())
        assertTrue(overridePlan.warnings.single().contains("flatten distinct boss/event/state music"))
        overrideController.approve(overridePlan.id)
        assertEquals(0x18, overrideEditor.project.rooms["91F8"]?.stateDataChange?.musicData)
    }

    @Test
    fun `music bytes and ASM source mode fail closed`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())

        assertEquals("invalid_arguments", controller.plan("set_room_music", musicArguments(data = -1)).errorCode())
        assertEquals("invalid_arguments", controller.plan("set_room_music", musicArguments(track = 256)).errorCode())

        editor.project.asmWorkspace.enabled = true
        editor.project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val sourceMode = controller.plan("set_room_music", musicArguments())
        assertEquals("asm_source_unsupported", sourceMode.errorCode())
        assertTrue(editor.project.rooms.isEmpty())
    }

    @Test
    fun `approval applies all target states and undo redo restore only music fields`() {
        val editor = editor()
        val existing = editor.project.getOrCreateRoom(0x91F8)
        existing.stateDataChange = StateDataChange(tileset = 3, musicData = 6, musicTrack = 5, bgScrolling = 0x1234)
        existing.states += state("state-1", StateDataChange(tileset = 7, musicData = 8, musicTrack = 9))
        existing.states += state("state-2", StateDataChange(bgScrolling = 0x0102, musicData = 10, musicTrack = 11))
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_room_music",
            musicArguments(roomIds = listOf("91F8", "9AD9")),
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        controller.approve(planning.plan!!.id)

        assertEquals(StateDataChange(tileset = 3, musicData = 0x18, musicTrack = 5, bgScrolling = 0x1234), existing.stateDataChange)
        assertEquals(StateDataChange(tileset = 7), existing.states[0].stateDataChange)
        assertEquals(StateDataChange(bgScrolling = 0x0102), existing.states[1].stateDataChange)
        assertEquals(StateDataChange(musicData = 0x18, musicTrack = 5), editor.project.rooms["9AD9"]?.stateDataChange)
        assertTrue(editor.dirty)
        assertTrue(controller.canUndo)

        controller.undo()

        assertEquals(StateDataChange(tileset = 3, musicData = 6, musicTrack = 5, bgScrolling = 0x1234), existing.stateDataChange)
        assertEquals(StateDataChange(tileset = 7, musicData = 8, musicTrack = 9), existing.states[0].stateDataChange)
        assertEquals(StateDataChange(bgScrolling = 0x0102, musicData = 10, musicTrack = 11), existing.states[1].stateDataChange)
        assertFalse(editor.project.rooms.containsKey("9AD9"), "Undo removes an otherwise-empty entry created by the AI action")
        assertTrue(controller.canRedo)

        controller.redo()
        assertEquals(StateDataChange(musicData = 0x18, musicTrack = 5), editor.project.rooms["9AD9"]?.stateDataChange)
        assertNull(existing.states[0].stateDataChange?.musicData)
        assertNull(existing.states[0].stateDataChange?.musicTrack)
    }

    @Test
    fun `stale approval and undo are blocked before any partial mutation`() {
        val editor = editor()
        val first = editor.project.getOrCreateRoom(0x91F8)
        first.stateDataChange = StateDataChange(musicData = 1, musicTrack = 2)
        val second = editor.project.getOrCreateRoom(0x9AD9)
        second.stateDataChange = StateDataChange(musicData = 3, musicTrack = 4)
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val stalePlan = controller.plan(
            "set_room_music",
            musicArguments(roomIds = listOf("91F8", "9AD9")),
        ).plan!!
        second.stateDataChange = StateDataChange(musicData = 0x55, musicTrack = 4)

        assertFailsWith<IllegalStateException> { controller.approve(stalePlan.id) }
        assertEquals(StateDataChange(musicData = 1, musicTrack = 2), first.stateDataChange)
        assertEquals(StateDataChange(musicData = 0x55, musicTrack = 4), second.stateDataChange)
        controller.reject(stalePlan.id)

        val applied = controller.plan("set_room_music", musicArguments(roomIds = listOf("91F8"))).plan!!
        controller.approve(applied.id)
        first.stateDataChange = StateDataChange(musicData = 0x44, musicTrack = 5)
        assertFailsWith<IllegalStateException> { controller.undo() }
        assertEquals(StateDataChange(musicData = 0x44, musicTrack = 5), first.stateDataChange)
    }

    @Test
    fun `environmental patch configuration plans applies and round trips through undo redo`() {
        val editor = editor()
        val patch = environmentalPatch()
        editor.project.patches += patch
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())

        val planning = controller.plan(
            "configure_patch",
            patchArguments(
                patch = "environmental_damage",
                enabled = true,
                values = mapOf(
                    EnvironmentalDamagePatch.HEAT_KEY to 1,
                    EnvironmentalDamagePatch.LAVA_KEY to 5,
                    EnvironmentalDamagePatch.ACID_KEY to 10,
                    EnvironmentalDamagePatch.ALL_ROOMS_HEATED_KEY to 1,
                ),
            ),
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(SmeditMutationPlanKind.PATCH, planning.plan?.kind)
        assertEquals(4, planning.plan?.patch?.valueChanges?.size)
        assertFalse(patch.enabled, "Planning must not enable the patch")
        assertNull(patch.configData, "Planning must not configure the patch")

        controller.approve(planning.plan!!.id)
        assertTrue(patch.enabled)
        assertEquals(
            mapOf(
                EnvironmentalDamagePatch.HEAT_KEY to 1,
                EnvironmentalDamagePatch.LAVA_KEY to 5,
                EnvironmentalDamagePatch.ACID_KEY to 10,
                EnvironmentalDamagePatch.ALL_ROOMS_HEATED_KEY to 1,
            ),
            patch.configData?.toMap(),
        )

        controller.undo()
        assertFalse(patch.enabled)
        assertNull(patch.configData)

        controller.redo()
        assertTrue(patch.enabled)
        assertEquals(5, patch.configData?.get(EnvironmentalDamagePatch.LAVA_KEY))
        patch.configData!![EnvironmentalDamagePatch.LAVA_KEY] = 6
        assertFailsWith<IllegalStateException> { controller.undo() }
        assertEquals(6, patch.configData?.get(EnvironmentalDamagePatch.LAVA_KEY))
    }

    @Test
    fun `enabling an exclusive patch previews applies and undoes the peer disable`() {
        val editor = editor()
        val target = SmPatch("variant-a", "Variant A", enabled = false, exclusiveGroup = "mode")
        val peer = SmPatch("variant-b", "Variant B", enabled = true, exclusiveGroup = "mode")
        editor.project.patches += listOf(target, peer)
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())

        val planning = controller.plan(
            "configure_patch",
            patchArguments(target.id, enabled = true),
        )

        assertEquals(listOf("Variant B"), planning.plan?.patch?.automaticallyDisabledPatches)
        controller.approve(planning.plan!!.id)
        assertTrue(target.enabled)
        assertFalse(peer.enabled)

        controller.undo()
        assertFalse(target.enabled)
        assertTrue(peer.enabled)
    }

    @Test
    fun `patch configuration rejects unknown out of range and stale values without partial mutation`() {
        val editor = editor()
        val patch = environmentalPatch()
        editor.project.patches += patch
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())

        assertEquals(
            "unknown_config_field",
            controller.plan("configure_patch", patchArguments(patch.id, values = mapOf("lava" to 5))).errorCode(),
        )
        assertEquals(
            "config_out_of_range",
            controller.plan(
                "configure_patch",
                patchArguments(patch.id, values = mapOf(EnvironmentalDamagePatch.LAVA_KEY to 10_000)),
            ).errorCode(),
        )
        val plan = controller.plan(
            "configure_patch",
            patchArguments(patch.id, enabled = true, values = mapOf(EnvironmentalDamagePatch.LAVA_KEY to 5)),
        ).plan!!
        patch.configData = mutableMapOf(EnvironmentalDamagePatch.HEAT_KEY to 77)

        assertFailsWith<IllegalStateException> { controller.approve(plan.id) }
        assertFalse(patch.enabled)
        assertEquals(mapOf(EnvironmentalDamagePatch.HEAT_KEY to 77), patch.configData?.toMap())
    }

    @Test
    fun `patch writes fail closed in ASM source mode`() {
        val editor = editor()
        editor.project.patches += environmentalPatch()
        editor.project.asmWorkspace.enabled = true
        editor.project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())

        val planning = controller.plan(
            "configure_patch",
            patchArguments("environmental_damage", enabled = true),
        )

        assertEquals("asm_source_unsupported", planning.errorCode())
        assertFalse(editor.project.patches.single().enabled)
    }

    private fun editor() = EditorState()

    private fun state(id: String, change: StateDataChange) = RoomStateEdits(
        id = id,
        condition = projectRoomStateCondition(ProjectRoomStateConditionKind.DEFAULT),
        resources = RoomStateResourceLinks("level", "fx", "enemies", "gfx", "scrolls", "plms", "bg", "xray"),
        stateDataChange = change,
    )

    private fun musicArguments(
        data: Int = 0x18,
        track: Int = 5,
        roomIds: List<String> = emptyList(),
        excludeAreas: List<String> = emptyList(),
        overwriteSpecialStateMusic: Boolean? = null,
    ) = buildJsonObject {
        put("music_data", data)
        put("music_track", track)
        if (roomIds.isNotEmpty()) put("room_ids", JsonArray(roomIds.map(::JsonPrimitive)))
        if (excludeAreas.isNotEmpty()) put("exclude_areas", JsonArray(excludeAreas.map(::JsonPrimitive)))
        overwriteSpecialStateMusic?.let { put("overwrite_special_state_music", it) }
    }

    private fun patchArguments(
        patch: String,
        enabled: Boolean? = null,
        values: Map<String, Int> = emptyMap(),
    ) = buildJsonObject {
        put("patch", patch)
        enabled?.let { put("enabled", it) }
        if (values.isNotEmpty()) put("values", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
    }

    private fun environmentalPatch() = SmPatch(
        id = "config_environmental_damage",
        name = "Environmental Damage",
        description = "Environmental rates",
        enabled = false,
        configType = EnvironmentalDamagePatch.CONFIG_TYPE,
    )

    private fun SmeditMutationPlanningResult.errorCode(): String =
        result.output.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private class FakeWorkspace(
        private val distinctMusicRoomIds: Set<Int> = emptySet(),
    ) : SmeditWorkspaceAccess {
        private val roomList = listOf(
            SmeditRoomSummary("0x91F8", "Landing Site", area = "Crateria"),
            SmeditRoomSummary("0x9AD9", "Green Brinstar", area = "Brinstar"),
            SmeditRoomSummary("0xDF45", "Ceres Elevator", area = "Ceres"),
        )
        private val stateCounts = mapOf(0x91F8 to 2, 0x9AD9 to 3, 0xDF45 to 4)

        override fun workspaceSummary() = SmeditWorkspaceSummary(projectLoaded = true, roomCount = roomList.size)
        override fun rooms() = roomList
        override fun room(roomId: Int) = stateCounts[roomId]?.let { count ->
            SmeditRoomDetails(
                id = "0x${roomId.toString(16).uppercase()}",
                name = roomList.single { it.id.endsWith(roomId.toString(16), ignoreCase = true) }.name,
                handle = "room-$roomId",
                area = roomList.single { it.id.endsWith(roomId.toString(16), ignoreCase = true) }.area!!,
                areaIndex = 0,
                mapX = 0,
                mapY = 0,
                widthScreens = 1,
                heightScreens = 1,
                tileset = 0,
                musicData = 0,
                musicTrack = 0,
                stateCount = count,
                projectStateEditCount = 0,
                edited = false,
                projectOwned = false,
                sourceStateMusic = List(count) { index ->
                    SmeditRoomMusicState(
                        index = index,
                        condition = if (index == count - 1) "Standard (default)" else "State $index",
                        musicData = if (roomId in distinctMusicRoomIds && index == 0) 0x24 else 0,
                        musicTrack = if (roomId in distinctMusicRoomIds && index == 0) 3 else 0,
                    )
                },
                hasDistinctSourceMusic = roomId in distinctMusicRoomIds,
            )
        }
        override fun patches() = emptyList<SmeditPatchSummary>()
        override fun patch(identifier: String): SmeditPatchDetails? = null
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
        override fun searchAsm(query: String, limit: Int) = emptyList<SmeditAsmSearchMatch>()
    }
}
