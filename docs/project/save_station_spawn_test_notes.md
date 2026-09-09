# Save Station Spawn Editing Test Notes

Implemented on 2026-07-13; runtime-slot safety hardened on 2026-09-09.

## What changed

- Save station PLMs (`$B76F`) now auto-create an AreaSave spawn override when placed.
- Auto-derived spawn data is stored in the project file under the room's `saveStationSpawns`.
- Removing a save station removes the associated spawn override when nothing else in the room uses that save index.
- The item overlay now draws a spawn marker for each save station:
  - Cyan: ROM value
  - Green: auto-derived project value
  - Yellow: manual override
- Right-clicking a save station shows the AreaSave index and lets you edit Samus X/Y plus scroll X/Y.
- Export writes the edited AreaSave slot fields: room, door pointer, scroll X/Y, Samus Y, and Samus X.

## Things to test

1. Open a room and enable the item overlay.
2. Place a save station.
3. Confirm the save station chooses an unused save index for the current area.
4. Confirm a green spawn marker appears near the placed station.
5. Right-click the save station and verify the detail row says `Auto`.
6. Change Samus X/Y or Scroll X/Y, press `Apply Spawn`, and confirm the marker moves and the detail row says `Override`.
7. Press `Reset Auto` and confirm the marker returns near the save station and the detail row says `Auto`.
8. Delete the save station and confirm its spawn override is removed from the project, including after a manual override.
9. Export a ROM, reload it, and confirm saving/resuming from that station returns Samus to the expected room position.
10. Fill indices `0` through `7`, then try to place another save station. Confirm
    the placement is rejected, a clear status message appears, and no PLM,
    AreaSave override, or undo record is partially created.

## Known limits

- This writes existing AreaSave table slots. Save-station PLM activation masks
  the parameter with `AND #$0007`, so only indices `0-7` are runtime-addressable.
  Entries at index 8 and above are elevator/start/debug load records, not extra
  save-station capacity.
- If all eight runtime slots are occupied, placement and area migration are
  rejected without changing project state. SMEDIT never reuses an occupied slot.
- Relocating or lengthening the AreaSave table alone would create false capacity.
  More than eight save stations in one area requires an engine patch covering
  the PLM mask, save/load logic, SRAM/file-select assumptions, and emulator
  regression tests. Until that exists, eight is an intentional hard limit.
- Door pointer derivation prefers an existing save entry for the same room, then an incoming door to the room, then the first room door. If a custom room has unusual entrances, verify the exported resume path in-game.
