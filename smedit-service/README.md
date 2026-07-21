# SMEDIT Service

`smedit-service` is a lightweight Ktor wrapper around the shared SMEDIT headless build engine.

Run it from the repository root:

```bash
./gradlew :smedit-service:runService
```

The server listens on port `8080` by default. Set `SMEDIT_SERVICE_PORT` or `-Dsmedit.service.port=8081` to change it.

## POST /patch

The caller supplies the ROM in the request. The service does not read, store, or host ROM files. For command-line tools and web forms, prefer `multipart/form-data` with a `rom` file field. JSON with `romBase64` is also supported for SDK-style callers and tests.

ROM uploads must be either a normal 3 MB Super Metroid ROM (`3145728` bytes) or a 3 MB ROM with a 512-byte SMC copier header (`3146240` bytes). Headered ROMs are patched at the correct file offsets; generated IPS patches use normal headerless offsets.

Multipart fields:

| Field | Required | Description |
| --- | --- | --- |
| `rom` | Yes | Uploaded headerless-or-headered Super Metroid ROM file. |
| `build` | No | JSON `SmeditBuildRequest`. Defaults to an empty build. |
| `randomize` | No | JSON randomizer request. Defaults to no randomization. |

Use public patch IDs in `build.patches`. For the common intro patches, `skip_intro_and_ceres` starts directly on Zebes, while `skip_intro` starts at Ceres with only the intro cinematic skipped. Older internal IDs such as `bundled_*` and `hex_*` are still accepted as aliases.

By default the response body is the patched ROM as `application/octet-stream`.

## GET /metadata

Use metadata to discover the service's supported public patch IDs, config schemas, randomizer presets, enemy categories, beam keys, color effects, and sprite palette region IDs:

```bash
curl -sS http://localhost:8080/metadata | jq '.randomization.enemyCategories'
curl -sS http://localhost:8080/metadata | jq '.colorize.effects[].id'
```

### Quick File Upload

This writes a patched ROM to disk:

```bash
ROM=path/to/base.smc
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    skip_intro_and_ceres: { enabled: true },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    },
    higher_jump: { enabled: true },
    energy_free_shinesparks: { enabled: true }
  }
}')"

curl -sS \
  -X POST \
  -F rom=@"$ROM" \
  -F "build=$BUILD_JSON" \
  http://localhost:8080/patch \
  --output patched.smc
```

There is also a ready-to-run version of this pattern at `examples/service/random-spicy-combat.sh`. It accepts `--colorize psychedelic` to add a ROM palette effect to the generated combat ROM.

### Colorize Palettes

Add `colorize` to the `build` field to apply one shared palette effect to area tileset palettes and fixed sprite palette regions:

```bash
ROM=path/to/base.smc
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    skip_intro_and_ceres: { enabled: true },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    }
  },
  colorize: {
    effect: "psychedelic"
  }
}')"

curl -sS \
  -X POST \
  -F rom=@"$ROM" \
  -F "build=$BUILD_JSON" \
  http://localhost:8080/patch \
  --output psychedelic.smc
```

The effect must match an ID from SMEDIT's palette effect registry, such as `psychedelic`, `vaporwave`, `grayscale`, or `acid`. Palette colorize requires a ROM upload because the service must read compressed tileset palette pointers before writing or relocating color data.

### Raw ROM Response

For larger patch configs, build the JSON fields with `jq -nc` and upload the ROM file directly:

```bash
ROM=path/to/base.smc
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    vanilla_bugfixes: { enabled: true },
    skip_intro_and_ceres: { enabled: true },
    fast_doors: { enabled: true },
    higher_jump: { enabled: true },
    energy_free_shinesparks: { enabled: true },
    bombs: {
      enabled: true,
      config: {
        max_active_bombs: 5,
        fuse_frames: 10,
        cooldown_frames: 1,
        explosion_frame_delay: 1
      }
    },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    }
  }
}')"

curl -sS \
  -X POST \
  -F rom=@"$ROM" \
  -F "build=$BUILD_JSON" \
  http://localhost:8080/patch \
  --output patched.smc
```

### JSON Response

Add `?format=json` or send `Accept: application/json` to receive the patched ROM, IPS patch, and build report in one JSON response:

```json
{
  "romBase64": "<base64 patched ROM>",
  "ipsBase64": "<base64 IPS patch>",
  "report": {}
}
```

```bash
ROM=path/to/base.smc
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    skip_intro_and_ceres: { enabled: true },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    },
    hyper_beam: { enabled: true },
    beam_damage: {
      enabled: true,
      config: {
        power: 35,
        plasma: 220,
        iwp: 420
      }
    },
    boss_defeated: {
      enabled: true,
      config: {
        kraid: 1,
        phantoon: 1
      }
    }
  }
}')"

curl -sS \
  -X POST \
  -H 'Accept: application/json' \
  -F rom=@"$ROM" \
  -F "build=$BUILD_JSON" \
  'http://localhost:8080/patch?format=json' \
  > patch-response.json

jq -r '.romBase64' patch-response.json | base64 -D > patched.smc
jq -r '.ipsBase64' patch-response.json | base64 -D > patched.ips
jq '.report' patch-response.json
```

### IPS-Only Response

Use `?format=ips` to receive only the generated IPS patch as `application/octet-stream`:

```bash
ROM=path/to/base.smc
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    skip_intro_and_ceres: { enabled: true },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    },
    energy_free_shinesparks: { enabled: true }
  }
}')"

curl -sS \
  -X POST \
  -F rom=@"$ROM" \
  -F "build=$BUILD_JSON" \
  'http://localhost:8080/patch?format=ips' \
  --output smedit.ips
```

### Randomized Patch Configs

Add a `randomize` block next to `build` to generate patch configs before applying the build. Direct values in `build.patches` override randomized values, which is useful for pinning a few fields while randomizing the rest.

Available presets:

| Preset | Shape |
| --- | --- |
| `balanced` | Moderate beam, enemy stat, drop, and vulnerability changes with multiple effective weapons kept per enemy. |
| `spicy` | Wider combat variance, some immunity, and stingier drops. |
| `chaos` | High variance across all supported randomizer groups. |
| `survival` | Tougher enemies, restrained beams, and fewer generous drops. |

Top-level filters apply to every compatible randomizer:

```json
{
  "randomize": {
    "preset": "spicy",
    "includeEnemyCategories": ["Pirate", "Flyer"],
    "excludeEnemies": ["metroid"],
    "includeBeams": ["power", "ice", "wave", "plasma"]
  }
}
```

The same filters can also be set inside individual randomizer sections. Empty include lists mean "all"; excludes are removed after includes. Unknown keys fail the request.

This example randomizes beam damage, enemy HP, enemy drop tables, and enemy vulnerabilities. Enemy drop rows are normalized to total `255`. Enemy vulnerability values are valid one-byte multipliers, where `0` means no effect.

```bash
ROM=path/to/base.smc
RANDOMIZE_JSON="$(jq -nc '{
  seed: 12345,
  preset: "balanced",
  excludeEnemyCategories: ["Special"],
  beamDamage: {
    enabled: true,
    damageMin: 0.5,
    damageMax: 2.5,
    excludeBeams: ["iwp"]
  },
  enemyStats: {
    enabled: true,
    enemyHpMin: 0.5,
    enemyHpMax: 3.5,
    enemyDamageMin: 0.5,
    enemyDamageMax: 2.0,
    preserveOneHpEnemies: true,
    preserveZeroDamageEnemies: true
  },
  enemyDrops: {
    enabled: true,
    total: 255,
    smallEnergyWeight: 2.0,
    largeEnergyWeight: 1.0,
    missileWeight: 2.0,
    nothingWeight: 4.0,
    superMissileWeight: 0.6,
    powerBombWeight: 0.4,
    minNonZeroSlots: 3,
    maxNothing: 180
  },
  enemyVulnerabilities: {
    enabled: true,
    noEffectChance: 0.25,
    multipliers: [1, 2, 4, 8],
    ensureAtLeastOneEffectivePerEnemy: true,
    minEffectiveWeaponsPerEnemy: 2,
    requiredEffectiveWeaponSlots: [9, 21]
  }
}')"
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    beam_damage: {
      enabled: true,
      config: {
        power: 40
      }
    },
    skip_intro_and_ceres: { enabled: true },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    }
  }
}')"

curl -sS \
  -X POST \
  -H 'Accept: application/json' \
  -F rom=@"$ROM" \
  -F "randomize=$RANDOMIZE_JSON" \
  -F "build=$BUILD_JSON" \
  'http://localhost:8080/patch?format=json' \
  > randomized-response.json

jq '.randomization' randomized-response.json
jq '.resolvedBuild.patches.beam_damage.config.power' randomized-response.json
jq -r '.romBase64' randomized-response.json | base64 -D > randomized.smc
```

When randomization is used, JSON responses include:

```json
{
  "randomization": {
    "seed": 12345,
    "preset": "balanced",
    "randomizedConfigTypes": ["beam_damage", "enemy_stats", "enemy_drops", "enemy_vuln"],
    "randomizedFieldCounts": {
      "beam_damage": 11,
      "enemy_stats": 58,
      "enemy_drops": 174,
      "enemy_vuln": 638
    }
  },
  "resolvedBuild": {
    "schemaVersion": 1,
    "patches": {}
  }
}
```

Raw ROM and IPS responses include `X-SMEDIT-Randomization-Seed`, `X-SMEDIT-Randomization-Preset`, `X-SMEDIT-Randomized-Config-Types`, and `X-SMEDIT-Randomized-Field-Counts` headers when randomization is used.

### Boss Lab Request

This mirrors the CLI boss example against the service:

```bash
ROM=path/to/base.smc
BUILD_JSON="$(jq -nc '{
  schemaVersion: 1,
  patches: {
    boss_stats: {
      enabled: true,
      config: {
        kraid_hp: 10000,
        phantoon_hp: 3600,
        ridley_hp: 24000,
        draygon_hp: 9000
      }
    },
    kraid: {
      enabled: true,
      config: {
        intro_delay: 120,
        earthquake_ceiling_mask: 255,
        diagonal_up_x_speed: -3
      }
    },
    phantoon: {
      enabled: true,
      config: {
        vuln_0: 45,
        closed_0: 180,
        rev_cap_0: -4
      }
    },
    fast_mother_brain_cutscene: {
      enabled: true
    }
  }
}')"

curl -sS \
  -X POST \
  -F rom=@"$ROM" \
  -F "build=$BUILD_JSON" \
  http://localhost:8080/patch \
  --output boss-lab.smc
```

### Base64 JSON Alternative

Use JSON with `romBase64` when embedding requests in tests or SDK calls is more convenient than multipart upload:

```bash
ROM=path/to/base.smc
ROM_B64="$(base64 -i "$ROM")"

jq -nc --arg rom "$ROM_B64" '{
  romBase64: $rom,
  build: {
    schemaVersion: 1,
    patches: {
      skip_intro_and_ceres: { enabled: true },
      fanfares: {
        enabled: true,
        config: {
          item_fanfare_frames: 16
        }
      },
      higher_jump: { enabled: true },
      energy_free_shinesparks: { enabled: true }
    }
  }
}' |
curl -sS \
  -X POST \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  http://localhost:8080/patch \
  --output patched.smc
```
