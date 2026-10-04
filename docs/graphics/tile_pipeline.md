# Super Metroid Tile Graphics Pipeline: Complete Technical Reference

## Overview: ROM Data → Pixels on Screen

```
Room State Header (26 bytes in bank $8F)
  ├── byte +3: Tileset index (0-28) ──→ Tileset Table at $8F:E6A2
  │     ├── bytes 0-2: Tile Table pointer   (compressed metatile defs)
  │     ├── bytes 3-5: Tiles/GFX pointer    (compressed 4bpp 8x8 tiles)
  │     └── bytes 6-8: Palette pointer      (tileset palette data)
  │
  ├── bytes +0..+2: Level data pointer ──→ Compressed level data
  │     Each 16x16 block = 16-bit word:
  │       Bits 15-12: Block type (air/solid/door/slope/etc)
  │       Bits 11-10: H-flip, V-flip  
  │       Bits 9-0:   Metatile index (0-1023)
  │
  └── CRE (Common Room Elements) ──→ Fixed addresses
        ├── CRE 8x8 GFX:     $B9:8000 (SNES) = $1C8000 (PC)
        └── CRE Tile Table:   $B9:A09D (SNES) = $1CA09D (PC)
```

---

## 1. Tileset Pointer Table at `$8F:E6A2`

### Location
- **SNES address**: `$8F:E6A2`
- **PC file offset**: `$07E6A2` (unheadered), `$07E8A2` (headered, +$200)
- **P.JBoy label**: `$8F:E6A2: Tileset table`

### Format: 29 entries × 9 bytes = 261 ($105) bytes

Each entry is three consecutive 3-byte **little-endian SNES LoROM pointers**:

```
Offset  Size  Description
------  ----  -----------
+0      3     Tile Table pointer     → compressed metatile definitions
+3      3     Tiles/GFX pointer      → compressed 4bpp 8x8 tile graphics
+6      3     Palette pointer        → tileset color palette (may be compressed)
```

### How SMILE reads this (from `UGraphics.bas`):

```vb
' Returns the PC file offset of the 9-byte entry for a given graphics set (1-indexed)
Public Function GetAddressOfGSP(GraphicsSet)
    Dim Start
    Start = &H7E6A2                                ' PC offset of tileset table
    Address = (Start) + ((GraphicsSet - 1) * 9)    ' 9 bytes per entry
    GetAddressOfGSP = Address + ROM_HEADER          ' +$200 for headered ROM
End Function

' Reads all 29 entries into an array of 87 addresses (29 × 3)
Public Sub ReadGraphicsSetPointers(OutputArray())
    ReDim OutputArray(1 To 87)
    Dim TempBytes(0 To 8) As Byte
    Counter = 1
    While Counter < 30
        Address = GetAddressOfGSP(Counter)
        LunarReadFile TempBytes(0), 9, Address, LC_SEEK

        ' Bytes are little-endian in ROM, reassemble as big-endian hex strings
        TableString   = PadHex(TempBytes(2)) & PadHex(TempBytes(1)) & PadHex(TempBytes(0))
        TileString    = PadHex(TempBytes(5)) & PadHex(TempBytes(4)) & PadHex(TempBytes(3))
        PaletteString = PadHex(TempBytes(8)) & PadHex(TempBytes(7)) & PadHex(TempBytes(6))

        ArrayIndex = (Counter * 3) - 2
        OutputArray(ArrayIndex)     = SnesToHex(TableString)    ' Tile table PC offset
        OutputArray(ArrayIndex + 1) = SnesToHex(TileString)     ' GFX tiles PC offset
        OutputArray(ArrayIndex + 2) = SnesToHex(PaletteString)  ' Palette PC offset
        Counter = Counter + 1
    Wend
End Sub
```

### How the room state references this

The **room state** (mdb_roomstate) is 26 bytes. From Kejardon's `mdb_format.txt`:

```
Offset  Size  Field
------  ----  -----
+0      3     Level data pointer (compressed room map) [any bank]
+3      1     Graphics set to use (tileset index, 0-28)
+4      1     Music data index
+5      1     Music track index
+6      2     FX1 pointer [$83]
+8      2     Enemy population pointer [$a1]
+10     2     Enemy tileset pointer [$b4]
+12     2     Layer 2 scrolling data
+14     2     Scroll pointer [$8f]
+16     2     Unused
+18     2     Special FX pointer (sprites) [$8f]
+20     2     PLM population pointer [$8f]
+22     2     BG data pointer [$8f]
+24     2     Layer 1/2 handling pointer [$8f]
```

The **byte at offset +3** is the tileset/graphics set index. This indexes into
the table at `$8F:E6A2` to retrieve the three pointers for the tile table,
8×8 graphics, and palette.

### Table boundaries

The table spans from `$8F:E6A2` to `$8F:E7A6` (29 × 9 = 261 bytes).
Immediately following at `$8F:E7A7` is a secondary table P.JBoy labels
"Tileset pointers" (29 × 2 = 58 bytes of 16-bit within-bank pointers).

### Source-owned pointer manifest (verified 2026-10-03)

The exact source labels below are the canonical mapping. Repeated labels are
intentional aliases, not missing assets. Across the 87 fields there are 55 unique
compressed payloads: 14 metatile tables, 16 graphics sets, and 25 palettes.

| ID | Tileset | Metatile-table asset | Graphics asset | Palette asset |
|---:|---|---|---|---|
| `$00` | Upper Crateria | `TileTables_0_1_UpperCrateria` | `Tiles_0_1_UpperCrateria` | `Palettes_0_UpperCrateria` |
| `$01` | Red Crateria | `TileTables_0_1_UpperCrateria` | `Tiles_0_1_UpperCrateria` | `Palettes_1_RedCrateria` |
| `$02` | Lower Crateria | `TileTables_2_3_LowerCrateria` | `Tiles_2_3_LowerCrateria` | `Palettes_2_LowerCrateria` |
| `$03` | Old Tourian | `TileTables_2_3_LowerCrateria` | `Tiles_2_3_LowerCrateria` | `Palettes_3_OldTourian` |
| `$04` | Wrecked Ship — power on | `TileTables_4_5_WreckedShip` | `Tiles_4_5_WreckedShip` | `Palettes_4_WreckedShip_PowerOn` |
| `$05` | Wrecked Ship — power off | `TileTables_4_5_WreckedShip` | `Tiles_4_5_WreckedShip` | `Palettes_5_WreckedShip_PowerOff` |
| `$06` | Green/blue Brinstar | `TileTables_6_GreenBlueBrinstar` | `Tiles_6_GreenBlueBrinstar` | `Palettes_6_GreenBlueBrinstar` |
| `$07` | Red Brinstar / Kraid's lair | `TileTables_7_8_RedBrinstar_Kraid_StatuesHall` | `Tiles_7_8_RedBrinstar_Kraid_StatuesHall` | `Palettes_7_RedBrinstar_Kraid` |
| `$08` | Pre-Tourian entrance corridor | `TileTables_7_8_RedBrinstar_Kraid_StatuesHall` | `Tiles_7_8_RedBrinstar_Kraid_StatuesHall` | `Palettes_8_StatuesHall` |
| `$09` | Heated Norfair | `TileTables_9_A_Norfair` | `Tiles_9_A_Norfair` | `Palettes_9_HeatedNorfair` |
| `$0A` | Unheated Norfair | `TileTables_9_A_Norfair` | `Tiles_9_A_Norfair` | `Palettes_A_UnheatedNorfair` |
| `$0B` | Sandless Maridia | `TileTables_B_SandlessMaridia` | `Tiles_B_SandlessMaridia` | `Palettes_B_SandlessMaridia` |
| `$0C` | Sandy Maridia | `TileTables_C_SandyMaridia` | `Tiles_C_SandyMaridia` | `Palettes_C_SandyMaridia` |
| `$0D` | Tourian | `TileTables_D_E_Tourian` | `Tiles_D_E_Tourian` | `Palettes_D_Tourian` |
| `$0E` | Mother Brain's room | `TileTables_D_E_Tourian` | `Tiles_D_E_Tourian` | `Palettes_E_MotherBrain` |
| `$0F` | Blue Ceres | `TileTables_F_10_11_12_13_14_Ceres` | `Tiles_F_10_Ceres` | `Palettes_F_11_13_BlueCeres` |
| `$10` | White Ceres | `TileTables_F_10_11_12_13_14_Ceres` | `Tiles_F_10_Ceres` | `Palettes_10_12_14_WhiteCeres` |
| `$11` | Blue Ceres elevator | `TileTables_F_10_11_12_13_14_Ceres` | `Tiles_11_12_CeresElevator` | `Palettes_F_11_13_BlueCeres` |
| `$12` | White Ceres elevator | `TileTables_F_10_11_12_13_14_Ceres` | `Tiles_11_12_CeresElevator` | `Palettes_10_12_14_WhiteCeres` |
| `$13` | Blue Ceres Ridley's room | `TileTables_F_10_11_12_13_14_Ceres` | `Tiles_13_14_CeresRidley` | `Palettes_F_11_13_BlueCeres` |
| `$14` | White Ceres Ridley's room | `TileTables_F_10_11_12_13_14_Ceres` | `Tiles_13_14_CeresRidley` | `Palettes_10_12_14_WhiteCeres` |
| `$15` | Map room / Tourian entrance | `TileTables_15_16_17_18_19_UtilityRoom_Statues` | `Tiles_15_16_17_18_19_UtilityRoom_Statues` | `Palettes_Map_Statues` |
| `$16` | Wrecked Ship map — power off | `TileTables_15_16_17_18_19_UtilityRoom_Statues` | `Tiles_15_16_17_18_19_UtilityRoom_Statues` | `Palettes_16_WreckedShipMap_PowerOff` |
| `$17` | Blue refill room | `TileTables_15_16_17_18_19_UtilityRoom_Statues` | `Tiles_15_16_17_18_19_UtilityRoom_Statues` | `Palettes_17_BlueRefill` |
| `$18` | Yellow refill room | `TileTables_15_16_17_18_19_UtilityRoom_Statues` | `Tiles_15_16_17_18_19_UtilityRoom_Statues` | `Palettes_18_YellowRefill` |
| `$19` | Save room | `TileTables_15_16_17_18_19_UtilityRoom_Statues` | `Tiles_15_16_17_18_19_UtilityRoom_Statues` | `Palettes_19_SaveStation` |
| `$1A` | Kraid's room | `TileTables_1A_Kraid` | `Tiles_1A_Kraid` | `Palettes_1A_Kraid` |
| `$1B` | Crocomire's room | `TileTables_1B_Crocomire` | `Tiles_1B_Crocomire` | `Palettes_1B_Crocomire` |
| `$1C` | Draygon's room | `TileTables_1C_Draygon` | `Tiles_1C_Draygon` | `Palettes_1C_Draygon` |

There are 8 aliased metatile-table groups, 10 aliased graphics groups, and 2
aliased palette groups. `parityTilesets` derives those groups from source and writes
their complete membership to ignored `parity/reports/tilesets.json`; the committed
counts in `parity/reference.properties` make an unreviewed alias change fail parity.
SMEDIT export uses copy-on-write, so editing one member can repoint only that entry
without mutating the source shared by its siblings.

---

## 2. Metatile Table (Tile Table) — Exact Byte Layout

### Overview

The tile table defines **1024 metatiles** (16×16 pixel blocks). Each metatile
is composed of **4 sub-tiles** (8×8 pixels each), arranged in a 2×2 grid.

- **Entries**: 1024 (indices $000 through $3FF)
- **Bytes per entry**: 8 (4 × 16-bit words)
- **Total size (decompressed)**: 8192 bytes ($2000)

### Per-entry layout (8 bytes)

```
Byte offset   Word #   Sub-tile position
-----------   ------   -----------------
+0, +1        Word 0   Top-left 8×8
+2, +3        Word 1   Top-right 8×8
+4, +5        Word 2   Bottom-left 8×8
+6, +7        Word 3   Bottom-right 8×8
```

Each word is stored **little-endian** (low byte first) in ROM / decompressed data.

### Sub-tile word format (standard SNES BG tilemap word)

```
Bit:  15  14  13  12  11  10   9   8   7   6   5   4   3   2   1   0
       V   H   O   P   P   P   T   T   T   T   T   T   T   T   T   T

V     = Vertical flip
H     = Horizontal flip
O     = Priority (1 = in front of sprites at same priority)
PPP   = Palette number (0-7), selects which 16-color sub-palette
TTTTTTTTTT = Tile number (0-1023), indexes into VRAM 8×8 tile graphics
```

As a **16-bit value** (big-endian for clarity):
```
[VHOP PPTT] [TTTT TTTT]
 high byte    low byte
```

In memory (little-endian): `[TTTTTTTT] [VHOPPPTT]`

### SMILE's ConvertToOAM (from `UGraphics.bas`)

This function parses a 16-bit tile table word:

```vb
Public Function ConvertToOAM(RawData As Integer) As OAM
    ' RawData is the 16-bit word from the tile table
    HexOfRawData = Right$("0000" & Hex$(RawData), 4)
    Byte1 = Val("&H" & Right$(HexOfRawData, 2) & "&")   ' Low byte  (TTTTTTTT)
    Byte2 = Val("&H" & Left$(HexOfRawData, 2) & "&")    ' High byte (VHOPPPTT)

    ToBin Byte2, BitArray(0)   ' Convert high byte to 8-bit array

    ConvertToOAM.Vertical   = BitArray(0)                                  ' Bit 7 = V flip
    ConvertToOAM.Horizontal = BitArray(1)                                  ' Bit 6 = H flip
    ConvertToOAM.Priority   = BitArray(2)                                  ' Bit 5 = priority
    ConvertToOAM.Palette    = (BitArray(3)*4) + (BitArray(4)*2) + BitArray(5) ' Bits 4-2 = PPP
    ConvertToOAM.Tile       = (BitArray(6)*&H200) + (BitArray(7)*&H100) + Byte1 ' Bits 1-0 + low byte = tile#
End Function
```

### SMILE's Draw_Block (from `UGraphics.bas`)

This function renders one 16×16 metatile:

```vb
Public Sub Draw_Block(InSection As cDIBSection, BlockIndex, TileX, TileY)
    Dim PixelX, PixelY, X, Y, Counter
    Dim TtableEntry(0 To 3)

    PixelX = TileX * 16
    PixelY = TileY * 16

    ' Read 4 sub-tile words from the tile table for this metatile
    GetDrawBlockData BlockIndex, TtableEntry(), Ttable(), UBound(Ttable) - 1

    ' Draw 4 quarters of the 16×16 tile
    For Y = 0 To 1          ' Y=0: top row, Y=1: bottom row
        For X = 0 To 1      ' X=0: left col, X=1: right col
            LunarRender8x8 InSection.DIBSectionBitsPtr, _
                InSection.Width, InSection.Height, _
                PixelX + (X * 8), PixelY + (Y * 8), _
                Pixelmap(0), PcPalette(0), _
                TtableEntry(Counter), LC_DRAW
            Counter = Counter + 1
        Next X
    Next Y
End Sub
```

`GetDrawBlockData` reads from the decompressed `Ttable()` byte array at offset
`BlockIndex * 8`, returning 4 × 32-bit values (the 16-bit tile words extended
to 32-bit for the Lunar Compress DLL).

### Example: Metatile index $042

```
Tile table offset = $042 * 8 = $0210
Bytes at offset $0210: [B4 01] [B5 01] [C4 01] [C5 01]

Word 0 (top-left):     $01B4 = V=0 H=0 O=0 Pal=0 Tile=$1B4
Word 1 (top-right):    $01B5 = V=0 H=0 O=0 Pal=0 Tile=$1B5
Word 2 (bottom-left):  $01C4 = V=0 H=0 O=0 Pal=0 Tile=$1C4
Word 3 (bottom-right): $01C5 = V=0 H=0 O=0 Pal=0 Tile=$1C5
```

### Dual tile tables: Variable + CRE

The full 1024-entry tile table is assembled from **two compressed sources**:

1. **Variable tile table** — pointed to by entry bytes 0-2 in the tileset table.
   Provides definitions for tileset-specific metatiles.

2. **CRE tile table** — at fixed SNES address `$B9:A09D` (PC `$1CA09D`).
   Provides definitions for common elements (doors, save stations, etc.).

Both are compressed with SM's LZ5 format. In a normal non-Ceres room, they are
combined into the full 1024-entry table in this exact order:

- CRE: `$7E:A000..A7FF`, metatile IDs `$000..0FF` (256 entries).
- Tileset-specific/SCE: `$7E:A800..BFFF`, metatile IDs `$100..3FF` (up to 768 entries).

The previous version of this document had those two metatile-ID ranges reversed.
Ceres is the important exception: its full tileset table is decompressed at
`$7E:A000` and the separate CRE table is skipped.

### Source-backed metatile verification (2026-10-03)

`parityTileFormats` independently parses all 15 unique source tables: 14 tileset
tables plus CRE. Together they contain 11,264 metatiles / 45,056 subtile words.
The JVM parity test checks each raw table, every word's exact combined-table offset,
and its decoded tile, palette, priority, H-flip, and V-flip fields. It also tests all
65,536 possible 16-bit words for lossless decode/encode round trips.

This makes table ownership explicit rather than inferring it from graphics:

- The thirteen non-Ceres tables (including Kraid) each provide 768 entries at IDs
  `$100..3FF`; CRE supplies IDs `$000..0FF`.
- Kraid therefore still owns a normal CRE metatile-table region even though its
  full 1,024-tile graphics payload suppresses the CRE graphics overlay.
- The one shared Ceres table supplies all 1,024 entries at IDs `$000..3FF`, so the
  separate CRE table is not part of its runtime table.

---

## 3. SNES 4bpp Tile Format — Exact Bit Layout

### Size: 32 bytes per 8×8 tile

Each 8×8 tile at 4 bits per pixel uses 4 bitplanes. The SNES stores these as
two groups of interleaved 2bpp data:

```
Bytes 0-15:  Bitplanes 0 & 1 (interleaved, row by row)
Bytes 16-31: Bitplanes 2 & 3 (interleaved, row by row)
```

### Exact byte layout

```
Byte  Contents
----  --------
 0    Row 0, Bitplane 0
 1    Row 0, Bitplane 1
 2    Row 1, Bitplane 0
 3    Row 1, Bitplane 1
 4    Row 2, Bitplane 0
 5    Row 2, Bitplane 1
 6    Row 3, Bitplane 0
 7    Row 3, Bitplane 1
 8    Row 4, Bitplane 0
 9    Row 4, Bitplane 1
10    Row 5, Bitplane 0
11    Row 5, Bitplane 1
12    Row 6, Bitplane 0
13    Row 6, Bitplane 1
14    Row 7, Bitplane 0
15    Row 7, Bitplane 1
16    Row 0, Bitplane 2
17    Row 0, Bitplane 3
18    Row 1, Bitplane 2
19    Row 1, Bitplane 3
20    Row 2, Bitplane 2
21    Row 2, Bitplane 3
22    Row 3, Bitplane 2
23    Row 3, Bitplane 3
24    Row 4, Bitplane 2
25    Row 4, Bitplane 3
26    Row 5, Bitplane 2
27    Row 5, Bitplane 3
28    Row 6, Bitplane 2
29    Row 6, Bitplane 3
30    Row 7, Bitplane 2
31    Row 7, Bitplane 3
```

### Decoding a single pixel

For pixel at column X (0-7, where 0=leftmost) in row R:

```
bit_mask = (1 << (7 - X))    # MSB = leftmost pixel

bp0 = (byte[R*2 + 0]  & bit_mask) ? 1 : 0
bp1 = (byte[R*2 + 1]  & bit_mask) ? 1 : 0
bp2 = (byte[R*2 + 16] & bit_mask) ? 1 : 0
bp3 = (byte[R*2 + 17] & bit_mask) ? 1 : 0

color_index = bp0 | (bp1 << 1) | (bp2 << 2) | (bp3 << 3)   # 0-15
```

Color index 0 is transparent for sprites, but opaque for BG tiles
(though the SNES PPU treats palette entry 0 of each sub-palette as transparent
for BG tiles behind higher-priority layers).

### Global split-plane Ceres layout

`Tiles_11_12_CeresElevator` and `Tiles_13_14_CeresRidley` are each 32 KiB / 1,024
tiles, but are not a sequence of standard 32-byte tiles. They store two global
16 KiB halves:

```
bytes $0000..3FFF   bp0 rows 0..7, then bp2 rows 0..7, for every tile
bytes $4000..7FFF   bp1 rows 0..7, then bp3 rows 0..7, for every tile
```

For tile `n`, bp0/bp2 begin at `n * 16`; bp1/bp3 begin at `$4000 + n * 16`.
Tilesets `$11..14` use this layout. Kraid (`$1A`) also owns all 1,024 tile slots,
but its payload uses the standard interleaved layout above.

### Standard 2bpp layout

Layer-3 tiles use 16 bytes per 8×8 tile: each row is `[bp0, bp1]`, and the
leftmost pixel is bit 7. `Tiles_Standard_BG3` at `$9A:B200` is exactly 4,096 bytes
or 256 tiles. The shared `TileDecoder` is used by the production Layer-3 path, so
the parity oracle checks the same codec that renders those tiles.

Across the 16 unique tileset graphics resources plus CRE, `parityTileFormats`
compares 10,944 decoded 4bpp tiles pixel-for-pixel with an independent Python
decoder. Thirteen normal source payloads contain 576 tiles each, Kraid contains
1,024 standard tiles, the two split-plane resources contain 1,024 each, and CRE
contains 384. The oracle also checks all 256 standard BG3 2bpp tiles.

### Conversion (from SNESLab wiki algorithm)

```java
// For each 8x8 tile (32 bytes of source):
for (int row = 0; row < 8; row++) {
    byte b0 = src[row * 2];         // bitplane 0
    byte b1 = src[row * 2 + 1];     // bitplane 1
    byte b2 = src[row * 2 + 16];    // bitplane 2
    byte b3 = src[row * 2 + 17];    // bitplane 3
    for (int col = 0; col < 8; col++) {
        int shift = 7 - col;
        int pixel = ((b0 >> shift) & 1)
                   | (((b1 >> shift) & 1) << 1)
                   | (((b2 >> shift) & 1) << 2)
                   | (((b3 >> shift) & 1) << 3);
        dest[row * 8 + col] = pixel;  // 0-15
    }
}
```

### SMILE's conversion

SMILE delegates to Lunar Compress DLL:

```vb
Public Sub FourBppTilesToPixelMap(TileArray() As Byte, OutputArray() As Byte)
    NumberOfTiles = 1024
    SizeOfPixelmap = NumberOfTiles * 64       ' 64 pixels per 8x8 tile
    ReturnValue = LunarCreatePixelMap(TileArray(0), Destination(0), NumberOfTiles, LC_4BPP)
    TrimArray Destination, SizeOfPixelmap, OutputArray
End Sub
```

### Compression in ROM

The 4bpp tile data is stored **compressed** using SM's custom LZ5 algorithm
(Lunar Compress format 4). The decompression routine is at:

- `$80:B0FF` — hardcoded destination
- `$80:B119` — variable destination  
- `$80:B271` — decompress directly to VRAM

Command byte format:

| Cmd bits (upper 3) | Range     | Operation                                    |
|---------------------|-----------|----------------------------------------------|
| `000`               | $00-$1F   | Direct copy: next N+1 bytes verbatim         |
| `001`               | $20-$3F   | Byte fill: repeat 1 byte for N+1 times       |
| `010`               | $40-$5F   | Word fill: alternate 2 bytes for N+1 bytes    |
| `011`               | $60-$7F   | Incrementing fill: byte++, N+1 times         |
| `100`               | $80-$9F   | Dictionary copy: copy N+1 from earlier output |
| `101`               | $A0-$BF   | XOR copy: copy+XOR from earlier output        |
| `110`               | $C0-$DF   | Subtract copy: copy-subtract from earlier     |
| `111`               | $E0-$FE   | Extended: bits 4-2=cmd, {bits 1-0, next byte}=len |
|                     | $FF       | End of compressed data                       |

Lower 5 bits = length - 1 for short commands.
Extended ($E0+): 10-bit length from {bits 1-0 of cmd byte, next byte}.
Because `$FF` terminates first, command 7 only has extended headers `$FC..$FE`
(lengths 1..768); it has no short form or 769..1024-byte encoding.

SMEDIT uses one strict `LZ5Codec` for ROM parsing and export verification. The pinned
source-backed parity suite independently decodes all 421 exact compressed assets and
checks every decoded size/hash plus recompression; see
[`../validation/README.md`](../validation/README.md#shared-graphics-and-compression).

SMILE uses `LunarDecompress` with format=4 (LC_LZ5) for all SM decompression:

```vb
Public Sub Decompress(AddressToStart, OutputArray() As Byte, Optional DecompressedSize = 0)
    Dim Destination(65536) As Byte
    DecompressedSize = LunarDecompress(Destination(0), AddressToStart, 65536, 4, 0, 0)
    TrimArray Destination, DecompressedSize, OutputArray
End Sub
```

---

## 4. CRE + Variable Tileset Combining

### CRE (Common Room Elements)

CRE provides common room art such as doors, save stations, energy recharge,
missile refill, spikes, shot blocks, crumble blocks, bomb blocks, speed booster
blocks, and grapple blocks. Normal rooms combine it with their tileset-specific
data; Ceres/Mode-7 and other engine-special layouts are explicit exceptions.

### Fixed ROM addresses

| Data | Source label | SNES address | PC offset | Compressed | Decoded |
|---|---|---:|---:|---:|---:|
| CRE 8×8 tile graphics | `CRE_Tiles_Compressed` | `$B9:8000` | `$1C8000` | `$209D` / 8,349 bytes | `$3000` / 12,288 bytes |
| CRE tile table | `CRE_TileTable_Compressed` | `$B9:A09D` | `$1CA09D` | `$0597` / 1,431 bytes | `$0800` / 2,048 bytes |

The graphics compressed range is exactly `$B9:8000..A09C`; its exclusive end is
the tile table's `$B9:A09D` start. This is an exact source boundary, not an
approximation inferred from the next pointer.

From SMILE's `SmileMod1.bas`:

```vb
Public CRETilesOffset As Long   ' = &H1C8000  (default offset of CRE tiles)
Public CRETTableOffset As Long  ' = &H1CA09D  (default offset of CRE tile table)
```

### Complete CRE engine-consumer inventory

These are not four contiguous three-byte pointer fields. Each routine constructs a
24-bit decompression source through separate bank and low-word immediate loads. The
source-backed inventory scans every assembly file and fails if an unclassified
reference appears or if a consumer is added/removed.

| Resource | Consumer routine | Low-word load | Runtime destination/behavior |
|---|---|---:|---|
| CRE graphics | `DoorTransitionFunction_PlaceSamus_LoadTiles` | `$82:E418` | When the destination CRE bitset requests it (except the post-Crocomire special door), decode to staging buffer `$7E:7000..9FFF`, then transfer to VRAM `$2800..3FFF` as needed. |
| CRE graphics | `Load_CRETiles_TilesetTiles_and_TilesetPalette` | `$82:E79A` | Decode directly to VRAM byte range `$5000..7FFF` (VRAM words `$2800..3FFF`). |
| CRE tile table | `LoadLevelData_CRE_TileTable_ScrollData_PLMs_DoorASM_RoomASM` | `$82:E840` | Non-Ceres: decode CRE to `$7E:A000..A7FF`, then the selected tileset table to `$7E:A800`. Ceres: skip CRE and decode the full selected table at `$7E:A000`. |
| CRE tile table | `Load_Level_Scroll_and_CRE_Data` | `$82:EAF0` | Same split, but reload CRE only when CRE bit `$0002` requests it; Ceres again loads its full table at `$7E:A000`. |

The two bank-load instructions are at `$82:E413/$82:E795` for graphics and
`$82:E83B/$82:EAEB` for the table. The manifest records all eight instruction
references as four paired consumers.

### VRAM layout: How they combine

When loading a room, the game decompresses and places tile graphics into VRAM:

```
VRAM byte offset    8×8 Tile numbers     Source
----------------    ----------------     ------
$0000 - $47FF       Tiles 0-575          Variable source payload (normal layout)
$4800 - $4FFF       Tiles 576-639        Blank/reserved runtime gap
$5000 - $7FFF       Tiles 640-1023       CRE tiles (standard layout)
```

The standard runtime region reserves the first `$5000` bytes = 640 tile slots for
tileset-specific graphics, but each vanilla normal source payload defines only
`$4800` bytes = 576 tiles. The `$800`-byte / 64-tile gap is zero-filled; it is not
owned by the compressed source. CRE begins at byte offset `$5000` = tile index 640
= `$280` and contains exactly 384 tiles through index 1023. Ceres elevator/Ridley
tilesets `$11..14` use the full split-plane layout, while Kraid `$1A` uses a full
standard layout; neither receives the normal CRE graphics overlay.

From SMILE's `DecompressTilesForRip` (`UGraphics.bas`):

```vb
' Decompress both tile sets
Decompress CRETilesOffset + ROM_HEADER, CRETiles
Decompress GraphicsSetPointers(ArrayIndex) + ROM_HEADER, VarTiles

' Calculate total tile count
Smile.Tag = CStr(((SizeOfCRETiles + &H5000&) / 32))

' Combine: variable tiles at offset 0, CRE tiles at offset $5000
' "With tiles, the variable tiles go before the common room element tiles."
CombineArrays VarTiles, CRETiles, SizeOfVarTiles, SizeOfCRETiles, &H0, &H5000&, OutputArray
```

### Tile table combining

Similarly, the metatile tables combine:
- **CRE tile table** (from `$B9:A09D`): occupies metatile IDs `$000..0FF` at
  `$7E:A000..A7FF`. All 1,024 subtile words in the vanilla table reference CRE
  graphics tile numbers 640–1023.
- **Variable tile table** (from tileset entry bytes 0-2): follows at `$7E:A800`
  and provides metatile IDs `$100..3FF` in the standard layout.
- **Ceres exception**: a full 8 KiB tileset table replaces both regions beginning
  at `$7E:A000`; the separate CRE table is not loaded.

### What `DecompressTiles` and `DecompressTtable` do

These functions are not in the `.bas` module files (they're in SMILE's VB form
files or DLL), but their logic is clear from `DecompressTilesForRip`:

**`DecompressTiles(GraphicsSet, OutputArray)`**:
1. Call `ReadGraphicsSetPointers()` to read all 29 entries
2. `ArrayIndex = (GraphicsSet * 3) - 1` → the GFX/tiles pointer (2nd of 3)
3. Decompress CRE tiles from `CRETilesOffset` (PC `$1C8000`)
4. Decompress variable tiles from `GraphicsSetPointers(ArrayIndex)`
5. Combine: variable at offset 0, CRE at offset `$5000`
6. Total = variable + CRE 8×8 tile graphics in 4bpp format

**`DecompressTtable(GraphicsSet, OutputArray)`**:
1. Call `ReadGraphicsSetPointers()` to read all 29 entries
2. `ArrayIndex = (GraphicsSet * 3) - 2` → the tile table pointer (1st of 3)
3. Decompress CRE tile table from `CRETTableOffset` (PC `$1CA09D`)
4. Decompress variable tile table from `GraphicsSetPointers(ArrayIndex)`
5. Combine into single 1024-entry × 8-byte table

**`DecompressPalette(GraphicsSet, OutputArray)`**:
1. `ArrayIndex = (GraphicsSet * 3)` → the palette pointer (3rd of 3)
2. Decompress palette from `GraphicsSetPointers(ArrayIndex)`

The `DecompressGraphics` function in `LunarMod.bas` shows the same three-pointer
decompress pattern:

```vb
Public Sub DecompressGraphics(TablePointer As ThreeByte, TilePointer As ThreeByte, _
                              PalettePointer As ThreeByte)
    TableOffset = ThreePoint2Offset(TablePointer)
    TileOffset = ThreePoint2Offset(TilePointer)
    PaletteOffset = ThreePoint2Offset(PalettePointer)

    LunarOpenFile needslash & ".smc", 1
    TableSize   = LunarDecompress(MyTileTable(0), TableOffset + ROM_HEADER, 65536, 4, 0, 0)
    TileSize    = LunarDecompress(MyTileSet(0),   TileOffset + ROM_HEADER,  65536, 4, 0, 0)
    PaletteSize = LunarDecompress(MyPalette(0),   PaletteOffset + ROM_HEADER, 65536, 4, 0, 0)
    LunarCloseFile
End Sub
```

---

## 5. Palette Format and Location

### Color format: BGR555 (15-bit)

Each color is a **16-bit word** (2 bytes, little-endian):

```
Bit:  15  14  13  12  11  10   9   8   7   6   5   4   3   2   1   0
       0   B   B   B   B   B   G   G   G   G   G   R   R   R   R   R

Bit 15:    Always 0
Bits 14-10: Blue  (0-31)
Bits 9-5:   Green (0-31)
Bits 4-0:   Red   (0-31)
```

### Converting BGR555 → RGB888

```python
def bgr555_to_rgb888(word):
    r = (word & 0x1F) << 3          # bits 0-4 → red
    g = ((word >> 5) & 0x1F) << 3   # bits 5-9 → green
    b = ((word >> 10) & 0x1F) << 3  # bits 10-14 → blue
    return (r, g, b)
```

From SMILE's `PaletteSMILE.bas`:

```vb
Public Sub PaletteFromBytes(TempLong As Long)
    pRR = TempLong Mod 32             ' Bits 0-4: Red (0-31)
    pGG = (TempLong \ 32) Mod 32      ' Bits 5-9: Green (0-31)
    pBB = (TempLong \ 1024) Mod 32    ' Bits 10-14: Blue (0-31)
End Sub
```

### Palette structure for BG tiles

Super Metroid uses **SNES Mode 1** for gameplay. BG1 (level tiles) uses 4bpp,
which means **16 colors per sub-palette** and **8 sub-palettes** (palettes 0-7):

```
8 palettes × 16 colors × 2 bytes/color = 256 bytes total
```

The palette pointer (bytes 6-8 of the tileset entry) points to this 256-byte
block (possibly compressed). The 3-bit palette field (PPP) in each tile table
word selects which of the 8 sub-palettes to use for that 8×8 sub-tile.

### SNES CGRAM layout

The SNES has 256 words of CGRAM (Color Generator RAM) = 512 bytes:
- Words 0-127: BG palettes (8 × 16 colors)
- Words 128-255: Sprite palettes (8 × 16 colors)

The tileset palette is loaded into the BG portion of CGRAM.

### SMILE's palette conversion

```vb
Public Sub SnesPaletteToPcPalette(SnesPalette() As Byte, OutputArray())
    NumberOfColors = (UBound(SnesPalette) + 1) \ 2    ' 2 bytes per color
    ReDim OutputArray(NumberOfColors)
    While IndexCounter < NumberOfColors
        Bytes(0) = SnesPalette(ByteCounter)      ' Low byte
        Bytes(1) = SnesPalette(ByteCounter + 1)   ' High byte
        TempLong = BytesToLong(Bytes)              ' Combine to 16-bit value
        PcColor = LunarSNEStoPCRGB(TempLong)       ' BGR555 → PC RGB
        OutputArray(IndexCounter) = PcColor
        ByteCounter = ByteCounter + 2
        IndexCounter = IndexCounter + 1
    Wend
End Sub
```

---

## 6. SMILE's Complete Rendering Pipeline

### Entry point: `DrawTiles` (from `UGraphics.bas`)

```vb
Public Sub DrawTiles(GraphicsSet)
    ' --- Step 1: Decompress all three data sources ---
    DecompressTtable GraphicsSet, Ttable       ' → Ttable() byte array (8192 bytes)
    DecompressTiles GraphicsSet, Tiles          ' → Tiles() byte array (combined 4bpp)
    DecompressPalette GraphicsSet, Palette      ' → Palette() byte array (BGR555)

    ' --- Step 2: Convert 4bpp tiles to pixel indices ---
    FourBppTilesToPixelMap Tiles, Pixelmap      ' Tiles → Pixelmap (1 byte per pixel)

    ' --- Step 3: Convert SNES palette to PC RGB ---
    SnesPaletteToPcPalette Palette, PcPalette   ' BGR555 → PC RGB longs

    ' --- Step 4: Draw all 1024 metatiles into a 512×512 preview ---
    SecTiles.Create 512, 512
    TtableCounter = 0
    While TtableCounter < &H400&                ' 1024 metatiles
        If LineCounter > 31 Then                ' 32 metatiles per row
            XCounter = 0
            YCounter = YCounter + 1
            LineCounter = 0
        End If
        Draw_Block SecTiles, TtableCounter, XCounter, YCounter
        TtableCounter = TtableCounter + 1
        XCounter = XCounter + 1
        LineCounter = LineCounter + 1
    Wend
    SecTiles.VFlip
End Sub
```

### The render call: `LunarRender8x8`

The actual pixel rendering for each 8×8 sub-tile is done by the Lunar Compress DLL:

```vb
' Map8Tile is a 32-bit value encoding the tile table word.
' The DLL internally:
'   1. Extracts tile number (bits 9-0) → offset into Pixelmap
'   2. Extracts palette (bits 12-10) → offset into PcPalette (palette * 16)
'   3. Extracts flips (bits 14-15) → mirror the tile
'   4. For each pixel: color = PcPalette[palette*16 + Pixelmap[tile*64 + pixelOffset]]
'   5. Draws to the DIB section bitmap at (DisplayAtX, DisplayAtY)

LunarRender8x8(
    TheMapBits,     ' Pointer to DIB section pixel data (destination bitmap)
    TheWidth,       ' Bitmap width in pixels
    TheHeight,      ' Bitmap height in pixels
    DisplayAtX,     ' X position to draw at
    DisplayAtY,     ' Y position to draw at
    Pixelmap(0),    ' Array of pixel indices (1 byte per pixel, 64 per tile)
    PcPalette(0),   ' Array of PC RGB colors
    Map8Tile,       ' Tile table word (VHOPPPTTTTTTTTTT as 32-bit)
    LC_DRAW         ' Flags (LC_DRAW = draw all priorities)
)
```

### Full flow summary

```
ROM ──decompress──→ Ttable[8192 bytes] ────────────────────────────┐
     (tile table)   (1024 entries × 4 × 2-byte words)             │
                                                                    │
ROM ──decompress──→ Variable Tiles[$4800] ┐                        │
     (4bpp GFX)                             ├──combine──→ Tiles[]   │
ROM ──decompress──→ CRE Tiles[] ───────────┘             (4bpp)    │
     (CRE at $B9:8000)    after blank $4800..4FFF, at $5000        │
                                                                    │
                    Tiles[] ──LunarCreatePixelMap──→ Pixelmap[]     │
                    (4bpp)     (decode bitplanes)   (1 byte/pixel)  │
                                                                    │
ROM ──decompress──→ Palette[256 bytes] ──convert──→ PcPalette[]    │
     (palette)      (128 BGR555 words)              (PC RGB longs)  │
                                                                    │
For each of 1024 metatiles: ◄──────────────────────────────────────┘
  Read 4 words from Ttable at [metatile_index * 8]
  For each word (TL, TR, BL, BR):
    tile_num = word & $3FF
    palette  = (word >> 10) & 7
    h_flip   = (word >> 14) & 1
    v_flip   = (word >> 15) & 1
    For each pixel (x,y) in the 8×8 tile:
      pixel_index = Pixelmap[tile_num * 64 + y * 8 + x]
      color = PcPalette[palette * 16 + pixel_index]
      → write to bitmap (with flip transforms applied)
```

---

## Key ROM Addresses Quick Reference

| Item                          | SNES Address  | PC Offset   | Size                |
|-------------------------------|---------------|-------------|---------------------|
| Tileset table                 | `$8F:E6A2`   | `$07E6A2`   | 261 (29×9)          |
| Tileset pointers (secondary)  | `$8F:E7A7`   | `$07E7A7`   | 58 (29×2)           |
| CRE 8×8 tile graphics        | `$B9:8000`   | `$1C8000`   | `$209D` compressed → `$3000` decoded |
| CRE tile table                | `$B9:A09D`   | `$1CA09D`   | `$0597` compressed → `$0800` decoded |
| CRE GFX low-word load #1      | `$82:E418`   | `$016418`   | paired immediate load |
| CRE GFX low-word load #2      | `$82:E79A`   | `$01679A`   | paired immediate load |
| CRE tile-table low-word load #1 | `$82:E840` | `$016840`   | paired immediate load |
| CRE tile-table low-word load #2 | `$82:EAF0` | `$016AF0`   | paired immediate load |
| Decompression (hardcoded dst) | `$80:B0FF`   | `$0030FF`   | —                   |
| Decompression (variable dst)  | `$80:B119`   | `$003119`   | —                   |
| Decompress to VRAM            | `$80:B271`   | `$003271`   | —                   |
| Load CRE+tileset+palette      | `$82:E78C`   | `$01678C`   | core routine (`$E783` is the DB-setting wrapper) |
| Load level+CRE+tiletable      | `$82:E7D3`   | `$0167D3`   | —                   |
| Load CRE bitset               | `$82:DDF1`   | `$015DF1`   | —                   |
| Room headers start (Crateria) | `$8F:91F8`   | `$0711F8`   | variable            |

## Sources

1. **P.JBoy's disassembly** — https://patrickjohnston.org/bank/ (banks $80, $82, $8F)
2. **SMILE Legacy VB source** — github.com/glowysourworm/sm-editor (`UGraphics.bas`, `Lunar.bas`, `LunarMod.bas`, `SmileMod1.bas`, `PaletteSMILE.bas`)
3. **Kejardon's data bank docs** — `mdb_format.txt` in the sm-editor repo
4. **SNESLab wiki** — https://sneslab.net/wiki/Graphics_Format
5. **Metroid Construction forum** — CRE repointing thread (topic 1784)
6. **snes.nesdev.org** — https://snes.nesdev.org/wiki/Tiles
