# SMEDIT vs SMILE — Complete Feature Parity Analysis

**Date:** 2026-05-25 (updated)
**SMILE version analyzed:** SMILE RF (VB6, ~42 forms, last updated ~2009)
**SMEDIT version analyzed:** Current main branch

---

## Executive Summary

SMEDIT has **surpassed** SMILE in nearly every dimension. The remaining gaps are primarily advanced/niche features (tileset composition, ROM expansion, Samus pose editing). SMEDIT additionally has capabilities no SNES ROM editor has ever offered: embedded emulator, custom ASM embedding, auto-repointing engine, multi-state export, and visual scroll trigger editing.

---

## 1. AREAS WHERE SMEDIT EXCEEDS SMILE

| Feature | SMEDIT | SMILE |
|---------|--------|-------|
| **Cross-platform** | macOS, Windows, Linux (Kotlin/Compose) | Windows only (VB6) |
| **Embedded emulator** | Full libretro + live room sync | QuickMet (basic, external only) |
| **Boss AI behavior editor** | Phantoon: 30+ parameters | None — hex edit only |
| **Boss stat editor (GUI)** | 6 bosses + 7 mini-bosses | None — hex edit only |
| **Enemy stat editor (GUI)** | 60+ enemies, HP + damage + AI pointers + GFX | None — hex edit only |
| **Custom ASM embedding** | Paste hex bytes → auto-link to free space | Not available |
| **Auto-repointing engine** | Level data, PLMs, scrolls, door ASM | Not available (user manages manually) |
| **Room resize** | With scroll PLM remapping + door ASM generation | Manual with overflow warnings |
| **Scroll trigger PLM editor** | Visual screen grid for scroll commands | Manual hex editing |
| **In-game text editor** | Intro story, area names, escape, UI, item names | Basic text form |
| **Room JSON export** | Self-contained shareable room data | Not available |
| **Room shifting tool** | Selection + arrow keys | Not available |
| **78 ROM patches** | Movement, weapons, QoL, difficulty — all toggleable | None built-in |
| **Config patches** | Ceres timer, beam damage, controller remap — GUI-driven | None |
| **Sprite pixel editor** | ARGB pixel grid for enemies + bosses | Static GIF reference only |
| **Sprite import/export** | PNG I/O with palette quantization | No sprite editing |
| **Boss sprite assembly** | Phantoon (5 blocks), Kraid (full body) | Not available |
| **IPS patch export** | Generate .ips from original vs patched ROM | Full ROM only |
| **Project system** | .smedit JSON with versioned export naming | Single ROM session |
| **Pattern library** | 22 built-in patterns + CRE/URE separation | User patterns only |
| **Undo/redo** | Full operation stack (tiles, PLMs, enemies, doors, scrolls, FX) | Limited tile undo |
| **Custom graphics pipeline** | Base64 tile/sprite overrides in project | Not available |
| **SPC audio playback** | Native SPC emulator, WAV rendering | No audio features |
| **Modern theme system** | Multiple themes, configurable font sizes | Fixed Windows UI |
| **Live emulator sync** | Follow player room in editor | Not available |
| **Boss defeated flags** | GUI toggles with ASM hook generation | Manual hex only |
| **Multi-state export** | Auto-propagates edits across ALL states sharing data | Saves only current state |
| **Door cloning** | Auto-detect direction from screen edge | Manual property entry |
| **Space utilization** | Per-section byte counts for all room data | Level data overflow warning only |

---

## 2. FEATURES AT PARITY ✅

| Feature | Status |
|---------|--------|
| Room tile editing (paint, fill, erase, sample) | Parity |
| LZ5 decompression/compression | Parity |
| Block type system (16 types) | Parity |
| BTS editing (all sub-types) | Parity |
| PLM parsing, display, and placement | Parity |
| Enemy population parsing and placement | Parity |
| Door parsing, display, and property editing | Parity |
| Scroll data editing (Red/Blue/Green) | Parity |
| FX editing (16 types, liquid, blend) | Parity |
| Tile graphics rendering (2bpp/4bpp) | Parity |
| CRE tile handling | Parity |
| Room state parsing (all 9 condition types) | Parity |
| Multi-state room editing (state selector + switching) | Parity |
| LoROM address conversion | Parity |
| Pattern copy/paste | Parity (we have more built-ins) |
| Room header editing (all 11 fields) | Parity |
| Samus physics editor | Parity |
| Palette editor (RGB/HSV, import/export) | Parity |
| Enemy vulnerability editor (22 weapons) | Parity |
| Enemy drop rate editor (6 fields) | Parity |
| Music/song selector | Parity |
| Enemy AI pointer editor | Parity (we also have custom ASM embedding) |
| Enemy graphics/layer priority | Parity |
| Save station spawn display | Parity (read-only; SMILE also limited) |
| Mapshot / save as PNG | Parity |

---

## 3. REMAINING GAPS (SMILE features we still lack)

### Critical

| Feature | SMILE | SMEDIT | Impact |
|---------|-------|--------|--------|
| **Tileset/Metatile Composer** | Define 16x16 from 4 8x8 tiles with palette/flip/BTS | Can view/import/export tile sheets but no composition UI | High — needed for custom tilesets |

### Moderate

| Feature | SMILE | SMEDIT | Impact |
|---------|-------|--------|--------|
| **Layer 2/BG Scrolling Editor** | Parallax modes, BG data pointers, BG tileset selection | Can override bgScrolling value but no visual editor | Medium |
| **Validation Suite** | PLM scanner, door validator, item bitflag checker | Not available | Medium |
| **Samus Pose/Animation Editor** | Per-equipment animation poses | Not available | Low-Medium |
| **Hotkey Configuration** | Custom keyboard shortcuts | Fixed shortcuts | Low |

### Minor / Nice-to-have

| Feature | SMILE | SMEDIT |
|---------|-------|--------|
| ROM data export/import (arbitrary address) | Available | Not available |
| Exception rooms list | Documented | Not available |
| Plugin system | Available | Not available |
| Language/localization | Multiple languages | English only |

---

## 4. FEATURES BEYOND SMILE (SMEDIT + SMART Parity)

These features match or exceed what SMART offers:

| Feature | SMART | SMEDIT |
|---------|-------|--------|
| **Auto-repointing** | All data types | Level data, PLMs, scrolls, door ASM ✅ |
| **Room creation** | Auto-assigned IDs, blank level data | Not yet (foundation exists) |
| **Room resize** | With auto-repoint | With scroll/door ASM remapping ✅ |
| **XML export** | SMART XML format | JSON export (XML interop planned) |
| **Free space management** | Automatic | Backwards scan from bank end ✅ |
| **Room graph discovery** | Save station traversal | Not yet |

---

## 5. SMEDIT-UNIQUE FEATURES

Features that differentiate SMEDIT from existing editors:

1. **Cross-platform** — macOS, Windows, Linux
2. **Embedded emulator** — Live testing without leaving the editor
3. **Boss AI tuning** — Phantoon behavior config with 30+ parameters
4. **78 ROM patches** — One-click gameplay modifications
5. **Custom ASM embedding** — Paste hex → auto-link to free space
6. **Auto-repointing** — Automatic pointer management for level data, PLMs, scrolls, door ASM
7. **Scroll trigger visual editor** — Screen grid UI for scroll commands
8. **Sprite pixel editing** — Direct ARGB editing with PNG I/O
9. **Modern project system** — JSON-based, versioned, multi-file
10. **SPC audio** — Native music playback
11. **Room JSON export** — Shareable room data for collaboration
12. **Multi-state auto-export** — Edits propagate across all states sharing data
