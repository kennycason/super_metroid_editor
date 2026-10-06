# Mother Brain

Mother Brain is not one sprite sheet and phase 1 is not a smaller version of phase 2.
The encounter coordinates room-owned background art, independently drawn head/neck
OAM, an extended BG2/OAM body, staged DMA, and runtime palette effects. SMEDIT keeps
those owners visible instead of flattening them into an unsafe fictional source.

## Runtime identity

- Room header: `$8F:DD58`; live states `$8F:DD6E/$DD88`; defeated state `$8F:DDA2`
- Tileset: `$0E` in all three states
- AI, placement, head lists, and body maps: bank `$A9`
- Body `$EC7F` and head `$EC3F`, both initially at room position `($81,$6F)`
- Both headers declare 18,000 HP and 120 contact damage
- The body uses bit-15 alternate VRAM layout: header field `$8600`, actual
  `Tiles_MotherBrainBody` transfer `$0600`

Phase 1 draws the head enemy over the room's glass case, tubes, and machinery. Those
surroundings are level/BG art, not omitted head spritemap pieces. Phase 2 draws the
large body and then positions the head from neck segment 4 with a `-$15` Y offset.
Five independently positioned copies of `Spritemaps_MotherBrain_5` form the neck.

Phase 2 uses two coordinate systems that must not receive the same correction. Its
standard OBJ children (front/rear legs and other limbs) are already relative to the
body enemy origin. The BG2 torso is positioned by room scroll math, which places that
same origin at tilemap pixel `($20,$3E)` while standing, `($22,$3E)` while walking,
and `($26,$3E)` during the shifted crouch/stand transition poses. The renderer applies
only that pose-specific BG2 offset, then uses the source neck base `(+$20,-$32)` and
the runtime head rule above. This keeps the rear leg, feet, torso, neck, and head
aligned across the complete stand/crouch/walk inventory.

The extended child records around the two torso tilemaps contain X/Y fields, but the
engine's `ProcessExtendedTilemap` routine does not use them for placement. It copies
each word directly to the BG2 destination encoded inside the tilemap. SMEDIT therefore
ignores those child fields for Mother Brain: treating them as pixel offsets incorrectly
pulls the upper and lower torso apart while walking and leaves the central body behind
during crouching.

Layering is also structural rather than list-based. The dark rear-leg pieces use OBJ
priority 2 and palette row 3, so the high-priority BG2 torso covers them. The brighter
front leg and arms use OBJ priority 3 and palette row 1, so they render over the torso.
The editor preserves that BG2 priority boundary instead of flattening all children in
source-list order.

## Pixel ownership

| Visible role | Exact owner | Size / range | Editor policy |
|---|---|---:|---|
| Phase 1/2 head and neck OBJ | `Tiles_MotherBrainHead` at `$B7:8000` | `$1000` | Editable as `enemy:EC3F` |
| Phase 2 torso BG | tileset `$0E`, physical tiles `$160..1FF` from `Tiles_D_E_Tourian` | `$A0` tiles | Use tileset tools |
| Phase 2 limb OBJ | `Tiles_MotherBrainLegs` at `$B7:9000` | `$1000` | Read-only in combined diagnostic sheet |
| Body supplement | `Tiles_MotherBrainBody` at `$B0:E800` | `$0600` | Header-owned, but not a complete body |
| Bomb/hand-beam graphics | `Tiles_MotherBrain_BombShells_DeathBeam_UnusedGFX` at `$B7:A000` | `$0800` | Staged DMA / read-only |

The Sources tab therefore allows head edits and previews those edits in both phases.
Its body sheet is intentionally diagnostic: it concatenates four physical owners so
editing that flattened image could not be mapped back without ambiguity.

## Placement and animation inventory

The focused source manifest pins:

- 26 active standard Mother Brain spritemaps / 189 OAM entries;
- 16 active extended body/death-beam spritemaps / 145 child links;
- 6 active extended BG2 tilemaps / 47 runs / 290 words;
- 49 active body/head instruction lists containing 243 timed frame occurrences and
  253 handler occurrences;
- 5 unused standard maps, 10 unused extended maps, one unused BG2 map, and three
  unused instruction lists kept separate from production data.

SMEDIT's Animations view presents source-bounded encounter actions rather than scanning
nearby bank data: phase-1 idle; phase-2 neutral, forward/backward walk, crouch, stand,
blue rings, bomb, laser, rainbow charge, and the exact 15-frame death-beam body list;
plus phase-3 neutral, hyper-beam recoil, and death/corpse. Phase-2 previews assemble
the room-tile torso, OAM limbs, five neck segments, and independent head on one stable
canvas. Walking and crouch/stand lists also execute body-position handlers between
frames. The preview carries their cumulative deltas through the entire assembly: a
medium stride advances 24 pixels, while crouching/standing moves the body origin by
38 vertical pixels. This keeps the torso, neck, and head attached instead of swapping
lower-body poses beneath a stationary upper body. Neck articulation is represented by
the exact fake-death-ascent initialization geometry; arbitrary target-driven runtime
neck motion is not yet simulated.

Components drills down into the independently drawn head/neck maps and the complete
extended body poses. Compositions provides representative phase-1, phase-2, phase-3,
crouched, attacking, corpse, and death-beam states. As with Draygon and Phantoon, the
workspace opens on Animations, then narrows through Compositions, Components, and
Sources.

## Palettes and remaining effects boundary

The default main/head row is `Palette_MotherBrain` at `$A9:9472`; the independent
back-leg row is `$A9:9492`. Runtime health handling selects four 15-color pairs at
18,000-HP thresholds 9,000, 5,400, 1,800, and 0. Ten `$AD:E44A..E6A1` rainbow records
each contain a 15-color main row followed by a distinct 15-color back-leg row. The
editor exposes every one of these stages and keeps the two rows separate.

This completes the sprite-composition and palette preview boundary, not the entire
fight-effects engine. The rainbow-beam HDMA shape, projectiles, room destruction,
screen shake, and target-dependent neck AI remain engine effects rather than pixels
inside the Mother Brain sprite canvas.

## Parity and safe export

`./gradlew parityMotherBrain` writes ignored `parity/reports/mother-brain.json`.
`MotherBrainSourceParityTest` pins the owners and complete inventories above, proves
every curated animation frame against its named source list, and hashes all rendered
palettes, compositions, and animation frames. `parityReport` includes this focused
slice.

Only the exact `$B7:8000..8FFF` head owner is editable through the dedicated workspace.
Body placement, neck geometry, combined-source body pixels, instruction records, and
runtime effect tables remain read-only.
