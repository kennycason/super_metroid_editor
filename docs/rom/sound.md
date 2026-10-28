# Super Metroid Sound System

## Overview

SM's audio runs on the SPC-700 coprocessor with 64KB of ARAM. The CPU uploads data via a
transfer protocol; the SPC-700 runs the N-SPC music engine independently.

SMILE has **no audio features** — it only edits room music selection (song set + play index)
via the room header. All actual audio editing requires hex/assembly work or custom tools.
SMEDIT goes beyond SMILE with native SPC emulation, WAV rendering, and sample replacement.

## Architecture

```
CPU (65816)                          SPC-700
  |                                    |
  |-- UploadToAPU ($80:8024) --------->|  Transfer blocks -> ARAM
  |-- APU IO $2140-$2143 ------------->|  Track/SFX commands
  |                                    |
  |   Music_Pointers ($8F:E7E1)        |  N-SPC engine ($CF:8000)
  |   Song set -> 3-byte ptr           |  Tracker sequencer
  |   to transfer block chain          |  BRR sample playback
```

## ARAM Layout (Vanilla)

| Address       | Size    | Content                                     |
|---------------|---------|---------------------------------------------|
| `$0000-$00DF` | 224B    | Direct page (IO cache, music state, DSP)    |
| `$00E0-$01CF` | 240B    | Stack + SFX RAM                             |
| `$0200-$04A7` | 680B    | Per-track state (volume, ADSR, vibrato...)  |
| `$0500-$14FF` | 4096B   | Echo buffer                                 |
| `$1500-$56E1` | ~17KB   | SPC engine code (N-SPC v1.20)               |
| `$5800-$5807` | 8B      | Note ring length table                      |
| `$5808-$5817` | 16B     | Note volume table                           |
| `$5820-$6BFF` | ~5KB    | Tracker data (music sequences per song set) |
| `$6C00-$6CFB` | 252B    | Instrument table (42 slots x 6 bytes)        |
| `$6D00-$6D9F` | 160B    | Sample directory (40 entries x 4 bytes)      |
| `$6E00-$FFFF` | ~40KB   | BRR sample data                             |

## Transfer Block Format

Song set data is a chain of transfer blocks stored in ROM banks `$CF-$DF`:

```
[u16 size] [u16 ARAM dest] [size bytes of data]   block 0
[u16 size] [u16 ARAM dest] [size bytes of data]   block 1
...
[u16 0x0000] [u16 entry_addr]                      terminator
```

- Size = 0x0000 signals end of chain
- Each block writes `size` bytes to ARAM starting at `dest`
- Blocks are applied in order; later blocks overwrite earlier ones at the same address

Instrument edits may add a small prefix ending in `[u16 0xFFFF] [u24 ROM pointer]`.
SMEDIT installs a verified 43-byte CPU reader for this continuation marker; the retained
music chain then continues within the same APU receive session. It is not a vanilla
transfer block or a second engine upload. Unknown loader profiles are refused.

## Song Set Pointer Table (`$8F:E7E1`)

Packed array of 3-byte (24-bit) SNES pointers. The song set value is used directly as
a byte offset (song sets are multiples of 3).

| Offset | Song Set | Track             | ROM Pointer |
|--------|----------|-------------------|-------------|
| +0     | 0x00     | SPC Engine (base) | `$CF:8000`  |
| +3     | 0x03     | Title Screen      | `$D0:E20D`  |
| +6     | 0x06     | Empty Crateria    | `$D1:B62A`  |
| +9     | 0x09     | Lower Crateria    | `$D2:88CA`  |
| +12    | 0x0C     | Upper Crateria    | `$D2:D9B6`  |
| +15    | 0x0F     | Green Brinstar    | `$D3:933C`  |
| +18    | 0x12     | Red Brinstar      | `$D3:E812`  |
| +21    | 0x15     | Upper Norfair     | `$D4:B86C`  |
| +24    | 0x18     | Lower Norfair     | `$D4:F420`  |
| +27    | 0x1B     | Maridia           | `$D5:C844`  |
| +30    | 0x1E     | Tourian           | `$D6:98B7`  |
| +33    | 0x21     | Mother Brain      | `$D6:EF9D`  |
| +36    | 0x24     | Boss Fight 1      | `$D7:BF73`  |
| +39    | 0x27     | Boss Fight 2      | `$D8:99B2`  |
| +42    | 0x2A     | Miniboss Fight    | `$D8:EA8B`  |
| +45    | 0x2D     | Ceres             | `$D9:B67B`  |
| +48    | 0x30     | Wrecked Ship      | `$D9:F5DD`  |
| +51    | 0x33     | Zebes Explosion   | `$DA:B650`  |
| +54    | 0x36     | Intro             | `$DA:D63B`  |
| +57    | 0x39     | Death             | `$DB:A40F`  |
| +60    | 0x3C     | Credits           | `$DB:DF4F`  |
| +63    | 0x3F     | Last Metroid...   | `$DC:AF6C`  |
| +66    | 0x42     | Galaxy at Peace   | `$DC:FAC7`  |
| +69    | 0x45     | Baby Metroid      | `$DD:B104`  |
| +72    | 0x48     | Samus Theme       | `$DE:81C1`  |

## Sample Directory Format

At ARAM `$6D00`, up to 40 entries (DSP register `$5D` = `$6D`):

```
Per entry (4 bytes):
  [u16 BRR start address in ARAM]
  [u16 BRR loop address in ARAM]
```

## BRR Sample Format

SNES native compressed audio. Each BRR block = 9 bytes encoding 16 PCM samples:

```
Byte 0: header
  bits 7-4: range (shift amount, 0-12)
  bits 3-2: filter (0-3, prediction mode)
  bit 1:    loop flag (set on last block if sample loops)
  bit 0:    end flag (set on last block of sample)
Bytes 1-8: 16 nybbles (4-bit signed deltas), 2 per byte
```

Decoding: `sample[n] = (nybble << range >> 1) + filter_prediction(prev samples)`

## Music Triggering

Room states store two bytes for music:
- **Song Set** (MusicTrack): which instrument bank to load (index into pointer table)
- **Play Index** (MusicControl): which variant to play

Values for Play Index:
| Value | Meaning            |
|-------|--------------------|
| 0x00  | No change          |
| 0x01  | Samus appear jingle|
| 0x02  | Acquire item       |
| 0x03  | Elevator           |
| 0x04  | Pre-statue hall    |
| 0x05  | Song One           |
| 0x06  | Song Two           |
| 0x07  | Mute               |

The CPU maintains a music queue (`$0619`, 8 slots) processed by `HandleMusicQueue` at `$80:8F0C`:
- High bit clear: track command sent directly to SPC via APU IO
- High bit set: song set index, triggers full data upload via `UploadToAPU`

## Sound Effect Libraries

Three independent libraries, each on its own APU IO channel:

| Library | Channel | Voices | Purpose           | Queue RAM |
|---------|---------|--------|-------------------|-----------|
| Lib 1   | `$F5`   | 4      | Samus sounds      | `$0656`   |
| Lib 2   | `$F6`   | 2      | Enemy sounds      | `$0666`   |
| Lib 3   | `$F7`   | 2      | Misc sounds       | `$0676`   |

CPU-side queue functions in bank `$80`:
- `QueueSound_Lib1` at `$80:9051`
- `QueueSound_Lib2` at `$80:90D3`
- `QueueSound_Lib3` at `$80:9155`

Sound effects can be **song-independent** (use their own BRR data) or **song-dependent**
(borrow a BGM instrument — these sound different depending on which song set is loaded).

## CPU-SPC Transfer Protocol (`$80:8024`)

Handshake sequence:
1. CPU writes `$FF` to APU IO 0 (request upload)
2. CPU waits for APU IO 0..1 = `$AA $BB` (SPC boot ROM ready signal)
3. CPU sends `$CC` kick byte via APU IO 0
4. For each block: CPU sends dest in IO 2..3, data byte-by-byte in IO 1, index in IO 0
5. SPC echoes index back for flow control
6. On terminator (size=0): CPU sends entry address, final kick byte

SPC side: `receiveDataFromCpu` at ARAM `$1E8B` mirrors the protocol.

## SMEDIT Sample Replacement (Option B)

Strategy: append a new transfer block to the song set's chain that overwrites the
target sample's ARAM address with new BRR data. The original blocks stay untouched.

1. Build SPC RAM snapshot to find sample directory entry and BRR location
2. Encode imported WAV to BRR (resample to 32kHz, mono 16-bit)
3. Create new transfer block: `[newBrr.size] [sample.startAddr] [newBrr data]`
4. If BRR size changed, also patch the DIR entry's loop address via another block
5. Serialize the full chain (original + new blocks)
6. Write in-place if it fits, otherwise relocate to free space in `$CF-$DF` and update pointer table
7. If no free space, expand ROM to 4MB

Current branch implementation note (2026-06-02): `SpcData.buildSampleReplacementWrites`
uses an in-place overwrite of the exact ROM bytes backing a sample's BRR data. If an
imported BRR is larger than the original slot, it is trimmed to the original BRR footprint
and the final BRR block's end flag is forced on. It does not yet append a transfer block,
patch DIR entries, relocate song-set transfer chains, or expand the ROM.

## Standalone Sample Workbench

**Sounds → Samples → New sample** creates a standalone recording asset, without changing
the project or a loaded bank. Generate sine/triangle/pulse/saw/noise, or import a mono/stereo
WAV (up to 1.024 seconds, converted to 32 kHz). Set level, trim and loop start on native
16-frame boundaries. Oversize files are rejected instead of silently trimmed.

The waveform shows the encoded BRR decode. **Play recording** auditions those bytes through the
native SPC engine at recorded rate in disposable RAM. It is not generic PCM playback or
a fallback synth. Loop entries reset predictor history for stable subsequent iterations.

From a note's **Edit sound → Try new recording**, open the same workbench with a detached
snapshot of that bank. **Play as sound** uses the selected sound's actual envelope, gain,
tuning, and note pitch/length/velocity/gate. Generate a recording or **Open draft** to try a
saved asset. This changes neither the selected sound nor the arrangement; close the dialog
to return. The draft remains standalone and still needs an explicit local save.

The bank fit check finds contiguous BRR space without reclaiming any boot/bank uploads,
padding, sibling imports, relocated arrangements, or directory-referenced start/loop data.
Only gaps in `$582C..$6BFF` are candidates. Oversize recordings disable **Play as sound**
with a trimming suggestion; **Play recording**, saving and raw BRR export remain available.
For example, the original Wrecked Ship bank has only a 217-byte gap under this conservative
policy, so the default 288-byte waveform does not fit. This is a data-space check, **not**
proof of a free instrument/SRCN slot, ROM capacity, or safe bank transitions. A bank import
that changes the boot SPC engine is refused for this tryout, not played through a fallback.

**Save draft / Open draft** preserve source PCM and all authoring parameters in a versioned
`.smedit-sample` file. Save explicitly to keep work across app restarts; project save does
not include standalone drafts. **Export BRR** writes headerless raw compressed data: keep
the draft for its loop offset, which also appears in the workbench. Asset writes are atomic.

Adding a draft to a music bank is the next stage, not an enabled action. Sample-slot
ownership, persistent SPC/ROM allocation and shared DIR/BRR lifetime checks still need implementation;
the instrument-only continuation loader does not make arbitrary sample insertion safe.

## Track Import/Export Formats

SMEDIT exposes import/export from the main track page next to **Export WAV**, and from the
piano-roll editor toolbar `Import` and `Export` dropdowns:

| Format | Extension | Direction | Purpose | Caveats |
|--------|-----------|-----------|---------|---------|
| MIDI | `.mid`, `.midi` | Import/export | Common editable interchange for DAWs and trackers | Loses SNES-specific command detail. Export maps SMEDIT instrument IDs to best-effort General MIDI programs for more plausible generic playback, and writes SMEDIT text metadata so reimport can restore the original Super Metroid instrument IDs. It still cannot carry BRR samples, ADSR, echo, pitch modulation, or DSP state. Import packs chords/all source channels into eight non-overlapping SNES voices, pairs stops before clamping pitch to C1-B6, maps external GM programs approximately to playable bank sounds, and reports excess notes, missing stops, and unsupported events. Native long notes export with ties; editable percussion uses the actual global base. |
| Impulse Tracker | `.it` | Import | Tracker module import path used by mITroid-style workflows | The importer parses IT headers, instrument keyboard maps, sample metadata, orders, packed patterns, speed-adjusted note timing, tempo commands, note stops, and all 64 IT pattern channels, then deduplicates/voice-packs them into Super Metroid's 8 music voices. For modules with embedded mono uncompressed PCM samples that fit in SPC RAM, it converts samples to BRR and stores a native payload containing N-SPC sequence data, mITroid-style C5-derived instrument pitch bytes, instrument table bytes, sample directory entries, and BRR sample blocks. IT-compressed samples, stereo samples, invalid instrument headers, over-budget sample payloads, and most tracker effects are reported and fall back to editable sequence import without a custom BRR payload. |
| SPC snapshot | `.spc` | Import/export | Native SNES SPC-700 snapshot for playback/testing | Export is a playable snapshot of the current editable song and retained imported samples when native `libspc` is available, or a static ARAM image fallback. Import is sequence-only: compatible N-SPC data must be found in SPC RAM, and its custom sample bank is not retained. Arbitrary SPC dumps may not map back to piano-roll data. |
| SMEDIT N-SPC bundle / raw transfer payload | `.nspc` | Import/export | Lossless editable bundle for SMEDIT track round-trips, or raw transfer blocks when a preserved native payload exists | The normal `.nspc` export is SMEDIT's native editable track bundle, not a universal SNES music standard. Raw N-SPC and supported IT imports retain their source sample/instrument blocks after note/instrument edits and project save/reopen. Their `.nspc` export combines retained blocks with the latest edits as raw transfer blocks. |
| BRR sample | `.brr` inside ROM sample data | ROM patch path | Native compressed sample payload used by the SPC engine | Current WAV replacement still rewrites/trims the existing BRR footprint in place. Relocating BRR data and patching DIR entries remains future work. |

Track imports are staged as a preview before replacing the current piano-roll song. The
preview reports note count, active channel count, total ticks, tempo, encoded N-SPC byte
estimate, budget overages, and format-specific warnings. **Apply** replaces the editable
song and creates an undo point; **Cancel** leaves the open track unchanged. Reset always
returns the open track to original ROM data and clears any pending import.
Undo/redo restores both the editable song and its associated imported source bank.
Manual instrument overrides live once per song set, in `musicInstrumentBanks`.
Saved tracks contain arrangements and optional native sources, not instrument snapshots.
There is no legacy sound migration or conflict-choice UI. Reset Track
keeps shared manual overrides; **Reset sound** resets one shared slot with undo support.
The compact bank-load status runs a background shared-resource preflight. Its expandable
details identify affected sounds and banks. Export derives compact reset uploads for
common and bank-local instrument entries that a later bank does not normally reload.
Shared sample pointers/BRR and engine bytes that are not restored still block ROM export; isolated audition and
SPC/N-SPC export remain available. The check is not a full command/effect-usage or ROM-space
guarantee. All music chains and reset uploads must fit before any music ROM bytes are written.
Existing covered bank bytes are patched in place when no other music bank shares those
ROM locations. Missing instrument entries use prefixes; other new upload ranges and
aliased sources still require isolated relocated chains. The
exporter inventories setup and all native pointer entries, including unused bank `$48`,
preserves the complete native terminator/entry address, and reserves all required space
before changing even an in-place sound. This reduces ROM-space use; it does not relax
the sample-data or effect-sharing restrictions. The exporter installs the exact verified
instrument-only loader when needed, or reuses its verified installation on re-export.
Preview/export/source mapping share a strict reader that follows discontiguous streams,
tracks all owned physical spans, and rejects malformed, overlapping or cyclic chains.
The complete serialized ROM is re-read and checked before any music bytes are committed.

CPU/APU regression coverage includes retained-RAM bank changes via the original game
music queue, repeated reentry, LoROM crossings, audible music/effects, and the original
soft-reset path back to title music. Effect libraries run separately with silent controls.
The fixture uses an isolated test ROM, not the user's running editor; this is not
exhaustive gameplay or effect/dependency certification. See `docs/sound/sound_encode.md`.

## SMEDIT Implementation Map

| Area | File | Role |
|------|------|------|
| Track/sample browser UI | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundEditor.kt` | Track list, sample list, waveform view, Edit Track / Replace WAV actions |
| Standalone sample authoring | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SampleAuthoring.kt`, `SampleWorkbench.kt`, `SampleWorkbenchState.kt` | Waveform/WAV creation, loop/trim, native encoded audition, reusable draft assets and raw BRR export; no bank insertion |
| Bank-aware sample tryout | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MusicSamplePlacement.kt` | Conservative contiguous data-space planning and private audition through a selected native sound; no unused-slot claim, project writes or export permission |
| Sound state | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundEditorState.kt` | Selected track/sample state, native SPC previews, WAV import/export, sample replacement patch creation, piano-roll playback state |
| Piano roll UI | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/PianoRollEditor.kt` | Virtualized track visualization/editing, 1x-8x zoom, seek bar, note insertion/removal/move/resize, right-click note properties, playback cursor |
| Track interchange | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MusicTrackInterchange.kt` | MIDI read/write, SMEDIT `.nspc` editable bundle read/write, and compatible `.spc` snapshot import reports |
| Retained native imports | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/ImportedMusicResolver.kt` | Low-level retained-source/baseline decoding and safe sequence relocation; project-wide composition belongs to the bank resolver |
| Shared bank resolution | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MusicBankResolver.kt`, `MusicInstrumentBanks.kt` | Combine bank sources and arrangements, validate shared settings, and apply one bank-owned override per slot |
| Bank-transition safety | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MusicBankTransitions.kt`, `MusicBankSafetyStatus.kt` | Check incoming/outgoing resource lifetimes, derive instrument resets, and show compact export-blocking details |
| Music ROM transaction | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MusicRomExporter.kt`, `MusicTransferLoader.kt` | Protect source spans, reserve compact prefixes/isolated chains, verify and install the native loader, re-read serialized results before committing |
| Audio playback | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundPlayer.kt` | JVM `Clip` playback for mono 16-bit PCM and WAV export helpers |
| ROM/SPC transfer data | `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/SpcData.kt` | Song set pointer table, transfer block parsing, ARAM snapshots, sample directory parsing, BRR decode/encode, in-place sample replacement writes |
| Strict ROM transfer reader | `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/SpcTransferStream.kt` | Validate native/continued streams and the exact loader profile; preserve physical source ownership and last-writer mapping |
| N-SPC sequence model | `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/NspcSequence.kt` | Editable song model, piano-roll parser, simplified encoder back to N-SPC writes |
| Kotlin fallback renderer | `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/NspcRenderer.kt` | Sequence parser and sample-based renderer used when native SPC render is unavailable or insufficient |
| Native SPC emulator | `shared/src/jvmMain/kotlin/com/supermetroid/editor/rom/NativeSpcEmulator.kt` | JNA bridge to blargg `snes_spc`, builds SPC file images from ARAM, applies transfer blocks, sends play commands, renders mono/stereo PCM |
| Native SPC build | `shared/build.gradle.kts`, `tools/snes_spc/` | Builds and packages `libspc` resources for JNA |

## Fixed Editor Issue: Piano Roll Zoom Constraints

Symptom seen while opening/zooming Lower Norfair:

```
[SPC-PIANO] Parsed: 4668 notes, 2278 commands, 7 active channels, 46469 ticks, tempo=27
java.lang.IllegalArgumentException: Can't represent a size of 373352 in Constraints
```

Root cause: the piano roll measured a Compose child at `gridWidth.dp`, where
`gridWidth = totalTicks / ticksPerPixel`. Lower Norfair has about 46,469 parsed ticks
plus 200 display padding. At the 16x zoom level (`ticksPerPixel = 0.125`), that becomes
roughly 373,352 display units. Compose Desktop's packed `Constraints` representation
cannot encode dimensions that large, so layout fails before drawing.

Current branch behavior: `PianoRollEditor` virtualizes the horizontal piano-roll canvas.
The measured child stays viewport-sized, while draw code treats `hScrollPx` as the world
offset and renders only visible ticks/notes. Zoom currently exposes `1x`, `2x`, `4x`, and
`8x`, so Lower Norfair no longer asks Compose to measure an unrepresentable width.
The N-SPC piano-roll parser also now ends synchronized song blocks when a participating
channel reaches its block end marker, matching the preview parser and avoiding the old
46,469-tick Lower Norfair stretch. Current Lower Norfair parses to about 7,397 ticks
at tempo 27, which exports as roughly 2.3 minutes of MIDI instead of about 15 minutes.

Current piano-roll editing behavior:

- Switching tracks while playback is active stops the old clip and auto-plays the newly rendered track.
- Entering or leaving Edit Track stops current playback and disables Play All to avoid overlapping waveform and piano-roll previews.
- Reset reparses original N-SPC data, discards added notes, stops playback, and leaves the cursor at tick `0`.
- Stop leaves the cursor at tick `0`; closing the piano roll hides it.
- Right-clicking a note opens a compact inspector with its musical pitch, sound, and right-aligned Delete/Close actions. **Note details** reveals Start/Length in ticks, musical pitch (C1–B6), Velocity (0–15), and Gate (the native quantize field: 0 short, 7 full note length). These edits retain the existing undo history.
- The note property panel is a bottom overlay, so right-clicking a note does not push the piano roll down.
- The note inspector shows a sound card with **Play**, **Choose sound**, **Rename**, and **Edit sound**. Its searchable browser reports the filtered/available sound counts, used count, and source (**ROM bank** or **ROM + import**, including sibling imports). These are the instruments loaded into the current bank, not General MIDI presets. Zero-filled instrument slots and unavailable samples are excluded; the current sound and sounds used by the track come first. A scrollbar exposes the rest of the list. Generic descriptions such as “Short one-shot” describe sample/envelope metadata, not curated instrument names. Browsing and audition do not edit the arrangement; **Use sound** changes only the selected note and uses the existing undo history.
- Browser pitch choices are **This note (F1)** (using the selected note's actual pitch) and **Middle C (C4)** (a common comparison pitch); they are not sound IDs. The note inspector and raw fields follow the editor font setting and theme. Sound buttons use 6 dp corners to match the main navigation. An expanded inspector scrolls when the window is short.
- **Edit sound** opens a recording/noise source summary and compact native controls: Envelope/Gain modes, an envelope guide, Attack, Decay, Sustain level, Sustain fade, and source-relative Transpose/Fine tuning. **Change source** replaces the numeric sample-selection workflow with the current bank's sample chooser. **Advanced** is nested inside this editor and retains table address, read-only `SRCN`, sample pointers, BRR length/loop flag, ADSR1/ADSR2/GAIN bytes, and raw tuning. Choosing sounds and editing them are mutually exclusive views. A sound combines its sample, envelope, and tuning; its settings are shared across the bank, including sibling tracks.
- The sample chooser has recordings/noise tabs, names/source/ID search, a used-in-track filter, current/used-first ordering, waveform/loop guides, recorded length, shared/bank-ROM versus imported origin, import filenames, and known bank-instrument users (not proof a resource is unused by effects). Noise is generated rather than a recording; note/reference pitch chips are disabled there because its rate determines pitch. Waveforms describe a bounded decode, not the sustained note's duration or a loop's steady-state filter history. Unknown timbres keep factual one-shot/loop descriptions; source renaming is explicit, not a guessed instrument-name catalog.
- Recording labels live in display-only `musicSampleNames`, keyed by song set plus SHA-256 identity of encoded content and effective loop, not just a reusable sample number/address. Identical aliases in one bank share a label; changed recordings/loops do not inherit old names. Old resource labels remain available when undo restores their source. Names also appear in sound-card/browser descriptions and search without creating arrangement edits, bank overrides, ROM writes, or audio-version changes.
- Catalog reading uses the native `$6D00` directory's 40 slots, supports sparse entries and linked intro/loop or one-shot-tail chains, and excludes incomplete/invalid engine/table sources. The detached bank snapshot includes sibling native imports and provenance for directory/intro/loop uploads. Background decode is versioned/cancellable; instrument-only edits reuse the catalog. Previews render the candidate source through the selected sound's native envelope/tuning on private RAM, with fixed-size preview status/icons. Only **Use sample**/**Use noise** commits one shared-bank undo step; envelope/tuning and notes remain unchanged. Existing common-resource export restrictions still apply.
- The envelope uses native discrete rates: Sustain fade acts while a note is held, rate zero holds the level, and key-off release is fixed (no Release knob). Gain supports fixed level, linear/bent rise, and linear/exponential fall. Inactive envelope/gain bytes are preserved; inactive controls are hidden, and full sustain disables the irrelevant decay control. Falling gain and zero-rate rising gain start silent on a new note and disclose that fact. The graph is an isolated two-second key-on guide with fixed release, not a waveform or an exact sample/sequence/DSP-phase simulation.
- The instrument editor now uses a compact synth layout: a larger envelope guide, **Attack / Decay / Sustain / Held fade** knobs, and a separate **Transpose / Fine** module. Time knobs turn clockwise for longer times; underlying native rate bytes remain unchanged in meaning. **Shape → Pluck / Soft / Hold** applies native envelope starting points only, retaining the recording, tuning and inactive gain byte. Noise sources expose a rate knob and identify the shared noise clock rather than offering musical transpose controls. All shapes/knobs use the existing shared-bank persistence and undo history.
- Knobs use relative vertical dragging (no click-to-jump), Shift for finer motion, discrete keyboard arrows/Home/End, and accessible progress actions with readable units. Wheel input changes a knob only after it is focused and groups one burst into one edit; ordinary inspector scrolling does not alter unfocused controls. Pointer cancellation restores the draft, click jitter/return-to-start gestures create no edits, and focus loss finishes pending keyboard/wheel input. Narrow/short layouts scroll while preserving full-size targets. Late callbacks from a closed or different track cannot write to the newly selected bank. Explicit **Play** remains the audition action; the curve is a guide, not automatic live audio.
- Tuning offsets use the current ROM/imported bank **before** manual overrides, including sibling imports. They remain stable through save/reopen, import undo/redo, and track reset. Native 8.8 pitch quantizes/clamps the result; **Reset tuning** restores only the source tuning. Noise and missing/zero source tuning do not get a guessed musical reference. Control drafts update the guide locally and commit one undoable shared-bank edit on release; the explicit Play button renders the committed settings. Merely opening the editor creates no edits.
- The compact scope row separates note-local sound choice from bank-wide sound editing. Its dropdown lists known tracks loading that bank; it is not proof that every track/effect uses the selected slot. `BANK` badges indicate shared changes without an explicit arrangement edit.
- Common instrument slots 0–23 are not reloaded by vanilla music-bank transfers (which begin at slot 24 / `$6C90`). Their edits can carry into later banks at runtime. Export derives compact instrument reloads for both common settings and bank-local slots 24–41, including rain/voice banks which upload fewer entries. The derived resets do not become project edits or change another bank's authored settings. Unrestored sample-directory/BRR/engine changes remain blocked; effect dependency analysis remains future work.
- `MusicSampleRestoration` is a detached **prototype** for future bank-owned recordings. It derives complete DIR-entry and touched BRR-block reloads, closes over secondary write footprints across other banks, and rejects incomplete recordings, engine writes and oversized plans. Isolated CPU/APU fixtures verify generated recordings, all 24 native destinations, reentry, soft reset, independent effect audio and an unmodified-loader leak control. Their unrestricted serializer is test-only: production export remains instrument-only for derived restoration, and no sample slot is claimed or **Add to bank** action enabled. Saved bank resources, slot/dependency ownership and atomic production integration are still required.
- Instrument tuning in engine RAM is big-endian 8.8 fixed point. `InstrumentEntry.pitchAdj` packs the two raw bytes little-endian; `nativePitch` and `tuningMultiplier` expose the engine interpretation. The approximate renderer uses the native pitch-table scaling and multiplier; its envelope remains approximate.
- Instrument audition uses a separate RAM snapshot and native player at fixed monitor gain. It preserves note attacks instead of discarding the first second, disables echo for an isolated comparison, and limits held notes to two seconds plus release. Starting another preview, stopping, or switching tracks cancels pending audition playback. No software fallback is presented as native audition. Playback startup reports an explicit accepted/empty/output-failed result: Java Sound may not set `isRunning` until after `start()` returns. Preparing/playing/finished/error text shares a permanent fixed-height status row, with a fixed-size spinner slot and Stop button; row preview icons/spinners also have matching bounds, preventing preview transitions from resizing the overlay.
- Instrument-table fields are shared playback configuration, not per-note data. `SRCN`, ADSR1, ADSR2, GAIN, and pitch-adjust are editable preview fields patched into SPC RAM at `$6C00 + index*6`. Supported `.it` imports can now carry generated sample-directory and BRR blocks as a preserved native payload; ordinary manual sample start/loop pointer editing remains read-only.
- Left-drag moves notes, right-edge drag resizes note length, arrow keys move/transposes the selected note, and Delete/Backspace removes it.
- Drag operations use transient preview state and commit note data once on release; SPC re-encode/render should stay on explicit playback/export paths.
- Resize snaps the drag delta rather than a native note's original length. Click jitter and drags returned to their starting position do not change notes or create undo entries. Drafts with overlapping notes remain editable: an inline voice-conflict warning replaces the budget meter and disables track playback until repaired. Undo/Redo, moving, shortening and deleting notes still work; native encoding/ROM export continues to reject overlaps rather than silently shifting or discarding notes.
- Waveform preview and piano-roll preview use RMS-aware preview normalization before JVM playback. Raw rendered waveform storage is kept separate so exported WAV data is not silently mastered. Edit Track monitor playback additionally applies `EDIT_TRACK_PREVIEW_GAIN` to compensate for quiet piano-roll renders.
- `NspcSequence.parse` applies `E0` instrument and `EA` transpose state to parsed notes. New notes inherit nearby note instrument, velocity, quantize, and duration so user-added notes render with valid Super Metroid instrument state.
- Modified native SPC preview uses protected-range allocation and fail-fast sequence encoding. If an edited sequence cannot fit, an approximate renderer can retain the imported sample bank without writing across protected assets. Quiet or muted native import output is valid and does not trigger an audible fallback.
- Shared-bank project previews fall back to approximate sample-based rendering if native encoding/rendering is unavailable, retaining the bank's imported samples and shared overrides. They do not substitute a vanilla track. Older projectless preview helpers retain their hybrid delta-overlay path.

## Parser/Renderer Caveats

- Native SPC rendering is the audio ground truth when `libspc` is available.
- `NspcSequence.parse` applies `E0` instrument and `EA` transpose state to note metadata,
  but it still records many commands without fully modeling their playback-side effects.
- `NspcRenderer` and native SPC playback are still better cross-checks for command-side
  behavior such as channel volume, subroutines, sample decode, and envelope playback.
- `NspcRenderer.readInstrumentTable` exposes instrument-table metadata for the UI.
- Edit Track preview can patch changed instrument-table bytes into SPC RAM. Raw N-SPC and supported IT source blocks are retained independently of later edits. `MusicBankResolver` combines all arrangements/sources in the current bank for preview and native exports, applying shared overrides once. Note edits relocate the sequence around protected assets; instrument-only edits preserve original sequence bytes. Native sources require an editable baseline captured at import time; missing baselines are not reconstructed when opening a project. Overlapping native sources fail explicitly. Siblings share the imported sample bank; cross-bank common-slot/sample isolation is not implemented.
- `NspcSequence.encode` writes simplified one-block channel data. Native import and
  ROM export paths supply a protected-free allocation and reject silent truncation.

## External Resources

- **SM-SPC**: `/Users/kenny/code/super_metroid/SM-SPC/` — fully symbolic SPC engine source (asar-assemblable)
- **SM disassembly**: `/Users/kenny/code/super_metroid/sm_disassembly/src/` — CPU-side bank sources
- **sm decompilation**: `/Users/kenny/code/super_metroid/sm/` — C reimplementation, including `src/spc_player.c`, `src/spc_player.h`, and `src/spc_variables.h`
- **Sounds reference**: `docs/reference/sounds.txt` — community reference for all sound hex edits
- **Hex edits**: `docs/rom/hex_edits.txt` (lines 1190+) — sound effect tables and ROM offsets
