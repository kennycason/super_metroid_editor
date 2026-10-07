# Layer 2 Editing Test Notes

Use these checks for the first embedded Layer 2 editing slice.

## Scope

- Supported now: rooms whose decompressed level data includes embedded Layer 2 after `[Layer 1][BTS]`, including rooms with nonzero parallax motion factors.
- Read/render supported: bank-`$8F` library-background programs, including decompression, ROM/WRAM transfers, clears, 64×32 and Kraid 64×64 screen-block layout, and deterministic door-dependent previews.
- Not supported yet: direct authoring of library-background command programs or choosing an incoming door context for the map preview.
- Layer 2 edits are visual background edits only. The editor writes metatile ID plus H/V flip bits and does not write collision block type or BTS.

## Manual Checks

1. Open an embedded Layer 2 room such as Bat Cave (`0xB07A`), Hopper Energy Tank Room (`0xA15B`), or parallax-enabled Dachora Room (`0x9CB3`).
2. Confirm the map toolbar shows `L1` and enabled `L2` chips.
3. Select `L2`, choose a metatile from the tileset, and paint onto the map.
4. Confirm the visible Layer 2 background updates immediately and Layer 1 collision/block metadata does not change.
5. Use erase on Layer 2 and confirm only the background tile is cleared.
6. Use fill on a small matching Layer 2 region and confirm it fills by background tile, not by Layer 1 collision.
7. Use sample on a Layer 2 tile and confirm the brush picks that background metatile and its H/V flip state.
8. Undo and redo the paint/fill/erase operations.
9. Save the project, reload the same room, and confirm Layer 2 edits replay.
10. Export a ROM and quickly verify the edited room still loads.

## Follow-Ups

- Add selection/copy/paste support for Layer 2 rectangles.
- Add a dedicated editor for library-background command programs (`bgDataPtr != 0`).
- Let the map preview choose the incoming door for door-dependent transfers.
- Add a small status hint explaining why `L2` is disabled in non-embedded rooms.
