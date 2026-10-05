#!/usr/bin/env python3
"""Build Botwoon's head, history-following body, projectile, palette, and animation manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "botwoon.json"

DIRECTIONS = (
    ("up", "Up", "AimUp_FacingRight", "AimingUp_FacingRight", "Up_FacingRight", "20", 16),
    ("up-right", "Up-right", "AimingUpRight", "AimingUpRight", "UpRight", "27", 16),
    ("right", "Right", "AimingRight", "AimingRight", "Right", "26", 16),
    ("down-right", "Down-right", "AimingDownRight", "AimingDownRight", "DownRight", "25", 16),
    ("down", "Down", "AimDown_FacingRight", "AimingDown_FacingRight", "Down_FacingRight", "24", 16),
    ("down-left", "Down-left", "AimingDownLeft", "AimingDownLeft", "DownLeft", "23", 16),
    ("left", "Left", "AimingLeft", "AimingLeft", "Left", "22", 25),
    ("up-left", "Up-left", "AimingUpLeft", "AimingUpLeft", "UpLeft", "21", 16),
)

BODY_MAP_BASES = ("1C", "18", "14", "10", "C", "8", "4", "0")
TAIL_SUFFIXES = {
    "up": "Up_FacingRight",
    "up-right": "UpRight",
    "right": "Right",
    "down-right": "DownRight",
    "down": "Down",
    "down-left": "DownLeft",
    "left": "Left",
    "up-left": "UpLeft",
}
PROJECTILE_LIST_SIZES = {
    **{f"InstList_EnemyProjectile_BotwoonsBody_{row[4]}": 20 for row in DIRECTIONS},
    **{f"InstList_EnemyProjectile_BotwoonsTail_{TAIL_SUFFIXES[row[0]]}": 6 for row in DIRECTIONS},
    "InstList_EnemyProjectile_BotwoonsBodyTail_Hidden": 6,
    "InstList_EnemyProjectile_BotwoonsSpit": 24,
}


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
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityBotwoon")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Botwoon prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    header = next(
        (record for record in reports["headers"]["headers"] if record["sourceLabel"] == "EnemyHeaders_Botwoon"),
        None,
    )
    if header is None or int(header["speciesId"]) != 0xF293:
        fail("missing or changed Botwoon header")
    fields = header["fields"]
    if int(fields["tileDataSize"]["value"]) != 0x1800:
        fail("Botwoon tile transfer size changed")
    if fields["tileData"]["targetLabel"] != "Tiles_Botwoon":
        fail("Botwoon no longer owns Tiles_Botwoon")
    if fields["palette"]["targetLabel"] != "Palette_Botwoon":
        fail("Botwoon palette ownership changed")
    if int(fields["bank"]["value"]) != 0xB3:
        fail("Botwoon no longer uses AI bank $B3")
    headers = [{
        "sourceLabel": "EnemyHeaders_Botwoon",
        "speciesId": 0xF293,
        "headerSnesAddress": int(header["snesAddress"]),
        "tileDataSize": 0x1800,
        "tileDataSnesAddress": int(fields["tileData"]["value"]),
        "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
        "aiBank": int(fields["bank"]["value"]),
        "layer": int(fields["layer"]["value"]),
        "rawHeaderSha256": header["rawHeaderSha256"],
    }]

    asset = next((record for record in reports["assets"]["assets"] if record["name"] == "Tiles_Botwoon"), None)
    if asset is None or int(asset["snesAddress"]) != address("Tiles_Botwoon") or int(asset["size"]) != 0x1800:
        fail("Tiles_Botwoon ownership or size changed")
    assets = [{
        "sourceLabel": "Tiles_Botwoon",
        "role": "shared-head-body-tail-spit-obj",
        "snesAddress": int(asset["snesAddress"]),
        "size": int(asset["size"]),
        "sha256": asset["sha256"],
        "asset": asset["asset"],
    }]

    head_maps = [
        record for record in reports["oam"]["standardSpritemaps"]
        if "Botwoon" in str(record["sourceLabel"])
    ]
    active_head_maps = [record for record in head_maps if not record["sourceDeclaredUnused"]]
    unused_head_maps = [record for record in head_maps if record["sourceDeclaredUnused"]]
    if (len(head_maps), len(active_head_maps), len(unused_head_maps)) != (40, 30, 10):
        fail(f"Botwoon head OAM inventory changed: {len(active_head_maps)} active / {len(unused_head_maps)} unused")

    active_projectile_labels = [
        *(f"EnemyProjSpritemaps_BotwoonsBody_{value:X}" for value in range(0x28)),
        "EnemyProjSpritemaps_BotwoonsBody_28",
        *(f"EnemyProjSpritemaps_BotwoonsSpit_{index}" for index in range(5)),
    ]
    unused_projectile_labels = [
        *(f"EnemyProjSpritemaps_BotwoonsBody_UpFacingLeft_{index}" for index in range(4)),
        *(f"UNUSED_EnemyProjSpritemaps_BotwoonsBodyTail_{value:X}_8DB{0x762 + value * 7:03X}" for value in range(0x30)),
    ]

    def projectile_map(label: str, unused: bool) -> Dict[str, object]:
        snes_address = address(label)
        pc = snes_to_pc(snes_address)
        count = int.from_bytes(rom[pc:pc + 2], "little")
        if count not in (0, 1):
            fail(f"{label} has unexpected OAM entry count {count}")
        raw = rom[pc:pc + 2 + count * 5]
        return {
            "sourceLabel": label,
            "snesAddress": snes_address,
            "size": len(raw),
            "entryCount": count,
            "sourceDeclaredUnused": unused,
            "sha256": sha256(raw),
        }

    active_projectile_maps = [projectile_map(label, False) for label in active_projectile_labels]
    unused_projectile_maps = [projectile_map(label, True) for label in unused_projectile_labels]

    head_lists_all = [
        record for record in reports["instructions"]["lists"]
        if "Botwoon" in str(record["sourceLabel"])
    ]
    active_head_lists = [record for record in head_lists_all if not record["sourceDeclaredUnused"]]
    unused_head_lists = [record for record in head_lists_all if record["sourceDeclaredUnused"]]
    if (len(head_lists_all), len(active_head_lists), len(unused_head_lists)) != (31, 26, 5):
        fail(f"Botwoon head instruction inventory changed: {len(active_head_lists)} active / {len(unused_head_lists)} unused")

    def instruction_record(record: Dict[str, object]) -> Dict[str, object]:
        return {
            "sourceLabel": record["sourceLabel"],
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "sourceDeclaredUnused": bool(record["sourceDeclaredUnused"]),
            "records": [compact_record(item) for item in record["records"]],
        }

    head_instruction_lists = [instruction_record(record) for record in head_lists_all]

    projectile_instruction_lists = []
    for label, size in PROJECTILE_LIST_SIZES.items():
        snes_address = address(label)
        raw = rom[snes_to_pc(snes_address):snes_to_pc(snes_address) + size]
        projectile_instruction_lists.append({
            "sourceLabel": label,
            "snesAddress": snes_address,
            "size": size,
            "sha256": sha256(raw),
        })
    if len(projectile_instruction_lists) != 18:
        fail(f"Botwoon active projectile instruction inventory changed: {len(projectile_instruction_lists)}")

    palettes = []
    for label, snes_address in [
        ("Palette_Botwoon", address("Palette_Botwoon")),
        *((f"BotwoonHealthBasedPalettes[{index}]", address("BotwoonHealthBasedPalettes") + index * 0x20) for index in range(8)),
    ]:
        raw = rom[snes_to_pc(snes_address):snes_to_pc(snes_address) + 32]
        palettes.append({
            "sourceLabel": label,
            "snesAddress": snes_address,
            "size": 32,
            "sha256": sha256(raw),
        })

    runtime_ranges = []
    for label, size, role in (
        ("InstListPointers_Botwoon", 32, "head-direction-table"),
        ("InstListPointers_Botwoon_spit", 16, "spit-direction-table"),
        ("BotwoonSpeedTable", 12, "health-speed-and-history-distance"),
        ("BotwoonHealthThresholdsForPaletteChange", 16, "palette-health-thresholds"),
        ("UpdateBotwoonPositionHistory", 21, "write-head-history-record"),
        ("UpdateBotwonBodyProjectilePositions", 172, "sample-body-history-records"),
        ("UpdateBotwoonPositionHistoryIndex", 17, "advance-circular-history-index"),
        ("SetBotwoonInstListTableIndices", 115, "orient-body-links"),
        ("BotwoonsBodyTail_InstListPointers", 64, "body-tail-direction-table"),
    ):
        snes_address = address(label)
        raw = rom[snes_to_pc(snes_address):snes_to_pc(snes_address) + size]
        runtime_ranges.append({
            "sourceLabel": label,
            "role": role,
            "snesAddress": snes_address,
            "size": size,
            "sha256": sha256(raw),
        })

    guided_animations = []
    for index, row in enumerate(DIRECTIONS):
        key, name, head_suffix, spit_suffix, body_suffix, tail_hex, open_ticks = row
        body_base = int(BODY_MAP_BASES[index], 16)
        guided_animations.append({
            "key": f"swim-{key}",
            "name": f"Swim · {name}",
            "directionIndex": index,
            "closedHeadSpritemap": address(f"Spritemaps_Botwoon_MouthClosed_Priority2_{head_suffix}"),
            "headInstructionList": address(f"InstList_Botwoon_MouthClosed_{spit_suffix}"),
            "bodySpritemaps": [address(f"EnemyProjSpritemaps_BotwoonsBody_{value:X}") for value in range(body_base, body_base + 4)],
            "tailSpritemap": address(f"EnemyProjSpritemaps_BotwoonsBody_{tail_hex}"),
            "renderFrameCount": 4,
        })
        guided_animations.append({
            "key": f"spit-{key}",
            "name": f"Spit · {name}",
            "directionIndex": index,
            "closedHeadSpritemap": address(f"Spritemaps_Botwoon_MouthClosed_Priority2_{head_suffix}"),
            "openHeadSpritemap": address(
                "Spritemaps_Botwoon_MouthOpen_Priority2_AimingUp_FacingRight"
                if key == "up" else f"Spritemaps_Botwoon_MouthOpen_Priority2_{head_suffix}"
            ),
            "headInstructionList": address(f"InstList_Botwoon_Spit_{spit_suffix}"),
            "closedTicks": 32,
            "openTicks": open_ticks,
            "bodySpritemaps": [address(f"EnemyProjSpritemaps_BotwoonsBody_{value:X}") for value in range(body_base, body_base + 4)],
            "tailSpritemap": address(f"EnemyProjSpritemaps_BotwoonsBody_{tail_hex}"),
            "renderFrameCount": 4 + ((open_ticks + 7) // 8),
        })
    guided_animations.append({
        "key": "spit-projectile",
        "name": "Spit projectile",
        "instructionList": address("InstList_EnemyProjectile_BotwoonsSpit"),
        "spritemaps": [address(f"EnemyProjSpritemaps_BotwoonsSpit_{index}") for index in range(5)],
        "frameTicks": 3,
        "renderFrameCount": 5,
    })

    ownership = {
        "sharedObjPixels": {
            "sourceLabel": "Tiles_Botwoon",
            "snesAddress": address("Tiles_Botwoon"),
            "size": 0x1800,
            "projectEditKey": 'spriteTileBlocks["enemy:F293"]',
        },
        "head": {"sourceBank": 0xB3, "format": "standard-oam", "runtimeDirectionCount": 8},
        "body": {
            "projectileDefinition": address("EnemyProjectile_BotwoonsBody"),
            "projectileCount": 13,
            "bodySegmentCount": 12,
            "tailSegmentCount": 1,
            "positionHistoryBytes": 0x400,
            "positionHistoryRecordBytes": 4,
            "speedStages": [
                {"speed": 2, "historyByteDistance": 0x18, "historyFrameDistance": 6, "pixelDistance": 12},
                {"speed": 3, "historyByteDistance": 0x10, "historyFrameDistance": 4, "pixelDistance": 12},
                {"speed": 4, "historyByteDistance": 0x0C, "historyFrameDistance": 3, "pixelDistance": 12},
            ],
        },
        "spit": {"projectileDefinition": address("EnemyProjectile_BotwoonsSpit"), "frameCount": 5},
        "paletteRows": {"header": 1, "runtimeHealth": 8},
    }

    totals = {
        "headerCount": len(headers),
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "activeHeadSpritemapCount": len(active_head_maps),
        "unusedHeadSpritemapCount": len(unused_head_maps),
        "headOamEntryCount": sum(int(record["entryCount"]) for record in head_maps),
        "activeProjectileSpritemapCount": len(active_projectile_maps),
        "unusedProjectileSpritemapCount": len(unused_projectile_maps),
        "projectileOamEntryCount": sum(int(record["entryCount"]) for record in active_projectile_maps + unused_projectile_maps),
        "activeHeadInstructionListCount": len(active_head_lists),
        "unusedHeadInstructionListCount": len(unused_head_lists),
        "headFrameOccurrenceCount": sum(
            item["kind"] == "frame" for record in head_instruction_lists for item in record["records"]
        ),
        "activeProjectileInstructionListCount": len(projectile_instruction_lists),
        "runtimeRangeCount": len(runtime_ranges),
        "guidedAnimationCount": len(guided_animations),
        "guidedAnimationFrameCount": sum(int(record["renderFrameCount"]) for record in guided_animations),
        "paletteCount": len(palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "assets": aggregate_hash(assets),
        "activeHeadSpritemaps": aggregate_hash(active_head_maps),
        "unusedHeadSpritemaps": aggregate_hash(unused_head_maps),
        "activeProjectileSpritemaps": aggregate_hash(active_projectile_maps),
        "unusedProjectileSpritemaps": aggregate_hash(unused_projectile_maps),
        "headInstructionLists": aggregate_hash(head_instruction_lists),
        "projectileInstructionLists": aggregate_hash(projectile_instruction_lists),
        "runtimeRanges": aggregate_hash(runtime_ranges),
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
        "activeHeadSpritemaps": active_head_maps,
        "unusedHeadSpritemaps": unused_head_maps,
        "activeProjectileSpritemaps": active_projectile_maps,
        "unusedProjectileSpritemaps": unused_projectile_maps,
        "headInstructionLists": head_instruction_lists,
        "projectileInstructionLists": projectile_instruction_lists,
        "runtimeRanges": runtime_ranges,
        "guidedAnimations": guided_animations,
        "palettes": palettes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Botwoon manifest: "
        f"{totals['activeHeadSpritemapCount']} active head maps / "
        f"{totals['activeProjectileSpritemapCount']} active projectile maps, "
        f"{totals['guidedAnimationCount']} guided animations"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
