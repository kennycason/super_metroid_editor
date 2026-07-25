# Local Codebase Notes

Last reviewed: 2026-07-25

## Current Worktree

| Path | Branch | Notes |
|------|--------|-------|
| `/Users/kenny/code/super_metroid_dev` | `main` | Active SMEDIT repo. Sound editing (SPC track preview, piano roll, BRR import/export, native `snes_spc` render path, sample replacement) is merged to main. |

## SMEDIT Architecture

| Module | Role |
|--------|------|
| `shared/commonMain` | Pure ROM/domain code: room parsing, tile graphics, LZ5, minimap, sprite systems, SPC transfer data, BRR encode/decode, N-SPC parser/renderer models. |
| `shared/jvmMain` | JVM-specific integrations: JNA bridge to native `libspc`, libretro/JNA bindings. |
| `desktopApp` | Compose Desktop UI, editor state, emulator workspace, sound editor, piano roll, project save/load, patch management. |
| `cli` | Headless room/graph/image export tools. |
| `smedit-service` | Ktor HTTP API wrapping the headless build engine (POST /patch, GET /metadata). |
| `smedit-service-app` | TypeScript/Vite web frontend for the service (not a Gradle module). |

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
- `NspcSequence.parse` applies `E0` instrument and `EA` transpose state, but it still does not model every N-SPC command. Treat native SPC rendering as the audio ground truth when available.
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
- The note properties panel is a bottom overlay, not normal layout, so right-clicking a note does not push the piano roll down.
- The instrument inspector is sourced from the current SPC RAM instrument table: entry address, `SRCN`, sample start/loop pointers, scanned BRR byte length/loop flag, ADSR1/ADSR2/GAIN bytes, decoded ADSR nibbles, pitch-adjust word, and clickable instruments already used in the track.
- `SRCN`, ADSR1, ADSR2, GAIN, and pitch-adjust are editable as shared instrument-table bytes and are patched into preview SPC RAM at `$6C00 + index*6`. Sample start/loop pointers and BRR data remain read-only diagnostics until there is a safe sample-directory/BRR patch/export path.
- Left-drag moves selected notes, dragging the right edge changes note length, arrow keys move/transposes the selected note, and Delete/Backspace removes it.
- Dragging uses a transient preview state and commits the underlying `NspcSequence.Note` only on release/exit. Do not call `onSongChanged` or re-encode/render SPC data during pointer-move updates.
- Piano-roll add/delete/clear/drag/key/property changes emit explicit `[SPC-PIANO-EDIT]` logs with channel, note, tick, length, velocity, quantize, and instrument details. `SoundEditorState.notifySongChanged` still emits the coarse note-count summary.
- Entering or leaving Edit Track stops current playback and disables Play All so waveform preview and piano-roll preview cannot overlap.
- Waveform preview and piano-roll preview both pass through RMS-aware preview normalization before JVM `Clip` playback. Raw rendered waveform storage is kept separate so exported WAV data is not silently mastered. Edit Track monitor playback additionally applies `EDIT_TRACK_PREVIEW_GAIN` to compensate for quiet piano-roll renders.
- `NspcSequence.parse` applies `E0` instrument and `EA` transpose state to parsed notes. New notes inherit the nearest contextual note's instrument, velocity, quantize, and duration; this is required for user-added notes to be audible in modified playback.
- Modified native SPC playback uses `NspcSequence.encode(..., failOnOverflow = true)`. If the simplified encoder cannot fit the edited sequence before `$6C00`, or if native render is silent, Edit Track playback avoids corrupting the instrument table / playing silence.
- Additive note edits use a hybrid fallback when native re-encode overflows: native original track playback plus software-rendered added notes. Edits that remove or change original notes still require full software fallback because the native original audio cannot subtract or move existing notes.
- Selection/removal uses object identity because `NspcSequence.Note` is a data class and structural equality can collide on repeated notes.
- Regression coverage lives in `PianoRollPreviewLogicTest`, `NspcSequenceTest`, and `NspcRendererTest`: additive overlay planning, duplicate note accounting, moved/deleted-note fallback detection, instrument-table patch bytes, PCM mix clipping, bounded Lower Norfair parsing/encoding, and instrument metadata parsing.

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
