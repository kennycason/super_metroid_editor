#!/usr/bin/env python3
"""Build the bundled Echolocation Beam IPS patch.

Vanilla Super Metroid queues library-2 sound $0C when Kill_Projectile destroys
a beam. That cue is quiet, is shared by enemy and tile impacts, and never runs
for Wave or Hyper Beam because those projectiles pass through solid tiles.

This patch redirects all four beam block-reaction calls through small same-bank
wrappers. The wrappers queue library-1 sound $36 (map click), a high-pitched
two-tick cue, whenever a block reaction reports contact with a collidable tile.
Wave and Hyper penetration is deliberately preserved.

For ordinary beam tile collisions, Kill_Projectile would subsequently queue the
original $0C cue as well. Two additional call-site hooks invoke it with sounds
temporarily disabled, avoiding a doubled cue without changing the vanilla $0C
sound used when an ordinary beam hits an enemy.

Samus landing, hitting a ceiling, or entering the ran-into-a-wall pose queues
library-1 sound $37 (selection click), a slightly lower four-tick body-contact
cue. Vanilla landing sounds remain intact underneath it.

Reference labels/addresses come from:
  ~/code/super_metroid/sm_disassembly/src/bank_91.asm
  ~/code/super_metroid/sm_disassembly/src/bank_94.asm
  ~/code/super_metroid/sm_disassembly/src/bank_93.asm
The matching C reconstruction is in:
  ~/code/super_metroid/sm/src/sm_94.c
SPC sound definitions come from:
  ~/code/super_metroid/SM-SPC/vanilla/sound library 1.asm
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
    / "echolocation_beam.ips"
)

SAMUS_BANK = 0x91
PROJECTILE_BANK = 0x94

RAN_INTO_WALL_RETURN = 0xEB6F
LANDED_POINTER = 0xEFD2
HIT_CEILING_POINTER = 0xEFD8
SAMUS_WRAPPER_ADDR = 0xFFEE
SOLID_VERTICAL_COLLISION_HIT_CEILING = 0xEFDF
SOLID_VERTICAL_COLLISION_LANDED = 0xF010
SAMUS_X_SPEED_KILLED_FLAG = 0x0DCE

HORIZONTAL_NO_WAVE_REACTION_CALL = 0xA2AA
HORIZONTAL_NO_WAVE_KILL_CALL = 0xA2C3
VERTICAL_NO_WAVE_REACTION_CALL = 0xA339
VERTICAL_NO_WAVE_KILL_CALL = 0xA34B
HORIZONTAL_WAVE_REACTION_CALL = 0xA3D0
VERTICAL_WAVE_REACTION_CALL = 0xA462
WRAPPER_ADDR = 0xB19F

BLOCK_SHOT_REACTION_HORIZONTAL = 0xA1B5
BLOCK_SHOT_REACTION_VERTICAL = 0xA1D6
QUEUE_SOUND_LIB1_MAX15 = 0x809021
KILL_PROJECTILE = 0x90AE06
DISABLE_SOUNDS = 0x7E05F5
BEAM_CONTACT_SOUND = 0x0036
BODY_CONTACT_SOUND = 0x0037


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


def jsl(addr: int) -> bytes:
    return bytes((0x22,)) + long_le(addr)


def build_collision_wrapper(original_reaction: int) -> bytes:
    # JSR original_reaction
    # BCC .no_collision
    # PHA
    # LDA #$0036
    # JSL $80:9021       ; Queue sound, library 1, max 15
    # PLA
    # SEC                ; preserve original collision result
    # RTS
    # .no_collision
    # CLC
    # RTS
    return (
        jsr(original_reaction)
        + bytes((0x90, 0x0B, 0x48, 0xA9))
        + u16(BEAM_CONTACT_SOUND)
        + jsl(QUEUE_SOUND_LIB1_MAX15)
        + bytes((0x68, 0x38, 0x60, 0x18, 0x60))
    )


def build_kill_without_sound_wrapper() -> bytes:
    # Preserve the global sound-disable word, suppress Kill_Projectile's $0C
    # cue synchronously, and restore the word before returning.
    return (
        bytes((0x08, 0x48, 0xAF))
        + long_le(DISABLE_SOUNDS)
        + bytes((0x48, 0xA9, 0x01, 0x00, 0x8F))
        + long_le(DISABLE_SOUNDS)
        + jsl(KILL_PROJECTILE)
        + bytes((0x68, 0x8F))
        + long_le(DISABLE_SOUNDS)
        + bytes((0x68, 0x28, 0x6B))
    )


def build_body_sound_wrapper() -> bytes:
    # PHA / LDA #$0037 / JSL QueueSound_Lib1_Max15 / PLA / RTL
    return bytes((0x48, 0xA9)) + u16(BODY_CONTACT_SOUND) + jsl(QUEUE_SOUND_LIB1_MAX15) + bytes((0x68, 0x6B))


def build_wall_contact_wrapper() -> bytes:
    # Replaces STZ SamusXSpeedKilledDueToCollisionFlag / SEC / RTS at $91:EB6F.
    # Preserve the caller's accumulator and non-carry flags around the sound cue.
    return (
        bytes((0x08, 0x48, 0xA9))
        + u16(BODY_CONTACT_SOUND)
        + jsl(QUEUE_SOUND_LIB1_MAX15)
        + bytes((0x68, 0x28, 0x9C))
        + u16(SAMUS_X_SPEED_KILLED_FLAG)
        + bytes((0x38, 0x6B))
    )


def build_vertical_contact_dispatch(body_sound_addr: int) -> bytes:
    # The collision dispatcher enters with X = collision result * 2. Both the
    # landed (X=2) and hit-ceiling (X=8) table entries point here. Queue the
    # body cue, then tail-call the original handler so its RTS returns normally.
    return (
        jsl(body_sound_addr)
        + bytes((0xE0, 0x02, 0x00, 0xF0, 0x03, 0x4C))
        + u16(SOLID_VERTICAL_COLLISION_HIT_CEILING)
        + bytes((0x4C,))
        + u16(SOLID_VERTICAL_COLLISION_LANDED)
    )


def make_ips(records: list[tuple[int, bytes]]) -> bytes:
    out = bytearray(b"PATCH")
    for offset, data in records:
        if not data:
            continue
        if offset < 0 or offset > 0xFFFFFF:
            raise ValueError(f"IPS offset out of range: {offset:#x}")
        if len(data) > 0xFFFF:
            raise ValueError(f"IPS record too large at {offset:#x}: {len(data)} bytes")
        out += u24_be(offset)
        out += len(data).to_bytes(2, "big")
        out += data
    out += b"EOF"
    return bytes(out)


def apply_records(rom: bytearray, records: list[tuple[int, bytes]]) -> None:
    for offset, data in records:
        rom[offset : offset + len(data)] = data


def main() -> None:
    base = BASE_ROM.read_bytes()
    horizontal_wrapper = build_collision_wrapper(BLOCK_SHOT_REACTION_HORIZONTAL)
    vertical_wrapper_addr = WRAPPER_ADDR + len(horizontal_wrapper)
    vertical_wrapper = build_collision_wrapper(BLOCK_SHOT_REACTION_VERTICAL)
    kill_wrapper_addr = vertical_wrapper_addr + len(vertical_wrapper)
    kill_wrapper = build_kill_without_sound_wrapper()
    body_sound_wrapper_addr = kill_wrapper_addr + len(kill_wrapper)
    body_sound_wrapper = build_body_sound_wrapper()
    wall_contact_wrapper_addr = body_sound_wrapper_addr + len(body_sound_wrapper)
    wall_contact_wrapper = build_wall_contact_wrapper()
    projectile_payload = (
        horizontal_wrapper
        + vertical_wrapper
        + kill_wrapper
        + body_sound_wrapper
        + wall_contact_wrapper
    )
    vertical_contact_dispatch = build_vertical_contact_dispatch(
        (PROJECTILE_BANK << 16) | body_sound_wrapper_addr
    )

    records = [
        (
            lorom_pc(SAMUS_BANK, RAN_INTO_WALL_RETURN),
            jsl((PROJECTILE_BANK << 16) | wall_contact_wrapper_addr) + bytes((0x60,)),
        ),
        (lorom_pc(SAMUS_BANK, LANDED_POINTER), u16(SAMUS_WRAPPER_ADDR)),
        (lorom_pc(SAMUS_BANK, HIT_CEILING_POINTER), u16(SAMUS_WRAPPER_ADDR)),
        (lorom_pc(SAMUS_BANK, SAMUS_WRAPPER_ADDR), vertical_contact_dispatch),
        (lorom_pc(PROJECTILE_BANK, HORIZONTAL_NO_WAVE_REACTION_CALL), jsr(WRAPPER_ADDR)),
        (
            lorom_pc(PROJECTILE_BANK, HORIZONTAL_NO_WAVE_KILL_CALL),
            jsl((PROJECTILE_BANK << 16) | kill_wrapper_addr),
        ),
        (lorom_pc(PROJECTILE_BANK, VERTICAL_NO_WAVE_REACTION_CALL), jsr(vertical_wrapper_addr)),
        (
            lorom_pc(PROJECTILE_BANK, VERTICAL_NO_WAVE_KILL_CALL),
            jsl((PROJECTILE_BANK << 16) | kill_wrapper_addr),
        ),
        (lorom_pc(PROJECTILE_BANK, HORIZONTAL_WAVE_REACTION_CALL), jsr(WRAPPER_ADDR)),
        (lorom_pc(PROJECTILE_BANK, VERTICAL_WAVE_REACTION_CALL), jsr(vertical_wrapper_addr)),
        (lorom_pc(PROJECTILE_BANK, WRAPPER_ADDR), projectile_payload),
    ]

    expected_originals = {
        lorom_pc(SAMUS_BANK, RAN_INTO_WALL_RETURN): bytes((0x9C, 0xCE, 0x0D, 0x38, 0x60)),
        lorom_pc(SAMUS_BANK, LANDED_POINTER): u16(SOLID_VERTICAL_COLLISION_LANDED),
        lorom_pc(SAMUS_BANK, HIT_CEILING_POINTER): u16(SOLID_VERTICAL_COLLISION_HIT_CEILING),
        lorom_pc(SAMUS_BANK, SAMUS_WRAPPER_ADDR): b"\xFF" * len(vertical_contact_dispatch),
        lorom_pc(PROJECTILE_BANK, HORIZONTAL_NO_WAVE_REACTION_CALL): jsr(BLOCK_SHOT_REACTION_HORIZONTAL),
        lorom_pc(PROJECTILE_BANK, HORIZONTAL_NO_WAVE_KILL_CALL): jsl(KILL_PROJECTILE),
        lorom_pc(PROJECTILE_BANK, VERTICAL_NO_WAVE_REACTION_CALL): jsr(BLOCK_SHOT_REACTION_VERTICAL),
        lorom_pc(PROJECTILE_BANK, VERTICAL_NO_WAVE_KILL_CALL): jsl(KILL_PROJECTILE),
        lorom_pc(PROJECTILE_BANK, HORIZONTAL_WAVE_REACTION_CALL): jsr(BLOCK_SHOT_REACTION_HORIZONTAL),
        lorom_pc(PROJECTILE_BANK, VERTICAL_WAVE_REACTION_CALL): jsr(BLOCK_SHOT_REACTION_VERTICAL),
        lorom_pc(PROJECTILE_BANK, WRAPPER_ADDR): b"\xFF" * len(projectile_payload),
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
        f"Projectile/body wrappers: {len(projectile_payload)} bytes at "
        f"${PROJECTILE_BANK:02X}:{WRAPPER_ADDR:04X}-"
        f"${WRAPPER_ADDR + len(projectile_payload) - 1:04X}"
    )
    print(
        f"Vertical-contact dispatch: {len(vertical_contact_dispatch)} bytes at "
        f"${SAMUS_BANK:02X}:{SAMUS_WRAPPER_ADDR:04X}-"
        f"${SAMUS_WRAPPER_ADDR + len(vertical_contact_dispatch) - 1:04X}"
    )
    print(f"IPS size: {OUT_IPS.stat().st_size} bytes")


if __name__ == "__main__":
    main()
