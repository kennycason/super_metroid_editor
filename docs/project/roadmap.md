# Roadmap

**See also:** `smile_parity.md` for complete SMILE vs SMEDIT feature comparison.
**See also:** `plan.md` for detailed implementation notes per feature.
**Last updated:** 2026-05-25

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
- Multi-State Room Editing — State selector, switching, per-state enemies/PLMs/scrolls
- Scroll Trigger PLM Editor — Visual screen grid for scroll commands
- Door Cloning Tool — Auto-detect direction from screen edge
- Space Utilization Monitor — Per-section byte counts in Room Info
- Auto-Repointing Engine — Level data, PLMs, scroll data, door ASM auto-relocate

### Text & Data
- In-Game Text Editor — Intro story (6 parts), area names (7), escape messages (2), UI messages (9), item pickup names (19)
- Mapshot / Save as PNG — Export button on canvas toolbar
- Room JSON Export — Self-contained room data with PNG/JSON dropdown
- Custom ASM Embedding — Hex bytes → free space + auto-link pointer

### Infrastructure
- Enemy/Boss promoted to top-level tabs
- Save Station Spawn Display — Read-only in tile properties
- TestRomHelper migration — 73 test files, eliminated hardcoded ROM paths
- FlowRow tab navigation — Tabs wrap when column is narrow
- Minimap Room Move — Buffer-based with Apply/Cancel

---

## Remaining — Prioritized

### Tier 1: High Impact, Next Up

| # | Feature | Effort | Why |
|---|---------|--------|-----|
| 1 | **Tileset/Metatile Composer** | Large | Define 16x16 metatiles from 4 8x8 tiles with palette/flip per sub-tile. Enables truly custom tilesets. |
| 2 | **New Room Creation** | Medium | Allocate room header in $8F, door table, level data, enemy/PLM/scroll pointers. Auto-repointing foundation already exists. |
| 3 | **Room JSON Import** | Small | Export done; import creates RoomEdits from JSON file. |
| 4 | **Save Station Spawn Editing** | Small | Currently display-only. Need writable fields + export to AreaSave table. |
| 5 | **SMART XML Interop** | Medium | Export rooms in SMART XML format. Plugs into Map Randomizer ecosystem — no other modern editor has this. |

### Tier 2: Medium Impact

| # | Feature | Effort | Why |
|---|---------|--------|-----|
| 6 | **ROM Expansion** | Medium | Extend beyond 3MB (HiROM) to eliminate free space constraints. |
| 7 | **Palette Blending / FX Tint** | Medium | SNES color math register editing for transparency/blending effects. |
| 8 | **Layer 2/BG Scrolling Editor** | Medium | Parallax mode selector + BG data pointer editing. |
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
| 17 | **Room Creation/Deletion** | Medium | Blank rooms with auto-assigned IDs, copy/paste between areas. |
| 18 | **Plugin System** | Large | Extensibility framework for custom tool integration. |

---

## Shelved / Deferred

- **Instant Respawn on Death** — Multiple patch attempts freeze after death animation. Needs deeper investigation.
- **Death Counter** — SRAM persistence for tracking deaths. Low priority.
- **Kill Count Editor** — Enemy kill count byte exposure. Low priority.
