package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MusicTransferLoaderTest {
    @TempDir lateinit var directory: File

    @Test fun `hook bytes match independently assembled 65816 source`() {
        val parser = TestRomHelper.requireRomParser()
        val compiled = NativeMusicUploadFixture.assembleResource(directory, parser.copyRomData(), "/sound/transfer-continuation.asm").readBytes()
        val start = RomParser(compiled).snesToPc(0xB88002) // Exclude the assembler's REP width prelude.
        assertContentEquals(MusicTransferLoader.code, compiled.copyOfRange(start, start + MusicTransferLoader.code.size))
    }

    @Test fun `complete loader fingerprint rejects changed handshake reader overflow and return paths`() {
        val loaded = TestRomHelper.requireRomParser().copyRomData()
        val original = if (loaded.size % 0x8000 == 512) loaded.copyOfRange(512, loaded.size) else loaded
        for (offset in listOf(0, 512)) {
            val rom = if (offset == 512) ByteArray(original.size + 512).also { original.copyInto(it, 512) } else original.copyOf()
            val parser = RomParser(rom)
            MusicTransferLoader.requireOriginal(parser)
            for (at in listOf(0x808040, 0x8080B1, 0x8080C4, 0x8080F0, 0x808107)) {
                val pc = parser.snesToPc(at)
                rom[pc] = (rom[pc].toInt() xor 1).toByte()
                val before = rom.copyOf()
                assertFailsWith<IllegalArgumentException> { MusicTransferLoader.patchWrites(parser, 0xB88000) }
                assertContentEquals(before, rom, "An unknown loader must not be patched")
                rom[pc] = (rom[pc].toInt() xor 1).toByte()
            }
        }
    }

    @Test fun `concrete hook plan is detached and replaces only the original six byte size reader`() {
        val parser = TestRomHelper.requireRomParser()
        val before = parser.copyRomData()
        val writes = MusicTransferLoader.patchWrites(parser, 0xB88000)
        assertEquals(setOf(parser.snesToPc(0xB88000), parser.snesToPc(0x8080B1)), writes.keys)
        assertContentEquals(byteArrayOf(0x22, 0, 0x80.toByte(), 0xB8.toByte(), 0xEA.toByte(), 0xEA.toByte()),
            writes.getValue(parser.snesToPc(0x8080B1)))
        assertContentEquals(MusicTransferLoader.code, writes.getValue(parser.snesToPc(0xB88000)))
        writes.values.first()[0] = 0
        assertNotEquals(0, MusicTransferLoader.code[0].toInt())
        assertContentEquals(before, parser.copyRomData())
    }

    @Test fun `hook placement rejects invalid pointers bank crossings loader overlap and missing ROM`() {
        val parser = TestRomHelper.requireRomParser()
        for (pointer in listOf(0, 0x7F8000, 0xB87FFF, 0xB8FFF0, 0x8080B1, 0xFF8000)) {
            assertFailsWith<IllegalArgumentException>(pointer.toString(16)) { MusicTransferLoader.patchWrites(parser, pointer) }
        }
    }

    @Test fun `instrument prefix is sorted compact and continues to the retained ROM chain without terminator`() {
        val a = byteArrayOf(1, 2, 3, 4, 5, 6)
        val b = byteArrayOf(7, 8, 9, 10, 11, 12)
        val prefix = MusicTransferLoader.instrumentPrefix(linkedMapOf(0x6C06 to b, 0x6C00 to a), 0xD0E20D)
        assertContentEquals(byteArrayOf(12, 0, 0, 0x6C) + a + b +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x0D, 0xE2.toByte(), 0xD0.toByte()), prefix)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), a)
        assertEquals(42 * 6 + 4 + 5, MusicTransferLoader.instrumentPrefix(
            (0 until 42).associate { 0x6C00 + it * 6 to ByteArray(6) }, 0xD0E20D).size)
    }

    @Test fun `prefix cannot smuggle partial settings engine bytes directory or sample data`() {
        for ((at, data) in listOf(0x6C00 to ByteArray(5), 0x6C01 to ByteArray(6), 0x6CFC to ByteArray(6),
            0x6D00 to ByteArray(6), 0x1500 to ByteArray(6), 0x6E00 to ByteArray(6))) {
            assertFailsWith<IllegalArgumentException> { MusicTransferLoader.instrumentPrefix(mapOf(at to data), 0xD0E20D) }
        }
        assertFailsWith<IllegalArgumentException> { MusicTransferLoader.instrumentPrefix(emptyMap(), 0xD0E20D) }
        for (pointer in listOf(0x7F8000, 0xD07FFF, 0xD0FFFF, -1)) {
            assertFailsWith<IllegalArgumentException> { MusicTransferLoader.instrumentPrefix(mapOf(0x6C00 to ByteArray(6)), pointer) }
        }
    }

    @Test fun `continuation building block does not enable common resource exports`() {
        val parser = TestRomHelper.requireRomParser()
        val project = com.supermetroid.editor.data.SmEditProject(romPath = "test.sfc")
        val ram = SpcData.buildInitialSpcRam(parser)
        val original = com.supermetroid.editor.rom.NspcRenderer.readInstrumentTable(ram)[0]
        project.musicInstrumentBanks["03"] = com.supermetroid.editor.data.MusicInstrumentBankEdit(mutableMapOf(
            0 to MusicInstrumentBanks.entry(original.copy(gain = original.gain xor 1))))
        assertFalse(MusicBankTransitions.inspect(parser, project).canExport)
        val rom = parser.copyRomData()
        val before = rom.copyOf()
        assertFailsWith<IllegalStateException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertContentEquals(before, rom)
    }
}
