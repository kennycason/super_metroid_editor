package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomProjectionTest {
    @TempDir lateinit var directory: File
    private val parser get() = RomParser(objectSourceTestRom())
    private val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"
    private fun catalog(symbols: Map<String, Int> = emptyMap()) = AsmPlmSourceCatalog("", macros, symbols, parser, emptyMap())
    private fun roomSource(): Pair<RoomEdits, String> {
        val room = RoomEdits(0x91F8)
        val migration = AsmRoomObjectSource.migrate("", room, parser, "Test", emptyMap())
        room.states.addAll(migration.states)
        room.asmPlmSourceVersion = 1
        return room to migration.text
    }

    @Test fun `warm named scroll references retain exact source identity and invalidate for source commands or room bounds`() {
        val (room, original) = roomSource()
        val text = AsmRoomObjectSource(original).editScroll(room, "state-1", 4, 5,
            listOf(com.supermetroid.editor.data.ScrollCommand(3, 2)), 2, 2)
        val catalog = catalog()
        AsmRoomProjectionRepository(directory).load(text, macros, listOf(room), catalog)
        val warm = AsmRoomProjectionRepository(directory) { _, _ -> error("Warm source should not be decoded") }
        val document = warm.load(text, macros, listOf(room), catalog).getValue(room.roomId).documents.getValue("state-1")
        val row = document.records.last()
        assertEquals("SmeditObjects_91F8_Scroll_1", row.scrollResource)
        assertEquals(0, row.entry.param, "No allocation address may enter a durable preview")
        val repository = AsmRoomProjectionRepository(directory)
        assertTrue(repository.load(text.replace("db ${'$'}03, ${'$'}02", "db ${'$'}03, ${'$'}09"), macros, listOf(room), catalog).isEmpty())
        room.roomHeaderChange = com.supermetroid.editor.data.RoomHeaderChange(width = 1, height = 1)
        assertTrue(repository.load(text, macros, listOf(room), catalog).isEmpty(), "Out-of-room commands must not reuse a warm projection")
    }

    @Test fun `warm typed projections restore exact state rows and navigation without parsing source`() {
        val (room, text) = roomSource()
        val catalog = catalog()
        val expected = AsmRoomObjectSource(text).resources(room)
        AsmRoomProjectionRepository(directory).load(text, macros, listOf(room), catalog)
        val warm = AsmRoomProjectionRepository(directory) { _, _ -> error("Warm source should not be decoded") }
        val projection = assertNotNull(warm.load(text, macros, listOf(room), catalog)[room.roomId])
        for (state in room.states) {
            val doc = assertNotNull(projection.documents[state.id])
            assertEquals(expected.getValue(state.id).entries, doc.entries)
            val target = assertIs<AsmPlmPlacementTarget.Source>(doc.placementTarget(doc.entries.single()))
            assertContains(text.lines()[target.lineIndex], "%PLMPopEntry")
            assertEquals(text, doc.text)
        }
    }

    @Test fun `symbolic saves use warm projections but changed or missing door dependencies never reuse them`() {
        val (room, original) = roomSource()
        val reference = com.supermetroid.editor.data.ProjectSourceDoorReference(0x91F8, 9)
        val save = com.supermetroid.editor.rom.SourceSaveResource(AsmSaveStationSource.name(0x91F8, 0, 2), 0x91F8, 0, 2,
            listOf(0x91F8, 0, 7, 256, 0, 144, 0xFFE0), reference)
        val text = AsmRoomObjectSource(original).withSave(save)
        val catalog = AsmPlmSourceCatalog("", macros, emptyMap(), RomParser(saveSourceTestRom()), emptyMap())
        val door = com.supermetroid.editor.rom.SourceRoomDoor(0, com.supermetroid.editor.data.DoorChange(0, 0x91F8, 0, 0, 0, 0, 0x8000, 0), identity = 9)
        val doors = mapOf(0x91F8 to com.supermetroid.editor.rom.SourceRoomDoors(0x8060, listOf(door), structural = true))
        val expected = AsmRoomProjectionRepository(directory).load(text, macros, listOf(room), catalog, doors)
        assertTrue(expected.isNotEmpty())
        val warm = AsmRoomProjectionRepository(directory) { _, _ -> error("Valid warm objects must not be decoded") }
        assertEquals(expected, warm.load(text, macros, listOf(room), catalog, doors))
        assertTrue(warm.load(text, macros, listOf(room), catalog).isEmpty())
        val redirected = doors.mapValues { (_, value) -> value.copy(entries = listOf(door.copy(change = door.change.copy(destRoomPtr = 0xA000)))) }
        assertTrue(AsmRoomProjectionRepository(directory).load(text, macros, listOf(room), catalog, redirected).isEmpty())
        assertEquals(expected, warm.load(text, macros, listOf(room), catalog, doors))
    }

    @Test fun `external source changes and corrupt payloads repair rather than reuse stale rows`() {
        val (room, text) = roomSource()
        val catalog = catalog()
        var decodes = 0
        val repository = AsmRoomProjectionRepository(directory) { source, symbols -> decodes++; AsmRoomObjectSource(source, symbols) }
        repository.load(text, macros, listOf(room), catalog)
        assertEquals(1, decodes)
        val changed = text.replace("${'$'}EEDB", "${'$'}EEE3")
        val changedView = repository.load(changed, macros, listOf(room), catalog).getValue(room.roomId)
        assertTrue(changedView.documents.values.all { it.entries.single().id == 0xEEE3 })
        assertEquals(2, decodes)
        directory.listFiles()!!.forEach { it.writeText("{truncated") }
        assertEquals(changedView, repository.load(changed, macros, listOf(room), catalog).getValue(room.roomId))
        assertEquals(3, decodes)
    }

    @Test fun `symbols macro layout state membership and invalid drafts cannot borrow a valid cached model`() {
        val (room, literal) = roomSource()
        val text = literal.replace("${'$'}EEDB", "ItemId")
        val repository = AsmRoomProjectionRepository(directory)
        val first = repository.load(text, macros, listOf(room), catalog(mapOf("ItemId" to 0xEEDB)))
        assertEquals(0xEEDB, first.getValue(room.roomId).documents.values.first().entries.single().id)
        val changed = repository.load(text, macros, listOf(room), catalog(mapOf("ItemId" to 0xEEE3)))
        assertEquals(0xEEE3, changed.getValue(room.roomId).documents.values.first().entries.single().id)
        assertTrue(repository.load(text.replace("    dw", "    invalid draft\n    dw"), macros, listOf(room), catalog(mapOf("ItemId" to 0xEEDB))).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            repository.load(text, macros.replace("dw <ID>", "db <ID>"), listOf(room), catalog(mapOf("ItemId" to 0xEEDB)))
        }
        room.states.add(room.states.first().copy(id = "missing-source-wrapper"))
        assertTrue(repository.load(text, macros, listOf(room), catalog(mapOf("ItemId" to 0xEEDB))).isEmpty())
    }
}
