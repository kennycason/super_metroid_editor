# Super Metroid ROM — Master Documentation Index

> **This is THE reference for the SMEDIT project.**
> All knowledge about the Super Metroid ROM format, game engine internals,
> sprite systems, and editor architecture lives here (or is linked from here).
> If it's not documented, it's not known.

---

## Quick Reference

### LoROM Address Conversion

```
PC = ((bank & 0x7F) * 0x8000) + (snes_offset - 0x8000)

$8F:91F8 → PC 0x0791F8    $A0:E4BF → PC 0x1064BF
$A1:8000 → PC 0x108000    $B4:8000 → PC 0x1A0000
```

### Key Banks

| Bank      | Contents                                                         |
|-----------|------------------------------------------------------------------|
| `$8F`     | Room headers, state data, PLM sets, scroll data, door-out tables |
| `$83`     | FX entries, door data blocks (DDBs)                              |
| `$84`     | PLM headers and routines                                         |
| `$89`     | Item graphics source data                                        |
| `$90`     | Beam/weapon damage tables                                        |
| `$91`     | Samus physics/movement                                           |
| `$94`     | Block collision handlers                                         |
| `$A0`     | Enemy species headers (64 bytes each)                            |
| `$A1`     | Enemy population sets (per room)                                 |
| `$A2`     | Mini-boss AI (Spore Spawn, Botwoon, Crocomire, Golden Torizo)    |
| `$A3`     | Utility entity AI (elevator, save station, ship)                 |
| `$A7`     | Boss AI (Kraid, Phantoon, Draygon)                               |
| `$A8`     | Boss AI (Ridley, Mother Brain)                                   |
| `$B4`     | Enemy GFX sets, drop tables, resistances                         |
| `$B9`     | CRE (Common Room Elements) — tiles + tile table                  |
| `$C0-$CE` | Compressed level data (tiles)                                    |

---

## Documentation Map

### ROM Format & Engine (`docs/rom/`)

| File                                       | Contents                                                                                                                                                                                                                                                                                                               | When to read                                    |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|
| [`rom/data_format.md`](rom/data_format.md) | **Authoritative byte-level reference.** Room headers (11 bytes), state selectors, state data (26 bytes), PLM sets (6-byte entries), door data blocks (12 bytes), level data compression, tile word format, BTS values, enemy population format, item PLM graphics system, station PLM placement rules, export process. | Before modifying ANY ROM parsing or export code |
| [`rom/write_safety.md`](rom/write_safety.md) | Transactional export invariants, byte/resource ownership, patch hashes and preconditions, allocation claims, IPS validation, reports, and the Spike Olympics safety result. | Before adding any patch, allocator, hook, or ROM writer |
| [`rom/limits.md`](rom/limits.md)           | Per-room limits (PLMs, enemies, FX, scrolls, dimensions), bank free space sizes, scroll values, layer 2/BG scrolling, FX type codes.                                                                                                                                                                                   | When adding validation or hitting export errors |
| [`rom/internals.md`](rom/internals.md)     | Deep engine reference. Door system end-to-end (15-step transition state machine), PLM execution lifecycle, block collision dispatch, free space management patterns, reference codebase paths.                                                                                                                         | When implementing new engine features           |
| [`rom/hex_edits.txt`](rom/hex_edits.txt)   | Extensive recipe list for raw hex edits: physics, beams, missiles, morph ball, suits, doors, HUD, FX, sounds.                                                                                                                                                                                                          | When creating new patches                       |
| [`rom/sound.md`](rom/sound.md)             | SPC-700 sound system: ARAM layout, transfer block format, song set pointer table (`$8F:E7E1`), BRR sample format, sample directory, music triggering, SFX libraries, CPU-SPC transfer protocol, SMEDIT sample replacement strategy.                                                                                    | When working on audio features                  |
| [`rom/enemies.md`](rom/enemies.md)         | Enemy species headers, population sets, multi-piece/possessor system (types A-D), verified name mapping with ROM strings and SMILE GIF visual IDs.                                                                                                                                                                     | When working on enemy editing or validation     |

### Boss Data (`docs/bosses/`)

| File                                       | Contents                                                                                                                                                                 | Key Data                                                                    |
|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| [`bosses/phantoon.md`](bosses/phantoon.md) | Phantoon species IDs, all behavior data table addresses (eye timers, flame patterns, figure-8 speeds, wave constants), AI routines, safe vs. ASM-required modifications. | Species: $E4BF body, $E4FF/$E53F/$E57F flames. Room $CD13, AI $A7. HP=2500. |
| [`bosses/kraid.md`](bosses/kraid.md)       | Kraid species IDs (8 entities), HP/damage for all parts, AI routines, room enemy set layout. **Note: $D2BF is Squeept, NOT Kraid.**                                      | Species: $E2BF body. Room $A59F, AI $A7. HP=1000.                           |

### Graphics & Sprites (`docs/graphics/`)

| File                                                     | Contents                                                                                                                                                                                                                                                                         | When to read                                               |
|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| [`graphics/tile_pipeline.md`](graphics/tile_pipeline.md) | **Complete tile rendering pipeline.** Tileset pointer table ($8F:E6A2), 2bpp/4bpp tile decompression, metatile definitions, CRE tiles, palette loading, VRAM layout.                                                                                                             | Before modifying TileGraphics or TileDecoder               |
| [`graphics/sprites.md`](graphics/sprites.md)             | **Enemy sprite system deep dive.** 64-byte species header format, OAM spritemap format (5-byte entries), instruction list tracing, BG2 tilemap rendering (Phantoon/Kraid), enemy GFX set 4-entry hardware limit, all boss data tables (ROM-scanned), rendering pipeline summary. | Before modifying EnemySpriteGraphics or adding new enemies |

### Reference Data (`docs/reference/`)

| File                                                       | Contents                                                                |
|------------------------------------------------------------|-------------------------------------------------------------------------|
| [`reference/rogue_doors.md`](reference/rogue_doors.md)     | Phantom blue door root cause analysis and fix (door_orientation bit 2). |
| [`reference/plm_editor.txt`](reference/plm_editor.txt)     | PLM editing reference from SMILE documentation.                         |
| [`reference/enemy_editor.txt`](reference/enemy_editor.txt) | Enemy editing reference from SMILE documentation.                       |
| [`reference/fix_editor.txt`](reference/fix_editor.txt)     | FX editing reference from SMILE documentation.                          |
| [`reference/sounds.txt`](reference/sounds.txt)             | Sound/music track data reference.                                       |

### Project Planning (`docs/project/`)

| File                                       | Contents                                                                                          |
|--------------------------------------------|---------------------------------------------------------------------------------------------------|
| [`project/plan.md`](project/plan.md)                 | SMILE feature parity gap analysis and implementation phases.                                      |
| [`project/roadmap.md`](project/roadmap.md)           | Feature roadmap: boss/enemy stats editors, patches, sprite export, scroll editor, FX editor, etc. |
| [`project/smile_parity.md`](project/smile_parity.md) | Complete SMILE vs SMEDIT feature comparison matrix with priority tiers and implementation notes.   |
| [`project/codebase_notes.md`](project/codebase_notes.md) | Local repo/codebase map, current sound branch architecture notes, and piano-roll editor implementation notes. |
| [`project/parity_hardening_backlog.md`](project/parity_hardening_backlog.md) | Current quality-first priority order, completed hardening, and remaining SMILE-parity risks. |
| [`project/lsnes-extra.md`](project/lsnes-extra.md) | Optional Linux-only lsnes rr2-beta25 TAS extra: not bundled, user-installed worker, TASVideos `.lsmv` playback. |

### Analysis Scripts (`docs/code/`)

| Script                                                         | Usage                                                                                                                       |
|----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| [`code/scan_enemies.py`](code/scan_enemies.py)                 | Scan all enemy species headers from ROM. Outputs text, markdown tables, or JSON. `python3 scan_enemies.py <rom> --markdown` |
| [`code/dump_room_data.py`](code/dump_room_data.py)             | Dump detailed room data: headers, states, doors, PLMs, door blocks. `python3 dump_room_data.py <rom> 0x91F8`                |
| [`code/compare_doors.py`](code/compare_doors.py)               | Compare door data between vanilla and edited ROMs.                                                                          |
| [`code/scan_state_selectors.py`](code/scan_state_selectors.py) | Scan all room state selectors across the ROM.                                                                               |

### Images (`docs/images/`)

Screenshots and reference images for SMILE editors (BTS, enemy, FX, PLM).

---

## Key ROM Conventions

### Enemy Species Headers (64 bytes at bank $A0)

| Offset | Size | Field            | Notes                                                  |
|--------|------|------------------|--------------------------------------------------------|
| +$00   | 2    | tileDataSize     | Bit 15 is VRAM flag, not size. `actual = val & 0x7FFF` |
| +$02   | 2    | palPtr           | Palette pointer (16-bit, used with aiBank)             |
| +$04   | 2    | HP               |                                                        |
| +$06   | 2    | Damage           | Contact damage                                         |
| +$08   | 2    | Width            | Hitbox half-width (pixels)                             |
| +$0A   | 2    | Height           | Hitbox half-height (pixels)                            |
| +$0C   | 1    | aiBank           | Bank for AI, palette, AND spritemap data               |
| +$12   | 2    | initAI           | Init function pointer (in aiBank)                      |
| +$14   | 2    | parts            | Sub-pieces (0 = 1 part)                                |
| +$36   | 2    | GRAPHADR offset  | Raw 4bpp tile data offset                              |
| +$38   | 1    | GRAPHADR bank    | Raw 4bpp tile data bank                                |
| +$39   | 1    | Layer control    | 02=front, 05=behind Samus, 0B=behind BG                |
| +$3A   | 2    | Drop chances ptr | Bank $B4                                               |
| +$3C   | 2    | Resistances ptr  | Bank $B4                                               |
| +$3E   | 2    | Name ptr         | Bank $B4                                               |

### Boss AI Banks

- **$A7**: Kraid, Phantoon, Draygon, Etecoon, Dachora
- **$A8**: Ridley, Mother Brain
- **$A2**: Spore Spawn, Botwoon, Crocomire, Golden Torizo
- **$A6**: Mini-Kraid

### Pre-rendered Enemy Sprite PNGs

`desktopApp/src/jvmMain/resources/enemies/<speciesId>.png`

---

## Room Scroll System

Three layers that interact at runtime:

1. **Static scroll data** — 1 byte per screen at `roomScrollsPtr` (state data +14). Values: $00=Red (locked), $01=Blue (open), $02=Green (PLM-gated).
2. **Door ASM** — Runs AFTER static scrolls load, can override specific screens.
3. **Scroll PLMs** — PLM $B703 triggers at runtime when Samus walks over treadmill blocks.

**Load order**: Static → PLMs created → Door ASM executes → Gameplay begins.

**Common pitfall**: Changing static scrolls without removing vanilla scroll PLMs causes them to fight at runtime.

---

## Enemy Population Export

16 bytes per entry in bank $A1, terminated by `$FFFF` + 1-byte kill count.

**Properties word (offset 8-9)**:

- Bit 13 (0x2000): **CRITICAL** — assigns initial spritemap pointer. Without it → crash.
- Bit 11 (0x0800): Process off-screen.
- Default for new enemies: `0x2800`.

**GFX set hard limit**: 4 entries in bank $B4. 5th entry = RAM corruption = crash.

---

## Item Collection Bits

Each item PLM's `param` maps to a bit in `$7E:D870` (512 bits). Every item across the entire ROM must have a unique `param`. Vanilla uses 0x00–0x50;
editor assigns from 0x51–0x1FF (431 slots).

---

## Controller Configuration

Default button mapping at $82:F575 (7 slots × 2 bytes):
Shot=X(0x0040), Jump=A(0x0080), Dash=B(0x8000), ItemSel=Select(0x2000), ItemCancel=Y(0x4000), AngleDown=L(0x0020), AngleUp=R(0x0010).

---

## IPS Patch Export

Format: 5-byte "PATCH" header, records of `[3-byte offset, 2-byte size, data]`, 3-byte "EOF" footer. RLE: size=0 → `[2-byte run, 1-byte fill]`.

---

## External References

| Source                        | URL / Path                                                                                                |
|-------------------------------|-----------------------------------------------------------------------------------------------------------|
| Kejardon's docs               | https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/                             |
| Patrick Johnston bank logs    | https://patrickjohnston.org/bank/8F (also /B4, /A0, /A7, /A8, etc.)                                       |
| Metroid Construction wiki     | https://wiki.metroidconstruction.com/                                                                     |
| SM decompilation (snesrev/sm) | `~/code/super_metroid/sm/` — C structs, bank-by-bank reimplementation                                     |
| SM-SPC                        | `~/code/super_metroid/SM-SPC/` — A fully symbolic, asar-assemblable source code for Super Metroid's SPC (audio) engine. |
| MapRandomizer                 | `~/code/super_metroid/MapRandomizer/` — Door handling, room geometry                                      |
| SM Mod 3.0.80                 | `docs/Super Metroid Mod 3.0.80/SMMM_black.html` — Community reference (ground truth for species IDs)      |
| SMILE source                  | `~/code/super_metroid/smile/` — Original SM editor                                                        |
