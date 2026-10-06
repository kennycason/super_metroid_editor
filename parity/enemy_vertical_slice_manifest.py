#!/usr/bin/env python3
"""Build source-backed end-to-end fixtures for three ordinary enemy slices."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Dict, List, Sequence, Tuple


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT_DIR = PARITY_DIR / "reports"
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "enemy-vertical-slices.json"

SLICE_SPECS = (
    {
        "key": "zoomer",
        "displayName": "Zoomer",
        "speciesId": 0xDCFF,
        "headerLabel": "EnemyHeaders_Zoomer",
        "entryListLabel": "InstList_Zeela_Zoomer_UpsideRight_0",
        "pathListLabels": (
            "InstList_Zeela_Zoomer_UpsideRight_0",
            "InstList_Zeela_Zoomer_UpsideRight_1",
        ),
        "termination": "LOOP",
        "terminalListLabel": "InstList_Zeela_Zoomer_UpsideRight_1",
    },
    {
        "key": "sidehopper",
        "displayName": "Sidehopper",
        "speciesId": 0xD93F,
        "headerLabel": "EnemyHeaders_Sidehopper",
        "entryListLabel": "InstList_Sidehopper_Landed_UpsideUp",
        "pathListLabels": ("InstList_Sidehopper_Landed_UpsideUp",),
        "termination": "SLEEP",
        "terminalRecordAddress": 0xA3AA9A,
    },
    {
        "key": "space-pirate-walking",
        "displayName": "Grey Walking Space Pirate",
        "speciesId": 0xF653,
        "headerLabel": "EnemyHeaders_PirateGreyWalking",
        "entryListLabel": "InstList_PirateWalking_WalkingLeft_0",
        "pathListLabels": (
            "InstList_PirateWalking_WalkingLeft_0",
            "InstList_PirateWalking_WalkingLeft_1",
        ),
        "termination": "LOOP",
        "terminalListLabel": "InstList_PirateWalking_WalkingLeft_1",
    },
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def read_bytes(rom: bytes, address: int, size: int) -> bytes:
    pc = snes_to_pc(address)
    if pc < 0 or pc + size > len(rom):
        fail(f"read outside reference ROM: {address:06X} + {size:X}")
    return rom[pc : pc + size]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    encoded = json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded)


def flatten_entries(
    address: int,
    standard: Dict[int, Dict[str, object]],
    extended: Dict[int, Dict[str, object]],
) -> Tuple[str, List[Dict[str, object]]]:
    if address in standard:
        record = standard[address]
        return "standard-oam", [dict(entry) for entry in record["entries"]]
    if address not in extended:
        fail(f"vertical-slice frame has no named OAM structure: {address:06X}")
    result: List[Dict[str, object]] = []
    for child in extended[address]["children"]:
        if child["childType"] != "standard-oam":
            fail(f"vertical-slice frame unexpectedly uses an extended tilemap: {address:06X}")
        child_address = int(child["childSnesAddress"])
        child_record = standard.get(child_address)
        if child_record is None:
            fail(f"extended child has no named standard OAM record: {child_address:06X}")
        for entry in child_record["entries"]:
            flattened = dict(entry)
            flattened["xOffset"] = int(flattened["xOffset"]) + int(child["xOffset"])
            flattened["yOffset"] = int(flattened["yOffset"]) + int(child["yOffset"])
            result.append(flattened)
    return "extended-spritemap", result


def entry_semantics(entry: Dict[str, object]) -> Dict[str, object]:
    return {
        "xOffset": int(entry["xOffset"]),
        "yOffset": int(entry["yOffset"]),
        "tileNumber": int(entry["tileNumber"]),
        "paletteRow": int(entry["paletteRow"]),
        "priority": int(entry["priority"]),
        "hFlip": bool(entry["hFlip"]),
        "vFlip": bool(entry["vFlip"]),
        "is16x16": bool(entry["is16x16"]),
    }


def frame_geometry(entries: Sequence[Dict[str, object]]) -> Dict[str, object]:
    if not entries:
        fail("vertical-slice frame has no flattened OAM entries")
    semantic_entries = [entry_semantics(entry) for entry in entries]
    min_x = min(int(entry["xOffset"]) for entry in semantic_entries)
    min_y = min(int(entry["yOffset"]) for entry in semantic_entries)
    max_x = max(
        int(entry["xOffset"]) + (16 if bool(entry["is16x16"]) else 8)
        for entry in semantic_entries
    )
    max_y = max(
        int(entry["yOffset"]) + (16 if bool(entry["is16x16"]) else 8)
        for entry in semantic_entries
    )
    return {
        "flattenedOamEntryCount": len(semantic_entries),
        "flattenedOamEntries": semantic_entries,
        "minX": min_x,
        "minY": min_y,
        "width": max_x - min_x,
        "height": max_y - min_y,
        "flattenedOamSha256": aggregate_hash(semantic_entries),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disassembly", type=Path, default=DEFAULT_DISASSEMBLY)
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    disassembly = args.disassembly.expanduser().resolve()
    report_dir = args.report_dir.expanduser().resolve()
    rom_path = disassembly / "SM.sfc"
    input_paths = {
        "headers": report_dir / "enemy-headers.json",
        "instructions": report_dir / "enemy-instructions.json",
        "oam": report_dir / "enemy-oam.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in input_paths.values()):
        fail("missing reference ROM or enemy reports; run ./gradlew parityEnemyInstructions")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in input_paths.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("enemy reports belong to different disassembly revisions")

    headers = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    lists = {record["sourceLabel"]: record for record in reports["instructions"]["lists"]}
    standard = {
        int(record["snesAddress"]): record for record in reports["oam"]["standardSpritemaps"]
    }
    extended = {
        int(record["snesAddress"]): record for record in reports["oam"]["extendedSpritemaps"]
    }

    slices: List[Dict[str, object]] = []
    for spec in SLICE_SPECS:
        header = headers.get(str(spec["headerLabel"]))
        if header is None or int(header["speciesId"]) != int(spec["speciesId"]):
            fail(f"missing or changed source header for {spec['displayName']}")
        path = [lists.get(label) for label in spec["pathListLabels"]]
        if any(record is None for record in path):
            fail(f"missing source list in {spec['displayName']} path")
        typed_path = [record for record in path if record is not None]
        for left, right in zip(typed_path, typed_path[1:]):
            if int(left["snesAddress"]) + int(left["size"]) != int(right["snesAddress"]):
                fail(f"{spec['displayName']} path no longer falls through contiguously")

        source_frames: List[Dict[str, object]] = []
        handler_sequence: List[Dict[str, object]] = []
        for list_record in typed_path:
            for record in list_record["records"]:
                if record["kind"] == "handler":
                    handler_sequence.append(
                        {
                            "sourceListLabel": list_record["sourceLabel"],
                            "recordSnesAddress": int(record["snesAddress"]),
                            "handlerSnesAddress": int(record["handlerSnesAddress"]),
                            "sourceToken": record["sourceToken"],
                            "operandByteCount": int(record["operandByteCount"]),
                            "controlFlow": record["controlFlow"],
                        }
                    )
                    continue
                spritemap_address = int(record["spritemapSnesAddress"])
                structure_type, entries = flatten_entries(spritemap_address, standard, extended)
                source_frames.append(
                    {
                        "sourceListLabel": list_record["sourceLabel"],
                        "recordSnesAddress": int(record["snesAddress"]),
                        "duration": int(record["duration"]),
                        "spritemapSnesAddress": spritemap_address,
                        "spritemapLabels": record["spritemapLabels"],
                        "structureType": structure_type,
                        **frame_geometry(entries),
                    }
                )

        fields = header["fields"]
        ai_bank = int(fields["bank"]["value"])
        palette_address = (ai_bank << 16) | int(fields["palette"]["value"])
        tile_address = int(fields["tileData"]["value"])
        tile_size = int(fields["tileDataSize"]["value"]) & 0x7FFF
        entry = lists.get(str(spec["entryListLabel"]))
        if entry is None:
            fail(f"missing entry list for {spec['displayName']}")
        if "terminalListLabel" in spec:
            terminal = lists[str(spec["terminalListLabel"])]
            terminal_address = int(terminal["snesAddress"])
        else:
            terminal_address = int(spec["terminalRecordAddress"])

        slices.append(
            {
                "key": spec["key"],
                "displayName": spec["displayName"],
                "speciesId": int(spec["speciesId"]),
                "sourceHeaderLabel": header["sourceLabel"],
                "headerSnesAddress": int(header["snesAddress"]),
                "aiBank": ai_bank,
                "entryListLabel": entry["sourceLabel"],
                "entryListSnesAddress": int(entry["snesAddress"]),
                "pathListLabels": list(spec["pathListLabels"]),
                "expectedTermination": spec["termination"],
                "expectedTerminalSnesAddress": terminal_address,
                "tileDataSnesAddress": tile_address,
                "tileDataSize": tile_size,
                "tileDataSha256": sha256(read_bytes(rom, tile_address, tile_size)),
                "paletteSnesAddress": palette_address,
                "paletteSha256": sha256(read_bytes(rom, palette_address, 32)),
                "frames": source_frames,
                "handlers": handler_sequence,
            }
        )

    totals = {
        "sliceCount": len(slices),
        "frameOccurrenceCount": sum(len(record["frames"]) for record in slices),
        "uniqueFrameSpritemapCount": len(
            {
                int(frame["spritemapSnesAddress"])
                for record in slices
                for frame in record["frames"]
            }
        ),
        "handlerOccurrenceCount": sum(len(record["handlers"]) for record in slices),
        "standardFrameOccurrenceCount": sum(
            frame["structureType"] == "standard-oam"
            for record in slices
            for frame in record["frames"]
        ),
        "extendedFrameOccurrenceCount": sum(
            frame["structureType"] == "extended-spritemap"
            for record in slices
            for frame in record["frames"]
        ),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "oracle": "source-derived headers, instruction records, OAM, and rebuilt-ROM assets",
        "totals": totals,
        "aggregateHashes": {"slices": aggregate_hash(slices)},
        "slices": slices,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Enemy vertical-slice manifest valid")
    print(
        f"  Slices: {totals['sliceCount']} / frames: {totals['frameOccurrenceCount']} "
        f"({totals['uniqueFrameSpritemapCount']} unique) / handlers: "
        f"{totals['handlerOccurrenceCount']}"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
