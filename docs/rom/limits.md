# Super Metroid ROM Limits & Constraints

Quick reference for practical editing limits, verified against the ROM format
documentation (`rom_data_format.md`), parser code (`RomParser.kt`), and export
logic (`EditorState.exportToRom()`).

---

## Per-Room Limits

| Resource | Hard limit | Practical limit | Entry size | Notes |
|----------|-----------|-----------------|------------|-------|
| **PLMs** (items, doors, save stations, gates…) | Terminated by `0x0000` | **256** (parser safety cap) | 6 bytes | Total set = `(N × 6) + 2` bytes |
| **Enemies** | Terminated by `0xFFFF` | ~60 per set | 16 bytes | Bank `$A1` free space is the real cap |
| **FX entries** | Terminated by `doorSelect == 0` | **16** (parser safety cap) | 16 bytes | Bank `$83` |
| **Room scrolls** | 1 byte per screen | **50 bytes** (max 50 screens) | 1 byte | Bank `$8F` |
| **Room dimensions** | 0x0F × 0x0F (16 × 16 screens) | **50 screens** max area | — | `width × height ≤ 50` |
| **Door-out entries** | No terminator; count derived from next pointer | ~20 | 2 bytes (ptr) | DDB is 12 bytes in bank `$83` |

## Scroll Values

| Value | Color | Meaning |
|-------|-------|---------|
| `0x00` | Red | Hidden — screen does not scroll into view |
| `0x01` | Blue | Explorable — normal scrolling, revealed on map |
| `0x02` | Green | Show floor — shows the bottom of the screen when entering from above |

Special scroll pointers: `0x0000` = all Blue, `0x0001` = all Green.

## Bank Free Space

These banks are shared across all rooms. Expanding data past its original
footprint requires relocating to free space within the same bank.

| Bank | Range | Size | Contents |
|------|-------|------|----------|
| `$8F` | `$8F8000`–`$8FFFFF` | 32 KB | PLM sets, scroll data, BG data, Main/Setup ASM, door-out tables |
| `$83` | `$838000`–`$83FFFF` | 32 KB | FX entries, door data blocks |
| `$A1` | `$A18000`–`$A1FFFF` | 32 KB | Enemy population sets |
| `$B4` | `$B48000`–`$B4FFFF` | 32 KB | Enemy graphics sets |
| `$C0`–`$CE` | `$xx8000`–`$xxFFFF` each | 32 KB × 15 | Compressed level data (tiles) |

## Export Behavior

When our editor writes data back to ROM:

1. **Fits in place** → overwritten at original location, remainder zeroed.
2. **Grew larger** → relocated to trailing `0xFF` free space in the appropriate bank.
   All room state pointers that referenced the old location are updated.
3. **No free space** → a warning is printed and that room's data is **skipped**.

This applies to: level data (tiles), PLM sets, enemy sets, and (once implemented)
scroll data and FX data.

## Layer 2 / BG Scrolling

The 2-byte value at state data offset +12 controls layer 2 behavior:

| Value | Meaning |
|-------|---------|
| `0x0000` | Layer 2 fixed (no scroll, same as layer 1) |
| `0x0001` | Layer 2 follows layer 1 |
| `0x00xx` / `0xYYxx` | Y/X scroll rates — varies per room |

## FX Types (Layer3Type byte — from SMILE FX1_1.frx)

| Byte | Type | Liquid Physics |
|------|------|---------------|
| `0x00` | None | — |
| `0x02` | Lava | Damage (Varia protects) |
| `0x04` | Acid | Damage (ignores suits) |
| `0x06` | Water | No damage |
| `0x08` | Spores | — |
| `0x0A` | Rain | — |
| `0x0C` | Fog | — |
| `0x0E` | Haze | — |
| `0x10` | Dense Fog | — |
| `0x16` | Firefleas | — |
| `0x18` | Lightning | — |
| `0x1A` | Smoke | — |
| `0x1C` | Heat Shimmer | — |
| `0x20` | Sky Scrolling | — |
| `0x24` | Fireflea FX | — |
| `0x26` | 4 Statues | — |
| `0x28` | Ceres Elevator | — |
| `0x2A` | Ceres Ridley | — |
| `0x2C` | Haze | — |

Liquid physics index: `(fxType & 0xF) >> 1` → 1=Lava, 2=Acid, 3=Water.

### FX Data Structure (16 bytes per entry, from SMILE SmileMod1.bas)

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 2 | doorSelect | 0=default, else door ID that triggers this FX |
| +2 | 2 | liquidSurfaceStart | Initial liquid height (0xFFFF = no liquid) |
| +4 | 2 | liquidSurfaceNew | Target liquid height |
| +6 | 2 | liquidSpeed | Speed of surface movement |
| +8 | 1 | liquidDelay | Delay before liquid moves |
| +9 | 1 | fxType (Layer3Type) | See table above |
| +10 | 1 | fxBitA | Layer rendering control |
| +11 | 1 | fxBitB | Additional layer rendering |
| +12 | 1 | fxBitC | Liquid options bitfield |
| +13 | 1 | paletteFxBitflags | Palette glow toggles |
| +14 | 1 | tileAnimBitflags | Animated tile toggles |
| +15 | 1 | paletteBlend | Palette blend index |

### Liquid Options (fxBitC bitfield)

| Bit | Mask | Name |
|-----|------|------|
| 0 | `0x01` | Small Tide |
| 1 | `0x02` | Large Tide |
| 5 | `0x20` | BG Warp-Line Shift |
| 6 | `0x40` | BG Warp-Cascade Heat |
| 7 | `0x80` | Flow Left |

### Layer 3 Replacement GFX (from SMILE Layer3Editor.frm)

| fxType | ROM PC | Effect |
|--------|--------|--------|
| `0x02` (Lava) | `$3A564` | Replaces tiles 0-3 with lava surface |
| `0x04` (Acid) | `$3A6A4` | Replaces tiles 0-3 with acid surface |
| `0x08` (Spores) | `$3A7E4` | Replaces tiles 0-3 with spore particles |
| `0x0A` (Rain) | `$3A974` | Replaces tiles 0-3 with rain streaks |

## Sources

- `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/RomParser.kt` — parser safety caps
- `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt` — free space scanning + export
- `docs/rom_data_format.md` — byte-level format reference
