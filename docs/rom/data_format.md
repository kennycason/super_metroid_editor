# Super Metroid ROM Data Format Reference

Verified against authoritative sources:
- **Kejardon's `mdb_format.txt`** — room header, state select, room state byte layouts
- **Kejardon's `PLM_Details.txt`** — PLM header structure, door PLM specifics
- **Patrick Johnston's bank $8F disassembly** — fully annotated PLM sets, state conditions
- **Metroid Construction wiki** — room data format page

All addresses are for an **unheadered** LoROM image unless noted.

---

## LoROM Address Mapping

```
PC offset = ((bank & 0x7F) * 0x8000) + (snes_offset - 0x8000)

Examples:
  $8F:8000 → PC 0x078000
  $8F:91F8 → PC 0x0791F8
  $A1:8000 → PC 0x108000
  $84:8000 → PC 0x020000
```

---

## Room Header (11 bytes, bank $8F)

```
Offset  Size  Field
  0      1    Room index
  1      1    Room area (00=Crateria, 01=Brinstar, 02=Norfair, 03=WS, 04=Maridia, 05=Tourian, 06=Ceres)
  2      1    X position on minimap
  3      1    Y position on minimap
  4      1    Width (in screens, 0-indexed: 00 = 1 screen)
  5      1    Height (in screens, 0-indexed)
  6      1    Up scroller
  7      1    Down scroller
  8      1    Special graphics bitflag (00=normal, 01=unload CRE, 02=refresh CRE, 05=Ceres/Kraid)
  9-10   2    Door out pointer [bank $8F]
```

Max room area = 50 screens (width × height). Max dimension = 0x0F (16 screens).

---

## State Select (variable length, immediately after room header)

State conditions are checked in order; the first that passes is used.
The final entry is always `E5E6` (default), whose 26-byte state data follows inline.

| Code   | Name                              | Args after code | Total bytes |
|--------|-----------------------------------|-----------------|-------------|
| `E5E6` | Default                           | 26-byte state data follows | 28 |
| `E612` | Event [X] is set                  | 1-byte event + 2-byte state ptr | 5 |
| `E629` | Boss [X] is dead                  | 1-byte boss + 2-byte state ptr | 5 |
| `E652` | Morph ball + missiles collected   | 2-byte state ptr | 4 |
| `E669` | Power bombs collected             | 2-byte state ptr | 4 |
| `E5EB` | Door pointer = [X] *(unused)*     | 2-byte arg + 2-byte state ptr | 6 |
| `E5FF` | Main area boss dead               | 2-byte state ptr | 4 |
| `E640` | Morph ball collected *(unused)*   | 2-byte state ptr | 4 |
| `E678` | Speed booster collected *(unused)*| 2-byte state ptr | 4 |

State pointers are 16-bit offsets in bank `$8F`.

**Example** — Landing Site (`$8F:91F8`), state select after 11-byte header:
```
E612 xx PPPP   → event "Zebes timebomb set" → state $9261
E669    PPPP   → power bombs collected      → state $9247
E612 xx PPPP   → event "Zebes is awake"     → state $922D
E5E6           → default, 26-byte state data for $9213 follows
```

### Implementation: `RomParser.findAllStateDataOffsets()`

---

## Room State Data (26 bytes, bank $8F)

Each state condition points to one of these blocks. The default state's block
follows inline after the `E5E6` code.

```
Offset  Size  Field                        Bank    Notes
  0-2    3    Level data pointer           any     Compressed LZ2/LZ5 tile data
  3      1    Tileset index                        0-28, indexes tileset table at $8F:E6A2
  4      1    Music: collection
  5      1    Music: track
  6-7    2    FX1 pointer                  $83
  8-9    2    Enemy population pointer     $A1     List of enemy entries
 10-11   2    Enemy GFX/set pointer        $B4     Which enemy tilesets to load
 12-13   2    Layer 2 scrolling                    Y-axis byte, X-axis byte
 14-15   2    Scroll pointer               $8F     Room scroll data (00/01/02 per screen)
 16-17   2    RoomVar / Unknown                    Usually 0000
 18-19   2    Main ASM / FX2 pointer       $8F
 20-21   2    PLM set pointer              $8F     ← Critical for door/gate/item placement
 22-23   2    BG data pointer              $8F
 24-25   2    Setup ASM / Layer1_2         $8F     FX0/setup code
```

### Implementation: `RomParser.readStateData()`

---

## PLM Set Format (bank $8F)

Each PLM entry is **6 bytes**. The set is terminated by a 2-byte `0x0000`.

```
Offset  Size  Field
  0-1    2    PLM ID (pointer to PLM header in bank $84)
  2      1    X position (in 16×16 blocks from left edge of room)
  3      1    Y position (in 16×16 blocks from top edge, +2 for HUD)
  4-5    2    Parameter (meaning varies by PLM type)
```

**Terminator:** `00 00` (2 bytes)

Total set size = `(N × 6) + 2` bytes, where N = number of PLMs.

### PLM Parameter Meanings

| PLM Type       | Parameter meaning |
|----------------|-------------------|
| Door caps      | Door index into room's door-out table |
| Scroll PLMs    | Pointer to scroll modification command (bank $8F) |
| Gate top (C836)| Color/direction selector (see gate param table) |
| Items          | Item index for pickup tracking |
| Save station   | Save station index |
| Grey doors     | High byte 0x90 = door bit tracking |

### Implementation: `EditorState.exportToRom()` — PLM set writing

---

## PLM Header Format (bank $84)

PLM headers live in bank `$84`. The PLM ID in a PLM set entry is the
16-bit offset of the header within bank `$84`.

**Standard PLMs** — 4-byte header (2 pointers):
```
Offset  Size  Field
  0-1    2    Setup routine pointer (bank $84)
  2-3    2    First instruction pointer (bank $84)
```

**Door PLMs** — 6-byte header (3 pointers):
```
Offset  Size  Field
  0-1    2    Setup routine pointer (bank $84)
  2-3    2    Open instruction pointer (bank $84)
  4-5    2    Close instruction pointer (bank $84)
```

> **CRITICAL:** Because door PLMs have 6-byte headers, consecutive
> door directions (Left, Right, Up, Down) are spaced **6 bytes apart**,
> not 4. Getting this wrong produces invalid PLM IDs that crash the game.

---

## Door Cap PLM IDs

All door cap PLMs have 6-byte headers. Left → Right → Up → Down are +6 each.

### Opening Door Caps (shootable, placed on door tiles)

| Color  | Left   | Right  | Up     | Down   |
|--------|--------|--------|--------|--------|
| Grey   | `C842` | `C848` | `C84E` | `C854` |
| Yellow | `C85A` | `C860` | `C866` | `C86C` |
| Green  | `C872` | `C878` | `C87E` | `C884` |
| Red    | `C88A` | `C890` | `C896` | `C89C` |
| Blue   | `C8A2` | `C8A8` | `C8AE` | `C8B4` |

### Closing Door Caps (auto-close behind Samus)

| Color  | Left   | Right  | Up     | Down   |
|--------|--------|--------|--------|--------|
| Blue   | `C8BA` | `C8BE` | `C8C2` | `C8C6` |

Blue closing caps have 4-byte headers (standard PLM format).

### Door Cap Setup Routines

| Setup  | Behavior |
|--------|----------|
| `C7B1` | Colored doors: set block to shootable, BTS 0x44 |
| `C7BB` | Blue doors: if power bomb, delete PLM; else make block solid |
| `C794` | Grey doors: complex condition checking |

### Implementation: `EditorState.seedBuiltInPatterns()` — door pattern definitions

---

## Gate PLM IDs

Gates use a single PLM ID `C836` ("Shot Gate Top PLM") for all variants.
The **parameter** selects color and direction:

| Param | Gate type |
|-------|-----------|
| `0x00` | Blue, facing left |
| `0x02` | Blue, facing right |
| `0x04` | Pink/Red, facing left |
| `0x06` | Pink/Red, facing right |
| `0x08` | Green, facing left |
| `0x0A` | Green, facing right |
| `0x0C` | Yellow/Orange, facing left |
| `0x0E` | Yellow/Orange, facing right |

Gate setup routine `C6E0` reads the parameter to select the appropriate
instruction list for open/close animations.

Gate patterns are 1×4 tiles (the gate extends downward from the PLM position).

### Implementation: `EditorState.seedBuiltInPatterns()` — gate pattern definitions

---

## Other Common PLM IDs

| PLM ID | Type | Notes |
|--------|------|-------|
| `B703` | Scroll PLM | Normal scroll trigger; param = ptr to scroll command |
| `B63B` | Rightwards extension | Copies scroll trigger rightward |
| `B647` | Upwards extension | Copies scroll trigger upward |
| `B76F` | Save station | Param = save station index |
| `B6DF` | Energy refill station | |
| `B6EB` | Missile refill station | Param = station index |
| `B6D3` | Map station | |
| `B70B` | Elevator platform | |

---

## Item PLM Graphics System (bank $84)

Item PLMs display their sprites using CRE tile table entries and VRAM tile data.
There are two distinct categories with different rendering mechanisms:

### Expansion Items (ETank, Missile, Super Missile, Power Bomb)

Use **hardcoded CRE metatiles** that are always present in VRAM. No dynamic loading.

| Item           | Frame 0 metatile | Frame 1 metatile | DF0C value |
|----------------|-----------------|-----------------|------------|
| Energy Tank    | 0x4A            | 0x4B            | 0x08       |
| Missile        | 0x4C            | 0x4D            | 0x0A       |
| Super Missile  | 0x4E            | 0x4F            | 0x0C       |
| Power Bomb     | 0x50            | 0x51            | 0x0E       |

Their setup routines ($EE4D-$EE5C) store fixed DF0C values and use direct draw data
pointers in their instruction streams. **No slot limit.**

### Upgrade Items (Bombs through Reserve Tank — 17 types)

Use instruction `$8764` to DMA custom 8×8 tile graphics from bank `$89` into
the last 2 rows of CRE VRAM, and write tile table entries for metatiles 0x8E-0x95.

**Slot allocation** — `$84:8764` uses counter `$7E:1C2D`:
```
$8764: LDA $1C2D       ; current slot (0, 2, 4, or 6)
       STA $7EDF0C,X   ; assign to this PLM
       ...
       INC A
       INC A           ; next slot
       AND #$0006      ; wrap: 0→2→4→6→0→2→4→6...
       STA $1C2D       ; store back
```

| Slot | $1C2D | Frame 0 metatile | Frame 1 metatile | VRAM dest  |
|------|-------|-----------------|-----------------|------------|
| 0    | 0     | 0x8E            | 0x8F            | $3E00      |
| 1    | 2     | 0x90            | 0x91            | $3E80      |
| 2    | 4     | 0x92            | 0x93            | $3F00      |
| 3    | 6     | 0x94            | 0x95            | $3F80      |

The `AND #$0006` hard-codes a **4-slot limit**. The 5th upgrade item PLM wraps
to slot 0 and overwrites the 1st item's graphics. The draw pointer tables at
`$84:E05F` (frame 0) and `$84:E077` (frame 1) each contain exactly 4 entries.

Items still **function correctly** when collected (PLM ID and param are unaffected) —
only the visual sprite is wrong.

### Graphics source (bank $89)

Each upgrade item's `$8764` argument points to its 8×8 tile data in bank `$89`:

| Item           | Bank $89 ptr | Palette bytes                    |
|----------------|-------------|----------------------------------|
| Bombs          | $8000       | 00 00 00 00 00 00 00 00          |
| Gravity Suit   | $8100       | 00 00 00 00 00 00 00 00          |
| Spring Ball    | $8200       | 00 00 00 00 00 00 00 00          |
| Varia Suit     | $8300       | 00 00 00 00 00 00 00 00          |
| Hi-Jump Boots  | $8400       | 00 00 00 00 00 00 00 00          |
| Screw Attack   | $8500       | 00 00 00 00 00 00 00 00          |
| Space Jump     | $8600       | 00 00 00 00 00 00 00 00          |
| Morph Ball     | $8700       | 00 00 00 00 00 00 00 00          |
| Grapple Beam   | $8800       | 00 00 00 00 00 00 00 00          |
| X-Ray Scope    | $8900       | 01 01 00 00 03 03 00 00          |
| Speed Booster  | $8A00       | 00 00 00 00 00 00 00 00          |
| Charge Beam    | $8B00       | 00 00 00 00 00 00 00 00          |
| Ice Beam       | $8C00       | 00 03 00 00 00 03 00 00          |
| Wave Beam      | $8D00       | 00 02 00 00 00 02 00 00          |
| Plasma Beam    | $8E00       | 00 01 00 00 00 01 00 00          |
| Spazer         | $8F00       | 00 00 00 00 00 00 00 00          |
| Reserve Tank   | $9000       | 00 00 00 00 00 00 00 00          |

### CRE VRAM reservation (MapRandomizer context)

MapRandomizer's `load_plms_early.asm` explicitly skips the last 2 CRE rows during
door transitions to avoid clobbering item PLM graphics:
- Ordinary rooms: load `$1C` rows (28 of 32), reserving 4 8×8-tile rows for items
- Kraid's Room: loads all `$20` rows (no items allowed)
- `validate_bts.py` flags metatiles 0x8E-0x95 as overwritable by item PLMs

---

## Door-Out Table (bank $8F)

Pointed to by room header bytes 9-10. Contains a list of 2-byte pointers
to Door Data Blocks (DDBs) in bank `$83`.

```
Entry 0: PPPP   → DDB for door index 0
Entry 1: PPPP   → DDB for door index 1
...
```

Door cap PLM params and door-tile BTS values reference indices into this table.

### Door Data Block (DoorDef, 12 bytes, bank $83)

```
Offset  Size  Field
  0-1    2    Destination room ID (SNES pointer in bank $8F)
  2      1    Bitflag (elevator bit, region switching; 0x40 = cross-area)
  3      1    Direction (0=Right, 1=Left, 2=Down, 3=Up; +4 for closing door cap)
  4      1    x_pos_plm — X block position of door cap PLM (used by SpawnDoorClosingPLM)
  5      1    y_pos_plm — Y block position of door cap PLM
  6      1    Spawn screen X coordinate
  7      1    Spawn screen Y coordinate
  8-9    2    Samus distance from door (0x8000 = use default)
  10-11  2    Door ASM pointer (bank $8F, scroll update code)
```

**Note**: Bytes 4-5 are the position where `SpawnDoorClosingPLM` ($82:E8EB) places
the blue closing cap if no colored cap PLM exists in the room's PLM set at that position.
See `~/code/sm/src/sm_82.c` DoorDef structure and `SpawnDoorClosingPLM()`.
In our editor these are stored as a single 16-bit `doorCapCode` field (little-endian: x=low, y=high).

---

## Level Data (Compressed, any bank)

Pointed to by state data bytes 0-2 (3-byte SNES address). Compressed with
LZ2/LZ5 format.

### Decompressed Layout

For a room of width W screens and height H screens, with `totalBlocks = W × H × 256`:

```
Offset              Size              Content
0-1                 2 bytes           Layer 1 size in bytes (= totalBlocks × 2)
2                   layer1Size bytes  Layer 1 metatile words (16-bit, little-endian)
2 + layer1Size      totalBlocks bytes BTS data (1 byte per block)
2 + layer1Size      totalBlocks × 2   Layer 2 metatile words (optional, only if
  + totalBlocks     bytes             bgScrolling == 0 in room state — embedded L2)
```

**Important**: The order is `[L1][BTS][L2]`, NOT `[L1][L2][BTS]`.
Layer 2 is only present when `bgScrolling == 0` (embedded L2). When `bgScrolling != 0`,
the background uses a separate scrolling tilemap pointed to by `bgDataPtr`.

### 16-bit Tile Word Format

```
Bits 15-12: Block type
  0=Air, 1=Slope, 3=Treadmill/Speed, 5=H-copy, 8=Solid, 9=Door,
  A=Spike, B=Crumble, C=Shot, D=V-copy, E=Grapple, F=Bomb

Bits 11:    V-flip
Bits 10:    H-flip
Bits 9-0:   Metatile index (0-1023)
```

### BTS (Block Type Special)

One byte per tile. Meaning depends on block type:

| Block Type | BTS meaning |
|------------|-------------|
| 0x9 (Door) | Door index into room's door-out table |
| 0x1 (Slope) | Slope angle/shape index |
| 0xB (Crumble) | 0x00-0x03=reform (sizes), 0x04-0x07=permanent, 0x0E/0x0F=speed booster |
| 0xC (Shot) | 0x00-0x03=any weapon (sizes), 0x04-0x07=hidden, 0x08/0x09=PB, 0x0A/0x0B=super |
| 0xF (Bomb) | 0x00-0x03=reform (sizes), 0x04-0x07=permanent |

**Shot block BTS detail** (verified against PLM table at `$94:9EA6`):
- 0x00-0x03: Any weapon breakable (beam, missile, bomb, super, PB) — reform, sizes 1×1 to 2×2
- 0x04-0x07: Hidden (invisible until X-Ray/revealed) — same breakability, sizes 1×1 to 2×2
- 0x08: Power bomb only, reform
- 0x09: Power bomb only, permanent
- 0x0A: Super missile only, reform
- 0x0B: Super missile only, permanent
- 0x0C-0x0F: Map to PLM `$B62F` (no-op) — **non-functional in vanilla SM**

### Implementation: `RomParser.decompressLZ2WithSize()`, `LZ5Compressor.compress()`

---

## Enemy Population Set (bank $A1)

Pointed to by state data bytes 8-9 (16-bit offset in bank `$A1`).

Each enemy entry is **16 bytes**:
```
Offset  Size  Field
  0-1    2    Enemy ID
  2-3    2    X position (pixels)
  4-5    2    Y position (pixels)
  6-7    2    Init parameter
  8-9    2    Properties (word 1)
 10-11   2    Properties (word 2)
 12-13   2    Extra parameter 1
 14-15   2    Extra parameter 2
```

**Terminator:** `FFFF` (2 bytes, at the enemy ID position)

After the terminator: 1 byte for "required kill count" (usually 0x00).

---

## Station PLM Placement Rules

Station PLMs (Energy Refill `$B6DF`, Missile Refill `$B6EB`, etc.) have specific
requirements derived from the game engine (verified via `snesrev/sm` decompilation):

### Runtime behavior (`PlmSetup_PlmB6DF_EnergyStation` at `$84:B21D`)

When a station PLM spawns, its setup function modifies three blocks:
- **Center** (PLM position): block type set to `0x8` (solid)
- **Left** (x-1, same y): block type `0xB`, BTS `0x4A` (left-access trigger)
- **Right** (x+1, same y): block type `0xB`, BTS `0x49` (right-access trigger)

The PLM's draw instruction renders the station tiles (the animated sprite).

### Activation conditions (`PlmSetup_B6E3_EnergyStationRightAccess`)

When Samus collides with a BTS trigger block, an access PLM spawns and checks:
1. Samus is **facing the station** (ran-into-wall pose)
2. Samus is **NOT at full health** (`samus_health != samus_max_health`)
3. **Pixel-exact Y alignment**: `samus_y_pos == trigger_block_y * 16 + 11`

If Samus is at full health, the station silently acts as a solid wall. No error.

### Y alignment requirement

The Y check means the **floor must be exactly 2 blocks below the PLM center**.
Standing Samus has a Y radius of ~20 pixels. The math works out to:

```
samus_y_pos = floor_top_pixel - 1 - y_radius
            = (plm_y + 2) * 16 - 1 - 20
            = plm_y * 16 + 11  ✓
```

### Pattern layout (3×3)

```
Row 0 (plm_y-1): decorative top tiles     (blockType 0x0, air)
Row 1 (plm_y):   station center + sides   (blockType 0x8, solid — overridden by PLM)
Row 2 (plm_y+1): bottom-center tile       (blockType 0x8, solid)
                  floor at plm_y+2         (must be solid — existing room data)
```

Bottom-left and bottom-right pattern cells are `null` (skip painting, preserve room data).

### Missile station (`$B6EB`)

Same rules apply. Setup writes BTS `0x4B`/`0x4C` instead. Activation checks
`samus_missiles != samus_max_missiles` instead of health.

---

## Export Process Summary

Our `EditorState.exportToRom()` follows this order:

1. **Apply IPS patches** — custom code/data written to ROM first
2. **Scan free space** — banks `$8F` (PLM sets), `$A1` (enemies), `$C0-$CE` (level data)
3. **For each edited room:**
   - Recompress level data (LZ5), write in-place or relocate to free space
   - Build new PLM set, write to free space in bank `$8F`, update all state pointers at offset +20
   - Build new enemy set, write to free space in bank `$A1`, update all state pointers at offset +8
   - Update door-out table entries as needed
4. **Apply GFX patches** — tileset modifications

Patches are applied **before** free space scanning to prevent conflicts
(e.g., `skip_intro` patch writes ASM to bank `$A1` that must not be overwritten
by relocated enemy data).

---

## Sources

- Kejardon's docs: https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/
- Patrick Johnston's bank logs: https://patrickjohnston.org/bank/8F
- Metroid Construction wiki: https://wiki.metroidconstruction.com/doku.php?id=sm:technical_information:room_data_format
- SMILE RF documentation: https://metroidconstruction.com/SMMM/
