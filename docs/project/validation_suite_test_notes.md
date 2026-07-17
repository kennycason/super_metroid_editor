# Validation Suite Test Notes

Implemented on 2026-07-16.

## What changed

- The top-bar `Validate` action now scans both ROM data and the open `.smedit` project.
- ROM checks still include door destinations/spawn bounds, duplicate item collection bits, enemy GFX slot pressure, and room dimensions.
- New ROM checks scan PLM sets for valid pointers, terminators, and PLM coordinates outside room bounds.
- New project checks validate save-station AreaSave overrides, graphics/metatile/tileset-palette export fit, enemy tile-sheet edit exportability, and sprite palette payloads.
- The validation popup now calls out errors separately from warnings so release-blocking problems are easier to spot.

## Things to test

1. Open a known-good Super Metroid ROM and the demo `.smedit` project.
2. Click `Validate` from the top bar.
3. Confirm the popup appears quickly and groups results by category.
4. Confirm errors are red, warnings are amber, and the summary line reports total issues plus scan time.
5. Make a normal metatile edit, save the project, and click `Validate` again.
6. Confirm the Graphics Export category does not report the edited metatile table unless the compressed replacement no longer fits the original ROM allocation.
7. Place a save station, then click `Validate`.
8. Confirm the AreaSave category does not report a missing writable slot when the station selected a valid existing slot.
9. If an AreaSave warning appears for door pointer derivation, test the exported ROM resume path from that station in emulator before treating the project as release-ready.
10. Edit a normal enemy tile sheet, then click `Validate`.
11. Confirm the Sprite Export category does not report that enemy edit as non-exportable.
12. Edit a Samus, boss, or enemy sprite palette, then click `Validate`.
13. Confirm the Sprite Palettes category does not report the edited palette as malformed.

## Test-Covered Guardrails

- Duplicate AreaSave slot overrides are validation errors.
- Save-station overrides that point past the area's existing AreaSave table are validation errors.
- Malformed custom graphics/metatile project payloads are validation errors.
- Wrong-sized raw enemy tile-sheet edits are validation errors.
- Wrong-sized fixed sprite palette and enemy sprite palette edits are validation errors.
- Vanilla ROM PLM sets are expected to have terminators.

## Current Limits

- Validation is advisory in this pass; export is not automatically blocked yet.
- Compressed graphics, metatile, palette, and special boss block checks enforce the current conservative in-place export limit. ROM expansion/relocation will remove many of those size failures later.
- Scroll pointer bounds, room-state pointer consistency, room graph checks, and JSON import conflict validation are still future validation categories.
