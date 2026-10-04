# Kraid Boss — ROM Data Analysis

## Species IDs

All Kraid entities live in bank `$A0`. Confirmed from Kraid's room enemy set at `$A1:9EB5`.

> **NOTE:** `$D2BF` is **Squeept** (a Norfair lava enemy), `$D2FF` is Geruta, `$D33F` is Holtz.
> These are NOT Kraid. Prior incorrect documentation confused these IDs.

| Entity | Species ID | Bank $A0 Offset | PC Offset | HP | Contact Dmg | Size |
|--------|-----------|-----------------|-----------|-----|-------------|------|
| Kraid | `$E2BF` | `$A0:E2BF` | `0x1062BF` | 1000 | 20 | 56×144 |
| Kraid arm | `$E2FF` | `$A0:E2FF` | `0x1062FF` | 1000 | 20 | 48×48 |
| Kraid lint (top) | `$E33F` | `$A0:E33F` | `0x10633F` | 1000 | 10 | 24×8 |
| Kraid lint (middle) | `$E37F` | `$A0:E37F` | `0x10637F` | 1000 | 10 | 24×8 |
| Kraid lint (bottom) | `$E3BF` | `$A0:E3BF` | `0x1063BF` | 1000 | 10 | 24×8 |
| Kraid foot | `$E3FF` | `$A0:E3FF` | `0x1063FF` | 1000 | 20 | 8×8 |
| Kraid nail | `$E43F` | `$A0:E43F` | `0x10643F` | 10 | 10 | 8×8 |
| Kraid nail (bad trajectory) | `$E47F` | `$A0:E47F` | `0x10647F` | 10 | 10 | 8×8 |

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
| Lint Contact Damage | `$A0:E345` | `0x106345` | 10 (same default for all three lints) |
| Foot Contact Damage | `$A0:E405` | `0x106405` | 20 |

> The old SMEDIT labels “belly spikes” and “flying claws” conflated separate source entities.
> The assembly names `$E33F..E3BF` lints, `$E3FF` the foot, and `$E43F/$E47F` nails.

## AI Routine Addresses (Bank $A7)

| Routine | SNES Address | PC Offset | Description |
|---------|-------------|-----------|-------------|
| Init AI | `$A7:A959` | `0x13A959` | Main Kraid initialization |
| Main AI | `$A7:AC21` | `0x13AC21` | Per-frame main loop |
| Hurt AI | `$A7:804C` | `0x13804C` | No-op Kraid hurt callback; palette flashing is handled by main AI |
| Touch AI | `$A7:949F` | `0x13949F` | Contact with Samus |
| Shot AI | `$A7:804C` | `0x13804C` | Projectile hit handler |

## What's Safely Modifiable (Data-Only Writes)

1. **HP** — `$A0:E2C3` (+4 from species base). Only the main body HP matters for the fight.
2. **Contact damage** — `$A0:E2C5` (+6 from species base). Controls body-slam damage.
3. **Lint damage** — `$A0:E345`, `$A0:E385`, `$A0:E3C5` (top/middle/bottom).
4. **Foot/nail damage** — `$A0:E405`, `$A0:E445`, `$A0:E485`.

## Graphics Composition and Safe Ownership

Kraid is not one giant enemy spritemap. The source-backed render recipe has three
separate resource families:

1. `Tiles_1A_Kraid` (`$BC:DFF0`, compressed) expands to exactly 32 KiB / 1024
   4bpp BG tiles. Tileset `$1A` has no CRE graphics overlay, so the complete
   decompressed resource is the safe edit/export unit (`varGfx["26"]`).
2. `Background_Brinstar_1A_Kraid_Upper` (`$B9:FA38`) and
   `...Lower_0` (`$B9:FE3E`) each expand to two 32×32 BG2 screen blocks. Together
   they form the active 64×64 BG2 map. `...Lower_1` exists in source but is only
   referenced by an unreferenced library-background definition. Each active map is
   consumed by both the room library-background loader and
   `SetupKraidGFXWithTheTilePriorityCleared`.
3. `Tilemap_KraidHead_0..3` (`$A7:97C8`, `$9AC8`, `$9DC8`, `$A0C8`) are four
   stored 32×12 head maps. `ProcessKraidInstList` uploads only `$02C0` bytes—the
   first 32×11 rows—to the upper-left BG2 screen block. The last stored row is
   intentionally never drawn.

The arm, lints, foot, and nails are ordinary/extended OAM. All eight Kraid species
headers point to the same raw `$AB:CC00..EA00` `Tiles_Kraid` range (7680 bytes / 240
tiles) and `$A7:8687` sprite palette. That linked OAM range is independent of the
room tileset graphics above. Kraid also owns 21 named palette states: the base OAM
and room-background palettes, BG hurt/health/death states, and OAM hurt/health states.
Tileset `$1A` palette row 7 byte-matches `Palette_Kraid_BG_8_8` (the full-health
state); the separate room-background fade targets row 6.
The small 512-byte `Tiles_KraidRoomBackground` payload is another independent
environment resource, consumed by `InitAI_Kraid`, `DrawKraidsRoomBackground`, and
`UnpauseHook_KraidIsDead`.

SMEDIT's Kraid editor exposes these layers without pretending they are one giant OAM
sprite. **Components** shows all four complete 512×512 live BG2 body states and the
four editable head sources. **Animations** plays the four exact custom head lists over
the complete body, offers ten paired health/hurt/death palette stages, and plays twelve
source-bounded linked-OAM lists: five foot, four arm, two lint, and one nail sequence.
Those lists contain 173 rendered frame occurrences. The separate canvases are
intentional: the runtime places linked enemies in room space, and their independent AI
does not provide one canonical, synchronized whole-boss timeline to merge with BG2.

`./gradlew parityKraid` regenerates the ignored `parity/reports/kraid.json` proof;
the strict `parityReport` additionally checks production head/composite pixel hashes
and a complete-resource edit round trip. It also hashes every rendered frame from the
four head lists, twelve linked-OAM lists, and six Mini Kraid lists.

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
E2BF @ (256, 536)  ← Kraid
E2FF @ (232, 488)  ← arm
E33F @ (200, 528)  ← lint (top)
E37F @ (176, 592)  ← lint (middle)
E3BF @ (178, 648)  ← lint (bottom)
E3FF @ (256, 632)  ← foot
E43F @ (232, 488)  ← nail
E47F @ (232, 488)  ← nail (bad trajectory)
```

## Mini Kraid

Mini Kraid (the pre-fight encounter in Baby Kraid Room) is a separate, ordinary
OAM enemy. It is not one of main Kraid's linked sub-entities:

| Entity | Species ID | PC Offset |
|--------|-----------|-----------|
| Mini Kraid | `$E0FF` | `0x1060FF` |

Its six active action lists are bounded at `$A6:99AE–9A42` and use fourteen
unique active spritemaps at `$A6:9C64–A08E`. SMEDIT renders those named lists
directly; scanning all of shared bank `$A6` also finds unrelated Ridley data and
must not be used for Mini Kraid pose discovery. The editor now exposes forward step,
backward step, and spit actions in both directions. Together they contain 24 frame
occurrences, all parity-pinned against the exact source order, duration, address, and
rendered pixels. Its source places foreground limb entries before the torso; SMEDIT
uses the SNES's lower-OAM-index-wins overlap rule so the hand and curled front leg draw
over the body instead of disappearing behind it.

## References

- Disassembly: https://patrickjohnston.org/bank/A7 (Kraid section near $8000)
- Room enemy set confirmed via ROM parse of room `$8F:A59F`, enemy set ptr `$A1:9EB5`
- Species IDs confirmed against `$A0` stat blocks — Squeept=`$D2BF`, Geruta=`$D2FF`, Holtz=`$D33F`
