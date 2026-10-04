#!/usr/bin/env python3
"""Measure named enemy instruction-list records and SMEDIT preview coverage."""

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

from symbol_catalog import Symbol, SymbolCatalog


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_OAM_REPORT = PARITY_DIR / "reports" / "enemy-oam.json"
DEFAULT_REPORT = PARITY_DIR / "reports" / "enemy-instructions.json"

ENEMY_BANKS = (0xA0, *range(0xA2, 0xAB), 0xB2, 0xB3)
LIST_LABEL = re.compile(r"^(?:(?:UNUSED|UNSUED)_)?InstList_[A-Za-z0-9_]+$")
TOP_LEVEL_LABEL = re.compile(r"^([A-Za-z_][A-Za-z0-9_.]*):")
DATA_DIRECTIVE = re.compile(r"^(db|dw|dl)\s+(.+)$", re.IGNORECASE)
ADDRESS_COMMENT = re.compile(r";([0-9A-Fa-f]{6});")

# These labels look like lists by name but are native instruction handlers. Their
# source blocks contain CPU instructions rather than data directives.
MISNAMED_HANDLER_LABELS = {
    "InstList_MotherBrainHead_SpawnLaserProjectile",
    "InstList_DachoraEscape_GotoY_IfAcidLessThanCE",
    "InstList_DachoraEscape_GotoY_IfCrittersEscaped",
}

# This is a different interpreter and record format despite sharing the InstList
# naming convention. Keep it visible, but outside enemy-instruction coverage.
NON_ENEMY_LIST_PREFIXES = (
    "InstList_HDMAObject_",
    "InstList_Kraid_",
    "InstList_RoomPalette_",
)

COMMON_HANDLER_SEMANTICS = {
    0x807C: (0, "terminal"),
    0x808A: (2, "linear-call"),
    0x809C: (4, "linear-call"),
    0x80ED: (2, "unconditional-branch"),
    0x80F2: (1, "unconditional-relative-branch"),
    0x8108: (2, "conditional-branch"),
    0x8110: (2, "conditional-branch"),
    0x8118: (1, "conditional-relative-branch"),
    0x8123: (2, "linear"),
    0x812C: (0, "dynamic-skip"),
    0x812F: (0, "terminal-sleep"),
    0x813A: (2, "yield"),
    0x814B: (7, "linear-dma"),
    0x8173: (0, "linear"),
    0x817D: (0, "linear"),
}

MAX_PREVIEW_CHUNKS = 32


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def formatted(address: int) -> str:
    return f"{address >> 16:02X}:{address & 0xFFFF:04X}"


def snes_to_pc(address: int) -> int:
    bank = address >> 16
    offset = address & 0xFFFF
    if bank < 0x80 or offset < 0x8000:
        fail(f"not a ROM LoROM address: {formatted(address)}")
    return ((bank & 0x7F) * 0x8000) + offset - 0x8000


def read_bytes(rom: bytes, address: int, size: int) -> bytes:
    pc = snes_to_pc(address)
    if pc < 0 or pc + size > len(rom):
        fail(f"read outside rebuilt ROM: {formatted(address)} + ${size:X}")
    return rom[pc : pc + size]


def read_u16(rom: bytes, address: int) -> int:
    data = read_bytes(rom, address, 2)
    return data[0] | data[1] << 8


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]], fields: Sequence[str]) -> str:
    digest = hashlib.sha256()
    for record in records:
        for field in fields:
            value = record[field]
            if isinstance(value, (list, dict)):
                value = json.dumps(value, sort_keys=True, separators=(",", ":"))
            digest.update(str(value).encode("utf-8"))
            digest.update(b"\0")
        digest.update(b"\n")
    return digest.hexdigest()


def source_revision(disassembly: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(disassembly), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def split_top_level(value: str, delimiter: str) -> List[str]:
    parts: List[str] = []
    depth = 0
    start = 0
    for index, char in enumerate(value):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth < 0:
                fail(f"unbalanced expression: {value}")
        elif char == delimiter and depth == 0:
            parts.append(value[start:index].strip())
            start = index + 1
    if depth != 0:
        fail(f"unbalanced expression: {value}")
    parts.append(value[start:].strip())
    return [part for part in parts if part]


def directive_size_and_first_token(text: str) -> Tuple[int, str]:
    total = 0
    first_token: Optional[str] = None
    widths = {"db": 1, "dw": 2, "dl": 3}
    for part in split_top_level(text, ":"):
        match = DATA_DIRECTIVE.fullmatch(part)
        if match is None:
            fail(f"unsupported instruction-list data directive: {text}")
        directive, arguments = match.groups()
        tokens = split_top_level(arguments, ",")
        if not tokens:
            fail(f"empty instruction-list data directive: {text}")
        if first_token is None:
            first_token = tokens[0]
        total += widths[directive.lower()] * len(tokens)
    if first_token is None:
        fail(f"no data in instruction-list directive: {text}")
    return total, first_token


def condition_value(line: str) -> Optional[bool]:
    expression = line.strip()[3:].strip()
    if expression == "!PAL == 0":
        return True
    if expression == "!FEATURE_KEEP_UNREFERENCED":
        return True
    return None


def active_block_lines(lines: Sequence[str]) -> Iterable[Tuple[int, str]]:
    active_stack = [True]
    condition_stack: List[bool] = []
    for line_number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith("if "):
            value = condition_value(stripped)
            if value is None:
                fail(f"unsupported conditional inside instruction list: {stripped}")
            condition_stack.append(value)
            active_stack.append(active_stack[-1] and value)
            continue
        if stripped == "else":
            if not condition_stack or len(active_stack) < 2:
                fail("orphaned else inside instruction list")
            condition_stack[-1] = not condition_stack[-1]
            active_stack[-1] = active_stack[-2] and condition_stack[-1]
            continue
        if stripped.startswith("endif"):
            # Some source labels begin inside a file-level feature block; the
            # matching endif can therefore be inside this label's slice even
            # though its opening if precedes the label.
            if not condition_stack or len(active_stack) < 2:
                continue
            condition_stack.pop()
            active_stack.pop()
            continue
        if active_stack[-1]:
            yield line_number, line


def symbol_for_name(catalog: SymbolCatalog, name: str) -> Optional[Symbol]:
    matches = catalog.exact(name)
    if len(matches) > 1:
        fail(f"duplicate symbol name: {name}")
    return matches[0] if matches else None


def preferred_labels(catalog: SymbolCatalog, address: int) -> List[str]:
    return sorted(symbol.name for symbol in catalog.at(address))


def source_token_name(token: str) -> str:
    match = re.match(r"([A-Za-z_][A-Za-z0-9_]*)", token.strip())
    return match.group(1) if match else token.strip()


def is_handler_token(token: str) -> bool:
    name = source_token_name(token)
    return (
        "Instruction_" in name
        or name.startswith("Instruction_")
        or name.startswith("Inst_")
        or name in MISNAMED_HANDLER_LABELS
    )


def is_return_stub_token(token: str) -> bool:
    name = source_token_name(token)
    return name.startswith("RTS_") or name.startswith("RTL_")


def control_flow_kind(labels: Sequence[str], source_token: str, low_pointer: int) -> str:
    common = COMMON_HANDLER_SEMANTICS.get(low_pointer)
    if common is not None:
        return common[1]
    joined = " ".join([source_token, *labels]).lower()
    if "sleep" in joined:
        return "terminal-sleep"
    if "deleteenemy" in joined or "delete_enemy" in joined:
        return "terminal"
    if "goto" in joined or "go_back" in joined or "goback" in joined:
        conditional_words = (
            "if",
            "unless",
            "chance",
            "random",
            "decrement",
            "check",
            "less",
            "greater",
        )
        return "conditional-branch" if any(word in joined for word in conditional_words) else "branch"
    if "wait" in joined and "frame" in joined:
        return "yield"
    if "skip" in joined:
        return "dynamic-skip"
    if "call" in joined or "functioniny" in joined or "function_in_y" in joined:
        return "linear-call"
    return "linear-or-stateful"


def expected_operand_bytes(source_token: str, low_pointer: int) -> Optional[int]:
    common = COMMON_HANDLER_SEMANTICS.get(low_pointer)
    if common is not None:
        return common[0]
    normalized = source_token_name(source_token).lower()
    # Source convention: handlers named "...InY" read one word at [Y] and
    # advance Y by two. This includes arbitrary function pointers, movement
    # functions, alternate instruction lists, and scalar arguments.
    if "iny" in normalized or "_in_y" in normalized:
        return 2
    return None


def make_oam_index(oam: Dict[str, object]) -> Tuple[Dict[int, Dict[str, object]], set[int]]:
    structures: Dict[int, Dict[str, object]] = {}
    visible: set[int] = set()
    for record in oam["standardSpritemaps"]:
        address = int(record["snesAddress"])
        structures[address] = {
            "type": "standard-oam",
            "labels": [record["sourceLabel"]],
        }
        if int(record["entryCount"]) > 0:
            visible.add(address)
    for record in oam["extendedSpritemaps"]:
        address = int(record["snesAddress"])
        structures[address] = {
            "type": "extended-spritemap",
            "labels": [record["sourceLabel"]],
        }
        if any(
            child["childType"] == "standard-oam"
            and int(child["childSnesAddress"]) in visible
            for child in record["children"]
        ):
            visible.add(address)
    return structures, visible


def production_pc(address: int) -> int:
    """Mirror RomParser.snesToPc, including low-half LoROM mirrors."""
    bank = (address >> 16) & 0xFF
    offset = address & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (offset & 0x7FFF)


def production_u16(rom: bytes, address: int) -> Optional[int]:
    pc = production_pc(address)
    if pc < 0 or pc + 2 > len(rom):
        return None
    return rom[pc] | rom[pc + 1] << 8


def production_standard_oam_entry_count(rom: bytes, address: int) -> Optional[int]:
    """Return the count when production parseSpritemap accepts the address."""
    pc = production_pc(address)
    if pc < 0 or pc + 2 > len(rom):
        return None
    count = rom[pc] | rom[pc + 1] << 8
    if count > 64 or pc + 2 + count * 5 > len(rom):
        return None
    return count


def production_extended_tilemap_valid(rom: bytes, address: int) -> bool:
    pc = production_pc(address)
    if pc < 0 or pc + 2 > len(rom) or rom[pc] | rom[pc + 1] << 8 != 0xFFFE:
        return False
    offset = pc + 2
    total_tiles = 0
    has_run = False
    for _ in range(128):
        if offset + 2 > len(rom):
            return False
        destination = rom[offset] | rom[offset + 1] << 8
        offset += 2
        if destination == 0xFFFF:
            return has_run
        if offset + 2 > len(rom):
            return False
        count = rom[offset] | rom[offset + 1] << 8
        offset += 2
        if not 1 <= count <= 1024 or offset + count * 2 > len(rom):
            return False
        offset += count * 2
        total_tiles += count
        if total_tiles > 4096:
            return False
        has_run = True
    return False


def production_extended_oam_visibility(rom: bytes, address: int) -> Optional[bool]:
    """Return flattened OAM visibility, or None when the extended parse rejects."""
    pc = production_pc(address)
    if pc < 0 or pc + 2 > len(rom):
        return None
    count = (rom[pc] | rom[pc + 1] << 8) & 0xFF
    if not 1 <= count <= 64 or pc + 2 + count * 8 > len(rom):
        return None
    bank = address & 0xFF0000
    visible = False
    for index in range(count):
        child_pc = pc + 2 + index * 8
        child_pointer = rom[child_pc + 4] | rom[child_pc + 5] << 8
        if child_pointer < 0x8000:
            return None
        child_address = bank | child_pointer
        first_word = production_u16(rom, child_address)
        if first_word is None:
            return None
        if first_word == 0xFFFE:
            if not production_extended_tilemap_valid(rom, child_address):
                return None
        else:
            child_count = production_standard_oam_entry_count(rom, child_address)
            if child_count is None:
                return None
            visible = visible or child_count > 0
    return visible


def production_renderable_oam(rom: bytes, address: int) -> bool:
    """Mirror parseRenderableSpritemap's extended-first, nonempty-OAM rule."""
    extended_visibility = production_extended_oam_visibility(rom, address)
    if extended_visibility is not None:
        return extended_visibility
    standard_count = production_standard_oam_entry_count(rom, address)
    return standard_count is not None and standard_count > 0


def source_blocks(disassembly: Path, catalog: SymbolCatalog) -> Tuple[List[Dict[str, object]], List[Dict[str, object]]]:
    lists: List[Dict[str, object]] = []
    exclusions: List[Dict[str, object]] = []
    for bank in ENEMY_BANKS:
        path = disassembly / "src" / f"bank_{bank:02X}.asm"
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            match = TOP_LEVEL_LABEL.match(line)
            if match is None:
                continue
            name = match.group(1)
            if not LIST_LABEL.fullmatch(name):
                continue
            symbol = symbol_for_name(catalog, name)
            if symbol is None:
                continue
            end = index + 1
            while end < len(lines) and TOP_LEVEL_LABEL.match(lines[end]) is None:
                end += 1
            block = lines[index + 1 : end]
            next_label = TOP_LEVEL_LABEL.match(lines[end]).group(1) if end < len(lines) else None
            next_symbol = symbol_for_name(catalog, next_label) if next_label is not None else None
            directives = []
            cpu_lines = []
            relative_line_numbers = []
            for relative_line, source_line in active_block_lines(block):
                code = source_line.split(";", 1)[0].strip()
                if DATA_DIRECTIVE.match(code):
                    directives.append(source_line)
                    relative_line_numbers.append(relative_line)
                elif re.match(r"^[A-Z]{2,5}(?:\.[BWL])?\b", code):
                    cpu_lines.append(code)
            record = {
                "sourceLabel": name,
                "sourceDeclaredUnused": name.startswith(("UNUSED_", "UNSUED_")),
                "address": symbol.address,
                "snesAddress": symbol.snes_address,
                "bank": bank,
                "sourceFile": f"src/bank_{bank:02X}.asm",
                "sourceLine": index + 1,
                "blockLines": block,
                "directiveLines": directives,
                "directiveRelativeLines": relative_line_numbers,
                "cpuLines": cpu_lines,
                "nextSourceLabel": next_label,
                "nextSourceSnesAddress": next_symbol.snes_address if next_symbol is not None else None,
            }
            if name in MISNAMED_HANDLER_LABELS:
                if directives or not cpu_lines:
                    fail(f"misnamed handler classification changed for {name}")
                exclusions.append(
                    {
                        "sourceLabel": name,
                        "address": symbol.address,
                        "snesAddress": symbol.snes_address,
                        "reason": "native instruction handler whose source name begins InstList",
                    }
                )
            elif name.startswith(NON_ENEMY_LIST_PREFIXES):
                reason = (
                    "HDMA-object instruction list; different interpreter and record format"
                    if name.startswith("InstList_HDMAObject_")
                    else (
                        "Kraid head instruction list; dedicated eight-byte/ASM interpreter"
                        if name.startswith("InstList_Kraid_")
                        else "Mother Brain room-palette list; dedicated palette interpreter"
                    )
                )
                exclusions.append(
                    {
                        "sourceLabel": name,
                        "address": symbol.address,
                        "snesAddress": symbol.snes_address,
                        "reason": reason,
                    }
                )
            else:
                if not directives or cpu_lines:
                    fail(f"enemy list {name} is not a pure source data block")
                lists.append(record)
    lists.sort(key=lambda item: (int(item["snesAddress"]), str(item["sourceLabel"])))
    exclusions.sort(key=lambda item: (int(item["snesAddress"]), str(item["sourceLabel"])))
    return lists, exclusions


def parse_list(
    source: Dict[str, object],
    catalog: SymbolCatalog,
    rom: bytes,
    oam_structures: Dict[int, Dict[str, object]],
    visible_oam: set[int],
) -> Dict[str, object]:
    position = int(source["snesAddress"])
    bank = int(source["bank"]) << 16
    statements: List[Dict[str, object]] = []
    directive_lines = source["directiveLines"]
    relative_lines = source["directiveRelativeLines"]
    for raw_line, relative_line in zip(directive_lines, relative_lines):
        code = str(raw_line).split(";", 1)[0].strip()
        size, first_token = directive_size_and_first_token(code)
        comment_match = ADDRESS_COMMENT.search(str(raw_line))
        comment_address = int(comment_match.group(1), 16) if comment_match is not None else None
        raw = read_bytes(rom, position, size)
        first_word = raw[0] | raw[1] << 8 if size >= 2 else None
        target = bank | (raw[2] | raw[3] << 8) if size >= 4 else None
        category = "operand"
        if first_word is not None and first_word >= 0x8000 and is_handler_token(first_token):
            category = "handler"
        elif first_word is not None and first_word >= 0x8000 and is_return_stub_token(first_token):
            category = "return-stub"
        elif first_word is not None and first_word < 0x8000 and target in oam_structures:
            category = "frame"
        statements.append(
            {
                "address": formatted(position),
                "snesAddress": position,
                "size": size,
                "sourceLine": int(source["sourceLine"]) + int(relative_line),
                "sourceText": code,
                "sourceCommentAddress": formatted(comment_address) if comment_address is not None else None,
                "sourceCommentAddressMatches": comment_address is None or comment_address == position,
                "firstToken": first_token,
                "firstWord": first_word,
                "category": category,
                "rawSha256": sha256(raw),
            }
        )
        position += size

    # A small Zebetite family spells the normal four-byte frame record as two
    # adjacent ``dw`` lines (duration, then spritemap pointer). Normalize that
    # source formatting before identifying semantic records.
    normalized_statements: List[Dict[str, object]] = []
    index = 0
    while index < len(statements):
        statement = statements[index]
        if (
            statement["category"] == "operand"
            and int(statement["size"]) == 2
            and int(statement["firstWord"]) < 0x8000
            and index + 1 < len(statements)
        ):
            following = statements[index + 1]
            following_word = int(following["firstWord"])
            target = bank | following_word
            if (
                following["category"] == "operand"
                and int(following["size"]) == 2
                and target in oam_structures
            ):
                address = int(statement["snesAddress"])
                statement = {
                    **statement,
                    "size": 4,
                    "sourceText": f"{statement['sourceText']} / {following['sourceText']}",
                    "category": "frame",
                    "rawSha256": sha256(read_bytes(rom, address, 4)),
                }
                index += 1
        normalized_statements.append(statement)
        index += 1
    statements = normalized_statements

    records: List[Dict[str, object]] = []
    current: Optional[Dict[str, object]] = None
    orphan_statements: List[Dict[str, object]] = []
    for statement in statements:
        force_operand = False
        if current is not None and current["kind"] == "handler":
            handler_pointer = read_u16(rom, int(current["snesAddress"]))
            expected_width = expected_operand_bytes(
                str(current["statements"][0]["firstToken"]), handler_pointer
            )
            force_operand = (
                expected_width is not None and int(current["size"]) - 2 < expected_width
            )
        starts_record = statement["category"] in ("frame", "handler")
        if statement["category"] == "return-stub":
            starts_record = not force_operand
            if starts_record:
                statement["category"] = "handler"
        elif force_operand:
            starts_record = False
        if starts_record:
            if current is not None:
                records.append(current)
            current = {
                "kind": statement["category"],
                "address": statement["address"],
                "snesAddress": statement["snesAddress"],
                "size": statement["size"],
                "statements": [statement],
            }
        elif current is None:
            orphan_statements.append(statement)
        else:
            current["size"] = int(current["size"]) + int(statement["size"])
            current["statements"].append(statement)
    if current is not None:
        records.append(current)
    if orphan_statements:
        fail(
            f"{source['sourceLabel']} begins with {len(orphan_statements)} unclassified data statements"
        )

    frames: List[Dict[str, object]] = []
    handlers: List[Dict[str, object]] = []
    for record in records:
        address = int(record["snesAddress"])
        if record["kind"] == "frame":
            if int(record["size"]) != 4:
                fail(f"frame record at {formatted(address)} is not four bytes")
            duration = read_u16(rom, address)
            pointer = read_u16(rom, address + 2)
            target = bank | pointer
            structure = oam_structures[target]
            frame = {
                "address": formatted(address),
                "snesAddress": address,
                "duration": duration,
                "spritemapPointer": pointer,
                "spritemapAddress": formatted(target),
                "spritemapSnesAddress": target,
                "spritemapType": structure["type"],
                "spritemapLabels": structure["labels"],
                "productionRenderable": target in visible_oam,
            }
            record.update(frame)
            frames.append(frame)
        else:
            pointer = read_u16(rom, address)
            target = bank | pointer
            labels = preferred_labels(catalog, target)
            operand_bytes = int(record["size"]) - 2
            common = COMMON_HANDLER_SEMANTICS.get(pointer)
            common_width_matches = common is not None and common[0] == operand_bytes
            handler = {
                "address": formatted(address),
                "snesAddress": address,
                "pointer": pointer,
                "handlerAddress": formatted(target),
                "handlerSnesAddress": target,
                "sourceToken": record["statements"][0]["firstToken"],
                "handlerLabels": labels,
                "operandByteCount": operand_bytes,
                "controlFlow": control_flow_kind(labels, str(record["statements"][0]["firstToken"]), pointer),
                "productionSemantics": (
                    "width-known-not-interpreted" if common_width_matches else "unsupported"
                ),
            }
            if common is not None and not common_width_matches:
                fail(
                    f"common handler ${pointer:04X} width changed at {formatted(address)}: "
                    f"source={operand_bytes}, expected={common[0]}"
                )
            record.update(handler)
            handlers.append(handler)

    size = position - int(source["snesAddress"])
    expected_end = source["nextSourceSnesAddress"]
    if (
        expected_end is not None
        and int(expected_end) >> 16 == int(source["bank"])
        and int(expected_end) >= int(source["snesAddress"])
        and position != int(expected_end)
    ):
        fail(
            f"source block size mismatch for {source['sourceLabel']}: parsed end "
            f"{formatted(position)}, next label {source['nextSourceLabel']} is "
            f"{formatted(int(expected_end))}"
        )
    raw = read_bytes(rom, int(source["snesAddress"]), size)
    expected_frame_addresses = {int(frame["snesAddress"]) for frame in frames}
    seen_pointers: set[int] = set()
    preview_frames: List[Dict[str, object]] = []
    scan_address = int(source["snesAddress"])
    for chunk in range(MAX_PREVIEW_CHUNKS):
        word0 = read_u16(rom, scan_address)
        word1 = read_u16(rom, scan_address + 2)
        if word0 == 0 and word1 == 0:
            break
        if word0 == 0x8000:
            break
        if word0 < 0x8000 and word1 not in seen_pointers:
            target = bank | word1
            if production_renderable_oam(rom, target):
                seen_pointers.add(word1)
                preview_frames.append(
                    {
                        "chunk": chunk,
                        "address": formatted(scan_address),
                        "snesAddress": scan_address,
                        "duration": word0,
                        "spritemapAddress": formatted(target),
                        "spritemapSnesAddress": target,
                        "isSourceFrameInThisBlock": scan_address in expected_frame_addresses,
                    }
                )
        scan_address += 4

    recovered = {
        int(frame["snesAddress"])
        for frame in preview_frames
        if bool(frame["isSourceFrameInThisBlock"])
    }
    missed_frames = [
        frame
        for frame in frames
        if bool(frame["productionRenderable"]) and int(frame["snesAddress"]) not in recovered
    ]
    unrenderable_frames = [frame for frame in frames if not bool(frame["productionRenderable"])]
    unsupported_handlers = [
        handler for handler in handlers if handler["productionSemantics"] == "unsupported"
    ]
    result = {
        key: source[key]
        for key in (
            "sourceLabel",
            "sourceDeclaredUnused",
            "address",
            "snesAddress",
            "bank",
            "sourceFile",
            "sourceLine",
        )
    }
    result.update(
        {
            "size": size,
            "rawSha256": sha256(raw),
            "recordCount": len(records),
            "frameCount": len(frames),
            "handlerCount": len(handlers),
            "records": records,
            "productionPreview": {
                "algorithm": "fixed four-byte chunks, at most 32 chunks, duplicate pointers removed",
                "detectedFrames": preview_frames,
                "sourceRenderableFrameCount": len(frames) - len(unrenderable_frames),
                "recoveredSourceFrameCount": len(recovered),
                "missedSourceFrames": missed_frames,
                "unrenderableSourceFrames": unrenderable_frames,
                "outOfBlockFrameCount": sum(
                    not bool(frame["isSourceFrameInThisBlock"]) for frame in preview_frames
                ),
            },
            "unsupportedHandlerOccurrences": unsupported_handlers,
        }
    )
    return result


def handler_inventory(lists: Sequence[Dict[str, object]]) -> List[Dict[str, object]]:
    grouped: Dict[int, List[Dict[str, object]]] = defaultdict(list)
    for record in lists:
        for handler in record["records"]:
            if handler["kind"] == "handler":
                grouped[int(handler["handlerSnesAddress"])].append(handler)
    inventory = []
    for address, occurrences in sorted(grouped.items()):
        widths = sorted({int(item["operandByteCount"]) for item in occurrences})
        control_flows = sorted({str(item["controlFlow"]) for item in occurrences})
        support = sorted({str(item["productionSemantics"]) for item in occurrences})
        inventory.append(
            {
                "address": formatted(address),
                "snesAddress": address,
                "labels": sorted({label for item in occurrences for label in item["handlerLabels"]}),
                "sourceTokens": sorted({str(item["sourceToken"]) for item in occurrences}),
                "operandByteCounts": widths,
                "controlFlow": control_flows,
                "productionSemantics": support,
                "occurrenceCount": len(occurrences),
            }
        )
    return inventory


def count_by(records: Iterable[Dict[str, object]], field: str) -> Dict[str, int]:
    counts = Counter(str(record[field]) for record in records)
    return {key: counts[key] for key in sorted(counts)}


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate source-backed enemy instruction-list coverage and misses."
    )
    parser.add_argument("--disassembly", type=Path, help="sm_disassembly checkout")
    parser.add_argument("--oam-report", type=Path, default=DEFAULT_OAM_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    symbols_path = disassembly / "symbols.sym"
    rom_path = disassembly / "SM.sfc"
    oam_path = args.oam_report.expanduser().resolve()
    if not symbols_path.is_file() or not rom_path.is_file() or not oam_path.is_file():
        fail("missing source build or enemy OAM report; run ./gradlew parityEnemyOam")

    catalog = SymbolCatalog.read(symbols_path)
    rom = rom_path.read_bytes()
    oam = json.loads(oam_path.read_text(encoding="utf-8"))
    if oam["disassemblyCommit"] != source_revision(disassembly):
        fail("enemy OAM report belongs to a different disassembly revision")
    oam_structures, visible_oam = make_oam_index(oam)
    sources, exclusions = source_blocks(disassembly, catalog)
    lists = [
        parse_list(source, catalog, rom, oam_structures, visible_oam)
        for source in sources
    ]
    handlers = handler_inventory(lists)
    all_frames = [frame for record in lists for frame in record["records"] if frame["kind"] == "frame"]
    all_handler_occurrences = [
        handler for record in lists for handler in record["records"] if handler["kind"] == "handler"
    ]
    unsupported_handlers = [
        handler
        for handler in handlers
        if handler["productionSemantics"] == ["unsupported"]
    ]
    inconsistent_handlers = [
        handler for handler in handlers if len(handler["operandByteCounts"]) != 1
    ]
    missed_frames = [
        frame
        for record in lists
        for frame in record["productionPreview"]["missedSourceFrames"]
    ]
    unrenderable_frames = [
        frame
        for record in lists
        for frame in record["productionPreview"]["unrenderableSourceFrames"]
    ]
    source_comment_mismatches = [
        statement
        for record in lists
        for parsed_record in record["records"]
        for statement in parsed_record["statements"]
        if not bool(statement["sourceCommentAddressMatches"])
    ]
    totals = {
        "enemyListCount": len(lists),
        "sourceDeclaredUnusedListCount": sum(bool(item["sourceDeclaredUnused"]) for item in lists),
        "excludedNonEnemyListCount": len(exclusions),
        "recordCount": len(all_frames) + len(all_handler_occurrences),
        "frameRecordCount": len(all_frames),
        "uniqueFrameSpritemapCount": len({int(frame["spritemapSnesAddress"]) for frame in all_frames}),
        "handlerOccurrenceCount": len(all_handler_occurrences),
        "uniqueHandlerCount": len(handlers),
        "commonWidthKnownHandlerCount": len(handlers) - len(unsupported_handlers),
        "unsupportedHandlerCount": len(unsupported_handlers),
        "inconsistentHandlerWidthCount": len(inconsistent_handlers),
        "productionRenderableFrameCount": sum(bool(frame["productionRenderable"]) for frame in all_frames),
        "productionUnrenderableFrameCount": len(unrenderable_frames),
        "productionRecoveredSourceFrameCount": sum(
            int(record["productionPreview"]["recoveredSourceFrameCount"]) for record in lists
        ),
        "productionMissedSourceFrameCount": len(missed_frames),
        "productionOutOfBlockFrameCount": sum(
            int(record["productionPreview"]["outOfBlockFrameCount"]) for record in lists
        ),
        "listsWithMissedSourceFrames": sum(
            bool(record["productionPreview"]["missedSourceFrames"]) for record in lists
        ),
        "listsWithUnsupportedHandlers": sum(bool(record["unsupportedHandlerOccurrences"]) for record in lists),
        "frameOnlyListCount": sum(int(record["handlerCount"]) == 0 for record in lists),
        "handlerOnlyListCount": sum(int(record["frameCount"]) == 0 for record in lists),
        "sourceCommentAddressMismatchCount": len(source_comment_mismatches),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": source_revision(disassembly),
        "oracle": "named source directives and byte-identical rebuilt ROM",
        "banks": [f"{bank:02X}" for bank in ENEMY_BANKS],
        "productionPreviewModel": {
            "implementation": "EnemySpritemap legacy fixed-chunk fallback",
            "chunkBytes": 4,
            "maxChunks": MAX_PREVIEW_CHUNKS,
            "handlerBehavior": "all words >= $8000 are skipped as if every handler plus operands occupied four bytes",
            "deduplicatesSpritemapPointers": True,
            "executesControlFlow": False,
        },
        "totals": totals,
        "handlerCountsByControlFlow": count_by(all_handler_occurrences, "controlFlow"),
        "handlerCountsByOperandBytes": count_by(all_handler_occurrences, "operandByteCount"),
        "aggregateHashes": {
            "lists": aggregate_hash(
                lists,
                (
                    "sourceLabel",
                    "snesAddress",
                    "size",
                    "rawSha256",
                    "recordCount",
                    "frameCount",
                    "handlerCount",
                    "records",
                ),
            ),
            "handlers": aggregate_hash(
                handlers,
                (
                    "snesAddress",
                    "labels",
                    "sourceTokens",
                    "operandByteCounts",
                    "controlFlow",
                    "productionSemantics",
                    "occurrenceCount",
                ),
            ),
            "misses": aggregate_hash(
                missed_frames,
                ("snesAddress", "duration", "spritemapSnesAddress"),
            ),
        },
        "excludedLabels": exclusions,
        "lists": lists,
        "handlers": handlers,
        "unsupportedHandlers": unsupported_handlers,
        "inconsistentWidthHandlers": inconsistent_handlers,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Enemy instruction-list coverage manifest valid")
    print(
        f"  Lists: {totals['enemyListCount']} / records: {totals['recordCount']} "
        f"({totals['frameRecordCount']} frames, {totals['handlerOccurrenceCount']} handlers)"
    )
    print(
        f"  Handlers: {totals['uniqueHandlerCount']} unique / "
        f"{totals['commonWidthKnownHandlerCount']} common-family widths / "
        f"{totals['unsupportedHandlerCount']} source-inferred only; none executed"
    )
    print(
        f"  Preview frames: {totals['productionRecoveredSourceFrameCount']} recovered / "
        f"{totals['productionMissedSourceFrameCount']} missed / "
        f"{totals['productionUnrenderableFrameCount']} structurally unrenderable"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
