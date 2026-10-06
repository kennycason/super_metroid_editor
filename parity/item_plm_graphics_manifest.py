#!/usr/bin/env python3
"""Build a source/ROM manifest for dynamically loaded item PLM graphics."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, List, Sequence

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "item-plm-graphics.json"

LABEL = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):")
LOAD_INSTRUCTION = re.compile(
    r"^\s*dw\s+Instruction_PLM_LoadItemPLMGFX\s+;([0-9A-Fa-f]{6});"
)
LOAD_ARGUMENTS = re.compile(
    r"^\s*dw\s+(ItemPLMGFX_[A-Za-z0-9_]+)\s*:\s*db\s+(.+?)\s+;([0-9A-Fa-f]{6});"
)
WORD_SOURCE = re.compile(
    r"^\s*dw\s+([A-Za-z_][A-Za-z0-9_]*)\s+;([0-9A-Fa-f]{6});"
)
CALL_SITE = re.compile(
    r"^\s*(JSR\.W|JSL\.L)\s+Instruction_PLM_LoadItemPLMGFX\s+;([0-9A-Fa-f]{6});"
)

DISPLAY_NAMES = {
    "Bombs": "Bomb",
    "ChargeBeam": "Charge Beam",
    "IceBeam": "Ice Beam",
    "HiJumpBoots": "Hi-Jump Boots",
    "SpeedBooster": "Speed Booster",
    "WaveBeam": "Wave Beam",
    "Spazer": "Spazer",
    "SpringBall": "Spring Ball",
    "VariaSuit": "Varia Suit",
    "GravitySuit": "Gravity Suit",
    "XrayScope": "X-Ray Scope",
    "PlasmaBeam": "Plasma Beam",
    "GrappleBeam": "Grapple Beam",
    "SpaceJump": "Space Jump",
    "ScrewAttack": "Screw Attack",
    "MorphBall": "Morph Ball",
    "ReserveTank": "Reserve Tank",
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


def read_u16(rom: bytes, address: int) -> int:
    pc = snes_to_pc(address)
    return rom[pc] | (rom[pc + 1] << 8)


def read_bytes(rom: bytes, address: int, size: int) -> bytes:
    pc = snes_to_pc(address)
    return rom[pc : pc + size]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]], fields: Sequence[str]) -> str:
    digest = hashlib.sha256()
    for record in records:
        for field in fields:
            value = record[field]
            if isinstance(value, list):
                value = ",".join(str(part) for part in value)
            digest.update(str(value).encode("utf-8"))
            digest.update(b"\0")
        digest.update(b"\n")
    return digest.hexdigest()


def load_json(path: Path, task: str) -> Dict[str, object]:
    if not path.is_file():
        fail(f"missing {path}; run ./gradlew {task}")
    return json.loads(path.read_text(encoding="utf-8"))


def source_revision(disassembly: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def require_unique_symbol(catalog: SymbolCatalog, name: str) -> int:
    matches = catalog.exact(name)
    if len(matches) != 1:
        fail(f"expected one source symbol named {name}, found {len(matches)}")
    return matches[0].snes_address


def next_source_lines(lines: Sequence[str], start: int, count: int) -> List[str]:
    result: List[str] = []
    for raw_line in lines[start:]:
        code = raw_line.split(";", 1)[0].strip()
        if not code or code.startswith("if ") or code.startswith("endif "):
            continue
        if code.endswith(":"):
            break
        result.append(raw_line)
        if len(result) == count:
            break
    return result


def decode_standard_4bpp(data: bytes) -> bytes:
    if not data or len(data) % 32:
        fail(f"item PLM graphics payload has invalid 4bpp size {len(data)}")
    pixels = bytearray()
    for tile_offset in range(0, len(data), 32):
        for row in range(8):
            bp0 = data[tile_offset + row * 2]
            bp1 = data[tile_offset + row * 2 + 1]
            bp2 = data[tile_offset + row * 2 + 16]
            bp3 = data[tile_offset + row * 2 + 17]
            for column in range(8):
                bit = 7 - column
                pixels.append(
                    ((bp0 >> bit) & 1)
                    | (((bp1 >> bit) & 1) << 1)
                    | (((bp2 >> bit) & 1) << 2)
                    | (((bp3 >> bit) & 1) << 3)
                )
    return bytes(pixels)


def build_assets(
    disassembly: Path,
    source_assets: Sequence[Dict[str, object]],
    rom: bytes,
) -> List[Dict[str, object]]:
    assets: List[Dict[str, object]] = []
    for source in sorted(source_assets, key=lambda record: int(record["snesAddress"])):
        label = str(source["name"])
        item_key = label.removeprefix("ItemPLMGFX_")
        if item_key not in DISPLAY_NAMES:
            fail(f"unknown item PLM graphics label {label}")
        address = int(source["snesAddress"])
        size = int(source["size"])
        data = (disassembly / str(source["asset"])).read_bytes()
        if size != 0x100 or len(data) != 0x100:
            fail(f"{label} must be exactly $100 bytes")
        if address >> 16 != 0x89:
            fail(f"{label} must be in bank $89")
        if read_bytes(rom, address, size) != data:
            fail(f"{label} source bytes disagree with rebuilt ROM")
        pixels = decode_standard_4bpp(data)
        assets.append(
            {
                "sourceLabel": label,
                "itemKey": item_key,
                "displayName": DISPLAY_NAMES[item_key],
                "asset": source["asset"],
                "address": formatted(address),
                "snesAddress": address,
                "size": size,
                "tileCount": size // 32,
                "frameCount": 2,
                "tilesPerFrame": 4,
                "sha256": sha256(data),
                "pixelCount": len(pixels),
                "pixelSha256": sha256(pixels),
            }
        )
    if len(assets) != 17:
        fail(f"expected 17 item PLM graphics assets, found {len(assets)}")
    for index, asset in enumerate(assets):
        expected = 0x898000 + index * 0x100
        if int(asset["snesAddress"]) != expected:
            fail(
                f"item PLM payload {index} starts at {asset['address']}; "
                f"expected {formatted(expected)}"
            )
    return assets


def parse_plm_entries(
    lines: Sequence[str], catalog: SymbolCatalog, rom: bytes
) -> Dict[str, Dict[str, object]]:
    by_instruction_list: Dict[str, Dict[str, object]] = {}
    for index, line in enumerate(lines):
        match = LABEL.match(line)
        if match is None or not match.group(1).startswith("PLMEntries_"):
            continue
        entry_label = match.group(1)
        words = next_source_lines(lines, index + 1, 2)
        if len(words) != 2:
            continue
        setup_match = WORD_SOURCE.match(words[0])
        list_match = WORD_SOURCE.match(words[1])
        if setup_match is None or list_match is None:
            continue
        setup_label, setup_field_text = setup_match.groups()
        instruction_list_label, list_field_text = list_match.groups()
        if not instruction_list_label.startswith("InstList_PLM_"):
            continue
        entry_address = require_unique_symbol(catalog, entry_label)
        setup_field = int(setup_field_text, 16)
        list_field = int(list_field_text, 16)
        setup_address = require_unique_symbol(catalog, setup_label)
        instruction_list_address = require_unique_symbol(catalog, instruction_list_label)
        if setup_field != entry_address or list_field != entry_address + 2:
            fail(f"{entry_label} source field addresses are not contiguous")
        if read_u16(rom, entry_address) != (setup_address & 0xFFFF):
            fail(f"{entry_label} setup pointer disagrees with rebuilt ROM")
        if read_u16(rom, entry_address + 2) != (instruction_list_address & 0xFFFF):
            fail(f"{entry_label} instruction-list pointer disagrees with rebuilt ROM")
        by_instruction_list[instruction_list_label] = {
            "plmEntryLabel": entry_label,
            "plmId": entry_address & 0xFFFF,
            "plmAddress": formatted(entry_address),
            "plmSnesAddress": entry_address,
            "setupLabel": setup_label,
            "setupAddress": formatted(setup_address),
            "setupSnesAddress": setup_address,
        }
    return by_instruction_list


def variant_for_entry(label: str, item_key: str) -> str:
    base = f"PLMEntries_{item_key}"
    if label == base:
        return "visible"
    if label == base + "ChozoOrb":
        return "chozo"
    if label == base + "ShotBlock":
        return "hidden"
    fail(f"cannot classify {label} for {item_key}")
    raise AssertionError


def parse_load_records(
    lines: Sequence[str], catalog: SymbolCatalog, rom: bytes
) -> List[Dict[str, object]]:
    plm_entries = parse_plm_entries(lines, catalog, rom)
    current_label = ""
    records: List[Dict[str, object]] = []
    load_opcode = require_unique_symbol(catalog, "Instruction_PLM_LoadItemPLMGFX")
    for index, line in enumerate(lines):
        label_match = LABEL.match(line)
        if label_match is not None:
            current_label = label_match.group(1)
        load_match = LOAD_INSTRUCTION.match(line)
        if load_match is None:
            continue
        instruction_address = int(load_match.group(1), 16)
        arguments = next_source_lines(lines, index + 1, 1)
        if len(arguments) != 1:
            fail(f"missing arguments after load instruction at {formatted(instruction_address)}")
        argument_match = LOAD_ARGUMENTS.match(arguments[0])
        if argument_match is None:
            fail(f"malformed item GFX arguments after {formatted(instruction_address)}")
        source_label, palette_text, argument_address_text = argument_match.groups()
        argument_address = int(argument_address_text, 16)
        palette_bytes = [
            int(token.strip().removeprefix("$"), 16) for token in palette_text.split(",")
        ]
        if len(palette_bytes) != 8 or any(value > 7 for value in palette_bytes):
            fail(f"{current_label} must declare eight 3-bit palette indices")
        source_address = require_unique_symbol(catalog, source_label)
        entry = plm_entries.get(current_label)
        if entry is None:
            fail(f"no PLM entry points to item instruction list {current_label}")
        item_key = source_label.removeprefix("ItemPLMGFX_")
        variant = variant_for_entry(str(entry["plmEntryLabel"]), item_key)
        if read_u16(rom, instruction_address) != (load_opcode & 0xFFFF):
            fail(f"load opcode at {formatted(instruction_address)} disagrees with rebuilt ROM")
        if read_u16(rom, argument_address) != (source_address & 0xFFFF):
            fail(f"GFX pointer at {formatted(argument_address)} disagrees with rebuilt ROM")
        if list(read_bytes(rom, argument_address + 2, 8)) != palette_bytes:
            fail(f"palette bytes at {formatted(argument_address + 2)} disagree with rebuilt ROM")
        records.append(
            {
                "itemKey": item_key,
                "displayName": DISPLAY_NAMES[item_key],
                "variant": variant,
                "instructionListLabel": current_label,
                "instructionListAddress": formatted(
                    require_unique_symbol(catalog, current_label)
                ),
                "instructionListSnesAddress": require_unique_symbol(catalog, current_label),
                "loadInstructionAddress": formatted(instruction_address),
                "loadInstructionSnesAddress": instruction_address,
                "argumentAddress": formatted(argument_address),
                "argumentSnesAddress": argument_address,
                "sourceLabel": source_label,
                "sourceAddress": formatted(source_address),
                "sourceSnesAddress": source_address,
                "paletteIndices": palette_bytes,
                **entry,
            }
        )
    records.sort(key=lambda record: int(record["loadInstructionSnesAddress"]))
    if len(records) != 51:
        fail(f"expected 51 item GFX loads, found {len(records)}")
    counts = Counter(str(record["sourceLabel"]) for record in records)
    if set(counts.values()) != {3} or len(counts) != 17:
        fail("each of the 17 item GFX payloads must have exactly three PLM variants")
    variants = Counter(str(record["variant"]) for record in records)
    if variants != {"visible": 17, "chozo": 17, "hidden": 17}:
        fail(f"unexpected item PLM variant distribution: {dict(variants)}")
    return records


def build_slot_contract(
    catalog: SymbolCatalog, rom: bytes
) -> Dict[str, object]:
    routine = require_unique_symbol(catalog, "Instruction_PLM_LoadItemPLMGFX")
    vram_table = require_unique_symbol(
        catalog, "Instruction_PLM_LoadItemPLMGFX_VRAMAddresses"
    )
    tile_table = require_unique_symbol(
        catalog, "Instruction_PLM_LoadItemPLMGFX_tileTableIndices"
    )
    starting_tiles = require_unique_symbol(
        catalog, "Instruction_PLM_LoadItemPLMGFX_startingTileNumbers"
    )
    frame0_table = require_unique_symbol(catalog, "Instruction_PLM_DrawItemFrame0_drawInsts")
    frame1_table = require_unique_symbol(catalog, "Instruction_PLM_DrawItemFrame1_drawInsts")
    expected_vram = (0x3E00, 0x3E80, 0x3F00, 0x3F80)
    expected_offsets = (0x0470, 0x0480, 0x0490, 0x04A0)
    expected_tiles = (0x03E0, 0x03E8, 0x03F0, 0x03F8)
    slots: List[Dict[str, object]] = []
    for index in range(4):
        vram = read_u16(rom, vram_table + index * 2)
        table_offset = read_u16(rom, tile_table + index * 2)
        first_tile = read_u16(rom, starting_tiles + index * 2)
        if (vram, table_offset, first_tile) != (
            expected_vram[index],
            expected_offsets[index],
            expected_tiles[index],
        ):
            fail(f"item PLM slot {index} tables disagree with the vanilla contract")
        draw_frames: List[Dict[str, object]] = []
        for frame, pointer_table in enumerate((frame0_table, frame1_table)):
            draw_pointer = read_u16(rom, pointer_table + index * 2)
            draw_address = 0x840000 | draw_pointer
            duration_or_count = read_u16(rom, draw_address)
            draw_word = read_u16(rom, draw_address + 2)
            terminator = read_u16(rom, draw_address + 4)
            expected_metatile = 0x8E + index * 2 + frame
            if duration_or_count != 1 or terminator != 0 or draw_word & 0x03FF != expected_metatile:
                fail(f"item draw instruction for slot {index}, frame {frame} is inconsistent")
            draw_frames.append(
                {
                    "frame": frame,
                    "pointerFieldAddress": formatted(pointer_table + index * 2),
                    "pointerFieldSnesAddress": pointer_table + index * 2,
                    "drawInstructionAddress": formatted(draw_address),
                    "drawInstructionSnesAddress": draw_address,
                    "drawWord": draw_word,
                    "metatileIndex": expected_metatile,
                }
            )
        slots.append(
            {
                "slot": index,
                "counterValue": index * 2,
                "vramWordAddress": vram,
                "vramByteAddress": vram * 2,
                "tileTableByteOffset": table_offset,
                "tileTableWramAddress": 0x7EA000 + table_offset,
                "firstTileNumber": first_tile,
                "lastTileNumber": first_tile + 7,
                "frame0Metatile": 0x8E + index * 2,
                "frame1Metatile": 0x8F + index * 2,
                "drawFrames": draw_frames,
            }
        )

    immediate_checks = (
        (routine + 0x08, bytes((0x1A, 0x1A, 0x29, 0x06, 0x00)), "slot increment/mask"),
        (routine + 0x26, bytes((0xA9, 0x00, 0x01)), "$100-byte DMA size"),
        (routine + 0x30, bytes((0xA9, 0x89, 0x00)), "bank-$89 DMA source"),
    )
    checks = []
    for address, expected, description in immediate_checks:
        if read_bytes(rom, address, len(expected)) != expected:
            fail(f"{description} instruction bytes disagree with rebuilt ROM")
        checks.append(
            {
                "description": description,
                "address": formatted(address),
                "snesAddress": address,
                "bytes": list(expected),
            }
        )

    return {
        "loadRoutineAddress": formatted(routine),
        "loadRoutineSnesAddress": routine,
        "sourceBank": 0x89,
        "transferSize": 0x100,
        "slotCounterWramAddress": require_unique_symbol(catalog, "PLM_ItemGFXIndex"),
        "slotCounterMask": 0x0006,
        "slotCount": 4,
        "pointerArrayWramAddress": require_unique_symbol(catalog, "PLM_ItemGFXPointers"),
        "plmAssignedSlotWramAddress": require_unique_symbol(catalog, "PLMExtra_Vars"),
        "tileTableWramAddress": require_unique_symbol(catalog, "TileTable"),
        "vramAddressTable": formatted(vram_table),
        "vramAddressTableSnesAddress": vram_table,
        "tileTableIndexTable": formatted(tile_table),
        "tileTableIndexTableSnesAddress": tile_table,
        "startingTileTable": formatted(starting_tiles),
        "startingTileTableSnesAddress": starting_tiles,
        "frame0DrawPointerTable": formatted(frame0_table),
        "frame0DrawPointerTableSnesAddress": frame0_table,
        "frame1DrawPointerTable": formatted(frame1_table),
        "frame1DrawPointerTableSnesAddress": frame1_table,
        "immediateChecks": checks,
        "slots": slots,
    }


def build_dispatch_calls(lines: Sequence[str], catalog: SymbolCatalog, rom: bytes) -> List[Dict[str, object]]:
    target = require_unique_symbol(catalog, "Instruction_PLM_LoadItemPLMGFX")
    calls: List[Dict[str, object]] = []
    for line in lines:
        match = CALL_SITE.match(line)
        if match is None:
            continue
        instruction, address_text = match.groups()
        address = int(address_text, 16)
        if instruction == "JSR.W":
            if read_bytes(rom, address, 1) != b"\x20" or read_u16(rom, address + 1) != (target & 0xFFFF):
                fail(f"item GFX dispatch call at {formatted(address)} disagrees with rebuilt ROM")
        else:
            fail("unexpected long call to same-bank item GFX instruction")
        calls.append(
            {
                "instruction": instruction,
                "address": formatted(address),
                "snesAddress": address,
                "targetAddress": formatted(target),
                "targetSnesAddress": target,
            }
        )
    if len(calls) != 1:
        fail(f"expected one item GFX interpreter dispatch call, found {len(calls)}")
    return calls


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate source-backed item PLM graphics, PLM IDs, and runtime placement."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    assets_manifest = load_json(args.asset_manifest.expanduser().resolve(), "parityAssets")
    revision = source_revision(disassembly)
    if assets_manifest["disassemblyCommit"] != revision:
        fail("asset manifest and disassembly checkout use different commits")
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    if not symbols_path.is_file() or not rom_path.is_file():
        fail(f"missing reference build outputs in {disassembly}; run ./gradlew parityBuildReference")
    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    lines = (disassembly / "src" / "bank_84.asm").read_text(encoding="utf-8").splitlines()
    source_assets = [
        record
        for record in assets_manifest["assets"]
        if record["category"] == "item-plm-graphics"
    ]

    assets = build_assets(disassembly, source_assets, rom)
    load_records = parse_load_records(lines, catalog, rom)
    contract = build_slot_contract(catalog, rom)
    dispatch_calls = build_dispatch_calls(lines, catalog, rom)
    asset_labels = {str(asset["sourceLabel"]) for asset in assets}
    if {str(record["sourceLabel"]) for record in load_records} != asset_labels:
        fail("item GFX load records and extracted asset labels do not match")

    palette_profiles = {
        tuple(int(value) for value in record["paletteIndices"])
        for record in load_records
    }
    totals = {
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "tileCount": sum(int(record["tileCount"]) for record in assets),
        "frameCount": sum(int(record["frameCount"]) for record in assets),
        "loadRecordCount": len(load_records),
        "plmIdCount": len({int(record["plmId"]) for record in load_records}),
        "visiblePlmCount": sum(record["variant"] == "visible" for record in load_records),
        "chozoPlmCount": sum(record["variant"] == "chozo" for record in load_records),
        "hiddenPlmCount": sum(record["variant"] == "hidden" for record in load_records),
        "paletteProfileCount": len(palette_profiles),
        "slotCount": len(contract["slots"]),
        "drawPointerCount": sum(len(slot["drawFrames"]) for slot in contract["slots"]),
        "dispatchCallCount": len(dispatch_calls),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": revision,
        "oracle": "Source declarations plus independent standard-4bpp decoding and rebuilt-ROM checks",
        "totals": totals,
        "aggregateHashes": {
            "assets": aggregate_hash(assets, ("sourceLabel", "snesAddress", "size", "sha256")),
            "pixels": aggregate_hash(assets, ("sourceLabel", "pixelSha256")),
            "loadRecords": aggregate_hash(
                load_records,
                ("itemKey", "variant", "plmId", "sourceSnesAddress", "paletteIndices"),
            ),
            "slots": aggregate_hash(
                contract["slots"],
                (
                    "slot",
                    "counterValue",
                    "vramWordAddress",
                    "tileTableByteOffset",
                    "firstTileNumber",
                    "frame0Metatile",
                    "frame1Metatile",
                ),
            ),
        },
        "assets": assets,
        "loadRecords": load_records,
        "runtimeContract": contract,
        "dispatchCalls": dispatch_calls,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Item PLM graphics manifest valid")
    print(
        f"  Assets: {totals['assetCount']} / {totals['assetByteCount']} bytes / "
        f"{totals['tileCount']} tiles / {totals['frameCount']} frames"
    )
    print(
        f"  PLMs: {totals['loadRecordCount']} load records "
        f"({totals['visiblePlmCount']} visible, {totals['chozoPlmCount']} Chozo, "
        f"{totals['hiddenPlmCount']} shot-block)"
    )
    print(
        f"  Runtime: {totals['slotCount']} slots / {totals['drawPointerCount']} draw pointers / "
        f"{totals['paletteProfileCount']} palette profiles"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
