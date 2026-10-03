# SMEDIT Parity Harness

This directory contains developer-only tooling for proving that SMEDIT agrees with
the exact Super Metroid ROM and disassembly. It is not part of the editor's runtime,
packaged application, project format, or ROM exporter.

## What is committed

- `reference.properties` pins the upstream disassembly commit and the clean ROM's
  expected size and SHA-256.
- `bootstrap.py` provisions that exact disassembly revision.
- `check_fixtures.py` validates fixture identity before strict parity work.
- `test-support/` provides one fixture contract to JVM tests in all modules.

The cloned checkout lives in ignored `parity/work/`. Generated reports will live in
ignored `parity/reports/`. No ROM, rebuilt ROM, extracted binary asset, or cloned
upstream repository is committed here.

## First-time setup

Requirements are Git, Python 3, and the normal SMEDIT/JDK toolchain.

```bash
./gradlew parityBootstrap
export SMEDIT_TEST_ROM='/absolute/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew parityCheck
```

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

Pure unit tests remain fixture-independent. Diagnostic images are written under a
module's `build/test-output/`, never into `test-resources/`.

## Updating the oracle

Do not run `git pull` inside the managed checkout and call the result equivalent.
Updating the oracle means deliberately changing `disassembly.commit`, regenerating
future manifests, running the strict parity suite, and reviewing every resulting
difference. The future all-subsystem report is tracked separately as P0.5 in
[`docs/validation/README.md`](../docs/validation/README.md).
