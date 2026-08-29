package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MapStationTileEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MinimapExportTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `map station reveal edits export alongside minimap data`() {
        val bytes = TestRomHelper.loadRomBytes()
        assumeTrue(bytes != null, "Test ROM not found")
        val input = File(tempDir, "minimap-station.smc")
        input.writeBytes(bytes!!)
        val parser = RomParser(bytes)
        val area = 0
        val x = 7
        val y = 5
        val baselineReveal = parser.readMapStationData(area).isRevealed(x, y)
        val baselineTile = parser.readMinimapTiles(area).getTile(x, y)
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.mapStationEdits[area.toString()] = mutableListOf(
                MapStationTileEdit(x, y, !baselineReveal),
            )
        }

        val outputPath = RomExporter(project, parser).export()

        assertNotNull(outputPath)
        val exported = RomParser(File(outputPath!!).readBytes())
        assertEquals(!baselineReveal, exported.readMapStationData(area).isRevealed(x, y))
        assertEquals(baselineTile, exported.readMinimapTiles(area).getTile(x, y))
    }
}
