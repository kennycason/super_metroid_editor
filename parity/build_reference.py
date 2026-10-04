#!/usr/bin/env python3
"""Rebuild the pinned Super Metroid disassembly from a private clean ROM."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, Optional, Sequence


PARITY_DIR = Path(__file__).resolve().parent
REPO_ROOT = PARITY_DIR.parent
REFERENCE_FILE = PARITY_DIR / "reference.properties"
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASAR_SOURCE = PARITY_DIR / "work" / "asar"


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


def run(
    args: Sequence[str],
    *,
    cwd: Optional[Path] = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        list(args),
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )
    if completed.returncode != 0:
        if capture:
            if completed.stdout:
                print(completed.stdout.rstrip(), file=sys.stderr)
            if completed.stderr:
                print(completed.stderr.rstrip(), file=sys.stderr)
        fail(f"command failed ({completed.returncode}): {' '.join(args)}")
    return completed


def output(args: Sequence[str], *, cwd: Optional[Path] = None) -> str:
    return run(args, cwd=cwd, capture=True).stdout.strip()


def ensure_clean_pinned_checkout(
    directory: Path,
    *,
    repo_url: str,
    commit: str,
    label: str,
    clone_if_missing: bool,
) -> None:
    if directory.exists() and not (directory / ".git").is_dir():
        fail(f"{label} path exists but is not a Git checkout: {directory}")
    if not directory.exists():
        if not clone_if_missing:
            fail(f"{label} checkout is missing: {directory}")
        directory.parent.mkdir(parents=True, exist_ok=True)
        print(f"Cloning pinned {label} source into {directory}")
        run(["git", "clone", repo_url, str(directory)])

    tracked_changes = output(
        ["git", "-C", str(directory), "status", "--porcelain", "--untracked-files=no"]
    )
    if tracked_changes:
        fail(f"{label} checkout has tracked changes:\n{tracked_changes}")

    actual_commit = output(["git", "-C", str(directory), "rev-parse", "HEAD"])
    if actual_commit != commit:
        if not clone_if_missing:
            fail(f"{label} checkout is at {actual_commit}; expected {commit}")
        has_commit = subprocess.run(
            ["git", "-C", str(directory), "cat-file", "-e", f"{commit}^{{commit}}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        ).returncode == 0
        if not has_commit:
            print(f"Fetching pinned {label} commit {commit}")
            run(["git", "-C", str(directory), "fetch", "--depth=1", "origin", commit])
        run(["git", "-C", str(directory), "checkout", "--detach", commit])


def validate_rom(rom: Path, reference: Dict[str, str]) -> None:
    if not rom.is_file():
        fail(f"clean ROM is not a file: {rom}")
    expected_size = int(reference["rom.size"])
    if rom.stat().st_size != expected_size:
        fail(f"ROM size is {rom.stat().st_size}; expected {expected_size} unheadered bytes")
    actual_hash = sha256(rom)
    expected_hash = reference["rom.sha256"]
    if actual_hash != expected_hash:
        fail(f"ROM SHA-256 is {actual_hash}; expected {expected_hash}")


def managed_asar_binary(source: Path) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return source / "build" / "asar" / f"asar-standalone{suffix}"


def asar_version(asar: Path) -> str:
    completed = subprocess.run(
        [str(asar), "--version"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return completed.stdout.strip()


def resolve_asar(args: argparse.Namespace, reference: Dict[str, str]) -> Path:
    explicit = args.asar or (
        Path(os.environ["SMEDIT_ASAR"]) if os.environ.get("SMEDIT_ASAR") else None
    )
    if explicit is not None:
        asar = explicit.expanduser().resolve()
        if not asar.is_file():
            fail(f"configured Asar executable is not a file: {asar}")
    else:
        source = args.asar_source.expanduser().resolve()
        ensure_clean_pinned_checkout(
            source,
            repo_url=reference["assembler.repoUrl"],
            commit=reference["assembler.commit"],
            label="Asar",
            clone_if_missing=True,
        )
        asar = managed_asar_binary(source)
        if not asar.is_file():
            if shutil.which("cmake") is None:
                fail("CMake is required to build the pinned Asar source (or set SMEDIT_ASAR)")
            build_dir = source / "build"
            print("Configuring pinned Asar")
            run(
                ["cmake", "-S", str(source / "src"), "-B", str(build_dir), "-DCMAKE_BUILD_TYPE=Release"],
                capture=True,
            )
            print("Building pinned Asar")
            run(
                ["cmake", "--build", str(build_dir), "--target", "asar-standalone", "--parallel"],
                capture=True,
            )
        if not asar.is_file():
            fail(f"Asar build completed without expected executable: {asar}")

    version_output = asar_version(asar)
    expected_version = reference["assembler.version"]
    if f"Asar {expected_version}" not in version_output:
        fail(f"Asar version output does not contain 'Asar {expected_version}':\n{version_output}")
    return asar


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract private assets and build the pinned disassembly byte-identically."
    )
    parser.add_argument("--rom", type=Path, help="clean unheadered vanilla ROM")
    parser.add_argument("--disassembly", type=Path, help="pinned sm_disassembly checkout")
    parser.add_argument("--asar", type=Path, help="Asar 1.81 executable")
    parser.add_argument(
        "--asar-source",
        type=Path,
        default=DEFAULT_ASAR_SOURCE,
        help=f"managed Asar source location (default: {DEFAULT_ASAR_SOURCE.relative_to(REPO_ROOT)})",
    )
    args = parser.parse_args()

    reference = read_properties(REFERENCE_FILE)
    rom_setting = args.rom or (
        Path(os.environ["SMEDIT_TEST_ROM"]) if os.environ.get("SMEDIT_TEST_ROM") else None
    )
    if rom_setting is None:
        fail("SMEDIT_TEST_ROM is not set (the ROM is user-supplied and is never downloaded)")
    rom = rom_setting.expanduser().resolve()
    validate_rom(rom, reference)

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    ensure_clean_pinned_checkout(
        disassembly,
        repo_url=reference["disassembly.repoUrl"],
        commit=reference["disassembly.commit"],
        label="disassembly",
        clone_if_missing=False,
    )
    asar = resolve_asar(args, reference)

    print(f"Extracting source assets from {rom}")
    run(
        [sys.executable, "tools/rip_assets.py", str(rom), "-o", "data"],
        cwd=disassembly,
        capture=True,
    )
    asset_count = sum(1 for path in (disassembly / "data").rglob("*.bin") if path.is_file())
    expected_asset_count = int(reference["assets.ntsc.count"])
    if asset_count != expected_asset_count:
        fail(f"asset extraction produced {asset_count} .bin files; expected {expected_asset_count}")

    # Use the pinned project's own blank-ROM helper, then invoke the resolved native
    # assembler directly instead of its Linux-only bundled executable.
    run([sys.executable, "tools/ff_file.py", "../SM.sfc"], cwd=disassembly, capture=True)
    built_rom = disassembly / "SM.sfc"
    symbols = disassembly / "symbols.sym"
    if symbols.exists():
        symbols.unlink()

    print(f"Assembling with {asar}")
    run(
        [
            str(asar),
            "--no-title-check",
            "--symbols=wla",
            "--symbols-path=symbols.sym",
            "src/main.asm",
            "SM.sfc",
        ],
        cwd=disassembly,
        capture=True,
    )

    if not built_rom.is_file():
        fail(f"assembler did not produce {built_rom}")
    built_hash = sha256(built_rom)
    if built_rom.stat().st_size != int(reference["rom.size"]) or built_hash != reference["rom.sha256"]:
        fail(f"rebuilt SM.sfc is not byte-identical (SHA-256 {built_hash})")
    if not symbols.is_file() or symbols.stat().st_size == 0:
        fail(f"assembler did not produce a non-empty symbol file: {symbols}")

    symbol_count = 0
    in_labels = False
    for line in symbols.read_text(encoding="utf-8", errors="strict").splitlines():
        if line == "[labels]":
            in_labels = True
            continue
        if in_labels and line.startswith("["):
            break
        if in_labels and line.strip():
            symbol_count += 1
    expected_symbol_count = int(reference["symbols.count"])
    if symbol_count != expected_symbol_count:
        fail(f"assembler emitted {symbol_count} labels; expected {expected_symbol_count}")
    print("Reference build valid")
    print(f"  Assets: {asset_count} extracted .bin files")
    print(f"  ROM: {built_rom}")
    print(f"  SHA-256: {built_hash}")
    print(f"  Symbols: {symbols} ({symbol_count} entries)")
    print(f"  Asar: {asar} ({reference['assembler.version']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
