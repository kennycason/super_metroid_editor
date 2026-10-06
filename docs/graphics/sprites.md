# Super Metroid Sprite System — Complete Reference

> **Parity status (2026-10-05):** All 164 species headers, raw `GRAPHADR`
> ownership, and every named standard/extended enemy OAM structure are now
> source/ROM verified through SMEDIT's production parsers. Instruction-list
> control flow remains a measured best-effort preview; focused source renderers now
> cover Kraid, Phantoon, Draygon, Ridley, Mother Brain, Crocomire, Spore Spawn,
> Botwoon, and Torizo. The
> remaining boss ID/bank tables below contain known stale assignments; do not use
> those tables for implementation until regenerated from exact source. See
> [`../validation/README.md`](../validation/README.md).

### Current boss pixel-editing safety boundary

- Ordinary enemy raw tile edits export through the species header's verified
  `GRAPHADR` range and exact `tileDataSize`. The shared ROM write planner rejects
  overlapping edits by default, so conflicting writes fail closed at export.
  Earlier editor feedback and an intentional identical-alias editing model remain
  E-09 requirements.
- Phantoon's **Components** editor remains enabled. It edits the room-tileset tiles
  used by all 22 active extended BG2 tilemaps. The Animations view composes the body,
  eye, tentacles, and mouth in their shared runtime coordinates and exposes all eight
  health palettes.
- Phantoon's old standalone tile-sheet mapping is listed only as a quarantined legacy
  item in **Sources** because it resolves inside Mother Brain leg graphics
  (`$B7:9000..9FFF`). Existing legacy edits block export until reset.
- Kraid's old `kraid:*` tile-sheet mapping remains quarantined because `$B9:FA38`
  is a compressed BG2 tilemap. The source-backed Components editor is now enabled:
  head edits save the complete no-CRE tileset `$1A` resource through `varGfx["26"]`.
  The independent `$AB:CC00` linked OAM sheet remains a separate resource.
- Draygon combines room tileset `$1C` BG2 pixels with the separate `$B0:C800` enemy
  OBJ payload, exposes ten complete poses, 39 bounded animations, eight health stages,
  and the hurt flash. Its exact body-owned `$2000` OBJ source is editable and previews
  all named compositions live; flattened composite editing stays disabled because a
  visible pixel may belong to either owner.
- Mother Brain is one consolidated `$EC3F` workspace. Phase 1 correctly renders the
  enemy head without pretending that the room-owned glass case is sprite data. Phase 2
  combines tileset `$0E` torso BG, `$B7:8000/$9000` head and limb OBJ, the `$B0:E800`
  supplement, five neck segments, and independent palettes. Only the exact head owner
  is editable; the four-owner body diagnostic remains read-only.

The dedicated Kraid, Phantoon, Draygon, and Mother Brain workspaces use one navigation model and
open at the richest available level: **Animations** are timed assembled behavior,
**Compositions** are static assembled poses, **Components** are runtime pieces, and
**Sources** are underlying pixel owners. A complex workspace without animations
defaults to Compositions; ordinary enemies keep their compact single-page viewer.
Selecting a component always changes the isolated preview. Editing is capability-based:
Phantoon BG2 component pixels reversibly map to tileset `$05`, while a Draygon extended
component can mix BG2 and OBJ children and therefore links to its unambiguous OBJ source
editor instead of offering a dangerous flattened edit.

These are fail-closed export rules, not claims that the bosses cannot be edited. The
correct source-backed edit model is tracked as parity milestone P0.6.

## Overview

Super Metroid uses three distinct sprite rendering systems:

1. **OAM Spritemaps** — Standard enemies use hardware OAM (Object Attribute Memory) sprites
2. **BG2 Tilemaps** — Phantoon and Kraid use background layer 2 for their large bodies
3. **DMA-loaded sprites** — Bosses with dynamic tile loading during fight phases

The vanilla NTSC source assembles 164 enemy species headers in bank `$A0`. Each is
64 bytes and contains stats, graphics pointers, palette info, and AI routine addresses.

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

Boss headers can also have valid raw `GRAPHADR` transfers, but that range alone is
not their complete render recipe. Phantoon, Kraid, Ridley, Mother Brain, and other
special cases additionally use BG layers, room tilesets, staged DMA, custom OAM, or
linked enemy slots as directed by their AI.

### Source-Backed Header and GRAPHADR Verification (2026-10-04)

`parityEnemyHeaders` parses all 164 assembled `EnemyHeader` macro calls rather than
discovering likely headers by scanning ROM. It evaluates all 29 arguments per call
(4,756 fields), verifies both four-byte zero-padding regions, and compares the exact
64-byte record with the rebuilt ROM and SMEDIT's production parser. One source-declared
unused header and all nine zero-size graphics headers remain explicit. Six species set
bit 15 of `tileDataSize`; the flag is recorded separately from the transfer byte count.

The 155 nonempty graphics associations resolve to 100 unique address/size ranges and
99 named extracted assets. This is not a one-header/one-file relationship:

- 25 groups share a start address, and 24 groups share an exact range.
- Nine distinct range pairs overlap. Seven are nested at the same start; two begin at
  different addresses.
- Six species transfers span adjacent asset declarations. Ridley and Ceres Ridley each
  cover five `Tiles_Ridley_*` chunks; Ceres Door covers three. Lava Rocks/Rinka and
  Geruta intentionally continue into a prefix of the following named asset.

The manifest records every contiguous segment, so a gap, stale size, renamed asset,
or changed overlap fails parity. The overlap inventory is also an edit-safety boundary:
writing one valid species range can modify bytes another species uses. E-09 remains
partial until project/export logic detects conflicts between simultaneous edits instead
of validating each block only in isolation. Machine-readable evidence is in ignored
`parity/reports/enemy-headers.json`.

### Palette Loading

The game's `ProcessEnemyTilesets` (`$A0:8D64`) loads exactly 32 bytes (one 16-color
palette row) from `$(aiBank):$(palPtr)`. Always row 0, directly at the pointer address.
It performs that palette copy independently before staging the graphics transfer, so
a zero-byte graphics header can still own an editable palette. Cutscene Baby Metroid,
Mother Brain's falling tubes, and the three small corpse headers do exactly that;
Elevator, Ceres Steam, and Zebetite instead select global palette rows from AI.

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

Instruction lists are programs, not uniform arrays of four-byte records:

- A word below `$8000` begins a frame record: `[duration, spritemap pointer]`.
- A word at or above `$8000` is an instruction routine pointer. Its operand count
  and control-flow behavior are defined by that routine; instructions may sleep,
  delete, branch, loop, mutate state, or consume additional words.

`EnemySpritemap` currently uses bounded pattern tracing to find useful preview
frames. It is not yet a complete instruction interpreter. The source-backed P1.4
inventory below measures every record, handler width/control-flow shape, and preview
miss; preview success still must not be treated as full animation parity.

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

### Source-Backed OAM Verification (2026-10-04)

`parityEnemyOam` derives its fixtures from named source labels in the shared and
enemy AI banks (`$A0`, `$A2..AA`, `$B2..B3`), not from scanning for plausible
counts. It independently decodes and then compares through SMEDIT's production
parser:

- 2,312 standard spritemaps containing 14,400 five-byte OAM entries;
- 811 extended/multibox spritemaps containing 1,984 child associations;
- 99 extended tilemaps containing 441 runs and 2,763 words.

Every signed coordinate, 8x8/16x16 flag, 9-bit tile/name-table value, palette row,
priority, flip, child type/address, hitbox pointer, destination, and tilemap word is
asserted. Source-declared unused structures remain in the corpus. The manifest also
recognizes the source's `Spritemap`/`Spritemaps`, `Extended`/`Ext`, and bank-$B2
`Spitemaps` naming variants instead of losing data because of spelling.

This proof corrected two production-parser gaps. A count of zero is a valid shared
“nothing” standard spritemap (12 named structures use it). Extended spritemaps use
only the count word's low byte; Ceres steam deliberately stores `$1001` for one
child. One unused Torizo label is excluded explicitly because the source identifies
it as an orphaned five-byte entry with a missing count, not a spritemap structure.
The ignored machine-readable evidence is `parity/reports/enemy-oam.json`.

Standard OAM overlap follows SNES hardware order: the engine copies a spritemap's
first source entry to the lower OAM index, and the lower index wins when opaque OBJ
pixels overlap. SMEDIT therefore composites standard entries in reverse painter order
(last source entry first). This is visibly important for layered sprites such as Mini
Kraid, whose foreground arm and curled front leg precede its torso entries.

### Source-Backed Instruction-List Coverage (2026-10-04)

`parityEnemyInstructions` parses named source blocks rather than scanning ROM for
plausible four-byte pairs. Its corpus contains 1,139 enemy instruction lists and
7,820 semantic records: 4,573 timed frame records plus 3,247 handler occurrences at
502 unique handler addresses. Exact source bytes, list boundaries, record widths,
frame pointers, conservative control-flow classes, and the current production
preview result are pinned. Twenty-one similarly named blocks are explicitly excluded:
Kraid's dedicated head-list interpreter, the Mother Brain room-palette interpreter,
HDMA-object lists, and three native routines whose labels begin with `InstList_`.

The measured result is a limitation report, not an interpreter-complete claim. Of
4,395 structurally renderable source frames, the generic fixed-four-byte fallback
recovers 2,543 (57.9%) and misses 1,852 across 465 lists. It removes repeated
spritemap pointers, does not execute any of the 502 handler addresses, and produces
4,718 detections after crossing a named source-block boundary. Some source blocks
intentionally fall through, so those are boundary-crossing candidates rather than
automatically bugs. Another 178 source frames point to structures that flatten to no
visible OAM. The report lists every missed frame, unrenderable frame, handler, and
boundary-crossing detection in `parity/reports/enemy-instructions.json`.

The source comments contain 351 stale/advisory address annotations, largely where
inline operands were not included in later comments. Assembled symbols and rebuilt
ROM bytes remain authoritative. The manifest proves a consistent operand width for
every observed handler address; it recognizes 58 common-family handler addresses,
but the generic fallback still skips rather than interprets them.

### Verified Ordinary-Enemy Vertical Slices (2026-10-04)

P1.5 adds a bounded, fail-closed visual instruction interpreter and proves three
deliberately different source paths:

- Zoomer `$DCFF`: setup-handler fallthrough into five standard-OAM frames, followed
  by a backward `GotoY` loop;
- Sidehopper `$D93F`: one-word and operand-bearing setup handlers, four landed
  standard-OAM frames with intentional repeated poses, a ready-to-hop handler, and
  terminal sleep;
- grey walking Space Pirate `$F653`: setup-handler fallthrough into eight full-body
  extended/multibox frames, followed by a backward `GotoY` loop.

Together these cover 17 timed frame occurrences, 15 unique spritemaps, and eight
handler occurrences. Each fixture connects the exact species header, raw GRAPHADR
bytes, palette bytes, initial list, handler sequence, durations, flattened OAM
entries, geometry, rendered frames, and the species-level animation API. Unknown
handlers terminate the bounded trace explicitly; nonvisual effects such as queuing
sound are width-validated and skipped without pretending their game state was
emulated. The older fixed-chunk scanner remains only as a generic fallback while
coverage expands.

This work fixed a concrete preview failure: Sidehopper previously produced zero
animation frames because its variable-width handlers misaligned the four-byte
scanner. It also corrected a test that called stone Zoomer `$DD3F` “Sidehopper”;
the actual Sidehopper species is `$D93F`. Machine-readable evidence is in ignored
`parity/reports/enemy-vertical-slices.json`.

### Explicit Helper-Selected Animation Routes (2026-10-05)

Puyo `$CFBF`, Owtch `$D03F`, Choot `$D3BF`, and the two Sbug/roach headers
`$D87F/$D8BF` cannot be routed reliably by scanning their init routines for a direct
instruction-list store. Their source uses helpers, direction/state selection, or
setup-list fallthrough. E-11 therefore records exact named routes instead of teaching
the generic scanner to guess through arbitrary AI.

The editor exposes 18 compact source actions / 65 guided frames. Puyo has three exact
grounded loops and two ordered hop-pose sequences; each source hop list draws once and
sleeps while gameplay AI advances it. Owtch begins at the left/right visual loops after
retaining the separate direction-setup fallthrough lists in the manifest. Choot exposes
its idle, jumping, and falling state lists separately. Sbug exposes all eight directional
loops shared by its normal and alternate-VRAM headers. Thirty-nine OAM maps, 23 lists,
four palettes, default previews, timing, and deterministic component/action pixels are pinned
by `parityOrdinaryEnemyAnimations` and `OrdinaryEnemyAnimationSourceParityTest`.
See [`ordinary_enemy_source_routes.md`](ordinary_enemy_source_routes.md) for addresses
and edit boundaries.

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
| Kraid | `$E2BF` | 1000 | 20 | 56x144 | `$A7:8687` | `$A7:A959` | 7680 |
| Arm | `$E2FF` | 1000 | 20 | 48x48 | `$A7:8687` | `$A7:AB43` | 7680 |
| Lint (top) | `$E33F` | 1000 | 10 | 24x8 | `$A7:8687` | `$A7:AB68` | 7680 |
| Lint (middle) | `$E37F` | 1000 | 10 | 24x8 | `$A7:8687` | `$A7:AB9C` | 7680 |
| Lint (bottom) | `$E3BF` | 1000 | 10 | 24x8 | `$A7:8687` | `$A7:ABCA` | 7680 |
| Foot | `$E3FF` | 1000 | 20 | 8x8 | `$A7:8687` | `$A7:ABF8` | 7680 |
| Nail | `$E43F` | 10 | 10 | 8x8 | `$A7:8687` | `$A7:BCEF` | 7680 |
| Nail (bad trajectory) | `$E47F` | 10 | 10 | 8x8 | `$A7:8687` | `$A7:BD2D` | 7680 |

All eight headers share the exact raw OAM range `$AB:CC00..EA00` and palette
`$A7:8687`. The arm, lints, foot, and nails use ordinary/extended OAM structures.
Kraid's large body and animated head are a separate BG2 composition: two active
compressed 64×32 room halves plus one of four 32×11 uploaded head frames, all
referencing the complete 1024-tile, no-CRE tileset `$1A` graphics resource. SMEDIT
now renders all four 64×64 live body states, four source head animations, ten paired
runtime palette stages, and 12 bounded linked-OAM animations with 173 frame
occurrences. The assembled composition now also places the representative linked
arm/claw and front foot at their live body-relative AI anchors—`(0,-$2C)` and
`(0,+$64)`, respectively—so the forearm connects and those pieces are no longer
missing from the full-body preview. It safely persists head pixel edits through the ordinary `varGfx["26"]`
tileset relocation path. The tilemaps themselves remain read-only placement data. See
`docs/bosses/kraid.md` and `parityKraid` for the exact consumer, palette-state,
hitbox, animation, and ownership manifest.

### Phantoon (Room $CD13, AI Bank $A7)

| Entity | Species ID | HP | Dmg | Palette | Init AI | Tile Size |
|--------|-----------|-----|------|---------|---------|-----------|
| Body | `$E4BF` | 2500 | 40 | `$A7:CA01` | `$A7:CDF3` | 3072 |
| Eye | `$E4FF` | 2500 | 40 | `$A7:CA01` | `$A7:CE55` | 1024 |
| Tentacles | `$E53F` | 2500 | 40 | `$A7:CA01` | `$A7:CE55` | 1024 |
| Mouth | `$E57F` | 2500 | 40 | `$A7:CA01` | `$A7:CE55` | 1024 |

The header palette `$A7:CA01` and shared raw `$AC:AA00` OBJ payload are separate from
the visible boss's BG2 recipe. The live room uses tileset `$05`; 22 active tilemaps at
`$A7:E0AA..E3D1` place the body, three eye lids, nine eyeball directions, six tentacle
halves, and three mouth poses. Eight health palettes span `$A7:CB41..CC40`; active
full-health is `$A7:CC21`, while `$A7:CA21` is only an unused byte-identical clone.

SMEDIT exposes all 22 components, the closed-eye full body plus all nine gaze poses in
its Compositions view, and five exact bounded part animations (13 timed frame occurrences).
Because the four enemy slots animate independently, animation previews hold the other
three slots in source-valid resting poses. Pixel edits persist through `varGfx["5"]`;
the tilemaps, instruction lists, hitboxes, and raw OBJ payload remain read-only. See
`docs/bosses/phantoon.md` and `parityPhantoon` for the complete ownership manifest.

### Draygon (Room `$DA60`, AI Bank `$A5`)

| Runtime slot | Species ID | HP | Damage | Header palette | Init AI | Raw transfer |
|---|---:|---:|---:|---|---|---:|
| Body | `$DE3F` | 6000 | 160 | `$A5:A1F7` | `$A5:8687` | `$2000` |
| Eye | `$DE7F` | 6000 | 160 | special no-op pointer `$A5:8069` | `$A5:C46B` | `$1800` |
| Tail | `$DEBF` | 6000 | 160 | `$A5:A1F7` | `$A5:C599` | `$1800` |
| Arms | `$DEFF` | 6000 | 160 | `$A5:A1F7` | `$A5:C5AD` | `$1800` |

All four slots point at the same raw enemy payload, `Tiles_Draygon` at
`$B0:C800..E7FF`; the body is the single enemy-set transfer and the other headers
use prefixes of those bytes. That is only the OBJ half of the recipe. Both live and
dead room states select tileset `$1C`, whose decompressed `$4800` graphics payload
feeds 48 extended BG2 tilemaps. Bank `$A5` joins those maps with 94 standard OAM maps
through 103 extended spritemaps.

The dedicated renderer parses all 57 active named instruction lists and renders the
39 lists containing frames: 250 timed frame occurrences, each assembled with
source-valid resting poses for the other independent slots. It also exposes eight
health-dependent replacements for palette indexes 9–12 and the white hurt-flash
palette. Ten static compositions cover left/right resting poses and every eye gaze.
The Sources tab edits the exact `$2000` body-owned OBJ payload, stores it under
`spriteTileBlocks["enemy:DE3F"]`, and reassembles every named composition live while
painting. Placement and flattened BG2/OBJ edits remain read-only.
See [`../bosses/draygon.md`](../bosses/draygon.md) and `parityDraygon` for exact
ownership, source inventories, and the current placement boundary.

### Ridley (AI Bank `$A6`)

| Encounter | Species ID | HP | Dmg | Layer | Palette | Init AI | GFX |
|---|---:|---:|---:|---:|---|---|---|
| Ceres | `$E13F` | 32767 | 5 | 5 | `$A6:E14F` | `$A6:A0F5` | `$B0:9400..B3FF` |
| Norfair | `$E17F` | 18000 | 160 | 2 | `$A6:E14F` | `$A6:A0F5` | `$B0:9400..B3FF` |

These are distinct encounter headers over one visual recipe. Five contiguous assets
form the shared 256-tile source; six later `$B0:B800..B9FF` assets supply runtime
ribs/claws DMA. The enemy set additionally places the read-only `$B0:B400` Ridley-
explosion payload at physical OBJ tiles `$E0..FF`; the facing-forward body selects
that low page while ordinary Ridley parts select `$100..1FF`. Bank `$A6` combines 11
extended body maps with 12 wing maps and an articulated tail built from three segment
maps plus 16 directional tips. The editor therefore exposes one Ridley workspace and
labels Ceres-only lunge/retrieve actions inside it. See
[`../bosses/ridley.md`](../bosses/ridley.md) and `parityRidley` for the complete
ownership, assembly, palette, and safe-edit contract.

### Mother Brain (Room `$DD58`, AI Bank `$A9`)

| Runtime slot | Species ID | HP | Dmg | Hitbox | Palette | Init AI | Header transfer |
|---|---:|---:|---:|---:|---|---|---|
| Head / brain | `$EC3F` | 18000 | 120 | 16x16 | `$A9:9472` | `$A9:8705` | `$1000` from `$B7:8000` |
| Body | `$EC7F` | 18000 | 120 | 8x8 | `$A9:9472` | `$A9:8687` | `$8600` field → `$0600` from `$B0:E800` |

Phase 1 is the head enemy over room-owned glass, tubes, and machinery. Phase 2 is a
split composition: tileset `$0E` physical tiles `$160..1FF` draw the torso; staged
`$B7:8000/$9000` data supplies head/neck and limbs; `$B0:E800` supplies the small
body-owned supplement; and bank `$A9` joins them through 16 active extended maps.
Five runtime-positioned neck segments connect the independent head to the body.
Standard limb OBJ coordinates are already relative to the body enemy origin. The BG2
torso is the layer that needs scroll compensation: that origin lands at tilemap pixel
`($20,$3E)` while standing, `($22,$3E)` while walking, and `($26,$3E)` in the shifted
crouch/stand transition poses. SMEDIT follows those pose-specific runtime anchors so
the rear leg, feet, torso, neck, and head remain one assembly throughout movement. It
also mirrors `ProcessExtendedTilemap`: BG2 placement comes from destinations encoded
inside each tilemap, while the surrounding extended-child X/Y fields are ignored.
Treating those inert fields as pixel offsets splits the torso during walking and
crouching. It
also places priority-2, palette-row-3 rear-leg OBJ behind the high-priority BG2 torso
and priority-3, palette-row-1 front limbs above it, matching the room's actual SNES
layer split.

The consolidated editor renders representative phase-1/2/3 compositions and 14
source-bounded animations, including the exact 15-frame death-beam body list. It
exposes four health pairs and all ten rainbow main/back-leg palette pairs. The head
source is safely editable with live phase-1 and phase-2 references; the combined body
sheet stays read-only because it crosses four owners. See
[`../bosses/mother_brain.md`](../bosses/mother_brain.md) and `parityMotherBrain`.
Walking and crouch/stand previews apply the source instruction handlers' cumulative
enemy-position changes to the full assembly, so the BG2 torso, OBJ limbs, neck, and
head travel together rather than leaving the upper body pinned in place.

### Crocomire (Room `$A98D`, AI Bank `$A4`)

The boss body `$DDBF` and tongue `$DDFF` share `Tiles_Crocomire` at `$AD:8000`.
The body's raw `$A600` header size contains the alternate-layout bit, leaving an
effective `$2600`-byte transfer; the tongue aliases its first `$2000` bytes. Living
poses combine that OBJ source at physical tile `$D0` with room-owned BG2 graphics
from tileset `$1B`.

Death does not reuse the living sheet unchanged. Two `$0C00` melting payloads at
`$A4:A07D/$AC7D` replace physical tiles beginning at `$130`, followed by six
`$0200` skeleton chunks from `$AD:A600..B1FF` uploaded to non-contiguous tile
destinations. The dedicated renderer keeps all four ownership classes separate,
then assembles 74 standard maps, 94 extended maps, and 11 BG2 maps with the exact
palette rows. Its guided UI exposes all 20 multi-frame Fight/Tongue/Melting/Skeleton
lists (218 frames), while strict parity covers all 36 active lists / 233 timed frame
occurrences. Only the living `$2600` OBJ owner is editable through the sprite
workspace. See [`../bosses/crocomire.md`](../bosses/crocomire.md) and
`parityCrocomire` for the runtime transfers and safe-edit boundary.

### Spore Spawn (Room `$9DC7`, AI Bank `$A5`)

Spore Spawn's `$DF3F` body and `$DF7F` alias share the one `$0E00`-byte
`Tiles_SporeSpawn` owner at `$AC:9C00`, but the visible stalk is not another enemy
body. Initialization spawns four bank-`$86` stalk projectiles whose one-entry map is
stored in bank `$8D`; `$A5:EC49` places them at a fixed base and the exact quarter,
half, and three-quarter points between the stalk origin and moving body target.

The dedicated workspace joins those projectiles to 12 active extended body maps,
consolidates the spawner/spore animations, and exposes four health plus eight death
body palettes. Strict parity covers all 22 body-child OAM maps / 365 entries, seven
projectile maps, nine active lists / 41 timed frames, seven source-declared unused
extended maps, and all 27 boss/room palette rows. Only the shared pixel payload is
editable; placement, motion, and room palette effects stay read-only. See
[`../bosses/spore_spawn.md`](../bosses/spore_spawn.md) and `paritySporeSpawn`.

### Botwoon (Room `$D95E`, AI Bank `$B3`)

Botwoon's `$F293` enemy draws only its head. Initialization spawns thirteen bank-`$86`
enemy projectiles—twelve body segments and one tail—whose one-entry maps live in bank
`$8D`. All parts plus the five-frame spit share the one `$1800`-byte `Tiles_Botwoon`
owner at `$B7:E300`.

The body is sampled from a `$400`-byte circular history of four-byte head positions.
Health stages use `$18/$10/$0C` byte distances at speeds 2/3/4, which means 6/4/3
history frames and the same 12-pixel steady segment cadence in every stage. Each link
selects its own eight-way body orientation from the vector to the preceding link; the
last projectile selects the matching tail map. The dedicated workspace renders this
per-link behavior, all eight head directions, independent four-phase body motion,
source-timed spits, the spit projectile, eight health palettes, and partial emergence.
Only the shared pixel payload is editable. See
[`../bosses/botwoon.md`](../bosses/botwoon.md) and `parityBotwoon`.

### Bomb / Golden Torizo (AI Bank `$AA`)

Bomb `$EEFF` and Golden `$EF7F` Torizo share the `$2000`-byte body source at
`$AF:C200`, body instruction machinery, and extended OAM. The apparent `$EF3F/$EFBF`
“orb enemies” are drop-chance headers referenced by bank-`$86` projectile code, not
separate visible Torizos, so all four records resolve through one editor workspace.

The base body is only one of four pixel owners. A read-only `$AA:B279` overlay supplies
eye, destroyed-gut/face, and three egg-release DMA states; `$AF:E200` supplies Golden
egg/hatchling art; `$AD:B200` supplies Bomb statue fragments. The dedicated renderer
keeps low/high physical OBJ pages distinct, applies all 16 transfers only to their
matching frames, and exposes all eight two-row Golden health-palette stages. Strict
parity covers 106 active body maps, 91 active body-child maps, 70 active bank-`$8D`
projectile/effect maps, and 206 body plus 30 projectile animation frames. Only the
shared base body is editable. See [`../bosses/torizo.md`](../bosses/torizo.md) and
`parityTorizo`.

### Normal Metroid (`$DD7F`, AI Bank `$A3`)

The normal Metroid is a three-owner composite even though all pixels come from one
`$1000`-byte `Tiles_Metroid` transfer at `$AE:9000`. The enemy draws four inside
maps; bank-`$B4` sprite objects `$32` and `$34` independently draw 24 electricity
maps and three shell maps at the enemy position. Their entry lists deliberately fall
through into source-labelled unused `$33/$35` lists, so those instruction bodies are
runtime-active and `$35` supplies the third shell pose.

The dedicated editor synchronizes all three tracks, exposes representative complete
compositions and every component, and safely edits the one shared pixel owner with
live assembled references. Placement, companion creation, and timing stay read-only.
See [`metroid.md`](metroid.md) and `parityMetroid` for exact boundaries and timings.

### Mini-Bosses

| Boss | Species ID | HP | Dmg | AI Bank | GFX |
|------|-----------|-----|------|---------|-----|
| Spore Spawn | `$DF3F` | 960 | 12 | `$A5` | `$AC:9C00` |
| Spore Spawn stalk | `$DF7F` | 960 | 12 | `$A5` | `$AC:9C00` |
| Botwoon | `$F293` | 3000 | 120 | `$B3` | `$B7:E300` |
| Bomb Torizo | `$EEFF` | 800 | 8 | `$AA` | `$AF:C200` |
| Golden Torizo | `$EF7F` | 13500 | 160 | `$AA` | `$AF:C200` |
| Mini Kraid | `$E0FF` | 400 | 100 | `$A6` | `$AB:8000` |

Mini Kraid is a complete ordinary OAM enemy, not a spike component. Its renderer uses
six exact source-bounded action lists—forward/backward step and spit, facing both
directions—for 24 frame occurrences and 14 unique spritemaps. It never scans shared
bank `$A6`, which would incorrectly admit neighboring Ridley structures.

---

## Editor-Supported Enemies

The sprite editor currently catalogs **121 source-valid species IDs** through
`EnemySpriteGraphics.EDITOR_ENEMIES`. The broader name catalog contains 150 of the
164 source headers. Catalog membership does not itself prove successful OAM assembly;
the source-complete status ledger below measures the actual production preview path.
Phantoon, Draygon, Mother Brain, and Crocomire each appear once: their internal enemy slots remain fully
represented inside the dedicated boss editor instead of duplicating navigation rows.

### Categories

These are current UI groupings, not engine types or render guarantees:

| UI category | Catalog entries |
|---|---:|
| Boss | 9 |
| Mini-Boss | 8 |
| Space Pirate | 12 |
| Mechanism | 6 |
| Enemy (default) | 86 |
| **Total** | **121** |

The source's 164 headers include internal pieces, projectiles, cutscene entities,
unused data, and other records the sprite editor does not currently catalog. These
coarse UI buckets are therefore navigation aids, not rendering-support claims.

### All-species rendering status

The strict E-08 ledger starts with every source header rather than the 121-entry
sprite picker or bundled PNG filenames. It calls the same tile, palette, ordinary
OAM, special OAM, boss-pose, Kraid BG2, and Phantoon BG2 production paths used by the
editor, including Draygon's split BG2/OBJ, Mother Brain's split room-BG/OBJ, and
Crocomire's room-BG/living-OBJ/melting/skeleton
renderers. A raw tile sheet never
counts as an assembled sprite.

| Status | Count | Meaning |
|---|---:|---|
| Assembled | 139 | At least one ordinary or special OAM frame renders. This measures current production capability; it is not a claim that every animation/state is source-proven. |
| Composite | 14 | A known multi-part/BG2/room-tile recipe has a dedicated renderer. |
| Tile-sheet-only | 9 | `GRAPHADR` tiles and a palette load, but no assembled frame is available. |
| Nonvisual | 2 | The respawn sentinel and one source-declared unused header have no standalone visual contract. |
| Failed | 0 | Every active visual species now has at least an assembled, composite, or tile-sheet path. |

The current tile-sheet-only set is Kzan bottom `$E03F`, Ridley's explosion helper
`$E1BF`, Evir and its projectile `$E63F/$E67F`,
Magdollite `$E83F`, Beetom `$E87F`, Hopper remains `$ED7F`, the Torizo corpse helper
`$EDBF`, and the Tourian statue ghost `$F03F`. Some legacy display names for these
internal records are misleading; source labels are authoritative here.

The former eight failures have zero-size header transfers, but are not empty. Elevator
and Ceres Steam use the always-loaded standard sprite tiles and common sprite palette
5; Zebetite uses Mother Brain's head tiles and common sprite palette 2; the cutscene
Baby Metroid shares the normal Baby Metroid payload; Mother Brain's falling tubes use
the head payload; and the Zoomer/Ripper/Skree corpses share the Sidehopper corpse
payload. Exact source lists now yield 19 visible timed frame occurrences across these
eight species. SMEDIT exposes those bytes only through a read-only preview source:
the tile sheet and exporter still honor the species' zero-byte ownership declaration.
Palette ownership remains independent: the three global-row users are read-only,
while the five headers whose palettes are copied by `ProcessEnemyTilesets` remain
editable.

The machine-readable and human ledgers are generated at
`parity/reports/enemy-species-status.json` and `.md`. Each row records the source ID,
catalog membership, raw tile/palette availability, selected production preview path,
frame count, category, and reason. Counts and the complete row hash are pinned in
`parity/reference.properties`.

### Rendering Modes

- **OAM Spritemap Assembly** — Standard enemies where init/instruction tracing succeeds
- **Special composition** — Boss-specific BG2, OAM, DMA, room-tile, and linked-slot paths;
  the old Phantoon/Kraid generic mappings are quarantined, while their source-backed
  component editors use normal tileset ownership; Draygon keeps its two pixel owners
  explicit; Mother Brain keeps its four body owners explicit and exposes only the
  unambiguous head sheet for editing; Crocomire keeps room, living, melting, and
  skeleton payloads distinct; normal Metroid synchronizes enemy insides with its
  shell/electricity sprite objects; placement remains read-only
- **Raw Tile Sheet** — A direct view is possible for nonempty `GRAPHADR` ranges, but
  raw ownership alone does not prove complete composition or conflict-free editing

### Pixel Editing

Supported raw edits are stored in the project file under
`customGfx.spriteTileBlocks["enemy:<speciesId>"]` and exported to the patched ROM.
The validator enforces the selected header's exact range and size. It does not yet
offer early coordination for two project edits that target aliased or overlapping
ranges; the final write planner rejects conflicting overlaps safely. Quarantined boss
mappings remain non-exportable. See E-09 in the validation matrix.

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
  │     ├── V/H flip, palette row, priority
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
| `EnemyHeaderSourceParityTest.kt` | All 164 source macro records, production parsing, raw range segments, aliases, overlaps, and pixel hashes |
| `EnemyOamSourceParityTest.kt` | All 2,312 standard OAM structures, 811 extended structures, 99 tilemaps, and every decoded field/link through the production parser |
| `EnemyInstructionSourceParityTest.kt` | All 1,139 named enemy lists, their 7,820 source records and raw ranges, frame structures, handler widths, and the exact measured production-preview result |
| `EnemyVerticalSliceSourceParityTest.kt` | Complete source-backed header/asset/instruction/OAM/render paths for Zoomer, Sidehopper, and the grey walking Space Pirate |
| `OrdinaryEnemyAnimationSourceParityTest.kt` | Exact helper-selected Puyo/Owtch/Choot/Sbug routes, timing, OAM/component bytes, default previews, and deterministic action pixels |
| `EnemySharedVramSourceParityTest.kt` | Exact graphics/palette owners, editability, and visible instruction-list frames for all eight zero-transfer visual species |
| `EnemySpeciesStatusSourceParityTest.kt` | All 164 source species classified through production paths as assembled, composite, tile-sheet-only, nonvisual, or failed |
| `EnemySpriteRenderTest.kt` | Palette detection, raw tile decoding, render, stats verification |
| `EnemyExportDiagTest.kt` | Population roundtrip, GFX set, properties bit 0x2000, kill count |
| `EnemySpritemapTest.kt` | OAM parsing, instruction tracing, assembled sprites |
| `EnemyTileScanTest.kt` | GRAPHADR decompression, tileDataSize mask, palette row 0 |
| `PhantoonSpritemapRoundtripTest.kt` | Pixel-perfect match vs reference PNG, edit roundtrip |
| `PhantoonSourceParityTest.kt` | All four slots, 22 tilemaps/extended spritemaps, 19 lists, eight health palettes, full-body/gaze rendering, five bounded animations, hashes, and edit ownership |
| `DraygonSourceParityTest.kt` | Four slots, split BG2/OBJ ownership, 94 OAM + 103 extended + 48 BG2 maps, 57 lists, 250 production frames, palettes, hitboxes, and deterministic complete-composition hashes |
| `RidleySourceParityTest.kt` | Both encounter headers, five shared base assets, the low-page forward/explosion asset and enemy-set placement, six ribs/claws DMA assets, 11 body + 12 wing + 19 tail maps, encounter lists, pointer tables, palettes, and deterministic complete-composition hashes |
| `MotherBrainSourceParityTest.kt` | Both phases, four physical pixel owners, head/body/BG2 structures, exact curated source animations, split palette stages, and deterministic complete-composition hashes |
| `CrocomireSourceParityTest.kt` | Body/tongue aliasing, tileset `$1B`, living/melting/skeleton assets, all active OAM/BG2/list structures, six palettes, and deterministic composition/component/animation hashes |
| `SporeSpawnSourceParityTest.kt` | Shared body/stalk pixels, interpolated projectile placement, body/projectile OAM, palettes, animations, and deterministic render hashes |
| `BotwoonSourceParityTest.kt` | Shared head/body/tail pixels, circular position history, health-speed cadence, head/projectile structures, guided animations, and deterministic render hashes |
| `TorizoSourceParityTest.kt` | Four family headers, split base/overlay/egg/statue pixels, body/body-child/projectile OAM, runtime transfers, health palettes, and deterministic animation hashes |
| `MetroidSourceParityTest.kt` | Shared pixels/palette, four inside + three shell + 24 electricity maps, independent companion timing, both live fallthrough continuations, and deterministic complete-animation hashes |

---

## Sources

- Kejardon's EnemyData.txt — Full 64-byte species header format
- Patrick Johnston bank $A0 disassembly — `ProcessEnemyTilesets` at $A0:8D64
- SM decompilation (`snesrev/sm`) — C structs in `ida_types.h`
- VG Resource SM ripping project — GRAPHADR discovery, tilemap format
- Generated data: `docs/code/scan_enemies.py --markdown`
