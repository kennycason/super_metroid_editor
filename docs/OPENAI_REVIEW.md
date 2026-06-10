# OpenAI Review Tracker

Last updated: 2026-06-08

This document tracks the systematic review of SMEDIT against SMILE,
MapRandomizer, the Super Metroid disassembly/decompilation, and our own docs.
It is intentionally review-first: implementation changes should be proposed and
discussed before code is edited.

## Review Rules

- Do not change production code from this review without first discussing the finding and proposed fix.
- Verify feature claims against at least one source of truth where possible.
- Distinguish shipped functionality from documented intent, partial support, and unverified assumptions.
- Prefer ROM/decomp/disassembly behavior over SMILE behavior when the two disagree.
- Track reproducible bugs with ROM address, room/enemy/state, source reference, and test coverage target.
- Keep docs honest: if something is partial or heuristic, say so.

## Status Terms

| Status | Meaning |
| --- | --- |
| Verified | Reviewed against current code and at least one reference source. |
| Partial | Exists, but coverage, UX, correctness, or export behavior is incomplete. |
| Needs Fix | Known incorrect behavior or missing feature. |
| Unknown | Claimed or suspected, but not yet verified. |
| Surplus | Functionality beyond SMILE, verified enough to keep claiming. |

## Source References

| Source | Location | Use |
| --- | --- | --- |
| Project context | `docs/CONTEXT.md` | Repo-specific ROM map, docs index, external reference map. |
| SMILE source | `/Users/kenny/code/super_metroid/smile/source` | Legacy editor feature parity, forms, UI behavior, binary assumptions. |
| SMILE data files | `/Users/kenny/code/super_metroid/smile/files` | Enemy/PLM/reference assets and legacy resource assumptions. |
| SM decompilation | `/Users/kenny/code/super_metroid/sm/src` | Runtime behavior, drawing paths, game logic, pointer handling. |
| SM disassembly | `/Users/kenny/code/super_metroid/sm_disassembly/src` | Bank-level data labels, spritemaps, instruction lists, ROM data truth. |
| SM disassembly tools | `/Users/kenny/code/super_metroid/sm_disassembly/tools` | Existing decoders, especially spritemap parsing helpers. |
| Enemy instruction decoder | `/Users/kenny/code/super_metroid/sm/assets/enemy_instr_decode.py` | Candidate source of truth for enemy instruction-list traversal. |
| MapRandomizer | `/Users/kenny/code/super_metroid/MapRandomizer` | Map graph, room geometry, randomizer constraints, JSON data. |
| SM-SPC | `/Users/kenny/code/super_metroid/SM-SPC` | SPC/song engine reference. |

## Initial Summary

Current docs plausibly show SMEDIT at or beyond SMILE for many workflows, but
some claims need tightening. The strongest immediate risks are enemy sprite
assembly correctness, raw sprite tile-sheet import/export, docs freshness around
validation, and inconsistent UX primitives across editors.

## Feature Parity Audit

| Area | Current Claim | Initial Evidence | Reference To Verify | Status | Notes / Actions |
| --- | --- | --- | --- | --- | --- |
| Room tile editing | Parity/surplus | Room editor, tile tools, selection work, export paths exist. | SMILE `Smile.frm`, `UGraphics.bas`; SM decomp room loading. | Unknown | Review selection UX, floating paste behavior, undo/redo, tile/BTS/layer semantics. |
| BTS editor | Parity/surplus | Docs claim all SMILE variants; UI has BTS rendering/editing. | SMILE docs/manual, `UGraphics.bas`; block collision code. | Unknown | Verify subtype names, slope profiles, grapple/spike/crumble/bomb/shot semantics. |
| PLM/item editing | Parity/surplus | PLM docs, Items tab, item list panel, validation for duplicate item bits. | SMILE `PLMForm.frm`; disassembly PLM banks; MapRandomizer item data. | Unknown | Verify collection-bit allocation, multi-state PLMs, station/gate semantics, export roundtrip. |
| Door editor | Parity/surplus | Door parsing/editing and validator exist. | SMILE `DoorForm1.frm`; SM decomp transition code. | Unknown | Verify door cap behavior, spawn offsets, direction/BTS consistency, state-dependent door lists. |
| Scroll/FX editor | Parity/surplus | Docs and UI indicate visual scroll triggers and FX editing. | SMILE `SaveScrollPLM1.frm`, `FX1_1.frm`, `Layer3Editor.frm`; bank $8F/$83. | Unknown | Verify event/scroll bytes, liquid physics, layer 3 types, room-state interaction. |
| Enemy population/stats | Parity/surplus | Enemy stats, vulnerabilities, drop rates, GFX limits, layer props exist. | SMILE `SpeciesForm.frm`, `VulnerabilitiesForm1.frm`, `EnemyMiscellaneousEdit1.frm`; bank $A0/B4. | Unknown | Verify species header field offsets, AI/touch/shot pointer edits, vulnerability table indexing. |
| Enemy sprites | Claimed surplus | Tile-sheet rendering/editing exists, assembled previews exist, but known garbling remains. | SM decomp `sm_a0.c`, `sm_81.c`; disassembly bank A0/A2-AF spritemaps. | Needs Fix | Highest-priority correctness audit. See "Known Issue: Enemy Sprite Assembly". |
| Boss editors | Claimed surplus | Kraid/Phantoon docs and specialized editors exist. | Disassembly/decomp boss-specific banks and graphics loading. | Unknown | Verify all edited data is ROM-exportable, not just preview/editor state. |
| Samus sprites | Partial/surplus claimed | Samus viewer and "export all sprites" exist; docs describe architecture. | SMILE `SamusForm*`; disassembly banks $9B-$9F; SpriteSomething. | Partial | Viewer/export exists. Raw Samus tile-sheet import/edit/export is not yet verified. |
| Tile graphics pipeline | Parity/surplus | CRE/URE import/export exists; docs describe SMILE/Lunar pipeline. | SMILE `UGraphics.bas`, `Lunar*.bas`; ROM compression tests. | Unknown | Verify imported sheets preserve palette/indexing/flip semantics and export to ROM safely. |
| Tileset/metatile composer | Gap documented | Docs list composer as missing. | SMILE graphics/metatile editor forms. | Needs Fix | Confirm whether current UI covers 8x8-to-16x16 composition; if not, keep as gap. |
| Minimap/mapshot | Parity/surplus | Minimap docs/UI and map canvas exist. | SMILE `Mapper1.frm`, `MapshotForm1.frm`; MapRandomizer map data. | Unknown | Verify map tile encoding, area maps, map station state, export roundtrip. |
| Text editor | Parity | Text UI and docs exist. | SMILE `TextForm1.frm`; text encoding tables. | Unknown | Verify character table, terminators, free-space/repoint handling. |
| Sound/SPC | Surplus claimed | Native SPC support exists; recent CI changes around snes_spc. | SM-SPC, decomp sound engine, SMILE room music selector. | Unknown | Verify build portability, song pointer safety, sample replacement, test coverage. |
| Patches/ASM/IPS | Surplus claimed | Patch editor and IPS/project export paths exist. | Asar conventions, ROM patch docs, SMILE hex edit docs. | Unknown | Verify conflict detection, free-space allocation, patch ordering, reversibility. |
| Validation suite | Docs say missing, code exists | `RomValidator` checks doors, item bit duplicates, enemy GFX limit, room dimensions. | SMILE validators/scanners; known hack failure modes. | Partial | Update parity docs after audit. Expand validation categories and tests. |
| Hotkey configuration | Gap documented | Fixed shortcuts are common. | SMILE `HotKeys1.frm`. | Needs Fix | Decide whether configurable shortcuts are required for surplus claim. |
| Plugins/localization | Gap documented | SMILE has plugin/localization forms. | SMILE `Plugins.frm`, `plugin.frm`. | Unknown | Likely low priority unless we want exact feature parity wording. |

## Docs Freshness Audit

| Doc | Initial Finding | Status | Action |
| --- | --- | --- | --- |
| `docs/project/smile_parity.md` | Strong surplus claims, but some rows are stale or over-broad. Validation is listed as unavailable despite current code. Sprite import/export wording is ambiguous. | Partial | Reconcile every claim against current code and references. |
| `docs/project/roadmap.md` | Lists validation as future work; likely stale. | Partial | Update after validation audit. |
| `docs/project/plan.md` | Similar roadmap/status drift; useful but not authoritative yet. | Partial | Convert to historical plan or refresh status. |
| `docs/graphics/sprites.md` | Good architecture overview, but enemy sprite correctness is not fully verified and import/export wording needs precision. | Partial | Add explicit distinction between assembled sprite export, raw tile-sheet editing, and ROM-exportable data. |
| `docs/graphics/samus_sprites.md` | Detailed Samus reference; implementation parity not yet verified. | Unknown | Compare decoder against disassembly/SpriteSomething and identify import/edit/export gaps. |
| `docs/graphics/tile_pipeline.md` | Strong SMILE/Lunar notes; needs current-code roundtrip verification. | Unknown | Verify compression, palette, CRE/URE, and metatile behavior. |
| `docs/rom/enemies.md` | Important multi-piece enemy notes; should drive validation/tests. | Partial | Convert possessor/multi-piece rules into validator checks where possible. |
| `docs/rom/*` | Broad ROM notes appear useful but need line-by-line status labeling. | Unknown | Audit one subsystem at a time. |
| `docs/reference/*` | Legacy reference dumps are useful, not current status docs. | Verified | Keep as reference, do not treat as implementation status. |

## Known Issue: Enemy Sprite Assembly

User-reported problem: not all enemy sprites assemble correctly; some are
garbled. This matches an initial source-level risk.

Current implementation touchpoints:

| File | Observation |
| --- | --- |
| `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/EnemySpritemap.kt` | Parses OAM spritemap entries and renders `entry.tileNum and 0xFF` into a local tile sheet. |
| `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/EnemySpritemap.kt` | `findDefaultSpritemap` traces init AI heuristically and chooses candidate spritemaps. |
| `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/EnemySpriteGraphics.kt` | Loads graphics data from species headers and selected boss special cases. |
| `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EnemySpriteViewer.kt` | Uses `findDefaultSpritemap` plus `renderSpritemap` for assembled preview, with tile-sheet fallback. |

Reference behavior:

| Reference | Observation |
| --- | --- |
| `/Users/kenny/code/super_metroid/sm/src/sm_a0.c` | `WriteEnemyOams` uses `E->vram_tiles_index` as the base tile and handles extended spritemaps. |
| `/Users/kenny/code/super_metroid/sm/src/sm_81.c` | `DrawSpritemapWithBaseTile` writes OAM char data as palette/layer bits OR base tile plus spritemap tile word. |
| `/Users/kenny/code/super_metroid/sm_disassembly/src/bank_A0.asm` | Contains enemy draw path and extended spritemap processing. |
| `/Users/kenny/code/super_metroid/sm_disassembly/src/bank_A2.asm` and later banks | Contains concrete `%spritemapEntry(...)` data to compare decoded entries against. |

Hypotheses to verify:

| Hypothesis | Why It Matters | Verification |
| --- | --- | --- |
| Missing VRAM base tile modeling | Vanilla adds `vram_tiles_index` to spritemap tile words; local `tileNum & 0xFF` can pick the wrong tile for room GFX slot layouts. | Compare assembled preview for enemies sharing a room GFX set against decomp/disassembly OAM tile math. |
| Extended spritemaps incomplete | Some enemies use extended spritemap format and optional `ProcessExtendedTilemap`. | Identify species with `extra_properties & 4` and render them through a matching path. |
| Default spritemap tracing is too heuristic | Init AI/instruction-list paths are dynamic and state dependent. | Use disassembly labels or decoded instruction tables as fixtures for representative species. |
| Palette/base row assumptions | Palette/layer bits come from enemy runtime state and spritemap attrs. | Compare against decomp `palette_index` and OAM attr handling. |
| Tile data source can be room-context dependent | Room enemy GFX set slots can combine multiple enemy graphics blocks. | Build previews from a room instance, not only species-local raw tile data, where needed. |

Review output target:

| Artifact | Purpose |
| --- | --- |
| Enemy sprite status table | One row per supported species: tile sheet OK, assembled OK, garbled, no spritemap, extended, boss-specific. |
| Fixture list | Small set of enemies covering normal, 16x16, extended, multi-GFX, boss, dynamic animation. |
| Regression tests | Compare decoded spritemap entries, tile indices, and rendered dimensions/nontransparent counts against known-good fixtures. |

## Known Issue: Sprite Tile-Sheet Import/Export

Current code has multiple sprite-related paths that should be separated in docs
and UX:

| Path | Current Evidence | Review Status |
| --- | --- | --- |
| Room CRE/URE tile-sheet file import/export | `EditorState.exportTileSheet` and `EditorState.importTileSheet` exist. | Partial |
| Enemy assembled frame/sheet export | Enemy viewer exports rendered frames/animations/sheets. | Partial |
| Enemy raw 4bpp tile-sheet in-app editing | `loadEnemyTileData` and `applyEnemyTileSheetEdits` store raw data in `customGfx.spriteTileBlocks`. | Partial |
| Enemy raw tile-sheet file import/export | Not yet verified in UI. Desired target is file import/export of tile sheets, not assembled sprite PNG overrides. | Needs Fix |
| Legacy enemy PNG sprite override | `customGfx.enemyGfx` stores PNG overrides; likely editor/resource-level, not correct ROM-exportable tile-sheet data. | Needs Review |
| Samus all-sprite export | `SamusSpriteViewer` exports assembled sprite sheets. | Partial |
| Samus raw tile-sheet import/edit/export | Not yet verified. Desired support includes Samus/etc tile-sheet workflows. | Needs Fix |
| Boss raw tile-sheet editing | Phantoon/Kraid tile-sheet editors exist. | Partial |

Proposed direction to discuss before code changes:

| Proposal | Reason |
| --- | --- |
| Treat raw tile sheets as the canonical import/export unit for ROM-exportable sprite graphics. | Assembled preview PNGs do not map cleanly back to 4bpp tile blocks and OAM layouts. |
| Reuse `SpritePixelEditor` for enemy, boss, and Samus tile sheets. | Keeps editing UX consistent and avoids one-off sprite editors. |
| Deprecate or clearly label PNG override paths that are not ROM-exportable. | Prevents users from thinking preview/resource overrides will patch the ROM correctly. |

## UX Consistency Audit

Initial scan shows several editors implement zoom, pan, keyboard movement, and
font sizing independently.

| Concern | Evidence | Status | Action |
| --- | --- | --- | --- |
| Zoom ranges differ | Map, tileset preview/editor, minimap, pattern editor, sprite pixel editor use different min/max and increments. | Partial | Define shared zoom semantics per surface type. |
| Trackpad/mouse behavior differs | Some surfaces support pinch/ctrl-wheel/mouse-centered zoom; others do not. | Partial | Build a common input policy for canvas-like editors. |
| Panning differs | Some views pan with wheel/drag; others only scroll containers. | Partial | Standardize pan gestures and keyboard arrows where applicable. |
| Selection preview differs | Room editor selection should hover until commit; similar preview behavior should apply to paste/placement tools. | Partial | Confirm editor-wide placement model: preview first, Enter/click commits, Escape cancels. |
| Font sizes are hardcoded | Many Compose files use direct `fontSize = N.sp` despite theme helpers. | Partial | Consolidate typography constants and default sizes. |
| Magic numbers are scattered | UI and ROM constants mix local literals with shared constants. | Partial | Move repeated UI/ROM constants to named objects when they are not self-evident. |
| Shortcuts are fixed/inconsistent | SMILE had configurable hotkeys; current behavior appears fixed. | Needs Fix | Inventory shortcuts and decide config priority. |

## Code Quality Review Areas

| Area | Questions |
| --- | --- |
| Reusable canvas primitives | Can map, room, minimap, tile, sprite, and pattern editors share zoom/pan/selection infrastructure? |
| Editor state boundaries | Are preview edits, committed project edits, ROM exports, and resource overrides clearly separated? |
| Constants | Are ROM offsets, sizes, UI ranges, palette rows, and format flags named once? |
| Error handling | Do import/export failures report actionable messages, not silent false/null results? |
| Tests | Do tests assert behavior, or only print diagnostics? Are ROM-fixture tests deterministic in CI? |
| Docs-to-code traceability | Can each surplus/parity claim link to code and a reference source? |

## Test Quality Audit

Initial test inventory is broad, but some tests appear diagnostic rather than
assertive. This should be reviewed per subsystem.

| Test Area | Existing Evidence | Desired Coverage |
| --- | --- | --- |
| Validation | `RomValidatorTest` exists and asserts item/GFX vanilla invariants. | Add mutation tests for each validation category. |
| Enemy sprites | Many enemy/spritemap diagnostics exist. | Promote known-good enemy fixtures to assertions. |
| Samus sprites | Samus decoder/baseline tests exist. | Add import/export/tile-sheet roundtrip tests if editing is added. |
| Room editor | Selection/placement behavior should be testable at state/model level. | Add tests for preview vs commit and no overwrite until commit. |
| Import/export | CRE/URE, enemy, boss, Samus, IPS/project exports need roundtrip tests. | Verify raw bytes and ROM reload behavior. |
| Sound/SPC | Recent CI issues show build/test portability matters. | Keep native dependency checks explicit and CI-safe. |

## Review Backlog

| Priority | Section | Goal | Output |
| --- | --- | --- | --- |
| P0 | Enemy sprite assembly | Identify and classify garbled enemies; compare renderer to vanilla OAM path. | Bug list, hypotheses confirmed/rejected, proposed fix plan. |
| P0 | Sprite tile-sheet import/export | Confirm exact gap for enemy, Samus, boss, and legacy PNG paths. | UX/data model proposal before code changes. |
| P1 | Docs status refresh | Make parity/roadmap docs accurate. | Updated docs proposal. |
| P1 | Validation suite | Compare against SMILE validators and known hack hazards. | Validator backlog and tests. |
| P1 | Room editor UX | Review selection, placement, keyboard, mouse, trackpad, undo/redo. | UX behavior spec and bug list. |
| P1 | Tileset/metatile pipeline | Verify tile-sheet, metatile composition, palette, compression, export. | Gap list and tests. |
| P2 | Doors/scroll/FX | Verify against SMILE and decomp behavior. | Correctness matrix and validator candidates. |
| P2 | Items/PLMs | Verify item bits, stations, gates, multi-state PLMs. | Correctness matrix and validator candidates. |
| P2 | Samus sprites/physics | Verify docs, decoder, export/edit gaps, physics patch safety. | Feature/gap list and tests. |
| P2 | Sound/SPC | Verify feature claims and CI/native dependency robustness. | Portability and correctness checklist. |
| P3 | Hotkeys/plugins/localization | Decide if these matter for parity wording. | Product decision and possible backlog. |

## Review Template

Use this for each subsystem review.

| Field | Notes |
| --- | --- |
| Subsystem | Name and scope. |
| Current user-facing behavior | What the app does now. |
| Current docs claim | Exact doc path/section. |
| SMILE behavior | Relevant forms/modules/manual notes. |
| ROM/decomp/disassembly behavior | Runtime truth and data format. |
| Current code path | Main classes/files. |
| Bugs found | Repro steps and expected/actual behavior. |
| Missing tests | Specific assertions or fixtures needed. |
| Proposed changes | Discussion item only until approved. |
| Status | Verified, Partial, Needs Fix, Unknown, or Surplus. |

## Decision Log

| Date | Decision |
| --- | --- |
| 2026-06-08 | Created this tracker as a review-first artifact. No production-code changes should come from the audit without discussion. |
| 2026-06-08 | Treat enemy sprite assembly and raw sprite tile-sheet import/export as the first high-priority review targets. |
