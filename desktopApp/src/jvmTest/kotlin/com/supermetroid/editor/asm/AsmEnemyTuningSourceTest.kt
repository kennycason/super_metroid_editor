package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmEnemyTuningSourceTest {
    @TempDir lateinit var directory: File
    private val headers = """
        EnemyHeaders_TestZoomer: ;A0DCFF;
            %EnemyHeader(\
            %health(15), ; keep HP note
            %damage(5),
            %drops(EnemyDropChances_Test),
            %name(0))
        EnemyHeaders_TestRipper: ;A0D47F;
            %EnemyHeader(\
            %health(200),
            %damage(5),
            %drops(EnemyDropChances_Alias),
            %name(0))
    """.trimIndent() + "\n"
    private val drops = "EnemyDropChances_Test:\nEnemyDropChances_Alias:\n    db ${'$'}37, ${'$'}19, ${'$'}AA, ${'$'}00, ${'$'}05, ${'$'}00 ; keep drop note\nNext_Table:\n    db 0\nEnemyVulnerabilities_Test:\n    db 2, 2\n"

    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState, val bridge: AsmEnemyTuningBridge, val parser: RomParser) {
        val project get() = editor.project
        fun a() = workspace.projectSourceText(ENEMY_HEADER_SOURCE_FILE)
        fun b() = workspace.projectSourceText(ENEMY_DROP_SOURCE_FILE)
    }
    private suspend fun fixture(): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_A0.asm\nincsrc bank_B4.asm\nincsrc bank_8F.asm\n")
        File(src, ENEMY_HEADER_SOURCE_FILE).writeText(headers.replace("\n", "\r\n"))
        File(src, ENEMY_DROP_SOURCE_FILE).writeText(drops.replace("\n", "\r\n"))
        File(src, PLM_SOURCE_FILE).writeText("; Unrelated room source\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList()).also { it.initialize(file.path, reference) }
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        val parser = RomParser(ByteArray(0x300000))
        val project = SmEditProject("test.sfc").apply { asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), parser, emptyMap()), workspace)
        return Fixture(editor, workspace, assertNotNull(editor.enemyTuningBridge), parser)
    }

    @Test fun `native literal lenses preserve comments CRLF and aliases without accepting expressions or malformed tables`() {
        val source = AsmEnemyTuningSource(headers, drops)
        assertEquals(15, source.original("zoomer_hp").value)
        assertEquals(200, source.original("ripper_hp").value)
        assertEquals(0x37, source.original("zoomer_drop0").value)
        assertEquals(source.original("zoomer_drop0").span, source.original("ripper_drop0").span)
        assertEquals(setOf(0xDCFF, 0xD47F), source.dropConsumers("zoomer_drop0", headers, drops).toSet())
        val (a, b) = source.edit("zoomer_hp", 65535, headers.replace("\n", "\r\n"), drops)
        assertContains(a, "%health(${ '$'}FFFF), ; keep HP note\r\n")
        assertEquals(drops, b)
        assertEquals(5, source.field("zoomer_dmg", a, b).value)
        assertFails { source.field("zoomer_hp", a.replace("${'$'}FFFF", "1+2"), b) }
        assertFails { source.edit("zoomer_hp", -1, a, b) }
        assertFails { source.field("zoomer_drop0", a, b.replace("${'$'}37,", "${'$'}100,")) }
        assertFails { source.field("zoomer_drop0", a, b.replace("; keep drop note", ", 1 ; keep drop note")) }
    }

    @Test fun `category shortcuts open live declarations without changing source ownership project or build state`() = runBlocking {
        val f = fixture()
        f.workspace.showProjectWorkspace()
        val projectBefore = Json.encodeToString(SmEditProject.serializer(), f.project)
        val dirtyBefore = f.editor.dirty
        val revision = f.workspace.sourceBufferRevision
        val buildBefore = f.workspace.sourceBuildRequired
        val a = f.a(); val b = f.b()
        for (section in AsmEnemyTuningSection.entries) {
            val (file, line) = f.bridge.sectionLine(section)
            f.workspace.showProjectWorkspace()
            f.workspace.openSource(file, line)
            assertEquals(section.fileId, f.workspace.selectedFileId)
            assertEquals(line, f.workspace.selectedLineIndex)
            val text = if (file == ENEMY_HEADER_SOURCE_FILE) a else b
            assertTrue(text.lineSequence().elementAt(line).startsWith(section.labelPrefix))
        }
        assertEquals(a, f.a()); assertEquals(b, f.b())
        assertEquals(projectBefore, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(dirtyBefore, f.editor.dirty)
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(buildBefore, f.workspace.sourceBuildRequired)

        // Index coordinates can be stale after typing; shortcuts use the live CRLF buffer.
        assertTrue(f.workspace.updateSourceEditText(ENEMY_DROP_SOURCE_FILE, "; inserted\r\n" + b))
        val (file, line) = f.bridge.sectionLine(AsmEnemyTuningSection.VULNERABILITIES)
        assertTrue(f.workspace.projectSourceText(file).lineSequence().elementAt(line).startsWith("EnemyVulnerabilities_Test:"))
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            f.bridge.catalog.sectionLine(AsmEnemyTuningSection.DROPS, "; EnemyDropChances_Comment:\nUnrelated:\n db 0\n")
        }
        // Category navigation still works with non-literal/unfinished scalar drafts.
        val invalid = a.replace("%health(15)", "%health(unfinished)")
        assertEquals(0, f.bridge.catalog.sectionLine(AsmEnemyTuningSection.STATS, invalid))
    }

    @Test fun `first GUI field edit retires only its legacy config with reciprocal ownership and source undo`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40, "zoomer_dmg" to 17))
        f.project.patches.add(patch)
        val before = f.a(); val revision = f.workspace.sourceBufferRevision
        assertEquals(40, f.bridge.value(patch, "zoomer_hp").value)
        f.bridge.line("zoomer_hp")
        assertEquals(revision, f.workspace.sourceBufferRevision); assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        f.editor.setPatchConfigData("stats", "zoomer_hp", 61)
        assertEquals(61, f.bridge.value(patch, "zoomer_hp").value)
        assertEquals(mapOf("zoomer_dmg" to 17), assertNotNull(patch.configData).toMap())
        assertEquals(setOf("zoomer_hp"), f.project.asmWorkspace.enemyTuningSourceKeys)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.a()); assertEquals(40, patch.configData!!["zoomer_hp"])
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(setOf("zoomer_hp"), cold.asmWorkspace.enemyTuningSourceKeys)
        assertNull(cold.patches.single { it.id == "stats" }.configData!!["zoomer_hp"])
    }

    @Test fun `raw invalid drafts retain latest good values isolate fields and restore legacy values with source undo`() = runBlocking {
        val f = fixture()
        f.bridge.edit("zoomer_hp", 61)
        val good = f.a()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, good.replace("${'$'}003D", "unfinished")))
        assertEquals(61, f.bridge.view("zoomer_hp").value)
        assertNotNull(f.bridge.view("zoomer_hp").problem)
        assertNull(f.bridge.view("zoomer_dmg").problem)
        f.bridge.edit("zoomer_dmg", 19)
        assertEquals(19, f.bridge.view("zoomer_dmg").value)
        assertContains(f.a(), "unfinished")
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(good, f.a())
        assertNull(f.bridge.view("zoomer_hp").problem)
    }

    @Test fun `a shared drop byte updates all species aliases and retires their config while preserving other weights`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("drops", "Drops", enabled = true, configType = "enemy_drops", configData = mutableMapOf("zoomer_drop0" to 10, "ripper_drop0" to 20, "zoomer_drop1" to 8))
        f.project.patches.add(patch)
        f.editor.setPatchConfigData("drops", "zoomer_drop0", 73)
        assertEquals(73, f.bridge.view("zoomer_drop0").value)
        assertEquals(73, f.bridge.view("ripper_drop0").value)
        assertEquals(mapOf("zoomer_drop1" to 8), assertNotNull(patch.configData).toMap())
        assertEquals(0x19, f.bridge.view("zoomer_drop1").value)
        assertEquals(setOf("zoomer_drop0", "ripper_drop0"), f.project.asmWorkspace.enemyTuningSourceKeys)
        assertContains(f.b(), "; keep drop note\r\n")
        assertEquals(2, f.bridge.editorLinks(ENEMY_DROP_SOURCE_FILE, f.bridge.line("zoomer_drop0").second).size)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        assertEquals(10, patch.configData!!["zoomer_drop0"]); assertEquals(20, patch.configData!!["ripper_drop0"])
    }

    @Test fun `raw source edits migrate just touched scalar config and unchanged inspection never dirties ownership`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40))
        f.project.patches.add(patch)
        val before = f.a()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, before.replace("%health(15)", "%health(37)")))
        assertEquals(37, f.bridge.value(patch, "zoomer_hp").value)
        assertNull(patch.configData)
        assertEquals(AsmEditorTarget.EnemyTuning(0xDCFF), f.bridge.editorLinks(ENEMY_HEADER_SOURCE_FILE, f.bridge.line("zoomer_hp").second).single().target)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(40, patch.configData!!["zoomer_hp"])
        val revision = f.workspace.sourceBufferRevision
        f.bridge.edit("zoomer_dmg", 5)
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
    }

    @Test fun `build protection follows drop symbols but rejects moved headers wrong banks dual authority and typed byte drift`() {
        val source = AsmEnemyTuningSource(headers, drops)
        val project = SmEditProject("test.sfc").apply { asmWorkspace.enemyTuningSourceKeys.addAll(listOf("zoomer_hp", "zoomer_drop0")) }
        val rom = ByteArray(0x300000)
        val parser = RomParser(rom)
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val hp = parser.snesToPc(0xA0DCFF) + 4
        word(hp, 15); word(parser.snesToPc(0xA0DCFF) + 0x3A, 0xF900)
        rom[parser.snesToPc(0xB4F900)] = 0x37
        fun compiled(header: String = "A0:DCFF", table: String = "B4:F900") = AsmCompiledBuildBase(rom.copyOf(), rom.copyOf(), "test", emptyList(),
            assemblerVersion = "test", compilerOutput = emptyList(), compilerSymbols = "[labels]\n$header EnemyHeaders_TestZoomer\n$table EnemyDropChances_Test\n".toByteArray())
        val checked = protectEnemyTuningSource(source, headers, drops, project, compiled())
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == hp && it.length == 2 }, "The whole word is claimed even though it equals native source")
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == parser.snesToPc(0xB4F900) && it.length == 1 })
        assertFails { protectEnemyTuningSource(source, headers, drops, project, compiled("A0:DD00")) }
        assertFails { protectEnemyTuningSource(source, headers, drops, project, compiled(table = "B5:F900")) }
        assertFails { protectEnemyTuningSource(source, headers.replace("%health(15)", "%health(16)"), drops, project, compiled()) }
        project.patches.add(SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 20)))
        assertFails { protectEnemyTuningSource(source, headers, drops, project, compiled()) }
    }

    @Test fun `external ASM config conflicts have one atomic repair and newer patch consumers block ownership undo`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40, "zoomer_dmg" to 17))
        f.project.patches.add(patch)
        val a = f.a()
        f.workspace.replaceProjectSource(ENEMY_HEADER_SOURCE_FILE, a, a.replace("%health(15)", "%health(37)").replace("%damage(5)", "%damage(19)"))
        assertNotNull(f.bridge.view("zoomer_hp").problem)
        val external = f.a()
        f.bridge.useSourceValues(setOf("zoomer_hp", "zoomer_dmg"))
        assertNull(f.bridge.view("zoomer_hp").problem); assertNull(f.bridge.view("zoomer_dmg").problem)
        assertNull(patch.configData); assertEquals(external, f.a())
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(mapOf("zoomer_hp" to 40, "zoomer_dmg" to 17), assertNotNull(patch.configData).toMap())
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        val newer = SmPatch("newer", "Newer", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 90))
        f.project.patches.add(newer)
        assertFalse(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE))
        assertEquals(external, f.a()); assertNull(patch.configData)
        assertEquals(setOf("zoomer_hp", "zoomer_dmg"), f.project.asmWorkspace.enemyTuningSourceKeys)
        f.project.patches.remove(newer)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
    }

    @Test fun `advanced ROM-only override removal is undoable and does not touch scalar settings or ASM`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40, "zoomer_mainAi" to 0x9000))
        f.project.patches.add(patch)
        val before = f.a()
        f.bridge.removeAdvancedOverrides()
        assertEquals(mapOf("zoomer_hp" to 40), assertNotNull(patch.configData).toMap())
        assertEquals(before, f.a()); assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(0x9000, patch.configData!!["zoomer_mainAi"])
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertNull(patch.configData!!["zoomer_mainAi"])
    }
}
