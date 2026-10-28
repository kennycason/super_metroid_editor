package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class MusicSampleRestorationTest {
    private fun word(ram: ByteArray, at: Int, value: Int) { ram[at] = value.toByte(); ram[at + 1] = (value ushr 8).toByte() }
    private fun recording(ram: ByteArray, slot: Int = 0, start: Int = 0x6000, loop: Int = start) {
        word(ram, 0x6D00 + slot * 4, start); word(ram, 0x6D02 + slot * 4, loop)
        ram[start] = 3
        if (loop != start) ram[loop] = 3
    }
    private fun derive(initial: ByteArray, banks: Map<Int, MusicSampleRestoration.Bank>, vararg needs: MusicSampleRestoration.Need) =
        MusicSampleRestoration.derive(initial, banks, needs.toList())
    private fun bank(ram: ByteArray, vararg uploads: SpcData.TransferBlock) = MusicSampleRestoration.Bank(ram, uploads.toList())
    private fun apply(ram: ByteArray, writes: Map<Int, ByteArray>) { writes.forEach { (at, bytes) -> bytes.copyInto(ram, at) } }

    @Test fun `single byte requests restore complete native directory instrument and BRR units only`() {
        val ram = ByteArray(65536).also { recording(it); it[0x6001] = 42 }
        val plan = derive(ram, mapOf(3 to bank(ram)),
            MusicSampleRestoration.Need(3, 0x6001, 0x6002), MusicSampleRestoration.Need(3, 0x6D01, 0x6D02),
            MusicSampleRestoration.Need(3, 0x6C05, 0x6C06))
        val writes = plan.writes.getValue(3)
        assertEquals(setOf(0x6000, 0x6C00, 0x6D00), writes.keys)
        assertEquals(listOf(9, 6, 4), writes.values.map { it.size })
        assertEquals(19, plan.payloadBytes); assertEquals(36, plan.prefixBytes)
        writes.forEach { (at, data) -> assertContentEquals(ram.copyOfRange(at, at + data.size), data) }
    }

    @Test fun `restoring a complete block closes over a third bank beyond the originally changed byte`() {
        val initial = ByteArray(65536).also { recording(it) }
        val second = initial.copyOf().also { it[0x6001] = 11 }
        val third = initial.copyOf().also { it[0x6002] = 22 }
        val plan = derive(initial, mapOf(3 to bank(initial), 6 to bank(second), 9 to bank(third)),
            MusicSampleRestoration.Need(6, 0x6001, 0x6002))
        assertEquals(setOf(3, 6, 9), plan.writes.keys)
        // All three streams must have their own bytes, not the first source's baseline.
        for ((set, expected) in mapOf(3 to initial, 6 to second, 9 to third)) {
            assertContentEquals(expected.copyOfRange(0x6000, 0x6009), plan.writes.getValue(set).getValue(0x6000))
        }
        val retained = third.copyOf()
        for (set in listOf(6, 9, 3, 9, 6, 3)) {
            apply(retained, plan.writes.getValue(set))
            assertContentEquals(mapOf(3 to initial, 6 to second, 9 to third).getValue(set).copyOfRange(0x6000, 0x6009),
                retained.copyOfRange(0x6000, 0x6009))
        }
    }

    @Test fun `complete incoming uploads avoid redundant resets but partial coverage does not`() {
        val initial = ByteArray(65536).also { recording(it) }
        val changed = initial.copyOf().also { it[0x6001] = 11; it[0x6002] = 22 }
        val plan = derive(initial, mapOf(3 to bank(changed),
            6 to bank(initial, SpcData.TransferBlock(0x6000, initial.copyOfRange(0x6000, 0x6009))),
            9 to bank(initial, SpcData.TransferBlock(0x6001, byteArrayOf(0)))), MusicSampleRestoration.Need(3, 0x6001, 0x6002))
        assertEquals(setOf(3, 9), plan.writes.keys)
        assertEquals(9, plan.writes.getValue(9).getValue(0x6000).size)
    }

    @Test fun `linked loop chains and aliases restore blocks relative to each stream start`() {
        val initial = ByteArray(65536).also {
            recording(it, start = 0x6000, loop = 0x6100)
            recording(it, slot = 1, start = 0x6100)
            it[0x6100] = 0; it[0x6109] = 3
        }
        val plan = derive(initial, mapOf(3 to bank(initial)), MusicSampleRestoration.Need(3, 0x610A, 0x610B))
        assertEquals(setOf(0x6109), plan.writes.getValue(3).keys)
        assertEquals(9, plan.payloadBytes)
        assertContentEquals(initial.copyOfRange(0x6109, 0x6112), plan.writes.getValue(3).getValue(0x6109))
    }

    @Test fun `invalid unterminated engine table and unowned requests fail closed`() {
        val initial = ByteArray(65536).also { recording(it) }
        for (at in listOf(-1, 65536, 0x5000, 0x6CFF, 0x6DA0, 0x6200)) {
            assertFails { derive(initial, mapOf(3 to bank(initial)), MusicSampleRestoration.Need(3, at, at + 1)) }
        }
        for (start in listOf(0xFFFF, 0x6500, 0x5800, 0x6C00)) {
            val invalid = ByteArray(65536).also { word(it, 0x6D00, start) }
            if (start == 0x5800) invalid[0x6C00] = 1 // A terminated stream still cannot cross the native tables.
            val at = if (start == 0x6C00) 0x6E00 else start
            assertFails { derive(invalid, mapOf(3 to bank(invalid)), MusicSampleRestoration.Need(3, at, at + 1)) }
        }
        assertFails { MusicSampleRestoration.derive(initial, mapOf(3 to bank(initial)), listOf(MusicSampleRestoration.Need(3, 0x6001, 0x6002)),
            listOf(MusicSequenceBudget.SpcRange(0x6000, 0x6010))) }
        assertFails { MusicSampleRestoration.derive(initial, mapOf(3 to bank(initial)), listOf(MusicSampleRestoration.Need(3, 0x6D02, 0x6D03)),
            listOf(MusicSequenceBudget.SpcRange(0x6D00, 0x6D01))) }
        val crossing = ByteArray(65536).also { recording(it, start = 0x6BFC) }
        assertFails { derive(crossing, mapOf(3 to bank(crossing)), MusicSampleRestoration.Need(3, 0x6BFC, 0x6BFD)) }
        assertFails { derive(initial, mapOf(3 to bank(initial, SpcData.TransferBlock(65535, byteArrayOf(1, 2)))) ) }
        assertFails { derive(initial, mapOf(3 to bank(initial)), MusicSampleRestoration.Need(6, 0x6000, 0x6001)) }
    }

    @Test fun `restoration is deterministic detached and never claims sample or instrument slots`() {
        val initial = ByteArray(65536).also { recording(it) }
        val before = initial.copyOf()
        val banks = mapOf(6 to bank(initial), 3 to bank(initial))
        val needs = listOf(MusicSampleRestoration.Need(6, 0x6001, 0x6003), MusicSampleRestoration.Need(3, 0x6D01, 0x6D03))
        val plan = MusicSampleRestoration.derive(initial, banks, needs)
        val again = MusicSampleRestoration.derive(initial, banks.toList().reversed().toMap(), needs.reversed())
        for ((set, writes) in plan.writes) for ((at, bytes) in writes) assertContentEquals(bytes, again.writes.getValue(set).getValue(at))
        assertContentEquals(before, initial)
        initial.fill(99)
        assertEquals(3, plan.writes.getValue(6).getValue(0x6000)[0].toInt())
        assertEquals(setOf(0x6000), plan.writes.getValue(6).keys)
        assertEquals(setOf(0x6D00), plan.writes.getValue(3).keys)
    }

    @Test fun `all native destinations get compact sample restores while actual export remains gated`() {
        val parser = TestRomHelper.requireRomParser()
        val original = parser.copyRomData()
        val project = SmEditProject(romPath = "test.sfc")
        val ram = MusicBankResolver.sourceRam(parser, project, 3)
        val writes = mapOf(0x6E01 to byteArrayOf((ram[0x6E01].toInt() xor 1).toByte()),
            0x6D01 to byteArrayOf((ram[0x6D01].toInt() xor 1).toByte()))
        val changed = ram.copyOf().also { apply(it, writes) }
        val resolved = mapOf(3 to MusicBankResolver.Resolved(changed, writes, 1, true))
        val report = MusicBankTransitions.inspect(parser, project, resolved)
        assertFalse(report.canExport)
        val plan = MusicSampleRestoration.plan(parser, project, resolved)
        assertEquals(SpcData.TRANSFER_SONG_SETS.filter { it > 0 && it != 3 }.toSet(), plan.writes.keys)
        assertTrue(plan.prefixBytes < 1024, "Do not duplicate full recordings or music banks")
        for ((set, restores) in plan.writes) {
            val expected = MusicBankResolver.sourceRam(parser, project, set)
            assertEquals(setOf(0x6D00, 0x6E00), restores.keys)
            val retained = changed.copyOf().also { apply(it, restores); SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, set)) }
            assertContentEquals(expected.copyOfRange(0x6D00, 0x6D04), retained.copyOfRange(0x6D00, 0x6D04))
            assertContentEquals(expected.copyOfRange(0x6E00, 0x6E09), retained.copyOfRange(0x6E00, 0x6E09))
        }
        // No prototype is wired into the production export path or saved project.
        assertFailsWith<IllegalStateException> { report.requireSafe() }
        assertTrue(project.musicEdits.isEmpty()); assertTrue(project.musicInstrumentBanks.isEmpty())
        assertContentEquals(original, parser.copyRomData())
    }

    @Test fun `unknown loaders and custom engines cannot receive sample restoration permission`() {
        val base = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc")
        val ram = MusicBankResolver.sourceRam(base, project, 3)
        val engine = ram.copyOf().also { it[0x1500] = (it[0x1500].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { MusicSampleRestoration.plan(base, project,
            mapOf(3 to MusicBankResolver.Resolved(engine, mapOf(0x1500 to byteArrayOf(engine[0x1500])), 1, true))) }
        val rom = base.copyRomData().also { it[base.snesToPc(0x8080C4)] = 0 }
        val changed = ram.copyOf().also { it[0x6E01] = (it[0x6E01].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { MusicSampleRestoration.plan(RomParser(rom), project,
            mapOf(3 to MusicBankResolver.Resolved(changed, mapOf(0x6E01 to byteArrayOf(changed[0x6E01])), 1, true))) }
    }

    @Test fun `incoming dependency repair handles a retained recording overwritten by an untouched native bank`() {
        val base = TestRomHelper.requireRomParser()
        val rom = base.copyRomData()
        val initial = SpcData.buildInitialSpcRam(base)
        // A native destination that overwrites one common BRR byte, but no instrument
        // or DIR bytes. The edited bank must reload its new dependency on entry.
        val raw = byteArrayOf(1, 0, 1, 0x6E, (initial[0x6E01].toInt() xor 1).toByte(), 0, 0, 0, 0x15)
        val allocation = assertNotNull(RomFreeSpaceAllocator(rom, base::snesToPc, base::pcToSnes)
            .allocate(raw, listOf(0xB8), "incoming dependency fixture", 2))
        val table = SpcData.findSongSetPointerEntryPc(base, 0x0F)
        repeat(3) { rom[table + it] = (allocation.snesAddress ushr (it * 8)).toByte() }
        val parser = RomParser(rom)
        val project = SmEditProject(romPath = "test.sfc")
        val old = NspcRenderer.readInstrumentTable(MusicBankResolver.sourceRam(parser, project, 3))[0]
        project.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(mutableMapOf(0 to MusicInstrumentBanks.entry(old.copy(gain = old.gain xor 1))))
        val report = MusicBankTransitions.inspect(parser, project)
        assertTrue(report.issues.any { it.sourceSongSet == 0x0F && it.targetSongSet == 3 && it.resource == MusicBankTransitions.Resource.SAMPLE })
        val plan = MusicSampleRestoration.plan(parser, project)
        assertContentEquals(initial.copyOfRange(0x6E00, 0x6E09), plan.writes.getValue(3).getValue(0x6E00))
        val retained = MusicBankResolver.sourceRam(parser, project, 0x0F)
        apply(retained, plan.writes.getValue(3))
        SpcData.applyTransferBlocks(retained, SpcData.findSongSetTransferData(parser, 3))
        assertContentEquals(initial.copyOfRange(0x6E00, 0x6E09), retained.copyOfRange(0x6E00, 0x6E09))
    }

    @Test fun `oversized restoration is refused instead of truncating or expanding the ROM`() {
        val ram = ByteArray(65536)
        word(ram, 0x6D00, 0x7000)
        ram[65536 - 9] = 1
        assertTrue(assertFailsWith<IllegalArgumentException> {
            derive(ram, mapOf(3 to bank(ram)), MusicSampleRestoration.Need(3, 0x7000, 65536))
        }.message.orEmpty().contains("single ROM bank"))
    }

    @Test fun `detached proof does not enable sample restoration in production ROM export`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc")
        val song = NspcSequence.Song(tempo = 36).also {
            it.channels[0].notes += NspcSequence.Note(0, 48, NspcSequence.nameToNote("C4"), instrument = 0)
        }
        val initial = SpcData.buildInitialSpcRam(parser)
        val blocks = NspcSequence.encode(song, 5, conductorAddrOverride = 0x6500).map { (at, data) -> SpcData.TransferBlock(at, data) } +
            SpcData.TransferBlock(0x6E01, byteArrayOf((initial[0x6E01].toInt() xor 1).toByte()))
        val native = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(blocks, 5, "test.nspc", "N-SPC"), song)
        val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        project.musicEdits["03:05"] = MusicEditConversion.toProjectEdit(track, song, native)
        val before = MusicBankTransitions.snapshot(project)
        assertTrue(MusicSampleRestoration.plan(parser, project).writes.isNotEmpty())
        val rom = parser.copyRomData()
        assertTrue(assertFailsWith<IllegalStateException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }.message.orEmpty().contains("not restored"))
        assertContentEquals(parser.getRomData(), rom)
        assertEquals(before.musicEdits, project.musicEdits)
        assertEquals(before.musicInstrumentBanks, project.musicInstrumentBanks)
    }

    @Test fun `bank snapshots must match their actual uploads before claiming restoration is possible`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc")
        assertTrue(MusicSampleRestoration.plan(parser, project).writes.isEmpty())
        val ram = MusicBankResolver.sourceRam(parser, project, 3).also { it[0x6E01] = (it[0x6E01].toInt() xor 1).toByte() }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            MusicSampleRestoration.plan(parser, project, mapOf(3 to MusicBankResolver.Resolved(ram, emptyMap(), 1, true)))
        }.message.orEmpty().contains("does not match its uploads"))
    }
}
