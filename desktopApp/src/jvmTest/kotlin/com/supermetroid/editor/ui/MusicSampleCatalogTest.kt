package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import kotlin.test.*

class MusicSampleCatalogTest {
    private fun word(ram: ByteArray, at: Int, value: Int) { ram[at] = value.toByte(); ram[at + 1] = (value ushr 8).toByte() }
    private fun recording(ram: ByteArray, srcn: Int, at: Int, loop: Boolean = false, loopOffset: Int = 0) {
        val bytes = byteArrayOf(0xB0.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())
        bytes.copyInto(ram, at); bytes.copyInto(ram, at + 9)
        ram[at + 9] = if (loop) 0xB3.toByte() else 0xB1.toByte()
        word(ram, 0x6D00 + srcn * 4, at); word(ram, 0x6D02 + srcn * 4, at + loopOffset)
    }
    private fun fixture() = ByteArray(65536).also {
        recording(it, 0, 0x9000); recording(it, 24, 0x9100, true, 9); recording(it, 39, 0x9200)
    }

    @Test fun `catalog reads sparse native directory slots without heuristic probing and never mutates source RAM`() {
        val ram = fixture()
        word(ram, 0x6DA0, 0x9300) // Not one of the engine's 40 recording slots.
        val original = ram.copyOf()
        val samples = MusicSampleCatalog.read(ram).filterNot { it.isNoise }
        assertEquals(listOf(0, 24, 39), samples.map { it.srcn })
        assertTrue(samples.all { it.frames == 32 && it.brrBytes == 18 && it.waveform.size == 32 })
        assertEquals(null, samples[0].loopFrame)
        assertEquals(16, samples[1].loopFrame)
        assertTrue(samples.flatMap { it.waveform }.all { it.low in -1f..1f && it.high in -1f..1f && it.low <= it.high })
        assertContentEquals(original, ram)
    }

    @Test fun `identity follows encoded bytes and effective loop rather than addresses or reusable sample numbers`() {
        val ram = fixture()
        recording(ram, 5, 0x9300)
        val first = MusicSampleCatalog.read(ram).associateBy { it.srcn }
        assertEquals(first.getValue(0).resourceId, first.getValue(5).resourceId)
        ram[0x9301] = 0x44
        val changed = MusicSampleCatalog.read(ram).associateBy { it.srcn }
        assertNotEquals(first.getValue(5).resourceId, changed.getValue(5).resourceId)
        word(ram, 0x6D02 + 24 * 4, 0x9100)
        assertNotEquals(first.getValue(24).resourceId, MusicSampleCatalog.read(ram).first { it.srcn == 24 }.resourceId)
        word(ram, 0x6D02, 0xD000) // Unused loop pointer on a one-shot is not part of its identity.
        assertEquals(first.getValue(0).resourceId, MusicSampleCatalog.read(ram).first { it.srcn == 0 }.resourceId)
    }

    @Test fun `terminal loop flag determines looping not arbitrary earlier BRR header bits`() {
        val ram = fixture()
        ram[0x9000] = 0xB2.toByte()
        assertNull(MusicSampleCatalog.read(ram).first { it.srcn == 0 }.loopFrame)
    }

    @Test fun `invalid incomplete engine table and missing loop sources are not offered as recordings`() {
        val ram = fixture()
        word(ram, 0x6D04, 0x1000)
        word(ram, 0x6D08, 0x6C00)
        word(ram, 0x6D0C, 0xFFFD)
        word(ram, 0x6D10, 0xA000) // No end block before RAM ends.
        recording(ram, 5, 0x9400, true)
        word(ram, 0x6D02 + 5 * 4, 0x1000) // Loop points into engine memory.
        recording(ram, 6, 0x9500, true, 0x100) // Disjoint loop.
        assertEquals(listOf(0, 24, 39), MusicSampleCatalog.read(ram).filterNot { it.isNoise }.map { it.srcn })
    }

    @Test fun `linked loops and one-shot tails retain predictor history content identity and provenance`() {
        val ram = ByteArray(65536)
        recording(ram, 0, 0x9000, true)
        recording(ram, 24, 0x9300, true)
        word(ram, 0x6D02, 0x9300)
        val original = ram.copyOf()
        val imports = listOf("loop.nspc" to listOf(SpcData.TransferBlock(0x9301, byteArrayOf(1))))
        val first = MusicSampleCatalog.read(ram, imports = imports).first { it.srcn == 0 }
        assertEquals(64, first.frames)
        assertEquals(36, first.brrBytes)
        assertEquals(32, first.loopFrame)
        assertEquals(listOf("loop.nspc"), first.importedFrom)
        assertContentEquals(original, ram)
        ram[0x9301] = 0x44
        assertNotEquals(first.resourceId, MusicSampleCatalog.read(ram).first { it.srcn == 0 }.resourceId)
        ram[0x9309] = 0xB1.toByte() // The linked segment is now a one-shot tail.
        val tail = MusicSampleCatalog.read(ram).first { it.srcn == 0 }
        assertEquals(64, tail.frames)
        assertNull(tail.loopFrame)
    }

    @Test fun `silent complete recordings remain valid and noise sources are separate generated identities`() {
        val ram = ByteArray(65536)
        word(ram, 0x6D00, 0x9000); ram[0x9000] = 1
        val samples = MusicSampleCatalog.read(ram)
        val silent = samples.first { !it.isNoise }
        assertTrue(silent.waveform.all { it.low == 0f && it.high == 0f })
        val noise = samples.filter { it.isNoise }
        assertEquals((0x80..0x9F).toList(), noise.map { it.srcn })
        assertEquals(32, noise.map { it.resourceId }.toSet().size)
        assertTrue(noise.all { it.waveform.isEmpty() && it.origin == "Generated" && it.frames == 0 })
    }

    @Test fun `provenance accounts for partial BRR uploads directory rewrites and sibling source filenames`() {
        val ram = fixture()
        val bank = listOf(SpcData.TransferBlock(0x9101, byteArrayOf(1)))
        val imports = listOf("brass.nspc" to listOf(SpcData.TransferBlock(0x9208, byteArrayOf(1))),
            "voice.it" to listOf(SpcData.TransferBlock(0x6D00, byteArrayOf(1))))
        val samples = MusicSampleCatalog.read(ram, bank, imports).associateBy { it.srcn }
        assertEquals("Import", samples.getValue(0).origin)
        assertEquals(listOf("voice.it"), samples.getValue(0).importedFrom)
        assertEquals("Bank ROM", samples.getValue(24).origin)
        assertEquals(listOf("brass.nspc"), samples.getValue(39).importedFrom)
        assertEquals("Shared ROM", MusicSampleCatalog.read(ram).first { it.srcn == 24 }.origin)
    }

    @Test fun `source application preserves all envelope tuning and slot metadata`() {
        val entry = NspcRenderer.InstrumentEntry(0, 0xAD, 0xE4, 0x60, 0x1234, 28, 0x6CA8)
        for (sample in MusicSampleCatalog.read(fixture())) {
            val changed = MusicSampleCatalog.apply(entry, sample)
            assertEquals(sample.srcn, changed.srcn)
            assertEquals(entry, changed.copy(srcn = entry.srcn))
        }
    }

    @Test fun `labels are bank and content scoped metadata and never audio edits`() {
        val samples = MusicSampleCatalog.read(fixture())
        val resource = samples.first().resourceId
        val editor = EditorState()
        editor.renameMusicSample(3, resource, " Warm pluck ")
        editor.renameMusicSample(0x18, resource, "Other bank")
        assertEquals("Warm pluck", editor.musicSampleNames(3)[resource])
        assertEquals("Other bank", editor.musicSampleNames(0x18)[resource])
        assertTrue(editor.project.musicEdits.isEmpty())
        assertTrue(editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(editor.project.patches.isEmpty())
        assertEquals(0, editor.musicEditVersion)
        val namedSound = NspcRenderer.InstrumentEntry(24, 0x8F, 0xE0, 0x60, 0x0001, 28)
        assertTrue(InstrumentDescriptions.matches(namedSound, emptyMap(), "sample 24", mapOf(24 to "Warm loop")))
        assertTrue(InstrumentDescriptions.matches(namedSound, emptyMap(), "0x18", mapOf(24 to "Warm loop")))
        val version = editor.musicSampleNameVersion
        editor.renameMusicSample(3, resource, "Warm pluck")
        assertEquals(version, editor.musicSampleNameVersion)
        editor.renameMusicSample(3, resource, " ")
        assertFalse(resource in editor.musicSampleNames(3))
        assertFailsWith<IllegalArgumentException> { editor.renameMusicSample(3, "sample:0", "Wrong identity") }
    }

    @Test fun `real loaded banks expose every recording used by their instrument table`() {
        val parser = TestRomHelper.requireRomParser()
        for (songSet in SpcData.KNOWN_TRACKS.map { it.songSet }.distinct()) {
            val ram = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet)) }
            val sources = MusicSampleCatalog.read(ram).map { it.srcn }.toSet()
            val instruments = NspcRenderer.readInstrumentTable(ram).filter(InstrumentDescriptions::playable)
            val missing = instruments.filter { it.srcn !in sources }
            assertTrue(missing.isEmpty(), "Bank $songSet has unlisted sources: ${missing.map {
                val loopBytes = SpcData.measureBrrSize(ram, it.sampleLoopAddr)
                "$it, endHeader=${ram[it.sampleStartAddr + it.brrByteLength - 9].toInt() and 255}, loopBytes=$loopBytes, " +
                    "loopEndHeader=${if (loopBytes > 0) ram[it.sampleLoopAddr + loopBytes - 9].toInt() and 255 else -1}"
            }}")
        }
    }
}
