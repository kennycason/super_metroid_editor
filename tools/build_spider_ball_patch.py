#!/usr/bin/env python3
"""Build the bundled Spider Ball prototype IPS patch.

This is intentionally self-contained: the local Super Metroid disassembly has
an asar binary, but it is not runnable on macOS arm64. The small encoder below
emits the exact 65816 instructions used by this patch and writes IPS records.

The behavior is inspired by Metroid II's spider ball, but adapted to Super
Metroid's movement engine:

* Vanilla morph movement owns floors, slopes, falling, and mockball.
* Spider attaches only by pressing into a side wall.
* Wall movement uses up/down. Ceiling movement uses left/right.
* Small bounded corner nudges help negotiate ledges and ceilings.
* Jump releases the latch.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE_ROM = ROOT / "test-resources" / "Super Metroid (JU) [!].smc"
OUT_IPS = ROOT / "shared" / "src" / "commonMain" / "resources" / "patches" / "spider_ball.ips"
CODE_ADDR = 0xF800
POSE_GUARD_ADDR = 0x80BE


def lorom_pc(bank: int, addr: int) -> int:
    if addr < 0x8000:
        raise ValueError(f"LoROM address must be in upper half of bank: ${bank:02X}:{addr:04X}")
    return (bank & 0x7F) * 0x8000 + (addr & 0x7FFF)


def u16(value: int) -> bytes:
    return bytes((value & 0xFF, (value >> 8) & 0xFF))


def u24(value: int) -> bytes:
    return bytes(((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF))


def long_le(value: int) -> bytes:
    return bytes((value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF))


@dataclass
class Fixup:
    offset: int
    label: str
    kind: str


class Bank90Writer:
    def __init__(self, start_addr: int) -> None:
        self.start_addr = start_addr
        self.data = bytearray()
        self.labels: dict[str, int] = {}
        self.fixups: list[Fixup] = []

    @property
    def addr(self) -> int:
        return self.start_addr + len(self.data)

    def label(self, name: str) -> None:
        if name in self.labels:
            raise ValueError(f"duplicate label {name}")
        self.labels[name] = self.addr

    def emit(self, *values: int) -> None:
        self.data.extend(v & 0xFF for v in values)

    def emit_word(self, value: int) -> None:
        self.data.extend(u16(value))

    def ref16(self, label: str, kind: str = "abs") -> None:
        self.fixups.append(Fixup(len(self.data), label, kind))
        self.emit_word(0)

    def branch(self, opcode: int, label: str) -> None:
        self.emit(opcode)
        self.fixups.append(Fixup(len(self.data), label, "branch"))
        self.emit(0)

    def branch_long(self, opcode: int, label: str) -> None:
        opposite = {
            0x90: 0xB0,  # BCC -> BCS
            0xB0: 0x90,  # BCS -> BCC
            0xD0: 0xF0,  # BNE -> BEQ
            0xF0: 0xD0,  # BEQ -> BNE
        }
        if opcode not in opposite:
            raise ValueError(f"unsupported long branch opcode {opcode:#x}")
        skip = f"__long_branch_skip_{len(self.fixups)}_{len(self.data)}"
        self.branch(opposite[opcode], skip)
        self.jmp_label(label)
        self.label(skip)

    def resolve(self) -> bytes:
        for fixup in self.fixups:
            target = self.labels[fixup.label]
            if fixup.kind == "branch":
                branch_operand_addr = self.start_addr + fixup.offset
                rel = target - (branch_operand_addr + 1)
                if not -128 <= rel <= 127:
                    raise ValueError(f"branch to {fixup.label} out of range: {rel}")
                self.data[fixup.offset] = rel & 0xFF
            elif fixup.kind == "abs":
                self.data[fixup.offset : fixup.offset + 2] = u16(target)
            else:
                raise ValueError(f"unknown fixup kind {fixup.kind}")
        return bytes(self.data)

    # 65816 helpers used by this patch. The code runs with A/X/Y in 16-bit mode.
    def php(self) -> None: self.emit(0x08)
    def plp(self) -> None: self.emit(0x28)
    def rts(self) -> None: self.emit(0x60)
    def pha(self) -> None: self.emit(0x48)
    def pla(self) -> None: self.emit(0x68)
    def rep_30(self) -> None: self.emit(0xC2, 0x30)
    def lda_imm(self, value: int) -> None: self.emit(0xA9); self.emit_word(value)
    def lda_abs(self, addr: int) -> None: self.emit(0xAD); self.emit_word(addr)
    def lda_label_x(self, label: str) -> None: self.emit(0xBD); self.ref16(label)
    def lda_dp(self, addr: int) -> None: self.emit(0xA5, addr)
    def ldx_imm(self, value: int) -> None: self.emit(0xA2); self.emit_word(value)
    def sta_abs(self, addr: int) -> None: self.emit(0x8D); self.emit_word(addr)
    def sta_dp(self, addr: int) -> None: self.emit(0x85, addr)
    def stz_abs(self, addr: int) -> None: self.emit(0x9C); self.emit_word(addr)
    def stz_dp(self, addr: int) -> None: self.emit(0x64, addr)
    def and_imm(self, value: int) -> None: self.emit(0x29); self.emit_word(value)
    def and_abs(self, addr: int) -> None: self.emit(0x2D); self.emit_word(addr)
    def ora_imm(self, value: int) -> None: self.emit(0x09); self.emit_word(value)
    def cmp_imm(self, value: int) -> None: self.emit(0xC9); self.emit_word(value)
    def asl_a(self) -> None: self.emit(0x0A)
    def tax(self) -> None: self.emit(0xAA)
    def clc(self) -> None: self.emit(0x18)
    def sec(self) -> None: self.emit(0x38)
    def jsr_abs(self, addr: int) -> None: self.emit(0x20); self.emit_word(addr)
    def jsl_long(self, addr: int) -> None: self.emit(0x22); self.data.extend(long_le(addr))
    def jmp_abs(self, addr: int) -> None: self.emit(0x4C); self.emit_word(addr)
    def jsr_label(self, label: str) -> None: self.emit(0x20); self.ref16(label)
    def jmp_label(self, label: str) -> None: self.emit(0x4C); self.ref16(label)
    def inc_abs(self, addr: int) -> None: self.emit(0xEE); self.emit_word(addr)
    def dec_abs(self, addr: int) -> None: self.emit(0xCE); self.emit_word(addr)


# Direct page / WRAM addresses.
DP_TEMP_12 = 0x12
DP_TEMP_14 = 0x14
DP_CONTROLLER_1_INPUT = 0x8B
DP_CONTROLLER_1_NEW = 0x8F

COLLECTED_ITEMS = 0x09A4
UP_BINDING = 0x09AA
DOWN_BINDING = 0x09AC
LEFT_BINDING = 0x09AE
RIGHT_BINDING = 0x09B0
JUMP_BINDING = 0x09B4
MOVEMENT_TYPE = 0x0A1F
SAMUS_X_POSITION = 0x0AF6
SAMUS_X_SUBPOSITION = 0x0AF8
SAMUS_Y_POSITION = 0x0AFA
SAMUS_Y_SUBPOSITION = 0x0AFC
COLLISION_MOVEMENT_DIRECTION = 0x0B02
SPIDER_ACTIVE = 0x0B1C
SPIDER_STATE = 0x0B1E
MORPH_BALL_BOUNCE_STATE = 0x0B20
SAMUS_IS_FALLING_FLAG = 0x0B22
SPIDER_CONTACT = 0x0B28
SPIDER_LAST_CONTACT = 0x0B2A
SAMUS_Y_SUBSPEED = 0x0B2C
SAMUS_Y_SPEED = 0x0B2E
SAMUS_Y_DIRECTION = 0x0B36
SAMUS_X_EXTRA_RUN_SPEED = 0x0B42
SAMUS_X_EXTRA_RUN_SUBSPEED = 0x0B44
SAMUS_X_BASE_SPEED = 0x0B46
SAMUS_X_BASE_SUBSPEED = 0x0B48
SAMUS_X_ACCELERATION_MODE = 0x0B4A
SAMUS_SOLID_VERTICAL_COLLISION_RESULT = 0x0DC6
SAMUS_SOLID_COLLISION_FLAG = 0x0DD0

# Existing collision/alignment routines.
ALIGN_SAMUS_Y_WITH_NON_SQUARE_SLOPE = 0x9487F4
WALL_JUMP_BLOCK_COLLISION_DETECTION = 0x94967F
BLOCK_COLLISION_DUE_TO_CHANGE_OF_POSE_SINGLE_BLOCK = 0x9496E3
ORIG_MORPH_GROUND = 0xA521
ORIG_MORPH_FALLING = 0xA5CA
ORIG_SPRING_GROUND = 0xA69F
ORIG_SPRING_AIR = 0xA6F1
ORIG_SPRING_FALLING = 0xA703

# Existing bank $91 pose input handlers.
ORIG_POSE_MORPH_GROUND = 0x807E
ORIG_POSE_MORPH_FALLING = 0x810A
ORIG_POSE_SPRING_GROUND = 0x814F
ORIG_POSE_SPRING_AIR = 0x8157
ORIG_POSE_SPRING_FALLING = 0x815F

SPIDER_BALL_ITEM_BIT = 0x0010
SPIDER_ACTIVE_MAGIC = 0x5A5A
SPIDER_STATE_WALL_RIGHT = 0x0001
SPIDER_STATE_WALL_LEFT = 0x0002
SPIDER_STATE_CEILING = 0x0004

# Custom Spider Ball item PLMs in bank $84 free space. These mirror the Morph
# Ball item's visible/Chozo/hidden scripts, but the pickup instruction grants
# the Spider Ball bit and points at Spider-specific item graphics/message text.
SPIDER_BALL_PLM_VISIBLE = 0xF200
SPIDER_BALL_PLM_CHOZO = 0xF204
SPIDER_BALL_PLM_HIDDEN = 0xF208
SPIDER_BALL_PLM_LIST_START = 0xF20C

SPIDER_BALL_ITEM_GFX_SOURCE = 0x8700
SPIDER_BALL_ITEM_GFX_ADDR = 0xF000
SPIDER_BALL_ITEM_GFX_SIZE = 0x100

MESSAGE_BOX_TABLE_ORIGINAL = 0x869B
MESSAGE_BOX_TABLE_ORIGINAL_ENTRIES = 29
MESSAGE_BOX_TABLE_RELOCATED = 0x9C00
SPIDER_BALL_MESSAGE_ID = 0x1D
SPIDER_BALL_MESSAGE_TILEMAP = 0x9D00
SPIDER_BALL_MESSAGE_TILEMAP_END = SPIDER_BALL_MESSAGE_TILEMAP + 0x40

MORPH_PLM_SETUP_VISIBLE_CHOZO = 0xEE64
MORPH_PLM_SETUP_HIDDEN = 0xEE8E
MORPH_VISIBLE_LIST_START = 0xE3EF
MORPH_VISIBLE_LIST_END = 0xE41D
MORPH_CHOZO_LIST_START = 0xE89C
MORPH_CHOZO_LIST_END = 0xE8D7
MORPH_HIDDEN_LIST_START = 0xEDCC
MORPH_HIDDEN_LIST_END = 0xEE0D


def emit_probe_state_push(w: Bank90Writer) -> None:
    for addr in (
        SAMUS_SOLID_VERTICAL_COLLISION_RESULT,
        SAMUS_SOLID_COLLISION_FLAG,
        SAMUS_X_POSITION,
        SAMUS_X_SUBPOSITION,
        SAMUS_Y_POSITION,
        SAMUS_Y_SUBPOSITION,
    ):
        w.lda_abs(addr)
        w.pha()


def emit_probe_state_pull(w: Bank90Writer) -> None:
    for addr in (
        SAMUS_Y_SUBPOSITION,
        SAMUS_Y_POSITION,
        SAMUS_X_SUBPOSITION,
        SAMUS_X_POSITION,
        SAMUS_SOLID_COLLISION_FLAG,
        SAMUS_SOLID_VERTICAL_COLLISION_RESULT,
    ):
        w.pla()
        w.sta_abs(addr)


def emit_probe_state_drop(w: Bank90Writer) -> None:
    for _ in range(6):
        w.pla()


def emit_probe(w: Bank90Writer, label: str, step_label: str, contact_bit: int) -> None:
    w.label(label)
    emit_probe_state_push(w)
    w.jsr_label(step_label)
    w.lda_abs(SAMUS_SOLID_COLLISION_FLAG)
    w.branch(0xF0, f"{label}_restore")  # BEQ
    w.lda_abs(SPIDER_CONTACT)
    w.ora_imm(contact_bit)
    w.sta_abs(SPIDER_CONTACT)
    w.label(f"{label}_restore")
    emit_probe_state_pull(w)
    w.rts()


def emit_try_nudge(w: Bank90Writer, label: str, step_labels: tuple[str, ...], contact_mask: int) -> None:
    w.label(label)
    emit_probe_state_push(w)
    for step_label in step_labels:
        w.jsr_label(step_label)
    w.jsr_label("CheckSpiderContact")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(contact_mask)
    w.branch(0xF0, f"{label}Fail")
    emit_probe_state_drop(w)
    w.lda_imm(0x0001)
    w.rts()
    w.label(f"{label}Fail")
    emit_probe_state_pull(w)
    w.lda_imm(0x0000)
    w.rts()


def build_spider_code() -> bytes:
    w = Bank90Writer(start_addr=CODE_ADDR)

    w.label("SpiderBallMovementWrapper")
    w.php()
    w.rep_30()
    w.lda_abs(COLLECTED_ITEMS)
    w.and_imm(SPIDER_BALL_ITEM_BIT)
    w.branch(0xF0, "WrapperInactive")  # BEQ
    w.lda_abs(SPIDER_ACTIVE)
    w.cmp_imm(SPIDER_ACTIVE_MAGIC)
    w.branch(0xF0, "WrapperActive")  # BEQ
    w.jsr_label("CheckSpiderContact")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0001)
    w.branch(0xF0, "WrapperCheckAttachLeft")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(RIGHT_BINDING)
    w.branch(0xF0, "WrapperCheckAttachLeft")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(LEFT_BINDING)
    w.branch(0xD0, "WrapperInactive")
    w.jsr_label("SetSpiderWallRight")
    w.plp()
    w.rts()
    w.label("WrapperCheckAttachLeft")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0002)
    w.branch(0xF0, "WrapperInactive")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(LEFT_BINDING)
    w.branch(0xF0, "WrapperInactive")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(RIGHT_BINDING)
    w.branch(0xD0, "WrapperInactive")
    w.jsr_label("SetSpiderWallLeft")
    w.plp()
    w.rts()
    w.label("WrapperActive")
    w.jsr_label("SpiderBallMovement")
    w.branch(0xB0, "WrapperFallThrough")  # BCS
    w.plp()
    w.rts()
    w.label("WrapperFallThrough")
    w.plp()
    w.jmp_label("DispatchOriginalMovement")
    w.label("WrapperInactive")
    w.plp()
    w.jmp_label("DispatchOriginalMovement")

    w.label("SpiderBallMovement")
    w.lda_dp(DP_CONTROLLER_1_NEW)
    w.and_abs(JUMP_BINDING)
    w.branch(0xF0, "SpiderNoJump")
    w.jsr_label("ReleaseSpider")
    w.sec()
    w.rts()
    w.label("SpiderNoJump")
    w.jsr_label("CheckSpiderContact")
    w.lda_abs(SPIDER_CONTACT)
    w.sta_abs(SPIDER_LAST_CONTACT)
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0007)
    w.branch(0xD0, "SpiderStateDispatch")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch(0xF0, "SpiderLostContact")
    w.jmp_label("SpiderReleaseToOriginal")
    w.label("SpiderLostContact")
    w.jmp_label("HandleLostContact")
    w.label("SpiderStateDispatch")
    w.lda_abs(SPIDER_STATE)
    w.cmp_imm(SPIDER_STATE_WALL_RIGHT)
    w.branch(0xD0, "SpiderDispatchCheckWallLeft")
    w.jmp_label("SpiderWallRight")
    w.label("SpiderDispatchCheckWallLeft")
    w.cmp_imm(SPIDER_STATE_WALL_LEFT)
    w.branch(0xD0, "SpiderDispatchCheckCeiling")
    w.jmp_label("SpiderWallLeft")
    w.label("SpiderDispatchCheckCeiling")
    w.cmp_imm(SPIDER_STATE_CEILING)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("SpiderCeiling")

    w.label("SpiderWallRight")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0001)
    w.branch(0xD0, "WallRightHasWall")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xF0, "WallRightLostWall")
    w.jmp_label("SetSpiderCeilingHandled")
    w.label("WallRightLostWall")
    w.jmp_label("WallRightLostContact")
    w.label("WallRightHasWall")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(UP_BINDING)
    w.branch(0xF0, "WallRightCheckDown")
    w.jsr_label("StepUp")
    w.jsr_label("CheckContactAfterStep")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xD0, "WallRightUpCeiling")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0001)
    w.branch_long(0xD0, "SpiderStayHandled")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("WallRightUpCorner")
    w.label("WallRightUpCeiling")
    w.jmp_label("SetSpiderCeilingHandled")
    w.label("WallRightCheckDown")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(DOWN_BINDING)
    w.branch_long(0xF0, "SpiderStayHandled")
    w.jsr_label("StepDown")
    w.jsr_label("CheckContactAfterStep")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xD0, "WallRightDownCeiling")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0001)
    w.branch_long(0xD0, "SpiderStayHandled")
    w.jmp_label("WallRightDownCorner")
    w.label("WallRightDownCeiling")
    w.jmp_label("SetSpiderCeilingHandled")

    w.label("SpiderWallLeft")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0002)
    w.branch(0xD0, "WallLeftHasWall")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xF0, "WallLeftLostWall")
    w.jmp_label("SetSpiderCeilingHandled")
    w.label("WallLeftLostWall")
    w.jmp_label("WallLeftLostContact")
    w.label("WallLeftHasWall")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(UP_BINDING)
    w.branch(0xF0, "WallLeftCheckDown")
    w.jsr_label("StepUp")
    w.jsr_label("CheckContactAfterStep")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xD0, "WallLeftUpCeiling")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0002)
    w.branch_long(0xD0, "SpiderStayHandled")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("WallLeftUpCorner")
    w.label("WallLeftUpCeiling")
    w.jmp_label("SetSpiderCeilingHandled")
    w.label("WallLeftCheckDown")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(DOWN_BINDING)
    w.branch_long(0xF0, "SpiderStayHandled")
    w.jsr_label("StepDown")
    w.jsr_label("CheckContactAfterStep")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xD0, "WallLeftDownCeiling")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0002)
    w.branch_long(0xD0, "SpiderStayHandled")
    w.jmp_label("WallLeftDownCorner")
    w.label("WallLeftDownCeiling")
    w.jmp_label("SetSpiderCeilingHandled")

    w.label("SpiderCeiling")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch(0xD0, "CeilingHasCeiling")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0001)
    w.branch(0xD0, "CeilingTouchRightWall")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0002)
    w.branch(0xD0, "CeilingTouchLeftWall")
    w.jmp_label("CeilingLostContact")
    w.label("CeilingTouchRightWall")
    w.jmp_label("SetSpiderWallRightHandled")
    w.label("CeilingTouchLeftWall")
    w.jmp_label("SetSpiderWallLeftHandled")
    w.label("CeilingHasCeiling")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(LEFT_BINDING)
    w.branch(0xF0, "CeilingCheckRight")
    w.jsr_label("StepLeft")
    w.jsr_label("CheckContactAfterStep")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch_long(0xD0, "SpiderStayHandled")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0002)
    w.branch(0xD0, "CeilingLeftWall")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("CeilingLeftCorner")
    w.label("CeilingLeftWall")
    w.jmp_label("SetSpiderWallLeftHandled")
    w.label("CeilingCheckRight")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(RIGHT_BINDING)
    w.branch_long(0xF0, "SpiderStayHandled")
    w.jsr_label("StepRight")
    w.jsr_label("CheckContactAfterStep")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0004)
    w.branch_long(0xD0, "SpiderStayHandled")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0001)
    w.branch(0xD0, "CeilingRightWall")
    w.lda_abs(SPIDER_CONTACT)
    w.and_imm(0x0008)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("CeilingRightCorner")
    w.label("CeilingRightWall")
    w.jmp_label("SetSpiderWallRightHandled")

    w.label("HandleLostContact")
    w.lda_abs(SPIDER_STATE)
    w.cmp_imm(SPIDER_STATE_WALL_RIGHT)
    w.branch(0xD0, "LostCheckWallLeft")
    w.jmp_label("WallRightLostContact")
    w.label("LostCheckWallLeft")
    w.cmp_imm(SPIDER_STATE_WALL_LEFT)
    w.branch(0xD0, "LostCheckCeiling")
    w.jmp_label("WallLeftLostContact")
    w.label("LostCheckCeiling")
    w.cmp_imm(SPIDER_STATE_CEILING)
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("CeilingLostContact")

    w.label("WallRightLostContact")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(UP_BINDING)
    w.branch(0xF0, "WallRightLostCheckDown")
    w.jmp_label("WallRightUpCorner")
    w.label("WallRightLostCheckDown")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(DOWN_BINDING)
    w.branch_long(0xF0, "SpiderReleaseToOriginal")
    w.jmp_label("WallRightDownCorner")

    w.label("WallLeftLostContact")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(UP_BINDING)
    w.branch(0xF0, "WallLeftLostCheckDown")
    w.jmp_label("WallLeftUpCorner")
    w.label("WallLeftLostCheckDown")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(DOWN_BINDING)
    w.branch_long(0xF0, "SpiderReleaseToOriginal")
    w.jmp_label("WallLeftDownCorner")

    w.label("CeilingLostContact")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(LEFT_BINDING)
    w.branch(0xF0, "CeilingLostCheckRight")
    w.jmp_label("CeilingLeftCorner")
    w.label("CeilingLostCheckRight")
    w.lda_dp(DP_CONTROLLER_1_INPUT)
    w.and_abs(RIGHT_BINDING)
    w.branch_long(0xF0, "SpiderReleaseToOriginal")
    w.jmp_label("CeilingRightCorner")

    w.label("WallRightUpCorner")
    w.jsr_label("TryNudgeRightForFloor")
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jsr_label("TryNudgeLeftForCeiling")
    w.branch(0xD0, "SetSpiderCeilingHandled")
    w.jmp_label("SpiderReleaseToOriginal")

    w.label("WallLeftUpCorner")
    w.jsr_label("TryNudgeLeftForFloor")
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jsr_label("TryNudgeRightForCeiling")
    w.branch(0xD0, "SetSpiderCeilingHandled")
    w.jmp_label("SpiderReleaseToOriginal")

    w.label("WallRightDownCorner")
    w.jsr_label("TryNudgeLeftForCeiling")
    w.branch(0xD0, "SetSpiderCeilingHandled")
    w.jsr_label("TryNudgeRightForFloor")
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("SpiderReleaseToOriginal")

    w.label("WallLeftDownCorner")
    w.jsr_label("TryNudgeRightForCeiling")
    w.branch(0xD0, "SetSpiderCeilingHandled")
    w.jsr_label("TryNudgeLeftForFloor")
    w.branch_long(0xD0, "SpiderReleaseToOriginal")
    w.jmp_label("SpiderReleaseToOriginal")

    w.label("CeilingLeftCorner")
    w.jsr_label("TryNudgeLeftDownForLeftWall")
    w.branch(0xD0, "SetSpiderWallLeftHandled")
    w.jmp_label("SpiderReleaseToOriginal")

    w.label("CeilingRightCorner")
    w.jsr_label("TryNudgeRightDownForRightWall")
    w.branch(0xD0, "SetSpiderWallRightHandled")
    w.jmp_label("SpiderReleaseToOriginal")

    w.label("SetSpiderWallRightHandled")
    w.jsr_label("SetSpiderWallRight")
    w.rts()
    w.label("SetSpiderWallLeftHandled")
    w.jsr_label("SetSpiderWallLeft")
    w.rts()
    w.label("SetSpiderCeilingHandled")
    w.jsr_label("SetSpiderCeiling")
    w.rts()
    w.label("SpiderStayHandled")
    w.jsr_label("ZeroSpiderMotion")
    w.clc()
    w.rts()
    w.label("SpiderReleaseToOriginal")
    w.jsr_label("ReleaseSpider")
    w.sec()
    w.rts()

    w.label("SetSpiderWallRight")
    w.lda_imm(SPIDER_ACTIVE_MAGIC)
    w.sta_abs(SPIDER_ACTIVE)
    w.lda_imm(SPIDER_STATE_WALL_RIGHT)
    w.sta_abs(SPIDER_STATE)
    w.stz_abs(SPIDER_LAST_CONTACT)
    w.jsr_label("ZeroSpiderMotion")
    w.clc()
    w.rts()

    w.label("SetSpiderWallLeft")
    w.lda_imm(SPIDER_ACTIVE_MAGIC)
    w.sta_abs(SPIDER_ACTIVE)
    w.lda_imm(SPIDER_STATE_WALL_LEFT)
    w.sta_abs(SPIDER_STATE)
    w.stz_abs(SPIDER_LAST_CONTACT)
    w.jsr_label("ZeroSpiderMotion")
    w.clc()
    w.rts()

    w.label("SetSpiderCeiling")
    w.lda_imm(SPIDER_ACTIVE_MAGIC)
    w.sta_abs(SPIDER_ACTIVE)
    w.lda_imm(SPIDER_STATE_CEILING)
    w.sta_abs(SPIDER_STATE)
    w.stz_abs(SPIDER_LAST_CONTACT)
    w.jsr_label("ZeroSpiderMotion")
    w.clc()
    w.rts()

    w.label("ReleaseSpider")
    w.stz_abs(SPIDER_ACTIVE)
    w.stz_abs(SPIDER_STATE)
    w.stz_abs(SPIDER_LAST_CONTACT)
    w.jsr_label("ZeroSpiderMotion")
    w.rts()

    w.label("ZeroSpiderMotion")
    for addr in (
        SAMUS_Y_SUBSPEED,
        SAMUS_Y_SPEED,
        SAMUS_Y_DIRECTION,
        MORPH_BALL_BOUNCE_STATE,
        SAMUS_IS_FALLING_FLAG,
        SAMUS_SOLID_VERTICAL_COLLISION_RESULT,
        SAMUS_SOLID_COLLISION_FLAG,
        SAMUS_X_EXTRA_RUN_SPEED,
        SAMUS_X_EXTRA_RUN_SUBSPEED,
        SAMUS_X_BASE_SPEED,
        SAMUS_X_BASE_SUBSPEED,
        SAMUS_X_ACCELERATION_MODE,
    ):
        w.stz_abs(addr)
    w.rts()

    w.label("SetOnePixelDisplacement")
    w.lda_imm(0x0001)
    w.sta_dp(DP_TEMP_12)
    w.stz_dp(DP_TEMP_14)
    w.rts()

    w.label("SetNegativeOnePixelDisplacement")
    w.lda_imm(0xFFFF)
    w.sta_dp(DP_TEMP_12)
    w.stz_dp(DP_TEMP_14)
    w.rts()

    w.label("StepLeft")
    w.jsr_label("SetNegativeOnePixelDisplacement")
    w.stz_abs(COLLISION_MOVEMENT_DIRECTION)
    w.jsl_long(WALL_JUMP_BLOCK_COLLISION_DETECTION)
    w.branch(0xB0, "StepLeftReturn")
    w.dec_abs(SAMUS_X_POSITION)
    w.jsl_long(ALIGN_SAMUS_Y_WITH_NON_SQUARE_SLOPE)
    w.label("StepLeftReturn")
    w.rts()
    w.label("StepRight")
    w.jsr_label("SetOnePixelDisplacement")
    w.lda_imm(0x0001)
    w.sta_abs(COLLISION_MOVEMENT_DIRECTION)
    w.jsl_long(WALL_JUMP_BLOCK_COLLISION_DETECTION)
    w.branch(0xB0, "StepRightReturn")
    w.inc_abs(SAMUS_X_POSITION)
    w.jsl_long(ALIGN_SAMUS_Y_WITH_NON_SQUARE_SLOPE)
    w.label("StepRightReturn")
    w.rts()
    w.label("StepUp")
    w.jsr_label("SetNegativeOnePixelDisplacement")
    w.lda_imm(0x0002)
    w.sta_abs(COLLISION_MOVEMENT_DIRECTION)
    w.jsl_long(BLOCK_COLLISION_DUE_TO_CHANGE_OF_POSE_SINGLE_BLOCK)
    w.branch(0xB0, "StepUpReturn")
    w.dec_abs(SAMUS_Y_POSITION)
    w.label("StepUpReturn")
    w.rts()
    w.label("StepDown")
    w.jsr_label("SetOnePixelDisplacement")
    w.lda_imm(0x0003)
    w.sta_abs(COLLISION_MOVEMENT_DIRECTION)
    w.jsl_long(BLOCK_COLLISION_DUE_TO_CHANGE_OF_POSE_SINGLE_BLOCK)
    w.branch(0xB0, "StepDownReturn")
    w.inc_abs(SAMUS_Y_POSITION)
    w.label("StepDownReturn")
    w.rts()

    w.label("CheckContactAfterStep")
    w.jsr_label("CheckSpiderContact")
    w.lda_abs(SPIDER_CONTACT)
    w.sta_abs(SPIDER_LAST_CONTACT)
    w.rts()

    w.label("CheckSpiderContact")
    w.stz_abs(SPIDER_CONTACT)
    w.jsr_label("ProbeRight")
    w.jsr_label("ProbeLeft")
    w.jsr_label("ProbeUp")
    w.jsr_label("ProbeDown")
    w.rts()

    emit_probe(w, "ProbeRight", "StepRight", 0x0001)
    emit_probe(w, "ProbeLeft", "StepLeft", 0x0002)
    emit_probe(w, "ProbeUp", "StepUp", 0x0004)
    emit_probe(w, "ProbeDown", "StepDown", 0x0008)

    emit_try_nudge(w, "TryNudgeRightForFloor", ("StepRight",), 0x0008)
    emit_try_nudge(w, "TryNudgeLeftForFloor", ("StepLeft",), 0x0008)
    emit_try_nudge(w, "TryNudgeRightForCeiling", ("StepRight",), 0x0004)
    emit_try_nudge(w, "TryNudgeLeftForCeiling", ("StepLeft",), 0x0004)
    emit_try_nudge(w, "TryNudgeLeftDownForLeftWall", ("StepLeft", "StepDown"), 0x0002)
    emit_try_nudge(w, "TryNudgeRightDownForRightWall", ("StepRight", "StepDown"), 0x0001)

    w.label("DispatchOriginalMovement")
    w.lda_abs(MOVEMENT_TYPE)
    w.and_imm(0x00FF)
    w.cmp_imm(0x0004)
    w.branch(0xD0, "DispatchCheckMorphFalling")
    w.jmp_abs(ORIG_MORPH_GROUND)
    w.label("DispatchCheckMorphFalling")
    w.cmp_imm(0x0008)
    w.branch(0xD0, "DispatchCheckSpringGround")
    w.jmp_abs(ORIG_MORPH_FALLING)
    w.label("DispatchCheckSpringGround")
    w.cmp_imm(0x0011)
    w.branch(0xD0, "DispatchCheckSpringAir")
    w.jmp_abs(ORIG_SPRING_GROUND)
    w.label("DispatchCheckSpringAir")
    w.cmp_imm(0x0012)
    w.branch(0xD0, "DispatchCheckSpringFalling")
    w.jmp_abs(ORIG_SPRING_AIR)
    w.label("DispatchCheckSpringFalling")
    w.cmp_imm(0x0013)
    w.branch(0xD0, "DispatchFallback")
    w.jmp_abs(ORIG_SPRING_FALLING)
    w.label("DispatchFallback")
    w.jmp_abs(ORIG_MORPH_FALLING)

    return w.resolve()


def build_pose_guard_code() -> bytes:
    w = Bank90Writer(start_addr=POSE_GUARD_ADDR)

    w.label("PoseInputGuard")
    w.lda_abs(SPIDER_ACTIVE)
    w.cmp_imm(SPIDER_ACTIVE_MAGIC)
    w.branch(0xD0, "PoseDispatchOriginal")  # BNE
    w.rts()

    w.label("PoseDispatchOriginal")
    w.lda_abs(MOVEMENT_TYPE)
    w.and_imm(0x00FF)
    w.cmp_imm(0x0004)
    w.branch(0xD0, "PoseCheckMorphFalling")
    w.jmp_abs(ORIG_POSE_MORPH_GROUND)
    w.label("PoseCheckMorphFalling")
    w.cmp_imm(0x0008)
    w.branch(0xD0, "PoseCheckSpringGround")
    w.jmp_abs(ORIG_POSE_MORPH_FALLING)
    w.label("PoseCheckSpringGround")
    w.cmp_imm(0x0011)
    w.branch(0xD0, "PoseCheckSpringAir")
    w.jmp_abs(ORIG_POSE_SPRING_GROUND)
    w.label("PoseCheckSpringAir")
    w.cmp_imm(0x0012)
    w.branch(0xD0, "PoseCheckSpringFalling")
    w.jmp_abs(ORIG_POSE_SPRING_AIR)
    w.label("PoseCheckSpringFalling")
    w.cmp_imm(0x0013)
    w.branch(0xD0, "PoseFallback")
    w.jmp_abs(ORIG_POSE_SPRING_FALLING)
    w.label("PoseFallback")
    w.jmp_abs(ORIG_POSE_MORPH_FALLING)

    return w.resolve()


def read_bank84(base: bytes, addr: int, length: int) -> bytes:
    pc = lorom_pc(0x84, addr)
    return base[pc : pc + length]


def read_bank85(base: bytes, addr: int, length: int) -> bytes:
    pc = lorom_pc(0x85, addr)
    return base[pc : pc + length]


def read_bank89(base: bytes, addr: int, length: int) -> bytes:
    pc = lorom_pc(0x89, addr)
    return base[pc : pc + length]


def remap_4bpp_tile(tile: bytes, palette_map: list[int]) -> bytes:
    if len(tile) != 32:
        raise ValueError(f"expected one 4bpp SNES tile, got {len(tile)} bytes")
    out = bytearray(32)
    for row in range(8):
        plane0 = tile[row * 2]
        plane1 = tile[row * 2 + 1]
        plane2 = tile[16 + row * 2]
        plane3 = tile[16 + row * 2 + 1]
        row_colors: list[int] = []
        for col in range(8):
            mask = 1 << (7 - col)
            color = 0
            if plane0 & mask:
                color |= 0x1
            if plane1 & mask:
                color |= 0x2
            if plane2 & mask:
                color |= 0x4
            if plane3 & mask:
                color |= 0x8
            row_colors.append(palette_map[color] & 0xF)
        for col, color in enumerate(row_colors):
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


def build_spider_item_gfx(base: bytes) -> bytes:
    morph_gfx = read_bank89(base, SPIDER_BALL_ITEM_GFX_SOURCE, SPIDER_BALL_ITEM_GFX_SIZE)
    palette_map = list(range(16))
    # Keep transparency at 0 and rotate Morph's used colours so the icon shape
    # remains readable while clearly not being the Morph Ball pickup.
    palette_map[1] = 9
    palette_map[9] = 12
    palette_map[12] = 13
    palette_map[13] = 15
    palette_map[15] = 1
    return b"".join(
        remap_4bpp_tile(morph_gfx[offset : offset + 32], palette_map)
        for offset in range(0, len(morph_gfx), 32)
    )


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
        if ch == " ":
            words.append(0x284E)
        else:
            words.append(0x28E0 + ord(ch) - ord("a"))
    words.extend([0x000E] * (32 - len(words)))
    if len(words) != 32:
        raise ValueError(f"small message produced {len(words)} tiles")
    return b"".join(u16(word) for word in words)


def message_box_entry(tilemap_addr: int) -> bytes:
    return u16(0x8436) + u16(0x8289) + u16(tilemap_addr)


def build_message_box_table(base: bytes) -> bytes:
    table = bytearray(read_bank85(
        base,
        MESSAGE_BOX_TABLE_ORIGINAL,
        MESSAGE_BOX_TABLE_ORIGINAL_ENTRIES * 6,
    ))
    spider_entry_offset = (SPIDER_BALL_MESSAGE_ID - 1) * 6
    table[spider_entry_offset : spider_entry_offset + 6] = message_box_entry(SPIDER_BALL_MESSAGE_TILEMAP)
    return b"".join(
        (
            bytes(table),
            message_box_entry(SPIDER_BALL_MESSAGE_TILEMAP_END),
        )
    )


def relocate_morph_plm_list(data: bytes, old_start: int, new_start: int) -> bytes:
    out = bytearray(data)
    old_end = old_start + len(out)
    for i in range(len(out) - 1):
        value = out[i] | (out[i + 1] << 8)
        if old_start <= value < old_end:
            relocated = new_start + (value - old_start)
            out[i : i + 2] = u16(relocated)

    pickup_offsets = [
        i for i in range(len(out) - 4)
        if out[i : i + 2] == bytes((0xF3, 0x88)) and out[i + 4] == 0x09
    ]
    if len(pickup_offsets) != 1:
        raise ValueError(f"expected one Morph Ball pickup instruction, found {len(pickup_offsets)}")
    pickup_offset = pickup_offsets[0]
    out[pickup_offset + 2 : pickup_offset + 4] = u16(SPIDER_BALL_ITEM_BIT)
    out[pickup_offset + 4] = SPIDER_BALL_MESSAGE_ID

    gfx_offsets = [
        i for i in range(len(out) - 11)
        if out[i : i + 4] == bytes((0x64, 0x87, 0x00, 0x87))
    ]
    if len(gfx_offsets) != 1:
        raise ValueError(f"expected one Morph Ball item graphics instruction, found {len(gfx_offsets)}")
    gfx_offset = gfx_offsets[0]
    out[gfx_offset + 2 : gfx_offset + 4] = u16(SPIDER_BALL_ITEM_GFX_ADDR)
    return bytes(out)


def build_spider_item_plms(base: bytes) -> bytes:
    visible_raw = read_bank84(base, MORPH_VISIBLE_LIST_START, MORPH_VISIBLE_LIST_END - MORPH_VISIBLE_LIST_START)
    chozo_raw = read_bank84(base, MORPH_CHOZO_LIST_START, MORPH_CHOZO_LIST_END - MORPH_CHOZO_LIST_START)
    hidden_raw = read_bank84(base, MORPH_HIDDEN_LIST_START, MORPH_HIDDEN_LIST_END - MORPH_HIDDEN_LIST_START)

    visible_start = SPIDER_BALL_PLM_LIST_START
    chozo_start = visible_start + len(visible_raw)
    hidden_start = chozo_start + len(chozo_raw)

    visible = relocate_morph_plm_list(visible_raw, MORPH_VISIBLE_LIST_START, visible_start)
    chozo = relocate_morph_plm_list(chozo_raw, MORPH_CHOZO_LIST_START, chozo_start)
    hidden = relocate_morph_plm_list(hidden_raw, MORPH_HIDDEN_LIST_START, hidden_start)

    headers = b"".join(
        (
            u16(MORPH_PLM_SETUP_VISIBLE_CHOZO), u16(visible_start),
            u16(MORPH_PLM_SETUP_VISIBLE_CHOZO), u16(chozo_start),
            u16(MORPH_PLM_SETUP_HIDDEN), u16(hidden_start),
        )
    )
    return headers + visible + chozo + hidden


def make_ips(records: list[tuple[int, bytes]]) -> bytes:
    out = bytearray(b"PATCH")
    for offset, data in records:
        if not data:
            continue
        if offset < 0 or offset > 0xFFFFFF:
            raise ValueError(f"IPS offset out of range: {offset:#x}")
        cursor = 0
        while cursor < len(data):
            chunk = data[cursor : cursor + 0xFFFF]
            out += u24(offset + cursor)
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
    code = build_spider_code()
    pose_guard = build_pose_guard_code()
    spider_item_plms = build_spider_item_plms(base)
    spider_item_gfx = build_spider_item_gfx(base)
    message_box_table = build_message_box_table(base)
    spider_ball_message = encode_small_message_tilemap("spider ball")
    free_len = 0x10000 - CODE_ADDR
    if len(code) > free_len:
        raise SystemExit(f"Spider Ball code is {len(code)} bytes, exceeds free space {free_len}")
    pose_guard_len = ORIG_POSE_MORPH_FALLING - POSE_GUARD_ADDR
    if len(pose_guard) > pose_guard_len:
        raise SystemExit(
            f"Spider Ball pose guard is {len(pose_guard)} bytes, exceeds unused bank $91 space {pose_guard_len}"
        )

    records = [
        # SamusMovementHandler_Normal dispatch table entries for morph/spring ball.
        (lorom_pc(0x90, 0xA353), u16(CODE_ADDR)),
        (lorom_pc(0x90, 0xA35B), u16(CODE_ADDR)),
        (lorom_pc(0x90, 0xA36D), u16(CODE_ADDR)),
        (lorom_pc(0x90, 0xA36F), u16(CODE_ADDR)),
        (lorom_pc(0x90, 0xA371), u16(CODE_ADDR)),
        (lorom_pc(0x90, CODE_ADDR), code),
        # NormalSamusPoseInputHandler dispatch table entries for morph/spring ball.
        (lorom_pc(0x91, 0x801C), u16(POSE_GUARD_ADDR)),
        (lorom_pc(0x91, 0x8024), u16(POSE_GUARD_ADDR)),
        (lorom_pc(0x91, 0x8036), u16(POSE_GUARD_ADDR)),
        (lorom_pc(0x91, 0x8038), u16(POSE_GUARD_ADDR)),
        (lorom_pc(0x91, 0x803A), u16(POSE_GUARD_ADDR)),
        (lorom_pc(0x91, POSE_GUARD_ADDR), pose_guard),
        # Relocate/extend the message definition table so Spider Ball can use
        # a new message id without overwriting vanilla message/button tables.
        (lorom_pc(0x85, 0x8251), u16(MESSAGE_BOX_TABLE_RELOCATED + 2)),
        (lorom_pc(0x85, 0x8255), u16(MESSAGE_BOX_TABLE_RELOCATED)),
        (lorom_pc(0x85, 0x82F2), u16(MESSAGE_BOX_TABLE_RELOCATED + 4)),
        (lorom_pc(0x85, 0x82F7), u16(MESSAGE_BOX_TABLE_RELOCATED + 10)),
        (lorom_pc(0x85, MESSAGE_BOX_TABLE_RELOCATED), message_box_table),
        (lorom_pc(0x85, SPIDER_BALL_MESSAGE_TILEMAP), spider_ball_message),
        # Custom visible/Chozo/hidden Spider Ball item PLMs.
        (lorom_pc(0x84, SPIDER_BALL_PLM_VISIBLE), spider_item_plms),
        (lorom_pc(0x89, SPIDER_BALL_ITEM_GFX_ADDR), spider_item_gfx),
    ]
    ips = make_ips(records)

    expected_originals = {
        lorom_pc(0x90, 0xA353): bytes.fromhex("21 A5"),
        lorom_pc(0x90, 0xA35B): bytes.fromhex("CA A5"),
        lorom_pc(0x90, 0xA36D): bytes.fromhex("9F A6"),
        lorom_pc(0x90, 0xA36F): bytes.fromhex("F1 A6"),
        lorom_pc(0x90, 0xA371): bytes.fromhex("03 A7"),
        lorom_pc(0x90, CODE_ADDR): b"\xFF" * min(64, len(code)),
        lorom_pc(0x91, 0x801C): bytes.fromhex("7E 80"),
        lorom_pc(0x91, 0x8024): bytes.fromhex("0A 81"),
        lorom_pc(0x91, 0x8036): bytes.fromhex("4F 81"),
        lorom_pc(0x91, 0x8038): bytes.fromhex("57 81"),
        lorom_pc(0x91, 0x803A): bytes.fromhex("5F 81"),
        lorom_pc(0x91, POSE_GUARD_ADDR): bytes.fromhex(
            "AD 1C 0A C9 29 00 F0 05 C9 2A 00 D0 3D AD 27 0A "
            "29 FF 00 C9 05 00 F0 32 A5 8B 89 00 03 D0 2B AD"
        ),
        lorom_pc(0x85, 0x8251): bytes.fromhex("9D 86"),
        lorom_pc(0x85, 0x8255): bytes.fromhex("9B 86"),
        lorom_pc(0x85, 0x82F2): bytes.fromhex("9F 86"),
        lorom_pc(0x85, 0x82F7): bytes.fromhex("A5 86"),
        lorom_pc(0x85, MESSAGE_BOX_TABLE_RELOCATED): b"\xFF" * len(message_box_table),
        lorom_pc(0x85, SPIDER_BALL_MESSAGE_TILEMAP): b"\xFF" * len(spider_ball_message),
        lorom_pc(0x84, SPIDER_BALL_PLM_VISIBLE): b"\xFF" * min(64, len(spider_item_plms)),
        lorom_pc(0x89, SPIDER_BALL_ITEM_GFX_ADDR): b"\xFF" * len(spider_item_gfx),
    }
    for offset, expected in expected_originals.items():
        actual = base[offset : offset + len(expected)]
        if actual != expected:
            raise SystemExit(
                f"Unexpected original bytes at {offset:#06x}: "
                f"expected {expected.hex(' ')}, got {actual.hex(' ')}"
            )

    patched = bytearray(base)
    apply_ips(patched, ips)
    for offset, data in records:
        actual = patched[offset : offset + len(data)]
        if actual != data:
            raise SystemExit(f"Patch verification failed at {offset:#06x}")

    OUT_IPS.write_bytes(ips)

    print(f"Wrote {OUT_IPS.relative_to(ROOT)}")
    print(f"Spider Ball code: {len(code)} bytes at $90:{CODE_ADDR:04X}")
    print(f"Spider Ball pose guard: {len(pose_guard)} bytes at $91:{POSE_GUARD_ADDR:04X}")
    print(
        "Spider Ball PLMs: "
        f"{len(spider_item_plms)} bytes at $84:{SPIDER_BALL_PLM_VISIBLE:04X} "
        f"(visible ${SPIDER_BALL_PLM_VISIBLE:04X}, Chozo ${SPIDER_BALL_PLM_CHOZO:04X}, "
        f"hidden ${SPIDER_BALL_PLM_HIDDEN:04X})"
    )
    print(f"Spider Ball item graphics: {len(spider_item_gfx)} bytes at $89:{SPIDER_BALL_ITEM_GFX_ADDR:04X}")
    print(
        "Spider Ball message: "
        f"id ${SPIDER_BALL_MESSAGE_ID:02X}, table ${MESSAGE_BOX_TABLE_RELOCATED:04X}, "
        f"tilemap ${SPIDER_BALL_MESSAGE_TILEMAP:04X}"
    )
    print(f"IPS size: {len(ips)} bytes")


if __name__ == "__main__":
    main()
