# Local Codebase Notes

Last reviewed: 2026-06-02

## Current Worktree

| Path | Branch | Notes |
|------|--------|-------|
| `/Users/kenny/code/super_metroid_dev` | `kenny/sound-edit` | Active SMEDIT repo. Current sound branch includes SPC track preview, piano roll editing, BRR import/export, native `snes_spc` render path, and in-place sample replacement. |

Current known dirty state during this review: `tools/snes_spc` is a modified submodule. Treat it as existing work unless explicitly asked to inspect or reset it.

## SMEDIT Architecture

| Module | Role |
|--------|------|
| `shared/commonMain` | Pure ROM/domain code: room parsing, tile graphics, LZ5, minimap, sprite systems, SPC transfer data, BRR encode/decode, N-SPC parser/renderer models. |
| `shared/jvmMain` | JVM-specific integrations: JNA bridge to native `libspc`, libretro/JNA bindings. |
| `desktopApp` | Compose Desktop UI, editor state, emulator workspace, sound editor, piano roll, project save/load, patch management. |
| `cli` | Headless room/graph/image export tools. |

Key start points:

| Topic | Files |
|-------|-------|
| Master docs | `docs/CONTEXT.md` |
| Audio docs | `docs/rom/sound.md`, `docs/reference/sounds.txt`, `docs/rom/hex_edits.txt` |
| Sound UI | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundEditor.kt`, `SoundEditorState.kt`, `PianoRollEditor.kt`, `SoundPlayer.kt` |
| Sound data/rendering | `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/SpcData.kt`, `NspcSequence.kt`, `NspcRenderer.kt`, `shared/src/jvmMain/kotlin/com/supermetroid/editor/rom/NativeSpcEmulator.kt` |
| Sound tests | `shared/src/jvmTest/kotlin/com/supermetroid/editor/rom/SpcDataTest.kt`, `NspcSequenceTest.kt`, `NativeSpcEmulatorTest.kt` |

## Sound Branch Notes

Current flow:

1. `SoundListPanel` lists known tracks from `SpcData.KNOWN_TRACKS` and decoded BRR samples.
2. `SoundEditorState.selectTrack` records song set/play index and triggers `loadTrackSamples`.
3. `SpcData.buildInitialSpcRam` loads the base SPC engine from `$CF:8000` transfer blocks.
4. `SpcData.findSongSetTransferData` reads song-set transfer chains via the packed pointer table at `$8F:E7E1`.
5. `SoundEditorState.buildCompositeWaveform` prefers native `NativeSpcEmulator`, then Kotlin `NspcRenderer`, then a sample-preview fallback.
6. `openPianoRoll` builds ARAM for the selected song set and parses play index data with `NspcSequence.parse`.
7. Piano roll playback of unmodified tracks uses native SPC. Modified tracks are encoded through `NspcSequence.encode`, applied as extra `TransferBlock`s, and rendered through native SPC when available.
8. WAV sample replacement currently creates an SMEDIT patch that overwrites the ROM bytes backing the original BRR slot. Oversized BRR imports are trimmed to fit.
9. If a track is selected while playback is active, `selectTrack` deliberately stops the current JVM clip, clears stale waveform/editor state, and sets `autoPlayEnabled` so the newly rendered track begins playback.

Known caveats:

- Native SPC render is the accuracy target. The Kotlin renderer and piano-roll parser are convenience layers.
- `NspcSequence.parse` does not fully apply every command to note metadata yet. Instrument logs such as `instruments=[0]` can be incomplete for real tracks.
- Sequence encoding is simplified and constrained to the existing sequence region before `$6C00`.
- Sample replacement does not yet implement appended transfer blocks, directory patching, chain relocation, or ROM expansion.

## Piano Roll Editor Notes

Observed log:

```text
[SPC-PIANO] Opening piano roll for 'Lower Norfair' songSet=0x18 playIndex=5
[SPC-PIANO] Parsed: 4668 notes, 2278 commands, 7 active channels, 46469 ticks, tempo=27
java.lang.IllegalArgumentException: Can't represent a size of 373352 in Constraints
```

Cause:

`PianoRollEditor` measured the timeline and note canvas at `gridWidth.dp`, where `gridWidth = totalTicks / ticksPerPixel`. Lower Norfair at 16x zoom (`ticksPerPixel = 0.125`) produces about 373,352 display units, which is too large for Compose Desktop's packed constraints.

Current branch behavior:

- `PianoRollEditor` uses a virtualized horizontal viewport. The measured child remains viewport-sized; drawing subtracts `hScrollPx` and skips offscreen notes/grid ticks. This fixes the Compose constraint crash for long tracks.
- Zoom now exposes `1x`, `2x`, `4x`, and `8x`. The previous oversized-layout crash is avoided because 8x no longer creates an enormous measured child.
- Reset reparses the original N-SPC song, removes added notes, stops playback, and leaves the playback cursor at tick `0`.
- Stop also leaves the piano-roll cursor at tick `0`; close hides it.
- Right-clicking a note opens an inline properties panel for tick, length, pitch, velocity, quantize, instrument, and delete.
- Left-drag moves selected notes, dragging the right edge changes note length, arrow keys move/transposes the selected note, and Delete/Backspace removes it.
- Dragging uses a transient preview state and commits the underlying `NspcSequence.Note` only on release/exit. Do not call `onSongChanged` or re-encode/render SPC data during pointer-move updates.
- Piano-roll add/delete/clear/drag/key/property changes emit explicit `[SPC-PIANO-EDIT]` logs with channel, note, tick, length, velocity, quantize, and instrument details. `SoundEditorState.notifySongChanged` still emits the coarse note-count summary.
- Entering or leaving Edit Track stops current playback and disables Play All so waveform preview and piano-roll preview cannot overlap.
- Waveform preview and piano-roll preview both pass through RMS-aware preview normalization before JVM `Clip` playback. Raw rendered waveform storage is kept separate so exported WAV data is not silently mastered. Edit Track monitor playback additionally applies `EDIT_TRACK_PREVIEW_GAIN` to compensate for quiet piano-roll renders.
- `NspcSequence.parse` applies `E0` instrument and `EA` transpose state to parsed notes. New notes inherit the nearest contextual note's instrument, velocity, quantize, and duration; this is required for user-added notes to be audible in modified playback.
- Selection/removal uses object identity because `NspcSequence.Note` is a data class and structural equality can collide on repeated notes.

## Local Reference Codebases

These live under `/Users/kenny/code/super_metroid/` and are the primary references for future SMEDIT work.

| Path | Use For |
|------|---------|
| `/Users/kenny/code/super_metroid/SM-SPC` | Authoritative symbolic SPC engine source. Use `vanilla/music.asm`, `vanilla/sound library*.asm`, `vanilla/engine.asm`, and `vanilla/ram.asm` for N-SPC command semantics, ARAM variables, SFX libraries, and music engine behavior. |
| `/Users/kenny/code/super_metroid/sm_disassembly` | CPU-side 65816 disassembly. Use for upload protocol, room music fields, music queue handling, sound effect queue routines, and bank-level ROM behavior. |
| `/Users/kenny/code/super_metroid/sm` | C reimplementation. Use `src/spc_player.c`, `src/spc_player.h`, and `src/spc_variables.h` as a readable cross-check for SPC variables and player logic. |
| `/Users/kenny/code/super_metroid/smile` | Original SMILE editor source/reference. Useful for room/music selector parity and binary patching assumptions. It has no real audio editor. |
| `/Users/kenny/code/super_metroid/MapRandomizer` | Door, room geometry, and ASM patch reference. Not sound-specific, but useful for room graph/export work. |
| `/Users/kenny/code/super_metroid/metroid-infinite-mission` | Hack/project reference. Lower priority unless comparing hack data layouts. |
| `/Users/kenny/code/super_metroid/Planets v1.33` | Hack/resource reference. Lower priority for SMEDIT core. |
| `/Users/kenny/code/super_metroid/smart` | SMART-related reference area. Use when implementing SMART XML or interoperability work. |

## Nearby Repositories

Observed `~/code/super_metroid*` repos that may matter later:

| Path | Notes |
|------|-------|
| `/Users/kenny/code/super_metroid_research` | Tech handbook/reference material. |
| `/Users/kenny/code/super_metroid_techno` | Music/audio hack project; likely useful for sound-editing experiments. |
| `/Users/kenny/code/super_metroid_tiled` | Tiled workflow reference. |
| `/Users/kenny/code/super_metroid_colors` | Palette/color tooling reference. |
| `/Users/kenny/code/super_metroid_hud` | HUD-specific work area. |
| `/Users/kenny/code/super_metroid_auto_tracker` | Tracker/autosplits project, separate from SMEDIT editor. |
| `/Users/kenny/code/super_metroid_dev_bkp` | Backup SMEDIT checkout on `pr-7`. |
