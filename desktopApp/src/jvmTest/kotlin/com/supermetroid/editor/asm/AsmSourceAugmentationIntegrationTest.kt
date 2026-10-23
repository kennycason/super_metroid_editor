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
            for (fileId in listOf("bank_80.asm", "bank_82.asm", "bank_8F.asm", "bank_90.asm", "bank_A0.asm", "bank_A3.asm")) {
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
            AsmSourceRename("Main_Enemy_Routine", "RunEnemyActors"),
            AsmSourceRename("ProcessEnemyInstructions", "AdvanceEnemyScript"),
        ))

    @Test fun `explicit v1 to current update preserves a native enemy edit and real authored Objectives ROM output`() {
        val v2 = previousCrawlerRecipe()
        verifyBaselineUpdate(v2.copy(revision = "super-metroid-ntsc-1", documentation = v2.documentation.filterNot { it.id.startsWith("crawlers.") }))
    }

    @Test fun `explicit v2 to current update adds inline comments and preserves native edits and authored Objectives ROM output`() {
        verifyBaselineUpdate(previousCrawlerRecipe())
    }

    @Test fun `explicit v3 to current update adds Waver documentation while retaining crawler notes and authored ROM output`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-3.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        verifyBaselineUpdate(previous)
    }

    @Test fun `explicit v4 to current update adds core documentation while retaining enemy notes and authored ROM output`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-4.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        verifyBaselineUpdate(previous)
    }

    @Test fun `explicit v5 to current update adds pause and door notes while preserving the authored Objectives hook and ROM`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-5.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        verifyBaselineUpdate(previous)
    }

    @Test fun `explicit v6 to current update adds room loading notes while preserving authored enemies Objectives and ROM`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-6.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        verifyBaselineUpdate(previous)
    }

    @Test fun `explicit v7 to current update adds enemy runtime notes while preserving authored enemies Objectives and ROM`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-7.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        verifyBaselineUpdate(previous)
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
        val edited = catalog.edit("waver_hp", 27, catalog.edit("zoomer_hp", 42, headers, drops).first, drops).first
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
        assertContentEquals(before.romBytes, after.romBytes, "Adding documentation must preserve the complete edited/modular ROM")
        assertEquals(latest.revision, result.workspace.baselineRevision)
        assertContains(File(result.workspace.workingDirectory, "src/bank_A3.asm").readText(), "; Falling uses a separate signed 16.16")
        assertContains(File(result.workspace.workingDirectory, "src/bank_A3.asm").readText(),
            ";A3E66F; Keep the starting orientation: upside right, left, down, or up (0..3).")
        assertContains(File(result.workspace.workingDirectory, "src/bank_A3.asm").readText(),
            ";A3875F; Read packed 8.8 speed across the subvelocity high byte and velocity low byte.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_82.asm").readText(),
            ";82897A; Publish the completed frame and wait for NMI's acknowledgement.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_80.asm").readText(),
            ";8095F9; Count every NMI, including the no-request lag path.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_82.asm").readText(),
            ";829108; Convert the page-state index into a word-pointer byte offset.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_82.asm").readText(),
            ";82E320; Test that pixel byte for zero; immediate BIT preserves the load's N flag.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_90.asm").readText(),
            ";90EA7A; The next outer-loop dispatch enters state $0C.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_A0.asm").readText(),
            ";A08B0B; Copy header health before running enemy-specific Init AI.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_8F.asm").readText(),
            ";8FE5E9; Discard the condition loop's synthetic near return word.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_A0.asm").readText(),
            ";A0907D; Stop on the first set bit, giving it priority over higher bits.")
        assertContains(File(result.workspace.workingDirectory, "src/bank_A3.asm").readText(),
            ";A38136; Replace it with the interpreter's shared exit word minus one.")
        assertEquals(objectives, File(result.workspace.root, "modules/objectives.asm").readText())
        val reloaded = assertNotNull(AsmEnemyTuningSource.load(result.workspace.originalDirectory))
        assertEquals(42, reloaded.field("zoomer_hp", File(result.workspace.workingDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText(), drops).value)
        assertEquals(27, reloaded.field("waver_hp", File(result.workspace.workingDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText(), drops).value)
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
        verifyWaverContracts(compiled.romBytes, manifest.symbols.canonicalLabels(currentSymbols.labels), after, manifest.symbols)
        verifyCoreContracts(compiled.romBytes, manifest.symbols.canonicalLabels(currentSymbols.labels), after, manifest.symbols)
        verifyAsmTransitionContracts(compiled.romBytes, manifest.symbols.canonicalLabels(currentSymbols.labels), after, manifest.symbols)
        verifyAsmRoomLoadingContracts(compiled.romBytes, manifest.symbols.canonicalLabels(currentSymbols.labels), after, manifest.symbols)
        verifyAsmEnemyRuntimeContracts(compiled.romBytes, manifest.symbols.canonicalLabels(currentSymbols.labels), after, manifest.symbols)
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

    /** Independently pin the vector, frame-handshake and dispatch contracts described by the core notes. */
    private fun verifyCoreContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
        fun address(name: String) = assertNotNull(symbols[name], name)
        fun bytes(name: String, size: Int, offset: Int = 0): List<Int> {
            val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
            return rom.copyOfRange(pc, pc + size).map { it.toInt() and 255 }
        }
        fun word(name: String, offset: Int = 0): Int = bytes(name, 2, offset).let { it[0] or (it[1] shl 8) }
        fun longJump(name: String) = listOf(0x5C, address(name) and 255, (address(name) ushr 8) and 255, address(name) ushr 16)
        assertEquals(address("Boot") and 0xFFFF, word("Emulation_RESET"))
        assertEquals(address("NMI") and 0xFFFF, word("Native_NMI"))
        assertEquals(address("IRQ") and 0xFFFF, word("Native_IRQ"))
        assertEquals(listOf(0x78, 0x18, 0xFB) + longJump("Boot_bank80"), bytes("Boot", 7), "SEI, CLC, XCE, then enter FastROM")
        for (entry in listOf("NMI", "IRQ"))
            assertEquals(listOf(0xC2, 0x30) + longJump("${entry}_bank80"), bytes(entry, 6), "$entry saves full-width registers in bank 80")
        assertEquals(listOf(0xFC, address("MainGameLoop_gamemodes") and 255, (address("MainGameLoop_gamemodes") ushr 8) and 255),
            bytes("MainGameLoop", 3, 0x2E), "The dispatcher uses JSR (table,X), not a long or computed-bank call")
        val states = listOf(
            "GameState_0_ResetStart", "GameState_1_TitleSequence", "GameState_2_GameOptionsMenu", "GameState_3_Nothing",
            "GameState_4_FileSelectMenus", "GameState_5_FileSelectMap", "GameState_6_1F_28_LoadingGameData_SetupNewGame_LoadDemoData",
            "GameState_7_MainGameplayFadingIn", "GameState_8_MainGameplay", "GameState_9_HitADoorBlock", "GameState_A_LoadingNextRoom",
            "GameState_B_LoadingNextRoom", "GameState_C_Pausing_NormalGameplayDarkening", "GameState_D_Pausing_LoadingPauseScreen",
            "GameState_E_Paused_LoadingPauseScreen", "GameState_F_Paused_MapAndItemScreens", "GameState_10_Unpausing_LoadingNormalGameplay",
            "GameState_11_Unpausing_LoadingNormalGameplay", "GameState_12_Unpausing_NormalGameplayBrightening", "GameState_13_DeathSequence_Start",
            "GameState_14_DeathSequence_BlackOutSurroundings", "GameState_15_DeathSequence_WaitForMusic", "GameState_16_DeathSequence_PreFlashing",
            "GameState_17_DeathSequence_Flashing", "GameState_18_DeathSequence_ExplosionWhiteOut", "GameState_19_DeathSequence_BlackOut",
            "GameState_1A_GameOverScreen", "GameState_1B_ReserveTankAuto", "UNUSED_GameState_1C_828B3F", "GameState_1D_DebugGameOverMenu",
            "GameState_1E_22_25_Intro_CeresGoesBoom_SamusGoesToZebes", "GameState_6_1F_28_LoadingGameData_SetupNewGame_LoadDemoData",
            "GameState_20_MadeItToCeresElevator", "GameState_21_BlackoutFromCeres", "GameState_1E_22_25_Intro_CeresGoesBoom_SamusGoesToZebes",
            "GameState_23_TimeUpWhiteOut", "GameState_23_TimeUpBlackOut", "GameState_1E_22_25_Intro_CeresGoesBoom_SamusGoesToZebes",
            "GameState_26_SamusEscapesFromZebes", "GameState_27_EndingAndCredits", "GameState_6_1F_28_LoadingGameData_SetupNewGame_LoadDemoData",
            "GameState_29_TransitionToDemo", "GameState_2A_PlayingDemo", "GameState_2B_UnloadGameData", "GameState_2C_TransitionFromDemo")
        assertEquals(0x2D, states.size)
        states.forEachIndexed { slot, state -> assertEquals(address(state) and 0xFFFF, word("MainGameLoop_gamemodes", slot * 2), "Game state $slot") }
        for ((symbol, ram) in mapOf("NMI_Request" to 0x05B4, "NMI_8bitFrameCounter" to 0x05B5, "NMI_FrameCounter" to 0x05B6,
            "NMI_Counter" to 0x05B8, "NMI_LagCounter" to 0x05BA, "NMI_MaximumLag" to 0x05BB, "DP_IRQAutoJoy" to 0x84, "DP_IRQCmd" to 0xAB))
            assertEquals(ram, address(symbol), "Request/byte-counter adjacency makes register widths significant: $symbol")

        fun statements(symbol: String, local: String? = null): List<String> {
            val root = index.labels.single { it.name == names.active(symbol) && it.localScope == null }
            val source = assertNotNull(index.file(root.fileId))
            val start = local?.let { name -> index.labels.single { it.fileId == root.fileId && it.localScope == root.name && it.name == name }.lineIndex }
                ?: root.lineIndex
            val end = (start + 1 until source.lines.size).firstOrNull { line ->
                source.scopePathByLine[line].firstOrNull() != root.name ||
                    (local != null && index.labels.any { it.fileId == root.fileId && it.lineIndex == line })
            } ?: source.lines.size
            return source.lines.subList(start + 1, end).map { asmStatementSignature(names.canonicalCode(asmExecutableCode(it))) }.filter(String::isNotBlank)
        }
        fun signature(vararg code: String) = code.map(::asmStatementSignature)
        val wait = statements("WaitForNMI")
        assertEquals(signature("PHP", "PHB", "PHK", "PLB", "SEP #\$30", "LDA.B #\$01", "STA.W NMI_Request", ".wait:",
            "LDA.W NMI_Request", "BNE .wait", "PLB", "PLP", "RTL"), wait)
        assertEquals(signature("REP #\$30", "INC.W NMI_Counter", "PLY", "PLX", "PLA", "PLD", "PLB", "RTI"), statements("NMI", ".return"))
        assertEquals(signature("LDX.W NMI_LagCounter", "INX", "STX.W NMI_LagCounter", "LDX.W NMI_LagCounter", "CPX.W NMI_MaximumLag",
            "BCC .return", "STX.W NMI_MaximumLag", "BRA .return"), statements("NMI", ".lag"))
        val nmi = statements("NMI")
        val requestTest = nmi.indexOf(asmStatementSignature("LDX.W NMI_Request"))
        assertEquals(signature("SEP #\$10", "LDX.W \$4210", "LDX.W NMI_Request", "BEQ .lag", "JSR.W UpdateOAM_CGRAM"),
            nmi.subList(requestTest - 2, requestTest + 3))
        assertEquals(signature("SEP #\$10", "REP #\$20", "LDX.B DP_HDMAEnable", "STX.W \$420C", "JSL.L ReadControllerInput", "LDX.B #\$00",
            "STX.W NMI_Request", "STX.W NMI_LagCounter", "LDX.W NMI_8bitFrameCounter", "INX", "STX.W NMI_8bitFrameCounter", "INC.W NMI_FrameCounter"),
            statements("NMI", ".mode7Disabled").drop(2))
        val irq = statements("IRQ", ".bank80")
        assertEquals(signature("LDA.W \$4211", "LDX.B DP_IRQCmd", "JSR.W (InterruptCommandPointers,X)", "STA.B DP_IRQCmd",
            "STY.W \$4209", "STX.W \$4207"), irq.drop(6).take(6))
        assertTrue(irq.none { it == asmStatementSignature("TCD") || it == asmStatementSignature("PHD") })
        val loop = statements("MainGameLoop").takeWhile { it != asmStatementSignature(".gamemodes:") }
        assertEquals(signature("JSL.L HDMAObjectHandler_HandleMusicQueue", "JSL.L GenerateRandomNumber", "JSL.L ClearHighOAM", "JSR.W (.gamemodes,X)",
            "JSL.L HandleSounds", "JSL.L Finalise_OAM", "JSL.L ShowSpareCPUDebug_UpdatePrevCtrl1Input", "JSL.L WaitForNMI"),
            loop.filter { it.startsWith("JSL") || it.startsWith("JSR") })
        val gameplay = statements("GameState_8_MainGameplay")
        // Anonymous labels share instruction lines in this scope; compare call operands, not their textual prefix.
        fun calls(rows: List<String>) = rows.mapNotNull { row -> Regex("(?:JSL\\.L|JSR\\.W)(.+)").find(row)?.groupValues?.get(1) }
        assertEquals(listOf("Determine_Which_Enemies_to_Process", "DebugHandler", "PaletteFXObject_Handler", "SamusCurrentStateHandler",
            "Samus_Projectiles_Interaction_Handling", "Main_Enemy_Routine", "SamusNewStateHandler", "Enemy_Projectile_Handler", "PLM_Handler",
            "AnimatedTilesObject_Handler", "EnemyProjectile_Samus_Collision_Handling", "Projectile_vs_Projectile_Collision_Handling",
            "Process_Enemy_PowerBomb_Interaction", "Main_Scrolling_Routine", "Debug_Layer1Position_Saving_Loading",
            "Draw_Samus_Projectiles_Enemies_and_Enemy_Projectiles", "Handle_Queuing_Enemy_BG2_Tilemap_VRAM_Transfer", "HandleHUDTilemap_PausedAndRunning",
            "Calc_Layer2Position_BGScrolls_UpdateBGGraphics_WhenScrolling", "Execute_Room_Main_ASM", "HandleSamusRunningOutOfEnergy_and_IncrementGameTime",
            "Handle_Room_Shaking", "DecrementSamusHurtTimers_ClearActiveEnemyIndicesLists"), calls(gameplay))
        assertTrue(gameplay.indexOf(asmStatementSignature("BNE .skipProcessing")) < gameplay.indexOf(asmStatementSignature(".skipProcessing:")))
        assertEquals(signature("JSL.L HandleHUDTilemap_PausedAndRunning", "JSL.L Calc_Layer2Position_BGScrolls_UpdateBGGraphics_WhenScrolling",
            "JSL.L Execute_Room_Main_ASM", "JSR.W HandleSamusRunningOutOfEnergy_and_IncrementGameTime", "JSL.L Handle_Room_Shaking",
            "JSL.L DecrementSamusHurtTimers_ClearActiveEnemyIndicesLists", "PLP", "RTS"), statements("GameState_8_MainGameplay", ".skipProcessing"))
    }

    private fun verifyWaverContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
        fun address(name: String) = assertNotNull(symbols[name], name)
        fun word(name: String, offset: Int = 0): Int {
            val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
            return (rom[pc].toInt() and 255) or ((rom[pc + 1].toInt() and 255) shl 8)
        }
        assertEquals(address("InitAI_Waver") and 0xFFFF, word("EnemyHeaders_Waver_initAI"))
        assertEquals(address("MainAI_Waver") and 0xFFFF, word("EnemyHeaders_Waver_mainAI"))
        assertEquals(0x0180, word("InitAI_Waver", 4), "The NTSC magnitude is signed 8.8 before splitting")
        assertEquals(0x0FA8, address("Waver.XSubVelocity"))
        assertEquals(address("Enemy.var0"), address("Waver.XSubVelocity"))
        assertEquals(address("Waver.XSubVelocity") + 2, address("Waver.XVelocity"), "var0+1 reads overlapping fraction/integer bytes")
        assertEquals(0x0FAC, address("Waver.instListIndex"))
        assertEquals(0x0FAE, address("Waver.angle"))
        assertEquals(0x0FB0, address("Waver.spinFinishedFlag"))
        assertEquals(0x0FB2, address("Waver.newInstListIndex"))
        val lists = listOf("Steady_FacingLeft", "Steady_FacingRight", "Spinning_FacingLeft", "Spinning_FacingRight")
        lists.forEachIndexed { slot, suffix ->
            assertEquals(address("InstList_Waver_$suffix") and 0xFFFF, word("InstListPointers_Waver", slot * 2))
        }
        for ((side, sprite) in listOf("Left" to 2, "Right" to 7)) {
            val steady = "InstList_Waver_Steady_Facing$side"
            assertEquals(1, word(steady))
            assertEquals(address("Spritemap_Waver_$sprite") and 0xFFFF, word(steady, 2))
            assertEquals(address("Instruction_Common_Sleep") and 0xFFFF, word(steady, 4))
        }
        for ((side, sprites) in listOf("Left" to listOf(3, 4, 0, 1), "Right" to listOf(8, 9, 5, 6))) {
            val spin = "InstList_Waver_Spinning_Facing$side"
            sprites.forEachIndexed { frame, sprite ->
                assertEquals(8, word(spin, frame * 4), "$side spin frame duration")
                assertEquals(address("Spritemap_Waver_$sprite") and 0xFFFF, word(spin, frame * 4 + 2))
            }
            assertEquals(address("Instruction_Waver_SetSpinFinishedFlag") and 0xFFFF, word(spin, 16))
            assertEquals(address("Instruction_Common_Sleep") and 0xFFFF, word(spin, 18))
        }
        fun statements(symbol: String, local: String? = null): List<String> {
            val root = index.labels.single { it.name == symbol && it.localScope == null }
            val label = local?.let { name -> index.labels.single { it.fileId == root.fileId && it.localScope == symbol && it.name == name } } ?: root
            val source = assertNotNull(index.file(root.fileId))
            val end = index.labels.filter { it.fileId == root.fileId && it.lineIndex > label.lineIndex }.minOfOrNull { it.lineIndex } ?: source.lines.size
            return source.lines.subList(label.lineIndex + 1, end)
                .map { asmStatementSignature(names.canonicalCode(asmExecutableCode(it))) }.filter(String::isNotEmpty)
        }
        fun signature(vararg code: String) = code.map(::asmStatementSignature)
        assertEquals(signature("LDX.W EnemyIndex", "LDA.W #\$0001", "STA.W Waver.spinFinishedFlag,X", "RTL"),
            statements("Instruction_Waver_SetSpinFinishedFlag"))
        assertEquals(signature("STZ.W Waver.newInstListIndex,X", "STZ.W Waver.instListIndex,X", "STZ.W Waver.spinFinishedFlag,X",
            "LDA.W #InstList_Waver_Steady_FacingLeft", "STA.W Enemy.instList,X", "LDA.W Enemy.init0,X", "AND.W #\$0001",
            "STA.W Waver.newInstListIndex,X", "JSR.W SetWaverInstList", "RTL"), statements("InitAI_Waver", ".facingLeft"))
        val horizontal = statements("MainAI_Waver")
        assertEquals(signature("LDX.W EnemyIndex", "LDA.W Waver.XSubVelocity,X", "STA.B DP_Temp12", "LDA.W Waver.XVelocity,X",
            "STA.B DP_Temp14", "JSL.L MoveEnemyRightBy_14_12_IgnoreSlopes", "BCC .noWallCollision"), horizontal.take(7))
        assertEquals(signature("LDA.W Enemy.var0+1,X", "STA.B DP_Temp12", "EOR.W #\$FFFF", "INC"), horizontal.drop(7).take(4))
        assertEquals(signature("LDA.W Waver.newInstListIndex,X", "EOR.W #\$0001", "AND.W #\$0001", "STA.W Waver.newInstListIndex,X",
            "JSR.W SetWaverInstList", "JMP.W .merge"), horizontal.takeLast(6))
        assertEquals(signature("LDA.W #\$0004", "STA.W Temp_Radius", "LDA.W Waver.angle,X", "AND.W #\$00FF",
            "JSL.L EightBitNegativeSineMultiplication_A0B0C6", "STA.B DP_Temp14", "STZ.B DP_Temp12", "JSL.L MoveEnemyDownBy_14_12",
            "BCC .noBlockCollision", "LDX.W EnemyIndex", "LDA.W Waver.angle,X", "CLC", "ADC.W #\$0080", "AND.W #\$00FF",
            "STA.W Waver.angle,X", "BRA .merge"), statements("MainAI_Waver", ".noWallCollision"))
        assertEquals(signature("LDX.W EnemyIndex", "LDA.W Waver.angle,X", "CLC", "ADC.W #\$0002", "STA.W Waver.angle,X"),
            statements("MainAI_Waver", ".noBlockCollision"))
        assertEquals(signature("LDX.W EnemyIndex", "LDA.W Waver.angle,X", "AND.W #\$007F", "CMP.W #\$0038", "BNE .notSpinning",
            "LDA.W Waver.newInstListIndex,X", "ORA.W #\$0002", "STA.W Waver.newInstListIndex,X", "JSR.W SetWaverInstList"),
            statements("MainAI_Waver", ".merge"))
        assertEquals(signature("LDA.W Waver.spinFinishedFlag,X", "BEQ .return", "STZ.W Waver.spinFinishedFlag,X", "LDA.W Waver.newInstListIndex,X",
            "AND.W #\$0001", "STA.W Waver.newInstListIndex,X", "JSR.W SetWaverInstList"), statements("MainAI_Waver", ".notSpinning"))
        assertEquals(signature("LDX.W EnemyIndex", "LDA.W Waver.newInstListIndex,X", "CMP.W Waver.instListIndex,X", "BEQ .return",
            "STA.W Waver.instListIndex,X", "ASL", "TAY", "LDA.W InstListPointers_Waver,Y", "STA.W Enemy.instList,X",
            "LDA.W #\$0001", "STA.W Enemy.instTimer,X", "STZ.W Enemy.loopCounter,X"), statements("SetWaverInstList"))
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
