# Feature 2: Door Cloning Tool

## Goal
Click a screen edge on the map → auto-create a door with correct cap PLM,
door entry, spawn point, and direction. Massive QoL for connecting rooms.

## Where it lives
- MapCanvas tile properties panel — new "+ Add Door" button
- When clicked on a tile at a screen edge (x=0, x=15, y=0, y=15 within a screen),
  auto-derives direction and placement

## Auto-derivation logic
Given a click at tile (bx, by) on a screen edge:
1. **Direction**: Left edge (bx%16==0) → Left door, Right edge (bx%16==15) → Right,
   Top (by%16==0) → Up, Bottom (by%16==15) → Down
2. **Door cap position**: Place cap PLM at the edge tile
3. **Door cap type**: Default to Grey (0xC848 right, 0xC842 left, 0xC84E up, 0xC854 down)
4. **Door entry**: Create with default spawn point (center of first screen of destination)
5. **BTS**: Set tile to door type (block type 0x9) with BTS = door index

## UI Flow
1. User clicks "+ Add Door" in tile properties
2. If tile is on a screen edge, auto-populate direction + cap position
3. Show dropdown for door color (Grey/Blue/Red/Yellow/Green)
4. Show destination room selector
5. On confirm: add door cap PLM + set BTS + add door entry to door list

## Implementation
1. Add `isScreenEdge(bx, by)` helper
2. Add `deriveDoorDirection(bx, by)` helper
3. Add door cap PLM ID lookup by direction + color
4. Add `addDoor()` to EditorState that creates both PLM and door entry
5. UI: "+ Add Door" button in tile properties, visible when tile is on screen edge

## Tests
- Verify direction derivation for all 4 edges
- Verify correct door cap PLM IDs for each direction × color
- Verify BTS is set correctly for door tiles
