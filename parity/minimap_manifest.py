#!/usr/bin/env python3
"""Build the source-backed pause-map and map-station manifest.

This inventories the seven bank-$B5 area tilemaps, bank-$82 MapData reveal
masks, their runtime pointer tables and consumers, the five vanilla map-station
PLM placements, the pause-map tile graphics, and both coordinate transforms.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, List, Tuple

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ROOMS = PARITY_DIR / "reports" / "rooms.json"
DEFAULT_ASSETS = PARITY_DIR / "reports" / "assets.json"
DEFAULT_OUTPUT = PARITY_DIR / "reports" / "minimap.json"

AREA_NAMES = ["Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres"]
AREA_MAP_POINTER_TABLE = 0x82964A
MAP_DATA_POINTER_TABLE = 0x829717
AREA_MAP_BYTES = 0x1000
MAP_DATA_BYTES = 0x100
MAP_WIDTH = 64
MAP_HEIGHT = 32
ROOM_MAP_HEIGHT = 31
PAGE_WIDTH = 32
PAGE_HEIGHT = 32
MAP_STATION_PLM_ID = 0xB6D3
PAUSE_MAP_GRAPHICS = 0xB68000
PAUSE_MAP_GRAPHICS_USED_BYTES = 0x2000

ENGINE_CONSUMERS = [
    ("LoadMirrorOfCurrentAreasMapExplored", 0x80858C),
    ("MirrorCurrentAreasMapExplored", 0x8085C6),
    ("LoadPauseMenuMapTilemap", 0x82943D),
    ("DrawRoomSelectMap", 0x829517),
    ("Instruction_PLM_Activate_MapStation", 0x848C8F),
    ("Setup_MapStation", 0x84B18B),
    ("MarkMapTilesExplored", 0x90A8A6),
]


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def formatted(address: int) -> str:
    return f"{address >> 16:02X}:{address & 0xFFFF:04X}"


def snes_to_pc(address: int) -> int:
    bank = (address >> 16) & 0xFF
    offset = address & 0xFFFF
    if bank < 0x80 or offset < 0x8000:
        fail(f"not a ROM LoROM address: {formatted(address)}")
    return ((bank & 0x7F) * 0x8000) + (offset - 0x8000)


def read_u16(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 2 > len(data):
        fail(f"16-bit read outside ROM at PC {offset:X}")
    return data[offset] | (data[offset + 1] << 8)


def read_u24(data: bytes, offset: int) -> int:
    return read_u16(data, offset) | (data[offset + 2] << 16)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded)


def label_at(catalog: SymbolCatalog, address: int, prefixes: Tuple[str, ...]) -> str:
    names = sorted(symbol.name for symbol in catalog.at(address))
    for prefix in prefixes:
        match = next((name for name in names if name.startswith(prefix)), None)
        if match is not None:
            return match
    fail(f"missing source label at {formatted(address)} matching {prefixes}")


def storage_word_index(x: int, y: int) -> int:
    page = x // PAGE_WIDTH
    local_x = x % PAGE_WIDTH
    storage_y = (y + 1) % PAGE_HEIGHT
    return page * PAGE_WIDTH * PAGE_HEIGHT + storage_y * PAGE_WIDTH + local_x


def map_data_byte_and_mask(x: int, y: int) -> Tuple[int, int]:
    page = x // PAGE_WIDTH
    local_x = x % PAGE_WIDTH
    storage_y = (y + 1) % PAGE_HEIGHT
    return page * 0x80 + storage_y * 4 + local_x // 8, 0x80 >> (local_x & 7)


def decode_4bpp(raw: bytes) -> bytes:
    if len(raw) % 32:
        fail("pause-map 4bpp payload is not tile aligned")
    pixels = bytearray()
    for tile in range(len(raw) // 32):
        start = tile * 32
        for row in range(8):
            p0 = raw[start + row * 2]
            p1 = raw[start + row * 2 + 1]
            p2 = raw[start + 16 + row * 2]
            p3 = raw[start + 16 + row * 2 + 1]
            for column in range(8):
                bit = 7 - column
                pixels.append(
                    ((p0 >> bit) & 1)
                    | (((p1 >> bit) & 1) << 1)
                    | (((p2 >> bit) & 1) << 2)
                    | (((p3 >> bit) & 1) << 3)
                )
    return bytes(pixels)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build exact minimap and map-station parity")
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--rooms", type=Path, default=DEFAULT_ROOMS)
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    disassembly = (
        args.disassembly or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    for path, task in (
        (symbols_path, "parityBuildReference"),
        (rom_path, "parityBuildReference"),
        (args.rooms, "parityRooms"),
        (args.assets, "parityAssets"),
    ):
        if not Path(path).is_file():
            fail(f"missing {path}; run ./gradlew {task}")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    rooms = json.loads(Path(args.rooms).read_text(encoding="utf-8"))
    assets = json.loads(Path(args.assets).read_text(encoding="utf-8"))
    commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=disassembly, text=True,
    ).strip()

    area_pointer_pc = snes_to_pc(AREA_MAP_POINTER_TABLE)
    map_data_pointer_pc = snes_to_pc(MAP_DATA_POINTER_TABLE)
    area_pointer_raw = rom[area_pointer_pc:area_pointer_pc + 7 * 3]
    map_data_pointer_raw = rom[map_data_pointer_pc:map_data_pointer_pc + 8 * 2]

    area_records: List[Dict[str, object]] = []
    for area, name in enumerate(AREA_NAMES):
        tilemap_address = read_u24(rom, area_pointer_pc + area * 3)
        expected_tilemap = [0xB59000, 0xB58000, 0xB5A000, 0xB5B000, 0xB5C000, 0xB5D000, 0xB5E000][area]
        if tilemap_address != expected_tilemap:
            fail(f"{name} tilemap pointer is {formatted(tilemap_address)}, expected {formatted(expected_tilemap)}")
        tilemap_pc = snes_to_pc(tilemap_address)
        tilemap_raw = rom[tilemap_pc:tilemap_pc + AREA_MAP_BYTES]
        if len(tilemap_raw) != AREA_MAP_BYTES:
            fail(f"truncated {name} tilemap")
        words = [read_u16(tilemap_raw, offset) for offset in range(0, AREA_MAP_BYTES, 2)]

        map_data_address = 0x820000 | read_u16(rom, map_data_pointer_pc + area * 2)
        expected_map_data = 0x829727 + area * MAP_DATA_BYTES
        if map_data_address != expected_map_data:
            fail(f"{name} map-data pointer is {formatted(map_data_address)}, expected {formatted(expected_map_data)}")
        map_data_pc = snes_to_pc(map_data_address)
        map_data_raw = rom[map_data_pc:map_data_pc + MAP_DATA_BYTES]
        if len(map_data_raw) != MAP_DATA_BYTES:
            fail(f"truncated {name} map-data mask")

        logical_words = [
            words[storage_word_index(x, y)]
            for y in range(MAP_HEIGHT) for x in range(MAP_WIDTH)
        ]
        revealed = []
        for y in range(MAP_HEIGHT):
            for x in range(MAP_WIDTH):
                byte_offset, mask = map_data_byte_and_mask(x, y)
                revealed.append(bool(map_data_raw[byte_offset] & mask))
        area_records.append({
            "area": area,
            "name": name,
            "tilemapAddress": tilemap_address,
            "tilemapAddressText": formatted(tilemap_address),
            "tilemapSourceLabel": label_at(catalog, tilemap_address, ("MapTilemaps_", "UNUSED_MapTilemaps_")),
            "tilemapByteCount": len(tilemap_raw),
            "tilemapWordCount": len(words),
            "tilemapRawSha256": sha256(tilemap_raw),
            "tilemapLogicalSha256": sha256(b"".join(word.to_bytes(2, "little") for word in logical_words)),
            "nonBlankTileCount": sum((word & 0x3FF) != 0x1F for word in words),
            "uniqueTileIndexCount": len({word & 0x3FF for word in words}),
            "paletteCounts": {str(key): value for key, value in sorted(Counter((word >> 10) & 7 for word in words).items())},
            "priorityTileCount": sum(bool(word & 0x2000) for word in words),
            "mapDataAddress": map_data_address,
            "mapDataAddressText": formatted(map_data_address),
            "mapDataSourceLabel": label_at(catalog, map_data_address, ("MapData_",)),
            "mapDataByteCount": len(map_data_raw),
            "mapDataRawSha256": sha256(map_data_raw),
            "revealedTileCount": sum(revealed),
        })

    # The eighth debug area intentionally aliases Tourian's map-data mask.
    debug_pointer = 0x820000 | read_u16(rom, map_data_pointer_pc + 7 * 2)
    if debug_pointer != area_records[5]["mapDataAddress"]:
        fail("debug map-data pointer does not alias Tourian")

    room_by_id = {int(room["roomId"]): room for room in rooms["rooms"]}
    placements: List[Dict[str, object]] = []
    for population in rooms["resources"]["plm"]:
        for entry_index, entry in enumerate(population["entries"]):
            if int(entry["id"]) != MAP_STATION_PLM_ID:
                continue
            for consumer in population["consumers"]:
                room = room_by_id[int(consumer["roomId"])]
                placements.append({
                    "populationPointer": int(population["pointer"]),
                    "populationAddress": population["address"],
                    "entryIndex": entry_index,
                    "roomId": int(consumer["roomId"]),
                    "roomIdHex": room["roomIdHex"],
                    "roomSourceLabel": consumer["roomLabel"],
                    "stateSourceLabel": consumer["stateLabel"],
                    "area": int(room["area"]),
                    "x": int(entry["x"]),
                    "y": int(entry["y"]),
                    "param": int(entry["param"]),
                })
    placements.sort(key=lambda entry: (entry["area"], entry["roomId"], entry["entryIndex"]))
    if len(placements) != 5 or [entry["area"] for entry in placements] != list(range(5)):
        fail("expected one vanilla map station in each area 0 through 4")

    graphic_asset = next(
        (asset for asset in assets["assets"] if asset["name"] == "Tiles_PauseScreen_BG1_BG2"),
        None,
    )
    if graphic_asset is None or int(graphic_asset["snesAddress"]) != PAUSE_MAP_GRAPHICS:
        fail("pause-map graphics asset ownership is missing or incorrect")
    graphics_pc = snes_to_pc(PAUSE_MAP_GRAPHICS)
    graphics_raw = rom[graphics_pc:graphics_pc + PAUSE_MAP_GRAPHICS_USED_BYTES]
    graphics_pixels = decode_4bpp(graphics_raw)

    transforms = {
        "storageWordIndices": [
            storage_word_index(x, y)
            for y in range(MAP_HEIGHT) for x in range(MAP_WIDTH)
        ],
        "mapDataByteMasks": [
            list(map_data_byte_and_mask(x, y))
            for y in range(MAP_HEIGHT) for x in range(MAP_WIDTH)
        ],
    }
    if len(set(transforms["storageWordIndices"])) != MAP_WIDTH * MAP_HEIGHT:
        fail("tilemap coordinate transform is not bijective")
    if len({tuple(value) for value in transforms["mapDataByteMasks"]}) != MAP_WIDTH * MAP_HEIGHT:
        fail("map-data coordinate transform is not bijective")

    room_screen_count = sum(int(room["width"]) * int(room["height"]) for room in rooms["rooms"])
    max_room_right = max(int(room["mapX"]) + int(room["width"]) for room in rooms["rooms"])
    max_room_bottom = max(int(room["mapY"]) + int(room["height"]) for room in rooms["rooms"])
    rooms_outside = [
        room["sourceLabel"] for room in rooms["rooms"]
        if int(room["mapX"]) + int(room["width"]) > MAP_WIDTH
        or int(room["mapY"]) + int(room["height"]) > ROOM_MAP_HEIGHT
    ]
    if rooms_outside:
        fail(f"room map rectangles outside the 64x31 room-coordinate area: {rooms_outside}")

    engine = []
    for label, address in ENGINE_CONSUMERS:
        actual = label_at(catalog, address, (label,))
        if actual != label:
            fail(f"engine consumer label mismatch at {formatted(address)}")
        engine.append({"sourceLabel": label, "address": formatted(address), "snesAddress": address})

    report = {
        "schemaVersion": 1,
        "disassemblyCommit": commit,
        "areaCount": len(area_records),
        "areaMapPointerCount": 7,
        "mapDataPointerCount": 8,
        "mapDataUniquePointerCount": len({int(area["mapDataAddress"]) for area in area_records}),
        "tilemapWordCount": sum(int(area["tilemapWordCount"]) for area in area_records),
        "mapDataByteCount": sum(int(area["mapDataByteCount"]) for area in area_records),
        "revealedTileCount": sum(int(area["revealedTileCount"]) for area in area_records),
        "mapStationPlacementCount": len(placements),
        "mapStationAreaCount": len({int(entry["area"]) for entry in placements}),
        "roomCount": len(rooms["rooms"]),
        "roomScreenCount": room_screen_count,
        "roomCoordinateHeight": ROOM_MAP_HEIGHT,
        "maxRoomRight": max_room_right,
        "maxRoomBottom": max_room_bottom,
        "engineConsumerCount": len(engine),
        "areaMapPointerTable": {
            "address": formatted(AREA_MAP_POINTER_TABLE),
            "snesAddress": AREA_MAP_POINTER_TABLE,
            "sourceLabel": label_at(catalog, AREA_MAP_POINTER_TABLE, ("AreaMapPointers",)),
            "rawSha256": sha256(area_pointer_raw),
        },
        "mapDataPointerTable": {
            "address": formatted(MAP_DATA_POINTER_TABLE),
            "snesAddress": MAP_DATA_POINTER_TABLE,
            "sourceLabel": label_at(catalog, MAP_DATA_POINTER_TABLE, ("MapData_pointers",)),
            "rawSha256": sha256(map_data_pointer_raw),
            "debugAliasArea": 5,
        },
        "areas": area_records,
        "mapStations": {
            "plmId": MAP_STATION_PLM_ID,
            "placements": placements,
        },
        "graphics": {
            "sourceLabel": "Tiles_PauseScreen_BG1_BG2",
            "address": formatted(PAUSE_MAP_GRAPHICS),
            "snesAddress": PAUSE_MAP_GRAPHICS,
            "sourceAssetByteCount": int(graphic_asset["size"]),
            "usedByteCount": len(graphics_raw),
            "tileCount": len(graphics_raw) // 32,
            "rawSha256": sha256(graphics_raw),
            "decodedPixelSha256": sha256(graphics_pixels),
        },
        "coordinateTransform": {
            "width": MAP_WIDTH,
            "height": MAP_HEIGHT,
            "roomCoordinateHeight": ROOM_MAP_HEIGHT,
            "pageWidth": PAGE_WIDTH,
            "pageHeight": PAGE_HEIGHT,
            "storageRowOffset": 1,
            "tilemapTransformSha256": aggregate_hash(transforms["storageWordIndices"]),
            "mapDataTransformSha256": aggregate_hash(transforms["mapDataByteMasks"]),
        },
        "engineConsumers": engine,
        "aggregateHashes": {
            "tilemaps": aggregate_hash([
                [area["area"], area["tilemapAddress"], area["tilemapRawSha256"]]
                for area in area_records
            ]),
            "mapData": aggregate_hash([
                [area["area"], area["mapDataAddress"], area["mapDataRawSha256"]]
                for area in area_records
            ]),
            "mapStations": aggregate_hash(placements),
            "consumers": aggregate_hash(engine),
        },
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Minimap/map-station source manifest complete")
    print(f"  Area tilemaps: {report['areaCount']} / {report['tilemapWordCount']} words")
    print(f"  Map data: {report['mapDataByteCount']} bytes / {report['revealedTileCount']} set tiles")
    print(f"  Map stations: {report['mapStationPlacementCount']} placements across {report['mapStationAreaCount']} areas")
    print(f"  Output: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
