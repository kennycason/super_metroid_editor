package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NspcSequenceTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `parse Title Screen song has notes across multiple channels`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, 0x03)
        SpcData.applyTransferBlocks(baseRam, blocks)

        // Title Screen uses play index 5 (0x05)
        val song = NspcSequence.parse(baseRam, 5)
        assertTrue(song.totalTicks > 0, "Song should have ticks")

        val totalNotes = song.channels.sumOf { it.notes.size }
        assertTrue(totalNotes > 10, "Should have at least 10 notes, got $totalNotes")

        val activeChannels = song.channels.count { it.notes.isNotEmpty() }
        assertTrue(activeChannels >= 2, "Should have at least 2 active channels, got $activeChannels")

        System.err.println("[TEST] Title Screen: $totalNotes notes, $activeChannels channels, " +
            "${song.totalTicks} ticks, tempo=${song.tempo}")
        for (ch in 0 until 8) {
            val notes = song.channels[ch].notes
            if (notes.isNotEmpty()) {
                val pitchRange = "${NspcSequence.noteToName(notes.minOf { it.noteValue })}-${NspcSequence.noteToName(notes.maxOf { it.noteValue })}"
                System.err.println("[TEST]   Ch $ch: ${notes.size} notes, range $pitchRange")
            }
        }
    }

    @Test
    fun `noteToName and nameToNote round-trip`() {
        assertEquals("C1", NspcSequence.noteToName(0x80))
        assertEquals("C#1", NspcSequence.noteToName(0x81))
        assertEquals("B6", NspcSequence.noteToName(0xC7))
        assertEquals("TIE", NspcSequence.noteToName(0xC8))
        assertEquals("REST", NspcSequence.noteToName(0xC9))

        assertEquals(0x80, NspcSequence.nameToNote("C1"))
        assertEquals(0x8C, NspcSequence.nameToNote("C2"))
        assertEquals(0xC7, NspcSequence.nameToNote("B6"))

        // Round-trip all valid notes
        for (note in 0x80..0xC7) {
            val name = NspcSequence.noteToName(note)
            assertEquals(note, NspcSequence.nameToNote(name), "Round-trip failed for $name ($note)")
        }
    }

    @Test
    fun `encode then decode produces same notes`() {
        // Create a simple test song
        val song = NspcSequence.Song(tempo = 40)
        song.channels[0].notes.addAll(listOf(
            NspcSequence.Note(tick = 0, duration = 24, noteValue = 0x94, velocity = 15, quantize = 7, instrument = 0),
            NspcSequence.Note(tick = 24, duration = 24, noteValue = 0x98, velocity = 15, quantize = 7, instrument = 0),
            NspcSequence.Note(tick = 48, duration = 48, noteValue = 0x9C, velocity = 15, quantize = 7, instrument = 0),
        ))

        // Encode to SPC RAM writes
        val writes = NspcSequence.encode(song, 0)
        assertTrue(writes.isNotEmpty(), "Should produce writes")

        // Apply writes to a fresh SPC RAM and re-parse
        val spcRam = ByteArray(65536)
        for ((addr, data) in writes) {
            if (addr + data.size <= spcRam.size) {
                data.copyInto(spcRam, addr)
            }
        }

        val parsed = NspcSequence.parse(spcRam, 0)
        val ch0 = parsed.channels[0].notes
        assertEquals(3, ch0.size, "Should have 3 notes in channel 0")
        assertEquals(0x94, ch0[0].noteValue)
        assertEquals(0x98, ch0[1].noteValue)
        assertEquals(0x9C, ch0[2].noteValue)
        assertEquals(24, ch0[0].duration)
        assertEquals(24, ch0[1].duration)
        assertEquals(48, ch0[2].duration)
    }

    @Test
    fun `parse applies E0 instrument command to subsequent notes`() {
        val spcRam = ByteArray(65536)
        val conductorAddr = 0x5830
        val blockTableAddr = 0x5840
        val channelAddr = 0x5850

        spcRam[0x581E] = (conductorAddr and 0xFF).toByte()
        spcRam[0x581F] = ((conductorAddr shr 8) and 0xFF).toByte()

        spcRam[conductorAddr] = (blockTableAddr and 0xFF).toByte()
        spcRam[conductorAddr + 1] = ((blockTableAddr shr 8) and 0xFF).toByte()
        spcRam[conductorAddr + 2] = 0
        spcRam[conductorAddr + 3] = 0

        spcRam[blockTableAddr] = (channelAddr and 0xFF).toByte()
        spcRam[blockTableAddr + 1] = ((channelAddr shr 8) and 0xFF).toByte()

        spcRam[channelAddr] = 0xE0.toByte()
        spcRam[channelAddr + 1] = 0x0B
        spcRam[channelAddr + 2] = 24
        spcRam[channelAddr + 3] = 0x7F
        spcRam[channelAddr + 4] = 0x94.toByte()
        spcRam[channelAddr + 5] = 0x00

        val song = NspcSequence.parse(spcRam, 0)
        val note = song.channels[0].notes.single()
        assertEquals(0x0B, note.instrument)
        assertEquals(15, note.velocity)
        assertEquals(7, note.quantize)
        assertEquals(0x94, note.noteValue)
    }
}
