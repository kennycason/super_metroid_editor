#!/usr/bin/env python3
"""Build the source-derived tileset pointer and CRE ownership manifest."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Sequence

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_LZ5_REPORT = PARITY_DIR / "reports" / "lz5.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "tilesets.json"

TABLE_LABEL = re.compile(r"^(Tileset_Table_([0-9A-F]+)_[A-Za-z0-9_]+):")
DATA_LONG = re.compile(
    r"^\s+dl\s+([A-Za-z_][A-Za-z0-9_]*)\s+;([0-9A-Fa-f]{6});\s*(.*)$"
)
DATA_WORD = re.compile(
    r"^\s+dw\s+([A-Za-z_][A-Za-z0-9_]*)\s+;([0-9A-Fa-f]{6});"
)
GLOBAL_LABEL = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):")
CODE_ADDRESS = re.compile(r";([0-9A-Fa-f]{6});")

RESOURCE_SPECS = (
    ("tileTable", "tile-table"),
    ("graphics", "tiles"),
    ("palette", "palette"),
)
CRE_LABELS = ("CRE_Tiles_Compressed", "CRE_TileTable_Compressed")


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def formatted(address: int) -> str:
    return f"{address >> 16:02X}:{address & 0xFFFF:04X}"


def load_json(path: Path, task: str) -> Dict[str, object]:
    if not path.is_file():
        fail(f"missing {path}; run ./gradlew {task}")
    return json.loads(path.read_text(encoding="utf-8"))


def require_unique_symbol(catalog: SymbolCatalog, name: str) -> int:
    matches = catalog.exact(name)
    if len(matches) != 1:
        fail(f"expected one source symbol named {name}, found {len(matches)}")
    return matches[0].snes_address


def one_by_name(records: Iterable[Dict[str, object]], name: str, source: str) -> Dict[str, object]:
    matches = [record for record in records if record.get("name") == name]
    if len(matches) != 1:
        fail(f"expected one {source} record named {name}, found {len(matches)}")
    return matches[0]


def source_revision(disassembly: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def parse_tilesets(
    source_lines: Sequence[str],
    catalog: SymbolCatalog,
    assets: Sequence[Dict[str, object]],
    streams: Sequence[Dict[str, object]],
) -> List[Dict[str, object]]:
    entries: List[Dict[str, object]] = []
    for line_index, line in enumerate(source_lines):
        label_match = TABLE_LABEL.match(line)
        if label_match is None:
            continue
        table_label, id_hex = label_match.groups()
        tileset_id = int(id_hex, 16)
        description = ""
        resources: List[Dict[str, object]] = []
        for raw_line in source_lines[line_index + 1:]:
            if GLOBAL_LABEL.match(raw_line):
                break
            stripped = raw_line.strip()
            description_match = re.match(r";\s*[0-9A-F]+h?:\s*(.+)$", stripped)
            if description_match is not None and not description:
                description = description_match.group(1)
            pointer_match = DATA_LONG.match(raw_line)
            if pointer_match is None:
                continue
            target_label, annotated_hex, source_comment = pointer_match.groups()
            resource_index = len(resources)
            if resource_index >= len(RESOURCE_SPECS):
                fail(f"too many pointer fields in {table_label}")
            kind, expected_category = RESOURCE_SPECS[resource_index]
            pointer_address = int(annotated_hex, 16)
            target_address = require_unique_symbol(catalog, target_label)
            asset = one_by_name(assets, target_label, "asset")
            stream = one_by_name(streams, target_label, "LZ5")
            if asset["category"] != expected_category:
                fail(
                    f"{table_label} {kind} points to {target_label} category "
                    f"{asset['category']}, expected {expected_category}"
                )
            if int(asset["snesAddress"]) != target_address or int(stream["snesAddress"]) != target_address:
                fail(f"symbol/asset/LZ5 address disagreement for {target_label}")
            resources.append(
                {
                    "kind": kind,
                    "pointerFieldAddress": formatted(pointer_address),
                    "pointerFieldSnesAddress": pointer_address,
                    "sourceLabel": target_label,
                    "address": formatted(target_address),
                    "snesAddress": target_address,
                    "asset": asset["asset"],
                    "compressedSize": stream["compressedSize"],
                    "decompressedSize": stream["decompressedSize"],
                    "decompressedSha256": stream["decompressedSha256"],
                    "sourceComment": source_comment.strip(),
                }
            )
        if len(resources) != 3:
            fail(f"expected three pointer fields in {table_label}, found {len(resources)}")
        table_address = require_unique_symbol(catalog, table_label)
        expected_pointer_addresses = [table_address + offset for offset in (0, 3, 6)]
        actual_pointer_addresses = [int(item["pointerFieldSnesAddress"]) for item in resources]
        if actual_pointer_addresses != expected_pointer_addresses:
            fail(f"non-contiguous pointer fields in {table_label}")
        entries.append(
            {
                "id": tileset_id,
                "idHex": f"{tileset_id:02X}",
                "name": description,
                "tableLabel": table_label,
                "tableAddress": formatted(table_address),
                "tableSnesAddress": table_address,
                "resources": {item["kind"]: item for item in resources},
            }
        )

    entries.sort(key=lambda item: int(item["id"]))
    if [int(entry["id"]) for entry in entries] != list(range(29)):
        fail("tileset table must contain exactly IDs 00 through 1C")
    first_address = int(entries[0]["tableSnesAddress"])
    for index, entry in enumerate(entries):
        if int(entry["tableSnesAddress"]) != first_address + index * 9:
            fail(f"tileset entry {index:02X} is not in the contiguous 9-byte table")
    return entries


def parse_indirect_pointer_table(
    source_lines: Sequence[str], catalog: SymbolCatalog, entries: Sequence[Dict[str, object]]
) -> Dict[str, object]:
    start = next(
        (index for index, line in enumerate(source_lines) if line == "Tileset_Pointers:"),
        None,
    )
    if start is None:
        fail("source label Tileset_Pointers was not found")
    pointers: List[Dict[str, object]] = []
    for raw_line in source_lines[start + 1:]:
        match = DATA_WORD.match(raw_line)
        if match is not None:
            label, annotated_hex = match.groups()
            pointers.append(
                {
                    "index": len(pointers),
                    "pointerFieldAddress": formatted(int(annotated_hex, 16)),
                    "pointerFieldSnesAddress": int(annotated_hex, 16),
                    "tableLabel": label,
                }
            )
        elif pointers and (not raw_line.strip() or GLOBAL_LABEL.match(raw_line)):
            break
    expected_labels = [str(entry["tableLabel"]) for entry in entries]
    if [pointer["tableLabel"] for pointer in pointers] != expected_labels:
        fail("Tileset_Pointers does not name the 29 tileset entries in ID order")
    table_address = require_unique_symbol(catalog, "Tileset_Pointers")
    expected_start = int(entries[0]["tableSnesAddress"]) + len(entries) * 9
    if table_address != expected_start:
        fail("Tileset_Pointers does not begin immediately after the 29 pointer triples")
    for index, pointer in enumerate(pointers):
        if int(pointer["pointerFieldSnesAddress"]) != table_address + index * 2:
            fail("Tileset_Pointers is not a contiguous 29-entry word table")
    return {
        "label": "Tileset_Pointers",
        "address": formatted(table_address),
        "snesAddress": table_address,
        "entryCount": len(pointers),
        "entries": pointers,
    }


def alias_groups(entries: Sequence[Dict[str, object]]) -> Dict[str, List[Dict[str, object]]]:
    result: Dict[str, List[Dict[str, object]]] = {}
    for kind, _ in RESOURCE_SPECS:
        grouped: Dict[int, List[Dict[str, object]]] = defaultdict(list)
        for entry in entries:
            resource = entry["resources"][kind]
            grouped[int(resource["snesAddress"])].append(entry)
        result[kind] = [
            {
                "sourceLabel": group[0]["resources"][kind]["sourceLabel"],
                "address": group[0]["resources"][kind]["address"],
                "snesAddress": address,
                "asset": group[0]["resources"][kind]["asset"],
                "tilesetIds": [entry["id"] for entry in group],
                "tilesetIdHex": [entry["idHex"] for entry in group],
                "tilesetLabels": [entry["tableLabel"] for entry in group],
            }
            for address, group in sorted(grouped.items())
            if len(group) > 1
        ]
    return result


def scan_cre_references(
    disassembly: Path, catalog: SymbolCatalog, target: str
) -> Dict[str, object]:
    references: List[Dict[str, object]] = []
    current_routine = ""
    for source_path in sorted((disassembly / "src").rglob("*.asm")):
        relative = source_path.relative_to(disassembly).as_posix()
        for line_number, raw_line in enumerate(
            source_path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            global_match = GLOBAL_LABEL.match(raw_line)
            if global_match is not None:
                current_routine = global_match.group(1)
            code = raw_line.split(";", 1)[0]
            if not re.search(rf"\b{re.escape(target)}\b", code) or code.strip() == f"{target}:":
                continue
            if code.lstrip().lower().startswith("incbin "):
                continue
            address_match = CODE_ADDRESS.search(raw_line)
            if address_match is None:
                fail(f"unaddressed reference to {target} at {relative}:{line_number}")
            load_kind = "bank" if f"{target}>>8" in code else "lowWord"
            if not re.search(rf"LDA(?:\.[A-Z])?\s+#{re.escape(target)}", code):
                fail(f"unsupported reference to {target} at {relative}:{line_number}: {code.strip()}")
            references.append(
                {
                    "address": formatted(int(address_match.group(1), 16)),
                    "snesAddress": int(address_match.group(1), 16),
                    "loadKind": load_kind,
                    "routine": current_routine,
                    "routineAddress": formatted(require_unique_symbol(catalog, current_routine)),
                    "sourceFile": relative,
                    "sourceLine": line_number,
                    "source": code.strip(),
                }
            )

    by_routine: Dict[str, List[Dict[str, object]]] = defaultdict(list)
    for reference in references:
        by_routine[str(reference["routine"])].append(reference)
    consumers: List[Dict[str, object]] = []
    for routine, routine_references in sorted(
        by_routine.items(), key=lambda item: int(item[1][0]["snesAddress"])
    ):
        kinds = sorted(str(reference["loadKind"]) for reference in routine_references)
        if kinds != ["bank", "lowWord"]:
            fail(f"{target} consumer {routine} does not load exactly one bank and low word")
        low_word = next(reference for reference in routine_references if reference["loadKind"] == "lowWord")
        consumers.append(
            {
                "routine": routine,
                "routineAddress": low_word["routineAddress"],
                "pointerLoadAddress": low_word["address"],
                "pointerLoadSnesAddress": low_word["snesAddress"],
                "sourceFile": low_word["sourceFile"],
                "sourceLine": low_word["sourceLine"],
            }
        )
    return {
        "sourceLabel": target,
        "referenceCount": len(references),
        "references": references,
        "consumerCount": len(consumers),
        "consumers": consumers,
    }


def build_cre_manifest(
    disassembly: Path,
    catalog: SymbolCatalog,
    assets: Sequence[Dict[str, object]],
    streams: Sequence[Dict[str, object]],
) -> Dict[str, object]:
    records: Dict[str, Dict[str, object]] = {}
    for label in CRE_LABELS:
        asset = one_by_name(assets, label, "asset")
        stream = one_by_name(streams, label, "LZ5")
        address = require_unique_symbol(catalog, label)
        if int(asset["snesAddress"]) != address or int(stream["snesAddress"]) != address:
            fail(f"symbol/asset/LZ5 address disagreement for {label}")
        records[label] = {
            "sourceLabel": label,
            "address": formatted(address),
            "snesAddress": address,
            "asset": asset["asset"],
            "compressedSize": stream["compressedSize"],
            "compressedSha256": asset["sha256"],
            "decompressedSize": stream["decompressedSize"],
            "decompressedSha256": stream["decompressedSha256"],
            "endAddressExclusive": formatted(address + int(stream["compressedSize"])),
            "endSnesAddressExclusive": address + int(stream["compressedSize"]),
            "consumers": scan_cre_references(disassembly, catalog, label),
        }

    graphics = records["CRE_Tiles_Compressed"]
    tile_table = records["CRE_TileTable_Compressed"]
    if int(graphics["endSnesAddressExclusive"]) != int(tile_table["snesAddress"]):
        fail("compressed CRE graphics do not end exactly where the CRE tile table begins")
    if int(graphics["decompressedSize"]) % 32 != 0:
        fail("CRE graphics are not a whole number of 4bpp tiles")
    if int(tile_table["decompressedSize"]) % 8 != 0:
        fail("CRE tile table is not a whole number of metatiles")

    table_asset = disassembly / str(tile_table["asset"])
    # The independent LZ5 report owns the decoded hash/size. Decode again only to
    # record semantic bounds for the source-owned table in this focused manifest.
    from lz5_oracle import decode_lz5

    table_data = decode_lz5(table_asset.read_bytes()).data
    tile_numbers = [
        (table_data[offset] | (table_data[offset + 1] << 8)) & 0x03FF
        for offset in range(0, len(table_data), 2)
    ]
    cre_tile_start = 640
    cre_tile_count = int(graphics["decompressedSize"]) // 32
    if min(tile_numbers) != cre_tile_start or max(tile_numbers) >= cre_tile_start + cre_tile_count:
        fail("CRE tile-table words escape the exact CRE graphics tile range")

    cre_tiles_address = require_unique_symbol(catalog, "CRETiles")
    room_tiles_address = require_unique_symbol(catalog, "RoomTiles")
    decompressed_cre_address = require_unique_symbol(catalog, "DecompressedCRE")
    decompressed_sce_address = require_unique_symbol(catalog, "DecompressedSCE")
    if decompressed_cre_address + int(tile_table["decompressedSize"]) != decompressed_sce_address:
        fail("CRE tile-table output does not end exactly at DecompressedSCE")
    if cre_tiles_address + int(graphics["decompressedSize"]) != room_tiles_address + 0x8000:
        fail("CRE staging buffer does not exactly occupy $7E:7000..9FFF")

    graphics["runtimeOwnership"] = {
        "bytesPerTile": 32,
        "tileStart": cre_tile_start,
        "tileEndInclusive": cre_tile_start + cre_tile_count - 1,
        "tileCount": cre_tile_count,
        "vramByteStart": "5000",
        "vramByteEndInclusive": "7FFF",
        "vramWordStart": "2800",
        "vramWordEndInclusive": "3FFF",
        "wramStagingStart": formatted(cre_tiles_address),
        "wramStagingEndInclusive": formatted(cre_tiles_address + int(graphics["decompressedSize"]) - 1),
        "variableGraphicsStart": formatted(room_tiles_address),
    }
    tile_table["runtimeOwnership"] = {
        "bytesPerMetatile": 8,
        "metatileStart": 0,
        "metatileEndInclusive": int(tile_table["decompressedSize"]) // 8 - 1,
        "metatileCount": int(tile_table["decompressedSize"]) // 8,
        "subtileWordCount": len(tile_numbers),
        "minimumReferencedTile": min(tile_numbers),
        "maximumReferencedTile": max(tile_numbers),
        "creTileReferenceCount": sum(number >= cre_tile_start for number in tile_numbers),
        "wramStart": formatted(decompressed_cre_address),
        "wramEndInclusive": formatted(decompressed_cre_address + int(tile_table["decompressedSize"]) - 1),
        "tilesetSpecificTableStart": formatted(decompressed_sce_address),
    }
    return {
        "compressedRangesContiguous": True,
        "graphics": graphics,
        "tileTable": tile_table,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate the source-derived tileset/CRE ownership manifest."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--lz5-report", type=Path, default=DEFAULT_LZ5_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    source_path = disassembly / "src" / "bank_8F.asm"
    if not symbols_path.is_file() or not source_path.is_file():
        fail(f"missing built source fixture in {disassembly}; run ./gradlew parityBuildReference")
    asset_manifest = load_json(args.asset_manifest.expanduser().resolve(), "parityAssets")
    lz5_report = load_json(args.lz5_report.expanduser().resolve(), "parityLz5Oracle")
    catalog = SymbolCatalog.read(symbols_path)
    source_lines = source_path.read_text(encoding="utf-8").splitlines()
    entries = parse_tilesets(
        source_lines,
        catalog,
        asset_manifest["assets"],
        lz5_report["streams"],
    )
    indirect = parse_indirect_pointer_table(source_lines, catalog, entries)
    aliases = alias_groups(entries)
    unique_counts = {
        kind: len({int(entry["resources"][kind]["snesAddress"]) for entry in entries})
        for kind, _ in RESOURCE_SPECS
    }
    alias_counts = {kind: len(groups) for kind, groups in aliases.items()}
    first_address = int(entries[0]["tableSnesAddress"])
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": source_revision(disassembly),
        "tilesetCount": len(entries),
        "pointerTripleCount": len(entries),
        "pointerFieldCount": len(entries) * 3,
        "uniqueResourceCount": sum(unique_counts.values()),
        "table": {
            "address": formatted(first_address),
            "snesAddress": first_address,
            "endAddressInclusive": formatted(first_address + len(entries) * 9 - 1),
            "entryBytes": 9,
            "sizeBytes": len(entries) * 9,
        },
        "indirectPointerTable": indirect,
        "uniqueResourceCounts": unique_counts,
        "aliasGroupCounts": alias_counts,
        "aliasGroups": aliases,
        "tilesets": entries,
        "cre": build_cre_manifest(
            disassembly,
            catalog,
            asset_manifest["assets"],
            lz5_report["streams"],
        ),
    }
    output_path = args.output.expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Tileset/CRE ownership manifest valid")
    print(f"  Tilesets: {payload['tilesetCount']} ({payload['pointerFieldCount']} pointer fields)")
    print(
        "  Unique assets: "
        + ", ".join(f"{kind}={count}" for kind, count in unique_counts.items())
    )
    print(
        "  Intentional alias groups: "
        + ", ".join(f"{kind}={count}" for kind, count in alias_counts.items())
    )
    print(
        "  CRE consumers: "
        f"graphics={payload['cre']['graphics']['consumers']['consumerCount']}, "
        f"tileTable={payload['cre']['tileTable']['consumers']['consumerCount']}"
    )
    print(f"  Output: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
