package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class SampleLoopPolishTest {
    @TempDir lateinit var directory: File
    private fun discontinuous() = SampleDraft("Hard join", List(256) { if (it < 128) 16000 else -16000 }, loopStart = 0)

    @Test fun `crossfade joins both sides reversibly without changing source trim loop duration or headroom`() {
        val original = discontinuous().copy(trimStart = 16, loopStart = 32)
        val before = original.pcm.toList()
        val faded = original.copy(crossfadeFrames = 32)
        val shaped = SampleLoopPolish.pcm(faded)
        val at = faded.loopStart!! - faded.trimStart
        assertEquals(shaped[at], shaped.last(), "The two seam endpoints must meet")
        assertEquals(original.trimEnd - original.trimStart, shaped.size)
        assertTrue(shaped.all { it in -16000..16000 }, "The blend cannot boost or wrap PCM")
        assertContentEquals(before.take(faded.loopStart).drop(faded.trimStart).map(Int::toShort).toShortArray(), shaped.copyOfRange(0, at))
        assertEquals(before, original.pcm); assertSame(original.pcm, faded.pcm)
        val raw = SampleAuthoring.encode(original)
        val encoded = SampleAuthoring.encode(faded)
        assertEquals(raw.brr.size, encoded.brr.size); assertEquals(raw.loopFrame, encoded.loopFrame)
        assertEquals(raw.loopByteOffset, encoded.loopByteOffset)
        assertTrue(encoded.loopInspection!!.joins.maxOf { it.jump } < raw.loopInspection!!.joins.maxOf { it.jump } / 4)
        assertTrue(SampleAuthoring.encode(faded.copy(crossfadeFrames = 0)).brr.contentEquals(raw.brr))
    }

    @Test fun `crossfade fits short loops and inactive loops without invalidating trim edits`() {
        for (frames in listOf(16, 32, 48, 64, 512, SampleAuthoring.MAX_FRAMES)) {
            val source = SampleDraft("Bounded", List(frames) { if (it % 2 == 0) Short.MAX_VALUE.toInt() else Short.MIN_VALUE.toInt() }, loopStart = 0)
            for (fade in listOf(0, 16, 32, 128, 256)) {
                val draft = source.copy(crossfadeFrames = fade)
                val actual = SampleLoopPolish.crossfadeFrames(draft)
                assertTrue(actual <= frames / 2 && actual % 16 == 0)
                assertEquals(frames, SampleLoopPolish.pcm(draft).size)
                assertEquals(draft.crossfadeFrames, fade, "The requested setting stays reversible when the loop shrinks")
            }
        }
        val oneShot = discontinuous().copy(loopStart = null)
        assertContentEquals(SampleAuthoring.encode(oneShot).brr, SampleAuthoring.encode(oneShot.copy(crossfadeFrames = 256)).brr)
        assertNull(SampleAuthoring.encode(oneShot).loopInspection)
    }

    @Test fun `sample files preserve crossfade settings old files default to off and invalid writes cannot overwrite`() {
        val draft = discontinuous().copy(crossfadeFrames = 64)
        val file = File(directory, "blend.smedit-sample")
        SampleAuthoring.save(draft, file)
        assertEquals(draft, SampleAuthoring.open(file))
        assertTrue(SampleAuthoring.encode(draft).brr.contentEquals(SampleAuthoring.encode(SampleAuthoring.open(file)).brr))
        val saved = file.readBytes()
        for (bad in listOf(-16, 1, 17, 272, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SampleAuthoring.save(draft.copy(crossfadeFrames = bad), file) }
            assertTrue(saved.contentEquals(file.readBytes()))
        }
        file.writeText("""{"name":"Legacy","pcm":[${List(32) { 100 }.joinToString(",")}],"loopStart":0}""")
        val old = SampleAuthoring.open(file)
        assertEquals(0, old.crossfadeFrames); assertEquals(32, old.trimEnd)
        assertTrue(SampleAuthoring.encode(old).brr.contentEquals(SampleAuthoring.encode(old.copy(crossfadeFrames = 0)).brr))
    }

    @Test fun `encoded repeat inspection retains predictor history and exposes a non resetting loop entry`() {
        val stable = SampleAuthoring.encode(discontinuous().copy(crossfadeFrames = 64))
        val inspection = stable.loopInspection!!
        assertEquals(3, inspection.joins.size); assertEquals(0, inspection.maxRepeatDifference)
        assertTrue(inspection.joins.all { it.pcm.contentEquals(inspection.joins.first().pcm) })
        // This native filter-1 loop deliberately carries history from its tail into its head.
        val brr = byteArrayOf(0x57, 0x77, 0x77, 0x77, 0x77, 0x77, 0x77, 0x77, 0x77)
        val encoded = SampleAuthoring.Encoded(brr, ShortArray(16), 0)
        val changed = SampleLoopPolish.inspect(encoded)!!
        assertTrue(changed.maxRepeatDifference > 0, "Inspection cannot reset/repeat the first decode and hide predictor changes")
        assertFalse(changed.joins.first().pcm.contentEquals(changed.joins.last().pcm))
        val full = SampleAuthoring.encode(SampleDraft("Full", List(SampleAuthoring.MAX_FRAMES) { 1234 }, loopStart = 0, crossfadeFrames = 256))
        assertEquals(3, full.loopInspection!!.joins.size)
        assertTrue(full.loopInspection.joins.all { it.pcm.size == 64 }, "Keep bounded join windows, not four full loops in UI state")
    }

    @Test fun `near zero uses native aligned crossings for all markers and keeps valid loop boundaries`() {
        val pcm = MutableList(512) { 10000 }
        pcm[159] = -10; pcm[160] = 10
        val source = SampleDraft("Crossing", pcm, trimStart = 0, trimEnd = 480, loopStart = 256)
        for (marker in SampleLoopEditing.Marker.entries) {
            val nearby = when (marker) {
                SampleLoopEditing.Marker.START -> source.copy(trimStart = 128)
                SampleLoopEditing.Marker.LOOP -> source.copy(loopStart = 128)
                SampleLoopEditing.Marker.END -> source.copy(trimEnd = 192, loopStart = 176)
            }
            val snapped = SampleLoopEditing.nearZero(nearby, marker)
            assertEquals(160, SampleLoopEditing.position(snapped, marker))
            assertTrue(SampleLoopEditing.validBounds(snapped)); assertSame(pcm, snapped.pcm)
            SampleAuthoring.validate(snapped)
        }
        val quiet = source.copy(pcm = List(512) { 0 }, loopStart = 128)
        assertEquals(quiet, SampleLoopEditing.nearZero(quiet, SampleLoopEditing.Marker.LOOP), "Equal candidates cannot drift")
        val smallest = SampleDraft("One block", List(16) { 0 }, loopStart = 0)
        for (marker in SampleLoopEditing.Marker.entries) assertTrue(SampleLoopEditing.validBounds(SampleLoopEditing.nearZero(smallest, marker)))
    }

    @Test fun `native crossfade reduces seam transients while retaining the recorded playback period`() {
        val parser = TestRomHelper.requireRomParser()
        val ram = SpcData.buildInitialSpcRam(parser)
        val original = discontinuous()
        fun render(draft: SampleDraft) = InstrumentAudition.render(SampleAuthoring.preview(ram, SampleAuthoring.encode(draft)))
        val raw = render(original)
        val faded = render(original.copy(crossfadeFrames = 32))
        fun transients(pcm: ShortArray) = (12001 until 24000).sumOf { val step = pcm[it].toLong() - pcm[it - 1]; step * step }
        assertTrue(raw.any { abs(it.toInt()) > 100 })
        assertTrue(transients(faded) < transients(raw) * 0.8, "Native seam transients: raw=${transients(raw)}, crossfade=${transients(faded)}")
        fun crossings(pcm: ShortArray) = (12001 until 24000).count { pcm[it - 1] < 0 && pcm[it] >= 0 }
        assertTrue(abs(crossings(faded) - crossings(raw)) <= 1, "Crossfade cannot shift the loop's pitch")
    }

    @Test fun `live crossfade edits replace native audio and leave the source pcm and project independent`() = runBlocking {
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SampleWorkbenchState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.update(discontinuous()); state.prepare()
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        state.preview(ram)
        val first = clips.single()
        val source = state.draft.pcm
        state.update(state.draft.copy(crossfadeFrames = 32))
        assertFalse(first.closed, "The old audio stays while the new blend is encoded")
        state.prepare()
        assertTrue(first.closed); assertEquals(2, clips.size); assertTrue(clips.last().looping)
        assertFalse(clips.first().audioBytes!!.contentEquals(clips.last().audioBytes!!))
        assertSame(source, state.draft.pcm)
        state.stop(); assertTrue(clips.last().closed)
    }

    @Test fun `polished samples survive bank apply presets project reopen undo and production ROM export`() {
        val romFile = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(romFile.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(romFile.path) }
        val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val sound = SoundEditorState().apply { selectTrack(track); openPianoRoll(parser, editor) }
        val note = NspcSequence.Note(0, 120, NspcSequence.nameToNote("C4"), instrument = 24)
        val target = SampleBankPreview.Target(3, note, track.name, "Blended sound", MusicBankTransitions.snapshot(editor.project), track.id)
        val sample = SampleAuthoring.encode(discontinuous().copy(crossfadeFrames = 32))
        val plan = MusicSampleBanks.plan(parser, target, sample, "Blended sample")
        val preview = InstrumentAudition.render(SampleBankPreview.load(parser, target).build(sample))
        assertTrue(sound.useWorkbenchSample(plan, parser, editor)); assertEquals(1, sound.pianoRollUndoDepth)
        val source = MusicBankResolver.sourceRam(parser, editor.project, 3)
        val played = InstrumentAudition.render(InstrumentAudition.build(source, sound.pianoRollInstruments[24], note))
        assertTrue(preview.contentEquals(played), "Applying must retain exactly the blended native audition")
        val preset = SoundPresets.capture(parser, source, 24, "Blended preset")
        val library = SoundPresetLibrary(File(directory, "presets")).also { it.save(preset) }
        assertTrue(sample.brr.contentEquals(SoundPresets.bytes(library.load().presets.single())))
        val projectFile = File(directory, "blend.smedit")
        assertTrue(ProjectFileService.saveProject(editor.project, projectFile.path, null, false, {}))
        val reopened = EditorState().apply { testMode = true; initForProject(projectFile, ProjectFileService.loadProject(projectFile)) }
        val expected = MusicBankResolver.resolve(parser, editor.project, 3).ram
        assertTrue(expected.contentEquals(MusicBankResolver.resolve(parser, reopened.project, 3).ram))
        val rom = parser.copyRomData(); assertEquals(1, MusicRomExporter(reopened.project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val actual = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 3)) }
        assertTrue(expected.contentEquals(actual), "Exported bank must contain the exact polished sample and loop pointer")
        assertTrue(sound.undoPianoRollEdit(editor)); assertTrue(editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(sound.redoPianoRollEdit(editor)); assertTrue(expected.contentEquals(MusicBankResolver.resolve(parser, editor.project, 3).ram))
    }
}
