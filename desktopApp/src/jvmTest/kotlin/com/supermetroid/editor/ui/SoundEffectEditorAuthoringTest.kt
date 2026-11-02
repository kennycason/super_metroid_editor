package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class SoundEffectEditorAuthoringTest {
    @TempDir lateinit var directory: File
    private fun editor(): Pair<RomParser, EditorState> {
        val file = File(directory, "source.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        return RomParser(file.readBytes()) to EditorState().apply { testMode = true; initForRom(file.path) }
    }
    @Test fun `duplicate structural edits undo redo reset delete and save reopen form independent history`() = runBlocking {
        val (parser, editor) = editor(); val state = SoundEffectEditorState()
        state.load(parser, editor)
        val original = state.selected!!
        assertTrue(state.duplicate(parser, editor, "Pickup variation", 2), state.message)
        assertEquals("2:80", state.selectedKey); assertEquals(1, state.undoDepth)
        state.load(parser, editor)
        assertEquals(241, state.effects.size); assertEquals("Pickup variation", state.selected!!.identity.name)
        assertEquals(original, state.effects.first { it.identity.key == original.identity.key })
        val initial = SoundEffectPrograms.copy(editor.project.soundEffectPrograms.getValue("2:80"))
        assertTrue(state.addSteps(parser, editor, 0, 0, EffectStepTemplates.choices().getValue("Note")), state.message)
        state.load(parser, editor)
        assertEquals(initial.voices[0].steps.size + 1, state.owned!!.voices[0].steps.size)
        assertTrue(state.changeStep(parser, editor, 0, 1, move = -1), state.message)
        state.load(parser, editor)
        assertEquals(EffectStepTemplates.choices().getValue("Note")[0], state.owned!!.voices[0].steps[0])
        state.undo(editor); state.load(parser, editor)
        assertNotEquals(EffectStepTemplates.choices().getValue("Note")[0], state.owned!!.voices[0].steps[0])
        state.redo(editor); state.load(parser, editor)
        assertTrue(state.changeStep(parser, editor, 0, 0, remove = true), state.message)
        state.load(parser, editor)
        assertEquals(initial.voices, state.owned!!.voices)
        assertTrue(state.addSteps(parser, editor, 0, 0, EffectStepTemplates.choices().getValue("Envelope + note")), state.message)
        state.load(parser, editor)
        val file = File(directory, "authored.smedit")
        assertTrue(ProjectFileService.saveProject(editor.project, file.path, null, false, {}))
        assertEquals(editor.project.soundEffectPrograms, ProjectFileService.loadProject(file).soundEffectPrograms)
        state.reset(editor); state.load(parser, editor)
        assertEquals(initial.voices, state.owned!!.voices)
        assertTrue(state.delete(parser, editor), state.message)
        assertTrue(editor.project.soundEffectPrograms.isEmpty())
        state.undo(editor); state.load(parser, editor)
        assertEquals(initial, editor.project.soundEffectPrograms.getValue("2:80"))
        state.stop()
    }
    @Test fun `independent native cue and assignment do not change original aliased programs`() = runBlocking {
        val (parser, editor) = editor(); val state = SoundEffectEditorState()
        state.load(parser, editor)
        val originals = state.effects.associateBy { it.identity.key }
        assertTrue(state.makeIndependent(parser, editor), state.message); state.load(parser, editor)
        val operand = state.selected!!.voices[0].steps.flatMap { it.operands }.first { it.field == SoundEffects.Field.LEVEL }
        assertTrue(state.apply(parser, editor, state.selectedKey, operand, 0), state.message)
        state.load(parser, editor)
        assertEquals(0, state.selected!!.voices[0].steps.flatMap { it.operands }.first { it.field == SoundEffects.Field.LEVEL }.value)
        for (effect in state.effects.filter { it.identity.key != state.selectedKey }) assertEquals(originals[effect.identity.key], effect)
        assertTrue(state.duplicate(parser, editor, "Quiet variant", 1), state.message); state.load(parser, editor)
        val sourceKey = state.selectedKey
        val target = SoundEffects.identities.first { it.voices == state.owned!!.voices.size && it.key != "2:01" }
        assertTrue(state.assign(parser, editor, target.key), state.message); state.load(parser, editor)
        assertEquals(target.id, state.owned!!.routingId)
        assertEquals(editor.project.soundEffectPrograms.getValue(sourceKey).voices, state.owned!!.voices)
        state.reset(editor); state.load(parser, editor)
        assertEquals(originals[target.key], state.selected)
        assertTrue(sourceKey in editor.project.soundEffectPrograms)
        state.stop()
    }
    @Test fun `live structural edits keep accepted audio until updated render and stale callbacks cannot add history`() = runBlocking {
        val (parser, editor) = editor(); val clips = mutableListOf<DeferredSoundClip>()
        val state = SoundEffectEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.load(parser, editor)
        assertTrue(state.duplicate(parser, editor, "Live variant", 2), state.message); state.load(parser, editor)
        state.preview(parser, editor); val old = clips.single()
        assertTrue(state.addSteps(parser, editor, 0, 0, EffectStepTemplates.choices().getValue("Note")), state.message)
        assertTrue(state.playing); assertTrue(state.refreshRequested); assertFalse(old.closed)
        val depth = state.undoDepth
        assertFalse(state.addSteps(parser, editor, 0, 0, EffectStepTemplates.choices().getValue("Note")))
        assertEquals(depth, state.undoDepth)
        state.load(parser, editor); assertFalse(old.closed)
        state.preview(parser, editor); assertTrue(old.closed); assertTrue(state.playing)
        state.undo(editor); state.load(parser, editor); state.preview(parser, editor)
        assertEquals(3, clips.size); assertTrue(clips.take(2).all { it.closed })
        state.stop(); assertTrue(clips.last().closed)
    }
    @Test fun `native command phases invalid changes and exhausted IDs leave project history and ROM intact`() = runBlocking {
        val (parser, editor) = editor(); val state = SoundEffectEditorState()
        state.load(parser, editor)
        assertTrue(state.duplicate(parser, editor, "One extra", 2), state.message); state.load(parser, editor)
        val before = MusicBankTransitions.snapshot(editor.project); val depth = state.undoDepth
        assertFalse(state.duplicate(parser, editor, "No room", 2)); assertTrue(state.message.contains("IDs"))
        assertFalse(state.changeStep(parser, editor, 0, state.owned!!.voices[0].steps.lastIndex, remove = true))
        assertFalse(state.addSteps(parser, editor, 0, 0, listOf(SoundEffectInstruction(0xFB))))
        assertEquals(before.soundEffectPrograms, editor.project.soundEffectPrograms); assertEquals(depth, state.undoDepth)
        editor.project.musicSampleNames["03:00"] = "Changed during callback"
        assertFalse(state.addSteps(parser, editor, 0, 0, EffectStepTemplates.choices().getValue("Note")))
        assertEquals(depth, state.undoDepth); state.stop()
    }
    @Test fun `each insertion template is a bounded executable voice including looping blocks`() {
        for ((name, steps) in EffectStepTemplates.choices()) {
            val voice = SoundEffectVoiceProgram((steps + SoundEffectInstruction(0xFF)).toMutableList())
            assertTrue(SoundEffectPrograms.encode(voice).isNotEmpty(), name)
        }
    }
    @Test fun `editing one owned voice splits shared storage without changing other voices`() = runBlocking {
        val (parser, editor) = editor()
        val source = SoundEffects.catalog(SpcData.buildInitialSpcRam(parser)).first { it.identity.key == "2:01" }
        val voice = SoundEffectPrograms.capture(source)[0]
        val voices = SoundEffectPrograms.copyVoices(List(4) { voice })
        val program = SoundEffectProgram(1, 67, "Four pickups", 1, voices, SoundEffectPrograms.copyVoices(voices))
        editor.setSoundEffectState(emptyMap(), mapOf("1:43" to program))
        val state = SoundEffectEditorState(); state.select("1:43"); state.load(parser, editor)
        assertEquals(1, state.selected!!.voices.map { it.address }.distinct().size, "Identical voices share physical bytes before editing")
        val field = state.selected!!.voices[3].steps.flatMap { it.operands }.first { it.field == SoundEffects.Field.LEVEL }
        assertTrue(state.apply(parser, editor, "1:43", field, 0, voiceIndex = 3), state.message)
        state.load(parser, editor)
        for (i in 0..2) assertEquals(voices[i], state.owned!!.voices[i])
        assertNotEquals(voices[3], state.owned!!.voices[3])
        assertEquals(2, state.selected!!.voices.map { it.address }.distinct().size)
        state.undo(editor); state.load(parser, editor)
        assertEquals(voices, state.owned!!.voices); state.stop()
    }

}
