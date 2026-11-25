# Baby Metroid patch

SMEDIT's Enemy > Baby Metroid editor manages the end-game Baby Metroid enemy
header at `$A0:EEBF` and its AI in bank `$A9`.

## Combat behavior

Vanilla writes `$7FFF` to the Baby's health every frame at `$A9:EFC8`, and its
shot AI at `$A9:F842` performs knockback without applying normal enemy damage.
Mortal mode:

- NOPs only that six-byte health restore.
- Relocates a guarded shot-AI shim into free space in bank `$A9`.
- Gives the Baby a private 22-byte vulnerability table in bank `$B4`.
- Repoints only the Baby's shot-AI and vulnerability pointers.

The guard checks `BabyMetroid.contactReactionFlag` (`$0FB6,x`). Shots remain
non-damaging during the opening corpse sequence. The native AI sets the flag
when it unlocks scrolling and clears both invisible walls, after which the shim
runs the original knockback reaction followed by normal damage/freeze/death
handling. This prevents an early kill from trapping Samus behind the scripted
walls.

The vanilla enemy header already selects `Common_NormalEnemyFrozenAI`. Ice
therefore freezes the current Baby spritemap with the engine's normal frozen
palette; no additional frozen sprite asset is required.

The original vulnerability table at `$B4:F12E` is shared by corpse enemies.
SMEDIT never modifies it for this feature. The private table keeps all
non-Ice beams and non-missile weapons immune, optionally freezes every Ice Beam
combination, and exposes independent raw missile and Super Missile multipliers.
As elsewhere in Super Metroid's tables, `2` is normal damage and `4` is double
damage.

For the same reason, the general Enemy Vulnerabilities grid and randomizer omit
the Baby. Persisted or headless `metroid_vuln*` settings fail export with a
message directing the user to the dedicated editor instead of silently changing
the corpse table.

The **50-Missile Preset** sets 5,000 HP and normal missile/Super vulnerability,
which is approximately 50 missiles or 17 supers at vanilla projectile damage.

## Movement tuning

The editor changes the acceleration-table operands at these end-game AI call
sites:

| Setting | Instruction(s) | Immediate operand(s) |
| --- | --- | --- |
| Opening rush agility | `$A9:F053`, `$A9:F07B` | `$A9:F054`, `$A9:F07C` |
| Samus chase agility | `$A9:F1EA` | `$A9:F1EB` |
| Latch approach agility | `$A9:F208` | `$A9:F209` |
| Remorse chase agility | `$A9:F436` | `$A9:F437` |

Values are table indexes from 0 (slowest acceleration) through 15 (fastest).
SMEDIT deliberately leaves the shared horizontal and vertical velocity caps in
the common movement routines unchanged, because those routines are also used by
the later Mother Brain Baby Metroid sequence.

## Export safety

Installation validates the vanilla health-restore bytes, original shot-AI
pointer, vulnerability pointer, and each movement operand opcode before writing.
The two relocated blocks use the shared free-space allocator and are restricted
to the banks required by the game's 16-bit pointer conventions. Headless builds
require an input ROM so those signatures and allocations can be verified;
patch-only output is rejected.
