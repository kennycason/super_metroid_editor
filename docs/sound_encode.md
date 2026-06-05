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
- Tail-trim export: if the flattened edited sequence is over budget, export keeps the earliest part of the edited track and drops later notes/commands until the sequence fits.
- Preview parity: piano-roll playback and waveform playback try the same relocated native N-SPC patch path as ROM export before falling back to software/delta overlay. This keeps newly-added notes from sounding correct in the app but different in the exported ROM.
- Safe failure: if the trimmed edited sequence still cannot fit, export fails instead of writing a ROM that can black-screen.

Current limitation:

- The encoder flattens expanded N-SPC subroutines. Some tracks, especially Title Screen playIndex `5`, become much larger than vanilla. Tail-trim export is a practical stopgap, but the next implementation step is subroutine-preserving or compact encoding so we can keep full songs while still fitting SPC RAM.

## Safety Rules

- Never reuse a block-table pointer below the sequence area.
- Never write sequence bytes over protected transfer ranges such as the instrument table, sample directory, or BRR sample data.
- Never let two relocated edited sequences overlap.
- Never let a relocated sequence overlap original sequence data for an unedited sibling track.
- If the edited sequence is too large, trim tail notes/commands to fit the available budget.
- If allocation still fails, fail export with a clear message instead of producing a ROM.

## Tests To Maintain

- Re-encoding does not reuse unsafe low SPC RAM pointers such as `0x18F0`.
- A relocatable title-track note edit exports and remains parseable from the exported ROM.
- Multiple edited tracks in the same song set export by tail-trimming later sequence content when flattened sequences do not fit.
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
