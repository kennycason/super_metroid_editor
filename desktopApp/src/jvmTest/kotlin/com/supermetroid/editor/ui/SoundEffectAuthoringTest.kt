package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class SoundEffectAuthoringTest {
    @TempDir lateinit var directory: File
    private fun program(ram: ByteArray, library: Int = 2, source: Int = 1): SoundEffectProgram {
        val effect = SoundEffects.catalog(ram).first { it.identity.library == library && it.identity.id == source }
        val voices = SoundEffectPrograms.capture(effect)
        return SoundEffectProgram(library, SoundEffectPrograms.count(library) + 1, "My effect", source, voices, SoundEffectPrograms.copyVoices(voices))
    }
    @Test fun `all stock voices can be captured without changing any instruction bytes`() {
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        val failures = mutableListOf<String>()
        for (effect in SoundEffects.catalog(ram)) for ((index, voice) in SoundEffectPrograms.capture(effect).withIndex()) {
            runCatching { SoundEffectPrograms.encode(voice) }.onSuccess { bytes ->
                val original = effect.voices[index].address
                assertContentEquals(ram.copyOfRange(original, original + bytes.size), bytes)
            }.onFailure { failures += "${effect.identity.key}/$index: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun `new effect ID has independent bytes audible native routing and exact exported boot RAM`() {
        val parser = TestRomHelper.requireRomParser()
        val original = SpcData.buildInitialSpcRam(parser)
        val copy = program(original)
        val project = SmEditProject(romPath = "test.sfc", soundEffectPrograms = mutableMapOf("2:80" to copy))
        val ram = SoundEffects.bootRam(parser, project)
        SoundEffects.requireEngine(ram)
        assertEquals(241, SoundEffects.catalog(ram, project).size)
        assertEquals("My effect", SoundEffects.catalog(ram, project).first { it.identity.key == "2:80" }.identity.name)
        val before = SoundEffects.catalog(original).first { it.identity.key == "2:01" }
        val after = SoundEffects.catalog(ram).first { it.identity.key == "2:01" }
        assertEquals(before, after)
        val audio = NativeSpcEmulator().use { it.loadFromRam(ram, emptyList(), 0, playbackWarmupSeconds = 0); it.playSoundEffect(2, 128); it.renderMono(3) }
        assertTrue(audio.any { it != 0.toShort() }, "New ID must route through its copied native configuration")
        val rom = parser.copyRomData(); assertTrue(MusicRomExporter(project, {}).applyMusicEditsToRom(rom) > 0)
        assertTrue(ram.contentEquals(SpcData.buildInitialSpcRam(RomParser(rom))), "Boot splice must match native preview RAM")
        NativeMusicUploadFixture(directory, rom).use { runtime ->
            for (set in listOf(3, 0x0F, 6, 3)) runtime.queueBank(set)
            runtime.effect(2, 128)
            assertTrue(runtime.frames(180).any { it != 0.toShort() }, "Actual CPU boot/upload and custom dispatch must work")
        }
    }
    @Test fun `native cue can be detached without changing shared aliases and survives save reopen`() {
        val parser = TestRomHelper.requireRomParser()
        val source = SpcData.buildInitialSpcRam(parser)
        val effect = SoundEffects.catalog(source).first { it.identity.key == "2:01" }
        val voices = SoundEffectPrograms.capture(effect)
        for (voice in voices) for (step in voice.steps.filter { it.opcode in 0..41 }) step.params[0] = 0
        val owned = SoundEffectProgram(2, 1, "Quiet pickup", 1, voices, SoundEffectPrograms.copyVoices(voices))
        val project = SmEditProject(romPath = "test.sfc", soundEffectPrograms = mutableMapOf("2:01" to owned))
        val ram = SoundEffects.bootRam(parser, project)
        val pcm = NativeSpcEmulator().use { it.loadFromRam(ram, emptyList(), 0, playbackWarmupSeconds = 0); it.playSoundEffect(2, 1); it.renderMono(3) }
        assertTrue(pcm.all { it == 0.toShort() })
        assertEquals(240, SoundEffects.catalog(ram).size)
        val file = File(directory, "programs.smedit")
        assertTrue(ProjectFileService.saveProject(project, file.path, null, false, {}))
        assertEquals(project.soundEffectPrograms, ProjectFileService.loadProject(file).soundEffectPrograms)
        val rom = parser.copyRomData(); MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        assertTrue(ram.contentEquals(SpcData.buildInitialSpcRam(RomParser(rom))))
    }
    @Test fun `invalid prefix flow zero time cycles oversized voices and memory collisions fail before ROM mutation`() {
        fun voice(vararg op: SoundEffectInstruction) = SoundEffectVoiceProgram(op.toMutableList())
        fun step(op: Int, vararg params: Int) = SoundEffectInstruction(op, params.toMutableList())
        assertFails { SoundEffectPrograms.encode(voice(step(0xFE, 2), step(0xFB), step(0xFF))) }
        assertFails { SoundEffectPrograms.encode(voice(step(0xF8, 32, 0xA4), step(0xF9, 0x8F, 0xE0), step(0xFF))) }
        val parser = TestRomHelper.requireRomParser()
        val p = program(SpcData.buildInitialSpcRam(parser))
        p.voices[0].steps.addAll(0, List(60) { step(0, 100, 10, 0xA4, 10) })
        val project = SmEditProject(romPath = "test.sfc", soundEffectPrograms = mutableMapOf("2:80" to p))
        val rom = parser.copyRomData()
        assertFails { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertTrue(rom.contentEquals(parser.getRomData()))
    }
    @Test fun `installed source reexports overrides reuses loader and survives native soft reset`() {
        val parser = TestRomHelper.requireRomParser()
        val p = program(SpcData.buildInitialSpcRam(parser))
        val project = SmEditProject(romPath = "test.sfc", soundEffectPrograms = mutableMapOf("2:80" to p))
        val rom = parser.copyRomData(); MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        val source = RomParser(rom)
        val pointer = SpcTransferStream.installedCodePointer(source)
        val definitions = SoundEffectPrograms.definitions(SpcData.buildInitialSpcRam(source))
        val imported = SoundEffectPrograms.copy(definitions.getValue("2:80"))
        for (voice in imported.voices) for (step in voice.steps.filter { it.opcode in 0..41 }) step.params[0] = 0
        val next = project.copy(soundEffectPrograms = mutableMapOf("2:80" to imported))
        MusicRomExporter(next, {}).applyMusicEditsToRom(rom)
        assertEquals(pointer, SpcTransferStream.installedCodePointer(RomParser(rom)))
        assertTrue(SoundEffects.bootRam(source, next).contentEquals(SpcData.buildInitialSpcRam(RomParser(rom))))
        NativeMusicUploadFixture(File(directory, "reopened"), rom).use { runtime ->
            runtime.effect(2, 128); assertTrue(runtime.frames(180).all { it == 0.toShort() })
            runtime.effect(2, 1); assertTrue(runtime.frames(180).any { it != 0.toShort() }, "Native control remains audible")
            assertTrue(runtime.softResetToTitle().any { it != 0.toShort() }, "Boot splice must survive the original title reset")
            val reset = runtime.ram()
            for (range in SoundEffectPrograms.arenas) assertTrue(reset.copyOfRange(range.first, range.last + 1).contentEquals(SpcData.buildInitialSpcRam(RomParser(rom)).copyOfRange(range.first, range.last + 1)))
        }
    }
    @Test fun `global arena exhaustion and conflicting project sample roll back without touching ROM`() {
        val parser = TestRomHelper.requireRomParser(); val ram = SpcData.buildInitialSpcRam(parser)
        val route = SoundEffects.identities.first { it.library == 1 && it.voices == 1 }
        val programs = (67..70).associate { id ->
            val voice = SoundEffectVoiceProgram((List(30) { SoundEffectInstruction(0, mutableListOf(id, 10, 0xA4, 2)) } + SoundEffectInstruction(0xFF)).toMutableList())
            val p = SoundEffectProgram(1, id, "Large $id", route.id, mutableListOf(voice), SoundEffectPrograms.copyVoices(listOf(voice)))
            SoundEffectPrograms.key(1, id) to p
        }.toMutableMap()
        val rom = parser.copyRomData()
        val full = SmEditProject(romPath = "test.sfc", soundEffectPrograms = programs)
        assertTrue(assertFails { MusicRomExporter(full, {}).applyMusicEditsToRom(rom) }.message.orEmpty().contains("memory is full"))
        assertTrue(rom.contentEquals(parser.getRomData()))
        val project = full.copy(soundEffectPrograms = mutableMapOf("2:80" to program(ram)))
        project.musicEdits["03:05"] = MusicTrackEdit(songSet = 3, playIndex = 5, trackName = "Collision", tempo = 120,
            nativePayload = MusicNativePayloadEdit(baseline = MusicNativeBaselineEdit(120), blocks = mutableListOf(MusicTransferBlockEdit(0x56E2, java.util.Base64.getEncoder().encodeToString(byteArrayOf(0))))))
        assertFails { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertTrue(rom.contentEquals(parser.getRomData()))
        project.musicEdits.clear()
        val encoded = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE, period = 32))
        val slot = SpcData.STOCK_SAMPLE_COUNT
        project.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(samples = mutableMapOf(slot to MusicSampleEdit(slot, 0x6B27,
            java.util.Base64.getEncoder().encodeToString(encoded.brr), encoded.loopByteOffset, "Conflicting sample")))
        val collision = assertFails { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }.message.orEmpty()
        assertTrue(collision.contains("project sample owns effect memory"), collision)
        assertTrue(rom.contentEquals(parser.getRomData()))
    }

    @Test fun `maximum effect IDs in every library execute after native bank transitions and retain stock routes`() {
        val parser = TestRomHelper.requireRomParser(); val ram = SpcData.buildInitialSpcRam(parser)
        val root = program(ram)
        val copies = (1..3).associate { lib ->
            val route = SoundEffects.identities.first { it.library == lib && it.voices == root.voices.size }
            val p = root.copy(library = lib, id = 128, routingId = route.id, voices = SoundEffectPrograms.copyVoices(root.voices))
            SoundEffectPrograms.key(lib, 128) to p
        }
        val project = SmEditProject(romPath = "test.sfc", soundEffectPrograms = copies.toMutableMap())
        val rom = parser.copyRomData(); MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        NativeMusicUploadFixture(File(directory, "all-libraries"), rom).use { runtime ->
            for (set in listOf(0x0F, 6, 3)) {
                runtime.queueBank(set)
                for (lib in 1..3) {
                    runtime.effect(lib, 128)
                    assertTrue(runtime.frames(180).any { it != 0.toShort() }, "Library $lib ID 128 in bank $set must be audible")
                }
            }
        }
    }
    @Test fun `custom effects new bank new track and an authored sample share one atomic production export`() {
        val parser = TestRomHelper.requireRomParser(); val ram = SpcData.buildInitialSpcRam(parser)
        val project = SmEditProject(romPath = "test.sfc", soundEffectPrograms = mutableMapOf("2:80" to program(ram)))
        val target = SampleBankPreview.Target(3, NspcSequence.Note(0, 48, 0xA4, instrument = 24), "Title", "Sound", MusicBankTransitions.snapshot(project))
        val sample = MusicSampleBanks.plan(parser, target, SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE)), "Sine")
        project.musicInstrumentBanks["03"] = sample.bank
        val source = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val created = MusicCreation.plan(parser, project, "Custom theme", 3, "My palette", source)
        val rom = parser.copyRomData(); MusicRomExporter(created.after, {}).applyMusicEditsToRom(rom)
        val exported = RomParser(rom)
        assertTrue(SoundEffects.bootRam(parser, created.after).contentEquals(SpcData.buildInitialSpcRam(exported)))
        val expected = MusicBankResolver.resolve(parser, created.after, created.track.songSet).ram
        val loaded = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, created.track.songSet)) }
        assertTrue(expected.contentEquals(loaded), "Resources and authored arrangement must match preview")
        NativeMusicUploadFixture(File(directory, "combined"), rom).use { runtime ->
            runtime.queueBank(created.track.songSet); runtime.play(created.track.playIndex)
            assertTrue(runtime.frames(180).any { it != 0.toShort() })
            runtime.play(0); runtime.effect(2, 128)
            assertTrue(runtime.frames(180).any { it != 0.toShort() })
            runtime.queueBank(3); runtime.effect(2, 1)
            assertTrue(runtime.frames(180).any { it != 0.toShort() })
        }
    }

    @Test fun `headered ROM boot splice preserves header while exhausted allocation cannot publish effects`() {
        val original = TestRomHelper.requireRomBytes(); val header = ByteArray(512) { 0x5A }
        val rom = header + original; val parser = RomParser(rom)
        val project = SmEditProject(romPath = "source.smc", soundEffectPrograms = mutableMapOf("2:80" to program(SpcData.buildInitialSpcRam(parser))))
        val blocked = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes, excludedRanges = listOf(rom.indices))
        assertFails { MusicRomExporter(project, {}).applyMusicEditsToRom(rom, blocked) }
        assertTrue(rom.contentEquals(header + original))
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        assertContentEquals(header, rom.copyOfRange(0, 512))
        assertTrue(SoundEffects.bootRam(RomParser(header + original), project).contentEquals(SpcData.buildInitialSpcRam(RomParser(rom))))
        val at = SoundEffectPrograms.installed(SpcData.buildInitialSpcRam(RomParser(rom)))!!.stubs.getValue(2)
        val corrupt = SpcData.buildInitialSpcRam(RomParser(rom)).also { it[at] = 0 }
        assertFails { SoundEffects.requireEngine(corrupt) }
    }

}
