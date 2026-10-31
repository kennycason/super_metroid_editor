package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class CompactMusicEncodingTest {
    @TempDir lateinit var directory: File
    private fun bank(parser: RomParser, set: Int) = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, set)) }
    @Test fun `compact edited native tracks preserve the full arrangement and share exact size preflight`() {
        val parser = TestRomHelper.requireRomParser()
        for (set in listOf(3, 0x0F, 0x18)) {
            val ram = bank(parser, set)
            val song = NspcSequence.parse(ram, 5)
            song.channels.first { it.notes.isNotEmpty() }.notes.first().noteValue += 1
            val stats = NspcSequence.encodingStats(song)
            println("PACK bank=$set literal=${stats.literalBytes} packed=${stats.bytes} phrases=${stats.phrases}")
            assertTrue(stats.savedBytes > 0)
            val writes = NspcSequence.encode(song, 5, ram, failOnOverflow = true, conductorAddrOverride = 0x7000, sequenceEndAddr = 0x7000 + stats.bytes)
            assertEquals(stats.bytes, writes.filterKeys { it != 0x5828 }.values.sumOf { it.size })
            val updated = ram.copyOf().also { out -> writes.forEach { (at, data) -> data.copyInto(out, at) } }
            val parsed = NspcSequence.parse(updated, 5)
            assertEquals(song.channels.map { it.notes }, parsed.channels.map { it.notes })
            val literal = ram.copyOf().also { out ->
                NspcSequence.encode(song, 5, ram, failOnOverflow = true, conductorAddrOverride = 0x7000,
                    sequenceEndAddr = 0x7000 + stats.literalBytes, compact = false).forEach { (at, data) -> data.copyInto(out, at) }
            }
            assertEquals(NspcSequence.parse(literal, 5), parsed, "Packing must retain every literal command and note")
        }
    }

    @Test fun `packed native playback preserves notes ties rests and effects within tiny DSP rounding differences`() {
        val parser = TestRomHelper.requireRomParser()
        val ram = SpcData.buildInitialSpcRam(parser)
        byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte()).copyInto(ram, 0x9000)
        byteArrayOf(0, 0x90.toByte(), 0, 0x90.toByte()).copyInto(ram, 0x6D00)
        for (slot in 24..27) byteArrayOf(0, 0, 0, 0x60, 1, 0).copyInto(ram, 0x6C00 + slot * 6)
        val song = NspcSequence.Song()
        for (ch in 0..2) repeat(16) { i ->
            val tick = i * 72
            song.channels[ch].notes += NspcSequence.Note(tick, 48, 0xA4 + (i % 4), instrument = 24 + ch)
            song.channels[ch].commands += NspcSequence.ControlCommand(tick, 0xED, intArrayOf(80 + (i % 4) * 20))
            song.channels[ch].commands += NspcSequence.ControlCommand(tick + 24, 0xE1, intArrayOf(8 + i % 4))
        }
        song.channels[3].notes += NspcSequence.Note(0, song.totalTicks, 0xA4, instrument = 24)
        fun render(compact: Boolean): ShortArray {
            val data = ram.copyOf()
            NspcSequence.encode(song, 5, data, failOnOverflow = true, conductorAddrOverride = 0x6000, sequenceEndAddr = 0x6C00, compact = compact)
                .forEach { (at, bytes) -> bytes.copyInto(data, at) }
            return NativeSpcEmulator().use { it.loadFromRam(data, emptyList(), 5, playbackWarmupSeconds = 0); it.renderMono(18) }
        }
        assertTrue(NspcSequence.encodingStats(song).savedBytes > 0)
        val literal = render(false)
        assertTrue(literal.any { it != 0.toShort() })
        val packed = render(true)
        val differences = literal.indices.map { kotlin.math.abs(literal[it].toInt() - packed[it].toInt()) }
        val rmsError = kotlin.math.sqrt(differences.sumOf { it.toDouble() * it } / differences.size)
        val rmsSignal = kotlin.math.sqrt(literal.sumOf { it.toDouble() * it } / literal.size)
        println("NATIVE AUDIO max=${differences.max()} rms=$rmsError signal=$rmsSignal first=${differences.indexOfFirst { it > 0 }}")
        // EF adds SPC instruction cycles before register writes; the expanded musical stream is exact.
        assertTrue(differences.max() <= 16 && rmsError <= 0.5, "Native audio changed beyond DSP rounding: max=${differences.max()}, rms=$rmsError")
    }

    @Test fun `one note title edits export without discarding notes or commands and leave sibling tracks intact`() {
        val parser = TestRomHelper.requireRomParser()
        val source = bank(parser, 3)
        val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val song = NspcSequence.parse(source, 5)
        song.channels.first { it.notes.isNotEmpty() }.notes.first().noteValue += 1
        val project = SmEditProject(romPath = "test.sfc")
        project.musicEdits[MusicEditConversion.key(3, 5)] = MusicEditConversion.toProjectEdit(track, song)
        val resolved = MusicBankResolver.resolve(parser, project, 3)
        val expected = NspcSequence.parse(resolved.ram, 5)
        assertEquals(song.channels.map { it.notes }, expected.channels.map { it.notes })
        assertEquals(NspcSequence.parse(source, 6), NspcSequence.parse(resolved.ram, 6))
        val rom = parser.copyRomData()
        assertTrue(MusicRomExporter(project, {}).applyMusicEditsToRom(rom) > 0)
        assertContentEquals(resolved.ram, bank(RomParser(rom), 3))
    }

    @Test fun `shortening the last held note is still a trimmed fit even when event counts do not change`() {
        val song = NspcSequence.Song().apply {
            channels[0].notes += NspcSequence.Note(0, 256, 0xA4, instrument = 24)
            channels[0].commands += NspcSequence.ControlCommand(24, 0xED, intArrayOf(100))
        }
        val partial = PianoRollPreviewLogic.deepCopySong(song).also { it.channels[0].notes[0].duration = 25 }
        val fit = MusicSequenceBudget.fitSongToEncodedBudget(song, NspcSequence.encodedSequenceSize(partial))
        assertEquals(0, fit.removedNotes); assertEquals(0, fit.removedCommands)
        assertTrue(fit.removedTicks > 0); assertTrue(fit.trimmed)
        val available = NspcSequence.encodedSequenceSize(partial)
        assertFailsWith<IllegalArgumentException> {
            MusicSequenceBudget.encodeRelocated(song, 5, ByteArray(65536), listOf(
                MusicSequenceBudget.SpcRange(0x582C, 0x6000), MusicSequenceBudget.SpcRange(0x6000 + available, 65536)), "complete")
        }
        assertEquals(256, song.channels[0].notes.single().duration)
    }

    @Test fun `exported packed arrangements upload and play on the real CPU APU across bank reentry`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc")
        val selected = listOf(3 to 5, 3 to 6, 0x0F to 5)
        for ((set, play) in selected) {
            val song = NspcSequence.parse(bank(parser, set), play)
            val note = song.channels.first { it.notes.isNotEmpty() }.notes.first()
            note.noteValue += if (note.noteValue == NspcSequence.NOTE_MAX) -1 else 1
            val track = SpcData.KNOWN_TRACKS.first { it.songSet == set && it.playIndex == play }
            project.musicEdits[MusicEditConversion.key(set, play)] = MusicEditConversion.toProjectEdit(track, song)
        }
        val expected = selected.map { it.first }.distinct().associateWith { MusicBankResolver.resolve(parser, project, it) }
        val rom = parser.copyRomData(); MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        NativeMusicUploadFixture(directory, rom).use { runtime ->
            for ((set, play) in selected + selected.reversed()) {
                runtime.queueBank(set)
                val actual = runtime.ram()
                for ((at, data) in expected.getValue(set).writes) assertTrue(data.contentEquals(actual.copyOfRange(at, at + data.size)),
                    "Actual CPU/APU uploaded packed bank $set at ${at.toString(16)}")
                assertEquals(NspcSequence.parse(expected.getValue(set).ram, play), NspcSequence.parse(actual, play))
                runtime.play(play)
                assertTrue(runtime.frames(180).any { it != 0.toShort() }, "Packed $set/$play must play after reentry")
            }
        }
    }
}
