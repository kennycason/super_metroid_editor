#!/usr/bin/env python3
"""Build the exact source/ROM ownership and composition manifest for Kraid."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Dict, List, Sequence

from lz5_oracle import decode_lz5


PARITY_DIR = Path(__file__).resolve().parent
DEFAULT_DISASSEMBLY = PARITY_DIR / "work" / "sm_disassembly"
DEFAULT_REPORT_DIR = PARITY_DIR / "reports"
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "kraid.json"

HEAD_LABELS = tuple(f"Tilemap_KraidHead_{index}" for index in range(4))
HITBOX_LABELS = tuple(f"Hitbox_KraidMouth_{index}" for index in range(8))
CUSTOM_SEQUENCES = (
    ("roar", "InstList_Kraid_Roar_0", "InstList_Kraid_DyingRoar_0"),
    ("dying-roar", "InstList_Kraid_DyingRoar_0", "InstList_Kraid_EyeGlowing_0"),
    ("eye-glowing", "InstList_Kraid_EyeGlowing_0", "InstList_Kraid_Dying_0"),
    ("dying", "InstList_Kraid_Dying_0", "Hitbox_KraidMouth_0"),
)
OAM_HEADERS = (
    ("EnemyHeaders_Kraid", 0xE2BF),
    ("EnemyHeaders_KraidArm", 0xE2FF),
    ("EnemyHeaders_KraidLintTop", 0xE33F),
    ("EnemyHeaders_KraidLintMiddle", 0xE37F),
    ("EnemyHeaders_KraidLintBottom", 0xE3BF),
    ("EnemyHeaders_KraidFoot", 0xE3FF),
    ("EnemyHeaders_KraidNail", 0xE43F),
    ("EnemyHeaders_KraidNailBad", 0xE47F),
)
PALETTES = (
    ("Palette_Kraid", "oam-base"),
    ("Palette_KraidRoomBackground", "room-bg-row-6"),
    ("Palette_Kraid_BG_HurtFlash", "bg-hurt-flash"),
    *((f"Palette_Kraid_BG_{index}_8", f"bg-health-{index}-of-8") for index in range(1, 9)),
    ("Palette_Kraid_Death", "bg-death"),
    ("Palette_Kraid_Sprite_HurtFlash", "oam-hurt-flash"),
    *((f"Palette_Kraid_Sprite_{index}_8", f"oam-health-{index}-of-8") for index in range(1, 9)),
)

KRAID_OAM_SEQUENCE_GROUPS = (
    ("foot-initial", "InstList_KraidFoot_Initial", "InstList_KraidFoot_KraidIsBig_Neutral", True, False),
    ("foot-neutral", "InstList_KraidFoot_KraidIsBig_Neutral", "InstList_KraidFoot_KraidIsBig_WalkingForward_0", True, False),
    ("foot-walk-forward", "InstList_KraidFoot_KraidIsBig_WalkingForward_0", "InstList_KraidFoot_LungeForward_0", True, True),
    ("foot-lunge", "InstList_KraidFoot_LungeForward_0", "InstList_KraidFoot_KraidIsBig_WalkingBackwards_0", True, False),
    ("foot-walk-backward", "InstList_KraidFoot_KraidIsBig_WalkingBackwards_0", "UNUSED_InstList_KraidFoot_WalkingBackwards_Fast_A7893D", True, True),
    ("arm-normal", "InstList_KraidArm_Normal_0", "InstList_KraidArm_Slow", True, True),
    ("arm-slow", "InstList_KraidArm_Slow", "Instruction_KraidArm_SlowArmIfLessThanHalfHealth", True, True),
    ("arm-rising", "InstList_KraidArm_RisingSinking", "InstList_KraidArm_Dying_PreparingToLungeForward", True, True),
    ("arm-dying", "InstList_KraidArm_Dying_PreparingToLungeForward", "InstList_KraidLint_Initial", True, False),
    ("lint-initial", "InstList_KraidLint_Initial", "InstList_KraidLint_KraidIsBig", False, False),
    ("lint-big", "InstList_KraidLint_KraidIsBig", "InstList_KraidNail", False, False),
    ("nail", "InstList_KraidNail", "UNUSED_ExtendedSpritemap_KraidArm_A78B2E", False, True),
)

MINI_KRAID_SEQUENCES = (
    ("step-forward-left", "InstList_MiniKraid_StepForwards_FacingLeft", "InstList_MiniKraid_ChooseAction_duplicate"),
    ("step-backward-left", "InstList_MiniKraid_StepBackwards_FacingLeft", "InstList_MiniKraid_FireSpit_FacingLeft"),
    ("fire-left", "InstList_MiniKraid_FireSpit_FacingLeft", "UNUSED_InstList_MiniKraid_Standing_FacingLeft_A699F4"),
    ("step-forward-right", "InstList_MiniKraid_StepForwards_FacingRight", "InstList_MiniKraid_ChooseAction_duplicate_again3"),
    ("step-backward-right", "InstList_MiniKraid_StepBackwards_FacingRight", "InstList_MiniKraid_FireSpit_FacingRight"),
    ("fire-right", "InstList_MiniKraid_FireSpit_FacingRight", "UNUSED_InstList_MiniKraid_Standing_FacingRight_A69A42"),
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def snes_to_pc(address: int) -> int:
    return (((address >> 16) & 0x7F) * 0x8000) + (address & 0x7FFF)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def aggregate_hash(records: Sequence[Dict[str, object]]) -> str:
    return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def read_word(data: bytes, offset: int) -> int:
    return data[offset] | data[offset + 1] << 8


def unique_by_name(records: Sequence[Dict[str, object]], name: str, description: str) -> Dict[str, object]:
    matches = [record for record in records if record.get("name") == name]
    if len(matches) != 1:
        fail(f"expected one {description} named {name}, found {len(matches)}")
    return matches[0]


def deinterleave_screen_pair(source: bytes) -> bytes:
    if len(source) != 0x1000:
        fail(f"Kraid screen pair must decode to 0x1000 bytes, found 0x{len(source):X}")
    result = bytearray(64 * 32 * 2)
    for row in range(32):
        result[(row * 64) * 2 : (row * 64 + 32) * 2] = source[(row * 32) * 2 : ((row + 1) * 32) * 2]
        result[(row * 64 + 32) * 2 : ((row + 1) * 64) * 2] = source[(1024 + row * 32) * 2 : (1024 + (row + 1) * 32) * 2]
    return bytes(result)


def compact_instruction_record(record: Dict[str, object]) -> Dict[str, object]:
    result: Dict[str, object] = {
        "kind": record["kind"],
        "snesAddress": int(record["snesAddress"]),
        "size": int(record["size"]),
    }
    if record["kind"] == "frame":
        result.update(
            duration=int(record["duration"]),
            spritemapSnesAddress=int(record["spritemapSnesAddress"]),
            spritemapType=record["spritemapType"],
        )
    elif record["kind"] == "handler":
        result.update(
            handlerSnesAddress=int(record["handlerSnesAddress"]),
            operandByteCount=int(record["operandByteCount"]),
            controlFlow=record["controlFlow"],
        )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    disassembly = (
        args.disassembly
        or Path(os.environ.get("SMEDIT_DISASSEMBLY_DIR", str(DEFAULT_DISASSEMBLY)))
    ).expanduser().resolve()
    report_dir = args.report_dir.expanduser().resolve()
    rom_path = disassembly / "SM.sfc"
    inputs = {
        "symbols": report_dir / "symbols.json",
        "assets": report_dir / "assets.json",
        "lz5": report_dir / "lz5.json",
        "tilesets": report_dir / "tilesets.json",
        "headers": report_dir / "enemy-headers.json",
        "instructions": report_dir / "enemy-instructions.json",
    }
    if not rom_path.is_file() or any(not path.is_file() for path in inputs.values()):
        fail("missing reference ROM or prerequisite reports; run ./gradlew parityKraid")

    rom = rom_path.read_bytes()
    reports = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in inputs.items()}
    symbols = {record["name"]: int(record["snesAddress"]) for record in reports["symbols"]["symbols"]}
    commits = {
        str(reports[name]["disassemblyCommit"])
        for name in ("symbols", "assets", "tilesets", "headers", "instructions")
    }
    if len(commits) != 1:
        fail("Kraid prerequisite reports belong to different disassembly revisions")

    def address(label: str) -> int:
        if label not in symbols:
            fail(f"missing source symbol {label}")
        return symbols[label]

    asset_records = reports["assets"]["assets"]
    lz_records = reports["lz5"]["streams"]
    tileset = next((record for record in reports["tilesets"]["tilesets"] if int(record["id"]) == 0x1A), None)
    if tileset is None or tileset["tableLabel"] != "Tileset_Table_1A_Kraid":
        fail("tileset $1A no longer resolves to Tileset_Table_1A_Kraid")
    if int(tileset["resources"]["graphics"]["decompressedSize"]) != 0x8000:
        fail("Tiles_1A_Kraid must own all 1024 4bpp tile slots")

    room_states = []
    for label, background in (
        ("RoomState_Kraid_0", "LibBG_Brinstar_1A_Kraid_Upper_Lower"),
        ("RoomState_Kraid_1", "LibBG_Kraid_Dead"),
    ):
        state_address = address(label)
        if rom[snes_to_pc(state_address) + 3] != 0x1A:
            fail(f"{label} no longer selects tileset $1A")
        room_states.append(
            {
                "sourceLabel": label,
                "snesAddress": state_address,
                "tilesetId": 0x1A,
                "libraryBackgroundLabel": background,
                "libraryBackgroundSnesAddress": address(background),
            }
        )

    background_maps = []
    decoded_maps: Dict[str, bytes] = {}
    for label, active in (
        ("Background_Brinstar_1A_Kraid_Upper", True),
        ("Background_Brinstar_1A_Kraid_Lower_0", True),
        ("Background_Brinstar_1A_Kraid_Lower_1", False),
    ):
        stream = unique_by_name(lz_records, label, "LZ5 stream")
        stream_address = int(stream["snesAddress"])
        decoded = decode_lz5(rom, snes_to_pc(stream_address)).data
        if sha256(decoded) != stream["decompressedSha256"]:
            fail(f"independent decode changed for {label}")
        decoded_maps[label] = decoded
        consumer_labels = (
            ("LibBG_Brinstar_1A_Kraid_Upper_Lower", "SetupKraidGFXWithTheTilePriorityCleared")
            if active
            else ("UNUSED_LibBG_Brinstar_1A_Kraid_Lower_8FB863",)
        )
        background_maps.append(
            {
                "sourceLabel": label,
                "snesAddress": stream_address,
                "compressedSize": int(stream["compressedSize"]),
                "decompressedSize": len(decoded),
                "decompressedSha256": sha256(decoded),
                "runtimeConsumer": "active-room-library" if active else "source-declared-unreferenced-library",
                "consumers": [
                    {"sourceLabel": consumer, "snesAddress": address(consumer)}
                    for consumer in consumer_labels
                ],
            }
        )

    head_maps = []
    head_addresses = {address(label) for label in HEAD_LABELS}
    for label in HEAD_LABELS:
        head_address = address(label)
        stored = rom[snes_to_pc(head_address) : snes_to_pc(head_address) + 0x300]
        visible = stored[:0x2C0]
        words = [read_word(visible, offset) for offset in range(0, len(visible), 2)]
        visible_words = [word for word in words if (word & 0x03FF) != 0x0338]
        head_maps.append(
            {
                "sourceLabel": label,
                "snesAddress": head_address,
                "storedColumns": 32,
                "storedRows": 12,
                "storedBytes": len(stored),
                "copiedRows": 11,
                "copiedBytes": len(visible),
                "storedSha256": sha256(stored),
                "copiedSha256": sha256(visible),
                "visibleTileMin": min(word & 0x03FF for word in visible_words),
                "visibleTileMax": max(word & 0x03FF for word in visible_words),
                "visibleUniqueTileCount": len({word & 0x03FF for word in visible_words}),
                "visiblePaletteRows": sorted({(word >> 10) & 7 for word in visible_words}),
            }
        )

    sequences: List[Dict[str, object]] = []
    for key, start_label, end_label in CUSTOM_SEQUENCES:
        start = address(start_label)
        end = address(end_label)
        position = start
        records: List[Dict[str, object]] = []
        while position < end:
            pc = snes_to_pc(position)
            first = read_word(rom, pc)
            if first == 0xFFFF:
                records.append({"kind": "terminator", "snesAddress": position})
                position += 2
                break
            if first & 0x8000:
                records.append(
                    {
                        "kind": "handler",
                        "snesAddress": position,
                        "handlerSnesAddress": 0xA70000 | first,
                    }
                )
                position += 2
                continue
            tilemap = 0xA70000 | read_word(rom, pc + 2)
            vulnerable = 0xA70000 | read_word(rom, pc + 4)
            invulnerable_word = read_word(rom, pc + 6)
            if tilemap not in head_addresses:
                fail(f"{key} references unexpected head tilemap ${tilemap:06X}")
            records.append(
                {
                    "kind": "frame",
                    "snesAddress": position,
                    "duration": first,
                    "tilemapSnesAddress": tilemap,
                    "vulnerableHitboxSnesAddress": vulnerable,
                    "invulnerableHitboxSnesAddress": None if invulnerable_word == 0xFFFF else 0xA70000 | invulnerable_word,
                }
            )
            position += 8
        if position != end:
            fail(f"custom Kraid sequence {key} ended at ${position:06X}, expected ${end:06X}")
        sequences.append(
            {
                "key": key,
                "sourceLabel": start_label,
                "snesAddress": start,
                "endSnesAddressExclusive": end,
                "records": records,
            }
        )

    hitboxes = []
    for index, label in enumerate(HITBOX_LABELS):
        hitbox_address = address(label)
        raw = rom[snes_to_pc(hitbox_address) : snes_to_pc(hitbox_address) + 8]
        values = [read_word(raw, offset) for offset in range(0, 8, 2)]
        hitboxes.append(
            {
                "sourceLabel": label,
                "snesAddress": hitbox_address,
                "left": values[0],
                "top": values[1],
                "right": values[2],
                "bottom": values[3],
                "sourceDeclaredUnused": index == 4,
                "sha256": sha256(raw),
            }
        )

    header_by_label = {record["sourceLabel"]: record for record in reports["headers"]["headers"]}
    oam_headers = []
    for label, species_id in OAM_HEADERS:
        header = header_by_label.get(label)
        if header is None or int(header["speciesId"]) != species_id:
            fail(f"missing or changed Kraid OAM header {label}")
        fields = header["fields"]
        if int(fields["tileData"]["value"]) != address("Tiles_Kraid"):
            fail(f"{label} no longer shares Tiles_Kraid")
        if int(fields["tileDataSize"]["value"]) != 0x1E00:
            fail(f"{label} no longer owns the exact 0x1E00-byte Kraid range")
        if int(fields["palette"]["targetSnesAddress"]) != address("Palette_Kraid"):
            fail(f"{label} no longer shares Palette_Kraid")
        oam_headers.append(
            {
                "sourceLabel": label,
                "speciesId": species_id,
                "headerSnesAddress": int(header["snesAddress"]),
                "tileDataSnesAddress": int(fields["tileData"]["value"]),
                "tileDataSize": int(fields["tileDataSize"]["value"]),
                "paletteSnesAddress": int(fields["palette"]["targetSnesAddress"]),
            }
        )

    kraid_oam_lists = [
        record
        for record in reports["instructions"]["lists"]
        if str(record["sourceLabel"]).startswith("InstList_Kraid")
    ]
    all_instruction_lists = reports["instructions"]["lists"]

    def bounded_sequence(
        key: str,
        start_label: str,
        end_label: str,
        extended: bool,
        loop: bool,
    ) -> Dict[str, object]:
        start = address(start_label)
        end = address(end_label)
        records = sorted(
            (
                compact_instruction_record(record)
                for instruction_list in all_instruction_lists
                for record in instruction_list["records"]
                if start <= int(record["snesAddress"]) < end
            ),
            key=lambda record: int(record["snesAddress"]),
        )
        position = start
        for record in records:
            if int(record["snesAddress"]) != position:
                fail(
                    f"{key} source records have a gap at ${position:06X}; "
                    f"next record is ${int(record['snesAddress']):06X}"
                )
            position += int(record["size"])
        if position != end:
            fail(f"{key} source records end at ${position:06X}, expected ${end:06X}")
        return {
            "key": key,
            "sourceLabel": start_label,
            "snesAddress": start,
            "endSourceLabel": end_label,
            "endSnesAddressExclusive": end,
            "extended": extended,
            "loop": loop,
            "records": records,
        }

    active_oam_sequences = [
        bounded_sequence(key, start, end, extended, loop)
        for key, start, end, extended, loop in KRAID_OAM_SEQUENCE_GROUPS
    ]

    linked_oam = {
        "graphics": unique_by_name(asset_records, "Tiles_Kraid", "asset"),
        "headers": oam_headers,
        "ordinaryInstructionListCount": len(kraid_oam_lists),
        "ordinaryFrameOccurrenceCount": sum(
            record["kind"] == "frame"
            for instruction_list in kraid_oam_lists
            for record in instruction_list["records"]
        ),
        "ordinaryHandlerOccurrenceCount": sum(
            record["kind"] == "handler"
            for instruction_list in kraid_oam_lists
            for record in instruction_list["records"]
        ),
    }
    linked_oam["graphics"] = {
        key: linked_oam["graphics"][key]
        for key in ("name", "snesAddress", "size", "sha256", "asset")
    }

    mini_header = header_by_label.get("EnemyHeaders_MiniKraid")
    if mini_header is None or int(mini_header["speciesId"]) != 0xE0FF:
        fail("missing or changed Mini Kraid header")
    mini_fields = mini_header["fields"]
    if int(mini_fields["tileData"]["value"]) != address("Tiles_MiniKraid"):
        fail("Mini Kraid no longer owns Tiles_MiniKraid")
    if int(mini_fields["tileDataSize"]["value"]) != 0x1000:
        fail("Mini Kraid tile range is no longer exactly 0x1000 bytes")
    if int(mini_fields["palette"]["targetSnesAddress"]) != address("Palette_MiniKraid"):
        fail("Mini Kraid no longer owns Palette_MiniKraid")
    mini_sequences = [
        bounded_sequence(key, start, end, False, False)
        for key, start, end in MINI_KRAID_SEQUENCES
    ]
    mini_pose_addresses = sorted(
        {
            int(record["spritemapSnesAddress"])
            for sequence in mini_sequences
            for record in sequence["records"]
            if record["kind"] == "frame"
        }
    )
    mini_asset = unique_by_name(asset_records, "Tiles_MiniKraid", "asset")
    mini_kraid = {
        "speciesId": 0xE0FF,
        "headerSourceLabel": "EnemyHeaders_MiniKraid",
        "headerSnesAddress": int(mini_header["snesAddress"]),
        "graphics": {
            key: mini_asset[key]
            for key in ("name", "snesAddress", "size", "sha256", "asset")
        },
        "paletteSourceLabel": "Palette_MiniKraid",
        "paletteSnesAddress": address("Palette_MiniKraid"),
        "sequences": mini_sequences,
        "uniquePoseSnesAddresses": mini_pose_addresses,
    }

    palettes = []
    for label, role in PALETTES:
        palette_address = address(label)
        raw = rom[snes_to_pc(palette_address) : snes_to_pc(palette_address) + 32]
        palettes.append(
            {
                "sourceLabel": label,
                "role": role,
                "snesAddress": palette_address,
                "size": 32,
                "sha256": sha256(raw),
            }
        )

    palette_resource = tileset["resources"]["palette"]
    decoded_tileset_palette = decode_lz5(
        rom, snes_to_pc(int(palette_resource["snesAddress"]))
    ).data
    full_health_palette = rom[
        snes_to_pc(address("Palette_Kraid_BG_8_8")) : snes_to_pc(address("Palette_Kraid_BG_8_8")) + 32
    ]
    if decoded_tileset_palette[7 * 32 : 8 * 32] != full_health_palette:
        fail("tileset $1A palette row 7 no longer matches Kraid's full-health BG palette")

    upper_with_head = bytearray(decoded_maps["Background_Brinstar_1A_Kraid_Upper"])
    first_head_pc = snes_to_pc(address(HEAD_LABELS[0]))
    upper_with_head[:0x2C0] = rom[first_head_pc : first_head_pc + 0x2C0]
    composite = deinterleave_screen_pair(bytes(upper_with_head)) + deinterleave_screen_pair(
        decoded_maps["Background_Brinstar_1A_Kraid_Lower_0"]
    )

    bank_a7 = (disassembly / "src" / "bank_A7.asm").read_text(encoding="utf-8")
    bank_8f = (disassembly / "src" / "bank_8F.asm").read_text(encoding="utf-8")
    required_source_tokens = (
        (bank_a7, "LDA.W #$02C0", "Kraid head DMA byte count"),
        (bank_a7, "LDA.B #Tilemap_KraidHead_0>>16", "Kraid head DMA source bank"),
        (bank_a7, "LDA.W #Tiles_KraidRoomBackground", "room-background tile consumer"),
        (bank_8f, "dl Background_Brinstar_1A_Kraid_Upper", "active upper BG consumer"),
        (bank_8f, "dl Background_Brinstar_1A_Kraid_Lower_0", "active lower BG consumer"),
        (bank_8f, "dl Background_Brinstar_1A_Kraid_Lower_1", "unreferenced lower BG consumer"),
    )
    for source, token, description in required_source_tokens:
        if token not in source:
            fail(f"source no longer contains {description}: {token}")

    frame_count = sum(record["kind"] == "frame" for sequence in sequences for record in sequence["records"])
    handler_count = sum(record["kind"] == "handler" for sequence in sequences for record in sequence["records"])
    totals = {
        "roomStateCount": len(room_states),
        "backgroundMapCount": len(background_maps),
        "activeBackgroundMapCount": sum(record["runtimeConsumer"] == "active-room-library" for record in background_maps),
        "headMapCount": len(head_maps),
        "customSequenceCount": len(sequences),
        "customFrameOccurrenceCount": frame_count,
        "customHandlerOccurrenceCount": handler_count,
        "mouthHitboxCount": len(hitboxes),
        "paletteStateCount": len(palettes),
        "linkedOamHeaderCount": len(oam_headers),
        "linkedOamInstructionListCount": linked_oam["ordinaryInstructionListCount"],
        "linkedOamFrameOccurrenceCount": linked_oam["ordinaryFrameOccurrenceCount"],
        "activeOamSequenceCount": len(active_oam_sequences),
        "activeOamFrameOccurrenceCount": sum(
            record["kind"] == "frame"
            for sequence in active_oam_sequences
            for record in sequence["records"]
        ),
        "miniKraidSequenceCount": len(mini_sequences),
        "miniKraidFrameOccurrenceCount": sum(
            record["kind"] == "frame"
            for sequence in mini_sequences
            for record in sequence["records"]
        ),
        "miniKraidUniquePoseCount": len(mini_pose_addresses),
        "backgroundMapConsumerAssociationCount": sum(
            len(record["consumers"]) for record in background_maps
        ),
        "roomBackgroundTileConsumerCount": 3,
    }
    room_background_tile_consumers = [
        {"sourceLabel": label, "snesAddress": address(label)}
        for label in ("InitAI_Kraid", "DrawKraidsRoomBackground", "UnpauseHook_KraidIsDead")
    ]
    ownership = {
        "bgPixels": {
            "resource": tileset["resources"]["graphics"],
            "editUnit": "entire decompressed tileset graphics resource",
            "projectKey": "varGfx[\"26\"]",
            "creGraphicsOverlay": False,
        },
        "bgPlacement": {
            "backgroundMaps": background_maps,
            "headMaps": head_maps,
            "projectEditable": False,
        },
        "paletteOwnership": {
            "headAndBodyRow": 7,
            "fullHealthSourceLabel": "Palette_Kraid_BG_8_8",
            "fullHealthMatchesTilesetPalette": True,
            "roomBackgroundRow": 6,
            "roomBackgroundSourceLabel": "Palette_KraidRoomBackground",
            "linkedOamSourceLabel": "Palette_Kraid",
        },
        "linkedOam": linked_oam,
        "roomBackgroundTiles": {
            key: unique_by_name(asset_records, "Tiles_KraidRoomBackground", "asset")[key]
            for key in ("name", "snesAddress", "size", "sha256", "asset")
        },
    }
    ownership["roomBackgroundTiles"]["consumers"] = room_background_tile_consumers
    aggregate_hashes = {
        "ownership": aggregate_hash([ownership]),
        "headMaps": aggregate_hash(head_maps),
        "customSequences": aggregate_hash(sequences),
        "palettes": aggregate_hash(palettes),
        "liveCompositeTilemap": sha256(composite),
        "activeOamSequences": aggregate_hash(active_oam_sequences),
        "miniKraid": aggregate_hash([mini_kraid]),
    }
    payload = {
        "schemaVersion": 1,
        "disassemblyCommit": commits.pop(),
        "oracle": "named source symbols, source consumers, rebuilt-ROM bytes, and prerequisite manifests",
        "runtimeConsumers": {
            "setupGfxSnesAddress": address("SetupKraidGFXWithTheTilePriorityCleared"),
            "customHeadInterpreterSnesAddress": address("ProcessKraidInstList"),
            "headDmaByteCount": 0x2C0,
            "headDestination": "start of BG2 tilemap VRAM (upper-left 32x32 screen block)",
        },
        "roomStates": room_states,
        "ownership": ownership,
        "customHeadSequences": sequences,
        "activeOamSequences": active_oam_sequences,
        "miniKraid": mini_kraid,
        "mouthHitboxes": hitboxes,
        "palettes": palettes,
        "liveComposite": {
            "columns": 64,
            "rows": 64,
            "headSourceLabel": HEAD_LABELS[0],
            "tilemapWordSha256": sha256(composite),
        },
        "totals": totals,
        "aggregateHashes": aggregate_hashes,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("Kraid source manifest valid")
    print(
        f"  Head maps: {totals['headMapCount']} / custom frames: {frame_count} / "
        f"linked OAM headers: {totals['linkedOamHeaderCount']}"
    )
    print(
        f"  Active linked OAM: {totals['activeOamSequenceCount']} sequences / "
        f"{totals['activeOamFrameOccurrenceCount']} frames"
    )
    print(
        f"  Mini Kraid: {totals['miniKraidSequenceCount']} sequences / "
        f"{totals['miniKraidFrameOccurrenceCount']} frames / "
        f"{totals['miniKraidUniquePoseCount']} unique poses"
    )
    print(f"  Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
