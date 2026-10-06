#!/usr/bin/env python3
"""Build exact helper-selected animation-route fixtures for ordinary enemies."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "ordinary-enemy-animations.json"

SPECIES = (
    {
        "key": "puyo", "name": "Puyo", "speciesId": 0xCFBF,
        "header": "EnemyHeaders_Puyo", "asset": "Tiles_Puyo", "palette": "Palette_Puyo",
        "mapPrefix": "Spritemap_Puyo_", "mapCount": 8, "listPrefix": "InstList_Puyo_",
        "animations": (
            ("puyo-ground-fast", "Grounded · fast", ("InstList_Puyo_GroundedDropping_Fast",), (4,), True),
            ("puyo-ground-medium", "Grounded · medium", ("InstList_Puyo_GroundedDropping_Medium",), (4,), True),
            ("puyo-ground-slow", "Grounded · slow", ("InstList_Puyo_GroundedDropping_Slow",), (4,), True),
            ("puyo-hop-right", "Hop poses · right", (
                "InstList_Puyo_HoppingRight_0_HoppingLeft_4",
                "InstList_Puyo_HoppingRight_1_HoppingLeft_3",
                "InstList_Puyo_Hopping_2",
                "InstList_Puyo_HoppingRight_3_HoppingLeft_1",
                "InstList_Puyo_HoppingRight_4_HoppingLeft_0",
            ), (1, 1, 1, 1, 1), False),
            ("puyo-hop-left", "Hop poses · left", (
                "InstList_Puyo_HoppingRight_4_HoppingLeft_0",
                "InstList_Puyo_HoppingRight_3_HoppingLeft_1",
                "InstList_Puyo_Hopping_2",
                "InstList_Puyo_HoppingRight_1_HoppingLeft_3",
                "InstList_Puyo_HoppingRight_0_HoppingLeft_4",
            ), (1, 1, 1, 1, 1), False),
        ),
    },
    {
        "key": "owtch", "name": "Owtch", "speciesId": 0xD03F,
        "header": "EnemyHeaders_Owtch", "asset": "Tiles_Owtch", "palette": "Palette_Owtch",
        "mapPrefix": "Spritemap_Owtch_", "mapCount": 3, "listPrefix": "InstList_Owtch_",
        "animations": (
            ("owtch-left", "Moving left", ("InstList_Owtch_MovingLeft_1",), (3,), True),
            ("owtch-right", "Moving right", ("InstList_Owtch_MovingRight_1",), (3,), True),
        ),
    },
    {
        "key": "choot", "name": "Choot", "speciesId": 0xD3BF,
        "header": "EnemyHeaders_Choot", "asset": "Tiles_Choot", "palette": "Palette_Choot",
        "mapPrefix": "Spritemap_Choot_", "mapCount": 4, "listPrefix": "InstList_Choot_",
        "animations": (
            ("choot-idle", "Idle", ("InstList_Choot_Idle",), (1,), False),
            ("choot-jumping", "Jumping", ("InstList_Choot_Jumping",), (2,), False),
            ("choot-falling", "Falling", ("InstList_Choot_Falling",), (2,), False),
        ),
    },
    {
        "key": "sbug", "name": "Sbug (roach)", "speciesId": 0xD87F,
        "header": "EnemyHeaders_Sbug", "asset": "Tiles_Sbug", "palette": "Palette_Sbug",
        "headerAliases": (
            (0xD8BF, "EnemyHeaders_Sbug2", "Sbug (roach, alternate VRAM)"),
        ),
        "aiBank": 0xA3,
        "mapPrefix": "Spritemap_Sbug_", "mapCount": 24, "listPrefix": "InstList_Sbug_",
        "animations": (
            ("sbug-up", "Facing up", ("InstList_Sbug_FacingUp",), (4,), True),
            ("sbug-up-left", "Facing up-left", ("InstList_Sbug_FacingUpLeft",), (4,), True),
            ("sbug-left", "Facing left", ("InstList_Sbug_FacingLeft",), (4,), True),
            ("sbug-down-left", "Facing down-left", ("InstList_Sbug_FacingDownLeft",), (4,), True),
            ("sbug-down", "Facing down", ("InstList_Sbug_FacingDown",), (4,), True),
            ("sbug-down-right", "Facing down-right", ("InstList_Sbug_FacingDownRight",), (4,), True),
            ("sbug-right", "Facing right", ("InstList_Sbug_FacingRight",), (4,), True),
            ("sbug-up-right", "Facing up-right", ("InstList_Sbug_FacingUpRight",), (4,), True),
        ),
    },
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def compact_instruction(record: Dict[str, object]) -> Dict[str, object]:
    result: Dict[str, object] = {
        "kind": record["kind"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
    }
    if record["kind"] == "frame":
        result.update(
            duration=int(record["duration"]),
            spritemapSnesAddress=int(record["spritemapSnesAddress"]),
        )
    else:
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
    paths = {
        "symbols": report_dir / "symbols.json",
        "assets": report_dir / "assets.json",
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    rom_path = disassembly / "SM.sfc"
    if not rom_path.is_file() or any(not path.is_file() for path in paths.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityOrdinaryEnemyAnimations")

    rom = rom_path.read_bytes()
    reports = {key: json.loads(path.read_text(encoding="utf-8")) for key, path in paths.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("ordinary-enemy prerequisite reports belong to different revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}
    headers_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    assets_by_name = {record["name"]: record for record in reports["assets"]["assets"]}
    maps_by_label = {record["sourceLabel"]: record for record in reports["oam"]["standardSpritemaps"]}
    lists_by_label = {record["sourceLabel"]: record for record in reports["instructions"]["lists"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    species_records = []
    all_maps = []
    all_lists = []
    animations = []
    palettes = []
    for spec in SPECIES:
        header_specs = [
            (spec["speciesId"], spec["header"], spec["name"]),
            *spec.get("headerAliases", ()),
        ]
        validated_headers = []
        for species_id, header_label, display_name in header_specs:
            header = headers_by_label.get(header_label)
            if header is None or int(header["speciesId"]) != species_id:
                fail(f"missing or changed {display_name} header")
            fields = header["fields"]
            ai_bank = int(spec.get("aiBank", 0xA2))
            if int(fields["bank"]["value"]) != ai_bank:
                fail(f"{display_name} no longer uses AI bank ${ai_bank:02X}")
            if fields["tileData"]["targetLabel"] != spec["asset"]:
                fail(f"{display_name} tile ownership changed")
            if fields["palette"]["targetLabel"] != spec["palette"]:
                fail(f"{display_name} palette ownership changed")
            validated_headers.append((species_id, header_label, display_name, header))

        fields = validated_headers[0][3]["fields"]
        asset = assets_by_name.get(spec["asset"])
        if asset is None or int(asset["snesAddress"]) != int(fields["tileData"]["value"]):
            fail(f"missing {spec['asset']} asset")

        species_maps = [
            record for label, record in maps_by_label.items()
            if str(label).startswith(spec["mapPrefix"])
        ]
        species_maps.sort(key=lambda record: int(record["snesAddress"]))
        if len(species_maps) != spec["mapCount"] or any(record["sourceDeclaredUnused"] for record in species_maps):
            fail(f"{spec['name']} OAM inventory changed")
        compact_maps = [{
            "speciesKey": spec["key"],
            "sourceLabel": record["sourceLabel"],
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "entryCount": int(record["entryCount"]),
            "sha256": record["rawSha256"],
        } for record in species_maps]
        all_maps.extend(compact_maps)

        species_lists = [
            record for label, record in lists_by_label.items()
            if str(label).startswith(spec["listPrefix"])
        ]
        species_lists.sort(key=lambda record: int(record["snesAddress"]))
        compact_lists = [{
            "speciesKey": spec["key"],
            "sourceLabel": record["sourceLabel"],
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "records": [compact_instruction(item) for item in record["records"]],
        } for record in species_lists]
        all_lists.extend(compact_lists)

        for key, name, list_labels, expected_frames, loop in spec["animations"]:
            list_addresses = [address(label) for label in list_labels]
            for label, expected in zip(list_labels, expected_frames):
                record = lists_by_label.get(label)
                if record is None:
                    fail(f"missing guided list {label}")
                actual = sum(item["kind"] == "frame" for item in record["records"])
                if actual != expected:
                    fail(f"{label} frame count changed: {actual}")
            animations.append({
                "key": key,
                "name": name,
                "speciesKey": spec["key"],
                "speciesId": spec["speciesId"],
                "speciesIds": [record[0] for record in validated_headers],
                "instructionLists": list_addresses,
                "expectedFramesPerList": list(expected_frames),
                "loop": loop,
                "renderFrameCount": sum(expected_frames),
            })

        palette_address = address(spec["palette"])
        palette_raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 32]
        palettes.append({
            "speciesKey": spec["key"],
            "sourceLabel": spec["palette"],
            "snesAddress": palette_address,
            "size": 32,
            "sha256": sha256(palette_raw),
        })
        for index, (species_id, header_label, display_name, header) in enumerate(validated_headers):
            species_records.append({
                "key": spec["key"] if index == 0 else f"{spec['key']}-{species_id:04x}",
                "familyKey": spec["key"],
                "displayName": display_name,
                "speciesId": species_id,
                "headerLabel": header_label,
                "headerSnesAddress": int(header["snesAddress"]),
                "tileDataLabel": spec["asset"],
                "tileDataSnesAddress": int(asset["snesAddress"]),
                "tileDataSize": int(asset["size"]),
                "tileDataSha256": asset["sha256"],
                "paletteLabel": spec["palette"],
                "paletteSnesAddress": palette_address,
                "defaultInstructionList": address(spec["animations"][0][2][0]),
                "spritemapCount": len(compact_maps),
                "instructionListCount": len(compact_lists),
                "animationCount": len(spec["animations"]),
            })

    ownership = [{
        "speciesKey": record["key"],
        "pixelOwner": record["tileDataLabel"],
        "paletteOwner": record["paletteLabel"],
        "placementOwner": f"bank-${int(headers_by_label[record['headerLabel']]['fields']['bank']['value']):02X}-{record['familyKey']}-instruction-lists",
        "editable": ["pixels", "header palette"],
        "readOnly": ["OAM placement", "instruction timing", "AI action selection"],
    } for record in species_records]
    unique_assets = {record["tileDataLabel"]: record for record in species_records}
    totals = {
        "speciesCount": len(species_records),
        "assetCount": len(unique_assets),
        "assetByteCount": sum(int(record["tileDataSize"]) for record in unique_assets.values()),
        "spritemapCount": len(all_maps),
        "oamEntryCount": sum(int(record["entryCount"]) for record in all_maps),
        "instructionListCount": len(all_lists),
        "sourceFrameOccurrenceCount": sum(
            item["kind"] == "frame" for record in all_lists for item in record["records"]
        ),
        "animationCount": len(animations),
        "animationFrameCount": sum(int(record["renderFrameCount"]) for record in animations),
        "paletteCount": len(palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash(ownership),
        "species": aggregate_hash(species_records),
        "spritemaps": aggregate_hash(all_maps),
        "instructionLists": aggregate_hash(all_lists),
        "animations": aggregate_hash(animations),
        "palettes": aggregate_hash(palettes),
    }
    result = {
        "schemaVersion": 2,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "ownership": ownership,
        "species": species_records,
        "spritemaps": all_maps,
        "instructionLists": all_lists,
        "animations": animations,
        "palettes": palettes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Ordinary-enemy animation manifest: "
        f"{totals['speciesCount']} species / {totals['spritemapCount']} maps / "
        f"{totals['animationCount']} actions / {totals['animationFrameCount']} guided frames"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
