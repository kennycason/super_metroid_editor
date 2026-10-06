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
| `$90-$92` | Samus drawing, movement, poses, animation, and spritemaps         |
| `$9B-$9F` | Samus graphics                                                   |
| `$94`     | Block collision handlers                                         |
| `$A0`     | Enemy species headers (64 bytes each)                            |
| `$A1`     | Enemy population sets (per room)                                 |
| `$A2-$A3` | Standard enemy AI                                                |
| `$A4`     | Crocomire AI                                                     |
| `$A5`     | Draygon and Spore Spawn AI                                       |
| `$A6`     | Ridley, Mini-Kraid, and related enemy AI                         |
| `$A7`     | Kraid, Phantoon, Etecoon, and Dachora AI                         |
| `$A8`     | Standard enemy AI                                                |
| `$A9`     | Mother Brain, Baby Metroid, and corpse AI                        |
| `$AA`     | Bomb/Golden Torizo, statues, and Shaktool AI                     |
| `$B2-$B3` | Space Pirate, pipe enemy, Botwoon, and escape-animal AI          |
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
| [`rom/environmental_damage.md`](rom/environmental_damage.md) | Heated-room, lava, and acid damage rates; fixed-point ROM locations; native suit mitigation; desktop and headless configuration. | When changing environmental damage |
| [`rom/limits.md`](rom/limits.md)           | Per-room limits (PLMs, enemies, FX, scrolls, dimensions), bank free space sizes, scroll values, layer 2/BG scrolling, FX type codes.                                                                                                                                                                                   | When adding validation or hitting export errors |
| [`rom/internals.md`](rom/internals.md)     | Deep engine reference. Door transition state machine, PLM lifecycle, block collision dispatch, strict source-backed LZ5 behavior, free-space patterns, and reference codebase paths.                                                                                                                                    | When implementing new engine features           |
| [`rom/hex_edits.txt`](rom/hex_edits.txt)   | Extensive recipe list for raw hex edits: physics, beams, missiles, morph ball, suits, doors, HUD, FX, sounds.                                                                                                                                                                                                          | When creating new patches                       |
| [`rom/sound.md`](rom/sound.md)             | SPC-700 sound system: ARAM layout, transfer block format, song set pointer table (`$8F:E7E1`), BRR sample format, sample directory, music triggering, SFX libraries, CPU-SPC transfer protocol, SMEDIT sample replacement strategy.                                                                                    | When working on audio features                  |
| [`rom/enemies.md`](rom/enemies.md)         | Enemy species headers, population sets, multi-piece/possessor system (types A-D), verified name mapping with ROM strings and SMILE GIF visual IDs.                                                                                                                                                                     | When working on enemy editing or validation     |

### Boss Data (`docs/bosses/`)

| File                                       | Contents                                                                                                                                                                 | Key Data                                                                    |
|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| [`bosses/phantoon.md`](bosses/phantoon.md) | Phantoon's four independently animated slots, exact 22-tilemap BG2 composition, eight health palettes, safe pixel ownership, and all behavior data tables (eye timers, flame patterns, figure-8 speeds, wave constants). | Species: `$E4BF` body, `$E4FF` eye, `$E53F` tentacles, `$E57F` mouth. Room `$CD13`, tileset `$05`, AI `$A7`. HP=2500. |
| [`bosses/draygon.md`](bosses/draygon.md) | Draygon's four independent enemy slots, split room-BG2/enemy-OBJ ownership, exact OAM/tilemap/list inventories, health palettes, dedicated editor views, and read-only placement boundary. | Species: `$DE3F` body, `$DE7F` eye, `$DEBF` tail, `$DEFF` arms. Room `$DA60`, tileset `$1C`, AI `$A5`. HP=6000. |
| [`bosses/kraid.md`](bosses/kraid.md)       | Kraid species IDs, stats/AI, exact 64×64 BG2/head recipe, palettes, linked OAM ownership, and safe edit boundary. **Note: $D2BF is Squeept, NOT Kraid.** | Species: $E2BF. Room $A59F, tileset $1A, AI $A7. HP=1000. |
| [`bosses/mother_brain.md`](bosses/mother_brain.md) | Mother Brain's phase-1 room-art boundary, phase-2 room-BG/head/limb/body ownership, exact map/list inventories, neck geometry, health/rainbow palettes, and safe head-edit boundary. | Species: `$EC3F` head, `$EC7F` body. Room `$DD58`, tileset `$0E`, AI `$A9`. HP=18000. |
| [`bosses/torizo.md`](bosses/torizo.md) | Bomb/Golden Torizo's shared body, low/high OBJ pages, runtime eye/damage/egg transfers, Golden health palettes, projectile/effect maps, consolidated editor, and safe edit boundary. | Encounters: `$EEFF/$EF7F`; drop-only headers: `$EF3F/$EFBF`; shared GFX `$AF:C200`; AI `$AA`. |

### Graphics & Sprites (`docs/graphics/`)

| File                                                     | Contents                                                                                                                                                                                                                                                                         | When to read                                               |
|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| [`graphics/tile_pipeline.md`](graphics/tile_pipeline.md) | **Complete tile rendering pipeline.** Tileset pointer table ($8F:E6A2), 2bpp/4bpp tile decompression, metatile definitions, CRE tiles, animated-tile DMA, item-PLM graphics/slots, palette loading, VRAM layout.                                                                                          | Before modifying TileGraphics, TileDecoder, room tile animation, or item graphics |
| [`graphics/sprites.md`](graphics/sprites.md)             | **Enemy sprite system deep dive.** Source-verified inventory of all 164 species headers, raw `GRAPHADR` ownership/aliases, all named standard/extended OAM structures and tilemaps, measured coverage for all 1,139 named enemy instruction lists, complete ordinary-enemy visual slices and explicit helper-selected routes, all-species production render status, BG2 rendering, enemy GFX set 4-entry hardware limit, and remaining boss-composition caveats. | Before modifying EnemySpriteGraphics or adding new enemies |
| [`graphics/ordinary_enemy_source_routes.md`](graphics/ordinary_enemy_source_routes.md) | Exact Puyo/Owtch/Choot/Sbug helper-selected action routes, AI-stepped pose caveats, source addresses, and edit/read-only boundaries. | When adding an ordinary enemy that the generic init scanner cannot route |

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
| [`project/room_model.md`](project/room_model.md) | Current room/state model, verified runtime behavior, export invariants, and explicitly deferred extensions. |
| [`project/project_format.md`](project/project_format.md) | Native `.smedit` boundary, internal schema marker, ROM-compatibility separation, and foreign-import rules. |
| [`project/codebase_notes.md`](project/codebase_notes.md) | Local repo/codebase map, current sound branch architecture notes, and piano-roll editor implementation notes. |
| [`project/parity_hardening_backlog.md`](project/parity_hardening_backlog.md) | Current quality-first priority order, completed hardening, and remaining SMILE-parity risks. |

### Validation (`docs/validation/`)

| File | Contents |
|------|----------|
| [`validation/README.md`](validation/README.md) | Assembly/disassembly parity program, confirmed mismatches, subsystem validation matrix, ordered milestones, and progress log. |
| [`../parity/README.md`](../parity/README.md) | Portable pinned reference build; source-symbol/asset/LZ5/tileset catalogs; CRE, tile/metatile, animated-tile DMA, item-PLM, all-species enemy-header/`GRAPHADR`, named enemy-OAM proof, exhaustive enemy instruction-list measurement, integrated ordinary-enemy slices and explicit helper-selected routes, and the complete species render-status ledger; strict fixture contract; and unified parity report. |

### Analysis Scripts (`docs/code/`)

| Script                                                         | Usage                                                                                                                       |
|----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| [`code/scan_enemies.py`](code/scan_enemies.py)                 | Scan the source-verified inventory of all 164 enemy species headers from ROM. Outputs text, Markdown tables, or JSON. `python3 scan_enemies.py <rom> --markdown` |
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

- **$A4**: Crocomire
- **$A5**: Draygon, Spore Spawn
- **$A6**: Ridley, Mini-Kraid
- **$A7**: Kraid, Phantoon
- **$A9**: Mother Brain
- **$AA**: Bomb Torizo, Golden Torizo
- **$B3**: Botwoon

### Pre-rendered Enemy Sprite PNGs

`desktopApp/src/jvmMain/resources/enemies/<speciesId>.png`

---

## Room Scroll System

Three layers that interact at runtime:

1. **Static scroll data** — 1 byte per screen at `roomScrollsPtr` (state data +14). Values: $00=Red (camera blocked), $01=Blue (normal), $02=Green (open with a 31-pixel lower vertical clamp offset).
2. **Door ASM** — Runs AFTER static scrolls load, can override specific screens.
3. **Scroll PLMs** — PLM $B703 triggers at runtime when Samus walks over treadmill blocks.

**Load order**: Static → PLMs created → Door ASM executes → Gameplay begins.

**Common pitfall**: The static table contains only the initial room scrolls. Scroll PLMs overwrite individual values when crossed; the last trigger that writes a screen wins until another trigger or room reload. Changing static scrolls without updating obsolete vanilla scroll PLMs can therefore produce runtime behavior that disagrees with the initial scrolls.

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

Room-state authoring exposes this as a named “specific item pickup” condition, listing the ROM's
item PLMs with room, coordinates, and hexadecimal pickup ID. This is distinct from ammo thresholds:
“Missile capacity ≥ 25” reads maximum missiles at `$7E:09C8`, while “item pickup ID `$012`
collected” tests one bit in `$7E:D870..D8AF`.

SMEDIT also supports typed conditions for every collected or equipped equipment/beam bit,
maximum and current missile/Super Missile/Power Bomb/energy/reserve thresholds, named bosses across
areas, persistent door and Chozo-block IDs, and escape-active. Conditions may be nested with visual
`AND`, `OR`, and `NOT` logic, then tested in the first-match room-state simulator before export.
Boss flags are stored per area: `$01` is the area boss, `$02` the area mini-boss (including Mother
Brain), and `$04` a Torizo. The editor names every verified vanilla boss and also exposes the
remaining one-bit slots (`$08` through `$80`) as custom flags for hacks.
Simple predicates use the tagged `SMEDPRED` bank-`$8F` routine; compound/newer predicates use the
tagged `SMEX` postfix format and shared `SMEXRUN` interpreter documented in
`docs/rom/data_format.md`. Both survive export and reopening as semantic project data rather than
opaque ASM addresses.

Existing-room state editing is considered feature complete as of 2026-09-22: users can inspect,
preview, add, duplicate, delete, reorder, simulate, and export branches with state-scoped content and
typed or compound conditions. Layouts are whole resources: the first change to an unacknowledged
shared layout asks whether to edit every linked state or make the active state's complete layout
unique. The compact shared/unique indicator, resource-owned operations, make-unique/copy/share/revert
commands, and selection-to-state copying use that same rule. Relinking for non-layout resources,
persistent-state action authoring, and separate-background/custom-code
authoring remain separate future features. Core project-owned room
creation is implemented; deletion, automatic minimap tiles, templates, and generator output remain
follow-ups documented in `docs/project/room_model.md`.

---

## Controller Configuration

Default button mapping at $82:F575 (7 slots × 2 bytes):
Shot=X(0x0040), Jump=A(0x0080), Dash=B(0x8000), ItemSel=Select(0x2000), ItemCancel=Y(0x4000), AngleDown=L(0x0020), AngleUp=R(0x0010).

---

## IPS Patch Export

Format: 5-byte "PATCH" header, records of `[3-byte offset, 2-byte size, data]`, 3-byte "EOF" footer. RLE: size=0 → `[2-byte run, 1-byte fill]`.

---

## Enemy Sprite Parity Status

The source-complete ledger covers all 164 bank-`$A0` species headers: 139 assembled,
nine tile-sheet-only, 14 source-known composites, two nonvisual, and zero failed; 153
species produce a ROM-derived assembled preview. Eight visual species intentionally
declare a zero-byte graphics transfer. Their previews borrow exact shared/global VRAM
providers (standard sprite tiles, Mother Brain head tiles, Baby Metroid tiles, or the
common corpse payload), but those bytes remain read-only and are never exposed as the
species' own tile edit/export range. Palette ownership is separate: five of those
headers still load and own their palette row, while three select global runtime rows.
See `docs/graphics/sprites.md` and
`docs/validation/README.md` for the ownership table and remaining work.

Puyo `$CFBF`, Owtch `$D03F`, Choot `$D3BF`, and Sbug/roach `$D87F/$D8BF` now use
explicit source routes because their init AI selects instruction lists through helpers
or state logic. Eighteen compact actions expose 65 guided frames while preserving
Puyo's AI-stepped sleeping pose lists, Owtch's setup-to-visual fallthrough, Choot's
separate state lists, and Sbug's eight-direction table. The exact
ownership, timing, OAM, and pixel hashes are pinned by `parityOrdinaryEnemyAnimations`.
See `docs/graphics/ordinary_enemy_source_routes.md`.

Kraid's P2.1 source slice is complete. Its large body is a 64×64 BG2 composition,
not an OAM body: active compressed maps at `$B9:FA38/$B9:FE3E` use the complete
1024-tile/no-CRE tileset `$1A`, and the custom `$A7:AF3D` interpreter copies only
32×11 rows from one of four stored 32×12 head maps. Head pixel edits therefore save
the complete `varGfx["26"]` resource through normal safe relocation. The arm, three
lints, foot, and two nail variants instead share the independent 240-tile OAM range
`$AB:CC00..EA00`. `parityKraid`/`KraidSourceParityTest` pin this ownership, all 21
custom head-frame occurrences, eight mouth hitboxes, 21 palette states, four live
composites, and all 12 bounded linked-OAM sequences / 173 frame occurrences. Mini
Kraid `$E0FF` is separately pinned to six bounded `$A6` action lists / 24 frame
occurrences / 14 unique poses; never infer its poses by scanning shared Ridley bank
`$A6`. The editor hashes every rendered animation frame. Placement tilemaps remain
read-only, and main Kraid's independently positioned OAM entities remain separate
from the BG2 animation canvas.

Mother Brain's focused graphics slice is also source-pinned. Phase 1 renders `$EC3F`
head OAM over room-owned machinery; phase 2 combines tileset `$0E` torso tiles,
`$B7:8000` head/neck, `$B7:9000` limbs, `$B0:E800` supplemental body pixels, five
neck segments, and the independent head. `parityMotherBrain` pins 26 standard maps,
16 active extended maps, six BG2 maps, and 49 body/head lists / 243 timed frames.
The consolidated workspace exposes exact curated animations, four health pairs, and
all ten split rainbow main/back-leg palettes. Only the unambiguous head owner is
editable; HDMA beam shape, projectiles, room destruction, and target-driven neck
motion remain separate engine-effect work.

Botwoon's focused slice is source-pinned as a dynamic composite, not a long static
enemy spritemap. The `$F293` enemy owns the head and a `$400`-byte circular position
history; thirteen bank-`$86` projectiles draw twelve independently oriented body
segments plus the tail using bank-`$8D` OAM. The `$18/$10/$0C` history distances are
byte offsets into four-byte records, producing the same 12-pixel steady segment
spacing at all three health speeds. `parityBotwoon` pins the shared `$B7:E300`
`$1800`-byte pixel owner, head/projectile maps and lists, history routines, nine
palette rows, and deterministic complete compositions/animations. See
`docs/bosses/botwoon.md` for the safe-edit and simulation boundary.

Bomb and Golden Torizo's focused slice is source-pinned as one shared visual family.
The `$EEFF/$EF7F` encounters own the same `$AF:C200` base while `$EF3F/$EFBF` are
projectile drop records rather than extra visible enemies. Production keeps that
editable base separate from the `$AA:B279` runtime overlays, `$AF:E200` Golden egg,
and `$AD:B200` Bomb statue pixels, while preserving both physical OBJ pages.
`parityTorizo` pins 106 active full-body maps, 91 body-child maps, 70 active bank-`$8D`
projectile/effect maps, 16 runtime transfers, 26 palette rows, and deterministic
pixels for 236 guided frames. See `docs/bosses/torizo.md` for exact ownership.

Normal Metroid `$DD7F` is also source-pinned as a three-owner composite. Bank `$A3`
draws four inside maps while bank `$B4` sprite objects `$32/$34` independently draw
24 electricity and three shell maps from the same `$AE:9000` pixel transfer. The
source-labelled unused `$33/$35` lists are live fallthrough continuations, not dead
data; `$35` supplies the third shell map. `parityMetroid` pins all four tracks / 124
companion frame occurrences / 270 ticks and deterministic complete renders. See
`docs/graphics/metroid.md` for timing and edit ownership.

---

## External References

| Source                        | URL / Path                                                                                                |
|-------------------------------|-----------------------------------------------------------------------------------------------------------|
| Kejardon's docs               | https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/                             |
| Patrick Johnston bank logs    | https://patrickjohnston.org/bank/8F (also /B4, /A0, /A7, /A8, etc.)                                       |
| Metroid Construction wiki     | https://wiki.metroidconstruction.com/                                                                     |
| SM decompilation (snesrev/sm) | `~/code/super_metroid/sm/` — C structs, bank-by-bank reimplementation                                     |
| Exact SM disassembly          | `parity/work/sm_disassembly/` — ignored, pinned checkout provisioned by `./gradlew parityBootstrap`; override with `SMEDIT_DISASSEMBLY_DIR` |
| SM-SPC                        | `~/code/super_metroid/SM-SPC/` — A fully symbolic, asar-assemblable source code for Super Metroid's SPC (audio) engine. |
| MapRandomizer                 | `~/code/super_metroid/MapRandomizer/` — Door handling, room geometry                                      |
| SM Mod 3.0.80                 | `docs/Super Metroid Mod 3.0.80/SMMM_black.html` — Community reference (ground truth for species IDs)      |
| SMILE source                  | `~/code/super_metroid/smile/` — Original SM editor                                                        |
| Local SM reference root       | `~/code/sm/` — contains the exact disassembly/build toolchain and other local SM references; inspect it before engine/audio work |

These local repositories are standing implementation references, not optional
reminders. Before changing ROM layout, engine behavior, graphics, rooms, or
audio, consult the relevant disassembly/reference project and record any
behavioral limit that changes the editor design.
