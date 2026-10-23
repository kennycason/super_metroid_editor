# Sound Encode And ROM Export Plan

## Current Model

Super Metroid music loads a song set into SPC RAM with transfer blocks stored in ROM. A song set contains shared engine state, sequence data, instrument config, sample directory data, and BRR sample data. A track is selected by a play index. The play index points into the SPC song table at `0x581E + playIndex * 2`, and that table entry points to a conductor for the selected sequence.

The editor already persists music edits in `.smedit` and can preview them in the sound editor. ROM export relocates the ROM transfer chain and appends patch transfer blocks. That is the correct ROM-side mechanism. The remaining hard part is choosing safe SPC RAM addresses for edited sequence data.

## Problem

Re-encoding a track in its original SPC sequence allocation can grow the encoded bytes. If it grows into another track's original conductor/block table/channel data, the game may play wrong music, silence, or black-screen. This happened when Title Screen playIndex `5` and Title Screen After Button playIndex `6` shared song set `0x03`: one edit corrupted the sibling track's sequence pointers and produced writes around `0x18F0`, which is unsafe SPC engine RAM.

## Goal

When a track is edited, export it as a relocated sequence:

1. Encode the edited track into a new safe SPC sequence RAM range.
2. Patch only that play index's song-table entry at `0x581E + playIndex * 2` to the new conductor address.
3. Append the new conductor, block table, and channel data as transfer blocks in the relocated ROM song-set transfer chain.
4. Keep sibling tracks in the same song set untouched.
5. Keep instrument/sample tables shared unless the user explicitly edited instruments.

## Constraints

- ROM free space is not enough by itself. The relocated transfer chain can live in ROM free space, but the data loaded by that chain still lands in finite 64KB SPC RAM.
- Sequence data must stay below the instrument table at `0x6C00` unless we prove another range is safe.
- Low SPC RAM around the engine area is not safe for sequence writes.
- Multiple edited tracks in the same song set must not overlap each other in SPC RAM.
- Instrument edits target shared instrument table bytes and may overlap only if they write the same values.

## Implementation Strategy

1. Build the original song-set SPC RAM by applying the original transfer blocks.
2. Collect original sequence occupancy for all known play indexes in the song set: song-table entries, conductors, block tables, and channel streams.
3. For note edits, compute the encoded sequence size and allocate a contiguous protected-free SPC RAM range starting at or after the sequence/song-table area.
4. Encode the edited song with an explicit conductor address at that allocation.
5. Validate all generated sequence writes stay in the assigned allocation, except the two-byte song-table pointer.
6. Merge all patch writes byte-by-byte with conflict detection.
7. Serialize original transfer blocks plus patch transfer blocks into a relocated ROM transfer chain and repoint the song-set pointer table.

## Phase 1.5 Status

Implemented:

- Explicit encoder placement: export can choose the relocated conductor address instead of overwriting the original conductor.
- SPC occupancy checks: export protects unedited sibling tracks, base/common transfer ranges, instrument table data, sample directory data, and BRR sample ranges.
- Free-range allocation: export places edited sequences in a protected-free SPC RAM range and patches the selected play index to the relocated conductor.
- Editor budget meter: the piano roll progress bar shows the per-track compact edit budget, not the larger relocation pool. It starts at the vanilla compact sequence size, adding notes consumes budget, and deleting notes frees budget. The message also reports the current phase-1 flattened export size versus relocation space, because that is what ROM export writes today for note edits until phase 2 compact/subroutine-preserving export exists.
- Export preflight: the fitting helper can estimate a shortened arrangement, but ROM export rejects a fit that would discard notes or commands. Explicit import fitting remains a staged user action.
- Preview parity: piano-roll playback and waveform playback try the same relocated native N-SPC patch path as ROM export before falling back to software/delta overlay. This keeps newly-added notes from sounding correct in the app but different in the exported ROM.
- Safe failure: if the trimmed edited sequence still cannot fit, export fails instead of writing a ROM that can black-screen.

Current limitation:

- The encoder flattens expanded N-SPC subroutines. Some tracks, especially Title Screen playIndex `5`, become much larger than vanilla. Subroutine-preserving or compact encoding is needed to keep full songs while still fitting SPC RAM.

## Safety Rules

- Never reuse a block-table pointer below the sequence area.
- Never write sequence bytes over protected transfer ranges such as the instrument table, sample directory, or BRR sample data.
- Never let two relocated edited sequences overlap.
- Never let a relocated sequence overlap original sequence data for an unedited sibling track.
- If the edited sequence is too large, reject export before publishing a partial song. Shortening an arrangement requires an explicit user edit or staged import fit.
- If allocation still fails, fail export with a clear message instead of producing a ROM.

## Tests To Maintain

- Re-encoding does not reuse unsafe low SPC RAM pointers such as `0x18F0`.
- A relocatable title-track note edit exports and remains parseable from the exported ROM.
- Multiple edited tracks in the same song set must fit without silently trimming later content.
- Budget trimming keeps early notes and commands and drops only tail content.
- Relocated exports never reuse unsafe low original block-table pointers such as `0x18F0`.
- App previews prefer relocated native patch rendering so waveform/sound-editor playback matches exported ROM behavior.
- Two edited tracks in the same song set should export when compact/subroutine-preserving encoding makes them fit.
- Export fails safely when there is not enough SPC sequence RAM.

## Future Improvements

- Preserve N-SPC subroutines instead of flattening everything, reducing encoded size.
- Compact original sequence allocations for a song set when there is no simple free tail.
- Show ROM-export eligibility in the UI before export.
- Add an allocation/debug view showing sequence ranges, instrument table, and free SPC RAM.

## Instrument UX checkpoint — 2026-10-23

Working branch: `sound/instrument-browser-preview`.

First chunk:

- Replace the note's numeric instrument field with a compact card and explicit instrument browser.
- Search descriptions, sample IDs, and user names; filter to instruments used in the track.
- Audition the selected pitch or reference C4 through the native engine, without changing notes, undo history, or stored waveforms. Preserve the attack and use fixed monitor gain.
- Save display names per song set/instrument slot as project metadata, separate from music patches.
- Keep raw shared settings under Advanced. Interpret tuning correctly while preserving existing serialized and exported bytes.
- Emit explicit E0 instrument selection for melodic notes. CA..DF are percussion notes and cannot substitute for an instrument-select command.
- Pad participating channels with rests to the arrangement end. A short note on a previously unused voice must not terminate the entire synchronized song block.

Second chunk — imported sound persistence:

- Retain raw N-SPC / supported IT source blocks after note and instrument edits, including project save/reopen. Store a separate editable import baseline so unchanged imports and instrument-only edits keep their original sequence bytes.
- Resolve retained samples plus current edits through one path for piano-roll preview, waveform preview, raw N-SPC export, SPC snapshot export, and ROM export. Preserve source bytes; relocate only changed sequences and protect custom BRR samples even when they occupy lower sequence RAM.
- Keep native quiet/muted output instead of substituting an audible approximate envelope. Preserve attacks on short imported arrangements.
- Allocate isolated instrument previews around imported BRR samples, including samples in the usual preview area. SPC snapshots start before the song advances, preserving short-note attacks.
- Undo/redo restores the matching source bank when undoing imports or moving between two imports. Reset returns to stock data. Sparse source payloads do not zero unrelated instrument entries.
- Use the actual merged original/import transfer chain for ROM budget preflight, matching the exporter. Reject malformed payloads, allocation failures, and over-budget exports without discarding samples or shortening the song.
- Read older native-payload projects without a baseline as pristine imports; initialize a baseline when opening them for editing. Compatible SPC snapshot import remains sequence-only and does not capture its sample bank.

Continue in this order:

1. Move shared instrument overrides to song-bank ownership, reconcile existing per-track overrides, and show affected sibling tracks. Add safe duplication only after proving a slot is unused by music and effects. Imports still share bank resources; retaining them is not per-track sample isolation.
2. Add compact native envelope controls/graph, gain modes, and musical tuning offsets. SNES key-off release is fixed; avoid presenting a conventional synth ADSR or inactive gain controls. Keep raw values under Advanced.
3. Repair general sequence fidelity: long notes/ties, percussion parsing/base state, overlapping MIDI voices, and GM-to-bank instrument mapping. Improve preview/export eligibility reporting and distinguish approximate whole-song fallback audio.
4. Stage sample replacement and audition the encoded result; key replacements by bank/resource identity, support loops, and add safe relocation. Then build a separate named sound-effect browser and native effect audition.

Offline verification (dependencies/native library are already built in this checkout):

```sh
SMEDIT_TEST_ROM="$PWD/test-resources/Super Metroid (JU) [!].smc" ./gradlew --offline \
  :desktopApp:jvmTest --tests '*InstrumentAuditionTest' --tests '*InstrumentSelectorTest' \
  --tests '*ImportedMusicPersistenceTest' --tests '*MusicEditConversionTest' \
  --tests '*MusicTransferChainBudgetTest' --tests '*MusicTrackInterchangeTest' \
  --tests '*ImpulseTrackerImportTest' --tests '*PianoRollEditLogicTest' \
  --tests '*PianoRollPlaybackTimingTest' --tests '*PianoRollPreviewLogicTest' --tests '*MusicEditExportTest' \
  :shared:jvmTest --tests '*NspcRendererTest' --tests '*SmEditProjectMusicTest' --tests '*NspcSequenceTest' \
  --tests '*SpcDataTest' --tests '*NativeSpcEmulatorTest' --console=plain
```

Set `SMEDIT_CAPTURE_INSTRUMENT_UI=true` to capture browser layouts for all font sizes under `desktopApp/build/test-output/`. The `InstrumentAuditionTest` fixture uses the real SPC engine with a synthetic loop to compare native tuning, note attacks, gain changes, and arrangement/audition behavior. Missing ROM fixtures must be reported as skipped, not treated as audio verification.

`ImportedMusicPersistenceTest` covers a custom loop in lower sequence RAM, real project save/reopen, matching preview/ROM RAM, raw N-SPC round trips, instrument-only sequence preservation, legacy projects, native mute/attack behavior, sparse instrument tables, undo/redo across imports, and safe allocation failure.
