#!/usr/bin/env python3
"""Provision pinned, ignored MapRandoSprites sheets for decoder conformance tests."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path
from typing import Optional


PARITY_DIR = Path(__file__).resolve().parent
REPO_ROOT = PARITY_DIR.parent
DEFAULT_CHECKOUT = PARITY_DIR / "work" / "community" / "MapRandoSprites"
REPO_URL = "https://github.com/blkerby/MapRandoSprites.git"
PINNED_COMMIT = "91fdbf43a4ccf41fc0bd4153eb98c5189bd38a25"
SAMPLES = {
    "manifest.json": "34a7d6aa2ee371d14456e9ecf9a2a5e2e3b5c77c2ca46a6eb41fdc2dc2d60e2b",
    "samus_vanilla.png": "628777976fb4a3ce75200765a3d42d8fab79423648405f11f766136d72b19740",
    "samus_invisible.png": "d077592dd67b545bccc78bfdf9c1b5ade1814c20676519657e6f00528806403e",
    "samus_outline.png": "2eac2c496ab4f499c30c17d930feed37d4ad16f0e53b6b70cb495b72dfcf397b",
    "samus_zero-mission.png": "fa836504718fb607ecd24c9c49bb48c02df11317706e0f3c7af908adb47601a3",
}


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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Clone the pinned MapRandoSprites fixtures used by community Samus tests."
    )
    parser.add_argument(
        "--dir",
        type=Path,
        default=DEFAULT_CHECKOUT,
        help=f"managed checkout location (default: {DEFAULT_CHECKOUT.relative_to(REPO_ROOT)})",
    )
    args = parser.parse_args()
    checkout = args.dir.expanduser().resolve()

    if checkout.exists() and not (checkout / ".git").is_dir():
        print(f"ERROR: {checkout} exists but is not a Git checkout", file=sys.stderr)
        return 2
    if not checkout.exists():
        checkout.parent.mkdir(parents=True, exist_ok=True)
        print(f"Cloning community Samus test fixtures into {checkout}")
        run("git", "clone", "--no-checkout", REPO_URL, str(checkout))

    tracked_changes = git_output(checkout, "status", "--porcelain", "--untracked-files=no")
    if tracked_changes:
        print(
            "ERROR: community fixture checkout has tracked changes; preserve or discard them first:\n"
            f"{tracked_changes}",
            file=sys.stderr,
        )
        return 2

    has_commit = subprocess.run(
        ["git", "-C", str(checkout), "cat-file", "-e", f"{PINNED_COMMIT}^{{commit}}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0
    if not has_commit:
        print(f"Fetching pinned MapRandoSprites commit {PINNED_COMMIT}")
        run("git", "-C", str(checkout), "fetch", "--depth=1", REPO_URL, PINNED_COMMIT)

    # Always materialize the tree. A fresh --no-checkout clone can already have
    # HEAD at the pin while its working tree is intentionally still empty.
    run("git", "-C", str(checkout), "checkout", "--detach", PINNED_COMMIT)
    actual = git_output(checkout, "rev-parse", "HEAD")
    if actual != PINNED_COMMIT:
        print(f"ERROR: expected {PINNED_COMMIT}, got {actual}", file=sys.stderr)
        return 2

    sprites = checkout / "samus_sprites"
    for name, expected_hash in SAMPLES.items():
        path = sprites / name
        if not path.is_file():
            print(f"ERROR: pinned fixture is missing {path}", file=sys.stderr)
            return 2
        actual_hash = sha256(path)
        if actual_hash != expected_hash:
            print(
                f"ERROR: {name} SHA-256 is {actual_hash}; expected {expected_hash}",
                file=sys.stderr,
            )
            return 2

    print(f"Community Samus fixtures ready: {sprites}")
    print(f"Pinned MapRandoSprites commit: {actual}")
    print("Samples: Vanilla, Invisible Samus, Outline Samus, Zero Mission Samus")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
