# Samus Sprite System — ROM Data Reference

Complete reference for Samus's sprite data in the Super Metroid ROM.
Verified by test extraction (see `test-resources/samus_poses_sheet.png`).

---

## Architecture Overview

Samus sprites use a **4-tier indirection system**:

```
Pose ID (0x00-0xFC)
  → Frame Progression Table ($92:D94E)    [4 bytes per frame: top_table, top_entry, bot_table, bot_entry]
    → DMA Transfer Tables ($92:D91E/D938) [7 bytes per entry: 3-byte source ptr + 2x 2-byte sizes]
      → Raw 4bpp Tile Data (banks $9B-$9F) [32 bytes per 8x8 tile]
```

Separately, **spritemaps** define how to assemble tiles into the final sprite:
```
Animation ID
  → Tilemap Pointer Tables ($92:945D / $92:9263)  [lower body / upper body]
    → Tilemap List ($92:808D)                       [5 bytes per entry: x, y, tile#, palette, flips]
```

---

## Key ROM Addresses

### Pointer Tables (Bank $92)

| Address | Name | Format | Description |
|---------|------|--------|-------------|
| `$92:D94E` | Frame Progression Pointers | 2-byte ptr × 253 animations | Indexed by animation ID → points to 4-byte frame entries |
| `$92:D91E` | Top-Half DMA Table Ptrs | 2-byte ptr × 13 sets | Indexed by equipment config → points to DMA entry lists |
| `$92:D938` | Bottom-Half DMA Table Ptrs | 2-byte ptr × 13 sets | Same as top, for lower body |
| `$92:945D` | Lower Body Tilemap Index | 2-byte ptr × animations | Animation → tilemap pose list base |
| `$92:9263` | Upper Body Tilemap Index | 2-byte ptr × animations | Animation → tilemap pose list base |
| `$92:808D` | Tilemap Pointers | 2-byte ptr, variable | Pose → specific tilemap data |

### Frame Progression Entry (4 bytes)

Each pose's frame at `[$92:D94E + animation*2] + pose*4`:

| Byte | Field | Description |
|------|-------|-------------|
| 0 | `top_tiles_table` | Index into top-half DMA table pointers (0-12) |
| 1 | `top_tiles_entry` | Entry index within that DMA table |
| 2 | `bot_tiles_table` | Index into bottom-half DMA table pointers (0-10) |
| 3 | `bot_tiles_entry` | Entry index within that DMA table |

### DMA Transfer Entry (7 bytes)

Each entry at `[$92:D91E/D938 + table*2] + entry*7`:

| Bytes | Field | Description |
|-------|-------|-------------|
| 0-2 | `source_ptr` | 24-bit SNES address to raw tile data (typically in $9B-$9F) |
| 3-4 | `row1_size` | Byte count for first DMA chunk (tiles starting at tile index `vram_offset`) |
| 5-6 | `row2_size` | Byte count for second DMA chunk (tiles starting at tile index `0x10 + vram_offset`) |

**VRAM layout**: Offsets 0x00/0x08/0x10/0x18 are **tile indices** (byte offset = tile_index × 32).
Top-half DMA writes to tile indices 0x00 and 0x10. Bottom-half writes to tile indices 0x08 and 0x18.

### Tilemap Entry (5 bytes)

Each tilemap at the pose pointer, preceded by a 2-byte count:

| Bytes | Bits | Field | Description |
|-------|------|-------|-------------|
| 0-1 | 15 | `size` | 0 = 8x8, 1 = 16x16 |
| 0-1 | 8:0 | `x_offset` | Signed X offset from Samus center (9-bit, sign-extended) |
| 2 | 7:0 | `y_offset` | Signed Y offset from Samus center (8-bit, sign-extended) |
| 3-4 | 15 | `y_flip` | Vertical flip |
| 3-4 | 14 | `x_flip` | Horizontal flip |
| 3-4 | 12:11 | `priority` | OAM priority (0-3) |
| 3-4 | 11:9 | `palette` | OAM palette row (typically 4 for Samus) |
| 3-4 | 8:0 | `tile` | VRAM tile number (9-bit) |

For 16x16 tiles: composed of 4 8x8 tiles: `tile`, `tile+1`, `tile+16`, `tile+17`.

---

## Tile Data Storage

### Banks $9B-$9F: Raw 4bpp Tile Graphics

| Bank | Used Bytes | Tiles | Content |
|------|-----------|-------|---------|
| `$9B` | 26,367 | ~824 | Samus tile data |
| `$9C` | 29,954 | ~936 | Samus tile data |
| `$9D` | 29,454 | ~920 | Samus tile data |
| `$9E` | 28,846 | ~901 | Samus tile data |
| `$9F` | 29,006 | ~906 | Samus tile data |
| **Total** | **143,627** | **~4,488** | **~1,122 logical 16x16 sprites** |

### Default VRAM Data

| Address | Size | Description |
|---------|------|-------------|
| `$9A:D200` | 0x2000 (8KB) | Default VRAM population (256 tiles, rows 0x00-0x0F) |
| `$9A:F200` | 0x100 | Standard weapon tiles (row 0x30) |
| `$9A:F400` | 0x100 | Ice beam weapon tiles |
| `$9A:F600` | 0x100 | Wave beam weapon tiles |
| `$9A:F800` | 0x100 | Plasma beam weapon tiles |
| `$9A:FA00` | 0x100 | Spazer beam weapon tiles |

### VRAM Row Layout During Gameplay

| Row | Content | Loaded By |
|-----|---------|-----------|
| 0x00-0x07 | Upper body tiles (row 1) | Top-half DMA, part 1 |
| 0x08-0x0F | Lower body tiles (row 1) | Bottom-half DMA, part 1 |
| 0x10-0x17 | Upper body tiles (row 2) | Top-half DMA, part 2 |
| 0x18-0x1F | Lower body tiles (row 2) | Bottom-half DMA, part 2 |
| 0x30-0x37 | Weapon tiles | Loaded from weapon-specific data |

---

## Equipment Configurations (DMA Table Sets)

### Top-Half Sets (13 entries at $92:D91E)

| Set | Description | Typical Usage |
|-----|-------------|---------------|
| 0 | Power Suit standard | Standing, running |
| 1 | Power Suit variant | Jumping, falling |
| 2-8 | Various suit/pose combos | Equipment-dependent |
| 9 | Shared large set | Multiple poses reference this |
| 10-12 | Specialty sets | Morphball, screw attack, etc. |

### Bottom-Half Sets (13 entries at $92:D938)

Same structure, indexes lower body tiles.

---

## Palettes

### Suit Palettes (16 colors each, BGR555)

| Address | Suit | Notes |
|---------|------|-------|
| `$9B:9400` | Power Suit | Default yellow/green/red visor |
| `$9B:9820` | Varia Suit | Orange/red |
| `$9B:9C40` | Gravity Suit | Purple/pink |

### Special Palettes

15 palette modes total: Standard, Loader, Heat, Charge, Speed Boost, Speed Squat,
Shinespark, Screw Attack, Hyper Beam, Death Suit, Death Flesh, Crystal Flash,
Door Transition, X-Ray, File Select.

---

## Animations & Poses

### Animation Count: 253 entries (156 unique)

### Major Animation Groups (from SpriteSomething manifest)

| Animation | Directions | Description |
|-----------|-----------|-------------|
| Stand | 8 | Idle standing, all aim angles |
| Run | 8 | Running animation (10 frames) |
| Moonwalk | 6 | Backwards walking |
| Crouch | 8 | Crouching, all aim angles |
| Jump | 12 | Jumping, all aim angles |
| Spin Jump | 2 | Left/right spin |
| Space Jump | 2 | Space jump animation |
| Screw Attack | 2 | Screw attack spin |
| Wall Jump | 2 | Wall jump push-off |
| Fall | 12 | Falling, all aim angles |
| Morphball | varies | Rolling morphball |
| Shinespark | 10 | Speed boost charge |
| X-Ray | 10 | X-Ray visor sweep |
| Grapple | varies | Grapple beam swing |
| Death | varies | Death explosion sequence |

### Total Unique Images: 637 (from SpriteSomething layout.json)

Top categories by frame count:
- Run: 80 frames
- Jump: 71 frames
- Fall: 51 frames
- Morphball: 48 frames
- Crouch: 39 frames
- Grapple: 38 frames
- Moonwalk: 36 frames
- Stand: 31 frames

---

## Extraction Algorithm (Verified)

```python
def get_pose(rom, animation, pose):
    # 1. Get frame progression entry
    afp = rom.read16(0x92D94E + 2*animation)
    top_tbl, top_ent, bot_tbl, bot_ent = rom[afp + 4*pose : afp + 4*pose + 4]

    # 2. Get DMA writes (tile data)
    dma = {}
    for base, tbl, ent, vram_off in [(0x92D938, bot_tbl, bot_ent, 0x08),
                                      (0x92D91E, top_tbl, top_ent, 0x00)]:
        table_ptr = 0x920000 + rom.read16(base + 2*tbl)
        src_ptr = rom.read24(table_ptr + 7*ent)
        row1_sz = rom.read16(table_ptr + 7*ent + 3)
        row2_sz = rom.read16(table_ptr + 7*ent + 5)
        dma[vram_off]      = rom.bulk_read(src_ptr, row1_sz)
        dma[0x10+vram_off] = rom.bulk_read(src_ptr + row1_sz, row2_sz)

    # 3. Get tilemaps (lower body reversed, then upper body reversed)
    tilemaps = []
    for base in [0x92945D, 0x929263]:
        idx = rom.read16(base + 2*animation)
        ptr = 0x920000 + rom.read16(0x92808D + 2*idx + 2*pose)
        count = rom.read16(ptr)
        for i in range(count):
            tilemaps.append(parse_5byte_entry(rom, ptr + 2 + 5*i))
    tilemaps.reverse()

    # 4. Build VRAM: start with default ($9A:D200), overlay DMA writes
    # 5. Decode 4bpp tiles and render using tilemap x/y/flip/palette
    return dma, tilemaps
```

---

## References

- SM Disassembly: `~/code/super_metroid/sm_disassembly/src/bank_92.asm` (pose tables, DMA tables)
- SM Disassembly: `~/code/super_metroid/sm_disassembly/src/bank_9E.asm` (tile data)
- SpriteSomething: `~/code/super_metroid/MapRandomizer/SpriteSomething/` (full sprite editor reference)
- SpriteSomething ROM extraction: `source/snes/metroid3/rom.py` (verified extraction algorithm)
- SpriteSomething layout manifest: `resources/app/snes/metroid3/samus/manifests/layout.json` (637 images)
- SpriteSomething animations: `resources/app/snes/metroid3/samus/manifests/animations.json` (44 animation groups)
