#!/usr/bin/env python3
"""Build the bundled Spazer + Plasma compatibility IPS patch.

Super Metroid normally keeps Spazer ($0004) and Plasma ($0008) mutually
exclusive.  If both bits are forced on, the low beam nibble is $C-$F, but the
native beam tables end at $B.  Those values consequently read adjacent data as
sounds, graphics, projectile records, velocities, or routine pointers.

The patch keeps both inventory bits intact while normalizing every active mixed
projectile to the matching Plasma combination ($8-$B).  It also normalizes
graphics/palette and firing-sound lookups before projectile initialization.
Mixed Spazer/Plasma SBAs remain unavailable, matching all other multi-beam SBA
combinations.  For presentation, mixed shots submit the native Plasma spritemap
twice with symmetric perpendicular offsets.  The separation alternates subtly,
and the native Ice/Plasma palette supplies the blue Ice combinations.
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
    / "spazer_plasma.ips"
)

PAUSE_BANK = 0x82
PLM_BANK = 0x84
PROJECTILE_LOGIC_BANK = 0x90
PROJECTILE_DATA_BANK = 0x93

PAUSE_PLAZMA_SPAZER_CHECK = 0xB068
PICKUP_CLEAR_PLASMA = 0x88C9
PICKUP_CLEAR_SPAZER = 0x88D3

BEAM_TILE_INDEX = 0xAC96
BEAM_PALETTE_SETUP_INDEX = 0xACC8
BEAM_PALETTE_INDEX = 0xACFC
UNCHARGED_SOUND_INDEX = 0xB8D9
CHARGED_SOUND_INDEX = 0xB9D3
SBA_INDEX = 0xCCCD

INITIALIZE_PROJECTILE_TYPE_TEST = 0x800F
DRAW_PROJECTILE_SPRITEMAP = 0x82D7
RUNTIME_HELPERS = 0xF63A
DRAW_HELPER = 0xF61D
ADD_PROJECTILE_SPRITEMAP_TO_OAM = 0x818A4B
NMI_FRAME_COUNTER = 0x05B6
EQUIPPED_BEAMS = 0x09A6
HYPER_BEAM = 0x0A76
SAMUS_PROJECTILE_DIRECTIONS = 0x0C04
SAMUS_PROJECTILE_TYPES = 0x0C18
PROJECTILE_INDEX = 0x0DDE


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


def jsr(addr: int) -> bytes:
    return bytes((0x20,)) + u16(addr)


def jsl(address: int) -> bytes:
    return bytes((0x22,)) + long_le(address)


def append_imm16(code: bytearray, opcode: int, value: int) -> None:
    code.append(opcode)
    code += u16(value)


def append_abs_x(code: bytearray, opcode: int, address: int) -> None:
    code.append(opcode)
    code += u16(address)


def append_abs(code: bytearray, opcode: int, address: int) -> None:
    code.append(opcode)
    code += u16(address)


def append_dp(code: bytearray, opcode: int, address: int) -> None:
    code += bytes((opcode, address))


def append_stack_relative(code: bytearray, opcode: int, offset: int) -> None:
    code += bytes((opcode, offset))


def append_long_x_placeholder(code: bytearray, opcode: int) -> int:
    code += bytes((opcode, 0x00, 0x00, 0x00))
    return len(code) - 3


def resolve_long(code: bytearray, operand_index: int, address: int) -> None:
    code[operand_index : operand_index + 3] = long_le(address)


def append_branch(code: bytearray, opcode: int) -> int:
    code += bytes((opcode, 0x00))
    return len(code) - 1


def resolve_branch(code: bytearray, operand_index: int, target: int) -> None:
    displacement = target - (operand_index + 1)
    if displacement not in range(-128, 128):
        raise ValueError(f"branch displacement is out of range: {displacement}")
    code[operand_index] = displacement & 0xFF


def normalize_beam_index(value: int) -> int:
    """Map mixed Spazer/Plasma indices C-F to Plasma indices 8-B."""
    index = value & 0x000F
    return index & 0x000B if index >= 0x000C else index


def normalize_sba_index(value: int) -> int:
    """Mixed Spazer/Plasma combinations dispatch through the no-SBA entry."""
    index = value & 0x000F
    return 0 if index >= 0x000C else index


def build_index_normalizer() -> bytes:
    # A &= $000F
    # if A >= $000C: A &= $000B  ; clear Spazer, retain Plasma/Ice/Wave
    # RTS
    code = bytearray()
    append_imm16(code, 0x29, 0x000F)  # AND #$000F
    append_imm16(code, 0xC9, 0x000C)  # CMP #$000C
    below_mixed = append_branch(code, 0x90)  # BCC .return
    append_imm16(code, 0x29, 0x000B)  # AND #$000B
    return_offset = len(code)
    code.append(0x60)  # RTS
    resolve_branch(code, below_mixed, return_offset)
    return bytes(code)


def build_sba_normalizer() -> bytes:
    # A &= $000F
    # if A >= $000C: A = 0       ; cost zero, clear-carry SBA entry
    # RTS
    code = bytearray()
    append_imm16(code, 0x29, 0x000F)  # AND #$000F
    append_imm16(code, 0xC9, 0x000C)  # CMP #$000C
    below_mixed = append_branch(code, 0x90)  # BCC .return
    append_imm16(code, 0xA9, 0x0000)  # LDA #$0000
    return_offset = len(code)
    code.append(0x60)  # RTS
    resolve_branch(code, below_mixed, return_offset)
    return bytes(code)


def build_projectile_type_normalizer() -> bytes:
    # Replaces the native LDA SamusProjectile_Types,X / BIT #$0F00 pair.
    # Non-beams return with the original A and non-zero BIT result.  Beams are
    # normalized in WRAM, reloaded, and return with a zero BIT result so the
    # native BNE .notBeam immediately after the hook retains its exact contract.
    code = bytearray()
    append_abs_x(code, 0xBD, SAMUS_PROJECTILE_TYPES)  # LDA types,X
    append_imm16(code, 0x89, 0x0F00)  # BIT #$0F00
    non_beam = append_branch(code, 0xD0)  # BNE .return
    append_imm16(code, 0x29, 0x000F)  # AND #$000F
    append_imm16(code, 0xC9, 0x000C)  # CMP #$000C
    unchanged_beam = append_branch(code, 0x90)  # BCC .reload
    append_abs_x(code, 0xBD, SAMUS_PROJECTILE_TYPES)  # LDA types,X
    append_imm16(code, 0x29, 0xFFFB)  # AND #$FFFB
    append_abs_x(code, 0x9D, SAMUS_PROJECTILE_TYPES)  # STA types,X
    reload_offset = len(code)
    append_abs_x(code, 0xBD, SAMUS_PROJECTILE_TYPES)  # LDA types,X
    append_imm16(code, 0x89, 0x0F00)  # BIT #$0F00
    return_offset = len(code)
    code.append(0x6B)  # RTL
    resolve_branch(code, non_beam, return_offset)
    resolve_branch(code, unchanged_beam, reload_offset)
    return bytes(code)


def build_double_draw_helper(address: int) -> bytes:
    """Draw a mixed beam as two native Plasma lobes around its real origin."""
    code = bytearray()

    # Only a normalized Plasma beam fired while both equipment bits remain on
    # receives the double draw. Hyper Beam has its own presentation path.
    append_abs_x(code, 0xBD, SAMUS_PROJECTILE_TYPES)  # LDA types,X
    append_imm16(code, 0x29, 0x0F08)  # AND #$0F08
    append_imm16(code, 0xC9, 0x0008)  # CMP #$0008
    not_plasma_beam = append_branch(code, 0xD0)  # BNE .single
    append_abs(code, 0xAD, HYPER_BEAM)  # LDA HyperBeam
    hyper = append_branch(code, 0xD0)  # BNE .single
    append_abs(code, 0xAD, EQUIPPED_BEAMS)  # LDA EquippedBeams
    append_imm16(code, 0x29, 0x000C)  # AND #$000C
    append_imm16(code, 0xC9, 0x000C)  # CMP #$000C
    mixed = append_branch(code, 0xF0)  # BEQ .double

    single_offset = len(code)
    code += jsl(ADD_PROJECTILE_SPRITEMAP_TO_OAM)
    code.append(0x6B)  # RTL

    double_offset = len(code)
    resolve_branch(code, not_plasma_beam, single_offset)
    resolve_branch(code, hyper, single_offset)
    resolve_branch(code, mixed, double_offset)

    # Keep the caller's screen origin under the two calls. PHX temporarily
    # shifts the stack-relative origin words from 1/3,S to 3/5,S.
    append_dp(code, 0xA5, 0x12)  # LDA $12
    code.append(0x48)  # PHA
    append_dp(code, 0xA5, 0x14)  # LDA $14
    code.append(0x48)  # PHA

    table_references: list[tuple[int, str]] = []
    for lobe in range(2):
        if lobe:
            append_abs_x(code, 0xAE, PROJECTILE_INDEX)  # LDX ProjectileIndex
        code.append(0xDA)  # PHX
        append_abs_x(code, 0xBD, SAMUS_PROJECTILE_DIRECTIONS)  # LDA directions,X
        append_imm16(code, 0x29, 0x000F)  # AND #$000F
        code.append(0x0A)  # ASL A (word table index)
        code.append(0xAA)  # TAX
        append_abs(code, 0xAD, NMI_FRAME_COUNTER)  # LDA NMI_FrameCounter
        append_imm16(code, 0x89, 0x0004)  # BIT #$0004
        narrow_phase = append_branch(code, 0xF0)  # BEQ .phaseSelected
        code.append(0x8A)  # TXA
        code.append(0x18)  # CLC
        append_imm16(code, 0x69, 0x0014)  # ADC #20 (second ten-word phase)
        code.append(0xAA)  # TAX
        resolve_branch(code, narrow_phase, len(code))

        x_ref = append_long_x_placeholder(code, 0xBF)  # LDA.L xOffsets,X
        table_references.append((x_ref, "x"))
        if lobe:
            append_imm16(code, 0x49, 0xFFFF)  # EOR #$FFFF
            code.append(0x1A)  # INC A
        code.append(0x18)  # CLC
        append_stack_relative(code, 0x63, 0x03)  # ADC 3,S (original X origin)
        append_dp(code, 0x85, 0x14)  # STA $14

        y_ref = append_long_x_placeholder(code, 0xBF)  # LDA.L yOffsets,X
        table_references.append((y_ref, "y"))
        if lobe:
            append_imm16(code, 0x49, 0xFFFF)  # EOR #$FFFF
            code.append(0x1A)  # INC A
        code.append(0x18)  # CLC
        append_stack_relative(code, 0x63, 0x05)  # ADC 5,S (original Y origin)
        append_dp(code, 0x85, 0x12)  # STA $12
        code.append(0xFA)  # PLX
        code += jsl(ADD_PROJECTILE_SPRITEMAP_TO_OAM)

    code.append(0x68)  # PLA
    append_dp(code, 0x85, 0x14)  # STA $14
    code.append(0x68)  # PLA
    append_dp(code, 0x85, 0x12)  # STA $12
    code.append(0x6B)  # RTL

    # Directions: up, up-right, right, down-right, down, down-left-facing,
    # down-left, left, up-left, up-left-facing. Phase two widens each pair.
    x_offsets = (-4, -3, 0, -3, -4, -4, -3, 0, -3, -4,
                 -6, -4, 0, -4, -6, -6, -4, 0, -4, -6)
    y_offsets = (0, -3, -4, 3, 0, 0, -3, -4, 3, 0,
                 0, -4, -6, 4, 0, 0, -4, -6, 4, 0)
    x_table_address = address + len(code)
    for value in x_offsets:
        code += u16(value)
    y_table_address = address + len(code)
    for value in y_offsets:
        code += u16(value)
    for operand, table in table_references:
        resolve_long(code, operand, (PROJECTILE_DATA_BANK << 16) | (x_table_address if table == "x" else y_table_address))

    assert len(x_offsets) == len(y_offsets) == 20
    return bytes(code)


def build_runtime_helpers() -> tuple[bytes, int, int]:
    index_normalizer = build_index_normalizer()
    sba_addr = RUNTIME_HELPERS + len(index_normalizer)
    sba_normalizer = build_sba_normalizer()
    projectile_addr = sba_addr + len(sba_normalizer)
    projectile_normalizer = build_projectile_type_normalizer()
    payload = index_normalizer + sba_normalizer + projectile_normalizer

    assert len(index_normalizer) == 12
    assert len(sba_normalizer) == 12
    assert len(projectile_normalizer) == 32
    assert len(payload) == 56
    assert [normalize_beam_index(i) for i in range(16)] == list(range(12)) + [8, 9, 10, 11]
    assert [normalize_sba_index(i) for i in range(16)] == list(range(12)) + [0, 0, 0, 0]
    return payload, sba_addr, projectile_addr


def make_ips(records: list[tuple[int, bytes]]) -> bytes:
    out = bytearray(b"PATCH")
    previous_end = -1
    for offset, data in sorted(records):
        if not data:
            continue
        if offset < 0 or offset > 0xFFFFFF:
            raise ValueError(f"IPS offset out of range: {offset:#x}")
        if len(data) > 0xFFFF:
            raise ValueError(f"IPS record too large at {offset:#x}: {len(data)} bytes")
        if offset < previous_end:
            raise ValueError(f"overlapping IPS record at {offset:#x}")
        out += u24_be(offset)
        out += len(data).to_bytes(2, "big")
        out += data
        previous_end = offset + len(data)
    out += b"EOF"
    return bytes(out)


def apply_records(rom: bytearray, records: list[tuple[int, bytes]]) -> None:
    for offset, data in records:
        rom[offset : offset + len(data)] = data


def main() -> None:
    base = BASE_ROM.read_bytes()
    helpers, sba_normalizer_addr, projectile_normalizer_addr = build_runtime_helpers()
    draw_helper = build_double_draw_helper(DRAW_HELPER)

    records = [
        (lorom_pc(PAUSE_BANK, PAUSE_PLAZMA_SPAZER_CHECK), bytes((0x60,))),
        (lorom_pc(PLM_BANK, PICKUP_CLEAR_PLASMA), bytes((0xEA, 0xEA, 0xEA))),
        (lorom_pc(PLM_BANK, PICKUP_CLEAR_SPAZER), bytes((0xEA, 0xEA, 0xEA))),
        (lorom_pc(PROJECTILE_LOGIC_BANK, BEAM_TILE_INDEX), jsr(RUNTIME_HELPERS)),
        (lorom_pc(PROJECTILE_LOGIC_BANK, BEAM_PALETTE_SETUP_INDEX), jsr(RUNTIME_HELPERS)),
        (lorom_pc(PROJECTILE_LOGIC_BANK, BEAM_PALETTE_INDEX), jsr(RUNTIME_HELPERS)),
        (lorom_pc(PROJECTILE_LOGIC_BANK, UNCHARGED_SOUND_INDEX), jsr(RUNTIME_HELPERS)),
        (lorom_pc(PROJECTILE_LOGIC_BANK, CHARGED_SOUND_INDEX), jsr(RUNTIME_HELPERS)),
        (lorom_pc(PROJECTILE_LOGIC_BANK, SBA_INDEX), jsr(sba_normalizer_addr)),
        (lorom_pc(PROJECTILE_LOGIC_BANK, RUNTIME_HELPERS), helpers),
        (
            lorom_pc(PROJECTILE_DATA_BANK, INITIALIZE_PROJECTILE_TYPE_TEST),
            jsl((PROJECTILE_LOGIC_BANK << 16) | projectile_normalizer_addr) + bytes((0xEA, 0xEA)),
        ),
        (
            lorom_pc(PROJECTILE_DATA_BANK, DRAW_PROJECTILE_SPRITEMAP),
            jsl((PROJECTILE_DATA_BANK << 16) | DRAW_HELPER),
        ),
        (lorom_pc(PROJECTILE_DATA_BANK, DRAW_HELPER), draw_helper),
    ]

    expected_originals = {
        lorom_pc(PAUSE_BANK, PAUSE_PLAZMA_SPAZER_CHECK): bytes((0x08,)),
        lorom_pc(PLM_BANK, PICKUP_CLEAR_PLASMA): bytes((0x1C, 0xA6, 0x09)),
        lorom_pc(PLM_BANK, PICKUP_CLEAR_SPAZER): bytes((0x1C, 0xA6, 0x09)),
        lorom_pc(PROJECTILE_LOGIC_BANK, BEAM_TILE_INDEX): bytes((0x29, 0xFF, 0x0F)),
        lorom_pc(PROJECTILE_LOGIC_BANK, BEAM_PALETTE_SETUP_INDEX): bytes((0x29, 0xFF, 0x0F)),
        lorom_pc(PROJECTILE_LOGIC_BANK, BEAM_PALETTE_INDEX): bytes((0x29, 0xFF, 0x0F)),
        lorom_pc(PROJECTILE_LOGIC_BANK, UNCHARGED_SOUND_INDEX): bytes((0x29, 0x0F, 0x00)),
        lorom_pc(PROJECTILE_LOGIC_BANK, CHARGED_SOUND_INDEX): bytes((0x29, 0x0F, 0x00)),
        lorom_pc(PROJECTILE_LOGIC_BANK, SBA_INDEX): bytes((0x29, 0x0F, 0x00)),
        lorom_pc(PROJECTILE_LOGIC_BANK, RUNTIME_HELPERS): b"\xFF" * len(helpers),
        lorom_pc(PROJECTILE_DATA_BANK, INITIALIZE_PROJECTILE_TYPE_TEST): bytes(
            (0xBD, 0x18, 0x0C, 0x89, 0x00, 0x0F)
        ),
        lorom_pc(PROJECTILE_DATA_BANK, DRAW_PROJECTILE_SPRITEMAP): jsl(ADD_PROJECTILE_SPRITEMAP_TO_OAM),
        lorom_pc(PROJECTILE_DATA_BANK, DRAW_HELPER): b"\xFF" * len(draw_helper),
    }
    for offset, expected in expected_originals.items():
        actual = base[offset : offset + len(expected)]
        if actual != expected:
            raise SystemExit(
                f"Unexpected original bytes at PC {offset:#07x}: "
                f"expected {expected.hex(' ')}, got {actual.hex(' ')}"
            )

    patched = bytearray(base)
    apply_records(patched, records)
    for offset, data in records:
        actual = patched[offset : offset + len(data)]
        if actual != data:
            raise SystemExit(f"Patch verification failed at PC {offset:#07x}")

    OUT_IPS.write_bytes(make_ips(records))
    print(f"Wrote {OUT_IPS.relative_to(ROOT)}")
    print(
        f"Runtime helpers: {len(helpers)} bytes at "
        f"${PROJECTILE_LOGIC_BANK:02X}:{RUNTIME_HELPERS:04X}-"
        f"${RUNTIME_HELPERS + len(helpers) - 1:04X}"
    )
    print(
        f"Dual-lobe drawing helper: {len(draw_helper)} bytes at "
        f"${PROJECTILE_DATA_BANK:02X}:{DRAW_HELPER:04X}-"
        f"${DRAW_HELPER + len(draw_helper) - 1:04X}"
    )
    print(f"IPS size: {OUT_IPS.stat().st_size} bytes")


if __name__ == "__main__":
    main()
