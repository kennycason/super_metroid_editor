# SMEDIT Parity And Hardening Backlog

Last updated: 2026-07-13

This file captures the current SMILE/local-reference audit so the next work can resume without redoing the full review.

## Current Priority Order

1. Tileset/metatile composer hardening.
   - SMILE parity requires editing 16x16 metatile definitions from four 8x8 tilemap words.
   - Each subtile needs tile index, palette row, priority, h-flip, and v-flip controls.
   - Project save and ROM export must preserve changed variable tile tables and shared CRE tile tables.
2. AreaSave/save-station hardening.
   - Auto-derived spawn overrides and manual X/Y/scroll editing now exist.
   - Remaining work is AreaSave table expansion, duplicate slot conflict UI, and emulator-validated resume paths.
3. Enemy sprite correctness and export hardening.
   - Close the remaining assembled-sprite/spritemap mismatch risks documented in `docs/OPENAI_REVIEW.md`.
   - Prefer raw 4bpp tile-sheet import/export over legacy PNG sprite replacement for ROM-critical paths.
   - Add regression fixtures for sidehopper, pirates, bosses, palette variants, and DMA transfer edge cases.
4. Validation expansion.
   - Existing validator covers doors, duplicate item bits, enemy GFX slot pressure, and room dimensions.
   - Next checks should include PLM terminators/counts, scroll pointer bounds, room-state pointer consistency, compressed data fit/relocation failures, and graphics table fit.
5. Spider Ball behavior hardening.
   - Keep spider code out of ground/slope ownership.
   - Preserve jump, wall-jump, morph tunnel, slope, and moving-platform behavior from the documented acceptance list.
6. Sound export hardening.
   - Verify relocated transfer chains, SPC RAM budget, sample directory consistency, and no-overlap rules.

## SMILE-Parity Gaps Still Open

- Layer 2 / BG hardening: embedded L2 editing exists; richer BG data pointer workflows, scrolling/link behavior, and door-dependent background transfers still need a fuller authoring surface.
- AreaSave expansion/conflict UI: save station spawn editing exists for existing slots; authoring still needs table expansion and duplicate-slot safety before it is fully general.
- Room JSON import: export exists, import still needs conflict handling and validation.
- New room creation: requires room header/state/door/minimap allocation and route validation.
- SMART XML import: useful for interoperability and migration from older tools.
- ROM expansion: needed to remove in-place compressed-size limits for graphics, music, and level data.
- Auto item/door ID assignment: reduce hand-maintained ID collisions.
- Room graph discovery: should feed validation, minimap, and randomizer-style workflows.

## Notes From Local References

- `smile` remains the reference for graphics/metatile editing expectations, old UI workflows, and editor affordances.
- `smart` and `MapRandomizer` are useful for room graph, topology, and randomizer-facing data modeling.
- `SM-SPC` and current sound docs are the reference path for N-SPC/SPC transfer correctness.
- `sm_disassembly` and `sm` remain the canonical source for engine behavior and data layout.

## Current Work Item

Next up after the current save-station slice:

- Harden the metatile composer UX/export path and continue emulator-backed checks.
- Add AreaSave table expansion or a conflict-resolution UI before treating save-station creation as fully general.
- Continue enemy sprite correctness work for special compressed/DMA/boss cases.

## Recent Progress

- First tileset/metatile composer pass is implemented and has manual notes in `docs/project/metatile_composer_test_notes.md`.
- Enemy tile-sheet export hardening has manual notes in `docs/project/enemy_sprite_hardening_test_notes.md`.
- Embedded Layer 2 editing has manual notes in `docs/project/layer2_editing_test_notes.md`.
- Save station spawn editing has manual notes in `docs/project/save_station_spawn_test_notes.md`.
