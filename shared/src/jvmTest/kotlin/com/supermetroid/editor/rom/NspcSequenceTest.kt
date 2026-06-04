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

    @Test
    fun `parse expands EF subroutine notes instead of storing stale flow command`() {
        val spcRam = ByteArray(65536)
        val conductorAddr = 0x5830
        val blockTableAddr = 0x5840
        val channelAddr = 0x5850
        val subroutineAddr = 0x5900

        spcRam[0x581E] = (conductorAddr and 0xFF).toByte()
        spcRam[0x581F] = ((conductorAddr shr 8) and 0xFF).toByte()
        spcRam[conductorAddr] = (blockTableAddr and 0xFF).toByte()
        spcRam[conductorAddr + 1] = ((blockTableAddr shr 8) and 0xFF).toByte()
        spcRam[conductorAddr + 2] = 0
        spcRam[conductorAddr + 3] = 0
        spcRam[blockTableAddr] = (channelAddr and 0xFF).toByte()
        spcRam[blockTableAddr + 1] = ((channelAddr shr 8) and 0xFF).toByte()

        spcRam[channelAddr] = 24
        spcRam[channelAddr + 1] = 0x7F
        spcRam[channelAddr + 2] = 0x94.toByte()
        spcRam[channelAddr + 3] = 0xEF.toByte()
        spcRam[channelAddr + 4] = (subroutineAddr and 0xFF).toByte()
        spcRam[channelAddr + 5] = ((subroutineAddr shr 8) and 0xFF).toByte()
        spcRam[channelAddr + 6] = 2
        spcRam[channelAddr + 7] = 0x98.toByte()
        spcRam[channelAddr + 8] = 0
        spcRam[subroutineAddr] = 0x9C.toByte()
        spcRam[subroutineAddr + 1] = 0

        val song = NspcSequence.parse(spcRam, 0)
        val notes = song.channels[0].notes

        assertEquals(listOf(0x94, 0x9C, 0x9C, 0x98), notes.map { it.noteValue })
        assertEquals(listOf(0, 24, 48, 72), notes.map { it.tick })
        assertTrue(song.channels[0].commands.none { it.command == 0xEF }, "EF should not be saved as an editable command")
    }

    @Test
    fun `encode ignores legacy EF flow-control commands`() {
        val spcRam = ByteArray(65536)
        val subroutineAddr = 0x5900
        spcRam[subroutineAddr] = 0x9C.toByte()
        spcRam[subroutineAddr + 1] = 0

        val song = NspcSequence.Song(tempo = 40)
        song.channels[0].commands += NspcSequence.ControlCommand(
            tick = 0,
            command = 0xEF,
            params = intArrayOf(subroutineAddr and 0xFF, (subroutineAddr shr 8) and 0xFF, 2)
        )
        song.channels[0].notes += NspcSequence.Note(tick = 0, duration = 24, noteValue = 0x94, velocity = 15, quantize = 7, instrument = 0)
        song.channels[0].notes += NspcSequence.Note(tick = 24, duration = 24, noteValue = 0x98, velocity = 15, quantize = 7, instrument = 0)

        for ((addr, data) in NspcSequence.encode(song, 0)) {
            data.copyInto(spcRam, addr)
        }

        val parsed = NspcSequence.parse(spcRam, 0)
        assertEquals(listOf(0x94, 0x98), parsed.channels[0].notes.map { it.noteValue })
        assertTrue(parsed.channels[0].commands.none { it.command == 0xEF })
    }

    @Test
    fun `encode does not reuse unsafe low block table pointer`() {
        val spcRam = ByteArray(RomConstants.SPC_RAM_SIZE)
        val playIndex = 6
        val conductorAddr = 0x5D9D
        val unsafeBlockTableAddr = 0x18F0
        val songTableEntry = 0x581E + playIndex * 2

        spcRam[songTableEntry] = (conductorAddr and 0xFF).toByte()
        spcRam[songTableEntry + 1] = ((conductorAddr shr 8) and 0xFF).toByte()
        spcRam[conductorAddr] = (unsafeBlockTableAddr and 0xFF).toByte()
        spcRam[conductorAddr + 1] = ((unsafeBlockTableAddr shr 8) and 0xFF).toByte()
        spcRam[conductorAddr + 2] = 0
        spcRam[conductorAddr + 3] = 0

        val song = NspcSequence.Song(tempo = 21)
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 24,
            noteValue = NspcSequence.nameToNote("C3"),
            velocity = 15,
            quantize = 7,
            instrument = 0x1E
        )

        val writes = NspcSequence.encode(song, playIndex, spcRam, failOnOverflow = true)

        assertFalse(writes.containsKey(unsafeBlockTableAddr), "Encoder must not write channel table to low SPC RAM")
        assertTrue(
            writes.all { (addr, data) -> addr >= 0x581E && addr + data.size <= 0x6C00 },
            "All re-encode writes must stay in song sequence RAM"
        )
    }

    @Test
    fun `encode round-trips notes with large gaps between them`() {
        val song = NspcSequence.Song(tempo = 40)
        song.channels[0].notes.addAll(listOf(
            NspcSequence.Note(tick = 0, duration = 24, noteValue = 0x94, velocity = 15, quantize = 7, instrument = 0),
            NspcSequence.Note(tick = 200, duration = 48, noteValue = 0x98, velocity = 15, quantize = 7, instrument = 0),
            NspcSequence.Note(tick = 500, duration = 24, noteValue = 0x9C, velocity = 12, quantize = 5, instrument = 0),
        ))

        val writes = NspcSequence.encode(song, 0)
        val spcRam = ByteArray(65536)
        for ((addr, data) in writes) {
            if (addr + data.size <= spcRam.size) data.copyInto(spcRam, addr)
        }

        val parsed = NspcSequence.parse(spcRam, 0)
        val ch0 = parsed.channels[0].notes
        assertEquals(3, ch0.size, "Should have 3 notes")
        assertEquals(0, ch0[0].tick)
        assertEquals(24, ch0[0].duration)
        assertEquals(0x94, ch0[0].noteValue)
        assertEquals(200, ch0[1].tick)
        assertEquals(48, ch0[1].duration)
        assertEquals(0x98, ch0[1].noteValue)
        assertEquals(500, ch0[2].tick)
        assertEquals(24, ch0[2].duration)
        assertEquals(0x9C, ch0[2].noteValue)
        assertEquals(12, ch0[2].velocity)
        assertEquals(5, ch0[2].quantize)
    }

    @Test
    fun `encode emits correct duration bytes after rest gaps`() {
        // Regression: emitRests must propagate its last duration back to the caller.
        // If a rest gap uses duration 73, the next note with duration 73 must NOT
        // skip emitting its duration+qv byte (they'd differ in quantize/velocity).
        val song = NspcSequence.Song(tempo = 40)
        song.channels[0].notes.addAll(listOf(
            NspcSequence.Note(tick = 0, duration = 73, noteValue = 0x94, velocity = 15, quantize = 7, instrument = 0),
            // Gap of 200 ticks: emitRests produces 127 + 73. Last rest duration = 73.
            NspcSequence.Note(tick = 273, duration = 73, noteValue = 0x98, velocity = 10, quantize = 4, instrument = 0),
        ))

        val writes = NspcSequence.encode(song, 0)
        val spcRam = ByteArray(65536)
        for ((addr, data) in writes) {
            if (addr + data.size <= spcRam.size) data.copyInto(spcRam, addr)
        }

        val parsed = NspcSequence.parse(spcRam, 0)
        val ch0 = parsed.channels[0].notes
        assertEquals(2, ch0.size, "Should have 2 notes")
        assertEquals(273, ch0[1].tick)
        assertEquals(73, ch0[1].duration)
        // The velocity/quantize must come through correctly even though duration
        // matches the last rest's duration
        assertEquals(10, ch0[1].velocity)
        assertEquals(4, ch0[1].quantize)
    }

    @Test
    fun `encode round-trips multi-channel song with commands`() {
        val song = NspcSequence.Song(tempo = 55)
        song.channels[0].notes.addAll(listOf(
            NspcSequence.Note(tick = 0, duration = 48, noteValue = 0x94, velocity = 15, quantize = 7, instrument = 5),
            NspcSequence.Note(tick = 48, duration = 48, noteValue = 0x96, velocity = 15, quantize = 7, instrument = 5),
        ))
        song.channels[1].notes.addAll(listOf(
            NspcSequence.Note(tick = 0, duration = 96, noteValue = 0x80, velocity = 12, quantize = 6, instrument = 3),
        ))

        val writes = NspcSequence.encode(song, 0)
        val spcRam = ByteArray(65536)
        for ((addr, data) in writes) {
            if (addr + data.size <= spcRam.size) data.copyInto(spcRam, addr)
        }

        val parsed = NspcSequence.parse(spcRam, 0)
        assertEquals(55, parsed.tempo)
        assertEquals(2, parsed.channels[0].notes.size)
        assertEquals(1, parsed.channels[1].notes.size)
        assertEquals(0x94, parsed.channels[0].notes[0].noteValue)
        assertEquals(0x96, parsed.channels[0].notes[1].noteValue)
        assertEquals(0x80, parsed.channels[1].notes[0].noteValue)
        assertEquals(96, parsed.channels[1].notes[0].duration)
    }

    @Test
    fun `encode failOnOverflow rejects sequence data crossing instrument table`() {
        val song = NspcSequence.Song(tempo = 40)
        repeat(3000) { i ->
            song.channels[0].notes.add(
                NspcSequence.Note(
                    tick = i * 24,
                    duration = 24 + (i % 48),
                    noteValue = 0x94,
                    velocity = 15,
                    quantize = 7,
                    instrument = 0x0B + (i % 20)
                )
            )
        }

        assertThrows(IllegalStateException::class.java) {
            NspcSequence.encode(song, 0, failOnOverflow = true)
        }

        val writes = NspcSequence.encode(song, 0)
        assertTrue(writes.all { (addr, data) -> addr < 0x6C00 && addr + data.size <= 0x6C00 })
    }

    @Test
    fun `Lower Norfair one note edit refuses native encode overflow`() {
        val parser = loadTestRom() ?: return
        val spcRam = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, 0x18)
        SpcData.applyTransferBlocks(spcRam, blocks)

        val song = NspcSequence.parse(spcRam, 5)
        val originalNotes = song.channels.sumOf { it.notes.size }
        assertTrue(originalNotes > 4000, "Lower Norfair should parse as a large song, got $originalNotes notes")

        song.channels[0].notes += NspcSequence.Note(
            tick = 84,
            duration = 96,
            noteValue = NspcSequence.nameToNote("G#1"),
            velocity = 15,
            quantize = 7,
            instrument = 0x1B
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            NspcSequence.encode(song, 5, spcRam, failOnOverflow = true)
        }
        assertTrue(
            ex.message?.contains("overflow into instrument table") == true,
            "Expected instrument table overflow message, got: ${ex.message}"
        )

        val truncatedWrites = NspcSequence.encode(song, 5, spcRam, failOnOverflow = false)
        assertTrue(
            truncatedWrites.all { (addr, data) -> addr < 0x6C00 && addr + data.size <= 0x6C00 },
            "Non-fail-fast encode must still avoid writing into instrument table"
        )
    }
}
