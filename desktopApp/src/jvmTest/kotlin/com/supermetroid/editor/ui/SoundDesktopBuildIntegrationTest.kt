package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmProjectTestFixture
import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Exact Build/Run, source build and IPS publication paths, with all artifacts isolated in a temp project. */
class SoundDesktopBuildIntegrationTest {
    @TempDir lateinit var directory: File
    @Test fun `large native import new track bank sample effects and room assignment survive desktop source build reopen and IPS`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(directory)
        val parser = RomParser(fixture.rom)
        val original = fixture.rom.copyOf()
        val input = musicWithRetainedSampleSnapshot(parser, fixture.romFile.path)
        val source = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val creation = MusicCreation.plan(parser, input, "Custom room theme", 3, "Independent palette", source)
        val project = creation.after
        val target = SampleBankPreview.Target(creation.track.songSet, NspcSequence.Note(0, 48, 0xA4, instrument = 24),
            "Independent palette", "Sound", MusicBankTransitions.snapshot(project))
        val sample = MusicSampleBanks.plan(parser, target, SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE)), "Room sine")
        project.musicInstrumentBanks[MusicInstrumentBanks.key(creation.track.songSet)] = sample.bank
        val health = SoundEffects.catalog(SpcData.buildInitialSpcRam(parser)).first { it.identity.key == "2:01" }
        val voices = SoundEffectPrograms.capture(health)
        val quiet = SoundEffectPrograms.copyVoices(voices)
        for (voice in quiet) for (step in voice.steps.filter { it.opcode in 0..41 }) step.params[0] = 0
        project.soundEffectPrograms["2:80"] = SoundEffectProgram(2, 128, "My pickup", 1, voices, SoundEffectPrograms.copyVoices(voices))
        project.soundEffectPrograms["2:01"] = SoundEffectProgram(2, 1, "Quiet native pickup", 1, quiet, SoundEffectPrograms.copyVoices(voices))
        project.getOrCreateRoom(0x91F8).stateDataChange = StateDataChange(musicData = creation.track.songSet, musicTrack = creation.track.playIndex)
        val logs = mutableListOf<String>()
        fun build(owner: SmEditProject, path: String = "") = assertNotNull(ProjectFileService.buildRom(owner, parser, logs::add, logs::add, path), logs.takeLast(15).joinToString("\n"))
        val patched = build(ProjectFileService.snapshotProject(project))
        val partial = com.supermetroid.editor.asm.AsmSourceAssetMaterializer().materialize(fixture.projectFile.path, patched.writeReport)
        assertTrue(partial.isEmpty, "Sound is one post-compile transaction; partial asset claims: " + partial.claims.joinToString { "${it.assetPath}@${it.pcOffset.toString(16)}:${it.length}" })
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val sourceBuild = build(project, fixture.projectFile.path)
        fun verify(result: RomBuildResult) {
            val exported = RomParser(result.resultRom)
            assertTrue(SoundEffects.bootRam(parser, project).contentEquals(SpcData.buildInitialSpcRam(exported)))
            assertTrue(SpcTransferStream.read(exported, SpcData.readSongSetPointer(exported, 3)).romRanges.sumOf { it.count() } > 32768)
            for (set in listOf(3, creation.track.songSet)) {
                val actual = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, set)) }
                assertTrue(MusicBankResolver.resolve(parser, project, set).ram.contentEquals(actual), "Preview parity for bank $set")
            }
            val room = assertNotNull(exported.readRoomHeader(0x91F8))
            assertEquals(creation.track.songSet, room.musicData); assertEquals(creation.track.playIndex, room.musicTrack)
            assertTrue(result.writeReport.writes.any { it.owner == "music:project" && it.kind == RomWriteKind.MUSIC })
            assertTrue(result.musicPatched > 0)
        }
        verify(patched); verify(sourceBuild)
        assertTrue(ProjectFileService.saveProject(project, fixture.projectFile.path, null, false, logs::add))
        ProjectFileService.clearAsmBuildCachesForTests()
        val reopened = ProjectFileService.loadProject(fixture.projectFile)
        assertEquals(project.soundEffectPrograms, reopened.soundEffectPrograms)
        val coldBuild = build(reopened, fixture.projectFile.path); verify(coldBuild)
        val ips = File(assertNotNull(ProjectFileService.exportToIps(reopened, parser, logs::add, logs::add, fixture.projectFile.path), logs.takeLast(15).joinToString("\n")))
        val reconstructed = original.copyOf()
        for (write in PatchRepository.parseIps(ips.readBytes())) write.bytes.forEachIndexed { i, value -> reconstructed[write.offset.toInt() + i] = value.toByte() }
        assertTrue(reconstructed.contentEquals(coldBuild.resultRom), "IPS must reproduce the exact checked desktop ROM")
        assertTrue(original.contentEquals(parser.getRomData())); assertTrue(original.contentEquals(fixture.romFile.readBytes()))
        NativeMusicUploadFixture(File(directory, "native-desktop"), reconstructed).use { runtime ->
            runtime.queueBank(creation.track.songSet)
            runtime.effect(2, 1); assertTrue(runtime.frames(180).all { it == 0.toShort() }, "Existing game cue must use its independent replacement")
            runtime.effect(2, 128); assertTrue(runtime.frames(180).any { it != 0.toShort() }, "Additional effect must remain independently audible")
            runtime.play(creation.track.playIndex)
            assertTrue(runtime.frames(180).any { it != 0.toShort() }, "New room theme and custom sample must play in game")
            runtime.queueBank(3); runtime.play(5); assertTrue(runtime.frames(180).any { it != 0.toShort() })
        }
    }
}
