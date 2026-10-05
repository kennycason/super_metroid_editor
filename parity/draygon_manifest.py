#!/usr/bin/env python3
"""Build Draygon's exact source/ROM graphics, OAM, and animation manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "draygon.json"

HEADER_SPECS = (
    ("EnemyHeaders_DraygonBody", 0xDE3F, 0x2000, "Palette_Draygon_Sprite7", "body"),
    ("EnemyHeaders_DraygonEye", 0xDE7F, 0x1800, "NOPNOP_A58069", "eye"),
    ("EnemyHeaders_DraygonTail", 0xDEBF, 0x1800, "Palette_Draygon_Sprite7", "tail"),
    ("EnemyHeaders_DraygonArms", 0xDEFF, 0x1800, "Palette_Draygon_Sprite7", "arms"),
)
PALETTE_LABELS = (
    "Palette_Draygon_Sprite7",
    "Palette_Draygon_Sprite1",
    "Palette_Draygon_Sprite2",
    "Palette_Draygon_Sprite3",
    "Palette_Draygon_BG12_5",
    "Palette_Draygon_WhiteFlash",
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def read_word(data: bytes, offset: int) -> int:
    return data[offset] | data[offset + 1] << 8


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
        "tilesets": report_dir / "tilesets.json",
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityDraygon")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Draygon prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    room_states = []
    for label, library_background in (
        ("RoomState_Draygon_0", "LibBG_Draygon_State0"),
        ("RoomState_Draygon_1", "LibBG_Phantoon_Draygon_State1"),
    ):
        state_address = address(label)
        if rom[snes_to_pc(state_address) + 3] != 0x1C:
            fail(f"{label} no longer selects tileset $1C")
        room_states.append(
            {
                "sourceLabel": label,
                "snesAddress": state_address,
                "tilesetId": 0x1C,
                "libraryBackgroundLabel": library_background,
            }
        )

    tileset = next((record for record in reports["tilesets"]["tilesets"] if int(record["id"]) == 0x1C), None)
    if tileset is None or tileset["tableLabel"] != "Tileset_Table_1C_Draygon":
        fail("tileset $1C no longer resolves to Draygon's named source table")
    expected_resources = {
        "tileTable": ("TileTables_1C_Draygon", 0x1800),
        "graphics": ("Tiles_1C_Draygon", 0x4800),
        "palette": ("Palettes_1C_Draygon", 0x0100),
    }
    for kind, (label, decompressed_size) in expected_resources.items():
        resource = tileset["resources"][kind]
        if resource["sourceLabel"] != label or int(resource["decompressedSize"]) != decompressed_size:
            fail(f"tileset $1C {kind} ownership or decompressed size changed")

    header_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    headers = []
    for label, species_id, transfer_size, palette_label, role in HEADER_SPECS:
        header = header_by_label.get(label)
        if header is None or int(header["speciesId"]) != species_id:
            fail(f"missing or changed Draygon header {label}")
        fields = header["fields"]
        if int(fields["tileData"]["value"]) != address("Tiles_Draygon"):
            fail(f"{label} no longer shares Tiles_Draygon")
        if int(fields["tileDataSize"]["value"]) != transfer_size:
            fail(f"{label} tile transfer size changed")
        if fields["palette"]["targetLabel"] != palette_label:
            fail(f"{label} palette ownership changed")
        if int(fields["bank"]["value"]) != 0xA5:
            fail(f"{label} no longer uses AI bank $A5")
        headers.append(
            {
                "sourceLabel": label,
                "role": role,
                "speciesId": species_id,
                "headerSnesAddress": int(header["snesAddress"]),
                "tileDataSize": transfer_size,
                "tileDataSnesAddress": int(fields["tileData"]["value"]),
                "paletteSourceLabel": palette_label,
                "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
                "rawHeaderSha256": header["rawHeaderSha256"],
            }
        )

    obj_asset = next(
        (record for record in reports["assets"]["assets"] if record["name"] == "Tiles_Draygon"),
        None,
    )
    if obj_asset is None or int(obj_asset["size"]) != 0x2000:
        fail("Tiles_Draygon is no longer the exact 0x2000-byte OBJ payload")
    obj_graphics = {key: obj_asset[key] for key in ("name", "snesAddress", "size", "sha256", "asset")}

    oam = reports["oam"]
    standard = [
        record for record in oam["standardSpritemaps"]
        if str(record["sourceLabel"]).startswith("Spritemap_Draygon_") and not record["sourceDeclaredUnused"]
    ]
    extended = [
        record for record in oam["extendedSpritemaps"]
        if str(record["sourceLabel"]).startswith("ExtendedSpritemap_Draygon_") and not record["sourceDeclaredUnused"]
    ]
    tilemaps = [
        record for record in oam["extendedTilemaps"]
        if str(record["sourceLabel"]).startswith("ExtendedTilemap_Draygon_") and not record["sourceDeclaredUnused"]
    ]
    if (len(standard), len(extended), len(tilemaps)) != (94, 103, 48):
        fail(f"Draygon OAM inventory changed: {len(standard)} standard, {len(extended)} extended, {len(tilemaps)} BG2")
    standard_addresses = {int(record["snesAddress"]) for record in standard}
    tilemap_addresses = {int(record["snesAddress"]) for record in tilemaps}
    for record in extended:
        for child in record["children"]:
            child_address = int(child["childSnesAddress"])
            if child["childType"] == "standard-oam" and child_address not in standard_addresses:
                fail(f"{record['sourceLabel']} links outside Draygon's standard OAM inventory")
            if child["childType"] == "extended-tilemap" and child_address not in tilemap_addresses:
                fail(f"{record['sourceLabel']} links outside Draygon's BG2 tilemap inventory")

    all_draygon_lists = [
        record for record in reports["instructions"]["lists"]
        if "Draygon" in str(record["sourceLabel"])
    ]
    active_lists = [record for record in all_draygon_lists if not record["sourceDeclaredUnused"]]
    unused_lists = [record for record in all_draygon_lists if record["sourceDeclaredUnused"]]
    if (len(active_lists), len(unused_lists)) != (57, 5):
        fail(f"Draygon instruction inventory changed: {len(active_lists)} active, {len(unused_lists)} unused")
    extended_addresses = {int(record["snesAddress"]) for record in extended}

    def compact_list(record: Dict[str, object]) -> Dict[str, object]:
        records = [compact_record(item) for item in record["records"]]
        for item in records:
            if item["kind"] == "frame" and int(item["spritemapSnesAddress"]) not in extended_addresses:
                fail(f"{record['sourceLabel']} references a non-Draygon frame")
        return {
            "sourceLabel": record["sourceLabel"],
            "snesAddress": int(record["snesAddress"]),
            "endSnesAddressExclusive": int(record["snesAddress"]) + int(record["size"]),
            "size": int(record["size"]),
            "records": records,
        }

    instruction_lists = [compact_list(record) for record in active_lists]
    unused_instruction_lists = [compact_list(record) for record in unused_lists]
    production_animations = [
        record for record in instruction_lists
        if any(item["kind"] == "frame" for item in record["records"])
    ]
    production_frame_count = sum(
        item["kind"] == "frame" for record in production_animations for item in record["records"]
    )
    if len(production_animations) != 39 or production_frame_count != 250:
        fail(f"Draygon production animation coverage changed: {len(production_animations)} lists / {production_frame_count} frames")

    palettes = []
    for label in PALETTE_LABELS:
        palette_address = address(label)
        raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 32]
        palettes.append(
            {
                "sourceLabel": label,
                "snesAddress": palette_address,
                "size": 32,
                "sha256": sha256(raw),
            }
        )
    health_address = address("DraygonHealthBasedPaletteTable")
    threshold_address = address("DraygonHealthBasedPaletteThresholds")
    health_raw = rom[snes_to_pc(health_address):snes_to_pc(health_address) + 0x40]
    threshold_raw = rom[snes_to_pc(threshold_address):snes_to_pc(threshold_address) + 0x12]
    health_palette = {
        "sourceLabel": "DraygonHealthBasedPaletteTable",
        "snesAddress": health_address,
        "size": len(health_raw),
        "stageCount": 8,
        "colorsPerStage": 4,
        "destinationPaletteIndexes": [9, 10, 11, 12],
        "sha256": sha256(health_raw),
    }
    health_thresholds = {
        "sourceLabel": "DraygonHealthBasedPaletteThresholds",
        "snesAddress": threshold_address,
        "size": len(threshold_raw),
        "values": [read_word(threshold_raw, index * 2) for index in range(9)],
        "sha256": sha256(threshold_raw),
    }
    if health_thresholds["values"] != [5250, 4500, 3750, 3000, 2250, 1500, 750, 0, 0xFFFF]:
        fail("Draygon health-palette thresholds changed")

    hitboxes = []
    for index in range(0x2F):
        label = f"Hitbox_Draygon_{index:X}"
        hitbox_address = address(label)
        pc = snes_to_pc(hitbox_address)
        count = read_word(rom, pc)
        raw = rom[pc:pc + 2 + count * 12]
        hitboxes.append(
            {
                "sourceLabel": label,
                "snesAddress": hitbox_address,
                "boxCount": count,
                "size": len(raw),
                "sha256": sha256(raw),
            }
        )

    bank_a1 = (disassembly / "src" / "bank_A1.asm").read_text(encoding="utf-8")
    bank_b4 = (disassembly / "src" / "bank_B4.asm").read_text(encoding="utf-8")
    bank_8f = (disassembly / "src" / "bank_8F.asm").read_text(encoding="utf-8")
    required_tokens = (
        (bank_8f, "dw $0002 : dl $7E2000 : dw $4800,$1000", "live BG2 room-map upload"),
        (bank_a1, "%enemyID(EnemyHeaders_DraygonBody)", "body population slot"),
        (bank_a1, "%enemyID(EnemyHeaders_DraygonEye)", "eye population slot"),
        (bank_a1, "%enemyID(EnemyHeaders_DraygonTail)", "tail population slot"),
        (bank_a1, "%enemyID(EnemyHeaders_DraygonArms)", "arms population slot"),
        (bank_b4, "dw EnemyHeaders_DraygonBody,$0007", "single shared graphics transfer"),
    )
    for source, token, description in required_tokens:
        if token not in source:
            fail(f"source no longer contains {description}: {token}")

    ownership = {
        "bgPixels": {
            "tilesetId": 0x1C,
            "resource": tileset["resources"]["graphics"],
            "projectEditKey": 'varGfx["28"]',
        },
        "objPixels": {
            "resource": obj_graphics,
            "physicalTileOrigin": 0x100,
            "projectEditKey": 'spriteTileBlocks["enemy:DE3F"]',
        },
        "placement": {
            "sourceBank": 0xA5,
            "editable": False,
            "standardSpritemaps": len(standard),
            "extendedSpritemaps": len(extended),
            "extendedTilemaps": len(tilemaps),
        },
    }
    totals = {
        "roomStateCount": len(room_states),
        "headerCount": len(headers),
        "standardSpritemapCount": len(standard),
        "standardOamEntryCount": sum(int(record["entryCount"]) for record in standard),
        "extendedSpritemapCount": len(extended),
        "extendedChildCount": sum(int(record["childCount"]) for record in extended),
        "tilemapCount": len(tilemaps),
        "tilemapRunCount": sum(int(record["runCount"]) for record in tilemaps),
        "tilemapWordCount": sum(int(record["wordCount"]) for record in tilemaps),
        "instructionListCount": len(instruction_lists),
        "frameOccurrenceCount": sum(item["kind"] == "frame" for record in instruction_lists for item in record["records"]),
        "handlerOccurrenceCount": sum(item["kind"] == "handler" for record in instruction_lists for item in record["records"]),
        "productionAnimationCount": len(production_animations),
        "productionAnimationFrameCount": production_frame_count,
        "unusedInstructionListCount": len(unused_instruction_lists),
        "sourcePaletteCount": len(palettes),
        "healthPaletteStageCount": 8,
        "hitboxCount": len(hitboxes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "standardSpritemaps": aggregate_hash(standard),
        "extendedSpritemaps": aggregate_hash(extended),
        "tilemaps": aggregate_hash(tilemaps),
        "instructionLists": aggregate_hash(instruction_lists),
        "productionAnimations": aggregate_hash(production_animations),
        "unusedInstructionLists": aggregate_hash(unused_instruction_lists),
        "palettes": aggregate_hash(palettes + [health_palette, health_thresholds]),
        "hitboxes": aggregate_hash(hitboxes),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "roomStates": room_states,
        "ownership": ownership,
        "headers": headers,
        "standardSpritemaps": standard,
        "extendedSpritemaps": extended,
        "tilemaps": tilemaps,
        "instructionLists": instruction_lists,
        "productionAnimations": production_animations,
        "unusedInstructionLists": unused_instruction_lists,
        "palettes": palettes,
        "healthPalette": health_palette,
        "healthThresholds": health_thresholds,
        "hitboxes": hitboxes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Draygon manifest: "
        f"{totals['standardSpritemapCount']} OAM, {totals['extendedSpritemapCount']} extended, "
        f"{totals['tilemapCount']} BG2 maps, {totals['productionAnimationCount']} animations / "
        f"{totals['productionAnimationFrameCount']} frames"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
