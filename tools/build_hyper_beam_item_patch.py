#!/usr/bin/env python3
"""Build the bundled placeable Hyper Beam item IPS patch.

The item deliberately does not allocate a Samus inventory bit. Its PLM room
argument still uses vanilla's saved item-collected table, just like every
ordinary pickup, so each placed copy disappears permanently after collection.

Vanilla enables Hyper Beam by storing $1009 in equipped beams, refreshing beam
graphics, spawning palette FX object $E1F0, and storing $8000 at $7E:0A76. The
equipped-beam word is saved but the Hyper Beam flag is not. A small wrapper on
the native beam refresh routine restores that flag after loading a save whose
equipped-beam word is $1009. It also gives door transitions the same native
palette-FX lifecycle as the Mother Brain acquisition path.

This builder is intentionally self-contained. It emits the exact 65816 bytes,
derives the three item PLM scripts from vanilla Morph Ball, validates every
fixed/free-space precondition against the JU ROM, and verifies the completed
IPS before writing it.
"""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE_ROM = ROOT / "test-resources" / "Super Metroid (JU) [!].smc"
OUT_IPS = (
    ROOT
    / "shared"
    / "src"
    / "commonMain"
    / "resources"
    / "patches"
    / "hyper_beam_item.ips"
)

# Bank $84 is free from $EFD3-$FFFF in the annotated disassembly. Spider Ball
# owns $F200-$F2B5, so this patch starts at the next aligned block.
HYPER_PLM_VISIBLE = 0xF300
HYPER_PLM_CHOZO = 0xF304
HYPER_PLM_HIDDEN = 0xF308
HYPER_PLM_LIST_START = 0xF30C
HYPER_PICKUP_INSTRUCTION = 0xF3C0
BEAM_REFRESH_WRAPPER = 0xF400

# Bank $89 is free from $AEFD-$FFFF. Spider Ball uses $F000-$F0FF.
HYPER_ITEM_GFX_ADDR = 0xF100
HYPER_ITEM_GFX_SIZE = 0x100

MORPH_PLM_SETUP_VISIBLE_CHOZO = 0xEE64
MORPH_PLM_SETUP_HIDDEN = 0xEE8E
MORPH_VISIBLE_LIST_START = 0xE3EF
MORPH_VISIBLE_LIST_END = 0xE41D
MORPH_CHOZO_LIST_START = 0xE89C
MORPH_CHOZO_LIST_END = 0xE8D7
MORPH_HIDDEN_LIST_START = 0xEDCC
MORPH_HIDDEN_LIST_END = 0xEE0D

BEAM_REFRESH_HOOK_ADDR = 0xAC8D
BEAM_REFRESH_RESUME_ADDR = 0xAC91
UPDATE_BEAM_TILES_AND_PALETTE = 0x90AC8D
PLAY_ROOM_MUSIC_AFTER_A_FRAMES = 0x82E118
SPAWN_PALETTE_FX_OBJECT = 0x8DC4E9
HYPER_PALETTE_FX_OBJECT = 0xE1F0
MESSAGE_BOX_ROUTINE = 0x858080

# Spider Ball and Hyper Beam share this relocated vanilla message table. Both
# patches emit byte-identical table/hook writes, while each owns only its own
# 64-byte tilemap. That keeps either patch standalone and lets them coexist.
MESSAGE_BOX_TABLE_ORIGINAL = 0x869B
MESSAGE_BOX_TABLE_ORIGINAL_ENTRIES = 29
MESSAGE_BOX_TABLE_RELOCATED = 0x9C00
SPIDER_BALL_MESSAGE_TILEMAP = 0x9D00
HYPER_BEAM_MESSAGE_ID = 0x1F
HYPER_BEAM_MESSAGE_TILEMAP = 0x9D40
CUSTOM_ITEM_MESSAGE_TILEMAP_END = 0x9D80

EQUIPPED_BEAMS = 0x09A6
GAME_STATE = 0x0998
HYPER_BEAM_FLAG = 0x0A76
RESUME_CHARGING_BEAM_SFX_FLAG = 0x0DC0
HYPER_EQUIPPED_BEAMS = 0x1009
HYPER_BEAM_ENABLED = 0x8000


def lorom_pc(bank: int, addr: int) -> int:
    if addr < 0x8000:
        raise ValueError(f"LoROM address must be in upper half of bank: ${bank:02X}:{addr:04X}")
    return (bank & 0x7F) * 0x8000 + (addr & 0x7FFF)


def u16(value: int) -> bytes:
    return bytes((value & 0xFF, (value >> 8) & 0xFF))


def u24_be(value: int) -> bytes:
    return bytes(((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF))


def long_le(value: int) -> bytes:
    return bytes((value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF))


def read_bank(base: bytes, bank: int, addr: int, length: int) -> bytes:
    start = lorom_pc(bank, addr)
    data = base[start : start + length]
    if len(data) != length:
        raise ValueError(
            f"short read from ${bank:02X}:{addr:04X}: expected {length}, got {len(data)}"
        )
    return data


def encode_4bpp_tile(pixels: list[list[int]]) -> bytes:
    if len(pixels) != 8 or any(len(row) != 8 for row in pixels):
        raise ValueError("expected an 8x8 pixel tile")
    out = bytearray(32)
    for row in range(8):
        for col, color in enumerate(pixels[row]):
            if color not in range(16):
                raise ValueError(f"invalid 4bpp colour index {color}")
            mask = 1 << (7 - col)
            if color & 0x1:
                out[row * 2] |= mask
            if color & 0x2:
                out[row * 2 + 1] |= mask
            if color & 0x4:
                out[16 + row * 2] |= mask
            if color & 0x8:
                out[16 + row * 2 + 1] |= mask
    return bytes(out)


def build_hyper_frame(pulse: bool) -> list[list[int]]:
    """Return one 16x16 indexed frame: dark orb plus a wide / beam."""
    pixels = [[0 for _ in range(16)] for _ in range(16)]
    center = 7.5
    for y in range(16):
        for x in range(16):
            distance_squared = (x - center) ** 2 + (y - center) ** 2
            if distance_squared <= 45.0:
                # The Morph Ball item palette uses 1/9 as its dark interior,
                # 12/13 for its shell, and 15 for its brightest highlight.
                if distance_squared >= 34.0:
                    pixels[y][x] = 13 if (x + y) % 2 == 0 else 12
                elif x + y < 13:
                    pixels[y][x] = 1
                else:
                    pixels[y][x] = 9

    # x + y = 15 runs from bottom-left to top-right. Let the energized beam
    # extend just beyond the orb, with a two/three-pixel halo and white core.
    for y in range(1, 15):
        for x in range(1, 15):
            diagonal_distance = abs(x + y - 15)
            if diagonal_distance <= (2 if pulse else 1):
                pixels[y][x] = 13
            if diagonal_distance == 0:
                pixels[y][x] = 15
            elif pulse and diagonal_distance == 1 and (x + y) % 2:
                pixels[y][x] = 15

    # Bright caps make the diagonal read as a beam rather than a ring stripe.
    pixels[13][1] = pixels[14][1] = pixels[14][2] = 15
    pixels[1][13] = pixels[1][14] = pixels[2][14] = 15
    return pixels


def split_frame_into_tiles(frame: list[list[int]]) -> bytes:
    tiles = []
    for tile_y in range(2):
        for tile_x in range(2):
            tile = [
                row[tile_x * 8 : tile_x * 8 + 8]
                for row in frame[tile_y * 8 : tile_y * 8 + 8]
            ]
            tiles.append(encode_4bpp_tile(tile))
    return b"".join(tiles)


def build_hyper_item_gfx() -> bytes:
    data = split_frame_into_tiles(build_hyper_frame(pulse=False))
    data += split_frame_into_tiles(build_hyper_frame(pulse=True))
    if len(data) != HYPER_ITEM_GFX_SIZE:
        raise ValueError(f"Hyper Beam item graphics are {len(data)} bytes, expected 256")
    return data


def encode_small_message_tilemap(text: str) -> bytes:
    if any(ch != " " and not ("a" <= ch <= "z") for ch in text):
        raise ValueError("small message text supports lowercase a-z and spaces")
    text_width = 19
    if len(text) > text_width:
        raise ValueError(f"small message text is too long: {text!r}")

    words: list[int] = [0x000E] * 6
    left_spaces = (text_width - len(text)) // 2
    right_spaces = text_width - len(text) - left_spaces
    for ch in (" " * left_spaces) + text + (" " * right_spaces):
        words.append(0x284E if ch == " " else 0x28E0 + ord(ch) - ord("a"))
    words.extend([0x000E] * (32 - len(words)))
    if len(words) != 32:
        raise ValueError(f"small message produced {len(words)} tiles")
    return b"".join(u16(word) for word in words)


def message_box_entry(tilemap_addr: int) -> bytes:
    return u16(0x8436) + u16(0x8289) + u16(tilemap_addr)


def build_message_box_table(base: bytes) -> bytes:
    original = read_bank(
        base,
        0x85,
        MESSAGE_BOX_TABLE_ORIGINAL,
        MESSAGE_BOX_TABLE_ORIGINAL_ENTRIES * 6,
    )
    return b"".join(
        (
            original,
            message_box_entry(SPIDER_BALL_MESSAGE_TILEMAP),
            message_box_entry(HYPER_BEAM_MESSAGE_TILEMAP),
            message_box_entry(CUSTOM_ITEM_MESSAGE_TILEMAP_END),
        )
    )


def relocate_morph_plm_list(data: bytes, old_start: int, new_start: int) -> bytes:
    out = bytearray(data)
    old_end = old_start + len(out)
    for i in range(len(out) - 1):
        value = out[i] | (out[i + 1] << 8)
        if old_start <= value < old_end:
            out[i : i + 2] = u16(new_start + value - old_start)

    pickup_offsets = [
        i
        for i in range(len(out) - 4)
        if out[i : i + 2] == bytes((0xF3, 0x88)) and out[i + 4] == 0x09
    ]
    if len(pickup_offsets) != 1:
        raise ValueError(f"expected one Morph Ball pickup instruction, found {len(pickup_offsets)}")
    pickup_offset = pickup_offsets[0]
    out[pickup_offset : pickup_offset + 2] = u16(HYPER_PICKUP_INSTRUCTION)
    # Preserve the original three-byte instruction footprint. The custom
    # instruction ignores these arguments and advances Y by three bytes.
    out[pickup_offset + 2 : pickup_offset + 5] = b"\x00\x00\x00"

    gfx_offsets = [
        i
        for i in range(len(out) - 11)
        if out[i : i + 4] == bytes((0x64, 0x87, 0x00, 0x87))
    ]
    if len(gfx_offsets) != 1:
        raise ValueError(f"expected one Morph Ball item graphics instruction, found {len(gfx_offsets)}")
    gfx_offset = gfx_offsets[0]
    out[gfx_offset + 2 : gfx_offset + 4] = u16(HYPER_ITEM_GFX_ADDR)
    return bytes(out)


def build_hyper_item_plms(base: bytes) -> bytes:
    visible_raw = read_bank(
        base, 0x84, MORPH_VISIBLE_LIST_START, MORPH_VISIBLE_LIST_END - MORPH_VISIBLE_LIST_START
    )
    chozo_raw = read_bank(
        base, 0x84, MORPH_CHOZO_LIST_START, MORPH_CHOZO_LIST_END - MORPH_CHOZO_LIST_START
    )
    hidden_raw = read_bank(
        base, 0x84, MORPH_HIDDEN_LIST_START, MORPH_HIDDEN_LIST_END - MORPH_HIDDEN_LIST_START
    )

    visible_start = HYPER_PLM_LIST_START
    chozo_start = visible_start + len(visible_raw)
    hidden_start = chozo_start + len(chozo_raw)

    visible = relocate_morph_plm_list(visible_raw, MORPH_VISIBLE_LIST_START, visible_start)
    chozo = relocate_morph_plm_list(chozo_raw, MORPH_CHOZO_LIST_START, chozo_start)
    hidden = relocate_morph_plm_list(hidden_raw, MORPH_HIDDEN_LIST_START, hidden_start)

    headers = b"".join(
        (
            u16(MORPH_PLM_SETUP_VISIBLE_CHOZO),
            u16(visible_start),
            u16(MORPH_PLM_SETUP_VISIBLE_CHOZO),
            u16(chozo_start),
            u16(MORPH_PLM_SETUP_HIDDEN),
            u16(hidden_start),
        )
    )
    return headers + visible + chozo + hidden


def build_pickup_instruction() -> bytes:
    """PLM instruction with the same three-byte argument contract as $88F3."""
    return b"".join(
        (
            b"\xA9" + u16(HYPER_EQUIPPED_BEAMS),  # LDA #$1009
            b"\x8D" + u16(EQUIPPED_BEAMS),  # STA $09A6
            b"\xDA\x5A",  # PHX / PHY
            b"\x22" + long_le(UPDATE_BEAM_TILES_AND_PALETTE),  # JSL $90:AC8D
            b"\xA0" + u16(HYPER_PALETTE_FX_OBJECT),  # LDY #$E1F0
            b"\x22" + long_le(SPAWN_PALETTE_FX_OBJECT),
            b"\x7A\xFA",  # PLY / PLX
            b"\xA9" + u16(HYPER_BEAM_ENABLED),
            b"\x8D" + u16(HYPER_BEAM_FLAG),
            b"\x9C" + u16(RESUME_CHARGING_BEAM_SFX_FLAG),  # STZ $0DC0
            b"\xA9" + u16(0x0168),  # six-second item-fanfare delay
            b"\x22" + long_le(PLAY_ROOM_MUSIC_AFTER_A_FRAMES),
            b"\xA9" + u16(HYPER_BEAM_MESSAGE_ID),
            b"\x22" + long_le(MESSAGE_BOX_ROUTINE),
            b"\xC8\xC8\xC8\x60",  # INY x3 / RTS
        )
    )


def build_beam_refresh_wrapper() -> bytes:
    """Restore the non-saved Hyper flag from vanilla's saved beam mask."""
    wrapper = bytearray(
        b"".join(
            (
                # The native entry's PHK would push bank $90. This trampoline
                # lives in bank $84, so explicitly install DB=$90; using PHK
                # here makes the beam-tile pointer table read from bank $84
                # and produces invisible Hyper Beam projectiles.
                b"\x08\x8B\xE2\x20\xA9\x90\x48\xAB",  # PHP/PHB; DB=$90
                b"\xC2\x30",  # REP #$30
                b"\xAD" + u16(HYPER_BEAM_FLAG),
            )
        )
    )
    resume_branches: list[int] = []

    def bne_resume() -> None:
        resume_branches.append(len(wrapper))
        wrapper.extend(b"\xD0\x00")

    bne_resume()  # Hyper is already active.
    # Native Mother Brain acquisition also sets $1009 immediately before its
    # beam refresh, but happens in normal gameplay state 8. Restrict volatile
    # flag restoration to state 6, the engine's saved-game loading state.
    wrapper.extend(b"\xAD" + u16(GAME_STATE))
    wrapper.extend(b"\xC9" + u16(0x0006))
    bne_resume()
    wrapper.extend(b"\xAD" + u16(EQUIPPED_BEAMS))
    wrapper.extend(b"\xC9" + u16(HYPER_EQUIPPED_BEAMS))
    bne_resume()
    wrapper.extend(b"\xA9" + u16(HYPER_BEAM_ENABLED))
    wrapper.extend(b"\x8D" + u16(HYPER_BEAM_FLAG))
    wrapper.extend(b"\xA0" + u16(HYPER_PALETTE_FX_OBJECT))
    wrapper.extend(b"\x22" + long_le(SPAWN_PALETTE_FX_OBJECT))

    resume = len(wrapper)
    for branch in resume_branches:
        relative = resume - (branch + 2)
        if relative not in range(0x80):
            raise ValueError(f"beam-refresh branch out of range: {relative}")
        wrapper[branch + 1] = relative
    wrapper += b"\x5C" + long_le(0x900000 | BEAM_REFRESH_RESUME_ADDR)  # JML $90:AC91
    return bytes(wrapper)


def make_ips(records: list[tuple[int, bytes]]) -> bytes:
    out = bytearray(b"PATCH")
    for offset, data in records:
        if not data:
            continue
        cursor = 0
        while cursor < len(data):
            chunk = data[cursor : cursor + 0xFFFF]
            out += u24_be(offset + cursor)
            out += len(chunk).to_bytes(2, "big")
            out += chunk
            cursor += len(chunk)
    out += b"EOF"
    return bytes(out)


def apply_ips(rom: bytearray, ips: bytes) -> None:
    if ips[:5] != b"PATCH":
        raise ValueError("not an IPS patch")
    pos = 5
    while pos < len(ips):
        if ips[pos : pos + 3] == b"EOF":
            return
        offset = int.from_bytes(ips[pos : pos + 3], "big")
        size = int.from_bytes(ips[pos + 3 : pos + 5], "big")
        pos += 5
        if size == 0:
            run_len = int.from_bytes(ips[pos : pos + 2], "big")
            value = ips[pos + 2]
            pos += 3
            rom[offset : offset + run_len] = bytes([value]) * run_len
        else:
            rom[offset : offset + size] = ips[pos : pos + size]
            pos += size
    raise ValueError("IPS missing EOF")


def main() -> None:
    base = BASE_ROM.read_bytes()
    item_plms = build_hyper_item_plms(base)
    pickup_instruction = build_pickup_instruction()
    refresh_wrapper = build_beam_refresh_wrapper()
    item_gfx = build_hyper_item_gfx()
    message_box_table = build_message_box_table(base)
    hyper_beam_message = encode_small_message_tilemap("hyper beam")

    item_plm_end = HYPER_PLM_VISIBLE + len(item_plms)
    if item_plm_end > HYPER_PICKUP_INSTRUCTION:
        raise SystemExit(
            f"Hyper Beam PLMs overlap pickup instruction: ${item_plm_end:04X} > "
            f"${HYPER_PICKUP_INSTRUCTION:04X}"
        )
    if HYPER_PICKUP_INSTRUCTION + len(pickup_instruction) > BEAM_REFRESH_WRAPPER:
        raise SystemExit("Hyper Beam pickup instruction overlaps beam-refresh wrapper")
    if BEAM_REFRESH_WRAPPER + len(refresh_wrapper) > 0x10000:
        raise SystemExit("Hyper Beam beam-refresh wrapper extends outside bank $84")

    records = [
        # Three placeable item variants and their shared custom acquisition instruction.
        (lorom_pc(0x84, HYPER_PLM_VISIBLE), item_plms),
        (lorom_pc(0x84, HYPER_PICKUP_INSTRUCTION), pickup_instruction),
        # Restore Hyper's volatile flag when native beam graphics are refreshed.
        (
            lorom_pc(0x90, BEAM_REFRESH_HOOK_ADDR),
            b"\x5C" + long_le(0x840000 | BEAM_REFRESH_WRAPPER),
        ),
        (lorom_pc(0x84, BEAM_REFRESH_WRAPPER), refresh_wrapper),
        # Shared custom-item message table hooks. Spider Ball emits the same
        # bytes, so write planning can safely merge them when both are used.
        (lorom_pc(0x85, 0x8251), u16(MESSAGE_BOX_TABLE_RELOCATED + 2)),
        (lorom_pc(0x85, 0x8255), u16(MESSAGE_BOX_TABLE_RELOCATED)),
        (lorom_pc(0x85, 0x82F2), u16(MESSAGE_BOX_TABLE_RELOCATED + 4)),
        (lorom_pc(0x85, 0x82F7), u16(MESSAGE_BOX_TABLE_RELOCATED + 10)),
        (lorom_pc(0x85, MESSAGE_BOX_TABLE_RELOCATED), message_box_table),
        (lorom_pc(0x85, HYPER_BEAM_MESSAGE_TILEMAP), hyper_beam_message),
        # Two animated 16x16 pickup frames (eight SNES 4bpp tiles).
        (lorom_pc(0x89, HYPER_ITEM_GFX_ADDR), item_gfx),
    ]

    expected_originals = {
        lorom_pc(0x84, HYPER_PLM_VISIBLE): b"\xFF" * len(item_plms),
        lorom_pc(0x84, HYPER_PICKUP_INSTRUCTION): b"\xFF" * len(pickup_instruction),
        lorom_pc(0x90, BEAM_REFRESH_HOOK_ADDR): bytes.fromhex("08 8B 4B AB"),
        lorom_pc(0x84, BEAM_REFRESH_WRAPPER): b"\xFF" * len(refresh_wrapper),
        lorom_pc(0x85, 0x8251): bytes.fromhex("9D 86"),
        lorom_pc(0x85, 0x8255): bytes.fromhex("9B 86"),
        lorom_pc(0x85, 0x82F2): bytes.fromhex("9F 86"),
        lorom_pc(0x85, 0x82F7): bytes.fromhex("A5 86"),
        lorom_pc(0x85, MESSAGE_BOX_TABLE_RELOCATED): b"\xFF" * len(message_box_table),
        lorom_pc(0x85, HYPER_BEAM_MESSAGE_TILEMAP): b"\xFF" * len(hyper_beam_message),
        lorom_pc(0x89, HYPER_ITEM_GFX_ADDR): b"\xFF" * len(item_gfx),
    }
    for offset, expected in expected_originals.items():
        actual = base[offset : offset + len(expected)]
        if actual != expected:
            raise SystemExit(
                f"Unexpected original bytes at PC ${offset:06X}: expected "
                f"{expected[:16].hex(' ')}, got {actual[:16].hex(' ')}"
            )

    ips = make_ips(records)
    patched = bytearray(base)
    apply_ips(patched, ips)
    for offset, expected in records:
        actual = patched[offset : offset + len(expected)]
        if actual != expected:
            raise SystemExit(f"Patch verification failed at PC ${offset:06X}")

    OUT_IPS.write_bytes(ips)
    print(f"Wrote {OUT_IPS.relative_to(ROOT)}")
    print(
        f"Hyper Beam PLMs: {len(item_plms)} bytes at $84:{HYPER_PLM_VISIBLE:04X} "
        f"(visible ${HYPER_PLM_VISIBLE:04X}, Chozo ${HYPER_PLM_CHOZO:04X}, "
        f"hidden ${HYPER_PLM_HIDDEN:04X})"
    )
    print(
        f"Pickup instruction: {len(pickup_instruction)} bytes at "
        f"$84:{HYPER_PICKUP_INSTRUCTION:04X}"
    )
    print(
        f"Beam-refresh wrapper: {len(refresh_wrapper)} bytes at "
        f"$84:{BEAM_REFRESH_WRAPPER:04X}, hook $90:{BEAM_REFRESH_HOOK_ADDR:04X}"
    )
    print(
        f"Hyper Beam message: id ${HYPER_BEAM_MESSAGE_ID:02X}, "
        f"shared table ${MESSAGE_BOX_TABLE_RELOCATED:04X}, "
        f"tilemap ${HYPER_BEAM_MESSAGE_TILEMAP:04X}"
    )
    print(f"Item graphics: {len(item_gfx)} bytes at $89:{HYPER_ITEM_GFX_ADDR:04X}")
    print(f"IPS size: {len(ips)} bytes")


if __name__ == "__main__":
    main()
