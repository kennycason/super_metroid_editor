# Ridley

Norfair Ridley and Ceres Ridley are separate encounter headers over one visual
system. They share the same graphics, palette, body maps, wings, articulated tail,
and runtime ribs/claws transfers. The differences belong to encounter AI, stats,
layering, and palette selection—not a second sprite sheet.

## Runtime identity

| Encounter | Species | HP | Contact damage | Boss ID | Layer | Main AI |
|---|---:|---:|---:|---:|---:|---|
| Ceres | `$E13F` | 32767 | 5 | 1 | 5 | `MainAI_RidleyCeres` |
| Norfair | `$E17F` | 18000 | 160 | 5 | 2 | `MainAI_Ridley` |

Both headers use AI bank `$A6`, `InitAI_Ridley`, `Palette_Ridley` at `$A6:E14F`,
and the same `$2000`-byte graphics transfer beginning at `$B0:9400`. Ceres has its
own hit and retreat flow, including the baby Metroid. Norfair has the complete boss
fight, health/death flow, and time-is-frozen handler.

SMEDIT consequently presents one top-level **Ridley** workspace. Ceres-only actions
are labeled inside Animations; `$E13F` remains a distinct ROM species internally for
room behavior, header editing, validation, and export.

## Pixel ownership and runtime DMA

The base 256-tile OBJ source is five contiguous assets:

| Source | Address | Size |
|---|---:|---:|
| `Tiles_Ridley_0` | `$B0:9400` | `$0440` |
| `Tiles_Ridley_1` | `$B0:9840` | `$0200` |
| `Tiles_Ridley_2` | `$B0:9A40` | `$0F40` |
| `Tiles_Ridley_3` | `$B0:A980` | `$0200` |
| `Tiles_Ridley_4` | `$B0:AB80` | `$0880` |

Together they exactly fill `$B0:9400..B3FF`. The Sources tab safely edits this
single owner through `spriteTileBlocks["enemy:E17F"]`; both encounter previews use
the edited bytes.

The forward-turn body is the important exception to that page. `EnemySets_Ridley`
loads the `$0400`-byte `Tiles_RidleyExplosion` payload at `$B0:B400` with enemy-set
word `$E001`. `ProcessEnemySet_LoadPalettesAndEnemyLoadingData` interprets its high
layout bits as buffer offset `$0400`, so the transfer lands at VRAM `$6E00`—physical
OBJ tiles `$E0..FF`. `Spritemap_Ridley_FacingForward` deliberately omits the usual
`$100` OBJ-page bit and reads this shared low page. SMEDIT models that two-page VRAM
layout exactly and exposes the auxiliary page as a read-only source; treating `$E0`
as local Ridley tile `$1E0` produces a symmetric pile of tail and claw fragments.

Six additional assets at `$B0:B800..B9FF` provide two animated ribs stages and
clenched claws. The engine transfers `$40`-byte ribs blocks to VRAM `$7220/$7320`
and `$80`-byte claw blocks to `$7AC0/$7BC0`. They are read-only runtime DMA sources,
not part of the base `$2000`-byte edit range.

## Complete assembly

Ridley is not the four-child body map by itself. Bank `$A6` separately draws wings
and seven positioned tail pieces around it:

- 11 active extended body maps / 41 child links;
- 17 standard body-child maps / 164 OAM entries;
- 12 wing maps / 56 OAM entries;
- 3 segment sizes and 16 directional tail-tip maps / 19 OAM entries.

At runtime the custom passes append tail and wings around the normally queued body.
Because lower SNES OAM indexes win overlaps, the equivalent editor painter order is
body, then wings, then tail. The forward-facing turn frame suppresses wings and uses
the engine's vertical-tail geometry; that unusual middle silhouette is source-authentic.

The tail preview mirrors the seven initial distances and angles consumed by
`CalculateRidleyTailSegmentPositions`. Neutral left/right and straight pogo states
are representative initialized states; arbitrary tail-whip targeting remains runtime
physics rather than a fixed sprite frame.

## Animations and encounter differences

The focused manifest pins eight relevant instruction lists containing 34 timed frames:
six active lists and two source-declared unused right-facing Ceres mirrors. The editor
adds the procedural ten-step wing pointer loop and four-step ribs DMA loop, yielding
11 curated animation choices across the two facing filters.

Ceres-only visible actions are explicit:

- lunge, including the unreferenced right-facing mirror for inspection;
- retrieve baby Metroid at `$A6:E658`, whose active route faces left.

Opening roar and turn lists are shared with the Norfair visual system. The turn's
middle frames use `ExtendedSpritemap_Ridley_FacingForward` at `$A6:EAD7` exactly as
the source does.

## Palettes

The base palette supplies all 16 colors. Three 14-color replacement rows at
`$A6:E46A`, `$A6:E486`, and `$A6:E4A2` replace colors 1 through E.

Norfair chooses them at HP thresholds 9000, 5400, and 1800. Ceres feeds the same
rows from its hit counter; the vanilla routine begins changing at 50 hits and contains
the documented missing branch after its 90-hit comparison, so its exact stage behavior
is not equivalent to the Norfair thresholds. The editor labels these as neutral damage
stages and explains the encounter-specific selector beside the controls.

## Parity and edit boundary

`./gradlew parityRidley` writes ignored `parity/reports/ridley.json`.
`RidleySourceParityTest` pins both headers, all twelve base/auxiliary/DMA assets, the
enemy-set VRAM layout, every body, wing, and tail structure, the pointer/DMA tables,
four palette stages, source-backed animation records, and deterministic production-
render hashes for palettes, compositions, and all curated animations.

The base graphics are editable. OAM placement, tail physics, instruction records,
the auxiliary low OBJ page, runtime DMA sources, and the flattened assembled sprite
remain read-only because they do not map unambiguously to one writable pixel owner.
