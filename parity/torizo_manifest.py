#!/usr/bin/env python3
"""Build the Bomb/Golden Torizo ownership, OAM, instruction, palette, and DMA manifest."""

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
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "torizo.json"

HEADER_LABELS = (
    "EnemyHeaders_BombTorizo",
    "EnemyHeaders_BombTorizoOrb",
    "EnemyHeaders_GoldenTorizo",
    "EnemyHeaders_GoldenTorizoOrb",
)
ASSET_ROLES = {
    "Tiles_BombTorizo_GoldenTorizo": "shared-bomb-golden-obj",
    "Tiles_Torizo": "runtime-eyes-damage-and-egg-release-overlays",
    "Tiles_GoldenTorizoEgg": "room-loaded-golden-egg-and-hatchling-obj",
    "Tiles_BombTorizosCrumblingChozo": "room-loaded-bomb-statue-fragment-obj",
}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def compact_instruction(record: Dict[str, object]) -> Dict[str, object]:
    return {
        "sourceLabel": record["sourceLabel"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
        "sourceDeclaredUnused": bool(record["sourceDeclaredUnused"]),
        "records": [
            {
                key: value for key, value in item.items()
                if key in {
                    "kind", "snesAddress", "size", "duration", "spritemapSnesAddress",
                    "spritemapType", "handlerSnesAddress", "operandByteCount", "controlFlow",
                }
            }
            for item in record["records"]
        ],
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
        "headers": report_dir / "enemy-headers.json",
        "oam": report_dir / "enemy-oam.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityTorizo")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    commits = {str(report["disassemblyCommit"]) for report in reports.values()}
    if len(commits) != 1:
        fail("Torizo prerequisite reports belong to different disassembly revisions")
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}

    headers = []
    source_headers = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    for label in HEADER_LABELS:
        record = source_headers.get(label)
        if record is None:
            fail(f"missing source header {label}")
        fields = record["fields"]
        if int(fields["tileDataSize"]["value"]) != 0x2000:
            fail(f"{label} tile transfer size changed")
        if fields["tileData"]["targetLabel"] != "Tiles_BombTorizo_GoldenTorizo":
            fail(f"{label} no longer aliases the shared Torizo OBJ source")
        if int(fields["bank"]["value"]) != 0xAA:
            fail(f"{label} no longer uses AI bank $AA")
        headers.append({
            "sourceLabel": label,
            "speciesId": int(record["speciesId"]),
            "headerSnesAddress": int(record["snesAddress"]),
            "tileDataSize": 0x2000,
            "tileDataSnesAddress": int(fields["tileData"]["value"]),
            "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
            "aiBank": 0xAA,
            "rawHeaderSha256": record["rawHeaderSha256"],
        })

    source_assets = {record["name"]: record for record in reports["assets"]["assets"]}
    assets = []
    for label, role in ASSET_ROLES.items():
        record = source_assets.get(label)
        if record is None:
            fail(f"missing source asset {label}")
        assets.append({
            "sourceLabel": label,
            "role": role,
            "snesAddress": int(record["snesAddress"]),
            "size": int(record["size"]),
            "sha256": record["sha256"],
            "asset": record["asset"],
        })
    expected_sizes = {
        "Tiles_BombTorizo_GoldenTorizo": 0x2000,
        "Tiles_Torizo": 0x600,
        "Tiles_GoldenTorizoEgg": 0x600,
        "Tiles_BombTorizosCrumblingChozo": 0x400,
    }
    for record in assets:
        if int(record["size"]) != expected_sizes[str(record["sourceLabel"])]:
            fail(f"{record['sourceLabel']} size changed")

    def torizo_record(record: Dict[str, object]) -> bool:
        label = str(record.get("sourceLabel", ""))
        return "Torizo" in label and "CorpseTorizo" not in label

    standard_maps = [record for record in reports["oam"]["standardSpritemaps"] if torizo_record(record)]
    extended_maps = [record for record in reports["oam"]["extendedSpritemaps"] if torizo_record(record)]
    active_standard = [record for record in standard_maps if not record["sourceDeclaredUnused"]]
    unused_standard = [record for record in standard_maps if record["sourceDeclaredUnused"]]
    active_extended = [record for record in extended_maps if not record["sourceDeclaredUnused"]]
    unused_extended = [record for record in extended_maps if record["sourceDeclaredUnused"]]
    if (len(active_standard), len(unused_standard), len(active_extended), len(unused_extended)) != (91, 18, 106, 7):
        fail(
            "Torizo OAM inventory changed: "
            f"{len(active_standard)}/{len(unused_standard)} standard active/unused, "
            f"{len(active_extended)}/{len(unused_extended)} extended active/unused"
        )

    instruction_lists = [
        compact_instruction(record)
        for record in reports["instructions"]["lists"]
        if torizo_record(record)
    ]
    if (sum(not record["sourceDeclaredUnused"] for record in instruction_lists),
            sum(record["sourceDeclaredUnused"] for record in instruction_lists)) != (112, 1):
        fail("Torizo bank-$AA instruction-list inventory changed")

    projectile_instruction_symbols = [
        {"sourceLabel": name, "snesAddress": address}
        for name, address in symbols.items()
        if name.startswith("InstList_EnemyProjectile_") and "Torizo" in name
    ]
    projectile_instruction_symbols.sort(key=lambda record: (int(record["snesAddress"]), str(record["sourceLabel"])))
    if len(projectile_instruction_symbols) != 50:
        fail(f"Torizo bank-$86 projectile instruction inventory changed: {len(projectile_instruction_symbols)}")

    projectile_map_symbols = [
        (name, address)
        for name, address in symbols.items()
        if (
            name.startswith("EnemyProjSpritemaps_")
            or name.startswith("UNUSED_EnemyProjSpritemaps_")
        ) and "Torizo" in name
    ]
    projectile_map_symbols.sort(key=lambda item: (item[1], item[0]))

    def projectile_map(label: str, snes_address: int) -> Dict[str, object]:
        pc = snes_to_pc(snes_address)
        count = int.from_bytes(rom[pc:pc + 2], "little")
        if count > 32:
            fail(f"{label} has implausible OAM entry count {count}")
        raw = rom[pc:pc + 2 + count * 5]
        return {
            "sourceLabel": label,
            "snesAddress": snes_address,
            "size": len(raw),
            "entryCount": count,
            "sourceDeclaredUnused": label.startswith("UNUSED_"),
            "sha256": sha256(raw),
        }

    projectile_maps = [projectile_map(label, address) for label, address in projectile_map_symbols]
    active_projectile_maps = [record for record in projectile_maps if not record["sourceDeclaredUnused"]]
    unused_projectile_maps = [record for record in projectile_maps if record["sourceDeclaredUnused"]]
    if (len(active_projectile_maps), len(unused_projectile_maps)) != (70, 4):
        fail(
            "Torizo bank-$8D projectile OAM inventory changed: "
            f"{len(active_projectile_maps)} active / {len(unused_projectile_maps)} unused"
        )

    palettes = []
    palette_ranges = (
        ("Palette_Torizo_OrbProjectile", 0xAA8687, 10, "bank-AA encounter rows"),
        ("GoldenTorizo_HealthBasedPalette_Handling.palette1", 0x848032, 8, "bank-84 health rows 1"),
        ("GoldenTorizo_HealthBasedPalette_Handling.palette2", 0x848132, 8, "bank-84 health rows 2"),
    )
    for label, start, count, role in palette_ranges:
        for index in range(count):
            snes_address = start + index * 0x20
            raw = rom[snes_to_pc(snes_address):snes_to_pc(snes_address) + 0x20]
            palettes.append({
                "sourceLabel": f"{label}[{index}]",
                "role": role,
                "snesAddress": snes_address,
                "size": 0x20,
                "sha256": sha256(raw),
            })

    runtime_transfers = [
        {"role": "eyes-0", "sourceSnesAddress": 0xAAB279, "size": 0x40, "destinationVram": 0x7D80},
        {"role": "eyes-1", "sourceSnesAddress": 0xAAB2B9, "size": 0x40, "destinationVram": 0x7D80},
        {"role": "eyes-2", "sourceSnesAddress": 0xAAB2F9, "size": 0x40, "destinationVram": 0x7D80},
        {"role": "eyes-3", "sourceSnesAddress": 0xAAB339, "size": 0x40, "destinationVram": 0x7D80},
        {"role": "gut-top", "sourceSnesAddress": 0xAAB479, "size": 0x40, "destinationVram": 0x7300},
        {"role": "gut-bottom", "sourceSnesAddress": 0xAAB679, "size": 0x40, "destinationVram": 0x7400},
        {"role": "gut-top-edge", "sourceSnesAddress": 0xAAB4B9, "size": 0x20, "destinationVram": 0x7E70},
        {"role": "gut-bottom-edge", "sourceSnesAddress": 0xAAB6B9, "size": 0x20, "destinationVram": 0x7F70},
        {"role": "face-top", "sourceSnesAddress": 0xAAB4D9, "size": 0x20, "destinationVram": 0x7E50},
        {"role": "face-bottom", "sourceSnesAddress": 0xAAB6D9, "size": 0x20, "destinationVram": 0x7F50},
        {"role": "egg-release-1-top", "sourceSnesAddress": 0xAAB4F9, "size": 0x40, "destinationVram": 0x7300},
        {"role": "egg-release-1-bottom", "sourceSnesAddress": 0xAAB6F9, "size": 0x40, "destinationVram": 0x7400},
        {"role": "egg-release-2-top", "sourceSnesAddress": 0xAAB539, "size": 0x40, "destinationVram": 0x7300},
        {"role": "egg-release-2-bottom", "sourceSnesAddress": 0xAAB739, "size": 0x40, "destinationVram": 0x7400},
        {"role": "egg-release-3-top", "sourceSnesAddress": 0xAAB579, "size": 0x40, "destinationVram": 0x7300},
        {"role": "egg-release-3-bottom", "sourceSnesAddress": 0xAAB779, "size": 0x40, "destinationVram": 0x7400},
    ]
    for transfer in runtime_transfers:
        start = snes_to_pc(int(transfer["sourceSnesAddress"]))
        raw = rom[start:start + int(transfer["size"])]
        transfer["sha256"] = sha256(raw)

    ownership = {
        "encounterHeaders": [0xEEFF, 0xEF7F],
        "dropChanceOnlyHeaders": [0xEF3F, 0xEFBF],
        "sharedObj": {"snesAddress": 0xAFC200, "size": 0x2000, "projectEditKey": 'spriteTileBlocks["enemy:EEFF"]'},
        "runtimeOverlay": {"snesAddress": 0xAAB279, "size": 0x600, "editable": False},
        "goldenEgg": {"snesAddress": 0xAFE200, "size": 0x600, "vramDestination": 0x6D00},
        "bombStatue": {"snesAddress": 0xADB200, "size": 0x400, "physicalObjTileBase": 0xE0},
        "body": {"sourceBank": 0xAA, "format": "extended-oam", "sharedByEncounters": True},
        "projectiles": {"instructionBank": 0x86, "oamBank": 0x8D},
        "goldenHealthPalette": {"handler": 0x848000, "stageCount": 8, "rowCount": 2},
    }

    totals = {
        "headerCount": len(headers),
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "activeStandardSpritemapCount": len(active_standard),
        "unusedStandardSpritemapCount": len(unused_standard),
        "standardOamEntryCount": sum(int(record["entryCount"]) for record in standard_maps),
        "activeExtendedSpritemapCount": len(active_extended),
        "unusedExtendedSpritemapCount": len(unused_extended),
        "extendedChildCount": sum(int(record["childCount"]) for record in extended_maps),
        "activeBodyInstructionListCount": sum(not record["sourceDeclaredUnused"] for record in instruction_lists),
        "unusedBodyInstructionListCount": sum(record["sourceDeclaredUnused"] for record in instruction_lists),
        "bodyFrameOccurrenceCount": sum(
            item["kind"] == "frame" for record in instruction_lists for item in record["records"]
        ),
        "projectileInstructionSymbolCount": len(projectile_instruction_symbols),
        "activeProjectileSpritemapCount": len(active_projectile_maps),
        "unusedProjectileSpritemapCount": len(unused_projectile_maps),
        "projectileOamEntryCount": sum(int(record["entryCount"]) for record in projectile_maps),
        "runtimeTransferCount": len(runtime_transfers),
        "paletteRowCount": len(palettes),
    }
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headers": aggregate_hash(headers),
        "assets": aggregate_hash(assets),
        "activeStandardSpritemaps": aggregate_hash(active_standard),
        "unusedStandardSpritemaps": aggregate_hash(unused_standard),
        "activeExtendedSpritemaps": aggregate_hash(active_extended),
        "unusedExtendedSpritemaps": aggregate_hash(unused_extended),
        "bodyInstructionLists": aggregate_hash(instruction_lists),
        "projectileInstructionSymbols": aggregate_hash(projectile_instruction_symbols),
        "activeProjectileSpritemaps": aggregate_hash(active_projectile_maps),
        "unusedProjectileSpritemaps": aggregate_hash(unused_projectile_maps),
        "runtimeTransfers": aggregate_hash(runtime_transfers),
        "palettes": aggregate_hash(palettes),
    }
    result = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "romSha256": sha256(rom),
        "ownership": ownership,
        "headers": headers,
        "assets": assets,
        "activeStandardSpritemaps": active_standard,
        "unusedStandardSpritemaps": unused_standard,
        "activeExtendedSpritemaps": active_extended,
        "unusedExtendedSpritemaps": unused_extended,
        "bodyInstructionLists": instruction_lists,
        "projectileInstructionSymbols": projectile_instruction_symbols,
        "activeProjectileSpritemaps": active_projectile_maps,
        "unusedProjectileSpritemaps": unused_projectile_maps,
        "runtimeTransfers": runtime_transfers,
        "palettes": palettes,
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        "Torizo manifest: "
        f"{totals['activeExtendedSpritemapCount']} active body maps / "
        f"{totals['activeStandardSpritemapCount']} active body-child maps / "
        f"{totals['activeProjectileSpritemapCount']} active projectile maps, "
        f"{totals['paletteRowCount']} palette rows"
    )
    print(f"  Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
