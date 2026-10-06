#!/usr/bin/env python3
"""Build Phantoon's exact source/ROM BG2 composition and ownership manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Dict, List, Sequence


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT_DIR = PARITY_DIR / "reports"
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "phantoon.json"

HEADER_SPECS = (
    ("EnemyHeaders_PhantoonBody", 0xE4BF, 0x0C00, "body"),
    ("EnemyHeaders_PhantoonEye", 0xE4FF, 0x0400, "eye"),
    ("EnemyHeaders_PhantoonTentacles", 0xE53F, 0x0400, "tentacles"),
    ("EnemyHeaders_PhantoonMouth", 0xE57F, 0x0400, "mouth"),
)

TILEMAP_LABELS = (
    "ExtendedTilemap_Phantoon_Body",
    "ExtendedTilemap_Phantoon_Eye_Open",
    "ExtendedTilemap_Phantoon_Eye_OpeningClosing",
    "ExtendedTilemap_Phantoon_Eye_Closed",
    "ExtendedTilemap_Phantoon_Eyeball_Centered",
    "ExtendedTilemap_Phantoon_Eyeball_LookingUp",
    "ExtendedTilemap_Phantoon_Eyeball_LookingDown",
    "ExtendedTilemap_Phantoon_Eyeball_LookingLeft",
    "ExtendedTilemap_Phantoon_Eyeball_LookingRight",
    "ExtendedTilemap_Phantoon_Eyeball_LookingDownLeft",
    "ExtendedTilemap_Phantoon_Eyeball_LookingDownRight",
    "ExtendedTilemap_Phantoon_Eyeball_LookingUpLeft",
    "ExtendedTilemap_Phantoon_Eyeball_LookingUpRight",
    "ExtendedTilemap_Phantoon_Tentacle_Left_0",
    "ExtendedTilemap_Phantoon_Tentacle_Left_1",
    "ExtendedTilemap_Phantoon_Tentacle_Left_2",
    "ExtendedTilemap_Phantoon_Tentacle_Right_0",
    "ExtendedTilemap_Phantoon_Tentacle_Right_1",
    "ExtendedTilemap_Phantoon_Tentacle_Right_2",
    "ExtendedTilemap_Phantoon_Mouth_0",
    "ExtendedTilemap_Phantoon_Mouth_1",
    "ExtendedTilemap_Phantoon_Mouth_2",
)

EXTENDED_SPRITEMAP_LABELS = (
    "ExtendedSpritemap_Phantoon_Body_Invulnerable",
    "ExtendedSpritemap_Phantoon_Body_FullHitbox",
    "ExtendedSpritemap_Phantoon_Body_EyeHitbox",
    "ExtendedSpritemap_Phantoon_Eye_Closed",
    "ExtendedSpritemap_Phantoon_Eye_Opening",
    "ExtendedSpritemap_Phantoon_Eye_OpeningClosing",
    "ExtendedSpritemap_Phantoon_Eye_Open",
    "ExtendedSpritemap_Phantoon_Eyeball_Centered",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingUp",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingDown",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingLeft",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingRight",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingDownLeft",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingDownRight",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingUpLeft",
    "ExtendedSpritemap_Phantoon_Eyeball_LookingUpRight",
    "ExtendedSpritemap_Phantoon_Tentacles_0",
    "ExtendedSpritemap_Phantoon_Tentacles_1",
    "ExtendedSpritemap_Phantoon_Tentacles_2",
    "ExtendedSpritemap_Phantoon_Mouth_Normal",
    "ExtendedSpritemap_Phantoon_Mouth_SpawningFlame_0",
    "ExtendedSpritemap_Phantoon_Mouth_SpawningFlame_1",
)

PALETTES = (
    ("Palette_Phantoon", "enemy-header-obj-base"),
    ("Palette_Phantoon_FadeOutTarget", "fade-out-target"),
    *((f"Palette_Phantoon_HealthBased_{index}", f"bg-health-{index}-of-7") for index in range(8)),
)

PRODUCTION_ANIMATIONS = (
    ("eye-open", "InstList_Phantoon_Eye_Open", 0xA7CC69, False),
    ("eye-close-pattern", "InstList_Phantoon_Eye_Close_PickNewPattern", 0xA7CC91, False),
    ("eye-close", "InstList_Phantoon_Eye_Close", 0xA7CC9D, False),
    ("tentacles", "InstList_Phantoon_Tentacles", 0xA7CCEB, True),
    ("mouth-flame", "InstList_Phantoon_Mouth_SpawnFlame", 0xA7CCF7, False),
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


def unique_by_name(records: Sequence[Dict[str, object]], name: str, description: str) -> Dict[str, object]:
    matches = [record for record in records if record.get("sourceLabel", record.get("name")) == name]
    if len(matches) != 1:
        fail(f"expected one {description} named {name}, found {len(matches)}")
    return matches[0]


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
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityPhantoon")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {
        str(reports[name]["disassemblyCommit"])
        for name in ("symbols", "assets", "tilesets", "headers", "oam", "instructions")
    }
    if len(commits) != 1:
        fail("Phantoon prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    room_state_address = address("RoomState_Phantoon_0")
    dead_state_address = address("RoomState_Phantoon_1")
    if rom[snes_to_pc(room_state_address) + 3] != 0x05:
        fail("live Phantoon room state no longer selects tileset $05")
    if rom[snes_to_pc(dead_state_address) + 3] != 0x04:
        fail("dead Phantoon room state no longer selects tileset $04")

    tileset = next((record for record in reports["tilesets"]["tilesets"] if int(record["id"]) == 0x05), None)
    if tileset is None or tileset["tableLabel"] != "Tileset_Table_5_WreckedShip_PowerOff":
        fail("tileset $05 no longer resolves to the powered-off Wrecked Ship source table")
    if int(tileset["resources"]["graphics"]["decompressedSize"]) != 0x4800:
        fail("Phantoon room graphics resource is no longer the exact 0x4800-byte payload")

    header_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    headers = []
    for label, species_id, tile_size, role in HEADER_SPECS:
        header = header_by_label.get(label)
        if header is None or int(header["speciesId"]) != species_id:
            fail(f"missing or changed Phantoon header {label}")
        fields = header["fields"]
        if int(fields["tileData"]["value"]) != address("Tiles_Phantoon"):
            fail(f"{label} no longer shares Tiles_Phantoon")
        if int(fields["tileDataSize"]["value"]) != tile_size:
            fail(f"{label} tile transfer size changed")
        if int(fields["palette"]["targetSnesAddress"]) != address("Palette_Phantoon"):
            fail(f"{label} no longer shares Palette_Phantoon")
        headers.append(
            {
                "sourceLabel": label,
                "role": role,
                "speciesId": species_id,
                "headerSnesAddress": int(header["snesAddress"]),
                "tileDataSize": tile_size,
                "tileDataSnesAddress": int(fields["tileData"]["value"]),
                "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
            }
        )

    obj_asset = unique_by_name(reports["assets"]["assets"], "Tiles_Phantoon", "asset")
    if int(obj_asset["size"]) != 0x0C00:
        fail("Tiles_Phantoon is no longer exactly 0x0C00 bytes")
    obj_graphics = {key: obj_asset[key] for key in ("name", "snesAddress", "size", "sha256", "asset")}

    tilemaps = [unique_by_name(reports["oam"]["extendedTilemaps"], label, "extended tilemap") for label in TILEMAP_LABELS]
    active_tilemaps = [record for record in reports["oam"]["extendedTilemaps"] if str(record["sourceLabel"]).startswith("ExtendedTilemap_Phantoon_") and not record["sourceDeclaredUnused"]]
    if {record["sourceLabel"] for record in active_tilemaps} != set(TILEMAP_LABELS):
        fail("active Phantoon tilemap inventory no longer matches the 22 source-backed components")

    extended_spritemaps = [
        unique_by_name(reports["oam"]["extendedSpritemaps"], label, "extended spritemap")
        for label in EXTENDED_SPRITEMAP_LABELS
    ]
    active_extended = [record for record in reports["oam"]["extendedSpritemaps"] if str(record["sourceLabel"]).startswith("ExtendedSpritemap_Phantoon_") and not record["sourceDeclaredUnused"]]
    if {record["sourceLabel"] for record in active_extended} != set(EXTENDED_SPRITEMAP_LABELS):
        fail("active Phantoon extended-spritemap inventory changed")
    tilemap_addresses = {int(record["snesAddress"]) for record in tilemaps}
    for record in extended_spritemaps:
        for child in record["children"]:
            if child["childType"] != "extended-tilemap" or int(child["childSnesAddress"]) not in tilemap_addresses:
                fail(f"{record['sourceLabel']} links outside the active Phantoon tilemap set")

    instruction_lists = [
        record
        for record in reports["instructions"]["lists"]
        if str(record["sourceLabel"]).startswith("InstList_Phantoon_") and not record["sourceDeclaredUnused"]
    ]
    if len(instruction_lists) != 19:
        fail(f"expected 19 active Phantoon instruction lists, found {len(instruction_lists)}")
    extended_addresses = {int(record["snesAddress"]) for record in extended_spritemaps}
    compact_lists: List[Dict[str, object]] = []
    for record in instruction_lists:
        records = [compact_record(item) for item in record["records"]]
        for item in records:
            if item["kind"] == "frame" and int(item["spritemapSnesAddress"]) not in extended_addresses:
                fail(f"{record['sourceLabel']} references a non-Phantoon frame")
        compact_lists.append(
            {
                "sourceLabel": record["sourceLabel"],
                "snesAddress": int(record["snesAddress"]),
                "size": int(record["size"]),
                "records": records,
            }
        )

    list_by_label = {record["sourceLabel"]: record for record in compact_lists}
    production_animations = []
    for key, label, end_address, loop in PRODUCTION_ANIMATIONS:
        record = list_by_label[label]
        if int(record["snesAddress"]) + int(record["size"]) != end_address:
            fail(f"production animation {key} source boundary changed")
        production_animations.append(
            {
                "key": key,
                "sourceLabel": label,
                "snesAddress": int(record["snesAddress"]),
                "endSnesAddressExclusive": end_address,
                "loop": loop,
                "records": record["records"],
            }
        )

    palettes = []
    for label, role in PALETTES:
        palette_address = address(label)
        raw = rom[snes_to_pc(palette_address) : snes_to_pc(palette_address) + 32]
        palettes.append(
            {
                "sourceLabel": label,
                "role": role,
                "snesAddress": palette_address,
                "size": 32,
                "sha256": sha256(raw),
            }
        )
    if address("UNUSED_Palette_Phantoon_A7CA21") == address("Palette_Phantoon_HealthBased_7"):
        fail("unused clone unexpectedly aliases the active full-health palette address")
    unused_clone = rom[snes_to_pc(address("UNUSED_Palette_Phantoon_A7CA21")) : snes_to_pc(address("UNUSED_Palette_Phantoon_A7CA21")) + 32]
    full_health = rom[snes_to_pc(address("Palette_Phantoon_HealthBased_7")) : snes_to_pc(address("Palette_Phantoon_HealthBased_7")) + 32]
    if unused_clone != full_health:
        fail("the source-declared unused $CA21 palette is no longer an exact clone of the active full-health palette")

    hitboxes = []
    for label in ("Hitbox_Phantoon_0", "Hitbox_Phantoon_1", "Hitbox_Phantoon_2"):
        hitbox_address = address(label)
        pc = snes_to_pc(hitbox_address)
        count = read_word(rom, pc)
        raw = rom[pc : pc + 2 + count * 12]
        hitboxes.append(
            {
                "sourceLabel": label,
                "snesAddress": hitbox_address,
                "boxCount": count,
                "size": len(raw),
                "sha256": sha256(raw),
            }
        )

    bank_a7 = (disassembly / "src" / "bank_A7.asm").read_text(encoding="utf-8")
    bank_8f = (disassembly / "src" / "bank_8F.asm").read_text(encoding="utf-8")
    required_tokens = (
        (bank_8f, "dw $0002 : dl $7E2000 : dw $4800,$1000", "live BG2 room-map upload"),
        (bank_a7, "dw InstList_Phantoon_Body_Invulnerable", "four-part initial instruction table"),
        (bank_a7, "dw Palette_Phantoon_HealthBased_7", "health-palette pointer table"),
        (bank_a7, "LDA.W Palette_Phantoon_FadeOutTarget,X", "fade-out palette consumer"),
    )
    for source, token, description in required_tokens:
        if token not in source:
            fail(f"source no longer contains {description}: {token}")

    totals = {
        "roomStateCount": 2,
        "headerCount": len(headers),
        "tilemapCount": len(tilemaps),
        "tilemapRunCount": sum(int(record["runCount"]) for record in tilemaps),
        "tilemapWordCount": sum(int(record["wordCount"]) for record in tilemaps),
        "extendedSpritemapCount": len(extended_spritemaps),
        "extendedChildCount": sum(int(record["childCount"]) for record in extended_spritemaps),
        "instructionListCount": len(compact_lists),
        "frameOccurrenceCount": sum(item["kind"] == "frame" for record in compact_lists for item in record["records"]),
        "handlerOccurrenceCount": sum(item["kind"] == "handler" for record in compact_lists for item in record["records"]),
        "productionAnimationCount": len(production_animations),
        "productionAnimationFrameCount": sum(item["kind"] == "frame" for record in production_animations for item in record["records"]),
        "paletteStateCount": len(palettes),
        "healthPaletteCount": 8,
        "hitboxCount": len(hitboxes),
    }

    ownership = {
        "bgPixels": {
            "tilesetId": 5,
            "resource": tileset["resources"]["graphics"],
            "projectEditKey": 'varGfx["5"]',
        },
        "objPixels": {
            "resource": obj_graphics,
            "role": "shared enemy-header transfer; separate from the visible BG2 component editor",
        },
        "placement": {
            "sourceBank": 0xA7,
            "editable": False,
            "tilemaps": [record["sourceLabel"] for record in tilemaps],
        },
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "tilemaps": aggregate_hash(tilemaps),
        "extendedSpritemaps": aggregate_hash(extended_spritemaps),
        "instructionLists": aggregate_hash(compact_lists),
        "productionAnimations": aggregate_hash(production_animations),
        "palettes": aggregate_hash(palettes),
        "hitboxes": aggregate_hash(hitboxes),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "roomStates": [
            {
                "sourceLabel": "RoomState_Phantoon_0",
                "snesAddress": room_state_address,
                "tilesetId": 5,
                "libraryBackgroundLabel": "LibBG_Phantoon_State0",
            },
            {
                "sourceLabel": "RoomState_Phantoon_1",
                "snesAddress": dead_state_address,
                "tilesetId": 4,
                "libraryBackgroundLabel": "LibBG_Phantoon_Draygon_State1",
            },
        ],
        "ownership": ownership,
        "headers": headers,
        "tilemaps": tilemaps,
        "extendedSpritemaps": extended_spritemaps,
        "instructionLists": compact_lists,
        "productionAnimations": production_animations,
        "palettes": palettes,
        "unusedFullHealthPaletteClone": {
            "sourceLabel": "UNUSED_Palette_Phantoon_A7CA21",
            "snesAddress": address("UNUSED_Palette_Phantoon_A7CA21"),
            "activeSourceLabel": "Palette_Phantoon_HealthBased_7",
            "activeSnesAddress": address("Palette_Phantoon_HealthBased_7"),
            "bytesIdentical": True,
        },
        "hitboxes": hitboxes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Phantoon manifest: "
        f"{totals['tilemapCount']} tilemaps, {totals['extendedSpritemapCount']} extended spritemaps, "
        f"{totals['instructionListCount']} lists / {totals['frameOccurrenceCount']} frames, "
        f"{totals['healthPaletteCount']} health palettes"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
