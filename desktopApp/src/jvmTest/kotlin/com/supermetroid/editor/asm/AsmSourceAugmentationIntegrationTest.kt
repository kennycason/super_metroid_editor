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
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Uses the same private parity fixtures as the source-symbol audit; never touches a user's project. */
@Tag("asm-symbol-coverage")
class AsmSourceAugmentationIntegrationTest {
    @TempDir lateinit var temporary: File

    @Test fun `bundled documentation preserves the complete source graph and ROM`() {
        val fixture = verify(AsmSourceAugmentation.bundled())
        // A disposable, generated review artifact, never a change to a user's pinned baseline.
        System.getProperty("smedit.asmEnrichmentReviewDirectory")?.let { directory ->
            for (fileId in listOf("bank_A0.asm", "bank_A3.asm")) {
                val output = File(directory, "src/$fileId")
                output.parentFile.mkdirs()
                File(fixture.workspace.originalDirectory, "src/$fileId").copyTo(output, overwrite = true)
            }
        }
    }

    @Test fun `cross bank scratch table and parent renames preserve all symbols ROM and smart fields`() {
        verify(stressRecipe())
    }

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

    @Test fun `explicit v1 to current update preserves a native enemy edit and real authored Objectives ROM output`() {
        val v2 = previousCrawlerRecipe()
        verifyBaselineUpdate(v2.copy(revision = "super-metroid-ntsc-1", documentation = v2.documentation.filterNot { it.id.startsWith("crawlers.") }))
    }

    @Test fun `explicit v2 to current update adds inline comments and preserves native edits and authored Objectives ROM output`() {
        verifyBaselineUpdate(previousCrawlerRecipe())
    }

    private fun previousCrawlerRecipe(): AsmSourceAugmentation =
        checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-2.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }

    private fun verifyBaselineUpdate(previous: AsmSourceAugmentation) {
        AsmProjectCompiler.clearBuildCacheForTests()
        val latest = AsmSourceAugmentation.bundled()
        val fixture = AsmProjectTestFixture.create(temporary, previous)
        val repo = AsmProjectWorkspaceRepository(starterModules = emptyList())
        val catalog = assertNotNull(AsmEnemyTuningSource.load(fixture.workspace.originalDirectory))
        val headers = File(fixture.workspace.workingDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText()
        val drops = File(fixture.workspace.workingDirectory, "src/$ENEMY_DROP_SOURCE_FILE").readText()
        val edited = catalog.edit("zoomer_hp", 42, headers, drops).first
        repo.persistSource(fixture.projectFile.path, ENEMY_HEADER_SOURCE_FILE, edited)
        val objectives = assertNotNull(javaClass.getResourceAsStream("/asm-templates/objectives_pause_screen.asm"))
            .bufferedReader().use { it.readText() } + "; My authored Objectives notes stay.\n"
        repo.createModule(fixture.projectFile.path, "objectives", initialSource = objectives)
        val before = AsmProjectCompiler(repo).compile(fixture.projectFile.path, fixture.rom)
        val updater = AsmBaselineUpdateRepository(repo)
        val upstream = TestRomHelper.requireDisassemblyDir()
        val reference = AsmReferenceWorkspace(upstream, assertNotNull(AsmSourceBaseline.load(fixture.workspace.root)).reference,
            AsmSourceParser().parse(File(upstream, "src"), File(upstream, "data"), emptyList()))
        val plan = updater.prepare(fixture.projectFile.path, reference)
        assertEquals(0, plan.conflictCount)
        assertEquals(previous.revision, AsmSourceBaseline.load(fixture.workspace.root)?.augmentationRevision)
        val result = updater.apply(plan, emptyMap(), fixture.rom)
        val after = AsmProjectCompiler(repo).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(before.romBytes, after.romBytes, "Adding crawler documentation must preserve the complete edited/modular ROM")
        assertEquals(latest.revision, result.workspace.baselineRevision)
        assertContains(File(result.workspace.workingDirectory, "src/bank_A3.asm").readText(), "; Falling uses a separate signed 16.16")
        assertContains(File(result.workspace.workingDirectory, "src/bank_A3.asm").readText(),
            ";A3E66F; Keep the starting orientation: upside right, left, down, or up (0..3).")
        assertEquals(objectives, File(result.workspace.root, "modules/objectives.asm").readText())
        val reloaded = assertNotNull(AsmEnemyTuningSource.load(result.workspace.originalDirectory))
        assertEquals(42, reloaded.field("zoomer_hp", File(result.workspace.workingDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText(), drops).value)
        assertEquals(previous.revision, AsmSourceBaseline.load(File(result.backupDirectory, "asm"))?.augmentationRevision)
    }

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

    private fun verify(recipe: AsmSourceAugmentation): AsmProjectTestFixture {
        val fixture = AsmProjectTestFixture.create(temporary, recipe)
        val manifest = assertNotNull(AsmSourceBaseline.load(fixture.workspace.root))
        val upstream = TestRomHelper.requireDisassemblyDir()
        val before = AsmSourceParser().parse(File(upstream, "src"), File(upstream, "data"), emptyList())
        val after = AsmSourceParser().parse(File(fixture.workspace.workingDirectory, "src"), File(fixture.workspace.workingDirectory, "data"), emptyList())
        val inlineNotes = planAsmSourceLineDocumentation(recipe.lineDocumentation, before)
            .associateBy { it.fileId to it.lineIndex }
        assertEquals(before.labels.size, after.labels.size, "No declarations may disappear")
        before.labels.zip(after.labels).forEach { (old, updated) ->
            assertEquals(old.fileId, updated.fileId)
            assertEquals(manifest.symbols.active(old.name), updated.name, old.name)
            assertEquals(old.localScope?.let(manifest.symbols::active), updated.localScope, old.name)
            val origin = manifest.upstreamLines[updated.fileId]?.get(updated.lineIndex) ?: updated.lineIndex
            assertEquals(old.lineIndex, origin, old.name)
        }
        after.files.forEach { file ->
            val original = assertNotNull(before.file(file.id)).lines
            file.lines.forEachIndexed { line, text ->
                val origin = manifest.upstreamLines[file.id]?.get(line) ?: line
                if (origin < 0) {
                    assertTrue(asmExecutableCode(text).isBlank(), "Inserted line must be documentation: ${file.id}:$line")
                } else {
                    val addition = inlineNotes[file.id to origin]?.text.orEmpty()
                    assertEquals(manifest.symbols.activeCode(original[origin]) + addition, text,
                        "Preserve original line ${file.id}:$origin except its explicitly declared comment suffix")
                }
            }
        }
        val compiled = AsmProjectCompiler().compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(fixture.rom, compiled.romBytes, "Documentation and naming must preserve the entire ROM")
        assertTrue(compiled.sourceOwnedRanges.isEmpty(), "Augmentation is the baseline, not a project edit")
        val originalSymbols = AsmWlaSymbolParser.parse(File(upstream, "symbols.sym").readText())
        val currentSymbols = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8))
        verifyCrawlerContracts(compiled.romBytes, manifest.symbols.canonicalLabels(currentSymbols.labels), after, manifest.symbols)
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
        return fixture
    }

    /** Pin the actual script/parameter/state contracts described by the new comments, not just their placement. */
    private fun verifyCrawlerContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
        fun address(name: String) = assertNotNull(symbols[name], name)
        fun word(name: String, offset: Int = 0): Int {
            val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
            return (rom[pc].toInt() and 255) or ((rom[pc + 1].toInt() and 255) shl 8)
        }
        val sides = listOf("Right", "Left", "Down", "Up")
        sides.forEachIndexed { orientation, side ->
            val entry = "InstList_Zeela_Zoomer_Upside${side}_0"
            val frames = "InstList_Zeela_Zoomer_Upside${side}_1"
            val handler = "Function_Crawlers_Crawling" + if (orientation < 2) "Vertically" else "Horizontally"
            assertEquals(address(entry) and 0xFFFF, word("InitialInstListPointers_Crawlers", orientation * 2))
            assertEquals(address("Instruction_Crawlers_FunctionInY") and 0xFFFF, word(entry))
            assertEquals(address(handler) and 0xFFFF, word(entry, 2))
            repeat(5) { frame -> assertEquals(3, word(frames, frame * 4), "$side frame duration") }
            assertEquals(address("Instruction_Common_GotoY") and 0xFFFF, word(frames, 20))
            assertEquals(address(frames) and 0xFFFF, word(frames, 22), "Frame loop must not reinstall the handler")
        }
        val species = listOf("Zeela_Zoomer", "Zeela_Zoomer", "Zeela_Zoomer", "Viola", "Sciser", "Zero")
        for (side in sides) {
            val table = "CrawlersInstListPointerTable_upside$side"
            species.forEachIndexed { variant, family ->
                val expected = when {
                    family == "Viola" -> "InstList_Viola_Upside$side"
                    family == "Zero" && side == "Up" -> "UNUSED_InstList_Zero_UpsideUp_FacingRight_A3990B"
                    family == "Zero" && side == "Down" -> "InstList_Zero_UpsideDown_FacingLeft_0"
                    family == "Zero" && side == "Right" -> "InstList_Zero_UpsideRight_FacingDown_0"
                    family == "Zero" -> "InstList_Zero_UpsideLeft_FacingUp_0"
                    else -> "InstList_${family}_Upside${side}_0"
                }
                assertEquals(address(expected) and 0xFFFF, word(table, variant * 2), "$table variant $variant")
            }
        }
        assertEquals(address("InitAI_Zoomer_MZoomer") and 0xFFFF, word("EnemyHeaders_Zoomer_initAI"))
        assertEquals(address("MainAI_Crawlers") and 0xFFFF, word("EnemyHeaders_Zoomer_mainAI"))
        assertEquals(0x0040, word("CrawlersSpeedTable"))
        assertEquals(0x0080, word("CrawlersSpeedTable", 2))

        fun statements(symbol: String, local: String? = null): List<String> {
            val root = index.labels.single { it.name == symbol && it.localScope == null }
            val label = local?.let { name -> index.labels.single { it.fileId == root.fileId && it.localScope == symbol && it.name == name } } ?: root
            val end = index.labels.filter { it.fileId == root.fileId && it.lineIndex > label.lineIndex }.minOfOrNull { it.lineIndex }
                ?: assertNotNull(index.file(root.fileId)).lines.size
            return assertNotNull(index.file(root.fileId)).lines.subList(label.lineIndex + 1, end)
                .map { asmStatementSignature(names.canonicalCode(asmExecutableCode(it))) }.filter(String::isNotEmpty)
        }
        fun signature(vararg code: String) = code.map(::asmStatementSignature)
        assertEquals(signature("LDX.W EnemyIndex", "JMP.W (Crawler.function,X)"), statements("MainAI_Crawlers"))
        assertEquals(signature("LDA.W \$0000,Y", "STA.W Crawler.function,X", "INY", "INY", "RTL"),
            statements("Instruction_Crawlers_FunctionInY"))
        for ((routine, branch) in listOf("Function_Crawlers_CrawlingVertically" to ".negate", "Function_Crawlers_CrawlingHorizontally" to ".notFalling")) {
            val turn = statements(routine, ".outsideTurn")
            assertEquals(signature("LDA.L Crawler.consecutiveTurnCounter,X", "INC", "STA.L Crawler.consecutiveTurnCounter,X",
                "CMP.W #\$0004", "BMI $branch", "LDA.W Crawler.function,X", "STA.L Crawler.nonFallingFunction,X",
                "LDA.W #Function_Crawlers_Falling", "STA.W Crawler.function,X", "RTL"), turn)
            assertEquals(signature("LDA.W EarthquakeTimer", "CMP.W #\$001E", "BNE .noQuake", "LDA.W EarthquakeType",
                "CMP.W #\$0014", "BNE .noQuake", "LDA.W Crawler.function,X", "STA.L Crawler.nonFallingFunction,X",
                "LDA.W #Function_Crawlers_Falling", "STA.W Crawler.function,X"), statements(routine))
        }
        assertEquals(signature("LDA.L Crawler.fallingYVelocity,X", "CMP.W #\$0004", "BPL .fallSpeedCapped",
            "LDA.L Crawler.fallingYSubVelocity,X", "CLC", "ADC.W #regional(\$8000, \$A000)",
            "STA.L Crawler.fallingYSubVelocity,X", "LDA.L Crawler.fallingYVelocity,X", "ADC.W #\$0000", "STA.L Crawler.fallingYVelocity,X"),
            statements("Function_Crawlers_Falling", ".noCollision"))
    }
}
