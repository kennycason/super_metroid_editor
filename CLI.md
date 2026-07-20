# SMEDIT CLI

The `cli` module exposes SMEDIT features without launching the desktop app. It is meant for local automation, tests, and future services that want to call the same headless build API.

Use IPS output when distributing generated changes. Only create a patched ROM when the caller has provided their own ROM locally.

## Run

Most development usage goes through Gradle:

```bash
./gradlew -q :cli:runCli -Pargs='patches'
./gradlew -q :cli:runCli -Pargs='--rom base.smc rooms'
```

The `-Pargs='...'` value is passed to SMEDIT's CLI parser.

## Commands

```bash
Usage: [--rom <path.smc>] [--compact] <command> [options]
```

| Command | Requires ROM | Output |
| --- | --- | --- |
| `patches` | No | Built-in patch IDs and headless support status |
| `rooms` | Yes | Room summary JSON |
| `room <id|handle>` | Yes | One room with collision grid JSON |
| `graph` | Yes | Navigation graph JSON |
| `export -o <dir>` | Yes | `rooms.json`, `nav_graph.json`, and per-room JSON files |
| `build --config <json> --patch <ips>` | No | IPS patch |
| `build --config <json> --output <smc>` | Yes | Patched ROM |

Add `--compact` before the command to remove JSON indentation.

## Structured ROM Export

```bash
# List all rooms
./gradlew -q :cli:runCli -Pargs='--rom rom.smc rooms'

# Export one room by handle or hex room ID
./gradlew -q :cli:runCli -Pargs='--rom rom.smc room landingSite'
./gradlew -q :cli:runCli -Pargs='--rom rom.smc room 0x91F8'

# Navigation graph
./gradlew -q :cli:runCli -Pargs='--rom rom.smc graph'

# Full export directory
./gradlew -q :cli:runCli -Pargs='--rom rom.smc export -o /tmp/sm_export'
```

## Patch Discovery

Use `patches` to find built-in IDs and config types:

```bash
./gradlew -q :cli:runCli -Pargs='--compact patches'
```

Each entry includes:

| Field | Meaning |
| --- | --- |
| `id` | Patch ID accepted by build config |
| `name` | Display name |
| `configType` | Alternate config key, when available |
| `headlessSupported` | Whether CLI build v1 can apply it |
| `writeRecords` / `writeBytes` | Static write size for direct hex/IPS patches |

## Build Config

`build` reads a JSON request:

```json
{
  "schemaVersion": 1,
  "project": "projects/Super Mazetroid/Super Mazetroid.smedit",
  "patches": {
    "bundled_fast_doors": { "enabled": true },
    "hex_higher_jump": { "enabled": true },
    "bombs": {
      "enabled": true,
      "config": {
        "max_active_bombs": 5,
        "fuse_frames": 10,
        "cooldown_frames": 1,
        "explosion_frame_delay": 1
      }
    },
    "fanfares": {
      "enabled": true,
      "config": {
        "item_fanfare_frames": 16
      }
    },
    "ceres_escape_seconds": {
      "enabled": true,
      "configValue": 90
    }
  },
  "rawWrites": [
    {
      "address": "80:8000",
      "bytes": [170],
      "label": "example SNES write"
    },
    {
      "address": "pc:0x1234",
      "bytes": [1, 2, 3],
      "label": "example PC write"
    }
  ]
}
```

`patches` keys can be either patch IDs, such as `bundled_fast_doors`, or supported config types, such as `bombs`.

`project` is optional. Relative project paths are resolved from the build config file's directory.

Enemy and beam patch config keys match the desktop patch UI:

```json
{
  "patches": {
    "beam_damage": {
      "config": {
        "power": 40,
        "plasma": 300
      }
    },
    "enemy_stats": {
      "config": {
        "zoomer_hp": 50,
        "zoomer_dmg": 12,
        "zoomer_touchAi": 32803
      }
    },
    "enemy_drops": {
      "config": {
        "zoomer_drop2": 77
      }
    },
    "enemy_vuln": {
      "config": {
        "zoomer_vuln9": 4
      }
    }
  }
}
```

## Build Outputs

Generate an IPS without providing a ROM:

```bash
./gradlew -q :cli:runCli -Pargs='build --config build.json --patch out.ips --report report.json'
```

Generate a patched ROM and an IPS from a caller-provided ROM:

```bash
./gradlew -q :cli:runCli -Pargs='--rom base.smc build --config build.json --output out.smc --patch out.ips --report report.json'
```

Reports are JSON and are also printed to stdout:

```json
{
  "schemaVersion": 1,
  "mode": "patch",
  "inputRomBytes": 0,
  "outputRomBytes": 0,
  "changedBytes": 42,
  "patchBytes": 42,
  "applied": [],
  "warnings": []
}
```

## Supported Headless Build Features

Build v1 supports:

- Bundled IPS patches from `shared/src/commonMain/resources/patches`.
- Hardcoded hex patches from the shared patch catalog.
- Config patches for `bombs`, `fanfares`, `ceres_escape_seconds`, `beam_damage`, `enemy_stats`, `enemy_drops`, and `enemy_vuln`.
- ROM-backed `.smedit` tileset palette overrides, including area palette randomization.
- `.smedit` fixed sprite palette overrides for Samus, beams, bosses, and listed enemy palette regions.
- ROM-backed dynamic enemy palette overrides stored as `enemy_pal:<speciesId>`.
- Raw PC writes with `pcOffset` or `address: "pc:0x..."`.
- Raw SNES LoROM writes with `snesAddress` or `address: "80:8000"`.
- IPS-only generation without reading or writing a ROM.

IPS-only generation supports fixed-address data, including beam damage and enemy header stats. Tileset palette randomization, dynamic enemy palettes, enemy drop tables, and enemy vulnerability tables require `--rom` because SMEDIT must inspect the base ROM's compressed palette pointers, enemy table pointers, or free space before writing or relocating data.

Build v1 intentionally does not yet export the full desktop project pipeline. If a `.smedit` project includes room edits, graphics tile edits, metatile table edits, palette effect metadata, text edits, minimap edits, music edits, or custom ASM, the CLI emits a warning that those project sections were ignored. Explicitly requested unsupported config patches fail instead of silently doing nothing.

Desktop-only config patch types currently listed as unsupported by `patches`:

- `boss_stats`
- `phantoon`
- `kraid`
- `ridley`
- `draygon`
- `spore_spawn`
- `crocomire`
- `botwoon`
- `torizo`
- `mother_brain`
- `samus_physics`
- `controller_config`
- `room_name_pause_map`
- `boss_defeated`
- `hyper_beam`

## Programmatic API

The shared JVM API lives under `com.supermetroid.editor.headless`.

```kotlin
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBuildService
import com.supermetroid.editor.headless.SmeditPatchRequest

val request = SmeditBuildRequest(
    patches = mapOf(
        "bundled_fast_doors" to SmeditPatchRequest(enabled = true)
    )
)

val service = SmeditBuildService()
val patchOnly = service.buildPatch(request)
val romBuild = service.build(inputRom = romBytes, request = request)
```

Future web or service integrations should call this shared API rather than shelling into desktop UI code.
