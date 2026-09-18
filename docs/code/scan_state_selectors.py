#!/usr/bin/env python3
"""
Comprehensive state-selector scanner for Super Metroid ROMs.

Scans ALL rooms from room_mapping_complete.json and catalogs every state
selector code encountered, validating entry sizes and state data pointers.
Compares results against the known set handled by RomParser.kt.

Usage:
    python3 scan_state_selectors.py <rom_path> [--verbose]

References:
    - ~/code/sm/src/sm_8f.c     HandleRoomDefStateSelect
    - ~/code/sm/assets/restool.py  Room.parse_actual_binary
    - ~/code/sm/assets/names.txt   function addresses
"""
import json
import struct
import sys
import os
from collections import defaultdict, OrderedDict
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.parent.parent
ROOM_MAPPING = SCRIPT_DIR / "shared/src/commonMain/resources/room_mapping_complete.json"

# ── Known state selector codes ──────────────────────────────────────────
# From ~/code/sm/src/sm_8f.c  +  ~/code/sm/assets/names.txt
# Format: code -> (name, total_entry_bytes, description)
KNOWN_SELECTORS = OrderedDict([
    (0xE5E6, ("Default",            "TERMINAL",  "RoomDefStateSelect_Finish — 26-byte state data inline")),
    (0xE5EB, ("Door",               6,           "RoomDefStateSelect_Door — code(2)+doorPtr(2)+statePtr(2)")),
    (0xE5FF, ("TourianBoss01",      4,           "RoomDefStateSelect_TourianBoss01 — code(2)+statePtr(2)")),
    (0xE60F, ("Never",              4,           "Always false — code(2)+statePtr(2)")),
    (0xE612, ("IsEventSet",         5,           "RoomDefStateSelect_IsEventSet — code(2)+eventFlag(1)+statePtr(2)")),
    (0xE629, ("IsBossDead",         5,           "RoomDefStateSelect_IsBossDead — code(2)+bossFlag(1)+statePtr(2)")),
    (0xE640, ("MorphBall",          4,           "Morph Ball collected — code(2)+statePtr(2)")),
    (0xE652, ("MorphBallMissiles",  4,           "RoomDefStateSelect_MorphBallMissiles — code(2)+statePtr(2)")),
    (0xE669, ("PowerBombs",         4,           "RoomDefStateSelect_PowerBombs — code(2)+statePtr(2)")),
    (0xE678, ("SpeedBooster",       4,           "Speed Booster collected — code(2)+statePtr(2)")),
])

# What our Kotlin parser handles (from RomParser.kt findAllStateDataOffsets)
KOTLIN_HANDLED = {0xE5E6, 0xE5EB, 0xE5FF, 0xE60F, 0xE612, 0xE629, 0xE640, 0xE652, 0xE669, 0xE678}

# Also from restool.py — what snesrev handles (subset that actually appears in vanilla)
RESTOOL_HANDLED = {0xE5E6, 0xE5FF, 0xE612, 0xE629, 0xE652, 0xE669}


def snes_to_pc(snes_addr, has_header=False):
    """LoROM address mapping. Assumes no copier header unless specified."""
    bank = (snes_addr >> 16) & 0xFF
    offset = snes_addr & 0xFFFF
    header = 0x200 if has_header else 0
    return ((bank & 0x7F) * 0x8000) + (offset & 0x7FFF) + header


def read_u8(rom, pc):
    return rom[pc]


def read_u16(rom, pc):
    return struct.unpack_from('<H', rom, pc)[0]


def detect_header(rom_data):
    """Detect copier header (512 bytes prepended)."""
    size = len(rom_data)
    if size % 0x8000 == 0x200:
        return True
    return False


def load_room_ids(mapping_path):
    """Load room IDs from room_mapping_complete.json."""
    with open(mapping_path) as f:
        data = json.load(f)
    rooms = data.get('rooms', data)
    result = []
    for handle, info in rooms.items():
        rid_str = info.get('id', '')
        name = info.get('name', handle)
        try:
            rid = int(rid_str, 16)
            result.append((rid, name, handle))
        except (ValueError, TypeError):
            pass
    return result


def validate_state_ptr(rom, state_ptr_16, has_header):
    """Check if a 16-bit state pointer in bank $8F points to valid state data."""
    snes = 0x8F0000 | state_ptr_16
    pc = snes_to_pc(snes, has_header)
    if pc < 0 or pc + 26 > len(rom):
        return False, pc

    level_data_ptr = read_u16(rom, pc)
    tileset = rom[pc + 2]
    music_data = rom[pc + 3]
    music_track = rom[pc + 4]
    fx_ptr = read_u16(rom, pc + 6)
    enemy_set_ptr = read_u16(rom, pc + 8)
    enemy_gfx_ptr = read_u16(rom, pc + 10)

    # Sanity: level data pointer should be in $C0-$CE bank range (high nybble)
    # or at least non-zero
    if level_data_ptr == 0:
        return False, pc

    return True, pc


def parse_state_selectors(rom, room_id, has_header, verbose=False):
    """
    Parse the full state selector list for a room.
    Returns: list of (code, name, entry_bytes, state_pc, extra_info)
    """
    snes = 0x8F0000 | room_id
    pc = snes_to_pc(snes, has_header)
    if pc < 0 or pc + 11 > len(rom):
        return None, "room header out of bounds"

    room_index = rom[pc]
    room_area = rom[pc + 1]
    room_x = rom[pc + 2]
    room_y = rom[pc + 3]
    room_w = rom[pc + 4]
    room_h = rom[pc + 5]

    state_list_start = pc + 11
    pos = state_list_start
    max_pos = min(state_list_start + 300, len(rom) - 1)
    entries = []

    while pos + 1 < max_pos:
        code = read_u16(rom, pos)

        if code == 0xE5E6:
            # Terminal: 26-byte state data follows inline
            state_pc = pos + 2
            valid, _ = True, state_pc
            if state_pc + 26 > len(rom):
                valid = False
            entries.append({
                'code': code,
                'name': 'Default',
                'entry_bytes': 'TERMINAL',
                'state_pc': state_pc,
                'valid': valid,
                'rom_offset': pos,
                'extra': None,
            })
            break

        elif code == 0xE5EB:
            # Door: code(2) + door_ptr(2) + state_ptr(2) = 6
            if pos + 5 >= len(rom):
                entries.append({'code': code, 'name': 'Door', 'entry_bytes': 6,
                                'state_pc': None, 'valid': False, 'rom_offset': pos,
                                'extra': 'truncated'})
                break
            door_ptr = read_u16(rom, pos + 2)
            state_ptr = read_u16(rom, pos + 4)
            valid, state_pc = validate_state_ptr(rom, state_ptr, has_header)
            entries.append({
                'code': code, 'name': 'Door', 'entry_bytes': 6,
                'state_pc': state_pc, 'valid': valid, 'rom_offset': pos,
                'extra': f'door_ptr=0x{door_ptr:04X} state_ptr=0x{state_ptr:04X}',
            })
            pos += 6

        elif code in (0xE612, 0xE629):
            # IsEventSet / IsBossDead: code(2) + flag(1) + state_ptr(2) = 5
            name = 'IsEventSet' if code == 0xE612 else 'IsBossDead'
            if pos + 4 >= len(rom):
                entries.append({'code': code, 'name': name, 'entry_bytes': 5,
                                'state_pc': None, 'valid': False, 'rom_offset': pos,
                                'extra': 'truncated'})
                break
            flag = rom[pos + 2]
            state_ptr = read_u16(rom, pos + 3)
            valid, state_pc = validate_state_ptr(rom, state_ptr, has_header)
            entries.append({
                'code': code, 'name': name, 'entry_bytes': 5,
                'state_pc': state_pc, 'valid': valid, 'rom_offset': pos,
                'extra': f'flag=0x{flag:02X} state_ptr=0x{state_ptr:04X}',
            })
            pos += 5

        elif code in (0xE5FF, 0xE60F, 0xE640, 0xE652, 0xE669, 0xE678):
            # 4-byte entries: code(2) + state_ptr(2)
            names = {
                0xE5FF: 'TourianBoss01',
                0xE60F: 'Never',
                0xE640: 'MorphBall',
                0xE652: 'MorphBallMissiles',
                0xE669: 'PowerBombs',
                0xE678: 'SpeedBooster',
            }
            name = names[code]
            if pos + 3 >= len(rom):
                entries.append({'code': code, 'name': name, 'entry_bytes': 4,
                                'state_pc': None, 'valid': False, 'rom_offset': pos,
                                'extra': 'truncated'})
                break
            state_ptr = read_u16(rom, pos + 2)
            valid, state_pc = validate_state_ptr(rom, state_ptr, has_header)
            entries.append({
                'code': code, 'name': name, 'entry_bytes': 4,
                'state_pc': state_pc, 'valid': valid, 'rom_offset': pos,
                'extra': f'state_ptr=0x{state_ptr:04X}',
            })
            pos += 4

        else:
            # UNKNOWN CODE
            entries.append({
                'code': code, 'name': f'UNKNOWN_0x{code:04X}',
                'entry_bytes': '?',
                'state_pc': None, 'valid': False, 'rom_offset': pos,
                'extra': f'raw bytes: {" ".join(f"{rom[pos+i]:02X}" for i in range(min(12, len(rom)-pos)))}',
            })
            break

    return entries, None


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <rom_path> [--verbose]")
        sys.exit(1)

    rom_path = sys.argv[1]
    verbose = '--verbose' in sys.argv

    if not os.path.exists(rom_path):
        print(f"ROM not found: {rom_path}")
        sys.exit(1)

    with open(rom_path, 'rb') as f:
        rom = f.read()

    has_header = detect_header(rom)
    print(f"ROM: {rom_path}")
    print(f"Size: {len(rom)} bytes (0x{len(rom):X})")
    print(f"Copier header: {'yes' if has_header else 'no'}")

    mapping_path = ROOM_MAPPING
    if not mapping_path.exists():
        mapping_path = Path(rom_path).parent.parent / "shared/src/commonMain/resources/room_mapping_complete.json"
    if not mapping_path.exists():
        print(f"Room mapping not found at {mapping_path}")
        sys.exit(1)

    room_list = load_room_ids(mapping_path)
    print(f"Rooms to scan: {len(room_list)}")
    print()

    # ── Scan all rooms ──────────────────────────────────────────────────
    all_codes = defaultdict(int)
    code_rooms = defaultdict(list)
    invalid_states = []
    unknown_codes = []
    rooms_with_errors = []
    total_states = 0
    multi_state_rooms = []

    for room_id, name, handle in room_list:
        entries, error = parse_state_selectors(rom, room_id, has_header, verbose)
        if error:
            rooms_with_errors.append((room_id, name, error))
            continue

        for e in entries:
            all_codes[e['code']] += 1
            code_rooms[e['code']].append((room_id, name))
            total_states += 1

            if not e['valid']:
                invalid_states.append((room_id, name, e))
            if e['code'] not in KNOWN_SELECTORS:
                unknown_codes.append((room_id, name, e))

        if len(entries) > 1:
            multi_state_rooms.append((room_id, name, len(entries), entries))

        if verbose and entries:
            print(f"Room 0x{room_id:04X} ({name}):")
            for e in entries:
                v = "OK" if e['valid'] else "INVALID"
                extra = f" [{e['extra']}]" if e['extra'] else ""
                print(f"  {e['name']:20s} (0x{e['code']:04X}) {e['entry_bytes']:>8}B  "
                      f"state_pc=0x{e['state_pc']:06X}  {v}{extra}" if e['state_pc'] else
                      f"  {e['name']:20s} (0x{e['code']:04X}) {e['entry_bytes']:>8}  "
                      f"state_pc=None  {v}{extra}")

    # ── Summary ─────────────────────────────────────────────────────────
    print("=" * 70)
    print("STATE SELECTOR CODE INVENTORY")
    print("=" * 70)
    print(f"{'Code':>8}  {'Name':<22} {'Count':>5}  {'Size':>6}  {'In Kotlin':>9}  {'In restool':>10}")
    print("-" * 70)
    for code in sorted(all_codes.keys()):
        count = all_codes[code]
        known = KNOWN_SELECTORS.get(code)
        name = known[0] if known else f"UNKNOWN_0x{code:04X}"
        size = known[1] if known else "?"
        in_kt = "YES" if code in KOTLIN_HANDLED else "MISSING"
        in_rt = "YES" if code in RESTOOL_HANDLED else "no"
        print(f"  0x{code:04X}  {name:<22} {count:>5}  {str(size):>6}  {in_kt:>9}  {in_rt:>10}")
    print("-" * 70)
    print(f"Total state entries across all rooms: {total_states}")
    print(f"Unique selector codes: {len(all_codes)}")
    print()

    # ── Coverage check ──────────────────────────────────────────────────
    missing_in_kotlin = set(all_codes.keys()) - KOTLIN_HANDLED
    if missing_in_kotlin:
        print("!!! CODES FOUND IN ROM BUT NOT IN KOTLIN PARSER !!!")
        for code in sorted(missing_in_kotlin):
            rooms = code_rooms[code]
            print(f"  0x{code:04X}: appears in {len(rooms)} rooms:")
            for rid, rname in rooms[:5]:
                print(f"    - 0x{rid:04X} ({rname})")
            if len(rooms) > 5:
                print(f"    ... and {len(rooms)-5} more")
        print()
    else:
        print("All state selector codes found in ROM are handled by Kotlin parser.")
        print()

    extra_in_kotlin = KOTLIN_HANDLED - set(all_codes.keys())
    if extra_in_kotlin:
        print(f"Codes in Kotlin parser but NOT found in this ROM (defensive handling):")
        for code in sorted(extra_in_kotlin):
            known = KNOWN_SELECTORS.get(code)
            name = known[0] if known else "?"
            print(f"  0x{code:04X} ({name})")
        print()

    # ── Invalid states ──────────────────────────────────────────────────
    if invalid_states:
        print(f"INVALID STATE POINTERS ({len(invalid_states)} found):")
        for rid, name, e in invalid_states:
            print(f"  Room 0x{rid:04X} ({name}): {e['name']} (0x{e['code']:04X}) "
                  f"state_pc={e['state_pc']} [{e['extra']}]")
        print()

    # ── Unknown codes ───────────────────────────────────────────────────
    if unknown_codes:
        print(f"UNKNOWN STATE CODES ({len(unknown_codes)} found):")
        for rid, name, e in unknown_codes:
            print(f"  Room 0x{rid:04X} ({name}): {e['name']} [{e['extra']}]")
        print()

    # ── Multi-state rooms ───────────────────────────────────────────────
    if multi_state_rooms:
        print(f"MULTI-STATE ROOMS ({len(multi_state_rooms)} rooms):")
        for rid, name, count, entries in sorted(multi_state_rooms, key=lambda x: -x[2]):
            codes = ", ".join(f"{e['name']}(0x{e['code']:04X})" for e in entries)
            print(f"  0x{rid:04X} ({name}): {count} states — {codes}")
        print()

    # ── Rooms with parse errors ─────────────────────────────────────────
    if rooms_with_errors:
        print(f"ROOMS WITH PARSE ERRORS ({len(rooms_with_errors)}):")
        for rid, name, err in rooms_with_errors:
            print(f"  0x{rid:04X} ({name}): {err}")
        print()

    # ── Verification: compare against snesrev restool.py known set ──────
    rom_only = set(all_codes.keys()) - {0xE5E6}  # exclude terminal
    restool_only = RESTOOL_HANDLED - {0xE5E6}
    restool_missing = rom_only - restool_only
    if restool_missing:
        print(f"Codes in ROM but NOT in snesrev restool.py parse loop:")
        for code in sorted(restool_missing):
            known = KNOWN_SELECTORS.get(code)
            name = known[0] if known else "?"
            count = all_codes.get(code, 0)
            print(f"  0x{code:04X} ({name}) — {count} occurrences")
        print()

    print("=" * 70)
    print("SCAN COMPLETE")
    print("=" * 70)


if __name__ == '__main__':
    main()
