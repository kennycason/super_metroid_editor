#!/usr/bin/env python3
"""Build Mother Brain's exact room/BG, OBJ, OAM, palette, and animation manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "mother-brain.json"

ASSET_NAMES = (
    "Tiles_MotherBrainBody",
    "Tiles_MotherBrainHead",
    "Tiles_MotherBrainLegs",
    "Tiles_MotherBrain_BombShells_DeathBeam_UnusedGFX",
)
HEADER_SPECS = (
    ("EnemyHeaders_MotherBrainHead", 0xEC3F, 0x1000, False, "Tiles_MotherBrainHead"),
    ("EnemyHeaders_MotherBrainBody", 0xEC7F, 0x0600, True, "Tiles_MotherBrainBody"),
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


def compact_oam(record: Dict[str, object]) -> Dict[str, object]:
    result = {
        "sourceLabel": record["sourceLabel"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
        "sourceDeclaredUnused": bool(record["sourceDeclaredUnused"]),
    }
    for field in ("entryCount", "childCount", "runCount", "wordCount", "rawSha256"):
        if field in record:
            result[field] = record[field]
    if "children" in record:
        result["children"] = [
            {
                "childType": child["childType"],
                "childSnesAddress": int(child["childSnesAddress"]),
            }
            for child in record["children"]
        ]
    return result


def compact_instruction(record: Dict[str, object]) -> Dict[str, object]:
    entries = []
    for entry in record["records"]:
        compact = {
            "kind": entry["kind"],
            "snesAddress": int(entry["snesAddress"]),
            "size": int(entry["size"]),
        }
        if entry["kind"] == "frame":
            compact.update(
                duration=int(entry["duration"]),
                spritemapSnesAddress=int(entry["spritemapSnesAddress"]),
                spritemapType=entry["spritemapType"],
            )
        elif entry["kind"] == "handler":
            compact.update(
                handlerSnesAddress=int(entry["handlerSnesAddress"]),
                operandByteCount=int(entry["operandByteCount"]),
                controlFlow=entry["controlFlow"],
            )
        entries.append(compact)
    return {
        "sourceLabel": record["sourceLabel"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
        "sourceDeclaredUnused": bool(record["sourceDeclaredUnused"]),
        "records": entries,
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
        "tilesets": report_dir / "tilesets.json",
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityMotherBrain")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Mother Brain prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    room_states = []
    for label, active in (
        ("RoomState_MotherBrain_0", True),
        ("RoomState_MotherBrain_1", True),
        ("RoomState_MotherBrain_2", False),
    ):
        state_address = address(label)
        tileset_id = rom[snes_to_pc(state_address) + 3]
        if tileset_id != 0x0E:
            fail(f"{label} no longer selects tileset $0E")
        room_states.append({
            "sourceLabel": label,
            "snesAddress": state_address,
            "tilesetId": tileset_id,
            "motherBrainPopulationActive": active,
        })

    tileset = next((record for record in reports["tilesets"]["tilesets"] if int(record["id"]) == 0x0E), None)
    if tileset is None or tileset["tableLabel"] != "Tileset_Table_E_MotherBrain":
        fail("tileset $0E no longer resolves to Mother Brain's named source table")
    expected_tileset_resources = {
        "tileTable": ("TileTables_D_E_Tourian", 0x1800),
        "graphics": ("Tiles_D_E_Tourian", 0x4800),
        "palette": ("Palettes_E_MotherBrain", 0x0100),
    }
    for kind, (label, size) in expected_tileset_resources.items():
        resource = tileset["resources"][kind]
        if resource["sourceLabel"] != label or int(resource["decompressedSize"]) != size:
            fail(f"tileset $0E {kind} ownership or decompressed size changed")

    assets_by_name = {record["name"]: record for record in reports["assets"]["assets"]}
    assets = []
    expected_asset_sizes = {
        "Tiles_MotherBrainBody": 0x0600,
        "Tiles_MotherBrainHead": 0x1000,
        "Tiles_MotherBrainLegs": 0x1000,
        "Tiles_MotherBrain_BombShells_DeathBeam_UnusedGFX": 0x0800,
    }
    for name in ASSET_NAMES:
        record = assets_by_name.get(name)
        if record is None or int(record["size"]) != expected_asset_sizes[name]:
            fail(f"Mother Brain asset missing or changed: {name}")
        assets.append({key: record[key] for key in ("name", "snesAddress", "size", "sha256", "asset")})

    header_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    headers = []
    for label, species_id, transfer_size, alternate_layout, tile_label in HEADER_SPECS:
        record = header_by_label.get(label)
        if record is None or int(record["speciesId"]) != species_id:
            fail(f"missing or changed Mother Brain header {label}")
        fields = record["fields"]
        graphics = record["graphics"]
        if int(fields["bank"]["value"]) != 0xA9:
            fail(f"{label} no longer uses AI bank $A9")
        if fields["palette"]["targetLabel"] != "Palette_MotherBrain":
            fail(f"{label} no longer owns Palette_MotherBrain")
        if fields["tileData"]["targetLabel"] != tile_label:
            fail(f"{label} graphics owner changed")
        if int(graphics["transferSize"]) != transfer_size or bool(graphics["alternateVramLayout"]) != alternate_layout:
            fail(f"{label} transfer contract changed")
        headers.append({
            "sourceLabel": label,
            "speciesId": species_id,
            "snesAddress": int(record["snesAddress"]),
            "rawHeaderSha256": record["rawHeaderSha256"],
            "tileDataField": int(fields["tileDataSize"]["value"]),
            "transferSize": int(graphics["transferSize"]),
            "alternateVramLayout": bool(graphics["alternateVramLayout"]),
            "tileDataSourceLabel": tile_label,
            "tileDataSnesAddress": int(fields["tileData"]["value"]),
            "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
        })

    oam = reports["oam"]
    head_oam = [
        compact_oam(record) for record in oam["standardSpritemaps"]
        if str(record["sourceLabel"]).startswith("Spritemaps_MotherBrain_")
    ]
    unused_head_oam = [
        compact_oam(record) for record in oam["standardSpritemaps"]
        if str(record["sourceLabel"]).startswith("UNUSED_Spritemaps_MotherBrain_")
    ]
    body_extended = [
        compact_oam(record) for record in oam["extendedSpritemaps"]
        if (
            str(record["sourceLabel"]).startswith("ExtendedSpritemap_MotherBrainBody_")
            or str(record["sourceLabel"]).startswith("ExtendedSpritemap_MotherBrainBrain_DeathBeamMode_")
        )
    ]
    unused_extended = [
        compact_oam(record) for record in oam["extendedSpritemaps"]
        if str(record["sourceLabel"]).startswith("UNUSED_ExtendedSpritemap_MotherBrainBrain_")
    ]
    body_tilemaps = [
        compact_oam(record) for record in oam["extendedTilemaps"]
        if str(record["sourceLabel"]).startswith("ExtendedTilemaps_MotherBrain_")
    ]
    unused_tilemaps = [
        compact_oam(record) for record in oam["extendedTilemaps"]
        if str(record["sourceLabel"]).startswith("UNUSED_ExtendedTilemaps_MotherBrain_")
    ]
    if (len(head_oam), len(unused_head_oam), len(body_extended), len(unused_extended), len(body_tilemaps), len(unused_tilemaps)) != (26, 5, 16, 10, 6, 1):
        fail(
            "Mother Brain OAM inventory changed: "
            f"{len(head_oam)} head, {len(body_extended)} body, {len(body_tilemaps)} BG2; "
            f"{len(unused_head_oam)}/{len(unused_extended)}/{len(unused_tilemaps)} unused"
        )

    instructions = [
        compact_instruction(record) for record in reports["instructions"]["lists"]
        if (
            str(record["sourceLabel"]).startswith("InstList_MotherBrainBody_")
            or str(record["sourceLabel"]).startswith("InstList_MotherBrainHead_")
        ) and not record["sourceDeclaredUnused"]
    ]
    unused_instructions = [
        compact_instruction(record) for record in reports["instructions"]["lists"]
        if str(record["sourceLabel"]).startswith("UNUSED_InstList_MotherBrainHead_")
    ]
    if len(instructions) != 49 or len(unused_instructions) != 3:
        fail(f"Mother Brain instruction inventory changed: {len(instructions)} active / {len(unused_instructions)} unused")

    base_palettes = []
    for label in ("Palette_MotherBrain", "Palette_MotherBrain_BackLeg"):
        palette_address = address(label)
        raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 0x20]
        base_palettes.append({
            "sourceLabel": label,
            "snesAddress": palette_address,
            "size": len(raw),
            "sha256": sha256(raw),
        })
    health_palettes = []
    for index in range(4):
        for role in ("BrainBody", "BackLeg"):
            label = f"MotherBrainHealthBasedPalettes_{role}_{index}"
            palette_address = address(label)
            raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 0x1E]
            health_palettes.append({
                "sourceLabel": label,
                "role": role,
                "stage": index,
                "snesAddress": palette_address,
                "size": len(raw),
                "sha256": sha256(raw),
            })
    rainbow_palettes = []
    for index in range(10):
        label = f"MotherBrainBodyRainbowBeamPalette_{index}"
        palette_address = address(label)
        raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 0x3C]
        rainbow_palettes.append({
            "sourceLabel": label,
            "stage": index,
            "snesAddress": palette_address,
            "mainSize": 0x1E,
            "backLegOffset": 0x1E,
            "size": len(raw),
            "sha256": sha256(raw),
        })

    bank_a1 = (disassembly / "src" / "bank_A1.asm").read_text(encoding="utf-8")
    bank_a9 = (disassembly / "src" / "bank_A9.asm").read_text(encoding="utf-8")
    required_tokens = (
        (bank_a1, "%enemyID(EnemyHeaders_MotherBrainBody)", "body population slot"),
        (bank_a1, "%enemyID(EnemyHeaders_MotherBrainHead)", "head population slot"),
        (bank_a1, "%XPosition($0081)", "shared room X origin"),
        (bank_a1, "%YPosition($006F)", "shared room Y origin"),
        (bank_a9, "dl Tiles_MotherBrainLegs", "leg DMA source"),
        (bank_a9, "dl Tiles_MotherBrain_BombShells_DeathBeam_UnusedGFX", "attack DMA source"),
        (bank_a9, "LDX.W #$0082", "body and head palette destination"),
        (bank_a9, "LDX.W #$0162", "back-leg palette destination"),
        (bank_a9, "LDA.W #$FFEB", "head Y offset from neck segment 4"),
    )
    for source, token, description in required_tokens:
        if token not in source:
            fail(f"source no longer contains {description}: {token}")

    ownership = {
        "phase1": {
            "enemyPixels": "Tiles_MotherBrainHead",
            "environmentPixels": "room level/BG art",
            "assembledEnemyIncludesEnvironment": False,
        },
        "phase2": {
            "roomTorsoTiles": {
                "tilesetId": 0x0E,
                "physicalTileStart": 0x160,
                "tileCount": 0xA0,
                "resource": tileset["resources"]["graphics"],
            },
            "headTiles": assets[1],
            "legTiles": assets[2],
            "bodySupplement": assets[0],
            "attackTiles": assets[3],
            "headProjectEditKey": 'spriteTileBlocks["enemy:EC3F"]',
            "combinedBodySheetEditable": False,
        },
        "placement": {
            "sourceBank": 0xA9,
            "bodyExtendedSpritemaps": len(body_extended),
            "headSpritemaps": len(head_oam),
            "neckSegmentCount": 5,
            "bodyRoomOrigin": [0x81, 0x6F],
            "headYFromNeckSegment4": -0x15,
        },
    }
    all_palettes = base_palettes + health_palettes + rainbow_palettes
    totals = {
        "roomStateCount": len(room_states),
        "activeRoomStateCount": sum(bool(record["motherBrainPopulationActive"]) for record in room_states),
        "headerCount": len(headers),
        "assetCount": len(assets),
        "headSpritemapCount": len(head_oam),
        "headOamEntryCount": sum(int(record["entryCount"]) for record in head_oam),
        "bodyExtendedSpritemapCount": len(body_extended),
        "bodyExtendedChildCount": sum(int(record["childCount"]) for record in body_extended),
        "bodyTilemapCount": len(body_tilemaps),
        "bodyTilemapRunCount": sum(int(record["runCount"]) for record in body_tilemaps),
        "bodyTilemapWordCount": sum(int(record["wordCount"]) for record in body_tilemaps),
        "instructionListCount": len(instructions),
        "frameOccurrenceCount": sum(entry["kind"] == "frame" for record in instructions for entry in record["records"]),
        "handlerOccurrenceCount": sum(entry["kind"] == "handler" for record in instructions for entry in record["records"]),
        "unusedHeadSpritemapCount": len(unused_head_oam),
        "unusedExtendedSpritemapCount": len(unused_extended),
        "unusedTilemapCount": len(unused_tilemaps),
        "unusedInstructionListCount": len(unused_instructions),
        "basePaletteCount": len(base_palettes),
        "healthPaletteRowCount": len(health_palettes),
        "rainbowPaletteStageCount": len(rainbow_palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "assets": aggregate_hash(assets),
        "headSpritemaps": aggregate_hash(head_oam),
        "bodyExtendedSpritemaps": aggregate_hash(body_extended),
        "bodyTilemaps": aggregate_hash(body_tilemaps),
        "instructions": aggregate_hash(instructions),
        "unusedStructures": aggregate_hash(unused_head_oam + unused_extended + unused_tilemaps + unused_instructions),
        "palettes": aggregate_hash(all_palettes),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "roomStates": room_states,
        "tileset": tileset,
        "ownership": ownership,
        "headers": headers,
        "assets": assets,
        "headSpritemaps": head_oam,
        "bodyExtendedSpritemaps": body_extended,
        "bodyTilemaps": body_tilemaps,
        "instructionLists": instructions,
        "unusedStructures": {
            "headSpritemaps": unused_head_oam,
            "extendedSpritemaps": unused_extended,
            "tilemaps": unused_tilemaps,
            "instructionLists": unused_instructions,
        },
        "palettes": {
            "base": base_palettes,
            "health": health_palettes,
            "rainbow": rainbow_palettes,
        },
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Mother Brain manifest: "
        f"{totals['headSpritemapCount']} head OAM, "
        f"{totals['bodyExtendedSpritemapCount']} body maps, "
        f"{totals['instructionListCount']} instruction lists / "
        f"{totals['frameOccurrenceCount']} frames"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
