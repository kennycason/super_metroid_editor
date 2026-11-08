package com.supermetroid.editor.asm

import com.supermetroid.editor.data.MapStationTileEdit
import com.supermetroid.editor.data.MinimapTileEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.MinimapData
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmMinimapSourceBridgeTest {
    @TempDir lateinit var directory: File

    @Test
    fun `inline source round trips storage coordinates without reformatting either bank`() = runBlocking {
        val fixture = fixture()
        val initial = fixture.bridge.view(0)
        assertNull(initial.problem)
        assertEquals(32, initial.map!!.getTile(0, 0))
        assertEquals(0, initial.map.getTile(0, 31))
        assertTrue(initial.stations!!.isRevealed(0, 31))
        assertTrue(initial.stations.isRevealed(0, 0))

        val map = initial.map.withTile(7, 0, 0xABCD).withTile(40, 31, 0x1234)
        val stations = initial.stations.withToggle(7, 0).withToggle(0, 31)
        val beforeB5 = fixture.workspace.projectSourceText(MINIMAP_TILE_SOURCE_FILE)
        val before82 = fixture.workspace.projectSourceText(MINIMAP_REVEAL_SOURCE_FILE)
        val plan = fixture.bridge.plan(mapOf(0 to (map to stations)))
        val updatedB5 = plan.replacements.getValue(MINIMAP_TILE_SOURCE_FILE).second
        val updated82 = plan.replacements.getValue(MINIMAP_REVEAL_SOURCE_FILE).second
        assertTrue(updatedB5.contains("; keep tile formatting"))
        assertTrue(updated82.contains("; keep reveal formatting"))
        assertEquals(beforeB5.lines().size, updatedB5.lines().size)
        assertEquals(before82.lines().size, updated82.lines().size)

        fixture.project.minimapEdits["0"] = mutableListOf(MinimapTileEdit(7, 0, 0xABCD))
        fixture.project.mapStationEdits["0"] = mutableListOf(MapStationTileEdit(7, 0, true))
        var transitions = 0
        val history = fixture.bridge.apply(plan, fixture.project) { transitions++ }
        assertFalse("0" in fixture.project.minimapEdits)
        assertFalse("0" in fixture.project.mapStationEdits)
        assertEquals(map, fixture.bridge.view(0).map)
        assertEquals(stations, fixture.bridge.view(0).stations)
        assertEquals(1, transitions)

        assertTrue(history.undo())
        assertEquals(beforeB5, fixture.workspace.projectSourceText(MINIMAP_TILE_SOURCE_FILE))
        assertEquals(before82, fixture.workspace.projectSourceText(MINIMAP_REVEAL_SOURCE_FILE))
        assertNotNull(fixture.project.minimapEdits["0"])
        assertNotNull(fixture.project.mapStationEdits["0"])
        assertTrue(history.redo())
        assertEquals(map, fixture.bridge.view(0).map)
        assertEquals(3, transitions)
    }

    @Test
    fun `custom pointers malformed arrays and newer source edits fail closed`() = runBlocking {
        val customPointers = fixture(areaPointer = "CustomCrateria")
        assertTrue(customPointers.bridge.view(0).problem.orEmpty().contains("custom"))

        val malformed = fixture(tileCount = MinimapData.TILE_COUNT - 1)
        assertTrue(malformed.bridge.view(0).problem.orEmpty().contains("expected 2048"))

        val fixture = fixture()
        val opened = fixture.bridge.view(0)
        val plan = fixture.bridge.plan(mapOf(0 to (opened.map!!.withTile(4, 4, 0xCAFE) to opened.stations!!)))
        val history = fixture.bridge.apply(plan, fixture.project) {}
        val current = fixture.workspace.projectSourceText(MINIMAP_TILE_SOURCE_FILE)
        fixture.workspace.replaceProjectSource(MINIMAP_TILE_SOURCE_FILE, current, current + "; newer\n")
        val problem = runCatching { history.undo() }.exceptionOrNull()
        assertNotNull(problem)
        assertTrue(problem.message.orEmpty().contains("newer", ignoreCase = true))
    }

    private data class Fixture(
        val project: SmEditProject,
        val workspace: AsmWorkspaceState,
        val bridge: AsmMinimapSourceBridge,
    )

    private suspend fun fixture(
        areaPointer: String = "MapTilemaps_crateria",
        tileCount: Int = MinimapData.TILE_COUNT,
    ): Fixture {
        val root = File(directory, "reference-${directory.listFiles().orEmpty().size}")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_B5.asm\nincsrc bank_82.asm\n")
        File(src, MINIMAP_TILE_SOURCE_FILE).writeText(tileSource(tileCount))
        File(src, MINIMAP_REVEAL_SOURCE_FILE).writeText(revealSource(areaPointer))
        val reference = AsmReferenceWorkspace(
            root,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "test",
                "base.sfc",
                "ab".repeat(32),
                0x300000,
                0,
            ),
            AsmSourceParser().parse(src, data, emptyList()),
        )
        val file = File(root, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        return Fixture(SmEditProject(File(root, "base.sfc").path), workspace, AsmMinimapSourceBridge(workspace))
    }

    private fun tileSource(firstCount: Int): String = buildString {
        val labels = listOf(
            "MapTilemaps_brinstar",
            "MapTilemaps_crateria",
            "MapTilemaps_norfair",
            "MapTilemaps_wreckedShip",
            "MapTilemaps_maridia",
            "MapTilemaps_tourian",
            "UNUSED_MapTilemaps_ceres_B5E000",
        )
        for ((labelIndex, label) in labels.withIndex()) {
            append(label).append(":\n")
            val count = if (label == "MapTilemaps_crateria") firstCount else MinimapData.TILE_COUNT
            val values = IntArray(count) { if (labelIndex == 1) it else 0x1F }
            values.asList().chunked(16).forEachIndexed { line, words ->
                append("    dw ").append(words.joinToString(",") { "\$${it.toString(16).uppercase().padStart(4, '0')}" })
                if (label == "MapTilemaps_crateria" && line == 0) append(" ; keep tile formatting")
                append('\n')
            }
        }
        append("Freespace_BankB5_F000:\n")
    }

    private fun revealSource(areaPointer: String): String = buildString {
        append("AreaMapPointers:\n")
        listOf(areaPointer, "MapTilemaps_brinstar", "MapTilemaps_norfair", "MapTilemaps_wreckedShip",
            "MapTilemaps_maridia", "MapTilemaps_tourian", "UNUSED_MapTilemaps_ceres_B5E000")
            .forEach { append("    dl ").append(it).append('\n') }
        append("AreaLabelTilemaps:\n")
        append("MapData:\n  .pointers:\n")
        val labels = listOf("crateria", "brinstar", "norfair", "wreckedShip", "maridia", "tourianDebug", "ceres")
        labels.forEach { append("    dw MapData_").append(it).append('\n') }
        append("    dw MapData_tourianDebug\n")
        labels.forEach { label ->
            append("  .").append(label).append(":\n")
            val values = IntArray(MinimapData.MAP_STATION_DATA_SIZE)
            if (label == "crateria") { values[0] = 0x80; values[4] = 0x80 }
            values.asList().chunked(16).forEachIndexed { line, bytes ->
                append("    db ").append(bytes.joinToString(",") { "\$${it.toString(16).uppercase().padStart(2, '0')}" })
                if (label == "crateria" && line == 0) append(" ; keep reveal formatting")
                append('\n')
            }
        }
        append("SetupMapScrollingForPauseMenu:\n    rts\n")
    }
}
