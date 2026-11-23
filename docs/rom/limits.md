# Super Metroid ROM Limits & Constraints

Quick reference for practical editing limits, verified against the ROM format
documentation (`rom_data_format.md`), parser code (`RomParser.kt`), and export
logic (`EditorState.exportToRom()`).

---

## Per-Room Limits

| Resource | Hard limit | Practical limit | Entry size | Notes |
|----------|-----------|-----------------|------------|-------|
| **PLMs** (items, doors, save stations, gates…) | Terminated by `0x0000` | **256** (parser safety cap) | 6 bytes | Total set = `(N × 6) + 2` bytes |
| **Enemies** | **32 native slots**, terminated by `0xFFFF` | Leave room for actors spawned by AI | 16 bytes | `$0F78..1777` holds 32 × `$40`-byte runtime slots; the preview parser's 64-record bound is not an engine limit. Authored population includes the trailing kill-quota byte. |
| **FX entries** | Full Default header (`doorSelect == 0`) or 2-byte `$FFFF` stop | **16** (parser/authoring safety cap, not hardware) | 16 bytes | Bank `$83`; runtime-indexed tails need separate ownership |
| **Room scrolls** | 1 byte per screen | **50 bytes** (max 50 screens) | 1 byte | Bank `$8F` |
| **Room dimensions** | 0x0F × 0x0F (16 × 16 screens) | **50 screens** max area | — | `width × height ≤ 50` |
| **Room map rectangle** | 64 × 31 coordinate cells | `x + width ≤ 64`, `y + height ≤ 31` | — | Pause-map storage is 64×32, but its first stored row is engine padding |
| **Door-out entries** | No terminator; count derived from next pointer | ~20 | 2 bytes (ptr) | DDB is 12 bytes in bank `$83` |
| **Save stations per normal area** | PLM parameter is masked with `AND #$0007` | **8 slots** | 14-byte load-station record | Occupied ROM records count even without a `$B76F` PLM; Crateria slot 0 belongs to the gunship |

## Scroll Values

| Value | Color | Meaning |
|-------|-------|---------|
| `0x00` | Red | Hidden — screen does not scroll into view |
| `0x01` | Blue | Normal camera scrolling (not a pause-map reveal flag) |
| `0x02` | Green | Open camera scrolling with a 31-pixel lower vertical clamp offset |

Special scroll pointers, verified at `$82:EB3D-$EB67`: `0x0000` = Green on upper
rows and Blue on the bottom row; `0x0001` = all Green. In a single-row room,
`0x0000` therefore appears all Blue. These are generated presets, not ROM addresses.

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

## Supported ROM Layouts

SMEDIT's editable ROM path currently supports the standard 3 MiB Super Metroid layout:

| Layout | Size | Editing |
|--------|------|---------|
| Standard map, headerless LoROM | `0x300000` bytes | Supported |
| Standard map, 512-byte SMC header + LoROM | `0x300200` bytes | Supported |
| Expanded LoROM | e.g. `0x310000`, `0x400000` | Read-only inspection when room discovery succeeds |

Expanded hacks can be valid in-game and may relocate room headers, state data,
or compressed level data into extra banks. SMEDIT rejects those as editable
because the writer/exporter still assumes the standard room map and bank-local
free-space model. Loading a relocated ROM as
if it were safely editable can corrupt room data on export.

When an expanded ROM is selected, SMEDIT runs a compatibility scan and then tries
to build a ROM-derived room catalog from plausible room headers in bank `$8F`.
If that succeeds, the ROM opens in read-only mode: room listing, door metadata,
tile/collision map rendering, PLM/enemy parsing, and CLI room export can inspect
the ROM without writing changes. Room rendering still uses the normal SMEDIT
renderer. Expanded ROM support adds adapters that discover ROM-specific room and
tileset pointer catalogs, then feed the resolved room headers, level data,
tileset tables, graphics, and palettes through the same pipeline used for
standard-layout ROMs. If discovery fails, SMEDIT reports the compatibility details and
does not load the ROM.

Read-only mode intentionally disables project save/export and editor tabs that
would create writeable room edits. Write support should be added feature by
feature only after the corresponding data family is understood for expanded
layouts.

## Export Behavior

When our editor writes data back to ROM:

1. **Fits in place** → overwritten at original location, remainder zeroed.
2. **Grew larger** → relocated to trailing `0xFF` free space in the appropriate bank.
   All room state pointers that referenced the old location are updated.
3. **No free space** → a warning is printed and that room's data is **skipped**.

This applies to: level data (tiles), PLM sets, enemy sets, and (once implemented)
scroll data and FX data.

## Layer 2 / BG Scrolling

The 2-byte value at state data offset +12 holds two independent motion bytes: X first,
then Y. For either axis, `0x00` follows the camera at 1× speed, `0x01` remains fixed,
and other values move at `(byte & 0xFE) / 256` of camera speed. An odd value also
disables tilemap streaming on that axis.

For example, the Landing Site value `0x0181` decodes as X = 1/2 camera speed with
tilemap streaming disabled, and Y = fixed. This byte behavior is implemented by the
engine's layer 2 position and background-update routines; the value is not a single
enumerated “scroll mode.”

## FX Types (native dispatch at $83:AC18–AC45)

| Byte | Type | Liquid Physics |
|------|------|---------------|
| `0x00` | None | — |
| `0x02` | Lava | Damage (Varia protects) |
| `0x04` | Acid | Damage (ignores suits) |
| `0x06` | Water | No damage |
| `0x08` | Spores | — |
| `0x0A` | Rain | — |
| `0x0C` | Fog | — |
| `0x0E`–`0x1E` (even) | Unused — `RTL` setup handler | Not supported named effects |
| `0x20` | Scrolling Sky | — |
| `0x22` | Scrolling Sky (unused variant) | — |
| `0x24` | Fireflea | — |
| `0x26` | Tourian Statues | — |
| `0x28` | Ceres Ridley | — |
| `0x2A` | Ceres Elevator | — |
| `0x2C` | Ceres Haze | — |

Liquid physics index: `(fxType & 0xF) >> 1` → 1=Lava, 2=Acid, 3=Water.

The native bank-`$83` dispatch uses even **byte offsets**, not sequential indices.
SMEDIT's `NativeFxTypes` drives UI/header names and generated comments from this
table. Historical SMILE labels such as `$18 = Lightning`, `$16 = Firefleas` and
`$0E = Haze` do not name implemented handlers at those slots. An `RTL` setup handler
does not mean every header field is inert: the loader still loads blend/options,
palette and animated-tile values. New source-owned FX accepts only even offsets
`$00-$2C`. Successful assembly of another byte does not prove it is a valid type.

### FX Data Structure (16 bytes per entry)

Verified against `Load_FX_Header` at `$89:AB82-$89:ACC2`. A complete Default
header ends normal lookup. Otherwise the table needs the **two-byte** `$FFFF`
door-word stop; an empty table consists only of that stop. Do not confuse it with
a 16-byte header. Mother Brain has additional runtime-indexed records after
Default; they are guarded from the initial reciprocal FX migration.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 2 | doorSelect | 0=Default, else incoming bank-$83 DoorDef pointer; FFFF=stop (no remaining fields) |
| +2 | 2 | liquidSurfaceStart | Initial liquid height (0xFFFF = no liquid) |
| +4 | 2 | liquidSurfaceNew | Target liquid height |
| +6 | 2 | liquidSpeed | Signed 8.8 surface velocity |
| +8 | 1 | liquidDelay | Frame timer; 00 does not start movement |
| +9 | 1 | fxType (Layer3Type) | See table above |
| +10 | 1 | fxBitA | Layer rendering control |
| +11 | 1 | fxBitB | Additional layer rendering |
| +12 | 1 | fxBitC | Liquid options bitfield |
| +13 | 1 | paletteFxBitflags | Palette glow toggles |
| +14 | 1 | tileAnimBitflags | Animated tile toggles |
| +15 | 1 | paletteBlend | Byte offset into native blend-color tables |

### Liquid Options (fxBitC bitfield)

| Bit | Mask | Name |
|-----|------|------|
| 0 | `0x01` | Flow Left |
| 1 | `0x02` | BG Animate (vertical BG2 shimmer) |
| 2 | `0x04` | BG Liquid (horizontal BG2 shimmer) |
| 3–5 | `0x08`–`0x20` | Unused / unknown |
| 6 | `0x40` | Large Tide (approximately ±16 px) |
| 7 | `0x80` | Small Tide (approximately ±4 px; wins if both tide bits are set) |

These meanings come from the native bank `$88` handlers, not the historical
SMILE labels. See [lava_acid_fx_rendering.md](lava_acid_fx_rendering.md) for the
rendering and tide behavior.

### Layer 3 Replacement GFX

| fxType | Frame 0 ROM PC | BG3 tiles | Effect |
|--------|----------------|-----------|--------|
| `0x02` (Lava) | `$3A564` | `$50`–`$53` | Lava surface/body |
| `0x04` (Acid) | `$3A6A4` | `$50`–`$53` | Acid surface/body |
| `0x08` (Spores) | `$3A7E4` | `$50`–`$52` | Spore particles |
| `0x0A` (Rain) | `$3A874` | `$50`–`$54` | Rain streaks |

These destinations come from the animated-tile objects in bank `$87`. The FX
Layer preview uses frame zero; lava/acid frame animation remains separate from
the BG2 shimmer controlled by `fxBitC`.

## Sources

- `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/RomParser.kt` — parser safety caps
- `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt` — free space scanning + export
- `docs/rom_data_format.md` — byte-level format reference
