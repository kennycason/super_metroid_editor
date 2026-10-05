# Crocomire

Crocomire is a split room-BG/OBJ boss whose death sequence changes the physical
contents of OBJ VRAM. A correct editor cannot treat it as one static enemy sheet:
the living body, melting overlays, and skeleton are separately owned sources that
bank `$A4` combines with room graphics from tileset `$1B`.

## Runtime identity

| Slot | Species | Role | AI bank | Palette | Header graphics field |
|---|---:|---|---:|---|---|
| Body | `$DDBF` | Boss body and death sequence | `$A4` | `$A4:B87D` | `$A600` → effective `$2600` bytes from `$AD:8000` |
| Tongue | `$DDFF` | Independently animated tongue | `$A4` | `$A4:B87D` | `$2000`-byte prefix of the same source |

The high bit in the body size field is a layout flag; it does not make the source
payload `$A600` bytes long. Masking it leaves the exact `$2600`-byte
`Tiles_Crocomire` asset. Both headers therefore share one pixel owner, and tongue
edits must alias the corresponding prefix rather than create a second writable copy.

## Pixel ownership

Living Crocomire uses two independent sources:

- tileset `$1B` decompresses `Tiles_1B_Crocomire` to `$4800` bytes of room/BG2
  graphics; it remains part of the room tileset edit unit `varGfx["27"]`;
- `Tiles_Crocomire` at `$AD:8000` supplies `$2600` bytes of enemy OBJ graphics,
  uploaded beginning at physical tile `$D0`; this is the unambiguous editable
  source stored as `spriteTileBlocks["enemy:DDBF"]`.

The Sources tab intentionally does not flatten those into one editable canvas.
Compositions and animations use both, while edits are applied only to the selected
owner and reassembled live.

## Melting and skeleton DMA

The death sequence replaces parts of the physical OBJ page:

| Runtime source | Address | Size | Destination |
|---|---:|---:|---:|
| `Tiles_Crocomire_Melting1` | `$A4:A07D` | `$0C00` | physical tile `$130` |
| `Tiles_Crocomire_Melting2` | `$A4:AC7D` | `$0C00` | physical tile `$130` |
| `Tiles_CrocomireSkeleton_0..5` | `$AD:A600..B1FF` | six × `$0200` | tiles `$160,$170,$180,$190,$1E0,$1F0` |

These payloads are runtime overlays, not bytes owned by the normal body header.
SMEDIT exposes them as read-only source sheets and uses them for their corresponding
melting/skeleton frames. This prevents a living-body paint operation from silently
overwriting a separately transferred death asset.

## Placement and animations

Bank `$A4` defines 74 active standard OBJ maps (683 entries), 94 active extended
maps (463 child links), and 11 BG2 maps (606 tile words). The complete living pose
combines room-owned BG2 body/tail art with enemy-owned OBJ head and limb art. The
editor uses the engine's low-nine-bit OBJ tile selection and the source palette rows
for sprite rows 1, 2, 3, 5, and 7.

All 36 active instruction lists are byte-bounded and decode to 233 timed frame
occurrences / 92 unique frame maps plus 178 handler occurrences. The guided UI shows
the 20 multi-frame lists (218 frames), grouped as Fight, Tongue, Melting, and Skeleton.
It opens on Animations, with complete static compositions and layer-isolated components
available one level deeper.

The preview models the visual sources and placement records. Environmental effects
such as the bridge/floor behavior, lava interaction, scrolling, and sound are runtime
room/AI behavior and are not baked into the sprite canvas.

## Parity and edit boundary

`./gradlew parityCrocomire` writes ignored `parity/reports/crocomire.json`.
`CrocomireSourceParityTest` pins both headers, all nine raw enemy/runtime assets,
tileset `$1B`, all active OBJ/extended/BG2 structures, all 36 active lists, three
source-declared unused lists, six palette rows, exact asset hashes, and deterministic
production-render hashes for the palette, compositions, components, and all guided
animation frames.

Only `Tiles_Crocomire` is edited through the Crocomire sprite workspace. Room BG2
pixels retain normal tileset ownership; melting/skeleton DMA, OAM placement,
instruction data, and flattened compositions remain read-only until each has a safe
writer with an explicit ownership contract.
