# SMEDIT Parity And Hardening Backlog

Last updated: 2026-07-13

This file captures the current SMILE/local-reference audit so the next work can resume without redoing the full review.

## Current Priority Order

1. Enemy sprite correctness and export hardening.
   - Close the remaining assembled-sprite/spritemap mismatch risks documented in `docs/OPENAI_REVIEW.md`.
   - Prefer raw 4bpp tile-sheet import/export over legacy PNG sprite replacement for ROM-critical paths.
   - Add regression fixtures for sidehopper, pirates, bosses, palette variants, and DMA transfer edge cases.
2. Tileset/metatile composer.
   - SMILE parity requires editing 16x16 metatile definitions from four 8x8 tilemap words.
   - Each subtile needs tile index, palette row, priority, h-flip, and v-flip controls.
   - Project save and ROM export must preserve changed variable tile tables and shared CRE tile tables.
3. Validation expansion.
   - Existing validator covers doors, duplicate item bits, enemy GFX slot pressure, and room dimensions.
   - Next checks should include PLM terminators/counts, scroll pointer bounds, room-state pointer consistency, compressed data fit/relocation failures, and graphics table fit.
4. Spider Ball behavior hardening.
   - Keep spider code out of ground/slope ownership.
   - Preserve jump, wall-jump, morph tunnel, slope, and moving-platform behavior from the documented acceptance list.
5. Sound export hardening.
   - Verify relocated transfer chains, SPC RAM budget, sample directory consistency, and no-overlap rules.

## SMILE-Parity Gaps Still Open

- Layer 2 / BG editor: expose BG data pointer workflows, scrolling/link behavior, and door-dependent background transfers as an authoring surface instead of mostly read-only metadata.
- Save station spawn editing: current parsing/display is read-only; authoring needs station PLM/link/spawn metadata edits with emulator validation.
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

Continue enemy sprite correctness and export hardening:

- Treat raw 4bpp tile sheets as the canonical edit/reimport unit for ordinary enemy graphics.
- Keep assembled PNG/GIF exports as visual/reference output unless a future reverse-mapper can safely recover OAM placement, flips, frame selection, and runtime tile sources.
- Validate ordinary enemy custom tile blocks against species `tileDataSize`, GRAPHADR, and ROM bounds before export.
- Continue hardening special boss cases where graphics are compressed, DMA-composed, or split across room/runtime tile sources.

## Recent Progress

- First tileset/metatile composer pass is implemented and has manual notes in `docs/project/metatile_composer_test_notes.md`.
- Enemy tile-sheet export hardening has manual notes in `docs/project/enemy_sprite_hardening_test_notes.md`.
