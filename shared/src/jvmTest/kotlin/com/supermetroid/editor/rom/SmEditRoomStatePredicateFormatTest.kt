package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executes the emitted opcode subset so predicate behavior is tested, not merely serialized. */
class SmEditRoomStatePredicateFormatTest {
    @Test
    fun `generated routine evaluates equipment beams capacities item bits and bosses`() {
        assertTrue(runPredicate(SmEditRoomStatePredicateFormat.TYPE_EQUIPMENT, 0x0001) {
            word(0x09A4, 0x0001)
        })
        assertFalse(runPredicate(SmEditRoomStatePredicateFormat.TYPE_BEAM, 0x1000) {
            word(0x09A8, 0x0002)
        })
        assertTrue(runPredicate(SmEditRoomStatePredicateFormat.TYPE_MAX_MISSILES, 25) {
            word(0x09C8, 25)
        })
        assertFalse(runPredicate(SmEditRoomStatePredicateFormat.TYPE_MAX_MISSILES, 25) {
            word(0x09C8, 20)
        })
        assertTrue(runPredicate(SmEditRoomStatePredicateFormat.TYPE_ITEM_PICKUP, 0x51) {
            byte(0x7ED870 + (0x51 ushr 3), 1 shl (0x51 and 7))
        })
        assertFalse(runPredicate(SmEditRoomStatePredicateFormat.TYPE_ITEM_PICKUP, 0x51))
        assertTrue(
            runPredicate(
                SmEditRoomStatePredicateFormat.TYPE_BOSS,
                packedBossConditionArgument(4, 0x02),
            ) { byte(0x7ED828 + 4, 0x02) }
        )
        assertFalse(
            runPredicate(
                SmEditRoomStatePredicateFormat.TYPE_BOSS,
                packedBossConditionArgument(4, 0x02),
            ) { byte(0x7ED828 + 4, 0x01) }
        )
        assertTrue(
            runPredicate(
                SmEditRoomStatePredicateFormat.TYPE_BOSS,
                packedBossConditionArgument(7, 0x80),
            ) { byte(0x7ED828 + 7, 0x80) }
        )
    }

    @Test
    fun `generated routine inverts both mask and threshold predicates`() {
        assertTrue(runPredicate(SmEditRoomStatePredicateFormat.TYPE_EQUIPMENT, 0x0020, inverted = true))
        assertFalse(runPredicate(SmEditRoomStatePredicateFormat.TYPE_EQUIPMENT, 0x0020, inverted = true) {
            word(0x09A4, 0x0020)
        })
        assertTrue(runPredicate(SmEditRoomStatePredicateFormat.TYPE_MAX_POWER_BOMBS, 10, inverted = true) {
            word(0x09D0, 5)
        })
        assertFalse(runPredicate(SmEditRoomStatePredicateFormat.TYPE_MAX_POWER_BOMBS, 10, inverted = true) {
            word(0x09D0, 10)
        })
    }

    private class TestMemory {
        private val bytes = mutableMapOf<Int, Int>()

        fun byte(address: Int, value: Int) {
            bytes[address] = value and 0xFF
        }

        fun word(address: Int, value: Int) {
            byte(address, value)
            byte(address + 1, value ushr 8)
        }

        fun readByte(address: Int): Int = bytes[address] ?: 0
        fun readWord(address: Int): Int = readByte(address) or (readByte(address + 1) shl 8)
    }

    private fun runPredicate(
        type: Int,
        value: Int,
        inverted: Boolean = false,
        configure: TestMemory.() -> Unit = {},
    ): Boolean {
        val code = SmEditRoomStatePredicateFormat.routineBytes
        val memory = TestMemory().apply(configure)
        val payload = 0xA000
        memory.byte(payload, type)
        memory.byte(payload + 1, if (inverted) SmEditRoomStatePredicateFormat.FLAG_INVERTED else 0)
        memory.word(payload + 2, value)
        memory.word(payload + 4, 0xBEEF)

        var pc = 0
        var a = 0
        var x = payload
        var zero = false
        var carry = false
        val stack = mutableListOf<Int>()

        fun setA(value16: Int) {
            a = value16 and 0xFFFF
            zero = a == 0
        }

        fun codeByte(): Int = code[pc++].toInt() and 0xFF
        fun codeWord(): Int = codeByte() or (codeByte() shl 8)
        fun signed(value8: Int): Int = if (value8 < 0x80) value8 else value8 - 0x100

        repeat(256) {
            when (val opcode = codeByte()) {
                0xBD -> setA(memory.readWord((x + codeWord()) and 0xFFFF)) // LDA abs,X
                0x29 -> setA(a and codeWord()) // AND immediate
                0x3A -> setA(a - 1) // DEC A
                0xF0 -> { val d = signed(codeByte()); if (zero) pc += d } // BEQ
                0xD0 -> { val d = signed(codeByte()); if (!zero) pc += d } // BNE
                0x80 -> { val d = signed(codeByte()); pc += d } // BRA
                0xAD -> setA(memory.readWord(codeWord())) // LDA absolute
                0x3D -> setA(a and memory.readWord((x + codeWord()) and 0xFFFF)) // AND abs,X
                0xDD -> { // CMP abs,X
                    val operand = memory.readWord((x + codeWord()) and 0xFFFF)
                    carry = a >= operand
                    zero = a == operand
                }
                0xB0 -> { val d = signed(codeByte()); if (carry) pc += d } // BCS
                0xDA -> stack += x // PHX
                0x22 -> { // JSL $80:818E, the verified vanilla bit-index helper
                    val address = codeByte() or (codeByte() shl 8) or (codeByte() shl 16)
                    check(address == 0x80818E)
                    val bitIndex = a
                    x = bitIndex ushr 3
                    memory.word(0x05E7, 1 shl (bitIndex and 7))
                    setA(x)
                }
                0xBF -> {
                    val address = codeByte() or (codeByte() shl 8) or (codeByte() shl 16)
                    setA(memory.readWord(address + x))
                }
                0x2D -> setA(a and memory.readWord(codeWord())) // AND absolute
                0xFA -> { x = stack.removeLast(); zero = x == 0 } // PLX
                0xEB -> setA(((a and 0xFF) shl 8) or ((a ushr 8) and 0xFF)) // XBA
                0xAA -> x = a // TAX
                0x4C -> {
                    val address = codeWord()
                    check(address == 0xE5E6)
                    return x == 0xBEEF
                }
                0x8A -> setA(x) // TXA
                0x18 -> carry = false // CLC
                0x69 -> { // ADC immediate
                    val operand = codeWord()
                    val sum = a + operand + if (carry) 1 else 0
                    carry = sum > 0xFFFF
                    setA(sum)
                }
                0x60 -> return false // RTS to the selector loop
                else -> error("Unsupported generated opcode \$${opcode.toString(16)} at ${pc - 1}")
            }
        }
        error("Generated predicate did not terminate")
    }
}
