#!/usr/bin/env python3
"""Build the source-derived room, state, level, and resource manifest.

The pinned disassembly remains the authority.  This script parses the RoomHeader,
state-check, and StateHeader macro calls in bank $8F, evaluates their named symbols,
and requires the resulting bytes to match the byte-identical reference ROM.  It then
independently inventories every resource reached by those state records.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

from lz5_oracle import decode_lz5
from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
REPO_ROOT = PARITY_DIR.parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_LZ5_REPORT = PARITY_DIR / "reports" / "lz5.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "rooms.json"
ROOM_MAPPING = REPO_ROOT / "shared/src/commonMain/resources/room_mapping_complete.json"

LABEL = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):")
INNER_MACRO = re.compile(r"^%([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$", re.DOTALL)

HEADER_FIELDS = (
    "room", "area", "positions", "dimensions", "scrollers", "CRE", "doorList",
)
STATE_FIELDS = (
    "levelData", "tileset", "music", "FX", "enemyPop", "enemySet",
    "layer2Scrolls", "scrollPointer", "specialXray", "mainASM", "PLMPop",
    "libraryBG", "setupASM",
)

STATE_CHECKS = {
    "stateCheckDoor": ("UNUSED_RoomStateCheck_Door_8FE5EB", "INCOMING_DOOR", 6, "word"),
    "stateCheckMainBoss": ("RoomStateCheck_MainAreaBossIsDead", "AREA_MAIN_BOSS_DEAD", 4, None),
    "stateCheckEventSet": ("RoomStateCheck_EventHasBeenSet", "EVENT_SET", 5, "byte"),
    "stateCheckBossDead": ("RoomStateCheck_BossIsDead", "AREA_BOSS_BIT_SET", 5, "byte"),
    "stateCheckMorph": ("UNUSED_RoomStateCheck_Morphball_8FE640", "MORPH_BALL_COLLECTED", 4, None),
    "stateCheckMorphMissiles": ("RoomStateCheck_MorphballAndMissiles", "MORPH_BALL_AND_MISSILES", 4, None),
    "stateCheckPowerBombs": ("RoomStateCheck_PowerBombs", "POWER_BOMBS_COLLECTED", 4, None),
    "stateCheckSpeedBooster": ("UNUSED_RoomStateCheck_SpeedBooster_8FE678", "SPEED_BOOSTER_COLLECTED", 4, None),
}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def formatted(address: int) -> str:
    return f"{address >> 16:02X}:{address & 0xFFFF:04X}"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: object) -> str:
    encoded = json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded)


def read_u16(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def read_u24(data: bytes, offset: int) -> int:
    return read_u16(data, offset) | (data[offset + 2] << 16)


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def require_symbol(catalog: SymbolCatalog, name: str) -> int:
    matches = catalog.exact(name)
    if len(matches) != 1:
        fail(f"expected one source symbol named {name}, found {len(matches)}")
    return matches[0].snes_address


def value(expression: str, catalog: SymbolCatalog) -> int:
    expression = expression.strip()
    if re.fullmatch(r"\$[0-9A-Fa-f]+", expression):
        return int(expression[1:], 16)
    if re.fullmatch(r"[0-9]+", expression):
        return int(expression, 10)
    return require_symbol(catalog, expression)


def split_args(text: str) -> List[str]:
    result: List[str] = []
    depth = 0
    start = 0
    for index, character in enumerate(text):
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        elif character == "," and depth == 0:
            result.append(text[start:index].strip())
            start = index + 1
    result.append(text[start:].strip())
    return [part for part in result if part]


def inner_call(text: str) -> Tuple[str, List[str]]:
    match = INNER_MACRO.fullmatch(text.strip())
    if match is None:
        fail(f"expected nested macro call, found {text!r}")
    return match.group(1), split_args(match.group(2))


def invocation(lines: Sequence[str], start: int, macro: str) -> Tuple[List[str], int]:
    marker = f"%{macro}("
    line_index = start
    while line_index < len(lines) and marker not in lines[line_index]:
        if line_index > start and LABEL.match(lines[line_index]):
            fail(f"did not find %{macro} after source line {start + 1}")
        line_index += 1
    if line_index >= len(lines):
        fail(f"did not find %{macro} after source line {start + 1}")
    joined = "\n".join(lines[line_index:])
    begin = joined.index(marker) + len(marker)
    depth = 1
    cursor = begin
    while cursor < len(joined) and depth:
        if joined[cursor] == "(":
            depth += 1
        elif joined[cursor] == ")":
            depth -= 1
        cursor += 1
    if depth:
        fail(f"unterminated %{macro} at source line {line_index + 1}")
    consumed_lines = joined[:cursor].count("\n")
    return split_args(joined[begin:cursor - 1].replace("\\\n", "")), line_index + consumed_lines


def indexed_labels(lines: Sequence[str]) -> Dict[str, int]:
    result: Dict[str, int] = {}
    for index, line in enumerate(lines):
        match = LABEL.match(line)
        if match is not None:
            result[match.group(1)] = index
    return result


def encode_u16(number: int) -> bytes:
    return bytes((number & 0xFF, (number >> 8) & 0xFF))


def encode_u24(number: int) -> bytes:
    return encode_u16(number) + bytes(((number >> 16) & 0xFF,))


def parse_states(
    lines: Sequence[str], catalog: SymbolCatalog, rom: bytes,
) -> Dict[str, Dict[str, object]]:
    records: Dict[str, Dict[str, object]] = {}
    current_label = ""
    for index, line in enumerate(lines):
        label_match = LABEL.match(line)
        if label_match is not None:
            current_label = label_match.group(1)
        if "%StateHeader(" not in line or not current_label.startswith(("RoomState_", "UNUSED_RoomState_")):
            continue
        arguments, _ = invocation(lines, index, "StateHeader")
        if len(arguments) != len(STATE_FIELDS):
            fail(f"{current_label} has {len(arguments)} StateHeader fields")
        fields: Dict[str, object] = {}
        encoded = bytearray()
        source_expressions: Dict[str, object] = {}
        for expected, nested in zip(STATE_FIELDS, arguments):
            actual, nested_args = inner_call(nested)
            if actual != expected:
                fail(f"{current_label} expected %{expected}, found %{actual}")
            source_expressions[expected] = nested_args[0] if len(nested_args) == 1 else nested_args
            numbers = [value(item, catalog) for item in nested_args]
            if expected == "levelData":
                fields["levelDataPtr"] = numbers[0]
                fields["levelDataLabel"] = nested_args[0]
                encoded.extend(encode_u24(numbers[0]))
            elif expected == "tileset":
                fields["tileset"] = numbers[0]
                encoded.append(numbers[0] & 0xFF)
            elif expected == "music":
                fields["musicData"], fields["musicTrack"] = numbers
                encoded.extend(number & 0xFF for number in numbers)
            elif expected == "layer2Scrolls":
                fields["layer2ScrollX"], fields["layer2ScrollY"] = numbers
                fields["bgScrolling"] = numbers[0] | (numbers[1] << 8)
                encoded.extend(number & 0xFF for number in numbers)
            else:
                field_name = {
                    "FX": "fxPtr", "enemyPop": "enemySetPtr", "enemySet": "enemyGfxPtr",
                    "scrollPointer": "scrollPtr", "specialXray": "xraySpecialCasingPtr",
                    "mainASM": "mainAsmPtr", "PLMPop": "plmSetPtr",
                    "libraryBG": "bgDataPtr", "setupASM": "setupAsmPtr",
                }[expected]
                fields[field_name] = numbers[0] & 0xFFFF
                fields[f"{field_name}Label"] = nested_args[0] if not nested_args[0][0].isdigit() and not nested_args[0].startswith("$") else None
                encoded.extend(encode_u16(numbers[0]))
        address = require_symbol(catalog, current_label)
        pc = snes_to_pc(address)
        actual = rom[pc:pc + 26]
        if bytes(encoded) != actual:
            fail(f"{current_label} source fields do not assemble to the reference ROM at {formatted(address)}")
        records[current_label] = {
            "sourceLabel": current_label,
            "address": formatted(address),
            "snesAddress": address,
            "rawSha256": sha256(actual),
            "sourceExpressions": source_expressions,
            **fields,
        }
    return records


def parse_room_mapping() -> Dict[int, Dict[str, object]]:
    payload = json.loads(ROOM_MAPPING.read_text(encoding="utf-8"))["rooms"]
    result: Dict[int, Dict[str, object]] = {}
    for record in payload.values():
        room_id = int(str(record["id"]), 16)
        if room_id in result:
            fail(f"duplicate room mapping for {room_id:04X}")
        result[room_id] = record
    return result


def parse_rooms(
    lines: Sequence[str], catalog: SymbolCatalog, rom: bytes,
    states: Dict[str, Dict[str, object]],
) -> List[Dict[str, object]]:
    rooms: List[Dict[str, object]] = []
    mapping = parse_room_mapping()
    current_label = ""
    default_code = require_symbol(catalog, "Use_StatePointer_inX") & 0xFFFF
    for index, line in enumerate(lines):
        label_match = LABEL.match(line)
        if label_match is not None:
            current_label = label_match.group(1)
        if "%RoomHeader(" not in line or not current_label.startswith(("RoomHeader_", "UNUSED_RoomHeader_")):
            continue
        arguments, header_end = invocation(lines, index, "RoomHeader")
        if len(arguments) != len(HEADER_FIELDS):
            fail(f"{current_label} has {len(arguments)} RoomHeader fields")
        nested_values: Dict[str, List[str]] = {}
        for expected, nested in zip(HEADER_FIELDS, arguments):
            actual, nested_args = inner_call(nested)
            if actual != expected:
                fail(f"{current_label} expected %{expected}, found %{actual}")
            nested_values[expected] = nested_args
        numbers = {name: [value(item, catalog) for item in args] for name, args in nested_values.items()}
        fixed = bytes((
            numbers["room"][0] & 0xFF, numbers["area"][0] & 0xFF,
            numbers["positions"][0] & 0xFF, numbers["positions"][1] & 0xFF,
            numbers["dimensions"][0] & 0xFF, numbers["dimensions"][1] & 0xFF,
            numbers["scrollers"][0] & 0xFF, numbers["scrollers"][1] & 0xFF,
            numbers["CRE"][0] & 0xFF,
        )) + encode_u16(numbers["doorList"][0])
        checks, _ = invocation(lines, header_end, "stateChecks")
        declared_count = int(checks[0], 10)
        check_calls = checks[1:]
        if declared_count != len(check_calls):
            fail(f"{current_label} declares {declared_count} checks but contains {len(check_calls)}")
        address = require_symbol(catalog, current_label)
        pc = snes_to_pc(address)
        if rom[pc:pc + 11] != fixed:
            fail(f"{current_label} source fields do not assemble to the reference ROM at {formatted(address)}")

        selectors: List[Dict[str, object]] = []
        cursor = address + 11
        selector_bytes = bytearray()
        for source_order, check in enumerate(check_calls):
            macro, args = inner_call(check)
            if macro not in STATE_CHECKS:
                fail(f"unsupported room-state source macro %{macro} in {current_label}")
            routine_label, kind, size, argument_width = STATE_CHECKS[macro]
            routine = require_symbol(catalog, routine_label) & 0xFFFF
            state_label = args[-1]
            state_address = require_symbol(catalog, state_label)
            argument_expression = args[0] if argument_width is not None else None
            argument = value(argument_expression, catalog) if argument_expression is not None else None
            encoded = bytearray(encode_u16(routine))
            if argument_width == "byte":
                encoded.append(int(argument) & 0xFF)
            elif argument_width == "word":
                encoded.extend(encode_u16(int(argument)))
            encoded.extend(encode_u16(state_address))
            if len(encoded) != size:
                fail(f"internal selector size mismatch for %{macro}")
            selectors.append({
                "index": source_order,
                "sourceMacro": macro,
                "conditionKind": kind,
                "routineCode": routine,
                "argument": argument,
                "entrySize": size,
                "selectorAddress": formatted(cursor),
                "selectorSnesAddress": cursor,
                "stateLabel": state_label,
                "stateAddress": formatted(state_address),
                "stateSnesAddress": state_address,
            })
            cursor += size
            selector_bytes.extend(encoded)
        selector_bytes.extend(encode_u16(default_code))
        default_state_address = cursor + 2
        default_candidates = [name for name, record in states.items() if record["snesAddress"] == default_state_address]
        if len(default_candidates) != 1:
            fail(f"{current_label} has no unique inline default state at {formatted(default_state_address)}")
        default_label = default_candidates[0]
        selectors.append({
            "index": len(selectors),
            "sourceMacro": "stateChecks.default",
            "conditionKind": "DEFAULT",
            "routineCode": default_code,
            "argument": None,
            "entrySize": 2,
            "selectorAddress": formatted(cursor),
            "selectorSnesAddress": cursor,
            "stateLabel": default_label,
            "stateAddress": formatted(default_state_address),
            "stateSnesAddress": default_state_address,
        })
        selector_pc = pc + 11
        if rom[selector_pc:selector_pc + len(selector_bytes)] != bytes(selector_bytes):
            fail(f"{current_label} selector macros disagree with the reference ROM")
        for selector in selectors:
            if selector["stateLabel"] not in states:
                fail(f"{current_label} references unknown {selector['stateLabel']}")
        room_id = address & 0xFFFF
        mapped = mapping.get(room_id)
        if mapped is None:
            fail(f"source room {current_label} ({room_id:04X}) is absent from room_mapping_complete.json")
        rooms.append({
            "sourceLabel": current_label,
            "name": mapped["name"],
            "handle": mapped["handle"],
            "address": formatted(address),
            "snesAddress": address,
            "roomId": room_id,
            "roomIdHex": f"{room_id:04X}",
            "index": numbers["room"][0],
            "area": numbers["area"][0],
            "mapX": numbers["positions"][0],
            "mapY": numbers["positions"][1],
            "width": numbers["dimensions"][0],
            "height": numbers["dimensions"][1],
            "upScroller": numbers["scrollers"][0],
            "downScroller": numbers["scrollers"][1],
            "creBitflag": numbers["CRE"][0],
            "doorListLabel": nested_values["doorList"][0],
            "doorListPtr": numbers["doorList"][0] & 0xFFFF,
            "rawSha256": sha256(fixed),
            "selectors": selectors,
        })
    rooms.sort(key=lambda record: int(record["snesAddress"]))
    if set(mapping) != {int(room["roomId"]) for room in rooms}:
        fail("room_mapping_complete.json and active source RoomHeader labels do not cover the same IDs")
    used_states = [selector["stateLabel"] for room in rooms for selector in room["selectors"]]
    if len(used_states) != len(set(used_states)) or set(used_states) != set(states):
        fail("active room selectors do not form a one-to-one cover of active RoomState labels")
    return rooms


def source_door_lists(
    lines: Sequence[str], labels: Dict[str, int], catalog: SymbolCatalog,
    rom: bytes, rooms: Sequence[Dict[str, object]],
) -> List[Dict[str, object]]:
    result: List[Dict[str, object]] = []
    for room in rooms:
        label = str(room["doorListLabel"])
        start = labels.get(label)
        if start is None:
            fail(f"missing source door list {label}")
        door_labels: List[str] = []
        for line in lines[start + 1:]:
            if LABEL.match(line):
                break
            stripped = line.strip()
            if not stripped or stripped.startswith(";"):
                continue
            match = re.match(r"dw\s+([^,;\s]+)", stripped)
            if match is not None:
                door_labels.append(match.group(1))
        pointer = require_symbol(catalog, label)
        if (pointer & 0xFFFF) != room["doorListPtr"]:
            fail(f"{room['sourceLabel']} door-list pointer does not resolve to {label}")
        entries: List[Dict[str, object]] = []
        for door_label in door_labels:
            address = require_symbol(catalog, door_label)
            pc = snes_to_pc(address)
            raw = rom[pc:pc + 12]
            entries.append({
                "sourceLabel": door_label,
                "address": formatted(address),
                "snesAddress": address,
                "doorDefPtr": address & 0xFFFF,
                "destRoomPtr": read_u16(raw, 0),
                "elevatorProperties": raw[2],
                "direction": raw[3],
                "doorCapX": raw[4],
                "doorCapY": raw[5],
                "screenX": raw[6],
                "screenY": raw[7],
                "spawnDistance": read_u16(raw, 8),
                "entryCode": read_u16(raw, 10),
                "rawSha256": sha256(raw),
            })
        list_pc = snes_to_pc(pointer)
        expected = b"".join(encode_u16(int(entry["doorDefPtr"])) for entry in entries)
        if rom[list_pc:list_pc + len(expected)] != expected:
            fail(f"{label} source list disagrees with the reference ROM")
        result.append({
            "roomId": room["roomId"],
            "roomLabel": room["sourceLabel"],
            "sourceLabel": label,
            "address": formatted(pointer),
            "snesAddress": pointer,
            "entryCount": len(entries),
            "entries": entries,
        })
    return result


def parse_plms(rom: bytes, pointer: int) -> Dict[str, object]:
    if pointer in (0, 0xFFFF):
        return {"entries": [], "byteCount": 0, "terminator": None}
    pc = snes_to_pc(0x8F0000 | pointer)
    entries: List[Dict[str, int]] = []
    for _ in range(256):
        plm_id = read_u16(rom, pc)
        if plm_id == 0:
            return {"entries": entries, "byteCount": len(entries) * 6 + 2, "terminator": 0}
        entries.append({
            "id": plm_id, "x": rom[pc + 2], "y": rom[pc + 3], "param": read_u16(rom, pc + 4),
        })
        pc += 6
    fail(f"unterminated PLM population at $8F:{pointer:04X}")


def parse_enemy_population(rom: bytes, pointer: int) -> Dict[str, object]:
    if pointer in (0, 0xFFFF):
        return {"entries": [], "byteCount": 0, "terminator": None, "killCount": None}
    pc = snes_to_pc(0xA10000 | pointer)
    entries: List[Dict[str, int]] = []
    for _ in range(256):
        enemy_id = read_u16(rom, pc)
        if enemy_id == 0xFFFF:
            return {
                "entries": entries, "byteCount": len(entries) * 16 + 3,
                "terminator": 0xFFFF, "killCount": rom[pc + 2],
            }
        entries.append({
            "id": enemy_id, "x": read_u16(rom, pc + 2), "y": read_u16(rom, pc + 4),
            "initParam": read_u16(rom, pc + 6), "properties": read_u16(rom, pc + 8),
            "extra1": read_u16(rom, pc + 10), "extra2": read_u16(rom, pc + 12),
            "extra3": read_u16(rom, pc + 14),
        })
        pc += 16
    fail(f"unterminated enemy population at $A1:{pointer:04X}")


def parse_enemy_gfx(rom: bytes, pointer: int) -> Dict[str, object]:
    if pointer in (0, 0xFFFF):
        return {"entries": [], "byteCount": 0, "terminator": None}
    pc = snes_to_pc(0xB40000 | pointer)
    entries: List[Dict[str, int]] = []
    for _ in range(32):
        species = read_u16(rom, pc)
        if species == 0xFFFF:
            return {"entries": entries, "byteCount": len(entries) * 4 + 2, "terminator": 0xFFFF}
        entries.append({"speciesId": species, "paletteIndex": read_u16(rom, pc + 2)})
        pc += 4
    fail(f"unterminated enemy-GFX set at $B4:{pointer:04X}")


def parse_fx(rom: bytes, pointer: int) -> Dict[str, object]:
    if pointer in (0, 0xFFFF):
        return {"entries": [], "byteCount": 0, "terminator": None}
    pc = snes_to_pc(0x830000 | pointer)
    entries: List[Dict[str, int]] = []
    for _ in range(32):
        if read_u16(rom, pc) == 0xFFFF:
            return {
                "entries": entries,
                "byteCount": len(entries) * 16 + 2,
                "terminator": 0xFFFF,
            }
        raw = rom[pc:pc + 16]
        if len(raw) != 16:
            fail(f"truncated FX list at $83:{pointer:04X}")
        entry = {
            "doorSelect": read_u16(raw, 0), "liquidSurfaceStart": read_u16(raw, 2),
            "liquidSurfaceNew": read_u16(raw, 4), "liquidSpeed": read_u16(raw, 6),
            "liquidDelay": raw[8], "fxType": raw[9], "fxBitA": raw[10], "fxBitB": raw[11],
            "fxBitC": raw[12], "paletteFxBitflags": raw[13], "tileAnimBitflags": raw[14],
            "paletteBlend": raw[15],
        }
        entries.append(entry)
        if entry["doorSelect"] == 0:
            return {"entries": entries, "byteCount": len(entries) * 16, "terminator": 0}
        pc += 16
    fail(f"unterminated FX list at $83:{pointer:04X}")


def source_scroll_tables(
    lines: Sequence[str], catalog: SymbolCatalog, rom: bytes,
) -> List[Dict[str, object]]:
    records: List[Dict[str, object]] = []
    for index, raw_line in enumerate(lines):
        match = LABEL.match(raw_line)
        if match is None or not match.group(1).startswith("RoomScrolls_"):
            continue
        label = match.group(1)
        values: List[int] = []
        cursor = index + 1
        while cursor < len(lines):
            source = lines[cursor].split(";", 1)[0].strip()
            if not source:
                if values:
                    break
                cursor += 1
                continue
            if not source.startswith("db "):
                break
            for expression in source[3:].split(","):
                parsed = expression.strip()
                if re.fullmatch(r"\$[0-9A-Fa-f]+", parsed):
                    values.append(int(parsed[1:], 16))
                elif re.fullmatch(r"[0-9]+", parsed):
                    values.append(int(parsed, 10))
                else:
                    fail(f"unsupported scroll byte {parsed!r} in {label}")
            cursor += 1
        if not values:
            fail(f"source scroll table {label} has no bytes")
        if any(item not in (0, 1, 2) for item in values):
            fail(f"source scroll table {label} contains a noncanonical value")
        address = require_symbol(catalog, label)
        raw = bytes(values)
        pc = snes_to_pc(address)
        if rom[pc:pc + len(raw)] != raw:
            fail(f"source scroll table {label} does not match rebuilt ROM bytes")
        records.append({
            "sourceLabel": label,
            "address": formatted(address),
            "snesAddress": address,
            "pointer": address & 0xFFFF,
            "byteCount": len(values),
            "values": values,
            "rawSha256": sha256(raw),
        })
    records.sort(key=lambda record: int(record["snesAddress"]))
    return records


def resource_manifest(
    rom: bytes, rooms: Sequence[Dict[str, object]], states: Dict[str, Dict[str, object]],
    scroll_tables: Sequence[Dict[str, object]],
) -> Dict[str, object]:
    consumers: Dict[str, Dict[int, List[Dict[str, object]]]] = {
        kind: defaultdict(list) for kind in ("plm", "enemyPopulation", "enemyGfx", "fx")
    }
    state_room: Dict[str, Dict[str, object]] = {}
    scroll_associations: List[Dict[str, object]] = []
    scroll_tables_by_pointer = {
        int(record["pointer"]): record for record in scroll_tables
    }
    for room in rooms:
        for selector in room["selectors"]:
            state_label = str(selector["stateLabel"])
            state_room[state_label] = room
            state = states[state_label]
            association = {"roomId": room["roomId"], "roomLabel": room["sourceLabel"], "stateLabel": state_label}
            for kind, field in (
                ("plm", "plmSetPtr"), ("enemyPopulation", "enemySetPtr"),
                ("enemyGfx", "enemyGfxPtr"), ("fx", "fxPtr"),
            ):
                pointer = int(state[field])
                if pointer not in (0, 0xFFFF):
                    consumers[kind][pointer].append(association)
            pointer = int(state["scrollPtr"])
            screen_count = int(room["width"]) * int(room["height"])
            if pointer == 0:
                values = [1] * screen_count
                mode = "all-blue-sentinel"
            elif pointer == 1:
                values = [2] * screen_count
                mode = "all-green-sentinel"
            else:
                pc = snes_to_pc(0x8F0000 | pointer)
                raw_values = list(rom[pc:pc + screen_count])
                values = [raw if raw in (0, 1) else 2 for raw in raw_values]
                mode = "table"
                if len(raw_values) != screen_count:
                    fail(f"truncated scroll table at $8F:{pointer:04X}")
                invalid_raw_values = [
                    {"screenIndex": index, "rawValue": raw}
                    for index, raw in enumerate(raw_values) if raw > 2
                ]
                source_table = scroll_tables_by_pointer.get(pointer)
                if source_table is None:
                    fail(f"scroll pointer $8F:{pointer:04X} has no named source table")
            scroll_associations.append({
                **association, "pointer": pointer, "mode": mode,
                "screenCount": screen_count, "values": values,
                "rawValues": raw_values if pointer not in (0, 1) else values,
                "nonCanonicalRawValues": invalid_raw_values if pointer not in (0, 1) else [],
                "sourceLabel": source_table["sourceLabel"] if pointer not in (0, 1) else None,
                "sourceByteCount": source_table["byteCount"] if pointer not in (0, 1) else None,
                "sourceCoversRoom": source_table["byteCount"] >= screen_count if pointer not in (0, 1) else True,
            })

    parsers = {
        "plm": (0x8F0000, parse_plms),
        "enemyPopulation": (0xA10000, parse_enemy_population),
        "enemyGfx": (0xB40000, parse_enemy_gfx),
        "fx": (0x830000, parse_fx),
    }
    result: Dict[str, object] = {}
    for kind, grouped in consumers.items():
        bank, parser = parsers[kind]
        records: List[Dict[str, object]] = []
        for pointer, associations in sorted(grouped.items()):
            parsed = parser(rom, pointer)
            records.append({
                "pointer": pointer,
                "address": formatted(bank | pointer),
                "snesAddress": bank | pointer,
                "consumerCount": len(associations),
                "consumers": associations,
                **parsed,
            })
        result[kind] = records
    result["scroll"] = scroll_associations
    result["scrollTable"] = list(scroll_tables)
    return result


def level_manifest(
    disassembly: Path, assets_payload: Dict[str, object], lz5_payload: Dict[str, object],
    rooms: Sequence[Dict[str, object]], states: Dict[str, Dict[str, object]],
) -> List[Dict[str, object]]:
    stream_by_name = {record["name"]: record for record in lz5_payload["streams"]}
    consumers: Dict[str, List[Dict[str, object]]] = defaultdict(list)
    for room in rooms:
        for selector in room["selectors"]:
            state = states[str(selector["stateLabel"])]
            label = str(state["levelDataLabel"])
            consumers[label].append({
                "roomId": room["roomId"], "roomLabel": room["sourceLabel"],
                "stateLabel": selector["stateLabel"], "width": room["width"], "height": room["height"],
            })
    records: List[Dict[str, object]] = []
    for asset in assets_payload["assets"]:
        if asset["category"] != "level-data":
            continue
        name = str(asset["name"])
        stream = stream_by_name.get(name)
        if stream is None:
            fail(f"level asset {name} is absent from the exact LZ5 corpus")
        compressed = (disassembly / str(asset["asset"])).read_bytes()
        decoded = decode_lz5(compressed).data
        layer1_bytes = read_u16(decoded, 0)
        if layer1_bytes % 2:
            fail(f"{name} has odd Layer-1 byte count {layer1_bytes}")
        block_count = layer1_bytes // 2
        no_layer2_size = 2 + layer1_bytes + block_count
        layer2_size = no_layer2_size + layer1_bytes
        if len(decoded) == no_layer2_size:
            has_layer2 = False
        elif len(decoded) == layer2_size:
            has_layer2 = True
        else:
            fail(f"{name} decoded size {len(decoded)} does not match its Layer-1/BTS layout")
        dimension_mismatches: List[Dict[str, object]] = []
        for consumer in consumers.get(name, []):
            expected_blocks = int(consumer["width"]) * int(consumer["height"]) * 256
            if expected_blocks != block_count:
                dimension_mismatches.append({
                    **consumer,
                    "expectedBlockCount": expected_blocks,
                    "payloadBlockCount": block_count,
                    "extraBlockCount": block_count - expected_blocks,
                })
        if sha256(compressed) != asset["sha256"] or sha256(decoded) != stream["decompressedSha256"]:
            fail(f"{name} source asset/hash disagreement")
        records.append({
            "sourceLabel": name,
            "asset": asset["asset"],
            "unused": asset["unused"],
            "address": asset["address"],
            "snesAddress": asset["snesAddress"],
            "compressedSize": len(compressed),
            "compressedSha256": sha256(compressed),
            "decompressedSize": len(decoded),
            "decompressedSha256": sha256(decoded),
            "layer1ByteCount": layer1_bytes,
            "blockCount": block_count,
            "btsByteCount": block_count,
            "hasLayer2": has_layer2,
            "layer2ByteCount": layer1_bytes if has_layer2 else 0,
            "consumerCount": len(consumers.get(name, [])),
            "consumers": consumers.get(name, []),
            "consumerDimensionsMatch": not dimension_mismatches,
            "dimensionMismatches": dimension_mismatches,
        })
    records.sort(key=lambda record: int(record["snesAddress"]))
    referenced = set(consumers)
    available = {str(record["sourceLabel"]) for record in records}
    if not referenced <= available:
        fail(f"state records reference missing level assets: {sorted(referenced - available)}")
    return records


def alias_groups(rooms: Sequence[Dict[str, object]], states: Dict[str, Dict[str, object]]) -> Dict[str, object]:
    fields = (
        "levelDataPtr", "fxPtr", "enemySetPtr", "enemyGfxPtr", "scrollPtr",
        "xraySpecialCasingPtr", "mainAsmPtr", "plmSetPtr", "bgDataPtr", "setupAsmPtr",
    )
    grouped: Dict[str, Dict[int, List[str]]] = {field: defaultdict(list) for field in fields}
    for room in rooms:
        for selector in room["selectors"]:
            label = str(selector["stateLabel"])
            state = states[label]
            for field in fields:
                grouped[field][int(state[field])].append(label)
    return {
        field: [
            {"pointer": pointer, "stateCount": len(labels), "stateLabels": labels}
            for pointer, labels in sorted(values.items()) if len(labels) > 1
        ]
        for field, values in grouped.items()
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build exact room/state/resource parity manifests")
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--lz5-report", type=Path, default=DEFAULT_LZ5_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()
    disassembly = (
        args.disassembly or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    source_path = disassembly / "src/bank_8F.asm"
    for path, task in (
        (symbols_path, "parityBuildReference"), (rom_path, "parityBuildReference"),
        (args.asset_manifest, "parityAssets"), (args.lz5_report, "parityLz5Oracle"),
    ):
        if not Path(path).is_file():
            fail(f"missing {path}; run ./gradlew {task}")
    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    lines = source_path.read_text(encoding="utf-8").splitlines()
    states = parse_states(lines, catalog, rom)
    rooms = parse_rooms(lines, catalog, rom, states)
    labels = indexed_labels(lines)
    doors = source_door_lists(lines, labels, catalog, rom, rooms)
    assets_payload = json.loads(Path(args.asset_manifest).read_text(encoding="utf-8"))
    lz5_payload = json.loads(Path(args.lz5_report).read_text(encoding="utf-8"))
    levels = level_manifest(disassembly, assets_payload, lz5_payload, rooms, states)
    scroll_tables = source_scroll_tables(lines, catalog, rom)
    resources = resource_manifest(rom, rooms, states, scroll_tables)
    ordered_states = [states[str(selector["stateLabel"])] for room in rooms for selector in room["selectors"]]
    aliases = alias_groups(rooms, states)
    revision = subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True, text=True, stdout=subprocess.PIPE,
    ).stdout.strip()
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": revision,
        "roomCount": len(rooms),
        "stateCount": len(ordered_states),
        "conditionalSelectorCount": sum(len(room["selectors"]) - 1 for room in rooms),
        "levelStreamCount": len(levels),
        "activeLevelStreamCount": sum(1 for level in levels if not level["unused"]),
        "unusedLevelStreamCount": sum(1 for level in levels if level["unused"]),
        "levelStreamWithLayer2Count": sum(1 for level in levels if level["hasLayer2"]),
        "resourceCounts": {
            "plm": len(resources["plm"]),
            "enemyPopulation": len(resources["enemyPopulation"]),
            "enemyGfx": len(resources["enemyGfx"]),
            "fx": len(resources["fx"]),
            "scrollAssociation": len(resources["scroll"]),
            "scrollTable": len(resources["scrollTable"]),
            "scrollSentinelAssociation": sum(
                1 for record in resources["scroll"] if record["pointer"] in (0, 1)
            ),
            "doorList": len(doors),
            "doorAssociation": sum(int(record["entryCount"]) for record in doors),
            "uniqueDoorDef": len({entry["doorDefPtr"] for record in doors for entry in record["entries"]}),
        },
        "aggregateHashes": {
            "roomHeaders": aggregate_hash([{key: room[key] for key in (
                "sourceLabel", "snesAddress", "index", "area", "mapX", "mapY", "width", "height",
                "upScroller", "downScroller", "creBitflag", "doorListPtr",
            )} for room in rooms]),
            "selectors": aggregate_hash([room["selectors"] for room in rooms]),
            "states": aggregate_hash(ordered_states),
            "levels": aggregate_hash(levels),
            "resources": aggregate_hash(resources),
            "doors": aggregate_hash(doors),
        },
        "rooms": rooms,
        "states": ordered_states,
        "levels": levels,
        "resources": resources,
        "doors": doors,
        "aliasGroups": aliases,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Room/world source manifest complete")
    print(f"  Rooms: {payload['roomCount']}")
    print(f"  States: {payload['stateCount']} ({payload['conditionalSelectorCount']} conditional selectors)")
    print(
        f"  Level streams: {payload['activeLevelStreamCount']} active + "
        f"{payload['unusedLevelStreamCount']} source-declared unused "
        f"({payload['levelStreamWithLayer2Count']} with Layer 2)"
    )
    print(f"  Resources: {payload['resourceCounts']}")
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
