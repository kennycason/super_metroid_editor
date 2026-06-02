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
| `$6C00-$6CE9` | 234B    | Instrument table                            |
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

## SMEDIT Implementation Map

| Area | File | Role |
|------|------|------|
| Track/sample browser UI | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundEditor.kt` | Track list, sample list, waveform view, Edit Track / Replace WAV actions |
| Sound state | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundEditorState.kt` | Selected track/sample state, native SPC previews, WAV import/export, sample replacement patch creation, piano-roll playback state |
| Piano roll UI | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/PianoRollEditor.kt` | Virtualized track visualization/editing, 1x-8x zoom, seek bar, note insertion/removal/move/resize, right-click note properties, playback cursor |
| Audio playback | `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/SoundPlayer.kt` | JVM `Clip` playback for mono 16-bit PCM and WAV export helpers |
| ROM/SPC transfer data | `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/SpcData.kt` | Song set pointer table, transfer block parsing, ARAM snapshots, sample directory parsing, BRR decode/encode, in-place sample replacement writes |
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

Current piano-roll editing behavior:

- Switching tracks while playback is active stops the old clip and auto-plays the newly rendered track.
- Entering or leaving Edit Track stops current playback and disables Play All to avoid overlapping waveform and piano-roll previews.
- Reset reparses original N-SPC data, discards added notes, stops playback, and leaves the cursor at tick `0`.
- Stop leaves the cursor at tick `0`; closing the piano roll hides it.
- Right-clicking a note opens editable note properties: tick, length, pitch, velocity, quantize, instrument, delete.
- Left-drag moves notes, right-edge drag resizes note length, arrow keys move/transposes the selected note, and Delete/Backspace removes it.
- Drag operations use transient preview state and commit note data once on release; SPC re-encode/render should stay on explicit playback/export paths.
- Waveform preview and piano-roll preview use RMS-aware preview normalization before JVM playback. Raw rendered waveform storage is kept separate so exported WAV data is not silently mastered. Edit Track monitor playback additionally applies `EDIT_TRACK_PREVIEW_GAIN` to compensate for quiet piano-roll renders.

## Parser/Renderer Caveats

- Native SPC rendering is the audio ground truth when `libspc` is available.
- `NspcRenderer` has broader command-side state handling than the current
  `NspcSequence.parse` piano-roll parser, including `E0` instrument changes,
  transpose, channel volume, and subroutine calls.
- `NspcSequence.parse` currently records commands but does not fully apply every command
  to note state, so piano-roll metadata such as `instruments=[0]` can be simplified even
  when the native audio preview is correct.
- `NspcSequence.encode` writes simplified one-block channel data into the original
  sequence area and guards against overflow into the instrument table at `$6C00`.

## External Resources

- **SM-SPC**: `/Users/kenny/code/super_metroid/SM-SPC/` — fully symbolic SPC engine source (asar-assemblable)
- **SM disassembly**: `/Users/kenny/code/super_metroid/sm_disassembly/src/` — CPU-side bank sources
- **sm decompilation**: `/Users/kenny/code/super_metroid/sm/` — C reimplementation, including `src/spc_player.c`, `src/spc_player.h`, and `src/spc_variables.h`
- **Sounds reference**: `docs/reference/sounds.txt` — community reference for all sound hex edits
- **Hex edits**: `docs/rom/hex_edits.txt` (lines 1190+) — sound effect tables and ROM offsets
