#!/usr/bin/env python3
"""Check or regenerate revision-addressed native identities from pinned Asar output, not comments."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

from check_fixtures import DEFAULT_DISASSEMBLY, REFERENCE_FILE, read_properties
from symbol_catalog import SymbolCatalog


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, help="private clean unheadered ROM; defaults to SMEDIT_TEST_ROM")
    parser.add_argument("--disassembly", type=Path, default=DEFAULT_DISASSEMBLY)
    parser.add_argument("--write", action="store_true", help="explicitly replace the generated TSV; otherwise check only")
    args = parser.parse_args()
    rom = args.rom or (Path(os.environ["SMEDIT_TEST_ROM"]) if os.environ.get("SMEDIT_TEST_ROM") else None)
    if rom is None:
        parser.error("--rom or SMEDIT_TEST_ROM is required; the ROM is never downloaded")
    disassembly = args.disassembly.resolve()
    subprocess.run([sys.executable, str(REFERENCE_FILE.parent / "check_fixtures.py"),
                    "--rom", str(rom), "--disassembly", str(disassembly)], check=True)
    reference = read_properties(REFERENCE_FILE)
    if not (disassembly / "SM.sfc").is_file():
        parser.error("build the verified reference ROM and symbol map first")
    catalog = SymbolCatalog.read(disassembly / "symbols.sym")
    macros = (disassembly / "src/macros.asm").read_text(encoding="utf-8")
    definition = re.search(r"^macro EnemyHeader\([^\n]*\)\n(.*?)^endmacro\s*$", macros, re.MULTILINE | re.DOTALL)
    if definition is None:
        parser.error("missing EnemyHeader layout macro")
    members = sorted(re.findall(r"^\s*\.([A-Za-z_][A-Za-z0-9_]*)\s*$", definition[1], re.MULTILINE))
    if not members or len(set(members)) != len(members):
        parser.error("missing or ambiguous EnemyHeader generated members")
    declarations = re.findall(r"^\s*(EnemyHeaders_[A-Za-z0-9_]+):", (disassembly / "src/bank_A0.asm").read_text(), re.MULTILINE)
    if not declarations or len(set(declarations)) != len(declarations):
        parser.error("missing or ambiguous native enemy declarations")
    rows = [f"# sm_disassembly.commit={reference['disassembly.commit']}",
            "# file\tcanonical_symbol\tcompiled_snes_address\tgenerated_members"]
    addresses = set()
    for name in sorted(declarations):
        matches = catalog.exact(name)
        if len(matches) != 1 or matches[0].snes_address >> 16 != 0xA0:
            parser.error(f"missing or ambiguous compiled native identity: {name}")
        address = matches[0].snes_address
        if address in addresses:
            parser.error(f"duplicate native species address: {name}")
        addresses.add(address)
        for member in members:
            generated = catalog.exact(f"{name}_{member}")
            if len(generated) != 1 or not address <= generated[0].snes_address < address + 0x40:
                parser.error(f"invalid compiled EnemyHeader member: {name}_{member}")
        rows.append(f"bank_A0.asm\t{name}\t{address:06X}\t{','.join(members)}")
    expected = "\n".join(rows) + "\n"
    target = REFERENCE_FILE.parent.parent / "desktopApp/src/jvmMain/resources/asm-reference/native-symbols" / f"{reference['disassembly.commit']}.tsv"
    if args.write:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(expected, encoding="utf-8")
    elif not target.is_file() or target.read_text(encoding="utf-8") != expected:
        parser.error(f"native symbol manifest differs: {target}; review regeneration with --write")
    print(f"Verified {len(declarations)} compiler-backed native identities: {target.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
