package com.supermetroid.editor.api

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.RoomStateResourceLinks
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.NativeLiquidOptions
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.asm.objectSourceTestRom
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
    fun `bulk music independently protects Tourian song dependent audio`() {
        val protectedEditor = editor()
        val protectedController = DesktopSmeditMutationController(
            protectedEditor,
            FakeWorkspace(includeTourianRoom = true),
        )

        val protectedPlan = protectedController.plan(
            "set_room_music",
            musicArguments(overwriteSpecialStateMusic = true),
        ).plan!!

        assertEquals(3, protectedPlan.affectedRoomCount)
        assertEquals(listOf("0xDCB1"), protectedPlan.protectedRoomIds)
        assertEquals(listOf("Baby Metroid Room (0xDCB1)"), protectedPlan.protectedRoomPreview)
        protectedController.approve(protectedPlan.id)
        assertFalse(protectedEditor.project.rooms.containsKey("DCB1"))

        val overrideEditor = editor()
        val overrideController = DesktopSmeditMutationController(
            overrideEditor,
            FakeWorkspace(includeTourianRoom = true),
        )
        val overridePlan = overrideController.plan(
            "set_room_music",
            musicArguments(overwriteAudioSensitiveMusic = true),
        ).plan!!

        assertEquals(4, overridePlan.affectedRoomCount)
        assertTrue(overridePlan.protectedRoomIds.isEmpty())
        assertTrue(overridePlan.warnings.single().contains("deadlock door transitions"))
        overrideController.approve(overridePlan.id)
        assertEquals(0x18, overrideEditor.project.rooms["DCB1"]?.stateDataChange?.musicData)
    }

    @Test
    fun `exact room state fields stage apply undo and redo without changing sibling states`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })
        val before = kotlinx.serialization.json.Json.encodeToString(
            com.supermetroid.editor.data.SmEditProject.serializer(),
            editor.project,
        )

        val planning = controller.plan(
            "set_room_state_fields",
            stateFieldArguments(
                stateId = "state-1",
                fields = mapOf("tileset" to 5, "music_data" to 0x24, "music_track" to 3, "bg_scrolling" to 0x0101),
            ),
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(SmeditMutationPlanKind.ROOM_STATE_FIELDS, planning.plan?.kind)
        assertEquals(4, planning.plan?.roomStateFields?.changes?.size)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(com.supermetroid.editor.data.SmEditProject.serializer(), editor.project))

        controller.approve(planning.plan!!.id)
        val room = editor.project.rooms.getValue("91F8")
        assertEquals(StateDataChange(tileset = 5, musicData = 0x24, musicTrack = 3, bgScrolling = 0x0101), room.states[0].stateDataChange)
        assertNull(room.states[1].stateDataChange)
        assertNull(room.stateDataChange)

        controller.undo()
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(com.supermetroid.editor.data.SmEditProject.serializer(), editor.project))
        controller.redo()
        assertEquals(0x24, editor.project.rooms.getValue("91F8").states[0].stateDataChange?.musicData)
        assertNull(editor.project.rooms.getValue("91F8").states[1].stateDataChange)
    }

    @Test
    fun `exact room state fields validate scope no-ops and Tourian music independently`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val controller = DesktopSmeditMutationController(
            editor,
            FakeWorkspace(roomAreaOverrides = mapOf(0x91F8 to "Tourian")),
            parser = { parser },
        )

        assertEquals(
            "no_changes",
            controller.plan("set_room_state_fields", stateFieldArguments(fields = mapOf("tileset" to 0))).errorCode(),
        )
        assertEquals(
            "invalid_arguments",
            controller.plan("set_room_state_fields", stateFieldArguments(fields = mapOf("tileset" to 29))).errorCode(),
        )
        assertEquals(
            "state_not_found",
            controller.plan("set_room_state_fields", stateFieldArguments(stateId = "missing", fields = mapOf("tileset" to 1))).errorCode(),
        )
        assertEquals(
            "protected_music",
            controller.plan(
                "set_room_state_fields",
                stateFieldArguments(fields = mapOf("music_data" to 0x18)),
            ).errorCode(),
        )
        val allowed = controller.plan(
            "set_room_state_fields",
            stateFieldArguments(
                fields = mapOf("music_data" to 0x18),
                overwriteAudioSensitiveMusic = true,
            ),
        )
        assertFalse(allowed.result.isError, allowed.result.output.toString())
        assertTrue(allowed.plan!!.warnings.single { it.contains("Tourian music") }.contains("deadlock"))
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

    @Test
    fun `room FX plans preserve door variants and round trip all selected states`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val room = editor.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        room.states.first().doorFxChanges["8000"] = FxChange(fxType = 4, liquidSurfaceStart = 0x120)
        val before = kotlinx.serialization.json.Json.encodeToString(
            com.supermetroid.editor.data.SmEditProject.serializer(),
            editor.project,
        )
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })

        val defaultOnly = controller.plan(
            "set_room_fx",
            fxArguments(includeConditionalStates = false),
        )
        assertFalse(defaultOnly.result.isError, defaultOnly.result.output.toString())
        assertEquals(1, defaultOnly.plan?.affectedStateCount)
        assertEquals(SmeditMutationPlanKind.ROOM_FX, defaultOnly.plan?.kind)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(com.supermetroid.editor.data.SmEditProject.serializer(), editor.project))
        controller.reject(defaultOnly.plan!!.id)

        val exactState = controller.plan(
            "set_room_fx",
            fxArguments(includeConditionalStates = true, stateTargets = listOf("0x91F8:state-1")),
        )
        assertFalse(exactState.result.isError, exactState.result.output.toString())
        assertEquals(1, exactState.plan?.affectedStateCount)
        controller.reject(exactState.plan!!.id)

        val planning = controller.plan(
            "set_room_fx",
            fxArguments(includeConditionalStates = true),
        )
        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(2, planning.plan?.affectedStateCount)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(com.supermetroid.editor.data.SmEditProject.serializer(), editor.project))

        controller.approve(planning.plan!!.id)
        val applied = editor.project.rooms.getValue("91F8")
        assertTrue(applied.states.all { it.fxChange?.fxType == 2 })
        assertTrue(applied.states.all { it.fxChange?.fxBitC == NativeLiquidOptions.BG_ANIMATE })
        assertEquals(FxChange(fxType = 4, liquidSurfaceStart = 0x120), applied.states.first().doorFxChanges["8000"])

        controller.undo()
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(com.supermetroid.editor.data.SmEditProject.serializer(), editor.project))

        controller.redo()
        assertTrue(editor.project.rooms.getValue("91F8").states.all { it.fxChange?.fxType == 2 })
    }

    @Test
    fun `room FX blocks hidden liquid tides and stale approval atomically`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })

        val unsafe = controller.plan(
            "set_room_fx",
            fxArguments(liquidOptions = listOf("small_tide"), includeConditionalStates = true),
        )
        assertEquals("unsafe_hidden_liquid_tide", unsafe.errorCode())
        assertTrue(editor.project.rooms.isEmpty())

        val plan = controller.plan("set_room_fx", fxArguments(includeConditionalStates = true)).plan!!
        editor.project.getOrCreateRoom(0x91F8).fxChange = FxChange(fxBitA = 7)
        assertFailsWith<IllegalStateException> { controller.approve(plan.id) }
        assertEquals(FxChange(fxBitA = 7), editor.project.rooms.getValue("91F8").fxChange)
        assertTrue(editor.project.rooms.getValue("91F8").states.isEmpty())
    }

    @Test
    fun `semantic liquid options take precedence over generated raw byte defaults`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })
        val arguments = JsonObject(
            fxArguments(includeConditionalStates = true) + mapOf(
                "fx_bit_c" to JsonPrimitive(0),
                "fx_bit_a" to JsonPrimitive(0),
                "palette_blend" to JsonPrimitive(0),
            ),
        )

        val planning = controller.plan("set_room_fx", arguments)

        assertFalse(planning.result.isError, planning.result.output.toString())
        controller.approve(planning.plan!!.id)
        assertTrue(
            editor.project.rooms.getValue("91F8").states.all {
                it.fxChange?.fxBitC == NativeLiquidOptions.BG_ANIMATE
            },
        )
        assertTrue(editor.project.rooms.getValue("91F8").states.all { it.fxChange?.fxBitA == null })
        assertTrue(editor.project.rooms.getValue("91F8").states.all { it.fxChange?.paletteBlend == null })
    }

    @Test
    fun `palette color plan applies and round trips through AI undo redo`() {
        val editor = editor()
        val workspace = FakeWorkspace()
        val controller = DesktopSmeditMutationController(editor, workspace)
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette", "sprite:boss_kraid_health_bg")
                put("changes", JsonArray(listOf(buildJsonObject {
                    put("index", 17)
                    put("color", "#FF8000")
                })))
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(SmeditMutationPlanKind.PALETTE, planning.plan?.kind)
        assertTrue(editor.project.customGfx.spritePalettes.isEmpty(), "Planning must not write palette data")

        controller.approve(planning.plan!!.id)
        val applied = com.supermetroid.editor.rom.SpritePalettes.bytesToColors(
            java.util.Base64.getDecoder().decode(
                editor.project.customGfx.spritePalettes.getValue("boss_kraid_health_bg"),
            ),
        )
        assertEquals("#FF8400", bgr555Hex(applied[17]))

        controller.undo()
        assertFalse(editor.project.customGfx.spritePalettes.containsKey("boss_kraid_health_bg"))
        controller.redo()
        assertTrue(editor.project.customGfx.spritePalettes.containsKey("boss_kraid_health_bg"))
    }

    @Test
    fun `related Kraid palettes apply atomically in one approval`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_oam")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                )))
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(1, planning.plan?.palette?.additionalTargets?.size)
        assertTrue(editor.project.customGfx.spritePalettes.isEmpty())
        controller.approve(planning.plan!!.id)
        assertTrue(editor.project.customGfx.spritePalettes.keys.containsAll(
            listOf("boss_kraid_health_bg", "boss_kraid_health_oam"),
        ))
        controller.undo()
        assertTrue(editor.project.customGfx.spritePalettes.isEmpty())
        controller.redo()
        assertTrue(editor.project.customGfx.spritePalettes.keys.containsAll(
            listOf("boss_kraid_health_bg", "boss_kraid_health_oam"),
        ))
    }

    @Test
    fun `Phantoon base and runtime health palettes apply atomically and round trip`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_phantoon")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 1)
                            put("color", "#FF0000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_phantoon_health")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 113)
                            put("color", "#FF0000")
                        })))
                    },
                )))
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(1, planning.plan?.palette?.additionalTargets?.size)
        assertTrue(editor.project.customGfx.spritePalettes.isEmpty())
        controller.approve(planning.plan!!.id)
        assertTrue(editor.project.customGfx.spritePalettes.keys.containsAll(
            listOf("boss_phantoon", "boss_phantoon_health"),
        ))
        controller.undo()
        assertTrue(editor.project.customGfx.spritePalettes.isEmpty())
        controller.redo()
        assertTrue(editor.project.customGfx.spritePalettes.keys.containsAll(
            listOf("boss_phantoon", "boss_phantoon_health"),
        ))
    }

    @Test
    fun `non-empty atomic palette batch takes precedence over redundant single fields`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette", "sprite:boss_kraid_health_bg")
                put("changes", JsonArray(listOf(buildJsonObject {
                    put("index", 17)
                    put("color", "#00FF00")
                })))
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_oam")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                )))
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(1, planning.plan?.palette?.additionalTargets?.size)
        controller.approve(planning.plan!!.id)
        assertTrue(editor.project.customGfx.spritePalettes.keys.containsAll(
            listOf("boss_kraid_health_bg", "boss_kraid_health_oam"),
        ))
    }

    @Test
    fun `duplicate palette rows merge when their color indices do not conflict`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 17)
                            put("color", "#FF0000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                )))
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals("sprite:boss_kraid_health_bg", planning.plan?.palette?.paletteId)
        assertEquals(2, planning.plan?.palette?.colorChanges?.size)
        assertTrue(planning.plan?.palette?.additionalTargets?.isEmpty() == true)
    }

    @Test
    fun `duplicate palette rows reject only a conflicting color assignment`() {
        val controller = DesktopSmeditMutationController(editor(), FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 17)
                            put("color", "#FF0000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 17)
                            put("color", "#00FF00")
                        })))
                    },
                )))
            },
        )

        assertEquals("conflicting_palette_index", planning.errorCode())
    }

    @Test
    fun `atomic palette plan skips an already matching target and stages remaining changes`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#000000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_oam")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                )))
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals("sprite:boss_kraid_health_oam", planning.plan?.palette?.paletteId)
        assertTrue(planning.plan!!.warnings.any { it.contains("already matches") })
        assertEquals(
            listOf("sprite:boss_kraid_health_bg"),
            (planning.result.output.getValue("already_matching_palette_ids") as JsonArray)
                .map { it.jsonPrimitive.content },
        )
        controller.approve(planning.plan!!.id)
        assertFalse(editor.project.customGfx.spritePalettes.containsKey("boss_kraid_health_bg"))
        assertTrue(editor.project.customGfx.spritePalettes.containsKey("boss_kraid_health_oam"))
    }

    @Test
    fun `related palette approval fails before any write when one target is stale`() {
        val editor = editor()
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace())
        val planning = controller.plan(
            "set_palette_colors",
            buildJsonObject {
                put("palette_edits", JsonArray(listOf(
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_bg")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                    buildJsonObject {
                        put("palette", "sprite:boss_kraid_health_oam")
                        put("changes", JsonArray(listOf(buildJsonObject {
                            put("index", 128)
                            put("color", "#FF0000")
                        })))
                    },
                )))
            },
        )
        val external = java.util.Base64.getEncoder().encodeToString(ByteArray(144 * 2) { 1 })
        editor.project.customGfx.spritePalettes["boss_kraid_health_oam"] = external

        assertFailsWith<IllegalArgumentException> { controller.approve(planning.plan!!.id) }
        assertFalse(editor.project.customGfx.spritePalettes.containsKey("boss_kraid_health_bg"))
        assertEquals(external, editor.project.customGfx.spritePalettes["boss_kraid_health_oam"])
    }

    @Test
    fun `item placement plans all state change and is undoable`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })
        val planning = controller.plan(
            "place_item",
            buildJsonObject {
                put("room_id", "0x91F8")
                put("plm_id", 0xEEDB)
                put("x", 4)
                put("y", 6)
                put("state_scope", "all_states")
            },
        )

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(SmeditMutationPlanKind.ITEM, planning.plan?.kind)
        assertTrue(editor.project.rooms.isEmpty(), "Planning must not create a room edit")

        controller.approve(planning.plan!!.id)
        val placed = editor.project.rooms.getValue("91F8").plmChanges.single()
        assertEquals(PlmChange("add", 0xEEDB, 4, 6, 0x51), placed)
        controller.undo()
        assertFalse(editor.project.rooms.containsKey("91F8"))
        controller.redo()
        assertEquals(placed, editor.project.rooms.getValue("91F8").plmChanges.single())
    }

    @Test
    fun `item placement fails closed when global collection parameters are unreadable`() {
        val editor = editor()
        val parser = RomParser(objectSourceTestRom())
        val controller = DesktopSmeditMutationController(
            editor,
            FakeWorkspace(unreadableItemRoomIds = setOf(0x9AD9)),
            parser = { parser },
        )

        val planning = controller.plan("place_item", buildJsonObject {
            put("room_id", "0x91F8")
            put("plm_id", 0xEEDB)
            put("x", 4)
            put("y", 6)
            put("state_scope", "all_states")
        })

        assertEquals("item_catalog_incomplete", planning.errorCode())
        assertTrue(editor.project.rooms.isEmpty())
    }

    @Test
    fun `single state room tile edit forks shared layout and is undoable`() {
        val editor = editor()
        val parser = RomParser(roomTileTestRom())
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })
        val planning = controller.plan("set_room_tiles", buildJsonObject {
            put("room_id", "0x91F8")
            put("state_scope", "single_state")
            put("state_id", "state-1")
            put("tile_edits", JsonArray(listOf(buildJsonObject {
                put("x", 2)
                put("y", 3)
                put("metatile_id", 7)
            })))
        })

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(SmeditMutationPlanKind.ROOM_TILES, planning.plan?.kind)
        assertEquals(listOf("state-1"), planning.plan?.roomTiles?.forkedStateIds)
        assertTrue(editor.project.rooms.isEmpty(), "Planning must not materialize the room model")

        controller.approve(planning.plan!!.id)
        val edited = editor.project.rooms.getValue("91F8")
        assertEquals("level-ai-1", edited.states.first().resources.level)
        assertEquals("level-1", edited.states.last().resources.level)
        assertEquals(0x8007, levelWord(editor.effectiveLevelDataForAi(edited, edited.states.first(), parser)!!, 2, 3))
        assertEquals(0x8003, levelWord(editor.effectiveLevelDataForAi(edited, edited.states.last(), parser)!!, 2, 3))

        controller.undo()
        assertFalse(editor.project.rooms.containsKey("91F8"))
        controller.redo()
        assertEquals("level-ai-1", editor.project.rooms.getValue("91F8").states.first().resources.level)
    }

    @Test
    fun `room tile writes reject conflicting duplicates and unavailable layer two`() {
        val editor = editor()
        val parser = RomParser(roomTileTestRom())
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })
        val duplicate = controller.plan("set_room_tiles", buildJsonObject {
            put("room_id", "91F8")
            put("state_scope", "all_states")
            put("tile_edits", JsonArray(listOf(
                buildJsonObject { put("x", 1); put("y", 1); put("metatile_id", 4) },
                buildJsonObject { put("x", 1); put("y", 1); put("bts", 2) },
            )))
        })
        assertEquals("invalid_arguments", duplicate.errorCode())

        val layer2 = controller.plan("set_room_tiles", buildJsonObject {
            put("room_id", "91F8")
            put("state_scope", "all_states")
            put("tile_edits", JsonArray(listOf(buildJsonObject {
                put("x", 1); put("y", 1); put("layer", 2); put("metatile_id", 4)
            })))
        })
        assertEquals("layer2_unavailable", layer2.errorCode())
        assertTrue(editor.project.rooms.isEmpty())
    }

    @Test
    fun `approved room tile changes refresh active canvas across undo and redo`() {
        val editor = editor()
        val parser = RomParser(roomTileTestRom())
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        val controller = DesktopSmeditMutationController(editor, FakeWorkspace(), parser = { parser })
        val planning = controller.plan("set_room_tiles", buildJsonObject {
            put("room_id", "91F8")
            put("state_scope", "all_states")
            put("tile_edits", JsonArray(listOf(buildJsonObject {
                put("x", 2); put("y", 3); put("metatile_id", 9)
            })))
        })

        controller.approve(planning.plan!!.id)
        assertEquals(0x8009, editor.readBlockWord(2, 3))
        controller.undo()
        assertEquals(0x8003, editor.readBlockWord(2, 3))
        assertFalse(editor.project.rooms.containsKey("91F8"))
        controller.redo()
        assertEquals(0x8009, editor.readBlockWord(2, 3))
    }

    private fun roomTileTestRom(): ByteArray = objectSourceTestRom().also { rom ->
        val header = 0x791F8
        rom[header + 4] = 1
        rom[header + 5] = 1
        val level = ByteArray(2 + 16 * 16 * 3)
        level[0] = 0x00
        level[1] = 0x02
        repeat(16 * 16) { index ->
            val offset = 2 + index * 2
            level[offset] = 0x03
            level[offset + 1] = 0x80.toByte()
        }
        val compressed = LZ5Compressor.compress(level)
        compressed.copyInto(rom, 0x100000)
        fun long(pc: Int, value: Int) {
            rom[pc] = value.toByte()
            rom[pc + 1] = (value ushr 8).toByte()
            rom[pc + 2] = (value ushr 16).toByte()
        }
        long(0x7920A, 0xA08000)
        long(0x79300, 0xA08000)
    }

    private fun levelWord(data: ByteArray, x: Int, y: Int): Int {
        val offset = 2 + (y * 16 + x) * 2
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun bgr555Hex(color: Int): String {
        fun expand(value: Int) = (value * 255 + 15) / 31
        return "#%02X%02X%02X".format(
            expand(color and 31), expand((color ushr 5) and 31), expand((color ushr 10) and 31),
        )
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
        overwriteAudioSensitiveMusic: Boolean? = null,
    ) = buildJsonObject {
        put("music_data", data)
        put("music_track", track)
        if (roomIds.isNotEmpty()) put("room_ids", JsonArray(roomIds.map(::JsonPrimitive)))
        if (excludeAreas.isNotEmpty()) put("exclude_areas", JsonArray(excludeAreas.map(::JsonPrimitive)))
        overwriteSpecialStateMusic?.let { put("overwrite_special_state_music", it) }
        overwriteAudioSensitiveMusic?.let { put("overwrite_audio_sensitive_music", it) }
    }

    private fun stateFieldArguments(
        roomId: String = "91F8",
        stateId: String = "state-1",
        fields: Map<String, Int>,
        overwriteAudioSensitiveMusic: Boolean? = null,
    ) = buildJsonObject {
        put("room_id", roomId)
        put("state_id", stateId)
        put("update_fields", JsonArray(fields.keys.map(::JsonPrimitive)))
        fields.forEach { (field, value) -> put(field, value) }
        overwriteAudioSensitiveMusic?.let { put("overwrite_audio_sensitive_music", it) }
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

    private fun fxArguments(
        liquidOptions: List<String> = listOf("bg_animate"),
        includeConditionalStates: Boolean,
        stateTargets: List<String> = emptyList(),
    ) = buildJsonObject {
        put(
            "update_fields",
            JsonArray(
                listOf("fx_type", "liquid_surface_start", "liquid_surface_new", "liquid_options")
                    .map(::JsonPrimitive),
            ),
        )
        put("room_ids", JsonArray(listOf(JsonPrimitive("91F8"))))
        put("only_if_fx_types", JsonArray(listOf(JsonPrimitive(0))))
        put("include_conditional_states", includeConditionalStates)
        put("fx_type", 2)
        put("liquid_surface_start", 0xFFFF)
        put("liquid_surface_new", 0xFFFF)
        put("liquid_options", JsonArray(liquidOptions.map(::JsonPrimitive)))
        if (stateTargets.isNotEmpty()) put("state_targets", JsonArray(stateTargets.map(::JsonPrimitive)))
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
        includeTourianRoom: Boolean = false,
        private val unreadableItemRoomIds: Set<Int> = emptySet(),
        private val roomAreaOverrides: Map<Int, String> = emptyMap(),
    ) : SmeditWorkspaceAccess {
        private val roomList = buildList {
            add(SmeditRoomSummary("0x91F8", "Landing Site", area = roomAreaOverrides[0x91F8] ?: "Crateria"))
            add(SmeditRoomSummary("0x9AD9", "Green Brinstar", area = roomAreaOverrides[0x9AD9] ?: "Brinstar"))
            add(SmeditRoomSummary("0xDF45", "Ceres Elevator", area = roomAreaOverrides[0xDF45] ?: "Ceres"))
            if (includeTourianRoom) add(SmeditRoomSummary("0xDCB1", "Baby Metroid Room", area = "Tourian"))
        }
        private val stateCounts = buildMap {
            put(0x91F8, 2)
            put(0x9AD9, 3)
            put(0xDF45, 4)
            if (includeTourianRoom) put(0xDCB1, 2)
        }

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
                states = List(count) { index ->
                    SmeditRoomStateDetails(
                        id = "state-${index + 1}",
                        index = index,
                        sourceIndex = index,
                        condition = if (index == count - 1) "Standard (default)" else "State $index",
                        conditionKind = if (index == count - 1) "DEFAULT" else "EVENT_SET",
                        defaultState = index == count - 1,
                        readable = true,
                        projectEdited = false,
                        projectFxEdited = false,
                        fxSourceManaged = false,
                        tileset = 0,
                        musicData = if (roomId in distinctMusicRoomIds && index == 0) 0x24 else 0,
                        musicTrack = if (roomId in distinctMusicRoomIds && index == 0) 3 else 0,
                        bgScrolling = 0,
                    )
                },
            )
        }
        override fun patches() = emptyList<SmeditPatchSummary>()
        override fun patch(identifier: String): SmeditPatchDetails? = null
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
            SmeditPaletteSummary(
                id = "sprite:boss_kraid_health_oam",
                name = "Kraid (health stages · OAM)",
                kind = "sprite",
                category = "bosses",
                description = "Kraid OAM palettes",
                colorCount = 144,
                rowCount = 9,
                editable = true,
                sourceManaged = false,
                modified = false,
            ),
            SmeditPaletteSummary(
                id = "sprite:boss_phantoon",
                name = "Phantoon",
                kind = "sprite",
                category = "bosses",
                description = "Phantoon base palettes",
                colorCount = 32,
                rowCount = 2,
                editable = true,
                sourceManaged = false,
                modified = false,
                relatedPaletteIds = listOf("sprite:boss_phantoon_health"),
            ),
            SmeditPaletteSummary(
                id = "sprite:boss_phantoon_health",
                name = "Phantoon (health stages)",
                kind = "sprite",
                category = "bosses",
                description = "Phantoon runtime health palettes",
                colorCount = 128,
                rowCount = 8,
                editable = true,
                sourceManaged = false,
                modified = false,
                relatedPaletteIds = listOf("sprite:boss_phantoon"),
            ),
        )
        override fun palette(identifier: String): SmeditPaletteDetails? = palettes().singleOrNull {
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
                relatedPaletteIds = summary.relatedPaletteIds,
                colorsBgr555 = List(summary.colorCount) { 0 },
                colorsRgbHex = List(summary.colorCount) { "#000000" },
            )
        }
        override fun itemTypes() = listOf(SmeditItemType("Missile", "Visible", 0xEEDB, "0xEEDB"))
        override fun roomItems(roomId: Int): SmeditRoomItemsDetails? {
            if (roomId in unreadableItemRoomIds) return null
            val room = room(roomId) ?: return null
            return SmeditRoomItemsDetails(
                roomId = room.id,
                roomName = room.name,
                widthBlocks = room.widthScreens * 16,
                heightBlocks = room.heightScreens * 16,
                sourceManaged = false,
                states = room.states.map { state ->
                    SmeditRoomItemState(state.id, state.index, state.condition, emptyList())
                },
            )
        }
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
