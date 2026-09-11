# SMEDIT Parity And Hardening Backlog

Last updated: 2026-09-09

This file captures the current SMILE/local-reference audit so the next work can resume without redoing the full review.

## Current Priority Order

1. ROM write safety and patch-combination hardening.
   - Transactional byte ownership, expected-before checks, curated patch ROM hashes, complete allocation claims, strict IPS parsing, declared runtime resources, one shared allocation session, fail-closed requested edits, and pointer-alias copy-on-write are implemented.
   - Next: direct planned writes for every legacy exporter, broader resource metadata, structured desktop preflight blockers, and emulator-backed combination tests.
   - The 2026-09-09 spot review added decompressed engine-destination limits,
     configured-field validation, staged-ROM structural postflight, and clearer
     blocker status treatment. See `docs/project/rom_write_safety_test_notes.md`.
2. Tileset/metatile composer hardening.
   - SMILE parity requires editing 16x16 metatile definitions from four 8x8 tilemap words.
   - Each subtile needs tile index, palette row, priority, h-flip, and v-flip controls.
   - Variable graphics/metatile/palette payloads now relocate through their U24
     table fields and use copy-on-write for aliases. Safe relocation of fixed
     CRE engine pointers remains open and oversized CRE exports fail closed.
3. AreaSave/save-station hardening.
   - Auto-derived spawn overrides and manual X/Y/scroll editing now exist.
   - Existing empty runtime slots now support save-station editing and safe cross-area migration. The engine masks save PLM indices to 0-7; the editor now rejects a ninth station without partial state instead of treating elevator/debug entries as capacity.
   - Remaining work is duplicate-slot conflict UI, special start/elevator migration, emulator-validated resume paths, and—only if truly needed—a deliberate engine/SRAM patch to raise the eight-slot limit.
4. Enemy sprite correctness and export hardening.
   - Close the remaining assembled-sprite/spritemap mismatch risks documented in `docs/OPENAI_REVIEW.md`.
   - Prefer raw 4bpp tile-sheet import/export over legacy PNG sprite replacement for ROM-critical paths.
   - Add regression fixtures for sidehopper, pirates, bosses, palette variants, and DMA transfer edge cases.
5. Validation expansion.
   - Validator covers doors, duplicate item bits, enemy GFX slot pressure, room dimensions, PLM set terminators/bounds, AreaSave override conflicts, graphics/metatile/tileset-palette export fit, enemy tile edit exportability, and sprite palette payloads.
   - Next checks should include scroll pointer bounds, room-state pointer consistency, room graph consistency, JSON import conflicts, and richer export blocker UX.
6. Spider Ball behavior hardening.
   - Keep spider code out of ground/slope ownership.
   - Preserve jump, wall-jump, morph tunnel, slope, and moving-platform behavior from the documented acceptance list.
   - Directional and hold-Aim-Down activation variants now share one generator,
     item/assets, ownership metadata, and fail-closed exclusivity. Emulator
     tuning of the hold behavior remains in the acceptance matrix.
7. Sound export hardening.
   - Verify relocated transfer chains, SPC RAM budget, sample directory consistency, and no-overlap rules.

## SMILE-Parity Gaps Still Open

- Layer 2 / BG hardening: embedded L2 editing exists; richer BG data pointer workflows, scrolling/link behavior, and door-dependent background transfers still need a fuller authoring surface.
- AreaSave conflict UI: save station spawn editing exists for the engine's eight runtime slots. More capacity is an engine-patch project, not a table-only relocation task.
- Room JSON import: export exists, import still needs conflict handling and validation.
- New room creation: requires room header/state/door/minimap allocation and route validation.
- SMART XML import: useful for interoperability and migration from older tools.
- Managed layout/ROM expansion: variable graphics, music, and level data can
  already relocate when free space exists. Expansion is still needed for large
  projects, but must extend the shared allocator and mapper/header/checksum
  rules rather than bypassing them.
- Auto item/door ID assignment: reduce hand-maintained ID collisions.
- Room graph discovery: should feed validation, minimap, and randomizer-style workflows.

## Notes From Local References

- `smile` remains the reference for graphics/metatile editing expectations, old UI workflows, and editor affordances.
- `smart` and `MapRandomizer` are useful for room graph, topology, and randomizer-facing data modeling.
- `SM-SPC` and current sound docs are the reference path for N-SPC/SPC transfer correctness.
- `sm_disassembly` and `sm` remain the canonical source for engine behavior and data layout.

## Current Work Item

Next quality-first slices:

1. Run an emulator-backed Room Names + Spider Ball equipment-state matrix,
   especially Varia acquisition/equipped transitions, to visually confirm the
   fixed label-tile allocation and preserve it as a permanent smoke test.
2. Finish fixed CRE relocation by identifying and patching every hardcoded
   engine reference, or retain the current clear blocker if proof is incomplete.
3. Continue enemy sprite correctness for special compressed/DMA/boss cases;
   Phantoon and Kraid must not use the generic raw-tile assumptions.
4. Build new-room creation on the shared allocator plus room/state/door/minimap
   reference validation, then add managed ROM growth when real projects exhaust
   verified free space.

## Recent Progress

- Minimap editing now has a room/sprite-style top toolbar, consistent shortcuts,
  flip and exact-representable rotation, map-station reveal editing, full
  minimap history, and safe room-area reassignment from Room Info or a minimap
  right-click. See `docs/project/minimap_editor_hardening.md`.
- Shared transactional ROM write planning now rejects byte overlaps, stale fixed-write preconditions, out-of-bounds writes, allocation reuse, incompatible ROM hashes, and declared runtime-resource conflicts before output is emitted. See `docs/rom/write_safety.md`.
- All allocating desktop export paths now share one session. Music and custom
  ASM report their complete allocations; lossy music trimming/fallback is
  blocked; malformed/unlinked/out-of-bounds project edits fail instead of being
  logged and omitted.
- Variable tileset graphics, metatile tables, and palettes relocate safely and
  use copy-on-write when table pointers are shared. Room level/PLM/scroll/FX
  pointer aliases also preserve the other room through copy-on-write.
- Editing a room that uses the engine's `$0000`/`$0001` uniform-scroll sentinel
  now materializes a private scroll table and repoints every room state. Legacy
  PNG enemy replacements are blocked instead of being silently omitted; the
  raw sprite-tile workflow remains the exportable path.
- Save-station assignment and area migration enforce the engine's real 0-7
  index range. Index 8+ load-station entries are no longer presented as save
  capacity.
- Room, minimap, text, graphics, and custom-ASM adapters use logical sub-owners. Stateful room-graph rebuilds have an explicit same-owner policy for shared door/BG aggregates; that policy cannot overwrite another subsystem.
- The real Spike Olympics project exports with Room Names and Spider Ball
  through both safety paths. The Varia-only rendering defect was traced to the
  Spider patch itself replacing live vanilla Varia wireframe character tiles,
  not to Room Names colliding with Spider Ball. The label now uses two audited
  holes in the pause sprite sheet; static regression coverage is complete and
  the emulator equipment-state matrix remains for visual confirmation.
- A hold-Aim-Down Spider Ball sibling now respects the configurable in-game Aim
  Down binding, detaches on release, and gates the same directional attachment,
  corner, and traversal path as the original variant. The original directional
  IPS remains byte-for-byte unchanged; both variants have desktop/web selection
  UX, headless support, shared custom item placement, mutual-exclusion checks,
  and static regression coverage.
- First tileset/metatile composer pass is implemented and has manual notes in `docs/project/metatile_composer_test_notes.md`.
- Enemy tile-sheet export hardening has manual notes in `docs/project/enemy_sprite_hardening_test_notes.md`.
- Embedded Layer 2 editing has manual notes in `docs/project/layer2_editing_test_notes.md`.
- Save station spawn editing has manual notes in `docs/project/save_station_spawn_test_notes.md`.
- Validation expansion has manual notes in `docs/project/validation_suite_test_notes.md`.
