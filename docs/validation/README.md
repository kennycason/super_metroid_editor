# Disassembly Parity Program

Last updated: 2026-10-05

This is the working ledger for validating SMEDIT against the exact Super Metroid
assembly and extracted assets. The immediate goal is not to make SMEDIT depend on
the assembly project. The goal is to turn the assembly into an independent oracle
for every parser, renderer, editor, validator, and exporter we already have.

The long-term north star is two explicit project modes:

1. **ROM patch project** — open an existing ROM and make bounded, validated edits.
2. **Assembly project** — edit source-owned data/code and build a new ROM.

Assembly-project authoring stays out of scope until vanilla ROM parity is measured.
Otherwise we would be building a second workflow on top of assumptions we have not
yet proven.

## Baseline and sources of truth

The pinned reference is:

- Repository: `https://github.com/InsaneFirebat/sm_disassembly.git`
- Managed local checkout: `parity/work/sm_disassembly` (ignored)
- Commit: `11c906f547edc1b57f5a5923cf977fe7b50a3694`
- Commit date: 2026-09-26
- Extracted assets: 1,130 `.bin` files, approximately 5.4 MiB
- Rebuilt ROM: `parity/work/sm_disassembly/SM.sfc` (ignored managed checkout)
- Rebuilt ROM SHA-256: `12b77c4bc9c1832cee8881244659065ee1d84c70c3d29e6eaf92e6798cc2ca72`
- Rebuilt ROM SHA-1: `da957f0d63d14cb441d215462904c4fa8519c613`
- Result: byte-identical to the clean vanilla ROM used by SMEDIT's tests

Use evidence in this order:

1. A byte-identical assembled ROM.
2. Assembly labels, macros, pointer expressions, and control flow.
3. `tools/rip_assets.py` plus the extracted `.bin` payloads.
4. Runtime behavior observed in an emulator.
5. Community documentation and SMILE behavior as supporting context.
6. Existing SMEDIT comments, diagnostic images, and hand-maintained address lists.

The disassembly checkout is a development oracle, not a runtime dependency. Normal
SMEDIT builds and user projects must not require a personal absolute path.
See [`../../parity/README.md`](../../parity/README.md) for portable setup and the
strict fixture-identity command.

## Status language

| Status | Meaning |
|---|---|
| **Verified** | Compared with a named assembly symbol/data range and protected by a deterministic assertion. |
| **Covered** | SMEDIT has useful tests, but they currently prove internal consistency rather than disassembly parity. |
| **Partial** | Some formats or variants work; the supported boundary is not yet measured. |
| **Queued** | Inventory exists but source-backed validation has not started. |
| **Mismatch** | Concrete disagreement with the exact assembly or extracted data. |
| **Blocked** | A named external/runtime fact is required before work can continue. |

An item is not **Verified** merely because it renders a plausible image or because a
test produced no failure.

## Definition of done for one validation unit

Each row we complete should leave behind all of the following where applicable:

- The assembly symbol, source file, SNES address, size, and asset name are recorded.
- SMEDIT reads the same raw bytes and assigns the same field boundaries.
- Parsed semantic values are asserted, including flags and signed values.
- An unchanged parse/serialize or decompress/recompress round trip is checked.
- A one-field edit changes only the expected pointer or owned byte range.
- Rendered output has structural assertions and an approved deterministic golden.
- Complex runtime behavior has at least one emulator-backed smoke case.
- Missing fixtures fail or explicitly skip; they never silently return as a pass.
- The relevant permanent ROM documentation is corrected after the proof lands.

## Initial audit findings

### What is already strong

SMEDIT has much more machinery than a first glance suggests:

- Room headers, selectors, state data, PLMs, enemies, enemy GFX sets, FX, doors,
  scrolls, backgrounds, embedded layer 2, minimaps, and save stations are parsed.
- Room/state export uses allocation and write-safety infrastructure rather than
  unbounded direct writes.
- The graphics layer handles LZ5 data, 2bpp/4bpp tiles, CRE/variable tiles,
  metatiles, palettes, flips, and pixel round trips.
- Enemy rendering supports standard OAM, extended/multibox spritemaps, multiple
  palette rows, and several runtime tile-transfer special cases.
- Kraid, Phantoon, Draygon, Ridley, and Mother Brain have source-pinned complete
  composition and animation renderers; Samus has pose, DMA, tilemap, palette, and
  animation code.
- The focused 2026-10-02 baseline ran 114 tests across the room, tile,
  enemy/OAM, extended-spritemap, Kraid, Phantoon, Ridley, and Samus suites with
  zero failures.

Those tests are a good regression base. They do not yet establish full source parity.

### Remaining validation weaknesses

- P0.2 removed user-specific fixture lookup. The shared test-only helper now aborts
  missing-fixture tests explicitly, so legacy `loadTestRom() ?: return` callers are
  reported as skipped before reaching the silent return. Those redundant nullable
  call sites can be simplified incrementally.
- At least 25 ROM test files are diagnostic/audit/render/export-style tools. Several
  print observations or write PNGs without asserting correctness.
- Some render tests scan arbitrary bank bytes for data that merely looks like a
  spritemap. This is useful for exploration but can produce believable false positives.
- Most address knowledge is duplicated as numeric constants. Nothing currently
  compares those constants to `symbols.sym`.
- Existing PNG resources are useful visual references, but comparing SMEDIT output to
  images originally produced by SMEDIT is not independent validation.

### Confirmed mismatches requiring early correction

#### 1. Legacy Phantoon tile-sheet data points into Mother Brain graphics

The deprecated `EnemySpriteGraphics.PHANTOON_BLOCKS` uses PC offsets `0x1B970F`
and `0x1B9808`, which resolve to `$B7:970F/$B7:9808`.

The exact assembly shows:

- `$B7:9000..9FFF` = `Tiles_MotherBrainLegs`
- `$AC:AA00..B5FF` = `Tiles_Phantoon` (`$C00` raw bytes)
- `$A7:E0AA...` = Phantoon's named extended BG2 tilemaps

Before P0.1, the loader LZ5-decompressed bytes inside Mother Brain's raw leg graphics,
and its self-consistency tests only asserted that the resulting blocks were non-empty.
The legacy exporter could write compressed data back over that same Mother Brain range.

The newer assembled-component path uses Phantoon's room tileset and named extended
tilemaps and is separate from this legacy sheet path. P0.1 removed the legacy
loader/editor/writer surface. Old `phantoon:*` project data is now visible only as a
resettable export blocker.

Status: **Mismatch — P0 safety item**

#### 2. Legacy Kraid tile-sheet export pointed at a BG2 tilemap

The quarantined legacy `EnemySpriteGraphics.KRAID_BLOCKS` identifies `$B9:FA38` as compressed Kraid
sprite graphics. The exact assembly labels it
`Background_Brinstar_1A_Kraid_Upper`, a compressed BG2 tilemap. It is not 4bpp tile
graphics.

The exact source distinguishes three resources:

- `$AB:CC00` = `Tiles_Kraid`, `$1E00` raw enemy/OAM tile bytes
- `Tiles_1A_Kraid.bin` = compressed room tileset graphics
- `$B9:FA38` = compressed upper Kraid BG2 tilemap

Before P0.1, both assembled-component and legacy tile-sheet edits were persisted
under `kraid:0`, and the exporter wrote either form through the false `$B9:FA38`
mapping. P0.1 removed that loader/writer and made old `kraid:*` data a resettable
export blocker. B-01 subsequently proved the complete recipe: tileset `$1A` owns all
1024 BG tiles and has no CRE graphics overlay; `$B9:FA38/$B9:FE3E` form the active
64×64 BG2 room map; and `$A7:97C8..A0C8` are four 32×12 head maps whose first 32×11
rows are uploaded by the custom interpreter. Head pixel editing now saves the complete
32 KiB `Tiles_1A_Kraid` unit through `varGfx["26"]`, while placement maps remain
read-only and `$AB:CC00` remains a distinct linked OAM resource.
The production UI now exposes four full BG2 body states, four exact head animations,
ten paired runtime palette stages, and 12 bounded linked-OAM animations containing 173
frame occurrences. Mini Kraid uses six exact action lists with 24 frame occurrences
and 14 unique poses; the previous shared-bank scan no longer admits Ridley data.

Status: **Resolved and parity-pinned — B-01**

#### 3. Boss documentation has shifted IDs and banks

The exact assembly's source-bank ownership is:

| System | Exact AI/source bank |
|---|---|
| Crocomire | `$A4` |
| Draygon and Spore Spawn | `$A5` |
| Ridley and Mini-Kraid | `$A6` |
| Kraid and Phantoon | `$A7` |
| Mother Brain | `$A9` |
| Bomb/Golden Torizo | `$AA` |
| Botwoon | `$B3` |

Several existing docs instead group these under `$A2`, `$A7`, or `$A8`. The boss
species table in `docs/graphics/sprites.md` also assigns Draygon, Ridley, and Mother
Brain IDs that belong to unrelated ordinary enemies. Verified anchor IDs include:

- Draygon body/eye/tail/arms: `$DE3F/$DE7F/$DEBF/$DEFF`
- Ridley: `$E17F` (Ceres Ridley: `$E13F`)
- Kraid: `$E2BF` plus `$E2FF..E47F` parts
- Phantoon: `$E4BF` plus `$E4FF/$E53F/$E57F` parts
- Mother Brain phase 1/2: `$EC3F/$EC7F`

Status: **Mismatch — canonical docs must be regenerated from symbols/headers**

#### 4. Hardcoded constants have no drift detector

Some constants do match exact symbols—for example Samus's spritemap and DMA tables
at `$92:808D`, `$92:9263`, `$92:945D`, `$92:D91E`, and `$92:D938`, and the named
Kraid/Phantoon tilemaps. The problem is that correct and stale constants currently
look identical in code review. A generated/reference manifest should make disagreement
an immediate test failure.

Status: **Partial**

## Validation matrix

This matrix tracks source-backed proof, not feature existence.

### Foundation

| ID | Unit | Current status | Next proof |
|---|---|---|---|
| F-01 | Exact assembly build | **Verified** | `parityBuildReference` reproducibly extracts assets, builds pinned Asar, emits symbols, and requires byte identity. |
| F-02 | Portable private-fixture contract | **Verified** | Bootstrap/check commands, shared test-only lookup, exact hash validation, and explicit skip/fail behavior. |
| F-03 | `symbols.sym` reader | **Verified** | Strict WLA parser, searchable JSON catalog, name/address lookups, and seed drift assertions. |
| F-04 | Extracted-asset manifest | **Verified** | All 1,130 active NTSC assets map to named source ranges and exactly match rebuilt ROM bytes; 17 PAL-only declarations are explicit. |
| F-05 | Address drift test | **Partial** | Twelve standalone boss/Samus constants and all 87 tileset pointer fields are source-linked; inventory and map the remainder. |
| F-06 | One parity report command | **Verified** | `parityReport` runs the strict foundation/LZ5/tileset/CRE/tile-format/animated-tile/item-PLM/enemy-header/enemy-OAM/enemy-instruction/enemy-slice/helper-routed-animation/species-status chain and writes ignored JSON/Markdown evidence with status counts. |
| F-07 | Golden-image policy | **Queued** | Separate human-approved/emulator goldens from diagnostic output. |

### Shared graphics and compression

| ID | Unit | Current status | Source-backed target |
|---|---|---|---|
| G-01 | LoROM/headered address conversion | **Covered** | Exhaustively compare symbol addresses and both ROM layouts. |
| G-02 | LZ5 decompression | **Verified** | Independent `$80:B119` oracle plus Kotlin checks match decoded size/hash for all 421 exact streams; malformed input fails closed. |
| G-03 | LZ5 recompression | **Verified** | All 421 payloads survive SMEDIT encode/decode byte-exactly; destination capacity and the 64 KiB engine maximum are enforced. |
| G-04 | 2bpp/4bpp tile decoding | **Verified** | Independent source oracle matches all 10,944 4bpp tiles and 256 Layer-3 2bpp tiles through production decoders, including standard, global split-plane Ceres, blank-gap, and flip cases. |
| G-05 | CRE graphics/tile table | **Verified** | Exact compressed/decoded sizes and hashes, the adjacent ROM split, WRAM/VRAM boundaries, semantic tile range, and all four direct engine consumers are source-backed. |
| G-06 | 29 tileset pointer triples | **Verified** | All 87 fields match the clean ROM, detected catalog, source labels, named assets, and decoded hashes; 20 intentional alias groups are explicit. |
| G-07 | Metatile words | **Verified** | All 45,056 source words match exact runtime placement/ownership and semantic hashes; all 65,536 possible words exhaustively round-trip tile/palette/priority/H/V fields. |
| G-08 | Animated tiles | **Verified** | All 68 bank-$87 payloads, 94 reachable timed frame instructions, 20 object headers/DMA destinations, 64 area-bit mappings, and 15 engine call sites match named source and clean ROM. |
| G-09 | Item/PLM graphics | **Verified** | All 17 bank-$89 payloads / 136 tiles, 51 visible/Chozo/shot-block PLM IDs and load records, five palette profiles, four runtime slots, and eight draw pointers match source, clean ROM, and SMEDIT's catalog/decoder. |

### Rooms and world data

| ID | Unit | Current status | Source-backed target |
|---|---|---|---|
| R-01 | Room catalog and 11-byte headers | **Partial** | Match every named room header in bank `$8F`. |
| R-02 | State selectors and 26-byte state data | **Partial** | Match branch order, condition routines, and state pointers for all rooms. |
| R-03 | 246 extracted level-data streams | **Queued** | Decompress, parse dimensions/layers/BTS, then compare raw and semantic hashes. |
| R-04 | PLM populations | **Partial** | Match every bank `$8F` set, terminator, parameter, and room-state pointer. |
| R-05 | Enemy populations | **Partial** | Match every bank `$A1` set, pieces, properties, terminator, and kill count. |
| R-06 | Enemy GFX sets | **Partial** | Match every bank `$B4` set, palette/VRAM field, terminator, and four-slot limit. |
| R-07 | FX entries | **Partial** | Match bank `$83` lists, sentinels, liquids, palettes, and door dependencies. |
| R-08 | Door lists and DDBs | **Partial** | Match all sources/destinations, directions, caps, distances, and door ASM. |
| R-09 | Static scrolls and sentinels | **Partial** | Match every table, `$0000/$0001` semantics, dimensions, and aliases. |
| R-10 | Scroll PLMs and door-ASM overrides | **Partial** | Prove load/write order and every vanilla mutation target. |
| R-11 | BG data and embedded layer 2 | **Partial** | Match library/custom BG commands, transfers, and shared resources. |
| R-12 | Minimap and map stations | **Partial** | Match bank `$B5` maps, reveal masks, area transforms, and write round trips. |
| R-13 | Save/elevator/debug stations | **Partial** | Match tables, area ownership, slot limits, and runtime spawn fields. |

### Enemy sprites

| ID | Unit | Current status | Source-backed target |
|---|---|---|---|
| E-01 | All bank `$A0` 64-byte species headers | **Verified** | All 164 assembled headers / 4,756 macro fields match exact source expressions, rebuilt-ROM bytes, and SMEDIT's full production parser; one explicitly unused header remains inventoried. |
| E-02 | `GRAPHADR` raw tile ranges | **Verified** | All 155 nonempty species associations map to 100 unique ranges and 99 named assets with byte/pixel identity; 25 shared-start groups, 24 exact-range aliases, nine overlapping range pairs, and six multi-asset ranges are explicit. |
| E-03 | Palette pointer/bank behavior | **Partial** | Compare every species plus runtime palette overrides and multi-row cases. |
| E-04 | Standard 5-byte OAM entries | **Verified** | All 2,312 named structures / 14,400 entries match source bytes and production decoding for signed X/Y, size, tile/name-table, priority, palette, and flips; lower-index-wins OBJ overlap order is reproduced, and all 12 valid empty structures remain explicit. |
| E-05 | Extended/multibox spritemaps | **Verified** | All 811 named extended structures / 1,984 child links and 99 extended tilemaps / 2,763 words match source offsets, child types, hitbox pointers, runs, and production parsing. |
| E-06 | Instruction-list interpreter | **Partial** | All 1,139 named lists / 7,820 records are source- and ROM-pinned. The generic fixed-chunk fallback recovers 2,543 of 4,395 renderable source frames (57.9%), misses 1,852 frames across 465 lists, and crosses named-list boundaries for 4,718 candidates. A fail-closed bounded interpreter now proves the Zoomer, Sidehopper, and walking Space Pirate visual paths, but broad handler/state semantics remain incomplete. |
| E-07 | VRAM destination/name-table mapping | **Partial** | The eight active zero-transfer species now have exact read-only graphics providers: standard global sprite tiles for Elevator/Steam, shared Mother Brain head tiles for Zebetite/tubes, normal Baby Metroid tiles for the cutscene entity, and the Sidehopper-owned common corpse payload for three corpse species. The live Sidehopper corpse poses additionally prove the adjacent common-corpse plus large-Sidehopper OBJ layout. Their independently resolved palette ownership remains editable or read-only as appropriate. Full `ProcessEnemyTilesets`, arbitrary room VRAM, and dynamic DMA modeling remain. |
| E-08 | All-species render inventory | **Verified** | All 164 source headers are pinned as 145 assembled, zero tile-sheet-only, 15 source-known composites, four nonvisual, and zero failed. Production paths render 159 species; every former raw-only record now has an exact visual or engine-helper classification. |
| E-09 | Sprite tile edit/export | **Partial** | Parse/edit/export/reparse, assert exact owned ranges and aliases. |
| E-10 | Pre-rendered PNG fallbacks | **Partial** | Make fallback visible; never count it as a successful ROM-derived render. |
| E-11 | Helper-selected ordinary-enemy animations | **Verified** | Puyo, Owtch, Choot, both Sbug/roach headers, Evir plus its internal projectile, Magdollite, Beetom, the three Kihunter color bodies, and both Sidehopper corpse headers now have 59 exact source-routed actions / 255 guided frames with pinned headers, pixels, palettes, OAM, lists, timing, default previews, and render hashes. |

### Composite enemies and bosses

These are case studies, because a single generic “enemy sprite” model cannot describe
BG layers, per-frame DMA, linked enemy slots, palette FX, and room-owned graphics.

| Order | Case | Relevant exact sources | Current status |
|---|---|---|---|
| B-01 | Kraid + Mini Kraid | AI `$A7/$A6`; OAM tiles `$AB:CC00/$AB:8000`; BG2 tilemaps `$A7:97C8...` and `$B9:FA38...`; tileset `$1A` | **Verified** |
| B-02 | Phantoon | AI/extended tilemaps `$A7`; raw OBJ tiles `$AC:AA00`; tileset `$05`; eight health palettes | **Verified** |
| B-03 | Draygon | AI `$A5`; raw tiles `$B0:C800`; tileset `$1C`; four linked species | **Verified** |
| B-04 | Ridley | AI `$A6`; staged tile chunks `$B0:9400...`; ribs/claws `$B0:B800...`; custom OAM add routine | **Verified** |
| B-05 | Mother Brain | AI `$A9`; body `$B0:E800`; head/legs `$B7:8000/$B7:9000`; tileset `$0E`; HDMA/palette phases | **Verified graphics / Partial effects** |
| B-06 | Crocomire | AI `$A4`; body/skeleton/melting transfers; tileset `$1B` | **Verified graphics** |
| B-07 | Spore Spawn | AI `$A5`; extended body plus bank-`$86/$8D` stalk/spawner/spore projectiles | **Verified graphics** |
| B-08 | Botwoon | AI `$B3`; enemy head plus 13 history-following bank-`$86/$8D` body projectiles | **Verified graphics** |
| B-09 | Torizos | AI `$AA`; shared body, runtime tile replacement, health palettes, and bank-`$86/$8D` projectiles | **Verified graphics** |

For each case, validate the complete composition recipe—not only one attractive frame.
That includes which layer draws each part, initial enemy-slot ordering, tile transfer
timing, palette state, hitboxes, and edit/export ownership.

### Samus

| ID | Unit | Current status | Source-backed target |
|---|---|---|---|
| S-01 | Pose/animation tables in `$91/$92` | **Partial** | Enumerate every pose and frame transition from labels. |
| S-02 | Top/bottom DMA definition tables | **Partial** | Assert `$92:D91E/$92:D938`, every table/entry, source, and byte count. |
| S-03 | Top/bottom spritemap indices | **Partial** | Assert `$92:9263/$92:945D` and every pose lookup. |
| S-04 | Spritemap table | **Partial** | Assert `$92:808D`, halves, offsets, flips, and forward-facing exceptions. |
| S-05 | 418 extracted Samus tile assets | **Queued** | Associate every asset with its DMA entry and prove no missing/overread data. |
| S-06 | Power/Varia/Gravity palettes | **Covered** | Match all normal, heat, charge, speed, hurt, and special palette states. |
| S-07 | All-pose render atlas | **Partial** | Deterministic structural checks plus reviewed goldens for every valid pose. |
| S-08 | Samus edit/export | **Queued** | Define safe source ownership before enabling source- or ROM-backed pixel edits. |

### Audio and remaining banks

Audio is not the first sprite/room milestone, but the same method applies to the
extracted SPC engine and 24 music payloads in banks `$CF..$DE`. Cinematics, projectiles,
HDMA, menus, and cutscene graphics should enter the matrix after rooms, enemies, and
Samus have a stable validation harness.

## Ordered work queue

Work one checked slice at a time. Do not mark parent rows verified from a spot check.

### P0 — Make the oracle trustworthy and remove known hazards

- [x] **P0.1** Quarantine the disproved Phantoon/Kraid legacy tile-sheet mappings
  with clear validation blockers. Later source-backed component paths use separate keys.
- [x] **P0.2** Add an explicit parity-fixture contract for the vanilla ROM and
  disassembly checkout; remove personal absolute test paths and silent success.
- [x] **P0.3** Parse `symbols.sym` and assert a small seed set of named addresses.
- [x] **P0.4** Parse assembly `incbin`s / `rip_assets.py` into an asset-range manifest.
- [x] **P0.5** Add a single parity-report Gradle task with no source-tree output.
- [x] **P0.6** Correct the Phantoon and Kraid load/edit/export ownership using named
  raw graphics and tileset resources; add expected-before checks for every target.
- [x] **P0.7** Regenerate/correct the boss IDs and bank ownership in permanent docs.

### P1 — Prove the ordinary enemy pipeline end to end

- [x] **P1.1** Generate all species headers from `EnemyHeader` macros and compare all fields.
- [x] **P1.2** Compare every `GRAPHADR` range and alias with named enemy tile assets.
- [x] **P1.3** Turn the OAM parser tests into named-source format fixtures.
- [x] **P1.4** Measure instruction-list opcode/control-flow coverage; list every miss.
- [x] **P1.5** Validate three vertical slices: Zoomer, Sidehopper, and one Space Pirate.
- [x] **P1.6** Produce an all-species status report that distinguishes assembled,
  tile-sheet-only, composite, nonvisual, and failed.
- [x] **P1.7** Resolve every active zero-transfer visual species through source-pinned
  runtime graphics and palette owners; keep borrowed graphics/global palettes read-only
  without suppressing independently loaded header palettes.
- [x] **P1.8** Add exact source routes for helper/state-selected ordinary enemies,
  beginning with Puyo, Owtch, Choot, Sbug, and Evir; retain AI-stepped, direction-table,
  shared-header, and fallthrough semantics rather than inventing generic loops.

### P2 — Prove special composition

- [x] **P2.1** Kraid complete recipe and safe edit ownership.
- [x] **P2.2** Phantoon complete recipe and safe edit ownership.
- [x] **P2.3** Draygon body/eye/tail/arms composition and room dependencies.
- [x] **P2.4** Ridley staged DMA, body/wings/tail, Ceres variant, and palettes.
- [x] **P2.5a** Mother Brain phase 1/2 body/head/legs composition, exact source
  animations, four health pairs, ten rainbow palette stages, and safe head ownership.
- [ ] **P2.5b** Mother Brain rainbow-beam HDMA geometry, projectiles, room destruction,
  and target-dependent neck motion.
- [x] **P2.6a** Crocomire living BG2/OBJ assembly, tongue, melting overlays,
  skeleton DMA, exact source animations, palettes, and safe body ownership.
- [ ] **P2.6b** Crocomire bridge/lava/environmental effects.
- [x] **P2.7a** Spore Spawn body/stalk runtime assembly, spawner/spore projectiles,
  health/death palettes, exact source animations, and shared pixel ownership.
- [ ] **P2.7b** Spore Spawn fight-path simulation, dust/explosion effects, and room
  death-palette rendering.
- [x] **P2.8a** Botwoon enemy head, twelve animated body projectiles, tail, spit,
  circular position-history placement, health palettes, and shared pixel ownership.
- [ ] **P2.8b** Botwoon full hole/fight/death-path simulation and room-priority effects.
- [x] **P2.9a** Bomb/Golden Torizo shared body, low/high OBJ pages, eye/damage/egg
  runtime transfers, Golden health palettes, projectile/effect maps, exact guided
  animations, and safe shared-source ownership.
- [ ] **P2.9b** Torizo full encounter simulation: AI decisions, collision, room events,
  sound/explosion timing, and Bomb statue fragment motion.
- [x] **P2.10a** Normal Metroid three-owner assembly: enemy insides, shell/electricity
  sprite objects, exact independent timing, fallthrough continuations, and shared
  editable pixel ownership.
- [ ] **P2.10b** Metroid gameplay simulation: latch/drain transition phase, freeze,
  bomb-release behavior, collision, and synchronized flashing.

### P3 — Prove Samus

- [ ] **P3.1** Build the source-derived pose/DMA/spritemap manifest.
- [ ] **P3.2** Associate all 418 extracted tile payloads with their consuming entries.
- [ ] **P3.3** Validate every pose structurally and report invalid/missing frames.
- [ ] **P3.4** Review and freeze full Power/Varia/Gravity pose atlases.
- [ ] **P3.5** Add emulator smoke captures for representative dynamic states.

### P4 — Prove rooms and exporters exhaustively

- [ ] **P4.1** Validate all room headers and state selectors.
- [ ] **P4.2** Validate all 246 extracted level-data streams and layer layouts.
- [ ] **P4.3** Validate every PLM, enemy, GFX, FX, door, and scroll resource.
- [ ] **P4.4** Run no-edit parse/export/reparse checks per subsystem.
- [ ] **P4.5** Run one-field mutation tests and assert the complete ROM diff allowlist.
- [ ] **P4.6** Add representative emulator traversal/state-switch smoke tests.

### P5 — Prepare, but do not yet ship, assembly-project mode

- [ ] Define a source workspace descriptor and supported disassembly version.
- [ ] Let SMEDIT provision the supported `sm_disassembly` checkout as an explicit
  assembly workspace, then index its banks, labels, macros, and assets for a native
  ASM editor mode.
- [ ] Keep SMEDIT-authored comments, relationships, validation facts, and editing
  context in a versioned overlay database so upstream source stays clean and can be
  refreshed deliberately.
- [ ] Make the reference manifest usable without hardcoded ROM addresses in UI code.
- [ ] Add read-only browsing from source symbol to SMEDIT object and rendered asset.
- [ ] Prototype source-owned asset edits in a disposable checkout.
- [ ] Build through an adapter and compare the result with the expected ROM diff.
- [ ] Only then design “New project from assembly” UX and project persistence.

## Progress log

| Date | Change | Evidence |
|---|---|---|
| 2026-10-02 | Established exact source oracle | Asar build produced a byte-identical ROM with the hashes above. |
| 2026-10-02 | Inventoried extracted assets | 1,130 `.bin` files; major groups include 418 Samus tiles, 246 level-data streams, 197 tile payloads, and 70 backgrounds. The initial filename-prefix count of 64 animated-tile payloads was later corrected to 68. |
| 2026-10-02 | Ran focused SMEDIT baseline | 114 tests, 0 failures across selected room/graphics/sprite/Samus suites. |
| 2026-10-02 | Found Phantoon legacy range mismatch | SMEDIT `$B7:970F/$B7:9808` falls inside exact `Tiles_MotherBrainLegs`; exact Phantoon tiles are `$AC:AA00`. |
| 2026-10-02 | Found Kraid legacy range mismatch | SMEDIT `$B9:FA38` is exact `Background_Brinstar_1A_Kraid_Upper`, not pixel graphics. |
| 2026-10-02 | Corrected Kraid tileset identity | Exact `RoomState_Kraid_0/1` uses tileset `$1A` (decimal 26); SMEDIT UI/comments and its special-case constant incorrectly said decimal 27. |
| 2026-10-02 | Found canonical documentation drift | Exact source bank/ID ownership disagrees with the current boss reference tables. |
| 2026-10-02 | Quarantined disproved boss sprite exports | Removed the dangerous loader/writer paths, added validator/export blockers and regression tests, disabled affected pixel-editing surfaces, and retained reset controls for old project data. Phantoon Components remains editable through its separate tileset-backed path; 86 focused tests pass. |
| 2026-10-02 | Added portable parity fixtures | Added the pinned `parity/` workspace, ignored managed clone, exact ROM hash check, strict `parityCheck`, and one test-only fixture helper across JVM modules. A 107-test configured-fixture run passed; without the ROM, legacy ROM-backed cases report skipped instead of passing. |
| 2026-10-02 | Automated the exact reference build | `parityBuildReference` provisions pinned Asar 1.81 source, extracts private assets into the ignored checkout, emits `SM.sfc` and `symbols.sym`, and fails unless the ROM is byte-identical. |
| 2026-10-02 | Added the source-symbol catalog | A strict WLA parser exports 64,992 named labels; seed parity assertions tie tileset, boss, and Samus constants directly to exact source symbols. |
| 2026-10-02 | Added the extracted-asset manifest | All 1,130 active NTSC `incbin` files now have source labels, exact ROM ranges, sizes, aliases, and hashes; all match the rebuilt ROM. The report also records 17 PAL-only declarations and 55 advisory source-comment size mismatches. |
| 2026-10-02 | Added the unified foundation report | `parityReport` executes the strict build/catalog/manifest/tagged-test chain and writes ignored JSON plus Markdown with exact identity and pass/partial/mismatch/uncovered counts. |
| 2026-10-03 | Proved LZ5 decode/recompression parity | An independent `$80:B119` model classified 421 exact streams (417 active, 4 unused). SMEDIT matches every decoded size/hash and every payload survives re-encoding; strict malformed-input and 64 KiB destination checks replaced two permissive duplicate decoders. |
| 2026-10-03 | Proved all tileset pointer triples | A source-derived manifest maps 87 pointer fields across 29 tilesets to 55 named assets (14 tables, 16 graphics, 25 palettes), verifies the secondary pointer table, and records 20 intentional alias groups. Clean-ROM/catalog pointers and all decoded hashes match. |
| 2026-10-03 | Proved CRE ownership and consumers | CRE graphics are 8,349 compressed / 12,288 decoded bytes and end exactly at the 1,431 compressed / 2,048 decoded-byte CRE tile table. Runtime boundaries, all 1,024 CRE subtile references, and both graphics plus both table consumer routines are asserted. This also corrected the old documentation's reversed metatile-table order. |
| 2026-10-03 | Proved tile pixels and metatile semantics | An independent source oracle now covers 10,944 4bpp tiles, 256 Layer-3 2bpp tiles, and 45,056 metatile words. It proved the two global split-plane Ceres payloads, corrected the unused legacy 2bpp/4bpp decoder, distinguished the normal 576-tile source payload from its 640-tile runtime capacity, and caught Kraid's CRE-table ownership independently of its graphics overlay. |
| 2026-10-03 | Proved animated-tile ownership and DMA | Source/ROM graph traversal covers 68 raw payloads, 94 reachable timed frame instructions, 20 object definitions, all 64 area/FX-bit mappings, and all 15 spawn/handler/DMA call sites. Sixty-five payloads are referenced; three explicitly unused `X` ranges are orphaned. The proof corrected the old 64-file prefix count, which omitted four `UNUSED_AnimatedTiles_CrateriaLava_*` files. |
| 2026-10-04 | Proved item-PLM graphics and slot ownership | All 17 contiguous bank-$89 payloads decode to 136 standard-4bpp tiles / 34 frames. The 51 visible/Chozo/shot-block source PLM IDs match `RomParser.ITEM_DEFS`; all source pointers, palette bytes, four wrapping VRAM/metatile slots, eight draw pointers, and the interpreter call are asserted. This establishes the fifth-item visual-overwrite boundary without confusing pickup images with OAM sprites. |
| 2026-10-04 | Proved every enemy species header and raw graphics range | All 164 bank-$A0 headers / 4,756 source macro fields now match rebuilt-ROM bytes and SMEDIT's full 64-byte parser. All 155 nonempty `GRAPHADR` associations decode byte/pixel-exactly and resolve through 168 contiguous segments into 99 named assets. The manifest makes 25 shared starts, nine overlap pairs, six cross-asset transfers, and six bit-15 alternate-layout headers explicit; it also removed eight catalog IDs that landed mid-header. |
| 2026-10-04 | Proved every named enemy OAM structure | A source-derived manifest and production-parser tests now cover 2,312 standard spritemaps / 14,400 entries, 811 extended spritemaps / 1,984 child associations, and 99 extended tilemaps / 2,763 words. The proof fixed valid zero-entry “nothing” spritemaps and the engine's low-byte-only extended child count (`$1001` Ceres steam), preserves OAM priority, recognizes source naming variants including eight one-off Zebetite labels, and explicitly excludes one source-declared Torizo orphan entry whose count is missing. |
| 2026-10-04 | Measured every named enemy instruction list | The source manifest covers 1,139 lists / 7,820 records: 4,573 timed frames and 3,247 handler occurrences at 502 unique addresses. It pins exact operand widths and conservative control-flow classes, explicitly excludes 21 similarly named lists/routines owned by other interpreters, and validates the report against SMEDIT's generic preview fallback. That scanner recovers only 2,543 of 4,395 renderable source frames, misses 1,852 across 465 lists, and can walk into adjacent named blocks; its explicit miss inventory became the baseline for the bounded P1.5 interpreter. |
| 2026-10-04 | Proved three ordinary-enemy vertical slices | Zoomer, Sidehopper, and the grey walking Space Pirate now have source-backed header, GRAPHADR, palette, entry-list, handler path, timed-frame, standard/extended OAM, geometry, render, and species-animation checks. The bounded interpreter preserves 17 frame occurrences / 15 unique spritemaps and eight handler occurrences, follows fallthrough and loops, stops at sleep, retains repeated poses, and fails explicitly on unknown handlers. This fixed Sidehopper's zero-frame preview and a test that had mislabeled stone Zoomer `$DD3F` as Sidehopper instead of using `$D93F`. |
| 2026-10-04 | Classified every source enemy species | A production-path ledger now covers all 164 source headers: 126 assembled, 14 tile-sheet-only, 14 composite, two nonvisual, and eight failed; 140 have a real assembled preview. It reports source/catalog membership, GRAPHADR/palette availability, preview path, frame count, and an explicit reason per species. The audit also fixed Botwoon routing so the editor uses its 14-part composite pose instead of the generic head-only OAM path. |
| 2026-10-04 | Resolved all zero-transfer visual species | Source-backed runtime ownership recovered Elevator, Ceres Steam, Zebetite, cutscene Baby Metroid, Mother Brain tubes, and the three non-owner corpse species. All eight now render 19 visible timed frames from exact lists/assets/palettes while their shared/global graphics remain read-only. Palette ownership is independent: five retain editable header palettes and three use read-only global rows. The complete ledger moves to 134 assembled / 14 tile-sheet-only / 14 composite / two nonvisual / zero failed, with 148 assembled previews. |
| 2026-10-04 | Proved Kraid's complete graphics recipe | The dedicated manifest pins tileset `$1A`'s complete 32 KiB no-CRE edit unit, three compressed BG2 maps and their active/unreferenced consumers, four stored 32×12 head maps and the interpreter's 32×11 DMA boundary, 21 custom frame occurrences, eight mouth hitboxes, 21 palette states, and eight headers sharing `$AB:CC00`. Production now composes all four 512×512 live BG2 views pixel-exactly and persists head edits through the normal tileset relocation path. Source entity names replace the former belly-spike/flying-claw guesses. |
| 2026-10-04 | Proved Phantoon's complete graphics recipe | The dedicated manifest pins all four enemy slots (body, eye, tentacles, mouth), 22 active BG2 tilemaps / 240 words, 22 extended spritemaps, 19 instruction lists / 27 frame occurrences, three hitbox sets, the raw `$AC:AA00` OBJ payload, tileset `$05` BG ownership, and all eight health palettes. Production now renders all 22 components, nine gaze compositions, and five bounded part animations / 13 full-body frame occurrences. It also corrected the editor from the unused `$A7:CA21` palette clone to active full-health `$A7:CC21`, replaces the stale flame-size species names, and keeps placement data read-only while pixel edits use `varGfx["5"]`. |
| 2026-10-04 | Proved Draygon's complete four-slot graphics recipe | The dedicated manifest pins body/eye/tail/arms (`$DE3F/$DE7F/$DEBF/$DEFF`), tileset `$1C` room BG ownership, the separate `$B0:C800` OBJ payload, 94 standard OAM maps / 632 entries, 103 extended maps, 48 BG2 maps / 980 words, 57 active lists, five source-declared unused lists, 47 hitboxes, six full palettes, and the eight-row health table. Production renders ten complete poses and all 39 frame-bearing lists / 250 full-composition frame occurrences with deterministic hashes. The editor now exposes guided composition, animation, palette, and source-owner views while leaving ambiguous flattened placement/pixel editing read-only. |
| 2026-10-04 | Proved Mother Brain's split phase-1/2 graphics recipe | The dedicated manifest pins head/body `$EC3F/$EC7F`, tileset `$0E`, four physical pixel owners, 26 head maps / 189 OAM entries, 16 active extended body maps / 145 children, six BG2 maps / 290 words, and 49 active body/head lists / 243 timed frames. Production assembles torso, limbs, five neck segments, and independent head; exposes four health and all ten correctly split rainbow main/back-leg palette stages; and hashes every curated composition and animation. Phase-1 room machinery and remaining HDMA/projectile effects stay explicitly outside the sprite canvas. |
| 2026-10-05 | Proved and consolidated Ridley's shared encounter renderer | Ceres `$E13F` and Norfair `$E17F` now share one top-level workspace while remaining distinct ROM headers. The manifest pins five contiguous base assets / `$2000` bytes, the `$0400`-byte low-page forward/explosion asset and `$E001` enemy-set placement, six ribs/claws DMA assets, 11 extended body maps / 41 children, 17 body-child maps / 164 entries, 12 wing maps / 56 entries, 19 tail maps, four palette stages, and eight encounter lists / 34 timed frames. Production hashes all complete compositions and 11 curated animations, including Ceres lunge, the correct forward turn, and baby-Metroid retrieval. |
| 2026-10-05 | Proved Crocomire's living, melting, and skeleton renderer | The dedicated manifest pins body/tongue `$DDBF/$DDFF`, tileset `$1B`, the editable `$2600`-byte living OBJ owner, two `$0C00` melting overlays, six `$0200` skeleton DMA chunks, 74 standard maps / 683 entries, 94 extended maps / 463 children, 11 BG2 maps / 606 words, six palette rows, and 36 active lists / 233 timed frames. Production renders 20 guided animations / 218 frames plus complete compositions and components with deterministic hashes while preserving the room-BG, body, melting, and skeleton ownership boundaries. The audit also replaced a stale mini-boss table that had mislabeled ordinary `$A2` species `$CEFF..D23F` as Spore Spawn, Botwoon, Crocomire, and Torizo. |
| 2026-10-05 | Proved Spore Spawn's cross-bank body and stalk renderer | The dedicated manifest pins body/stalk headers `$DF3F/$DF7F`, their shared `$AC:9C00` / `$0E00`-byte pixel owner, 22 standard maps / 365 entries, 12 active plus seven unused extended body maps, seven bank-`$8D` projectile maps, 27 sprite/room palette rows, and nine active lists / 41 timed frames. Production composes all four bank-`$86` stalk projectiles with the body using the exact `$A5:EC49` quarter/half/three-quarter interpolation, exposes six body animations plus spawner/spore sequences, and preserves one safe editable source. |
| 2026-10-05 | Proved Botwoon's position-history body renderer | The dedicated manifest pins header `$F293`, the shared `$B7:E300` / `$1800`-byte head/body/tail/spit owner, 30 active plus ten unused head maps, 46 active plus 52 unused projectile maps, 26 active plus five unused head lists, 18 active projectile lists, nine runtime tables/routines, and nine palette rows. Production assembles the head with twelve independently oriented body projectiles and one tail from the source's circular-history cadence, preserves the independent four-phase body loop through spits, exposes 17 guided animations / 87 frames, and corrects the former 16-pixel static preview spacing to the source-derived 12 pixels. |
| 2026-10-05 | Proved and consolidated the Bomb/Golden Torizo renderer | One Torizo workspace now represents the real `$EEFF/$EF7F` encounters while keeping `$EF3F/$EFBF` as their actual projectile drop records. The manifest pins four pixel owners / 12,288 bytes, 106 active full-body maps, 91 body-child maps, 70 active plus four unused bank-`$8D` projectile/effect maps, 112 active body lists / 564 timed frames, 50 bank-`$86` projectile instruction symbols, 16 runtime tile transfers, and 26 palette rows. Production preserves both physical OBJ pages, applies eye/damage/egg overlays per state, exposes all eight Golden health pairs, and hashes 206 body plus 30 projectile guided frames while allowing edits only to the shared `$AF:C200` base. |
| 2026-10-05 | Proved the normal Metroid's three-owner renderer | The dedicated manifest pins `$DD7F`, its shared `$AE:9000` / `$1000`-byte pixel owner, four enemy-inside maps, three shell maps, 24 electricity maps, two enemy lists, and four independent bank-`$B4` companion tracks / 124 frame occurrences / 270 ticks. Production synchronizes all three layers, preserves transparent draw intervals, and proves that the lists labelled unused objects `$33/$35` are runtime-active fallthrough continuations of live objects `$32/$34`; the `$35` continuation supplies the previously omitted third shell map. |
| 2026-10-05 | Added exact Puyo, Owtch, and Choot source routes | These three ordinary enemies were tile-sheet-only because init AI selects their lists through helpers/state logic outside the conservative scanner. The new manifest pins three owners / 2,560 bytes, 15 OAM maps / 32 entries, 15 lists / 28 source frames, three palettes, and ten compact actions / 33 guided frames. Production preserves Puyo's AI-stepped sleeping hop poses, Owtch's setup-list fallthrough, and Choot's separate idle/jump/fall states; strict component and animation hashes move the ledger to 137 assembled / 11 tile-sheet-only and 151 previewable species. |
| 2026-10-05 | Corrected false Puyo and added Sbug/roach directions | `$E1BF` was a stale name-table error: it is Ridley's internal explosion/forward-turn payload and is now consolidated under Ridley rather than shown as a second Puyo. Actual Puyo remains `$CFBF`. The `$D87F/$D8BF` headers are source Sbug/roach variants, not Reo; they now share all eight exact four-frame direction loops. The helper-routed manifest grows to five headers, four unique owners / 3,072 bytes, 39 OAM maps / 56 entries, 23 lists / 60 source frames, and 18 actions / 65 guided frames, moving the ledger to 139 assembled / nine tile-sheet-only and 153 previewable species. |
| 2026-10-05 | Consolidated and source-routed the Evir family | Evir `$E63F` and its internal projectile `$E67F` share one pixel/palette owner but retain distinct runtime headers. The standalone “Zero” mislabel was removed; Evir now exposes exact left/right body and arms loops plus the normal projectile pose under one editor entry. All 24 maps / 161 entries and seven lists / 49 source frame occurrences are pinned, growing E-11 to seven headers, 23 actions / 112 guided frames and E-08 to 141 assembled / seven tile-sheet-only / 155 previewable species. |
| 2026-10-05 | Closed the all-species tile-only gap | Magdollite gained 11 source actions across its three slots, Beetom gained eight directional/action routes, and both Sidehopper corpse headers gained four shared behavior routes over their exact two-owner runtime OBJ layout. Kzan bottom and the Tourian soul header are now proved non-drawing helpers, while Ridley's explosion payload is assigned to the consolidated Ridley renderer. E-11 reaches 11 headers / 46 actions / 196 guided frames; E-08 reaches 145 assembled / zero tile-sheet-only / 15 composite / four nonvisual / zero failed, with 159 previewable species. This also corrected stale stats IDs for green Kihunter, Beetom, and Magdollite. |
| 2026-10-05 | Consolidated and source-routed Kihunter | Green/red/gold Kihunter now expose 13 exact body/wing actions per color from 41 maps / 300 OAM entries and 13 lists / 59 source frame occurrences. The three `$0200` wing-transfer headers remain runtime companions but no longer appear as disconnected standalone characters; wing actions synchronize and composite against the matching body in runtime OAM order. Large ordinary tile sheets no longer trigger the bank-wide boss-pose scanner, removing bogus Body Pose panels for Kihunter and the Sidehopper corpse. E-11 reaches 14 routed headers / 59 actions / 255 guided frames. |

## Deliberately deferred

- Replacing SMEDIT's ROM-patch architecture with assembly builds.
- Writing assembly source directly from the normal ROM editor.
- Treating the disassembly's labels as stable project-file identifiers.
- Claiming arbitrary hacks are supported because vanilla data passes.
- Bulk-fixing every diagnostic test before the first reusable oracle tools exist.

The intended loop is: pick one row, gather exact source evidence, add a failing parity
test, correct the implementation, verify export/runtime behavior, update permanent
docs, and check the row. This keeps the gold mine useful without turning it into one
giant rewrite.
