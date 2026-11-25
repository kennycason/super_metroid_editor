# Super Metroid Enemy System Reference

## Enemy Species Header (Bank $A0, 64 bytes each)

The vanilla NTSC source assembles 164 enemy species headers, each 64 bytes at
`$A0:XXXX`. The species ID is the 16-bit address within bank $A0. The name pointer
at offset `+0x3E` points to a ROM string in bank $B4 (Japanese dev names like
RSTONE, SABOTEN, HOTARY).

This inventory is source/ROM verified as of 2026-10-04. `parityEnemyHeaders`
evaluates all 4,756 `EnemyHeader` macro fields, checks the complete records through
SMEDIT's production parser, and maps the 155 nonempty raw `GRAPHADR` associations
to named extracted assets. The mapping deliberately preserves shared starts,
exact aliases, partial overlaps, and transfers spanning adjacent assets. See
[`../graphics/sprites.md`](../graphics/sprites.md#source-backed-header-and-graphadr-verification-2026-10-04)
for the byte layout and edit-safety implications; machine-readable evidence is in
ignored `parity/reports/enemy-headers.json`.

## Vulnerability Table (Bank $B4)

Enemy header offset `+$3C` points to a 22-byte vulnerability table in bank `$B4`.
The byte indexes are source-verified against `EnemyVulnerabilities` in
`labels.asm`; they are not the reverse presentation order historically used by
some editor UIs:

| Index | Weapon/contact case |
|---:|---|
| 0–3 | Power, Wave, Ice, Ice + Wave |
| 4–7 | Spazer, Spazer + Wave, Spazer + Ice, Spazer + Ice + Wave |
| 8–11 | Plasma, Plasma + Wave / shinespark echo, Plasma + Ice, Plasma + Ice + Wave |
| 12–15 | Missile, Super Missile, Bomb, Power Bomb |
| 16–18 | Speed Booster contact, Shinespark contact, Screw Attack |
| 19–21 | Charged Beam (including SBA), Pseudo-Screw Attack, Unused |

Pseudo-Screw is spin-jump contact while holding a fully charged beam; it is not
Space Jump. The low seven bits store twice the damage multiplier (`2` = 1× and
`4` = 2×). Bit 7 normally prevents freezing, so `130` (`$82`) means normal
damage without freezing. `$FF` is the special freeze-without-damage value.

The end-game Baby Metroid (`$EEBF`) shares its vanilla table with corpse
enemies, so SMEDIT manages its mortal/freezable behavior through a private
relocated table. See [Baby Metroid patch](baby_metroid.md).

## Enemy Population Set (Bank $A1)

Each room state references an enemy population set. Each entry is 16 bytes:

| Offset | Size | WRAM | Field | Description |
|--------|------|------|-------|-------------|
| +0 | 2 | $0F78,x | Species ID | Bank $A0 address of species header |
| +2 | 2 | $0F7A,x | X position | Pixel X coordinate in room |
| +4 | 2 | $0F7E,x | Y position | Pixel Y coordinate in room |
| +6 | 2 | $0F92,x | Init parameter | Stored to instruction list pointer; meaning varies by species |
| +8 | 2 | $0F86,x | Properties | Per-instance behaviour flags (see below) |
| +10 | 2 | $0FAE,x | GfxExtra (extra1) | Species-specific graphics parameter |
| +12 | 2 | $0FB4,x | Speed (extra2) | Species-specific; often encodes facing direction |
| +14 | 2 | $0FB6,x | Speed2 (extra3) | Species-specific additional parameter |

Terminated by species ID `0xFFFF`.

The native loader clears `$0800` bytes at `Enemy.ID` (`$A0:8A76..8A87`)
and advances the runtime slot by `$40` per record (`$A0:8BB7..8BBC`).
`memory.asm` places the next owner at `$1778`, after the array starting `$0F78`:
the native capacity is **32 slots**, not the tolerant parser's 64-record bound.
AI-spawned or possessed companions can require additional slots; byte-valid
population data alone does not prove that an encounter has enough free slots.
The word terminator is followed by one required-kill-count byte. Empty native
populations sometimes omit that byte because initialization exits before reading it.

Reciprocal ASM authoring keeps population and GFX values in one `bank_A1.asm`
buffer, with independent state references and checked placement into `$A1/$B4`.
The GFX loader matches species IDs, not equal graphics pointers. New transfers
are checked against four entries, palette offsets 0–7, LoROM source boundaries,
and the `$7000..97FF` staging buffer. Zero-byte graphics headers must not acquire
invented transfer entries. See [enemy source authoring](../asm/reference_workspace.md#reciprocal-enemy-populations-and-gfx).

### Properties Field Flags (offset +8)

| Bit | Mask | Name |
|-----|------|------|
| 0 | 0x0001 | Invisible (don't draw) |
| 9 | 0x0200 | Persist off-screen |
| 10 | 0x0400 | Platform (walkable) |
| 11 | 0x0800 | Non-responsive (no damage) |
| 12 | 0x1000 | Extended spritemap |
| 13 | 0x2000 | Solid to beams |

### Facing Direction Encoding

Enemy facing is **not universal** — each species uses different fields and bits.
Most enemies determine facing at **runtime** via AI (reacting to Samus position,
walls, etc.). The following species encode static facing in the population data:

| Species | Field | Bit/Value | Default (0) | Set |
|---------|-------|-----------|-------------|-----|
| Wall Space Pirates ($F353..$F493) | Speed (extra2) | bit 0 (0x0001) | Right-facing (left wall) | Left-facing (right wall) |
| Ninja and walking Space Pirates ($F4D3..$F793) | Speed (extra2) | bit 0 (0x0001) | Left-facing | Right-facing |
| Namihe ($E73F) | Speed (extra2) | bit 4 (0x0010) | Right wall | Left wall |
| Fune ($E6FF) | Speed (extra2) | bit 4 (0x0010) | Right wall | Left wall |
| Ripper ($D3FF/$D43F/$D47F) | Init parameter | non-zero | Right | Left |
| Geruta ($D2FF) | Init parameter | non-zero | Right | Left |
| Waver ($D63F) | Init parameter | non-zero | Right | Left |
| Holtz ($D33F) | Init parameter | non-zero | Right | Left |

**Space Pirate facing:** All 18 variants test `$0FB4,x` (`Enemy.init0`,
Speed/extra2) bit 0, but wall mounting and facing are not interchangeable.
Wall initialization at `$B2:EF9F` chooses `$ED36` (left wall, facing right)
or `$EDAC` (right wall, facing left). Ninja initialization at `$B2:F5DE`
and walking initialization at `$B2:FD02` instead choose explicitly left/right-facing
lists, with bit 0 clear for left and set for right. The editor toggle follows each
family's encoding and preserves all unrelated speed bits.

**Namihe/Fune facing** (verified from disassembly at `$A8:96F2`):
The init AI loads `$0FB4,x` (Speed/extra2), masks bits 0-3, and branches on
the result. Bit 4 (`0x0010`) determines wall mounting side.

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
| Lavaman / Magdollite | $E83F | 3, all required | C | 1=floating head, 2=rising pillar, 3=hand throwing lavaballs |
| Evir | $E63F, $E67F | 3 | B | 1=falling body, 2=animated legs (optional), 3=spiny projectile (optional, separate type) |
| Samus's Ship | $D07F, $D0BF | 3, all required | B | 1=main (enter/hover/top GFX/thrusters), 2=bottom GFX, 3=unknown but required |
| Dragon | $D4BF | 2, both required | A | 1=main enemy, 2=animated wings |
| Hibashi | $E07F | 2, both required | D | 1=graphics and sound, 2=hitbox movement |
| Puu | $E8BF | 2, both required | A | 1=grapplable bottom, 2=main rising body |
| Kzan | $DFFF, $E03F | 2 | B | 1=visible spike-platform top, 2=invisible lower collision follower |
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
| $D87F/$D8BF | SBUG | Sbug (roach) | Eight-direction animated roach; second header uses alternate VRAM layout |
| $D93F | SSIDE | Sidehopper | Small green hopper |
| $D97F | SDEATH | Dessgeega | Blue 4-legged hopper |
| $D9BF | SIDE | Sidehopper (big) | Large green hopper |
| $DA3F | DESGEEGA | Dessgeega | Large variant |
| $DABF | — | Viola | Blue/turquoise sphere |
| $DCBF | NOVA | Sova | Orange Norfair wall crawler |
| $DD3F | MZOOMER | Sova (grey) | Grey invincible wall crawler |
| $DFBF | — | Boulder | Rolling stone in Blue Brinstar |
| $E03F | — | Kzan bottom | Invisible collision follower; `$DFFF` owns the visible spike platform |
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
