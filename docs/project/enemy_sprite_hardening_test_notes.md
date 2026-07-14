# Enemy Sprite Hardening Test Notes

Use these checks after opening a known-good Super Metroid ROM and a normal `.smedit` project.

## Editable Source Path

The ROM-editable enemy graphics path is the raw 4bpp tile sheet. In the UI this is the Tile Sheet panel and the `Edit Tiles`, `Export Tile PNG`, and `Import Tile PNG` flow. Animation GIFs, assembled frame PNGs, and animation sprite-sheet PNGs are visual/reference exports; they are not source files that can be safely imported back into ROM graphics.

## Tile Sheet Import / Export

1. Open the Enemy Sprites view.
2. Select a regular enemy such as Zoomer (`$A0:DCFF`) or Sidehopper.
3. Confirm the Tile Sheet panel shows `ROM` and `ROM-EXPORT`.
4. Confirm the raw source line shows the byte count, tile count, expected byte count, and GRAPHADR address.
5. Click `Export Tile PNG` and save the tile-sheet PNG.
6. Edit a small area of the PNG externally using only colors already present in the enemy palette.
7. Click `Import Tile PNG` and select the edited file.
8. Confirm the tile sheet updates, the badge changes to `CUSTOM`, and the panel still shows `ROM-EXPORT`.
9. Confirm the assembled sprite preview and animation preview reflect the changed tiles where those tiles are used.
10. Save, close, and reopen the project; confirm the custom tile sheet persists.

## Pixel Editor Flow

1. Select a regular enemy with visible animation frames.
2. Click `Edit Tiles`.
3. Paint a few pixels using the fixed palette.
4. Confirm the live reference/preview updates before closing the editor.
5. Click Apply in the pixel editor.
6. Confirm the Tile Sheet panel shows `CUSTOM` and `ROM-EXPORT`.
7. Export the ROM and watch the log for `Patched enemy XXXX sprite tiles`.

## Palette Interaction

1. Edit one visible enemy palette color and apply the palette change.
2. Click `Edit Tiles` for the same enemy.
3. Confirm the pixel editor uses the edited palette colors.
4. Paint with the edited color and apply.
5. Confirm the resulting tile pixels use the edited palette color instead of snapping back to the original ROM palette.

## Guardrails

1. Try importing a PNG with the wrong dimensions for the current tile sheet.
2. Confirm import fails with an expected-dimensions message and does not change the project.
3. Open Mother Brain body (`$A0:EC7F`) if present.
4. Confirm generic tile editing is disabled there and the UI explains that MB2 body tiles are split across runtime tile sources.

## Visual Export Checks

1. For an enemy with animation frames, export a frame PNG, an animated GIF, and an animation sprite-sheet PNG.
2. Confirm the files render correctly and preserve transparency.
3. Confirm these exports do not create a custom enemy tile override by themselves.
4. Use `Export Tile PNG` instead when the intent is external editing and reimport.

## Current Limits

- Raw ordinary enemy tile sheets patch in-place at the species GRAPHADR and must match the species `tileDataSize` exactly.
- Special boss paths can involve compressed blocks, DMA transfers, room tiles, and runtime composition. Some of those are previewed, but they are not all safe generic tile-sheet edits yet.
- Assembled frame import is intentionally not exposed yet because it would need to reverse OAM placement, flips, animation frame selection, and runtime tile sources.
