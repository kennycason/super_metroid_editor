#!/usr/bin/env python3
"""Build the source-backed runtime scroll mutation manifest.

The static room manifest proves initial scroll tables. This companion proves what can
change them afterwards: generic scroll PLMs, extension topology, incoming door ASM,
and every direct engine store to $7E:CD20 (Scrolls). Source labels and instruction
addresses come from the pinned disassembly and every store opcode/target is checked
against its byte-identical rebuilt ROM.
"""

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
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ROOM_MANIFEST = PARITY_DIR / "reports" / "rooms.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "scroll-runtime.json"

LABEL = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):")
CODE_ADDRESS = re.compile(r";([0-9A-Fa-f]{6});")
SCROLL_STORE = re.compile(r"\bSTA\.L\s+Scrolls(?:(?:\+\$?([0-9A-Fa-f]+))|(,X))?\b")
IMMEDIATE_LDA = re.compile(r"^\s*LDA\.(B|W)\s+#\$([0-9A-Fa-f]+)\b")
STATUS_WIDTH = re.compile(r"^\s*(SEP|REP)\s+#\$([0-9A-Fa-f]+)\b")
INSTRUCTION = re.compile(r"^\s*([A-Z]{2,4})(?:\.[BWL])?\b")

SCROLL_PLM_ID = 0xB703
SOLID_SCROLL_PLM_ID = 0xB707
EXTENSION_DIRECTIONS = {
    0xB63B: (1, 0),   # rightwards: its predecessor is one block left
    0xB63F: (-1, 0),  # leftwards: predecessor is one block right
    0xB647: (0, -1),  # upwards: predecessor is one block down
    0xB643: (0, 1),   # downwards: predecessor is one block up
}
SCROLLS_ADDRESS = 0x7ECD20


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


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded)


def source_label(catalog: SymbolCatalog, address: int, prefix: str = "") -> str:
    matches = [symbol.name for symbol in catalog.at(address) if not prefix or symbol.name.startswith(prefix)]
    if not matches:
        fail(f"no source label at {formatted(address)} matching {prefix!r}")
    return sorted(matches)[0]


def decode_scroll_commands(rom: bytes, pointer: int) -> Tuple[List[Dict[str, int]], bytes]:
    pc = snes_to_pc(0x8F0000 | pointer)
    commands: List[Dict[str, int]] = []
    raw = bytearray()
    for _ in range(128):
        screen = rom[pc]
        raw.append(screen)
        pc += 1
        if screen & 0x80:
            return commands, bytes(raw)
        value = rom[pc]
        raw.append(value)
        pc += 1
        commands.append({"screenIndex": screen, "scrollValue": value})
    fail(f"unterminated scroll command stream at 8F:{pointer:04X}")


def extension_reaches_trigger(entry: Dict[str, int], entries: Sequence[Dict[str, int]]) -> bool:
    by_position: Dict[Tuple[int, int], List[Dict[str, int]]] = defaultdict(list)
    for candidate in entries:
        by_position[(int(candidate["x"]), int(candidate["y"]))].append(candidate)
    remaining = [entry]
    visited = set()
    while remaining:
        current = remaining.pop()
        key = (int(current["id"]), int(current["x"]), int(current["y"]))
        if key in visited:
            continue
        visited.add(key)
        dx, dy = EXTENSION_DIRECTIONS[int(current["id"])]
        predecessors = by_position[(int(current["x"]) - dx, int(current["y"]) - dy)]
        if any(int(candidate["id"]) == SCROLL_PLM_ID for candidate in predecessors):
            return True
        remaining.extend(
            candidate for candidate in predecessors
            if int(candidate["id"]) in EXTENSION_DIRECTIONS
        )
    return False


def plm_manifest(
    rooms: Dict[str, object], catalog: SymbolCatalog, rom: bytes,
) -> Dict[str, object]:
    streams: Dict[int, Dict[str, object]] = {}
    triggers: List[Dict[str, object]] = []
    extensions: List[Dict[str, object]] = []
    solid_count = 0

    for population in rooms["resources"]["plm"]:
        entries = population["entries"]
        consumers = population["consumers"]
        for entry_index, entry in enumerate(entries):
            plm_id = int(entry["id"])
            base = {
                "populationPointer": int(population["pointer"]),
                "populationAddress": population["address"],
                "entryIndex": entry_index,
                "x": int(entry["x"]),
                "y": int(entry["y"]),
                "consumers": consumers,
            }
            if plm_id == SOLID_SCROLL_PLM_ID:
                solid_count += 1
            if plm_id == SCROLL_PLM_ID:
                pointer = int(entry["param"])
                commands, raw = decode_scroll_commands(rom, pointer)
                if any(command["scrollValue"] not in range(3) for command in commands):
                    fail(f"scroll PLM command at 8F:{pointer:04X} has a noncanonical value")
                label = source_label(catalog, 0x8F0000 | pointer, "RoomPLM_")
                stream = streams.setdefault(pointer, {
                    "pointer": pointer,
                    "address": formatted(0x8F0000 | pointer),
                    "sourceLabel": label,
                    "commands": commands,
                    "commandCount": len(commands),
                    "terminator": raw[-1],
                    "rawBytes": raw.hex().upper(),
                    "rawSha256": sha256(raw),
                    "associationCount": 0,
                })
                if stream["commands"] != commands or stream["rawSha256"] != sha256(raw):
                    fail(f"conflicting scroll command decode for 8F:{pointer:04X}")
                stream["associationCount"] = int(stream["associationCount"]) + 1
                triggers.append({**base, "commandPointer": pointer, "sourceLabel": label})
            elif plm_id in EXTENSION_DIRECTIONS:
                extensions.append({
                    **base,
                    "id": plm_id,
                    "connected": extension_reaches_trigger(entry, entries),
                })

    ordered_streams = [streams[pointer] for pointer in sorted(streams)]
    orphan_extensions = [entry for entry in extensions if not entry["connected"]]
    return {
        "airTriggerId": SCROLL_PLM_ID,
        "solidTriggerId": SOLID_SCROLL_PLM_ID,
        "triggerCount": len(triggers),
        "solidTriggerCount": solid_count,
        "populationCount": len({entry["populationPointer"] for entry in triggers}),
        "stateAssociationCount": sum(len(population["consumers"]) for population in rooms["resources"]["plm"] if any(int(entry["id"]) == SCROLL_PLM_ID for entry in population["entries"])),
        "uniqueCommandStreamCount": len(ordered_streams),
        "commandCount": sum(int(stream["commandCount"]) for stream in ordered_streams),
        "extensionCount": len(extensions),
        "orphanExtensionCount": len(orphan_extensions),
        "triggers": triggers,
        "commandStreams": ordered_streams,
        "extensions": extensions,
        "orphanExtensions": orphan_extensions,
    }


def writer_category(bank_name: str, label: str) -> str:
    if bank_name == "bank_8F.asm" and "DoorASM" in label:
        return "doorAsm"
    if bank_name == "bank_84.asm":
        return "plm"
    if bank_name == "bank_88.asm":
        return "fx"
    if bank_name == "bank_82.asm":
        return "roomLoad" if label.startswith("Load") else "demo"
    return "enemy"


def source_writer_manifest(
    disassembly: Path, catalog: SymbolCatalog, rom: bytes,
) -> List[Dict[str, object]]:
    routines: Dict[Tuple[str, str], Dict[str, object]] = {}
    for source_path in sorted((disassembly / "src").glob("bank_*.asm")):
        current_label = ""
        accumulator8 = False
        accumulator: Optional[int] = None
        for line_number, raw_line in enumerate(source_path.read_text(encoding="utf-8").splitlines(), 1):
            label_match = LABEL.match(raw_line)
            if label_match is not None:
                current_label = label_match.group(1)
                accumulator8 = False
                accumulator = None
            code = raw_line.split(";", 1)[0].rstrip()
            status_match = STATUS_WIDTH.match(code)
            if status_match and int(status_match.group(2), 16) & 0x20:
                accumulator8 = status_match.group(1) == "SEP"
            immediate_match = IMMEDIATE_LDA.match(code)
            if immediate_match:
                accumulator8 = immediate_match.group(1) == "B"
                accumulator = int(immediate_match.group(2), 16)
            elif INSTRUCTION.match(code):
                mnemonic = INSTRUCTION.match(code).group(1)
                if mnemonic in {"LDA", "ADC", "SBC", "AND", "ORA", "EOR", "PLA", "TXA", "TYA", "XBA"}:
                    accumulator = None

            store_match = SCROLL_STORE.search(code)
            if store_match is None:
                continue
            if not current_label:
                fail(f"unlabelled Scrolls store at {source_path}:{line_number}")
            address_match = CODE_ADDRESS.search(raw_line)
            if address_match is None:
                fail(f"Scrolls store without machine address at {source_path}:{line_number}")
            instruction_address = int(address_match.group(1), 16)
            dynamic = store_match.group(2) is not None
            offset = None if dynamic else int(store_match.group(1) or "0", 16)
            pc = snes_to_pc(instruction_address)
            expected_opcode = 0x9F if dynamic else 0x8F
            if rom[pc] != expected_opcode:
                fail(f"source/ROM Scrolls opcode mismatch at {formatted(instruction_address)}")
            if not dynamic:
                target = rom[pc + 1] | (rom[pc + 2] << 8) | (rom[pc + 3] << 16)
                if target != SCROLLS_ADDRESS + int(offset):
                    fail(f"source/ROM Scrolls target mismatch at {formatted(instruction_address)}")
            key = (source_path.name, current_label)
            routine = routines.setdefault(key, {
                "sourceFile": f"src/{source_path.name}",
                "sourceLabel": current_label,
                "snesAddress": catalog.exact(current_label)[0].snes_address if catalog.exact(current_label) else None,
                "category": writer_category(source_path.name, current_label),
                "storeSites": [],
            })
            static_writes: List[Dict[str, int]] = []
            if offset is not None and accumulator is not None:
                static_writes.append({"screenIndex": offset, "scrollValue": accumulator & 0xFF})
                if not accumulator8:
                    static_writes.append({"screenIndex": offset + 1, "scrollValue": (accumulator >> 8) & 0xFF})
            routine["storeSites"].append({
                "sourceLine": line_number,
                "instructionAddress": formatted(instruction_address),
                "snesAddress": instruction_address,
                "screenIndex": offset,
                "dynamicIndex": dynamic,
                "accumulatorWidth": 8 if accumulator8 else 16,
                "staticValue": accumulator,
                "staticWrites": static_writes,
            })

    result = list(routines.values())
    for routine in result:
        routine["storeCount"] = len(routine["storeSites"])
        routine["staticWrites"] = [
            write for site in routine["storeSites"] for write in site["staticWrites"]
        ]
    result.sort(key=lambda routine: (int(routine["snesAddress"] or 0), str(routine["sourceLabel"])))
    return result


def door_manifest(
    rooms: Dict[str, object], catalog: SymbolCatalog, writers: Sequence[Dict[str, object]],
) -> Dict[str, object]:
    room_labels = {int(room["roomId"]): room["sourceLabel"] for room in rooms["rooms"]}
    writer_by_address = {
        int(routine["snesAddress"]): routine
        for routine in writers
        if routine["category"] == "doorAsm" and routine["snesAddress"] is not None
    }
    associations: List[Dict[str, object]] = []
    routine_consumers: Dict[int, List[Dict[str, object]]] = defaultdict(list)
    for door_list in rooms["doors"]:
        for entry in door_list["entries"]:
            destination = int(entry["destRoomPtr"])
            entry_code = int(entry["entryCode"])
            if entry_code in (0, 0xFFFF) or not (0x8000 <= destination < 0xFFFF):
                continue
            labels = sorted(symbol.name for symbol in catalog.at(0x8F0000 | entry_code))
            association = {
                "sourceRoomId": int(door_list["roomId"]),
                "sourceRoomLabel": door_list["roomLabel"],
                "doorDefPtr": int(entry["doorDefPtr"]),
                "doorDefLabel": entry["sourceLabel"],
                "destinationRoomId": destination,
                "destinationRoomLabel": room_labels.get(destination),
                "entryCode": entry_code,
                "entryAddress": formatted(0x8F0000 | entry_code),
                "entryLabels": labels,
            }
            associations.append(association)
            routine_consumers[entry_code].append(association)

    scroll_routines: List[Dict[str, object]] = []
    for entry_code, consumers in sorted(routine_consumers.items()):
        address = 0x8F0000 | entry_code
        writer = writer_by_address.get(address)
        if writer is None:
            continue
        scroll_routines.append({
            "entryCode": entry_code,
            "address": formatted(address),
            "sourceLabel": writer["sourceLabel"],
            "pureScrollBySource": str(writer["sourceLabel"]).startswith("DoorASM_Scroll_"),
            "staticWrites": writer["staticWrites"],
            "storeCount": writer["storeCount"],
            "consumerCount": len(consumers),
            "consumers": consumers,
        })

    all_door_writers = [writer for writer in writers if writer["category"] == "doorAsm"]
    active_addresses = {0x8F0000 | int(routine["entryCode"]) for routine in scroll_routines}
    unused_writers = [
        writer for writer in all_door_writers
        if int(writer["snesAddress"]) not in active_addresses
    ]
    return {
        "nonzeroValidAssociationCount": len(associations),
        "nonzeroValidRoutineCount": len(routine_consumers),
        "scrollWriterAssociationCount": sum(int(routine["consumerCount"]) for routine in scroll_routines),
        "scrollWriterRoutineCount": len(scroll_routines),
        "pureScrollRoutineCount": sum(bool(routine["pureScrollBySource"]) for routine in scroll_routines),
        "mixedScrollRoutineCount": sum(not bool(routine["pureScrollBySource"]) for routine in scroll_routines),
        "sourceDeclaredUnusedWriterCount": len(unused_writers),
        "associations": associations,
        "scrollWriterRoutines": scroll_routines,
        "sourceDeclaredUnusedWriters": unused_writers,
    }


def load_order_manifest(catalog: SymbolCatalog) -> Dict[str, object]:
    def symbol(name: str) -> Dict[str, object]:
        matches = catalog.exact(name)
        if len(matches) != 1:
            fail(f"expected one load-order symbol {name}, found {len(matches)}")
        return {"sourceLabel": name, "address": matches[0].address, "snesAddress": matches[0].snes_address}

    return {
        "precedence": ["staticScrolls", "plmSetup", "incomingDoorAsm", "roomSetupAsm"],
        "combinedPath": {
            "routine": symbol("LoadLevelData_CRE_TileTable_ScrollData_PLMs_DoorASM_RoomASM"),
            "staticStoreAddresses": ["82:E87E", "82:E8A7"],
            "plmSpawnAddress": "82:E8C9",
            "doorAsmCallAddress": "82:E8D5",
            "setupAsmCallAddress": "82:E8D9",
        },
        "splitPath": {
            "routine": symbol("CreatePLMs_ExecuteDoorASM_RoomSetupASM_SetElevatorStatus"),
            "plmSpawnAddress": "82:EB7F",
            "doorAsmCallAddress": "82:EB8B",
            "setupAsmCallAddress": "82:EB8F",
        },
        "doorDispatcher": symbol("Execute_Door_ASM"),
        "setupDispatcher": symbol("Execute_Room_Setup_ASM"),
        "airScrollTouchHandler": symbol("Instruction_PLM_ProcessAirScrollUpdate"),
        "solidScrollTouchHandler": symbol("Instruction_PLM_ProcessSolidScrollUpdate"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build exact runtime scroll mutation parity")
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--room-manifest", type=Path, default=DEFAULT_ROOM_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()
    disassembly = (
        args.disassembly or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    for path, task in ((symbols_path, "parityBuildReference"), (rom_path, "parityBuildReference"), (args.room_manifest, "parityRooms")):
        if not Path(path).is_file():
            fail(f"missing {path}; run ./gradlew {task}")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    rooms = json.loads(Path(args.room_manifest).read_text(encoding="utf-8"))
    plms = plm_manifest(rooms, catalog, rom)
    writers = source_writer_manifest(disassembly, catalog, rom)
    doors = door_manifest(rooms, catalog, writers)
    categories = Counter(str(routine["category"]) for routine in writers)
    revision = subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True, text=True, stdout=subprocess.PIPE,
    ).stdout.strip()
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": revision,
        "loadOrder": load_order_manifest(catalog),
        "genericPlms": plms,
        "doors": doors,
        "writerInventory": {
            "routineCount": len(writers),
            "storeSiteCount": sum(int(routine["storeCount"]) for routine in writers),
            "categoryCounts": dict(sorted(categories.items())),
            "routines": writers,
        },
    }
    payload["aggregateHashes"] = {
        "commandStreams": aggregate_hash(plms["commandStreams"]),
        "extensions": aggregate_hash(plms["extensions"]),
        "doorRoutines": aggregate_hash(doors["scrollWriterRoutines"]),
        "writerInventory": aggregate_hash(writers),
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Runtime scroll source manifest complete")
    print(
        f"  Generic PLMs: {plms['triggerCount']} triggers / {plms['uniqueCommandStreamCount']} streams / "
        f"{plms['commandCount']} writes; {plms['extensionCount']} extensions ({plms['orphanExtensionCount']} orphan)"
    )
    print(
        f"  Door ASM: {doors['scrollWriterRoutineCount']} active writer routines / "
        f"{doors['scrollWriterAssociationCount']} door associations"
    )
    print(
        f"  All direct writers: {payload['writerInventory']['routineCount']} routines / "
        f"{payload['writerInventory']['storeSiteCount']} store sites {dict(sorted(categories.items()))}"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
