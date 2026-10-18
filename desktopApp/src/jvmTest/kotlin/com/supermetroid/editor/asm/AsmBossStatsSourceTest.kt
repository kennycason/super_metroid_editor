package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.*
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AsmBossStatsSourceTest {
    @TempDir lateinit var directory: File

    // Minimal headers for every boss-stat control, including Kraid's grouped sub-species.
    private val headers = """
        EnemyHeaders_Kraid:                                                      ;A0E2BF;
            %EnemyHeader(\
            %health(${'$'}03E8),
            %damage(${'$'}0014),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_KraidArm:                                                   ;A0E2FF;
            %EnemyHeader(\
            %health(${'$'}03E8),
            %damage(${'$'}0014),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_KraidLintTop:                                               ;A0E33F;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}000A),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_KraidLintMiddle:                                            ;A0E37F;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}000A),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_KraidLintBottom:                                            ;A0E3BF;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}000A),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_KraidFoot:                                                  ;A0E3FF;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}0014),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_PhantoonBody:                                               ;A0E4BF;
            %EnemyHeader(\
            %health(${'$'}09C4),
            %damage(${'$'}0028),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_PhantoonEye:                                                ;A0E4FF;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}0028),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_PhantoonTentacles:                                          ;A0E53F;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}0028),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_PhantoonMouth:                                              ;A0E57F;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}0028),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_Ridley:                                                     ;A0E17F;
            %EnemyHeader(\
            %health(${'$'}4650),
            %damage(${'$'}00A0),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_DraygonBody:                                                ;A0DE3F;
            %EnemyHeader(\
            %health(${'$'}1770),
            %damage(${'$'}00A0),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_DraygonEye:                                                 ;A0DE7F;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}00A0),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_DraygonTail:                                                ;A0DEBF;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}00A0),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_DraygonArms:                                                ;A0DEFF;
            %EnemyHeader(\
            %health(${'$'}0001),
            %damage(${'$'}00A0),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_MotherBrainHead:                                            ;A0EC3F;
            %EnemyHeader(\
            %health(${'$'}4650),
            %damage(${'$'}0078),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_MotherBrainBody:                                            ;A0EC7F;
            %EnemyHeader(\
            %health(${'$'}4650),
            %damage(${'$'}0078),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_SporeSpawn:                                                 ;A0DF3F;
            %EnemyHeader(\
            %health(${'$'}03C0),
            %damage(${'$'}000C),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_Crocomire:                                                  ;A0DDBF;
            %EnemyHeader(\
            %health(${'$'}7FFF),
            %damage(${'$'}0028),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_Botwoon:                                                    ;A0F293;
            %EnemyHeader(\
            %health(${'$'}0BB8),
            %damage(${'$'}0078),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_GoldenTorizo:                                               ;A0EF7F;
            %EnemyHeader(\
            %health(${'$'}34BC),
            %damage(${'$'}00A0),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))

        EnemyHeaders_BombTorizo:                                                 ;A0EEFF;
            %EnemyHeader(\
            %health(${'$'}0320),
            %damage(${'$'}0008),
            %drops(EnemyDropChances_Kraid),
            %vulnerabilities(EnemyVulnerabilities_Kraid),
            %name(0))
    """.trimIndent() + "\n"

    private data class Fixture(
        val editor: EditorState, val workspace: AsmWorkspaceState,
        val bridge: AsmBossStatsBridge,
    ) {
        val project get() = editor.project
        fun text() = workspace.projectSourceText(ENEMY_HEADER_SOURCE_FILE)
    }

    private suspend fun fixture(headerText: String = headers): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_A0.asm\nincsrc bank_B4.asm\nincsrc bank_8F.asm\n")
        File(src, ENEMY_HEADER_SOURCE_FILE).writeText(headerText)
        File(src, ENEMY_DROP_SOURCE_FILE).writeText("EnemyDropChances_Kraid:\n    db 0,0,0,0,0,0\nEnemyVulnerabilities_Kraid:\n    db 0,0,0,0,0,0,0,0,0,0,0,0\n    db 0,0,0,0,0,0,0,0,0,0\n")
        File(src, PLM_SOURCE_FILE).writeText("; Unrelated\n")
        val reference = AsmReferenceWorkspace(
            src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()),
        )
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList()).also { it.initialize(file.path, reference) }
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        val parser = com.supermetroid.editor.rom.RomParser(ByteArray(0x300000))
        val project = SmEditProject("test.sfc").apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), parser, emptyMap()), workspace)
        return Fixture(editor, workspace, assertNotNull(editor.bossStatsBridge))
    }

    @Test fun `reads kraid HP from source`() = runBlocking {
        val f = fixture()
        val view = f.bridge.view("kraid_hp")
        assertNull(view.problem)
        assertEquals(1000, view.value) // $03E8 = 1000
    }

    @Test fun `boss toggle preserves independent sub species words and restores them with source undo`() = runBlocking {
        val f = fixture()
        val patch = f.editor.findOrCreateConfigPatch("boss_stats")
        f.bridge.edit("kraid_hp", 2400)
        val field = f.bridge.catalog.field("kraid_hp", f.text())
        val arm = field.words.single { it.speciesId == 0xE2FF }
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, f.text().replaceRange(arm.span, "1234")))
        assertTrue(patch.enabled); assertNull(f.bridge.catalog.field("kraid_hp", f.text()).sharedValue)
        val custom = f.text()
        f.editor.requestTogglePatch(patch.id); assertSame(patch, f.editor.pendingPatchDisable); f.editor.confirmPatchDisable()
        assertFalse(patch.enabled, f.editor.statusMessage)
        assertEquals(listOf(1000, 1000), f.bridge.catalog.field("kraid_hp", f.text()).values)
        assertEquals(listOf(2400, 1234), f.editor.project.asmWorkspace.disabledPatchValues["boss_stats"]?.get("kraid_hp"))
        f.editor.requestTogglePatch(patch.id)
        assertTrue(patch.enabled); assertEquals(listOf(2400, 1234), f.bridge.catalog.field("kraid_hp", f.text()).values)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertTrue(patch.enabled); assertEquals(custom, f.text())
    }

    @Test fun `reset original restores each native sub species even when their original values differ`() = runBlocking {
        val originalArm = AsmBossStatsSource(headers).original("kraid_hp").words.last()
        val f = fixture(headers.replaceRange(originalArm.span, "900"))
        val patch = f.editor.findOrCreateConfigPatch("boss_stats")
        val field = f.bridge.catalog.field("kraid_hp", f.text())
        var custom = f.text()
        for (word in field.words.sortedByDescending { it.span.first }) custom = custom.replaceRange(word.span, "2400")
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, custom))
        f.editor.togglePatch(patch.id); assertFalse(patch.enabled, f.editor.statusMessage)
        assertEquals(listOf(1000, 900), f.bridge.catalog.field("kraid_hp", f.text()).values)
        f.bridge.resetToOriginal()
        assertTrue(patch.enabled); assertTrue(f.editor.project.asmWorkspace.disabledPatchValues.isEmpty())
        assertEquals(listOf(1000, 900), f.bridge.catalog.field("kraid_hp", f.text()).values)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(listOf(2400, 2400), f.editor.project.asmWorkspace.disabledPatchValues["boss_stats"]?.get("kraid_hp"))
    }

    @Test fun `reads phantoon HP from source`() = runBlocking {
        val f = fixture()
        val view = f.bridge.view("phantoon_hp")
        assertNull(view.problem)
        assertEquals(2500, view.value) // $09C4 = 2500
    }

    @Test fun `reads ridley HP from source`() = runBlocking {
        val f = fixture()
        val view = f.bridge.view("ridley_hp")
        assertNull(view.problem)
        assertEquals(18000, view.value) // $4650 = 18000
    }

    @Test fun `edits kraid HP in source and updates additional species`() = runBlocking {
        val f = fixture()
        f.bridge.edit("kraid_hp", 2000)
        val text = f.text()
        // Primary species (E2BF) should be updated
        val field = f.bridge.catalog.field("kraid_hp", text)
        assertEquals(2000, field.value)
        // Additional species (E2FF / KraidArm) should also be updated
        assertTrue(text.contains("EnemyHeaders_KraidArm:"))
        assertEquals(mapOf(0xE2BF to 2000, 0xE2FF to 2000), field.words.associate { it.speciesId to it.value })
    }

    @Test fun `edits belly spike damage updates all lint species`() = runBlocking {
        val f = fixture()
        f.bridge.edit("kraid_belly_spike", 50)
        val text = f.text()
        // $0032 = 50; lint top, middle, bottom should all be updated
        assertEquals(mapOf(0xE33F to 50, 0xE37F to 50, 0xE3BF to 50),
            f.bridge.catalog.field("kraid_belly_spike", text).words.associate { it.speciesId to it.value })
    }

    @Test fun `edit retires patch config key`() = runBlocking {
        val f = fixture()
        val patch = com.supermetroid.editor.data.SmPatch("bs", "Boss Stats", enabled = true, configType = "boss_stats",
            configData = mutableMapOf("kraid_hp" to 5000))
        f.project.patches.add(patch)
        f.bridge.edit("kraid_hp", 3000)
        // The patch config key should be retired (removed) in favor of source ownership
        assertNull(patch.configData?.get("kraid_hp"))
        assertTrue("kraid_hp" in f.project.asmWorkspace.bossStatsSourceKeys)
    }

    @Test fun `detects conflict between source and patch override`() = runBlocking {
        val f = fixture()
        // First adopt into source
        f.bridge.edit("kraid_hp", 2000)
        assertTrue("kraid_hp" in f.project.asmWorkspace.bossStatsSourceKeys)
        // Now set a competing patch value
        val patch = com.supermetroid.editor.data.SmPatch("bs", "Boss Stats", enabled = true, configType = "boss_stats",
            configData = mutableMapOf("kraid_hp" to 5000))
        f.project.patches.add(patch)
        val view = f.bridge.view("kraid_hp")
        assertNotNull(view.problem)
        assertTrue(view.problem.orEmpty().contains("patch override"))
    }

    @Test fun `navigation keys at line returns boss fields for that header`() = runBlocking {
        val f = fixture()
        val text = f.text()
        // Find the line of EnemyHeaders_Kraid
        val line = text.lines().indexOfFirst { it.contains("EnemyHeaders_Kraid:") && it.contains("A0E2BF") }
        assertTrue(line >= 0)
        val keys = f.bridge.catalog.navigationKeysAt(text, line)
        assertTrue("kraid_hp" in keys)
        assertTrue("kraid_contact" in keys)
    }

    @Test fun `source field line returns correct line number`() = runBlocking {
        val f = fixture()
        val text = f.text()
        val line = f.bridge.catalog.line("kraid_hp", text)
        // The HP field should be on the line containing %health($03E8)
        val lineText = text.lines()[line]
        assertTrue(lineText.contains("%health("))
    }

    @Test fun `all boss fields are available as keys`() = runBlocking {
        val f = fixture()
        for (field in com.supermetroid.editor.ui.ALL_BOSS_FIELDS) {
            assertTrue(field.key in f.bridge.catalog.keys, "Missing key: ${field.key}")
        }
    }

    @Test fun `reset to original restores vanilla values`() = runBlocking {
        val f = fixture()
        f.bridge.edit("kraid_hp", 9999)
        assertEquals(9999, f.bridge.view("kraid_hp").value)
        f.bridge.resetToOriginal()
        assertEquals(1000, f.bridge.view("kraid_hp").value)
    }

    @Test fun `undo restores previous source text and ownership`() = runBlocking {
        val f = fixture()
        val patch = com.supermetroid.editor.data.SmPatch("bs", "Boss Stats", enabled = true, configType = "boss_stats",
            configData = mutableMapOf("kraid_hp" to 5000))
        f.project.patches.add(patch)
        val before = f.text()
        f.bridge.edit("kraid_hp", 3000)
        assertNotEquals(before, f.text())
        assertTrue("kraid_hp" in f.project.asmWorkspace.bossStatsSourceKeys)
        assertNull(patch.configData?.get("kraid_hp"))
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.text())
        assertTrue(f.project.asmWorkspace.bossStatsSourceKeys.isEmpty())
        assertEquals(5000, patch.configData?.get("kraid_hp"))
    }

    @Test fun `intercept adopts ownership on first edit`() = runBlocking {
        val f = fixture()
        val before = f.text()
        val after = before.replaceFirst("%health(\$03E8)", "%health(\$07D0)")
        val result = f.bridge.intercept(ENEMY_HEADER_SOURCE_FILE, before, after)
        // First-time edit triggers ownership adoption
        assertEquals(true, result)
        assertTrue("kraid_hp" in f.project.asmWorkspace.bossStatsSourceKeys)
    }

    @Test fun `intercept returns true when patch override exists`() = runBlocking {
        val f = fixture()
        val patch = com.supermetroid.editor.data.SmPatch("bs", "Boss Stats", enabled = true, configType = "boss_stats",
            configData = mutableMapOf("kraid_hp" to 5000))
        f.project.patches.add(patch)
        val before = f.text()
        val after = before.replaceFirst("%health(\$03E8)", "%health(\$07D0)")
        val result = f.bridge.intercept(ENEMY_HEADER_SOURCE_FILE, before, after)
        assertEquals(true, result) // Should intercept because there's a patch to retire
    }

    @Test fun `editor links from BossStatsBridge are returned for boss header lines`() = runBlocking {
        val f = fixture()
        val text = f.text()
        val line = text.lines().indexOfFirst { it.contains("EnemyHeaders_Kraid:") && it.contains("A0E2BF") }
        val links = f.bridge.editorLinks(ENEMY_HEADER_SOURCE_FILE, line)
        assertTrue(links.isNotEmpty())
        assertTrue(links.any { it.title.contains("Kraid") })
        assertTrue(links.all { it.target is AsmEditorTarget.BossStats })
    }

    @Test fun `editor links are empty for non bank_A0 files`() = runBlocking {
        val f = fixture()
        val links = f.bridge.editorLinks("bank_93.asm", 0)
        assertTrue(links.isEmpty())
    }

    @Test fun `inspection unchanged values and unchanged saves never adopt or dirty source`() = runBlocking {
        val f = fixture(headers.replace("%health(\$03E8)", "%health(1000)"))
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val text = f.text(); val revision = f.workspace.sourceBufferRevision
        f.bridge.view("kraid_hp"); f.bridge.line(null)
        assertTrue(f.editor.openBossStatsSource("kraid_hp"))
        f.bridge.edit("kraid_hp", 1000)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertEquals(text, f.text()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertFalse(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE))
    }

    @Test fun `secondary ASM edits adopt and retire the grouped override without overwriting independent values`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "boss_stats", configData = mutableMapOf("kraid_hp" to 5000))
        f.project.patches.add(patch)
        val before = f.text()
        val arm = f.bridge.catalog.field("kraid_hp", before).words.last()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, before.replaceRange(arm.span, "1234")))
        assertEquals(listOf(1000, 1234), f.bridge.catalog.field("kraid_hp", f.text()).values)
        assertTrue("kraid_hp" in f.project.asmWorkspace.bossStatsSourceKeys)
        assertNull(patch.configData)
        assertContains(assertNotNull(f.bridge.view("kraid_hp").problem), "managed in ASM")
        assertFails { f.bridge.edit("kraid_hp", 2000) }
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.text()); assertEquals(5000, patch.configData?.get("kraid_hp"))
        assertTrue(f.project.asmWorkspace.bossStatsSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(listOf(1000, 1234), f.bridge.catalog.field("kraid_hp", f.text()).values)
        val keyLine = f.text().take(f.bridge.catalog.field("kraid_hp", f.text()).words.last().span.first).count { it == '\n' }
        assertEquals(keyLine, f.bridge.line("kraid_hp"))
        assertEquals(listOf("kraid_hp"), f.bridge.catalog.navigationKeysAt(f.text(), keyLine))
    }

    @Test fun `invalid secondary and missing middle records fail atomically and retain repair navigation`() = runBlocking {
        val f = fixture()
        f.bridge.edit("kraid_belly_spike", 50)
        val good = f.text()
        val middle = f.bridge.catalog.field("kraid_belly_spike", good).words[1]
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, good.replaceRange(middle.span, "50+1")))
        assertEquals(50, f.bridge.view("kraid_belly_spike").value)
        assertNotNull(f.bridge.view("kraid_belly_spike").problem)
        val broken = f.text(); val owned = f.project.asmWorkspace.bossStatsSourceKeys.toSet()
        assertFails { f.bridge.editValues(mapOf("ridley_hp" to 5, "kraid_belly_spike" to 51)) }
        assertEquals(broken, f.text()); assertEquals(owned, f.project.asmWorkspace.bossStatsSourceKeys)
        assertEquals(broken.take(middle.span.first).count { it == '\n' }, f.bridge.line("kraid_belly_spike"))
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        val missing = good.replace("EnemyHeaders_KraidLintMiddle:", "MissingMiddle:")
        assertFails { f.bridge.catalog.field("kraid_belly_spike", missing) }
        assertTrue(f.bridge.catalog.changed("kraid_belly_spike", missing))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
    }

    @Test fun `mixed enemy and boss text edits adopt together and undo validates every participant before mutation`() = runBlocking {
        val zoomer = "\nEnemyHeaders_Zoomer: ;A0DCFF;\n    %health(15),\n    %damage(5),\n    %drops(EnemyDropChances_Kraid),\n    %vulnerabilities(EnemyVulnerabilities_Kraid),\n"
        val f = fixture(headers + zoomer)
        val bossPatch = SmPatch("boss", "Boss", enabled = true, configType = "boss_stats", configData = mutableMapOf("ridley_hp" to 20000))
        val enemyPatch = SmPatch("enemy", "Enemy", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 30))
        f.project.patches.addAll(listOf(bossPatch, enemyPatch))
        val before = f.text()
        val after = before.replace("%health(15)", "%health(16)").replaceFirst("%health(\$4650)", "%health(18001)")
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, after), f.workspace.error)
        assertNull(enemyPatch.configData); assertNull(bossPatch.configData)
        assertEquals(setOf("ridley_hp"), f.project.asmWorkspace.bossStatsSourceKeys)
        assertEquals(setOf("zoomer_hp"), f.project.asmWorkspace.enemyTuningSourceKeys)
        val newer = SmPatch("newer", "Newer", configType = "boss_stats", configData = mutableMapOf("ridley_hp" to 99))
        f.project.patches.add(newer)
        assertFalse(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE))
        assertEquals(after, f.text()); assertNull(enemyPatch.configData); assertNull(bossPatch.configData)
        assertEquals(setOf("zoomer_hp"), f.project.asmWorkspace.enemyTuningSourceKeys)
        f.project.patches.remove(newer)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.text()); assertEquals(30, enemyPatch.configData?.get("zoomer_hp")); assertEquals(20000, bossPatch.configData?.get("ridley_hp"))
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty()); assertTrue(f.project.asmWorkspace.bossStatsSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(after, f.text()); assertNull(enemyPatch.configData); assertNull(bossPatch.configData)
    }

    @Test fun `compiler validates all subparts full word ownership native identity and header normalization`() {
        val catalog = AsmBossStatsSource(headers)
        val project = SmEditProject("test.sfc")
        val rom = ByteArray(0x300000)
        val parser = RomParser(rom)
        fun word(bytes: ByteArray, pc: Int, value: Int) { bytes[pc] = value.toByte(); bytes[pc + 1] = (value ushr 8).toByte() }
        for (key in catalog.keys) for (field in catalog.original(key).words) word(rom, parser.snesToPc(0xA00000 or field.speciesId) + field.offset, field.value)
        val labels = catalog.headers.map { (species, label) -> label to (0xA00000 or species) }.toMap()
        fun compiled(bytes: ByteArray = rom, symbols: Map<String, Int> = labels) = AsmCompiledBuildBase(
            bytes.copyOf(), rom.copyOf(), "test", emptyList(), assemblerVersion = "test", compilerOutput = emptyList(),
            compilerSymbols = ("[labels]\n" + symbols.entries.joinToString("\n") { (name, address) ->
                "${(address ushr 16).toString(16)}:${(address and 65535).toString(16)} $name"
            } + "\n").toByteArray())
        project.asmWorkspace.bossStatsSourceKeys.add("kraid_hp")
        val checked = protectBossStatsSource(catalog, headers, project, compiled())
        assertEquals(setOf(parser.snesToPc(0xA0E2BF) + 4, parser.snesToPc(0xA0E2FF) + 4), checked.sourceOwnedRanges.map { it.pcOffset }.toSet())
        assertTrue(checked.sourceOwnedRanges.all { it.length == 2 })
        assertEquals(checked.sourceOwnedRanges, protectBossStatsSource(catalog, headers, project, compiled(ByteArray(512) { 0x5A } + rom)).sourceOwnedRanges)
        assertFails { protectBossStatsSource(catalog, headers, project, compiled(symbols = labels - "EnemyHeaders_KraidArm")) }
        assertFails { protectBossStatsSource(catalog, headers, project, compiled(symbols = labels + ("EnemyHeaders_KraidArm" to 0xA0F000))) }
        assertFails { protectBossStatsSource(catalog, headers, project, compiled(rom.copyOf().also { word(it, parser.snesToPc(0xA0E2FF) + 4, 1) })) }
        project.patches.add(SmPatch("pending", "Pending", enabled = true, configType = "boss_stats", configData = mutableMapOf("kraid_hp" to 2000)))
        assertFails { protectBossStatsSource(catalog, headers, project, compiled()) }
        project.asmWorkspace.bossStatsSourceKeys.clear()
        assertTrue(protectBossStatsSource(catalog, headers, project, compiled()).sourceOwnedRanges.isEmpty())
        assertFails { protectBossStatsSource(catalog, headers, project, compiled(symbols = labels - "EnemyHeaders_KraidArm")) }
        assertFails { protectBossStatsSource(null, headers, project, compiled()) }
        project.patches.single().configData = mutableMapOf("kraid_hp" to 65536)
        assertFails { protectBossStatsSource(catalog, headers, project, compiled()) }
        project.patches.single().configData = mutableMapOf("unknown_boss_field" to 10)
        assertFails { protectBossStatsSource(catalog, headers, project, compiled()) }
        project.patches.clear()
        val arm = catalog.original("kraid_hp").words.last()
        val changed = headers.replaceRange(arm.span, "1001")
        val external = protectBossStatsSource(catalog, changed, project, compiled(rom.copyOf().also { word(it, parser.snesToPc(0xA0E2FF) + 4, 1001) }))
        assertEquals(checked.sourceOwnedRanges, external.sourceOwnedRanges, "Cold external edits protect the entire grouped control")
        assertFails { protectBossStatsSource(catalog, headers.replaceRange(arm.span, "1000+1"), project, compiled()) }
    }

    @Test fun `boss canvas follows later ASM edits disables only unrepresentable fields and keeps repair links`() = runBlocking {
        val f = fixture()
        val patch = f.editor.findOrCreateConfigPatch("boss_stats")
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(760, 1100)
        var navigations = 0
        try {
            selectBossStats(null)
            scene.constraints = Constraints.fixed(760, 1100)
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                    MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) {
                        androidx.compose.material3.Surface { BossStatsEditor(patch, f.editor, null, onNavigateToAsm = { navigations++ }) }
                    }
                }
            }
            var time = System.nanoTime()
            suspend fun render() { repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); yield() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
            }
            fun tag(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            fun input(key: String) = tag("boss-stat-$key")
            fun displayed(key: String) = input(key).config[SemanticsProperties.EditableText].text
            render()
            val original = f.text(); val revision = f.workspace.sourceBufferRevision
            assertTrue(tag("boss-asm-kraid_hp").config[SemanticsActions.OnClick].action!!.invoke()); render()
            assertEquals(1, navigations); assertEquals(original, f.text()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertTrue(input("kraid_hp").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("2000"))); render()
            assertEquals(listOf(2000, 2000), f.bridge.catalog.field("kraid_hp", f.text()).values)
            val source = f.text()
            assertTrue(input("kraid_hp").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("65536"))); render()
            assertEquals("2000", displayed("kraid_hp")); assertEquals(source, f.text(), "Out-of-range input must not clamp or wrap silently")
            val edited = source.replace("%health(\$07D0)", "%health(2001)")
            assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, edited)); render()
            assertEquals("2001", displayed("kraid_hp"), "A later ASM edit must refresh even after ownership was already adopted")
            val arm = f.bridge.catalog.field("kraid_hp", f.text()).words.last()
            assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, f.text().replaceRange(arm.span, "2001+1"))); render()
            assertTrue(input("kraid_hp").config.contains(SemanticsProperties.Disabled))
            assertFalse(input("kraid_contact").config.contains(SemanticsProperties.Disabled))
            assertEquals("2001", displayed("kraid_hp"))
            assertTrue(tag("boss-asm-kraid_hp").config[SemanticsActions.OnClick].action!!.invoke()); render()
            assertEquals(f.bridge.line("kraid_hp"), f.workspace.selectedLineIndex)
            assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error); render()
            assertFalse(input("kraid_hp").config.contains(SemanticsProperties.Disabled))
            val asymmetric = f.bridge.catalog.field("kraid_hp", f.text()).words.last()
            assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, f.text().replaceRange(asymmetric.span, "2002"))); render()
            assertTrue(input("kraid_hp").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close(); surface.close(); selectBossStats(null) }
    }
}
