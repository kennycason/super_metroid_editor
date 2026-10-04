#!/usr/bin/env python3
"""Build a source/ROM manifest for all bank-$A0 enemy species headers."""

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

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "enemy-headers.json"

HEADER_LABEL = re.compile(
    r"^((?:UNUSED_)?EnemyHeaders_[A-Za-z0-9_]+):\s*;([0-9A-Fa-f]{6});"
)
SIMPLE_LABEL = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
BANK_EXPRESSION = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)>>16$")
REGIONAL_EXPRESSION = re.compile(
    r"^regional\(\$([0-9A-Fa-f]+),\s*\$([0-9A-Fa-f]+)\)$"
)

# The macro inserts two four-byte zero fields after deathAnimation and variantIndex.
# Those bytes are verified separately as padding and are not source arguments.
FIELD_SPECS: Tuple[Tuple[str, int, int], ...] = (
    ("tileDataSize", 0x00, 2),
    ("palette", 0x02, 2),
    ("health", 0x04, 2),
    ("damage", 0x06, 2),
    ("width", 0x08, 2),
    ("height", 0x0A, 2),
    ("bank", 0x0C, 1),
    ("hurtAITime", 0x0D, 1),
    ("cry", 0x0E, 2),
    ("bossID", 0x10, 2),
    ("initAI", 0x12, 2),
    ("parts", 0x14, 2),
    ("unused", 0x16, 2),
    ("mainAI", 0x18, 2),
    ("grappleAI", 0x1A, 2),
    ("hurtAI", 0x1C, 2),
    ("frozenAI", 0x1E, 2),
    ("timeIsFrozen", 0x20, 2),
    ("deathAnimation", 0x22, 2),
    ("powerBombReaction", 0x28, 2),
    ("variantIndex", 0x2A, 2),
    ("enemyTouch", 0x30, 2),
    ("enemyShot", 0x32, 2),
    ("spritemap", 0x34, 2),
    ("tileData", 0x36, 3),
    ("layer", 0x39, 1),
    ("drops", 0x3A, 2),
    ("vulnerabilities", 0x3C, 2),
    ("name", 0x3E, 2),
)
PADDING_RANGES = ((0x24, 4), (0x2C, 4))


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


def read_value(rom: bytes, address: int, size: int) -> int:
    pc = snes_to_pc(address)
    return sum(rom[pc + index] << (index * 8) for index in range(size))


def read_bytes(rom: bytes, address: int, size: int) -> bytes:
    pc = snes_to_pc(address)
    return rom[pc : pc + size]


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


def load_json(path: Path, task: str) -> Dict[str, object]:
    if not path.is_file():
        fail(f"missing {path}; run ./gradlew {task}")
    return json.loads(path.read_text(encoding="utf-8"))


def source_revision(disassembly: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def require_unique_symbol(catalog: SymbolCatalog, name: str) -> int:
    matches = catalog.exact(name)
    if len(matches) != 1:
        fail(f"expected one source symbol named {name}, found {len(matches)}")
    return matches[0].snes_address


def parse_argument_line(line: str) -> Tuple[str, str] | None:
    text = line.strip()
    if not text.startswith("%") or text.startswith("%EnemyHeader("):
        return None
    if text.endswith(","):
        text = text[:-1]
    opening = text.find("(")
    if opening < 2:
        fail(f"malformed enemy-header macro argument: {line!r}")
    field = text[1:opening]
    tail = text[opening + 1 :]
    if field == "name":
        if not tail.endswith("))"):
            fail(f"enemy-header name does not close the outer macro: {line!r}")
        expression = tail[:-2]
    else:
        if not tail.endswith(")"):
            fail(f"malformed enemy-header field: {line!r}")
        expression = tail[:-1]
    return field, expression


def evaluate_expression(expression: str, catalog: SymbolCatalog) -> Tuple[int, str | None]:
    if expression.startswith("$") and re.fullmatch(r"\$[0-9A-Fa-f]+", expression):
        return int(expression[1:], 16), None
    if re.fullmatch(r"\d+", expression):
        return int(expression), None
    regional = REGIONAL_EXPRESSION.fullmatch(expression)
    if regional is not None:
        return int(regional.group(1), 16), None  # pinned reference is NTSC
    bank = BANK_EXPRESSION.fullmatch(expression)
    if bank is not None:
        target = require_unique_symbol(catalog, bank.group(1))
        return target >> 16, bank.group(1)
    if SIMPLE_LABEL.fullmatch(expression):
        return require_unique_symbol(catalog, expression), expression
    fail(f"unsupported enemy-header expression {expression!r}")
    raise AssertionError


def decode_standard_4bpp(data: bytes) -> bytes:
    if not data or len(data) % 32:
        fail(f"enemy GRAPHADR range has invalid 4bpp size {len(data)}")
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


def graphics_segments(
    start: int,
    size: int,
    assets: Sequence[Dict[str, object]],
) -> List[Dict[str, object]]:
    if size == 0:
        return []
    end = start + size
    position = start
    segments: List[Dict[str, object]] = []
    for asset in assets:
        asset_start = int(asset["snesAddress"])
        asset_end = asset_start + int(asset["size"])
        if asset_end <= position:
            continue
        if asset_start >= end:
            break
        if asset_start > position:
            fail(
                f"enemy GRAPHADR {formatted(start)} + ${size:X} has an uncovered gap at "
                f"{formatted(position)}"
            )
        segment_end = min(end, asset_end)
        segments.append(
            {
                "sourceLabel": asset["name"],
                "asset": asset["asset"],
                "assetAddress": asset["address"],
                "assetSnesAddress": asset_start,
                "assetSize": asset["size"],
                "rangeAddress": formatted(position),
                "rangeSnesAddress": position,
                "assetOffset": position - asset_start,
                "size": segment_end - position,
            }
        )
        position = segment_end
        if position == end:
            break
    if position != end:
        fail(
            f"enemy GRAPHADR {formatted(start)} + ${size:X} ends outside extracted assets at "
            f"{formatted(position)}"
        )
    return segments


def build_headers(
    lines: Sequence[str],
    catalog: SymbolCatalog,
    rom: bytes,
    assets: Sequence[Dict[str, object]],
) -> List[Dict[str, object]]:
    expected_fields = [name for name, _offset, _size in FIELD_SPECS]
    headers: List[Dict[str, object]] = []
    for index, line in enumerate(lines):
        label_match = HEADER_LABEL.match(line)
        if label_match is None:
            continue
        label = label_match.group(1)
        annotation_address = int(label_match.group(2), 16)
        address = require_unique_symbol(catalog, label)
        if address != annotation_address or address >> 16 != 0xA0:
            fail(f"{label} is not at its annotated bank-$A0 address")

        arguments: List[Tuple[str, str]] = []
        for source_line in lines[index + 1 :]:
            parsed = parse_argument_line(source_line)
            if parsed is None:
                continue
            arguments.append(parsed)
            if parsed[0] == "name":
                break
        if [field for field, _expression in arguments] != expected_fields:
            fail(f"{label} does not have the exact 29-field EnemyHeader macro shape")

        fields: Dict[str, object] = {}
        for (field, offset, size), (_parsed_field, expression) in zip(FIELD_SPECS, arguments):
            field_address = require_unique_symbol(catalog, f"{label}_{field}")
            if field_address != address + offset:
                fail(f"{label}_{field} is at the wrong macro offset")
            evaluated, target_label = evaluate_expression(expression, catalog)
            expected = evaluated & ((1 << (size * 8)) - 1)
            actual = read_value(rom, field_address, size)
            if actual != expected:
                fail(
                    f"{label}.{field} source expression {expression} evaluates to ${expected:X}; "
                    f"rebuilt ROM contains ${actual:X}"
                )
            record: Dict[str, object] = {
                "offset": offset,
                "size": size,
                "value": actual,
                "sourceExpression": expression,
            }
            if target_label is not None:
                target = require_unique_symbol(catalog, target_label)
                record["targetLabel"] = target_label
                record["targetAddress"] = formatted(target)
                record["targetSnesAddress"] = target
            fields[field] = record

        for offset, size in PADDING_RANGES:
            if any(read_bytes(rom, address + offset, size)):
                fail(f"{label} macro padding at +${offset:02X} is not zero")

        raw_header = read_bytes(rom, address, 0x40)
        raw_tile_size = int(fields["tileDataSize"]["value"])
        transfer_size = raw_tile_size & 0x7FFF
        graphics_address = int(fields["tileData"]["value"])
        if transfer_size and graphics_address == 0:
            fail(f"{label} has nonzero tile size with a null GRAPHADR")
        graphics: Dict[str, object] = {
            "rawTileDataSize": raw_tile_size,
            "transferSize": transfer_size,
            "alternateVramLayout": bool(raw_tile_size & 0x8000),
            "sourceExpression": fields["tileData"]["sourceExpression"],
            "address": formatted(graphics_address) if graphics_address else "00:0000",
            "snesAddress": graphics_address,
            "tileCount": transfer_size // 32,
            "segments": graphics_segments(graphics_address, transfer_size, assets),
        }
        if transfer_size:
            data = read_bytes(rom, graphics_address, transfer_size)
            pixels = decode_standard_4bpp(data)
            graphics.update(
                {
                    "endAddressInclusive": formatted(graphics_address + transfer_size - 1),
                    "sha256": sha256(data),
                    "pixelCount": len(pixels),
                    "pixelSha256": sha256(pixels),
                }
            )
        headers.append(
            {
                "sourceLabel": label,
                "sourceDeclaredUnused": label.startswith("UNUSED_"),
                "address": formatted(address),
                "snesAddress": address,
                "speciesId": address & 0xFFFF,
                "size": 0x40,
                "rawHeaderSha256": sha256(raw_header),
                "fields": fields,
                "graphics": graphics,
            }
        )
    headers.sort(key=lambda record: int(record["snesAddress"]))
    if len(headers) != 164:
        fail(f"expected 164 bank-$A0 enemy headers, found {len(headers)}")
    if len({int(record["speciesId"]) for record in headers}) != len(headers):
        fail("enemy species IDs are not unique")
    return headers


def build_aliases(headers: Sequence[Dict[str, object]]) -> Dict[str, object]:
    nonempty = [record for record in headers if int(record["graphics"]["transferSize"]) > 0]
    by_start: Dict[int, List[Dict[str, object]]] = defaultdict(list)
    by_range: Dict[Tuple[int, int], List[Dict[str, object]]] = defaultdict(list)
    for header in nonempty:
        graphics = header["graphics"]
        start = int(graphics["snesAddress"])
        size = int(graphics["transferSize"])
        by_start[start].append(header)
        by_range[(start, size)].append(header)

    start_aliases = []
    for start, members in sorted(by_start.items()):
        if len(members) < 2:
            continue
        start_aliases.append(
            {
                "address": formatted(start),
                "snesAddress": start,
                "speciesLabels": [str(member["sourceLabel"]) for member in members],
                "transferSizes": sorted(
                    {int(member["graphics"]["transferSize"]) for member in members}
                ),
            }
        )

    exact_range_aliases = []
    for (start, size), members in sorted(by_range.items()):
        if len(members) < 2:
            continue
        exact_range_aliases.append(
            {
                "address": formatted(start),
                "snesAddress": start,
                "transferSize": size,
                "speciesLabels": [str(member["sourceLabel"]) for member in members],
            }
        )

    unique_ranges = sorted(by_range)
    overlaps = []
    for index, (left_start, left_size) in enumerate(unique_ranges):
        left_end = left_start + left_size
        for right_start, right_size in unique_ranges[index + 1 :]:
            if right_start >= left_end:
                break
            overlap_size = min(left_end, right_start + right_size) - right_start
            overlaps.append(
                {
                    "leftAddress": formatted(left_start),
                    "leftSnesAddress": left_start,
                    "leftTransferSize": left_size,
                    "leftSpeciesLabels": [
                        str(record["sourceLabel"]) for record in by_range[(left_start, left_size)]
                    ],
                    "rightAddress": formatted(right_start),
                    "rightSnesAddress": right_start,
                    "rightTransferSize": right_size,
                    "rightSpeciesLabels": [
                        str(record["sourceLabel"]) for record in by_range[(right_start, right_size)]
                    ],
                    "overlapSize": overlap_size,
                }
            )
    return {
        "startAliases": start_aliases,
        "exactRangeAliases": exact_range_aliases,
        "overlaps": overlaps,
    }


def flattened_fields(headers: Sequence[Dict[str, object]]) -> List[Dict[str, object]]:
    result: List[Dict[str, object]] = []
    for header in headers:
        for field, _offset, _size in FIELD_SPECS:
            value = header["fields"][field]
            result.append(
                {
                    "sourceLabel": header["sourceLabel"],
                    "field": field,
                    "value": value["value"],
                    "sourceExpression": value["sourceExpression"],
                }
            )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate source-backed bank-$A0 enemy headers and GRAPHADR ownership."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    assets_manifest = load_json(args.asset_manifest.expanduser().resolve(), "parityAssets")
    revision = source_revision(disassembly)
    if assets_manifest["disassemblyCommit"] != revision:
        fail("asset manifest and disassembly checkout use different commits")
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    if not symbols_path.is_file() or not rom_path.is_file():
        fail(f"missing reference build outputs in {disassembly}; run ./gradlew parityBuildReference")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    lines = (disassembly / "src" / "bank_A0.asm").read_text(encoding="utf-8").splitlines()
    assets = sorted(assets_manifest["assets"], key=lambda record: int(record["snesAddress"]))
    headers = build_headers(lines, catalog, rom, assets)
    aliases = build_aliases(headers)
    fields = flattened_fields(headers)
    graphics = [record["graphics"] | {"sourceLabel": record["sourceLabel"]} for record in headers]
    nonempty = [record for record in graphics if int(record["transferSize"]) > 0]
    segment_labels = {
        str(segment["sourceLabel"])
        for record in nonempty
        for segment in record["segments"]
    }
    totals = {
        "headerCount": len(headers),
        "headerByteCount": len(headers) * 0x40,
        "macroFieldCount": len(fields),
        "sourceDeclaredUnusedHeaderCount": sum(
            bool(record["sourceDeclaredUnused"]) for record in headers
        ),
        "nonEmptyGraphicsHeaderCount": len(nonempty),
        "zeroSizeGraphicsHeaderCount": len(headers) - len(nonempty),
        "alternateVramLayoutHeaderCount": sum(
            bool(record["alternateVramLayout"]) for record in graphics
        ),
        "uniqueGraphicsStartCount": len({int(record["snesAddress"]) for record in nonempty}),
        "uniqueGraphicsRangeCount": len(
            {(int(record["snesAddress"]), int(record["transferSize"])) for record in nonempty}
        ),
        "graphicsAssociationByteCount": sum(int(record["transferSize"]) for record in nonempty),
        "graphicsTileAssociationCount": sum(int(record["tileCount"]) for record in nonempty),
        "sourceAssetCount": len(segment_labels),
        "sourceAssetSegmentAssociationCount": sum(
            len(record["segments"]) for record in nonempty
        ),
        "multiAssetGraphicsHeaderCount": sum(len(record["segments"]) > 1 for record in nonempty),
        "startAliasGroupCount": len(aliases["startAliases"]),
        "exactRangeAliasGroupCount": len(aliases["exactRangeAliases"]),
        "overlappingUniqueRangePairCount": len(aliases["overlaps"]),
        "crossStartOverlapPairCount": sum(
            int(record["leftSnesAddress"]) != int(record["rightSnesAddress"])
            for record in aliases["overlaps"]
        ),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": revision,
        "oracle": "EnemyHeader macro expressions, WLA field labels, extracted assets, and rebuilt ROM",
        "fieldLayout": [
            {"name": field, "offset": offset, "size": size}
            for field, offset, size in FIELD_SPECS
        ],
        "paddingRanges": [
            {"offset": offset, "size": size, "expectedValue": 0}
            for offset, size in PADDING_RANGES
        ],
        "totals": totals,
        "aggregateHashes": {
            "headers": aggregate_hash(
                headers, ("sourceLabel", "snesAddress", "rawHeaderSha256")
            ),
            "sourceFields": aggregate_hash(
                fields, ("sourceLabel", "field", "value", "sourceExpression")
            ),
            "graphics": aggregate_hash(
                nonempty,
                (
                    "sourceLabel",
                    "snesAddress",
                    "transferSize",
                    "sha256",
                    "pixelSha256",
                    "segments",
                ),
            ),
            "overlaps": aggregate_hash(
                aliases["overlaps"],
                (
                    "leftSnesAddress",
                    "leftTransferSize",
                    "leftSpeciesLabels",
                    "rightSnesAddress",
                    "rightTransferSize",
                    "rightSpeciesLabels",
                    "overlapSize",
                ),
            ),
        },
        "headers": headers,
        "graphicsAliases": aliases,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Enemy-header/GRAPHADR manifest valid")
    print(
        f"  Headers: {totals['headerCount']} / {totals['macroFieldCount']} macro fields / "
        f"{totals['sourceDeclaredUnusedHeaderCount']} source-declared unused"
    )
    print(
        f"  Graphics: {totals['nonEmptyGraphicsHeaderCount']} species associations / "
        f"{totals['uniqueGraphicsRangeCount']} unique ranges / {totals['sourceAssetCount']} assets"
    )
    print(
        f"  Ownership: {totals['startAliasGroupCount']} shared starts / "
        f"{totals['overlappingUniqueRangePairCount']} overlapping unique range pairs / "
        f"{totals['multiAssetGraphicsHeaderCount']} multi-asset species ranges"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
