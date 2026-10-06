# Draygon

Draygon is a four-enemy-slot composition that mixes room-owned BG2 pixels with a
separate shared enemy OBJ payload. Treating it as one flat sprite sheet loses both
the runtime structure and the correct edit/export owner.

## Runtime identity

- Room: `$8F:DA60`; live state `$8F:DA72`, dead state `$8F:DA8C`
- Tileset: `$1C` in both states
- AI and all named placement structures: bank `$A5`
- Body `$DE3F`, eye `$DE7F`, tail `$DEBF`, arms `$DEFF`
- Body HP/damage: 6000 / 160
- Initial room population order: body, eye, tail, arms; each begins at `$FFB0,$FFB0`
- Enemy set: only `EnemyHeaders_DraygonBody,$0007` transfers the shared graphics

The four slots advance independent instruction lists. A truthful preview therefore
changes one slot while holding the other three in source-valid resting poses; it does
not flatten unrelated frames found near one another in bank `$A5`.

## Pixel ownership

### Room BG2

Tileset `$1C` owns the large background regions:

| Resource | Source | Compressed address | Decompressed size |
|---|---|---:|---:|
| Graphics | `Tiles_1C_Draygon` | `$BF:9DEA` | `$4800` |
| Metatile table | `TileTables_1C_Draygon` | `$C2:960D` | `$1800` |
| Palette | `Palettes_1C_Draygon` | `$C2:BA2C` | `$0100` |

`LibBG_Draygon_State0` uploads `$1000` bytes from `$7E:2000` to VRAM `$4800`.
The source-backed renderer uses the complete decoded room tile buffer for 48 extended
BG2 tilemaps (196 runs / 980 words).

### Enemy OBJ

All four species headers point at raw `Tiles_Draygon`, `$B0:C800..E7FF`. The body
declares the full `$2000` bytes; eye, tail, and arms each declare the first `$1800`.
The runtime load begins at physical tile `$100`. The manifest keeps these raw bytes
separate from tileset `$1C` even though one extended spritemap can reference both.

Header palette ownership is also nonuniform. Body, tail, and arms point at
`Palette_Draygon_Sprite7` (`$A5:A1F7`); the eye header uses the special no-op pointer
`$A5:8069`. Runtime AI installs the actual palette rows.

## Source inventories

The strict `parityDraygon` slice pins:

- 4 species headers;
- 94 standard spritemaps / 632 OAM entries;
- 103 extended spritemaps / 185 child links;
- 48 extended BG2 tilemaps / 980 words;
- 57 active named instruction lists: 250 frame and 147 handler occurrences;
- 39 frame-bearing production lists, also totaling 250 timed frame occurrences;
- 5 source-declared unused lists, kept separate from production;
- 47 hitbox structures, `Hitbox_Draygon_0..2E`.

The dedicated editor covers ten static compositions through two compact controls:
facing (left/right) and one shared pose list (idle plus four gaze directions). Its
animation filters select side and independently animated part (body, face overlay,
eye, tail, or arms). Fire-goop and roar lists are face overlays over the resting body
rather than body replacements.

## Palettes

Bank `$A5` declares six complete palettes: sprite rows 7, 1, 2, and 3; BG1/2 row 5;
and the all-white hurt flash. `DraygonHealthBasedPaletteTable` at `$A5:96AF` contains
eight rows of four colors. They replace sprite-palette indexes 9–12 at health
thresholds 5250, 4500, 3750, 3000, 2250, 1500, 750, and 0. The terminator is `$FFFF`.

## Editor and export boundary

The editor follows the shared boss layout: Components renders each of the four runtime
slots in isolation for the selected facing, Compositions shows the ten complete poses,
Animations plays the exact bounded lists, and Sources exposes pixel ownership. The
full `$2000` body-owned OBJ payload is
editable there and saves as `spriteTileBlocks["enemy:DE3F"]`. While painting, the
reference panel reassembles every named Draygon pose from the in-progress bytes. A
stable full-health palette is used for the indexed 4bpp round trip; the runtime health
and hurt palettes remain preview choices.

BG2/OBJ placement, instruction records, hitboxes, and flattened composite pixel
editing remain read-only. This includes the isolated component previews: an extended
spritemap frame can contain both BG2 and OBJ children, so a flattened edit cannot
identify which BG2 run or OBJ tile owns a visible pixel. The component panel links to
the exact OBJ source editor instead.

`DraygonSourceParityTest` pins raw owners, decoded tileset bytes, every source record,
all palettes, all ten compositions, and all 250 rendered production frame occurrences
with deterministic hashes.
