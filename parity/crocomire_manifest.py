#!/usr/bin/env python3
"""Build Crocomire's exact source/ROM graphics, OAM, DMA, and animation manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "crocomire.json"

HEADER_SPECS = (
    ("EnemyHeaders_Crocomire", 0xDDBF, 0xA600, "body"),
    ("EnemyHeaders_CrocomireTongue", 0xDDFF, 0x2000, "tongue"),
)
ASSET_SPECS = (
    ("Tiles_Crocomire", 0x2600, "living-obj"),
    ("Tiles_Crocomire_Melting1", 0x0C00, "melting-pass-1"),
    ("Tiles_Crocomire_Melting2", 0x0C00, "melting-pass-2"),
    *((f"Tiles_CrocomireSkeleton_{index}", 0x0200, f"skeleton-dma-{index}") for index in range(6)),
)
PALETTE_LABELS = (
    "Palette_Crocomire",
    "Palette_Crocomire_BG12",
    "Palette_Crocomire_Sprite2",
    "Palette_Crocomire_Sprite5",
    "Palette_Crocomire_Sprite1",
    "Palette_Crocomire_Sprite3",
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
        "tilesets": report_dir / "tilesets.json",
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityCrocomire")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Crocomire prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    tileset = next((record for record in reports["tilesets"]["tilesets"] if int(record["id"]) == 0x1B), None)
    if tileset is None or tileset["tableLabel"] != "Tileset_Table_1B_Crocomire":
        fail("tileset $1B no longer resolves to Crocomire's named source table")
    expected_resources = {
        "tileTable": ("TileTables_1B_Crocomire", 0x1800),
        "graphics": ("Tiles_1B_Crocomire", 0x4800),
        "palette": ("Palettes_1B_Crocomire", 0x0100),
    }
    for kind, (label, decompressed_size) in expected_resources.items():
        resource = tileset["resources"][kind]
        if resource["sourceLabel"] != label or int(resource["decompressedSize"]) != decompressed_size:
            fail(f"tileset $1B {kind} ownership or decompressed size changed")

    header_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    headers = []
    for label, species_id, raw_transfer_size, role in HEADER_SPECS:
        header = header_by_label.get(label)
        if header is None or int(header["speciesId"]) != species_id:
            fail(f"missing or changed Crocomire header {label}")
        fields = header["fields"]
        if int(fields["tileData"]["value"]) != address("Tiles_Crocomire"):
            fail(f"{label} no longer shares Tiles_Crocomire")
        if int(fields["tileDataSize"]["value"]) != raw_transfer_size:
            fail(f"{label} tile transfer size changed")
        if fields["palette"]["targetLabel"] != "Palette_Crocomire":
            fail(f"{label} palette ownership changed")
        if int(fields["bank"]["value"]) != 0xA4:
            fail(f"{label} no longer uses AI bank $A4")
        headers.append(
            {
                "sourceLabel": label,
                "role": role,
                "speciesId": species_id,
                "headerSnesAddress": int(header["snesAddress"]),
                "rawTileDataSizeField": raw_transfer_size,
                "effectiveTransferSize": raw_transfer_size & 0x7FFF,
                "tileDataSnesAddress": int(fields["tileData"]["value"]),
                "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
                "rawHeaderSha256": header["rawHeaderSha256"],
            }
        )

    asset_by_name = {record["name"]: record for record in reports["assets"]["assets"]}
    assets = []
    for label, size, role in ASSET_SPECS:
        source = asset_by_name.get(label)
        if source is None or int(source["size"]) != size or int(source["snesAddress"]) != address(label):
            fail(f"Crocomire asset ownership changed for {label}")
        assets.append(
            {
                "sourceLabel": label,
                "role": role,
                "snesAddress": int(source["snesAddress"]),
                "size": size,
                "sha256": source["sha256"],
                "asset": source["asset"],
            }
        )

    oam = reports["oam"]
    standard = [
        record for record in oam["standardSpritemaps"]
        if "Crocomire" in str(record["sourceLabel"]) and not record["sourceDeclaredUnused"]
    ]
    extended = [
        record for record in oam["extendedSpritemaps"]
        if "Crocomire" in str(record["sourceLabel"]) and not record["sourceDeclaredUnused"]
    ]
    tilemaps = [
        record for record in oam["extendedTilemaps"]
        if "Crocomire" in str(record["sourceLabel"]) and not record["sourceDeclaredUnused"]
    ]
    if (len(standard), len(extended), len(tilemaps)) != (74, 94, 11):
        fail(f"Crocomire OAM inventory changed: {len(standard)} standard, {len(extended)} extended, {len(tilemaps)} BG2")
    standard_addresses = {int(record["snesAddress"]) for record in standard}
    tilemap_addresses = {int(record["snesAddress"]) for record in tilemaps}
    for record in extended:
        for child in record["children"]:
            child_address = int(child["childSnesAddress"])
            if child["childType"] == "standard-oam" and child_address not in standard_addresses:
                fail(f"{record['sourceLabel']} links outside Crocomire's standard OAM inventory")
            if child["childType"] == "extended-tilemap" and child_address not in tilemap_addresses:
                fail(f"{record['sourceLabel']} links outside Crocomire's BG2 tilemap inventory")

    all_lists = [
        record for record in reports["instructions"]["lists"]
        if "Crocomire" in str(record["sourceLabel"])
    ]
    active_lists = [record for record in all_lists if not record["sourceDeclaredUnused"]]
    unused_lists = [record for record in all_lists if record["sourceDeclaredUnused"]]
    if (len(active_lists), len(unused_lists)) != (36, 3):
        fail(f"Crocomire instruction inventory changed: {len(active_lists)} active, {len(unused_lists)} unused")
    extended_addresses = {int(record["snesAddress"]) for record in extended}

    def compact_list(record: Dict[str, object]) -> Dict[str, object]:
        records = [compact_record(item) for item in record["records"]]
        for item in records:
            if item["kind"] == "frame" and int(item["spritemapSnesAddress"]) not in extended_addresses:
                fail(f"{record['sourceLabel']} references a non-Crocomire frame")
        return {
            "sourceLabel": record["sourceLabel"],
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "records": records,
        }

    instruction_lists = [compact_list(record) for record in active_lists]
    unused_instruction_lists = [compact_list(record) for record in unused_lists]
    guided_animations = [
        record for record in instruction_lists
        if sum(item["kind"] == "frame" for item in record["records"]) > 1
    ]
    frame_addresses = {
        int(item["spritemapSnesAddress"])
        for record in instruction_lists for item in record["records"] if item["kind"] == "frame"
    }
    if len(guided_animations) != 20 or len(frame_addresses) != 92:
        fail(f"Crocomire guided coverage changed: {len(guided_animations)} lists / {len(frame_addresses)} unique frames")

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

    ownership = {
        "roomBgPixels": {
            "tilesetId": 0x1B,
            "resource": tileset["resources"]["graphics"],
            "projectEditKey": 'varGfx["27"]',
        },
        "livingObjPixels": {
            "sourceLabel": "Tiles_Crocomire",
            "snesAddress": address("Tiles_Crocomire"),
            "size": 0x2600,
            "physicalTileOrigin": 0xD0,
            "projectEditKey": 'spriteTileBlocks["enemy:DDBF"]',
        },
        "tongueAlias": {
            "sourceLabel": "Tiles_Crocomire",
            "size": 0x2000,
            "editableThrough": 'spriteTileBlocks["enemy:DDBF"]',
        },
        "meltingOverlay": {
            "sourceLabels": ["Tiles_Crocomire_Melting1", "Tiles_Crocomire_Melting2"],
            "sizeEach": 0x0C00,
            "physicalTileOrigin": 0x130,
            "editable": False,
        },
        "skeletonDma": {
            "sourceLabels": [f"Tiles_CrocomireSkeleton_{index}" for index in range(6)],
            "sizeEach": 0x0200,
            "physicalTileDestinations": [0x160, 0x170, 0x180, 0x190, 0x1E0, 0x1F0],
            "editable": False,
        },
        "placement": {
            "sourceBank": 0xA4,
            "editable": False,
            "bg2Origin": [-0x33, -0x43],
            "oamTileNumberMode": "low-9",
        },
    }
    totals = {
        "headerCount": len(headers),
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "standardSpritemapCount": len(standard),
        "standardOamEntryCount": sum(int(record["entryCount"]) for record in standard),
        "extendedSpritemapCount": len(extended),
        "extendedChildCount": sum(int(record["childCount"]) for record in extended),
        "tilemapCount": len(tilemaps),
        "tilemapRunCount": sum(int(record["runCount"]) for record in tilemaps),
        "tilemapWordCount": sum(int(record["wordCount"]) for record in tilemaps),
        "instructionListCount": len(instruction_lists),
        "frameOccurrenceCount": sum(item["kind"] == "frame" for record in instruction_lists for item in record["records"]),
        "uniqueFrameCount": len(frame_addresses),
        "handlerOccurrenceCount": sum(item["kind"] == "handler" for record in instruction_lists for item in record["records"]),
        "guidedAnimationCount": len(guided_animations),
        "guidedAnimationFrameCount": sum(
            item["kind"] == "frame" for record in guided_animations for item in record["records"]
        ),
        "unusedInstructionListCount": len(unused_instruction_lists),
        "paletteCount": len(palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "assets": aggregate_hash(assets),
        "standardSpritemaps": aggregate_hash(standard),
        "extendedSpritemaps": aggregate_hash(extended),
        "tilemaps": aggregate_hash(tilemaps),
        "instructionLists": aggregate_hash(instruction_lists),
        "guidedAnimations": aggregate_hash(guided_animations),
        "unusedInstructionLists": aggregate_hash(unused_instruction_lists),
        "palettes": aggregate_hash(palettes),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "tileset": tileset,
        "ownership": ownership,
        "headers": headers,
        "assets": assets,
        "standardSpritemaps": standard,
        "extendedSpritemaps": extended,
        "tilemaps": tilemaps,
        "instructionLists": instruction_lists,
        "guidedAnimations": guided_animations,
        "unusedInstructionLists": unused_instruction_lists,
        "palettes": palettes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Crocomire manifest: "
        f"{totals['extendedSpritemapCount']} extended / {totals['standardSpritemapCount']} OAM / "
        f"{totals['tilemapCount']} BG2 maps, {totals['instructionListCount']} lists / "
        f"{totals['frameOccurrenceCount']} frames"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
