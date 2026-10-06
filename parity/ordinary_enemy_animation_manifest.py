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
    {
        "key": "evir", "name": "Evir", "speciesId": 0xE63F,
        "header": "EnemyHeaders_Evir", "asset": "Tiles_Evir", "palette": "Palette_Evir",
        "headerAliases": (
            (0xE67F, "EnemyHeaders_EvirProjectile", "Evir Projectile (internal helper)"),
        ),
        "animationSpeciesIds": (0xE63F,),
        "defaultInstructionLists": {
            0xE63F: "InstList_Evir_Body_FacingLeft",
            0xE67F: "InstList_Evir_Projectile_Normal",
        },
        "aiBank": 0xA8,
        "mapPrefix": "Spritemap_Evir_", "mapCount": 24, "listPrefix": "InstList_Evir_",
        "animations": (
            ("evir-body-left", "Body · facing left", ("InstList_Evir_Body_FacingLeft",), (6,), True),
            ("evir-body-right", "Body · facing right", ("InstList_Evir_Body_FacingRight",), (6,), True),
            ("evir-arms-left", "Arms · facing left", ("InstList_Evir_Arms_FacingLeft",), (17,), True),
            ("evir-arms-right", "Arms · facing right", ("InstList_Evir_Arms_FacingRight",), (17,), True),
            ("evir-projectile-ready", "Projectile · ready", ("InstList_Evir_Projectile_Normal",), (1,), False),
        ),
    },
    {
        "key": "magdollite", "name": "Magdollite (Lavaman)", "speciesId": 0xE83F,
        "header": "EnemyHeaders_Magdollite", "asset": "Tiles_Magdollite", "palette": "Palette_Magdollite",
        "defaultInstructionLists": {0xE83F: "InstList_Magdollite_Idling_FacingLeft"},
        "aiBank": 0xA8,
        "mapPrefix": "Spritemap_Magdollite_", "mapCount": 29, "listPrefix": "InstList_Magdollite_",
        "animations": (
            ("magdollite-idle-left", "Head · idle left", ("InstList_Magdollite_Idling_FacingLeft",), (4,), True),
            ("magdollite-idle-right", "Head · idle right", ("InstList_Magdollite_Idling_FacingRight",), (4,), True),
            ("magdollite-throw-left", "Hand · throw left", ("InstList_Magdollite_Slave2_ThrowFireballs_FacingLeft",), (6,), False),
            ("magdollite-throw-right", "Hand · throw right", ("InstList_Magdollite_ThrowFireballs_FacingRight",), (6,), False),
            ("magdollite-submerge-left", "Head · submerge left", ("InstList_Magdollite_SplashIntoLavaAndFormBasePillar_Left_0",), (5,), False),
            ("magdollite-submerge-right", "Head · submerge right", ("InstList_Magdollite_SplashIntoLavaAndFormBasePillar_Right_0",), (5,), False),
            ("magdollite-pillar-rise-left", "Base pillar · left", ("InstList_Magdollite_SplashIntoLavaAndFormBasePillar_Left_1",), (1,), True),
            ("magdollite-pillar-rise-right", "Base pillar · right", ("InstList_Magdollite_SplashIntoLavaAndFormBasePillar_Right_1",), (1,), True),
            ("magdollite-pillar-growth", "Pillar · growth poses", (
                "InstList_Magdollite_Slave1_3xPillarStack",
                "InstList_Magdollite_Slave1_4xPillarStack",
                "InstList_Magdollite_Slave1_5xPillarStack",
                "InstList_Magdollite_Slave1_6xPillarStack",
                "InstList_Magdollite_Slave1_7xPillarStack",
                "InstList_Magdollite_Slave1_8xPillarStack",
            ), (1, 1, 1, 1, 1, 1), False),
            ("magdollite-narrow-pillar", "Narrow pillar · left / right", (
                "InstList_Magdollite_Slave1_NarrowPillar_FacingLeft",
                "InstList_Magdollite_Slave1_NarrowPillar_FacingRight",
            ), (1, 1), False),
            ("magdollite-pillar-cap", "Hand · pillar cap", ("InstList_Magdollite_Slave2_PillarCap",), (1,), False),
        ),
    },
    {
        "key": "beetom", "name": "Beetom", "speciesId": 0xE87F,
        "header": "EnemyHeaders_Beetom", "asset": "Tiles_Beetom", "palette": "Palette_Beetom",
        "defaultInstructionLists": {0xE87F: "InstList_Beetom_Crawling_FacingLeft_1"},
        "aiBank": 0xA8,
        "mapPrefix": "Spritemap_Beetom_", "mapCount": 22, "listPrefix": "InstList_Beetom_",
        "animations": (
            ("beetom-crawl-left", "Crawling · left", ("InstList_Beetom_Crawling_FacingLeft_1",), (4,), True),
            ("beetom-crawl-right", "Crawling · right", ("InstList_Beetom_Crawling_FacingRight_1",), (4,), True),
            ("beetom-hop-left", "Hopping · left", ("InstList_Beetom_Hop_FacingLeft",), (4,), False),
            ("beetom-hop-right", "Hopping · right", ("InstList_Beetom_Hop_FacingRight",), (4,), False),
            ("beetom-latch-left", "Latching onto Samus · left", ("InstList_Beetom_DrainingSamus_FacingLeft_0",), (4,), False),
            ("beetom-drain-left", "Draining Samus · left", ("InstList_Beetom_DrainingSamus_FacingLeft_1",), (4,), True),
            ("beetom-latch-right", "Latching onto Samus · right", ("InstList_Beetom_DrainingSamus_FacingRight_0",), (4,), False),
            ("beetom-drain-right", "Draining Samus · right", ("InstList_Beetom_DrainingSamus_FacingRight_1",), (4,), True),
        ),
    },
    {
        "key": "kihunter", "name": "Kihunter (green)", "speciesId": 0xEABF,
        "header": "EnemyHeaders_KihunterGreen",
        "asset": "Tiles_Kihunter", "palette": "Palette_KihunterGreen",
        # The paired wing headers are runtime slot-2 helpers with a deliberately
        # shorter 0x200-byte transfer from this same source asset. They stay in
        # the all-header manifest, while this editor-facing family validates the
        # three full-sheet body/color owners and exposes the wing lists as actions.
        "headerAliases": (
            (0xEB3F, "EnemyHeaders_KihunterYellow", "Kihunter (red)"),
            (0xEBBF, "EnemyHeaders_KihunterRed", "Kihunter (gold)"),
        ),
        "headerPaletteOverrides": {
            0xEB3F: "Palette_KihunterYellow",
            0xEBBF: "Palette_KihunterRed",
        },
        "defaultInstructionLists": {
            0xEABF: "InstList_Kihunter_Idling_FacingLeft",
            0xEB3F: "InstList_Kihunter_Idling_FacingLeft",
            0xEBBF: "InstList_Kihunter_Idling_FacingLeft",
        },
        "animationContexts": {
            "kihunter-wings-left": ("InstList_Kihunter_Idling_FacingLeft", 3, True),
            "kihunter-wings-right": ("InstList_Kihunter_Idling_FacingRight", 3, True),
            # Falling wings acquire independent AI coordinates after detaching;
            # the first right-idle body pose is a stable editor reference only.
            "kihunter-wings-falling": ("InstList_Kihunter_Idling_FacingRight", 1, True),
        },
        "aiBank": 0xA8,
        "mapPrefix": "Spritemap_Kihunter", "mapCount": 41,
        "listPrefix": "InstList_Kihunter",
        "animations": (
            ("kihunter-idle-left", "Body · idle left", ("InstList_Kihunter_Idling_FacingLeft",), (3,), True),
            ("kihunter-idle-right", "Body · idle right", ("InstList_Kihunter_Idling_FacingRight",), (3,), True),
            ("kihunter-swipe-left", "Body · swipe left", ("InstList_Kihunter_Swiping_FacingLeft",), (6,), False),
            ("kihunter-swipe-right", "Body · swipe right", ("InstList_Kihunter_Swiping_FacingRight",), (6,), False),
            ("kihunter-hop-left", "Body · hop left", ("InstList_Kihunter_Hop_FacingLeft",), (6,), False),
            ("kihunter-hop-right", "Body · hop right", ("InstList_Kihunter_Hop_FacingRight",), (6,), False),
            ("kihunter-land-left", "Body · land left", ("InstList_Kihunter_LandedFromHop_FacingLeft",), (5,), False),
            ("kihunter-land-right", "Body · land right", ("InstList_Kihunter_LandedFromHop_FacingRight",), (5,), False),
            ("kihunter-acid-left", "Body · fire acid left", ("InstList_Kihunter_AcidSpitAttack_FacingLeft",), (6,), False),
            ("kihunter-acid-right", "Body · fire acid right", ("InstList_Kihunter_AcidSpitAttack_FacingRight",), (6,), False),
            ("kihunter-wings-left", "Wings · flapping left", ("InstList_KihunterWings_FacingLeft",), (3,), True),
            ("kihunter-wings-right", "Wings · flapping right", ("InstList_KihunterWings_FacingRight",), (3,), True),
            ("kihunter-wings-falling", "Wings · falling", ("InstList_KihunterWings_Falling",), (1,), False),
        ),
    },
    {
        "key": "corpse-sidehopper", "name": "Sidehopper Corpse", "speciesId": 0xED7F,
        "header": "EnemyHeaders_CorpseSidehopper",
        "asset": "Tiles_Corpse_Sidehopper_Zoomer_Ripper_Skree", "palette": "Palette_CorpseCommon",
        "headerAliases": (
            (0xEDBF, "EnemyHeaders_CorpseSidehopper2", "Sidehopper Corpse (large graphics variant)"),
        ),
        "headerAssetOverrides": {0xEDBF: "Tiles_SidehopperLarge"},
        "headerPaletteOverrides": {0xEDBF: "Palette_CorpseSidehopper2"},
        "defaultInstructionLists": {
            0xED7F: "InstList_CorpseSidehopper_Alive_Idle",
            0xEDBF: "InstList_CorpseSidehopper_Alive_Idle",
        },
        "aiBank": 0xA9,
        "mapPrefix": "Spritemap_CorpseSidehopper_", "mapCount": 5,
        "listPrefix": "InstList_CorpseSidehopper_",
        "animations": (
            ("corpse-sidehopper-hop", "Alive · hopping", ("InstList_CorpseSidehopper_Alive_Hopping",), (8,), False),
            ("corpse-sidehopper-idle", "Alive · idle", ("InstList_CorpseSidehopper_Alive_Idle",), (1,), False),
            ("corpse-sidehopper-drained", "Drained corpse", ("InstList_CorpseSidehopper_Alive_Corpse",), (1,), False),
            ("corpse-sidehopper-dead", "Dead", ("InstList_CorpseSidehopper_Alive_Dead",), (1,), False),
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
    palettes_by_label = {}
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
            asset_label = spec.get("headerAssetOverrides", {}).get(species_id, spec["asset"])
            palette_label = spec.get("headerPaletteOverrides", {}).get(species_id, spec["palette"])
            if fields["tileData"]["targetLabel"] != asset_label:
                fail(f"{display_name} tile ownership changed")
            if fields["palette"]["targetLabel"] != palette_label:
                fail(f"{display_name} palette ownership changed")
            asset = assets_by_name.get(asset_label)
            if asset is None or int(asset["snesAddress"]) != int(fields["tileData"]["value"]):
                fail(f"missing {asset_label} asset")
            palette_address = address(palette_label)
            if (palette_address & 0xFFFF) != int(fields["palette"]["value"]):
                fail(f"{display_name} palette address changed")
            validated_headers.append((
                species_id, header_label, display_name, header,
                asset_label, palette_label, asset, palette_address,
            ))

        fields = validated_headers[0][3]["fields"]
        animation_species_ids = list(spec.get(
            "animationSpeciesIds",
            [record[0] for record in validated_headers],
        ))
        if any(species_id not in {record[0] for record in validated_headers} for species_id in animation_species_ids):
            fail(f"{spec['name']} animation species are not declared headers")
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
            animation = {
                "key": key,
                "name": name,
                "speciesKey": spec["key"],
                "speciesId": spec["speciesId"],
                "speciesIds": animation_species_ids,
                "instructionLists": list_addresses,
                "expectedFramesPerList": list(expected_frames),
                "loop": loop,
                "renderFrameCount": sum(expected_frames),
            }
            context = spec.get("animationContexts", {}).get(key)
            if context is not None:
                context_label, context_frames, context_on_top = context
                context_record = lists_by_label.get(context_label)
                if context_record is None:
                    fail(f"missing context list {context_label}")
                actual_context_frames = sum(
                    item["kind"] == "frame" for item in context_record["records"]
                )
                if actual_context_frames < context_frames:
                    fail(f"{context_label} has too few context frames: {actual_context_frames}")
                animation.update(
                    contextInstructionList=address(context_label),
                    contextExpectedFrames=context_frames,
                    contextOnTop=context_on_top,
                )
            animations.append(animation)

        for index, (
            species_id, header_label, display_name, header,
            asset_label, palette_label, asset, palette_address,
        ) in enumerate(validated_headers):
            if palette_label not in palettes_by_label:
                palette_raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 32]
                palettes_by_label[palette_label] = {
                    "speciesKey": spec["key"],
                    "sourceLabel": palette_label,
                    "snesAddress": palette_address,
                    "size": 32,
                    "sha256": sha256(palette_raw),
                }
            default_list_label = spec.get("defaultInstructionLists", {}).get(
                species_id,
                spec["animations"][0][2][0],
            )
            species_records.append({
                "key": spec["key"] if index == 0 else f"{spec['key']}-{species_id:04x}",
                "familyKey": spec["key"],
                "displayName": display_name,
                "speciesId": species_id,
                "headerLabel": header_label,
                "headerSnesAddress": int(header["snesAddress"]),
                "tileDataLabel": asset_label,
                "tileDataSnesAddress": int(asset["snesAddress"]),
                "tileDataSize": int(asset["size"]),
                "tileDataSha256": asset["sha256"],
                "paletteLabel": palette_label,
                "paletteSnesAddress": palette_address,
                "defaultInstructionList": address(default_list_label),
                "spritemapCount": len(compact_maps),
                "instructionListCount": len(compact_lists),
                "animationCount": len(spec["animations"]) if species_id in animation_species_ids else 0,
            })

    palettes = list(palettes_by_label.values())
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
