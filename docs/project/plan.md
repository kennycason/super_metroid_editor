# SM Editor — SMILE Feature Parity Plan

Gap analysis and implementation plan derived from studying the SMILE (Super Metroid Integrated Level Editor) source code at `~/code/super_metroid/smile/` and comprehensive cross-reference of all features.

**See also:** `smile_parity.md` for the complete feature-by-feature comparison matrix.

---

## Completed Features

### ~~Phase 1: Quick Wins~~ — ALL DONE

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1 | **BTS Sub-Types** | ✅ Done | `btsOptionsForBlockType()` covers all SMILE variants: Crumble (12 options), Spike (5), Grapple (3), Bomb (8), Shot (12), Slope (40+) |
| 2 | **Enemy Drop Rates** | ✅ Done (2026-03-14) | 6-field editor per species, bank $B4 read/write, sum validation |
| 3 | **Music Selector** | ✅ Done | `MusicDropdown` in RoomPropertiesPanel.kt, 35 named tracks from SpcData.kt |
| 4 | **Enemy Names** | ✅ Done | 154 entries in ENEMY_NAMES (RomParser.kt) — exceeds SMILE's 123 |
| 5 | **Enemy Sprites on Map** | ✅ Done | PNG sprites rendered at enemy positions |

### ~~Phase 2: Major New Editors~~ — MOSTLY DONE

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 6 | **Samus Physics Editor** | ✅ Done (2026-03-14) | 17 verified fields across 4 categories (Jump, Gravity, Running, Air Control). Single-byte reads from verified hex_edits.txt addresses. |
| 7 | **Palette Editor** | ✅ Done (2026-03-14) | HSV/RGB color picker, 8x16 grid, undo/redo, eyedropper sampling, save/reset, interop with pixel editor |
| 8 | **Enemy Vulnerability Editor** | ✅ Done (2026-03-14) | 22 weapon slots per species, single-byte reads from bank $B4. SMILE-accurate weapon labels. |
| 9 | **Room Header Editor** | ❌ Not started | Make all 11 header bytes editable |

---

## Remaining Gaps

### CRITICAL (Features SMILE has that we still lack)

#### ~~1. Palette Editor~~ — DONE
Implemented with HSV/RGB color picker, 8x16 grid, undo/redo, eyedropper, save to project, reset to ROM. Integrated with pixel editor.

#### 2. Room Header Editor (Currently Read-Only)
SMILE edits: room index, area assignment, minimap X/Y, dimensions, up/down scrollers, CRE flag, door-out pointer.
**Data:** 11-byte room header in bank $8F.
**Plan:** Make all header fields editable in room properties panel.

#### 3. Multi-State Room Editing (Full)
SMILE fully edits all data per room state: tiles, enemies, PLMs, scrolls, music, FX, BG. All 9 condition types.
**Current:** We parse all states and can select them, but only default state tiles/enemies are editable.
**Plan:** Per-state enemy/PLM/scroll/FX editing. Major architectural work.

#### 4. Tileset/Metatile Composer
SMILE defines 16x16 metatiles from 4 8x8 tiles with palette assignment, flip controls, BTS assignment per sub-tile.
**Current:** We can view and import/export tile sheets but can't compose metatiles.
**Plan:** Dedicated metatile editor with 4-quadrant sub-tile picker, palette row selector, flip toggles.

---

### MODERATE GAPS

#### 5. Door Configuration Editing
We can edit door properties but lack SMILE's auto-clone tool (click screen edge → auto-calculate all properties).
**Plan:** Add "Clone Door" button that derives cap/spawn/direction from visual placement.

#### 6. Layer 2/BG Scrolling Editor
SMILE edits parallax modes (fixed, follow, custom rates), BG data pointers, BG tileset selection.
**Current:** Can override bgScrolling value but no visual editor.
**Plan:** Parallax mode selector + BG pointer in room properties.

#### 7. Save Station Spawn Point Editor
SMILE has dedicated spawn X/Y/screen configuration per save station.
**Plan:** Expose in PLM param editor when save station PLM is selected.

---

### VALIDATION GAPS (SMILE safety checks we lack)

| Validation | Description | Priority |
|---|---|---|
| PLM Index Scanner | Find duplicate Main PLM Variables across rooms | Medium |
| Door Consistency Validator | Verify destinations exist, coordinates valid | Medium |
| Item Bitflag Uniqueness | Ensure no two items share collection bit | Medium |
| Enemy GFX Limit Warning | Warn when room exceeds 4-entry hardware limit | Medium |
| Free Space Tracker | Monitor available bytes in shared banks | Low |
| Room State Validator | Check state conditions are sensible | Low |

---

## Implementation Priority (Remaining Work)

### Next Up: High Impact
1. ~~**Palette Editor**~~ — ✅ Done
2. **Room Header Editor** — Needed for creating new rooms, area reassignment

### Medium Term: Infrastructure
3. **Multi-State Room Editing** — Essential for boss rooms and event progression
4. **Tileset/Metatile Composer** — Enables truly custom tilesets
5. **Validation Suite** — PLM scanner, door validator, GFX limit warnings

### Longer Term: Expansion
6. **Door Expansion** — Free space tracking in bank $83 to support adding new door connections
7. **New Room Creation** — Allocate room headers in $8F, extend room pointer table, allocate all associated data (PLMs, enemies, level data, scrolls)
8. **ROM Expansion** — Extend ROM beyond 3MB (HiROM) to eliminate free space constraints
9. **Free Space Map** — Track all allocated/free regions across the ROM, reclaim space from relocated data

### Polish
10. **Door Cloning Tool** — Auto-calculate from screen edge click
11. **Layer 2/BG Editor** — Parallax modes + BG pointer
12. **Save Station Spawn Editor** — Fine-tune spawn coordinates

---

## Reference Data

### SMILE File Locations
- Enemy definitions: `~/code/super_metroid/smile/files/Enemies/*.txt`
- Enemy sprites: `~/code/super_metroid/smile/files/Enemies/*.GIF`
- PLM definitions: `~/code/super_metroid/smile/files/PLM/*.txt`
- PLM images: `~/code/super_metroid/smile/files/PLM/*.gif`
- Source code: `~/code/super_metroid/smile/source/*.frm`, `*.bas`

### Key SMILE Source Files
- `Smile.frm` (5067 lines) — Main editor, BTS menu, tile editing
- `DoorForm1.frm` — Door editor (9 properties + clone)
- `PLMForm.frm` — PLM placement and editing
- `SamusForm.frm` / `SamusForm2.frm` — Physics editor
- `Palette1.frm` — Palette editor
- `VulnerabilitiesForm1.frm` — Enemy resistances
- `SpeciesForm.frm` — Enemy species global editor
