#!/usr/bin/env python3
"""Build a source/ROM manifest for Super Metroid animated-tile objects."""

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
from typing import Dict, Iterable, List, Sequence, Set, Tuple

from symbol_catalog import SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_ASSET_MANIFEST = PARITY_DIR / "reports" / "assets.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "animated-tiles.json"

OBJECT_LABEL = re.compile(r"^((?:UNUSED_)?AnimatedTilesObjects?_[A-Za-z0-9_]+):")
OBJECT_POINTER = re.compile(r"^dw\s+((?:UNUSED_)?InstList_[A-Za-z0-9_]+)$")
OBJECT_FIELDS = re.compile(r"^dw\s+\$([0-9A-Fa-f]{4}),\$([0-9A-Fa-f]{4})$")
SOURCE_FRAME = re.compile(
    r"^\s*dw\s+\$([0-9A-Fa-f]{4}),((?:UNUSED_)?AnimatedTiles_[A-Za-z0-9_]+)"
)
CALL_SITE = re.compile(
    r"^\s*(JSL\.L|JSR\.W)\s+([A-Za-z_][A-Za-z0-9_]*)\s+;([0-9A-Fa-f]{6});"
)
CODE_ADDRESS = re.compile(r";([0-9A-Fa-f]{6});")

AREA_POINTER_TABLE = "AreaSpecific_AnimatedTilesObjectList_Pointers"
AREA_NAMES = (
    "Crateria",
    "Brinstar",
    "Norfair",
    "WreckedShip",
    "Maridia",
    "Tourian",
    "Ceres",
    "Debug",
)

# Instruction words used by the reachable vanilla lists. Argument sizes are
# bytes after the opcode. targetOffset is relative to the first argument.
INSTRUCTION_SPECS = {
    "Instruction_AnimatedTilesObject_Delete": ("terminal", 0, None),
    "Instruction_AnimatedTilesObject_GotoY": ("goto", 2, 0),
    "Instruction_AnimatedTilesObject_WaitUntilAreaBossIsDead": ("next", 0, None),
    "Instruction_AnimatedTilesObject_TourianStatueSetAnimStateY": ("next", 2, None),
    "Instruction_AnimatedTilesObject_GotoYIfEventYSet": ("conditional", 4, 2),
    "Instruction_AnimTilesObject_GotoYIfAnyBossBitsYSetForAreaY": (
        "conditional",
        4,
        2,
    ),
    "Instruction_AnimatedTilesObject_TourianStatueResetAnimStateY": ("next", 2, None),
    "Instruction_AnimatedTilesObject_GotoYIfTourianStatueBusy": ("conditional", 2, 0),
    "Instruction_AnimatedTilesObject_Clear3ColorsOfPaletteData": ("next", 2, None),
    "Instruction_AnimTilesObject_SpawnTourianStatueEyeGlowParamY": ("next", 2, None),
    "Instruction_AnimTilesObject_SpawnTourianStatuesSoulParamY": ("next", 2, None),
    "Instruction_AnimatedTilesObject_SpawnPaletteFXObjectInY": ("next", 2, None),
    "Instruction_AnimatedTilesObject_SetEventY": ("next", 2, None),
    "Instruction_AnimatedTilesObject_Write8ColorsOfTargetPaletteD": ("next", 2, None),
}

CONSUMER_TARGETS = {
    "Spawn_AnimatedTilesObject": "spawn",
    "AnimatedTilesObject_Handler": "handler",
    "ProcessAnimatedTilesObjectVRAMTransfers": "dma",
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


def read_u24(rom: bytes, address: int) -> int:
    pc = snes_to_pc(address)
    return rom[pc] | (rom[pc + 1] << 8) | (rom[pc + 2] << 16)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]], fields: Sequence[str]) -> str:
    digest = hashlib.sha256()
    for record in records:
        for field in fields:
            digest.update(str(record[field]).encode("utf-8"))
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


def one_by_address(
    records: Iterable[Dict[str, object]], address: int, description: str
) -> Dict[str, object]:
    matches = [record for record in records if int(record["snesAddress"]) == address]
    if len(matches) != 1:
        fail(f"expected one {description} at {formatted(address)}, found {len(matches)}")
    return matches[0]


def source_code_lines(lines: Sequence[str], start: int) -> List[str]:
    result: List[str] = []
    for raw_line in lines[start:]:
        code = raw_line.split(";", 1)[0].strip()
        if not code or code.startswith("if ") or code.startswith("endif "):
            continue
        if code.endswith(":"):
            break
        result.append(code)
        if len(result) == 2:
            break
    return result


def parse_objects(
    lines: Sequence[str], catalog: SymbolCatalog, rom: bytes
) -> List[Dict[str, object]]:
    objects: List[Dict[str, object]] = []
    for index, line in enumerate(lines):
        label_match = OBJECT_LABEL.match(line)
        if label_match is None:
            continue
        label = label_match.group(1)
        code = source_code_lines(lines, index + 1)
        if len(code) != 2:
            continue
        pointer_match = OBJECT_POINTER.match(code[0])
        fields_match = OBJECT_FIELDS.match(code[1])
        if pointer_match is None or fields_match is None:
            continue
        instruction_label = pointer_match.group(1)
        source_size = int(fields_match.group(1), 16)
        source_vram = int(fields_match.group(2), 16)
        address = require_unique_symbol(catalog, label)
        instruction_address = require_unique_symbol(catalog, instruction_label)
        if address >> 16 != 0x87 or instruction_address >> 16 != 0x87:
            fail(f"animated object {label} or its list is outside bank $87")
        if read_u16(rom, address) != (instruction_address & 0xFFFF):
            fail(f"{label} instruction-list pointer disagrees with assembled ROM")
        if read_u16(rom, address + 2) != source_size:
            fail(f"{label} transfer size disagrees with assembled ROM")
        if read_u16(rom, address + 4) != source_vram:
            fail(f"{label} VRAM destination disagrees with assembled ROM")
        objects.append(
            {
                "label": label,
                "address": formatted(address),
                "snesAddress": address,
                "instructionListLabel": instruction_label,
                "instructionListAddress": formatted(instruction_address),
                "instructionListSnesAddress": instruction_address,
                "transferSize": source_size,
                "vramWordAddress": source_vram,
                "vramByteAddress": source_vram * 2,
                "sourceDeclaredUnused": label.startswith("UNUSED_"),
            }
        )
    objects.sort(key=lambda record: int(record["snesAddress"]))
    if not objects:
        fail("no animated-tile object definitions found")
    return objects


def trace_object_frames(
    obj: Dict[str, object],
    rom: bytes,
    opcodes: Dict[int, Tuple[str, str, int, int | None]],
    assets_by_low_word: Dict[int, Dict[str, object]],
) -> Tuple[Set[int], Set[int]]:
    pending = [int(obj["instructionListSnesAddress"]) & 0xFFFF]
    visited: Set[int] = set()
    frames: Set[int] = set()
    while pending:
        pointer = pending.pop()
        if pointer in visited:
            continue
        if pointer < 0x8000:
            fail(f"{obj['label']} reached non-ROM instruction pointer ${pointer:04X}")
        visited.add(pointer)
        word = read_u16(rom, 0x870000 | pointer)
        if word < 0x8000:
            source = read_u16(rom, 0x870000 | ((pointer + 2) & 0xFFFF))
            if source not in assets_by_low_word:
                fail(
                    f"{obj['label']} frame ${pointer:04X} points to unknown bank-$87 source "
                    f"${source:04X}"
                )
            frames.add(pointer)
            pending.append((pointer + 4) & 0xFFFF)
            continue
        if word not in opcodes:
            fail(f"{obj['label']} uses unclassified opcode ${word:04X} at $87:{pointer:04X}")
        _name, behavior, argument_bytes, target_offset = opcodes[word]
        next_pointer = (pointer + 2 + argument_bytes) & 0xFFFF
        if behavior == "terminal":
            continue
        if behavior == "goto":
            pending.append(read_u16(rom, 0x870000 | (pointer + 2 + int(target_offset))))
            continue
        if behavior == "conditional":
            pending.append(next_pointer)
            pending.append(read_u16(rom, 0x870000 | (pointer + 2 + int(target_offset))))
            continue
        pending.append(next_pointer)
    return frames, visited


def build_frame_records(
    objects: List[Dict[str, object]],
    lines: Sequence[str],
    catalog: SymbolCatalog,
    rom: bytes,
    assets: List[Dict[str, object]],
) -> List[Dict[str, object]]:
    assets_by_low_word: Dict[int, Dict[str, object]] = {}
    for asset in assets:
        address = int(asset["snesAddress"])
        if address >> 16 != 0x87:
            fail(f"animated-tile asset {asset['name']} is outside bank $87")
        low = address & 0xFFFF
        if low in assets_by_low_word:
            fail(f"animated-tile assets alias bank-$87 address ${low:04X}")
        assets_by_low_word[low] = asset

    opcodes: Dict[int, Tuple[str, str, int, int | None]] = {}
    for name, (behavior, argument_bytes, target_offset) in INSTRUCTION_SPECS.items():
        address = require_unique_symbol(catalog, name)
        if address >> 16 != 0x87:
            fail(f"animated-tile opcode {name} is outside bank $87")
        opcodes[address & 0xFFFF] = (name, behavior, argument_bytes, target_offset)

    frames_by_address: Dict[int, Dict[str, object]] = {}
    object_frame_associations = 0
    for obj in objects:
        frame_addresses, visited = trace_object_frames(obj, rom, opcodes, assets_by_low_word)
        object_frame_associations += len(frame_addresses)
        obj["reachableInstructionCount"] = len(visited)
        obj["frameCount"] = len(frame_addresses)
        obj["frameAddresses"] = [formatted(0x870000 | address) for address in sorted(frame_addresses)]
        for frame_address in frame_addresses:
            duration = read_u16(rom, 0x870000 | frame_address)
            source_low = read_u16(rom, 0x870000 | (frame_address + 2))
            asset = assets_by_low_word[source_low]
            if int(obj["transferSize"]) != int(asset["size"]):
                fail(
                    f"{obj['label']} transfers {obj['transferSize']} bytes but frame "
                    f"{formatted(0x870000 | frame_address)} source {asset['name']} has {asset['size']}"
                )
            record = frames_by_address.setdefault(
                frame_address,
                {
                    "address": formatted(0x870000 | frame_address),
                    "snesAddress": 0x870000 | frame_address,
                    "duration": duration,
                    "sourceLabel": asset["name"],
                    "sourceAddress": asset["address"],
                    "sourceSnesAddress": asset["snesAddress"],
                    "transferSize": asset["size"],
                    "objectLabels": [],
                },
            )
            if record["duration"] != duration or record["sourceLabel"] != asset["name"]:
                fail(f"conflicting interpretation of frame {record['address']}")
            record["objectLabels"].append(obj["label"])

    frames = [frames_by_address[address] for address in sorted(frames_by_address)]
    for frame in frames:
        frame["objectLabels"].sort()

    source_pairs: Counter[Tuple[int, str]] = Counter()
    for line in lines:
        match = SOURCE_FRAME.match(line)
        if match is not None:
            source_pairs[(int(match.group(1), 16), match.group(2))] += 1
    traced_pairs = Counter((int(frame["duration"]), str(frame["sourceLabel"])) for frame in frames)
    if traced_pairs != source_pairs:
        fail("reachable ROM frames do not exactly match source frame declarations")

    # Stored on the list for the caller without introducing a second traversal.
    for obj in objects:
        obj["objectFrameAssociationCount"] = int(obj["frameCount"])
    if sum(int(obj["frameCount"]) for obj in objects) != object_frame_associations:
        fail("animated object/frame association accounting failed")
    return frames


def build_asset_records(
    disassembly: Path,
    assets: Sequence[Dict[str, object]],
    frames: Sequence[Dict[str, object]],
) -> List[Dict[str, object]]:
    references: Dict[str, List[str]] = {}
    for frame in frames:
        references.setdefault(str(frame["sourceLabel"]), []).append(str(frame["address"]))
    records: List[Dict[str, object]] = []
    for asset in sorted(assets, key=lambda item: int(item["snesAddress"])):
        data = (disassembly / str(asset["asset"])).read_bytes()
        if len(data) != int(asset["size"]) or sha256(data) != asset["sha256"]:
            fail(f"animated-tile asset manifest drift for {asset['name']}")
        frame_addresses = sorted(references.get(str(asset["name"]), []))
        records.append(
            {
                "sourceLabel": asset["name"],
                "asset": asset["asset"],
                "address": asset["address"],
                "snesAddress": asset["snesAddress"],
                "size": asset["size"],
                "sha256": asset["sha256"],
                "sourceDeclaredUnused": str(asset["name"]).startswith("UNUSED_"),
                "referenced": bool(frame_addresses),
                "frameAddresses": frame_addresses,
            }
        )
    return records


def build_area_activation(
    catalog: SymbolCatalog, rom: bytes, objects: Sequence[Dict[str, object]]
) -> Dict[str, object]:
    root = require_unique_symbol(catalog, AREA_POINTER_TABLE)
    objects_by_low = {int(obj["snesAddress"]) & 0xFFFF: obj for obj in objects}
    lists: List[Dict[str, object]] = []
    entries: List[Dict[str, object]] = []
    for area_index, area_name in enumerate(AREA_NAMES):
        pointer_field = root + area_index * 2
        list_low = read_u16(rom, pointer_field)
        list_address = 0x830000 | list_low
        expected_label = f"{area_name}_AnimatedTilesObjectList"
        if require_unique_symbol(catalog, expected_label) != list_address:
            fail(f"area {area_name} animated-tile list pointer disagrees with source label")
        lists.append(
            {
                "areaIndex": area_index,
                "area": area_name,
                "pointerFieldAddress": formatted(pointer_field),
                "pointerFieldSnesAddress": pointer_field,
                "listLabel": expected_label,
                "listAddress": formatted(list_address),
                "listSnesAddress": list_address,
            }
        )
        for bit in range(8):
            field_address = list_address + bit * 2
            object_low = read_u16(rom, field_address)
            if object_low not in objects_by_low:
                fail(f"area {area_name} bit {bit} points to unknown object $87:{object_low:04X}")
            obj = objects_by_low[object_low]
            entries.append(
                {
                    "areaIndex": area_index,
                    "area": area_name,
                    "bit": bit,
                    "mask": 1 << bit,
                    "pointerFieldAddress": formatted(field_address),
                    "pointerFieldSnesAddress": field_address,
                    "objectLabel": obj["label"],
                    "objectAddress": obj["address"],
                    "objectSnesAddress": obj["snesAddress"],
                }
            )
    return {
        "pointerTableLabel": AREA_POINTER_TABLE,
        "pointerTableAddress": formatted(root),
        "pointerTableSnesAddress": root,
        "lists": lists,
        "entries": entries,
    }


def build_consumers(
    disassembly: Path, catalog: SymbolCatalog, rom: bytes
) -> List[Dict[str, object]]:
    consumers: List[Dict[str, object]] = []
    for source_path in sorted((disassembly / "src").rglob("*.asm")):
        for line_number, line in enumerate(
            source_path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            match = CALL_SITE.match(line)
            if match is None:
                continue
            instruction, target_label, annotated = match.groups()
            if target_label not in CONSUMER_TARGETS:
                continue
            address = int(annotated, 16)
            target = require_unique_symbol(catalog, target_label)
            pc = snes_to_pc(address)
            if instruction == "JSL.L":
                if rom[pc] != 0x22 or read_u24(rom, address + 1) != target:
                    fail(f"JSL consumer mismatch at {formatted(address)}")
            else:
                if rom[pc] != 0x20 or read_u16(rom, address + 1) != (target & 0xFFFF):
                    fail(f"JSR consumer mismatch at {formatted(address)}")
                if address >> 16 != target >> 16:
                    fail(f"cross-bank JSR consumer at {formatted(address)}")
            consumers.append(
                {
                    "kind": CONSUMER_TARGETS[target_label],
                    "instruction": instruction,
                    "callSiteAddress": formatted(address),
                    "callSiteSnesAddress": address,
                    "targetLabel": target_label,
                    "targetAddress": formatted(target),
                    "targetSnesAddress": target,
                    "sourceFile": source_path.relative_to(disassembly).as_posix(),
                    "sourceLine": line_number,
                }
            )
    consumers.sort(key=lambda record: int(record["callSiteSnesAddress"]))
    return consumers


def require_instruction_address(lines: Sequence[str], text: str) -> int:
    matches: List[int] = []
    for line in lines:
        if line.split(";", 1)[0].strip() != text:
            continue
        address_match = CODE_ADDRESS.search(line)
        if address_match is not None:
            matches.append(int(address_match.group(1), 16))
    if len(matches) != 1:
        fail(f"expected one source instruction {text!r}, found {len(matches)}")
    return matches[0]


def source_block(lines: Sequence[str], label: str) -> List[str]:
    start = next((index for index, line in enumerate(lines) if line == f"{label}:"), None)
    if start is None:
        fail(f"source block {label} was not found")
    result: List[str] = []
    for line in lines[start + 1:]:
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*:$", line):
            break
        result.append(line)
    return result


def build_transfer_contract(
    disassembly: Path, catalog: SymbolCatalog, rom: bytes
) -> Dict[str, object]:
    bank80_lines = (disassembly / "src" / "bank_80.asm").read_text(encoding="utf-8").splitlines()
    transfer_lines = source_block(bank80_lines, "ProcessAnimatedTilesObjectVRAMTransfers")
    source_bank_instruction = require_instruction_address(transfer_lines, "LDY.B #$87")
    dma_control_instruction = require_instruction_address(transfer_lines, "LDA.W #$1801")
    size_instruction = require_instruction_address(
        transfer_lines, "LDA.W AnimatedTilesObject_Sizes,X"
    )
    destination_instruction = require_instruction_address(
        transfer_lines, "LDA.W AnimatedTilesObject_VRAMAddr,X"
    )
    checks = (
        (source_bank_instruction, bytes((0xA0, 0x87)), "source-bank immediate"),
        (dma_control_instruction, bytes((0xA9, 0x01, 0x18)), "DMA control immediate"),
        (
            size_instruction,
            bytes((0xBD, require_unique_symbol(catalog, "AnimatedTilesObject_Sizes") & 0xFF,
                   require_unique_symbol(catalog, "AnimatedTilesObject_Sizes") >> 8)),
            "transfer-size load",
        ),
        (
            destination_instruction,
            bytes((0xBD, require_unique_symbol(catalog, "AnimatedTilesObject_VRAMAddr") & 0xFF,
                   require_unique_symbol(catalog, "AnimatedTilesObject_VRAMAddr") >> 8)),
            "VRAM-destination load",
        ),
    )
    for address, expected, label in checks:
        pc = snes_to_pc(address)
        if rom[pc:pc + len(expected)] != expected:
            fail(f"animated-tile {label} changed at {formatted(address)}")
    return {
        "spawnRoutineAddress": formatted(require_unique_symbol(catalog, "Spawn_AnimatedTilesObject")),
        "spawnRoutineSnesAddress": require_unique_symbol(catalog, "Spawn_AnimatedTilesObject"),
        "dmaRoutineAddress": formatted(
            require_unique_symbol(catalog, "ProcessAnimatedTilesObjectVRAMTransfers")
        ),
        "dmaRoutineSnesAddress": require_unique_symbol(
            catalog, "ProcessAnimatedTilesObjectVRAMTransfers"
        ),
        "sourceBank": 0x87,
        "dmaControl": 0x1801,
        "objectHeader": {
            "instructionListOffset": 0,
            "transferSizeOffset": 2,
            "vramWordAddressOffset": 4,
            "size": 6,
        },
        "sourceBankInstructionAddress": formatted(source_bank_instruction),
        "sourceBankInstructionSnesAddress": source_bank_instruction,
        "dmaControlInstructionAddress": formatted(dma_control_instruction),
        "dmaControlInstructionSnesAddress": dma_control_instruction,
        "sizeInstructionAddress": formatted(size_instruction),
        "sizeInstructionSnesAddress": size_instruction,
        "destinationInstructionAddress": formatted(destination_instruction),
        "destinationInstructionSnesAddress": destination_instruction,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate source-backed animated-tile objects, frames, and DMA destinations."
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
    bank87_lines = (disassembly / "src" / "bank_87.asm").read_text(encoding="utf-8").splitlines()
    animated_assets = [
        record for record in assets_manifest["assets"] if record["category"] == "animated-tiles"
    ]

    objects = parse_objects(bank87_lines, catalog, rom)
    frames = build_frame_records(objects, bank87_lines, catalog, rom, animated_assets)
    assets = build_asset_records(disassembly, animated_assets, frames)
    area_activation = build_area_activation(catalog, rom, objects)
    consumers = build_consumers(disassembly, catalog, rom)
    transfer_contract = build_transfer_contract(disassembly, catalog, rom)

    consumer_counts = Counter(str(record["kind"]) for record in consumers)
    totals = {
        "assetCount": len(assets),
        "assetByteCount": sum(int(record["size"]) for record in assets),
        "referencedAssetCount": sum(bool(record["referenced"]) for record in assets),
        "orphanAssetCount": sum(not bool(record["referenced"]) for record in assets),
        "sourceDeclaredUnusedAssetCount": sum(
            bool(record["sourceDeclaredUnused"]) for record in assets
        ),
        "objectCount": len(objects),
        "nonEmptyObjectCount": sum(int(record["transferSize"]) > 0 for record in objects),
        "uniqueFrameInstructionCount": len(frames),
        "objectFrameAssociationCount": sum(int(record["frameCount"]) for record in objects),
        "areaListCount": len(area_activation["lists"]),
        "areaBitMappingCount": len(area_activation["entries"]),
        "consumerCount": len(consumers),
        "spawnConsumerCount": consumer_counts["spawn"],
        "handlerConsumerCount": consumer_counts["handler"],
        "dmaConsumerCount": consumer_counts["dma"],
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": revision,
        "totals": totals,
        "aggregateHashes": {
            "assets": aggregate_hash(assets, ("sourceLabel", "size", "sha256")),
            "objects": aggregate_hash(
                objects,
                ("label", "instructionListAddress", "transferSize", "vramWordAddress"),
            ),
            "frames": aggregate_hash(
                frames, ("address", "duration", "sourceLabel", "transferSize")
            ),
            "areaActivation": aggregate_hash(
                area_activation["entries"], ("areaIndex", "bit", "objectLabel")
            ),
        },
        "transferContract": transfer_contract,
        "assets": assets,
        "objects": objects,
        "frames": frames,
        "areaActivation": area_activation,
        "consumers": consumers,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Animated-tile manifest valid")
    print(
        f"  Assets: {totals['assetCount']} ({totals['referencedAssetCount']} referenced, "
        f"{totals['orphanAssetCount']} explicit orphans), {totals['assetByteCount']} bytes"
    )
    print(
        f"  Objects: {totals['objectCount']}, frames: {totals['uniqueFrameInstructionCount']} unique / "
        f"{totals['objectFrameAssociationCount']} object associations"
    )
    print(
        f"  FX activation: {totals['areaListCount']} area lists / "
        f"{totals['areaBitMappingCount']} bit mappings"
    )
    print(f"  Engine consumers: {totals['consumerCount']}")
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
