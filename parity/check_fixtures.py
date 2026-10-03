#!/usr/bin/env python3
"""Fail-fast validation for SMEDIT's private parity fixtures."""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path
from typing import Dict


PARITY_DIR = Path(__file__).resolve().parent
REFERENCE_FILE = PARITY_DIR / "reference.properties"
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"


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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate SMEDIT parity fixture identity.")
    parser.add_argument("--rom", type=Path, help="clean unheadered vanilla ROM")
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    args = parser.parse_args()

    reference = read_properties(REFERENCE_FILE)
    rom_setting = args.rom or (
        Path(os.environ["SMEDIT_TEST_ROM"]) if os.environ.get("SMEDIT_TEST_ROM") else None
    )
    if rom_setting is None:
        fail("SMEDIT_TEST_ROM is not set (the ROM is user-supplied and is never downloaded)")
    rom = rom_setting.expanduser().resolve()
    if not rom.is_file():
        fail(f"SMEDIT_TEST_ROM is not a file: {rom}")

    expected_size = int(reference["rom.size"])
    if rom.stat().st_size != expected_size:
        fail(f"ROM size is {rom.stat().st_size}; expected {expected_size} unheadered bytes")
    expected_hash = reference["rom.sha256"]
    actual_hash = sha256(rom)
    if actual_hash != expected_hash:
        fail(f"ROM SHA-256 is {actual_hash}; expected {expected_hash}")

    disassembly_setting = args.disassembly or Path(
        os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY))
    )
    disassembly = disassembly_setting.expanduser().resolve()
    if not (disassembly / ".git").is_dir():
        fail(f"disassembly is not a Git checkout: {disassembly}; run parity/bootstrap.py")
    for relative in ("src/main.asm", "src/bank_8F.asm", "tools/rip_assets.py"):
        if not (disassembly / relative).is_file():
            fail(f"disassembly is missing {relative}: {disassembly}")

    expected_commit = reference["disassembly.commit"]
    actual_commit = subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()
    if actual_commit != expected_commit:
        fail(f"disassembly commit is {actual_commit}; expected {expected_commit}")
    tracked_changes = subprocess.run(
        ["git", "-C", str(disassembly), "status", "--porcelain", "--untracked-files=no"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()
    if tracked_changes:
        fail(f"disassembly has tracked changes:\n{tracked_changes}")

    built_rom = disassembly / "SM.sfc"
    if built_rom.is_file():
        built_hash = sha256(built_rom)
        if built_rom.stat().st_size != expected_size or built_hash != expected_hash:
            fail(f"built SM.sfc is not byte-identical (SHA-256 {built_hash})")
        build_status = "byte-identical SM.sfc present"
    else:
        build_status = "source checkout valid; SM.sfc has not been built in this checkout"

    print("Parity fixtures valid")
    print(f"  ROM: {rom}")
    print(f"  SHA-256: {actual_hash}")
    print(f"  Disassembly: {disassembly}")
    print(f"  Commit: {actual_commit}")
    print(f"  Build: {build_status}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
