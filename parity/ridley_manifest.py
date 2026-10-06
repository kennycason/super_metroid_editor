#!/usr/bin/env python3
"""Build Ridley's exact shared graphics, custom OAM, DMA, palette, and encounter manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "ridley.json"

HEADER_LABELS = ("EnemyHeaders_RidleyCeres", "EnemyHeaders_Ridley")
BASE_ASSETS = (
    ("Tiles_Ridley_0", 0x0440),
    ("Tiles_Ridley_1", 0x0200),
    ("Tiles_Ridley_2", 0x0F40),
    ("Tiles_Ridley_3", 0x0200),
    ("Tiles_Ridley_4", 0x0880),
)
AUXILIARY_ASSETS = (
    # Enemy set word $E001 selects the alternate $0400-byte destination,
    # placing this block at physical OBJ tiles $E0..FF for the forward turn.
    ("Tiles_RidleyExplosion", 0x0400, 0xE0),
)
RUNTIME_ASSETS = (
    ("Tiles_RidleysRibsAndClaws_0", 0x0040, 0x22),
    ("Tiles_RidleysRibsAndClaws_1", 0x0040, 0x22),
    ("Tiles_RidleysRibsAndClaws_2", 0x0080, 0xAC),
    ("Tiles_RidleysRibsAndClaws_3", 0x0040, 0x32),
    ("Tiles_RidleysRibsAndClaws_4", 0x0040, 0x32),
    ("Tiles_RidleysRibsAndClaws_5", 0x0080, 0xBC),
)
BODY_LABELS = (
    "ExtendedSpritemap_Ridley_FacingLeft",
    "ExtendedSpritemap_Ridley_FacingRight",
    "ExtendedSpritemap_Ridley_FacingLeft_MouthHalfOpen",
    "ExtendedSpritemap_Ridley_FacingLeft_MouthOpen",
    "ExtendedSpritemap_Ridley_FacingRight_MouthHalfOpen",
    "ExtendedSpritemap_Ridley_FacingRight_MouthOpen",
    "ExtendedSpritemap_Ridley_FacingLeft_LegsHalfExtended",
    "ExtendedSpritemap_Ridley_FacingLeft_LegsExtended",
    "ExtendedSpritemap_Ridley_FacingRight_LegsHalfExtended",
    "ExtendedSpritemap_Ridley_FacingRight_LegsExtended",
    "ExtendedSpritemap_Ridley_FacingForward",
)
WING_LABELS = (
    "Spritemap_RidleyWings_FacingLeft_FullyRaised",
    "Spritemap_RidleyWings_FacingLeft_MostlyRaised",
    "Spritemap_RidleyWings_FacingLeft_SlightlyRaised",
    "Spritemap_RidleyWings_FacingLeft_SlightlyLowered",
    "Spritemap_RidleyWings_FacingLeft_MostlyLowered",
    "Spritemap_RidleyWings_FacingLeft_FullyLowered",
    "Spritemap_RidleyWings_FacingRight_FullyRaised",
    "Spritemap_RidleyWings_FacingRight_MostlyRaised",
    "Spritemap_RidleyWings_FacingRight_SlightlyRaised",
    "Spritemap_RidleyWings_FacingRight_SlightlyLowered",
    "Spritemap_RidleyWings_FacingRight_MostlyLowered",
    "Spritemap_RidleyWings_FacingRight_FullyLowered",
)
TAIL_LABELS = (
    "Spritemap_RidleyTail_Large",
    "Spritemap_RidleyTail_Medium",
    "Spritemap_RidleyTail_Small",
    "Spritemap_RidleyTailTip_PointingLeft",
    "Spritemap_RidleyTailTip_PointingLeftUpLeft",
    "Spritemap_RidleyTailTip_PointingUpLeft",
    "Spritemap_RidleyTailTip_PointingUpUpLeft",
    "Spritemap_RidleyTailTip_PointingUp",
    "Spritemap_RidleyTailTip_PointingUpUpRight",
    "Spritemap_RidleyTailTip_PointingUpRight",
    "Spritemap_RidleyTailTip_PointingRightUpRight",
    "Spritemap_RidleyTailTip_PointingRight",
    "Spritemap_RidleyTailTip_PointingRightDownRight",
    "Spritemap_RidleyTailTip_PointingDownRight",
    "Spritemap_RidleyTailTip_PointingDownDownRight",
    "Spritemap_RidleyTailTip_PointingDown",
    "Spritemap_RidleyTailTip_PointingDownDownLeft",
    "Spritemap_RidleyTailTip_PointingDownLeft",
    "Spritemap_RidleyTailTip_PointingLeftDownLeft",
)
ANIMATION_LABELS = (
    "InstList_Ridley_FacingLeft_OpeningRoar",
    "InstList_Ridley_FacingRight_OpeningRoar",
    "InstList_RidleyCeres_FacingLeft_Lunging",
    "UNUSED_InstList_RidleyCeres_FacingRight_Lunging_A6E576",
    "InstList_RidleyCeres_RetrieveBabyMetroid",
    "UNUSED_InstList_RidleyCeres_FacingRight_RetrieveBabyMetroid_A6E676",
    "InstList_Ridley_TurnFromLeftToRight",
    "InstList_Ridley_TurnFromRightToLeft",
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    payload = json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(payload)


def read_word(data: bytes, offset: int) -> int:
    return data[offset] | data[offset + 1] << 8


def compact_oam(record: Dict[str, object]) -> Dict[str, object]:
    result = {
        "sourceLabel": record["sourceLabel"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
        "sourceDeclaredUnused": bool(record["sourceDeclaredUnused"]),
        "rawSha256": record["rawSha256"],
    }
    if "entryCount" in record:
        result["entryCount"] = int(record["entryCount"])
    if "childCount" in record:
        result["childCount"] = int(record["childCount"])
        result["children"] = [
            {
                "childType": child["childType"],
                "childSnesAddress": int(child["childSnesAddress"]),
            }
            for child in record["children"]
        ]
    return result


def compact_instruction(record: Dict[str, object]) -> Dict[str, object]:
    records = []
    for item in record["records"]:
        compact = {
            "kind": item["kind"],
            "snesAddress": int(item["snesAddress"]),
            "size": int(item["size"]),
        }
        if item["kind"] == "frame":
            compact.update(
                duration=int(item["duration"]),
                spritemapSnesAddress=int(item["spritemapSnesAddress"]),
                spritemapType=item["spritemapType"],
            )
        elif item["kind"] == "handler":
            compact.update(
                handlerSnesAddress=int(item["handlerSnesAddress"]),
                operandByteCount=int(item["operandByteCount"]),
                controlFlow=item["controlFlow"],
            )
        records.append(compact)
    return {
        "sourceLabel": record["sourceLabel"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
        "sourceDeclaredUnused": bool(record["sourceDeclaredUnused"]),
        "records": records,
    }


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
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityRidley")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Ridley prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    assets_by_name = {record["name"]: record for record in reports["assets"]["assets"]}

    def collect_assets(specs: Sequence[tuple]) -> list[Dict[str, object]]:
        result = []
        for spec in specs:
            name, expected_size = spec[:2]
            record = assets_by_name.get(name)
            if record is None or int(record["size"]) != expected_size:
                fail(f"Ridley asset missing or changed: {name}")
            compact = {key: record[key] for key in ("name", "snesAddress", "size", "sha256", "asset")}
            if len(spec) == 3:
                compact["targetPhysicalTile"] = spec[2]
            result.append(compact)
        return result

    base_assets = collect_assets(BASE_ASSETS)
    auxiliary_assets = collect_assets(AUXILIARY_ASSETS)
    runtime_assets = collect_assets(RUNTIME_ASSETS)
    if int(base_assets[0]["snesAddress"]) != 0xB09400:
        fail("Ridley's shared graphics no longer begin at $B0:9400")
    for previous, current in zip(base_assets, base_assets[1:]):
        if int(previous["snesAddress"]) + int(previous["size"]) != int(current["snesAddress"]):
            fail("Ridley's five base graphics assets are no longer contiguous")
    if sum(int(record["size"]) for record in base_assets) != 0x2000:
        fail("Ridley's base graphics no longer total 0x2000 bytes")
    if (
        int(auxiliary_assets[0]["snesAddress"]) != 0xB0B400
        or int(auxiliary_assets[0]["targetPhysicalTile"]) != 0xE0
    ):
        fail("Ridley's facing-forward auxiliary OBJ source changed")

    headers_by_name = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    headers = []
    for label in HEADER_LABELS:
        record = headers_by_name.get(label)
        if record is None:
            fail(f"missing Ridley header {label}")
        fields = record["fields"]
        graphics = record["graphics"]
        if int(fields["bank"]["value"]) != 0xA6:
            fail(f"{label} no longer uses AI bank $A6")
        if fields["palette"]["targetLabel"] != "Palette_Ridley":
            fail(f"{label} no longer shares Palette_Ridley")
        if fields["tileData"]["targetLabel"] != "Tiles_Ridley_0" or int(graphics["transferSize"]) != 0x2000:
            fail(f"{label} no longer shares Ridley's exact 0x2000-byte graphics transfer")
        headers.append({
            "sourceLabel": label,
            "speciesId": int(record["speciesId"]),
            "snesAddress": int(record["snesAddress"]),
            "rawHeaderSha256": record["rawHeaderSha256"],
            "health": int(fields["health"]["value"]),
            "damage": int(fields["damage"]["value"]),
            "bossId": int(fields["bossID"]["value"]),
            "layer": int(fields["layer"]["value"]),
            "mainAiLabel": fields["mainAI"]["targetLabel"],
            "hurtAiLabel": fields["hurtAI"]["targetLabel"],
            "timeFrozenLabel": fields["timeIsFrozen"].get("targetLabel"),
            "tileDataSnesAddress": int(fields["tileData"]["value"]),
            "tileDataSize": int(graphics["transferSize"]),
            "graphicsSha256": graphics["sha256"],
        })
    if len({record["graphicsSha256"] for record in headers}) != 1:
        fail("Ceres and Norfair Ridley no longer share identical graphics")

    standard_by_name = {record["sourceLabel"]: record for record in reports["oam"]["standardSpritemaps"]}
    extended_by_name = {record["sourceLabel"]: record for record in reports["oam"]["extendedSpritemaps"]}
    body_maps = [compact_oam(extended_by_name[label]) for label in BODY_LABELS]
    wing_maps = [compact_oam(standard_by_name[label]) for label in WING_LABELS]
    tail_maps = [compact_oam(standard_by_name[label]) for label in TAIL_LABELS]
    body_child_addresses = {
        int(child["childSnesAddress"])
        for record in body_maps
        for child in record["children"]
    }
    body_child_maps = [
        compact_oam(record)
        for record in reports["oam"]["standardSpritemaps"]
        if int(record["snesAddress"]) in body_child_addresses
    ]
    if len(body_child_maps) != 17:
        fail(f"Ridley body child inventory changed: {len(body_child_maps)}")

    instructions_by_name = {
        record["sourceLabel"]: record for record in reports["instructions"]["lists"]
    }
    animation_lists = [compact_instruction(instructions_by_name[label]) for label in ANIMATION_LABELS]
    extended_addresses = {int(record["snesAddress"]) for record in body_maps}
    for record in animation_lists:
        for item in record["records"]:
            if item["kind"] == "frame" and int(item["spritemapSnesAddress"]) not in extended_addresses:
                fail(f"{record['sourceLabel']} references a non-Ridley body map")

    def raw_record(label: str, size: int) -> Dict[str, object]:
        snes = address(label)
        raw = rom[snes_to_pc(snes):snes_to_pc(snes) + size]
        return {
            "sourceLabel": label,
            "snesAddress": snes,
            "size": size,
            "sha256": sha256(raw),
            "words": [read_word(raw, offset) for offset in range(0, size, 2)],
        }

    runtime_tables = {
        "enemySetVramLayout": raw_record("EnemySets_Ridley", 0x08),
        "wingPointersLeft": raw_record("DrawRidleyWings_spritemapPointersLeft", 0x14),
        "wingPointersRight": raw_record("DrawRidleyWings_spritemapPointersRight", 0x14),
        "tailTipPointers": raw_record("RidleyTailTipSpritemapPointers", 0x20),
        "ribsAnimation": raw_record("RidleyRibsAnimationTable", 0x1A),
        "feetDmaPointers": raw_record("DrawRidleysFeet_unclenched", 0x08),
    }
    if runtime_tables["enemySetVramLayout"]["words"] != [0xE17F, 0x0001, 0xE1BF, 0xE001]:
        fail("Ridley's enemy-set VRAM placement contract changed")
    expected_wings = [int(record["snesAddress"]) & 0xFFFF for record in wing_maps]
    expected_left = expected_wings[:6] + expected_wings[4:0:-1]
    expected_right = expected_wings[6:] + expected_wings[10:6:-1]
    if runtime_tables["wingPointersLeft"]["words"] != expected_left:
        fail("left wing animation pointer table changed")
    if runtime_tables["wingPointersRight"]["words"] != expected_right:
        fail("right wing animation pointer table changed")

    palette_records = [
        raw_record("Palette_Ridley", 0x20),
        raw_record("Palette_Ridley_HealthBased_Below9000", 0x1C),
        raw_record("Palette_Ridley_HealthBased_Below5400", 0x1C),
        raw_record("Palette_Ridley_HealthBased_Below1800", 0x1C),
    ]

    bank_a6 = (disassembly / "src" / "bank_A6.asm").read_text(encoding="utf-8")
    required_tokens = (
        "Used for both Ridleys",
        "JSR.W DrawRidleyWings",
        "JSR.W DrawRidleyTail",
        "LDA.W #$7220",
        "LDA.W #$7AC0",
        "LDA.L RidleyCeres.hitCounter",
        "LDA.W Enemy.health",
    )
    for token in required_tokens:
        if token not in bank_a6:
            fail(f"Ridley source contract changed: {token}")

    ownership = {
        "sharedPixels": {
            "snesAddress": 0xB09400,
            "size": 0x2000,
            "sha256": headers[0]["graphicsSha256"],
            "assetLabels": [record["name"] for record in base_assets],
            "projectEditKey": 'spriteTileBlocks["enemy:E17F"]',
        },
        "sharedLowObjPage": {
            "snesAddress": int(auxiliary_assets[0]["snesAddress"]),
            "size": int(auxiliary_assets[0]["size"]),
            "sha256": auxiliary_assets[0]["sha256"],
            "assetLabel": auxiliary_assets[0]["name"],
            "sourceSpeciesId": 0xE1BF,
            "physicalTileStart": 0xE0,
            "physicalTileEnd": 0xFF,
            "editable": False,
        },
        "runtimeDma": {
            "assetLabels": [record["name"] for record in runtime_assets],
            "editable": False,
            "destinations": [0x7220, 0x7320, 0x7AC0, 0x7BC0],
        },
        "placement": {
            "sourceBank": 0xA6,
            "bodyExtendedMaps": len(body_maps),
            "bodyChildMaps": len(body_child_maps),
            "wingMaps": len(wing_maps),
            "tailMaps": len(tail_maps),
            "editable": False,
        },
        "encounters": {
            "sharedVisualRecipe": True,
            "ceresSpeciesId": 0xE13F,
            "norfairSpeciesId": 0xE17F,
            "ceresPaletteDriver": "hit counter",
            "norfairPaletteDriver": "health thresholds",
        },
    }
    totals = {
        "headerCount": len(headers),
        "baseAssetCount": len(base_assets),
        "baseAssetByteCount": sum(int(record["size"]) for record in base_assets),
        "auxiliaryAssetCount": len(auxiliary_assets),
        "auxiliaryAssetByteCount": sum(int(record["size"]) for record in auxiliary_assets),
        "runtimeDmaAssetCount": len(runtime_assets),
        "runtimeDmaByteCount": sum(int(record["size"]) for record in runtime_assets),
        "bodyExtendedSpritemapCount": len(body_maps),
        "bodyExtendedChildCount": sum(int(record["childCount"]) for record in body_maps),
        "bodyChildSpritemapCount": len(body_child_maps),
        "bodyChildOamEntryCount": sum(int(record["entryCount"]) for record in body_child_maps),
        "wingSpritemapCount": len(wing_maps),
        "wingOamEntryCount": sum(int(record["entryCount"]) for record in wing_maps),
        "tailSpritemapCount": len(tail_maps),
        "tailOamEntryCount": sum(int(record["entryCount"]) for record in tail_maps),
        "animationListCount": len(animation_lists),
        "activeAnimationListCount": sum(not bool(record["sourceDeclaredUnused"]) for record in animation_lists),
        "unusedAnimationListCount": sum(bool(record["sourceDeclaredUnused"]) for record in animation_lists),
        "animationFrameCount": sum(
            item["kind"] == "frame" for record in animation_lists for item in record["records"]
        ),
        "animationHandlerCount": sum(
            item["kind"] == "handler" for record in animation_lists for item in record["records"]
        ),
        "paletteStageCount": len(palette_records),
        "runtimeTableCount": len(runtime_tables),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "baseAssets": aggregate_hash(base_assets),
        "auxiliaryAssets": aggregate_hash(auxiliary_assets),
        "runtimeAssets": aggregate_hash(runtime_assets),
        "bodyExtendedSpritemaps": aggregate_hash(body_maps),
        "bodyChildSpritemaps": aggregate_hash(body_child_maps),
        "wingSpritemaps": aggregate_hash(wing_maps),
        "tailSpritemaps": aggregate_hash(tail_maps),
        "animationLists": aggregate_hash(animation_lists),
        "runtimeTables": aggregate_hash(list(runtime_tables.values())),
        "palettes": aggregate_hash(palette_records),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "ownership": ownership,
        "headers": headers,
        "baseAssets": base_assets,
        "auxiliaryAssets": auxiliary_assets,
        "runtimeAssets": runtime_assets,
        "bodyExtendedSpritemaps": body_maps,
        "bodyChildSpritemaps": body_child_maps,
        "wingSpritemaps": wing_maps,
        "tailSpritemaps": tail_maps,
        "animationLists": animation_lists,
        "runtimeTables": runtime_tables,
        "palettes": palette_records,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Ridley manifest: "
        f"{totals['bodyExtendedSpritemapCount']} body maps, "
        f"{totals['wingSpritemapCount']} wing maps, "
        f"{totals['tailSpritemapCount']} tail maps, "
        f"{totals['animationListCount']} encounter lists / "
        f"{totals['animationFrameCount']} frames"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
