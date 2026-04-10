# Super Metroid Enemy System Reference

## Enemy Species Header (Bank $A0, 64 bytes each)

Each enemy species has a 64-byte header at `$A0:XXXX`. The species ID is the 16-bit
address within bank $A0. The name pointer at offset `+0x3E` points to a ROM string
in bank $B4 (Japanese dev names like RSTONE, SABOTEN, HOTARY).

## Enemy Population Set (Bank $A1)

Each room state references an enemy population set. Each entry is 16 bytes:

| Offset | Size | Field |
|--------|------|-------|
| +0 | 2 | Species ID (bank $A0 address) |
| +2 | 2 | X position (pixels) |
| +4 | 2 | Y position (pixels) |
| +6 | 2 | Init parameter |
| +8 | 2 | Properties (Speed, Tilemaps, etc.) |
| +10 | 6 | Extra data |

Terminated by species ID `0xFFFF`.

## Multi-Piece Enemies and Possessors

Not all multi-piece enemies are possessors, but all possessors are multi-piece enemies.
Possessors usually require at least two pieces (enemy indexes) to function completely,
and some forcibly use a neighboring enemy index as their other piece, which could crash
the game if it's the wrong type.

### Possessor Types

- **Type A**: Automatically possesses neighboring index enemies. Wrong type = crash.
- **Type B**: Should only possess identical/intended types. Wrong type = probably won't work.
- **Type C**: Only possesses pieces when given correct speed values.
- **Type D**: Can possess other enemy types and still function. Creative hack potential.

### Possessor Enemies

| Enemy | ID(s) | Pieces | Type | Notes |
|-------|-------|--------|------|-------|
| Shaktool | $F07F | 7, all required | A | |
| Gamet | $F213 | 5, all required | B | |
| Dachora | $E5FF | 5 (main + 4 shinespark echoes) | B | Pieces 2-5 not required if last enemy index |
| Bang | $DB3F | 4 | D | Piece 1=possessed enemy (optional), 2=orange core, 3=respawn bubble (optional), 4=initial bubble |
| Lavaman | $E83F | 3, all required | C | 1=floating head, 2=rising body, 3=body throwing lavaballs |
| Evir | $E63F, $E67F | 3 | B | 1=falling body, 2=animated legs (optional), 3=spiny projectile (optional, separate type) |
| Samus's Ship | $D07F, $D0BF | 3, all required | B | 1=main (enter/hover/top GFX/thrusters), 2=bottom GFX, 3=unknown but required |
| Dragon | $D4BF | 2, both required | A | 1=main enemy, 2=animated wings |
| Hibashi | $E07F | 2, both required | D | 1=graphics and sound, 2=hitbox movement |
| Puu | $E8BF | 2, both required | A | 1=grapplable bottom, 2=main rising body |
| Kzan | $DFFF, $E0BF | 2 | B | 1=fully functioning enemy, 2=separate type (unused?) |
| Kihunter | $EABF/$EB3F/$EBBF + $EAFF/$EB7F/$EBFF | 2 | B/D | 1=main, 2=wings (separate type, not in room list). Wings can attach to non-Kihunter enemies. |

### Multi-Piece Enemies (Non-Possessor)

These don't require all pieces; missing pieces just remove some visual element:

| Enemy | ID | Pieces | Notes |
|-------|-----|--------|-------|
| Geruta | $D2FF | 2 | 1=main, 2=flame animation |
| Squeept | $D2BF | 2 | 1=main, 2=flame animation |
| Holtz | $D33F | 2 | 1=main, 2=flame animation |
| Eye | $E6BF | 2 | 1=neck (wall mount, yellow light, sound), 2=eye following Samus |

Excluding Holtz/Geruta/Squeept flame pieces can create variants (e.g., blue Squeept
from water instead of lava) when combined with shared palette values.

### Important Rules

- Certain enemies (Wrecked Ship ghosts, bosses) must be **enemy index $00** (first in the list).
- Possessor piece indexes must be **adjacent** in the enemy list.
- Incorrect enemy data values for possessors can easily crash the game.
- Enemies that should NOT be in the Room Enemies list: elevators, escape steam,
  Zebetites, Kihunter wings, Evir projectile, Kzan duplicate.

## Enemy Name Mapping

Our name mapping is verified against:
1. SMILE GIF sprites (visual identification)
2. ROM name strings at bank $B4
3. Community standard names (speedrunning wiki)

Key corrections from vanilla SM analysis (April 2026):

| ID | SMILE Dev Name | Community Name | Visual |
|----|----------------|----------------|--------|
| $CFFF | SABOTEN | Cacatac | Cactus with spines |
| $D03F | TOGE | Owtch | Thorn enemy |
| $D0FF | — | Mellow | Purple flying bat |
| $D27F | — | Reo | Green flying creature |
| $D3BF | HIRU | Choot | Leech enemy |
| $D6BF | HOTARY | Fireflea | Brinstar firefly |
| $D6FF | FISH | Skultera | Maridia fish |
| $D77F | KANI | Sciser | Crab enemy |
| $D7FF | KAMER | Tripper | Falling enemy |
| $D87F | SBUG | Reo | Reo variant |
| $D93F | SSIDE | Sidehopper | Small green hopper |
| $D97F | SDEATH | Dessgeega | Blue 4-legged hopper |
| $D9BF | SIDE | Sidehopper (big) | Large green hopper |
| $DA3F | DESGEEGA | Dessgeega | Large variant |
| $DABF | — | Viola | Blue/turquoise sphere |
| $DCBF | NOVA | Sova | Orange Norfair wall crawler |
| $DD3F | MZOOMER | Sova (grey) | Grey invincible wall crawler |
| $DFBF | — | Boulder | Rolling stone in Blue Brinstar |
| $E03F | — | Kihunter | Pink/purple winged insect |
| $E5FF | — | Dachora | Ostrich-like friendly creature |
| $E63F | EBI | Evir | Falling body enemy, 3 pieces |
| $E6BF | EYE | Eye | Eye enemy attached to wall |
| $E7BF | HAND | Yapping Maw | Skull-faced snapping creature |
| $E87F | — | Beetom | Green hopping bug |
| $E8BF | PUU | Puu | Grapple point puffball |
| $EABF | HACHI1 | Kihunter (green) | Green body, pink wings |
| $EB3F | HACHI2 | Kihunter (red) | Red/Norfair variant |
| $EBBF | HACHI3 | Kihunter (gold) | Gold/Tourian variant |
| $F07F | DORI | Shaktool | 7-piece digging robot |
| $F193 | ZEB | Zeb | Small spawner fly |
| $F1D3 | ZEBBO | Zebbo | Spawner fly variant |
| $F353 | BATTA1 | Space Pirate | Crateria pirate |

Note: SMILE dev names are Japanese abbreviations found in bank $B4 of the ROM.
HACHI = "bee" (Kihunter), SSIDE = "small side(hopper)", SDEATH = "small death(geega)",
NOVA = "nova" (community: Sova), HOTARY = "hotaru" = firefly.
