#!/usr/bin/env python3
"""Build Samus's exact pose, DMA, spritemap, palette, and asset-ownership manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, Sequence


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT_DIR = PARITY_DIR / "reports"
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "samus.json"

POSE_COUNT = 0xFD
DMA_ENTRY_SIZE = 7
SPRITEMAP_ENTRY_SIZE = 5
ANIMATION_DEFINITIONS_END = 0x92ED24
DMA_TABLES_END = 0x92D7D3
ANIMATION_DELAYS_END = 0x91B5D1

ANCHORS = {
    "animationDelayPointers": ("AnimationDelayTable", 0x91B010),
    "spritemapPointers": ("SamusSpritemapTable", 0x92808D),
    "topSpritemapIndices": ("SamusSpritemapTableIndices_TopHalf", 0x929263),
    "bottomSpritemapIndices": ("SamusSpritemapTableIndices_BottomHalf", 0x92945D),
    "topDmaPointers": ("SamusTopHalfTilesAnimation_TilesDefinitionPointers", 0x92D91E),
    "bottomDmaPointers": ("SamusBottomHalfTilesAnimation_TilesDefinitionPointers", 0x92D938),
    "animationDefinitionPointers": ("SamusTilesAnimation_AnimationDefinitionPointers", 0x92D94E),
}

PALETTE_LABELS = (
    "SamusPalettes_PowerSuit",
    "SamusPalettes_VariaSuit",
    "SamusPalettes_GravitySuit",
)
DEFAULT_VRAM_SOURCE = 0x9AD200
DEFAULT_VRAM_SIZE = 0x2000
WEAPON_TILES_SOURCE = 0x9AF200
WEAPON_TILES_SIZE = 0x100
WEAPON_TILES_DESTINATION = 0x30 * 32
VRAM_SIZE = 512 * 32


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def read_word(data: bytes, snes_address: int) -> int:
    offset = snes_to_pc(snes_address)
    return data[offset] | data[offset + 1] << 8


def read_long_at_pc(data: bytes, offset: int) -> int:
    return data[offset] | data[offset + 1] << 8 | data[offset + 2] << 16


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[object]) -> str:
    payload = json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(payload)


def source_label(
    labels_by_address: Dict[int, list[str]],
    address: int,
    prefixes: Iterable[str],
) -> str:
    labels = labels_by_address.get(address, [])
    for prefix in prefixes:
        for label in labels:
            if label.startswith(prefix):
                return label
    if labels:
        return labels[0]
    fail(f"no source label at ${address >> 16:02X}:{address & 0xFFFF:04X}")


def tilemap_hash(entries: Sequence[Dict[str, object]]) -> str:
    raw = bytearray()
    for entry in entries:
        raw.extend(int(entry["xOffset"]).to_bytes(2, "little", signed=True))
        raw.extend(int(entry["yOffset"]).to_bytes(2, "little", signed=True))
        raw.extend(int(entry["tileNumber"]).to_bytes(2, "little"))
        raw.append(int(entry["palette"]))
        raw.append(
            (1 if entry["xFlip"] else 0)
            | (2 if entry["yFlip"] else 0)
            | (4 if entry["is16x16"] else 0)
        )
    return sha256(bytes(raw))


def parse_spritemap(rom: bytes, address: int, label: str) -> Dict[str, object]:
    offset = snes_to_pc(address)
    count = rom[offset] | rom[offset + 1] << 8
    if count > 128 or offset + 2 + count * SPRITEMAP_ENTRY_SIZE > len(rom):
        fail(f"invalid Samus spritemap {label} at ${address:06X}: {count} entries")
    raw = rom[offset : offset + 2 + count * SPRITEMAP_ENTRY_SIZE]
    entries = []
    for index in range(count):
        base = offset + 2 + index * SPRITEMAP_ENTRY_SIZE
        position = rom[base] | rom[base + 1] << 8
        attributes = rom[base + 3] | rom[base + 4] << 8
        x_offset = position & 0x1FF
        if x_offset >= 0x100:
            x_offset -= 0x200
        y_offset = rom[base + 2]
        if y_offset >= 0x80:
            y_offset -= 0x100
        entries.append(
            {
                "xOffset": x_offset,
                "yOffset": y_offset,
                "tileNumber": attributes & 0x1FF,
                "palette": (attributes >> 9) & 7,
                "priority": (attributes >> 12) & 3,
                "xFlip": bool(attributes & 0x4000),
                "yFlip": bool(attributes & 0x8000),
                "is16x16": bool(position & 0x8000),
            }
        )
    return {
        "sourceLabel": label,
        "snesAddress": address,
        "entryCount": count,
        "size": len(raw),
        "rawSha256": sha256(raw),
        "entries": entries,
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
    symbols_path = report_dir / "symbols.json"
    assets_path = report_dir / "assets.json"
    if not rom_path.is_file() or not symbols_path.is_file() or not assets_path.is_file():
        fail("missing reference ROM or prerequisite reports; run ./gradlew paritySamus")

    rom = rom_path.read_bytes()
    symbols_report = json.loads(symbols_path.read_text(encoding="utf-8"))
    assets_report = json.loads(assets_path.read_text(encoding="utf-8"))
    if symbols_report["disassemblyCommit"] != assets_report["disassemblyCommit"]:
        fail("Samus prerequisite reports belong to different disassembly revisions")

    symbols = {record["name"]: int(record["snesAddress"]) for record in symbols_report["symbols"]}
    labels_by_address: Dict[int, list[str]] = defaultdict(list)
    for record in symbols_report["symbols"]:
        labels_by_address[int(record["snesAddress"])].append(str(record["name"]))

    anchors = {}
    for key, (label, expected_address) in ANCHORS.items():
        actual_address = symbols.get(label)
        if actual_address != expected_address:
            fail(f"{label} moved: expected ${expected_address:06X}, got {actual_address!r}")
        anchors[key] = {"sourceLabel": label, "snesAddress": actual_address}

    samus_assets = [
        record for record in assets_report["assets"] if record["category"] == "samus-tiles"
    ]
    assets_by_range = {
        (int(record["snesAddress"]), int(record["size"])): record for record in samus_assets
    }

    dma_specs = (
        ("top", anchors["topDmaPointers"]["snesAddress"], 13),
        ("bottom", anchors["bottomDmaPointers"]["snesAddress"], 11),
    )
    dma_starts = []
    for _, pointer_base, count in dma_specs:
        dma_starts.extend(
            0x920000 + read_word(rom, pointer_base + index * 2) for index in range(count)
        )
    sorted_dma_starts = sorted(set(dma_starts) | {DMA_TABLES_END})
    if len(sorted_dma_starts) != 25:
        fail(f"expected 24 distinct Samus DMA tables, got {len(sorted_dma_starts) - 1}")

    dma_tables = []
    dma_by_half_and_index: Dict[tuple[str, int], Dict[str, object]] = {}
    dma_entry_by_address: Dict[int, Dict[str, object]] = {}
    owned_asset_names = set()
    for half, pointer_base, table_count in dma_specs:
        for table_index in range(table_count):
            start = 0x920000 + read_word(rom, pointer_base + table_index * 2)
            end = sorted_dma_starts[sorted_dma_starts.index(start) + 1]
            if (end - start) % DMA_ENTRY_SIZE:
                fail(f"{half} DMA table {table_index:X} is not a whole number of entries")
            entries = []
            for entry_index in range((end - start) // DMA_ENTRY_SIZE):
                entry_address = start + entry_index * DMA_ENTRY_SIZE
                offset = snes_to_pc(entry_address)
                source_address = read_long_at_pc(rom, offset)
                row1_size = rom[offset + 3] | rom[offset + 4] << 8
                row2_size = rom[offset + 5] | rom[offset + 6] << 8
                total_size = row1_size + row2_size
                asset = assets_by_range.get((source_address, total_size))
                if asset is None:
                    fail(
                        f"{half} DMA {table_index:X}:{entry_index:X} does not exactly own a "
                        f"named Samus asset at ${source_address:06X} ({total_size} bytes)"
                    )
                owned_asset_names.add(str(asset["name"]))
                entries.append(
                    {
                        "index": entry_index,
                        "sourceLabel": source_label(
                            labels_by_address,
                            entry_address,
                            ("SamusTopTiles_", "SamusBottomTiles_", "UNUSED_Samus"),
                        ),
                        "snesAddress": entry_address,
                        "sourceSnesAddress": source_address,
                        "row1Size": row1_size,
                        "row2Size": row2_size,
                        "size": total_size,
                        "assetName": asset["name"],
                        "assetPath": asset["asset"],
                        "assetSha256": asset["sha256"],
                    }
                )
                dma_entry_by_address[entry_address] = entries[-1]
            table = {
                "half": half,
                "index": table_index,
                "sourceLabel": source_label(
                    labels_by_address,
                    start,
                    ("SamusTopTiles_", "SamusBottomTiles_", "UNUSED_Samus"),
                ),
                "snesAddress": start,
                "size": end - start,
                "entryCount": len(entries),
                "entries": entries,
            }
            dma_tables.append(table)
            dma_by_half_and_index[(half, table_index)] = table

    all_asset_names = {str(record["name"]) for record in samus_assets}
    if owned_asset_names != all_asset_names:
        missing = sorted(all_asset_names - owned_asset_names)
        extra = sorted(owned_asset_names - all_asset_names)
        fail(f"Samus DMA/asset ownership is not bijective; missing={missing}, extra={extra}")

    definition_starts = sorted(
        {
            int(record["snesAddress"])
            for record in symbols_report["symbols"]
            if str(record["name"]).startswith(
                (
                    "SamusTilesAnimation_AnimationDefinitions_",
                    "UNUSED_SamusTilesAnimation_AnimationDefinitions_",
                )
            )
        }
    )
    definition_ends = dict(
        zip(definition_starts, definition_starts[1:] + [ANIMATION_DEFINITIONS_END])
    )
    if len(definition_starts) != 156:
        fail(f"expected 156 unique Samus animation definitions, got {len(definition_starts)}")
    for start, end in definition_ends.items():
        if (end - start) % 4:
            fail(f"animation definition at ${start:06X} is not aligned to four-byte frames")

    delay_starts = sorted(
        {
            0x910000
            + read_word(rom, anchors["animationDelayPointers"]["snesAddress"] + pose * 2)
            for pose in range(POSE_COUNT)
        }
    )
    delay_ends = dict(zip(delay_starts, delay_starts[1:] + [ANIMATION_DELAYS_END]))
    animation_delays = []
    for start in delay_starts:
        end = delay_ends[start]
        raw = rom[snes_to_pc(start) : snes_to_pc(end)]
        animation_delays.append(
            {
                "sourceLabel": source_label(
                    labels_by_address,
                    start,
                    ("AnimationDelays_", "UNUSED_AnimationDelays_"),
                ),
                "snesAddress": start,
                "size": end - start,
                "rawSha256": sha256(raw),
            }
        )

    spritemaps_by_address: Dict[int, Dict[str, object]] = {}
    null_spritemap_lookup_count = 0
    animation_definition_pointer_base = anchors["animationDefinitionPointers"]["snesAddress"]
    spritemap_pointer_base = anchors["spritemapPointers"]["snesAddress"]
    top_spritemap_index_base = anchors["topSpritemapIndices"]["snesAddress"]
    bottom_spritemap_index_base = anchors["bottomSpritemapIndices"]["snesAddress"]
    animations = []

    def spritemap_for_lookup(index: int, frame_index: int) -> Dict[str, object] | None:
        nonlocal null_spritemap_lookup_count
        pointer_address = spritemap_pointer_base + index * 2 + frame_index * 2
        if not (spritemap_pointer_base <= pointer_address < top_spritemap_index_base):
            fail(f"Samus spritemap pointer lookup escaped its table at ${pointer_address:06X}")
        pointer = read_word(rom, pointer_address)
        if pointer == 0:
            null_spritemap_lookup_count += 1
            return None
        address = 0x920000 + pointer
        if address not in spritemaps_by_address:
            label = source_label(labels_by_address, address, ("SamusSpritemaps_", "UNUSED_"))
            spritemaps_by_address[address] = parse_spritemap(rom, address, label)
        return spritemaps_by_address[address]

    for pose_id in range(POSE_COUNT):
        definition_address = 0x920000 + read_word(
            rom, animation_definition_pointer_base + pose_id * 2
        )
        definition_end = definition_ends.get(definition_address)
        if definition_end is None:
            fail(f"pose ${pose_id:02X} points to an unlabeled definition ${definition_address:06X}")
        definition_label = source_label(
            labels_by_address,
            definition_address,
            (
                "SamusTilesAnimation_AnimationDefinitions_",
                "UNUSED_SamusTilesAnimation_AnimationDefinitions_",
            ),
        )
        frame_count = (definition_end - definition_address) // 4
        delay_address = 0x910000 + read_word(
            rom, anchors["animationDelayPointers"]["snesAddress"] + pose_id * 2
        )
        top_spritemap_index = read_word(rom, top_spritemap_index_base + pose_id * 2)
        bottom_spritemap_index = read_word(rom, bottom_spritemap_index_base + pose_id * 2)
        frames = []
        definition_offset = snes_to_pc(definition_address)
        for frame_index in range(frame_count):
            offset = definition_offset + frame_index * 4
            top_table_index, top_entry_index, bottom_table_index, bottom_entry_index = rom[
                offset : offset + 4
            ]
            try:
                top_table = dma_by_half_and_index[("top", top_table_index)]
                bottom_table = dma_by_half_and_index[("bottom", bottom_table_index)]
            except KeyError:
                fail(
                    f"pose ${pose_id:02X} frame {frame_index:X} selects invalid DMA "
                    f"top={top_table_index:X}:{top_entry_index:X}, "
                    f"bottom={bottom_table_index:X}:{bottom_entry_index:X}"
                )
            top_entry_address = int(top_table["snesAddress"]) + top_entry_index * DMA_ENTRY_SIZE
            bottom_entry_address = (
                int(bottom_table["snesAddress"]) + bottom_entry_index * DMA_ENTRY_SIZE
            )
            top_entry = dma_entry_by_address.get(top_entry_address)
            bottom_entry = dma_entry_by_address.get(bottom_entry_address)
            if top_entry is None or bottom_entry is None:
                fail(
                    f"pose ${pose_id:02X} frame {frame_index:X} selects DMA outside the "
                    f"complete definition region: top=${top_entry_address:06X}, "
                    f"bottom=${bottom_entry_address:06X}"
                )

            top_map = spritemap_for_lookup(top_spritemap_index, frame_index)
            bottom_map = spritemap_for_lookup(bottom_spritemap_index, frame_index)
            # Production appends bottom then top and reverses the complete list.
            combined_entries = []
            if bottom_map is not None:
                combined_entries.extend(bottom_map["entries"])
            if top_map is not None:
                combined_entries.extend(top_map["entries"])
            combined_entries.reverse()

            vram = bytearray(VRAM_SIZE)
            default_vram_offset = snes_to_pc(DEFAULT_VRAM_SOURCE)
            vram[:DEFAULT_VRAM_SIZE] = rom[
                default_vram_offset : default_vram_offset + DEFAULT_VRAM_SIZE
            ]
            weapon_offset = snes_to_pc(WEAPON_TILES_SOURCE)
            vram[
                WEAPON_TILES_DESTINATION : WEAPON_TILES_DESTINATION + WEAPON_TILES_SIZE
            ] = rom[weapon_offset : weapon_offset + WEAPON_TILES_SIZE]
            for entry, first_tile in ((bottom_entry, 0x08), (top_entry, 0x00)):
                source_offset = snes_to_pc(int(entry["sourceSnesAddress"]))
                row1_size = int(entry["row1Size"])
                row2_size = int(entry["row2Size"])
                first_destination = first_tile * 32
                second_destination = (0x10 + first_tile) * 32
                vram[first_destination : first_destination + row1_size] = rom[
                    source_offset : source_offset + row1_size
                ]
                vram[second_destination : second_destination + row2_size] = rom[
                    source_offset + row1_size : source_offset + row1_size + row2_size
                ]

            frames.append(
                {
                    "index": frame_index,
                    "topDmaTable": top_table_index,
                    "topDmaEntry": top_entry_index,
                    "bottomDmaTable": bottom_table_index,
                    "bottomDmaEntry": bottom_entry_index,
                    "topDmaSnesAddress": top_entry_address,
                    "bottomDmaSnesAddress": bottom_entry_address,
                    "topCrossesNamedTable": top_entry_index >= int(top_table["entryCount"]),
                    "bottomCrossesNamedTable": bottom_entry_index >= int(bottom_table["entryCount"]),
                    "topAssetName": top_entry["assetName"],
                    "bottomAssetName": bottom_entry["assetName"],
                    "topSpritemapSnesAddress": None if top_map is None else top_map["snesAddress"],
                    "bottomSpritemapSnesAddress": (
                        None if bottom_map is None else bottom_map["snesAddress"]
                    ),
                    "combinedEntryCount": len(combined_entries),
                    "combinedTilemapSha256": tilemap_hash(combined_entries),
                    "vramSha256": sha256(bytes(vram)),
                }
            )

        raw = rom[snes_to_pc(definition_address) : snes_to_pc(definition_end)]
        animations.append(
            {
                "poseId": pose_id,
                "definitionSourceLabel": definition_label,
                "definitionSnesAddress": definition_address,
                "definitionSha256": sha256(raw),
                "frameCount": frame_count,
                "animationDelaySourceLabel": source_label(
                    labels_by_address,
                    delay_address,
                    ("AnimationDelays_", "UNUSED_AnimationDelays_"),
                ),
                "animationDelaySnesAddress": delay_address,
                "topSpritemapIndex": top_spritemap_index,
                "bottomSpritemapIndex": bottom_spritemap_index,
                "frames": frames,
            }
        )

    palettes = []
    for label in PALETTE_LABELS:
        address = symbols.get(label)
        if address is None:
            fail(f"missing source palette {label}")
        raw = rom[snes_to_pc(address) : snes_to_pc(address) + 0x20]
        palettes.append(
            {
                "sourceLabel": label,
                "snesAddress": address,
                "size": len(raw),
                "rawSha256": sha256(raw),
            }
        )

    spritemaps = [spritemaps_by_address[address] for address in sorted(spritemaps_by_address)]
    unique_definition_frames = sum(
        (definition_ends[start] - start) // 4 for start in definition_starts
    )
    totals = {
        "poseCount": len(animations),
        "uniqueAnimationDefinitionCount": len(definition_starts),
        "uniqueAnimationDefinitionFrameCount": unique_definition_frames,
        "animationDefinitionFrameOccurrenceCount": sum(
            int(record["frameCount"]) for record in animations
        ),
        "uniqueAnimationDelayCount": len(animation_delays),
        "dmaTableCount": len(dma_tables),
        "dmaEntryCount": sum(int(record["entryCount"]) for record in dma_tables),
        "tileAssetCount": len(samus_assets),
        "tileAssetByteCount": sum(int(record["size"]) for record in samus_assets),
        "spritemapCount": len(spritemaps),
        "spritemapEntryCount": sum(int(record["entryCount"]) for record in spritemaps),
        "nullSpritemapLookupCount": null_spritemap_lookup_count,
        "paletteCount": len(palettes),
    }
    expected_totals = {
        "poseCount": 253,
        "uniqueAnimationDefinitionCount": 156,
        "uniqueAnimationDefinitionFrameCount": 1143,
        "animationDefinitionFrameOccurrenceCount": 1982,
        "dmaTableCount": 24,
        "dmaEntryCount": 435,
        "tileAssetCount": 435,
        "paletteCount": 3,
    }
    for key, expected in expected_totals.items():
        if totals[key] != expected:
            fail(f"Samus {key} changed: expected {expected}, got {totals[key]}")

    compact_dma = [
        {
            "half": table["half"],
            "index": table["index"],
            "sourceLabel": table["sourceLabel"],
            "entries": [
                {
                    "sourceLabel": entry["sourceLabel"],
                    "sourceSnesAddress": entry["sourceSnesAddress"],
                    "row1Size": entry["row1Size"],
                    "row2Size": entry["row2Size"],
                    "assetName": entry["assetName"],
                    "assetSha256": entry["assetSha256"],
                }
                for entry in table["entries"]
            ],
        }
        for table in dma_tables
    ]
    compact_animations = [
        {
            "poseId": record["poseId"],
            "definitionSourceLabel": record["definitionSourceLabel"],
            "definitionSha256": record["definitionSha256"],
            "animationDelaySnesAddress": record["animationDelaySnesAddress"],
            "topSpritemapIndex": record["topSpritemapIndex"],
            "bottomSpritemapIndex": record["bottomSpritemapIndex"],
            "frames": record["frames"],
        }
        for record in animations
    ]
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": symbols_report["disassemblyCommit"],
        "romSha256": sha256(rom),
        "anchors": anchors,
        "totals": totals,
        "aggregateHashes": {
            "dma": aggregate_hash(compact_dma),
            "animationDelays": aggregate_hash(animation_delays),
            "animations": aggregate_hash(compact_animations),
            "spritemaps": aggregate_hash(spritemaps),
            "palettes": aggregate_hash(palettes),
        },
        "dmaTables": dma_tables,
        "animationDelays": animation_delays,
        "animations": animations,
        "spritemaps": spritemaps,
        "palettes": palettes,
    }
    output_path = args.output.expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        f"Wrote {output_path}: {totals['poseCount']} poses / "
        f"{totals['animationDefinitionFrameOccurrenceCount']} definition frames, "
        f"{totals['dmaEntryCount']} exact DMA assets, "
        f"{totals['spritemapCount']} spritemaps"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
