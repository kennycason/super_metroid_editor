#!/usr/bin/env python3
"""Build the source-backed room background command manifest.

Room states either carry Layer 2 in their compressed level stream or point at a
bank-$8F "library background" program.  This script inventories every named
program, decodes the complete eight-command language, resolves its ROM/WRAM/door
operands to source labels, and checks every command byte against the rebuilt ROM.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ROOMS = PARITY_DIR / "reports" / "rooms.json"
DEFAULT_LZ5 = PARITY_DIR / "reports" / "lz5.json"
DEFAULT_OUTPUT = PARITY_DIR / "reports" / "backgrounds.json"

COMMANDS = {
    0x0000: ("terminate", 2),
    0x0002: ("transfer", 9),
    0x0004: ("decompress", 7),
    0x0006: ("clearFxTilemap", 2),
    0x0008: ("transferAndSetBg3Base", 9),
    0x000A: ("clearBg2Tilemap", 2),
    0x000C: ("clearKraidLayer2", 2),
    0x000E: ("doorDependentTransfer", 11),
}


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


def read_u16(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 2 > len(data):
        fail(f"16-bit read outside ROM at PC {offset:X}")
    return data[offset] | (data[offset + 1] << 8)


def read_u24(data: bytes, offset: int) -> int:
    return read_u16(data, offset) | (data[offset + 2] << 16)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded)


def labels_at(catalog: SymbolCatalog, address: int) -> List[str]:
    return sorted(symbol.name for symbol in catalog.at(address))


def preferred_label(catalog: SymbolCatalog, address: int) -> Optional[str]:
    labels = labels_at(catalog, address)
    if not labels:
        return None
    prefixes = (
        "Background_", "ScrollingSky_Tilemaps_", "Tiles_Standard_BG3",
        "BG2Tilemap", "EnemyBG2Tilemap", "Door_",
    )
    for prefix in prefixes:
        match = next((label for label in labels if label.startswith(prefix)), None)
        if match is not None:
            return match
    return labels[0]


def command_record(
    opcode: int,
    address: int,
    raw: bytes,
    catalog: SymbolCatalog,
    door_labels: Dict[int, str],
) -> Dict[str, object]:
    name, _ = COMMANDS[opcode]
    record: Dict[str, object] = {
        "opcode": opcode,
        "name": name,
        "address": formatted(address),
        "snesAddress": address,
        "byteCount": len(raw),
        "rawBytes": raw.hex().upper(),
    }
    if opcode in (0x0002, 0x0008):
        source = read_u24(raw, 2)
        record.update({
            "sourceAddress": source,
            "sourceAddressText": formatted(source),
            "sourceLabel": preferred_label(catalog, source),
            "vramDestination": read_u16(raw, 5),
            "size": read_u16(raw, 7),
        })
    elif opcode == 0x0004:
        source = read_u24(raw, 2)
        record.update({
            "sourceAddress": source,
            "sourceAddressText": formatted(source),
            "sourceLabel": preferred_label(catalog, source),
            "wramDestination": read_u16(raw, 5),
        })
    elif opcode == 0x000E:
        door = read_u16(raw, 2)
        source = read_u24(raw, 4)
        record.update({
            "doorDefPtr": door,
            "doorAddress": formatted(0x830000 | door),
            "doorLabel": door_labels.get(door) or preferred_label(catalog, 0x830000 | door),
            "sourceAddress": source,
            "sourceAddressText": formatted(source),
            "sourceLabel": preferred_label(catalog, source),
            "vramDestination": read_u16(raw, 7),
            "size": read_u16(raw, 9),
        })
    return record


def decode_program(
    rom: bytes,
    address: int,
    catalog: SymbolCatalog,
    door_labels: Dict[int, str],
) -> Tuple[List[Dict[str, object]], bytes]:
    pc = snes_to_pc(address)
    start = pc
    commands: List[Dict[str, object]] = []
    for _ in range(128):
        opcode = read_u16(rom, pc)
        definition = COMMANDS.get(opcode)
        if definition is None:
            fail(f"unknown library-background command ${opcode:04X} at {formatted(address + pc - start)}")
        size = definition[1]
        raw = rom[pc:pc + size]
        if len(raw) != size:
            fail(f"truncated library-background command at {formatted(address + pc - start)}")
        commands.append(command_record(opcode, address + pc - start, raw, catalog, door_labels))
        pc += size
        if opcode == 0:
            return commands, rom[start:pc]
    fail(f"unterminated library-background program at {formatted(address)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build exact room-background command parity")
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--rooms", type=Path, default=DEFAULT_ROOMS)
    parser.add_argument("--lz5", type=Path, default=DEFAULT_LZ5)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    disassembly = (
        args.disassembly or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    for path, task in (
        (symbols_path, "parityBuildReference"),
        (rom_path, "parityBuildReference"),
        (args.rooms, "parityRooms"),
        (args.lz5, "parityLz5Oracle"),
    ):
        if not Path(path).is_file():
            fail(f"missing {path}; run ./gradlew {task}")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    rooms = json.loads(Path(args.rooms).read_text(encoding="utf-8"))
    lz5 = json.loads(Path(args.lz5).read_text(encoding="utf-8"))
    compressed_by_address = {int(stream["snesAddress"]): stream for stream in lz5["streams"]}
    door_labels = {
        int(entry["doorDefPtr"]): str(entry["sourceLabel"])
        for door_list in rooms["doors"] for entry in door_list["entries"]
    }
    consumers: Dict[int, List[Dict[str, object]]] = defaultdict(list)
    for state in rooms["states"]:
        pointer = int(state["bgDataPtr"])
        if pointer not in (0, 0xFFFF):
            consumers[pointer].append({
                "stateLabel": state["sourceLabel"],
                "stateAddress": state["address"],
                "tileset": state["tileset"],
                "bgScrolling": state["bgScrolling"],
            })

    symbols = sorted(
        (
            symbol for symbol in catalog.symbols
            if symbol.name.startswith(("LibBG_", "UNUSED_LibBG_"))
        ),
        key=lambda symbol: (symbol.snes_address, symbol.name),
    )
    programs: List[Dict[str, object]] = []
    command_counts: Counter[str] = Counter()
    compressed_sources = set()
    for symbol in symbols:
        commands, raw = decode_program(rom, symbol.snes_address, catalog, door_labels)
        command_counts.update(str(command["name"]) for command in commands)
        for command in commands:
            if command["opcode"] == 0x0004:
                source = int(command["sourceAddress"])
                stream = compressed_by_address.get(source)
                if stream is None or not str(stream["name"]).startswith("Background_"):
                    fail(f"{symbol.name} decompresses unproven background source {formatted(source)}")
                command["compressedSize"] = int(stream["compressedSize"])
                command["decompressedSize"] = int(stream["decompressedSize"])
                command["decompressedSha256"] = stream["decompressedSha256"]
                compressed_sources.add(source)
            if command["opcode"] in (0x0002, 0x0008, 0x000E):
                source = int(command["sourceAddress"])
                size = int(command["size"])
                destination = int(command["vramDestination"])
                if size <= 0 or size > 0x10000 or destination + (size // 2) > 0x10000:
                    fail(f"invalid VRAM transfer in {symbol.name}: dst=${destination:04X} size=${size:04X}")
                if preferred_label(catalog, source) is None:
                    fail(f"unnamed transfer source {formatted(source)} in {symbol.name}")
            if command["opcode"] == 0x000E and command["doorLabel"] is None:
                fail(f"unknown door ${int(command['doorDefPtr']):04X} in {symbol.name}")
        pointer = symbol.snes_address & 0xFFFF
        records = consumers.get(pointer, [])
        programs.append({
            "sourceLabel": symbol.name,
            "address": symbol.address,
            "snesAddress": symbol.snes_address,
            "pointer": pointer,
            "unused": symbol.name.startswith("UNUSED_"),
            "byteCount": len(raw),
            "rawSha256": sha256(raw),
            "commandCount": len(commands),
            "commands": commands,
            "consumerCount": len(records),
            "consumers": records,
        })

    active_program_addresses = set(consumers)
    named_addresses = {program["pointer"] for program in programs}
    missing = sorted(active_program_addresses - named_addresses)
    if missing:
        fail("active room states reference unnamed BG programs: " + ", ".join(f"8F:{p:04X}" for p in missing))
    falsely_unused = [program["sourceLabel"] for program in programs if program["unused"] and program["consumerCount"]]
    if falsely_unused:
        fail(f"source-declared unused BG programs have consumers: {falsely_unused}")

    all_background_streams = {
        int(stream["snesAddress"]): stream
        for stream in lz5["streams"] if str(stream["name"]).startswith("Background_")
    }
    unreferenced_streams = [
        stream["name"] for address, stream in sorted(all_background_streams.items())
        if address not in compressed_sources
    ]
    levels_by_address = {int(level["snesAddress"]): level for level in rooms["levels"]}
    embedded_states = [
        state for state in rooms["states"]
        if int(state["bgDataPtr"]) == 0
        and bool(levels_by_address[int(state["levelDataPtr"])]["hasLayer2"])
    ]
    revision = subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True, text=True, stdout=subprocess.PIPE,
    ).stdout.strip()
    payload: Dict[str, object] = {
        "schemaVersion": 1,
        "disassemblyCommit": revision,
        "engine": {
            "commandTableLabel": "Library_Background_Function_Pointers",
            "commandTableAddress": "82:E5C7",
            "loadRoutineLabel": "LoadLibraryBackground",
            "commandOpcodes": {f"{opcode:02X}": name for opcode, (name, _) in COMMANDS.items()},
        },
        "programCount": len(programs),
        "activeProgramCount": sum(not bool(program["unused"]) for program in programs),
        "unusedProgramCount": sum(bool(program["unused"]) for program in programs),
        "stateAssociationCount": sum(len(records) for records in consumers.values()),
        "embeddedLayer2StateCount": len(embedded_states),
        "commandCount": sum(int(program["commandCount"]) for program in programs),
        "commandCounts": dict(sorted(command_counts.items())),
        "compressedBackgroundCount": len(all_background_streams),
        "referencedCompressedBackgroundCount": len(compressed_sources),
        "unreferencedCompressedBackgroundCount": len(unreferenced_streams),
        "unreferencedCompressedBackgrounds": unreferenced_streams,
        "doorDependentTransferCount": command_counts["doorDependentTransfer"],
        "programs": programs,
    }
    payload["aggregateHashes"] = {
        "programs": aggregate_hash(programs),
        "commands": aggregate_hash([command for program in programs for command in program["commands"]]),
        "consumers": aggregate_hash([
            {"pointer": program["pointer"], "consumers": program["consumers"]}
            for program in programs
        ]),
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Room background source manifest complete")
    print(
        f"  Programs: {payload['activeProgramCount']} active + {payload['unusedProgramCount']} unused; "
        f"{payload['stateAssociationCount']} state associations"
    )
    print(f"  Commands: {payload['commandCount']} {payload['commandCounts']}")
    print(
        f"  Compressed backgrounds: {payload['referencedCompressedBackgroundCount']}/"
        f"{payload['compressedBackgroundCount']} referenced; "
        f"{payload['doorDependentTransferCount']} door-dependent transfers"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
