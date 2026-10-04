# SMEDIT Parity Harness

This directory contains developer-only tooling for proving that SMEDIT agrees with
the exact Super Metroid ROM and disassembly. It is not part of the editor's runtime,
packaged application, project format, or ROM exporter.

## What is committed

- `reference.properties` pins the upstream disassembly commit and the clean ROM's
  expected size and SHA-256.
- `bootstrap.py` provisions that exact disassembly revision.
- `check_fixtures.py` validates fixture identity before strict parity work.
- `build_reference.py` extracts private assets, builds pinned Asar when needed,
  rebuilds the disassembly, and generates `symbols.sym`.
- `symbol_catalog.py` strictly parses, searches, and exports Asar's WLA labels.
- `asset_manifest.py` maps every active extracted `incbin` to its named symbol,
  exact ROM range, size, aliases, and content hash.
- `lz5_oracle.py` independently models `$80:B119`, classifies exact compressed
  assets, and records decoded sizes, hashes, and command coverage.
- `tileset_manifest.py` resolves all 29 source pointer triples to named assets,
  records intentional aliases, and proves CRE ROM/RAM/VRAM ownership plus every
  direct engine consumer.
- `tile_format_oracle.py` independently decodes source-owned 2bpp/4bpp pixels and
  metatile semantics, including Ceres's global split-plane layout.
- `animated_tiles_manifest.py` traces bank-$87 animation lists to exact raw payloads,
  durations, object DMA sizes/destinations, FX activation bits, and engine consumers.
- `item_plm_graphics_manifest.py` proves bank-$89 upgrade-item pixels, all 51 PLM
  variants, palette arguments, four runtime slots, and eight draw pointers.
- `enemy_header_manifest.py` evaluates every bank-$A0 `EnemyHeader` macro and maps
  each raw `GRAPHADR` transfer through named assets, aliases, and overlaps.
- `enemy_oam_manifest.py` inventories every named standard OAM, extended/multibox,
  and extended-tilemap structure in the enemy AI banks and independently decodes
  their fields and links.
- `enemy_instruction_manifest.py` parses every named enemy instruction-list source
  block and measures the current preview scanner against exact records and boundaries.
- `enemy_vertical_slice_manifest.py` joins exact headers, graphics/palettes,
  instruction paths, and OAM geometry for Zoomer, Sidehopper, and a walking Space Pirate.
- `kraid_manifest.py` proves Kraid's complete tileset/BG2/head-interpreter/palette/linked-OAM
  recipe, all 12 active linked-OAM lists, Mini Kraid's six bounded action lists, and the
  boundary between editable pixels and read-only placement data.
- `EnemySpeciesStatusSourceParityTest` probes every source header through production
  render paths and emits the complete assembled/tile-sheet/composite/nonvisual/failed ledger.
- `report.py` aggregates live fixture, build, symbol, asset, and tagged-test evidence.
- `test-support/` provides one fixture contract to JVM tests in all modules.

The cloned checkout lives in ignored `parity/work/`. Generated reports will live in
ignored `parity/reports/`. No ROM, rebuilt ROM, extracted binary asset, or cloned
upstream repository is committed here.

## First-time setup

Requirements are Git, Python 3, and the normal SMEDIT/JDK toolchain.

```bash
export SMEDIT_TEST_ROM='/absolute/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew parityReport
```

`parityReport` depends on the bootstrap, build, fixture check, catalogs, and strict
tagged tests. The individual `parityBootstrap`, `parityCheck`,
`parityBuildReference`, `paritySymbols`, `parityAssets`, `parityLz5Oracle`, and
`parityTilesets`, `parityTileFormats`, `parityAnimatedTiles`,
`parityItemPlmGraphics`, `parityEnemyHeaders`, `parityEnemyOam`,
`parityEnemyInstructions`, `parityEnemyVerticalSlices`, and `parityKraid` tasks remain
available for focused investigation.

`parityBootstrap` clones/fetches
`https://github.com/InsaneFirebat/sm_disassembly.git` and checks out the commit in
`reference.properties` in detached-HEAD mode. It deliberately does not follow the
latest upstream branch: parity results must be reproducible.

`parityCheck` is a strict fixture-identity check. It fails when:

- `SMEDIT_TEST_ROM` is absent, missing, headered, modified, or the wrong revision;
- the disassembly checkout is absent, on the wrong commit, or has tracked changes;
- a rebuilt `SM.sfc`, when present, is not byte-identical to the clean ROM.

The clean ROM remains user-supplied and is never downloaded. The pinned source clone
contains upstream disassembly material under its own license and remains ignored.

`parityBuildReference` additionally clones the pinned Asar 1.81 source into
`parity/work/asar`, builds it locally with CMake, extracts all disassembly assets from
the configured private ROM, and assembles `SM.sfc` plus `symbols.sym`. It fails unless
the result is byte-identical to the configured ROM. Generated assets, the rebuilt ROM,
symbols, Asar source, and compiler output all remain inside ignored `parity/work/`.
This task requires CMake and a C++ compiler; use `SMEDIT_ASAR=/path/to/asar` to supply
an existing Asar 1.81 executable instead.

`paritySymbols` writes a deterministic, searchable catalog to ignored
`parity/reports/symbols.json`. Direct name and address queries are also available:

```bash
python3 parity/symbol_catalog.py Tiles_Phantoon
python3 parity/symbol_catalog.py --address AC:AA00
```

Parity tests read the same WLA label section and compare named source symbols with
SMEDIT constants, turning address drift into a test failure.

`parityAssets` writes ignored `parity/reports/assets.json`. It requires all 1,130
active NTSC assets to have one source declaration and named address, verifies their
bytes against the exact rebuilt ROM range, rejects overlaps, and separately records
the 17 PAL-only declarations. Assembly comment sizes are advisory: disagreements are
reported explicitly, while extracted bytes plus the byte-identical ROM remain the
authority.

`parityLz5Oracle` reads the exact extracted ranges, accepts only streams whose `$FF`
terminator consumes the complete asset, and writes ignored `parity/reports/lz5.json`.
The independent Python decoder follows `Decompression_VariableDestination` at
`$80:B119`; it does not call SMEDIT's Kotlin decoder. Tagged JVM tests then require
all 421 classified streams to match those decoded sizes and SHA-256 hashes and to
survive SMEDIT decode/re-encode/decode byte-exactly. The pinned vanilla corpus uses
commands 0–6; command 7 is covered separately by a synthetic engine-format test.

`parityTilesets` writes ignored `parity/reports/tilesets.json`. It parses the source
definitions rather than maintaining a second pointer list: all 29 table entries and
the secondary `Tileset_Pointers` table must be contiguous and ordered, and each of
the 87 fields must resolve through `symbols.sym`, the extracted asset manifest, and
the independent LZ5 oracle. The report records 55 unique payloads and every
intentional alias group. It also proves the two CRE streams' exact boundary,
decoded ownership, semantic tile range, and all direct assembly references. Tagged
JVM tests then compare that source manifest with the clean ROM, SMEDIT's detected
graphics catalog, production constants, and decoded hashes. A stale pointer,
renamed/unmapped asset, changed alias, or added/removed CRE consumer fails parity.

`parityTileFormats` writes ignored `parity/reports/tile-formats.json`. Its Python
decoder is independent of SMEDIT and proves all 10,944 source-owned 4bpp tiles,
the 256 standard Layer-3 2bpp tiles, and 45,056 metatile words. It distinguishes
standard interleaved 4bpp from the global split-plane Ceres elevator/Ridley
payloads. It also records that thirteen normal graphics payloads define 576 tiles
inside a 640-tile runtime region, leaving indices 576–639 blank before CRE begins
at 640; reserved capacity is not attributed to a source asset. Tagged JVM tests
compare every decoded pixel and source-table placement through SMEDIT's production
paths, exercise H/V/HV rendering, and exhaustively decode and re-encode all 65,536
possible metatile words.

`parityAnimatedTiles` writes ignored `parity/reports/animated-tiles.json`. It walks
all reachable bank-$87 instruction-list branches and proves 94 unique timed frame
instructions across 20 object definitions. Every frame source matches one of 68
exact extracted payloads and its object's DMA size; 65 payloads are referenced and
the three source-declared unused `X` ranges remain explicit orphans. The manifest
also verifies all 64 area/FX-bit mappings, eleven spawn sites, three handler calls,
and the NMI DMA consumer that transfers from bank `$87` to each object's VRAM word
destination. This corrected the old 64-payload inventory, which had omitted four
files whose names begin `UNUSED_AnimatedTiles_`.

`parityItemPlmGraphics` writes ignored
`parity/reports/item-plm-graphics.json`. It proves the 17 contiguous `$100`-byte
bank-$89 upgrade-item payloads and independently decodes their 136 standard-4bpp
tiles. The source graph must contain exactly one visible, one Chozo-orb, and one
shot-block load for every item (51 PLM IDs total), with matching source pointers and
eight palette bytes. Tagged JVM tests compare those IDs with `RomParser.ITEM_DEFS`,
then verify the four wrapping VRAM/TileTable/starting-tile slots, eight frame draw
pointers, generated metatile semantics, and the interpreter dispatch call.

`parityEnemyHeaders` writes ignored `parity/reports/enemy-headers.json`. It parses
all 164 exact bank-`$A0` `EnemyHeader` macro calls and evaluates their 4,756 fields,
including source labels, decimal/hex expressions, bank extraction, and NTSC regional
values. Each assembled 64-byte record and its two zero-padding regions must match the
rebuilt ROM and SMEDIT's complete production parser. The 155 nonempty raw-graphics
associations reduce to 100 unique address/size ranges and resolve contiguously through
168 segments to 99 named extracted assets. The report pins all 25 shared-start groups,
24 exact-range alias groups, nine overlap pairs, and six multi-asset species ranges;
tagged JVM tests compare every raw byte and independently decoded pixel hash through
SMEDIT's production paths. This proves raw ownership, not OAM/composite assembly or
conflict-free simultaneous editing of overlapping ranges.

`parityEnemyOam` writes ignored `parity/reports/enemy-oam.json`. It derives the
corpus from named source labels in banks `$A0`, `$A2..AA`, and `$B2..B3`, while
recognizing the disassembly's singular/plural and bank-$B2 `Spitemaps` spellings.
The manifest covers 2,312 standard structures / 14,400 five-byte OAM entries,
811 extended structures / 1,984 child associations, and 99 extended tilemaps /
2,763 words. Tagged JVM tests compare every signed offset, tile/name-table bit,
palette, priority, flip, size, child/hitbox pointer, and tilemap run with SMEDIT's
production parser. This proof corrected two real parser gaps: the shared zero-entry
“nothing” structures are valid, and the engine ignores an extended spritemap count
word's high byte (Ceres steam deliberately stores `$1001`). The one excluded Torizo
label is source-declared as an orphaned OAM entry with a missing count, not a
spritemap structure.

`parityEnemyInstructions` writes ignored
`parity/reports/enemy-instructions.json`. It parses all 1,139 named enemy-list source
blocks into 7,820 variable-width records (4,573 frames and 3,247 handler occurrences),
checks exact rebuilt-ROM ranges, inventories 502 handler addresses and their observed
operand widths/control-flow shapes, and lists every preview miss. The tagged JVM test
then runs SMEDIT's actual generic fallback scanner from every named list address. It proves
the fallback recovers 2,543 of 4,395 renderable source frames, misses 1,852
across 465 lists, skips all handler semantics, and can detect 4,718 candidates after
crossing named block boundaries. This task measures the gap; it does not claim E-06
is complete.

`parityEnemyVerticalSlices` writes ignored
`parity/reports/enemy-vertical-slices.json`. It proves three complete ordinary-enemy
visual paths: Zoomer `$DCFF`, Sidehopper `$D93F`, and grey walking Space Pirate
`$F653`. The fixtures join exact species headers, GRAPHADR and palette bytes, source
entry lists, handler order/widths, 17 timed frame occurrences, 15 unique standard or
extended spritemaps, flattened OAM semantics, geometry, and rendered production
animations. The bounded interpreter follows exact fallthrough and `GotoY` loops,
stops at sleep, preserves repeated frames, and reports unknown handlers instead of
guessing their width. Nonvisual handler side effects remain outside this visual slice.

The tagged all-species status test writes ignored
`parity/reports/enemy-species-status.json` and `.md`. Unlike the legacy diagnostic,
it begins with all 164 source headers, never treats a raw tile sheet as assembled,
and runs the production ordinary OAM, special OAM, known boss, Kraid BG2, and Phantoon
BG2 paths. The pinned result is 134 assembled, 14 tile-sheet-only, 14 composite, two
nonvisual, and zero failed, with real assembled previews for 148 species. Every row
includes catalog membership and a reason. A dedicated source test also proves all
eight zero-transfer visual species against their exact shared/global graphics,
runtime palette, source entry list, and visible rendered frames; these providers are
read-only and never become writable `GRAPHADR` ranges. The same proof keeps graphics
and palette ownership independent: five zero-transfer headers still own their palette
row, while Elevator, Steam, and Zebetite use read-only global rows. This audit also found and fixed
Botwoon's editor routing: its existing 14-part composite renderer is now used.

`parityReport` is the normal strict entry point after setup. It performs the complete
foundation, LZ5, tileset, CRE, tile-format, animated-tile, item-PLM, enemy-header,
enemy-OAM, enemy-instruction, enemy-slice, and enemy-species-status chain and writes ignored
`parity-report.json` and `parity-report.md` beside the detailed catalogs. The report
records exact commits and hashes, pass/partial/mismatch/uncovered counts, warnings,
command coverage, pointer and alias counts, CRE consumers, decoded tile/metatile
totals, animated-tile/item-PLM/enemy-graphics ownership, and JUnit results. Any live
mismatch fails the task after the underlying evidence has been evaluated.

## Overrides and normal tests

To use an existing checkout instead of `parity/work/sm_disassembly`:

```bash
export SMEDIT_DISASSEMBLY_DIR='/absolute/path/to/sm_disassembly'
```

JVM tests accept the same environment variables. They also accept
`-Dsmedit.testRom=...` and `-Dsmedit.disassemblyDir=...`. Without private fixtures,
ROM/source-backed tests report **skipped** rather than silently passing. To make
missing fixtures fail during a selected Gradle test run, add:

```bash
-Dsmedit.requireParityFixtures=true
```

The `parityCheck` Gradle task also accepts `-Dsmedit.testRom=...` and
`-Dsmedit.disassemblyDir=...` when shell environment variables are inconvenient.
`parityBuildReference` accepts those properties plus `-Dsmedit.asar=...`.

Pure unit tests remain fixture-independent. Diagnostic images are written under a
module's `build/test-output/`, never into `test-resources/`.

## Updating the oracle

Do not run `git pull` inside the managed checkout and call the result equivalent.
Updating the oracle means deliberately changing `disassembly.commit`, regenerating
the manifests, running the strict parity suite, and reviewing every resulting
difference. The report currently covers the foundation, LZ5 compression, tileset
pointers, CRE ownership, tile pixel layouts, metatile semantics, animated-tile DMA
ownership, item-PLM graphics/slot ownership, enemy-header/`GRAPHADR` ownership, and
named standard/extended enemy OAM ownership and decoding, plus enemy instruction-list
structure, preview misses, three end-to-end ordinary-enemy slices, and the complete
164-species production render-status ledger. Subsystem
coverage expands through the matrix in
[`docs/validation/README.md`](../docs/validation/README.md).
