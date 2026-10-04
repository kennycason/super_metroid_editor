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
`parityBuildReference`, `paritySymbols`, `parityAssets`, and `parityLz5Oracle` tasks
remain available for focused investigation.

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

`parityReport` is the normal strict entry point after setup. It performs the complete
foundation and LZ5 chain and writes ignored `parity-report.json` and
`parity-report.md` beside the detailed catalogs. The report records exact commits and
hashes, pass/partial/mismatch/uncovered counts, warnings, command coverage, and JUnit
results. Any live mismatch fails the task after the underlying evidence has been
evaluated.

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
difference. The report currently covers the foundation and LZ5 compression;
subsystem coverage expands incrementally through the matrix in
[`docs/validation/README.md`](../docs/validation/README.md).
