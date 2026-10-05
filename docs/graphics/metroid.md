# Metroid sprite ownership

The normal Metroid (`$DD7F`) is not one ordinary enemy spritemap. Its live image is
three independently timed OAM owners at the same position:

| Layer | Runtime owner | Maps | Source bank |
|---|---|---:|---:|
| Insides | Enemy `$DD7F` | 4 | `$A3` |
| Electricity | Sprite object `$32` | 24 | `$B4` |
| Shell | Sprite object `$34` | 3 | `$B4` |

All three use the same `$1000`-byte `Tiles_Metroid` transfer at `$AE:9000` and the
same palette at `$A3:E9AF`. The pixels are therefore one safe editable owner. OAM
placement, instruction timing, and the two companion allocations are read-only.

## The important fallthrough rule

The electricity entry list at `$B4:C3BA` has no terminating instruction before the
source label at `$B4:C436`; execution falls directly into the list labeled as unused
sprite object `$33`. That continuation eventually loops to itself. The shell does
the same from `$B4:C4B6` into the list labeled as unused object `$35` at `$B4:C536`.

Those labels accurately say that objects `$33/$35` are not created directly, but
their instruction bodies are still active runtime continuations for objects `$32/$34`.
The `$35` continuation is also where the third shell map enters the live animation.
Code and parity reports must not discard a source block solely because its label says
`UNUSED`; control flow decides reachability.

## Timing and editor model

The enemy-inside chasing list has 20 timed occurrences over 256 ticks; draining has
five over 64 ticks. Electricity has a 31-occurrence, 93-tick intro followed by a
31-occurrence, 115-tick steady loop. Shell has a 32-occurrence, 32-tick intro followed
by a 30-occurrence, 30-tick steady loop. Empty draw records are real transparent
frames, not parse failures.

The dedicated workspace opens on Animations and synchronizes all three tracks on a
common runtime clock. Compositions show representative complete states, Components
isolates every source map, and Sources edits the shared tile owner with live complete
Metroid references. The draining preview resets the enemy's drain list while keeping
the already-running shell and electricity phase as a representative synchronized
window; exact transition phase depends on when Samus is latched in gameplay.

`./gradlew parityMetroid` generates `parity/reports/metroid.json` from the pinned
disassembly and rebuilt ROM. `MetroidSourceParityTest` pins the header, pixels,
palette, all 31 maps, all four companion tracks, both fallthrough edges, source
timings, and deterministic composition/component/animation pixels.
