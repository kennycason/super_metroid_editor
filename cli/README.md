# SMEDIT CLI

Command-line interface for exporting Super Metroid ROM data, rendering room maps, and applying headless patches.

Run from the repository root. Paths with spaces or special characters should be single-quoted inside `-Pargs`, and `!` must be escaped as `\!` in zsh:

```bash
ROM='/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc'
./gradlew :cli:runCli -Pargs="--rom '$ROM' <command> [options]"
```

## Commands

### rooms

List all rooms as lightweight JSON summaries:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' rooms"
```

### rooms-metadata

Export room metadata JSON (IDs, names, areas, map positions, dimensions). Uses vanilla SM room names unless the ROM has a room-names patch applied:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' rooms-metadata"
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' rooms-metadata --compact"
```

### room

Export a single room with full collision grid, BTS, doors, items, enemies, and PLMs:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' room 0x91F8"
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' room landingSite"
```

### graph

Export the navigation graph (room nodes + door edges):

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' graph"
```

### export

Export everything to a directory: `rooms.json`, `nav_graph.json`, and per-room JSON files:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' export -o /tmp/sm_export"
```

### render-rooms

Render every room map to PNG and bundle into a ZIP file. Each PNG is named by room ID (e.g. `91f8.png`). The ZIP also includes a `rooms.json` metadata file:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' render-rooms -o rooms.zip"
```

Add `--items` to overlay visible item icons (Energy Tank, Screw Attack, etc.) on each room map:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' render-rooms -o rooms.zip --items"
```

### build

Apply patches to produce a ROM and/or IPS:

```bash
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' build --config build.json --output patched.smc --patch patched.ips"
./gradlew :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' build --config build.json --colorize psychedelic --output patched.smc"
./gradlew :cli:runCli -Pargs="build --config build.json --patch patch-only.ips"
```

For the bundled **Echolocation Beam** patch, a ready-to-use config is included:

```bash
./gradlew -q :cli:runCli -Pargs="--rom '/path/to/rom/Super Metroid/Super Metroid (JU) [\!].smc' build --config ../examples/cli/echolocation-beam.json --output ../build/echolocation-beam.smc --patch ../build/echolocation-beam.ips"
```

This patch gives collidable tiles a short, high click for every beam and a
slightly lower click when Samus lands, hits a ceiling, or runs into a wall. It
also closes the silent Wave/Hyper Beam gap while preserving their ability to
pass through tiles; enemy hits and the original landing sounds remain intact.

### patches / schemas / schema

List available patches and config schemas (no ROM required):

```bash
./gradlew :cli:runCli -Pargs="patches"
./gradlew :cli:runCli -Pargs="schemas"
./gradlew :cli:runCli -Pargs="schema enemy_stats"
```

## Options

| Flag | Description |
| --- | --- |
| `--rom <path>` | Path to Super Metroid ROM file (.smc). Required for all commands except `patches`, `schemas`, `schema`, and `build --patch`. |
| `--compact` | Output compact JSON (no indentation). |
| `-h`, `--help` | Show help. |
