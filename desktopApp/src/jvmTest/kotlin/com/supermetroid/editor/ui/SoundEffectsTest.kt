package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class SoundEffectsTest {
    @TempDir lateinit var directory: File
    private fun editor(): Pair<RomParser, EditorState> {
        val file = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        return RomParser(file.readBytes()) to EditorState().apply { testMode = true; initForRom(file.path) }
    }
    @Test fun `all 240 identities parse complete native programs with bounded typed operands`() {
        val parser = TestRomHelper.requireRomParser()
        val effects = SoundEffects.catalog(SpcData.buildInitialSpcRam(parser))
        assertEquals(240, effects.size)
        assertEquals(listOf(66, 127, 47), (1..3).map { lib -> effects.count { it.identity.library == lib } })
        assertTrue(effects.all { it.problem == null }, effects.filter { it.problem != null }.joinToString { "${it.identity.key}: ${it.problem}" })
        assertTrue(effects.all { it.voices.size == it.identity.voices && it.voices.all { voice -> voice.steps.last().label == "End" } })
        assertTrue(effects.first { it.identity.key == "2:01" }.identity.name.contains("Health", true))
        assertTrue(effects.flatMap { it.voices }.flatMap { it.steps }.flatMap { it.operands }.all { it.address in 0x2AED..0x530F })
    }
    @Test fun `native effect previews are audible for all libraries and never mutate the project or music`() = runBlocking {
        val (parser, editor) = editor()
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SoundEffectEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.load(parser, editor)
        val before = MusicBankTransitions.snapshot(editor.project)
        for (key in listOf("1:37", "2:01", "3:05")) {
            state.select(key); state.preview(parser, editor)
            assertTrue(state.playing); assertTrue(clips.last().audioBytes!!.any { it != 0.toByte() }, "$key must be audible")
        }
        assertEquals(before.soundEffectEdits, editor.project.soundEffectEdits)
        assertEquals(before.musicEdits, editor.project.musicEdits); assertEquals(before.musicInstrumentBanks, editor.project.musicInstrumentBanks)
        assertEquals(0, state.undoDepth)
        state.stop(); assertTrue(clips.all { it.closed })
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.preview(parser, editor) }
        state.stop(); pending.join(); assertEquals(3, clips.size)
    }
    @Test fun `typed effect edits persist with undo and exact native preview and production export`() = runBlocking {
        val (parser, editor) = editor()
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SoundEffectEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.load(parser, editor)
        val levels = state.selected!!.voices.flatMap { it.steps }.flatMap { it.operands }.filter { it.field == SoundEffects.Field.LEVEL }
        assertTrue(levels.isNotEmpty())
        val operand = levels.first()
        state.apply(parser, editor, state.selectedKey, operand, 0)
        assertEquals(1, state.undoDepth); assertEquals(1, editor.project.soundEffectEdits.size)
        state.apply(parser, editor, state.selectedKey, operand, 0)
        assertEquals(1, state.undoDepth, "Repeated/stale callbacks cannot create duplicate history")
        val saved = editor.project.soundEffectEdits.toMap()
        val project = File(directory, "effects.smedit")
        assertTrue(ProjectFileService.saveProject(editor.project, project.path, null, false, {}))
        val reopened = EditorState().apply { testMode = true; initForProject(project, ProjectFileService.loadProject(project)) }
        assertEquals(saved, reopened.project.soundEffectEdits)
        val expected = SoundEffects.bootRam(parser, reopened.project)
        val rom = parser.copyRomData()
        assertTrue(MusicRomExporter(reopened.project, {}).applyMusicEditsToRom(rom) > 0)
        val exported = RomParser(rom)
        assertTrue(expected.contentEquals(SpcData.buildInitialSpcRam(exported)))
        assertTrue(SoundEffects.catalog(expected).all { it.problem == null })
        state.load(parser, editor); state.preview(parser, editor)
        val patched = clips.last().audioBytes
        val actual = NativeSpcEmulator().use { engine ->
            engine.loadFromRam(SpcData.buildInitialSpcRam(exported), emptyList(), 0, playbackWarmupSeconds = 0)
            engine.playSoundEffect(2, 1); pcmToBytes(engine.renderMono(3))
        }
        assertTrue(patched!!.contentEquals(actual))
        state.undo(editor); assertTrue(editor.project.soundEffectEdits.isEmpty())
        state.redo(editor); assertEquals(saved, editor.project.soundEffectEdits)
        state.stop()
    }
    @Test fun `live effect editing retains the current clip until replacement and stop cancels pending playback`() = runBlocking {
        val (parser, editor) = editor()
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SoundEffectEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.load(parser, editor); state.preview(parser, editor)
        val original = clips.single()
        val operand = state.selected!!.voices.flatMap { it.steps }.flatMap { it.operands }.first { it.field == SoundEffects.Field.LEVEL }
        state.apply(parser, editor, state.selectedKey, operand, 0)
        assertTrue(state.refreshRequested); assertTrue(state.playing); assertFalse(original.closed)
        state.load(parser, editor)
        assertFalse(original.closed, "The old audition continues while preparing updated native audio")
        state.preview(parser, editor)
        assertTrue(original.closed); assertEquals(2, clips.size); assertTrue(state.playing)
        assertFalse(state.refreshRequested)
        state.undo(editor); state.load(parser, editor)
        assertTrue(state.refreshRequested)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.preview(parser, editor) }
        state.stop(); pending.join()
        assertEquals(2, clips.size); assertFalse(state.playing); assertFalse(state.refreshRequested)
        assertTrue(clips.all { it.closed })
    }
    @Test fun `unsafe effect writes cannot target opcodes or corrupt a ROM and coexist with custom samples`() {
        val (parser, editor) = editor()
        editor.project.soundEffectEdits["1500"] = SoundEffectByteEdit(0, 1)
        val rom = parser.copyRomData()
        assertFails { MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom) }
        assertTrue(rom.contentEquals(parser.getRomData()))
        editor.project.soundEffectEdits.clear()
        val effect = SoundEffects.catalog(SpcData.buildInitialSpcRam(parser)).first { it.identity.key == "2:01" }
        val operand = effect.voices.flatMap { it.steps }.flatMap { it.operands }.first { it.field == SoundEffects.Field.LEVEL }
        editor.setSoundEffectEdits(mapOf(operand.address.toString(16).uppercase().padStart(4, '0') to SoundEffectByteEdit(operand.value, 0)))
        val sound = SoundEditorState().apply { selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }); openPianoRoll(parser, editor) }
        val note = NspcSequence.Note(0, 48, NspcSequence.nameToNote("C4"), instrument = 24)
        val target = SampleBankPreview.Target(3, note, "Title", "Sound", MusicBankTransitions.snapshot(editor.project))
        val plan = MusicSampleBanks.plan(parser, target, SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE)), "Sine")
        assertTrue(sound.useWorkbenchSample(plan, parser, editor))
        val snapshot = MusicBankResolver.sourceRam(parser, editor.project, 3)
        assertEquals(0, snapshot[operand.address].toInt() and 255)
        val preset = SoundPresets.capture(parser, snapshot, 24, "Edited", SoundEffects.bootRam(parser, editor.project))
        assertNotNull(SoundPresets.bytes(preset))
        assertTrue(MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom) > 0)
    }

    @Test fun `actual exported console effects honor edited program volumes through bank transitions`() {
        val (parser, editor) = editor()
        val source = SoundEffects.catalog(SpcData.buildInitialSpcRam(parser))
        val keys = listOf("1:37", "2:01", "3:05")
        for (effect in source.filter { it.identity.key in keys }) for (operand in effect.voices.flatMap { it.steps }.flatMap { it.operands }.filter { it.field == SoundEffects.Field.LEVEL }) {
            editor.project.soundEffectEdits[operand.address.toString(16).uppercase().padStart(4, '0')] = SoundEffectByteEdit(operand.value, 0)
        }
        val rom = parser.copyRomData(); MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom)
        for ((edited, bytes) in listOf(false to parser.copyRomData(), true to rom)) NativeMusicUploadFixture(File(directory, "console-$edited"), bytes).use { runtime ->
            for (bank in listOf(3, 0x0F, 6)) runtime.queueBank(bank)
            for (key in keys) {
                val parts = key.split(':'); runtime.effect(parts[0].toInt(), parts[1].toInt(16))
                val pcm = runtime.frames(180)
                if (edited) assertTrue(pcm.all { it == 0.toShort() }, "$key must use the exported zero levels")
                else assertTrue(pcm.any { it != 0.toShort() }, "$key needs an audible unmodified control")
            }
        }
    }
}
