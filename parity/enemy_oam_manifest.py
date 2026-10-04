#!/usr/bin/env python3
"""Build a source/ROM manifest for every named enemy OAM structure."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

from symbol_catalog import Symbol, SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT = PARITY_DIR / "reports" / "enemy-oam.json"

# The disassembly is not perfectly uniform about singular/plural names or the
# ``Extended``/``Ext`` abbreviation. All of these labels describe the same ROM
# structures, so the source oracle deliberately recognizes every spelling.
# Bank $B2 consistently spells Space Pirate structures ``Spitemaps`` (without
# the first "r"); those are real, referenced standard OAM structures too.
STANDARD_LABEL = re.compile(
    r"^(?:UNUSED_)?(?:(?:Sprite|Spite)maps?_|SpritemapZebetite_)"
)
EXTENDED_LABEL = re.compile(r"^(?:UNUSED_)?(?:Extended|Ext)Spritemaps?_")
TILEMAP_LABEL = re.compile(r"^(?:UNUSED_)?ExtendedTilemaps?_")
HITBOX_LABEL = re.compile(r"^(?:UNUSED_)?Hitbox(?:es)?_")

# Enemy headers select AI banks $A2..AA and $B2..B3. Bank $A0 owns the shared
# empty structures copied into each AI bank.
ENEMY_STRUCTURE_BANKS = {0xA0, *range(0xA2, 0xAB), 0xB2, 0xB3}
MAX_OAM_ENTRIES = 64
MAX_TILEMAP_RUNS = 128
MAX_TILEMAP_WORDS = 4096

# This label intentionally preserves one orphaned five-byte entry. The source
# itself says "Missing count", so it is not a standard spritemap structure and
# must not be presented to the production structure parser.
NON_STRUCTURE_LABELS = {
    "UNUSED_Spritemaps_Torizo_UnusedEntry_AA99CE": "orphaned OAM entry; source declares missing count",
}


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


def read_bytes(rom: bytes, address: int, size: int) -> bytes:
    pc = snes_to_pc(address)
    end = pc + size
    if pc < 0 or end > len(rom):
        fail(f"read outside rebuilt ROM: {formatted(address)} + ${size:X}")
    return rom[pc:end]


def read_u8(rom: bytes, address: int) -> int:
    return read_bytes(rom, address, 1)[0]


def read_u16(rom: bytes, address: int) -> int:
    data = read_bytes(rom, address, 2)
    return data[0] | (data[1] << 8)


def read_s16(rom: bytes, address: int) -> int:
    value = read_u16(rom, address)
    return value - 0x10000 if value & 0x8000 else value


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]], fields: Sequence[str]) -> str:
    digest = hashlib.sha256()
    for record in records:
        for field in fields:
            value = record[field]
            if isinstance(value, (list, dict)):
                value = json.dumps(value, sort_keys=True, separators=(",", ":"))
            digest.update(str(value).encode("utf-8"))
            digest.update(b"\0")
        digest.update(b"\n")
    return digest.hexdigest()


def source_revision(disassembly: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def source_symbols(catalog: SymbolCatalog, pattern: re.Pattern[str]) -> List[Symbol]:
    records = [
        symbol
        for symbol in catalog.symbols
        if symbol.snes_address >> 16 in ENEMY_STRUCTURE_BANKS
        and pattern.match(symbol.name)
        and symbol.name not in NON_STRUCTURE_LABELS
    ]
    records.sort(key=lambda symbol: (symbol.snes_address, symbol.name))
    if len({symbol.name for symbol in records}) != len(records):
        fail(f"duplicate source names found for {pattern.pattern}")
    return records


def labels_at(
    catalog: SymbolCatalog, address: int, pattern: re.Pattern[str]
) -> List[str]:
    return sorted(symbol.name for symbol in catalog.at(address) if pattern.match(symbol.name))


def excluded_label_records(
    catalog: SymbolCatalog, rom: bytes
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for name, reason in sorted(NON_STRUCTURE_LABELS.items()):
        matches = catalog.exact(name)
        if len(matches) != 1:
            fail(f"expected exactly one excluded source label {name}, found {len(matches)}")
        symbol = matches[0]
        first_word = read_u16(rom, symbol.snes_address)
        if first_word <= MAX_OAM_ENTRIES:
            fail(f"excluded label {name} now looks like a counted OAM structure")
        records.append(
            {
                "sourceLabel": name,
                "address": symbol.address,
                "snesAddress": symbol.snes_address,
                "firstWord": first_word,
                "reason": reason,
            }
        )
    return records


def decode_oam_entry(rom: bytes, address: int) -> Dict[str, object]:
    x_word = read_u16(rom, address)
    y_byte = read_u8(rom, address + 2)
    attributes = read_u16(rom, address + 3)
    x_raw = x_word & 0x1FF
    return {
        "address": formatted(address),
        "snesAddress": address,
        "xWord": x_word,
        "xOffset": x_raw - 0x200 if x_raw & 0x100 else x_raw,
        "yByte": y_byte,
        "yOffset": y_byte - 0x100 if y_byte & 0x80 else y_byte,
        "tileNumber": attributes & 0x1FF,
        "nameTable": (attributes >> 8) & 1,
        "paletteRow": (attributes >> 9) & 7,
        "priority": (attributes >> 12) & 3,
        "hFlip": bool(attributes & 0x4000),
        "vFlip": bool(attributes & 0x8000),
        "is16x16": bool(x_word & 0x8000),
        "attributes": attributes,
    }


def build_standard_spritemaps(
    symbols: Sequence[Symbol], rom: bytes
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for symbol in symbols:
        count = read_u16(rom, symbol.snes_address)
        if count > MAX_OAM_ENTRIES:
            fail(f"{symbol.name} has implausible OAM count {count}")
        size = 2 + count * 5
        raw = read_bytes(rom, symbol.snes_address, size)
        entries = [
            decode_oam_entry(rom, symbol.snes_address + 2 + index * 5)
            for index in range(count)
        ]
        records.append(
            {
                "sourceLabel": symbol.name,
                "sourceDeclaredUnused": symbol.name.startswith("UNUSED_"),
                "address": symbol.address,
                "snesAddress": symbol.snes_address,
                "size": size,
                "entryCount": count,
                "rawSha256": sha256(raw),
                "entries": entries,
            }
        )
    return records


def parse_extended_tilemap(
    rom: bytes, address: int
) -> Tuple[int, List[Dict[str, object]]]:
    if read_u16(rom, address) != 0xFFFE:
        fail(f"extended tilemap at {formatted(address)} does not begin with $FFFE")
    position = address + 2
    runs: List[Dict[str, object]] = []
    total_words = 0
    for _index in range(MAX_TILEMAP_RUNS):
        destination = read_u16(rom, position)
        position += 2
        if destination == 0xFFFF:
            if not runs:
                fail(f"extended tilemap at {formatted(address)} has no runs")
            return position - address, runs
        count = read_u16(rom, position)
        position += 2
        if count == 0 or total_words + count > MAX_TILEMAP_WORDS:
            fail(
                f"extended tilemap at {formatted(address)} has invalid run size {count}"
            )
        words = [read_u16(rom, position + index * 2) for index in range(count)]
        runs.append(
            {
                "destination": destination,
                "wordCount": count,
                "words": words,
            }
        )
        total_words += count
        position += count * 2
    fail(f"extended tilemap at {formatted(address)} has no terminator")
    raise AssertionError


def build_extended_tilemaps(
    symbols: Sequence[Symbol], rom: bytes
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for symbol in symbols:
        size, runs = parse_extended_tilemap(rom, symbol.snes_address)
        raw = read_bytes(rom, symbol.snes_address, size)
        records.append(
            {
                "sourceLabel": symbol.name,
                "sourceDeclaredUnused": symbol.name.startswith("UNUSED_"),
                "address": symbol.address,
                "snesAddress": symbol.snes_address,
                "size": size,
                "runCount": len(runs),
                "wordCount": sum(int(run["wordCount"]) for run in runs),
                "rawSha256": sha256(raw),
                "runs": runs,
            }
        )
    return records


def build_extended_spritemaps(
    symbols: Sequence[Symbol], catalog: SymbolCatalog, rom: bytes
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for symbol in symbols:
        raw_count = read_u16(rom, symbol.snes_address)
        # The engine reads only the low byte. Ceres steam deliberately stores
        # $1001, and the source calls out that the high byte is ignored.
        count = raw_count & 0xFF
        if count == 0 or count > MAX_OAM_ENTRIES:
            fail(f"{symbol.name} has invalid extended child count {count}")
        size = 2 + count * 8
        raw = read_bytes(rom, symbol.snes_address, size)
        children: List[Dict[str, object]] = []
        bank = symbol.snes_address & 0xFF0000
        for index in range(count):
            address = symbol.snes_address + 2 + index * 8
            child_pointer = read_u16(rom, address + 4)
            hitbox_pointer = read_u16(rom, address + 6)
            if child_pointer < 0x8000:
                fail(f"{symbol.name} child {index} has non-ROM pointer ${child_pointer:04X}")
            child_address = bank | child_pointer
            child_type = (
                "extended-tilemap"
                if read_u16(rom, child_address) == 0xFFFE
                else "standard-oam"
            )
            child_pattern = TILEMAP_LABEL if child_type == "extended-tilemap" else STANDARD_LABEL
            child_labels = labels_at(catalog, child_address, child_pattern)
            if not child_labels:
                fail(
                    f"{symbol.name} child {index} at {formatted(child_address)} has no named "
                    f"{child_type} source structure"
                )
            hitbox_address = bank | hitbox_pointer if hitbox_pointer >= 0x8000 else hitbox_pointer
            hitbox_labels = (
                labels_at(catalog, hitbox_address, HITBOX_LABEL)
                if hitbox_pointer >= 0x8000
                else []
            )
            if hitbox_pointer >= 0x8000 and not hitbox_labels:
                fail(
                    f"{symbol.name} child {index} at {formatted(hitbox_address)} has no named hitbox"
                )
            children.append(
                {
                    "xOffset": read_s16(rom, address),
                    "yOffset": read_s16(rom, address + 2),
                    "childPointer": child_pointer,
                    "childAddress": formatted(child_address),
                    "childSnesAddress": child_address,
                    "childType": child_type,
                    "childLabels": child_labels,
                    "hitboxPointer": hitbox_pointer,
                    "hitboxAddress": (
                        formatted(hitbox_address) if hitbox_pointer >= 0x8000 else None
                    ),
                    "hitboxSnesAddress": hitbox_address,
                    "hitboxLabels": hitbox_labels,
                }
            )
        records.append(
            {
                "sourceLabel": symbol.name,
                "sourceDeclaredUnused": symbol.name.startswith("UNUSED_"),
                "address": symbol.address,
                "snesAddress": symbol.snes_address,
                "size": size,
                "rawChildCount": raw_count,
                "childCount": count,
                "rawSha256": sha256(raw),
                "children": children,
            }
        )
    return records


def alias_groups(records: Sequence[Dict[str, object]]) -> List[Dict[str, object]]:
    by_address: Dict[int, List[Dict[str, object]]] = defaultdict(list)
    for record in records:
        by_address[int(record["snesAddress"])].append(record)
    return [
        {
            "address": formatted(address),
            "snesAddress": address,
            "sourceLabels": sorted(str(record["sourceLabel"]) for record in members),
        }
        for address, members in sorted(by_address.items())
        if len(members) > 1
    ]


def count_values(records: Iterable[Dict[str, object]], field: str) -> Dict[str, int]:
    counts = Counter(int(record[field]) for record in records)
    return {str(value): counts[value] for value in sorted(counts)}


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate source-backed enemy OAM/extended-spritemap structures."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    if not symbols_path.is_file() or not rom_path.is_file():
        fail(f"missing reference build outputs in {disassembly}; run ./gradlew parityBuildReference")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    excluded = excluded_label_records(catalog, rom)
    standard = build_standard_spritemaps(source_symbols(catalog, STANDARD_LABEL), rom)
    tilemaps = build_extended_tilemaps(source_symbols(catalog, TILEMAP_LABEL), rom)
    extended = build_extended_spritemaps(
        source_symbols(catalog, EXTENDED_LABEL), catalog, rom
    )

    standard_entries = [entry for record in standard for entry in record["entries"]]
    extended_children = [child for record in extended for child in record["children"]]
    tilemap_runs = [run for record in tilemaps for run in record["runs"]]
    aliases = {
        "standard": alias_groups(standard),
        "extended": alias_groups(extended),
        "tilemap": alias_groups(tilemaps),
    }
    totals = {
        "standardLabelCount": len(standard),
        "standardUniqueAddressCount": len({int(record["snesAddress"]) for record in standard}),
        "standardUnusedLabelCount": sum(bool(record["sourceDeclaredUnused"]) for record in standard),
        "standardZeroEntryCount": sum(int(record["entryCount"]) == 0 for record in standard),
        "standardEntryCount": len(standard_entries),
        "standard8x8EntryCount": sum(not bool(entry["is16x16"]) for entry in standard_entries),
        "standard16x16EntryCount": sum(bool(entry["is16x16"]) for entry in standard_entries),
        "extendedLabelCount": len(extended),
        "extendedUniqueAddressCount": len({int(record["snesAddress"]) for record in extended}),
        "extendedUnusedLabelCount": sum(bool(record["sourceDeclaredUnused"]) for record in extended),
        "extendedChildAssociationCount": len(extended_children),
        "extendedOamChildAssociationCount": sum(
            child["childType"] == "standard-oam" for child in extended_children
        ),
        "extendedTilemapChildAssociationCount": sum(
            child["childType"] == "extended-tilemap" for child in extended_children
        ),
        "uniqueHitboxAddressCount": len(
            {
                int(child["hitboxSnesAddress"])
                for child in extended_children
                if int(child["hitboxPointer"]) >= 0x8000
            }
        ),
        "tilemapLabelCount": len(tilemaps),
        "tilemapUniqueAddressCount": len({int(record["snesAddress"]) for record in tilemaps}),
        "tilemapUnusedLabelCount": sum(bool(record["sourceDeclaredUnused"]) for record in tilemaps),
        "tilemapRunCount": len(tilemap_runs),
        "tilemapWordCount": sum(int(record["wordCount"]) for record in tilemaps),
        "standardAliasGroupCount": len(aliases["standard"]),
        "extendedAliasGroupCount": len(aliases["extended"]),
        "tilemapAliasGroupCount": len(aliases["tilemap"]),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": source_revision(disassembly),
        "oracle": "named WLA source structures and byte-identical rebuilt ROM",
        "banks": [f"{bank:02X}" for bank in sorted(ENEMY_STRUCTURE_BANKS)],
        "excludedLabels": excluded,
        "totals": totals,
        "fieldCounts": {
            "priority": count_values(standard_entries, "priority"),
            "paletteRow": count_values(standard_entries, "paletteRow"),
            "nameTable": count_values(standard_entries, "nameTable"),
        },
        "aggregateHashes": {
            "standard": aggregate_hash(
                standard,
                ("sourceLabel", "snesAddress", "size", "entryCount", "rawSha256", "entries"),
            ),
            "extended": aggregate_hash(
                extended,
                (
                    "sourceLabel",
                    "snesAddress",
                    "size",
                    "rawChildCount",
                    "childCount",
                    "rawSha256",
                    "children",
                ),
            ),
            "tilemaps": aggregate_hash(
                tilemaps,
                ("sourceLabel", "snesAddress", "size", "runCount", "rawSha256", "runs"),
            ),
            "aliases": aggregate_hash(
                aliases["standard"] + aliases["extended"] + aliases["tilemap"],
                ("snesAddress", "sourceLabels"),
            ),
        },
        "standardSpritemaps": standard,
        "extendedSpritemaps": extended,
        "extendedTilemaps": tilemaps,
        "aliases": aliases,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Enemy OAM structure manifest valid")
    print(
        f"  Standard: {totals['standardLabelCount']} labels / "
        f"{totals['standardUniqueAddressCount']} unique / {totals['standardEntryCount']} entries"
    )
    print(
        f"  Extended: {totals['extendedLabelCount']} labels / "
        f"{totals['extendedChildAssociationCount']} child associations"
    )
    print(
        f"  Tilemaps: {totals['tilemapLabelCount']} labels / "
        f"{totals['tilemapRunCount']} runs / {totals['tilemapWordCount']} words"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
