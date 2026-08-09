# Code Cleanup And Missing Features Review

Last updated: 2026-08-08

Scope: static review of the current codebase, docs, and test structure. SMART experimental support is intentionally excluded. The original review pass was static; the 2026-08-08 implementation follow-up ran shared and desktop JVM tests.

Primary assumption: the desktop app is currently the canonical editing/export surface because it supports the broadest project export behavior.

## Top Findings

### P0: Export Can Knowingly Corrupt ROM Data

- Status: Addressed on 2026-08-08
- Area: desktop ROM export
- References:
  - [`ProjectRoomExporter.kt`](../../shared/src/commonMain/kotlin/com/supermetroid/editor/rom/ProjectRoomExporter.kt)
  - [`ProjectRoomExporterTest.kt`](../../shared/src/jvmTest/kotlin/com/supermetroid/editor/rom/ProjectRoomExporterTest.kt)

When expanded scroll data cannot be relocated, export now throws `ProjectRoomExportException` before a ROM file is written. A regression test exhausts bank `$8F` free space and verifies the export aborts instead of writing expanded scroll data in place.

Done when:

- [x] Export fails before writing whenever expanded data cannot be safely relocated.
- [x] The desktop UI reports the export failure through the export status/log path.
- [x] A regression test covers the no-free-space case.

### P1: Desktop And Headless Export Pipelines Are Divergent

- Status: Partially addressed on 2026-08-08
- Area: export architecture
- References:
  - [`ProjectRoomExporter.kt`](../../shared/src/commonMain/kotlin/com/supermetroid/editor/rom/ProjectRoomExporter.kt)
  - [`RomExporter.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/RomExporter.kt)
  - [`SmeditBuildService.kt`](../../shared/src/jvmMain/kotlin/com/supermetroid/editor/headless/SmeditBuildService.kt)

The room-edit export core is now shared. Desktop export and headless ROM builds both call `ProjectRoomExporter` for room level data, PLMs, custom scroll commands, doors, enemy population/GFX sets, scroll data, FX, room headers, state data, and save station spawns. Patch-only headless builds still require `--rom` for room edits because compressed room data cannot be produced safely without ROM context.

Remaining work: patch/config export, custom graphics, music, text, minimap, and custom ASM still have separate desktop/headless implementations or partial support.

Done when:

- [x] Shared room export engine lives in `shared`.
- [x] Desktop ROM export and service/headless ROM builds call the same room-edit core.
- [ ] Continue unifying patch/config, graphics, music, text, minimap, and custom ASM export.
- [ ] Replace ad hoc logs/warnings with structured export issues.

### P1: `EditorState` Is A God Object

- Status: Partially addressed on 2026-08-08
- Area: desktop editor architecture
- References:
  - [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L107)
  - [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L145)
  - [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L4027)
  - [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L4083)

`EditorState` is still large, but project file load/save and desktop ROM/IPS export orchestration have been moved into `ProjectFileService`. `EditorState` now keeps thin public methods for those workflows while no longer owning file serialization, custom graphics PNG side effects, or export file writing.

Done when:

- [x] Project file load/save and desktop export orchestration moved into a service.
- [ ] Project persistence should still become a fuller `ProjectStore` or equivalent.
- [ ] Room editing session state is separated from global app state.
- [ ] Graphics/sprite editor sessions are separated from room editing state.
- [x] Export orchestration is delegated to a service/coordinator.
- [ ] Compose state wraps stable immutable values or explicit observable stores.

### P1: Map Rendering Recomputes Too Much

- Status: Open
- Area: desktop performance
- References:
  - [`MapCanvas.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MapCanvas.kt#L446)
  - [`MapCanvas.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MapCanvas.kt#L898)
  - [`MapCanvas.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/MapCanvas.kt#L966)

`MapCanvas` loads room state inside a render effect and rebuilds full composite images on edit and overlay changes. This creates unnecessary work for painting, overlays, cursor previews, and large rooms.

Done when:

- Room loading/session changes happen outside render effects.
- Static room image, editable tile layer, overlays, and cursor/brush preview are separate layers.
- Small edits invalidate tile regions or layer caches instead of full-room composites where possible.
- Overlay toggles do not force level data re-rendering unless the underlying layer changed.

### P2: ROM Parsing Needs Per-ROM Indexes

- Status: Open
- Area: parser/service performance
- References:
  - [`RomParser.kt`](../../shared/src/commonMain/kotlin/com/supermetroid/editor/rom/RomParser.kt#L1091)
  - [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L2079)
  - [`RoomListView.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/RoomListView.kt#L86)

Several paths repeatedly scan all rooms or repeatedly parse headers/door lists. For example, `findDoorsLeadingTo()` scans every room each call, and room list sorting reads every room header in UI code.

Done when:

- A per-ROM `RoomHeaderIndex` exists.
- A per-ROM `DoorGraphIndex` exists.
- Parsed state data, PLM lists, enemy lists, and room metadata are cached with clear invalidation rules.
- UI code consumes indexes instead of repeatedly scanning through `RomParser`.

### P2: Service App Patch UI Is Hardcoded Below Backend Capability

- Status: Open
- Area: service app UX/API parity
- References:
  - [`SmeditPatchCatalog.kt`](../../shared/src/jvmMain/kotlin/com/supermetroid/editor/headless/SmeditPatchCatalog.kt#L65)
  - [`SmeditServiceMain.kt`](../../smedit-service/src/jvmMain/kotlin/com/supermetroid/editor/service/SmeditServiceMain.kt#L394)
  - [`App.tsx`](../../smedit-service-app/src/App.tsx#L28)
  - [`App.tsx`](../../smedit-service-app/src/App.tsx#L459)
  - [`types.ts`](../../smedit-service-app/src/types.ts#L1)

The backend exposes patch metadata dynamically, but the React app hardcodes a much smaller `patchOptions` list and a hardcoded TypeScript `PatchId` union. Metadata is used mostly to filter the static list rather than define the UI.

Done when:

- Patch options are derived from service metadata.
- Config schemas drive option controls where possible.
- The frontend keeps only presentation grouping/defaults as an overlay.
- TypeScript types no longer require editing every time backend patches change.

### P2: Test Suite Is Broad But Too Optional

- Status: Open
- Area: test reliability
- References:
  - [`TestRomHelper.kt`](../../shared/src/jvmTest/kotlin/com/supermetroid/editor/rom/TestRomHelper.kt#L10)
  - [`TestRomHelper.kt`](../../desktopApp/src/jvmTest/kotlin/com/supermetroid/editor/rom/TestRomHelper.kt#L10)

The test tree is large, but many ROM-dependent tests return early when the local ROM is unavailable. The local test ROM is not tracked in `test-resources`, so CI can pass while skipping important ROM behavior. There are also many diagnostic tests and `println()` outputs in normal test sources.

Observed counts from this review:

- 132 test files.
- 1,124 `@Test` declarations.
- 809 `println()` calls.
- 346 `loadTestRom() ?: return` or similar early-return skips.
- 20 diagnostic/investigation-style test files.

Done when:

- Diagnostics are separated from default test tasks.
- ROM-dependent tests use explicit assumptions and report skipped counts.
- Critical exporter/parser behavior has legal synthetic fixtures where possible.
- Critical diagnostic discoveries are promoted into assertive regression tests.

## Missing Or Incomplete Features

### Highest Priority

- [ ] ROM expansion and safer free-space allocation.
- [ ] New room creation/deletion with room header, state, door, level, enemy, PLM, scroll, and minimap allocation.
- [ ] Room JSON import with conflict handling and validation.
- [ ] AreaSave expansion and duplicate-slot conflict UI.
- [ ] Validation expansion and richer export blocker UX.
- [ ] Room graph discovery and route validation.
- [ ] Auto item/door ID assignment to prevent collisions.
- [ ] Enemy/Samus raw sprite tile-sheet import/export hardening.
- [ ] Enemy sprite assembly correctness for VRAM base tiles, extended spritemaps, palette rows, and room-context-dependent graphics.
- [ ] Layer 2/BG scrolling and door-dependent background transfer hardening.
- [ ] Sound export hardening around transfer chains, SPC RAM budget, sample directories, and no-overlap rules.
- [ ] Service app full patch/schema surface.

### Notes On Partial Features

- Metatile composition is not purely missing anymore. A first pass exists in [`TilesetEditor.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/TilesetEditor.kt#L724), and project persistence hooks exist in [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L641). Treat this as hardening/export-confidence work.
- Room JSON export exists in [`EditorState.kt`](../../desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt#L3962). Import is the missing side.
- Save station spawn editing exists for existing AreaSave slots, but table expansion and conflict handling remain open.
- `RomValidator` already checks several categories in [`RomValidator.kt`](../../shared/src/commonMain/kotlin/com/supermetroid/editor/rom/RomValidator.kt#L28). Next checks should include scroll pointer bounds, room-state pointer consistency, room graph consistency, JSON import conflicts, and export blockers.

## Suggested Work Order

1. Make corruption impossible: turn unsafe scroll-data in-place writes into hard export failures.
2. Introduce a structured export issue model and start routing desktop exporter warnings through it.
3. Extract a shared export planner/engine for one narrow slice, then move additional desktop/headless behavior into it incrementally.
4. Add parser indexes for room headers and door graph, then update room load/list code to use them.
5. Split the first piece out of `EditorState`, preferably project persistence or export orchestration.
6. Make the service patch UI metadata-driven.
7. Separate diagnostics from default tests and add focused regression fixtures for export safety.

## Quality Gates To Add

- Kotlin formatting/linting, such as ktlint or Spotless.
- Kotlin static analysis, such as Detekt, with complexity thresholds for new code.
- Frontend lint/test scripts for `smedit-service-app`.
- Coverage or at least required regression suites for export allocator, parser indexes, validator, and project serialization.
- A CI-visible skipped-ROM-test summary so optional ROM tests do not create false confidence.

## Open Questions

- Should headless/service builds ever allow partial project export, or should unsupported project data always fail by default?
- Should ROM expansion be implemented before new room creation, or should new room creation initially target existing free space only?
- Should room graph discovery become a core shared data model used by validation, minimap, room browser, and randomizer-oriented workflows?
- Which sprite workflows are product-critical first: enemy raw tile-sheet import/export, Samus raw tile-sheet import/export, or assembled preview correctness?
