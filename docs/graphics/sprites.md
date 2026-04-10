# Super Metroid Sprite System — Complete Reference

## Overview

Super Metroid uses three distinct sprite rendering systems:

1. **OAM Spritemaps** — Standard enemies use hardware OAM (Object Attribute Memory) sprites
2. **BG2 Tilemaps** — Phantoon and Kraid use background layer 2 for their large bodies
3. **DMA-loaded sprites** — Bosses with dynamic tile loading during fight phases

All enemy species have a 64-byte header at bank `$A0` containing stats, graphics pointers,
palette info, and AI routine addresses.

---

## Enemy Species Header Format (64 bytes, bank $A0)

Every enemy in the game has a header at `$A0:<speciesId>`. The species ID doubles as
the header offset within bank $A0.

| Offset | Size | Field | Notes |
|--------|------|-------|-------|
| +$00 | 2 | tileDataSize | Decompressed tile bytes. Bit 15 is a VRAM layout flag, not size. |
| +$02 | 2 | palPtr | Palette pointer (16-bit, combined with aiBank) |
| +$04 | 2 | HP | Hit points |
| +$06 | 2 | Damage | Contact damage to Samus |
| +$08 | 2 | Width | Hitbox half-width (pixels) |
| +$0A | 2 | Height | Hitbox half-height (pixels) |
| +$0C | 1 | aiBank | Bank for AI routines, palette, AND spritemap data |
| +$0D | 1 | ??? | Unknown |
| +$0E | 2 | ??? | Unknown |
| +$10 | 2 | ??? | Unknown |
| +$12 | 2 | initAI | Init function pointer (in aiBank) |
| +$14 | 2 | parts | Number of sub-pieces (0 = 1 part) |
| +$36 | 2 | GRAPHADR offset | 16-bit LE offset of raw 4bpp tile data |
| +$38 | 1 | GRAPHADR bank | Bank byte for tile data |
| +$39 | 1 | Layer control | 02=front, 05=behind Samus, 0B=behind BG |
| +$3A | 2 | Drop chances ptr | Bank $B4 |
| +$3C | 2 | Resistances ptr | Bank $B4 |
| +$3E | 2 | Name ptr | Bank $B4 |

### Tile Data Size — Bit 15 Flag

`actual_size = tile_data_size & 0x7FFF`

When bit 15 is set, the VRAM offset calculation changes from sequential
(`(vram_dst & 0x3000) >> 3` instead of linear), but the tile count is the same.

### GRAPHADR — Tile Data Source

`GRAPHADR = $(bank at +$38):(offset at +$36-37)`

Points to **raw (uncompressed) 4bpp tile data** in the ROM for regular enemies.
The game copies exactly `tileDataSize & 0x7FFF` bytes from this address into VRAM.

Multiple enemies can share the same GRAPHADR block with different tileDataSizes
(each species uses the first N bytes of the shared block).

Bosses (Phantoon, Kraid, etc.) use separate LZ5-compressed DMA-based tile loading instead.

### Palette Loading

The game's `ProcessEnemyTilesets` (`$A0:8D64`) loads exactly 32 bytes (one 16-color
palette row) from `$(aiBank):$(palPtr)`. Always row 0, directly at the pointer address.

The `vram_dst` low byte + 8 selects the CGRAM destination row (8-15 = OBJ palettes).

---

## OAM Spritemap Format (Standard Enemies)

Standard enemies use OAM spritemaps pointed to by their instruction list.

### Finding Spritemaps from Species Headers

The init function at `$(aiBank):$(initAI)` sets up the instruction list pointer
(`$0F92,x`). `EnemySpritemap.findInstructionListPointer()` traces the init code
using pattern matching:

| Pattern | Description |
|---------|-------------|
| `LDA #imm; STA $0F92,x` | Direct pointer to instruction list |
| `LDA abs,y; STA $0F92,x` | Direction table lookup (first entry used) |
| `TYA; STA $0F92,x` | Value from Y register |
| JSR following | Scans subroutine calls for patterns above |
| Cross-function trace | Traces through helper functions (e.g., Sidehopper) |

### Instruction List Format

4-byte entries `[word0, word1]`, two formats:
- **Standard**: word0 < 0x8000 → `[timer, spritemap_ptr]`
- **Handler-based**: word0 >= 0x8000 → `[handler_ptr, timer]`

### OAM Entry Format (5 bytes per tile)

| Byte(s) | Format | Contents |
|---------|--------|----------|
| 0-1 | LE word | `s_______ XXXXXXXX` — bit 15 = size (1=16x16, 0=8x8), bits 8-0 = signed 9-bit X |
| 2 | signed byte | Y offset from enemy center |
| 3-4 | LE word | `VHooPPPn cccccccc` — V/H flip, priority, palette row, name table, tile number |

### Tile Numbering

Tile numbers include the SNES name table select bit (bit 8).
`local_tile_index = tile_number & 0xFF` maps into decompressed tile data.
16x16 sprites use a 2x2 grid: tiles `[N, N+1, N+16, N+17]` in the 16-tile-wide VRAM layout.

---

## Enemy GFX Set (bank $B4) — 4-Entry Hardware Limit

The `enemyGfxPtr` (room state data offset +10) points to entries in bank $B4.
Each entry: `species_id(2) + vram_dst(2)`, terminated by `FFFF`.

**Hard limit: 4 entries.** `ProcessEnemyTilesets` writes to fixed 4-slot arrays.
A 5th entry overflows and corrupts adjacent RAM — guaranteed crash.

The `vram_dst` low byte is the palette index: `LOBYTE(vram_dst) + 8` selects
the CGRAM row (rows 8-15 are OBJ palettes on SNES).

### Species Without GFX Entries

Some species exist in rooms but intentionally have NO GFX entry (e.g., Elevator,
Phantoon). They work via `LoadEnemyGfxIndexes` defaults (palette row 13, tiles
index 0). Adding unneeded entries corrupts VRAM layout.

---

## Boss Sprites — Complete Data

*Generated from ROM scan (`scan_enemies.py`)*

### Kraid (Room $A59F, AI Bank $A7)

| Entity | Species ID | HP | Dmg | Hitbox | Palette | Init AI | Tile Size |
|--------|-----------|-----|------|--------|---------|---------|-----------|
| Body | `$E2BF` | 1000 | 20 | 56x144 | `$A7:8687` | `$A7:A959` | 7680 |
| Upper body | `$E2FF` | 1000 | 20 | 48x48 | `$A7:8687` | `$A7:AB43` | 7680 |
| Belly spike 1 | `$E33F` | 1000 | 10 | 24x8 | `$A7:8687` | `$A7:AB68` | 7680 |
| Belly spike 2 | `$E37F` | 1000 | 10 | 24x8 | `$A7:8687` | `$A7:AB9C` | 7680 |
| Belly spike 3 | `$E3BF` | 1000 | 10 | 24x8 | `$A7:8687` | `$A7:ABCA` | 7680 |
| Flying claw 1 | `$E3FF` | 1000 | 20 | 8x8 | `$A7:8687` | `$A7:ABF8` | 7680 |
| Flying claw 2 | `$E43F` | 10 | 10 | 8x8 | `$A7:8687` | `$A7:BCEF` | 7680 |
| Flying claw 3 | `$E47F` | 10 | 10 | 8x8 | `$A7:8687` | `$A7:BD2D` | 7680 |

All entities share GFX at `$AB:CC00` and palette at `$A7:8687`.
Kraid uses BG2 tilemaps + room tileset injection (not OAM spritemaps).

### Phantoon (Room $CD13, AI Bank $A7)

| Entity | Species ID | HP | Dmg | Palette | Init AI | Tile Size |
|--------|-----------|-----|------|---------|---------|-----------|
| Body | `$E4BF` | 2500 | 40 | `$A7:CA01` | `$A7:CDF3` | 3072 |
| Flame (small) | `$E4FF` | 2500 | 40 | `$A7:CA01` | `$A7:CE55` | 1024 |
| Flame (medium) | `$E53F` | 2500 | 40 | `$A7:CA01` | `$A7:CE55` | 1024 |
| Flame (large) | `$E57F` | 2500 | 40 | `$A7:CA01` | `$A7:CE55` | 1024 |

All entities share GFX at `$AC:AA00`. Phantoon uses BG2 tilemaps for rendering.

### Draygon (AI Bank $A7/$A8)

| Entity | Species ID | HP | Dmg | Hitbox | Palette | Init AI | GFX |
|--------|-----------|-----|------|--------|---------|---------|-----|
| Body | `$E5BF` | 32767 | 0 | 6x7 | `$A7:E7FE` | `$A7:E912` | `$AC:8200` |
| Turret | `$E5FF` | 32767 | 0 | 8x24 | `$A7:F225` | `$A7:F4DD` | `$AC:8800` |
| Goop | `$E63F` | 300 | 100 | 16x20 | `$A8:8687` | `$A8:87E0` | `$B1:9400` |

Draygon body has 32767 HP (effectively invincible to normal attacks).

### Ridley (AI Bank $A8)

| Entity | Species ID | HP | Dmg | Hitbox | Palette | Init AI | GFX |
|--------|-----------|-----|------|--------|---------|---------|-----|
| Body | `$E67F` | 300 | 100 | 8x8 | `$A8:8687` | `$A8:88B0` | `$B1:9400` |
| Fireball | `$E6BF` | 20 | 0 | 8x8 | `$A8:8F8C` | `$A8:9058` | `$B1:9A00` |
| Tail | `$E6FF` | 20 | 10 | 16x16 | `$A8:9379` | `$A8:96E3` | `$B1:9E00` |

### Mother Brain (AI Bank $A8)

| Entity | Species ID | HP | Dmg | Hitbox | Palette | Init AI | GFX |
|--------|-----------|-----|------|--------|---------|---------|-----|
| Phase 1 | `$E73F` | 20 | 10 | 16x16 | `$A8:959D` | `$A8:96E3` | `$B1:9E00` |
| Phase 2 | `$E77F` | 300 | 60 | 16x16 | `$A8:99AC` | `$A8:9AEE` | `$B1:A600` |
| Brain | `$E7BF` | 20 | 30 | 8x8 | `$A8:9F4F` | `$A8:A148` | `$B1:AA00` |
| Bomb | `$E7FF` | 1600 | 0 | 16x16 | `$A8:AAFE` | `$A8:AB46` | `$B1:AE00` |
| Laser | `$E83F` | 20 | 40 | 8x8 | `$A8:AC1C` | `$A8:AF8B` | `$B1:BC00` |

### Mini-Bosses

| Boss | Species ID | HP | Dmg | AI Bank | GFX |
|------|-----------|-----|------|---------|-----|
| Spore Spawn body | `$CEFF` | 20 | 40 | `$A2` | `$AC:D000` |
| Spore Spawn spore | `$CF7F` | 20000 | 0 | `$A2` | `$AC:D400` |
| Botwoon body | `$D07F` | 20 | 40 | `$A2` | `$AD:B600` |
| Botwoon body (2nd) | `$D0BF` | 20 | 40 | `$A2` | `$AD:B600` |
| Crocomire body | `$D13F` | 30 | 16 | `$A2` | `$AE:C920` |
| Crocomire bridge | `$D17F` | 100 | 60 | `$A2` | `$AE:CD20` |
| Crocomire spike wall | `$D1BF` | 90 | 50 | `$A2` | `$AE:B400` |
| Golden Torizo | `$D23F` | 10 | 40 | `$A2` | `$AE:B800` |
| Mini-Kraid spike | `$E0FF` | 400 | 100 | `$A6` | `$AB:8000` |

---

## Editor-Supported Enemies

The editor supports **110+ enemies** via `EnemySpriteGraphics.EDITOR_ENEMIES`, with live tile sheet
rendering, OAM spritemap assembly, and pixel editing for all of them.

### Categories

| Category | Examples | Count |
|----------|----------|-------|
| Boss | Phantoon, Kraid, Crocomire, Draygon, Ridley, Mother Brain, Big Metroid | 9 |
| Mini-Boss | Spore Spawn, Botwoon, Mini Kraid, Torizo, Golden Torizo | 5 |
| Wall Crawlers | Zoomer, Zeela, Geemer, Beetom, Sova | 5 |
| Hoppers | Sidehopper, Dessgeega | 6 |
| Flyers | Skree, Reo, Waver, Alcoon, Fireflea, Atomic, Mella, Mellow | 12 |
| Kihunters | Kihunter (Hachi 1-3), Kzan | 4 |
| Rippers | Ripper, Ripper II variants | 3 |
| Stationary/Plants | Cacatac, Boyon, Yapping Maw, Viola, Powamp, etc. | 17 |
| Aquatic/Maridia | Sciser, Oum, Skultera, Ebi, Alcoon, Yard, Zoa | 9 |
| Norfair | Holtz, Rinka, Squeept, Geruta, Hibashi, Lavaman, Dragon | 9 |
| Spawners | Zeb, Zebbo, Gamet, Geega, Dori | 6 |
| Space Pirates | 12 variants (Norfair, Maridia, Tourian, Mk.II, Mk.III) | 12 |
| Hachi (Bees) | Hachi 1-3 | 3 |
| Friendly/Misc | Etecoon, Samus' Ship, Mella, Menu | 5 |
| Mechanisms | Door Shutters, Shattered Glass, Work Robot | 5 |
| Other | Boulder (RSTONE), Multiviola, Polyp, Zero, Metroid, Puyo | 6 |

### Rendering Modes

- **OAM Spritemap Assembly** — Standard enemies: init function tracing → instruction list → spritemap → assembled sprite
- **BG2 Tilemap Rendering** — Phantoon (`PhantoonSpritemap.kt`), Kraid (`KraidSpritemap.kt`)
- **Raw Tile Sheet** — All enemies show their 4bpp tile sheet with pixel editing support

### Pixel Editing

All enemies support tile sheet pixel editing via `SpritePixelEditor`. Custom tile data is stored
in the project file under `customGfx.spriteTileBlocks["enemy:<speciesId>"]` and exported to the
patched ROM.

### Pre-Rendered PNG Fallbacks
Enemies without successful OAM spritemap tracing still display their tile sheet.
Static PNG sprites are also stored in `desktopApp/src/jvmMain/resources/enemies/<speciesId>.png`.

---

## Rendering Pipeline Summary

```
Species Header ($A0)
  ├── GRAPHADR → raw 4bpp tile data (direct ROM copy, NOT compressed)
  ├── palPtr + aiBank → 32-byte palette (16 colors)
  ├── initAI → trace instruction list → spritemap pointers
  └── tileDataSize & 0x7FFF → bytes to load

Spritemap
  ├── OAM entries (5 bytes each)
  │     ├── X/Y offset from center
  │     ├── tile number → index into decompressed tiles
  │     ├── V/H flip, palette row
  │     └── size (8x8 or 16x16)
  └── Assembled into final sprite image

Enemy GFX Set ($B4)
  ├── species_id + vram_dst (per entry)
  ├── vram_dst low byte → palette CGRAM row (8-15)
  └── Max 4 entries (hardware limit)
```

---

## Test Coverage

| Test File | What it validates |
|-----------|-------------------|
| `EnemySpriteRenderTest.kt` | Palette detection, tile decompression, render, stats verification |
| `EnemyExportDiagTest.kt` | Population roundtrip, GFX set, properties bit 0x2000, kill count |
| `EnemySpritemapTest.kt` | OAM parsing, instruction tracing, assembled sprites |
| `EnemyTileScanTest.kt` | GRAPHADR decompression, tileDataSize mask, palette row 0 |
| `PhantoonSpritemapRoundtripTest.kt` | Pixel-perfect match vs reference PNG, edit roundtrip |

---

## Sources

- Kejardon's EnemyData.txt — Full 64-byte species header format
- Patrick Johnston bank $A0 disassembly — `ProcessEnemyTilesets` at $A0:8D64
- SM decompilation (`snesrev/sm`) — C structs in `ida_types.h`
- VG Resource SM ripping project — GRAPHADR discovery, tilemap format
- Generated data: `docs/code/scan_enemies.py --markdown`
