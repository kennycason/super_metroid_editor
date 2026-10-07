#!/usr/bin/env python3
"""Build the source-backed load-station (AreaSave) manifest.

The native table contains save/resume slots, elevator/debug destinations, the
Ceres sequence, and the gunship landing entry. This manifest proves the pointer
table, every 14-byte runtime field, save-PLM ownership, and key engine consumers.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, List, Tuple

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ROOMS = PARITY_DIR / "reports" / "rooms.json"
DEFAULT_OUTPUT = PARITY_DIR / "reports" / "load-stations.json"

AREA_NAMES = ["Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres", "Debug"]
POINTER_TABLE = 0x80C4B5
DATA_END = 0x80CD07
ENTRY_SIZE = 14
SAVE_PLM_ID = 0xB76F
EXPECTED_SAVE_COUNTS = [2, 5, 6, 1, 4, 2, 0]

ENGINE_CONSUMERS = [
    ("LoadFromLoadStation", 0x80C437),
    ("SaveToSRAM", 0x818000),
    ("LoadFromSRAM", 0x818085),
    ("SetDebugElevatorAsUsed", 0x80CD07),
    ("Select_FileSelectMap_Area", 0x81A8A9),
    ("FileSelectMap_Index9_AreaSelectMapToRoomSelectMap_Init", 0x81AD17),
    ("GameState_6_1F_28_LoadingGameData_SetupNewGame_LoadDemoData", 0x828000),
    ("Instruction_PLM_GotoY_or_ActivateSaveStation", 0x848CF1),
    ("Setup_SaveStation", 0x84B5EE),
]


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


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded)


def label_at(catalog: SymbolCatalog, address: int, prefixes: Tuple[str, ...]) -> str:
    names = sorted(symbol.name for symbol in catalog.at(address))
    for prefix in prefixes:
        match = next((name for name in names if name.startswith(prefix)), None)
        if match is not None:
            return match
    fail(f"missing source label at {formatted(address)} matching {prefixes}")


def optional_label(catalog: SymbolCatalog, address: int, prefixes: Tuple[str, ...]) -> str | None:
    names = sorted(symbol.name for symbol in catalog.at(address))
    for prefix in prefixes:
        match = next((name for name in names if name.startswith(prefix)), None)
        if match is not None:
            return match
    return None


def entry_kind(area: int, index: int) -> str:
    if area == 6:
        return "ceresSequence"
    if area == 7:
        return "debug"
    if index < 8:
        return "saveSlot"
    if index < 16:
        return "elevator"
    if area == 0 and index == 0x12:
        return "gunshipLanding"
    return "debug"


def main() -> int:
    parser = argparse.ArgumentParser(description="Build exact load-station / AreaSave parity")
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--rooms", type=Path, default=DEFAULT_ROOMS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    disassembly = (
        args.disassembly or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    for path, task in ((symbols_path, "parityBuildReference"), (rom_path, "parityBuildReference"), (args.rooms, "parityRooms")):
        if not Path(path).is_file():
            fail(f"missing {path}; run ./gradlew {task}")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    rooms = json.loads(Path(args.rooms).read_text(encoding="utf-8"))
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=disassembly, text=True).strip()

    pointer_pc = snes_to_pc(POINTER_TABLE)
    pointer_raw = rom[pointer_pc:pointer_pc + len(AREA_NAMES) * 2]
    pointers = [0x800000 | read_u16(rom, pointer_pc + area * 2) for area in range(len(AREA_NAMES))]
    expected_labels = [f"LoadStations_{name.replace(' ', '')}" for name in AREA_NAMES]
    for area, address in enumerate(pointers):
        actual = label_at(catalog, address, ("LoadStations_",))
        if actual != expected_labels[area]:
            fail(f"area {area} pointer resolves to {actual}, expected {expected_labels[area]}")

    areas: List[Dict[str, object]] = []
    all_entries: List[Dict[str, object]] = []
    for area, (name, start) in enumerate(zip(AREA_NAMES, pointers)):
        end = pointers[area + 1] if area + 1 < len(pointers) else DATA_END
        byte_count = end - start
        if byte_count <= 0 or byte_count % ENTRY_SIZE:
            fail(f"{name} load-station range has invalid size {byte_count}")
        raw = rom[snes_to_pc(start):snes_to_pc(start) + byte_count]
        entries: List[Dict[str, object]] = []
        for index in range(byte_count // ENTRY_SIZE):
            offset = index * ENTRY_SIZE
            words = [read_u16(raw, offset + field * 2) for field in range(7)]
            room_id, door_ptr, door_bts, scroll_x, scroll_y, samus_y, samus_x = words
            kind = entry_kind(area, index)
            record: Dict[str, object] = {
                "area": area,
                "areaName": name,
                "index": index,
                "kind": kind,
                "address": formatted(start + offset),
                "snesAddress": start + offset,
                "roomId": room_id,
                "roomIdHex": f"{room_id:04X}",
                "roomSourceLabel": optional_label(catalog, 0x8F0000 | room_id, ("RoomHeader_",)) if room_id else None,
                "doorPtr": door_ptr,
                "doorSourceLabel": optional_label(catalog, 0x830000 | door_ptr, ("Door_", "UNUSED_Door_")) if door_ptr else None,
                "doorBts": door_bts,
                "scrollX": scroll_x,
                "scrollY": scroll_y,
                "samusY": samus_y,
                "samusX": samus_x,
                "empty": room_id == 0,
                "rawSha256": sha256(raw[offset:offset + ENTRY_SIZE]),
            }
            if room_id and record["roomSourceLabel"] is None:
                fail(f"{name} entry {index} has unknown room ${room_id:04X}")
            if door_ptr and record["doorSourceLabel"] is None:
                fail(f"{name} entry {index} has unknown door ${door_ptr:04X}")
            entries.append(record)
            all_entries.append(record)
        areas.append({
            "area": area,
            "name": name,
            "address": formatted(start),
            "snesAddress": start,
            "sourceLabel": expected_labels[area],
            "byteCount": byte_count,
            "entryCount": len(entries),
            "rawSha256": sha256(raw),
            "entries": entries,
        })

    room_by_id = {int(room["roomId"]): room for room in rooms["rooms"]}
    placements: List[Dict[str, object]] = []
    for population in rooms["resources"]["plm"]:
        for entry_index, entry in enumerate(population["entries"]):
            if int(entry["id"]) != SAVE_PLM_ID:
                continue
            for consumer in population["consumers"]:
                room = room_by_id[int(consumer["roomId"])]
                area = int(room["area"])
                save_index = int(entry["param"]) & 7
                target = areas[area]["entries"][save_index]
                if int(target["roomId"]) != int(room["roomId"]):
                    fail(
                        f"save PLM in {consumer['roomLabel']} selects {area}:{save_index}, "
                        f"which points to ${int(target['roomId']):04X}"
                    )
                placements.append({
                    "area": area,
                    "saveIndex": save_index,
                    "roomId": int(room["roomId"]),
                    "roomIdHex": room["roomIdHex"],
                    "roomSourceLabel": consumer["roomLabel"],
                    "stateSourceLabel": consumer["stateLabel"],
                    "populationPointer": int(population["pointer"]),
                    "entryIndex": entry_index,
                    "x": int(entry["x"]),
                    "y": int(entry["y"]),
                    "param": int(entry["param"]),
                })
    placements.sort(key=lambda value: (value["area"], value["saveIndex"], value["roomId"]))

    occupied_save_slots = [entry for entry in all_entries if entry["kind"] == "saveSlot" and not entry["empty"]]
    if [sum(entry["area"] == area for entry in occupied_save_slots) for area in range(7)] != EXPECTED_SAVE_COUNTS:
        fail("occupied save-slot counts do not match the source-owned vanilla counts")
    if len(placements) != sum(EXPECTED_SAVE_COUNTS) - 1:
        fail("expected every occupied save slot except the Crateria gunship slot to have a save PLM")
    unmatched_save_slots = [
        entry for entry in occupied_save_slots
        if not any(p["area"] == entry["area"] and p["saveIndex"] == entry["index"] for p in placements)
    ]
    if [(entry["area"], entry["index"]) for entry in unmatched_save_slots] != [(0, 0)]:
        fail("the sole occupied save slot without a normal save PLM must be Crateria 0 (gunship)")

    engine = []
    for label, address in ENGINE_CONSUMERS:
        actual = label_at(catalog, address, (label,))
        if actual != label:
            fail(f"engine consumer label mismatch at {formatted(address)}")
        engine.append({"sourceLabel": label, "address": formatted(address), "snesAddress": address})

    kinds = Counter(str(entry["kind"]) for entry in all_entries)
    report = {
        "schemaVersion": 1,
        "disassemblyCommit": commit,
        "areaCount": len(areas),
        "entryCount": len(all_entries),
        "entryByteCount": len(all_entries) * ENTRY_SIZE,
        "saveSlotCount": kinds["saveSlot"],
        "occupiedSaveSlotCount": len(occupied_save_slots),
        "emptySaveSlotCount": sum(entry["kind"] == "saveSlot" and entry["empty"] for entry in all_entries),
        "savePlmPlacementCount": len(placements),
        "elevatorEntryCount": kinds["elevator"],
        "occupiedElevatorEntryCount": sum(entry["kind"] == "elevator" and not entry["empty"] for entry in all_entries),
        "debugEntryCount": kinds["debug"],
        "occupiedDebugEntryCount": sum(entry["kind"] == "debug" and not entry["empty"] for entry in all_entries),
        "ceresSequenceEntryCount": kinds["ceresSequence"],
        "gunshipLandingEntryCount": kinds["gunshipLanding"],
        "doorBtsNonzeroCount": sum(int(entry["doorBts"]) != 0 for entry in all_entries),
        "engineConsumerCount": len(engine),
        "pointerTable": {
            "address": formatted(POINTER_TABLE),
            "snesAddress": POINTER_TABLE,
            "sourceLabel": label_at(catalog, POINTER_TABLE, ("LoadStationListPointers",)),
            "rawSha256": sha256(pointer_raw),
        },
        "dataEnd": {"address": formatted(DATA_END), "snesAddress": DATA_END},
        "areas": areas,
        "savePlms": placements,
        "unmatchedOccupiedSaveSlots": [
            {"area": entry["area"], "index": entry["index"], "roomId": entry["roomId"]}
            for entry in unmatched_save_slots
        ],
        "engineConsumers": engine,
        "aggregateHashes": {
            "pointers": sha256(pointer_raw),
            "entries": aggregate_hash([
                [entry["area"], entry["index"], entry["kind"], entry["rawSha256"]]
                for entry in all_entries
            ]),
            "savePlms": aggregate_hash(placements),
            "consumers": aggregate_hash(engine),
        },
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Load-station / AreaSave source manifest complete")
    print(f"  Areas: {report['areaCount']} / entries: {report['entryCount']} ({report['entryByteCount']} bytes)")
    print(
        f"  Save slots: {report['occupiedSaveSlotCount']} occupied + "
        f"{report['emptySaveSlotCount']} empty; {report['savePlmPlacementCount']} normal PLMs"
    )
    print(
        f"  Special: {report['occupiedElevatorEntryCount']} elevators, "
        f"{report['occupiedDebugEntryCount']} debug, {report['ceresSequenceEntryCount']} Ceres"
    )
    print(f"  Output: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
