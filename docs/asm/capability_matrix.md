# ASM / Visual Editor Capability Matrix

This is the human-readable view of the checked registry in
`AsmIntegrationContract.kt`. `AsmIntegrationContractTest` fails when a registry
row is missing here, when a capability/scenario has no decision, or when a cited
focused test suite no longer exists.

Rows are integration families, not individual fields or assets. A family may
own several focused bridges while sharing one authority and conformance boundary.

Legend: **Y** is supported; **N** is an intentional unsupported boundary; **—**
is structurally not applicable. “Live” means saved ASM refreshes the Visual
Editor and visual source edits refresh ASM without reopening the project.

| ID | Integration | Inspect | ASM | Visual | Live | Aliases | Restore | Relocate | Build |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `room-records` | Room headers, states, properties, selectors | Y | Y | Y | Y | Y | Y | Y | Y |
| `room-layouts` | Layout, BTS, layer 2, themes | Y | Y | Y | Y | Y | Y | Y | Y |
| `placed-objects` | Placed objects and PLM populations | Y | Y | Y | Y | Y | Y | Y | Y |
| `doors-arrivals-saves` | Doors, arrivals, entrance FX, saves | Y | Y | Y | Y | Y | Y | Y | Y |
| `room-fx` | Room FX records | Y | Y | Y | Y | Y | Y | Y | Y |
| `room-enemies` | Enemy populations and graphics sets | Y | Y | Y | Y | Y | Y | Y | Y |
| `room-scrolls` | Initial scroll grids and commands | Y | Y | Y | Y | Y | Y | Y | Y |
| `minimap` | Pause-map tilemaps and reveal data | Y | Y | Y | Y | — | Y | — | Y |
| `tileset-graphics` | Tileset/CRE graphics and metatiles | Y | Y | Y | Y | Y | Y | Y | Y |
| `tileset-palettes` | Tileset and CRE palettes | Y | Y | Y | Y | Y | Y | Y | Y |
| `native-sprite-graphics` | Native sprite pixel sheets | Y | Y | Y | Y | Y | Y | Y | Y |
| `native-sprite-palettes` | Native sprite palette rows | Y | Y | Y | Y | Y | Y | — | Y |
| `cinematic-maps` | Title, intro, ending, cinematic maps | Y | Y | Y | Y | Y | Y | Y | Y |
| `fixed-text` | Fixed tilemap text | Y | Y | Y | Y | Y | Y | — | Y |
| `typewriter-text` | Ceres/Zebes typed streams | Y | Y | Y | Y | Y | Y | Y | Y |
| `intro-story-text` | English NTSC intro pages | Y | Y | Y | Y | — | Y | — | Y |
| `gameplay-tuning` | Enemy/projectile/boss/environment/patch fields | Y | Y | Y | Y | Y | Y | — | Y |
| `fast-doors` | Fast Doors curated code module | Y | Y | Y | Y | — | Y | — | Y |
| `sound-music` | Sound/music source and tools | Y | Y | N | N | — | Y | — | Y |
| `custom-source` | Arbitrary source and project modules | Y | Y | N | N | — | Y | — | Y |

## Where to work

These locations come from the same checked registry as the capability decisions.
The in-app **ASM Library → ASM / Visual Editor capability matrix** makes the
same rows searchable and provides direct source/editor navigation when the
current workspace contains that destination.

| ID | Visual Editor | ASM source | Primary implementation |
|---|---|---|---|
| `room-records` | Rooms · Room Info | `bank_8F.asm` | `AsmRoomPropertiesBridge.kt`, `AsmRoomPropertiesSource.kt`, `AsmRoomSelectorsBridge.kt`, `AsmRoomSelectorsSource.kt` |
| `room-layouts` | Rooms · Layout canvas | `bank_8F.asm` | `AsmRoomLayoutBridge.kt`, `AsmRoomLayoutSource.kt`, `AsmRoomThemeSource.kt` |
| `placed-objects` | Rooms · Objects | `bank_8F.asm`, `macros.asm` | `AsmPlmSourceBridge.kt`, `AsmRoomObjectSource.kt` |
| `doors-arrivals-saves` | Rooms · Door connections | `bank_83.asm`, `bank_8F.asm` | `AsmDoorSourceBridge.kt`, `AsmDoorArrivalsSource.kt` |
| `room-fx` | Rooms · FX | `bank_83.asm` | `AsmFxBridge.kt`, `AsmFxSource.kt` |
| `room-enemies` | Rooms · Enemies | `bank_A1.asm`, `bank_B4.asm` | `AsmEnemyBridge.kt`, `AsmEnemySource.kt` |
| `room-scrolls` | Rooms · Scrolls | `bank_8F.asm` | `AsmInitialScrollBridge.kt`, `AsmScrollCommandSource.kt` |
| `minimap` | Minimap | `bank_B5.asm`, `bank_82.asm` | `AsmMinimapSourceBridge.kt` |
| `tileset-graphics` | Tilesets · Graphics | `bank_8F.asm`, `bank_B9..CE.asm` | `AsmTilesetGraphicsBridge.kt` |
| `tileset-palettes` | Tilesets · Palettes | `bank_8F.asm`, `bank_B9..CE.asm` | `AsmTilesetPaletteBridge.kt` |
| `native-sprite-graphics` | Sprites · Graphics | `bank_95.asm`, `bank_A0.asm` | `AsmNativeSpriteAssetBridge.kt` |
| `native-sprite-palettes` | Sprites · Palettes | `bank_8C.asm` | `AsmNativeSpritePaletteBridge.kt` |
| `cinematic-maps` | Text / Scenes | `bank_8B.asm`, `bank_94..99.asm` | `AsmCinematicMapAssetBridge.kt` |
| `fixed-text` | Text | `bank_85.asm` | `AsmTextSourceBridge.kt` |
| `typewriter-text` | Text | `bank_A6.asm` | `AsmTypewriterTextBridge.kt` |
| `intro-story-text` | Text | `bank_8B.asm` | `AsmIntroStoryTextBridge.kt` |
| `gameplay-tuning` | Enemy tuning · Boss tuning · Patches | `bank_A0.asm`, `bank_B4.asm`, `bank_93.asm`, `bank_8D.asm`, `bank_90.asm` | `AsmEnemyTuningBridge.kt`, `AsmBeamDamageBridge.kt`, `AsmBossStatsBridge.kt`, `AsmEnvironmentalDamageBridge.kt`, `AsmDataPatchBridge.kt`, `AsmPatchToggleController.kt` |
| `fast-doors` | Patches | `project/fast_doors.asm` | `AsmFastDoorsModule.kt` |
| `sound-music` | Sound tools · independent | `bank_CF..DE.asm`, `data/Music_*.bin` | `AsmSemanticBridge.kt` |
| `custom-source` | No Visual Editor · intentional | `main.asm`, `project/*.asm` | `AsmProjectWorkspace.kt`, `AsmCheckedHook.kt` |

## Shared reciprocity gate

Every **Y/Y** ASM-and-Visual row must decide and cover the same ten scenarios:

1. native decoded parity;
2. saved ASM → live Visual refresh;
3. Visual edit → authored ASM;
4. shared aliases or a proven single-owner invariant;
5. managed growth, or safe fixed-envelope refusal;
6. invalid/unsupported draft behavior;
7. stale/newer source or asset conflicts;
8. atomic Undo/Redo and baseline restore;
9. Save/reopen from durable inputs; and
10. cache-free build output, pointers, bounds, and ownership.

The registry assigns compiled proof suites to every scenario. A row cannot claim
reciprocity while omitting inspect, either edit direction, live refresh, restore,
or cold build. Unsupported/custom shapes retain the concrete **ASM only** reason
owned by each bridge; the matrix does not turn them into a generic fallback.

## Intentional boundaries

- Sound/music retains ASM navigation and build support, but reciprocal visual
  authoring is intentionally outside this contract.
- Arbitrary handwritten source remains authored ASM. Checked hooks and managed
  segments are supported tools, not a general ASM-to-GUI decompiler.
- OAM composition, animation/DMA programs, runtime palette effects, regional
  text branches, special/indexed FX, and custom room code remain read-only or
  ASM only as named by their owning row.
- **—** never means “untested.” The conformance scenario proves the single-owner
  case or the safe refusal boundary.

## Verification loops

Normal bridge work uses the fast contract/source/UI loop:

```bash
./gradlew :desktopApp:asmFastTest
```

Before declaring a reciprocal bridge complete, run the focused compiler loop:

```bash
./gradlew :desktopApp:asmReciprocityTest
```

The second task includes the fast suites plus the bridge-specific cold compiler
proofs. The long full-source parity, symbol coverage, community-ROM, and complete
pipeline suites remain milestone checks; they are not the normal edit/test loop.
