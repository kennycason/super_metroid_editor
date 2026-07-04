# Biome procgen — remaining work

Status after the code-quality refactor on branch `level_gen` (Jul 2026).

## Done in this pass

- Split `BiomeGenerator.kt` into focused modules:
  `BiomeCell`, `NeighborMask`, `DoorPreservation`, `StructureAlgorithms`,
  `GridConnectivity`, `BiomeMutators`, `TileDresser`, slim orchestrator.
- Unified rule overrides via `BiomeRules.withOverrides()` — UI rule card and
  generation use the same effective rules.
- Moved generation orchestration to `EditorState.generateBiome()`.
- Deduplicated undo commit path via `EditorState.pushEditOperation()`.
- Added `TilesetProfileCache` (filter by tileset before LZ2 decompress).
- Invalidate cache when a new ROM is loaded (`Main.kt`).
- Shared 8-bit neighbor mask in `NeighborMask` (learning + dressing).
- Named bottom-pane tab constants in `Main.kt`.
- Removed unused `TilesetProfile.pickShot` / visible shot-block learning.

## Remaining — high priority

### 1. Connectivity-first structure (delete repair loop)

`GridConnectivity.ensureConnected` still runs up to 60 merge iterations with
O(n²) closest-pair sampling. Structure algorithms should grow from door pockets
outward so connectivity is guaranteed by construction. Then:

- Shrink `ensureConnected` to a test-time assert, or a single cheap validation.
- Remove post-hoc L-tunnels that cut through solid terrain.

### 2. Mutators must not sever the main region

Ceiling fangs and hidden passages can still narrow passages enough to require
the second connectivity pass. Each mutator should check that it does not
partition the passable region (or only apply on cells that pass a local
reachability probe from the nearest door).

### 3. ~~Invalidate `TilesetProfileCache` on ROM reload~~ (done)

Cache is cleared in `Main.kt` whenever the user loads a different ROM file.

### 4. `EditorState` decomposition (pre-existing debt)

`EditorState` is ~5.6k lines. Procgen integration added `generateBiome` and
`pushEditOperation` but the class still needs a broader split (room loading,
undo, export, sprite editors) — out of scope for procgen but blocks long-term
maintainability.

## Remaining — medium priority

### 5. Preview before apply

Generate into a side-by-side or overlay preview without writing working tiles;
only commit on explicit Apply. Avoids accidental full-room overwrite.

### 6. PLM / enemy preservation policy

Only door blocks and their frame ring are preserved. Save stations, item PLMs,
enemies, and scroll PLMs are overwritten when generated tiles collide. Decide
policy: preserve all PLM cells, or warn when generation would bury gameplay
objects.

### 7. Layer-2 and BTS extend blocks

Generator only rewrites layer-1 words + BTS. H-extend (0x5) and V-extend (0xD)
anchors in the source room are not reconstructed; dressing may emit bare 0x8
solids where vanilla used extend chains. Consider extend-aware dressing or a
post-pass that collapses runs into extend blocks where the tileset uses them.

### 8. Test fixture helper

`BiomeGeneratorTest`, `BiomeRenderDiagnostic`, and future tests duplicate
Landing Site grid extraction. Extract `ProcgenTestFixtures.landingSiteGrid(rom)`
in `shared/src/jvmTest`.

### 9. `BiomeRules.describe()` vs slider thresholds

`describe()` uses fixed cutoffs (`> 0.05`) while mutators use `0.02` density
thresholds in `withOverrides`. Align wording thresholds or derive describe text
from the same constants.

## Remaining — lower priority / polish

### 10. Regenerate without re-roll

Keep style + seed fixed; add a "Regenerate" button that only re-runs the
generator (sliders already apply via `withOverrides`).

### 11. Per-style preview thumbnails in UI

`test-resources/biome_*.png` exist from `BiomeRenderDiagnostic`; wire small
thumbnails into the style dropdown so users see the archetype before rolling.

### 12. Batch generate for RL segments

CLI or script entry that emits multiple rooms with a seed sequence for
`platformer_common` segment configs — no UI required.

### 13. Visible shot blocks (`0xC` / BTS `0x00`)

Learning for visible shots was removed as unused. Re-add only if a mutator needs
breakable shot blocks (not hidden); would need `pickShot` + generator cell type.

## Verification

```bash
./gradlew :shared:jvmTest --tests 'com.supermetroid.editor.procgen.*'
```

ROM-backed tests skip when `test-resources/Super Metroid (JU) [!].smc` is absent.

## Module map

```
shared/src/commonMain/kotlin/com/supermetroid/editor/procgen/
  BiomeStyle.kt           — archetypes, algorithms, mutator enums
  BiomeRules.kt           — seeded rule card + withOverrides()
  LevelGrid.kt            — read-only decompressed level parse
  TilesetProfile.kt       — learned tile vocabulary
  TilesetProfileCache.kt  — per-tileset profile cache
  BiomeGenerator.kt       — pipeline orchestrator
  BiomeCell.kt            — internal cell states
  NeighborMask.kt           — shared 8-bit mask
  DoorPreservation.kt     — door detect + pockets
  StructureAlgorithms.kt  — cave / chambers / shaft / gallery
  GridConnectivity.kt     — merge + tunnel (candidate for removal)
  BiomeMutators.kt        — platforms, hazards, secrets
  TileDresser.kt          — block words + BTS from cell grid
```
