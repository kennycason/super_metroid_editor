# Spore Spawn

Spore Spawn is one visual character assembled by three runtime owners. Bank `$A5`
draws the head/body as extended OAM, bank `$86` owns the stalk and spore enemy
projectiles, and bank `$8D` supplies those projectile spritemaps. A head-only enemy
preview is therefore structurally incomplete even when its individual frame is correct.

## Runtime identity and shared pixels

| Header | Species | Runtime role | Layer | Graphics |
|---|---:|---|---:|---|
| `EnemyHeaders_SporeSpawn` | `$DF3F` | Boss body/AI | 2 | `$0E00` bytes from `$AC:9C00` |
| `EnemyHeaders_SporeSpawnStalk` | `$DF7F` | Shared drop/header identity | 5 | same `$0E00` bytes |

Both headers point to `Tiles_SporeSpawn`. The body, visible stalk segments, four
spore spawners, and spores all index this one physical pixel owner. SMEDIT therefore
stores edits only as `spriteTileBlocks["enemy:DF3F"]`; `$DF7F` is an alias, not a
second writable sheet.

The `$DF7F` name is historically misleading for rendering: the visible stalk is not
an independently populated `$DF7F` enemy. Spore Spawn's initialization AI creates
four instances of `EnemyProjectile_SporeSpawnStalk` (`$86:DE6C`). Its enemy header is
used by shared enemy/drop behavior, while the actual stalk pixels are drawn through
the projectile system.

## Body and stalk assembly

The body has 22 active standard OAM maps containing 365 entries. Twelve active
extended maps combine those structures into dead, closed/opening, and fully-open
states; seven additional extended maps are explicitly unused in source.

Every stalk segment uses the one-entry map at `$8D:A994`. The initialization routine
records the body origin `(80h, 270h)` and stalk origin `(80h, 228h)`. On each update,
`$A5:EC49` targets `(body X, body Y - 28h)` and places the four projectiles at:

1. the fixed base `(80h, 230h)`;
2. one quarter of the signed origin-to-target delta;
3. one half of that delta;
4. three quarters of that delta.

The fractions use the engine's integer shifts, including truncation toward zero for
negative deltas. The dedicated renderer reproduces this arithmetic rather than
stretching or spacing a decorative stalk by eye. Complete compositions expose the
descent and representative fight extents; animation poses preserve exact source body
frames and use a documented representative runtime anchor. They do not claim to
simulate the fight's full cosine/sine movement path.

## Projectiles and palettes

The workspace consolidates the bank-`$86` spore-spawner open/close sequence and the
three-frame looping spore sequence alongside the boss animations. Components expose
their seven one-entry bank-`$8D` maps independently: one stalk, three spawner, and
three spore maps.

The source contains 27 relevant 16-color rows:

- one base row for spores;
- four health-based body/stalk rows at HP thresholds 770, 410, and 70;
- eight body rows used while the corpse hardens;
- seven room-level and seven background rows used by the death palette effect.

The editor's compact Runtime Palette control exposes the four health and eight body
death rows that affect sprite pixels. The 14 room palette-effect rows remain pinned
and documented but are not painted into the isolated sprite canvas.

## Animations and editing

All nine active bank-`$A5` instruction lists are decoded within their named source
boundaries: 41 timed body-frame occurrences and 45 handler occurrences. The guided UI
shows the six multi-frame body lists (38 frames), plus the source spawner and spore
projectile animations. The seven hardening frames apply death palette rows 0–6 in
sequence instead of displaying seven visually identical corpse frames.

The editor opens on Animations and follows the common complex-sprite drill-down:
Animations, Compositions, Components, and Sources. `Tiles_SporeSpawn` is safely
editable with live complete-body references. OAM placement, instruction records,
stalk interpolation, projectile logic, and room palette effects remain read-only.

## Parity boundary

`./gradlew paritySporeSpawn` writes ignored `parity/reports/spore-spawn.json`.
`SporeSpawnSourceParityTest` pins both headers, the exact `$0E00`-byte shared asset,
22 standard maps, 12 active and seven unused extended maps, seven projectile maps,
all nine body lists, all 27 palettes, the cross-bank ownership recipe, and
deterministic palette/composition/component/animation pixels.

The sprite renderer intentionally stops at the visual ownership boundary. Fight-path
AI, collisions, spore movement, dust/explosion objects, room scrolling, sound, and
the level/background death-palette effect remain engine behavior rather than pixels
flattened into the editable source.
