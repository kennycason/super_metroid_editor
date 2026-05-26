# SM Editor — Feature Parity Plan

Gap analysis and implementation plan derived from studying SMILE, SMART, and the SM disassembly.

**See also:** `smile_parity.md` for the complete feature-by-feature comparison matrix.
**See also:** `roadmap.md` for the prioritized feature list.

---

## Completed Features

### Phase 1: Quick Wins — ALL DONE

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1 | **BTS Sub-Types** | ✅ Done | All SMILE variants: Crumble (12), Spike (5), Grapple (3), Bomb (8), Shot (12), Slope (40+) |
| 2 | **Enemy Drop Rates** | ✅ Done | 6-field editor per species, bank $B4 |
| 3 | **Music Selector** | ✅ Done | 35 named tracks in room properties |
| 4 | **Enemy Names** | ✅ Done | 154 entries (exceeds SMILE's 123) |
| 5 | **Enemy Sprites on Map** | ✅ Done | PNG sprites at enemy positions |

### Phase 2: Major Editors — ALL DONE

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 6 | **Samus Physics Editor** | ✅ Done | 17 fields across 4 categories |
| 7 | **Palette Editor** | ✅ Done | HSV/RGB picker, 8x16 grid, import/export |
| 8 | **Enemy Vulnerability Editor** | ✅ Done | 22 weapon slots per species |
| 9 | **Room Header Editor** | ✅ Done | All 11 fields editable, minimap links to Map tab |

### Phase 3: Infrastructure — ALL DONE

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 10 | **Mapshot / Save as PNG** | ✅ Done | Export button on canvas toolbar |
| 11 | **In-Game Text Editor** | ✅ Done | Intro story, area names, escape msgs, UI msgs, item pickup names |
| 12 | **Enemy AI Pointer Editor** | ✅ Done | All 8 AI fields + custom ASM embedding |
| 13 | **Enemy Graphics/Layer Priority** | ✅ Done | Tile data, layer control, extra GFX |
| 14 | **Save Station Spawn Display** | ✅ Done | Read-only spawn X/Y/scroll in tile properties |
| 15 | **Auto-Repointing Engine** | ✅ Done | Level data, PLMs, scroll data, door ASM — all auto-relocate |
| 16 | **Room JSON Export** | ✅ Done | Self-contained room data with PNG/JSON dropdown |
| 17 | **Multi-State Room Editing** | ✅ Done | State selector, switching, per-state enemies/PLMs/scrolls/export |
| 18 | **Door Cloning Tool** | ✅ Done | Auto-detect direction from screen edge |
| 19 | **Space Utilization Monitor** | ✅ Done | Per-section byte counts in Room Info |
| 20 | **Room Resize** | ✅ Done | Level data + scroll + door ASM remapping |
| 21 | **Room Shifting Tool** | ✅ Done | Selection + arrow keys, Ctrl for screen-step |
| 22 | **Scroll Trigger PLM Editor** | ✅ Done | Visual screen grid for scroll commands |
| 23 | **Custom ASM Embedding** | ✅ Done | Hex bytes → free space + auto-link pointer |
| 24 | **Enemy/Boss Top-Level Tabs** | ✅ Done | Promoted from Patches to dedicated tabs |
| 25 | **TestRomHelper Migration** | ✅ Done | 73 test files, eliminated hardcoded ROM paths |

---

## Remaining Gaps

### HIGH IMPACT — Next Up

| # | Feature | Effort | Notes |
|---|---------|--------|-------|
| 1 | **Tileset/Metatile Composer** | Large | Define 16x16 metatiles from 4 8x8 tiles. Per sub-tile palette/flip/BTS. Enables truly custom tilesets. |
| 2 | **New Room Creation** | Medium | Allocate room header in $8F, door table, level data, enemy/PLM/scroll pointers. Foundation is there (auto-repointing, resize). |
| 3 | **ROM Expansion** | Medium | Extend beyond 3MB (HiROM) to eliminate free space constraints. |
| 4 | **Room JSON Import** | Small | Export done; import needs to create RoomEdits from JSON. |
| 5 | **Save Station Spawn Editing** | Small | Currently display-only. Need writable fields + export. |

### MEDIUM IMPACT

| # | Feature | Effort | Notes |
|---|---------|--------|-------|
| 6 | **Palette Blending / FX Tint Editor** | Medium | SNES color math register editing for transparency/blending. |
| 7 | **Layer 2/BG Scrolling Editor** | Medium | Parallax mode selector + BG pointer editing. |
| 8 | **SMART XML Interop** | Medium | Export rooms in SMART XML format for Map Randomizer compatibility. |
| 9 | **Validation Suite** | Medium | PLM scanner, door validator, item bitflag checker, GFX limit warnings. |
| 10 | **Auto Item/Door ID Assignment** | Small | Scan rooms, deduplicate collection bits. |
| 11 | **Room Graph Discovery** | Small | Trace door connections, find orphaned rooms. |

### LOWER IMPACT — Backlog

| # | Feature | Effort | Notes |
|---|---------|--------|-------|
| 12 | **Projectile Editor** | Medium | Edit projectile behaviors, damage values, graphics. |
| 13 | **Block Grouping (2x1, 1x2, 2x2)** | Small | Grouped destructible blocks with respawn toggles. |
| 14 | **Hotkey Configuration** | Small | Custom keyboard shortcuts. |
| 15 | **Samus Pose/Animation Editor** | Large | Per-equipment animation poses. |
| 16 | **Color Math / Add-Subtract Editor** | Medium | SNES color math registers for transparency. |
| 17 | **New Room Creation/Deletion** | Medium | Blank rooms with auto-assigned IDs, copy/paste between areas. |
| 18 | **Plugin System** | Large | Extensibility framework for custom tools. |

---

## Reference Data

### SMILE File Locations
- Enemy definitions: `~/code/super_metroid/smile/files/Enemies/*.txt`
- Enemy sprites: `~/code/super_metroid/smile/files/Enemies/*.GIF`
- PLM definitions: `~/code/super_metroid/smile/files/PLM/*.txt`
- Source code: `~/code/super_metroid/smile/source/*.frm`, `*.bas`

### Key SMILE Source Files
- `Smile.frm` (5067 lines) — Main editor, BTS menu, tile editing
- `DoorForm1.frm` — Door editor (9 properties + clone)
- `SamusForm.frm` / `SamusForm2.frm` — Physics editor
- `Palette1.frm` — Palette editor
- `VulnerabilitiesForm1.frm` — Enemy resistances
- `SpeciesForm.frm` — Enemy species global editor
- `TextForm1.frm` — Text editor (intro, area names, escape, items)
- `States1.frm` — Room state selector
- `LoadPoints1.frm` — Save station spawn editor
