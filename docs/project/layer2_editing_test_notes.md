# Layer 2 Editing Test Notes

Use these checks for the first embedded Layer 2 editing slice.

## Scope

- Supported now: rooms whose state data has `BG scrolling = 0x0000` and whose level data includes embedded Layer 2 after `[Layer 1][BTS]`.
- Not supported yet: scrolling/door-dependent BG data streams. The Layer 2 chip is disabled for those rooms.
- Layer 2 edits are visual background edits only. The editor writes metatile ID plus H/V flip bits and does not write collision block type or BTS.

## Manual Checks

1. Open an embedded Layer 2 room such as Bat Cave (`0xB07A`) or Hopper Energy Tank Room (`0xA15B`).
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
- Add a dedicated editor for scrolling BG data streams (`bgScrolling != 0` / `bgDataPtr != 0`).
- Add a small status hint explaining why `L2` is disabled in non-embedded rooms.
