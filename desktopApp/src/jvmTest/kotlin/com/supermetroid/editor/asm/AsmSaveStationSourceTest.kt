package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.*
import kotlin.test.*

internal fun saveSourceTestRom(): ByteArray = objectSourceTestRom().also { rom ->
    fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
    listOf(0xC4C5, 0xC5CF, 0xC6D9, 0xC81B, 0xC917, 0xCA2F, 0xCB2B, 0xCC19).forEachIndexed { index, ptr -> word(0x44B5 + index * 2, ptr) }
    word(0x44C5, 0x91F8); word(0x44C7, 0x8000) // Gunship-like occupied slot without an ordinary save PLM.
    word(0x44C5 + 14, 0x9000) // Another room's occupied slot.
    word(0x44C5 + 2 * 14 + 4, 7) // Preserve this otherwise unedited demo-recorder field.
    word(0x791F8 + 9, 0x8060); word(0x78060, 0x8000) // Synthetic self/incoming door.
    word(0x18000, 0x91F8)
    rom.fill(0xFF.toByte(), 0x7F000, 0x80000)
}

class AsmSaveStationSourceTest {
    private val save = SourceSaveResource("SmeditObjects_91F8_Save_0_2", 0x91F8, 0, 2,
        listOf(0x91F8, 0x8000, 7, 256, 0, 144, 0xFFE0))

    @Test fun `symbolic save selectors are semantic even with stale compiled symbols and preserve notes in both directions`() {
        val reference = com.supermetroid.editor.data.ProjectSourceDoorReference(0x91F8, 4)
        val symbolic = save.copy(words = save.words.toMutableList().also { it[1] = 0 }, sourceDoor = reference)
        val text = AsmSaveStationSource.render(symbolic).replace("; Incoming door", "; keep entrance note · Incoming door").replace("\n", "\r\n")
        assertEquals(symbolic, AsmSaveStationSource.parse(save.identity, authoredMacroBody(text, save.identity), mapOf(reference.entryLabel to 0xF000)))
        val edited = AsmRoomObjectSource(text).withSave(symbolic.copy(words = symbolic.words.toMutableList().also { it[3] = 512 }))
        assertEquals(text.replace("dw ${'$'}0100", "dw ${'$'}0200"), edited)
        val native = AsmRoomObjectSource(text).withSave(save)
        assertContains(native, "dw ${'$'}8000 ; keep entrance note")
        assertEquals(text, AsmRoomObjectSource(native).withSave(symbolic))
        val cleared = AsmRoomObjectSource(text).withSave(symbolic.copy(words = List(7) { 0 }, sourceDoor = null))
        assertTrue(AsmRoomObjectSource(cleared).saveResources(0x91F8).single().cleared)
        assertFails { AsmSaveStationSource.parse(save.identity, authoredMacroBody(text, save.identity).replace(reference.entryLabel, "${reference.entryLabel}+2")) }
        assertFails { AsmSaveStationSource.parse(save.identity, authoredMacroBody(text, save.identity).replace("dw ${'$'}0100", "dw ${reference.entryLabel}"), mapOf(reference.entryLabel to 0xF000)) }
    }

    @Test fun `seven source words round trip and preserve negative two complement offsets and door BTS`() {
        val text = AsmSaveStationSource.render(save)
        assertEquals(save, AsmSaveStationSource.parse(save.identity, authoredMacroBody(text, save.identity)))
        assertEquals(14, save.bytes().size)
        assertEquals(0xE0.toByte(), save.bytes()[12])
        assertEquals(7, save.words[2])
        assertContains(text, "Samus X · screen center")
        assertTrue(save.copy(words = List(7) { 0 }).cleared)
    }

    @Test fun `width changes invalid owner wrong door and special slot identities fail closed`() {
        val body = authoredMacroBody(AsmSaveStationSource.render(save), save.identity)
        assertFails { AsmSaveStationSource.parse(save.identity, body + "dw 1\n") }
        assertFails { AsmSaveStationSource.parse(save.identity, body.replace("dw", "db")) }
        assertFails { AsmSaveStationSource.parse(save.identity, body.replace("${'$'}FFE0", "${'$'}10000")) }
        assertFails { AsmSaveStationSource.parse(save.identity, body.replace("${'$'}91F8", "${'$'}9000")) }
        for (area in listOf(6, 7)) assertFails { AsmSaveStationSource.render(save.copy(area = area)) }
        assertFails { save.copy(slot = 8).validate() }
        assertFails { save.copy(words = save.words.toMutableList().also { it[1] = 0 }).validate() }
    }

    @Test fun `blank slots are allowed but occupied foreign and gunship slots cannot be written or cleared`() {
        val parser = RomParser(saveSourceTestRom())
        validateSourceSaveOwnership(save, parser)
        assertFails { validateSourceSaveOwnership(save.copy(slot = 0), parser) }
        assertFails { validateSourceSaveOwnership(save.copy(slot = 0, words = List(7) { 0 }), parser) }
        assertFails { validateSourceSaveOwnership(save.copy(slot = 1), parser) }
        val wrongDoor = save.copy(words = save.words.toMutableList().also { it[1] = 0x8100 })
        assertFails { validateSourceSaveOwnership(wrongDoor, parser) }
    }

    @Test fun `GUI scalar save edits preserve CRLF notes symbolic unchanged fields and reject symbolic replacements`() {
        val original = AsmSaveStationSource.render(save).replace("dw ${'$'}91F8", "dw RoomName")
            .replace("; Camera X", "; my camera note").replace("\n", "\r\n")
        val source = AsmRoomObjectSource(original, mapOf("RoomName" to 0x91F8))
        val changed = save.copy(words = save.words.toMutableList().also { it[3] = 512 })
        val result = source.withSave(changed)
        assertContains(result, "dw RoomName")
        assertContains(result, "dw ${'$'}0200 ; my camera note\r\n")
        assertEquals(original.replace("${'$'}0100", "${'$'}0200"), result)
        assertFails { source.withSave(save.copy(words = List(7) { 0 })) }
    }

    @Test fun `save records remain until final state removal and released records survive source cleanup`() {
        val parser = RomParser(saveSourceTestRom())
        val room = RoomEdits(0x91F8)
        val migration = AsmRoomObjectSource.migrate("", room, parser, "Test", emptyMap())
        room.states.addAll(migration.states); room.asmPlmSourceVersion = 1
        val station = RomParser.PlmEntry(0xB76F, 8, 8, 0x8002)
        var source = AsmRoomObjectSource(migration.text)
        source = AsmRoomObjectSource(source.withSave(save))
        source = AsmRoomObjectSource(source.edit(room, "state-1", true, { false }, station))
        source = AsmRoomObjectSource(source.editSaveStation(room, "state-1", { it == station }, null, save, emptySet()))
        assertFalse(source.saveResources(room.roomId).single().cleared)
        assertTrue(source.table(room.roomId, "state-2").document.entries.contains(station))
        source = AsmRoomObjectSource(source.editSaveStation(room, "state-2", { it == station }, null, save, emptySet()))
        assertTrue(source.saveResources(room.roomId).single().cleared)
        assertContains(source.tidy(room), "macro ${save.identity}")
        assertTrue(source.resources(room).values.all { it.saves.single().cleared })
    }
}
