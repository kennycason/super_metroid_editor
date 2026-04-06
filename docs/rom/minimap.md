# Minimap (Pause Screen Map) System

## Overview
The pause screen map shows a 64×32 grid of 8×8 tiles per area. Each tile is a 16-bit word stored in bank $B5.

## Tile Word Format (16-bit LE)
| Bits   | Meaning                              |
|--------|--------------------------------------|
| 0-9    | Tile index (0-1023, typically 0-255) |
| 10-11  | Palette (0=black, 1=blue, 2=white, 3=red) |
| 12-13  | Unused                               |
| 14     | Horizontal flip                      |
| 15     | Vertical flip                        |

## ROM Addresses

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
- **PC offset**: `$D3200`
- **Format**: 2bpp interleaved (Game Boy / SNES format), 16 bytes per 8×8 tile
- **Total**: 256 tiles × 16 bytes = 4096 bytes
- **Layout**: 16×16 grid of tiles. Tile index → `column = idx % 16`, `row = idx / 16`
- **Source**: SMILE reads via `MakeOne8x8_GB &HD3200 + (TileI * &H10)`

## Grid Storage
Each area stores two 32×32 halves:
- Left half: base address, 2048 bytes (32×32 × 2 bytes)
- Right half: base + $0800

Row-major order: `tiles[y * 64 + x]`

## Tile Index Reference
**Verified from actual 2bpp pixel data at ROM $D3200.** Pixel value 2 = border/wall, value 1 = room fill, value 3 = background pattern.

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

## Map Station Reveal Data
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

One bit per tile (2048 tiles = 256 bytes). When the player visits a map station, tiles with their reveal bit set become visible on the pause screen.

## Source Files
- `shared/.../rom/MinimapData.kt` — Data model, tile word encoding, ROM addresses
- `desktopApp/.../ui/MinimapEditor.kt` — Canvas rendering, tile palette, wall drawing
- `desktopApp/.../ui/MinimapEditorState.kt` — Paint/sample/fill tools, undo/redo
