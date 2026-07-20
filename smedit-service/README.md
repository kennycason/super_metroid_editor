# SMEDIT Service

`smedit-service` is a lightweight Ktor wrapper around the shared SMEDIT headless build engine.

Run it from the repository root:

```bash
./gradlew :smedit-service:runService
```

The server listens on port `8080` by default. Set `SMEDIT_SERVICE_PORT` or `-Dsmedit.service.port=8081` to change it.

## POST /patch

The caller supplies the ROM in the request. The service does not read, store, or host ROM files.

Request body:

```json
{
  "romBase64": "<base64 headerless-or-headered Super Metroid ROM>",
  "build": {
    "schemaVersion": 1,
    "patches": {
      "hex_higher_jump": {
        "enabled": true
      }
    }
  }
}
```

By default the response body is the patched ROM as `application/octet-stream`.

### Raw ROM Response

This writes a patched ROM to disk:

```bash
ROM=path/to/base.smc
ROM_B64="$(base64 -i "$ROM")"

jq -n --arg rom "$ROM_B64" '{
  romBase64: $rom,
  build: {
    schemaVersion: 1,
    patches: {
      bundled_vanilla_bugfixes: { enabled: true },
      bundled_fast_doors: { enabled: true },
      hex_higher_jump: { enabled: true },
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
  }
}' |
curl -sS \
  -X POST \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  http://localhost:8080/patch \
  --output patched.smc
```

For SDK-style callers and tests, add `?format=json` or send `Accept: application/json` to receive:

```json
{
  "romBase64": "<base64 patched ROM>",
  "ipsBase64": "<base64 IPS patch>",
  "report": {}
}
```

### JSON Response

This returns the patched ROM, IPS patch, and build report in one JSON response:

```bash
ROM=path/to/base.smc
ROM_B64="$(base64 -i "$ROM")"

jq -n --arg rom "$ROM_B64" '{
  romBase64: $rom,
  build: {
    schemaVersion: 1,
    patches: {
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
  }
}' |
curl -sS \
  -X POST \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  --data-binary @- \
  'http://localhost:8080/patch?format=json' \
  > patch-response.json

jq -r '.romBase64' patch-response.json | base64 -D > patched.smc
jq -r '.ipsBase64' patch-response.json | base64 -D > patched.ips
jq '.report' patch-response.json
```

### Boss Lab Request

This mirrors the CLI boss example against the service:

```bash
ROM=path/to/base.smc
ROM_B64="$(base64 -i "$ROM")"

jq -n --arg rom "$ROM_B64" '{
  romBase64: $rom,
  build: {
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
      bundled_fast_mother_brain_cutscene: { enabled: true }
    }
  }
}' |
curl -sS \
  -X POST \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  http://localhost:8080/patch \
  --output boss-lab.smc
```

Config validation is strict by default. Use `"strictConfigValidation": false` inside `build` only when you intentionally want warnings instead of request failure.

For example, this returns `400 Bad Request` because `power_typo` is not a valid `beam_damage` field:

```bash
ROM=path/to/base.smc
ROM_B64="$(base64 -i "$ROM")"

jq -n --arg rom "$ROM_B64" '{
  romBase64: $rom,
  build: {
    schemaVersion: 1,
    patches: {
      beam_damage: {
        enabled: true,
        config: {
          power_typo: 35
        }
      }
    }
  }
}' |
curl -sS \
  -X POST \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  http://localhost:8080/patch
```
