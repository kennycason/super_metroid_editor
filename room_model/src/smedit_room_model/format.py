from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from . import FORMAT_VERSION


@dataclass(frozen=True)
class RoomGrid:
    room_id: int
    room_id_hex: str
    handle: str
    name: str
    area: int
    area_name: str
    tileset: int
    width_screens: int
    height_screens: int
    width: int
    height: int
    content_hash: str
    layer1_words: tuple[int, ...]
    block_types: tuple[int, ...]
    resolved_block_types: tuple[int, ...]
    bts: tuple[int, ...]

    @property
    def cell_count(self) -> int:
        return self.width * self.height

    @classmethod
    def load(cls, path: str | Path) -> "RoomGrid":
        source = Path(path)
        with source.open("r", encoding="utf-8") as stream:
            value = json.load(stream)
        return cls.from_dict(value, source)

    @classmethod
    def from_dict(cls, value: dict[str, Any], source: Path | None = None) -> "RoomGrid":
        where = str(source) if source is not None else "room object"
        schema = int(value.get("schemaVersion", -1))
        if schema != FORMAT_VERSION:
            raise ValueError(f"{where}: unsupported schemaVersion {schema}; expected {FORMAT_VERSION}")
        width = int(value["widthBlocks"])
        height = int(value["heightBlocks"])
        if width <= 0 or height <= 0:
            raise ValueError(f"{where}: room dimensions must be positive, got {width}x{height}")
        count = width * height

        def integer_array(name: str, low: int, high: int) -> tuple[int, ...]:
            raw = value.get(name)
            if not isinstance(raw, list) or len(raw) != count:
                size = len(raw) if isinstance(raw, list) else "non-list"
                raise ValueError(f"{where}: {name} has {size} values; expected {count}")
            result = tuple(int(item) for item in raw)
            invalid = next((item for item in result if item < low or item > high), None)
            if invalid is not None:
                raise ValueError(f"{where}: {name} value {invalid} is outside {low}..{high}")
            return result

        words = integer_array("layer1Words", 0, 0xFFFF)
        raw_types = integer_array("blockTypes", 0, 0xF)
        resolved_types = integer_array("resolvedBlockTypes", 0, 0xF)
        bts = integer_array("bts", 0, 0xFF)
        derived_types = tuple((word >> 12) & 0xF for word in words)
        if raw_types != derived_types:
            raise ValueError(f"{where}: blockTypes do not match the layer1Words high nibbles")

        return cls(
            room_id=int(value["roomId"]),
            room_id_hex=str(value["roomIdHex"]),
            handle=str(value.get("handle", "")),
            name=str(value.get("name", "")),
            area=int(value["area"]),
            area_name=str(value.get("areaName", "")),
            tileset=int(value["tileset"]),
            width_screens=int(value["widthScreens"]),
            height_screens=int(value["heightScreens"]),
            width=width,
            height=height,
            content_hash=str(value["contentHash"]),
            layer1_words=words,
            block_types=raw_types,
            resolved_block_types=resolved_types,
            bts=bts,
        )


def load_manifest(dataset_dir: str | Path) -> tuple[Path, dict[str, Any]]:
    root = Path(dataset_dir).resolve()
    manifest_path = root / "manifest.json"
    with manifest_path.open("r", encoding="utf-8") as stream:
        manifest = json.load(stream)
    schema = int(manifest.get("schemaVersion", -1))
    if schema != FORMAT_VERSION:
        raise ValueError(
            f"{manifest_path}: unsupported schemaVersion {schema}; expected {FORMAT_VERSION}"
        )
    entries = manifest.get("rooms")
    if not isinstance(entries, list) or not entries:
        raise ValueError(f"{manifest_path}: no room samples")
    return root, manifest


def load_rooms(dataset_dir: str | Path) -> tuple[list[RoomGrid], dict[str, Any]]:
    root, manifest = load_manifest(dataset_dir)
    rooms = [RoomGrid.load(root / entry["file"]) for entry in manifest["rooms"]]
    return rooms, manifest


def resolve_block_types(words: Iterable[int], width: int, height: int) -> list[int]:
    source = list(words)
    if len(source) != width * height:
        raise ValueError("word count does not match room dimensions")
    result = [0] * len(source)
    for index in range(len(source)):
        cursor = index
        for _ in range(32):
            block_type = (source[cursor] >> 12) & 0xF
            if block_type == 0x5:
                if cursor % width == 0:
                    block_type = 0x8
                    break
                cursor -= 1
            elif block_type == 0xD:
                if cursor < width:
                    block_type = 0x8
                    break
                cursor -= width
            else:
                break
        result[index] = block_type
    return result


def find_door_groups(block_types: Iterable[int], width: int, height: int) -> list[dict[str, Any]]:
    values = list(block_types)
    if len(values) != width * height:
        raise ValueError("block type count does not match room dimensions")
    remaining = {index for index, value in enumerate(values) if value == 0x9}
    groups: list[dict[str, Any]] = []
    while remaining:
        start = min(remaining)
        remaining.remove(start)
        queue = [start]
        cells: list[int] = []
        while queue:
            current = queue.pop()
            cells.append(current)
            x = current % width
            y = current // width
            for nx, ny in ((x, y - 1), (x, y + 1), (x - 1, y), (x + 1, y)):
                if nx < 0 or nx >= width or ny < 0 or ny >= height:
                    continue
                neighbor = ny * width + nx
                if neighbor in remaining:
                    remaining.remove(neighbor)
                    queue.append(neighbor)
        cells.sort()
        xs = [cell % width for cell in cells]
        ys = [cell // width for cell in cells]
        min_x, max_x = min(xs), max(xs)
        min_y, max_y = min(ys), max(ys)
        if min_x <= 1:
            edge = "left"
        elif max_x >= width - 2:
            edge = "right"
        elif min_y <= 1:
            edge = "top"
        elif max_y >= height - 2:
            edge = "bottom"
        else:
            edge = "interior"
        groups.append(
            {
                "edge": edge,
                "orientation": "vertical" if max_y - min_y >= max_x - min_x else "horizontal",
                "cellIndices": cells,
            }
        )
    return groups


def file_sha256(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_dataset(dataset_dir: str | Path) -> dict[str, Any]:
    rooms, manifest = load_rooms(dataset_dir)
    unique_contents = {room.content_hash for room in rooms}
    tilesets = sorted({room.tileset for room in rooms})
    block_counts = [0] * 16
    bts_nonzero = 0
    door_groups = 0
    cells = 0
    for room in rooms:
        cells += room.cell_count
        for block_type in room.block_types:
            block_counts[block_type] += 1
        bts_nonzero += sum(value != 0 for value in room.bts)
        door_groups += len(find_door_groups(room.block_types, room.width, room.height))
    return {
        "schemaVersion": manifest["schemaVersion"],
        "rooms": len(rooms),
        "uniqueRoomContents": len(unique_contents),
        "tilesets": tilesets,
        "cells": cells,
        "nonzeroBtsCells": bts_nonzero,
        "doorGroups": door_groups,
        "blockTypeCounts": block_counts,
    }

