# Bomb and Golden Torizo

Bomb Torizo and Golden Torizo are two encounters built from one shared body source.
They use the same bank-`$AA` body OAM and instruction machinery, then distinguish the
encounter through palettes, AI paths, projectiles, and runtime tile replacement. The
editor therefore presents one **Torizo** workspace with Bomb/Golden encounter controls
instead of four misleading species rows.

## Headers and ownership

| Header | Species ID | Runtime role |
|---|---:|---|
| `EnemyHeaders_BombTorizo` | `$EEFF` | Real Bomb Torizo encounter |
| `EnemyHeaders_BombTorizoOrb` | `$EF3F` | Drop-chance record selected by projectile code; not a spawned Torizo sprite |
| `EnemyHeaders_GoldenTorizo` | `$EF7F` | Real Golden Torizo encounter |
| `EnemyHeaders_GoldenTorizoOrb` | `$EFBF` | Drop-chance record selected by projectile code; not a spawned Torizo sprite |

All four headers point at the same `$2000`-byte `Tiles_BombTorizo_GoldenTorizo`
payload at `$AF:C200` and use AI bank `$AA`. Only `$EEFF` and `$EF7F` represent the
visible encounters. Keeping the orb headers out of top-level sprite navigation avoids
presenting drop tables as duplicate enemies.

The runtime has four separate pixel owners:

| Source | Address / size | Editor ownership |
|---|---:|---|
| Shared Bomb/Golden body OBJ | `$AF:C200`, `$2000` | Editable through `spriteTileBlocks["enemy:EEFF"]` |
| Eyes, damage, and egg-release overlays | `$AA:B279`, `$0600` | Read-only runtime DMA source |
| Golden egg and hatchling OBJ | `$AF:E200`, `$0600` | Read-only independent source |
| Bomb statue fragments | `$AD:B200`, `$0400` | Read-only independent source |

The shared body is loaded into the high physical OBJ page. Egg and crumbling-statue
maps use low-page physical tiles. A renderer must retain both pages; masking every
tile index to eight bits makes valid pieces borrow unrelated art.

## Runtime tile replacement

The body does not keep one immutable tile sheet for the whole fight. Sixteen explicit
transfers replace fixed VRAM destinations:

- four eye frames replace `$40` bytes at VRAM `$7D80`;
- destroyed-gut tiles replace `$7300`, `$7400`, `$7E70`, and `$7F70`;
- destroyed-face tiles replace `$7E50` and `$7F50`;
- three egg-release stages each replace `$40` bytes at `$7300` and `$7400`.

These overlays are applied only to the matching compositions and animations. They
remain distinct from the editable base payload because saving a flattened preview
back over `$AF:C200` would incorrectly bake transient fight state into every pose.

## OAM, animations, projectiles, and palettes

The source inventory contains 106 active plus seven unused extended body maps. Their
children resolve through 91 active plus 18 unused standard body-child maps. Bank `$AA`
contains 112 active body instruction lists with 564 timed frame occurrences.

Extended children append their entries into ascending SNES OAM slots. Because a
lower OAM index wins when sprites overlap, the editor paints the complete flattened
entry stream in reverse. This is significant anatomy, not cosmetic ordering: the
early head and foreground-arm entries must cover later torso entries during awaken,
attack, and jump/fall poses.

Effects use a separate cross-bank path: bank `$86` owns the projectile instruction
symbols and bank `$8D` owns 70 active plus four source-declared-unused projectile OAM
maps. The guided editor covers Chozo orbs and impacts, Golden Torizo sonic booms,
Golden eggs/hatchlings, and representative Bomb statue fragments without pretending
that those maps belong to bank `$AA`.

The palette control exposes the encounter rows and all eight Golden Torizo health
stages. The UI names these by the handler's exact `$0800`-HP bands rather than
approximate percentages: vanilla full health (13,500) is in `12288–14335`, while the
top `14336+` saturation row is not normally reached by the vanilla boss. Each health
stage is a two-row runtime pair sourced by `$84:8000`; it is not a tint synthesized
by SMEDIT. The separate post-awakening Golden pair at `$AA:8787/$AA:87A7` remains
visible as **Golden · active** even though its visible colors match the top source row.

## Editor workflow and safe boundary

The workspace opens on **Animations**, followed by **Compositions**, **Components**,
and **Sources**. Encounter and facing filters reduce duplicate permutations. Sources
labels the shared base as editable and every runtime/room-loaded owner as read-only;
painting the base updates all applicable full compositions immediately.

Body and projectile placement, instruction lists, runtime transfers, AI, and palette
handlers are inspection data, not writable sprite pixels. Full room choreography—AI
decisions, collision, sounds, explosions, statue breakup motion, and event state—is
outside this sprite renderer even when its independently addressable visual pieces
are available.

## Parity boundary

`./gradlew parityTorizo` writes ignored `parity/reports/torizo.json`.
`TorizoSourceParityTest` pins all four headers, all four pixel assets, body/body-child/
projectile OAM inventories, 112 active body lists, 50 bank-`$86` projectile instruction
symbols, 16 runtime transfers, and 26 palette rows. It also hashes every exposed
palette, composition, component, and all 236 guided animation frames so address,
ownership, assembly, and rendered-pixel drift fail together.
