# Kraid Boss — ROM Data Analysis

## Species IDs

All Kraid entities live in bank `$A0`. Confirmed from Kraid's room enemy set at `$A1:9EB5`.

> **NOTE:** `$D2BF` is **Squeept** (a Norfair lava enemy), `$D2FF` is Geruta, `$D33F` is Holtz.
> These are NOT Kraid. Prior incorrect documentation confused these IDs.

| Entity | Species ID | Bank $A0 Offset | PC Offset | HP | Contact Dmg | Size |
|--------|-----------|-----------------|-----------|-----|-------------|------|
| Kraid (body) | `$E2BF` | `$A0:E2BF` | `0x1062BF` | 1000 | 20 | 56×144 |
| Kraid (upper body) | `$E2FF` | `$A0:E2FF` | `0x1062FF` | 1000 | 20 | 48×48 |
| Kraid (belly spike 1) | `$E33F` | `$A0:E33F` | `0x10633F` | 1000 | 10 | 24×8 |
| Kraid (belly spike 2) | `$E37F` | `$A0:E37F` | `0x10637F` | 1000 | 10 | 24×8 |
| Kraid (belly spike 3) | `$E3BF` | `$A0:E3BF` | `0x1063BF` | 1000 | 10 | 24×8 |
| Kraid (flying claw 1) | `$E3FF` | `$A0:E3FF` | `0x1063FF` | 1000 | 20 | 8×8 |
| Kraid (flying claw 2) | `$E43F` | `$A0:E43F` | `0x10643F` | 10 | 10 | 8×8 |
| Kraid (flying claw 3) | `$E47F` | `$A0:E47F` | `0x10647F` | 10 | 10 | 8×8 |

Room ID: **`$A59F`** (Brinstar, area 1)  
AI Bank: **`$A7`** (shared with Phantoon, Etecoon, Dachora)  
Boss defeated flag: E629 condition arg `0x01` (Brinstar area boss)

## Stats — Editable Via Stat Block (bank $A0)

The 64-byte species header layout (offsets from species base PC):

| Offset | Size | Field |
|--------|------|-------|
| +4 | u16 LE | HP |
| +6 | u16 LE | Contact Damage |
| +8 | u16 LE | Hitbox Width |
| +10 | u16 LE | Hitbox Height |

### HP and Damage Addresses

| Parameter | SNES Address | PC Offset | Default |
|-----------|-------------|-----------|---------|
| Kraid HP | `$A0:E2C3` | `0x1062C3` | 1000 |
| Contact Damage | `$A0:E2C5` | `0x1062C5` | 20 |
| Belly Spike Damage | `$A0:E345` | `0x106345` | 10 (shared pattern × 3) |
| Flying Claw Damage | `$A0:E405` | `0x106405` | 20 |

> Belly spikes (E33F, E37F, E3BF) all default to 10 damage and HP=1000 (effectively indestructible).
> Flying claws vary: E3FF has HP=1000/Dmg=20, E43F/E47F have HP=10/Dmg=10.

## AI Routine Addresses (Bank $A7)

| Routine | SNES Address | PC Offset | Description |
|---------|-------------|-----------|-------------|
| Init AI | `$A7:0003` | `0x130003` | Kraid + all sub-entity initialization |
| Main AI | `$A7:0000` | `0x130000` | Per-frame main loop |
| Hurt AI | `$A7:800F` | `0x13800F` | Damage reaction handler |
| Touch AI | `$A7:949F` | `0x13949F` | Contact with Samus |
| Shot AI | `$A7:804C` | `0x13804C` | Projectile hit handler |

## What's Safely Modifiable (Data-Only Writes)

1. **HP** — `$A0:E2C3` (+4 from species base). Only the main body HP matters for the fight.
2. **Contact damage** — `$A0:E2C5` (+6 from species base). Controls body-slam damage.
3. **Belly spike damage** — `$A0:E345`, `$A0:E385`, `$A0:E3C5` (one per spike type).
4. **Flying claw damage** — `$A0:E405`, `$A0:E445`, `$A0:E485`.

## Behavior Editor Fields (Bank $A7)

The Kraid behavior editor writes named data-table words and selected immediate operands from the disassembly. Values are u16 little-endian words.

### Kraid Constants

| Field | SNES Address | Default | Notes |
|-------|--------------|---------|-------|
| Top lint timer | `$A7:A916` | `$0120` / 288 | Initial top lint function timer |
| Middle lint timer | `$A7:A918` | `$00A0` / 160 | Initial middle lint function timer |
| Bottom lint timer | `$A7:A91A` | `$0040` / 64 | Initial bottom lint function timer |
| Forward walk speed | `$A7:A91C` | `$0003` | Kraid forwards speed |
| Backward walk speed | `$A7:A920` | `$0003` | Kraid backwards speed |
| Lint X subspeed | `$A7:A926` | `$8000` | Fixed-point subspeed |
| Lint X speed | `$A7:A928` | `$0003` | Integer speed |

### Intro And Phase Timers

| Field | SNES Address | Default | Notes |
|-------|--------------|---------|-------|
| Intro delay | `$A7:AA6A` | `$012C` / 300 | Kraid function timer before raising through floor |
| Initial instruction timer | `$A7:AA77` | `$0040` / 64 | Initial Kraid instruction timer |
| Get-big pause | `$A7:C016` | `$00B4` / 180 | Delay before second phase/get-big sequence |
| Slow rock phase | `$A7:C8A4` | `$0078` / 120 | Raise-through-floor rocks every `$10` frames |
| Fast rock phase | `$A7:C8F3` | `$0060` / 96 | Raise-through-floor rocks every `$08` frames |
| Post-rise foot timer | `$A7:C973` | `$012C` / 300 | Kraid foot first-phase thinking timer |
| After mouth closes | `$A7:AEF3` | `$005A` / 90 | Kraid instruction timer after the mouth closes |
| Mouth reopen delay | `$A7:AF15` | `$0040` / 64 | Delay before forced mouth reopen |

### Fingernails

| Field | SNES Address(es) | Default | Notes |
|-------|------------------|---------|-------|
| Good fingernail timer | `$A7:AE5F` | `$0040` / 64 | Initial good fingernail function timer |
| Bad fingernail timer | `$A7:AE65` | `$0080` / 128 | Initial bad fingernail function timer |
| Horizontal nail X | `$A7:BE04` | `$0032` / 50 | Horizontal fingernail initial X |
| Horizontal nail Y | `$A7:BE0A` | `$00F0` / 240 | Horizontal fingernail initial Y |
| Horizontal nail X speed | `$A7:BE16` | `$0001` | Horizontal fingernail X velocity |
| Horizontal nail Y speed | `$A7:BE22` | `$0000` | Horizontal fingernail Y velocity |
| Diagonal up X speed | `$A7:BE50,$BE60,$BE70,$BE80` | `$FFFF` / -1 | Shared upward diagonal X velocity |
| Diagonal up Y speed | `$A7:BE54,$BE64,$BE74,$BE84` | `$0001` / 1 | Shared upward diagonal Y velocity |
| Diagonal down X speed | `$A7:BE58,$BE68,$BE78,$BE88` | `$FFFF` / -1 | Shared downward diagonal X velocity |
| Diagonal down Y speed | `$A7:BE5C,$BE6C,$BE7C,$BE8C` | `$FFFF` / -1 | Shared downward diagonal Y velocity |

## What Requires ASM (Not Simple Data Writes)

- **Rock spit speed/frequency** — Controlled by AI code, not a plain data table.
- **Number of rocks per volley** — Hardcoded in AI routines.
- **Rising sequence timing** — Part of the init and phase-transition code.
- **Phase transitions** — HP threshold comparisons are in ASM.

## Room Enemy Set

Enemy set at `$A1:9EB5` (default/alive state). Entries are 16 bytes each, terminated by `$FFFF`.

```
E2BF @ (256, 536)  ← main body
E2FF @ (232, 488)  ← upper body
E33F @ (200, 528)  ← belly spike row 1
E37F @ (176, 592)  ← belly spike row 2
E3BF @ (178, 648)  ← belly spike row 3
E3FF @ (256, 632)  ← flying claw 1
E43F @ (232, 488)  ← flying claw 2
E47F @ (232, 488)  ← flying claw 3
```

## Mini Kraid

Mini Kraid (the pre-fight encounter in Baby Kraid Room) uses separate species:

| Entity | Species ID | PC Offset |
|--------|-----------|-----------|
| Mini Kraid belly spike | `$E0FF` | `0x1060FF` |

## References

- Disassembly: https://patrickjohnston.org/bank/A7 (Kraid section near $8000)
- Room enemy set confirmed via ROM parse of room `$8F:A59F`, enemy set ptr `$A1:9EB5`
- Species IDs confirmed against `$A0` stat blocks — Squeept=`$D2BF`, Geruta=`$D2FF`, Holtz=`$D33F`
