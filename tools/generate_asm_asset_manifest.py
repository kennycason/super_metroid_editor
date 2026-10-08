#!/usr/bin/env python3
"""Generate SMEDIT's ROM-to-disassembly asset range manifest.

The pinned disassembly's ripper is the authority for the ranges.  This script
executes its extraction plan against an empty ROM while replacing its file
writer with a recorder; no ROM bytes or extracted assets enter the manifest.
"""

from __future__ import annotations

import argparse
import importlib.util
import inspect
from pathlib import Path
import subprocess


ROM_SIZE = 0x300000
SENTINEL_ROOT = Path("/__smedit_asset_manifest__")


def load_module(script: Path):
    spec = importlib.util.spec_from_file_location("smedit_rip_assets", script)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load {script}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("disassembly", type=Path, help="pinned sm_disassembly checkout")
    parser.add_argument("output", type=Path, help="TSV file to write")
    args = parser.parse_args()

    script = args.disassembly.resolve() / "tools" / "rip_assets.py"
    if not script.is_file():
        raise FileNotFoundError(script)
    module = load_module(script)
    commit = subprocess.run(
        ["git", "-C", str(args.disassembly.resolve()), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()
    entries: dict[str, tuple[int, int]] = {}

    def record(path: Path, data: bytes) -> None:
        caller = inspect.currentframe().f_back
        if caller is None:
            raise RuntimeError("Could not inspect rip_assets.dump")
        offset = caller.f_locals.get("offset_start")
        end = caller.f_locals.get("offset_end")
        if not isinstance(offset, int) or not isinstance(end, int):
            raise RuntimeError(f"Unexpected rip_assets writer call for {path}")
        relative = Path(path).relative_to(SENTINEL_ROOT).as_posix()
        entries[relative] = (offset, end - offset)

    module.write_file = record
    module.dump_rom(bytes(ROM_SIZE), SENTINEL_ROOT, False)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        output.write(f"# sm_disassembly.commit={commit}\n")
        output.write("# Generated from pinned tools/rip_assets.py (NTSC)\n")
        output.write("# path\tpc_offset\tlength\n")
        for path, (offset, length) in entries.items():
            output.write(f"{path}\t0x{offset:06X}\t0x{length:X}\n")

    print(f"Wrote {len(entries)} asset ranges to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
