package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmDataPatchSource

import com.supermetroid.editor.asm.AsmBuildArtifactRepository

import com.supermetroid.editor.asm.AsmProjectTestFixture
import com.supermetroid.editor.asm.AsmAssetManifest
import com.supermetroid.editor.asm.AsmWlaSymbolParser
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmPlmSourceBridge
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.AsmRoomObjectSource
import com.supermetroid.editor.asm.PLM_SOURCE_FILE
import com.supermetroid.editor.asm.FX_SOURCE_FILE
import com.supermetroid.editor.asm.RoomAsmSourceKind
import com.supermetroid.editor.asm.RoomAsmSourceRequest
import com.supermetroid.editor.asm.authoredMacroBody
import kotlinx.coroutines.runBlocking
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.rom.words
import java.io.File
import java.util.Base64
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** Explicit clean ROM/source fixtures; every project and compiler artifact lives in @TempDir. */
class AsmBuildPipelineIntegrationTest {
    @TempDir lateinit var tempDirectory: File

    @Test fun `fast doors curated module matches IPS preserves edits and follows checked symbols`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val patch = com.supermetroid.editor.data.PatchRepository.loadBundledPatches().single { it.id == "bundled_fast_doors" }
        project.patches += patch
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val file = com.supermetroid.editor.asm.AsmFastDoorsModule.FILE
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project, input: RomParser = parser): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(owner, input, logs::add, logs::add, fixture.projectFile.path)
        }
        fun same(a: ByteArray, b: ByteArray) = assertEquals(com.supermetroid.editor.asm.AsmVerifiedCache.hash(a), com.supermetroid.editor.asm.AsmVerifiedCache.hash(b))
        same(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertFalse(editor.fastDoorsBridge!!.installed)
        editor.togglePatch(patch.id)
        assertTrue(patch.enabled, editor.statusMessage)
        val first = assertNotNull(build(), logs.joinToString("\n"))
        val oracleProject = SmEditProject(fixture.romFile.path).apply { patches += patch.copy() }
        val oracle = assertNotNull(RomExporter(oracleProject, parser).build())
        // Asar recalculates the internal checksum; compare every game byte with the legacy IPS.
        for (i in fixture.rom.indices) if (i !in 0x7FDC..0x7FDF) assertEquals(oracle.resultRom[i], first.resultRom[i], "PC ${i.toString(16)}")
        assertTrue(first.writeReport.writes.none { it.owner == "patch:${patch.id}" && it.kind == RomWriteKind.FIXED_PATCH })
        assertTrue(editor.openFastDoorsSource())
        assertEquals(file, workspace.selectedFileId)
        assertTrue(workspace.sourceEditorLinks!!(file, workspace.selectedLineIndex).any { it.target is com.supermetroid.editor.asm.AsmEditorTarget.CodePatch })
        val custom = workspace.projectSourceText(file).replace("LDA.W #\$0002", "LDA.W #\$0003 ; custom speed")
            .replace("Freespace_Bank80_CD8E+\$0372", "Freespace_Bank80_CD8E+\$0400")
            .replace("Freespace_Bank82_F70F+\$0671", "Freespace_Bank82_F70F+\$0700")
        assertTrue(workspace.updateSourceEditText(file, custom))
        val moved = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0xD18E, RomParser(moved.resultRom).readUInt16At(0x2E5D))
        assertEquals(0xFE0F, RomParser(moved.resultRom).readUInt16At(0x1632C))
        assertEquals(0xFF, moved.resultRom[0x5100].toInt() and 255, "Old helper bytes are not patched")
        editor.togglePatch(patch.id)
        assertFalse(patch.enabled, editor.statusMessage)
        assertContains(workspace.projectSourceText(file), "#\$0003 ; custom speed")
        same(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(workspace.undoSourceEdit(file)); assertTrue(patch.enabled)
        same(moved.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(workspace.redoSourceEdit(file)); assertFalse(patch.enabled)
        assertTrue(workspace.saveAllSources())
        assertTrue(ProjectFileService.saveProject(project, fixture.projectFile.path, null, false) {})
        ProjectFileService.clearAsmBuildCachesForTests()
        val cold = ProjectFileService.loadProject(fixture.projectFile)
        same(fixture.rom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        editor.togglePatch(patch.id)
        val header = ByteArray(512) { (it % 251).toByte() }
        val headered = assertNotNull(build(input = RomParser(header + fixture.rom)), logs.joinToString("\n"))
        assertContentEquals(header, headered.resultRom.copyOfRange(0, 512))
        same(moved.resultRom, headered.resultRom.copyOfRange(512, headered.resultRom.size))
        // Relocate both native consumers as well, not just the new helpers.
        for ((bank, root, next, address, restore) in listOf(
            listOf("bank_80.asm", "DoorTransitionScrolling:", ";;; \$AE7E:", "80D300", "80AE7E"),
            listOf("bank_82.asm", "DoorTransitionFunction_ScrollScreenToAlignment:", ";;; \$E353:", "82F800", "82E353"))) {
            val text = workspace.projectSourceText(bank)
            val start = text.indexOf(root); val end = text.indexOf(next, start)
            assertTrue(workspace.updateSourceEditText(bank, text.replaceRange(start until end,
                "org \$$address\n" + text.substring(start, end) + "org \$$restore\n")))
        }
        val relocated = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0x20, relocated.resultRom[0x530E].toInt() and 255)
        assertEquals(0xD18E, RomParser(relocated.resultRom).readUInt16At(0x530F))
        assertEquals(0xEA, relocated.resultRom[0x17815].toInt() and 255)
        assertEquals(0xFF, relocated.resultRom[0x16325].toInt() and 255)
        assertTrue(workspace.createProjectModule("fast_doors_collision"), workspace.error)
        val collisionFile = "project/fast_doors_collision.asm"
        assertTrue(workspace.updateSourceEditText(collisionFile, "org \$80D18E\ndb \$DA ; same byte as the helper, still a competing allocation\n"))
        assertNull(build()); assertTrue(logs.any { it.contains("another authored source allocation") }, logs.joinToString("\n"))
        assertTrue(workspace.deleteProjectModule(collisionFile), workspace.error)
        // Full ownership includes an unchanged NOP byte, not just ROM diffs.
        val conflict = SmPatch("fast-doors-conflict", "Competing hook", enabled = true,
            writes = mutableListOf(PatchWrite(0x17815, listOf(0xEA))))
        project.patches += conflict
        assertNull(build()); assertTrue(logs.any { it.contains("conflict", true) || it.contains("overlap", true) }, logs.joinToString("\n"))
        project.patches.remove(conflict)
        val bank80 = workspace.projectSourceText("bank_80.asm")
        val start = bank80.indexOf("DoorTransitionScrolling:")
        assertTrue(workspace.updateSourceEditText("bank_80.asm", bank80.replaceRange(start, start + "DoorTransitionScrolling:".length,
            "DoorTransitionScrolling:\n    NOP")))
        assertNull(build()); assertTrue(logs.any { it.contains("native hook routine") }, logs.joinToString("\n"))
        assertTrue(workspace.undoSourceEdit("bank_80.asm"))
        val valid = workspace.projectSourceText(file)
        assertTrue(workspace.updateSourceEditText(file, valid.replace("JSR.W SMEDIT_FastDoors_ScrollTwice", "JSR.W DoorTransitionScrolling_Right")))
        assertNull(build()); assertTrue(logs.any { it.contains("compiled hook/consumer") }, logs.joinToString("\n"))
        assertTrue(workspace.undoSourceEdit(file))
        assertTrue(workspace.updateSourceEditText(file, valid.replace("\nendif", "\norg \$DFFFF0\ndb \$42\nendif")))
        assertNull(build()); assertTrue(logs.any { it.contains("outside its checked hooks/helper spans") }, logs.joinToString("\n"))
        assertTrue(workspace.undoSourceEdit(file))
        assertTrue(workspace.updateSourceEditText(file, valid.replace("Enabled = 1", "Enabled = 0")))
        assertFalse(patch.enabled)
        assertTrue(workspace.undoSourceEdit(file)); assertTrue(patch.enabled)
        val romOnly = project.copy(asmWorkspace = project.asmWorkspace.copy(buildMode = ProjectRomBuildMode.PATCHED_ROM))
        assertNull(build(romOnly)); assertTrue(logs.any { it.contains("requires ASM source mode") || it.contains("cannot ignore saved ASM") }, logs.joinToString("\n"))
        same(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `short charge packed timers and counter consumers survive native relocation cold builds and reversible toggles`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory); val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val bridge = assertNotNull(editor.dataPatchBridge)
        val type = SHORT_CHARGE_CONFIG_TYPE; val id = "$type:stages"
        assertEquals(4, bridge.view(type, "stages").value); assertNull(bridge.view(type, "stages").problem)
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        fun same(expected: ByteArray, actual: ByteArray) = assertEquals(com.supermetroid.editor.asm.AsmVerifiedCache.hash(expected), com.supermetroid.editor.asm.AsmVerifiedCache.hash(actual))
        same(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val before = workspace.projectSourceText("bank_91.asm")
        val timerSymbol = assertNotNull(AsmDataPatchSource.load(fixture.workspace.originalDirectory))
            .symbolNames.active("SpeedBoostTimerResetValues")
        val start = before.indexOf("$timerSymbol:"); val end = before.indexOf("PoseDefinitions:", start)
        assertTrue(start >= 0 && end > start, "The active timer table must be present before relocating it")
        val movedSnes = 0x91FFEE; val movedPc = parser.snesToPc(movedSnes)
        assertTrue(fixture.rom.copyOfRange(movedPc, movedPc + 10).all { it == 0xFF.toByte() })
        assertTrue(workspace.updateSourceEditText("bank_91.asm", before.replaceRange(start until end,
            "org \$91FFEE\n" + before.substring(start, end) + "org \$91B629\n")))
        assertTrue(project.asmWorkspace.patchSourceKeys.isEmpty(), "Relocation alone does not adopt values")
        val patch = editor.findOrCreateConfigPatch(type).apply { enabled = true; configValue = 2 }
        val pending = assertNotNull(build(), logs.joinToString("\n")); val pendingRom = RomParser(pending.resultRom)
        assertEquals(0x0201, pendingRom.readUInt16At(movedPc))
        assertEquals(0xFFFF, pendingRom.readUInt16At(parser.snesToPc(SHORT_CHARGE_INITIAL_TIMER_SNES)))
        assertEquals(0xFFEE, pendingRom.readUInt16At(parser.snesToPc(0x9097A3)))
        assertEquals(0xFFEE, pendingRom.readUInt16At(parser.snesToPc(0x9085B7)))
        assertEquals(0xFFEE, pendingRom.readUInt16At(parser.snesToPc(0x91E680)))
        editor.setPatchConfigValue(patch.id, 1); assertNull(patch.configValue); assertNull(patch.configData)
        assertEquals(setOf(id), project.asmWorkspace.patchSourceKeys)
        val field = bridge.catalog.field(id, bridge.texts())
        var custom = workspace.projectSourceText("bank_91.asm")
        custom = custom.replaceRange(field.words[3].span, "\$0009")
        custom = custom.replaceRange(field.words[0].span, "\$0307")
        assertTrue(workspace.updateSourceEditText("bank_91.asm", custom))
        editor.setPatchConfigValue(patch.id, 0)
        assertEquals(listOf(0x0407, 1, 1, 9, 2), bridge.catalog.field(id, bridge.texts()).values)
        val compiled = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0x0407, RomParser(compiled.resultRom).readUInt16At(movedPc))
        assertEquals(9, RomParser(compiled.resultRom).readUInt16At(movedPc + 6))
        val json = kotlinx.serialization.json.Json
        val cold = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        same(compiled.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        val header = ByteArray(512) { 0x5A }; val headerFile = File(tempDirectory, "Charge.smc").apply { writeBytes(header + fixture.rom) }
        logs.clear()
        val headered = assertNotNull(ProjectFileService.buildRom(cold.copy(romPath = headerFile.path), RomParser(header + fixture.rom), logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        same(header + compiled.resultRom, headered.resultRom)
        editor.requestTogglePatch(patch.id); assertNotNull(editor.pendingPatchDisable); editor.confirmPatchDisable(); assertFalse(patch.enabled)
        val disabled = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(listOf(1, 1, 1, 1, 2), (0..4).map { RomParser(disabled.resultRom).readUInt16At(movedPc + it * 2) })
        val saved = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        same(disabled.resultRom, assertNotNull(build(saved), logs.joinToString("\n")).resultRom)
        editor.requestTogglePatch(patch.id); assertTrue(patch.enabled)
        same(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val conflict = SmPatch("charge-timer-byte", "Unchanged timer byte", enabled = true,
            writes = mutableListOf(PatchWrite((movedPc + 3).toLong(), listOf(0), expectedBytes = listOf(0))))
        project.patches.add(conflict); assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        val valid = workspace.projectSourceText("bank_90.asm")
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid.replaceFirst("ADC.W #\$0100", "ADC.W #\$0200")))
        assertNull(build()); assertTrue(logs.any { "Short Charge" in it && "logic changed" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid))
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid + "\norg Handle_SpeedBooster_AnimationDelay+\$64\ndb \$00\n"))
        assertNull(build()); assertTrue(logs.any { "anonymous branch binding changed" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid))
        val unsupported = bridge.catalog.field(id, bridge.texts()).words.first()
        assertTrue(workspace.updateSourceEditText("bank_91.asm", workspace.projectSourceText("bank_91.asm").replaceRange(unsupported.span, "\$0400")))
        assertNull(build()); assertTrue(logs.any { "unsupported native counter/timer" in it }, logs.joinToString("\n"))
        assertNull(RomExporter(project, parser, logs::add).build(), "ROM-only export cannot omit authored charge timing")
        same(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `physics native fields preserve complete storage through pending relocation reciprocal edits and cold toggles`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory); val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val bridge = assertNotNull(editor.dataPatchBridge); val type = com.supermetroid.editor.asm.PHYSICS_CONFIG_TYPE
        val definitions = com.supermetroid.editor.asm.PHYSICS_PATCH_DEFINITIONS
        val edits = ALL_PHYSICS_FIELDS.associate { it.key to it.defaultValue + 1 } + mapOf("air_physics" to 4)
        val patch = editor.findOrCreateConfigPatch(type).apply { enabled = true; configData = edits.toMutableMap() }
        val source = workspace.projectSourceText("bank_90.asm")
        val speedStart = source.indexOf("SamusXSpeedTable_Normal:")
        val speedEnd = source.indexOf("SamusXSpeedTable_InWater:", speedStart)
        var moved = source.replaceRange(speedStart until speedEnd, "org \$90F800\n" + source.substring(speedStart, speedEnd) + "org \$90A08D\n")
        val jumpStart = moved.indexOf("  .InitialYSpeeds_Jumping:")
        val jumpEnd = moved.indexOf("  .InitialYSubSpeeds_Jumping:", jumpStart)
        moved = moved.replaceRange(jumpStart until jumpEnd, "org \$90FE00\n" + moved.substring(jumpStart, jumpEnd) + "org \$909EBF\n")
        assertTrue(workspace.updateSourceEditText("bank_90.asm", moved), workspace.error)
        assertTrue(project.asmWorkspace.patchSourceKeys.isEmpty(), "Relocation does not adopt values")
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val pending = assertNotNull(build(), logs.joinToString("\n"))
        fun pc(definition: com.supermetroid.editor.asm.DataPatchDefinition): Int {
            val site = definition.sites.single()
            return parser.snesToPc(when (site.symbol) {
                "SamusXSpeedTable_Normal" -> 0x90F800 + site.index * site.size
                "SamusPhysicsConstants_InitialYSpeeds_Jumping" -> 0x90FE00 + site.index * site.size
                else -> site.native
            })
        }
        fun raw(bytes: ByteArray, at: Int, size: Int) = (0 until size).fold(0) { value, index -> value or ((bytes[at + index].toInt() and 255) shl (8 * index)) }
        for (definition in definitions) {
            val original = bridge.catalog.original(definition.id).values.single()
            assertEquals(definition.encode(edits.getValue(definition.key), original), raw(pending.resultRom, pc(definition), definition.sites.single().size), definition.id)
        }
        assertEquals(255, pending.resultRom[0x81F64].toInt() and 255, "Pending running edits cannot use the old byte offsets")
        assertEquals(0xF800, RomParser(pending.resultRom).readUInt16At(parser.snesToPc(0x9497D1)), "Cross-bank pointer follows the moved table")
        assertEquals(0xFE00, RomParser(pending.resultRom).readUInt16At(parser.snesToPc(0x909906)))
        editor.setNativePatchValues(patch.id, edits); assertNull(patch.configData)
        assertEquals(definitions.map { it.id }.toSet(), project.asmWorkspace.patchSourceKeys)
        val adopted = assertNotNull(build(), logs.joinToString("\n")).resultRom
        // Asar calculates its header checksum before legacy pending writes are applied.
        assertNull(pending.resultRom.indices.firstOrNull { it !in 0x7FDC..0x7FDF && pending.resultRom[it] != adopted[it] },
            "Pending and source-adopted physics must produce the same game bytes")
        val rawSource = workspace.projectSourceText("bank_90.asm")
        val gravity = bridge.catalog.field("$type:gravity", bridge.texts()).words.single()
        assertTrue(workspace.updateSourceEditText("bank_90.asm", rawSource.replaceRange(gravity.span, "\$2A35")))
        assertEquals(42, bridge.view(type, "gravity").value)
        val compiled = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0x2A35, RomParser(compiled.resultRom).readUInt16At(0x81EA1))
        val json = kotlinx.serialization.json.Json
        val cold = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(compiled.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        val header = ByteArray(512) { 0x5A }; val headerFile = File(tempDirectory, "Physics.smc").apply { writeBytes(header + fixture.rom) }
        logs.clear()
        val headered = assertNotNull(ProjectFileService.buildRom(cold.copy(romPath = headerFile.path), RomParser(header + fixture.rom), logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(header + compiled.resultRom, headered.resultRom)
        editor.requestTogglePatch(patch.id); assertNotNull(editor.pendingPatchDisable); editor.confirmPatchDisable(); assertFalse(patch.enabled, editor.statusMessage)
        val disabled = assertNotNull(build(), logs.joinToString("\n"))
        for (definition in definitions) assertEquals(bridge.catalog.original(definition.id).values.single(), raw(disabled.resultRom, pc(definition), definition.sites.single().size), definition.id)
        val disabledCold = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(disabled.resultRom, assertNotNull(build(disabledCold), logs.joinToString("\n")).resultRom)
        editor.requestTogglePatch(patch.id); assertTrue(patch.enabled, editor.statusMessage)
        assertContentEquals(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val conflict = SmPatch("physics-word", "Unchanged running fractional byte", enabled = true,
            writes = mutableListOf(PatchWrite((0x87800 + 4 * 4 + 2).toLong(), listOf(0), expectedBytes = listOf(0))))
        project.patches.add(conflict); assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        val valid = workspace.projectSourceText("bank_94.asm")
        assertTrue(workspace.updateSourceEditText("bank_94.asm", valid.replaceFirst("#SamusXSpeedTable_Normal", "#SamusXSpeedTable_InWater")))
        assertNull(build()); assertTrue(logs.any { "binding changed" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_94.asm", valid))
        assertNull(RomExporter(project, parser, logs::add).build(), "Owned physics cannot silently fall back to fixed ROM writes")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `pending timer and bomb overrides follow relocated source and reject consumer rebinding`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory); val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        editor.findOrCreateConfigPatch("ceres_escape_seconds").apply { enabled = true; configValue = 120 }
        editor.findOrCreateConfigPatch(BOMB_CONFIG_TYPE).apply { enabled = true; configData = mutableMapOf(BOMB_FUSE_FRAMES_KEY to 80, BOMB_COOLDOWN_FRAMES_KEY to 12) }
        editor.findOrCreateConfigPatch(FANFARE_CONFIG_TYPE).apply { enabled = true; configData = mutableMapOf(FANFARE_FRAMES_KEY to 200) }
        editor.findOrCreateConfigPatch("controller_config").apply { enabled = true; configData = mutableMapOf("jump" to 0x4000) }
        for ((snes, count) in listOf(0x80FF00 to 24, 0x90FF00 to 9, 0x90FF20 to 2)) {
            val pc = parser.snesToPc(snes); assertTrue(fixture.rom.copyOfRange(pc, pc + count).all { it == 0xFF.toByte() })
        }
        val timer = workspace.projectSourceText("bank_80.asm")
        val start = timer.indexOf("ProcessTimer_CeresStart:"); val end = timer.indexOf("ProcessTimer_MotherBrainStart:")
        assertTrue(workspace.updateSourceEditText("bank_80.asm", timer.replaceRange(start until end,
            "org ${'$'}80FF00\n" + timer.substring(start, end).replace("ProcessTimer_CeresStart:\n", "ProcessTimer_CeresStart:\n NOP\n") + "org ${'$'}809E1C\n")))
        val bombs = workspace.projectSourceText("bank_90.asm")
        val tableStart = bombs.indexOf("  .NonBeamProjectiles:", bombs.indexOf("ProjectileCooldowns:")); val tableEnd = bombs.indexOf("BeamAutoFireCooldowns:", tableStart)
        var moved = bombs.replaceRange(tableStart until tableEnd, "org ${'$'}90FF00\n" + bombs.substring(tableStart, tableEnd) + "org ${'$'}90C283\n")
        val fuseStart = moved.indexOf("BombTimerResetValue:"); val fuseEnd = moved.indexOf("HUDSelectionHandler_MorphBall:", fuseStart)
        moved = moved.replaceRange(fuseStart until fuseEnd, "org ${'$'}90FF20\n" + moved.substring(fuseStart, fuseEnd) + "org ${'$'}90BF9D\n")
        assertTrue(workspace.updateSourceEditText("bank_90.asm", moved), workspace.error)
        assertTrue(project.asmWorkspace.patchSourceKeys.isEmpty(), "Address/layout edits alone don't adopt numerical values")
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val result = assertNotNull(build(), logs.joinToString("\n")); val rom = RomParser(result.resultRom)
        assertEquals(0x0200, rom.readUInt16At(0x7F06)); assertEquals(65535, rom.readUInt16At(0x1E0E))
        assertEquals(80, rom.readUInt16At(0x87F20)); assertEquals(12, result.resultRom[0x87F05].toInt() and 255)
        assertEquals(65535, rom.readUInt16At(BOMB_FUSE_TIMER_PC)); assertEquals(255, result.resultRom[BOMB_COOLDOWN_PC].toInt() and 255)
        assertEquals(0xFF20, rom.readUInt16At(0x83FFC), "Fuse consumer follows the moved symbol")
        assertEquals(0xFF00, rom.readUInt16At(0x84012), "Cooldown consumer follows the moved table")
        for (pc in FANFARE_MUSIC_RESUME_DELAY_PCS + FANFARE_MESSAGE_BOX_WAIT_PC) assertEquals(200, rom.readUInt16At(pc))
        assertEquals(0x4000, com.supermetroid.editor.rom.ControllerBindings.read(result.resultRom)["jump"])
        assertEquals(0x80, com.supermetroid.editor.rom.ControllerBindings.read(result.resultRom, reset = true)["item_cancel"])
        val conflict = SmPatch("pending-high-byte", "Unchanged pending fuse high byte", enabled = true,
            writes = mutableListOf(PatchWrite(0x87F21, listOf(0), expectedBytes = listOf(0))))
        project.patches.add(conflict)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        editor.setPatchConfigData(editor.findOrCreateConfigPatch(BOMB_CONFIG_TYPE).id, BOMB_FUSE_FRAMES_KEY, 75)
        assertEquals(75, assertNotNull(editor.dataPatchBridge).view(BOMB_CONFIG_TYPE, BOMB_FUSE_FRAMES_KEY).value)
        val valid = workspace.projectSourceText("bank_90.asm")
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid.replaceFirst("LDA.W BombTimerResetValue", "LDA.W SamusProjectile_CooldownTimer")))
        assertNull(build()); assertTrue(logs.any { "consumers changed" in it }, logs.joinToString("\n"))
    }

    @Test fun `native timers bombs fanfares and controller round trip real source builds and cold headered toggles`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory); val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val bridge = assertNotNull(editor.dataPatchBridge)
        val definitions = com.supermetroid.editor.asm.DATA_PATCH_DEFINITIONS
        assertEquals(definitions.size, bridge.catalog.definitions.size)
        for (def in definitions) {
            val field = bridge.catalog.original(def.id)
            assertNull(bridge.view(def.type, def.key).problem, "${def.id}: ${bridge.view(def.type, def.key).problem}")
            for (word in field.words) {
                val pc = parser.snesToPc(word.site.native)
                val actual = (0 until word.site.size).fold(0) { value, index -> value or ((fixture.rom[pc + index].toInt() and 255) shl (index * 8)) }
                assertEquals(actual, word.value, "${def.id}: ${word.site.symbol}")
            }
        }
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        assertContentEquals(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val edits = mapOf(
            "ceres_escape_seconds" to mapOf("seconds" to 95), ZEBES_ESCAPE_CONFIG_TYPE to mapOf("seconds" to 305),
            BOMB_CONFIG_TYPE to mapOf(BOMB_MAX_ACTIVE_KEY to 4, BOMB_FUSE_FRAMES_KEY to 70, BOMB_EXPLOSION_FRAME_DELAY_KEY to 3),
            FANFARE_CONFIG_TYPE to mapOf(FANFARE_FRAMES_KEY to 240), "controller_config" to mapOf("jump" to 0x4000),
        )
        val patches = edits.map { (type, values) -> editor.findOrCreateConfigPatch(type).also { editor.setNativePatchValues(it.id, values) } }
        patches.forEach { assertTrue(it.enabled, editor.statusMessage); assertNull(it.configData); assertNull(it.configValue) }
        val compiled = assertNotNull(build(), logs.joinToString("\n")); val rom = RomParser(compiled.resultRom)
        assertEquals(0x0135, rom.readUInt16At(0x1E0E)); assertEquals(0x0505, rom.readUInt16At(0x1E21))
        assertEquals(4, rom.readUInt16At(BOMB_ACTIVE_HARD_CAP_OPERAND_PC)); assertEquals(70, rom.readUInt16At(BOMB_FUSE_TIMER_PC))
        assertEquals(16, compiled.resultRom[BOMB_COOLDOWN_PC].toInt() and 255, "70 frame fuse reaches four bombs at the stock cooldown")
        assertEquals(3, rom.readUInt16At(BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC))
        for (pc in FANFARE_MUSIC_RESUME_DELAY_PCS + FANFARE_MESSAGE_BOX_WAIT_PC) assertEquals(240, rom.readUInt16At(pc))
        val controls = com.supermetroid.editor.rom.ControllerBindings
        for (reset in listOf(false, true)) {
            val mapping = controls.read(compiled.resultRom, reset)
            assertEquals(0x4000, mapping["jump"]); assertEquals(0x80, mapping["item_cancel"])
        }
        assertContentEquals(fixture.rom.copyOfRange(0x17575, 0x17587), compiled.resultRom.copyOfRange(0x17575, 0x17587))
        for (patch in patches) { editor.requestTogglePatch(patch.id); assertNotNull(editor.pendingPatchDisable); editor.confirmPatchDisable(); assertFalse(patch.enabled, editor.statusMessage) }
        assertContentEquals(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val json = kotlinx.serialization.json.Json
        val disabled = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(fixture.rom, assertNotNull(build(disabled), logs.joinToString("\n")).resultRom)
        for (patch in patches) { editor.requestTogglePatch(patch.id); assertTrue(patch.enabled, editor.statusMessage) }
        assertContentEquals(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val header = ByteArray(512) { 0x5A }; val file = File(tempDirectory, "HeaderedNative.smc").apply { writeBytes(header + fixture.rom) }
        logs.clear()
        val headered = assertNotNull(ProjectFileService.buildRom(project.copy(romPath = file.path), RomParser(header + fixture.rom), logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(header, headered.resultRom.copyOfRange(0, 512))
        assertContentEquals(compiled.resultRom, headered.resultRom.copyOfRange(512, headered.resultRom.size))
        val valid = workspace.projectSourceText("bank_80.asm")
        assertTrue(workspace.updateSourceEditText("bank_80.asm", valid.replaceFirst("#${'$'}0135", "#${'$'}0160")))
        assertNull(build()); assertTrue(logs.any { "BCD" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_80.asm", valid))
        val conflicting = SmPatch("native-byte", "Unchanged timer high byte", enabled = true, writes = mutableListOf(PatchWrite(0x1E0F, listOf(1), expectedBytes = listOf(1))))
        project.patches.add(conflicting)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflicting.enabled = false
        assertNull(RomExporter(project, parser, logs::add).build(), "No native source fallback in ROM-only exporter")
    }

    @Test fun `environmental rates follow relocated tables preserve exact fractions and protect cold headered builds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val bridge = assertNotNull(editor.environmentalDamageBridge)
        val model = com.supermetroid.editor.rom.EnvironmentalDamagePatch
        for (field in model.FIELDS) {
            val source = bridge.catalog.original(field.key)
            assertEquals(parser.readUInt16At(field.lowWordPc), source.words[0].value, field.key)
            assertEquals(parser.readUInt16At(field.highWordPc), source.words[1].value, field.key)
        }
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        assertContentEquals(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val patch = editor.findOrCreateConfigPatch(model.CONFIG_TYPE).apply { enabled = true
            configData = mutableMapOf(model.HEAT_KEY to 20, model.LAVA_KEY to 40, model.ACID_KEY to 120) }
        val pending = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertEquals(model.encodeEnergyPerSecond(20).lowWord, pending.readUInt16At(0x6E386))
        assertEquals(model.encodeEnergyPerSecond(40).lowWord, pending.readUInt16At(0x81E8B))
        assertEquals(2, pending.readUInt16At(0x81E91))
        val liquid = workspace.projectSourceText("bank_90.asm")
        val record = assertNotNull(Regex("(?ms)^  \\.LavaSubDamagePerFrame:.*?(?=^  \\.AcidSubDamagePerFrame:)").find(liquid))
        val movedPc = parser.snesToPc(0x90FF00)
        assertTrue(fixture.rom.copyOfRange(movedPc, movedPc + 4).all { it == 0xFF.toByte() })
        assertTrue(workspace.updateSourceEditText("bank_90.asm", liquid.replaceRange(record.range, "org ${'$'}90FF00\n${record.value}org ${'$'}909E8F\n")))
        val relocated = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertEquals(model.encodeEnergyPerSecond(40).lowWord, relocated.readUInt16At(movedPc))
        assertEquals(65535, relocated.readUInt16At(0x81E8B), "A pending override cannot write the old table address")
        assertEquals(0xFF00, relocated.readUInt16At(parser.snesToPc(0x9081FC)))
        editor.setPatchConfigData(patch.id, model.HEAT_KEY, 0)
        editor.setPatchConfigData(patch.id, model.LAVA_KEY, 45)
        editor.setPatchConfigData(patch.id, model.ACID_KEY, 90)
        assertNull(patch.configData)
        assertEquals(model.FIELDS.map { com.supermetroid.editor.asm.environmentalSourceKey(it.key) }.toSet(), project.asmWorkspace.patchSourceKeys)
        val acid = bridge.catalog.field(model.ACID_KEY, bridge.texts())
        assertTrue(workspace.updateSourceEditText(acid.file, workspace.projectSourceText(acid.file).replaceRange(acid.words[0].span, "${'$'}0001")))
        assertContains(bridge.view(model.ACID_KEY).display, "60.00091552734375")
        assertNotNull(bridge.view(model.ACID_KEY).problem)
        val suitPatch = project.patches.single { it.id == "hex_gravity_no_heat_protect" }.apply { enabled = true }
        val compiled = assertNotNull(build(), logs.joinToString("\n"))
        val result = RomParser(compiled.resultRom)
        assertEquals(0, result.readUInt16At(0x6E386)); assertEquals(0, result.readUInt16At(0x6E38F))
        assertEquals(0xC000, result.readUInt16At(movedPc)); assertEquals(0, result.readUInt16At(movedPc + 2))
        assertEquals(1, result.readUInt16At(0x81E8F)); assertEquals(1, result.readUInt16At(0x81E91))
        assertEquals(1, result.readUInt16At(0x6E37D), "The independent heat-protection suit patch remains compatible")
        assertTrue(suitPatch.enabled)
        assertTrue(compiled.writeReport.writes.any { it.offset == 0x81E91 && it.bytes.size == 2 && "bank_90.asm" in it.label })
        editor.requestTogglePatch(patch.id)
        assertNotNull(editor.pendingPatchDisable)
        editor.confirmPatchDisable()
        assertFalse(patch.enabled, editor.statusMessage)
        val disabled = assertNotNull(build(), logs.joinToString("\n"))
        val disabledRom = RomParser(disabled.resultRom)
        assertEquals(0x4000, disabledRom.readUInt16At(0x6E386))
        assertEquals(0x8000, disabledRom.readUInt16At(movedPc))
        assertEquals(0x8000, disabledRom.readUInt16At(0x81E8F)); assertEquals(1, disabledRom.readUInt16At(0x81E91))
        assertEquals(65535, disabledRom.readUInt16At(0x81E8B), "Disabling does not move relocated tables back")
        assertEquals(1, disabledRom.readUInt16At(0x6E37D), "Disabling damage rates must not revert the separate suit patch")
        val disabledCold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(disabled.resultRom, assertNotNull(build(disabledCold), logs.joinToString("\n")).resultRom)
        editor.requestTogglePatch(patch.id)
        assertTrue(patch.enabled, editor.statusMessage)
        assertEquals("60.00091552734375", bridge.view(model.ACID_KEY).display)
        assertContentEquals(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(compiled.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        val header = ByteArray(512) { 0x5A }
        val headeredFile = File(tempDirectory, "HeaderedHazards.smc").apply { writeBytes(header + fixture.rom) }
        logs.clear()
        val headered = assertNotNull(ProjectFileService.buildRom(cold.copy(romPath = headeredFile.path), RomParser(header + fixture.rom), logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(header, headered.resultRom.copyOfRange(0, 512))
        assertContentEquals(compiled.resultRom, headered.resultRom.copyOfRange(512, headered.resultRom.size))
        assertNull(RomExporter(project, parser, logs::add).build())
        val conflict = SmPatch("hazard-high-byte", "Unchanged whole high byte", enabled = true,
            writes = mutableListOf(PatchWrite(0x81E92, listOf(0), expectedBytes = listOf(0))))
        project.patches.add(conflict)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        val valid = workspace.projectSourceText("bank_90.asm")
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid.replaceFirst("ADC.W SamusPhysicsConstants_LavaSubDamagePerFrame", "ADC.W SamusPhysicsConstants_AcidSubDamagePerFrame")))
        assertNull(build()); assertTrue(logs.any { "accumulator instructions changed" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_90.asm", valid))
        project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        assertNull(build()); assertTrue(logs.any { "Authored patch data" in it }, logs.joinToString("\n"))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `new room reciprocal resources selectors and symbolic doors survive checked Asar and cold relocated rebuilds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val position = assertNotNull(findAvailableRoomMapPosition(effectiveMinimapData(parser, project, 1), effectiveMapStationData(parser, project, 1),
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.filter { it.area == 1 }, 1, 1, preferredX = 40, preferredY = 20))
        val room = editor.createNewRoom(NewRoomCreationRequest("Complete ASM chamber", false, area = 1, mapX = position.first, mapY = position.second,
            width = 1, height = 1, tileset = 0), parser)
        val preview = editor.prepareWorkspaceParser(parser)
        editor.loadRoom(room.previewRoomId, preview, preview.readRoomHeader(room.previewRoomId)!!)
        editor.addProjectRoomDoor(room.previewRoomId, room.previewRoomId, 0, 0, 0, preview)
        val default = editor.currentPlmStateId()!!
        editor.addPlm(0xEEDB, 4, 4, 0x151, ItemStateScope.ALL_STATES)
        editor.addScrollTriggerWithCommands(6, 6, listOf(com.supermetroid.editor.data.ScrollCommand(0, 2)), ItemStateScope.ALL_STATES)
        editor.setScroll(0, 0, 2, 1)
        editor.addEnemy(0xD17F, 128, 160)
        editor.setRoomStateFxChange(default, com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x123), preview)
        editor.setRoomStateDataChange(default, com.supermetroid.editor.data.StateDataChange(musicTrack = 7), preview)
        val incoming = com.supermetroid.editor.data.ProjectSourceDoorReference(0, 0, room.id)
        editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, sourceDoor = incoming), preview)
        assertNull(editor.doorPreviewProblem)
        assertNull(editor.objectPreviewProblem); assertNull(editor.enemyPreviewProblem)
        bridge.doors.createManagedArrival(room.previewRoomId, 0, room.name)
        editor.refreshLinkedPlms()
        val clonePosition = assertNotNull(findAvailableRoomMapPosition(effectiveMinimapData(preview, project, 1),
            effectiveMapStationData(preview, project, 1), preview.roomCatalog.rooms.mapNotNull { preview.readRoomHeader(it.getRoomIdAsInt()) }.filter { it.area == 1 },
            1, 1, preferredX = 42, preferredY = 20))
        val clone = editor.createNewRoom(NewRoomCreationRequest("Authored snapshot", true, area = 1,
            mapX = clonePosition.first, mapY = clonePosition.second, width = 1, height = 1, tileset = 0), preview)
        assertEquals(1, clone.initialState.scrollCommands.size)
        assertNotNull(clone.doors.single().arrivalAsmLabel)
        val before = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return assertNotNull(ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult): Int {
            val id = result.newRoomIds.getValue(room.id)
            val built = RomParser(result.resultRom)
            val header = built.readRoomHeader(id)!!
            val door = built.parseDoorList(header.doorOut).single()
            assertEquals(id, door.destRoomPtr)
            assertTrue(door.entryCode in 0x8000..0xFFFE)
            assertEquals(door.doorDefPtr, built.inspectRoomStates(id).states.first().condition.argument)
            built.parseRoomStatesWithData(id).forEach { state ->
                assertEquals(7, state.musicTrack)
                assertEquals(listOf(2), built.parseScrollData(state.scrollPtr, 1, 1).toList())
                assertEquals(0x123, built.parseFxEntries(state.fxPtr).single().liquidSurfaceStart)
                assertEquals(1, built.parseEnemyPopulation(state.enemySetPtr).size)
                val plms = built.parsePlmSet(state.plmSetPtr)
                assertEquals(2, plms.size)
                assertEquals(listOf(Triple(0, 0, 2)), RomParser.decodeScrollCommands(built, plms.single { it.id == 0xB703 }.param, 1))
            }
            val cloneHeader = built.readRoomHeader(result.newRoomIds.getValue(clone.id))!!
            val cloneDoor = built.parseDoorList(cloneHeader.doorOut).single()
            assertEquals(id, cloneDoor.destRoomPtr, "Copying outgoing doors does not invent a self-link or a return connection")
            assertEquals(door.entryCode, cloneDoor.entryCode)
            val cloneTrigger = built.parsePlmSet(cloneHeader.plmSetPtr).single { it.id == 0xB703 }
            assertEquals(listOf(Triple(0, 0, 2)), RomParser.decodeScrollCommands(built, cloneTrigger.param, 1))
            assertNotEquals(built.parsePlmSet(header.plmSetPtr).single { it.id == 0xB703 }.param, cloneTrigger.param)
            assertEquals(before, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
            return id
        }
        val firstId = verify(build())
        val bank = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bank, bank + "\norg ${'$'}8F${firstId.toString(16).uppercase()}\n db ${'$'}42\n")
        val relocated = build(); assertNotEquals(firstId, verify(relocated))
        ProjectFileService.clearAsmBuildCachesForTests()
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(before)
        assertContentEquals(relocated.resultRom, build(cold).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `cloned rooms coexist with every native source family and preserve independent outgoing destinations through cold relocated builds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureDoorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureRoomPropertiesSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureInitialScrollSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureFxSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureEnemySource(native.name), editor.statusMessage)
        editor.updateDoor(0, editor.doorEntries.first().copy(distFromDoor = 0x1234))
        editor.addEnemy(0xD17F, 128, 160)
        val actorsBefore = bridge.enemies.sourceText()
        val population = assertNotNull(bridge.enemies.view(native.roomId, assertNotNull(editor.currentPlmStateId())).resource).population
        val populationBody = authoredMacroBody(actorsBefore, population.identity)
        workspace.replaceProjectSource(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE, actorsBefore,
            actorsBefore.replace(populationBody, populationBody.replace(Regex("db \\$[0-9A-Fa-f]{2} ; Required kills")) { "db ${'$'}07 ; Required kills" }))
        editor.refreshLinkedPlms()
        editor.setRoomStateFxChange(assertNotNull(editor.currentPlmStateId()), com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x100), parser)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.applyBulkEdits("Clone geometry", listOf(com.supermetroid.editor.data.TileEdit(2, 2, editor.readBlockWord(2, 2), 0x8123)))
        assertEquals(0x8123, editor.readBlockWord(2, 2), editor.statusMessage)
        val owner = project.rooms.getValue("9879")
        val predicateState = owner.states.first().id
        val predicateMacro = com.supermetroid.editor.asm.AsmRoomSelectorsSource.conditionName(native.roomId, predicateState)
        val text = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, text,
            text.replace(authoredMacroBody(text, predicateMacro), "    ; smedit:asm-predicate-v1\n    dw CloneTestPredicate\n") + """

                pushpc
                org Freespace_Bank8F_E99B
                CloneTestPredicate:
                    clc
                    rts
                    fillbyte ${'$'}FF
                    fill 32
                CloneTestPredicate_End:
                pullpc
            """.trimIndent() + "\n")
        editor.refreshLinkedPlms()
        val originalDoors = editor.doorEntries.toList()
        val targetDoorLists = originalDoors.map { it.destRoomPtr }.distinct().associateWith { id ->
            parser.parseDoorList(assertNotNull(parser.readRoomHeader(id)).doorOut)
        }
        val level = assertNotNull(editor.workingLevelData).copyOf()
        val enemies = editor.workingEnemies.toList()
        val graphics = editor.workingEnemyGraphics.toList()
        assertTrue(enemies.any { it.id == 0xD17F }); assertTrue(graphics.any { it.speciesId == 0xD17F })
        val sourceBefore = bridge.sourceText() to bridge.doors.sourceText()
        val position = assertNotNull(findAvailableRoomMapPosition(effectiveMinimapData(parser, project, 1), effectiveMapStationData(parser, project, 1),
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.filter { it.area == 1 }, native.width, native.height, preferredX = 40, preferredY = 20))
        val request = NewRoomCreationRequest("Flyway copy", true, area = 1, mapX = position.first, mapY = position.second,
            width = native.width, height = native.height, tileset = native.tileset)
        val beforeClone = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val valid = bridge.sourceText()
        val layoutMacro = assertNotNull(bridge.layouts.view(native.roomId, assertNotNull(editor.currentPlmStateId())).resource).identity
        val layoutBody = authoredMacroBody(valid, layoutMacro)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, valid, valid.replace(layoutBody, layoutBody.replace("${'$'}8123", "unfinished_layout_literal")))
        assertContains(assertFailsWith<IllegalArgumentException> { editor.createNewRoom(request, parser) }.message.orEmpty(), "Clone blocked")
        assertEquals(beforeClone, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bridge.sourceText(), valid); editor.refreshLinkedPlms()
        // Symbolic arrivals and independently copied command resources are exercised
        // by the complete new-room integration above; this case retains native anchors.
        val definition = editor.createNewRoom(request, parser)
        assertEquals(originalDoors.size, definition.doors.size, "Copy outgoing doors is the clone default")
        assertEquals(originalDoors.map { project.romRoomDestination(it.destRoomPtr) }, definition.doors.map { it.destination })
        assertEquals(sourceBefore, bridge.sourceText() to bridge.doors.sourceText(), "Cloning must not rewrite native source")
        val preview = editor.prepareWorkspaceParser(parser)
        val previewDoors = preview.parseDoorList(assertNotNull(preview.readRoomHeader(definition.previewRoomId)).doorOut)
        assertTrue(previewDoors.none { copy -> originalDoors.any { it.doorDefPtr == copy.doorDefPtr } })
        // The reverse order matters too: adding a room must not disable later native adoption,
        // and navigation must accept our known preview parser without trusting a foreign ROM.
        val secondNative = assertNotNull(preview.readRoomHeader(0x91F8))
        editor.loadRoom(secondNative.roomId, preview, secondNative)
        val navigation = RoomAsmNavigation(editor, workspace, { secondNative.name })
        val beforeInspect = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)), editor.statusMessage)
        assertNotNull(workspace.roomSourceInspection?.automaticEdit)
        assertEquals(beforeInspect, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        val proposed = workspace.sourceEditorText(FX_SOURCE_FILE)
        assertTrue(workspace.updateSourceEditText(FX_SOURCE_FILE, proposed + "; Native adoption after adding a room.\n"), workspace.error)
        assertTrue(editor.ensureSelectorSource(secondNative.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(secondNative.name), editor.statusMessage)
        assertTrue(editor.ensureInitialScrollSource(secondNative.name), editor.statusMessage)
        assertTrue(editor.ensureFxSource(secondNative.name), editor.statusMessage)
        assertTrue(editor.ensureEnemySource(secondNative.name), editor.statusMessage)
        // Editing the clone must not mutate the original or the destination's return doors.
        definition.doors.first().distFromDoor = 0x2345
        val json = kotlinx.serialization.json.Json
        val saved = json.encodeToString(SmEditProject.serializer(), project)
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return assertNotNull(ProjectFileService.buildRom(json.decodeFromString<SmEditProject>(saved), parser,
                logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult): Int {
            val built = RomParser(result.resultRom)
            val id = result.newRoomIds.getValue(definition.id)
            val header = assertNotNull(built.readRoomHeader(id))
            val doors = built.parseDoorList(header.doorOut)
            assertEquals(originalDoors.map { it.destRoomPtr }, doors.map { it.destRoomPtr })
            doors.forEachIndexed { index, copy ->
                val original = originalDoors[index]
                val targetArea = assertNotNull(built.readRoomHeader(copy.destRoomPtr)).area
                val flags = if (targetArea == header.area) original.bitflag and 0x40.inv() else original.bitflag or 0x40
                assertEquals(original.copy(doorDefPtr = copy.doorDefPtr, bitflag = flags, distFromDoor = if (index == 0) 0x2345 else original.distFromDoor), copy)
                assertTrue(copy.doorDefPtr !in originalDoors.map { it.doorDefPtr })
            }
            assertEquals(originalDoors, built.parseDoorList(assertNotNull(built.readRoomHeader(native.roomId)).doorOut))
            targetDoorLists.filterKeys { it != native.roomId }.forEach { (target, expected) ->
                assertEquals(expected, built.parseDoorList(assertNotNull(built.readRoomHeader(target)).doorOut))
            }
            assertContentEquals(level, built.decompressLZ2(header.levelDataPtr))
            assertEquals(enemies, built.parseEnemyPopulation(header.enemySetPtr))
            assertEquals(7, com.supermetroid.editor.rom.readSourceEnemyPopulation(built, header.enemySetPtr, "Cloned quota").killCount)
            assertEquals(graphics, built.parseEnemyGfxSet(header.enemyGfxPtr))
            assertEquals(0x100, built.parseFxEntries(header.fxPtr).single().liquidSurfaceStart)
            val predicate = built.inspectRoomStates(native.roomId).states.first().condition
            assertEquals(com.supermetroid.editor.rom.RoomStateConditionKind.ASM_ONLY, predicate.kind)
            assertEquals(0x8FE99B, predicate.asmPredicateAddress)
            return id
        }
        val first = build(); val firstId = verify(first)
        val bank = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bank, bank + "\norg ${'$'}8F${firstId.toString(16).uppercase()}\n db ${'$'}42\n")
        val relocated = build(); assertNotEquals(firstId, verify(relocated))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(relocated.resultRom, build().resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `project room layout source coexists with native layouts preserves private states and survives cold relocated Asar builds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        editor.loadRoom(0x9879, parser, parser.readRoomHeader(0x9879)!!)
        assertTrue(editor.ensureLayoutSource("Flyway"), editor.statusMessage)
        val nativeLayouts = parser.parseRoomStatesWithData(0x9879).map { parser.decompressLZ2(it.levelDataPtr) }
        val position = assertNotNull(findAvailableRoomMapPosition(effectiveMinimapData(parser, project, 1), effectiveMapStationData(parser, project, 1),
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.filter { it.area == 1 }, 1, 1, preferredX = 40, preferredY = 20))
        val definition = editor.createNewRoom(NewRoomCreationRequest("Layout chamber", false, area = 1, mapX = position.first, mapY = position.second,
            width = 1, height = 1, tileset = 0), parser)
        val preview = editor.prepareWorkspaceParser(parser)
        editor.loadRoom(definition.previewRoomId, preview, preview.readRoomHeader(definition.previewRoomId)!!)
        val default = editor.roomSourceSnapshot().states.single().id
        editor.setRoomStateDataChange(default, com.supermetroid.editor.data.StateDataChange(musicTrack = 7), preview)
        editor.applyBulkEdits("Before source", listOf(com.supermetroid.editor.data.TileEdit(2, 2, editor.readBlockWord(2, 2), 0x8123, editor.readBts(2, 2), 7)))
        val layoutNavigation = RoomAsmNavigation(editor, workspace, { definition.name })
        assertTrue(layoutNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.LAYOUT)), editor.statusMessage)
        val layoutDraft = assertNotNull(workspace.roomSourceInspection?.automaticEdit)
        assertTrue(workspace.updateSourceEditText(PLM_SOURCE_FILE, layoutDraft.text + "; First new-room layout typing\n"), editor.statusMessage)
        val text = bridge.sourceText()
        val layoutName = bridge.layouts.view(definition.previewRoomId, default).resource!!.identity
        val body = authoredMacroBody(text, layoutName)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replaceFirst(body, body.replace("${'$'}8123", "${'$'}8456")))
        editor.refreshLinkedPlms(); assertEquals(0x8456, editor.readBlockWord(2, 2))
        editor.applyBulkEdits("After source", listOf(com.supermetroid.editor.data.TileEdit(3, 2, editor.readBlockWord(3, 2), 0x8124)))
        val conditional = editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), preview)
        editor.switchRoomState(conditional, preview)
        assertTrue(editor.makeCurrentLayoutUnique(preview), editor.statusMessage)
        editor.applyBulkEdits("Private layout", listOf(com.supermetroid.editor.data.TileEdit(2, 2, editor.readBlockWord(2, 2), 0x8678, 7, 0x22)))
        editor.setRoomStateDataChange(conditional, com.supermetroid.editor.data.StateDataChange(musicTrack = 9), preview)
        val before = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return assertNotNull(ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult): Int {
            val id = result.newRoomIds.getValue(definition.id)
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(id)
            assertEquals(listOf(9, 7), states.map { it.musicTrack })
            assertEquals(2, states.map { it.levelDataPtr }.distinct().size)
            states.forEachIndexed { index, state ->
                val bytes = built.decompressLZ2(state.levelDataPtr)
                fun word(cell: Int) = (bytes[2 + cell * 2].toInt() and 255) or ((bytes[3 + cell * 2].toInt() and 255) shl 8)
                val layerBytes = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
                assertEquals(if (index == 0) 0x8678 else 0x8456, word(34))
                assertEquals(0x8124, word(35))
                assertEquals(if (index == 0) 0x22 else 7, bytes[2 + layerBytes + 34].toInt() and 255)
            }
            built.parseRoomStatesWithData(0x9879).forEachIndexed { index, state ->
                assertContentEquals(nativeLayouts[index], built.decompressLZ2(state.levelDataPtr))
            }
            return id
        }
        val first = build(); val firstId = verify(first)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        val bank = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bank, bank + "\norg ${'$'}8F${firstId.toString(16).uppercase()}\n db ${'$'}42\n")
        val relocated = build(); assertNotEquals(firstId, verify(relocated))
        ProjectFileService.clearAsmBuildCachesForTests()
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(before)
        assertContentEquals(relocated.resultRom, build(cold).resultRom)
        val good = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, good, good.replaceFirst("${'$'}8678", "missing_layout_literal"))
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear()
        assertNull(ProjectFileService.buildRom(cold, parser, logs::add, logs::add, fixture.projectFile.path),
            "A cold build must reject the invalid source, not substitute the last-good canvas")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `project room scalar source coexists with native ownership follows new states and relocates through cold Asar builds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        val nativeState = editor.roomSourceSnapshot().states.last().id
        editor.setRoomStateDataChange(nativeState, com.supermetroid.editor.data.StateDataChange(musicTrack = 6), parser)
        assertEquals(1, project.rooms.getValue("91F8").asmRoomPropertiesSourceVersion)
        val position = assertNotNull(findAvailableRoomMapPosition(effectiveMinimapData(parser, project, 1), effectiveMapStationData(parser, project, 1),
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.filter { it.area == 1 }, 1, 1, preferredX = 40, preferredY = 20))
        val definition = editor.createNewRoom(NewRoomCreationRequest("Authored chamber", false, area = 1, mapX = position.first, mapY = position.second,
            width = 1, height = 1, tileset = 0), parser)
        val preview = editor.prepareWorkspaceParser(parser)
        editor.loadRoom(definition.previewRoomId, preview, preview.readRoomHeader(definition.previewRoomId)!!)
        val default = editor.roomSourceSnapshot().states.single().id
        editor.setRoomStateDataChange(default, com.supermetroid.editor.data.StateDataChange(musicTrack = 7), preview)
        editor.setRoomHeaderChange(com.supermetroid.editor.data.RoomHeaderChange(upScroller = 0x72, creBitflag = 3))
        val text = bridge.sourceText()
        val suffix = com.supermetroid.editor.asm.AsmRoomPropertiesSource.stateName(0, default)
            .removePrefix(com.supermetroid.editor.asm.AsmRoomPropertiesSource.prefix(0))
        val name = com.supermetroid.editor.asm.AsmRoomPropertiesSource.projectPrefix(definition.id) + suffix
        val body = authoredMacroBody(text, name)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replaceFirst(body, body.replace("db ${'$'}07 ; Music track", "db ${'$'}08 ; Music track / ASM edit")))
        editor.refreshLinkedPlms()
        assertEquals(8, bridge.properties.view(definition.previewRoomId).resource!!.states.getValue(default).musicTrack)
        val conditional = editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), preview)
        editor.setRoomStateDataChange(conditional, com.supermetroid.editor.data.StateDataChange(musicTrack = 9), preview)
        editor.addPlm(0xEEDB, 4, 4, 0x100, ItemStateScope.THIS_STATE)
        val before = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return assertNotNull(ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult): Int {
            val id = result.newRoomIds.getValue(definition.id)
            val built = RomParser(result.resultRom)
            val header = assertNotNull(built.readRoomHeader(id))
            assertEquals(0x72, header.upScroller); assertEquals(3, header.creBitflag)
            val states = built.parseRoomStatesWithData(id)
            assertEquals(listOf(9, 8), states.map { it.musicTrack })
            assertTrue(built.parsePlmSet(states.first().plmSetPtr).any { it.id == 0xEEDB && it.x == 4 && it.y == 4 })
            assertEquals(6, built.parseRoomStatesWithData(0x91F8).last().musicTrack)
            assertEquals(0x8F0000 or id, AsmBuildArtifactRepository().load(fixture.projectFile.path)!!.symbols.labels[com.supermetroid.editor.asm.AsmProjectRoomLabels.entry(definition.id)])
            return id
        }
        val first = build(); val firstId = verify(first)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        val bank = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bank, bank + "\norg ${'$'}8F${firstId.toString(16).uppercase()}\n db ${'$'}42\n")
        val second = build(); assertNotEquals(firstId, verify(second))
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(before)
        assertContentEquals(second.resultRom, build(cold).resultRom)
        val invalid = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, invalid, invalid.replace("db ${'$'}09 ; Music track", "db missing ; Music track"))
        assertTrue(workspace.saveAllSources(), workspace.error)
        assertNull(ProjectFileService.buildRom(cold, parser, logs::add, logs::add, fixture.projectFile.path), "A cold build must not use a last-good GUI projection")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `new rooms compile as a complete ASM graph with durable labels GUI edits relocation and semantic data navigation`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val rom = fixture.rom
        val parser = RomParser(rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val position = assertNotNull(findAvailableRoomMapPosition(effectiveMinimapData(parser, project, 1), effectiveMapStationData(parser, project, 1),
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.filter { it.area == 1 },
            1, 1, preferredX = 40, preferredY = 20))
        val definition = editor.createNewRoom(NewRoomCreationRequest("Project chamber", false, area = 1, mapX = position.first, mapY = position.second, width = 1, height = 1, tileset = 0), parser)
        var preview = editor.prepareWorkspaceParser(parser)
        editor.loadRoom(definition.previewRoomId, preview, preview.readRoomHeader(definition.previewRoomId)!!)
        editor.addProjectRoomDoor(definition.previewRoomId, definition.previewRoomId, 0, 0, 0, preview)
        preview = editor.prepareWorkspaceParser()
        editor.loadRoom(definition.previewRoomId, preview, preview.readRoomHeader(definition.previewRoomId)!!)
        editor.applyBulkEdits("Paint project room", listOf(com.supermetroid.editor.data.TileEdit(2, 2, editor.readBlockWord(2, 2), 0x8123)))
        val state = editor.currentPlmStateId()!!
        editor.setRoomStateDataChange(state, com.supermetroid.editor.data.StateDataChange(musicTrack = 7), preview)
        editor.setRoomStateFxChange(state, com.supermetroid.editor.data.FxChange(fxType = 4, liquidSurfaceStart = 0x0120), preview)
        editor.addPlm(0xEEDB, 4, 4, 0x100, ItemStateScope.THIS_STATE)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDB }, editor.statusMessage)
        val before = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        fun verify(result: RomBuildResult): Int {
            assertTrue(result.sourceRoomPatched > 0, "The graph must be assembled, not patched after Asar")
            val id = result.newRoomIds.getValue(definition.id)
            val built = RomParser(result.resultRom)
            val header = assertNotNull(built.readRoomHeader(id))
            val data = built.parseRoomStatesWithData(id).single()
            assertEquals(7, data.musicTrack)
            assertEquals(4, built.parseFxEntries(data.fxPtr).single().fxType)
            assertEquals(0x0120, built.parseFxEntries(data.fxPtr).single().liquidSurfaceStart)
            assertEquals(id, built.parseDoorList(header.doorOut).single().destRoomPtr)
            assertTrue(built.parsePlmSet(data.plmSetPtr).any { it.id == 0xEEDB && it.x == 4 && it.y == 4 })
            val layout = built.decompressLZ2(data.levelDataPtr)
            val offset = 2 + (2 * 16 + 2) * 2
            assertEquals(0x8123, (layout[offset].toInt() and 255) or ((layout[offset + 1].toInt() and 255) shl 8))
            val labels = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!.symbols.labels
            assertEquals(0x8F0000 or id, labels[com.supermetroid.editor.asm.AsmProjectRoomLabels.entry(definition.id)])
            return id
        }
        val first = assertNotNull(build(), logs.joinToString("\n")); val firstId = verify(first)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project), "Building must not persist physical placements")
        workspace.refreshBuildReport(validateSources = false)
        val section = assertNotNull(workspace.roomDataSnapshot).sections.first { it.projectRoomId == definition.id && it.label.endsWith("_Header") }
        assertEquals(definition.previewRoomId, com.supermetroid.editor.asm.AsmEditorTarget.Room(firstId, projectRoomId = section.projectRoomId).resolveRoomId(project))
        val revision = workspace.sourceBufferRevision
        assertTrue(RoomAsmNavigation(editor, workspace, { definition.name }).open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES)), editor.statusMessage)
        assertEquals(revision, workspace.sourceBufferRevision, "Opening authored header/state source must not mutate it")
        assertNull(workspace.roomSourceInspection)
        val bank = workspace.projectSourceText(PLM_SOURCE_FILE)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bank, bank + "\norg ${'$'}8F${firstId.toString(16).uppercase()}\n db ${'$'}42 ; occupy the old room placement\n")
        val second = assertNotNull(build(), logs.joinToString("\n")); val secondId = verify(second)
        assertNotEquals(firstId, secondId)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(before)
        val coldResult = assertNotNull(ProjectFileService.buildRom(cold, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(second.resultRom, coldResult.resultRom)
        project.newRooms.remove(definition)
        assertNull(com.supermetroid.editor.asm.AsmEditorTarget.Room(firstId, projectRoomId = definition.id).resolveRoomId(project))
        assertContentEquals(rom, fixture.romFile.readBytes())
    }

    @Test fun `private shared door copies relink mirrored numeric selectors and FX while native checkpoints remain stable`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val rom = com.supermetroid.editor.asm.saveSourceTestRom()
        rom.fill(0xFF.toByte(), 0x1F000, 0x20000)
        rom.fill(0xFF.toByte(), 0x207000, 0x208000)
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        com.supermetroid.editor.rom.LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        rom.copyOfRange(0x791F8, 0x791F8 + 44).copyInto(rom, 0x79879)
        val fixture = AsmProjectTestFixture.synthetic(tempDirectory, rom)
        val parser = RomParser(rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        val target = project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        val conditional = target.states.first { it.condition.kind != com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT }
        editor.setRoomStateCondition(conditional.id, com.supermetroid.editor.rom.projectRoomStateCondition(
            com.supermetroid.editor.data.ProjectRoomStateConditionKind.INCOMING_DOOR, 0x8000, negated = true), parser)
        editor.setRoomStateDoorFxChange(conditional.id, 0x8000, com.supermetroid.editor.data.FxChange(fxType = 4, liquidSurfaceStart = 0x120), parser)
        val logs = mutableListOf<String>()
        suspend fun build(candidate: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(candidate, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val before = assertNotNull(build(), logs.joinToString("\n"))
        editor.loadRoom(0x9879, parser, parser.readRoomHeader(0x9879)!!)
        assertTrue(editor.makeDoorIndependent(0), editor.statusMessage)
        val built = assertNotNull(build(), logs.joinToString("\n"))
        val output = RomParser(built.resultRom)
        val pointer = output.parseDoorList(output.readRoomHeader(0x9879)!!.doorOut).single().doorDefPtr
        assertNotEquals(0x8000, pointer)
        assertEquals(0x8000, output.readSaveEntry(0, 0)!!.doorPtr)
        val state = output.parseRoomStatesWithData(0x91F8).first { it.stateInfo.conditionCode != 0xE5E6 }
        val fx = output.parseFxEntries(state.fxPtr)
        assertEquals(listOf(0x8000, pointer), fx.map { it.doorSelect })
        val selectors = com.supermetroid.editor.asm.AsmRoomSelectorsSource(bridge.sourceText()).resource(target)
        val mirrored = selectors.conditions.getValue(conditional.id)
        assertTrue(mirrored.negated)
        assertEquals(com.supermetroid.editor.data.ProjectRoomStateConditionKind.ANY_OF, mirrored.kind)
        assertEquals(com.supermetroid.editor.data.ProjectSourceDoorReference(0x9879, 0), mirrored.children.last().sourceDoor)
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(built.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(before.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
    }

    @Test fun `boss stats preserve every native part cold ownership headered exports and reject competing or moved records`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val bridge = assertNotNull(editor.bossStatsBridge)
        for (field in ALL_BOSS_FIELDS) for (word in bridge.catalog.original(field.key).words) {
            assertEquals(parser.readUInt16At(parser.snesToPc(0xA00000 or word.speciesId) + word.offset), word.value, "${field.key}: ${word.header}")
        }
        val logs = mutableListOf<String>()
        suspend fun build(candidate: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(candidate, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        assertContentEquals(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val patch = editor.findOrCreateConfigPatch("boss_stats").apply { enabled = true; configData = mutableMapOf("kraid_hp" to 5000) }
        val bodyPc = parser.snesToPc(0xA0E2BF) + 4
        val armPc = parser.snesToPc(0xA0E2FF) + 4
        val pending = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertEquals(5000, pending.readUInt16At(bodyPc)); assertEquals(5000, pending.readUInt16At(armPc))
        editor.setPatchConfigData(patch.id, "kraid_hp", 2000)
        assertNull(patch.configData)
        val source = workspace.projectSourceText("bank_A0.asm")
        val arm = bridge.catalog.field("kraid_hp", source).words.last()
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", source.replaceRange(arm.span, "2001")))
        assertContains(assertNotNull(bridge.view("kraid_hp").problem), "managed in ASM")
        val compiled = assertNotNull(build(), logs.joinToString("\n"))
        val compiledParser = RomParser(compiled.resultRom)
        assertEquals(2000, compiledParser.readUInt16At(bodyPc)); assertEquals(2001, compiledParser.readUInt16At(armPc))
        assertTrue(compiled.writeReport.writes.any { it.offset == armPc && it.bytes.size == 2 && "bank_A0.asm" in it.label })
        editor.requestTogglePatch(patch.id); editor.confirmPatchDisable()
        assertFalse(patch.enabled, editor.statusMessage)
        assertContentEquals(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        editor.requestTogglePatch(patch.id)
        assertTrue(patch.enabled, editor.statusMessage)
        assertContentEquals(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(compiled.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        val header = ByteArray(512) { 0x5A }
        val headeredFile = File(tempDirectory, "HeaderedBoss.smc").apply { writeBytes(header + fixture.rom) }
        logs.clear()
        val headered = assertNotNull(ProjectFileService.buildRom(cold.copy(romPath = headeredFile.path), RomParser(header + fixture.rom),
            logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(header, headered.resultRom.copyOfRange(0, 512))
        assertContentEquals(compiled.resultRom, headered.resultRom.copyOfRange(512, headered.resultRom.size))
        assertNull(RomExporter(project, parser, logs::add).build(), "A ROM-only export must not silently drop authored boss source")
        val conflict = SmPatch("boss-high-byte", "Unchanged secondary byte", enabled = true,
            writes = mutableListOf(PatchWrite((armPc + 1).toLong(), listOf(7), expectedBytes = listOf(7))))
        project.patches.add(conflict)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        patch.configData = mutableMapOf("kraid_hp" to 3000)
        assertNull(build()); assertTrue(logs.any { "both authored ASM" in it }, logs.joinToString("\n"))
        bridge.useSourceValues("kraid_hp")
        assertContentEquals(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val valid = workspace.projectSourceText("bank_A0.asm")
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", valid.replaceFirst("EnemyHeaders_KraidArm:", "db ${'$'}00\nEnemyHeaders_KraidArm:")))
        assertNull(build()); assertTrue(logs.any { "moved or was removed" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", valid))
        val currentArm = bridge.catalog.field("kraid_hp", valid).words.last()
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", valid.replaceRange(currentArm.span, "2001+1")))
        assertNull(build()); assertTrue(logs.any { "16-bit literal" in it }, logs.joinToString("\n"))
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", valid))
        project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        assertNull(build()); assertTrue(logs.any { "boss stats requires ASM source mode" in it || "boss stats require ASM source mode" in it }, logs.joinToString("\n"))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `beam damage follows symbolic relocation preserves independent charged values and rejects competing patches after cold builds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val bridge = assertNotNull(editor.beamDamageBridge)
        for (beam in ALL_BEAMS) {
            assertEquals(parser.readUInt16At(parser.snesToPc(beam.snesAddress)), bridge.catalog.original(beam.key).value, beam.key)
            assertEquals(parser.readUInt16At(parser.snesToPc(beam.chargedSnesAddress)), bridge.catalog.original(beam.chargedKey).value, beam.chargedKey)
        }
        val romProject = SmEditProject(fixture.romFile.path).apply {
            patches.add(SmPatch("beams", "Beams", enabled = true, configType = "beam_damage",
                configData = mutableMapOf("power" to 65535, "power_charged" to 97, "ice_charged" to 0)))
        }
        val romOnly = RomParser(assertNotNull(RomExporter(romProject, parser).build()).resultRom)
        assertEquals(65535, romOnly.readUInt16At(parser.snesToPc(BASE_BEAMS.first().snesAddress)))
        assertEquals(97, romOnly.readUInt16At(parser.snesToPc(BASE_BEAMS.first().chargedSnesAddress)))
        assertEquals(0, romOnly.readUInt16At(parser.snesToPc(BASE_BEAMS[1].chargedSnesAddress)))
        val logs = mutableListOf<String>()
        suspend fun build(owner: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(owner, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        assertContentEquals(fixture.rom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val patch = editor.findOrCreateConfigPatch("beam_damage").apply { enabled = true; configData = mutableMapOf("power" to 40) }
        val legacy = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertEquals(40, legacy.readUInt16At(parser.snesToPc(BASE_BEAMS.first().snesAddress)))
        assertEquals(120, legacy.readUInt16At(parser.snesToPc(BASE_BEAMS.first().chargedSnesAddress)))

        // Move a whole native projectile record, not its instruction lists. The
        // existing legacy config must follow the pointer, not patch the old word.
        val source = workspace.projectSourceText("bank_93.asm")
        val field = bridge.catalog.original("power")
        val record = assertNotNull(Regex("(?m)^${Regex.escape(field.table)}:[^\\r\\n]*\\r?\\n(?:[ \\t]*dw[^\\r\\n]*\\r?\\n){11}").find(source))
        val movedPc = parser.snesToPc(0x93FF00)
        assertTrue(fixture.rom.copyOfRange(movedPc, movedPc + 22).all { it == 0xFF.toByte() })
        workspace.replaceProjectSource("bank_93.asm", source, source.replaceRange(record.range,
            "org ${'$'}93FF00\n${record.value}org ${'$'}938447\n"))
        val movedLegacy = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertEquals(0xFF00, movedLegacy.readUInt16At(parser.snesToPc(0x9383C1)))
        assertEquals(40, movedLegacy.readUInt16At(movedPc))
        assertEquals(65535, movedLegacy.readUInt16At(parser.snesToPc(0x938431)), "Old location is erased, not a damage-patch target anymore")
        editor.setPatchConfigData(patch.id, "power", 41)
        assertEquals(120, bridge.view("power_charged").value)
        editor.setPatchConfigData(patch.id, "power_charged", 97)
        val compiled = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(41, RomParser(compiled.resultRom).readUInt16At(movedPc))
        assertEquals(97, RomParser(compiled.resultRom).readUInt16At(parser.snesToPc(BASE_BEAMS.first().chargedSnesAddress)))
        assertTrue(com.supermetroid.editor.asm.AsmSemanticBridge.linksFor(0x93FF00, romParser = RomParser(compiled.resultRom))
            .any { it.target == com.supermetroid.editor.asm.AsmEditorTarget.BeamDamage("power") })
        assertTrue(compiled.writeReport.writes.any { it.offset == movedPc && it.bytes.size == 2 && "bank_93.asm" in it.label }, "Protect the unchanged upper damage byte too")
        editor.requestTogglePatch(patch.id); editor.confirmPatchDisable()
        assertFalse(patch.enabled, editor.statusMessage)
        val disabled = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertEquals(20, disabled.readUInt16At(movedPc)); assertEquals(60, disabled.readUInt16At(parser.snesToPc(BASE_BEAMS.first().chargedSnesAddress)))
        assertEquals(0xFF00, disabled.readUInt16At(parser.snesToPc(0x9383C1)))
        assertEquals(65535, disabled.readUInt16At(parser.snesToPc(0x938431)))
        editor.requestTogglePatch(patch.id)
        assertTrue(patch.enabled, editor.statusMessage)
        assertContentEquals(compiled.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(compiled.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        val copierHeader = ByteArray(512) { 0x5A }
        val headeredRom = copierHeader + fixture.rom
        val headeredFile = File(tempDirectory, "Headered.smc").apply { writeBytes(headeredRom) }
        logs.clear()
        val headeredBuild = assertNotNull(ProjectFileService.buildRom(cold.copy(romPath = headeredFile.path), RomParser(headeredRom),
            logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(copierHeader, headeredBuild.resultRom.copyOfRange(0, 512))
        assertContentEquals(compiled.resultRom, headeredBuild.resultRom.copyOfRange(512, headeredBuild.resultRom.size))
        assertNull(RomExporter(project, parser, logs::add).build(), "ROM-only export cannot omit owned beam source")
        val conflict = SmPatch("beam-high-byte", "Cannot overwrite unchanged high byte", enabled = true,
            writes = mutableListOf(PatchWrite((movedPc + 1).toLong(), listOf(0), expectedBytes = listOf(0))))
        project.patches.add(conflict)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        val pointerConflict = SmPatch("beam-binding", "Cannot retarget owned beam", enabled = true,
            writes = mutableListOf(PatchWrite(parser.snesToPc(0x9383C1).toLong(), listOf(0, 255), expectedBytes = listOf(0, 255))))
        project.patches.add(pointerConflict)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        pointerConflict.enabled = false
        val fieldNow = bridge.catalog.field("power", workspace.projectSourceText("bank_93.asm"))
        assertTrue(workspace.updateSourceEditText("bank_93.asm", workspace.projectSourceText("bank_93.asm").replaceRange(fieldNow.span, "41+1")))
        assertNotNull(bridge.view("power").problem)
        assertNull(build()); assertTrue(logs.any { "16-bit literal" in it }, logs.joinToString("\n"))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `enemy tuning uses native source fields follows relocated drop symbols and protects cold ownership from patches and moved headers`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val tuning = assertNotNull(editor.enemyTuningBridge)
        val hpPc = parser.snesToPc(0xA0DCFF) + 4
        val pointerPc = parser.snesToPc(0xA0DCFF) + 0x3A
        for (enemy in ENEMY_DEFS) {
            val pc = parser.snesToPc(0xA00000 or enemy.speciesId)
            assertEquals(parser.readUInt16At(pc + 4), tuning.catalog.original("${enemy.key}_hp").value, enemy.name)
            assertEquals(parser.readUInt16At(pc + 6), tuning.catalog.original("${enemy.key}_dmg").value, enemy.name)
            val pointer = parser.readUInt16At(pc + 0x3A)
            if (pointer in 0x8000..0xFFFA) for (slot in 0..5) {
                assertEquals(fixture.rom[parser.snesToPc(0xB40000 or pointer) + slot].toInt() and 255,
                    tuning.catalog.original("${enemy.key}_drop$slot").value, "${enemy.name}, drop $slot")
            }
        }
        val stats = editor.findOrCreateConfigPatch("enemy_stats")
        val drops = editor.findOrCreateConfigPatch("enemy_drops")
        val revision = workspace.sourceBufferRevision
        tuning.view("zoomer_hp"); tuning.line("zoomer_drop0")
        assertEquals(revision, workspace.sourceBufferRevision)
        editor.setPatchConfigData(stats.id, "zoomer_hp", 61)
        editor.setPatchConfigData(stats.id, "zoomer_dmg", 17)
        editor.setPatchConfigData(drops.id, "zoomer_drop0", 73)
        assertEquals(61, tuning.view("zoomer_hp").value)
        assertNull(stats.configData?.get("zoomer_hp")); assertNull(drops.configData?.get("zoomer_drop0"))
        val logs = mutableListOf<String>()
        suspend fun build(candidate: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(candidate, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val first = assertNotNull(build(), logs.joinToString("\n"))
        val firstParser = RomParser(first.resultRom)
        assertEquals(61, firstParser.readUInt16At(hpPc)); assertEquals(17, firstParser.readUInt16At(hpPc + 2))
        assertEquals(73, first.resultRom[parser.snesToPc(0xB40000 or firstParser.readUInt16At(pointerPc))].toInt() and 255)
        val headerText = workspace.projectSourceText("bank_A0.asm")
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", headerText.replaceFirst("%health(${ '$'}003D)", "%health(62)")))
        assertEquals(62, tuning.view("zoomer_hp").value)
        assertTrue(workspace.undoSourceEdit("bank_A0.asm"), workspace.error)
        assertEquals(61, tuning.view("zoomer_hp").value)
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)

        // Move only this six-byte table into verified erased bank-B4 space. Its
        // header stays fixed, but %drops must follow the real compiler symbol.
        val oldPointer = parser.readUInt16At(pointerPc)
        val tableName = tuning.catalog.original("zoomer_drop0").table!!
        val sourceB4 = workspace.projectSourceText("bank_B4.asm")
        val table = assertNotNull(Regex("(?m)^${Regex.escape(tableName)}:[^\\r\\n]*\\r?\\n[^\\r\\n]*\\r?\\n").find(sourceB4))
        assertTrue(fixture.rom.copyOfRange(parser.snesToPc(0xB4FF00), parser.snesToPc(0xB4FF00) + 6).all { it == 0xFF.toByte() })
        val relocated = sourceB4.replaceRange(table.range, "org ${'$'}B4FF00\n${table.value}org ${'$'}B4${(oldPointer + 6).toString(16).uppercase()}\n")
        assertTrue(workspace.updateSourceEditText("bank_B4.asm", relocated))
        val moved = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0xFF00, RomParser(moved.resultRom).readUInt16At(pointerPc))
        assertEquals(73, moved.resultRom[parser.snesToPc(0xB4FF00)].toInt() and 255)
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(moved.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)

        val conflict = SmPatch("enemy-unchanged-byte", "Cannot bypass typed ownership", enabled = true,
            writes = mutableListOf(PatchWrite((hpPc + 1).toLong(), listOf(0), expectedBytes = listOf(0))))
        project.patches.add(conflict)
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        assertTrue(workspace.updateSourceEditText("bank_A0.asm", headerText.replaceFirst("EnemyHeaders_Zoomer:", "db ${'$'}00\nEnemyHeaders_Zoomer:")))
        assertNull(build()); assertTrue(logs.any { "moved or was removed" in it }, logs.joinToString("\n"))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `vulnerability source preserves native flags shared bindings relocation cold rebuilds and patch safety`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val tuning = assertNotNull(editor.enemyTuningBridge)
        for (enemy in ENEMY_DEFS) {
            val pc = parser.snesToPc(0xA00000 or enemy.speciesId)
            val pointer = parser.readUInt16At(pc + 0x3C)
            if (pointer in 0x8000..0xFFEA) for (slot in 0..21) {
                assertEquals(fixture.rom[parser.snesToPc(0xB40000 or pointer) + slot].toInt() and 255,
                    tuning.catalog.original("${enemy.key}_vuln$slot").value, "${enemy.name}, vulnerability $slot")
            }
        }
        val patch = editor.findOrCreateConfigPatch("enemy_vuln")
        patch.configData = mutableMapOf("zoomer_vuln0" to 4, "geemer_horiz_vuln0" to 6)
        val original = workspace.projectSourceText("bank_B4.asm")
        editor.setPatchConfigData(patch.id, "zoomer_vuln0", 255)
        editor.setPatchConfigData(patch.id, "zoomer_vuln1", 128)
        editor.setPatchConfigData(patch.id, "zoomer_vuln21", 130)
        assertNull(patch.configData)
        assertEquals(255, tuning.view("geemer_horiz_vuln0").value)
        val consumers = tuning.catalog.tableConsumers("zoomer_vuln0", tuning.catalog.originalHeaders, original)
        assertTrue(consumers.size > 2, "Include native species beyond the edited row")
        val logs = mutableListOf<String>()
        suspend fun build(candidate: SmEditProject = project): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(candidate, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val first = assertNotNull(build(), logs.joinToString("\n"))
        val pointerPc = parser.snesToPc(0xA0DCFF) + 0x3C
        val oldPointer = parser.readUInt16At(pointerPc)
        val tablePc = parser.snesToPc(0xB40000 or oldPointer)
        assertEquals(255, first.resultRom[tablePc].toInt() and 255)
        assertEquals(128, first.resultRom[tablePc + 1].toInt() and 255)
        assertEquals(130, first.resultRom[tablePc + 21].toInt() and 255)
        val text = workspace.projectSourceText("bank_B4.asm")
        val raw = tuning.catalog.edit("zoomer_vuln21", 129, workspace.projectSourceText("bank_A0.asm"), text).second
        assertTrue(workspace.updateSourceEditText("bank_B4.asm", raw))
        assertEquals(129, tuning.view("zoomer_vuln21").value)
        assertTrue(workspace.undoSourceEdit("bank_B4.asm"), workspace.error)
        assertEquals(130, tuning.view("zoomer_vuln21").value)
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)

        // Move the complete shared table; all actual native pointer words must follow it.
        val tableName = tuning.catalog.original("zoomer_vuln0").table!!
        val table = assertNotNull(Regex("(?m)^${Regex.escape(tableName)}:[^\\r\\n]*\\r?\\n(?:[ \\t]*db[^\\r\\n]*\\r?\\n){2}").find(text))
        val movedPc = parser.snesToPc(0xB4FF00)
        assertTrue(fixture.rom.copyOfRange(movedPc, movedPc + 22).all { it == 0xFF.toByte() })
        val relocated = text.replaceRange(table.range, "org ${'$'}B4FF00\n${table.value}org ${'$'}B4${(oldPointer + 22).toString(16).uppercase()}\n")
        assertTrue(workspace.updateSourceEditText("bank_B4.asm", relocated))
        val moved = assertNotNull(build(), logs.joinToString("\n"))
        val movedParser = RomParser(moved.resultRom)
        for (species in consumers) assertEquals(0xFF00, movedParser.readUInt16At(parser.snesToPc(0xA00000 or species) + 0x3C))
        for (slot in 0..21) assertEquals(first.resultRom[tablePc + slot], moved.resultRom[movedPc + slot])
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        ProjectFileService.clearAsmBuildCachesForTests()
        assertContentEquals(moved.resultRom, assertNotNull(build(cold), logs.joinToString("\n")).resultRom)
        assertNull(RomExporter(project, parser, logs::add).build(), "A ROM-only export cannot ignore authored vulnerability source")
        val conflict = SmPatch("vulnerability-unchanged", "Cannot overwrite unchanged relocated bytes", enabled = true,
            writes = mutableListOf(PatchWrite((movedPc + 2).toLong(), listOf(2), expectedBytes = listOf(2))))
        project.patches.add(conflict)
        assertTrue(build() == null, logs.joinToString("\n")); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        conflict.enabled = false
        patch.enabled = true
        patch.configData = mutableMapOf("zoomer_vuln1" to 128)
        assertTrue(build() == null, logs.joinToString("\n")); assertTrue(logs.any { "both authored ASM" in it }, logs.joinToString("\n"))
        patch.configData = null
        assertTrue(workspace.undoSourceEdit("bank_B4.asm"), workspace.error)
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `shared native door records compile once update every consumer and protect unchanged bytes after reopen and reset`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val rom = com.supermetroid.editor.asm.saveSourceTestRom()
        com.supermetroid.editor.asm.DOOR_ARRIVAL_DISPATCH_BYTES.copyInto(rom, 0x7E8A3)
        // Real erased bank tails for the structural list's new DoorDef/layout allocations.
        rom.fill(0xFF.toByte(), 0x1F000, 0x20000)
        rom.fill(0xFF.toByte(), 0x207000, 0x208000)
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        com.supermetroid.editor.rom.LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        rom.copyOfRange(0x791F8, 0x791F8 + 44).copyInto(rom, 0x79879)
        val fixture = AsmProjectTestFixture.synthetic(tempDirectory, rom)
        val parser = RomParser(rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(catalog, workspace)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        editor.enableSharedDoorEditing(0x8000)
        editor.updateDoor(0, editor.doorEntries.single().copy(distFromDoor = 0x1234))
        assertEquals(0x1234, editor.doorEntries.single().distFromDoor, editor.statusMessage)
        assertTrue(editor.createManagedDoorArrival(0), editor.statusMessage)
        editor.loadRoom(0x9879, parser, parser.readRoomHeader(0x9879)!!)
        assertEquals(0x1234, editor.doorEntries.single().distFromDoor)
        assertTrue(editor.ensureDoorSource("Second shared consumer"), editor.statusMessage)
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error); logs.clear()
            return ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val built = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0x1234, RomParser(built.resultRom).parseDoorEntry(0x8060, 0)!!.distFromDoor)
        val symbols = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!.symbols.labels
        for (label in listOf("SmeditDoors_91F8_Door_0_Entry", "SmeditDoors_9879_Door_0_Entry", "SmeditSharedDoors_8000_Entry")) assertEquals(0x838000, symbols[label])
        assertEquals(symbols.getValue("SmeditArrival_91F8_Door_0_Entry") and 65535, RomParser(built.resultRom).parseDoorEntry(0x8060, 0)!!.entryCode)
        val snapshot = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val cold = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(snapshot)
        val coldWorkspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val coldBridge = AsmPlmSourceBridge(catalog, coldWorkspace, false) { cold }
        assertEquals(0x1234, coldBridge.doors.view(0x9879).resource!!.entries.single().change.distFromDoor)
        assertTrue(editor.makeDoorIndependent(0, 0), editor.statusMessage)
        val independent = assertNotNull(build(), logs.joinToString("\n"))
        val independentParser = RomParser(independent.resultRom)
        val privateDoor = independentParser.parseDoorList(independentParser.readRoomHeader(0x9879)!!.doorOut).single()
        assertNotEquals(0x8000, privateDoor.doorDefPtr)
        assertEquals(0x1234, privateDoor.distFromDoor)
        assertEquals(0x8000, independentParser.parseDoorList(independentParser.readRoomHeader(0x91F8)!!.doorOut).single().doorDefPtr)
        assertEquals(0x8000, independentParser.readSaveEntry(0, 0)!!.doorPtr)
        val privateSymbols = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!.symbols.labels
        assertEquals(0x830000 or privateDoor.doorDefPtr, privateSymbols["SmeditDoors_9879_Door_0_Entry"])
        assertEquals(0x838000, privateSymbols["SmeditSharedDoors_8000_Entry"])
        ProjectFileService.clearAsmBuildCachesForTests()
        val reopenedPrivate = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        assertContentEquals(independent.resultRom, assertNotNull(ProjectFileService.buildRom(reopenedPrivate, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n")).resultRom)
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(built.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(editor.resetCurrentRoomToOriginal(parser), editor.statusMessage)
        val reset = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0x1234, RomParser(reset.resultRom).parseDoorEntry(0x8060, 0)!!.distFromDoor, "Room reset must not undo a globally shared record")
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(built.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        editor.addProjectRoomDoor(0x91F8, 0x91F8, 0, 0, 0, parser)
        assertEquals(2, editor.doorEntries.size, editor.statusMessage)
        val grown = RomParser(assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val relocatedList = grown.readRoomHeader(0x91F8)!!.doorOut
        assertNotEquals(0x8060, relocatedList)
        assertEquals(0x8000, grown.parseDoorList(relocatedList).first().doorDefPtr)
        assertEquals(0x8060, grown.readRoomHeader(0x9879)!!.doorOut)
        assertEquals(0x1234, grown.parseDoorEntry(0x8060, 0)!!.distFromDoor)
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(built.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        editor.loadRoom(0x9879, parser, parser.readRoomHeader(0x9879)!!)
        assertTrue(editor.resetCurrentRoomToOriginal(parser), editor.statusMessage)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.resetCurrentRoomToOriginal(parser), editor.statusMessage)
        assertTrue(project.rooms.values.none { it.asmDoorSourceVersion != 0 })
        assertContentEquals(built.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom,
            "Global data and its managed arrival must survive resetting every room-local owner")
        project.patches += SmPatch("shared-unchanged-byte", "Cannot overwrite a shared field", enabled = true,
            writes = mutableListOf(PatchWrite(0x18004, listOf(0), expectedBytes = listOf(0))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        assertContentEquals(rom, fixture.romFile.readBytes())
    }

    @Test fun `structural doors allocate symbolically preserve native identities and rebuild cold add remove reset histories`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true, progressive = true) }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        editor.bindRoomSources(catalog, workspace)
        val native = assertNotNull(parser.readRoomHeader(0x91F8))
        val nativeDoors = parser.parseDoorList(native.doorOut)
        editor.loadRoom(native.roomId, parser, native)
        repeat(2) { editor.addProjectRoomDoor(native.roomId, native.roomId, 0, 0, 0, parser) }
        val keptId = nativeDoors.size + 1
        assertTrue(editor.createManagedDoorArrival(keptId), editor.statusMessage)
        val conditional = project.rooms.getValue(project.roomKey(native.roomId)).states.first {
            it.sourceCondition?.kind != ProjectRoomStateConditionKind.DEFAULT
        }.id
        val reference = com.supermetroid.editor.data.ProjectSourceDoorReference(native.roomId, keptId)
        editor.setRoomStateCondition(conditional, projectRoomStateCondition(ProjectRoomStateConditionKind.ALL_OF, children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, sourceDoor = reference),
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7, negated = true))), parser)
        editor.addRoomState(conditional, projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR,
            negated = true, sourceDoor = reference), parser)
        val fxState = editor.currentPlmStateId()!!
        editor.setRoomStateDoorFxChange(fxState, 0, com.supermetroid.editor.data.FxChange(fxType = 4, liquidSurfaceStart = 0x0120), parser, reference)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        editor.addPlm(0xB76F, 8, 8, 0x8000, ItemStateScope.THIS_STATE)
        val station = assertNotNull(editor.workingPlms.singleOrNull { it.id == 0xB76F }, editor.statusMessage)
        val saveSlot = station.param and 255
        assertTrue(editor.updateSaveStationIncomingDoor(station,
            editor.incomingDoorConditionOptions(native.roomId, parser).single { it.sourceDoor == reference }), editor.statusMessage)
        editor.updateSaveStationSpawnFields(0, saveSlot, -32, 104, 256, 0, parser)
        val json = kotlinx.serialization.json.Json
        assertTrue(workspace.saveAllSources(), workspace.error)
        val cold = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val cw = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true, progressive = true) }
        val ce = EditorState().apply { testMode = true; initForProject(fixture.projectFile, cold) }
        val cb = ce.bindRoomSources(catalog, cw)
        ce.loadRoom(native.roomId, parser, native)
        val map = MinimapEditorState().apply { loadArea(parser, native.area, ce, selectRoomId = native.roomId) }
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(cw.saveAllSources(), cw.error); logs.clear()
            return ProjectFileService.buildRom(json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), cold)), parser,
                logs::add, logs::add, fixture.projectFile.path)
        }
        fun verify(result: RomBuildResult, count: Int) {
            val built = RomParser(result.resultRom)
            val h = assertNotNull(built.readRoomHeader(native.roomId))
            assertNotEquals(native.doorOut, h.doorOut)
            val entries = built.parseDoorList(h.doorOut)
            assertEquals(count, entries.size)
            assertEquals(nativeDoors, entries.take(nativeDoors.size))
            val labels = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path)).symbols.labels
            assertEquals(labels.getValue("SmeditDoors_91F8_Door_${keptId}_Entry") and 65535, entries.last().doorDefPtr)
            assertEquals(labels.getValue("SmeditArrival_91F8_Door_${keptId}_Entry") and 65535, entries.last().entryCode)
            val condition = built.inspectRoomStates(native.roomId).states.first().condition
            assertEquals(com.supermetroid.editor.rom.RoomStateConditionKind.ALL_OF, condition.kind)
            assertEquals(com.supermetroid.editor.rom.RoomStateConditionKind.INCOMING_DOOR, condition.children.first().kind)
            assertEquals(entries.last().doorDefPtr, condition.children.first().argument)
            assertEquals(7, condition.children.last().argument); assertTrue(condition.children.last().negated)
            val invertedDoor = built.inspectRoomStates(native.roomId).states.single {
                it.condition.kind == com.supermetroid.editor.rom.RoomStateConditionKind.INCOMING_DOOR && it.condition.negated
            }.condition
            assertEquals(entries.last().doorDefPtr, invertedDoor.argument)
            val fx = built.parseRoomStatesWithData(native.roomId).flatMap { built.parseFxEntries(it.fxPtr) }
                .filter { it.doorSelect == entries.last().doorDefPtr }
            assertTrue(fx.isNotEmpty()); assertTrue(fx.all { it.fxType == 4 && it.liquidSurfaceStart == 0x0120 })
            val save = assertNotNull(built.readSaveEntry(0, saveSlot))
            assertEquals(entries.last().doorDefPtr, save.doorPtr)
            assertEquals(0xFFE0, save.samusX); assertEquals(256, save.scrollX)
            assertEquals(reference, cb.saveSpawn(native.roomId, 0, saveSlot)!!.sourceDoor)
            assertEquals(0, built.readUInt16At(built.snesToPc(0x8F0000 or h.doorOut) + count * 2))
            assertContentEquals(fixture.rom.copyOfRange(parser.snesToPc(0x8F0000 or native.doorOut), parser.snesToPc(0x8F0000 or native.doorOut) + nativeDoors.size * 2),
                result.resultRom.copyOfRange(parser.snesToPc(0x8F0000 or native.doorOut), parser.snesToPc(0x8F0000 or native.doorOut) + nativeDoors.size * 2))
            for (info in parser.roomCatalog.rooms) {
                val room = parser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
                assertEquals(parser.parseDoorList(room.doorOut), built.parseDoorList(room.doorOut), "Native DoorDefs remain at their original identities")
            }
            assertContentEquals(effectiveMinimapData(parser, cold, native.area).tiles, built.readMinimapTiles(native.area).tiles)
        }
        var added = assertNotNull(build(), logs.joinToString("\n")); verify(added, nativeDoors.size + 2)
        val initialBuilt = RomParser(added.resultRom)
        val initialPointer = initialBuilt.parseDoorList(initialBuilt.readRoomHeader(native.roomId)!!.doorOut).last().doorDefPtr
        // Occupy the old allocation in authored ASM. Both the list and the nested
        // condition must follow the DoorDef's newly planned address on the next build.
        val bank83 = cb.doors.sourceText()
        cw.replaceProjectSource(FX_SOURCE_FILE, bank83, bank83 + "\norg \$83${initialPointer.toString(16).uppercase()}\n db \$42 ; reserve old DoorDef location\n")
        added = assertNotNull(build(), logs.joinToString("\n")); verify(added, nativeDoors.size + 2)
        val relocated = RomParser(added.resultRom)
        assertNotEquals(initialPointer, relocated.parseDoorList(relocated.readRoomHeader(native.roomId)!!.doorOut).last().doorDefPtr)
        ce.removeProjectRoomDoor(native.roomId, nativeDoors.size)
        val removed = assertNotNull(build(), logs.joinToString("\n")); verify(removed, nativeDoors.size + 1)
        assertTrue(cw.undoSourceEdit(com.supermetroid.editor.asm.DOOR_SOURCE_FILE), cw.error)
        assertContentEquals(added.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        map.redo(ce)
        assertContentEquals(removed.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val current = RomParser(removed.resultRom)
        val list = current.readRoomHeader(native.roomId)!!.doorOut
        val terminator = current.snesToPc(0x8F0000 or list) + (nativeDoors.size + 1) * 2
        cold.patches += SmPatch("door-list-conflict", "Cannot replace an owned list terminator", enabled = true,
            writes = mutableListOf(PatchWrite(terminator.toLong(), listOf(0), expectedBytes = listOf(0))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        cold.patches.removeLast()
        assertTrue(ce.resetCurrentRoomToOriginal(parser), ce.statusMessage)
        val reset = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(native, RomParser(reset.resultRom).readRoomHeader(native.roomId))
        assertEquals(nativeDoors, RomParser(reset.resultRom).parseDoorList(native.doorOut))
        assertFalse(cb.doors.sourceText().contains("macro SmeditDoors_91F8_List()"))
        assertTrue(cw.undoSourceEdit(com.supermetroid.editor.asm.DOOR_SOURCE_FILE), cw.error)
        assertContentEquals(removed.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `coordinated resize authors incoming arrivals preserves native code and rebuilds cold reciprocal geometry`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(catalog, workspace)
        val native = assertNotNull(parser.readRoomHeader(0x91F8)) // Landing Site: 9x5 -> the 50-screen boundary.
        val neighbor = assertNotNull(parser.readRoomHeader(0x92B3)) // Gauntlet Entrance, native scroll $13 -> blue.
        editor.loadRoom(native.roomId, parser, native)
        project.getOrCreateRoom(neighbor.roomId).ensureStateManifest(parser)
        bridge.doors.migrate(neighbor.roomId, neighbor.name)
        val entrance = parser.parseDoorList(neighbor.doorOut).first { it.destRoomPtr == native.roomId && it.entryCode != 0 }
        val index = parser.parseDoorList(neighbor.doorOut).indexOf(entrance)
        val nativeWrites = com.supermetroid.editor.rom.analyzeDoorScrollAsm(parser, entrance.entryCode).writes
        assertTrue(nativeWrites.any { it.screenIndex >= native.width })
        val beforeModel = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val before8F = bridge.sourceText(); val before83 = bridge.doors.sourceText()
        assertTrue(editor.resizeRoom(native.width, native.height, native.width + 1, native.height), editor.statusMessage)
        val label = assertNotNull(bridge.doors.view(neighbor.roomId).resource).entries[index].arrival!!.label
        val macro = label.removeSuffix("_Entry")
        val adopted8F = bridge.sourceText(); val adopted83 = bridge.doors.sourceText()
        assertTrue(workspace.undoSourceEdit(FX_SOURCE_FILE), workspace.error)
        assertEquals(beforeModel, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        assertEquals(before8F, bridge.sourceText()); assertEquals(before83, bridge.doors.sourceText())
        assertTrue(editor.redo(), editor.statusMessage)
        assertEquals(adopted8F, bridge.sourceText()); assertEquals(adopted83, bridge.doors.sourceText())
        val body = authoredMacroBody(bridge.sourceText(), macro)
        // A user source edit remains authoritative through save/reopen and the next resize.
        assertContains(body, "lda.b #\$01")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bridge.sourceText(), bridge.sourceText().replaceFirst(body,
            body.replaceFirst("lda.b #\$01", "lda.b #\$02 ; authored arrival color")))
        assertTrue(workspace.saveAllSources(), workspace.error)
        val json = kotlinx.serialization.json.Json
        val cold = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val reopenedWorkspace = AsmWorkspaceState()
        reopenedWorkspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val reopenedEditor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, cold) }
        val reopenedBridge = reopenedEditor.bindRoomSources(catalog, reopenedWorkspace)
        reopenedEditor.loadRoom(native.roomId, parser, native)
        val map = MinimapEditorState().apply { loadArea(parser, native.area, reopenedEditor, selectRoomId = native.roomId) }
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(reopenedWorkspace.saveAllSources(), reopenedWorkspace.error)
            logs.clear()
            val headless = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), cold))
            return ProjectFileService.buildRom(headless, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        fun verify(result: RomBuildResult, width: Int) {
            val built = RomParser(result.resultRom)
            assertEquals(width, built.readRoomHeader(native.roomId)!!.width)
            val labels = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path)).symbols.labels
            val compiledDoor = built.parseDoorList(neighbor.doorOut)[index]
            assertEquals(labels.getValue(label) and 65535, compiledDoor.entryCode)
            assertEquals(entrance.copy(entryCode = compiledDoor.entryCode), compiledDoor)
            // The known dispatch-context prefix is LDA.w #native; STA.b $12. The
            // remaining emitted instructions must write precisely the resized coordinates.
            val expected = nativeWrites.map { it.copy(scrollValue = 2,
                addressLowByte = 0x20 + (it.screenIndex / native.width) * width + it.screenIndex % native.width) }
            assertEquals(expected, com.supermetroid.editor.rom.analyzeDoorScrollAsm(built, compiledDoor.entryCode + 5).writes)
            val pc = parser.snesToPc(0x8F0000 or entrance.entryCode)
            assertContentEquals(fixture.rom.copyOfRange(pc, pc + 11), result.resultRom.copyOfRange(pc, pc + 11), "Never patch the native routine in place")
            for (info in parser.roomCatalog.rooms) {
                val source = assertNotNull(parser.readRoomHeader(info.getRoomIdAsInt()))
                parser.parseDoorList(source.doorOut).zip(built.parseDoorList(source.doorOut)).forEach { (old, current) ->
                    assertEquals(old.copy(entryCode = if (old.doorDefPtr == entrance.doorDefPtr) compiledDoor.entryCode else old.entryCode), current)
                }
            }
            assertContentEquals(effectiveMinimapData(parser, cold, native.area).tiles, built.readMinimapTiles(native.area).tiles)
            assertContentEquals(effectiveMapStationData(parser, cold, native.area).revealed, built.readMapStationData(native.area).revealed)
            assertContains(reopenedBridge.sourceText(), "; authored arrival color")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        val grownModel = json.encodeToString(SmEditProject.serializer(), cold)
        val grown8F = reopenedBridge.sourceText(); val grown83 = reopenedBridge.doors.sourceText()
        val grown = assertNotNull(build(), logs.joinToString("\n")); verify(grown, native.width + 1)
        assertTrue(reopenedEditor.resizeRoom(native.width + 1, native.height, native.width, native.height), reopenedEditor.statusMessage)
        val returnedModel = json.encodeToString(SmEditProject.serializer(), cold)
        val returned8F = reopenedBridge.sourceText(); val returned83 = reopenedBridge.doors.sourceText()
        val returned = assertNotNull(build(), logs.joinToString("\n")); verify(returned, native.width)
        assertTrue(reopenedWorkspace.undoSourceEdit(FX_SOURCE_FILE), reopenedWorkspace.error)
        assertEquals(grownModel, json.encodeToString(SmEditProject.serializer(), cold))
        assertEquals(grown8F, reopenedBridge.sourceText()); assertEquals(grown83, reopenedBridge.doors.sourceText())
        assertContentEquals(grown.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        map.redo(reopenedEditor)
        assertEquals(returnedModel, json.encodeToString(SmEditProject.serializer(), cold))
        assertEquals(returned8F, reopenedBridge.sourceText()); assertEquals(returned83, reopenedBridge.doors.sourceText())
        assertContentEquals(returned.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val actual = RomParser(returned.resultRom).parseDoorList(neighbor.doorOut)[index]
        val arrivalPc = parser.snesToPc(0x8F0000 or actual.entryCode)
        cold.patches += SmPatch("arrival-resize-conflict", "Cannot replace source-owned arrival code", enabled = true,
            writes = mutableListOf(PatchWrite(arrivalPc.toLong(), listOf(0xEA), expectedBytes = listOf(returned.resultRom[arrivalPc].toInt() and 255))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        cold.patches.removeLast()
        assertContentEquals(returned.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(reopenedEditor.resizeRoom(native.width, native.height, native.width + 1, native.height), reopenedEditor.statusMessage)
        val beforeReset = json.encodeToString(SmEditProject.serializer(), cold)
        val beforeReset8F = reopenedBridge.sourceText(); val beforeReset83 = reopenedBridge.doors.sourceText()
        assertTrue(reopenedEditor.resetCurrentRoomToOriginal(parser), reopenedEditor.statusMessage)
        assertFalse(cold.rooms.getValue(cold.roomKey(native.roomId)).hasEdits)
        val reset = assertNotNull(build(), logs.joinToString("\n")); verify(reset, native.width)
        assertEquals(native, RomParser(reset.resultRom).readRoomHeader(native.roomId))
        assertTrue(reopenedWorkspace.undoSourceEdit(FX_SOURCE_FILE), reopenedWorkspace.error)
        assertEquals(beforeReset, json.encodeToString(SmEditProject.serializer(), cold))
        assertEquals(beforeReset8F, reopenedBridge.sourceText()); assertEquals(beforeReset83, reopenedBridge.doors.sourceText())
        verify(assertNotNull(build(), logs.joinToString("\n")), native.width + 1)
        map.redo(reopenedEditor)
        assertContentEquals(reset.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
    }

    @Test fun `relocated room reset reopens headless returns native data and builds exact reciprocal undo redo`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = { com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it) })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        val bridge = editor.bindRoomSources(catalog, workspace)
        val native = assertNotNull(parser.readRoomHeader(0xB167))
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.createManagedDoorArrival(0), editor.statusMessage)
        val ownArrival = assertNotNull(editor.doorArrivalBindings[0]).label.removeSuffix("_Entry")
        val neighbor = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.first { candidate ->
            candidate.roomId != native.roomId && parser.parseDoorList(candidate.doorOut).any { it.destRoomPtr == native.roomId }
        }
        val incomingIndex = parser.parseDoorList(neighbor.doorOut).indexOfFirst { it.destRoomPtr == native.roomId }
        bridge.doors.createManagedArrival(neighbor.roomId, incomingIndex, neighbor.name)
        val arrival = assertNotNull(bridge.doors.view(neighbor.roomId).resource).entries[incomingIndex].arrival!!.label
        val arrivalBody = authoredMacroBody(bridge.sourceText(), arrival.removeSuffix("_Entry"))
        val map = MinimapEditorState().apply { loadArea(parser, native.area, editor, selectRoomId = native.roomId) }
        assertTrue(map.reassignSelectedRoomArea(1, editor)!!.success, editor.statusMessage)
        val migrated = bridge.saveResources(native.roomId).single { !it.cleared }
        // This edit is newer than the move, but belongs to the neighboring room.
        val neighborDoor = assertNotNull(bridge.doors.view(neighbor.roomId).resource).entries[incomingIndex].entry()
        bridge.doors.edit(neighbor.roomId, incomingIndex, neighborDoor.copy(distFromDoor = 0x1234))
        assertTrue(workspace.saveAllSources(), workspace.error)
        val json = kotlinx.serialization.json.Json
        val cold = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val reopenedWorkspace = AsmWorkspaceState(sourceIndexLoader = { com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it) })
        reopenedWorkspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val reopenedEditor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, cold) }
        val reopenedBridge = reopenedEditor.bindRoomSources(catalog, reopenedWorkspace)
        reopenedEditor.loadRoom(native.roomId, parser, native)
        assertTrue(reopenedEditor.undoStack.isEmpty(), "Reset must not need session move history")
        val beforeModel = json.encodeToString(SmEditProject.serializer(), cold)
        val before8F = reopenedBridge.sourceText(); val before83 = reopenedBridge.doors.sourceText()
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(reopenedWorkspace.saveAllSources(), reopenedWorkspace.error)
            logs.clear()
            val headless = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), cold))
            return ProjectFileService.buildRom(headless, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val movedBuild = assertNotNull(build(), logs.joinToString("\n"))
        assertTrue(reopenedEditor.resetCurrentRoomToOriginal(parser), reopenedEditor.statusMessage)
        val afterModel = json.encodeToString(SmEditProject.serializer(), cold)
        val after8F = reopenedBridge.sourceText(); val after83 = reopenedBridge.doors.sourceText()
        assertFalse(cold.rooms.getValue(cold.roomKey(native.roomId)).hasEdits)
        assertFalse(after8F.contains("macro $ownArrival()"), "Reset retires this room's own managed arrival, not its neighbor's")
        assertEquals(arrivalBody, authoredMacroBody(after8F, arrival.removeSuffix("_Entry")))
        val resetBuild = assertNotNull(build(), logs.joinToString("\n"))
        val built = RomParser(resetBuild.resultRom)
        assertEquals(native, built.readRoomHeader(native.roomId))
        assertEquals(parser.parseDoorList(native.doorOut), built.parseDoorList(native.doorOut))
        val neighborBuilt = built.parseDoorList(neighbor.doorOut)[incomingIndex]
        val neighborNative = parser.parseDoorList(neighbor.doorOut)[incomingIndex]
        val labels = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path)).symbols.labels
        assertEquals(neighborNative.copy(distFromDoor = 0x1234, entryCode = labels.getValue(arrival) and 65535), neighborBuilt)
        for (area in setOf(native.area, 1)) {
            assertContentEquals(effectiveMinimapData(parser, cold, area).tiles, built.readMinimapTiles(area).tiles)
            assertContentEquals(effectiveMapStationData(parser, cold, area).revealed, built.readMapStationData(area).revealed)
            for (slot in 0 until parser.saveStationSlotCount(area))
                assertEquals(parser.readSaveEntry(area, slot)!!.words(), built.readSaveEntry(area, slot)!!.words())
        }
        assertEquals(parser.readSaveEntry(1, migrated.slot)!!.words(), built.readSaveEntry(1, migrated.slot)!!.words())
        assertTrue(reopenedWorkspace.undoSourceEdit(FX_SOURCE_FILE), reopenedWorkspace.error)
        assertEquals(beforeModel, json.encodeToString(SmEditProject.serializer(), cold))
        assertEquals(before8F, reopenedBridge.sourceText()); assertEquals(before83, reopenedBridge.doors.sourceText())
        assertContentEquals(movedBuild.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(reopenedEditor.redo(), reopenedEditor.statusMessage)
        assertEquals(afterModel, json.encodeToString(SmEditProject.serializer(), cold))
        assertEquals(after8F, reopenedBridge.sourceText()); assertEquals(after83, reopenedBridge.doors.sourceText())
        assertContentEquals(resetBuild.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `same area room drags build exact maps and reciprocal header history without rewriting saves or arrival code`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = { com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it) })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0xB167)) // Frog Savestation exercises saves and executable arrivals too.
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        val savePlm = editor.workingPlms.single { it.id == 0xB76F }
        assertTrue(editor.ensureSaveSpawnSource(savePlm), editor.statusMessage)
        assertTrue(editor.createManagedDoorArrival(0), editor.statusMessage)
        val arrival = assertNotNull(editor.doorArrivalBindings[0]).label
        val arrivalBody = authoredMacroBody(bridge.sourceText(), arrival.removeSuffix("_Entry"))
        val save = bridge.saveResources(native.roomId).single()
        val logs = mutableListOf<String>()
        val json = kotlinx.serialization.json.Json
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error)
            logs.clear()
            val headless = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
            return ProjectFileService.buildRom(headless, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val baseline = assertNotNull(build(), logs.joinToString("\n"))
        val baselineRom = RomParser(baseline.resultRom)
        val map = MinimapEditorState().apply { loadArea(parser, native.area, editor, selectRoomId = native.roomId) }
        val beforeModel = json.encodeToString(SmEditProject.serializer(), project)
        val before8F = bridge.sourceText(); val before83 = bridge.doors.sourceText()
        val ownership = roomMapOwnershipMask(native, map.areaRooms)
        val cleared = clearRoomTiles(map.mapData, native.mapX, native.mapY, native.width, native.height, ownership)
        val clearedReveal = clearMapStationRect(map.stationData, native.mapX, native.mapY, native.width, native.height, ownership)
        val target = assertNotNull(findAvailableRoomMapPosition(cleared, clearedReveal,
            map.areaRooms.filter { it.roomId != native.roomId }, native.width, native.height, 50, 25))
        assertNotEquals(native.mapX to native.mapY, target)
        assertTrue(map.beginPrimaryRoomDrag(native.mapX, native.mapY, editor))
        map.endPrimaryRoomDrag(target.first, target.second, editor)
        assertNull(map.moveBuffer); assertNull(map.selectedRoom)
        assertEquals(1, project.rooms.getValue(project.roomKey(native.roomId)).asmRoomPropertiesSourceVersion)
        assertEquals(before83, bridge.doors.sourceText()); assertEquals(arrivalBody, authoredMacroBody(bridge.sourceText(), arrival.removeSuffix("_Entry")))
        assertEquals(save, bridge.saveResources(native.roomId).single())
        val afterModel = json.encodeToString(SmEditProject.serializer(), project)
        val after8F = bridge.sourceText()
        val moved = assertNotNull(build(), logs.joinToString("\n"))
        val built = RomParser(moved.resultRom)
        assertEquals(target.first, built.readRoomHeader(native.roomId)?.mapX); assertEquals(target.second, built.readRoomHeader(native.roomId)?.mapY)
        assertEquals(native.area, built.readRoomHeader(native.roomId)?.area)
        for (info in parser.roomCatalog.rooms) {
            val old = assertNotNull(baselineRom.readRoomHeader(info.getRoomIdAsInt()))
            val current = assertNotNull(built.readRoomHeader(info.getRoomIdAsInt()))
            assertEquals(old.copy(mapX = if (old.roomId == native.roomId) target.first else old.mapX,
                mapY = if (old.roomId == native.roomId) target.second else old.mapY), current)
            assertEquals(baselineRom.parseDoorList(old.doorOut), built.parseDoorList(current.doorOut))
        }
        assertEquals(save.words, built.readSaveEntry(save.area, save.slot)!!.words())
        assertContentEquals(effectiveMinimapData(parser, project, native.area).tiles, built.readMinimapTiles(native.area).tiles)
        assertContentEquals(effectiveMapStationData(parser, project, native.area).revealed, built.readMapStationData(native.area).revealed)
        assertEquals(after8F, bridge.sourceText(), "Build must not reauthor the dragged room")
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertEquals(beforeModel, json.encodeToString(SmEditProject.serializer(), project)); assertEquals(before8F, bridge.sourceText())
        assertContentEquals(baseline.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        map.redo(editor)
        assertEquals(afterModel, json.encodeToString(SmEditProject.serializer(), project)); assertEquals(after8F, bridge.sourceText())
        assertContentEquals(moved.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(editor.undo(), editor.statusMessage)
        assertEquals(beforeModel, json.encodeToString(SmEditProject.serializer(), project))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `source owned area moves preserve arrival code migrate saves and build exact undo redo maps and door flags`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = { com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it) })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0xB167)) // Frog Savestation, an ordinary save rather than a load/elevator record.
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureRoomPropertiesSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.createManagedDoorArrival(0), editor.statusMessage)
        val arrival = assertNotNull(editor.doorArrivalBindings[0]).label
        val arrivalBody = authoredMacroBody(bridge.sourceText(), arrival.removeSuffix("_Entry"))
        val savePlm = editor.workingPlms.single { it.id == 0xB76F }
        val oldSave = assertNotNull(parser.readSaveEntry(native.area, savePlm.param and 255))
        val nativeRooms = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.associateBy { it.roomId }
        val incoming = nativeRooms.values.filter { parser.parseDoorList(it.doorOut).any { door -> door.destRoomPtr == native.roomId } }
        val neighbor = incoming.first()
        project.getOrCreateRoom(neighbor.roomId).ensureStateManifest(parser)
        bridge.doors.migrate(neighbor.roomId, neighbor.name) // Include an already-authored incoming connection.
        val map = MinimapEditorState().apply { loadArea(parser, native.area, editor, selectRoomId = native.roomId) }
        val json = kotlinx.serialization.json.Json
        val beforeModel = json.encodeToString(SmEditProject.serializer(), project)
        val before8F = bridge.sourceText(); val before83 = bridge.doors.sourceText()
        val result = assertNotNull(map.reassignSelectedRoomArea(1, editor))
        assertTrue(result.success, result.message)
        assertEquals(arrivalBody, authoredMacroBody(bridge.sourceText(), arrival.removeSuffix("_Entry")))
        assertEquals(arrival, editor.doorArrivalBindings[0]?.label)
        val liveSave = bridge.saveResources(native.roomId).single { !it.cleared }
        assertEquals(1, liveSave.area); assertEquals(oldSave.words(), liveSave.words)
        val afterModel = json.encodeToString(SmEditProject.serializer(), project)
        val after8F = bridge.sourceText(); val after83 = bridge.doors.sourceText()
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error)
            logs.clear()
            val reopened = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
            return ProjectFileService.buildRom(reopened, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val moved = assertNotNull(build(), logs.joinToString("\n"))
        val built = RomParser(moved.resultRom)
        val header = assertNotNull(built.readRoomHeader(native.roomId))
        assertEquals(1, header.area); assertEquals(result.destinationMapX, header.mapX); assertEquals(result.destinationMapY, header.mapY)
        assertEquals(List(7) { 0 }, built.readSaveEntry(native.area, savePlm.param and 255)!!.words())
        assertEquals(oldSave.words(), built.readSaveEntry(1, liveSave.slot)!!.words())
        assertEquals((savePlm.param and 0xFF00) or liveSave.slot, built.getAllPlmEntriesForRoom(native.roomId).single { it.id == 0xB76F }.param)
        val labels = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path)).symbols.labels
        assertEquals(labels.getValue(arrival) and 65535, built.parseDoorList(native.doorOut)[0].entryCode)
        for (source in nativeRooms.values) {
            val old = parser.parseDoorList(source.doorOut)
            val current = built.parseDoorList(source.doorOut)
            old.zip(current).forEachIndexed { index, (before, after) ->
                assertEquals(before.doorDefPtr, after.doorDefPtr)
                val expectedArrival = if (source.roomId == native.roomId && index == 0) labels.getValue(arrival) and 65535 else before.entryCode
                assertEquals(before.copy(bitflag = after.bitflag, entryCode = expectedArrival), after)
                if (source.roomId == native.roomId || before.destRoomPtr == native.roomId) {
                    val sourceArea = if (source.roomId == native.roomId) 1 else source.area
                    val target = nativeRooms[before.destRoomPtr] ?: return@forEachIndexed
                    val destinationArea = if (target.roomId == native.roomId) 1 else target.area
                    assertEquals(sourceArea != destinationArea, after.bitflag and 0x40 != 0)
                    assertEquals(before.bitflag and 0x40.inv(), after.bitflag and 0x40.inv())
                }
            }
        }
        for (area in listOf(native.area, 1)) {
            assertContentEquals(effectiveMinimapData(parser, project, area).tiles, built.readMinimapTiles(area).tiles)
            assertContentEquals(effectiveMapStationData(parser, project, area).revealed, built.readMapStationData(area).revealed)
        }
        for (slot in listOf(oldSave.pcOffset, parser.readSaveEntry(1, liveSave.slot)!!.pcOffset)) {
            for (pc in slot until slot + 14) assertTrue(moved.writeReport.writes.any { pc in it.offset..it.endInclusive }, "Every released/live save byte must be claimed")
        }
        assertTrue(workspace.undoSourceEdit(FX_SOURCE_FILE), workspace.error)
        assertEquals(beforeModel, json.encodeToString(SmEditProject.serializer(), project))
        assertEquals(before8F, bridge.sourceText()); assertEquals(before83, bridge.doors.sourceText())
        val undone = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(native.area, RomParser(undone.resultRom).readRoomHeader(native.roomId)?.area)
        map.redo(editor)
        assertEquals(afterModel, json.encodeToString(SmEditProject.serializer(), project))
        assertEquals(after8F, bridge.sourceText()); assertEquals(after83, bridge.doors.sourceText())
        assertContentEquals(moved.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val sourceSavePc = oldSave.pcOffset
        project.patches += SmPatch("released-save-conflict", "Cannot reclaim the old AreaSave slot", enabled = true,
            writes = mutableListOf(PatchWrite(sourceSavePc.toLong(), listOf(1), expectedBytes = listOf(0))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        project.patches.removeLast()
        assertContentEquals(moved.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `source owned door connections assemble exact fields with reciprocal GUI history identities and patch guards`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = {
            com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it)
        })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        editor.loadRoom(native.roomId, parser, native)
        val nativeDoors = editor.doorEntries.toList()
        val navigation = RoomAsmNavigation(editor, workspace, { native.name })
        val json = kotlinx.serialization.json.Json
        val model = json.encodeToString(SmEditProject.serializer(), project)
        val revision = workspace.sourceBufferRevision
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS, door = 1)), editor.statusMessage)
        val preview = assertNotNull(workspace.roomSourceInspection)
        assertContains(preview.text, "SmeditDoors_9879_Door_1")
        assertEquals(revision, workspace.sourceBufferRevision)
        assertEquals(model, json.encodeToString(SmEditProject.serializer(), project))
        assertTrue(workspace.isProjectSourceEditable)
        val staged = workspace.sourceEditorText(FX_SOURCE_FILE)
        assertTrue(workspace.updateSourceEditText(FX_SOURCE_FILE, staged + "; Door source edited without an adoption click.\n"), editor.statusMessage)
        assertNull(workspace.roomSourceInspection)
        assertEquals(1, project.rooms.getValue("9879").asmDoorSourceVersion)
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureFxSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val fx = bridge.fx.view(native.roomId, room.states.first().id).resource
        editor.updateDoor(0, editor.doorEntries.first().copy(distFromDoor = 0x1234))
        assertEquals(0x1234, editor.doorEntries.first().distFromDoor)
        assertTrue(room.doorChanges.isEmpty(), "Door values must not be duplicated in JSON")
        assertFalse(json.encodeToString(SmEditProject.serializer(), project).contains("asmDoorProjection"))
        val authored = bridge.doors.sourceText()
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error)
            logs.clear()
            val headless = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
            return ProjectFileService.buildRom(headless, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        val first = assertNotNull(build(), logs.joinToString("\n"))
        val built = RomParser(first.resultRom)
        val doors = built.parseDoorList(assertNotNull(built.readRoomHeader(native.roomId)).doorOut)
        assertEquals(nativeDoors.first().copy(distFromDoor = 0x1234), doors.first())
        assertEquals(nativeDoors.drop(1), doors.drop(1))
        assertEquals(nativeDoors.map { it.doorDefPtr }, doors.map { it.doorDefPtr })
        val source = com.supermetroid.editor.asm.AsmDoorSource(bridge.doors.sourceText()).resource(room, parser)
        for (door in source.entries) {
            val pc = built.snesToPc(0x830000 or door.doorDefPtr)
            assertContentEquals(door.bytes(), first.resultRom.copyOfRange(pc, pc + 12))
            for (offset in pc until pc + 12) assertTrue(first.writeReport.writes.any {
                it.owner == "room-graph:project" && offset in it.offset..it.endInclusive
            }, "Complete DoorDef bytes must be claimed even when unchanged")
        }
        val generated = assertNotNull(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path))
        assertTrue(generated.lines.any { "%SmeditDoors_9879_Door_0()" in it })
        assertEquals(authored, bridge.doors.sourceText(), "Build must not rewrite the authored door fields")
        val raw = authored.replace("dw ${'$'}1234 ; Distance", "dw ${'$'}2345 ; Distance")
        workspace.replaceProjectSource(FX_SOURCE_FILE, authored, raw)
        editor.refreshLinkedPlms()
        assertEquals(0x2345, editor.doorEntries.first().distFromDoor)
        assertEquals(fx, bridge.fx.view(native.roomId, room.states.first().id).resource)
        val rawBuild = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(0x2345, RomParser(rawBuild.resultRom).parseDoorList(native.doorOut).first().distFromDoor)
        assertTrue(workspace.undoSourceEdit(FX_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertTrue(editor.undo(), editor.statusMessage) // GUI distance edit in the same source document.
        assertEquals(nativeDoors, editor.doorEntries)
        val undone = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(nativeDoors, RomParser(undone.resultRom).parseDoorList(native.doorOut))
        assertTrue(editor.redo(), editor.statusMessage)
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)

        val doorPc = parser.snesToPc(0x830000 or nativeDoors.first().doorDefPtr)
        val lowFlags = fixture.rom[doorPc + 2].toInt() and 255
        project.patches += SmPatch("door-byte-collision", "Cannot overwrite an unchanged owned DoorDef byte", enabled = true,
            writes = mutableListOf(PatchWrite((doorPc + 2).toLong(), listOf(lowFlags xor 0x80), expectedBytes = listOf(lowFlags))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        project.patches.removeLast()
        val good = bridge.doors.sourceText()
        val broken = good.replace("dw ${'$'}1234 ; Distance", "dw ${'$'}10000 ; Distance")
        workspace.replaceProjectSource(FX_SOURCE_FILE, good, broken)
        editor.refreshLinkedPlms()
        assertNotNull(editor.doorPreviewProblem)
        assertNull(build()); assertTrue(workspace.saveAllSources(), workspace.error)
        assertEquals(broken, bridge.doors.sourceText())
        assertTrue(editor.selectorEditingAvailable(), "A bad door declaration must not lock supported selectors")
        assertTrue(workspace.undoSourceEdit(FX_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertNull(editor.doorPreviewProblem)
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)

        // A second raw owner of the native definition is rejected before generated source overwrites it.
        val originalMacro = Regex("(?m)^Door_Flyway_0:[\\s\\S]*?(?=^Door_Flyway_1:)").find(good)!!.value
        val competing = good.replace(originalMacro, originalMacro.replace("%spawnDistance(${'$'}8000)", "%spawnDistance(${'$'}4321)"))
        assertNotEquals(good, competing)
        workspace.replaceProjectSource(FX_SOURCE_FILE, good, competing)
        assertNull(build()); assertTrue(logs.any { "also changed in original bank_83.asm" in it }, logs.joinToString("\n"))
        assertTrue(workspace.undoSourceEdit(FX_SOURCE_FILE), workspace.error)
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `managed door arrivals preserve native behavior grow and relink with predicates reciprocal fields and complete patch ownership`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = { com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it) })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        editor.loadRoom(native.roomId, parser, native)
        val nativeDoors = editor.doorEntries.toList()
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.createManagedDoorArrival(0), editor.statusMessage)
        assertTrue(editor.createManagedDoorArrival(1), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val condition = com.supermetroid.editor.asm.AsmRoomSelectorsSource(bridge.sourceText()).resource(room).conditions
            .entries.first { it.value.kind != ProjectRoomStateConditionKind.DEFAULT }.key
        val predicateMacro = com.supermetroid.editor.asm.AsmRoomSelectorsSource.conditionName(native.roomId, condition)
        val predicateLabel = "${predicateMacro}_Predicate"
        val aLabel = "SmeditArrival_9879_Door_0_Entry"; val bLabel = "SmeditArrival_9879_Door_1_Entry"
        val originalLabel = bridge.catalog.symbols.entries.first { it.key.startsWith("DoorASM_") && it.value == nativeDoors.first().entryCode }.key
        val root = bridge.sourceText()
        val body = Regex("(?m)^macro $predicateMacro\\(\\)\\n([\\s\\S]*?)^endmacro").find(root)!!.groups[1]!!.range
        val custom = root.replaceRange(body, "    ; smedit:managed-predicate-v1\n    clc\n    rts\n")
            .replace("jmp.w $originalLabel ; Preserve the original behavior and caller stack.", "jmp.w $originalLabel ; Preserve the original behavior and caller stack.\n    fillbyte ${'$'}FF\n    fill 32")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, root, custom)
        editor.refreshLinkedPlms()
        assertFalse(editor.selectorEditingAvailable()); assertNull(editor.doorPreviewProblem)
        val logs = mutableListOf<String>()
        val json = kotlinx.serialization.json.Json
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error)
            logs.clear()
            val reopened = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
            return ProjectFileService.buildRom(reopened, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        fun labels() = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path)).symbols.labels
        fun connections(rom: ByteArray) = RomParser(rom).parseDoorList(native.doorOut)
        val first = assertNotNull(build(), logs.joinToString("\n"))
        val initial = labels()
        val a = initial.getValue(aLabel); val b = initial.getValue(bLabel); val predicate = initial.getValue(predicateLabel)
        assertEquals(40, b - a); assertEquals(1, predicate - b)
        assertEquals(a and 65535, connections(first.resultRom)[0].entryCode)
        assertEquals(b and 65535, connections(first.resultRom)[1].entryCode)
        assertEquals(nativeDoors.map { it.doorDefPtr }, connections(first.resultRom).map { it.doorDefPtr })
        val nativeLow = nativeDoors.first().entryCode.toByte(); val nativeHigh = (nativeDoors.first().entryCode ushr 8).toByte()
        assertContentEquals(byteArrayOf(0xA9.toByte(), nativeLow, nativeHigh, 0x85.toByte(), 0x12, 0x4C, nativeLow, nativeHigh),
            first.resultRom.copyOfRange(parser.snesToPc(a), parser.snesToPc(a) + 8))
        assertEquals(0x60, first.resultRom[parser.snesToPc(b)].toInt() and 255)
        for (offset in parser.snesToPc(a) until parser.snesToPc(predicate) + 2) assertTrue(first.writeReport.writes.any {
            it.owner.startsWith("asm-source:") && offset in it.offset..it.endInclusive
        }, "Every managed byte, including unchanged FF padding, must be claimed")
        assertTrue(editor.doorArrivalBindings.values.all { it.snesAddress == null }, "A build must not persist allocation guesses into previews")
        assertContains(bridge.doors.sourceText(), "dw $aLabel")
        assertEquals(custom, bridge.sourceText(), "Build must not rewrite authoring with allocated addresses")
        val grown = custom.replace("fill 32", "fill 96")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, custom, grown)
        val bigger = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(a, labels().getValue(aLabel)); assertEquals(b + 64, labels().getValue(bLabel)); assertEquals(predicate + 64, labels().getValue(predicateLabel))
        assertEquals((b + 64) and 65535, connections(bigger.resultRom)[1].entryCode)
        // Reserve a complete manually declared FF envelope; no changed-byte diff can find this.
        val occupied = grown + "\npushpc\norg ${'$'}${a.toString(16)}\nArrivalReserved:\nfillbyte ${'$'}FF\nfill 128\nArrivalReserved_End:\npullpc\n"
        workspace.replaceProjectSource(PLM_SOURCE_FILE, grown, occupied)
        val moved = assertNotNull(build(), logs.joinToString("\n"))
        val movedA = labels().getValue(aLabel); val movedB = labels().getValue(bLabel)
        assertTrue(movedA >= a + 128); assertEquals(104, movedB - movedA)
        assertEquals(movedB and 65535, connections(moved.resultRom)[1].entryCode)
        editor.updateDoor(1, editor.doorEntries[1].copy(distFromDoor = 0x1234))
        assertEquals(0x1234, editor.doorEntries[1].distFromDoor)
        assertContains(bridge.doors.sourceText(), "dw $bLabel")
        val edited = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(movedB and 65535, connections(edited.resultRom)[1].entryCode)
        assertEquals(0x1234, connections(edited.resultRom)[1].distFromDoor)
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(moved.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertTrue(editor.redo(), editor.statusMessage)
        assertContentEquals(edited.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        // A known native label may itself move. Both the original DoorHeader and our tail JMP
        // relink; the typed DoorDef remains the sole owner of this room's twelve-byte record.
        val relocatedNativeAddress = movedA + 256
        assertTrue(fixture.rom.copyOfRange(parser.snesToPc(relocatedNativeAddress), parser.snesToPc(relocatedNativeAddress) + 3).all { it.toInt() and 255 == 255 })
        val renamed = occupied.replaceFirst("$originalLabel:", "${originalLabel}_BeforeManagedRelink:")
        val nativeRelink = renamed + "\npushpc\norg ${'$'}${relocatedNativeAddress.toString(16)}\n$originalLabel:\n" +
            "    jmp.w ${originalLabel}_BeforeManagedRelink\n${originalLabel}_End:\npullpc\n"
        workspace.replaceProjectSource(PLM_SOURCE_FILE, occupied, nativeRelink)
        val nativeMoved = assertNotNull(build(), logs.joinToString("\n"))
        val aliasAddress = labels().getValue(originalLabel)
        assertEquals(relocatedNativeAddress, aliasAddress)
        val wrapperPc = parser.snesToPc(labels().getValue(aLabel))
        assertContentEquals(byteArrayOf(0xA9.toByte(), aliasAddress.toByte(), (aliasAddress ushr 8).toByte(), 0x85.toByte(), 0x12, 0x4C, aliasAddress.toByte(), (aliasAddress ushr 8).toByte()),
            nativeMoved.resultRom.copyOfRange(wrapperPc, wrapperPc + 8))
        assertEquals(labels().getValue(aLabel) and 65535, connections(nativeMoved.resultRom)[0].entryCode)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertContentEquals(edited.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val paddingPc = parser.snesToPc(movedA) + 10
        project.patches += SmPatch("arrival-padding-collision", "Cannot repurpose claimed FF code padding", enabled = true,
            writes = mutableListOf(PatchWrite(paddingPc.toLong(), listOf(0), expectedBytes = listOf(255))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        project.patches.removeLast()
        val oversize = occupied.replace("fill 96", "fill 1024")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, occupied, oversize)
        editor.refreshLinkedPlms(); assertNull(editor.doorPreviewProblem)
        assertNull(build()); assertEquals(oversize, bridge.sourceText(), "Unbuildable executable drafts remain saveable")
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        val dispatcher = Regex("(?m)^Execute_Door_ASM:[\\s\\S]*?(?=^;;;)").find(occupied)!!.value
        val incompatible = occupied.replace(dispatcher, dispatcher.replace("REP #${'$'}30", "REP #${'$'}20"))
        assertNotEquals(occupied, incompatible)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, occupied, incompatible)
        assertNull(build()); assertTrue(logs.any { "door-dispatch ABI" in it }, logs.joinToString("\n"))
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertContentEquals(edited.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `managed inline predicates grow relocate relink and preserve reciprocal PLMs without authored addresses`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = {
            com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it)
        })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val ids = com.supermetroid.editor.asm.AsmRoomSelectorsSource(bridge.sourceText()).resource(room).conditions
            .filterValues { it.kind != ProjectRoomStateConditionKind.DEFAULT }.keys
            .sortedBy { com.supermetroid.editor.asm.AsmRoomSelectorsSource.conditionName(native.roomId, it) }
        assertEquals(2, ids.size)
        val names = ids.map { com.supermetroid.editor.asm.AsmRoomSelectorsSource.conditionName(native.roomId, it) }
        val labels = names.map { "${it}_Predicate" }
        val typed = bridge.sourceText()
        fun body(text: String, name: String, code: String): String {
            val range = Regex("(?m)^macro $name\\(\\)\\n([\\s\\S]*?)^endmacro").find(text)!!.groups[1]!!.range
            return text.replaceRange(range, "    ; smedit:managed-predicate-v1\n$code\n")
        }
        val custom = body(body(typed, names[0], """
                jsr.w .test
                rts
            .test:
                lda.w ${'$'}09C8
                and.w #${'$'}0001
                cmp.w #${'$'}0001
                rts
                fillbyte ${'$'}FF
                fill 32
        """.trimIndent()), names[1], "    jsr.w ${labels[0]}\n    rts")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, typed, custom)
        editor.refreshLinkedPlms()
        assertFalse(editor.selectorEditingAvailable()); assertNull(editor.selectorPreviewProblem)
        val logs = mutableListOf<String>()
        val json = kotlinx.serialization.json.Json
        suspend fun build(): RomBuildResult? {
            assertTrue(workspace.saveAllSources(), workspace.error)
            logs.clear()
            val snapshot = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
            return ProjectFileService.buildRom(snapshot, parser, logs::add, logs::add, fixture.projectFile.path)
        }
        fun addresses(rom: ByteArray): Map<String, Int> {
            val conditions = RomParser(rom).inspectRoomStates(native.roomId).states
            return room.effectiveStates.mapIndexedNotNull { index, state ->
                conditions[index].condition.asmPredicateAddress?.let { state.id to it }
            }.toMap()
        }
        val first = assertNotNull(build(), logs.joinToString("\n"))
        val initialAddresses = addresses(first.resultRom)
        val a = initialAddresses.getValue(ids[0]); val b = initialAddresses.getValue(ids[1])
        assertEquals(46, b - a, "Managed bodies must be packed to their real assembled size, not 1 KiB reservations")
        val aPc = parser.snesToPc(a)
        val expected = byteArrayOf(0x20, ((a + 4) and 255).toByte(), ((a + 4) ushr 8).toByte(), 0x60,
            0xAD.toByte(), 0xC8.toByte(), 0x09, 0x29, 1, 0, 0xC9.toByte(), 1, 0, 0x60)
        assertContentEquals(expected, first.resultRom.copyOfRange(aPc, aPc + expected.size))
        assertContentEquals(byteArrayOf(0x20, a.toByte(), (a ushr 8).toByte(), 0x60),
            first.resultRom.copyOfRange(parser.snesToPc(b), parser.snesToPc(b) + 4))
        for (pc in aPc until aPc + 50) assertTrue(first.writeReport.writes.any { write ->
            write.owner.startsWith("asm-source:") && pc in write.offset..write.endInclusive
        }, "Unclaimed managed byte ${pc.toString(16)}")
        assertEquals(custom, bridge.sourceText(), "Building must not write placement or end labels into source")
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)

        // The normal visual PLM backend remains writable, then raw ASM changes the same row.
        editor.switchRoomState(ids[0], parser)
        editor.addPlm(0xEEDB, 4, 4, 0x1F0, ItemStateScope.THIS_STATE)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDB && it.x == 4 && it.y == 4 }, editor.statusMessage)
        val doc = assertNotNull(bridge.managedView(native.roomId, ids[0]).document)
        val index = doc.entries.indexOfFirst { it.id == 0xEEDB && it.x == 4 && it.y == 4 }
        val raw = doc.replace(index, doc.entries[index].copy(x = 5))
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bridge.sourceText(), raw)
        editor.refreshLinkedPlms()
        assertTrue(editor.workingPlms.any { it.id == 0xEEDB && it.x == 5 && it.y == 4 })
        val plmBuild = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(initialAddresses, addresses(plmBuild.resultRom))
        val plmParser = RomParser(plmBuild.resultRom)
        val stateIndex = room.effectiveStates.indexOfFirst { it.id == ids[0] }
        assertTrue(plmParser.parsePlmSet(plmParser.parseRoomStatesWithData(native.roomId)[stateIndex].plmSetPtr)
            .any { it.id == 0xEEDB && it.x == 5 && it.y == 4 })
        val valid = bridge.sourceText()
        val grown = valid.replace("fill 32", "fill 96")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, valid, grown)
        val grownBuild = assertNotNull(build(), logs.joinToString("\n"))
        assertEquals(a, addresses(grownBuild.resultRom).getValue(ids[0]))
        assertEquals(b + 64, addresses(grownBuild.resultRom).getValue(ids[1]))
        assertEquals(grown, bridge.sourceText())

        // Another authored allocation forces relocation, including unchanged padding and local JSRs.
        val helperAddress = a + 0x200
        val moved = grown + "\npushpc\norg ${'$'}${helperAddress.toString(16)}\nUserHelper:\n    rts\n    fillbyte ${'$'}FF\n    fill 32\nUserHelper_End:\npullpc\n"
        workspace.replaceProjectSource(PLM_SOURCE_FILE, grown, moved)
        val relocated = assertNotNull(build(), logs.joinToString("\n"))
        val movedA = addresses(relocated.resultRom).getValue(ids[0])
        assertEquals(helperAddress + 34, movedA)
        val movedPc = parser.snesToPc(movedA)
        val movedExpected = expected.copyOf().also { it[1] = (movedA + 4).toByte(); it[2] = ((movedA + 4) ushr 8).toByte() }
        assertContentEquals(movedExpected, relocated.resultRom.copyOfRange(movedPc, movedPc + expected.size))
        val movedB = addresses(relocated.resultRom).getValue(ids[1])
        assertContentEquals(byteArrayOf(0x20, movedA.toByte(), (movedA ushr 8).toByte(), 0x60),
            relocated.resultRom.copyOfRange(parser.snesToPc(movedB), parser.snesToPc(movedB) + 4))
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertContentEquals(grownBuild.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)

        project.patches += SmPatch("managed-padding-conflict", "Cannot overwrite live erased padding", enabled = true,
            writes = mutableListOf(PatchWrite((aPc + 100).toLong(), listOf(0), expectedBytes = listOf(255))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        project.patches.removeLast()
        val collisionPc = aPc + 100
        val collision = kotlin.test.assertFails {
            com.supermetroid.editor.asm.AsmProjectCompiler().compile(fixture.projectFile.path, fixture.rom,
                roomMaterialization = com.supermetroid.editor.asm.AsmRoomMaterialization(
                    "org ${'$'}${(a + 100).toString(16)}\ndb ${'$'}FF\n",
                    listOf(com.supermetroid.editor.asm.AsmGeneratedRoomClaim("test", "Identical erased byte", collisionPc, byteArrayOf(-1))),
                    emptySet(), 1, 0), managedBank8FStart = a)
        }
        assertContains(collision.message.orEmpty(), "overlap managed bank-8F", message = "Even identical writes must respect allocation ownership")
        val oversized = grown.replace("fill 96", "fill 1024")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, grown, oversized)
        assertNull(build()); assertTrue(logs.any { "1024 bytes" in it }, logs.joinToString("\n"))
        assertEquals(oversized, bridge.sourceText(), "A failed build must preserve the author's draft")
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertContentEquals(grownBuild.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `ASM only selectors execute authored routine addresses with full source claims and headless reciprocal room resources`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = {
            com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it)
        })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        val baseline = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureRoomPropertiesSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureInitialScrollSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureFxSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureEnemySource(native.name), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val id = room.states.first().id
        val typed = bridge.sourceText()
        val name = com.supermetroid.editor.asm.AsmRoomSelectorsSource.conditionName(native.roomId, id)
        val body = Regex("(?m)^macro $name\\(\\)\\n([\\s\\S]*?)^endmacro").find(typed)!!.groups[1]!!
        val custom = typed.replaceRange(body.range, "    ; smedit:asm-predicate-v1\n    dw MyRoomPredicate\n") + """

            pushpc
            org Freespace_Bank8F_E99B
            MyRoomPredicate:
                jsr.w .test
                rts
            .test:
                lda.w ${'$'}09C8
                and.w #${'$'}0001 ; Custom odd-missile-capacity predicate, not a typed threshold.
                cmp.w #${'$'}0001
                rts
                fillbyte ${'$'}FF
                fill 32 ; Live code envelope includes unchanged erased bytes.
            MyRoomPredicate_End:
            pullpc
        """.trimIndent() + "\n"
        workspace.replaceProjectSource(PLM_SOURCE_FILE, typed, custom)
        editor.refreshLinkedPlms()
        assertEquals(com.supermetroid.editor.asm.AsmSelectorProjectionStatus.ASM_ONLY, editor.selectorProjectionStatus)
        assertFalse(editor.selectorEditingAvailable()); assertTrue(editor.roomPropertiesPreviewAvailable())
        assertTrue(workspace.saveAllSources(), workspace.error)
        val json = kotlinx.serialization.json.Json
        fun headless() = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val logs = mutableListOf<String>()
        fun build() = ProjectFileService.buildRom(headless(), parser, logs::add, logs::add, fixture.projectFile.path)
        val first = assertNotNull(build(), logs.joinToString("\n"))
        assertTrue(assertNotNull(AsmBuildArtifactRepository().loadRoomData(fixture.projectFile.path)).lines.any { "ASM-only predicate" in it })
        val built = RomParser(first.resultRom)
        val inspection = built.inspectRoomStates(native.roomId)
        assertTrue(inspection.isComplete, inspection.issues.toString())
        val predicate = inspection.states.first().condition
        assertEquals(com.supermetroid.editor.rom.RoomStateConditionKind.ASM_ONLY, predicate.kind)
        assertEquals(0x8FE99B, predicate.asmPredicateAddress)
        val codePc = built.snesToPc(0x8FE99B)
        val expectedCode = byteArrayOf(0x20, 0x9F.toByte(), 0xE9.toByte(), 0x60, 0xAD.toByte(), 0xC8.toByte(), 0x09,
            0x29, 0x01, 0, 0xC9.toByte(), 0x01, 0, 0x60)
        assertContentEquals(expectedCode, first.resultRom.copyOfRange(codePc, codePc + expectedCode.size))
        val codeEnd = codePc + expectedCode.size + 32
        val adapterPc = built.snesToPc(0x8F0000 or predicate.code)
        assertTrue(adapterPc >= codeEnd, "Allocator must not use the FF tail of a live routine envelope")
        for (pc in codePc until codeEnd) assertTrue(first.writeReport.writes.any { write ->
            write.owner.startsWith("asm-source:") && pc in write.offset..write.endInclusive
        }, "Unclaimed live predicate byte ${pc.toString(16)}")
        val data = built.parseRoomStatesWithData(native.roomId)
        data.forEachIndexed { index, state ->
            val base = baseline[index]
            assertContentEquals(parser.decompressLZ2(base.levelDataPtr), built.decompressLZ2(state.levelDataPtr))
            assertEquals(parser.parsePlmSet(base.plmSetPtr), built.parsePlmSet(state.plmSetPtr))
            assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(state.enemySetPtr))
            assertEquals(parser.parseEnemyGfxSet(base.enemyGfxPtr), built.parseEnemyGfxSet(state.enemyGfxPtr))
            assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(state.fxPtr))
            assertEquals(base.tileset, state.tileset); assertEquals(base.bgScrolling, state.bgScrolling)
            assertEquals(base.mainAsmPtr, state.mainAsmPtr); assertEquals(base.setupAsmPtr, state.setupAsmPtr)
        }
        // Unrelated visual resources remain editable while opaque selector controls are locked.
        editor.switchRoomState(id, parser)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.setTileProperties(20, 8, 1, 7)
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear()
        val edited = assertNotNull(build(), logs.joinToString("\n"))
        assertNotEquals(first.resultRom.toList(), edited.resultRom.toList())
        assertContentEquals(expectedCode, edited.resultRom.copyOfRange(codePc, codePc + expectedCode.size))
        assertEquals(0x8FE99B, RomParser(edited.resultRom).inspectRoomStates(native.roomId).states.first().condition.asmPredicateAddress)
        assertTrue(editor.undo(), editor.statusMessage)
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear()
        assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        val restored = bridge.sourceText()
        val moved = restored.replace("org Freespace_Bank8F_E99B", "org Freespace_Bank8F_E99B+${'$'}0100")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, restored, moved)
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear()
        val relocated = assertNotNull(build(), logs.joinToString("\n"))
        val relocatedParser = RomParser(relocated.resultRom)
        assertEquals(0x8FEA9B, relocatedParser.inspectRoomStates(native.roomId).states.first().condition.asmPredicateAddress)
        val relocatedBytes = expectedCode.copyOf().also { it[2] = 0xEA.toByte() }
        assertContentEquals(relocatedBytes, relocated.resultRom.copyOfRange(codePc + 0x100, codePc + 0x100 + expectedCode.size))
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear(); assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        // An ordinary ROM patch cannot occupy an unchanged byte inside the code envelope.
        project.patches += SmPatch("code-padding-collision", "Must not overwrite predicate padding", enabled = true,
            writes = mutableListOf(PatchWrite((codeEnd - 1).toLong(), listOf(0), expectedBytes = listOf(255))))
        logs.clear(); assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
        project.patches.removeLast()
        project.patches += SmPatch("selector-abi-collision", "Must not alter the selector ABI", enabled = true,
            writes = mutableListOf(PatchWrite(0x07E5E6, listOf(0xEA), expectedBytes = listOf(0x8E))))
        logs.clear(); assertNull(build()); assertTrue(logs.any { "verified native selector ABI" in it }, logs.joinToString("\n"))
        project.patches.removeLast()
        val missing = bridge.sourceText().replace("dw MyRoomPredicate", "dw MissingPredicate")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, bridge.sourceText(), missing)
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear(); assertNull(build()); assertTrue(logs.any { "Missing executable predicate label" in it }, logs.joinToString("\n"))
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear(); assertContentEquals(first.resultRom, assertNotNull(build(), logs.joinToString("\n")).resultRom)
        assertContains(com.supermetroid.editor.asm.AsmRoomDataSnapshot.render(first.resultRom, parser.roomCatalog.rooms), "ASM-only predicate")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `recognized handwritten selectors require explicit conversion and build headlessly without stale fallback`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState(sourceIndexLoader = {
            com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it)
        })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        val baseStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureRoomPropertiesSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureInitialScrollSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureFxSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureEnemySource(native.name), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val id = room.states.first().id
        val typed = bridge.sourceText()
        val name = com.supermetroid.editor.asm.AsmRoomSelectorsSource.conditionName(native.roomId, id)
        val body = Regex("(?m)^macro $name\\(\\)\\n([\\s\\S]*?)^endmacro").find(typed)!!.groups[1]!!
        val draft = typed.replaceRange(body.range, """
                ; smedit:predicate-v1
                lda.w ${'$'}09A4
                bit.w #${'$'}0004
                beq .alternative
                lda.w ${'$'}09C8
                cmp.w #${'$'}0019
                bcs .yes
            .alternative:
                lda.w ${'$'}09A8
                bit.w #${'$'}1000
                bne .no
                lda.w ${'$'}09C6
                cmp.w #${'$'}0005
                bcc .no
            .yes: sec
                rts
            .no: clc
                rts
        """.trimIndent() + "\n")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, typed, draft)
        editor.refreshLinkedPlms()
        val preview = assertNotNull(editor.selectorConversionPreview)
        fun headless() = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(
            kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        suspend fun rejected() {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            assertNull(ProjectFileService.buildRom(headless(), parser, logs::add, logs::add, fixture.projectFile.path))
            assertTrue(logs.any { "explicit conversion" in it }, logs.joinToString("\n"))
        }
        rejected() // Recognition is not permission to build from a guessed/stale condition.
        assertTrue(editor.convertRecognizedSelectors(preview), editor.statusMessage)
        val converted = bridge.sourceText()
        assertTrue(workspace.saveAllSources(), workspace.error)
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(headless(), parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        val inspected = built.inspectRoomStates(native.roomId)
        assertTrue(inspected.isComplete, inspected.issues.toString())
        fun typedCondition(c: com.supermetroid.editor.rom.RoomStateCondition): com.supermetroid.editor.data.ProjectRoomStateCondition =
            projectRoomStateCondition(ProjectRoomStateConditionKind.valueOf(c.kind.name), c.argument, c.negated, c.children.map(::typedCondition))
        assertEquals(preview.selectors.order.map { preview.selectors.conditions.getValue(it) }, inspected.states.map { typedCondition(it.condition) })
        built.parseRoomStatesWithData(native.roomId).forEachIndexed { index, state ->
            val base = baseStates[index]
            assertContentEquals(parser.decompressLZ2(base.levelDataPtr), built.decompressLZ2(state.levelDataPtr))
            assertEquals(parser.parsePlmSet(base.plmSetPtr), built.parsePlmSet(state.plmSetPtr))
            assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(state.enemySetPtr))
            assertEquals(parser.parseEnemyGfxSet(base.enemyGfxPtr), built.parseEnemyGfxSet(state.enemyGfxPtr))
            assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(state.fxPtr))
            assertEquals(base.tileset, state.tileset); assertEquals(base.musicData, state.musicData)
            assertEquals(base.bgScrolling, state.bgScrolling)
            assertEquals(base.mainAsmPtr, state.mainAsmPtr); assertEquals(base.setupAsmPtr, state.setupAsmPtr)
        }
        assertTrue(editor.undo(), editor.statusMessage)
        assertEquals(draft, bridge.sourceText())
        rejected() // A successful cached build must not make the unconverted draft buildable.
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertEquals(converted, bridge.sourceText())
        assertTrue(workspace.saveAllSources(), workspace.error)
        logs.clear()
        val redone = assertNotNull(ProjectFileService.buildRom(headless(), parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        assertContentEquals(result.resultRom, redone.resultRom)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `source owned boolean state lifecycle compiles all resource banks with headless reopen and reciprocal undo`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        project.patches += SmPatch("state-lifecycle-sentinel", "Unrelated jump value", enabled = true,
            writes = mutableListOf(PatchWrite(0x081EB9, listOf(5), expectedBytes = listOf(4))))
        // This test exercises typed room/source authoring and headless builds, not the full IDE
        // index. Avoid a background re-index of the complete disassembly on every source save.
        val workspace = AsmWorkspaceState(sourceIndexLoader = {
            com.supermetroid.editor.asm.AsmProjectWorkspaceRepository().loadRoomWorkspace(it)
        })
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = assertNotNull(parser.readRoomHeader(0x9879))
        val baseStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        assertTrue(editor.ensureSelectorSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureRoomPropertiesSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureInitialScrollSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureFxSource(native.name), editor.statusMessage)
        assertTrue(editor.ensureEnemySource(native.name), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val template = room.states.first().id
        val compound = projectRoomStateCondition(ProjectRoomStateConditionKind.ALL_OF, children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7, negated = true),
            projectRoomStateCondition(ProjectRoomStateConditionKind.ANY_OF, children = listOf(
                projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 5),
                projectRoomStateCondition(ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED)))))
        val added = editor.addRoomState(template, compound, parser)
        val clone = editor.addRoomState(added, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 9), parser)
        val allBanks = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE, com.supermetroid.editor.asm.ENEMY_SOURCE_FILE)
        fun texts() = allBanks.associateWith(workspace::projectSourceText)
        suspend fun verify(): ByteArray {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val expected = room.effectiveStates.toList()
            val portable = ProjectFileService.snapshotProject(project)
            val log = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(portable, parser, log::add, log::add, fixture.projectFile.path), log.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val inspected = built.inspectRoomStates(native.roomId)
            assertTrue(inspected.isComplete, inspected.issues.toString())
            fun typed(c: com.supermetroid.editor.rom.RoomStateCondition): com.supermetroid.editor.data.ProjectRoomStateCondition =
                projectRoomStateCondition(ProjectRoomStateConditionKind.valueOf(c.kind.name), c.argument, c.negated, c.children.map(::typed))
            assertEquals(expected.map { it.effectiveCondition }, inspected.states.map { typed(it.condition) })
            val states = built.parseRoomStatesWithData(native.roomId)
            expected.forEachIndexed { index, state ->
                val base = baseStates[state.sourceStateIndex ?: state.templateSourceStateIndex!!]
                val actual = states[index]
                assertContentEquals(parser.decompressLZ2(base.levelDataPtr), built.decompressLZ2(actual.levelDataPtr))
                assertEquals(parser.parsePlmSet(base.plmSetPtr), built.parsePlmSet(actual.plmSetPtr))
                assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(actual.enemySetPtr))
                assertEquals(parser.parseEnemyGfxSet(base.enemyGfxPtr), built.parseEnemyGfxSet(actual.enemyGfxPtr))
                assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(actual.fxPtr))
                assertEquals(base.tileset, actual.tileset); assertEquals(base.musicTrack, actual.musicTrack)
                assertEquals(base.mainAsmPtr, actual.mainAsmPtr); assertEquals(base.setupAsmPtr, actual.setupAsmPtr)
            }
            assertEquals(5, result.resultRom[0x081EB9].toInt() and 255)
            assertEquals(parser.parseRoomStatesWithData(0x92FD), built.parseRoomStatesWithData(0x92FD))
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
            return result.resultRom
        }
        val fiveStateSource = texts()
        val fiveStateRom = verify()
        editor.deleteRoomState(added, parser)
        assertFalse(added in room.effectiveStates.map { it.id })
        assertTrue(clone in room.effectiveStates.map { it.id })
        val fourStateSource = texts()
        val fourStateRom = verify()
        assertTrue(workspace.undoSourceEdit(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE), workspace.error)
        assertEquals(fiveStateSource, texts())
        assertContentEquals(fiveStateRom, verify())
        assertTrue(editor.redo(), editor.statusMessage)
        assertEquals(fourStateSource, texts())
        assertContentEquals(fourStateRom, verify())
    }

    @Test fun `ASM first layout typing from native assets compiles without opt in and later joins the visual editor`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val asset = AsmAssetManifest.loadBundled().single { it.path == "LevelData_Flyway.bin" }
        val link = com.supermetroid.editor.asm.AsmSemanticBridge.buildIndex(parser).roomSourcesFor(asset.snesAddress)
            .single { it.roomId == 0x9879 && it.sourceStateIndex == 1 }
        val native = parser.readRoomHeader(link.roomId)!!
        val baseline = parser.parseRoomStatesWithData(native.roomId)
        val navigation = RoomAsmNavigation(editor, workspace, { "No visual room selected" })
        val model = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
        val revision = workspace.sourceBufferRevision
        assertTrue(navigation.openFromAsm(link), editor.statusMessage)
        assertEquals(model, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
        assertEquals(revision, workspace.sourceBufferRevision)
        val draft = assertNotNull(workspace.roomSourceInspection?.automaticEdit)
        val staged = editor.roomSourceSnapshot(native.roomId).also { it.asmLayoutSourceVersion = 1 }
        val selected = staged.states.single { it.sourceStateIndex == 1 }.id
        val input = com.supermetroid.editor.asm.AsmRoomLayoutSource(draft.text).resources(staged, 48, 16).getValue(selected).bytes()
        val wordOffset = 2 + (8 * 48 + 20) * 2
        val oldWord = (input[wordOffset].toInt() and 255) or ((input[wordOffset + 1].toInt() and 255) shl 8)
        val newWord = (oldWord and 0xFFF) or 0x8000
        val raw = com.supermetroid.editor.asm.AsmRoomLayoutSource(draft.text).edit(staged, selected, 48, 16,
            listOf(com.supermetroid.editor.data.TileEdit(20, 8, oldWord, newWord, newBts = 7)))
        assertTrue(workspace.updateSourceEditText(PLM_SOURCE_FILE, raw), editor.statusMessage)
        assertNull(workspace.roomSourceInspection, editor.statusMessage)
        assertEquals(0, editor.currentRoomId); assertNull(editor.roomSourceParser)
        val room = project.rooms.getValue("9879")
        suspend fun verifyBuild() {
            assertTrue(workspace.saveAllSources(), workspace.error)
            assertTrue(editor.saveProject())
            val headless = kotlinx.serialization.json.Json.decodeFromString(SmEditProject.serializer(),
                kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(headless, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val actual = built.parseRoomStatesWithData(native.roomId)
            val resources = com.supermetroid.editor.asm.AsmRoomLayoutSource(bridge.sourceText()).resources(room, 48, 16)
            assertEquals(1, actual.map { it.levelDataPtr }.distinct().size)
            room.states.forEachIndexed { index, state ->
                assertContentEquals(resources.getValue(state.id).bytes(), built.decompressLZ2(actual[index].levelDataPtr))
                assertEquals(parser.parsePlmSet(baseline[index].plmSetPtr), built.parsePlmSet(actual[index].plmSetPtr))
                assertEquals(parser.parseEnemyPopulation(baseline[index].enemySetPtr), built.parseEnemyPopulation(actual[index].enemySetPtr))
                assertEquals(parser.parseFxEntries(baseline[index].fxPtr), built.parseFxEntries(actual[index].fxPtr))
            }
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        verifyBuild()
        assertEquals(0, editor.currentRoomId, "A headless build must not load the canvas either")
        editor.loadRoom(native.roomId, parser, native)
        editor.requestLayoutNavigation(native.roomId, null, selected)
        assertEquals(selected, editor.currentPlmStateId())
        assertEquals(newWord, editor.readBlockWord(20, 8)); assertEquals(7, editor.readBts(20, 8))
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.setTileProperties(20, 8, 0, 9)
        assertNotEquals(raw, bridge.sourceText())
        verifyBuild()
        assertTrue(editor.undo(), editor.statusMessage)
        assertEquals(newWord, editor.readBlockWord(20, 8)); assertEquals(7, editor.readBts(20, 8))
    }

    @Test fun `selector source conditions compounds and branch order round trip real Asar without swapping state resources`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = parser.readRoomHeader(0x9879)!!
        val baseStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        val room = project.getOrCreateRoom(native.roomId).also { it.ensureStateManifest(parser) }
        val first = room.states.first().id
        val second = editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 8), parser)
        val navigation = RoomAsmNavigation(editor, workspace, { native.name })
        fun adopt(kind: RoomAsmSourceKind) {
            val before = kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project)
            val revision = workspace.sourceBufferRevision
            assertTrue(navigation.open(RoomAsmSourceRequest(kind)), editor.statusMessage)
            assertEquals(before, kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
            assertEquals(revision, workspace.sourceBufferRevision)
            assertNotNull(workspace.roomSourceInspection).startEditing()
            assertNull(workspace.roomSourceInspection, editor.statusMessage)
        }
        adopt(RoomAsmSourceKind.LAYOUT)
        adopt(RoomAsmSourceKind.PROPERTIES)
        bridge.migrateRoom(native.roomId, native.name)
        adopt(RoomAsmSourceKind.INITIAL_SCROLLS)
        adopt(RoomAsmSourceKind.FX)
        adopt(RoomAsmSourceKind.ENEMIES)
        adopt(RoomAsmSourceKind.SELECTORS)
        val before = bridge.sourceText()
        val raw = com.supermetroid.editor.asm.AsmRoomSelectorsSource(before).editCondition(room, first,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 9))
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        editor.refreshLinkedPlms()
        val compound = projectRoomStateCondition(ProjectRoomStateConditionKind.ALL_OF, children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 25, negated = true)))
        editor.setRoomStateCondition(second, compound, parser)
        assertTrue(editor.moveRoomState(second, -1, parser), editor.statusMessage)
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val order = room.effectiveStates
            val logs = mutableListOf<String>()
            // Headless reopened model: no transient selector/header preview may influence placement.
            val buildProject = kotlinx.serialization.json.Json.decodeFromString<SmEditProject>(kotlinx.serialization.json.Json.encodeToString(SmEditProject.serializer(), project))
            val result = assertNotNull(ProjectFileService.buildRom(buildProject, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val inspected = built.inspectRoomStates(native.roomId)
            assertTrue(inspected.isComplete, inspected.issues.toString())
            fun typed(c: com.supermetroid.editor.rom.RoomStateCondition): com.supermetroid.editor.data.ProjectRoomStateCondition =
                projectRoomStateCondition(ProjectRoomStateConditionKind.valueOf(c.kind.name), c.argument, c.negated, c.children.map(::typed))
            assertEquals(order.map { it.effectiveCondition }, inspected.states.map { typed(it.condition) })
            val data = built.parseRoomStatesWithData(native.roomId)
            order.forEachIndexed { index, state ->
                val base = baseStates[state.sourceStateIndex ?: state.templateSourceStateIndex!!]
                val actual = data[index]
                assertContentEquals(parser.decompressLZ2(base.levelDataPtr), built.decompressLZ2(actual.levelDataPtr))
                assertEquals(parser.parsePlmSet(base.plmSetPtr), built.parsePlmSet(actual.plmSetPtr))
                assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(actual.enemySetPtr))
                assertEquals(parser.parseEnemyGfxSet(base.enemyGfxPtr), built.parseEnemyGfxSet(actual.enemyGfxPtr))
                assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(actual.fxPtr))
                assertEquals(base.tileset, actual.tileset); assertEquals(base.musicData, actual.musicData)
                assertEquals(base.bgScrolling, actual.bgScrolling)
                assertEquals(base.mainAsmPtr, actual.mainAsmPtr); assertEquals(base.setupAsmPtr, actual.setupAsmPtr)
            }
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        assertTrue(editor.undo(), editor.statusMessage) // Reorder.
        buildAndVerify()
        assertTrue(editor.undo(), editor.statusMessage) // Compound predicate.
        assertFalse(editor.undo(), "A raw source edit still protects the older adoption boundary")
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertTrue(editor.undo(), editor.statusMessage) // Adoption restores native/reference GUI graph.
        assertEquals(0, room.asmStateSelectorsSourceVersion)
        buildAndVerify()
    }

    @Test fun `room header and state source fields round trip GUI raw ASM and undo through real Asar`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace)
        val native = parser.readRoomHeader(0x9879)!!
        val nativeStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        val json = kotlinx.serialization.json.Json
        val originalModel = json.encodeToString(SmEditProject.serializer(), project)
        val originalSource = bridge.sourceText(); val revision = workspace.sourceBufferRevision
        val navigation = RoomAsmNavigation(editor, workspace, { native.name })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES)), editor.statusMessage)
        assertTrue(workspace.isProjectSourceEditable); assertNotNull(workspace.roomSourceInspection?.automaticEdit)
        assertEquals(originalModel, json.encodeToString(SmEditProject.serializer(), project)); assertEquals(originalSource, bridge.sourceText())
        assertEquals(revision, workspace.sourceBufferRevision)
        val planned = workspace.sourceEditorText(PLM_SOURCE_FILE)
        val adoptedText = planned.replace("; Room index", "; Room index / first automatic edit")
        assertTrue(workspace.updateSourceEditText(PLM_SOURCE_FILE, adoptedText), editor.statusMessage)
        assertNull(workspace.roomSourceInspection); assertEquals(1, editor.undoStack.size)
        val room = project.rooms.getValue("9879")
        val selected = editor.currentPlmStateId()!!
        editor.setRoomStateDataChange(selected, com.supermetroid.editor.data.StateDataChange(musicData = 0x18, musicTrack = 5, bgScrolling = 0x0201), parser)
        editor.setRoomHeaderChange(com.supermetroid.editor.data.RoomHeaderChange(upScroller = 0x71, downScroller = 0x72, creBitflag = 3))
        val before = bridge.sourceText()
        val source = com.supermetroid.editor.asm.AsmRoomPropertiesSource(before)
        val raw = source.editStates(room, mapOf(selected to source.resource(room).states.getValue(selected).copy(musicTrack = 7)))
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        editor.refreshLinkedPlms()
        assertFalse(editor.undo(), "A newer raw source edit blocks older GUI undo")
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val authored = if (room.asmRoomPropertiesSourceVersion == 0) null else com.supermetroid.editor.asm.AsmRoomPropertiesSource(bridge.sourceText()).resource(room)
            val logs = mutableListOf<String>()
            val headless = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
            val result = assertNotNull(ProjectFileService.buildRom(headless, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val h = built.readRoomHeader(native.roomId)!!
            assertEquals(authored?.header?.index ?: native.index, h.index)
            assertEquals(authored?.header?.area ?: native.area, h.area)
            assertEquals(authored?.header?.mapX ?: native.mapX, h.mapX)
            assertEquals(authored?.header?.mapY ?: native.mapY, h.mapY)
            assertEquals(authored?.header?.upScroller ?: native.upScroller, h.upScroller)
            assertEquals(authored?.header?.downScroller ?: native.downScroller, h.downScroller)
            assertEquals(authored?.header?.creBitflag ?: native.creBitflag, h.creBitflag)
            assertEquals(native.width, h.width); assertEquals(native.height, h.height)
            built.parseRoomStatesWithData(native.roomId).forEachIndexed { index, data ->
                val base = nativeStates[index]
                val fields = authored?.states?.getValue(room.states[index].id)
                assertEquals(fields?.tileset ?: base.tileset, data.tileset)
                assertEquals(fields?.musicData ?: base.musicData, data.musicData)
                assertEquals(fields?.musicTrack ?: base.musicTrack, data.musicTrack)
                assertEquals(fields?.bgScrolling ?: base.bgScrolling, data.bgScrolling)
                assertContentEquals(parser.decompressLZ2(base.levelDataPtr), built.decompressLZ2(data.levelDataPtr))
                assertEquals(parser.parsePlmSet(base.plmSetPtr), built.parsePlmSet(data.plmSetPtr))
                assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(data.enemySetPtr))
                assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(data.fxPtr))
                assertEquals(base.mainAsmPtr, data.mainAsmPtr); assertEquals(base.setupAsmPtr, data.setupAsmPtr)
            }
            assertNull(room.stateDataChange)
            assertTrue(room.states.all { it.stateDataChange == null })
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertTrue(editor.undo(), editor.statusMessage) // GUI header fields.
        buildAndVerify()
        assertTrue(editor.undo(), editor.statusMessage) // GUI state fields.
        assertTrue(editor.undo(), editor.statusMessage) // Source adoption.
        assertEquals(0, room.asmRoomPropertiesSourceVersion)
        assertEquals(originalModel, json.encodeToString(SmEditProject.serializer(), project)); assertEquals(originalSource, bridge.sourceText())
        assertNotNull(workspace.roomSourceInspection?.automaticEdit)
        buildAndVerify()
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertNull(workspace.roomSourceInspection); assertEquals(adoptedText, bridge.sourceText())
        buildAndVerify()
    }

    @Test fun `authored layout code round trips Flyway geometry BTS sharing and undo through real Asar builds`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        val native = parser.readRoomHeader(0x9879)!!
        val baseStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        val profile = com.supermetroid.editor.procgen.TilesetProfileCache.getOrLearn(parser,
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }, native.tileset)
        assertTrue(editor.generateBiome(
            com.supermetroid.editor.procgen.BiomeRules.roll(com.supermetroid.editor.procgen.BiomeStyle.PIPE_MAZE, 1234L),
            profile, 1234L, romParser = parser) > 0, editor.statusMessage)
        val generated = editor.workingLevelData!!.copyOf()
        assertTrue(editor.ensureLayoutSource(native.name), editor.statusMessage)
        val room = project.rooms.getValue("9879")
        val selected = editor.currentPlmStateId()!!
        assertTrue(room.levelResourceOperations.isEmpty())
        val adopted = bridge.sourceText()
        editor.setTileProperties(20, 8, 8, 7)
        assertNotEquals(adopted, bridge.sourceText())
        val painted = bridge.sourceText()
        val newWord = editor.readBlockWord(20, 8) and 0x0FFF
        val raw = com.supermetroid.editor.asm.AsmRoomLayoutSource(painted).edit(room, selected, 48, 16,
            listOf(com.supermetroid.editor.data.TileEdit(20, 8, editor.readBlockWord(20, 8), newWord, editor.readBts(20, 8), 3)))
        workspace.replaceProjectSource(PLM_SOURCE_FILE, painted, raw)
        editor.refreshLinkedPlms()
        assertEquals(newWord, editor.readBlockWord(20, 8)); assertEquals(3, editor.readBts(20, 8))
        val shared = editor.workingLevelData!!.copyOf()
        assertFalse(editor.undo(), "A newer raw code edit must not be overwritten by GUI undo")
        suspend fun buildAndVerify(expected: Map<String, ByteArray>, pointers: Int) {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(native.roomId)
            assertEquals(pointers, states.map { it.levelDataPtr }.distinct().size)
            room.states.forEachIndexed { index, state ->
                assertContentEquals(expected.getValue(state.id), built.decompressLZ2(states[index].levelDataPtr))
                val base = baseStates[state.sourceStateIndex ?: state.templateSourceStateIndex!!]
                assertEquals(parser.parsePlmSet(base.plmSetPtr).filterNot { RomParser.isScrollPlm(it.id) }, built.parsePlmSet(states[index].plmSetPtr))
                assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(states[index].enemySetPtr))
                assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(states[index].fxPtr))
                assertEquals(base.mainAsmPtr, states[index].mainAsmPtr)
                assertEquals(base.setupAsmPtr, states[index].setupAsmPtr)
            }
            assertTrue(room.levelResourceOperations.isEmpty())
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify(room.states.associate { it.id to shared }, 1)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(generated, editor.workingLevelData)
        buildAndVerify(room.states.associate { it.id to generated }, 1)
        assertTrue(editor.redo(), editor.statusMessage)
        val sharedPainted = editor.workingLevelData!!.copyOf()
        editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        val originalType = editor.readBlockWord(21, 8) ushr 12
        editor.setTileProperties(21, 8, if (originalType == 8) 0 else 8, 9)
        val private = editor.workingLevelData!!.copyOf()
        buildAndVerify(room.states.associate { it.id to if (it.id == selected) private else sharedPainted }, 2)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error) // Undo the private-layout fork.
        editor.refreshLinkedPlms()
        assertContentEquals(sharedPainted, editor.workingLevelData)
        buildAndVerify(room.states.associate { it.id to sharedPainted }, 1)
    }

    @Test fun `Flyway generates one shared maze without merging state objects actors FX or scripts`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        val native = parser.readRoomHeader(0x9879)!!
        val nativeStates = parser.parseRoomStatesWithData(native.roomId)
        assertTrue(nativeStates.map { parser.parsePlmSet(it.plmSetPtr) }.distinct().size > 1)
        assertTrue(nativeStates.map { parser.parseEnemyPopulation(it.enemySetPtr) }.distinct().size > 1)
        editor.loadRoom(native.roomId, parser, native)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        val room = project.rooms.getValue("9879")
        val before = editor.workingLevelData!!.copyOf()
        val sourceBefore = bridge.sourceText()
        val profile = com.supermetroid.editor.procgen.TilesetProfileCache.getOrLearn(parser,
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }, native.tileset)
        assertTrue(editor.generateBiome(
            com.supermetroid.editor.procgen.BiomeRules.roll(com.supermetroid.editor.procgen.BiomeStyle.PIPE_MAZE, 1234L),
            profile, 1234L, romParser = parser) > 0, editor.statusMessage)
        val generated = editor.workingLevelData!!.copyOf()
        assertEquals(1, room.states.map { it.resources.level }.distinct().size)
        assertEquals(0, room.asmEnemySourceVersion)
        assertEquals(0, room.asmFxSourceVersion)
        room.states.forEachIndexed { index, state ->
            editor.switchRoomState(state.id, parser)
            assertContentEquals(generated, editor.workingLevelData)
            assertEquals(parser.parsePlmSet(nativeStates[index].plmSetPtr).filterNot { RomParser.isScrollPlm(it.id) }, editor.workingPlms)
            assertEquals(parser.parseEnemyPopulation(nativeStates[index].enemySetPtr), editor.workingEnemies)
        }
        suspend fun buildAndVerify(authored: Boolean) {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(native.roomId)
            assertEquals(nativeStates.size, states.size)
            assertEquals(1, states.map { it.levelDataPtr }.distinct().size, "All Flyway states retain one shared layout")
            nativeStates.zip(states).forEach { (base, actual) ->
                assertContentEquals(if (authored) generated else before, built.decompressLZ2(actual.levelDataPtr))
                assertEquals(parser.parsePlmSet(base.plmSetPtr).filterNot { authored && RomParser.isScrollPlm(it.id) }, built.parsePlmSet(actual.plmSetPtr))
                assertContentEquals(com.supermetroid.editor.rom.readSourceEnemyPopulation(parser, base.enemySetPtr, "native").bytes(),
                    com.supermetroid.editor.rom.readSourceEnemyPopulation(built, actual.enemySetPtr, "compiled").bytes())
                assertContentEquals(com.supermetroid.editor.rom.readSourceEnemyGraphics(parser, base.enemyGfxPtr, "native").bytes(),
                    com.supermetroid.editor.rom.readSourceEnemyGraphics(built, actual.enemyGfxPtr, "compiled").bytes())
                assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(actual.fxPtr))
                assertEquals(base.tileset, actual.tileset)
                assertEquals(base.mainAsmPtr, actual.mainAsmPtr)
                assertEquals(base.setupAsmPtr, actual.setupAsmPtr)
                assertEquals(if (authored) listOf(2, 2, 2) else parser.parseScrollData(base.scrollPtr, native.width, native.height).toList(),
                    built.parseScrollData(actual.scrollPtr, native.width, native.height).toList())
            }
            assertContentEquals(fixture.rom, fixture.romFile.readBytes(), "The input ROM remains untouched")
        }
        buildAndVerify(true)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertContentEquals(before, editor.workingLevelData)
        assertEquals(sourceBefore, bridge.sourceText())
        buildAndVerify(false)
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertContentEquals(generated, editor.workingLevelData)
        buildAndVerify(true)
    }

    @Test fun `themed generation compiles FX palette tileset and geometry with reciprocal ASM edits and undo`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        val native = parser.readRoomHeader(0xACB3)!!
        val nativeStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.addScrollTriggerWithCommands(8, 8, listOf(com.supermetroid.editor.data.ScrollCommand(0, 0)))
        assertTrue(editor.ensureFxSource(native.name), editor.statusMessage)
        assertTrue(workspace.saveAllSources(), workspace.error)
        val selected = editor.currentPlmStateId()!!
        val originalRoom = project.rooms.getValue("ACB3")
        val targets = originalRoom.states.filter { it.resources.level == originalRoom.states.single { state -> state.id == selected }.resources.level }.map { it.id }.toSet()
        val beforePixels = editor.workingLevelData!!.copyOf()
        val beforeSources = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE).associateWith { workspace.projectSourceText(it) }
        val theme = com.supermetroid.editor.procgen.BiomeTheme.CONCRETE.first { it.id == "grotto" }.copy(paletteEffectId = "icecold")
        val paletteBefore = editor.readTilesetPalette(theme.tilesetId!!, parser, includeOverride = false)!!
        val bytesBefore = ByteArray(256).also { bytes -> for (i in paletteBefore.indices) {
            bytes[i * 2] = paletteBefore[i].toByte(); bytes[i * 2 + 1] = (paletteBefore[i] ushr 8).toByte()
        } }
        project.customGfx.palettes[theme.tilesetId.toString()] = Base64.getEncoder().encodeToString(bytesBefore)
        project.customGfx.paletteEffects["tileset:${theme.tilesetId}"] = "previous"
        val profile = com.supermetroid.editor.procgen.TilesetProfileCache.getOrLearn(parser,
            parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }, theme.tilesetId!!)
        val count = editor.generateBiome(
            com.supermetroid.editor.procgen.BiomeRules.roll(com.supermetroid.editor.procgen.BiomeStyle.PIPE_MAZE, 1234L),
            profile, 1234L, romParser = parser, theme = theme)
        assertTrue(count > 0, editor.statusMessage)
        val themedPixels = editor.workingLevelData!!.copyOf()
        assertEquals(theme.tilesetId, editor.currentTilesetId)
        assertEquals(6, editor.effectiveFxEntries(native.roomId, parser).last().fxType)
        project.patches += SmPatch("theme-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify(themed: Boolean) {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(native.roomId)
            val room = project.rooms.getValue("ACB3")
            room.states.forEachIndexed { index, state ->
                val base = nativeStates[state.sourceStateIndex ?: state.templateSourceStateIndex!!]
                assertEquals(if (themed && state.id in targets) theme.tilesetId else base.tileset, states[index].tileset)
                assertContentEquals(if (state.id in targets) (if (themed) themedPixels else beforePixels) else parser.decompressLZ2(base.levelDataPtr),
                    built.decompressLZ2(states[index].levelDataPtr))
                assertEquals(bridge.fx.view(native.roomId, state.id).resource!!.entries, built.parseFxEntries(states[index].fxPtr))
                if (room.asmInitialScrollSourceVersion != 0) assertEquals(bridge.initialScrolls.view(native.roomId, state.id).resource!!.values,
                    built.parseScrollData(states[index].scrollPtr, native.width, native.height).toList())
                if (themed && state.id in targets) assertTrue(built.parsePlmSet(states[index].plmSetPtr).none { RomParser.isScrollPlm(it.id) })
            }
            val palette = Base64.getDecoder().decode(project.customGfx.palettes.getValue(theme.tilesetId.toString()))
            val actual = EditorState().readTilesetPalette(theme.tilesetId!!, built, includeOverride = false)!!
            assertEquals((0 until 128).map { (palette[it * 2].toInt() and 255) or ((palette[it * 2 + 1].toInt() and 255) shl 8) }, actual.toList())
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify(true)
        val source = bridge.fx.sourceText()
        val fx = bridge.fx.view(native.roomId, selected).resource!!
        val body = authoredMacroBody(source, fx.identity)
        val draft = source.replace(body, body.replace("db ${'$'}06 ; FX Type", "db ${'$'}08 ; FX Type"))
        assertNotEquals(source, draft)
        workspace.replaceProjectSource(FX_SOURCE_FILE, source, draft)
        editor.refreshLinkedPlms()
        assertEquals(8, editor.effectiveFxEntries(native.roomId, parser).last().fxType)
        assertFalse(editor.undo(), "A newer raw FX edit must block the whole themed generation undo")
        assertContentEquals(themedPixels, editor.workingLevelData)
        assertTrue(workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertTrue(editor.undo(), editor.statusMessage)
        assertTrue(project.rooms.getValue("ACB3") === originalRoom)
        assertEquals(beforeSources, beforeSources.mapValues { workspace.projectSourceText(it.key) })
        assertEquals("previous", project.customGfx.paletteEffects["tileset:${theme.tilesetId}"])
        assertContentEquals(beforePixels, editor.workingLevelData)
        buildAndVerify(false)
        assertTrue(workspace.redoSourceEdit(FX_SOURCE_FILE), workspace.error)
        buildAndVerify(true)
    }

    @Test fun `whole room source reset and cross bank undo compile native and authored resources with real Asar`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        val native = parser.readRoomHeader(0x91F8)!!
        val nativeStates = parser.parseRoomStatesWithData(native.roomId)
        editor.loadRoom(native.roomId, parser, native)
        editor.addPlm(0xB76F, 8, 8, 0x8000)
        val slot = editor.workingPlms.single { it.id == 0xB76F }.param and 255
        val nativeSave = parser.readSaveEntry(0, slot)!!.words()
        val width = editor.workingBlocksWide / 16
        editor.setScroll(0, 0, if (editor.workingScrolls[0] == 0) 1 else 0, width)
        assertTrue(editor.ensureEnemySource("Landing Site"), editor.statusMessage)
        val stateId = editor.currentPlmStateId()!!
        editor.setRoomStateFxChange(stateId, com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x100), parser)
        val added = editor.addRoomState(stateId, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        editor.switchRoomState(added, parser)
        val actor = editor.workingEnemies.first()
        editor.updateEnemy(actor, actor.copy(x = actor.x + 16, extra3 = 0x123))
        assertTrue(editor.resizeRoom(native.width, native.height, native.width + 1, native.height), editor.statusMessage)
        val room = project.rooms.getValue("91F8")
        bridge.fx.migrate(0x9C89, "Brinstar main shaft")
        val otherRoom = project.rooms.getValue("9C89")
        val otherFx = bridge.fx.view(otherRoom.roomId, otherRoom.states.first().id).resource!!
        project.patches += SmPatch("reset-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify(authored: Boolean) {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(room.roomId)
            val header = built.readRoomHeader(room.roomId)!!
            assertEquals(if (authored) native.width + 1 else native.width, header.width)
            assertEquals(if (authored) room.states.size else nativeStates.size, states.size)
            if (authored) {
                room.states.forEachIndexed { index, state ->
                    val expectedObjects = bridge.managedView(room.roomId, state.id).document!!.entries
                    val actualObjects = built.parsePlmSet(states[index].plmSetPtr)
                    assertEquals(expectedObjects.size, actualObjects.size)
                    expectedObjects.zip(actualObjects).forEach { (expected, actual) ->
                        assertEquals(expected, if (expected.id == 0xB703) actual.copy(param = expected.param) else actual)
                        if (expected.id == 0xB703) assertEquals(
                            bridge.scrollCommands(room.roomId, state.id, expected).map { Triple(it.screenIndex, it.screenIndex % header.width, it.scrollValue) },
                            RomParser.decodeScrollCommands(built, actual.param, header.width))
                    }
                    assertEquals(bridge.initialScrolls.view(room.roomId, state.id).resource!!.values,
                        built.parseScrollData(states[index].scrollPtr, header.width, header.height).toList())
                    assertEquals(bridge.fx.view(room.roomId, state.id).resource!!.entries, built.parseFxEntries(states[index].fxPtr))
                    val expected = bridge.enemies.view(room.roomId, state.id).resource!!
                    assertContentEquals(expected.population.bytes(), com.supermetroid.editor.rom.readSourceEnemyPopulation(built, states[index].enemySetPtr, "compiled").bytes())
                    assertContentEquals(expected.graphics.bytes(), com.supermetroid.editor.rom.readSourceEnemyGraphics(built, states[index].enemyGfxPtr, "compiled").bytes())
                    val base = nativeStates[state.sourceStateIndex ?: state.templateSourceStateIndex!!]
                    assertContentEquals(resizeLevelData(parser.decompressLZ2(base.levelDataPtr), native.width, native.height, native.width + 1, native.height),
                        built.decompressLZ2(states[index].levelDataPtr))
                }
                assertEquals(bridge.saveSpawn(room.roomId, 0, slot)!!.words, built.readSaveEntry(0, slot)!!.words())
            } else {
                nativeStates.zip(states).forEach { (base, actual) ->
                    assertContentEquals(parser.decompressLZ2(base.levelDataPtr), built.decompressLZ2(actual.levelDataPtr))
                    assertEquals(parser.parsePlmSet(base.plmSetPtr), built.parsePlmSet(actual.plmSetPtr))
                    assertContentEquals(parser.parseScrollData(base.scrollPtr, native.width, native.height), built.parseScrollData(actual.scrollPtr, native.width, native.height))
                    assertEquals(parser.parseFxEntries(base.fxPtr), built.parseFxEntries(actual.fxPtr))
                    assertEquals(parser.parseEnemyPopulation(base.enemySetPtr), built.parseEnemyPopulation(actual.enemySetPtr))
                    assertEquals(parser.parseEnemyGfxSet(base.enemyGfxPtr), built.parseEnemyGfxSet(actual.enemyGfxPtr))
                }
                assertEquals(parser.inspectRoomStates(room.roomId).states.map { it.condition }, built.inspectRoomStates(room.roomId).states.map { it.condition })
                assertEquals(nativeSave, built.readSaveEntry(0, slot)!!.words())
            }
            assertEquals(otherFx.entries, built.parseFxEntries(built.parseRoomStatesWithData(otherRoom.roomId).first().fxPtr))
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify(true)
        val banks = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE, com.supermetroid.editor.asm.ENEMY_SOURCE_FILE)
        val sources = banks.associateWith { workspace.projectSourceText(it) }
        val metadata = com.supermetroid.editor.asm.snapshotRoomForResize(room)
        assertTrue(editor.resetCurrentRoomToOriginal(parser), editor.statusMessage)
        assertFalse(project.rooms.getValue("91F8").hasEdits)
        buildAndVerify(false)
        assertTrue(workspace.undoSourceEdit(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE), workspace.error)
        assertEquals(metadata, project.rooms["91F8"])
        assertEquals(sources, banks.associateWith { workspace.projectSourceText(it) })
        buildAndVerify(true)
        assertTrue(editor.redo(), editor.statusMessage)
        assertFalse(project.rooms.getValue("91F8").hasEdits)
    }

    @Test fun `source room resize compiles grids triggers and geometry with reciprocal undo and ordinary patches`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        val native = parser.readRoomHeader(0xACB3)!!
        editor.loadRoom(native.roomId, parser, native)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.addScrollTriggerWithCommands(8, 8, listOf(com.supermetroid.editor.data.ScrollCommand(native.width + 1, 0)))
        editor.setTileProperties(12, 12, 0, 3)
        val before = editor.workingLevelData!!.copyOf()
        val oldGrid = editor.workingScrolls.copyOf()
        val source = bridge.sourceText()
        assertTrue(editor.resizeRoom(native.width, native.height, native.width + 1, native.height), editor.statusMessage)
        val expected = resizeLevelData(before, native.width, native.height, native.width + 1, native.height)
        assertContentEquals(expected, editor.workingLevelData)
        val room = project.rooms.getValue("ACB3")
        project.patches += SmPatch("resize-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify(width: Int, pixels: ByteArray, grid: IntArray) {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            assertEquals(width, built.readRoomHeader(room.roomId)!!.width)
            room.states.zip(built.parseRoomStatesWithData(room.roomId)).forEach { (state, runtime) ->
                assertContentEquals(pixels, built.decompressLZ2(runtime.levelDataPtr))
                assertContentEquals(grid, built.parseScrollData(runtime.scrollPtr, width, native.height))
                val trigger = built.parsePlmSet(runtime.plmSetPtr).single { it.id == 0xB703 && it.x == 8 && it.y == 8 }
                assertEquals(listOf(Triple(width + 1, 1, 0)), RomParser.decodeScrollCommands(built, trigger.param, width))
                if (room.asmInitialScrollSourceVersion != 0) assertEquals(grid.toList(), bridge.initialScrolls.view(room.roomId, state.id).resource!!.values)
            }
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify(native.width + 1, expected, editor.workingScrolls.copyOf())
        assertTrue(editor.undo(), editor.statusMessage)
        assertEquals(source, bridge.sourceText())
        assertContentEquals(before, editor.workingLevelData)
        buildAndVerify(native.width, before, oldGrid)
        assertTrue(editor.redo(), editor.statusMessage)
        assertContentEquals(expected, editor.workingLevelData)
    }

    @Test fun `compound generated room data adopts and undoes with real Asar and ordinary ROM patches`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        editor.loadRoom(0xACB3, parser, parser.readRoomHeader(0xACB3)!!)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.addScrollTriggerWithCommands(8, 8, listOf(com.supermetroid.editor.data.ScrollCommand(0, 0)))
        editor.addPlm(0xB647, 8, 9, 0x8000)
        assertTrue(editor.workingPlms.any { it.id == 0xB703 }, editor.statusMessage)
        val beforePixels = editor.workingLevelData!!.copyOf()
        val beforeObjects = editor.workingPlms.toList()
        val beforeGrid = editor.workingScrolls.copyOf()
        val old = editor.readBlockWord(12, 12)
        val width = editor.workingBlocksWide / 16
        val height = editor.workingBlocksTall / 16
        val scrolls = editor.workingScrolls.toList().mapIndexedNotNull { index, value ->
            val target = if (index / width == height - 1) 2 else 1
            if (value == target) null else com.supermetroid.editor.data.ScrollChange(index % width, index / width, value, target)
        }
        val removals = editor.workingPlms.filter { RomParser.isScrollPlm(it.id) }.map { PlmChange("remove", it.id, it.x, it.y, it.param) }
        assertTrue(editor.applyGeneratedRoomOperation("Generate biome (real Asar fixture)",
            listOf(com.supermetroid.editor.data.TileEdit(12, 12, old, old xor 1, editor.readBts(12, 12), editor.readBts(12, 12))), scrolls, removals), editor.statusMessage)
        val afterPixels = editor.workingLevelData!!.copyOf()
        val room = project.rooms.getValue("ACB3")
        assertEquals(1, room.asmPlmSourceVersion)
        assertEquals(1, room.asmInitialScrollSourceVersion)
        project.patches += SmPatch("generation-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify(pixels: ByteArray, sourceOwned: Boolean) {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(room.roomId)
            room.states.forEachIndexed { index, state ->
                assertContentEquals(pixels, built.decompressLZ2(states[index].levelDataPtr))
                if (sourceOwned) {
                    assertEquals(bridge.managedView(room.roomId, state.id).document!!.entries, built.parsePlmSet(states[index].plmSetPtr))
                    assertEquals(bridge.initialScrolls.view(room.roomId, state.id).resource!!.values,
                        built.parseScrollData(states[index].scrollPtr, width, height).toList())
                    assertTrue(built.parsePlmSet(states[index].plmSetPtr).none { RomParser.isScrollPlm(it.id) })
                } else {
                    val entries = built.parsePlmSet(states[index].plmSetPtr)
                    assertTrue(entries.any { it.id == 0xB703 })
                    val trigger = entries.single { it.id == 0xB703 && it.x == 8 && it.y == 8 }
                    assertEquals(0, RomParser.decodeScrollCommands(built, trigger.param, width).first().third)
                    assertContentEquals(beforeGrid, built.parseScrollData(states[index].scrollPtr, width, height))
                }
            }
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify(afterPixels, true)
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(beforePixels, editor.workingLevelData)
        assertEquals(beforeObjects, editor.workingPlms)
        assertEquals(0, room.asmPlmSourceVersion)
        assertEquals(0, room.asmInitialScrollSourceVersion)
        buildAndVerify(beforePixels, false)
        assertTrue(editor.redo(), editor.statusMessage)
        assertContentEquals(afterPixels, editor.workingLevelData)
    }

    @Test fun `authored enemy populations and GFX compose with states PLMs and ROM patches with real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.ensureEnemySource("Landing Site"), editor.statusMessage)
        val room = project.rooms.getValue("91F8")
        val stateId = editor.currentPlmStateId()!!
        val original = editor.workingEnemies.first()
        editor.updateEnemy(original, original.copy(x = original.x + 16, extra3 = 0x0123))
        assertNull(editor.enemyPreviewProblem)
        val newId = editor.addRoomState(stateId, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        editor.switchRoomState(newId, parser)
        editor.addEnemy(0xD17F, 128, 160)
        assertNull(editor.enemyPreviewProblem, editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource("Landing Site"))
        editor.setScroll(0, 0, 2, editor.workingBlocksWide / 16)
        editor.setRoomStateFxChange(newId, com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x100), parser)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = TileBrush(listOf(listOf(42, 43)), plmOverrides = mapOf(0L to (0xEEDF to 0)))
        val patternBefore = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.paintAt(12, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        val patternAfter = editor.workingLevelData!!.copyOf()
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(patternBefore, editor.workingLevelData)
        assertTrue(editor.redo(), editor.statusMessage)
        assertContentEquals(patternAfter, editor.workingLevelData)
        project.patches += SmPatch("enemy-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x43))))
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(room.roomId)
            room.states.forEachIndexed { index, state ->
                val expected = assertNotNull(bridge.enemies.view(room.roomId, state.id).resource)
                assertContentEquals(expected.population.bytes(), com.supermetroid.editor.rom.readSourceEnemyPopulation(built, states[index].enemySetPtr, expected.population.identity).bytes())
                assertContentEquals(expected.graphics.bytes(), com.supermetroid.editor.rom.readSourceEnemyGraphics(built, states[index].enemyGfxPtr, expected.graphics.identity).bytes())
                assertEquals(bridge.managedView(room.roomId, state.id).document!!.entries, built.parsePlmSet(states[index].plmSetPtr))
                if (state.resources.level == room.states.single { it.id == newId }.resources.level) {
                    assertContentEquals(patternAfter, built.decompressLZ2(states[index].levelDataPtr), "Compound pattern geometry and PLMs must reach the same built ROM")
                }
            }
            val populations = room.states.mapIndexed { index, state -> bridge.enemies.view(room.roomId, state.id).resource!!.population.identity to states[index].enemySetPtr }.groupBy({ it.first }, { it.second })
            assertTrue(populations.values.all { it.distinct().size == 1 })
            assertEquals(populations.size, populations.values.map { it.first() }.distinct().size)
            assertEquals(0x43, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.owner == "patch:enemy-test-patch" })
            val generated = AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!.lines.joinToString("\n")
            assertContains(generated, "; Enemy placement: authored paired values live in bank_A1.asm.")
            assertContains(generated, "assert (")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        val before = bridge.enemies.sourceText()
        val selected = bridge.enemies.view(room.roomId, newId).resource!!.population
        val body = authoredMacroBody(before, selected.identity)
        workspace.replaceProjectSource(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE, before, before.replace(body,
            body.replace("\$0080, \$00A0", "\$0090, \$00A0")))
        editor.refreshLinkedPlms()
        assertEquals(144, editor.workingEnemies.last().x)
        buildAndVerify()
        val current = bridge.enemies.sourceText()
        val invalid = current.replace("db \$00 ; Required kills", "db \$100 ; Required kills")
        assertNotEquals(current, invalid)
        workspace.replaceProjectSource(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE, current, invalid)
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val failure = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!
        assertFalse(failure.succeeded)
        assertTrue(failure.diagnostics.any { it.fileId == com.supermetroid.editor.asm.ENEMY_SOURCE_FILE && it.lineIndex != null })
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `authored FX compose with PLMs scrolls door variants new states and ROM patches with real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.ensureCurrentPlmSource("Landing Site"))
        editor.setScroll(0, 0, if (editor.workingScrolls[0] == 0) 1 else 0, editor.workingBlocksWide / 16)
        val landing = project.rooms.getValue("91F8")
        val landingState = editor.currentPlmStateId()!!
        editor.setRoomStateFxChange(landingState, com.supermetroid.editor.data.FxChange(fxType = 6,
            liquidSurfaceStart = 0x0100, liquidSurfaceNew = 0x0080, liquidSpeed = 0xFFF0, liquidDelay = 30), parser)
        assertEquals(1, landing.asmFxSourceVersion, editor.statusMessage)
        assertEquals(0x0100, editor.effectiveFxEntries(landing.roomId, parser).last().liquidSurfaceStart)
        val routed = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.first { room ->
            room.roomId != 0xDD58 && parser.parseRoomStatesWithData(room.roomId).any { state -> parser.parseFxEntries(state.fxPtr).any { it.doorSelect != 0 } }
        }
        bridge.fx.migrate(routed.roomId, routed.name)
        val routedRoom = project.rooms.getValue(project.roomKey(routed.roomId))
        val routedState = routedRoom.states.first { bridge.fx.view(routed.roomId, it.id).resource!!.entries.any { fx -> fx.doorSelect != 0 } }.id
        val doorEntry = bridge.fx.view(routed.roomId, routedState).resource!!.entries.first { it.doorSelect != 0 }
        val originalDefault = bridge.fx.view(routed.roomId, routedState).resource!!.entries.lastOrNull { it.doorSelect == 0 }
        editor.loadRoom(routed.roomId, parser, routed)
        editor.switchRoomState(routedState, parser)
        editor.setRoomStateDoorFxChange(routedState, doorEntry.doorSelect, com.supermetroid.editor.data.FxChange(liquidDelay = 60, liquidSpeed = 0xFFE0), parser)
        assertEquals(originalDefault, bridge.fx.view(routed.roomId, routedState).resource!!.entries.lastOrNull { it.doorSelect == 0 })
        val empty = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.first { room ->
            room.roomId != 0xDD58 && parser.parseRoomStatesWithData(room.roomId).all { parser.parseFxEntries(it.fxPtr).isEmpty() }
        }
        bridge.fx.migrate(empty.roomId, empty.name)
        val emptyRoom = project.rooms.getValue(project.roomKey(empty.roomId))
        bridge.fx.edit(empty.roomId, emptyRoom.states.last().id, 0, com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x0040))
        project.patches += SmPatch("fx-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources(), workspace.error ?: "Source save failed: busy=${workspace.busy}, progress=${workspace.progress}")
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            for (room in listOf(landing, routedRoom, emptyRoom)) {
                val states = built.parseRoomStatesWithData(room.roomId)
                val inputs = room.states.map { bridge.fx.view(room.roomId, it.id).resource!! }
                inputs.forEachIndexed { index, expected ->
                    assertEquals(expected.entries, built.parseFxEntries(states[index].fxPtr))
                    assertContentEquals(expected.bytes(), com.supermetroid.editor.rom.readSourceFxResource(built, states[index].fxPtr, expected.identity).bytes())
                }
                val identities = inputs.mapIndexed { index, input -> input.identity to states[index].fxPtr }.groupBy({ it.first }, { it.second })
                assertTrue(identities.values.all { it.distinct().size == 1 })
                assertEquals(identities.size, identities.values.map { it.first() }.distinct().size)
            }
            val builtLanding = built.parseRoomStatesWithData(landing.roomId)
            landing.states.forEachIndexed { index, state ->
                assertEquals(bridge.managedView(landing.roomId, state.id).document!!.entries, built.parsePlmSet(builtLanding[index].plmSetPtr))
                assertEquals(bridge.initialScrolls.view(landing.roomId, state.id).resource!!.values,
                    built.parseScrollData(builtLanding[index].scrollPtr, built.readRoomHeader(landing.roomId)!!.width, built.readRoomHeader(landing.roomId)!!.height).toList())
            }
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.owner == "patch:fx-test-patch" })
            assertContains(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!.lines.joinToString("\n"), "; FX placement; values live in bank_83.asm.")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        val selected = bridge.fx.view(landing.roomId, landingState).resource!!
        val before = bridge.fx.sourceText()
        val body = authoredMacroBody(before, selected.identity)
        workspace.replaceProjectSource(FX_SOURCE_FILE, before, before.replace(body, body.replace("dw \$0100 ; Liquid Start", "dw \$0080 ; Liquid Start")))
        editor.loadRoom(landing.roomId, parser, parser.readRoomHeader(landing.roomId)!!)
        editor.switchRoomState(landingState, parser)
        editor.refreshLinkedPlms()
        assertEquals(0x0080, editor.effectiveFxEntries(landing.roomId, parser).last().liquidSurfaceStart)
        val added = editor.addRoomState(landingState, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        bridge.fx.edit(landing.roomId, added, 0, com.supermetroid.editor.data.FxChange(paletteBlend = 0x40))
        buildAndVerify()
        val sourceBeforeGuard = bridge.fx.sourceText()
        assertTrue(runCatching { bridge.fx.migrate(0xDD58, "Mother Brain") }.isFailure)
        assertEquals(sourceBeforeGuard, bridge.fx.sourceText(), "Never drop Mother Brain's indexed battle FX tail")
        val currentFx = bridge.fx.view(landing.roomId, landingState).resource!!
        val currentBody = authoredMacroBody(sourceBeforeGuard, currentFx.identity)
        val invalid = sourceBeforeGuard.replace(currentBody, currentBody.replace("db \$06 ; FX Type", "db \$03 ; FX Type"))
        assertNotEquals(sourceBeforeGuard, invalid)
        workspace.replaceProjectSource(FX_SOURCE_FILE, sourceBeforeGuard, invalid)
        assertTrue(workspace.saveAllSources(), workspace.error)
        val errors = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(project, parser, errors::add, errors::add, fixture.projectFile.path), errors.joinToString("\n"))
        val failure = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!
        assertFalse(failure.succeeded)
        val diagnostic = failure.diagnostics.first { it.fileId == FX_SOURCE_FILE }
        assertContains(invalid.lines()[assertNotNull(diagnostic.lineIndex)], "db \$03 ; FX Type")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `initial screen scrolls assemble with state isolation sentinels new states and ordinary patches`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(0x91F8, "Landing Site") // Existing object integration must compose with initial scrolls.
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        val original = editor.workingScrolls.toList()
        val width = editor.workingBlocksWide / 16
        editor.setScroll(0, 0, if (original[0] == 0) 1 else 0, width)
        val landing = project.rooms.getValue("91F8")
        assertEquals(1, landing.asmInitialScrollSourceVersion)
        val current = editor.currentPlmStateId()!!
        val sentinel = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }
            .first { it.roomScrollsPtr == 0 && it.height > 1 && it.width * it.height <= 50 }
        bridge.initialScrolls.migrate(sentinel.roomId, sentinel.name)
        val sentinelRoom = project.rooms.getValue(project.roomKey(sentinel.roomId))
        assertEquals(0, sentinelRoom.asmPlmSourceVersion)
        sentinelRoom.states.forEach { state ->
            val source = assertNotNull(bridge.initialScrolls.view(sentinel.roomId, state.id).resource)
            assertEquals(parser.parseScrollData(parser.parseRoomStatesWithData(sentinel.roomId)[state.sourceStateIndex!!].scrollPtr,
                sentinel.width, sentinel.height).toList(), source.values)
        }
        project.patches += SmPatch("initial-scroll-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            for (room in listOf(landing, sentinelRoom)) {
                val header = assertNotNull(built.readRoomHeader(room.roomId))
                val states = built.parseRoomStatesWithData(room.roomId)
                room.states.forEachIndexed { index, state ->
                    val expected = assertNotNull(bridge.initialScrolls.view(room.roomId, state.id).resource)
                    assertTrue(states[index].scrollPtr >= 0x8000, "Presets become concrete private source allocations")
                    assertEquals(expected.values, built.parseScrollData(states[index].scrollPtr, header.width, header.height).toList())
                }
                val pointersByIdentity = room.states.mapIndexed { index, state ->
                    bridge.initialScrolls.view(room.roomId, state.id).resource!!.identity to states[index].scrollPtr
                }.groupBy({ it.first }, { it.second })
                assertTrue(pointersByIdentity.values.all { it.distinct().size == 1 })
                assertEquals(pointersByIdentity.size, pointersByIdentity.values.map { it.first() }.distinct().size)
            }
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.owner == "patch:initial-scroll-test-patch" })
            assertContains(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!.lines.joinToString("\n"),
                "; Initial screen scroll placement; values live in bank_8F.asm.")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        val selected = assertNotNull(bridge.initialScrolls.view(landing.roomId, current).resource)
        val before = bridge.sourceText()
        val prefix = "macro ${selected.identity}()\n    db \$${selected.values[0].toString(16).uppercase().padStart(2, '0')}"
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace(prefix, "macro ${selected.identity}()\n    db \$02"))
        editor.refreshLinkedPlms()
        assertEquals(2, editor.workingScrolls[0])
        val added = editor.addRoomState(current, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        assertEquals(selected.identity, bridge.initialScrolls.view(landing.roomId, added).resource!!.identity)
        bridge.initialScrolls.edit(landing.roomId, added, 0, 1)
        buildAndVerify()
    }

    @Test fun `paired save PLM and spawn assemble atomically preserve patches and release only the final state`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        editor.addPlm(0xB76F, 8, 8, 0x8000)
        val station = assertNotNull(editor.workingPlms.singleOrNull { it.id == 0xB76F }, editor.statusMessage)
        val slot = station.param and 255
        assertEquals(2, slot, "Crateria's gunship and original save retain their slots")
        val stateId = editor.currentPlmStateId()!!
        editor.updateSaveStationSpawnFields(0, slot, -32, 104, 0, 0, parser)
        val authored = bridge.saveSpawn(0x91F8, 0, slot)!!
        val legacy = SmEditProject("test.sfc")
        val legacyRoom = legacy.getOrCreateRoom(0x91F8)
        legacyRoom.ensureStateManifest(parser)
        legacyRoom.plmChanges += PlmChange("add", station.id, station.x, station.y, station.param)
        val w = authored.words
        legacyRoom.saveStationSpawns += com.supermetroid.editor.data.SaveStationSpawnChange(0, slot, w[0], w[1], w[3], w[4], w[5], w[6])
        val oracle = assertNotNull(RomExporter(legacy, parser).build())
        project.patches += SmPatch("save-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult {
            assertTrue(workspace.saveAllSources())
            logs.clear()
            return assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult, expected: List<Int>) {
            val built = RomParser(result.resultRom)
            assertEquals(expected, built.readSaveEntry(0, slot)!!.words())
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM && it.label.contains("Save_0_$slot") && it.bytes.size == 14 })
            assertEquals(parser.readSaveEntry(0, 0)!!.words(), built.readSaveEntry(0, 0)!!.words(), "Gunship record untouched")
            assertEquals(parser.readSaveEntry(0, 1)!!.words(), built.readSaveEntry(0, 1)!!.words(), "Other room's save untouched")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        val first = build()
        verify(first, authored.words)
        assertEquals(RomParser(oracle.resultRom).readSaveEntry(0, slot)!!.words(), RomParser(first.resultRom).readSaveEntry(0, slot)!!.words())
        assertEquals(RomParser(oracle.resultRom).parseRoomStatesWithData(0x91F8).map { RomParser(oracle.resultRom).parsePlmSet(it.plmSetPtr) },
            RomParser(first.resultRom).parseRoomStatesWithData(0x91F8).map { RomParser(first.resultRom).parsePlmSet(it.plmSetPtr) })
        val otherState = project.rooms.getValue("91F8").states.first { it.id != stateId }.id
        assertTrue(project.rooms.getValue("91F8").states.all { branch ->
            bridge.managedView(0x91F8, branch.id).document!!.entries.count { it == station } == 1
        }, "A default GUI addition includes every state, without duplicates")
        editor.removePlm(station.x, station.y, station.id, ItemStateScope.THIS_STATE)
        assertFalse(bridge.saveSpawn(0x91F8, 0, slot)!!.cleared)
        verify(build(), authored.words)
        bridge.editSaveStation(0x91F8, otherState, { it == station }, null, authored, allStates = true)
        assertTrue(bridge.saveSpawn(0x91F8, 0, slot)!!.cleared)
        verify(build(), List(7) { 0 })
        assertTrue(project.rooms.getValue("91F8").saveStationSpawns.isEmpty())
    }

    @Test fun `verified original scroll source edits agree with real Asar and resizing is blocked before assembly`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        assertEquals(0x92B0, catalog.symbols["RoomPLM_LandingSite_0"])
        val checkedStreams = mutableSetOf<Int>()
        for ((pointer, consumers) in catalog.consumers) {
            for (consumer in consumers.distinctBy { it.roomId }) {
                val header = assertNotNull(parser.readRoomHeader(consumer.roomId))
                for (trigger in parser.parsePlmSet(pointer).filter { it.id == 0xB703 }) {
                    val decoded = com.supermetroid.editor.asm.AsmScrollCommandSource.original(catalog.originalText,
                        catalog.symbols, trigger.param, parser, header.width * header.height, catalog.originalText)
                    assertEquals(RomParser.decodeScrollCommands(parser, trigger.param, header.width).map { (screen, _, value) ->
                        com.supermetroid.editor.data.ScrollCommand(screen, value)
                    }, decoded, "${consumer.roomName}: ${trigger.param.toString(16)}")
                    checkedStreams += trigger.param
                }
            }
        }
        assertTrue(checkedStreams.size > 100, "Cover the vanilla command graph, not only Landing Site")
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        bridge.migrateRoom(0x91F8, "Landing Site")
        val stateId = project.rooms.getValue("91F8").states.first { state ->
            bridge.managedView(0x91F8, state.id).document!!.entries.any { it.id == 0xB703 }
        }.id
        val trigger = bridge.managedView(0x91F8, stateId).document!!.entries.single { it.id == 0xB703 }
        val text = bridge.sourceText()
        val declaration = Regex("(?m)^RoomPLM_LandingSite_0:\\r?\\n[^\\r\\n]+").find(text)!!
        val changed = declaration.value.replace("${'$'}13,${'$'}01", "${'$'}13,${'$'}00")
        assertNotEquals(declaration.value, changed)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, text.replaceRange(declaration.range, changed))
        assertEquals(listOf(com.supermetroid.editor.data.ScrollCommand(0x13, 0)), bridge.scrollCommands(0x91F8, stateId, trigger))
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        assertEquals(0, RomParser.decodeScrollCommands(built, trigger.param, 9).single().third)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, bridge.sourceText().replace(changed, changed.replace("${'$'}80", "${'$'}01,${'$'}01,${'$'}80")))
        assertTrue(workspace.saveAllSources())
        logs.clear()
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path))
        assertTrue(logs.any { it.contains("original stream resizing") })
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `authored scroll commands migrate grow and assemble with real pointers without losing ROM patches`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val room = project.getOrCreateRoom(0xACB3)
        val initialCommands = listOf(com.supermetroid.editor.data.ScrollCommand(0, 1), com.supermetroid.editor.data.ScrollCommand(3, 2))
        room.customScrollCommands["cmd_0"] = initialCommands.toMutableList()
        room.plmChanges += PlmChange("add", 0xB703, 4, 5, 0xCC00)
        project.patches += SmPatch(id = "scroll-test-patch", name = "Independent test byte", enabled = true,
            writes = mutableListOf(PatchWrite(offset = 0x2FFFF0, bytes = listOf(0x42))))
        val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(room.roomId, "Bubble Mountain")
        val stateId = room.states.single().id
        val logs = mutableListOf<String>()
        fun build(): RomBuildResult {
            logs.clear()
            return assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult, commands: List<com.supermetroid.editor.data.ScrollCommand>) {
            val built = RomParser(result.resultRom)
            val entries = built.parsePlmSet(built.parseRoomStatesWithData(room.roomId).single().plmSetPtr)
            val trigger = entries.single { it.id == 0xB703 && it.x == 4 && it.y == 5 }
            assertTrue(trigger.param in 0x8000..0xFFFF)
            assertEquals(commands, RomParser.decodeScrollCommands(built, trigger.param, 2).map { (screen, _, value) ->
                com.supermetroid.editor.data.ScrollCommand(screen, value)
            })
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM })
            val generated = AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!
            assertTrue(generated.lines.any { it.contains("%SmeditObjects_ACB3_Scroll_") })
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        assertTrue(workspace.saveAllSources())
        val first = build()
        verify(first, initialCommands)
        val built = RomParser(first.resultRom)
        val oracleParser = RomParser(oracle.resultRom)
        val firstEntries = built.parsePlmSet(built.parseRoomStatesWithData(room.roomId).single().plmSetPtr)
        val oracleEntries = oracleParser.parsePlmSet(oracleParser.parseRoomStatesWithData(room.roomId).single().plmSetPtr)
        assertEquals(oracleEntries.map { if (it.id == 0xB703) it.copy(param = 0) else it }, firstEntries.map { if (it.id == 0xB703) it.copy(param = 0) else it })
        // Raw ASM updates the preview; then a GUI edit grows the same semantic resource.
        workspace.updateSourceEditText(PLM_SOURCE_FILE, bridge.sourceText().replace("db ${'$'}00, ${'$'}01", "db ${'$'}00, ${'$'}00"))
        val trigger = bridge.managedView(room.roomId, stateId).document!!.entries.single { it.id == 0xB703 }
        assertEquals(0, bridge.scrollCommands(room.roomId, stateId, trigger).first().scrollValue)
        val grown = listOf(com.supermetroid.editor.data.ScrollCommand(0, 0), com.supermetroid.editor.data.ScrollCommand(1, 1),
            com.supermetroid.editor.data.ScrollCommand(2, 2), com.supermetroid.editor.data.ScrollCommand(3, 1))
        bridge.editScroll(room.roomId, stateId, 4, 5, grown)
        assertTrue(workspace.saveAllSources())
        verify(build(), grown)
        // Empty streams are legitimate no-ops, not truncated data; preserve the terminator.
        bridge.editScroll(room.roomId, stateId, 4, 5, emptyList())
        assertTrue(workspace.saveAllSources())
        verify(build(), emptyList())
    }

    @Test
    fun `single-state Bubble Mountain stays simple then grows shared state source with real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val room = project.getOrCreateRoom(0xACB3)
        room.plmChanges += PlmChange("add", 0xEF23, 0x1A, 0x0A, 0x100)
        room.plmChanges += PlmChange("add", 0xEEE7, 0x1C, 0x09, 0x101)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(room.roomId, "Bubble Mountain")
        bridge.preloadProjections()
        val state = room.states.single().id
        val bomb = RomParser.PlmEntry(0xEEE7, 0x1C, 0x09, 0x101)
        repeat(3) {
            bridge.editManaged(room.roomId, state, true, { it == bomb }, null)
            bridge.editManaged(room.roomId, state, true, { false }, bomb)
        }
        assertTrue(!bridge.sourceText().contains("SmeditObjects_ACB3_Common_"))
        val logs = mutableListOf<String>()
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources())
            logs.clear()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path),
                logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val expected = room.states.map { assertNotNull(bridge.managedView(room.roomId, it.id).document).entries }
            assertEquals(expected, built.parseRoomStatesWithData(room.roomId).map { built.parsePlmSet(it.plmSetPtr) })
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()

        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(room.roomId, parser, assertNotNull(parser.readRoomHeader(room.roomId)))
        val newState = editor.addRoomState(state,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        assertEquals(bridge.managedView(room.roomId, state).document?.entries, bridge.managedView(room.roomId, newState).document?.entries)
        bridge.editManaged(room.roomId, newState, false, { it == bomb }, null)
        bridge.editManaged(room.roomId, state, true, { false }, bomb.copy(x = 20, param = 0x102))
        val authored = AsmRoomObjectSource(bridge.sourceText(), bridge.catalog.symbols)
        assertNull(authored.table(room.roomId, state).objects.last().commonMacro)
        assertTrue(!bridge.sourceText().contains("SmeditObjects_ACB3_Common_"))
        assertTrue(authored.table(room.roomId, state).document.entries.contains(bomb))
        assertTrue(!authored.table(room.roomId, newState).document.entries.contains(bomb))
        buildAndVerify()
    }

    @Test
    fun `GUI edited room migrates to authored growing lists with state exceptions and real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val landing = project.getOrCreateRoom(0x91F8)
        landing.ensureStateManifest(parser)
        landing.plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        landing.states.last().plmChanges += PlmChange("remove", 0xEEDB, 8, 8, 0x100)
        landing.states.add(1, landing.states.removeAt(0))
        landing.states.removeAll { it.id == "state-3" }
        landing.stateGraphChanged = true
        val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
        val expected = RomParser(oracle.resultRom).let { rom -> rom.parseRoomStatesWithData(0x91F8).map { rom.parsePlmSet(it.plmSetPtr) } }
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        assertTrue(editor.ensureCurrentPlmSource("Landing Site"))
        assertEquals(1, landing.asmPlmSourceVersion)
        assertTrue(landing.plmChanges.isEmpty() && landing.states.all { it.plmChanges.isEmpty() })
        assertEquals(expected, landing.states.map { bridge.managedView(0x91F8, it.id).document?.entries })
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        fun build(): RomBuildResult {
            logs.clear()
            return assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        val first = RomParser(build().resultRom)
        assertEquals(expected, first.parseRoomStatesWithData(0x91F8).map { first.parsePlmSet(it.plmSetPtr) })
        val generated = AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)?.lines.orEmpty().joinToString("\n")
        assertTrue(generated.contains("%SmeditObjects_91F8_State_"), generated.take(500))

        editor.switchRoomState(landing.states.first().id, parser)
        editor.addPlm(0xEEDF, 9, 9, 0x101, itemScopeOverride = ItemStateScope.ALL_STATES)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF && it.x == 9 })
        assertTrue(editor.undo())
        assertTrue(editor.workingPlms.none { it.id == 0xEEDF && it.x == 9 })
        assertTrue(editor.redo())
        // A real legacy multi-state helper becomes direct rows before the same checked Asar build.
        val commonEntry = RomParser.PlmEntry(0xEEDF, 9, 9, 0x101)
        val legacyHelper = "SmeditObjects_91F8_Common_7"
        val legacy = bridge.sourceText().replace(AsmRoomObjectSource.renderEntry(commonEntry), "%$legacyHelper()") +
            "\nmacro $legacyHelper()\n    ${AsmRoomObjectSource.renderEntry(commonEntry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        assertTrue(workspace.saveAllSources())
        assertTrue(!bridge.sourceText().contains("SmeditObjects_91F8_Common_"))
        val newId = editor.addRoomState(landing.states.first().id,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        editor.switchRoomState(newId, parser)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF && it.x == 9 })
        editor.addPlm(0xEEE3, 10, 9, 0x102, itemScopeOverride = ItemStateScope.THIS_STATE)
        val objectView = assertNotNull(bridge.managedView(0x91F8, newId).document)
        val changedIndex = objectView.entries.indexOfFirst { it.id == 0xEEE3 && it.x == 10 }
        workspace.updateSourceEditText(PLM_SOURCE_FILE, objectView.replace(changedIndex,
            objectView.entries[changedIndex].copy(param = 0x103)))
        editor.refreshLinkedPlms()
        assertEquals(0x103, editor.workingPlms.single { it.id == 0xEEE3 && it.x == 10 }.param)
        assertTrue(landing.plmChanges.isEmpty() && landing.states.all { it.plmChanges.isEmpty() })
        assertTrue(workspace.saveAllSources())
        val authored = AsmRoomObjectSource(workspace.projectSourceText(PLM_SOURCE_FILE), bridge.catalog.symbols)
        val expectedAfter = landing.states.map { authored.table(0x91F8, it.id).document.entries }
        val second = build()
        val built = RomParser(second.resultRom)
        assertEquals(expectedAfter, built.parseRoomStatesWithData(0x91F8).map { built.parsePlmSet(it.plmSetPtr) })
        val pointers = built.parseRoomStatesWithData(0x91F8).map { it.plmSetPtr }
        val identities = landing.states.map { authored.table(0x91F8, it.id).resource.name }
        for (a in pointers.indices) for (b in pointers.indices) {
            assertEquals(identities[a] == identities[b], pointers[a] == pointers[b], "Source resource sharing must survive allocation")
        }
        assertEquals(1, expectedAfter.count { entries -> entries.any { it.id == 0xEEE3 && it.x == 10 } })
        assertTrue(expectedAfter.all { entries -> entries.any { it.id == 0xEEDF && it.x == 9 } })
        assertContentEquals(second.resultRom, build().resultRom)
        assertTrue(logs.any { it.contains("reusing prepared source base") }, logs.joinToString("\n"))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        // Standard format is a contract, not a guess about arbitrary user macros.
        workspace.updateSourceEditText("macros.asm", workspace.projectSourceText("macros.asm")
            .replace("macro PLMPopEntry(ID, X, Y, Param)", "macro PLMPopEntry(ID, X, Y, Param)\n    db 0"))
        assertTrue(workspace.saveAllSources())
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path))
        assertTrue(logs.any { it.contains("custom layout") }, logs.joinToString("\n"))
    }

    @Test
    fun `automatic PLM authority round trips GUI ASM and build without linking or a second room writer`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        // Landing Site: one real source list shared by three states. No Link step or saved binding.
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        val states = project.getOrCreateRoom(0x91F8).ensureStateManifest(parser)
        val sharedStateIndex = catalog.consumers.getValue(0x8000).first().stateIndex
        editor.switchRoomState(states[sharedStateIndex].id, parser)
        assertEquals(0x8000, editor.currentPlmSourcePointer())
        val original = assertNotNull(bridge.view(0x8000).document)
        bridge.update(0x8000, original, 0, original.entries[0].copy(x = 0x1D))
        editor.refreshLinkedPlms()
        assertEquals(0x1D, editor.workingPlms[0].x)
        assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1D,"))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, workspace.projectSourceText(PLM_SOURCE_FILE)
            .replaceFirst("%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1D,", "%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1C,"))
        editor.refreshLinkedPlms()
        assertEquals(0x1C, editor.workingPlms[0].x)
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        editor.refreshLinkedPlms()
        assertEquals(original.entries, editor.workingPlms, "Undo to baseline must restore preview and release automatic ownership")
        assertTrue(!bridge.isLinked(0x8000))
        workspace.redoSourceEdit(PLM_SOURCE_FILE)
        workspace.redoSourceEdit(PLM_SOURCE_FILE)
        val anotherSharedState = catalog.consumers.getValue(0x8000).last().stateIndex
        editor.switchRoomState(states[anotherSharedState].id, parser)
        assertEquals(0x1C, editor.workingPlms[0].x, "Switching state must use the source projection immediately")
        assertTrue(project.rooms.values.all { it.plmChanges.isEmpty() && it.states.all { state -> state.plmChanges.isEmpty() } })
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        assertEquals(original.entries[0].copy(x = 0x1C), built.parsePlmSet(0x8000)[0])
        assertEquals(3, built.parseRoomStatesWithData(0x91F8).count { it.plmSetPtr == 0x8000 })
        assertTrue(result.writeReport.writes.none { it.owner == "room-graph:project" }, "Source-linked records must not be replayed as GUI edits")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())

        // An unchanged byte still belongs to the source resource: patch overlap must fail, even with identical data.
        project.patches += SmPatch("linked_overlap", "Conflicting object patch", enabled = true,
            writes = mutableListOf(PatchWrite(offset = 0x78000, bytes = listOf(fixture.rom[0x78000].toInt() and 0xFF))))
        val conflictLogs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(project, parser, conflictLogs::add, conflictLogs::add, fixture.projectFile.path))
        assertTrue(conflictLogs.any { it.contains("conflict", ignoreCase = true) }, conflictLogs.joinToString("\n"))
    }

    @Test
    fun `edited Landing Site keeps GUI authority while Wasteland source is adopted automatically`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val landing = project.getOrCreateRoom(0x91F8)
        val states = landing.ensureStateManifest(parser)
        // Model the user's important combination: a changed graph, common additions, and a state exception.
        val moved = landing.states.removeAt(0)
        landing.states.add(1, moved)
        landing.stateGraphChanged = true
        landing.plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        states.last().plmChanges += PlmChange("remove", 0xEEDB, 8, 8, 0x100)
        val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())

        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val source = workspace.projectSourceText(PLM_SOURCE_FILE)
        val changed = source.replace("%PLMPopEntry(PLMEntries_PowerBombTank, ${'$'}07, ${'$'}08, ${'$'}004C)",
            "%PLMPopEntry(PLMEntries_SuperMissileTank, ${'$'}07, ${'$'}08, ${'$'}004C)")
        assertNotEquals(source, changed)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, changed)
        assertTrue(workspace.saveAllSources())
        val metadataBefore = project.rooms.mapValues { (_, value) -> value.plmChanges.toList() }
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        val expected = RomParser(oracle.resultRom)
        assertEquals(expected.parseRoomStatesWithData(0x91F8).map { expected.parsePlmSet(it.plmSetPtr) },
            built.parseRoomStatesWithData(0x91F8).map { built.parsePlmSet(it.plmSetPtr) })
        assertEquals(0xEEDF, built.parsePlmSet(0x9036).last().id)
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertEquals(metadataBefore, project.rooms.mapValues { (_, value) -> value.plmChanges.toList() })
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())

        // Editing that same GUI-owned Landing Site list in source is NOT another automatic takeover.
        workspace.updateSourceEditText(PLM_SOURCE_FILE, changed.replaceFirst(
            "%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1E,", "%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1D,"))
        assertTrue(workspace.saveAllSources())
        logs.clear()
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path))
        assertTrue(logs.any { it.contains("Both versions are retained") }, logs.joinToString("\n"))
        assertEquals(metadataBefore, project.rooms.mapValues { (_, value) -> value.plmChanges.toList() })
    }

    @Test
    fun `changed selectors and reordered states keep source validation and final room data consistent`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        for (reorder in listOf(false, true)) {
            val project = sourceProject(fixture)
            val edits = project.getOrCreateRoom(0x91F8)
            val states = edits.ensureStateManifest(parser)
            if (reorder) {
                val first = edits.states.removeAt(0)
                edits.states.add(1, first)
                edits.stateGraphChanged = true
            } else {
                states[2].condition = states[2].condition.copy(argument = 2)
                states[2].conditionChanged = true
            }
            val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path),
                logs.joinToString("\n"))
            assertTrue(result.sourceRoomPatched > 0)
            assertEquals(asmGeneratedParitySha256(oracle.resultRom), asmGeneratedParitySha256(result.resultRom))
            val data = assertNotNull(AsmBuildArtifactRepository().loadRoomData(fixture.projectFile.path))
            assertTrue(data.isRoomDataSnapshot)
            assertEquals(parser.roomCatalog.rooms.size, data.groups().size)
            assertEquals("Landing Site", data.groups().single { it.roomId == 0x91F8 }.title)
        }
    }

    @Test
    fun `PLM growth sharing selected-state shrink and empty lists compile with symbolic pointers`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val before = parser.parseRoomStatesWithData(0x91F8)
        val originals = before.map { parser.parsePlmSet(it.plmSetPtr) }
        val sharedStateIndex = before.indices.first { i -> before.count { it.plmSetPtr == before[i].plmSetPtr } > 1 }
        val item = PlmChange("add", 0xEEDB, 8, 8, 0x100)
        val cases = listOf("all-state growth", "selected-state growth", "selected-state shrink", "empty lists")
        cases.forEach { scenario ->
            val project = sourceProject(fixture)
            val edits = project.getOrCreateRoom(0x91F8)
            when (scenario) {
                "all-state growth" -> edits.plmChanges += item
                "selected-state growth" -> edits.ensureStateManifest(parser)[sharedStateIndex].plmChanges += item
                "selected-state shrink" -> {
                    val removed = originals[sharedStateIndex].last()
                    edits.ensureStateManifest(parser)[sharedStateIndex].plmChanges +=
                        PlmChange("remove", removed.id, removed.x, removed.y, removed.param)
                }
                else -> originals.flatten().distinct().forEach { removed ->
                    edits.plmChanges += PlmChange("remove", removed.id, removed.x, removed.y, removed.param)
                }
            }
            val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(
                project, parser, logs::add, logs::add, fixture.projectFile.absolutePath,
            ), "$scenario\n${logs.joinToString("\n")}")
            assertEquals(asmGeneratedParitySha256(oracle.resultRom), asmGeneratedParitySha256(result.resultRom), scenario)
            assertTrue(result.sourceRoomPatched > 0)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM })
            assertTrue(result.writeReport.writes.none {
                it.owner == "room-graph:project" && it.kind in setOf(RomWriteKind.ROOM, RomWriteKind.ALLOCATION)
            })
            val buildDirectory = ProjectPaths(fixture.projectFile).asmBuild
            val generated = File(buildDirectory, "generated-rooms.asm").readText()
            assertTrue(generated.contains("dw SmeditRoom_91F8_State"), generated)
            val snapshot = assertNotNull(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path))
            assertEquals(generated.lines(), snapshot.lines)
            assertTrue(snapshot.sections.any { it.roomId == 0x91F8 }, "actual emitted tables must link back to Landing Site")
            val symbols = AsmWlaSymbolParser.parse(File(buildDirectory, "symbols.sym").readText())
            val tableAddresses = symbols.labels.filterKeys { it.startsWith("SmeditRoom_") && it.endsWith("_Plms") }
                .values.map { it and 0xFFFF }.toSet()
            assertTrue(tableAddresses.isNotEmpty())
            val built = RomParser(result.resultRom)
            val after = built.parseRoomStatesWithData(0x91F8)
            val lists = after.map { built.parsePlmSet(it.plmSetPtr) }
            when (scenario) {
                "all-state growth" -> {
                    assertEquals(before.size, after.size)
                    before.indices.forEach { i ->
                        assertEquals(originals[i] + RomParser.PlmEntry(item.plmId, item.x, item.y, item.param), lists[i])
                        assertNotEquals(before[i].plmSetPtr, after[i].plmSetPtr)
                        assertTrue(after[i].plmSetPtr in tableAddresses)
                        before.indices.forEach { j ->
                            assertEquals(before[i].plmSetPtr == before[j].plmSetPtr, after[i].plmSetPtr == after[j].plmSetPtr)
                        }
                    }
                }
                "selected-state growth", "selected-state shrink" -> {
                    assertNotEquals(before[sharedStateIndex].plmSetPtr, after[sharedStateIndex].plmSetPtr)
                    assertTrue(after[sharedStateIndex].plmSetPtr in tableAddresses)
                    before.indices.filter { it != sharedStateIndex }.forEach { i ->
                        assertEquals(originals[i], lists[i])
                        assertEquals(before[i].plmSetPtr, after[i].plmSetPtr)
                    }
                    val expectedSize = originals[sharedStateIndex].size + if (scenario.endsWith("growth")) 1 else -1
                    assertEquals(expectedSize, lists[sharedStateIndex].size)
                }
                else -> assertTrue(lists.all { it.isEmpty() })
            }
        }
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test
    fun `room source asset and patch backends compose and reuse an unchanged build`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        project.patches += SmPatch(
            "config_samus_physics", "Samus Physics", enabled = true,
            configType = "samus_physics", configData = mutableMapOf("gravity" to 0x2C),
        )
        val legacyOffset = 0x1000
        val legacyValue = (fixture.rom[legacyOffset].toInt() and 0xFF) xor 0x5A
        val legacyPatch = SmPatch("fixture-legacy", "Legacy byte patch", enabled = true,
            writes = mutableListOf(PatchWrite(legacyOffset.toLong(), listOf(legacyValue))))
        project.patches += legacyPatch
        project.patches += SmPatch("fixture-disabled", "Disabled byte patch", enabled = false,
            writes = mutableListOf(PatchWrite(legacyOffset.toLong(), listOf(legacyValue xor 0xFF))))
        val asset = AsmAssetManifest.loadBundled().single { it.path == "Tiles_Zoomer.bin" }
        val replacement = fixture.rom.copyOfRange(asset.pcOffset, asset.endExclusive)
        replacement[0] = (replacement[0].toInt() xor 1).toByte()
        project.customGfx.spriteTileBlocks["enemy:DCFF"] = Base64.getEncoder().encodeToString(replacement)
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(
            project, parser, logs::add, logs::add, fixture.projectFile.absolutePath,
        ), logs.joinToString("\n"))
        assertTrue(result.sourceAssetPatched > 0 && result.sourceRoomPatched > 0 && result.sourcePatchPatched == 0,
            "assets=${result.sourceAssetPatched}, rooms=${result.sourceRoomPatched}, patches=${result.sourcePatchPatched}\n${logs.joinToString("\n")}")
        assertEquals(0x2C, result.resultRom[0x081EA2].toInt() and 0xFF)
        assertEquals(legacyValue, result.resultRom[legacyOffset].toInt() and 0xFF,
            "Legacy writes must apply after ASM alongside source-supported patches")
        val generatedFile = File(ProjectPaths(fixture.projectFile).asmBuild, "generated-rooms.asm")
        val generated = generatedFile.readText()
        generatedFile.writeText("; report from a different build\n")
        val cachedLogs = mutableListOf<String>()
        val cached = assertNotNull(ProjectFileService.buildRom(
            project, parser, cachedLogs::add, cachedLogs::add, fixture.projectFile.absolutePath,
        ), cachedLogs.joinToString("\n"))
        assertContentEquals(result.resultRom, cached.resultRom)
        assertEquals(generated, generatedFile.readText())
        assertEquals(generated.lines(), AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)?.lines)
        assertTrue(cachedLogs.any { it.contains("unchanged — reusing prepared source base") }, cachedLogs.joinToString("\n"))
        assertTrue(cachedLogs.none { it.contains("Compiling project ASM source") })

        // The ROM backend applies the same enabled patch values from the same GUI settings.
        val romProject = ProjectFileService.snapshotProject(project).apply {
            asmWorkspace.enabled = false
            asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        }
        val romLogs = mutableListOf<String>()
        val romResult = assertNotNull(ProjectFileService.buildRom(romProject, parser, romLogs::add, romLogs::add), romLogs.joinToString("\n"))
        assertEquals(result.resultRom[legacyOffset], romResult.resultRom[legacyOffset])
        assertEquals(result.resultRom[0x081EA2], romResult.resultRom[0x081EA2])
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())

        // A successful Asar pass must not hide a later planner failure in the IDE.
        project.patches += SmPatch(
            "fixture-invalid-range", "Invalid range fixture", enabled = true,
            writes = mutableListOf(PatchWrite(Int.MAX_VALUE.toLong(), listOf(0xFF))),
        )
        val failureLogs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(
            project, parser, failureLogs::add, failureLogs::add, fixture.projectFile.path,
        ))
        val failedReport = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path))
        assertEquals(false, failedReport.succeeded)
        assertTrue(failedReport.diagnostics.any { it.message.contains("range", ignoreCase = true) }, failureLogs.joinToString("\n"))
    }

    @Test
    fun `renamed project exports from either backend without changing base or authored paths`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val renamedProject = File(tempDirectory, "My New Hack.smedit")
        assertTrue(fixture.projectFile.renameTo(renamedProject))
        val project = sourceProject(fixture)
        assertTrue(ProjectFileService.saveProject(project, renamedProject.path, null, false) {})
        val paths = ProjectPaths(renamedProject)
        val parser = RomParser(fixture.rom)
        val logs = mutableListOf<String>()
        val output = assertNotNull(ProjectFileService.exportToRom(project, parser, logs::add, logs::add, renamedProject.path), logs.joinToString("\n"))
        assertEquals(File(tempDirectory, "My New Hack-v1.0.smc").absolutePath, output)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        assertContentEquals(fixture.rom, File(output).readBytes())
        assertTrue(File(paths.asmBuild, "symbols.sym").isFile)
        val roomData = assertNotNull(AsmBuildArtifactRepository().loadRoomData(renamedProject.path))
        assertEquals(parser.roomCatalog.rooms.size, roomData.groups().size, "unchanged builds must also publish all room data")
        val reopened = ProjectFileService.loadProject(renamedProject)
        assertEquals(fixture.romFile.absolutePath, reopened.romPath)
        ProjectLifecycle.verify(reopened, fixture.romFile)
        reopened.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        reopened.buildName = "rom"
        val patchedOutput = assertNotNull(ProjectFileService.exportToRom(reopened, parser, logs::add, logs::add, renamedProject.path))
        assertEquals("My New Hack-rom-v1.0.smc", File(patchedOutput).name)
        assertContentEquals(File(output).readBytes(), File(patchedOutput).readBytes())
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    private fun sourceProject(fixture: AsmProjectTestFixture) = SmEditProject(fixture.romFile.absolutePath).also {
        it.asmWorkspace.enabled = true
        it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
    }
}
