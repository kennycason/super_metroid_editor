#!/usr/bin/env python3
"""Provision the pinned sm_disassembly checkout used by parity tooling."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path
from typing import Dict, Optional


PARITY_DIR = Path(__file__).resolve().parent
REPO_ROOT = PARITY_DIR.parent
REFERENCE_FILE = PARITY_DIR / "reference.properties"
DEFAULT_CHECKOUT = PARITY_DIR / "work" / "sm_disassembly"


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


def run(*args: str, cwd: Optional[Path] = None, capture: bool = False) -> str:
    completed = subprocess.run(
        args,
        cwd=cwd,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )
    return completed.stdout.strip() if capture else ""


def git_output(checkout: Path, *args: str) -> str:
    return run("git", "-C", str(checkout), *args, capture=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Clone/fetch and check out SMEDIT's pinned Super Metroid disassembly oracle."
    )
    parser.add_argument(
        "--dir",
        type=Path,
        default=DEFAULT_CHECKOUT,
        help=f"managed checkout location (default: {DEFAULT_CHECKOUT.relative_to(REPO_ROOT)})",
    )
    args = parser.parse_args()

    reference = read_properties(REFERENCE_FILE)
    repo_url = reference["disassembly.repoUrl"]
    commit = reference["disassembly.commit"]
    checkout = args.dir.expanduser().resolve()

    if checkout.exists() and not (checkout / ".git").is_dir():
        print(f"ERROR: {checkout} exists but is not a Git checkout", file=sys.stderr)
        return 2

    if not checkout.exists():
        checkout.parent.mkdir(parents=True, exist_ok=True)
        print(f"Cloning pinned parity oracle into {checkout}")
        run("git", "clone", repo_url, str(checkout))

    tracked_changes = git_output(checkout, "status", "--porcelain", "--untracked-files=no")
    if tracked_changes:
        print(
            "ERROR: parity checkout has tracked changes; preserve or discard them before bootstrap:\n"
            f"{tracked_changes}",
            file=sys.stderr,
        )
        return 2

    has_commit = subprocess.run(
        ["git", "-C", str(checkout), "cat-file", "-e", f"{commit}^{{commit}}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0
    if not has_commit:
        print(f"Fetching pinned commit {commit}")
        run("git", "-C", str(checkout), "fetch", "--depth=1", "origin", commit)

    current = git_output(checkout, "rev-parse", "HEAD") if (checkout / ".git" / "HEAD").exists() else ""
    if current != commit:
        run("git", "-C", str(checkout), "checkout", "--detach", commit)

    actual = git_output(checkout, "rev-parse", "HEAD")
    if actual != commit:
        print(f"ERROR: expected {commit}, got {actual}", file=sys.stderr)
        return 2

    print(f"Parity oracle ready: {checkout}")
    print(f"Pinned commit: {actual}")
    print("Set SMEDIT_TEST_ROM to your clean unheadered ROM, then run ./gradlew parityCheck")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
