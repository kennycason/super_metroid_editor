#!/usr/bin/env python3
"""Independent LZ5 oracle derived from Super Metroid's $80:B119 routine."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "lz5.json"
MAX_ENGINE_OUTPUT = 0x10000


class Lz5FormatError(ValueError):
    pass


@dataclass(frozen=True)
class DecodeResult:
    data: bytes
    consumed: int
    command_counts: Dict[int, int]


def decode_lz5(source: bytes, start: int = 0, max_output: int = MAX_ENGINE_OUTPUT) -> DecodeResult:
    """Decode one strict stream using the behavior of Decompression_VariableDestination."""
    if start < 0 or start >= len(source):
        raise Lz5FormatError(f"start offset {start} is outside {len(source)} source bytes")
    position = start
    output = bytearray()
    command_counts: Counter[int] = Counter()

    def read_byte(context: str) -> int:
        nonlocal position
        if position >= len(source):
            raise Lz5FormatError(f"truncated {context} at source offset {position - start}")
        value = source[position]
        position += 1
        return value

    while True:
        header = read_byte("command header")
        if header == 0xFF:
            return DecodeResult(bytes(output), position - start, dict(sorted(command_counts.items())))

        top_bits = header >> 5
        if top_bits == 7:
            command = (header >> 2) & 7
            length = (((header & 3) << 8) | read_byte("extended length")) + 1
        else:
            command = top_bits
            length = (header & 0x1F) + 1
        if len(output) + length > max_output:
            raise Lz5FormatError(
                f"command {command} would exceed the {max_output}-byte engine destination limit"
            )
        command_counts[command] += 1

        if command == 0:
            if position + length > len(source):
                raise Lz5FormatError(
                    f"truncated direct copy at source offset {position - start}: need {length} bytes"
                )
            output.extend(source[position:position + length])
            position += length
        elif command == 1:
            output.extend([read_byte("byte-fill operand")] * length)
        elif command == 2:
            first = read_byte("word-fill operand")
            second = read_byte("word-fill operand")
            output.extend(first if index % 2 == 0 else second for index in range(length))
        elif command == 3:
            first = read_byte("incrementing-fill operand")
            output.extend((first + index) & 0xFF for index in range(length))
        elif command in (4, 5):
            low = read_byte("absolute-copy operand")
            high = read_byte("absolute-copy operand")
            copy_from = low | (high << 8)
            for _ in range(length):
                if copy_from < 0 or copy_from >= len(output):
                    raise Lz5FormatError(
                        f"command {command} reads unwritten output offset 0x{copy_from:04X}"
                    )
                value = output[copy_from]
                output.append(value ^ 0xFF if command == 5 else value)
                copy_from += 1
        elif command in (6, 7):
            distance = read_byte("sliding-copy operand")
            copy_from = len(output) - distance
            for _ in range(length):
                if copy_from < 0 or copy_from >= len(output):
                    raise Lz5FormatError(
                        f"command {command} reads unwritten output offset {copy_from}"
                    )
                value = output[copy_from]
                output.append(value ^ 0xFF if command == 7 else value)
                copy_from += 1
        else:  # pragma: no cover - the three command bits make this unreachable
            raise Lz5FormatError(f"unsupported command {command}")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Classify and independently decode every exact extracted LZ5 stream."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    asset_manifest_path = args.asset_manifest.expanduser().resolve()
    if not asset_manifest_path.is_file():
        print(f"ERROR: missing {asset_manifest_path}; run ./gradlew parityAssets", file=sys.stderr)
        return 2
    asset_manifest = json.loads(asset_manifest_path.read_text(encoding="utf-8"))

    compressed: List[Dict[str, object]] = []
    rejected_exact_streams: List[Dict[str, str]] = []
    aggregate_commands: Counter[int] = Counter()
    for asset in asset_manifest["assets"]:
        asset_path = disassembly / str(asset["asset"])
        source = asset_path.read_bytes()
        try:
            result = decode_lz5(source)
        except Lz5FormatError:
            continue
        if result.consumed != len(source):
            rejected_exact_streams.append(
                {
                    "asset": str(asset["asset"]),
                    "reason": f"terminator consumed {result.consumed} of {len(source)} bytes",
                }
            )
            continue
        aggregate_commands.update(result.command_counts)
        compressed.append(
            {
                "name": asset["name"],
                "asset": asset["asset"],
                "category": asset["category"],
                "unused": asset["unused"],
                "address": asset["address"],
                "snesAddress": asset["snesAddress"],
                "compressedSize": len(source),
                "decompressedSize": len(result.data),
                "decompressedSha256": sha256(result.data),
                "commandCounts": {
                    str(command): count for command, count in result.command_counts.items()
                },
            }
        )

    compressed.sort(key=lambda record: (int(record["snesAddress"]), str(record["asset"])))
    categories = Counter(str(record["category"]) for record in compressed if not record["unused"])
    payload = {
        "schemaVersion": 1,
        "oracle": "Independent Python model of Decompression_VariableDestination at $80:B119",
        "maxEngineOutput": MAX_ENGINE_OUTPUT,
        "sourceAssetCount": len(asset_manifest["assets"]),
        "exactCompressedStreamCount": len(compressed),
        "activeCompressedStreamCount": sum(1 for record in compressed if not record["unused"]),
        "unusedCompressedStreamCount": sum(1 for record in compressed if record["unused"]),
        "maxDecompressedSize": max(int(record["decompressedSize"]) for record in compressed),
        "aggregateCommandCounts": {
            str(command): aggregate_commands[command] for command in range(8)
        },
        "activeCategoryCounts": dict(sorted(categories.items())),
        "validStreamWithTrailingDataCount": len(rejected_exact_streams),
        "validStreamsWithTrailingData": rejected_exact_streams,
        "streams": compressed,
    }
    output_path = args.output.expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Independent LZ5 oracle complete")
    print(f"  Exact compressed streams: {len(compressed)}")
    print(f"  Active: {payload['activeCompressedStreamCount']}")
    print(f"  Unused: {payload['unusedCompressedStreamCount']}")
    print(f"  Maximum decompressed size: {payload['maxDecompressedSize']} bytes")
    print(f"  Command counts: {payload['aggregateCommandCounts']}")
    print(f"  Output: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
