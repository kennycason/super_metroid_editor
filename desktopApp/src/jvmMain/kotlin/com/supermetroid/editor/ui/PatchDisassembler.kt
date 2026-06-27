package com.supermetroid.editor.ui

private enum class AddressMode {
    Implied,
    Accumulator,
    Immediate8,
    ImmediateM,
    ImmediateX,
    Direct,
    DirectX,
    DirectY,
    DirectIndirect,
    DirectIndirectLong,
    DirectIndirectY,
    DirectIndirectLongY,
    DirectXIndirect,
    StackRelative,
    StackRelativeIndirectY,
    Absolute,
    AbsoluteX,
    AbsoluteY,
    AbsoluteLong,
    AbsoluteLongX,
    AbsoluteIndirect,
    AbsoluteXIndirect,
    AbsoluteIndirectLong,
    Relative8,
    Relative16,
    BlockMove,
}

private data class Opcode(
    val mnemonic: String,
    val mode: AddressMode,
)

private val OPCODES_65816 = arrayOf(
    Opcode("BRK", AddressMode.Immediate8), Opcode("ORA", AddressMode.DirectXIndirect), Opcode("COP", AddressMode.Immediate8), Opcode("ORA", AddressMode.StackRelative),
    Opcode("TSB", AddressMode.Direct), Opcode("ORA", AddressMode.Direct), Opcode("ASL", AddressMode.Direct), Opcode("ORA", AddressMode.DirectIndirectLong),
    Opcode("PHP", AddressMode.Implied), Opcode("ORA", AddressMode.ImmediateM), Opcode("ASL", AddressMode.Accumulator), Opcode("PHD", AddressMode.Implied),
    Opcode("TSB", AddressMode.Absolute), Opcode("ORA", AddressMode.Absolute), Opcode("ASL", AddressMode.Absolute), Opcode("ORA", AddressMode.AbsoluteLong),

    Opcode("BPL", AddressMode.Relative8), Opcode("ORA", AddressMode.DirectIndirectY), Opcode("ORA", AddressMode.DirectIndirect), Opcode("ORA", AddressMode.StackRelativeIndirectY),
    Opcode("TRB", AddressMode.Direct), Opcode("ORA", AddressMode.DirectX), Opcode("ASL", AddressMode.DirectX), Opcode("ORA", AddressMode.DirectIndirectLongY),
    Opcode("CLC", AddressMode.Implied), Opcode("ORA", AddressMode.AbsoluteY), Opcode("INC", AddressMode.Accumulator), Opcode("TCS", AddressMode.Implied),
    Opcode("TRB", AddressMode.Absolute), Opcode("ORA", AddressMode.AbsoluteX), Opcode("ASL", AddressMode.AbsoluteX), Opcode("ORA", AddressMode.AbsoluteLongX),

    Opcode("JSR", AddressMode.Absolute), Opcode("AND", AddressMode.DirectXIndirect), Opcode("JSL", AddressMode.AbsoluteLong), Opcode("AND", AddressMode.StackRelative),
    Opcode("BIT", AddressMode.Direct), Opcode("AND", AddressMode.Direct), Opcode("ROL", AddressMode.Direct), Opcode("AND", AddressMode.DirectIndirectLong),
    Opcode("PLP", AddressMode.Implied), Opcode("AND", AddressMode.ImmediateM), Opcode("ROL", AddressMode.Accumulator), Opcode("PLD", AddressMode.Implied),
    Opcode("BIT", AddressMode.Absolute), Opcode("AND", AddressMode.Absolute), Opcode("ROL", AddressMode.Absolute), Opcode("AND", AddressMode.AbsoluteLong),

    Opcode("BMI", AddressMode.Relative8), Opcode("AND", AddressMode.DirectIndirectY), Opcode("AND", AddressMode.DirectIndirect), Opcode("AND", AddressMode.StackRelativeIndirectY),
    Opcode("BIT", AddressMode.DirectX), Opcode("AND", AddressMode.DirectX), Opcode("ROL", AddressMode.DirectX), Opcode("AND", AddressMode.DirectIndirectLongY),
    Opcode("SEC", AddressMode.Implied), Opcode("AND", AddressMode.AbsoluteY), Opcode("DEC", AddressMode.Accumulator), Opcode("TSC", AddressMode.Implied),
    Opcode("BIT", AddressMode.AbsoluteX), Opcode("AND", AddressMode.AbsoluteX), Opcode("ROL", AddressMode.AbsoluteX), Opcode("AND", AddressMode.AbsoluteLongX),

    Opcode("RTI", AddressMode.Implied), Opcode("EOR", AddressMode.DirectXIndirect), Opcode("WDM", AddressMode.Immediate8), Opcode("EOR", AddressMode.StackRelative),
    Opcode("MVP", AddressMode.BlockMove), Opcode("EOR", AddressMode.Direct), Opcode("LSR", AddressMode.Direct), Opcode("EOR", AddressMode.DirectIndirectLong),
    Opcode("PHA", AddressMode.Implied), Opcode("EOR", AddressMode.ImmediateM), Opcode("LSR", AddressMode.Accumulator), Opcode("PHK", AddressMode.Implied),
    Opcode("JMP", AddressMode.Absolute), Opcode("EOR", AddressMode.Absolute), Opcode("LSR", AddressMode.Absolute), Opcode("EOR", AddressMode.AbsoluteLong),

    Opcode("BVC", AddressMode.Relative8), Opcode("EOR", AddressMode.DirectIndirectY), Opcode("EOR", AddressMode.DirectIndirect), Opcode("EOR", AddressMode.StackRelativeIndirectY),
    Opcode("MVN", AddressMode.BlockMove), Opcode("EOR", AddressMode.DirectX), Opcode("LSR", AddressMode.DirectX), Opcode("EOR", AddressMode.DirectIndirectLongY),
    Opcode("CLI", AddressMode.Implied), Opcode("EOR", AddressMode.AbsoluteY), Opcode("PHY", AddressMode.Implied), Opcode("TCD", AddressMode.Implied),
    Opcode("JML", AddressMode.AbsoluteLong), Opcode("EOR", AddressMode.AbsoluteX), Opcode("LSR", AddressMode.AbsoluteX), Opcode("EOR", AddressMode.AbsoluteLongX),

    Opcode("RTS", AddressMode.Implied), Opcode("ADC", AddressMode.DirectXIndirect), Opcode("PER", AddressMode.Relative16), Opcode("ADC", AddressMode.StackRelative),
    Opcode("STZ", AddressMode.Direct), Opcode("ADC", AddressMode.Direct), Opcode("ROR", AddressMode.Direct), Opcode("ADC", AddressMode.DirectIndirectLong),
    Opcode("PLA", AddressMode.Implied), Opcode("ADC", AddressMode.ImmediateM), Opcode("ROR", AddressMode.Accumulator), Opcode("RTL", AddressMode.Implied),
    Opcode("JMP", AddressMode.AbsoluteIndirect), Opcode("ADC", AddressMode.Absolute), Opcode("ROR", AddressMode.Absolute), Opcode("ADC", AddressMode.AbsoluteLong),

    Opcode("BVS", AddressMode.Relative8), Opcode("ADC", AddressMode.DirectIndirectY), Opcode("ADC", AddressMode.DirectIndirect), Opcode("ADC", AddressMode.StackRelativeIndirectY),
    Opcode("STZ", AddressMode.DirectX), Opcode("ADC", AddressMode.DirectX), Opcode("ROR", AddressMode.DirectX), Opcode("ADC", AddressMode.DirectIndirectLongY),
    Opcode("SEI", AddressMode.Implied), Opcode("ADC", AddressMode.AbsoluteY), Opcode("PLY", AddressMode.Implied), Opcode("TDC", AddressMode.Implied),
    Opcode("JMP", AddressMode.AbsoluteXIndirect), Opcode("ADC", AddressMode.AbsoluteX), Opcode("ROR", AddressMode.AbsoluteX), Opcode("ADC", AddressMode.AbsoluteLongX),

    Opcode("BRA", AddressMode.Relative8), Opcode("STA", AddressMode.DirectXIndirect), Opcode("BRL", AddressMode.Relative16), Opcode("STA", AddressMode.StackRelative),
    Opcode("STY", AddressMode.Direct), Opcode("STA", AddressMode.Direct), Opcode("STX", AddressMode.Direct), Opcode("STA", AddressMode.DirectIndirectLong),
    Opcode("DEY", AddressMode.Implied), Opcode("BIT", AddressMode.ImmediateM), Opcode("TXA", AddressMode.Implied), Opcode("PHB", AddressMode.Implied),
    Opcode("STY", AddressMode.Absolute), Opcode("STA", AddressMode.Absolute), Opcode("STX", AddressMode.Absolute), Opcode("STA", AddressMode.AbsoluteLong),

    Opcode("BCC", AddressMode.Relative8), Opcode("STA", AddressMode.DirectIndirectY), Opcode("STA", AddressMode.DirectIndirect), Opcode("STA", AddressMode.StackRelativeIndirectY),
    Opcode("STY", AddressMode.DirectX), Opcode("STA", AddressMode.DirectX), Opcode("STX", AddressMode.DirectY), Opcode("STA", AddressMode.DirectIndirectLongY),
    Opcode("TYA", AddressMode.Implied), Opcode("STA", AddressMode.AbsoluteY), Opcode("TXS", AddressMode.Implied), Opcode("TXY", AddressMode.Implied),
    Opcode("STZ", AddressMode.Absolute), Opcode("STA", AddressMode.AbsoluteX), Opcode("STZ", AddressMode.AbsoluteX), Opcode("STA", AddressMode.AbsoluteLongX),

    Opcode("LDY", AddressMode.ImmediateX), Opcode("LDA", AddressMode.DirectXIndirect), Opcode("LDX", AddressMode.ImmediateX), Opcode("LDA", AddressMode.StackRelative),
    Opcode("LDY", AddressMode.Direct), Opcode("LDA", AddressMode.Direct), Opcode("LDX", AddressMode.Direct), Opcode("LDA", AddressMode.DirectIndirectLong),
    Opcode("TAY", AddressMode.Implied), Opcode("LDA", AddressMode.ImmediateM), Opcode("TAX", AddressMode.Implied), Opcode("PLB", AddressMode.Implied),
    Opcode("LDY", AddressMode.Absolute), Opcode("LDA", AddressMode.Absolute), Opcode("LDX", AddressMode.Absolute), Opcode("LDA", AddressMode.AbsoluteLong),

    Opcode("BCS", AddressMode.Relative8), Opcode("LDA", AddressMode.DirectIndirectY), Opcode("LDA", AddressMode.DirectIndirect), Opcode("LDA", AddressMode.StackRelativeIndirectY),
    Opcode("LDY", AddressMode.DirectX), Opcode("LDA", AddressMode.DirectX), Opcode("LDX", AddressMode.DirectY), Opcode("LDA", AddressMode.DirectIndirectLongY),
    Opcode("CLV", AddressMode.Implied), Opcode("LDA", AddressMode.AbsoluteY), Opcode("TSX", AddressMode.Implied), Opcode("TYX", AddressMode.Implied),
    Opcode("LDY", AddressMode.AbsoluteX), Opcode("LDA", AddressMode.AbsoluteX), Opcode("LDX", AddressMode.AbsoluteY), Opcode("LDA", AddressMode.AbsoluteLongX),

    Opcode("CPY", AddressMode.ImmediateX), Opcode("CMP", AddressMode.DirectXIndirect), Opcode("REP", AddressMode.Immediate8), Opcode("CMP", AddressMode.StackRelative),
    Opcode("CPY", AddressMode.Direct), Opcode("CMP", AddressMode.Direct), Opcode("DEC", AddressMode.Direct), Opcode("CMP", AddressMode.DirectIndirectLong),
    Opcode("INY", AddressMode.Implied), Opcode("CMP", AddressMode.ImmediateM), Opcode("DEX", AddressMode.Implied), Opcode("WAI", AddressMode.Implied),
    Opcode("CPY", AddressMode.Absolute), Opcode("CMP", AddressMode.Absolute), Opcode("DEC", AddressMode.Absolute), Opcode("CMP", AddressMode.AbsoluteLong),

    Opcode("BNE", AddressMode.Relative8), Opcode("CMP", AddressMode.DirectIndirectY), Opcode("CMP", AddressMode.DirectIndirect), Opcode("CMP", AddressMode.StackRelativeIndirectY),
    Opcode("PEI", AddressMode.DirectIndirect), Opcode("CMP", AddressMode.DirectX), Opcode("DEC", AddressMode.DirectX), Opcode("CMP", AddressMode.DirectIndirectLongY),
    Opcode("CLD", AddressMode.Implied), Opcode("CMP", AddressMode.AbsoluteY), Opcode("PHX", AddressMode.Implied), Opcode("STP", AddressMode.Implied),
    Opcode("JML", AddressMode.AbsoluteIndirectLong), Opcode("CMP", AddressMode.AbsoluteX), Opcode("DEC", AddressMode.AbsoluteX), Opcode("CMP", AddressMode.AbsoluteLongX),

    Opcode("CPX", AddressMode.ImmediateX), Opcode("SBC", AddressMode.DirectXIndirect), Opcode("SEP", AddressMode.Immediate8), Opcode("SBC", AddressMode.StackRelative),
    Opcode("CPX", AddressMode.Direct), Opcode("SBC", AddressMode.Direct), Opcode("INC", AddressMode.Direct), Opcode("SBC", AddressMode.DirectIndirectLong),
    Opcode("INX", AddressMode.Implied), Opcode("SBC", AddressMode.ImmediateM), Opcode("NOP", AddressMode.Implied), Opcode("XBA", AddressMode.Implied),
    Opcode("CPX", AddressMode.Absolute), Opcode("SBC", AddressMode.Absolute), Opcode("INC", AddressMode.Absolute), Opcode("SBC", AddressMode.AbsoluteLong),

    Opcode("BEQ", AddressMode.Relative8), Opcode("SBC", AddressMode.DirectIndirectY), Opcode("SBC", AddressMode.DirectIndirect), Opcode("SBC", AddressMode.StackRelativeIndirectY),
    Opcode("PEA", AddressMode.Absolute), Opcode("SBC", AddressMode.DirectX), Opcode("INC", AddressMode.DirectX), Opcode("SBC", AddressMode.DirectIndirectLongY),
    Opcode("SED", AddressMode.Implied), Opcode("SBC", AddressMode.AbsoluteY), Opcode("PLX", AddressMode.Implied), Opcode("XCE", AddressMode.Implied),
    Opcode("JSR", AddressMode.AbsoluteXIndirect), Opcode("SBC", AddressMode.AbsoluteX), Opcode("INC", AddressMode.AbsoluteX), Opcode("SBC", AddressMode.AbsoluteLongX),
)

internal fun disassemblePatchWrites(writes: List<SmPatchWrite>): String {
    if (writes.isEmpty()) return ""
    return buildString {
        for ((recordIndex, write) in writes.withIndex()) {
            if (recordIndex > 0) appendLine()
            val startSnes = pcToSnesHeaderless(write.offset)
            appendLine("; record ${recordIndex + 1}: PC ${formatPc(write.offset)} -> ${formatSnes(startSnes)}, ${write.bytes.size} bytes")
            disassembleRecord(write, this)
        }
    }.trimEnd()
}

private fun disassembleRecord(write: SmPatchWrite, out: StringBuilder) {
    var cursor = 0
    var accumulator8Bit = false
    var index8Bit = false
    val bytes = write.bytes.map { it and 0xFF }

    while (cursor < bytes.size) {
        val pc = write.offset + cursor
        val snes = pcToSnesHeaderless(pc)
        val opcodeByte = bytes[cursor]
        val opcode = OPCODES_65816[opcodeByte]
        val expectedLength = instructionLength(opcode.mode, accumulator8Bit, index8Bit)
        val remaining = bytes.size - cursor
        val actualLength = minOf(expectedLength, remaining)
        val instructionBytes = bytes.subList(cursor, cursor + actualLength)

        if (actualLength < expectedLength) {
            out.appendLine(
                "${formatSnes(snes)}  ${formatBytes(instructionBytes).padEnd(11)}  .db ${formatDb(instructionBytes)} ; incomplete ${opcode.mnemonic}"
            )
            cursor += actualLength
            continue
        }

        val operand = operandText(opcode, bytes, cursor, snes, expectedLength, accumulator8Bit, index8Bit)
        val asm = if (operand.isEmpty()) opcode.mnemonic else "${opcode.mnemonic} $operand"
        out.appendLine("${formatSnes(snes)}  ${formatBytes(instructionBytes).padEnd(11)}  $asm")

        if (opcodeByte == 0xC2 || opcodeByte == 0xE2) {
            val mask = bytes[cursor + 1]
            val setBits = opcodeByte == 0xE2
            if ((mask and 0x20) != 0) accumulator8Bit = setBits
            if ((mask and 0x10) != 0) index8Bit = setBits
        }

        cursor += expectedLength
    }
}

private fun instructionLength(mode: AddressMode, accumulator8Bit: Boolean, index8Bit: Boolean): Int =
    when (mode) {
        AddressMode.Implied, AddressMode.Accumulator -> 1
        AddressMode.Immediate8,
        AddressMode.Direct,
        AddressMode.DirectX,
        AddressMode.DirectY,
        AddressMode.DirectIndirect,
        AddressMode.DirectIndirectLong,
        AddressMode.DirectIndirectY,
        AddressMode.DirectIndirectLongY,
        AddressMode.DirectXIndirect,
        AddressMode.StackRelative,
        AddressMode.StackRelativeIndirectY -> 2
        AddressMode.ImmediateM -> if (accumulator8Bit) 2 else 3
        AddressMode.ImmediateX -> if (index8Bit) 2 else 3
        AddressMode.Absolute,
        AddressMode.AbsoluteX,
        AddressMode.AbsoluteY,
        AddressMode.AbsoluteIndirect,
        AddressMode.AbsoluteXIndirect,
        AddressMode.AbsoluteIndirectLong,
        AddressMode.Relative16,
        AddressMode.BlockMove -> 3
        AddressMode.AbsoluteLong,
        AddressMode.AbsoluteLongX -> 4
        AddressMode.Relative8 -> 2
    }

private fun operandText(
    opcode: Opcode,
    bytes: List<Int>,
    cursor: Int,
    snes: Int,
    length: Int,
    accumulator8Bit: Boolean,
    index8Bit: Boolean,
): String {
    fun b(index: Int): Int = bytes[cursor + index] and 0xFF
    fun w(index: Int): Int = b(index) or (b(index + 1) shl 8)
    fun l(index: Int): Int = w(index) or (b(index + 2) shl 16)

    return when (opcode.mode) {
        AddressMode.Implied -> ""
        AddressMode.Accumulator -> "A"
        AddressMode.Immediate8 -> "#${formatByteOperand(b(1))}"
        AddressMode.ImmediateM -> if (accumulator8Bit) "#${formatByteOperand(b(1))}" else "#${formatWordOperand(w(1))}"
        AddressMode.ImmediateX -> if (index8Bit) "#${formatByteOperand(b(1))}" else "#${formatWordOperand(w(1))}"
        AddressMode.Direct -> formatByteOperand(b(1))
        AddressMode.DirectX -> "${formatByteOperand(b(1))},X"
        AddressMode.DirectY -> "${formatByteOperand(b(1))},Y"
        AddressMode.DirectIndirect -> "(${formatByteOperand(b(1))})"
        AddressMode.DirectIndirectLong -> "[${formatByteOperand(b(1))}]"
        AddressMode.DirectIndirectY -> "(${formatByteOperand(b(1))}),Y"
        AddressMode.DirectIndirectLongY -> "[${formatByteOperand(b(1))}],Y"
        AddressMode.DirectXIndirect -> "(${formatByteOperand(b(1))},X)"
        AddressMode.StackRelative -> "${formatByteOperand(b(1))},S"
        AddressMode.StackRelativeIndirectY -> "(${formatByteOperand(b(1))},S),Y"
        AddressMode.Absolute -> formatWordOperand(w(1))
        AddressMode.AbsoluteX -> "${formatWordOperand(w(1))},X"
        AddressMode.AbsoluteY -> "${formatWordOperand(w(1))},Y"
        AddressMode.AbsoluteLong -> formatLongOperand(l(1))
        AddressMode.AbsoluteLongX -> "${formatLongOperand(l(1))},X"
        AddressMode.AbsoluteIndirect -> "(${formatWordOperand(w(1))})"
        AddressMode.AbsoluteXIndirect -> "(${formatWordOperand(w(1))},X)"
        AddressMode.AbsoluteIndirectLong -> "[${formatWordOperand(w(1))}]"
        AddressMode.Relative8 -> formatSnes(relativeTarget(snes, length, signed8(b(1))))
        AddressMode.Relative16 -> formatSnes(relativeTarget(snes, length, signed16(w(1))))
        AddressMode.BlockMove -> "${formatByteOperand(b(1))},${formatByteOperand(b(2))}"
    }
}

private fun relativeTarget(snes: Int, instructionLength: Int, displacement: Int): Int {
    val bank = snes and 0xFF0000
    val address = ((snes and 0xFFFF) + instructionLength + displacement) and 0xFFFF
    return bank or address
}

private fun signed8(value: Int): Int =
    if ((value and 0x80) == 0) value else value - 0x100

private fun signed16(value: Int): Int =
    if ((value and 0x8000) == 0) value else value - 0x10000

private fun pcToSnesHeaderless(pcOffset: Long): Int {
    val bank = (((pcOffset / 0x8000L).toInt()) and 0x7F) or 0x80
    val address = ((pcOffset % 0x8000L).toInt()) + 0x8000
    return (bank shl 16) or address
}

private fun formatPc(pc: Long): String =
    "0x${pc.toString(16).uppercase().padStart(6, '0')}"

private fun formatSnes(snes: Int): String =
    "\$${hex2((snes shr 16) and 0xFF)}:${hex4(snes and 0xFFFF)}"

private fun formatByteOperand(value: Int): String =
    "\$${hex2(value)}"

private fun formatWordOperand(value: Int): String =
    "\$${hex4(value)}"

private fun formatLongOperand(value: Int): String =
    "\$${hex2((value shr 16) and 0xFF)}:${hex4(value and 0xFFFF)}"

private fun formatBytes(bytes: List<Int>): String =
    bytes.joinToString(" ") { hex2(it) }

private fun formatDb(bytes: List<Int>): String =
    bytes.joinToString(", ") { "\$${hex2(it)}" }

private fun hex2(value: Int): String =
    (value and 0xFF).toString(16).uppercase().padStart(2, '0')

private fun hex4(value: Int): String =
    (value and 0xFFFF).toString(16).uppercase().padStart(4, '0')
