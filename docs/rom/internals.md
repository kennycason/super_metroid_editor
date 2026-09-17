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

Our compressor (`LZ5Compressor.kt`) uses cmds 0-4,6. Round-trip verified against
our decompressor. Game's decompressor at `~/code/sm/src/sm_80.c:2488`.

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

### LZ5 Compression Compatibility (VERIFIED)

Our `LZ5Compressor` produces valid compressed data that SM's `DecompressToMem` handles
correctly. Verified by:
1. Round-trip: compress → decompress → compare with original
2. Command-by-command comparison with game's decompressor (`sm_80.c:2488`)
3. All 8 command types match the game's format

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
