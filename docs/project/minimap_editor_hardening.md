# Minimap Editor Hardening

Last updated: 2026-08-29

## Safe area model

Super Metroid room headers always contain a real area ID. Valid room areas are
`0..6`; area `0` is Crateria, not an unassigned/null value.

The editor therefore exposes two separate operations:

- **Move room to area** changes room ownership and migrates its related map data.
- **Hide room map graphics** removes the visible map rectangle and map-station
  reveal cells without changing the room's area.

Both Room Info and the minimap context menu call the same room-area mutation
path. A successful move:

1. resolves source cells by room ownership, preserving intentionally nested
   smaller rooms instead of treating the whole header rectangle as one room;
2. uses the current coordinates when they are free in the destination area, or
   finds the nearest rectangle containing no room, map-tile, or station-reveal
   data;
3. moves the visible minimap tile rectangle;
4. moves the corresponding map-station reveal bits;
5. updates the room header area while preserving all other header overrides;
6. recomputes the `$40` cross-area flag for every incoming and outgoing door;
7. refreshes room, item, door, and minimap UI from the effective project header.

Standard save-station rooms migrate their AreaSave entry into an unused,
preallocated destination slot. The move also retargets the `$B76F` PLM index,
clears the released source slot, and includes both changes in undo/redo and ROM
validation. A move is still blocked when the destination has no empty existing
slot (table expansion is then required), or when the reference is a special
start/elevator entry rather than a save-station PLM. Validation also rejects
hand-edited project JSON that changes area without a complete migration.

Clearing map data writes the actual blank pause-map tile (`$001F`), not `$0000`.
This matters because the game's automap save logic identifies blank map groups
from `$001F` tiles.

## Editing UX

Canvas actions now live in a top toolbar, matching the room and sprite editors:

| Action | Shortcut |
| --- | --- |
| Select room | `S` |
| Paint | `P` |
| Fill | `G` |
| Erase | `E` |
| Sample | `I` |
| Flip selected tile horizontally | `H` |
| Flip selected tile vertically | `V` |
| Rotate clockwise | `R` |
| Rotate counter-clockwise | `Shift+R` |
| Undo / redo | `Ctrl/Cmd+Z`, `Ctrl/Cmd+Y`, `Ctrl/Cmd+Shift+Z` |
| Move selected room | Arrow keys |
| Apply/cancel room move | `Enter`, `Escape` |
| Zoom | `+`, `-`, or modified wheel/trackpad gesture |

Rooms can also be repositioned entirely with the mouse:

- In Select mode, click a room once to attach it to the pointer, then click
  again to place and deselect it.
- Middle-drag a room from any tool and release to place it.
- Invalid click placements and middle-button drops restore the room to its
  original location. Switching away from Select, choosing another room,
  pressing Escape, or Cancel also restores an uncommitted floating room.

Right-clicking a room rectangle opens area reassignment and the optional
hide-graphics action. Changing area keeps the editor on the source area, where
the room immediately disappears from the area room list and map; selecting the
destination area shows it there. If the same coordinates are occupied in the
destination, the room is automatically placed at the nearest safe map position
and its header coordinates are updated with the migrated graphics. Room
outlines are available as an opt-in overlay.

Pause-map tile words have horizontal and vertical flip bits but no 90-degree
rotation bit. Rotation therefore renders the requested pixel transform and
searches the ROM minimap tile sheet for an exact tile-plus-flips representation.
If no exact representation exists, the operation is refused instead of showing
a preview the ROM cannot encode.

Map-station reveal cells are now project-backed and export with minimap changes
from both desktop and ROM-backed headless builds. The reveal tool in the
toolbar edits them directly. Malformed sparse edits (invalid area keys,
coordinates, or tile words) are validation errors and fail export safely.

## Undo and collision safety

Minimap history snapshots all minimap areas, station-reveal edits, affected room
headers, and door changes. Paint, erase, fill, clear-room, room movement, and
area movement undo/redo as coherent operations. In particular, undoing a room
move restores both its graphics and header coordinates.

Room movement uses an Apply/Cancel preview. Apply is refused when the target
rectangle overlaps another room or any existing map tile. The preview also
moves station-reveal coverage so the two datasets cannot silently diverge.
Source extraction uses the same per-cell ownership rule as area reassignment;
moving or hiding a large room cannot erase a smaller room nested inside its
header bounds.

## Verification

Automated coverage includes:

- flip-bit and exact-rotation representation;
- blank-tile clearing and non-destructive room moves;
- header + minimap + station-reveal undo/redo;
- safe real-ROM area reassignment and connected-door flag correction;
- occupied-coordinate reassignment using Terminator Room from Crateria to
  Brinstar, including source-render removal and safe destination placement;
- overlapping-source reassignment using West Ocean while preserving Bowling
  Alley Path and Crateria Partial Room cells;
- AreaSave save-station migration, released-source cleanup, and blocking for
  unsupported start/elevator references or destinations requiring expansion;
- desktop and headless exported minimap/map-station bytes;
- project validation for invalid/null-sentinel areas and AreaSave mismatches.

## Remaining follow-ups

- Optionally add a destination-position chooser for users who want to override
  the automatic nearest-safe placement.
- Move editor-wide shortcut definitions into one configurable input policy.
- Add emulator-backed acceptance tests for pause-map reveal, save/reload, and
  representative cross-area transitions.
