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

Implement the first major tileset/metatile composer pass:

- Add durable project storage for variable tile-table and shared CRE tile-table overrides.
- Add `TileGraphics` APIs to mutate/export metatile table words.
- Add a composer UI for TL/TR/BL/BR tile index, palette, priority, and flips.
- Export changed tile tables to ROM when the recompressed table fits the original allocation.
- Leave relocation/ROM expansion for a follow-up unless the existing export infrastructure already makes it low-risk.
