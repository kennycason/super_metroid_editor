package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.ShortChargePatch
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Uses the same private parity fixtures as the source-symbol audit; never touches a user's project. */
@Tag("asm-symbol-coverage")
class AsmSourceAugmentationIntegrationTest {
    @TempDir lateinit var temporary: File

    @Test fun `bundled documentation preserves the complete source graph and ROM`() = verify(AsmSourceAugmentation.bundled())

    @Test fun `cross bank scratch table and parent renames preserve all symbols ROM and smart fields`() = verify(
        stressRecipe(),
    )

    private fun stressRecipe() = AsmSourceAugmentation.bundled().copy(revision = "rename-proof", renames = listOf(
            AsmSourceRename("DP_Temp14", "DP_Scratch14"),
            AsmSourceRename("SpeedBoostTimerResetValues", "SpeedBoostTimerReloadTable"),
            AsmSourceRename("SamusPhysicsConstants", "SamusMovementPhysicsConstants"),
            AsmSourceRename("PeriodicSubDamage", "PendingFractionalDamage"),
            AsmSourceRename("PreInstruction_PaletteFXObject_SamusInHeat", "HandleSamusHeatPalette"),
            AsmSourceRename("AnimateSamus_FX_Lava", "HandleSamusLavaContact"),
            AsmSourceRename("EnemyHeaders_Kraid", "KraidBodyRecord"),
            AsmSourceRename("EnemyHeaders_Zoomer", "CrawlerRecord"),
            AsmSourceRename("SamusProjectileDataPointers_UnchargedBeams", "NormalBeamProjectilePointers"),
            AsmSourceRename("SamusProjectileDataPointers_ChargedBeams", "ChargedBeamProjectilePointers"),
            AsmSourceRename("DoorDirection", "DoorFacing"),
            AsmSourceRename("Layer1XPosition", "ScrollLayer1X"),
            AsmSourceRename("Layer1YPosition", "ScrollLayer1Y"),
            AsmSourceRename("DoorTransitionScrolling", "ScrollRoomTransition"),
            AsmSourceRename("DoorTransitionFunction_ScrollScreenToAlignment", "AlignRoomTransition"),
            AsmSourceRename("MainPauseRoutine", "PauseMenuDispatch"),
        ))

    @Test fun `current Objectives and Fast Doors modules compile identically on two frozen naming baselines`() {
        var firstOutput: ByteArray? = null
        for ((name, recipe) in listOf("older" to AsmSourceAugmentation.NONE, "renamed" to stressRecipe())) {
            AsmProjectCompiler.clearBuildCacheForTests()
            val fixture = AsmProjectTestFixture.create(File(temporary, name).apply { mkdirs() }, recipe)
            val baseline = assertNotNull(AsmSourceBaseline.load(fixture.workspace.root))
            val bindings = baseline.bindings
            val repo = AsmProjectWorkspaceRepository(augmentation = AsmSourceAugmentation("future-app",
                documentation = listOf(AsmSourceDocumentation("not-replayed", "missing.asm", "Missing", "RTS", listOf("Not replayed.")))))
            val source = requireNotNull(javaClass.getResourceAsStream("/asm-templates/objectives_pause_screen.asm"))
                .bufferedReader().use { it.readText() }
            val objectives = bindings.template(source) + "; Preserve my authored objectives notes.\n"
            repo.createModule(fixture.projectFile.path, "objectives.asm", initialSource = objectives)
            val project = SmEditProject(fixture.romFile.path).apply {
                asmWorkspace.enabled = true
                asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
                patches += PatchRepository.loadBundledPatches().single { it.id == AsmFastDoorsModule.PATCH_ID }.apply { enabled = true }
            }
            AsmFastDoorsModule.prepare(project, fixture.projectFile.path)
            val fastDoors = File(fixture.workspace.root, "modules/fast_doors.asm").readText()
            // Use the earlier verified free bytes so this independently authored pause page
            // and the Fast Doors helper cannot compete for the later free-space payload.
            val authored = fastDoors.replace("+\$0671", "+\$0001") + "; Keep my custom helper placement.\n"
            repo.persistSource(fixture.projectFile.path, AsmFastDoorsModule.FILE, authored)
            assertContains(authored, "LDA.W ${bindings.names.active("Layer1XPosition")},X")
            assertContains(objectives, "org ${bindings.names.active("MainPauseRoutine")}")
            val workspace = assertNotNull(repo.loadForBuild(fixture.projectFile.path))
            val compiled = AsmProjectCompiler(repo).compile(fixture.projectFile.path, fixture.rom)
            val checked = AsmFastDoorsModule.protect(project, workspace, compiled)
            assertContains(checked.curatedPatchIds, AsmFastDoorsModule.PATCH_ID)
            firstOutput?.let { assertContentEquals(it, checked.romBytes, "Changing only baseline names must not change either module's code") }
            firstOutput = checked.romBytes.copyOf()
            val index = assertNotNull(repo.load(fixture.projectFile.path)).referenceWorkspace.index
            val symbols = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8))
            val coverage = AsmSourceReferenceAuditor.audit(index, symbols)
            assertTrue(coverage.unresolved.isEmpty(), coverage.unresolved.take(10).joinToString())
            assertEquals(objectives, File(fixture.workspace.root, "modules/objectives.asm").readText())
            assertEquals(authored, File(fixture.workspace.root, "modules/fast_doors.asm").readText())
            assertEquals(baseline.id, AsmSourceBaseline.load(fixture.workspace.root)?.id)
            AsmProjectCompiler.clearBuildCacheForTests()
            val header = ByteArray(512) { 0x5A }
            val cold = AsmProjectCompiler(repo).compile(fixture.projectFile.path, header + fixture.rom)
            assertContentEquals(checked.romBytes, AsmFastDoorsModule.protect(project, workspace, cold).romBytes.copyOfRange(512, cold.romBytes.size))
            assertContentEquals(header, cold.romBytes.copyOfRange(0, 512))
        }
    }

    private fun verify(recipe: AsmSourceAugmentation) {
        val fixture = AsmProjectTestFixture.create(temporary, recipe)
        val manifest = assertNotNull(AsmSourceBaseline.load(fixture.workspace.root))
        val upstream = TestRomHelper.requireDisassemblyDir()
        val before = AsmSourceParser().parse(File(upstream, "src"), File(upstream, "data"), emptyList())
        val after = AsmSourceParser().parse(File(fixture.workspace.workingDirectory, "src"), File(fixture.workspace.workingDirectory, "data"), emptyList())
        assertEquals(before.labels.size, after.labels.size, "No declarations may disappear")
        before.labels.zip(after.labels).forEach { (old, updated) ->
            assertEquals(old.fileId, updated.fileId)
            assertEquals(manifest.symbols.active(old.name), updated.name, old.name)
            assertEquals(old.localScope?.let(manifest.symbols::active), updated.localScope, old.name)
            val origin = manifest.upstreamLines[updated.fileId]?.get(updated.lineIndex) ?: updated.lineIndex
            assertEquals(old.lineIndex, origin, old.name)
        }
        val compiled = AsmProjectCompiler().compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(fixture.rom, compiled.romBytes, "Documentation and naming must preserve the entire ROM")
        assertTrue(compiled.sourceOwnedRanges.isEmpty(), "Augmentation is the baseline, not a project edit")
        val originalSymbols = AsmWlaSymbolParser.parse(File(upstream, "symbols.sym").readText())
        val currentSymbols = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8))
        val natives = assertNotNull(manifest.nativeSymbols)
        assertEquals(163, natives.size)
        natives.forEach { native ->
            assertEquals(originalSymbols.labels[native.name], native.address, native.name)
            native.generatedMembers.forEach { member ->
                val name = "${native.name}_$member"
                val address = assertNotNull(originalSymbols.labels[name], name)
                assertTrue(address in native.address until native.address + 0x40, name)
                assertEquals(address, currentSymbols.labels[manifest.symbols.active(name)], name)
            }
        }
        assertEquals(originalSymbols.labels.size, currentSymbols.labels.size)
        originalSymbols.labels.forEach { (name, address) ->
            assertEquals(address, currentSymbols.labels[manifest.symbols.active(name)], name)
        }
        val originalCoverage = AsmSourceReferenceAuditor.audit(before, originalSymbols)
        val coverage = AsmSourceReferenceAuditor.audit(after, currentSymbols)
        assertEquals(originalCoverage.referenceCount, coverage.referenceCount, "Reference coverage must not shrink")
        assertTrue(coverage.unresolved.isEmpty(), coverage.unresolved.take(10).joinToString())
        fun useLocations(index: AsmReferenceIndex, label: AsmLabel, augmented: Boolean) = index.usagesFor(label).map { use ->
            use.fileId to if (augmented) manifest.upstreamLines[use.fileId]?.get(use.lineIndex) ?: use.lineIndex else use.lineIndex
        }.sortedWith(compareBy({ it.first }, { it.second }))
        // The same edge must reach the same declaration, including aliases at identical addresses.
        before.labels.zip(after.labels).forEach { (old, updated) ->
            assertEquals(useLocations(before, old, false), useLocations(after, updated, true), old.name)
        }
        val catalog = assertNotNull(AsmDataPatchSource.load(fixture.workspace.originalDirectory))
        val texts = catalog.originals
        val project = SmEditProject("fixture.sfc").apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            asmWorkspace.patchSourceKeys += listOf("${ShortChargePatch.CONFIG_TYPE}:${ShortChargePatch.STAGES_KEY}", "samus_physics:gravity", "samus_physics:run_max")
        }
        val protected = protectDataPatchSource(catalog, texts, project, compiled)
        assertContentEquals(fixture.rom, protected.romBytes)
        for (key in project.asmWorkspace.patchSourceKeys) {
            assertTrue(protected.nativePatchLocations.containsKey(key), "Missing checked locations for $key")
        }

        val environmental = assertNotNull(AsmEnvironmentalDamageSource.load(fixture.workspace.originalDirectory))
        project.asmWorkspace.patchSourceKeys += EnvironmentalDamagePatch.FIELDS.map { environmentalSourceKey(it.key) }
        val hazards = protectEnvironmentalDamageSource(environmental, environmental.originals, project, protected)
        assertContentEquals(fixture.rom, hazards.romBytes)
        assertEquals(3, hazards.patchDataLocations.size)
        val beam = assertNotNull(AsmBeamDamageSource.load(fixture.workspace.originalDirectory))
        project.asmWorkspace.beamDamageSourceKeys += beam.keys
        val beams = protectBeamDamageSource(beam, beam.originalText, project, hazards)
        assertContentEquals(fixture.rom, beams.romBytes)
        assertEquals(beam.keys, beams.beamDamageLocations.keys)

        // Neither enrichment nor typed identity may depend on optional address annotations.
        val headerText = File(fixture.workspace.originalDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText()
            .replace(Regex(";A0[0-9A-Fa-f]{4};"), "")
        val bosses = AsmBossStatsSource(headerText, manifest.bindings)
        project.asmWorkspace.bossStatsSourceKeys += bosses.keys
        val bossBuild = protectBossStatsSource(bosses, headerText, project, beams)
        assertContentEquals(fixture.rom, bossBuild.romBytes)
        val drops = File(fixture.workspace.originalDirectory, "src/$ENEMY_DROP_SOURCE_FILE").readText()
        val enemies = AsmEnemyTuningSource(headerText, drops, manifest.bindings)
        assertEquals(163, enemies.headers.size)
        assertEquals(15, enemies.original("zoomer_hp").value)
        project.asmWorkspace.enemyTuningSourceKeys += listOf("zoomer_hp", "zoomer_drop0", "zoomer_vuln0")
        val enemyBuild = protectEnemyTuningSource(enemies, headerText, drops, project, bossBuild)
        assertContentEquals(fixture.rom, enemyBuild.romBytes)
    }
}
