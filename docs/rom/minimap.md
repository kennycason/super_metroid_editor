# Minimap (Pause Screen Map) System

## Overview

The pause screen map stores a 64×32 grid of 8×8 tiles per area in bank `$B5`.
It is physically two 32×32 SNES BG pages. The first stored row of each page is
the engine's fake/padding row: room map coordinate `(0,0)` begins on stored row 1.
Consequently, room rectangles have 64×31 usable coordinates (`x=0..63`,
`y=0..30`), while SMEDIT preserves all 64×32 stored cells so the ROM can round-trip
without losing the padding row.

## Tile Word Format (16-bit LE)
| Bits   | Meaning                              |
|--------|--------------------------------------|
| 0-9    | Tile index (0-1023, typically 0-255) |
| 10-12  | SNES palette index (0-7)             |
| 13     | BG priority                          |
| 14     | Horizontal flip                      |
| 15     | Vertical flip                        |

## ROM Addresses

### Pointer Tables

- `AreaMapPointers` at `$82:964A` contains seven 24-bit tilemap pointers.
- `MapData.pointers` at `$82:9717` contains eight bank-`$82` reveal-mask
  pointers. Areas 0-6 are unique; debug area 7 intentionally aliases Tourian.

Production parsing follows these runtime tables. The vanilla targets are:

### Tilemap Data (SNES → PC)
| Area         | SNES     | PC       |
|-------------|----------|----------|
| Crateria    | $B5:9000 | $1A9000  |
| Brinstar    | $B5:8000 | $1A8000  |
| Norfair     | $B5:A000 | $1AA000  |
| Wrecked Ship| $B5:B000 | $1AB000  |
| Maridia     | $B5:C000 | $1AC000  |
| Tourian     | $B5:D000 | $1AD000  |
| Ceres       | $B5:E000 | $1AE000  |

Note: Crateria and Brinstar indices are swapped vs area numbering.

### Tile Graphics

- **Source asset**: `Tiles_PauseScreen_BG1_BG2`
- **SNES / PC start**: `$B6:8000` / `$1B0000`
- **Format**: standard SNES 4bpp, 32 bytes per 8×8 tile
- **Map portion**: first `$2000` bytes = 256 tiles
- **Whole shared asset**: `$4000` bytes; the remaining half is other pause-screen art
- **Layout**: 16×16 grid of tiles. Tile index → `column = idx % 16`, `row = idx / 16`

## Grid Storage
Each area stores two 32×32 pages:
- Left half: base address, 2048 bytes (32×32 × 2 bytes)
- Right half: base + $0800

SMEDIT's logical row-major array uses `tiles[y * 64 + x]`. Conversion to the ROM
is exact:

```text
page      = x / 32
localX    = x % 32
storageY  = (y + 1) % 32
wordIndex = page * 1024 + storageY * 32 + localX
```

Logical row 31 represents the preserved padding row. A room must never extend into
it: the exploration routine adds one to room Y without wrapping, so a room screen
at Y=31 would write into the next page or beyond the 256-byte explored-map buffer.

## Tile Index Reference
**Verified from the source-owned standard 4bpp pixels at `$B6:8000`.** Pixel
indices are interpreted through the pause-screen palette selected by the tile word.

### Basic Tiles
| Index | Walls | Uses | Pixel pattern |
|-------|-------|------|---------------|
| 0x1F  | —     | bg   | Background checker (palette-dependent) |
| 0x1B  | none  | 62   | Solid fill (open room, no walls) |

### Single Wall
| Index | Walls | Uses | Notes |
|-------|-------|------|-------|
| 0x26  | T     | —    | Top only |
| 0x27  | R     | 67   | Right only |
| 0x5F  | B     | 2    | Bottom only (rare) |

### Two Walls
| Index | Walls | Uses | Notes |
|-------|-------|------|-------|
| 0x22  | T+B   | 197  | Horizontal corridor |
| 0x23  | L+R   | 110  | Vertical shaft |
| 0x25  | T+L   | 185  | Corner |
| 0x10  | L+R   | 5    | Shaft variant (wider bottom) |
| 0x5E  | T+B   | 4    | Corridor variant (center dot) |
| 0x8E  | T+L   | 21   | Corner variant (center dot) |

### Three Walls
| Index | Walls  | Uses | Notes |
|-------|--------|------|-------|
| 0x21  | T+B+L  | 202  | Open right |
| 0x24  | T+L+R  | 104  | Open bottom |
| 0x4F  | T+L+R  | 7    | Shaft cap style |
| 0x6E  | T+L+R  | 8    | Center dot variant |
| 0x8F  | T+B+L  | 24   | Center dot variant |

### Four Walls
| Index | Walls   | Uses | Notes |
|-------|---------|------|-------|
| 0x20  | T+B+L+R | 22   | Fully enclosed |
| 0x4D  | T+B+L+R | 19   | Zigzag inner pattern |
| 0x6F  | T+B+L+R | 43   | Center dot pattern |

### Diagonal Transitions (no walls)
| Index | Pattern | Uses | Notes |
|-------|---------|------|-------|
| 0x28  | BL fill | 4    | Diagonal: fill bottom-left |
| 0x29  | TL fill | 4    | Diagonal: fill top-left |
| 0x2A  | BR fill | 4    | Diagonal: fill bottom-right |
| 0x2B  | TR fill | 4    | Diagonal: fill top-right |
| 0x6D  | checker | 5    | Background checker pattern |

### Elevator & Special
| Index | Walls | Uses | Notes |
|-------|-------|------|-------|
| 0xCE  | none  | 30   | Elevator glyph (alternating lines) |
| 0x11  | none  | 11   | Shaft cap arrow (down-pointing) |

### Item Tiles (room + gold center dot)
| Index | Walls | Uses | Notes |
|-------|-------|------|-------|
| 0x76  | T     | 3    | Item with top wall |
| 0x77  | L     | 4    | Item with left wall |
| 0x78-0x7E | varies | 0 | Not used in vanilla |
| 0x80  | T+B+L+R | 0 | Not used in vanilla |

### Station Tiles (room + colored dot)
| Index | Color  | Uses | Notes |
|-------|--------|------|-------|
| 0x44  | Green  | 0    | Save station (runtime) |
| 0x46  | Cyan   | 0    | Map station (runtime) |
| 0x48  | Orange | 0    | Energy station (runtime) |
| 0x4A  | Red    | 0    | Missile station (runtime) |

### Door Arrow Tiles
| Index | Direction | Uses | Notes |
|-------|-----------|------|-------|
| 0x02  | ↓         | 0    | Generated at runtime |
| 0x03  | ↑         | 0    | Generated at runtime |
| 0x04  | →         | 0    | Generated at runtime |
| 0x05  | ←         | 0    | Generated at runtime |

## Flip Bit Usage
The vanilla game uses H-flip and V-flip to create additional orientations:
- `0x25` (T+L corner) + hflip → T+R corner
- `0x25` (T+L corner) + vflip → B+L corner
- `0x21` (T+B+L) + hflip → T+B+R
- `0x22` (T+B corridor) + vflip → same (symmetric)

**Rendering rule**: H-flip swaps left↔right walls, V-flip swaps top↔bottom walls.

## Map Data / Map Station Reveal Masks

Each area has 256 bytes of bitpacked reveal flags at bank $82:
| Area         | SNES     | PC      |
|-------------|----------|---------|
| Crateria    | $82:9727 | $11727  |
| Brinstar    | $82:9827 | $11827  |
| Norfair     | $82:9927 | $11927  |
| Wrecked Ship| $82:9A27 | $11A27  |
| Maridia     | $82:9B27 | $11B27  |
| Tourian     | $82:9C27 | $11C27  |
| Ceres       | $82:9D27 | $11D27  |

The mask has the same two-page, 32-row layout as the tilemap: `$80` bytes per
page, four bytes per row, and the most-significant bit is the leftmost tile. For a
logical coordinate:

```text
page       = x / 32
localX     = x % 32
storageY   = (y + 1) % 32
byteOffset = page * $80 + storageY * 4 + localX / 8
mask       = $80 >> (localX & 7)
```

When the area's map-station flag is set, the pause-map loader combines this static
mask with the player's explored-map bits. The map-station PLM `$B6D3` activates the
flag; it does not carry a private reveal rectangle. Vanilla has exactly five such
placements: one each in Crateria, Brinstar, Norfair, Wrecked Ship, and Maridia.
Tourian and Ceres have mask data but no vanilla map-station placement.

## Source-Verified Runtime Consumers

- `$80:858C` `LoadMirrorOfCurrentAreasMapExplored`
- `$80:85C6` `MirrorCurrentAreasMapExplored`
- `$82:943D` `LoadPauseMenuMapTilemap`
- `$82:9517` `DrawRoomSelectMap`
- `$84:8C8F` `Instruction_PLM_Activate_MapStation`
- `$84:B18B` `Setup_MapStation`
- `$90:A8A6` `MarkMapTilesExplored`

`parityMinimap` inventories these consumers, all seven area tilemaps, all seven
unique reveal masks plus the debug alias, the five PLM placements, the graphics,
and the coordinate transforms. Tagged production tests compare every tile/mask
cell and perform exact no-op write round trips.

## Source Files
- `shared/.../rom/MinimapData.kt` — Data model, tile word encoding, ROM addresses
- `shared/.../rom/RomParser.kt` — Pointer-driven tilemap/mask parsing and writing
- `desktopApp/.../ui/MinimapEditor.kt` — Canvas rendering, tile palette, wall drawing
- `desktopApp/.../ui/MinimapEditorState.kt` — Paint/sample/fill tools, undo/redo
- `parity/minimap_manifest.py` — Independent source/ROM oracle
