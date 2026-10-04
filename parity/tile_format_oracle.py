#!/usr/bin/env python3
"""Independently decode source-owned SNES tiles and metatile words."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

from lz5_oracle import decode_lz5


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_TILESET_MANIFEST = PARITY_DIR / "reports" / "tilesets.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "tile-formats.json"

SPLIT_PLANE_GRAPHICS = {
    "Tiles_11_12_CeresElevator",
    "Tiles_13_14_CeresRidley",
}
KRAID_GRAPHICS = "Tiles_1A_Kraid"
FULL_CERES_METATILE_TABLE = "TileTables_F_10_11_12_13_14_Ceres"
STANDARD_BG3_LABEL = "Tiles_Standard_BG3"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def load_json(path: Path, task: str) -> Dict[str, object]:
    if not path.is_file():
        fail(f"missing {path}; run ./gradlew {task}")
    return json.loads(path.read_text(encoding="utf-8"))


def one_by_name(records: Iterable[Dict[str, object]], name: str) -> Dict[str, object]:
    matches = [record for record in records if record.get("name") == name]
    if len(matches) != 1:
        fail(f"expected one asset record named {name}, found {len(matches)}")
    return matches[0]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def decode_standard_4bpp(data: bytes) -> bytes:
    if not data or len(data) % 32 != 0:
        fail(f"standard 4bpp payload has invalid size {len(data)}")
    pixels = bytearray()
    for tile_offset in range(0, len(data), 32):
        for row in range(8):
            bp0 = data[tile_offset + row * 2]
            bp1 = data[tile_offset + row * 2 + 1]
            bp2 = data[tile_offset + row * 2 + 16]
            bp3 = data[tile_offset + row * 2 + 17]
            for column in range(8):
                bit = 7 - column
                pixels.append(
                    ((bp0 >> bit) & 1)
                    | (((bp1 >> bit) & 1) << 1)
                    | (((bp2 >> bit) & 1) << 2)
                    | (((bp3 >> bit) & 1) << 3)
                )
    return bytes(pixels)


def decode_global_split_plane_4bpp(data: bytes) -> bytes:
    if not data or len(data) % 32 != 0:
        fail(f"split-plane 4bpp payload has invalid size {len(data)}")
    tile_count = len(data) // 32
    half = len(data) // 2
    pixels = bytearray()
    for tile in range(tile_count):
        low_offset = tile * 16
        high_offset = half + tile * 16
        for row in range(8):
            bp0 = data[low_offset + row]
            bp1 = data[high_offset + row]
            bp2 = data[low_offset + 8 + row]
            bp3 = data[high_offset + 8 + row]
            for column in range(8):
                bit = 7 - column
                pixels.append(
                    ((bp0 >> bit) & 1)
                    | (((bp1 >> bit) & 1) << 1)
                    | (((bp2 >> bit) & 1) << 2)
                    | (((bp3 >> bit) & 1) << 3)
                )
    return bytes(pixels)


def decode_standard_2bpp(data: bytes) -> bytes:
    if not data or len(data) % 16 != 0:
        fail(f"standard 2bpp payload has invalid size {len(data)}")
    pixels = bytearray()
    for tile_offset in range(0, len(data), 16):
        for row in range(8):
            bp0 = data[tile_offset + row * 2]
            bp1 = data[tile_offset + row * 2 + 1]
            for column in range(8):
                bit = 7 - column
                pixels.append(((bp0 >> bit) & 1) | (((bp1 >> bit) & 1) << 1))
    return bytes(pixels)


def pixel_record(pixels: bytes) -> Dict[str, object]:
    counts = Counter(pixels)
    return {
        "pixelCount": len(pixels),
        "pixelSha256": sha256(pixels),
        "colorIndexCounts": {
            str(index): counts[index] for index in range(max(counts.keys(), default=-1) + 1)
        },
    }


def decompress_asset(disassembly: Path, resource: Dict[str, object]) -> bytes:
    source = (disassembly / str(resource["asset"])).read_bytes()
    result = decode_lz5(source)
    if result.consumed != len(source):
        fail(f"{resource['sourceLabel']} is not one exact LZ5 source range")
    expected_size = int(resource["decompressedSize"])
    if len(result.data) != expected_size:
        fail(
            f"{resource['sourceLabel']} decoded to {len(result.data)} bytes; "
            f"tileset manifest says {expected_size}"
        )
    return result.data


def grouped_resources(
    tilesets: Sequence[Dict[str, object]], kind: str
) -> List[Tuple[Dict[str, object], List[int]]]:
    grouped: Dict[int, Tuple[Dict[str, object], List[int]]] = {}
    for tileset in tilesets:
        resource = tileset["resources"][kind]
        address = int(resource["snesAddress"])
        if address not in grouped:
            grouped[address] = (resource, [])
        grouped[address][1].append(int(tileset["id"]))
    return [grouped[address] for address in sorted(grouped)]


def build_4bpp_records(
    disassembly: Path, tileset_manifest: Dict[str, object]
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for resource, tileset_ids in grouped_resources(tileset_manifest["tilesets"], "graphics"):
        label = str(resource["sourceLabel"])
        decoded = decompress_asset(disassembly, resource)
        layout = "global-split-plane-4bpp" if label in SPLIT_PLANE_GRAPHICS else "standard-4bpp"
        pixels = (
            decode_global_split_plane_4bpp(decoded)
            if layout == "global-split-plane-4bpp"
            else decode_standard_4bpp(decoded)
        )
        tile_count = len(decoded) // 32
        owns_all_slots = layout == "global-split-plane-4bpp" or label == KRAID_GRAPHICS
        expected_tile_count = 1024 if owns_all_slots else 576
        if tile_count != expected_tile_count:
            fail(f"{label} must contain {expected_tile_count} tiles, found {tile_count}")
        runtime_capacity = 1024 if owns_all_slots else 640
        records.append(
            {
                "role": "tileset",
                "sourceLabel": label,
                "asset": resource["asset"],
                "address": resource["address"],
                "snesAddress": resource["snesAddress"],
                "layout": layout,
                "tilesetIds": tileset_ids,
                "decompressedSize": len(decoded),
                "tileCount": tile_count,
                "runtimeTileStart": 0,
                "runtimeTileCapacity": runtime_capacity,
                "undefinedReservedTileCount": runtime_capacity - tile_count,
                **pixel_record(pixels),
            }
        )

    cre_resource = tileset_manifest["cre"]["graphics"]
    cre_decoded = decompress_asset(disassembly, cre_resource)
    cre_pixels = decode_standard_4bpp(cre_decoded)
    if len(cre_decoded) != 384 * 32:
        fail(f"CRE graphics must contain 384 tiles, found {len(cre_decoded) // 32}")
    records.append(
        {
            "role": "cre",
            "sourceLabel": cre_resource["sourceLabel"],
            "asset": cre_resource["asset"],
            "address": cre_resource["address"],
            "snesAddress": cre_resource["snesAddress"],
            "layout": "standard-4bpp",
            "tilesetIds": [],
            "decompressedSize": len(cre_decoded),
            "tileCount": len(cre_decoded) // 32,
            "runtimeTileStart": 640,
            "runtimeTileCapacity": 384,
            "undefinedReservedTileCount": 0,
            **pixel_record(cre_pixels),
        }
    )
    return sorted(records, key=lambda record: int(record["snesAddress"]))


def semantic_metatile_record(data: bytes) -> Dict[str, object]:
    if not data or len(data) % 8 != 0:
        fail(f"metatile payload has invalid size {len(data)}")
    canonical = bytearray()
    palette_counts: Counter[int] = Counter()
    tile_counts: Counter[int] = Counter()
    priority_count = 0
    h_flip_count = 0
    v_flip_count = 0
    for offset in range(0, len(data), 2):
        word = data[offset] | (data[offset + 1] << 8)
        tile = word & 0x03FF
        palette = (word >> 10) & 7
        priority = bool(word & 0x2000)
        h_flip = bool(word & 0x4000)
        v_flip = bool(word & 0x8000)
        flags = int(priority) | (int(h_flip) << 1) | (int(v_flip) << 2)
        canonical.extend((tile & 0xFF, tile >> 8, palette, flags))
        tile_counts[tile] += 1
        palette_counts[palette] += 1
        priority_count += int(priority)
        h_flip_count += int(h_flip)
        v_flip_count += int(v_flip)
    return {
        "metatileCount": len(data) // 8,
        "wordCount": len(data) // 2,
        "semanticSha256": sha256(bytes(canonical)),
        "minimumTile": min(tile_counts),
        "maximumTile": max(tile_counts),
        "uniqueTileCount": len(tile_counts),
        "paletteCounts": {str(index): palette_counts[index] for index in range(8)},
        "priorityWordCount": priority_count,
        "hFlipWordCount": h_flip_count,
        "vFlipWordCount": v_flip_count,
    }


def build_metatile_records(
    disassembly: Path, tileset_manifest: Dict[str, object]
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for resource, tileset_ids in grouped_resources(tileset_manifest["tilesets"], "tileTable"):
        decoded = decompress_asset(disassembly, resource)
        metatile_count = len(decoded) // 8
        label = str(resource["sourceLabel"])
        expected_count = 1024 if label == FULL_CERES_METATILE_TABLE else 768
        if metatile_count != expected_count:
            fail(f"{label} must contain {expected_count} metatiles, found {metatile_count}")
        records.append(
            {
                "role": "tileset",
                "sourceLabel": resource["sourceLabel"],
                "asset": resource["asset"],
                "address": resource["address"],
                "snesAddress": resource["snesAddress"],
                "tilesetIds": tileset_ids,
                "decompressedSize": len(decoded),
                "runtimeMetatileStart": 0 if metatile_count == 1024 else 256,
                **semantic_metatile_record(decoded),
            }
        )

    cre_resource = tileset_manifest["cre"]["tileTable"]
    cre_decoded = decompress_asset(disassembly, cre_resource)
    if len(cre_decoded) != 256 * 8:
        fail(f"CRE tile table must contain 256 metatiles, found {len(cre_decoded) // 8}")
    records.append(
        {
            "role": "cre",
            "sourceLabel": cre_resource["sourceLabel"],
            "asset": cre_resource["asset"],
            "address": cre_resource["address"],
            "snesAddress": cre_resource["snesAddress"],
            "tilesetIds": [],
            "decompressedSize": len(cre_decoded),
            "runtimeMetatileStart": 0,
            **semantic_metatile_record(cre_decoded),
        }
    )
    return sorted(records, key=lambda record: int(record["snesAddress"]))


def aggregate_hash(records: Sequence[Dict[str, object]], field: str) -> str:
    digest = hashlib.sha256()
    for record in records:
        digest.update(str(record["sourceLabel"]).encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(record[field]).encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def source_revision(disassembly: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate independent source-backed 2bpp/4bpp and metatile semantics."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--tileset-manifest", type=Path, default=DEFAULT_TILESET_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    assets = load_json(args.asset_manifest.expanduser().resolve(), "parityAssets")
    tilesets = load_json(args.tileset_manifest.expanduser().resolve(), "parityTilesets")
    if source_revision(disassembly) != tilesets["disassemblyCommit"]:
        fail("tileset manifest and disassembly checkout use different commits")
    if assets["disassemblyCommit"] != tilesets["disassemblyCommit"]:
        fail("asset and tileset manifests use different disassembly commits")

    graphics_4bpp = build_4bpp_records(disassembly, tilesets)
    bg3_asset = one_by_name(assets["assets"], STANDARD_BG3_LABEL)
    bg3_data = (disassembly / str(bg3_asset["asset"])).read_bytes()
    if len(bg3_data) != 256 * 16:
        fail(f"{STANDARD_BG3_LABEL} must contain 256 tiles, found {len(bg3_data) // 16}")
    bg3_pixels = decode_standard_2bpp(bg3_data)
    graphics_2bpp = [
        {
            "sourceLabel": STANDARD_BG3_LABEL,
            "asset": bg3_asset["asset"],
            "address": bg3_asset["address"],
            "snesAddress": bg3_asset["snesAddress"],
            "layout": "standard-2bpp",
            "size": len(bg3_data),
            "tileCount": len(bg3_data) // 16,
            **pixel_record(bg3_pixels),
        }
    ]
    metatile_tables = build_metatile_records(disassembly, tilesets)

    totals = {
        "graphics4bppResourceCount": len(graphics_4bpp),
        "tilesetGraphics4bppResourceCount": sum(
            record["role"] == "tileset" for record in graphics_4bpp
        ),
        "creGraphics4bppResourceCount": sum(record["role"] == "cre" for record in graphics_4bpp),
        "standard4bppResourceCount": sum(
            record["layout"] == "standard-4bpp" for record in graphics_4bpp
        ),
        "splitPlane4bppResourceCount": sum(
            record["layout"] == "global-split-plane-4bpp" for record in graphics_4bpp
        ),
        "shortStandard4bppResourceCount": sum(
            int(record["undefinedReservedTileCount"]) > 0 for record in graphics_4bpp
        ),
        "graphics4bppTileCount": sum(int(record["tileCount"]) for record in graphics_4bpp),
        "graphics2bppResourceCount": len(graphics_2bpp),
        "graphics2bppTileCount": sum(int(record["tileCount"]) for record in graphics_2bpp),
        "metatileTableResourceCount": len(metatile_tables),
        "metatileCount": sum(int(record["metatileCount"]) for record in metatile_tables),
        "metatileWordCount": sum(int(record["wordCount"]) for record in metatile_tables),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": source_revision(disassembly),
        "oracle": "Independent Python SNES planar decoder and metatile bitfield parser",
        "totals": totals,
        "aggregateHashes": {
            "graphics4bppPixels": aggregate_hash(graphics_4bpp, "pixelSha256"),
            "graphics2bppPixels": aggregate_hash(graphics_2bpp, "pixelSha256"),
            "metatileSemantics": aggregate_hash(metatile_tables, "semanticSha256"),
        },
        "graphics4bpp": graphics_4bpp,
        "graphics2bpp": graphics_2bpp,
        "metatileTables": metatile_tables,
    }
    output_path = args.output.expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Tile-format oracle complete")
    print(
        f"  4bpp: {totals['graphics4bppResourceCount']} resources, "
        f"{totals['graphics4bppTileCount']} decoded tiles"
    )
    print(
        f"  2bpp: {totals['graphics2bppResourceCount']} resource, "
        f"{totals['graphics2bppTileCount']} decoded tiles"
    )
    print(
        f"  Metatiles: {totals['metatileTableResourceCount']} resources, "
        f"{totals['metatileCount']} entries / {totals['metatileWordCount']} words"
    )
    print(f"  Output: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
