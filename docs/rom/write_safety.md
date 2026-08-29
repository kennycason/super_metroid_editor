# ROM Write Safety

Last updated: 2026-08-28

SMEDIT exports through a shared transactional write plan. The planner exists to
prevent patches, generated code, room relocation, graphics, music, text, and
other editor features from silently overwriting each other or writing into an
unexpected base ROM.

## Safety Invariants

1. The input ROM is immutable. Export works on an isolated copy and writes an
   output file only after the full plan and post-export validation succeed;
   verification errors are export blockers rather than advisory log lines.
2. Every planned byte has an owner, label, canonical headerless PC offset, and
   write kind.
3. A second owner cannot write any claimed byte. Byte-identical sharing is also
   rejected unless the caller explicitly requests `ALLOW_IDENTICAL`. A stateful
   exporter may explicitly evolve bytes held by its own owner, but cannot use
   that permission against any other subsystem.
4. Fixed writes can carry `expectedBefore` bytes. A stale hook or operand fails
   before it is overwritten.
5. Curated IPS patches declare compatible input-ROM SHA-256 hashes. ROM builds
   reject a patch when the input does not match. Patch-only builds warn because
   no input ROM is available to verify.
6. Bounds errors are fatal. Writes are never clipped, partially applied, or
   silently skipped by the write-plan layer.
7. Allocations claim their complete range, including bytes whose payload value
   remains `$FF`. A later free-space user therefore cannot mistake an internal
   `$FF` byte for unowned space.
8. Copier-header handling is centralized. Reports and IPS offsets remain
   canonical headerless PC offsets.

The implementation lives in
`shared/src/commonMain/kotlin/com/supermetroid/editor/rom/RomWritePlan.kt`.
Desktop export and the headless/service build path both use it.

## Conflict Classes

### ROM byte conflicts

The plan rejects overlapping ranges before applying the later write and names
both owners, both labels, the exact PC offset, and both byte values. This covers:

- one IPS patch overwriting another IPS patch;
- a generated hook overwriting a fixed patch;
- relocated room data or graphics entering patch-owned ROM space;
- text, minimap, music, custom ASM, or graphics overwriting an earlier owner;
- aliases that point at the same shared data but contain different results.

Some vanilla tileset IDs intentionally share one palette. Those writes use the
explicit byte-identical policy: identical content can coalesce, but differing
content fails.

### Base-ROM conflicts

An overlap-free patch can still be wrong if its fixed addresses were authored
for a different ROM revision or for already-modified code. SMEDIT uses two
checks:

- `compatibleRomHashes` validates the whole input ROM for curated patches;
- `expectedBefore` validates exact bytes at sensitive fixed writes and hooks.

The built-in one-line hex edits have exact vanilla preconditions. Generated
Room Names and the combined per-frame hook verify the original hook bytes and
verify that allocated payload space is free.

### Runtime resource conflicts

Two patches can occupy different ROM addresses and still conflict at runtime.
Patch metadata can claim named ranges in independent namespaces such as:

- `wram`
- `vram`
- `rom_hook`
- `samus_item_bit`
- `message_id`
- `plm_id`
- `pause_bg_tile`

Claims are exclusive by default. A shared claim is accepted only when both
features opt into the same non-empty sharing group. Spider Ball declares its
known WRAM, item-bit, message, PLM, and pause-tile usage. Room Names declares
its pause-map hooks and VRAM row. Combined per-frame features share one managed
hook group.

Runtime claims catch known semantic ownership collisions, but they cannot infer
every interaction in arbitrary 65816 code. Emulator-backed combination tests
remain necessary.

## Legacy Writers

Some mature exporters still mutate a `ByteArray` directly. The planner wraps
them transactionally:

1. snapshot the current staged ROM;
2. run the legacy writer;
3. capture its exact diff;
4. restore the snapshot;
5. replay the diff through normal ownership, bounds, and precondition checks.

Writers that allocate data also report complete reservations so unchanged
`$FF` bytes are owned. Room Names declares its complete generated writes rather
than relying on a diff.

This adapter makes current export safe while patch/config, graphics, music,
text, minimap, and custom ASM code are incrementally moved to direct planned
writes.

## IPS Input

External IPS parsing is fail-closed. It requires a valid header, complete record
headers and payloads, non-zero RLE lengths, 24-bit range validity, a valid EOF,
and only the standard optional three-byte post-EOF size.

The bundled `skip_intro_ceres.ips` is a known legacy asset with complete records
but no EOF marker. Only trusted bundled-resource loading accepts this exact
legacy shape at a complete record boundary and logs a warning; normal parsing
remains strict.

## Reports

Every successful plan reports:

- total writes and logical claimed bytes;
- each owner and its min/max PC range;
- every non-ROM resource claim;
- fixed writes that lack both exact preconditions and stronger compatibility
  coverage.

The headless/service result exposes this as structured `writePlan` data. Desktop
export emits the same ownership summary to the export log.

## Spike Olympics Result

The real `Super Metroid Spike Olympics I` project was exported through both
paths with Spider Ball and Room Names enabled. The plan found no direct ROM-byte
or declared-resource overlap between those two features. It did find an
unrelated real alias: multiple project tileset IDs write the same palette ROM
address. Their payloads are identical, so the export now permits that sharing
explicitly and would reject it if the palettes diverged.

A stricter per-room pass also exposed an intentional aggregate update: the
`A6E2` and `B167` cross-area door edits successively rebuild and repoint Landing
Site's shared door-dependent BG transfer table. Room edits now carry individual
labels under one controlled `room-graph` owner, allowing that stateful rebuild
while continuing to reject writes from patches, graphics, text, ASM, or any
other subsystem.

The Varia-only black bar therefore is not explained by one of the two named
patches literally overwriting the other's ROM bytes. It is likely a runtime
state, VRAM/tile, palette, DMA, or Samus draw interaction not yet represented by
the current declarations. It should be diagnosed separately with emulator
breakpoints and state-combination regression tests; this safety work does not
silently claim that symptom is fixed.

## Required Metadata For New Patches

Before a new fixed patch is treated as production-safe:

- declare supported headerless ROM SHA-256 hashes;
- provide expected-before bytes for hooks and small fixed edits;
- declare WRAM, SRAM, VRAM, hardware slot, item-bit, message-ID, PLM-ID, and
  shared-hook ownership where relevant;
- route all writes and full allocation reservations through `RomWritePlan`;
- add a collision test and a stale-base/precondition test;
- add emulator tests for meaningful equipment, room, pause-screen, and state
  combinations.

## Remaining Hardening

The planner prevents known and declared stomps; it is not a complete linker or
an emulator proof. The next safety work is:

1. move every legacy captured writer to direct planned write intents;
2. replace independent bank scanners with one layout-aware allocation registry;
3. expand runtime resource declarations for complex bundled ASM patches;
4. add an automated emulator matrix for patch combinations and equipment state;
5. expose structured desktop blockers and a preflight ownership map before the
   user chooses Export;
6. add managed ROM expansion only after allocation, pointer, checksum/header,
   and mapper rules are centralized.
