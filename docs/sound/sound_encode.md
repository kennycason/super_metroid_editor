# Sound Encode And ROM Export Plan

## Current Model

Super Metroid music loads a song set into SPC RAM with transfer blocks stored in ROM. A song set contains shared engine state, sequence data, instrument config, sample directory data, and BRR sample data. A track is selected by a play index. The play index points into the SPC song table at `0x581E + playIndex * 2`, and that table entry points to a conductor for the selected sequence.

The editor persists music edits in `.smedit` and can preview them in the sound editor. ROM export reuses existing bank-owned payload bytes when all requested uploads are already covered and no other bank shares those ROM bytes. New upload ranges require a relocated, merged transfer chain. Choosing safe SPC RAM addresses for edited sequences and restoring resources across bank changes are separate requirements.

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
- Capture an editable sequence baseline when importing native source data. Compatible SPC snapshot import remains sequence-only and does not capture its sample bank.

Third chunk — shared instrument ownership (2026-10-24):

- `musicInstrumentBanks` owns manual overrides once per song set/instrument slot. Saved tracks contain arrangements and optional retained native sources, with no per-track instrument snapshots. Resetting the last shared override removes the bank record; no empty migration markers are needed.
- Forward-only beta policy: no legacy sound reconciliation, migration, compatibility fallback, or conflict-choice UI. Native sources require the import-time sequence baseline, not an inferred baseline on project open. Browsing a bank never creates edits. All nine checked-in `.smedit` files were inspected on 2026-10-24; none contained sound-editor edits requiring removal, so unrelated project data was left intact.
- Explicit editable N-SPC bundle imports apply their supplied settings to the shared owner. Native import reports disclose that existing shared overrides stay active. Genuine overlapping native resources still fail validation before playback/export; those checks protect current data, not old save formats.
- A compact scope row distinguishes note-local choice from shared-bank editing. The track dropdown lists known arrangements that load the bank, not a claimed per-slot usage analysis. Effects may also use those resources. `BANK` badges distinguish affected siblings from explicit arrangement edits.
- Shared sound reset has its own undo step. Reset Track removes that track's arrangement/import but preserves shared manual overrides. Resetting an import can therefore change source samples for its sibling tracks; it is not a sample-isolation operation.
- `MusicBankResolver` resolves all saved arrangements, native sources, and shared instrument overrides together for piano-roll/waveform preview, snapshots, raw export, and ROM export. Overrides apply once after source resources. Instrument-only changes do not flatten either arrangement. Imports share samples with sibling previews too.
- Reject overlapping native sources rather than choosing a source implicitly. Reserve source/sample regions before allocating any edited sequence. Validate every bank before writing relocated ROM chains. Sample decoding invalidates with the audio-edit version.

Important engine boundary discovered during this chunk:

- Vanilla song-set transfer chains reload instruments from `$6C90` (slot 24); common slots 0–23 at `$6C00..6C8F` are initialized with the base SPC upload and are not reset on ordinary bank changes. CPU `HandleMusicQueue` uploads only the selected bank. Thus a bank-staged common-slot edit can carry into later banks in the running game, despite fresh-RAM previews for those banks starting from their own baseline.
- The shared-ownership checkpoint initially warned about this limitation. The transition-safety chunk below now blocks unrestored common-resource changes. Full common-slot isolation, automatic instrument duplication, sound-effect usage analysis, and imported sample relocation are still not implemented. Do not advertise bank-local project ownership as unrestricted runtime isolation.
- Primary local references: `SM-SPC/vanilla/music.asm` (`loadNewMusicData`, `loadNewMusicTrack`, instrument address calculation), `SM-SPC/vanilla/ram.asm`, and `sm/src/sm_80.c` (`Vector_RESET_Async`, `HandleMusicQueue`). Actual vanilla transfer-chain inspection confirms every song set's instrument block begins at `$6C90`.

Fourth chunk — bank-transition safety (2026-10-24):

- `MusicBankTransitions` models uploads into existing SPC RAM, not another fresh-RAM preview. Check outgoing edited bytes against all known destination banks, including untouched ones. Also check incoming loads into edited banks: new note/E0 selections, manual overrides, and explicit native instrument entries can expose sample pointers/BRR that the destination does not upload.
- Protect the common/bank-local instrument tables, complete sample directory, initial/current BRR spans, and engine upload below `$5800`. Dependencies use the editable notes/E0 commands and explicit source table entries; this is **not** exhaustive native control-flow/percussion or sound-effect usage analysis. Passing this resource check does not prove a slot is unused or certify arbitrary SPC imports.
- Actual transfer chains vary in length. Rain loads slots 24–27; the two voice banks load only slots 24–25. Derive six-byte reset entries for affected bank-local slots 24–41 in destinations that would otherwise retain them. Copy each destination's resolved settings, not a previous bank's version. These are derived export writes, not saved project overrides or fake arrangement edits. Revalidate the concrete reset uploads before export.
- Do not automatically rewrite all banks for common data. Unrestored common instruments, sample-directory/BRR changes, and engine overwrites block ROM export with the owning edited bank and SPC address. Isolated audition and SPC/N-SPC export remain available. Supported IT/raw imports that replace common resources may therefore be useful for audition but not yet eligible for ROM export.
- Reserve **all** relocated chains/reset uploads before writing any music bytes or pointers. Budget/allocation failures leave the input ROM bytes untouched. Reset chains can consume significant space; this checkpoint copied authored banks as well, so even two slot-28 bank edits plus resets exceeded vanilla contiguous free space. The eighth chunk below removes unnecessary authored-bank copies; new reset ranges still need space. Do not silently expand the ROM, discard sibling data, or claim a passed resource check guarantees space.
- Sound has a compact, debounced background status with opt-in grouped details and human-readable bank names. Mutable sound containers are detached before background validation. Checking/blocked/passed states are distinct, and the passed state discloses derived resets and the limits of the check. The common-sound card warns that edits may block ROM export.
- Regression fixtures previously put native sequences at `$7000`, which overlaps common BRR, and overwrote common slot/SRCN 0. ROM-eligible persistence fixtures now use a sequence at `$6500`, instrument/SRCN 24, DIR `$6D60`, and a custom loop at `$6000`. Dedicated unsafe fixtures retain coverage of common/engine corruption rather than treating isolated playback as export safety.

Fifth chunk — preview feedback and inspector polish (2026-10-25):

- Fix the misleading “Audio output unavailable” path: opening/starting a Java Sound clip now returns an explicit accepted/empty/output-failed result. An asynchronously updated `isRunning` flag is not an output-open failure. Real device exceptions still report failure and close any partially opened clip. The native preview state reports preparing, playing, and completion without changing the arrangement/history.
- Reserve a permanent 36 dp status row, a spinner slot, and a 32 dp Stop button. The browser's row play icon and loading indicator have equal bounds. Exact geometry tests cover idle/preparing/playing/completed/error states so the bottom-anchored overlay does not jump during previews.
- Use shared 6 dp corners for sound-editor buttons, matching the main navigation. Replace “Name…”/“Choose…” with **Rename**/**Choose sound** and use **Use sound** for note-local application.
- Show filtered/available/used counts and **ROM bank**/**ROM + import** source labels. The picker draws from the resolved current music bank, including sibling native sources, not a General MIDI library or the separate sound-effect list. Hide zero-filled table padding and unavailable samples; put the current and used sounds first. The few visible rows are a scrollable viewport, not the whole bank. Do not invent timbre names: generic one-shot/loop/noise descriptors and user-provided names remain in use.
- Label browser preview pitches **This note (F1)** and **Middle C (C4)**. These are musical pitches, not instrument IDs. Keep the note's musical pitch visible, right-align Delete/Close, and move raw timing/velocity/gate fields into **Note details**. Pitch input accepts musical note names, not N-SPC bytes; full labels and one short Gate hint replace Tick/Len/Vel/Q abbreviations. Preserve existing clamping, undo labels, and mutation boundaries.
- Tie note detail/Advanced fonts to the editor setting, use theme colors for both light/dark modes, and allow the full inspector to scroll on shorter windows. Advanced labels **Sample ID** as a BRR recording or noise source and briefly defines sound = sample + envelope + tuning. Sample IDs and envelope bytes are intentionally still technical here: curated identities, a named sample picker, and compact native envelope/gain/tuning controls remain future work.
- No save-format migration, project-file cleanup, fallback sound library, or expanded ROM-export permissions were added. The bank-ownership and transition-safety rules above remain unchanged.

Sixth chunk — native instrument shaping (2026-10-25):

- **Edit sound** now opens the native shaping controls, replacing Advanced on the main action row. Raw Advanced is nested within the editor. Browser/editor views are mutually exclusive to keep the bottom inspector compact; rename, scope, warning, preview status, font/theme support, and short-window scrolling remain intact.
- `NativeInstrumentSound` masks the existing ADSR1/ADSR2/GAIN bitfields without introducing another saved representation. Envelope and Gain modes preserve inactive bytes. Native attack/decay timings and sustain percentages are readable; **Sustain fade** is the held-note fade rate, with zero meaning hold. Full sustain disables the ineffective decay control. Key-off release remains native/fixed, not a conventional synth Release knob.
- Gain supports fixed level, linear rise, bent rise, linear fall, and exponential fall. The selected mode exposes only its applicable level/rate field. Falling gain starts silent on key-on, as does rising gain at rate zero; these cases get a compact warning rather than invented initial volume or audible fallback.
- The envelope guide implements the DSP's integer updates, counter periods/offsets, hidden-envelope knee, and fixed release for an isolated two-second hold. It is not a waveform or a full sequence/sample/DSP-phase simulation: sample end, engine commands, and actual note duration can change what is heard. Source reference: `tools/snes_spc/snes_spc/SPC_DSP.cpp` (`run_counters`, `run_envelope`); cross-checked against the [upstream DSP implementation](https://github.com/snes9xgit/snes9x/blob/master/apu/bapu/dsp/SPC_DSP.cpp).
- Transpose/fine controls derive musical offsets from the resolved current ROM/import source, before bank overrides. This includes imports owned by sibling tracks, not just the open arrangement. Refresh the reference after import undo/redo and track reset; save/reopen must not redefine zero. Native pitch byte order, quantization, and 1..65535 limits are respected; Reset tuning changes only tuning. Zero/absent source tuning and noise sources do not get a guessed reference. Fine/coarse gesture axes remain stable when native rounding crosses a semitone boundary.
- Slider drafts update the guide without writing the bank, creating history, or rendering SPC audio on each frame. Pointer release/keyboard/accessible commit records one shared-bank undo step; no-op gestures record none. This checkout's Compose Material slider has accessibility progress but no desktop keyboard handler, so the sound controls add native-step arrow keys and Home/End. Repeated keys finish one gesture on release or focus loss. Explicit Play uses the committed bytes through existing native audition. Mode changes commit immediately, retaining the shared scope and export restrictions. No save format, migration, default synth preset, loader hook, or ROM permission changed.

Seventh chunk — bank-scoped recording chooser (2026-10-25):

- **Edit sound → Change source** now browses the current bank's recordings and generated noise. Search names, source filenames, IDs, and sound usage; optionally filter to the current track's sources. Prioritize current/used slots. Show recorded length, loop/one-shot status, shared/bank-ROM/imported provenance, small waveforms, a selected waveform/loop marker, and sound usage without long explanatory paragraphs. While browsing, the native shaping controls collapse; Advanced still exposes raw metadata, but SRCN is read-only there.
- `MusicSampleCatalog` reads the real `$6D00` directory (40 slots), not `findSampleDirectory`'s heuristic RAM scan. Validate bounded BRR chains and exclude engine/table bytes and missing ends/loops. Do not filter genuine silence out of the catalog. Add the 32 native noise rates separately; noise has no fake recording/waveform, and its pitch controls are disabled because the rate determines pitch.
- Real-ROM coverage exposed a linked loop: bank `$36`, SRCN 23, intro `$B516` (2736 B), loop `$CC6E` (873 B), both ending with LOOP+END (`$8B`). The catalog now handles separately stored intro/loop and one-shot-tail streams instead of treating every loop pointer as an aligned offset inside its intro. A display-only concatenation carries BRR predictor history through the linked segment; native audition uses the untouched bank and actual DIR pointers. The waveform is a bounded decode guide, not the sound's held-note duration or a steady-state loop simulation. All vanilla bank instrument sources are covered by a ROM regression.
- Snapshot the resolved bank before manual overrides, including sibling imports, and decode off the UI thread. Cache across instrument-only changes; invalidate on source/bank/provenance changes. Version and cancellation checks prevent stale jobs replacing another bank's catalog or leaving loading stuck. Provenance includes partial directory, intro, and linked-loop uploads, not just starting addresses.
- Preview a candidate with this sound's current envelope, gain, tuning, and selected/reference pitch through private native RAM. Do not commit or create note history while browsing/previewing. **Use sample**/**Use noise** changes only SRCN through the shared owner and records one undo step. Invalid/stale selections are rejected; common-slot ROM export restrictions, shared scope, and atomic preflight remain unchanged. Recording/noise candidate audio is regression-compared byte-for-byte against subsequent committed native audition.
- `musicSampleNames` stores optional display-only names by bank and content/loop identity. Hash encoded intro/linked data plus effective loop; omit reusable SRCN/absolute addresses. Identical aliases share a bank label; importing different bytes or changing loops cannot silently relabel unrelated content. Labels survive save/reopen and source undo/redo, and also appear in sound descriptions/search. They do not increment the audio-edit version or create ROM patches. Unknown timbres still use honest metadata descriptions; no guessed curated names, legacy migration, sound-effect program editor, sample allocation, or sample creation was added.

Eighth chunk — bounded ROM export planning (2026-10-25):

- Existing bank-owned uploads can now be patched **in place**. Require coverage of every requested SPC byte, use the existing last-upload-wins ROM mapper, and change only bytes that differ. Leave chain headers/order, native terminator, pointers, sequences, and untouched samples alone. Never use a base-upload fallback for a bank edit. New SPC upload ranges, including derived resets equal to cold-start bytes, still require relocation; equality is not proof that the running game reloads them.
- Inventory complete source ranges before planning writes. Include setup, all native pointer-table entries (including the unused `$48` Samus-theme bank), authored extra banks, and the hardcoded boot upload. Protect identical pointers and partial/suffix overlap: if another bank consumes a changed ROM byte, relocate the edited bank instead. Strictly reject truncated blocks/terminators and SPC overflow. Register live ranges across the entire shared allocator session, including erased-looking `$FF` payloads crossing LoROM boundaries. Existing/future observer views and later exporters retain that protection; cached cursors advance without discarding prior reservations. Reject protection colliding with an earlier reservation and independently check concrete music allocation/source intersections before writing anything.
- Reserve the entire relocation/reset plan before any in-place patch or pointer change. Existing two-bank slot-28 edits plus the three rain/voice resets now fit the unexpanded vanilla ROM. Existing slot-24 sounds in every native bank can be changed with zero ROM allocation. Derived reset banks do not inflate the authored edit count. Imports, sibling sources, bank ownership, native previews, and undo data retain their existing representations.
- Serialize and budget the complete native terminator: `size=0` **and** the original two-byte entry address. Previously relocated chains reserved only the size word, accidentally depending on following bytes for the ignored post-boot entry address. Original cross-bank chains remain readable; newly allocated chains retain the conservative single-LoROM-bank limit.
- This is an allocation/ownership improvement, not common-resource isolation. Common instruments, leaked DIR/BRR data, and engine edits remain blocked. No CPU/SPC loader patch, expanded export permission, ROM expansion/reclamation, migration, project-file rewrite, or UI paragraphs were added.

Common-resource implementation boundary:

- Prefer a **CPU transfer-reader prefix/continuation** design that uploads the destination's required baseline resources and explicit bank overrides in the existing stopped-audio receive session, then consumes its retained original chain. It must not reinitialize the whole SPC engine, assume a second upload handshake is harmless, or require cloning all music chains. A vanilla transfer block has no ROM continuation pointer; this requires a verified loader change, not a special block silently interpreted only by the editor.
- Keep current bank-local authoring semantics. Making common edits globally owned would intentionally change unrelated music and potentially effects; do not introduce that as an automatic workaround. Until isolation is verified, retain the common-slot restriction and effect-sharing disclosure. Do not infer unused slots from editable notes alone.
- Plan desired common instrument/DIR/BRR lifetimes across every destination, including untouched/unused banks and reentry. Native control flow, percussion, linked loops, and effects must be included before claiming full dependency coverage. Engine writes remain outside the permission set. Prefix ordering must preserve the destination's actual final bytes and avoid restoring over its own uploads.
- Runtime acceptance must exercise the **actual CPU/APU transfer path**, including processor widths, kick/echo state, DB/Y and bank crossings, warm boot/bank reentry, and effect playback. Applying serialized blocks to fresh RAM or using isolated SPC audition is not evidence for that hook. Reject unknown loader bytes rather than installing a guessed patch. No hook is enabled by this checkpoint.

Ninth chunk — native CPU/APU continuation proof (2026-10-25):

- `MusicTransferLoader` supplies a detached, instrument-only prefix and a 43-byte 65816 continuation reader. It does **not** install itself or change current export permissions. Verify the complete `$80:8024..8110` upload/overflow routine against SHA-256 `992a752c868ebd31545a8d098f989565b8014a96f1270edf45beb1f17058c048`; unknown loader bytes are refused before producing patch writes. The replacement covers only the six-byte size-reader call at `$80:80B1`, using a long call plus two NOPs. Independent Asar source verifies the machine bytes and relative branches.
- The prototype interprets `FFFF + u24 ROM pointer` as a continuation before sending any block header to the APU. It leaves the existing eight-bit kick on the CPU stack, maintains native data-bank/`$02` tracking and the original double-increment overflow behavior, and returns a normal size to the unchanged sender. No extra handshake, SPC engine restart, scratch-RAM allocation, sample copying, or global instrument owner is introduced. Prefixes permit only aligned six-byte native instrument entries; engine/DIR/BRR/partial-entry payloads and a size word straddling `$xx:FFFF` are rejected. Placement and reachable-chain validation remain caller/integration responsibilities.
- `NativeMusicUploadFixture` assembles an isolated test ROM which replaces reset/gameplay with a WRAM mailbox but retains the **actual game CPU uploader and SPC engine**. It runs in the local Snes9x libretro core, not the user's running editor/emulator, and reads retained SMP RAM through that pinned core's read-only APU save-state diagnostic. Native CPU status, DB, and stack are checked after each return. Asar/core are local prerequisites; the fixture never downloads them. Missing prerequisites skip explicitly, or fail when `SMEDIT_REQUIRE_MUSIC_UPLOAD_RUNTIME=true` is set.
- Establish an unmodified-loader control across all 24 native music banks and demonstrate actual common-instrument leakage without restoration. The continued-loader test restores common slots 0/23 and bank-local slot 28 across all native destinations, untouched banks, rain/voice banks, and repeated reentry. Source music chains and their pointers stay byte-identical. The demonstration uses 883 encoded bytes (907 bytes including alignment gaps), not hundreds of KiB of bank copies. Test a continuation marker near a bank end and a retained native header whose size word is at `$B9:FFFE` and destination at `$BA:8000`.
- Music is audibly nonzero after continued uploads. The original sequential effect check sent command 1 to each library; the tenth chunk corrects that check because library 3 command 1 is Silence and residual audio could mask it. These are CPU/APU/audio-buffer checks, not physical speaker tests or an exhaustive effect/percussion/control-flow usage analysis. The fixture respects boot initialization and the game's eight-frame post-upload downtime; immediate reentry after cold boot is not treated as a supported handshake.
- Keep `MusicRomExporter`, project data, existing ROM readers, bank-load status, and the common-resource restriction unchanged. A regression explicitly verifies that common edits are still rejected atomically. This staged proof is **not yet a user-facing export feature**: continuation-aware readers, source-span ownership, malformed/cyclic stream rejection, budgets, and the all-bank export transaction must land together before the hook is enabled. No compatibility layer, migration, project cleanup, or app restart was added.

Tenth chunk — instrument continuation export (2026-10-26):

- Common and bank-local instrument settings now export with compact six-byte-entry prefixes and the verified 43-byte CPU reader. Install the hook only when needed; compare the complete native routine byte-for-byte, or recognize and reuse only the exact installed profile. No alternate loader, compatibility mode, migration, global instrument ownership, or automatic ROM expansion is introduced. This supersedes the ninth checkpoint's export gate.
- `SpcTransferStream` is the shared strict ROM reader for previews, export, and final-writer source mapping. Follow discontiguous continuations with bounded hops, preserve the native four-byte terminator/entry address, and track all physical headers, payloads and markers. Reject cycles, source-span reuse, invalid ROM/RAM bounds, truncation, unverified hooks, and unsupported word/marker bank crossings. Raw interchange payload parsing remains separate from ROM continuation semantics.
- `MusicRomExporter` inventories setup and every native bank, including unused `$48`, before planning writes. Protect all retained spans, pointer cells, and hook code in the shared allocator. Reserve all prefixes, any required isolated chains, and code before writing. Expand partial instrument entries to six bytes and patch retained covered bytes so the original upload cannot overwrite the prefix. Larger contiguous authored writes retain their covered bytes too.
- Re-read the staged, serialized ROM through that same strict reader. Check full bank RAM parity and concrete transition restoration before committing; validate every allocator write first. Unknown loaders, malformed untouched banks, allocation failures and conflicting writes leave input ROM bytes untouched. Reopening/re-exporting an editor-produced ROM reuses the exact hook, follows its sources, and protects all physical spans even with no new music edits.
- Actual CPU/APU acceptance uses **exported** ROM pointers through the original `$80:8F0C` music queue, repeated bank reentry, music playback, and the original `$80:8462` soft reset into title music. The test-only mailbox lives in stock free space so gameplay routines remain intact. Effects are tested in separate fresh consoles with silent controls; library 3 command 1 is deliberately **Silence**, so use audible command 5 instead. The earlier prototype's sequential command-1 check could mask that silence with another library; its test is now isolated and corrected too. This is not exhaustive gameplay or effect usage analysis.
- The existing compact bank-load status discloses derived reloads; the common-sound label now describes bank scope instead of warning that all common edits block export. No large instructional UX text is added. Unrestored DIR/BRR/engine writes remain blocked, and no instrument/sample duplication or creation is advertised as safe.

Continue in this order:

1. Repair general sequence fidelity: long notes/ties, percussion parsing/base state, overlapping MIDI voices, and GM-to-bank instrument mapping. Improve preview/export eligibility reporting and distinguish approximate whole-song fallback audio.
2. **Create/import/replace samples**, not just overwrite: add WAVs as new recordings, support a small waveform/noise generator for from-scratch creation, author loops, audition the actual SNES-encoded result, and allocate/relocate safely within sample-slot/SPC/ROM budgets. Extend shared DIR/BRR resource-lifetime work first; instrument prefixes alone do not make sample replacement safe. Key replacements by bank/resource identity. Creating/duplicating a sound from an existing recording is distinct from creating new sample bytes, and requires proving a slot is unused by music and effects.
3. Build a separate named sound-effect browser and native effect audition/editor. Effect programs may share recordings with music; a sample is not itself an entire sound effect.

Offline verification (dependencies/native library are already built in this checkout):

```sh
SMEDIT_TEST_ROM="$PWD/test-resources/Super Metroid (JU) [!].smc" ./gradlew --offline \
  :desktopApp:jvmTest --tests '*InstrumentAuditionTest' --tests '*InstrumentSelectorTest' \
  --tests '*SoundPlayerTest' --tests '*NotePropertiesPanelTest' \
  --tests '*NativeInstrumentSoundTest' --tests '*NativeInstrumentPanelTest' \
  --tests '*MusicSampleCatalogTest' --tests '*MusicSamplePickerTest' --tests '*MusicSampleSelectionTest' \
  --tests '*MusicRomInPlaceExportTest' --tests '*MusicRomTransferChainTest' \
  --tests '*MusicTransferLoaderTest' --tests '*NativeMusicUploadTest' --tests '*MusicContinuationExportTest' \
  --tests '*MusicInstrumentBankTest' --tests '*MusicBankTransitionsTest' --tests '*MusicBankSafetyStatusTest' \
  --tests '*ImportedMusicPersistenceTest' --tests '*MusicEditConversionTest' \
  --tests '*MusicTransferChainBudgetTest' --tests '*MusicTrackInterchangeTest' \
  --tests '*ImpulseTrackerImportTest' --tests '*PianoRollEditLogicTest' \
  --tests '*PianoRollPlaybackTimingTest' --tests '*PianoRollPreviewLogicTest' --tests '*MusicEditExportTest' \
  :shared:jvmTest --tests '*NspcRendererTest' --tests '*SmEditProjectMusicTest' --tests '*NspcSequenceTest' \
  --tests '*SpcDataTest' --tests '*SpcTransferStreamTest' --tests '*NativeSpcEmulatorTest' --tests '*SmEditProjectFormatTest' \
  --tests '*RomFreeSpaceAllocatorTest' --console=plain
```

Set `SMEDIT_CAPTURE_INSTRUMENT_UI=true` to capture browser layouts and note inspectors for all font sizes under `desktopApp/build/test-output/`. Note-inspector captures include both themes and a short-window case. The `InstrumentAuditionTest` fixture uses the real SPC engine with a synthetic loop to compare native tuning, note attacks, gain changes, and arrangement/audition behavior. Missing ROM fixtures must be reported as skipped, not treated as audio verification.

For the CPU/APU tests, build the local Snes9x core and pinned Asar first, then set `SMEDIT_REQUIRE_MUSIC_UPLOAD_RUNTIME=true` so missing native prerequisites fail rather than skip. `SMEDIT_MUSIC_UPLOAD_CORE` and `SMEDIT_ASAR` can select explicit local files. The fixture uses Snes9x's exposed `_Z15S9xAPUSaveStatePh` diagnostic ABI; cores without that symbol are not claimed as tested. This checkpoint was exercised with the local macOS core. The harness covers the actual uploader, music-queue handler, and soft-reset/title path, not exhaustive gameplay.

`ImportedMusicPersistenceTest` covers a custom loop in lower sequence RAM, real project save/reopen, matching preview/ROM RAM, raw N-SPC round trips, instrument-only sequence preservation, native mute/attack behavior, sparse instrument tables, undo/redo across imports, and safe allocation failure.

`MusicInstrumentBankTest` covers shared-only edits, sibling consistency, no sequence flattening, preview/ROM RAM parity, real save/reopen, reset/undo/redo, removal of empty bank records, project bank separation, malformed shared settings, explicit bundle import, and overlapping native source rejection. `SmEditProjectMusicTest` verifies the forward-only schema: no instrument snapshots in saved tracks and no missing native baseline. `InstrumentSelectorTest` checks shared scope, Escape dismissal, the common-slot scope label, and layout at every font size.

`MusicBankTransitionsTest` covers common leakage, incoming sample dependencies from untouched banks, common DIR/BRR/engine changes, safe custom resources, concrete exported resets for all destination banks, reentry between differently authored banks, detached analysis, and allocation failure without ROM mutation. `MusicBankSafetyStatusTest` covers compact opt-in details at every font size, checking/blocked/passed states, reset disclosure, and explicit limits of the check. Captures are `music-bank-safety-{FONT}.png` under `desktopApp/build/test-output/`.

`MusicRomInPlaceExportTest` covers all native banks with allocation disabled, exact changed-byte accounting, headered ROMs, identical/suffix-aliased sources including the unused theme bank, last-upload-wins behavior, mixed-plan atomic failure after earlier reservations, live erased-looking cross-bank data, malformed unrelated sources, native entry-address preservation, and authored-only edit counts. `MusicRomTransferChainTest` covers complete source footprints, cross-bank reads, partial coverage, invalid pointers, truncation, RAM/ROM bounds, and exact four-byte terminator budgeting. `RomFreeSpaceAllocatorTest` additionally covers live protection across prior/future observer views, late discovery with cached cursors, unwritten reservation conflicts, and atomic rejection of invalid protection. These are serialized-ROM/retained-RAM regressions, not CPU/APU loader-hook validation.

`SoundPlayerTest` uses an injected asynchronous Java Sound fixture (no actual audio device) to verify accepted starts, explicit failures, empty audio, and stale completion isolation. `InstrumentAuditionTest` also renders a real native note into that deferred output to check end-to-end preview messages without mutating the song. `NotePropertiesPanelTest` checks right-aligned actions, font/theme support, musical pitch validation, undo/mutation behavior, exact preview-state geometry, and scrolling at 480 px height. `InstrumentSelectorTest` additionally checks source/count/filter labels, current/used ordering, exclusion of empty/unavailable entries, clearer pitch choices, and exact status-transition bounds at every font size. These tests do not claim a live trace of the user's hardware output.

`NativeInstrumentSoundTest` covers bitfield preservation, native gain modes, tuning byte order/range, envelope stages, zero-rate silence, bent gain, fixed release, and timing readouts. `NativeInstrumentPanelTest` covers browsing without edits, keyboard/accessibility commits, one commit per real pointer drag, half-semitone rounding stability, hidden inactive controls, silent-gain warnings, unavailable/noise tuning, both themes/all font sizes, and 420 px wrapping. Native audition regression compares attack, held-note sustain fade, fixed gain, falling-gain silence, and an actual octave shift. Bank/import integration tests verify source-reference stability, shared siblings, real save/reopen, undo/redo/reset, and preview/export byte parity. UI captures are `native-sound-{THEME}-{FONT}-{WIDTH}-{envelope|gain|full-sustain}.png` under `desktopApp/build/test-output/`.

`MusicSampleCatalogTest` covers the real sparse directory, all vanilla bank instrument sources, complete/invalid/linked BRR streams, terminal loop semantics, identities/aliases, waveform bounds, silence, provenance, and display-only labels. `MusicSamplePickerTest` checks search/filter/usage, explicit apply/cancel, native preview/pitch wiring, noise, renaming, loading/unlisted states, and exact preview geometry for both themes/all font sizes at 680/420 px widths. `NotePropertiesPanelTest` also checks the real inspector's source callbacks, unchanged note identity, and stationary bottom anchoring during sample previews. `MusicSampleSelectionTest` checks shared-only selection/undo, save/reopen/export parity, common-slot source selection through instrument restoration, sibling imports, rename identity through source undo/redo/reset, stale jobs, and cancellation/retry. The audition fixture captures accepted Clip bytes and compares candidate/committed native audio without requiring a physical audio device. Captures are `sample-browser-{THEME}-{FONT}-{WIDTH}-recordings.png` under `desktopApp/build/test-output/`.

`MusicContinuationExportTest` checks all destination banks, compact changed-byte accounting, exact preview/export RAM parity, re-export with hook reuse, continued-source aliases, headered ROMs, partial-entry ordering, contiguous authored writes, allocator failure, and corrupt untouched continuations. Its native tests use the exported pointer table via the real game queue, repeated reentry, music playback, soft-reset/title loading, and separate silent-control effect fixtures. `SpcTransferStreamTest` covers ordered discontiguous physical ownership, last-writer mapping, exact hook recognition, corrupt code, cycles, source-span reuse, invalid pointers/markers, hop limits, and native word-aligned header overflow.

The original shared-ownership checkpoint passed 143 sound-focused tests offline, with 3 optional real-world IT fixtures skipped. The subsequent forward-only cleanup removes the legacy-only tests along with their implementation; current verification is recorded below.

Forward-only cleanup validation on 2026-10-24: 142 sound-focused tests passed offline; 3 optional real-world IT fixtures were skipped. The tests exercise native import/preview/export consistency, required sequence baselines, bank-only persistence, note-only undo/redo without bank records, shared reset/save/reopen, and UI layout at all font sizes. No saved project files needed sound-data removal.

Bank-transition checkpoint validation on 2026-10-24: 154 sound-focused tests passed offline; 3 optional real-world IT fixtures were skipped. UI status captures were generated for all font sizes and the largest inspected. Transition assertions use serialized exported ROM chains applied to retained RAM, including actual vanilla bank coverage and a spacious in-memory ROM fixture for multi-bank reentry. A separate vanilla-ROM fixture verifies allocation failure is atomic. No CPU loader hook, save-format migration, project-data cleanup, or automatic ROM expansion was added.

Preview/inspector polish validation on 2026-10-25: 165 sound-focused tests passed offline; 3 optional real-world IT fixtures were skipped. Browser and note-inspector captures were generated for every editor font size, with both inspector themes and a 480 px window-height fixture. The largest browser, light/dark inspectors, and scrolled short-window inspector were visually inspected. Delayed output startup is a deterministic injected-Clip regression, not a live hardware trace. The user's running editor and project files were left untouched.

Native-shaping checkpoint validation on 2026-10-25: 187 sound-focused tests passed offline; 3 optional real-world IT fixtures were skipped. Native-rendered fixtures verify attack, held-note fade, fixed gain, falling-gain silence, and octave tuning; pure/scene fixtures cover byte fidelity, one-gesture commits, keyboard release/repeat/focus loss, tuning rounding, and UI layouts. Largest-font envelope and gain captures were visually inspected in both themes, along with narrow wrapping and a scrolled 480 px inspector. Bank/import tests verify source references across persistence and native-source changes, and preview/ROM byte parity. No running-app restart or project-file mutation was performed.

Recording-chooser checkpoint validation on 2026-10-25: 216 sound/project-format regressions passed offline; 3 optional real-world IT fixtures were skipped. Actual ROM fixtures cover every vanilla bank's instrument sources, including the linked loop identified above. Native candidate/committed audio matched byte-for-byte for audible recording and noise fixtures; no physical audio device was required. Source-only edits retain envelope/tuning, share across siblings, survive real save/reopen and undo/redo, and match ROM export. Common-resource rejection leaves ROM bytes untouched. Layout captures were generated at all font sizes in both themes and at 680/420 px widths; the largest layouts and full bottom-anchored inspector were visually inspected. No app restart, saved-project cleanup, legacy compatibility layer, sample creation, or expanded export permissions were added.

Bounded-ROM checkpoint validation on 2026-10-25: 240 sound/project-format/allocator regressions passed offline; 3 optional real-world IT fixtures were skipped. All 24 native music banks support covered slot-24 edits with allocation disabled, exact byte accounting, and preview/export RAM parity. Differently authored slot-28 edits plus all three derived destination resets now fit the original ROM without expansion. Headered/overlapping/aliased sources, complete entry addresses, cross-bank erased-looking payloads, shared-session protection, and atomic mixed-plan failures are covered. An additional 34 broader export regressions passed (`RomExporterSafetyTest`, `RomWritePlanTest`, `ProjectRoomExporterTest`); one existing patch-configuration message assertion failed at `RomExporterSafetyTest.kt:182`, reproduced with the same ROM on unchanged `5ae22d8` in a temporary detached checkout. No unrelated patch/rendering fixes, saved-project changes, app restart, or loader-hook permission changes were made. These checks validate serialized exports and retained RAM, not a CPU/APU continuation hook.

Native-continuation checkpoint validation on 2026-10-25: 252 sound/project-format/allocator regressions passed offline; the same 3 optional real-world IT fixtures were skipped. `SMEDIT_REQUIRE_MUSIC_UPLOAD_RUNTIME=true` required the local core, diagnostic ABI, and Asar: all five native CPU/APU tests and seven loader/assembler/scope tests ran, with no native skips. Actual warm uploads cover all 24 native banks, repeated reentry, narrow/wide CPU callers, native LoROM header crossing, unmodified-loader leakage, restored common/bank-local settings, resumed music, and one audible command from each effect library. The all-bank prototype occupies 907 bytes including alignment and preserves every retained music chain/pointer. Full existing sound/UI/native-audition/save/export regressions remain green. The application exporter still rejects common-resource edits atomically; no hook was enabled in user exports, no saved projects were rewritten, and the user's running app was not restarted. This is not full gameplay/queue/soft-reset or exhaustive effect/dependency certification.

Wider validation: the complete shared JVM suite ran 904 tests (900 passed, 2 skipped, 2 failed). Both failures are existing `EnemySpriteRenderTest` assertions expecting Elevator tile data to be non-null (lines 50 and 71). Reproduced the same two failures with the same verified vanilla ROM on unchanged commit `e7f69af` in a temporary detached checkout; no enemy/rendering changes were made for this sound checkpoint.

Continuation-export checkpoint validation on 2026-10-26: **269 sound/project-format/allocator regressions passed offline**, with 3 optional real-world IT fixtures skipped. `SMEDIT_REQUIRE_MUSIC_UPLOAD_RUNTIME=true` required all seven native CPU/APU cases to run; none skipped. Native acceptance covers serialized exported ROM pointers through the original music queue, differently edited common/bank-local entries, repeated reentry, music, independently audible effects (libraries 1/2 command 1, library 3 command 5), and the original soft reset back to title music. Headered/aliased/partial-entry/continued-source tests and atomic failure checks passed. UI captures were regenerated for all font sizes; the largest blocked-sample-directory details were visually inspected. No save-format migration, compatibility layer, project-file mutation, ROM expansion, app restart, push, or physical-speaker test was performed. Unrestored sample-directory/BRR/engine writes remain blocked; full gameplay, effect-usage/dependency certification, and new sample creation remain future work.

An additional 20 broader `RomWritePlanTest` / `ProjectRoomExporterTest` regressions passed for this checkpoint (289 passed in total, plus the same 3 optional skips).
