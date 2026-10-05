#!/usr/bin/env python3
"""Build Spore Spawn's cross-bank body, stalk, projectile, palette, and animation manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Dict, Sequence


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT_DIR = PARITY_DIR / "reports"
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "spore-spawn.json"

HEADER_SPECS = (
    ("EnemyHeaders_SporeSpawn", 0xDF3F, "body"),
    ("EnemyHeaders_SporeSpawnStalk", 0xDF7F, "drop-owner-alias"),
)
PROJECTILE_MAP_LABELS = (
    "EnemyProjSpritemaps_SporeSpawnsStalk",
    "EnemyProjSpritemaps_SporeSpawners_0",
    "EnemyProjSpritemaps_SporeSpawners_1",
    "EnemyProjSpritemaps_SporeSpawners_2",
    "EnemyProjSpritemaps_Spores_0",
    "EnemyProjSpritemaps_Spores_1",
    "EnemyProjSpritemaps_Spores_2",
)
PALETTE_LABELS = (
    "Palette_SporeSpawn",
    *(f"Palette_SporeSpawn_HealthBased_{index}" for index in range(4)),
    *(f"Palette_SporeSpawn_DeathSequence_{index}" for index in range(8)),
    *(f"Palette_SporeSpawn_DeathSequence_Level_{index}" for index in range(7)),
    *(f"Palette_SporeSpawn_DeathSequence_Background_{index}" for index in range(7)),
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def compact_record(record: Dict[str, object]) -> Dict[str, object]:
    result: Dict[str, object] = {
        "kind": record["kind"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
    }
    if record["kind"] == "frame":
        result.update(
            duration=int(record["duration"]),
            spritemapSnesAddress=int(record["spritemapSnesAddress"]),
            spritemapType=record["spritemapType"],
        )
    elif record["kind"] == "handler":
        result.update(
            handlerSnesAddress=int(record["handlerSnesAddress"]),
            operandByteCount=int(record["operandByteCount"]),
            controlFlow=record["controlFlow"],
        )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    report_dir = args.report_dir.expanduser().resolve()
    rom_path = disassembly / "SM.sfc"
    inputs = {
        "symbols": report_dir / "symbols.json",
        "assets": report_dir / "assets.json",
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew paritySporeSpawn")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Spore Spawn prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    header_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    headers = []
    for label, species_id, role in HEADER_SPECS:
        header = header_by_label.get(label)
        if header is None or int(header["speciesId"]) != species_id:
            fail(f"missing or changed Spore Spawn header {label}")
        fields = header["fields"]
        if int(fields["tileDataSize"]["value"]) != 0x0E00:
            fail(f"{label} tile transfer size changed")
        if fields["tileData"]["targetLabel"] != "Tiles_SporeSpawn":
            fail(f"{label} no longer shares Tiles_SporeSpawn")
        if fields["palette"]["targetLabel"] != "Palette_SporeSpawn":
            fail(f"{label} palette ownership changed")
        if int(fields["bank"]["value"]) != 0xA5:
            fail(f"{label} no longer uses AI bank $A5")
        headers.append({
            "sourceLabel": label,
            "role": role,
            "speciesId": species_id,
            "headerSnesAddress": int(header["snesAddress"]),
            "tileDataSize": 0x0E00,
            "tileDataSnesAddress": int(fields["tileData"]["value"]),
            "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
            "layer": int(fields["layer"]["value"]),
            "rawHeaderSha256": header["rawHeaderSha256"],
        })

    asset = next(
        (record for record in reports["assets"]["assets"] if record["name"] == "Tiles_SporeSpawn"),
        None,
    )
    if asset is None or int(asset["snesAddress"]) != address("Tiles_SporeSpawn") or int(asset["size"]) != 0x0E00:
        fail("Tiles_SporeSpawn ownership or size changed")
    assets = [{
        "sourceLabel": "Tiles_SporeSpawn",
        "role": "shared-body-stalk-spawner-spore-obj",
        "snesAddress": int(asset["snesAddress"]),
        "size": int(asset["size"]),
        "sha256": asset["sha256"],
        "asset": asset["asset"],
    }]

    oam = reports["oam"]
    standard = [
        record for record in oam["standardSpritemaps"]
        if str(record["sourceLabel"]).startswith("Spritemap_SporeSpawn_")
        and not record["sourceDeclaredUnused"]
    ]
    active_extended = [
        record for record in oam["extendedSpritemaps"]
        if "SporeSpawn" in str(record["sourceLabel"]) and not record["sourceDeclaredUnused"]
    ]
    unused_extended = [
        record for record in oam["extendedSpritemaps"]
        if "SporeSpawn" in str(record["sourceLabel"]) and record["sourceDeclaredUnused"]
    ]
    if (len(standard), len(active_extended), len(unused_extended)) != (22, 12, 7):
        fail(
            "Spore Spawn OAM inventory changed: "
            f"{len(standard)} standard, {len(active_extended)} active extended, {len(unused_extended)} unused extended"
        )
    standard_addresses = {int(record["snesAddress"]) for record in standard}
    for record in active_extended + unused_extended:
        for child in record["children"]:
            if child["childType"] != "standard-oam" or int(child["childSnesAddress"]) not in standard_addresses:
                fail(f"{record['sourceLabel']} links outside Spore Spawn's standard OAM inventory")

    projectile_maps = []
    for label in PROJECTILE_MAP_LABELS:
        snes_address = address(label)
        pc = snes_to_pc(snes_address)
        count = int.from_bytes(rom[pc:pc + 2], "little")
        if count != 1:
            fail(f"{label} no longer contains exactly one OAM entry")
        raw = rom[pc:pc + 7]
        projectile_maps.append({
            "sourceLabel": label,
            "snesAddress": snes_address,
            "size": len(raw),
            "entryCount": count,
            "sha256": sha256(raw),
        })

    all_lists = [
        record for record in reports["instructions"]["lists"]
        if str(record["sourceLabel"]).startswith("InstList_SporeSpawn_")
    ]
    active_lists = [record for record in all_lists if not record["sourceDeclaredUnused"]]
    if len(active_lists) != 9 or len(all_lists) != 9:
        fail(f"Spore Spawn instruction inventory changed: {len(active_lists)} active / {len(all_lists)} total")
    active_extended_addresses = {int(record["snesAddress"]) for record in active_extended}
    instruction_lists = []
    for record in active_lists:
        records = [compact_record(item) for item in record["records"]]
        for item in records:
            if item["kind"] == "frame" and int(item["spritemapSnesAddress"]) not in active_extended_addresses:
                fail(f"{record['sourceLabel']} references a non-Spore-Spawn frame")
        instruction_lists.append({
            "sourceLabel": record["sourceLabel"],
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "records": records,
        })
    guided_animations = [
        record for record in instruction_lists
        if sum(item["kind"] == "frame" for item in record["records"]) > 1
    ]
    if len(guided_animations) != 6:
        fail(f"Spore Spawn guided animation inventory changed: {len(guided_animations)}")

    palettes = []
    for label in PALETTE_LABELS:
        snes_address = address(label)
        raw = rom[snes_to_pc(snes_address):snes_to_pc(snes_address) + 32]
        palettes.append({
            "sourceLabel": label,
            "snesAddress": snes_address,
            "size": 32,
            "sha256": sha256(raw),
        })

    ownership = {
        "sharedObjPixels": {
            "sourceLabel": "Tiles_SporeSpawn",
            "snesAddress": address("Tiles_SporeSpawn"),
            "size": 0x0E00,
            "headerAliases": ["EnemyHeaders_SporeSpawn", "EnemyHeaders_SporeSpawnStalk"],
            "projectEditKey": 'spriteTileBlocks["enemy:DF3F"]',
        },
        "body": {
            "sourceBank": 0xA5,
            "format": "extended-oam",
            "activeMapCount": len(active_extended),
        },
        "stalk": {
            "projectileDefinition": address("EnemyProjectile_SporeSpawnStalk"),
            "instructionList": address("InstList_EnemyProjectile_SporeSpawnsStalk"),
            "spritemap": address("EnemyProjSpritemaps_SporeSpawnsStalk"),
            "positionRoutine": address("UpdateSporeSpawnStalkSegmentPositions"),
            "segmentCount": 4,
            "fractionsFromOrigin": ["fixed-base", "quarter", "half", "three-quarter"],
        },
        "spawnersAndSpores": {
            "sourceBank": 0x86,
            "spritemapBank": 0x8D,
            "projectileMapCount": 6,
        },
        "paletteRows": {
            "baseSpores": 1,
            "healthBody": 4,
            "deathBody": 8,
            "deathLevel": 7,
            "deathBackground": 7,
        },
    }
    frame_occurrences = sum(
        item["kind"] == "frame" for record in instruction_lists for item in record["records"]
    )
    guided_frames = sum(
        item["kind"] == "frame" for record in guided_animations for item in record["records"]
    )
    frame_addresses = {
        int(item["spritemapSnesAddress"])
        for record in instruction_lists for item in record["records"] if item["kind"] == "frame"
    }
    totals = {
        "headerCount": len(headers),
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "standardSpritemapCount": len(standard),
        "standardOamEntryCount": sum(int(record["entryCount"]) for record in standard),
        "activeExtendedSpritemapCount": len(active_extended),
        "activeExtendedChildCount": sum(int(record["childCount"]) for record in active_extended),
        "unusedExtendedSpritemapCount": len(unused_extended),
        "projectileSpritemapCount": len(projectile_maps),
        "projectileOamEntryCount": sum(int(record["entryCount"]) for record in projectile_maps),
        "instructionListCount": len(instruction_lists),
        "frameOccurrenceCount": frame_occurrences,
        "uniqueFrameCount": len(frame_addresses),
        "handlerOccurrenceCount": sum(
            item["kind"] == "handler" for record in instruction_lists for item in record["records"]
        ),
        "guidedAnimationCount": len(guided_animations),
        "guidedAnimationFrameCount": guided_frames,
        "paletteCount": len(palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "assets": aggregate_hash(assets),
        "standardSpritemaps": aggregate_hash(standard),
        "activeExtendedSpritemaps": aggregate_hash(active_extended),
        "unusedExtendedSpritemaps": aggregate_hash(unused_extended),
        "projectileSpritemaps": aggregate_hash(projectile_maps),
        "instructionLists": aggregate_hash(instruction_lists),
        "guidedAnimations": aggregate_hash(guided_animations),
        "palettes": aggregate_hash(palettes),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "ownership": ownership,
        "headers": headers,
        "assets": assets,
        "standardSpritemaps": standard,
        "activeExtendedSpritemaps": active_extended,
        "unusedExtendedSpritemaps": unused_extended,
        "projectileSpritemaps": projectile_maps,
        "instructionLists": instruction_lists,
        "guidedAnimations": guided_animations,
        "palettes": palettes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Spore Spawn manifest: "
        f"{totals['activeExtendedSpritemapCount']} body maps / "
        f"{totals['projectileSpritemapCount']} projectile maps, "
        f"{totals['instructionListCount']} lists / {totals['frameOccurrenceCount']} frames"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
