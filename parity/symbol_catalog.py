#!/usr/bin/env python3
"""Parse, search, and export the pinned disassembly's WLA symbol catalog."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT = PARITY_DIR / "reports" / "symbols.json"
LABEL_PATTERN = re.compile(r"^([0-9A-Fa-f]{2}):([0-9A-Fa-f]{4})\s+(.+?)\s*$")


@dataclass(frozen=True)
class Symbol:
    name: str
    snes_address: int

    @property
    def address(self) -> str:
        return f"{self.snes_address >> 16:02X}:{self.snes_address & 0xFFFF:04X}"

    def json_record(self) -> Dict[str, object]:
        return {
            "name": self.name,
            "address": self.address,
            "snesAddress": self.snes_address,
        }


class SymbolCatalog:
    def __init__(self, symbols: Iterable[Symbol]) -> None:
        self.symbols = tuple(symbols)
        self._by_name: Dict[str, List[Symbol]] = {}
        self._by_address: Dict[int, List[Symbol]] = {}
        for symbol in self.symbols:
            self._by_name.setdefault(symbol.name, []).append(symbol)
            self._by_address.setdefault(symbol.snes_address, []).append(symbol)

    @classmethod
    def read(cls, path: Path) -> "SymbolCatalog":
        symbols: List[Symbol] = []
        in_labels = False
        for line_number, raw_line in enumerate(
            path.read_text(encoding="utf-8", errors="strict").splitlines(), start=1
        ):
            line = raw_line.strip()
            if line.startswith("[") and line.endswith("]"):
                if in_labels:
                    break
                in_labels = line == "[labels]"
                continue
            if not in_labels or not line or line.startswith(";"):
                continue
            match = LABEL_PATTERN.fullmatch(line)
            if match is None:
                raise ValueError(f"Malformed label at {path}:{line_number}: {raw_line!r}")
            bank, offset, name = match.groups()
            symbols.append(Symbol(name=name, snes_address=(int(bank, 16) << 16) | int(offset, 16)))
        if not symbols:
            raise ValueError(f"No [labels] entries found in {path}")
        return cls(symbols)

    def exact(self, name: str) -> Sequence[Symbol]:
        return tuple(self._by_name.get(name, ()))

    def search(self, query: str) -> Sequence[Symbol]:
        lowered = query.casefold()
        return tuple(symbol for symbol in self.symbols if lowered in symbol.name.casefold())

    def at(self, snes_address: int) -> Sequence[Symbol]:
        return tuple(self._by_address.get(snes_address, ()))


def parse_address(value: str) -> int:
    normalized = value.strip().replace("$", "").replace(":", "")
    if normalized.lower().startswith("0x"):
        normalized = normalized[2:]
    if not re.fullmatch(r"[0-9A-Fa-f]{6}", normalized):
        raise argparse.ArgumentTypeError("address must look like AC:AA00, $ACAA00, or 0xACAA00")
    return int(normalized, 16)


def main() -> int:
    parser = argparse.ArgumentParser(description="Search or export the WLA source symbol catalog.")
    parser.add_argument("queries", nargs="*", help="case-insensitive symbol-name queries")
    parser.add_argument("--address", action="append", type=parse_address, default=[])
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument(
        "--json",
        type=Path,
        nargs="?",
        const=DEFAULT_REPORT,
        help=f"write deterministic JSON (default: {DEFAULT_REPORT})",
    )
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    if not symbols_path.is_file():
        print(f"ERROR: missing {symbols_path}; run ./gradlew parityBuildReference", file=sys.stderr)
        return 2

    catalog = SymbolCatalog.read(symbols_path)
    selected: List[Symbol] = []
    for query in args.queries:
        matches = catalog.exact(query) or catalog.search(query)
        selected.extend(matches)
    for address in args.address:
        selected.extend(catalog.at(address))
    if args.queries or args.address:
        seen = set()
        for symbol in selected:
            key = (symbol.name, symbol.snes_address)
            if key in seen:
                continue
            seen.add(key)
            print(f"{symbol.address} {symbol.name}")

    if args.json is not None:
        output_path = args.json.expanduser().resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        commit = subprocess.run(
            ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()
        payload = {
            "schemaVersion": 1,
            "disassemblyCommit": commit,
            "symbolCount": len(catalog.symbols),
            "symbols": [symbol.json_record() for symbol in catalog.symbols],
        }
        output_path.write_text(
            json.dumps(payload, indent=2, sort_keys=False) + "\n",
            encoding="utf-8",
        )
        print(f"Wrote {len(catalog.symbols)} symbols to {output_path}")
    elif not args.queries and not args.address:
        print(f"Loaded {len(catalog.symbols)} symbols from {symbols_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
