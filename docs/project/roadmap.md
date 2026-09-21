# Roadmap

**See also:** `smile_parity.md` for complete SMILE vs SMEDIT feature comparison.
**See also:** `plan.md` for detailed implementation notes per feature.
**See also:** `room_model_v2.md` for the state model, migration, and delivery chunks.
**Last updated:** 2026-09-20

---

## Completed ✅

### Core Editors
- Boss Stats Editor — 6 bosses + 7 mini-bosses, per-attack damage fields
- Enemy Stats Editor — 60+ species, HP + contact damage, AI pointers, GFX/layer priority
- Enemy Vulnerability Editor — 22 weapon slots per species
- Enemy Drop Rate Editor — 6 fields per species
- Samus Physics Editor — 17 verified fields (jump, gravity, running, air control)
- Palette Editor — HSV/RGB picker, 8x16 grid, import/export .pal
- Beam Damage Editor — Per-beam damage values
- Boss Defeated Flags — GUI toggles with ASM hook generation
- Phantoon Behavior Editor — 30+ parameters (timers, movement, flames)

### Room Editing
- Room Header Editor — All 11 fields writable, minimap links to Map tab
- Room Resize — Level data + BTS + L2 resize with scroll/door ASM remapping
- Room Shifting Tool — Selection + arrow keys, Ctrl for screen-step
- Stateful Room Editing — Ordered preview; add/duplicate/delete/reorder; state-scoped layouts, actors, objects, scrolls, music, tilesets, and FX; grouped vanilla/generated and compound conditions; load simulation; relocatable selector-graph export and semantic reopen
- Layer 2 Editing — Embedded L2 paint/sample/resize plus room-map zoom shortcuts
- Scroll Trigger PLM Editor — Visual screen grid for scroll commands
- Door Cloning Tool — Auto-detect direction from screen edge
- Space Utilization Monitor — Per-section byte counts in Room Info
- Auto-Repointing Engine — Level data, PLMs, scroll data, door ASM auto-relocate
- Save Station Spawn Editing — Auto-derived AreaSave overrides, manual X/Y/scroll editing, export to existing slots

### Text & Data
- In-Game Text Editor — Intro story (6 parts), area names (7), escape messages (2), UI messages (9), item pickup names (19)
- Mapshot / Save as PNG — Export button on canvas toolbar
- Room JSON Export — Self-contained room data with PNG/JSON dropdown
- Custom ASM Embedding — Hex bytes → free space + auto-link pointer

### Infrastructure
- Enemy/Boss promoted to top-level tabs
- TestRomHelper migration — 73 test files, eliminated hardcoded ROM paths
- FlowRow tab navigation — Tabs wrap when column is narrow
- Minimap Room Move — Buffer-based with Apply/Cancel
- Minimap Area/Transform Hardening — Shared safe area reassignment, context actions, station-reveal migration, exact flip/rotation UX, and coherent undo/redo
- Transactional ROM write planner — desktop/headless byte ownership, overlap and bounds failures, base-ROM hashes, expected-hook bytes, full allocation claims, runtime-resource declarations, and ownership reports

---

## Remaining — Prioritized

### Tier 1: High Impact, Next Up

| # | Feature | Effort | Why |
|---|---------|--------|-----|
| 1 | **New Room Creation** | Medium | Build on the same room model so new headers, selectors, states, doors, and resources are allocated as one validated graph. |
| 2 | **Tileset/Metatile Composer** | Large | Define 16x16 metatiles from 4 8x8 tiles with palette/flip per sub-tile. Enables truly custom tilesets. |
| 3 | **Room JSON Import** | Small | Export done; import should create semantic rooms rather than address-keyed legacy deltas. |
| 4 | **AreaSave Expansion / Conflict UI** | Small-Medium | Save station spawn editing and cross-area moves safely allocate existing empty slots; table expansion and manual collision resolution remain. |
| 5 | **SMART XML Interop** | Medium | Translate supported SMART project data into the native stateful model without making SMART XML an internal save format. |

### Tier 2: Medium Impact

| # | Feature | Effort | Why |
|---|---------|--------|-----|
| 6 | **Managed ROM Expansion / Shared Allocator** | Medium-Large | Replace independent free-space scanners with one ownership-aware registry, then extend beyond 3MB without invalid pointer or mapper assumptions. |
| 7 | **Palette Blending / FX Tint** | Medium | SNES color math register editing for transparency/blending effects. |
| 8 | **Layer 2/BG Scrolling Hardening** | Medium | Embedded L2 editing exists; still need richer parallax mode, BG pointer, and door-dependent transfer workflows. |
| 9 | **Validation Suite** | Medium | PLM index scanner, door validator, item bitflag checker, GFX limit warnings. |
| 10 | **Auto Item/Door ID Assignment** | Small | Scan all rooms, deduplicate collection bits, sequential ID assignment. |
| 11 | **Room Graph Discovery** | Small | Trace door connections from save stations, find orphaned/disconnected rooms. |

### Tier 3: Backlog

| # | Feature | Effort | Why |
|---|---------|--------|-----|
| 12 | **Projectile Editor** | Medium | Edit projectile behaviors, damage values, graphics. |
| 13 | **Block Grouping (2x1, 1x2, 2x2)** | Small | Grouped destructible blocks that break together with respawn toggles. |
| 14 | **Hotkey Configuration** | Small | Custom keyboard shortcut mapping. |
| 15 | **Samus Pose/Animation Editor** | Large | Configure animation poses per equipment state. |
| 16 | **Color Math Editor** | Medium | SNES Add/Subtract color math registers. |
| 17 | **Plugin System** | Large | Extensibility framework for custom tool integration. |

---

## Shelved / Deferred

- **Advanced state-resource relinking** — The completed editor uses safe copy-on-write for state-local edits. Explicit “link to another state” controls are deferred until a concrete workflow requires intentional re-sharing.
- **State-triggered action authoring** — Conditions select content when a room loads. Typed actions that mutate events or flags during play belong to a separate future Logic & Triggers feature.
- **Legacy behavioral materializer** — Current-format projects are supported; automated conversion of early development-only project behavior is deferred while there are no external consumers.
- **Instant Respawn on Death** — Multiple patch attempts freeze after death animation. Needs deeper investigation.
- **Death Counter** — SRAM persistence for tracking deaths. Low priority.
- **Kill Count Editor** — Enemy kill count byte exposure. Low priority.
