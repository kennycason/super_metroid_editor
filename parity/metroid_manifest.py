#!/usr/bin/env python3
"""Build the normal Metroid's three-owner OAM, timing, palette, and pixel manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "metroid.json"

INSIDE_LABELS = tuple(f"Spritemap_Metroid_Insides_{index}" for index in range(4))
SHELL_LABELS = (
    "SpriteObjectSpritemaps_34_35_36_MetroidShell_0",
    "SpriteObjectSpritemaps_34_36_MetroidShell_1",
    "SpriteObjectSpritemaps_34_35_36_MetroidShell_2",
)
ELECTRICITY_LABELS = tuple(
    f"SpriteObjectSpritemaps_32_33_MetroidElectricity_{index:X}"
    if index not in (0xC, 0xD, 0xE, 0x12, 0x13, 0x14)
    else f"SpriteObjectSpritemaps_32_MetroidElectricity_{index:X}"
    for index in range(0x18)
)
TRACKS = (
    ("electricity-intro", "InstList_SpriteObject_32_MetroidElectricity", "UNUSED_InstList_SpriteObject_33_B4C436", False),
    ("electricity-steady", "UNUSED_InstList_SpriteObject_33_B4C436", "InstList_SpriteObject_34_MetroidShell", True),
    ("shell-intro", "InstList_SpriteObject_34_MetroidShell", "UNUSED_InstList_SpriteObject_35_B4C536", False),
    ("shell-steady", "UNUSED_InstList_SpriteObject_35_B4C536", "UNUSED_InstList_SpriteObject_36_B4C5B2", True),
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8"))


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
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityMetroid")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Metroid prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    header = next(
        (record for record in reports["headers"]["headers"] if record["sourceLabel"] == "EnemyHeaders_Metroid"),
        None,
    )
    if header is None or int(header["speciesId"]) != 0xDD7F:
        fail("missing or changed normal Metroid header")
    fields = header["fields"]
    if int(fields["tileDataSize"]["value"]) != 0x1000:
        fail("Metroid tile transfer size changed")
    if fields["tileData"]["targetLabel"] != "Tiles_Metroid":
        fail("Metroid pixel ownership changed")
    if fields["palette"]["targetLabel"] != "Palette_Metroid" or int(fields["bank"]["value"]) != 0xA3:
        fail("Metroid palette or AI-bank ownership changed")
    headers = [{
        "sourceLabel": "EnemyHeaders_Metroid",
        "speciesId": 0xDD7F,
        "headerSnesAddress": int(header["snesAddress"]),
        "tileDataSize": 0x1000,
        "tileDataSnesAddress": int(fields["tileData"]["value"]),
        "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
        "aiBank": int(fields["bank"]["value"]),
        "rawHeaderSha256": header["rawHeaderSha256"],
    }]

    asset = next((record for record in reports["assets"]["assets"] if record["name"] == "Tiles_Metroid"), None)
    if asset is None or int(asset["snesAddress"]) != address("Tiles_Metroid") or int(asset["size"]) != 0x1000:
        fail("Tiles_Metroid ownership or size changed")
    assets = [{
        "sourceLabel": "Tiles_Metroid",
        "role": "shared-insides-shell-electricity-obj",
        "snesAddress": int(asset["snesAddress"]),
        "size": int(asset["size"]),
        "sha256": asset["sha256"],
        "asset": asset["asset"],
    }]

    source_oam = {record["sourceLabel"]: record for record in reports["oam"]["standardSpritemaps"]}
    inside_maps = []
    for label in INSIDE_LABELS:
        record = source_oam.get(label)
        if record is None or bool(record["sourceDeclaredUnused"]):
            fail(f"missing active inside map {label}")
        inside_maps.append({
            "sourceLabel": label,
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "entryCount": int(record["entryCount"]),
            "sha256": record["rawSha256"],
        })

    def sprite_map(label: str, role: str) -> Dict[str, object]:
        snes_address = address(label)
        pc = snes_to_pc(snes_address)
        count = int.from_bytes(rom[pc:pc + 2], "little")
        if count not in range(1, 17):
            fail(f"{label} has unexpected OAM entry count {count}")
        raw = rom[pc:pc + 2 + count * 5]
        return {
            "sourceLabel": label,
            "role": role,
            "snesAddress": snes_address,
            "size": len(raw),
            "entryCount": count,
            "sha256": sha256(raw),
        }

    shell_maps = [sprite_map(label, "shell") for label in SHELL_LABELS]
    electricity_maps = [sprite_map(label, "electricity") for label in ELECTRICITY_LABELS]

    source_lists = {record["sourceLabel"]: record for record in reports["instructions"]["lists"]}
    enemy_lists = []
    for label in ("InstList_Metroid_ChasingSamus", "InstList_Metroid_DrainingSamus"):
        record = source_lists.get(label)
        if record is None:
            fail(f"missing enemy instruction list {label}")
        enemy_lists.append({
            "sourceLabel": label,
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "records": record["records"],
        })

    sprite_tracks = []
    for key, start_label, end_label, loops in TRACKS:
        start = address(start_label)
        end = address(end_label)
        raw = rom[snes_to_pc(start):snes_to_pc(end)]
        if len(raw) % 4:
            fail(f"{key} list boundary is no longer four-byte aligned")
        records = []
        for offset in range(0, len(raw), 4):
            word = int.from_bytes(raw[offset:offset + 2], "little")
            operand = int.from_bytes(raw[offset + 2:offset + 4], "little")
            if word < 0x8000:
                records.append({
                    "kind": "frame",
                    "snesAddress": start + offset,
                    "duration": word,
                    "spritemapSnesAddress": 0xB40000 | operand,
                })
            elif word == 0xBD12 and offset + 4 == len(raw):
                records.append({
                    "kind": "goto",
                    "snesAddress": start + offset,
                    "targetSnesAddress": 0xB40000 | operand,
                })
            else:
                fail(f"unsupported {key} record ${word:04X} at ${start + offset:06X}")
        if loops != (records[-1]["kind"] == "goto"):
            fail(f"{key} fallthrough/loop shape changed")
        sprite_tracks.append({
            "key": key,
            "sourceLabel": start_label,
            "snesAddress": start,
            "endSnesAddressExclusive": end,
            "size": len(raw),
            "entersByFallthrough": key in ("electricity-steady", "shell-steady"),
            "loops": loops,
            "records": records,
            "sha256": sha256(raw),
        })

    expected_track_shape = {
        "electricity-intro": (31, 93),
        "electricity-steady": (31, 115),
        "shell-intro": (32, 32),
        "shell-steady": (30, 30),
    }
    for track in sprite_tracks:
        frames = [record for record in track["records"] if record["kind"] == "frame"]
        actual = (len(frames), sum(int(record["duration"]) for record in frames))
        if actual != expected_track_shape[track["key"]]:
            fail(f"{track['key']} timing changed: {actual}")

    palette_address = address("Palette_Metroid")
    palette_raw = rom[snes_to_pc(palette_address):snes_to_pc(palette_address) + 32]
    palettes = [{
        "sourceLabel": "Palette_Metroid",
        "snesAddress": palette_address,
        "size": 32,
        "sha256": sha256(palette_raw),
    }]

    init_address = address("InitAI_Metroid")
    init_size = 0x56
    init_raw = rom[snes_to_pc(init_address):snes_to_pc(init_address) + init_size]
    runtime_ranges = [{
        "sourceLabel": "InitAI_Metroid",
        "role": "create-electricity-32-then-shell-34-at-enemy-position",
        "snesAddress": init_address,
        "size": init_size,
        "sha256": sha256(init_raw),
    }]
    if b"\xA9\x32\x00" not in init_raw or b"\xA9\x34\x00" not in init_raw:
        fail("Metroid init no longer creates sprite objects $32 and $34")

    ownership = {
        "pixels": "one shared enemy transfer for all three layers",
        "insides": {"owner": "enemy-$DD7F", "bank": 0xA3, "mapCount": len(inside_maps)},
        "electricity": {
            "owner": "sprite-object-$32",
            "bank": 0xB4,
            "mapCount": len(electricity_maps),
            "steadyContinuationLabel": "UNUSED_InstList_SpriteObject_33_B4C436",
            "continuationIsRuntimeActive": True,
        },
        "shell": {
            "owner": "sprite-object-$34",
            "bank": 0xB4,
            "mapCount": len(shell_maps),
            "steadyContinuationLabel": "UNUSED_InstList_SpriteObject_35_B4C536",
            "continuationIsRuntimeActive": True,
        },
        "drawOrder": ["electricity", "shell", "insides"],
        "editable": ["pixels"],
        "readOnly": ["OAM placement", "instruction timing", "sprite-object allocation"],
    }
    totals = {
        "headerCount": len(headers),
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "insideSpritemapCount": len(inside_maps),
        "insideOamEntryCount": sum(int(record["entryCount"]) for record in inside_maps),
        "shellSpritemapCount": len(shell_maps),
        "shellOamEntryCount": sum(int(record["entryCount"]) for record in shell_maps),
        "electricitySpritemapCount": len(electricity_maps),
        "electricityOamEntryCount": sum(int(record["entryCount"]) for record in electricity_maps),
        "enemyInstructionListCount": len(enemy_lists),
        "spriteObjectTrackCount": len(sprite_tracks),
        "spriteObjectFrameOccurrenceCount": sum(
            record["kind"] == "frame" for track in sprite_tracks for record in track["records"]
        ),
        "spriteObjectTickCount": sum(
            int(record["duration"]) for track in sprite_tracks for record in track["records"]
            if record["kind"] == "frame"
        ),
        "fallthroughContinuationCount": sum(bool(track["entersByFallthrough"]) for track in sprite_tracks),
        "paletteCount": len(palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "assets": aggregate_hash(assets),
        "insideSpritemaps": aggregate_hash(inside_maps),
        "shellSpritemaps": aggregate_hash(shell_maps),
        "electricitySpritemaps": aggregate_hash(electricity_maps),
        "enemyInstructionLists": aggregate_hash(enemy_lists),
        "spriteObjectTracks": aggregate_hash(sprite_tracks),
        "palettes": aggregate_hash(palettes),
        "runtimeRanges": aggregate_hash(runtime_ranges),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "ownership": ownership,
        "headers": headers,
        "assets": assets,
        "insideSpritemaps": inside_maps,
        "shellSpritemaps": shell_maps,
        "electricitySpritemaps": electricity_maps,
        "enemyInstructionLists": enemy_lists,
        "spriteObjectTracks": sprite_tracks,
        "palettes": palettes,
        "runtimeRanges": runtime_ranges,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Metroid manifest: "
        f"{totals['insideSpritemapCount']} inside + {totals['shellSpritemapCount']} shell + "
        f"{totals['electricitySpritemapCount']} electricity maps; "
        f"{totals['spriteObjectFrameOccurrenceCount']} companion frames / "
        f"{totals['spriteObjectTickCount']} ticks"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
