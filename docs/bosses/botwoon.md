# Botwoon

Botwoon is one visible boss assembled by two runtime object systems. The bank-`$B3`
enemy owns the head, movement, health palettes, and position history. Initialization
spawns thirteen `EnemyProjectile_BotwoonsBody` objects from bank `$86`: twelve body
segments and one tail. Their one-entry OAM maps live in bank `$8D`. A head-only enemy
preview is therefore incomplete, while a single rigid “long sprite” is behaviorally
incorrect.

## Runtime identity and pixels

| Owner | Address | Role |
|---|---:|---|
| `EnemyHeaders_Botwoon` | `$A0:F293` | Head, stats, AI bank `$B3`, layer 5 |
| `Tiles_Botwoon` | `$B7:E300` | Shared `$1800`-byte OBJ payload |
| `EnemyProjectile_BotwoonsBody` | `$86:EBA0` | Twelve body objects plus one tail |
| `EnemyProjectile_BotwoonsSpit` | `$86:EC48` | Five-frame spit projectile |

The one 192-tile transfer supplies the head, body, tail, and spit. SMEDIT consequently
stores edits only as `spriteTileBlocks["enemy:F293"]`. The head and projectile maps
are independent placement owners, but not independent pixel owners.

## Position-history body

`UpdateBotwoonPositionHistory` (`$B3:9C7B`) writes the head's X/Y position into a
`$400`-byte circular buffer. Each record is four bytes. The body updater at
`$B3:9C90` starts one configured history distance behind the head and subtracts the
same distance for each successive projectile.

The source speed table at `$B3:94BB` is easy to misread because its “body travel
time” is used as a byte offset into those four-byte records:

| Health | Speed | History-byte distance | History frames | Steady segment distance |
|---|---:|---:|---:|---:|
| at least 50% | 2 px/frame | `$18` | 6 | 12 px |
| 25–49% | 3 px/frame | `$10` | 4 | 12 px |
| below 25% | 4 px/frame | `$0C` | 3 | 12 px |

Thus health changes how quickly the history advances, not the normal spacing of the
worm. The earlier generic preview's 16-pixel cardinal gaps were not source-accurate.
The dedicated renderer accumulates the 12-pixel distance, including alternating
rounded diagonal coordinates, rather than multiplying one rounded diagonal step.

`SetBotwoonInstListTableIndices` (`$B3:9D4D`) computes the vector from each segment
to the preceding segment. That vector chooses one of eight body lists; the last
projectile receives the corresponding tail list. This matters on turns: each link
can use a different orientation. The editor's curved position-history composition
exercises that rule rather than repeating one body map across an arc.

Hole traversal toggles projectile visibility when the relevant circular-history
index reaches the recorded crossing. The “Emerging from a hole” composition exposes
the partial-chain state, but it is an isolated sprite representation—not a simulation
of room collision, priority against the hole tiles, or Botwoon's target-hole AI.

## Head, body, and spit animation

The active runtime direction tables select eight visible closed-head lists and eight
spit lists. Two left-facing vertical alternatives exist in source but are not selected
by the runtime table; source-declared unused structures remain inventoried by parity.

Every body direction has four one-entry maps displayed for eight ticks each. The
head and body use independent interpreters. During a spit, the head remains closed
for 32 ticks and then open for 16 ticks, except the left-facing list, which remains
open for 25 ticks. The dedicated animation compositor continues the body’s four-phase
loop across those head durations instead of freezing the body on one phase. The spit
projectile separately loops five maps for three ticks each.

## Palettes and editing

The header palette at `$B3:9319` matches runtime health row 0. Eight runtime rows at
`$B3:971B` advance through the source thresholds 3000, 2625, 2250, 1875, 1500, 1125,
750, and 375 HP. All eight are selectable in the compact Runtime Health Palette
control. The UI also reports the matching movement speed/history stage so the palette
choice communicates the related runtime state without claiming that the two tables
share identical thresholds.

The workspace follows the complex-sprite drill-down used by the other bosses:
Animations, Compositions, Components, and Sources. Animations opens first. Components
separates bank-`$B3` head maps from bank-`$8D` body, tail, and spit maps. The shared
`Tiles_Botwoon` source is editable with live full-body references; position history,
OAM, instruction lists, visibility state, and movement AI remain read-only.

## Parity boundary

`./gradlew parityBotwoon` writes ignored `parity/reports/botwoon.json`.
`BotwoonSourceParityTest` pins the `$F293` header, exact `$1800`-byte pixel owner,
30 active plus ten unused head maps, 46 active plus 52 unused projectile maps,
26 active plus five unused head instruction lists, 18 active projectile lists, nine
critical runtime tables/routines, the header plus eight health palette rows, and 17
guided animations / 87 rendered frames. Deterministic hashes cover every exposed
palette, composition, component, and animation frame.

The renderer stops at the visual ownership boundary. Random hole choice, collision,
damage, body death/fall/explosions, sound, room priority, and full fight-path playback
remain engine behavior rather than editable sprite data.
