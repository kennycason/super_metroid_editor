# Feature 3: Room Shifting Tool (Selection Move)

## Goal
When the user has a rectangular selection on the map, arrow keys move the
entire selected region of tiles. Simple, intuitive UX.

## Where it lives
- MapCanvas — extends existing selection rectangle behavior
- When selection exists + arrow key pressed → shift tiles within selection

## Behavior
1. User draws a selection rectangle with the select tool (already exists)
2. While selection is active, pressing arrow keys:
   - Copies all tiles (L1 words + BTS) within the selection
   - Shifts them by 1 tile in the arrow direction
   - Fills vacated tiles with air (0x0000, BTS 0x00)
   - The selection rectangle moves with the tiles
3. Ctrl+arrow = shift by 16 tiles (one screen)
4. Each shift is one undo step

## Edge cases
- Tiles that shift beyond room bounds are discarded (clipped)
- PLMs and enemies within the selection are NOT moved (tiles only — moving entities
  is a separate operation)
- Undoable via Ctrl+Z

## Implementation
1. In MapCanvas keyboard handler, detect arrow keys when selection is active
2. Add `shiftSelection(dx, dy)` to EditorState:
   - Read all tiles in selection rect
   - Clear selection area
   - Write tiles at offset position
   - Update selection rect coordinates
   - Push to undo stack
3. The existing `selectionRect` state already tracks the selected area

## Tests
- Shift a 2x2 selection right by 1: verify tiles moved, old positions cleared
- Shift to room edge: verify clipping
- Undo after shift: verify tiles return to original position
