package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ProjectRoomExporter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RomWritePlanIntegrationTest {
    @Test
    fun `spike olympics legacy Varia save station is reconstructed when moved to Crateria`() {
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit"
        ) ?: return
        val baseRom = File(projectFile.parentFile, "Super Metroid Spike Olympics I.smc")
        if (!baseRom.isFile) return
        val parser = RomParser(baseRom.readBytes())
        val room = parser.readRoomHeader(0xA6E2) ?: return
        val originalSlot = parser.readSaveEntry(room.area, 1) ?: return
        val editor = EditorState().also {
            it.testMode = true
            it.initForRom(baseRom.absolutePath)
        }

        val result = editor.reassignRoomArea(room.roomId, 0, parser)

        assertTrue(result.success, result.message)
        assertTrue(result.message.contains("reconstructed 1 missing AreaSave slot"))
        val edits = editor.project.rooms.getValue(editor.project.roomKey(room.roomId))
        val destination = edits.saveStationSpawns.single {
            !it.clearSlot && it.area == 0 && it.roomId == room.roomId
        }
        assertTrue(edits.saveStationSpawns.none { it.clearSlot }, "Another room owns the legacy PLM's old index")
        val exportedBytes = parser.getRomData().copyOf()
        ProjectRoomExporter(editor.project, RomParser(exportedBytes), exportedBytes).exportRooms()
        val exported = RomParser(exportedBytes)
        assertEquals(0, exported.readRoomHeader(room.roomId)?.area)
        assertEquals(originalSlot, exported.readSaveEntry(room.area, 1))
        assertEquals(room.roomId, exported.readSaveEntry(0, destination.saveIndex)?.roomId)
        val station = exported.getAllPlmEntriesForRoom(room.roomId).single { it.id == 0xB76F }
        assertEquals(destination.saveIndex, station.param and 0xFF)
    }

    @Test
    fun `spike olympics legacy Frog Savestation can move to normal destination areas`() {
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit"
        ) ?: return
        val baseRom = File(projectFile.parentFile, "Super Metroid Spike Olympics I.smc")
        if (!baseRom.isFile) return
        val parser = RomParser(baseRom.readBytes())
        val room = parser.readRoomHeader(0xB167) ?: return
        for (targetArea in listOf(0, 1, 3, 4, 5)) {
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom(baseRom.absolutePath)
            }

            val result = editor.reassignRoomArea(room.roomId, targetArea, parser)

            assertTrue(result.success, "Target area $targetArea: ${result.message}")
            val edits = editor.project.rooms.getValue(editor.project.roomKey(room.roomId))
            assertEquals(targetArea, edits.roomHeaderChange?.area)
            assertTrue(edits.saveStationSpawns.any { it.clearSlot && it.area == room.area })
            assertTrue(edits.saveStationSpawns.any {
                !it.clearSlot && it.area == targetArea && it.roomId == room.roomId
            })
            if (targetArea == 1) {
                val sourceIndex = (0 until parser.saveEntryCount(room.area)).single { index ->
                    parser.readSaveEntry(room.area, index)?.roomId == room.roomId
                }
                val destination = edits.saveStationSpawns.single {
                    !it.clearSlot && it.area == targetArea && it.roomId == room.roomId
                }
                val exportedBytes = parser.getRomData().copyOf()
                ProjectRoomExporter(editor.project, RomParser(exportedBytes), exportedBytes).exportRooms()
                val exported = RomParser(exportedBytes)
                assertEquals(0, exported.readSaveEntry(room.area, sourceIndex)?.roomId)
                assertEquals(room.roomId, exported.readSaveEntry(targetArea, destination.saveIndex)?.roomId)
                val station = exported.getAllPlmEntriesForRoom(room.roomId).single { it.id == 0xB76F }
                assertEquals(destination.saveIndex, station.param and 0xFF)
            }
        }
    }

    @Test
    fun `spike olympics project exports through owned transactional plan`() {
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit"
        ) ?: return
        val baseRom = File(projectFile.parentFile, "Super Metroid Spike Olympics I.smc")
        if (!baseRom.isFile) return

        val tempDir = Files.createTempDirectory("smedit-spike-write-plan").toFile()
        try {
            val tempRom = File(tempDir, "spike.smc")
            baseRom.copyTo(tempRom)
            val project = ProjectFileService.loadProject(projectFile).copy(romPath = tempRom.absolutePath)
            val logs = mutableListOf<String>()

            val output = ProjectFileService.exportToRom(
                project = project,
                romParser = RomParser(tempRom.readBytes()),
                onLog = logs::add,
                onStatus = logs::add,
            )

            assertNotNull(output, logs.joinToString("\n"))
            assertTrue(File(output).isFile)
            assertTrue(logs.any { it.contains("[ROM-PLAN] Validated") })
            assertTrue(logs.any { it.contains("patch:bundled_spider_ball") })
            assertTrue(logs.any { it.contains("patch:config_room_name_pause_map") })
            assertTrue(logs.none { it.contains("failed safely", ignoreCase = true) })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun findWorkspaceFile(relativePath: String): File? {
        var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val candidate = cursor?.resolve(relativePath)
            if (candidate?.isFile == true) return candidate
            cursor = cursor?.parentFile
        }
        return null
    }
}
