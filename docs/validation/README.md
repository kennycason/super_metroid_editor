# Disassembly Parity Program

Last updated: 2026-10-02

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
- Rebuilt ROM: `~/code/sm/sm_disassembly/SM.sfc`
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
- Kraid and Phantoon have dedicated BG2/extended-tilemap renderers; Ridley has a
  dedicated pose wrapper; Samus has pose, DMA, tilemap, palette, and animation code.
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

#### 2. Legacy Kraid tile-sheet export points at a BG2 tilemap

Current `EnemySpriteGraphics.KRAID_BLOCKS` identifies `$B9:FA38` as compressed Kraid
sprite graphics. The exact assembly labels it
`Background_Brinstar_1A_Kraid_Upper`, a compressed BG2 tilemap. It is not 4bpp tile
graphics.

The exact source distinguishes three resources:

- `$AB:CC00` = `Tiles_Kraid`, `$1E00` raw enemy/OAM tile bytes
- `Tiles_1A_Kraid.bin` = compressed room tileset graphics
- `$B9:FA38` = compressed upper Kraid BG2 tilemap

`KraidSpritemap`'s assembled path correctly gets Kraid's BG body tiles from tileset
`$1A`. Before P0.1, both assembled-component and legacy tile-sheet edits were persisted
under `kraid:0`, and the exporter wrote either form through the false `$B9:FA38`
mapping. P0.1 removed the legacy loader/writer, paused Kraid pixel editing, and made any
old `kraid:*` data a resettable export blocker.

Status: **Mismatch — P0 safety item**

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
| F-05 | Address drift test | **Partial** | Twelve tileset, boss, and Samus constants are source-linked; inventory and map the remainder. |
| F-06 | One parity report command | **Verified** | `parityReport` runs the strict foundation/LZ5 chain and writes ignored JSON/Markdown evidence with status counts. |
| F-07 | Golden-image policy | **Queued** | Separate human-approved/emulator goldens from diagnostic output. |

### Shared graphics and compression

| ID | Unit | Current status | Source-backed target |
|---|---|---|---|
| G-01 | LoROM/headered address conversion | **Covered** | Exhaustively compare symbol addresses and both ROM layouts. |
| G-02 | LZ5 decompression | **Verified** | Independent `$80:B119` oracle plus Kotlin checks match decoded size/hash for all 421 exact streams; malformed input fails closed. |
| G-03 | LZ5 recompression | **Verified** | All 421 payloads survive SMEDIT encode/decode byte-exactly; destination capacity and the 64 KiB engine maximum are enforced. |
| G-04 | 2bpp/4bpp tile decoding | **Covered** | Fixed extracted tiles plus approved pixel hashes and flip cases. |
| G-05 | CRE graphics/tile table | **Partial** | Verify both compressed resources, split boundary, and all pointer users. |
| G-06 | 29 tileset pointer triples | **Partial** | Compare every table entry with named `Tiles`, `TileTables`, and `Palettes` assets. |
| G-07 | Metatile words | **Covered** | Exhaustive tile/palette/priority/H/V field parity and round trip. |
| G-08 | Animated tiles | **Queued** | Verify all 64 extracted sequences and runtime destinations. |
| G-09 | Item/PLM graphics | **Partial** | Verify all 17 extracted assets, tables, and VRAM placement. |

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
| E-01 | All bank `$A0` 64-byte species headers | **Partial** | Generate the species inventory and compare every macro field. |
| E-02 | `GRAPHADR` raw tile ranges | **Partial** | Compare source label, exact size, aliases, and ROM bytes for every species. |
| E-03 | Palette pointer/bank behavior | **Partial** | Compare every species plus runtime palette overrides and multi-row cases. |
| E-04 | Standard 5-byte OAM entries | **Covered** | Assert signed X/Y, size, tile, name table, priority, palette, and flips. |
| E-05 | Extended/multibox spritemaps | **Covered** | Match named structures and child/hitbox pointers, not bank scans. |
| E-06 | Instruction-list interpreter | **Partial** | Replace pattern-search coverage claims with opcode/control-flow coverage metrics. |
| E-07 | VRAM destination/name-table mapping | **Partial** | Recreate `ProcessEnemyTilesets` and runtime DMA destinations exactly. |
| E-08 | All-species render inventory | **Queued** | Classify each species as verified OAM, composite, nonvisual, projectile, or unsupported. |
| E-09 | Sprite tile edit/export | **Partial** | Parse/edit/export/reparse, assert exact owned ranges and aliases. |
| E-10 | Pre-rendered PNG fallbacks | **Partial** | Make fallback visible; never count it as a successful ROM-derived render. |

### Composite enemies and bosses

These are case studies, because a single generic “enemy sprite” model cannot describe
BG layers, per-frame DMA, linked enemy slots, palette FX, and room-owned graphics.

| Order | Case | Relevant exact sources | Current status |
|---|---|---|---|
| B-01 | Kraid | AI `$A7`; OAM tiles `$AB:CC00`; BG2 tilemaps `$A7:97C8...` and `$B9:FA38...`; tileset `$1A` | **Mismatch/Partial** |
| B-02 | Phantoon | AI/extended tilemaps `$A7`; raw tiles `$AC:AA00`; Wrecked Ship room tileset/palette | **Mismatch/Partial** |
| B-03 | Draygon | AI `$A5`; raw tiles `$B0:C800`; tileset `$1C`; four linked species | **Partial** |
| B-04 | Ridley | AI `$A6`; staged tile chunks `$B0:9400...`; ribs/claws `$B0:B800...`; custom OAM add routine | **Partial** |
| B-05 | Mother Brain | AI `$A9`; body `$B0:E800`; head/legs `$B7:8000/$B7:9000`; tileset `$0E`; HDMA/palette phases | **Partial** |
| B-06 | Crocomire | AI `$A4`; body/skeleton/melting transfers; tileset `$1B` | **Partial** |
| B-07 | Spore Spawn, Botwoon, Torizos | AI `$A5/$B3/$AA`; dynamic and multi-part special cases | **Queued** |

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

- [x] **P0.1** Quarantine the Phantoon legacy tile-sheet export and all current
  Kraid sprite pixel exports with a clear validation blocker.
- [x] **P0.2** Add an explicit parity-fixture contract for the vanilla ROM and
  disassembly checkout; remove personal absolute test paths and silent success.
- [x] **P0.3** Parse `symbols.sym` and assert a small seed set of named addresses.
- [x] **P0.4** Parse assembly `incbin`s / `rip_assets.py` into an asset-range manifest.
- [x] **P0.5** Add a single parity-report Gradle task with no source-tree output.
- [ ] **P0.6** Correct the Phantoon and Kraid load/edit/export ownership using named
  raw graphics and tileset resources; add expected-before checks for every target.
- [ ] **P0.7** Regenerate/correct the boss IDs and bank ownership in permanent docs.

### P1 — Prove the ordinary enemy pipeline end to end

- [ ] **P1.1** Generate all species headers from `EnemyHeader` macros and compare all fields.
- [ ] **P1.2** Compare every `GRAPHADR` range and alias with named enemy tile assets.
- [ ] **P1.3** Turn the OAM parser tests into named-source format fixtures.
- [ ] **P1.4** Measure instruction-list opcode/control-flow coverage; list every miss.
- [ ] **P1.5** Validate three vertical slices: Zoomer, Sidehopper, and one Space Pirate.
- [ ] **P1.6** Produce an all-species status report that distinguishes assembled,
  tile-sheet-only, composite, nonvisual, and failed.

### P2 — Prove special composition

- [ ] **P2.1** Kraid complete recipe and safe edit ownership.
- [ ] **P2.2** Phantoon complete recipe and safe edit ownership.
- [ ] **P2.3** Draygon body/eye/tail/arms composition and room dependencies.
- [ ] **P2.4** Ridley staged DMA, body/wings/tail, Ceres variant, and palettes.
- [ ] **P2.5** Mother Brain phase 1/2 body/head/legs, palettes, and HDMA states.
- [ ] **P2.6** Crocomire and remaining mini-bosses.

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
- [ ] Make the reference manifest usable without hardcoded ROM addresses in UI code.
- [ ] Add read-only browsing from source symbol to SMEDIT object and rendered asset.
- [ ] Prototype source-owned asset edits in a disposable checkout.
- [ ] Build through an adapter and compare the result with the expected ROM diff.
- [ ] Only then design “New project from assembly” UX and project persistence.

## Progress log

| Date | Change | Evidence |
|---|---|---|
| 2026-10-02 | Established exact source oracle | Asar build produced a byte-identical ROM with the hashes above. |
| 2026-10-02 | Inventoried extracted assets | 1,130 `.bin` files; major groups include 418 Samus tiles, 246 level-data streams, 197 tile payloads, 70 backgrounds, and 64 animated-tile payloads. |
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
