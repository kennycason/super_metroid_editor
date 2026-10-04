#!/usr/bin/env python3
"""Generate and validate the active NTSC incbin asset-range manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
REFERENCE_FILE = PARITY_DIR / "reference.properties"
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT = PARITY_DIR / "reports" / "assets.json"
INCBIN_PATTERN = re.compile(r'^\s*incbin\s+"([^"]+)"(?:\s*;\s*\$([0-9A-Fa-f]+)\s+bytes)?')
LABEL_PATTERN = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:")


@dataclass(frozen=True)
class IncbinDeclaration:
    asset_path: Path
    asset_relative: str
    label: str
    source_file: str
    source_line: int
    documented_size: Optional[int]


def read_properties(path: Path) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip() or not value.strip():
            raise ValueError(f"Invalid property line in {path}: {raw_line!r}")
        result[key.strip()] = value.strip()
    return result


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def snes_to_pc(address: int) -> int:
    bank = (address >> 16) & 0xFF
    offset = address & 0xFFFF
    if bank < 0x80 or offset < 0x8000:
        raise ValueError(f"not a ROM LoROM address: {format_address(address)}")
    return ((bank & 0x7F) * 0x8000) + (offset - 0x8000)


def pc_to_snes(offset: int) -> int:
    if offset < 0:
        raise ValueError(f"negative PC offset: {offset}")
    return (0x80 + (offset // 0x8000)) << 16 | 0x8000 | (offset % 0x8000)


def format_address(address: int) -> str:
    return f"{address >> 16:02X}:{address & 0xFFFF:04X}"


def category_for(filename: str) -> str:
    stem = Path(filename).stem
    if stem.startswith("UNUSED_"):
        stem = stem[len("UNUSED_"):]
    categories = (
        ("SamusTiles_", "samus-tiles"),
        ("LevelData_", "level-data"),
        ("AnimatedTiles_", "animated-tiles"),
        ("UNUSED_AnimatedTiles_", "animated-tiles"),
        ("ItemPLMGraphics_", "item-plm-graphics"),
        ("CRE_Tiles", "tiles"),
        ("CRE_TileTable", "tile-table"),
        ("Tiles_", "tiles"),
        ("TileTable_", "tile-table"),
        ("TileTables_", "tile-table"),
        ("Palette_", "palette"),
        ("Palettes_", "palette"),
        ("Background_", "background"),
        ("Music_", "music"),
    )
    for prefix, category in categories:
        if stem.startswith(prefix):
            return category
    if stem == "SPCEngine":
        return "spc-engine"
    if "Tilemap" in stem:
        return "tilemap"
    return "other"


def scan_incbins(disassembly: Path) -> List[IncbinDeclaration]:
    declarations: List[IncbinDeclaration] = []
    for source_path in sorted((disassembly / "src").rglob("*.asm")):
        current_label: Optional[str] = None
        for line_number, raw_line in enumerate(source_path.read_text(encoding="utf-8").splitlines(), start=1):
            code = raw_line.split(";", 1)[0]
            label_match = LABEL_PATTERN.match(code)
            if label_match is not None:
                current_label = label_match.group(1)
            incbin_match = INCBIN_PATTERN.match(raw_line)
            if incbin_match is None:
                continue
            if current_label is None:
                raise ValueError(f"incbin has no preceding global label at {source_path}:{line_number}")
            declared_path, documented_hex_size = incbin_match.groups()
            asset_path = (source_path.parent / declared_path).resolve()
            try:
                asset_relative = asset_path.relative_to(disassembly).as_posix()
            except ValueError as error:
                raise ValueError(
                    f"incbin escapes the disassembly checkout at {source_path}:{line_number}: {declared_path}"
                ) from error
            declarations.append(
                IncbinDeclaration(
                    asset_path=asset_path,
                    asset_relative=asset_relative,
                    label=current_label,
                    source_file=source_path.relative_to(disassembly).as_posix(),
                    source_line=line_number,
                    documented_size=int(documented_hex_size, 16) if documented_hex_size else None,
                )
            )
    return declarations


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate the source-backed NTSC asset address/size/hash manifest."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    reference = read_properties(REFERENCE_FILE)
    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    built_rom_path = disassembly / "SM.sfc"
    if not symbols_path.is_file() or not built_rom_path.is_file():
        fail(f"missing built reference outputs in {disassembly}; run ./gradlew parityBuildReference")

    catalog = SymbolCatalog.read(symbols_path)
    rom = built_rom_path.read_bytes()
    actual_rom_hash = sha256_bytes(rom)
    if actual_rom_hash != reference["rom.sha256"]:
        fail(f"built reference ROM SHA-256 is {actual_rom_hash}; expected {reference['rom.sha256']}")

    declarations = scan_incbins(disassembly)
    by_asset: Dict[str, List[IncbinDeclaration]] = {}
    for declaration in declarations:
        by_asset.setdefault(declaration.asset_relative, []).append(declaration)

    extracted_assets = sorted(
        path.resolve()
        for path in (disassembly / "data").rglob("*.bin")
        if path.is_file()
    )
    records: List[Dict[str, object]] = []
    ranges: List[Tuple[int, int, str]] = []
    documented_size_mismatches: List[Dict[str, object]] = []
    for asset_path in extracted_assets:
        relative = asset_path.relative_to(disassembly).as_posix()
        matches = by_asset.get(relative, [])
        if len(matches) != 1:
            fail(f"expected one incbin declaration for {relative}, found {len(matches)}")
        declaration = matches[0]
        named_symbols = catalog.exact(declaration.label)
        if len(named_symbols) != 1:
            fail(
                f"expected one symbol named {declaration.label} for {relative}, found {len(named_symbols)}"
            )
        symbol = named_symbols[0]
        data = asset_path.read_bytes()
        if declaration.documented_size is not None and declaration.documented_size != len(data):
            documented_size_mismatches.append(
                {
                    "asset": relative,
                    "documentedSize": declaration.documented_size,
                    "actualSize": len(data),
                    "sourceFile": declaration.source_file,
                    "sourceLine": declaration.source_line,
                }
            )
        try:
            pc_offset = snes_to_pc(symbol.snes_address)
        except ValueError as error:
            fail(f"asset {relative} has invalid source address: {error}")
        end_pc = pc_offset + len(data)
        if end_pc > len(rom):
            fail(f"asset {relative} exceeds the reference ROM at PC 0x{pc_offset:X}")
        rom_data = rom[pc_offset:end_pc]
        if rom_data != data:
            fail(
                f"asset {relative} does not match ROM range PC 0x{pc_offset:06X}..0x{end_pc:06X}"
            )
        ranges.append((pc_offset, end_pc, relative))
        aliases = sorted(item.name for item in catalog.at(symbol.snes_address))
        records.append(
            {
                "name": declaration.label,
                "asset": relative,
                "category": category_for(asset_path.name),
                "unused": asset_path.stem.startswith("UNUSED_"),
                "address": symbol.address,
                "snesAddress": symbol.snes_address,
                "endAddressInclusive": format_address(pc_to_snes(end_pc - 1)),
                "pcOffset": pc_offset,
                "endPcExclusive": end_pc,
                "size": len(data),
                "documentedSize": declaration.documented_size,
                "documentedSizeMatches": declaration.documented_size in (None, len(data)),
                "sha256": sha256_bytes(data),
                "aliasesAtStart": aliases,
                "sourceFile": declaration.source_file,
                "sourceLine": declaration.source_line,
            }
        )

    extracted_relatives = {path.relative_to(disassembly).as_posix() for path in extracted_assets}
    inactive = sorted(
        declaration.asset_relative
        for declaration in declarations
        if declaration.asset_relative not in extracted_relatives
    )
    expected_declarations = int(reference["assets.sourceIncbin.count"])
    expected_assets = int(reference["assets.ntsc.count"])
    expected_inactive = int(reference["assets.inactiveOrUnavailable.count"])
    if len(declarations) != expected_declarations:
        fail(f"found {len(declarations)} source incbins; expected {expected_declarations}")
    if len(records) != expected_assets:
        fail(f"manifested {len(records)} active assets; expected {expected_assets}")
    if len(inactive) != expected_inactive:
        fail(f"found {len(inactive)} inactive/unavailable incbins; expected {expected_inactive}")

    # Active incbins should partition their source-owned payloads without overlap.
    for previous, current in zip(sorted(ranges), sorted(ranges)[1:]):
        if current[0] < previous[1]:
            fail(
                f"active asset ranges overlap: {previous[2]} ends at PC 0x{previous[1]:06X}, "
                f"{current[2]} starts at PC 0x{current[0]:06X}"
            )

    records.sort(key=lambda record: (int(record["pcOffset"]), str(record["asset"])))
    commit = subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()
    payload = {
        "schemaVersion": 1,
        "variant": "NTSC-U unheadered",
        "disassemblyCommit": commit,
        "romSha256": actual_rom_hash,
        "sourceIncbinCount": len(declarations),
        "activeAssetCount": len(records),
        "inactiveOrUnavailableIncbinCount": len(inactive),
        "inactiveOrUnavailableIncbins": inactive,
        "sourceCommentSizeMismatchCount": len(documented_size_mismatches),
        "sourceCommentSizeMismatches": documented_size_mismatches,
        "assets": records,
    }
    output_path = args.output.expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Asset manifest valid")
    print(f"  Active assets: {len(records)}")
    print(f"  Declared inactive/unavailable assets: {len(inactive)}")
    print(f"  Advisory source-comment size mismatches: {len(documented_size_mismatches)}")
    print(f"  Every active asset matches its exact ROM range")
    print(f"  Output: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
