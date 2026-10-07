# Super Metroid Engine Internals — Deep Reference

Gold-standard knowledge base for the SM ROM editor. Accumulated from binary analysis,
reference codebases, and empirical testing. Use this to avoid re-performing analysis.

---

## Reference Codebases

| Source | Path | What it provides |
|--------|------|------------------|
| **snesrev/sm** | `~/code/sm` | Full C reimplementation of SM. Bank-by-bank (`sm_80.c`–`sm_b4.c`). Structures in `ida_types.h`, variables in `variables.h`. PLM decode in `assets/plm_decode.py`. |
| **MapRandomizer** | `~/code/MapRandomizer` | Rust randomizer that reconnects rooms. Door handling in `rust/maprando/src/patch.rs`. Door geometry in `room_geometry.json`. Python door loader in `python/rando/rom.py`. |
| **SM Mod 3.0.80** | (external) | SMILE-based editor reference for PLM editing and ROM patching conventions. |
| **Patrick Johnston** | https://patrickjohnston.org/bank/ | Per-bank disassembly with full annotations. |
| **Kejardon's docs** | (via Patrick Johnston's site) | `mdb_format.txt`, `PLM_Details.txt` — definitive room/PLM format docs. |

---

## Door System — Complete Reference

### How Doors Work (end-to-end)

1. **Level data** has type-9 (door) blocks. BTS byte = index into room's door-out table.
2. **Door-out table** (room header bytes 9-10, bank $8F) lists 2-byte pointers to DDBs in bank $83.
3. **DoorDef** (12 bytes, bank $83) defines the transition: destination, direction, cap position, spawn point.
4. **Door cap PLMs** (in the room's PLM set) draw the colored shields at the door position.
5. **Collision**: when Samus hits a type-9 block, `BlockColl_Horiz_Door` / `BlockColl_Vert_Door` ($94) look up the BTS, resolve the DoorDef, and start the transition state machine.

### DoorDef Structure (12 bytes)

```
Offset  Size  Field                 C struct field
  0-1    2    Destination room      room_definition_ptr
  2      1    Bitflag               door_bitflags (0x40=cross-area)
  3      1    Direction             door_orientation (0=R,1=L,2=D,3=U; +4=closing)
  4      1    Door cap X            x_pos_plm
  5      1    Door cap Y            y_pos_plm
  6      1    Spawn screen X        x_pos_in_room
  7      1    Spawn screen Y        y_pos_in_room
  8-9    2    Distance from door    samus_distance_from_door (0x8000=default)
  10-11  2    Door ASM              door_setup_code (bank $8F)
```

Source: `~/code/sm/src/ida_types.h:260` — `typedef struct DoorDef`

### SMEDIT Door Authoring and Diagnostics

The normal editor workflow is semantic: users choose a destination room by name and area, select
or copy a known entrance, and use one-based screen columns/rows constrained to that destination's
dimensions. DoorDef addresses, cap coordinates, distance, and entry ASM remain available under the
door panel's **Advanced** disclosure, but are not required for an ordinary connection.

When SMEDIT derives a closing-cap position it searches the selected destination **screen** edge,
not merely the outer edge of the whole room. Verified vanilla geometry uses a one-block inward
offset for horizontal entrances and a two-block inward offset for vertical entrances. A zero cap
position is also valid and intentionally suppresses an ordinary closing-cap placement in scripted
or elevator transitions.

Connection health is evaluated against the project's effective destination header, level/BTS data,
and edited door lists. The editor reports:

- a missing destination or out-of-bounds entrance as an error;
- no compatible destination opening as a warning;
- no return door as an advisory one-way warning;
- a return door without the opposite facing direction as a warning.

One-way links are legal and may be intentional. Diagnostics never create, rewrite, or remove the
return link automatically. Door tiles receive a red outline for errors and an amber dashed outline
for warnings; the selected door's panel explains the reason in plain language.

### Door Transition State Machine (15 steps)

Source: `~/code/sm/src/sm_82.c` and `~/code/sm/src/sm_80.c`

| Step | Function | Purpose |
|------|----------|---------|
| 1 | `HandleElevator` | Elevator vs normal door |
| 2 | `Wait48frames` | Optional delay |
| 3 | `WaitForSoundsToFinish` | |
| 4 | `FadeOutScreen` | |
| 5 | `LoadDoorHeaderEtc` | Read DoorDef fields |
| 6 | `ScrollScreenToAlignment` | |
| 7 | `FixDoorsMovingUp` | |
| 8 | `SetupNewRoom` | Load room header, evaluate states |
| 9 | `SetupScrolling` | |
| 10 | `PlaceSamusLoadTiles` | Decompress level data |
| 11 | `LoadMoreThings_Async` | **ClearPLMs → CreatePlms → SpawnDoorClosingPLM** |
| 12 | `HandleAnimTiles` | |
| 13 | `WaitForMusicToClear` | |
| 14 | `HandleTransition` | |
| 15 | `FadeInScreenAndFinish` | |

Step 11 is critical: `DoorTransitionFunction_LoadMoreThings_Async` ($82:DFD1) calls:
```
ClearPLMs()
CreatePlmsExecuteDoorAsmRoomSetup()   ← spawns room PLMs from the state's PLM set
LoadFXHeader()
SpawnDoorClosingPLM()                 ← spawns blue cap if no colored cap exists
```

### SpawnDoorClosingPLM ($82:E8EB)

```c
void SpawnDoorClosingPLM(void) {
  if (!CheckIfColoredDoorCapSpawned()) {
    // Table is indexed by door_direction (0-7), NOT direction & 3
    uint16 plmId = kDoorClosingPlmIds[door_direction];  // at $8F:E68A
    if (plmId) {
      spawn PLM at (DoorDef.x_pos_plm, DoorDef.y_pos_plm) with param=0
    }
  }
}
```

**Critical**: `door_direction` comes from `DoorDef.door_orientation` (byte 3). Bit 2 is the
"spawn closing cap" flag. When bit 2 is clear (directions 0-3), the table has value 0 and
**no cap is spawned**. Only directions 4-7 (bit 2 set) trigger cap spawning.

`kDoorClosingPlmIds` (at $8F:E68A, 8 entries indexed by `door_direction`):

| Index | Dir | PLM ID | Effect |
|-------|-----|--------|--------|
| 0 | Right | 0x0000 | No cap spawned |
| 1 | Left  | 0x0000 | No cap spawned |
| 2 | Down  | 0x0000 | No cap spawned |
| 3 | Up    | 0x0000 | No cap spawned |
| 4 | Right+cap | 0xC8BE | Blue closing right-facing |
| 5 | Left+cap  | 0xC8BA | Blue closing left-facing |
| 6 | Down+cap  | 0xC8C6 | Blue closing down-facing |
| 7 | Up+cap    | 0xC8C2 | Blue closing up-facing |

**Fix for rogue doors**: Clear bit 2 of `door_orientation` in edited DoorDefs (set orientation
to `orientation & 0xFB`). This makes the game index entries 0-3 (all zero), preventing
SpawnDoorClosingPLM from spawning blue caps at stale positions.

### CheckIfColoredDoorCapSpawned ($82:E91C)

```c
uint8 CheckIfColoredDoorCapSpawned(void) {
  // Calculate block index for DoorDef's (x_pos_plm, y_pos_plm)
  uint16 blockIdx = 2 * (y_pos_plm * room_width_in_blocks + x_pos_plm);
  // Search active PLM block indices (up to 40 slots, index 39→0)
  for (slot = 39; slot >= 0; slot--) {
    if (plm_block_indices[slot] == blockIdx) {
      if (plm_header_ptr[slot] == 0) return 0;  // PLM deleted
      int16 param = plm_room_arguments[slot];
      if (param >= 0) {   // bit 15 clear → check door opened bit
        if (opened_door_bit_array[param] is set) return 0;  // already opened
      }
      // Switch PLM to its closing instruction list
      plm_instruction_timer[slot] = 1;
      plm_instr_list_ptrs[slot] = PlmHeader[plm_id].instr_list_2_ptr;
      return 1;
    }
  }
  return 0;  // no colored cap found → blue cap will be spawned
}
```

**Key behavior with params:**
- Param bit 15 SET (e.g., 0x9002): door bit check SKIPPED, cap always considered present
- Param bit 15 CLEAR (e.g., 0x0002): door bit IS checked; if opened, cap considered absent

**Impact**: If a room state's PLM set has NO door cap PLMs, `CheckIfColoredDoorCapSpawned`
returns 0 for every door entry, and blue closing caps are spawned at all door positions.
This is the root cause of "phantom blue doors."

### Door Cap PLM ID Table (complete)

#### Opening Caps (6-byte PLM headers — setup/open/close instruction pointers)

| Color  | Left   | Right  | Up     | Down   |
|--------|--------|--------|--------|--------|
| Grey   | C842   | C848   | C84E   | C854   |
| Yellow | C85A   | C860   | C866   | C86C   |
| Green  | C872   | C878   | C87E   | C884   |
| Red    | C88A   | C890   | C896   | C89C   |
| Blue   | C8A2   | C8A8   | C8AE   | C8B4   |

Spacing: +6 per direction (Left→Right→Up→Down), +24 per color group.

#### Closing Caps (4-byte PLM headers — setup/instruction only)

| Color  | Left   | Right  | Up     | Down   |
|--------|--------|--------|--------|--------|
| Blue   | C8BA   | C8BE   | C8C2   | C8C6   |

These are spawned dynamically by `SpawnDoorClosingPLM`, NOT stored in PLM sets.

#### Other Door-Related PLMs

| PLM ID | Description | Source |
|--------|-------------|--------|
| C8CA   | Wall in Escape Room 1 | MapRandomizer `plm_types_to_remove` |
| DB48   | Eye door (right) | MapRandomizer |
| DB4C   | Eye door (left) | MapRandomizer |
| DB52-DB60 | Eye door variants | MapRandomizer |
| B63F   | Left continuation arrow (used as "remove" placeholder) | MapRandomizer |

---

## PLM System

### PLM Header Formats (bank $84)

**Standard PLMs (4 bytes):**
```
+0: setup routine ptr (bank $84)
+2: instruction list ptr (bank $84)
```

**Door PLMs (6 bytes):**
```
+0: setup routine ptr (bank $84)
+2: open instruction list ptr (bank $84)
+4: close instruction list ptr (bank $84)  ← used by CheckIfColoredDoorCapSpawned
```

### PLM Lifecycle

1. `CreatePlmsExecuteDoorAsmRoomSetup` reads PLM set from current state
2. For each entry: `SpawnRoomPLM` allocates a slot, calls setup routine
3. `PlmHandler_Async` runs every frame: executes pre-instructions, main instructions, draw
4. PLM instructions: sleep, goto, draw, delete, conditional branches
5. 40 PLM slots total (indices 0-39)

Source: `~/code/sm/src/sm_84.c`

### PLM Set Format (bank $8F)

Each entry: 6 bytes (2-byte ID + 1-byte X + 1-byte Y + 2-byte param).
Terminated by 2-byte 0x0000.
See `docs/rom_data_format.md` for full format.

---

## Room State System

### State Evaluation Order

States are checked first-to-last. First matching condition wins. Default (E5E6) is always last.

**Complete selector inventory** (programmatically verified across all 263 rooms — see `docs/code/scan_state_selectors.py`):

| Code | Name | Entry size | Args after code | Vanilla usage |
|------|------|-----------|-----------------|---------------|
| E5E6 | Default (Finish) | TERMINAL | 26-byte inline state data | 263 rooms (all) |
| E5EB | Door | 6 bytes | door_ptr(2) + state_ptr(2) | **0 vanilla rooms** |
| E5FF | TourianBoss01 | 4 bytes | state_ptr(2) | 1 room (Mother Brain) |
| E60F | Never | 4 bytes | state_ptr(2) | **0 vanilla rooms** |
| E612 | IsEventSet | 5 bytes | event_flag(1) + state_ptr(2) | 24 rooms |
| E629 | IsBossDead | 5 bytes | boss_flag(1) + state_ptr(2) | 33 rooms |
| E640 | Morph Ball | 4 bytes | state_ptr(2) | **0 vanilla rooms** |
| E652 | MorphBallMissiles | 4 bytes | state_ptr(2) | 2 rooms |
| E669 | PowerBombs | 4 bytes | state_ptr(2) | 1 room (Landing Site) |
| E678 | Speed Booster | 4 bytes | state_ptr(2) | **0 vanilla rooms** |

**Dispatch**: the machine code at `HandleRoomDefStateSelect` ($8F:E5D2) reads the
2-byte address and jumps to it indirectly. The C decompilation represents addresses it
observed as a `CallRoomDefStateSelect` switch, but that switch is not a runtime whitelist.
E5EB/E640/E678 are callable vanilla entry points even though vanilla room data does not use
them. E60F enters the common `INX; INX; RTS` path and is used by SMART projects as an
always-false condition.

**SMEDIT graph relocation**: the selector loop enters every predicate with X immediately after
the routine word and resumes through an RTS return address it placed on the stack. SMEDIT uses that
verified ABI to preserve an existing room-header address when the selector list grows. The inline
list starts with a pointer to `LDA $0000,X; TAX; RTS`, followed by the relocated graph pointer. The
routine returns to the unmodified loop with X at the relocated vanilla-format graph. Its unreachable
`SMEDITSG` + version signature makes parser detection explicit. Only the selector graph and copied
26-byte state records move; DoorDef destinations, AreaSave/load-station room IDs, and hardcoded room
comparisons remain valid. See `docs/rom/data_format.md` for the exact bridge bytes.

Source: `~/code/sm/src/sm_8f.c:683` (`CallRoomDefStateSelect`),
`~/code/sm/assets/restool.py:933` (`kRoomStateSelects`),
`~/code/sm/assets/names.txt:7300` (function addresses).

54 rooms have multiple states. Notable:
- Landing Site (0x91F8): 4 states (2× IsEventSet, PowerBombs, Default)
- Mother Brain (0xDD58): 3 states (TourianBoss01, IsEventSet, Default)
- All Wrecked Ship rooms: 2 states (IsBossDead[Phantoon], Default)
- All Ceres rooms: 2 states (IsBossDead, Default)

### State Data (26 bytes)

Each state has independent pointers for level data, PLM set, enemies, scrolls, etc.
Different states CAN share pointers (common for level data) or have unique ones.

**Critical**: states with different PLM set pointers may have different door cap coverage.
The export handles each distinct PLM set pointer independently.

See `docs/rom_data_format.md` for byte layout.

---

## Level Data Compression

### LZ2/LZ5 Format

Both use the same command byte structure. SM's `DecompressToMem` ($80:B119) handles both.

| Cmd bits | Type | Data bytes | Description |
|----------|------|------------|-------------|
| 000 | Literal | N bytes | Copy N bytes verbatim |
| 001 | Byte fill | 1 byte | Repeat byte N times |
| 010 | Word fill | 2 bytes | Alternate two bytes N times |
| 011 | Incr fill | 1 byte | Incrementing sequence |
| 100 | Abs copy | 2-byte addr | Copy from earlier output (absolute) |
| 101 | Abs copy XOR | 2-byte addr | Copy from earlier, XOR 0xFF |
| 110 | Rel copy | 1-byte offset | Copy from earlier (relative to current) |
| 111 | Rel copy XOR | 1-byte offset | Copy from earlier, XOR 0xFF |

**Short format**: top 3 bits = cmd, bits 4-0 = length-1 (max 32).
**Extended format**: byte starts with 0xE0+, cmd in bits 4-2, length = ((byte & 3) << 8 | next) + 1 (max 1024).
**Terminator**: 0xFF.

Commands 0–6 have short and extended forms. Command 7 exists only in extended form:
`$FC..$FE` encode lengths 1..768, while `$FF` is consumed as the terminator before
command decoding. A command-7 length above 768 is therefore not representable.

SMEDIT's compressor uses commands 0–4 and 6. The shared strict decoder is modeled on
`Decompression_VariableDestination` at `$80:B119` in the pinned disassembly. Both
`RomParser` and export round-trip validation use that one implementation.

The decoder rejects missing terminators, truncated operands, references to unwritten
output, and output beyond the supplied destination capacity. The engine advances a
16-bit destination index without changing the destination bank, so the absolute
maximum is 64 KiB; a caller decompressing at a non-zero bank offset must supply the
smaller remaining capacity. The `$FF` terminator counts as a consumed source byte.

---

## Confirmed Findings

### Blue Phantom Doors (Root Cause — CONFIRMED Feb 2026)

**Mechanism**: `SpawnDoorClosingPLM` ($82:E8EB) spawns a blue closing cap when ALL of:
1. `door_direction >= 4` (bit 2 set in `DoorDef.door_orientation`)
2. `kDoorClosingPlmIds[door_direction]` is non-zero (always true for 4-7)
3. `CheckIfColoredDoorCapSpawned()` returns 0 (no matching cap PLM at DoorDef position)

**Why it happens in our editor**: When the user edits a DoorDef (e.g., changes destination),
the `door_orientation` byte is preserved from the original ROM — including bit 2 (cap flag).
The `x_pos_plm` and `y_pos_plm` are also preserved, but they may be invalid for the
new destination room (e.g., pointing outside room bounds).

**Vanilla SM also has rogue caps**: Even in vanilla, many DoorDef positions don't have
matching PLM caps. The game spawns blue closing caps every time you enter through those
doors. This is normal behavior but becomes very visible in custom hacks.

**Fix (implemented)**: During export, clear bit 2 of `door_orientation` for ALL edited
DoorDefs: `orientation = orientation & 0xFB`. This makes the game read
`kDoorClosingPlmIds[0-3]` which are all zero, so no blue closing cap is spawned.

Previous approach (PLM propagation) was ineffective because:
- Adding blue opening caps still results in visible blue doors (CheckIfColoredDoorCapSpawned
  switches them to closing animation)
- Only propagated from other states; couldn't help rooms with 1 state or doors with no
  cap in ANY state

### LZ5 Compression Compatibility (VERIFIED 2026-10-03)

The source-backed parity harness now provides independent evidence rather than testing
SMEDIT only against itself:

1. A Python model derived directly from `$80:B119` strictly decodes every extracted
   asset that is exactly one complete LZ5 stream.
2. The pinned NTSC corpus contains 421 such streams: 417 active and 4 unused. They
   include 250 level-data, 70 background, 38 tile, 25 palette, 23 tilemap, and 15
   tile-table payloads. The largest expands to 62,722 bytes.
3. SMEDIT's Kotlin codec matches the independent decoded size and SHA-256 for every
   stream.
4. Every decoded payload survives SMEDIT compress → strict decompress with identical
   bytes.
5. Vanilla exercises commands 0–6. It never uses command 7, so inverted sliding copy
   is covered by a dedicated synthetic format test instead of being attributed to the
   corpus.
6. Focused tests cover all eight commands, extended lengths, exact source consumption,
   malformed/truncated streams, invalid backreferences, and destination overflow.

Run `./gradlew parityReport` with `SMEDIT_TEST_ROM` configured to regenerate the live
evidence in ignored `parity/reports/lz5.json` and the aggregate report. See
[`../../parity/README.md`](../../parity/README.md).

### Background Programs and Embedded Layer 2 (VERIFIED 2026-10-06)

Room-state `bgDataPtr` is a 16-bit pointer into bank `$8F`'s library-background
interpreter at `$82:E5C7`. The pinned source contains 79 named programs (68 active +
11 unused), 305 commands, 200 state associations, 70 compressed background assets,
and 14 door-dependent transfers. All eight command forms are covered: terminate,
decompress, transfer, transfer-and-set-BG3-base, clear BG2, clear Kraid Layer 2,
door-dependent transfer, and reserved/no-op.

The production parser executes the static portions into simulated WRAM/VRAM and
reconstructs SNES screen blocks as row-major 32×32, 64×32, or Kraid 64×64 maps.
This fixes six 4 KiB backgrounds whose right half was previously truncated/repeated.
Runtime-generated WRAM inputs fail closed rather than drawing fabricated data, and a
door-dependent list uses a deterministic first variant until an incoming door is
provided by the map UI.

Embedded Layer 2 is identified by the optional `[L1][BTS][L2]` payload, not by the
room's BG-scrolling word. Those bytes are horizontal/vertical motion factors; 65
Layer-2-bearing state associations use nonzero motion. All 124 active embedded-L2
state associations remain available to the editor.

### Pause Maps and Map Stations (VERIFIED 2026-10-07)

`AreaMapPointers` at `$82:964A` selects seven 4 KiB bank-`$B5` tilemaps.
`MapData.pointers` at `$82:9717` selects seven unique 256-byte bank-`$82` reveal
masks; its eighth debug entry intentionally aliases Tourian. Both formats are two
32×32 pages. Reveal bytes are MSB-first, four bytes per row per page.

Stored row 0 is a fake/padding row. The engine maps room `(x,y)` to stored row
`y+1`, so room coordinates have 31 safe rows (`0..30`) even though the tilemap and
mask each contain 32 stored rows. A screen at room Y=31 would spill exploration data
into the next page or beyond the 256-byte buffer. SMEDIT therefore preserves all
64×32 stored cells for byte-exact round trips but prevents room creation and movement
from entering the padding row.

The pause-map graphics are the first `$2000` bytes / 256 standard-4bpp tiles of
`Tiles_PauseScreen_BG1_BG2` at `$B6:8000`, not the Layer-3 2bpp graphics at PC
`$D3200`. Tilemap words use the normal 10-bit tile, 3-bit palette, priority, H-flip,
and V-flip fields. The map-station PLM `$B6D3` sets an area flag; the area's static
`MapData` mask controls what becomes visible. Vanilla places one station in each of
areas 0-4 and none in Tourian or Ceres.

`parityMinimap` proves all 14,336 tilemap words, 1,792 mask bytes / 911 set cells,
five placements, pointer tables, transforms, graphics, room bounds, seven direct
engine consumers, and exact production no-op writes. See
[`minimap.md`](minimap.md) for addresses and formulas.

### Tileset and CRE Ownership (VERIFIED 2026-10-03)

`parityTilesets` parses the exact `$8F:E6A2` source table and proves all 29
tile-table/graphics/palette triples against the clean ROM and SMEDIT's detected
catalog. The 87 fields resolve to 55 source assets: 14 metatile tables, 16 graphics
sets, and 25 palettes. Twenty intentional alias groups are recorded and pinned.

The two CRE source ranges in bank `$B9` are adjacent: 8,349 compressed graphics
bytes at `$B9:8000..A09C`, then the 1,431-byte compressed table at `$B9:A09D`.
They decode to exactly 384 4bpp tiles (12,288 bytes) and 256 metatiles (2,048
bytes). Standard runtime ownership is:

- CRE graphics: tiles 640–1023, VRAM bytes `$5000..7FFF`; the door-transition
  staging path uses WRAM `$7E:7000..9FFF`.
- CRE metatiles: IDs `$000..0FF`, WRAM `$7E:A000..A7FF`.
- Tileset-specific metatiles: IDs `$100..3FF`, WRAM beginning `$7E:A800`.

All 1,024 words in the CRE metatile table reference tiles 640–1023. The complete
direct consumer set is two graphics routines (`$82:E3C0`, `$82:E78C`) and two
table-loading routines (`$82:E7D3`, `$82:EA73`). Ceres skips the separate CRE table
and loads its full tileset table at `$7E:A000`; door transitions may retain existing
CRE data unless the destination room's CRE bitset requests a refresh. The full
per-instruction inventory and decoded hashes live in ignored
`parity/reports/tilesets.json`; see
[`../graphics/tile_pipeline.md`](../graphics/tile_pipeline.md) for the named 29-row
asset map.

### Tile Formats and Metatile Semantics (VERIFIED 2026-10-03)

`parityTileFormats` independently decodes the named source assets, then tagged JVM
tests compare every result with SMEDIT's production paths. The verified corpus is
10,944 4bpp tiles across 16 unique tileset graphics payloads plus CRE, 256 standard
Layer-3 2bpp tiles, and 45,056 words across 14 unique tileset metatile tables plus
CRE. All 65,536 possible metatile words also round-trip their 10-bit tile index,
3-bit palette, priority, horizontal-flip, and vertical-flip fields.

Two exceptional Ceres graphics resources store 1,024 tiles as global plane halves:
all bp0/bp2 data, then all bp1/bp3 data. Kraid instead stores 1,024 conventional
interleaved tiles. The thirteen normal graphics resources each define 576 tiles in
the 640-slot variable runtime region, leaving tiles 576–639 blank before CRE begins
at 640. This distinction is asserted so an unowned reserved gap cannot be mistaken
for source art.

Kraid demonstrates that graphics and metatile ownership are separate: it suppresses
the CRE graphics overlay, but still combines the 256-entry CRE metatile table with
its 768-entry variable table. Details and exact layouts are in
[`../graphics/tile_pipeline.md`](../graphics/tile_pipeline.md); machine-readable
hashes live in ignored `parity/reports/tile-formats.json`.

### Animated-Tile Objects and DMA (VERIFIED 2026-10-03)

Bank `$87` contains 68 raw animated-tile payloads totaling 9,376 bytes. Twenty
six-byte object headers pair an instruction-list pointer with a byte count and VRAM
word destination. The source/ROM control-flow manifest reaches 94 unique timed frame
instructions (98 object-frame associations), and every frame's payload size matches
the owning object's transfer size. Sixty-five payloads are referenced; the three
explicit unused `X` ranges at `$87:9064/$92E4/$9F04` are preserved as orphans.

The FX byte at entry offset `+14` is an eight-bit animated-tile activation mask.
All eight area lists and their 64 bit-to-object mappings at `$83:AC56` are asserted.
The full direct consumer inventory is eleven spawn calls, three handler calls, and
the NMI DMA call to `$80:9416`, which transfers from fixed source bank `$87` to the
object's VRAM word destination. See
[`../graphics/tile_pipeline.md`](../graphics/tile_pipeline.md) for formats and the
destination table; machine-readable evidence is in ignored
`parity/reports/animated-tiles.json`.

This subsystem includes the Tourian entrance's animated boss **statues**. It does
not describe the live bosses' multi-part OAM, BG layers, staged DMA, or AI-driven
animation; those remain the `B-01..B-07` composition cases in the validation matrix.

### Item PLM Graphics and Runtime Slots (VERIFIED 2026-10-04)

The 17 upgrade pickups use 17 contiguous, uncompressed `$100`-byte standard-4bpp
payloads at `$89:8000..90FF`. Each payload contains eight tiles: four quadrants for
each of two animation frames. The three forms of every upgrade item—visible, Chozo
orb, and shot block—produce 51 source instruction lists and 51 PLM IDs. All match
SMEDIT's `RomParser.ITEM_DEFS` catalog.

`Instruction_PLM_LoadItemPLMGFX` at `$84:8764` queues a `$100`-byte bank-`$89` DMA
and writes eight metatile words using embedded palette indices. Its counter at
`$7E:1C2D` cycles `0→2→4→6→0`, selecting four slots: VRAM word destinations
`$3E00/$3E80/$3F00/$3F80`, tile IDs `$3E0..3FF`, and metatiles `$8E..95`. Thus a
fifth concurrent upgrade item wraps and visually replaces slot 0 without changing
collection semantics. Five palette profiles exist, and the two four-entry frame
draw tables are at `$84:E05F` and `$84:E077`.

`parityItemPlmGraphics` verifies the source declarations, rebuilt ROM bytes,
independent pixel hashes, all load arguments and palette bytes, runtime tables, draw
pointers, and interpreter call. Machine-readable evidence lives in ignored
`parity/reports/item-plm-graphics.json`; the detailed format is in
[`../graphics/tile_pipeline.md`](../graphics/tile_pipeline.md).

### Enemy Species Headers and GRAPHADR Ownership (VERIFIED 2026-10-04)

Bank `$A0` contains 164 assembled, 64-byte `EnemyHeader` records. The source-backed
manifest evaluates every expression in all 29 macro arguments per record—4,756 fields
total—and checks the resulting words/bytes, both four-byte zero-padding regions, and
the complete header against the rebuilt ROM. SMEDIT now exposes the same complete
record through `EnemySpriteGraphics.readSpeciesHeader`; both user-facing enemy catalogs
are constrained to actual source header starts rather than plausible bank offsets.

The header's 24-bit `tileData` / `GRAPHADR` value at `+$36` points to raw standard-4bpp
bytes, while `tileDataSize & $7FFF` gives the transfer length and bit 15 selects the
alternate runtime VRAM layout. Of 164 headers, 155 have nonempty graphics ranges, nine
have a zero transfer size, and six set the layout flag. The nonempty associations reduce
to 100 unique ranges over 93 unique starts and map contiguously through 168 segments to
99 named extracted assets.

Aliasing is part of the format, not manifest noise: 25 start-address groups and 24
exact-range groups are shared. Nine distinct range pairs overlap, including two
cross-start cases where Lava Rocks/Rinka extend into `Tiles_Squeept` and Geruta extends
into `Tiles_Holtz`. Six species ranges span more than one asset declaration; the two
Ridley headers each cover all five adjacent `Tiles_Ridley_*` chunks.

One Ridley render dependency crosses that ordinary owner boundary. The same enemy set
includes Ridley Explosion `$E1BF`, whose `$8400` size field selects a `$0400` transfer
and alternate layout; enemy-set word `$E001` places its `$B0:B400` bytes at buffer
offset `$0400`, which transfers to VRAM `$6E00` / physical OBJ tiles `$E0..FF`.
Ridley's facing-forward map reads that low page, while its normal body/wings/tail read
the main species page at `$100..1FF`. The focused `parityRidley` manifest pins the
cross-species placement; the generic header manifest continues to pin each byte owner.

`parityEnemyHeaders` verifies source expressions, symbol offsets, rebuilt-ROM bytes,
independent 4bpp pixel hashes, production parsing, catalog membership, and the complete
segment/alias/overlap inventory. Evidence lives in ignored
`parity/reports/enemy-headers.json`. Palette overrides, instruction control flow,
render classification, and edit UX remain separate E-03/E-06..E-10 work in the
validation matrix. See [`../graphics/sprites.md`](../graphics/sprites.md) for the
header layout.

### Enemy OAM and Extended Spritemaps (VERIFIED 2026-10-04)

The named source corpus in banks `$A0`, `$A2..AA`, and `$B2..B3` contains 2,312
standard spritemaps with 14,400 five-byte OAM entries, 811 extended/multibox
spritemaps with 1,984 child associations, and 99 extended tilemaps with 441 runs /
2,763 words. `parityEnemyOam` independently decodes these structures from the
byte-identical reference ROM and tests every field and pointer through
`EnemySpritemap`'s production parsers.

Standard counts may be zero for shared “nothing” structures. Extended counts use
only the low byte of the count word; the high byte is ignored by the engine, and
Ceres steam intentionally uses `$1001` to mean one child. Extended children contain
signed 16-bit X/Y offsets, a same-bank standard-OAM or `$FFFE` extended-tilemap
pointer, and a hitbox pointer. Instruction lists remain variable-width programs:
timed frames are `[duration, spritemap]`, while words at or above `$8000` dispatch
handlers with handler-specific operands and branches. `parityEnemyInstructions`
now pins all 1,139 named lists / 7,820 records and measures the present scanner:
2,543 of 4,395 renderable source frames recovered, 1,852 missed across 465 lists,
and no semantic execution of 502 unique handler addresses in the generic fallback.
A bounded interpreter now proves complete visual paths for Zoomer, Sidehopper, and
the grey walking Space Pirate (17 frame occurrences / 15 unique spritemaps), including
fallthrough, backward loops, sleep, repeated frames, and extended OAM. Broad
interpretation remains later E-06 work. Exact source routes additionally cover Puyo,
Owtch, Choot, both Sbug headers, Evir plus its projectile, Magdollite, Beetom, and both
Sidehopper corpse headers when helpers, direction tables, multi-slot ownership, or state
logic hide their selected lists from init-pattern scanning; these 46 actions preserve
AI-stepped poses, setup-list fallthrough, split runtime VRAM ownership, and exact
component boundaries rather than emulating arbitrary AI. See [`../graphics/sprites.md`](../graphics/sprites.md);
machine-readable evidence is in ignored `parity/reports/enemy-oam.json` and
`parity/reports/enemy-instructions.json`, with the three integrated slices in
`parity/reports/enemy-vertical-slices.json` and helper-selected routes in
`parity/reports/ordinary-enemy-animations.json`.

### Enemy Species Rendering Status (MEASURED 2026-10-04)

The source-complete E-08 ledger executes SMEDIT's production preview paths for every
one of the 164 bank-`$A0` headers. It classifies 145 as assembled, 15 as known
composites, zero as tile-sheet-only, four as nonvisual, and zero as failed; 159 species
produce at least one assembled frame. A tile sheet is explicitly not an assembled
render.

Eight active visual headers legitimately transfer zero graphics bytes. Their E-07
preview contract follows runtime ownership instead: Elevator and Ceres Steam borrow
the global standard sprite bank, Zebetite and Mother Brain tubes borrow Mother Brain
head graphics, cutscene Baby Metroid borrows the normal Baby Metroid payload, and the
three small corpse species borrow the Sidehopper-owned common corpse payload. Global
palette rows 5 and 2 are selected for Elevator/Steam and Zebetite respectively. These
providers are preview-only; a zero-byte species never gains a writable tile range.
`ProcessEnemyTilesets` copies palettes independently of the graphics byte count, so
the cutscene Baby Metroid, falling tubes, and three corpse palette rows remain
header-owned and editable even though their borrowed graphics do not.

The ledger is generated as ignored `parity/reports/enemy-species-status.json` and
`.md`, while totals and a deterministic row hash are pinned. The run also proved that
Botwoon's existing composite scanner was unreachable from the editor's known-pose
gate; that gate now routes Botwoon to its head-plus-13-segment renderer.

### PLM Set Handling Across States (VERIFIED)

Export correctly:
1. Finds all distinct PLM set pointers across all states
2. Reads original PLMs from each pointer
3. Applies user changes (add/remove) to each independently
4. Deduplicates item PLMs by position
5. Writes each set (in-place or relocated)
6. Updates all state offsets pointing to the original pointer

### State Selector Entry Sizes (VERIFIED Feb 2026)

Verified against `~/code/sm/src/sm_8f.c` function implementations AND programmatically
confirmed across all 263 vanilla rooms (324 total state entries, all parsed successfully
with zero errors — see `docs/code/scan_state_selectors.py`).

| Code   | Name             | Data after code | Total |
|--------|------------------|-----------------|-------|
| E5E6   | Default/Finish   | 26-byte inline state data | 28 |
| E5EB   | Door             | door_ptr(2) + state_ptr(2) | 6 |
| E5FF   | TourianBoss01    | state_ptr(2)               | 4 |
| E60F   | Never            | state_ptr(2)               | 4 |
| E612   | IsEventSet       | flag(1) + state_ptr(2)     | 5 |
| E629   | IsBossDead       | flag(1) + state_ptr(2)     | 5 |
| E640   | Morph Ball       | state_ptr(2)               | 4 |
| E652   | MorphBallMissiles| state_ptr(2)               | 4 |
| E669   | PowerBombs       | state_ptr(2)               | 4 |
| E678   | Speed Booster    | state_ptr(2)               | 4 |

**E5FF was previously mis-sized as 6 bytes** (treated like E5EB with a 2-byte param).
The C code confirms E5FF has NO parameter — it checks hardcoded boss bit 1.
Fix: grouped E5FF with E60F/E640/E652/E669/E678 as 4-byte entries.

**E5EB/E60F/E640/E678 never appear in vanilla room data** but are handled by our parser.
They are valid indirect-jump entry points/conventions despite not appearing in the
decompiler's switch.

### DoorDef Bytes 4-5 (CONFIRMED)

Bytes 4-5 of the DoorDef are NOT a 16-bit ASM pointer. They are:
- Byte 4: `x_pos_plm` (X block position of door cap)
- Byte 5: `y_pos_plm` (Y block position of door cap)

Confirmed via `~/code/sm/src/ida_types.h:260` (DoorDef struct) and
`~/code/sm/src/sm_82.c:4263` (SpawnDoorClosingPLM reads these as PLM position).

Our editor stores them as a single 16-bit `doorCapCode` (little-endian). Read/write
is symmetric so data is preserved, but the UI label is misleading.

---

## MapRandomizer Door Handling Reference

MapRandomizer (`~/code/MapRandomizer/rust/maprando/src/patch.rs`) handles doors by:

1. **Removing colored caps**: Replaces all colored door cap PLMs with 0xB63F (arrow)
2. **Writing door data**: Copies 12-byte DDBs between exit/entrance pointers
3. **Cross-area flag**: Sets byte 2 bit 0x40 for area transitions
4. **Custom ASM**: Bytes 10-11 can be replaced with custom door ASM
5. **Locked doors**: Spawned via setup ASM (`JSL $84F380`), not in PLM sets
6. **Save stations**: Entrance pointers updated when doors are reconnected

Key functions: `write_one_door_data`, `remove_non_blue_doors`, `fix_save_stations`

---

## Diagnostic Scripts (`docs/code/`)

Python scripts for iterative ROM analysis. Run these to validate changes without
re-deriving internals from scratch:

| Script | Purpose |
|--------|---------|
| `scan_state_selectors.py` | Scan all rooms, catalog state codes, validate parser coverage |
| `compare_doors.py` | Diff door entries + PLM sets between two ROMs |
| `dump_room_data.py` | Dump full room data (header, states, doors, PLMs) for one room |

See `docs/code/README.md` for usage examples.

---

## Open Questions

- [ ] Are blue closing cap PLMs (C8BA-C8C6) temporary or persistent? (Need to decode their instruction lists)
- [ ] Does the DB44 PLM (screen shaker at 8,8) affect game state or is it purely visual?
- [ ] What is PLM C8CA's exact behavior? (Described as "wall in Escape Room 1" by MapRandomizer)
- [ ] Should the editor expose per-state PLM set editing? (Currently merges all states into one view)
