# Phantoon Boss — ROM Data Analysis

## Species IDs

| Entity | Species ID | Bank $A0 Offset |
|--------|-----------|-----------------|
| Phantoon (body) | $E4BF | $A0:E4BF |
| Phantoon eye | $E4FF | $A0:E4FF |
| Phantoon tentacles | $E53F | $A0:E53F |
| Phantoon mouth | $E57F | $A0:E57F |

Room ID: **$CD13** (Wrecked Ship)
AI Bank: **$A7** (shared with Kraid, Etecoon, Dachora)
Boss defeated flag: **$7E:D82B** bit 0x01

## Stats (Bank $A0)

64-byte species header, HP at +04 (u16 LE), damage at +06 (u16 LE).

| Parameter | SNES Address | PC Offset | Default |
|-----------|-------------|-----------|---------|
| Phantoon HP | $A0:E4C3 | 0x1064C3 | 2500 |
| Contact Damage | $A0:E4C5 | 0x1064C5 | 40 |
| Eye contact damage | $A0:E505 | 0x106505 | 40 |
| Tentacles contact damage | $A0:E545 | 0x106545 | 40 |
| Mouth contact damage | $A0:E585 | 0x106585 | 40 |

## Behavior Data Tables (Bank $A7)

All tables are plain data (u16 LE words). No ASM patches needed — just value writes.
60 frames = 1 second at NTSC.

### Figure-8 Vulnerable Window — Eye Open Duration

Address: **$A7:CD41** (PC 0x13CD41) — 8 entries × 2 bytes

Controls how long Phantoon's eye stays open (damageable) during figure-8 movement.

| Round | Offset | Default (frames) | ~Seconds |
|-------|--------|-----------------|----------|
| 0 | +00 | 60 | 1.0s |
| 1 | +02 | 30 | 0.5s |
| 2 | +04 | 15 | 0.25s |
| 3 | +06 | 30 | 0.5s |
| 4 | +08 | 60 | 1.0s |
| 5 | +0A | 30 | 0.5s |
| 6 | +0C | 15 | 0.25s |
| 7 | +0E | 60 | 1.0s |

Increasing = easier (more time to deal damage). Decreasing = harder.

### Eye Closed Duration — Time Between Patterns

Address: **$A7:CD53** (PC 0x13CD53) — 8 entries × 2 bytes

How long Phantoon's eye stays closed before the next vulnerability window.

| Round | Offset | Default (frames) | ~Seconds |
|-------|--------|-----------------|----------|
| 0 | +00 | 720 | 12.0s |
| 1 | +02 | 60 | 1.0s |
| 2 | +04 | 360 | 6.0s |
| 3 | +06 | 720 | 12.0s |
| 4 | +08 | 360 | 6.0s |
| 5 | +0A | 60 | 1.0s |
| 6 | +0C | 360 | 6.0s |
| 7 | +0E | 720 | 12.0s |

Reducing speeds up the fight. Increasing forces longer waits.

### Flame Rain Hiding Duration

Address: **$A7:CD63** (PC 0x13CD63) — 8 entries × 2 bytes

How long Phantoon hides (invisible, invulnerable) before reappearing during flame rain.

| Round | Offset | Default (frames) | ~Seconds |
|-------|--------|-----------------|----------|
| 0 | +00 | 60 | 1.0s |
| 1 | +02 | 120 | 2.0s |
| 2 | +04 | 30 | 0.5s |
| 3 | +06 | 60 | 1.0s |
| 4 | +08 | 30 | 0.5s |
| 5 | +0A | 60 | 1.0s |
| 6 | +0C | 30 | 0.5s |
| 7 | +0E | 30 | 0.5s |

### Figure-8 Movement Speed

#### Acceleration

Address: **$A7:CD73** (PC 0x13CD73) — 4 entries × 2 bytes (16-bit fixed-point)

| Round | Offset | Default | Notes |
|-------|--------|---------|-------|
| 0 | +00 | $0600 | Moderate |
| 1 | +02 | $0000 | Static (no acceleration) |
| 2 | +04 | $1000 | Fast |
| 3 | +06 | $0000 | Static |

#### Speed Caps

Address: **$A7:CD7B** (PC 0x13CD7B) — 3 entries × 2 bytes (signed pixels/frame)

| Index | Offset | Default | Notes |
|-------|--------|---------|-------|
| 0 | +00 | 2 | Slow |
| 1 | +02 | 7 | Fast |
| 2 | +04 | 0 | Static |

#### Reverse Figure-8 Acceleration

Address: **$A7:CD81** (PC 0x13CD81) — 4 entries × 2 bytes

Same structure as forward acceleration. Defaults: $0600, $0000, $1000, $0000.

#### Reverse Figure-8 Speed Caps

Address: **$A7:CD89** (PC 0x13CD89) — 3 entries × 2 bytes (signed, negative = leftward)

| Index | Offset | Default | Signed |
|-------|--------|---------|--------|
| 0 | +00 | $FFFE | -2 |
| 1 | +02 | $FFF9 | -7 |
| 2 | +04 | $0000 | 0 |

### Casual Flame Spawn Timing

Address: **$A7:CCFD** (PC 0x13CCFD)

Complex structure: 4 pointer words → sub-tables. Each sub-table controls:
- Number of flames to spawn
- Initial delay before first flame (e.g., 180 frames = 3s)
- Interval between subsequent flames (16–48 frames)

Sub-table values (after pointers, starting at $A7:CD05):

**Pattern A** (5 flames, 32-frame intervals):
`05, 00B4, 0020, 0020, 0020, 0020, 0020`

**Pattern B** (3 flames, 16-frame intervals):
`03, 00B4, 0010, 0010, 0010`

**Pattern C** (7 flames, 48-frame intervals):
`07, 00B4, 0030, 0030, 0030, 0030, 0030, 0030, 0030`

**Pattern D** (7 flames, variable intervals):
`07, 00B4, 0010, 0040, 0020, 0040, 0020, 0010, 0020`

### Flame Rain Positions

Address: **$A7:CDAD** (PC 0x13CDAD) — 8 position sets × 4 words

Controls where Phantoon materializes during flame rain. Each set: (movement index, X, Y, padding).

| Set | Movement index | X | Y |
|-----|----------------|---|---|
| 0 | 1 | 128 | 96 |
| 1 | 71 | 168 | 64 |
| 2 | 136 | 208 | 96 |
| 3 | 201 | 168 | 128 |
| 4 | 1 | 128 | 96 |
| 5 | 334 | 88 | 64 |
| 6 | 399 | 48 | 96 |
| 7 | 465 | 88 | 128 |

### Wavy Phantoon Constants (Intro/Death)

Address: **$A7:CD9B** (PC 0x13CD9B) — 5 entries × 2 bytes

| Index | Default | Purpose |
|-------|---------|---------|
| 0 | $0040 (64) | Amplitude delta - intro wavy Phantoon |
| 1 | $0C00 (3072) | Max amplitude - intro wavy Phantoon |
| 2 | $0100 (256) | Amplitude delta - dying wavy Phantoon |
| 3 | $F000 (-4096) | Max amplitude - dying wavy Phantoon |
| 4 | $0008 (8) | Wavy Phantoon phase delta |

## AI Routine Addresses (Bank $A7)

| Routine | SNES Address | Description |
|---------|-------------|-------------|
| Init AI | $A7:CDF3 | Phantoon body initialization |
| Main AI | $A7:CEA6 | Per-frame main loop |
| Hurt AI | $A7:DD3F | Damage reaction handler |
| Enemy touch | $A7:DD95 | Contact with Samus |
| Enemy shot | $A7:DD9B | Projectile hit handler |
| Pick pattern | $A7:D076 | RNG-based pattern selection |
| Figure-8 move | $A7:D0F1 | Movement during figure-8 |
| Swoop move | $A7:D2D1 | Swooping attack movement |
| Death start | $A7:D421 | Death sequence trigger |
| Flame spawn | $A7:CF5E | Casual flame creation |
| Flame rain | $A7:CF8B | Flame rain projectiles |

## What's Safely Modifiable (Data-Only Writes)

1. **HP and damage** — species header values
2. **Vulnerable window duration** — $A7:CD41, high confidence
3. **Eye closed duration** — $A7:CD53, high confidence
4. **Flame rain hiding time** — $A7:CD63, high confidence
5. **Figure-8 speed/acceleration** — $A7:CD73/$CD7B/$CD81/$CD89, moderate confidence (extreme values may look broken)
6. **Casual flame timing** — $A7:CCFD sub-tables, moderate confidence (pointer structure must stay intact)
7. **Flame rain positions** — $A7:CDAD, room-geometry dependent

## What Requires ASM Patches (Not Simple)

- **Pattern selection probability** — RNG branch logic at $A7:D076
- **Enraged HP threshold** — hardcoded comparison in code
- **Number of flame rain waves** — controlled by code, not data
- **Death sequence timing** — interleaved with HDMA effects

## References

- Disassembly: https://patrickjohnston.org/bank/A7 (Phantoon section $CA01–$E7FD)
- SMILE enemy files: `~/code/smile/files/Enemies/E4BF.txt`
- MapRandomizer boss requirements: `~/code/MapRandomizer/rust/maprando-logic/src/boss_requirements.rs`
