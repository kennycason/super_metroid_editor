# Metatile Composer Test Notes

Use these checks after opening a known-good Super Metroid ROM and a normal `.smedit` project.

## Basic Composer Flow

1. Open the Tilesets view.
2. Select tileset `0`.
3. Click metatile `0x100` or any other visible non-CRE area metatile.
4. Click `Compose`.
5. Select each quadrant (`TL`, `TR`, `BL`, `BR`) and change one field at a time:
   - tile number
   - palette row
   - priority
   - H Flip
   - V Flip
6. Confirm the large composer preview updates immediately as fields change.
7. Use `Pick` to open the visual 8x8 tile picker, choose a different tile, and confirm the preview updates immediately.
8. Change the palette row and confirm the picker redraws tiles using that same palette.
9. Use the picker filters (`All`, `Area`, `CRE`) and confirm selected tile labels match the source.
10. Click `Apply`.
11. Confirm the full tileset grid and toolbar preview update after Apply.
12. Close the composer and confirm the toolbar preview stays in sync.

## Project Save / Reload

1. Save the project.
2. Close and reopen SMEDIT.
3. Reopen the same project and ROM.
4. Open the same tileset/metatile.
5. Confirm the changed subtile words and rendered preview are still present.
6. Confirm the Revert menu shows `Revert Area Metatiles` as enabled.

## Shared CRE Table

1. Select a low-index CRE metatile, such as `0x000` on a normal non-Kraid tileset.
2. Change one quadrant in `Compose` and click `Apply`.
3. Switch to another tileset.
4. Confirm the same CRE metatile reflects the shared-table change there too.
5. Use `Revert Common Metatiles (CRE)` and confirm it returns to ROM defaults.
6. Pick an Area tile while editing a shared CRE metatile and confirm the warning appears.

## ROM Export

1. Make one small area-metatile change and export the ROM.
2. Watch the export log for `Patched tileset N metatile table in-place`.
3. Reopen the exported ROM and confirm the metatile change is present.
4. Make one small CRE-metatile change and export the ROM.
5. Watch the export log for `Patched CRE metatile table in-place`.
6. Reopen the exported ROM and confirm the CRE change is present.

## Known Limit

Metatile tables currently follow the same conservative export rule as custom tiles and palettes: compressed replacement data is written only when it fits the original compressed allocation. If the log says the compressed metatile table exceeds the original size, the project data is still saved, but ROM export skips that table until relocation/ROM expansion is implemented.
