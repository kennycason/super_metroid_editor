package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.*
import kotlin.test.*

class AsmArrivalScrollProgramTest {
    @Test fun `literal and named long writes retain order duplicates values and 16 bit byte expansion`() {
        val program = assertNotNull(AsmArrivalScrollProgram.parse("""
            php
            lda.w #${'$'}0201
            sta.l Scrolls+1
            php
            sep #${'$'}20
            lda.b #${'$'}00 ; handwritten note
            sta.l ${'$'}7ECD21
            plp
            plp
            rts
        """.trimIndent()))
        assertEquals(listOf(DoorScrollWrite(1, 0x21), DoorScrollWrite(2, 0x22), DoorScrollWrite(0, 0x21)), program.writes)
        val rendered = AsmArrivalScrollProgram.render("SmeditArrival_91F8_Door_0", program.writes, "DoorASM_Test", listOf("; retain me"),
            program.finalAccumulator, program.highByteKnown)
        val body = rendered.substringAfter("()\n").substringBefore("endmacro")
        val decoded = assertNotNull(AsmArrivalScrollProgram.parse(body))
        assertEquals(program.writes, decoded.writes); assertEquals("DoorASM_Test", decoded.nativeContext)
        assertFalse(decoded.delegatesToNative); assertContains(body, "; retain me")
        assertEquals(0x0200, decoded.finalAccumulator); assertTrue(decoded.highByteKnown)
    }

    @Test fun `split word code preserves return accumulator flags stack and mapped memory effects instruction by instruction`() {
        val original = "php\nlda.w #\$0201\nsta.l \$7ECD21\nplp\nrts"
        val program = assertNotNull(AsmArrivalScrollProgram.parse(original))
        val resized = AsmArrivalScrollProgram.render("SmeditArrival_91F8_Door_0",
            listOf(DoorScrollWrite(1, 0x21), DoorScrollWrite(2, 0x23)), null,
            finalAccumulator = program.finalAccumulator, highByteKnown = program.highByteKnown)
            .substringAfter("()\n").substringBefore("endmacro")
        fun execute(body: String): Pair<List<Int>, Map<Int, Int>> {
            var a = 0xF123; var p = 0x85
            val stack = mutableListOf<Int>(); val memory = linkedMapOf<Int, Int>()
            for (line in AsmArrivalScrollProgram.code(body)) {
                val operation = line.substringBefore(' ').substringBefore('.').uppercase()
                val operand = line.substringAfter(' ', "").trim()
                fun literal(value: String) = requireNotNull(AsmPlmDocument.literal(value))
                when (operation) {
                    "PHP" -> stack.add(p)
                    "PLP" -> p = stack.removeAt(stack.lastIndex)
                    "SEP" -> p = p or literal(operand.drop(1))
                    "REP" -> p = p and literal(operand.drop(1)).inv()
                    "LDA" -> {
                        val small = p and 0x20 != 0; val value = literal(operand.drop(1))
                        a = if (small) (a and 0xFF00) or value else value
                        p = (p and 0x82.inv()) or (if (value == 0) 2 else 0) or
                            (if (value and (if (small) 128 else 32768) != 0) 128 else 0)
                    }
                    "STA" -> {
                        val address = literal(operand)
                        memory[address] = a and 255
                        if (p and 0x20 == 0) memory[address + 1] = a ushr 8
                    }
                    "RTS" -> Unit
                    else -> error("Unproven instruction $line")
                }
            }
            return listOf(a, p, stack.size) to memory
        }
        val before = execute(original); val after = execute(resized)
        assertEquals(listOf(0x0201, 0x85, 0), before.first); assertEquals(before.first, after.first)
        assertEquals(mapOf(0x7ECD21 to 1, 0x7ECD22 to 2), before.second)
        assertEquals(mapOf(0x7ECD21 to 1, 0x7ECD23 to 2), after.second)
    }

    @Test fun `native scaffold is distinguished from an inline managed body`() {
        val scaffold = AsmDoorArrivalsSource.render(0x91F8, 0, "DoorASM_Test").substringAfter("()\n").substringBefore("endmacro")
        val parsed = assertNotNull(AsmArrivalScrollProgram.parse(scaffold))
        assertTrue(parsed.delegatesToNative); assertEquals("DoorASM_Test", parsed.nativeContext)
        assertTrue(assertNotNull(AsmArrivalScrollProgram.parse("rts")).writes.isEmpty())
    }

    @Test fun `branches data unknown DB effects unbalanced stacks and unknown high accumulator bytes are opaque`() {
        val valid = "php\nsep #\$20\nlda.b #\$02\nsta.l \$7ECD23\nplp\nrts"
        for (body in listOf(valid.replace("sta.l", "sta.w"), valid.replace("sta.l \$7ECD23", "sta.l \$7E0E1E"),
            valid.replace("lda.b #\$02", "jsr.w Other"), valid.replace("lda.b #\$02", "bra .else"),
            valid.replace("plp\n", ""), valid.replace("php\n", ""), "$valid\ndb \$00",
            valid.replace("sta.l", "rep #\$20\nsta.l"),
            // A balanced stack is insufficient if the original flags frame is popped
            // early and replaced after changing M: normalization would change return P.
            valid.replace("sep #\$20", "plp\nsep #\$20\nphp"))) assertNull(AsmArrivalScrollProgram.parse(body), body)
        val rom = ByteArray(0x300000)
        // An eight-bit load does not define B. A subsequent sixteen-bit store cannot be
        // replaced with a generated high-byte zero, even if all its opcodes are familiar.
        byteArrayOf(0x08, 0xE2.toByte(), 0x20, 0xA9.toByte(), 2, 0xC2.toByte(), 0x20,
            0x8F.toByte(), 0x23, 0xCD.toByte(), 0x7E, 0x28, 0x60).copyInto(rom, 0x79500)
        val analysis = analyzeDoorScrollAsm(RomParser(rom), 0x9500)
        assertFalse(analysis.isPureScrollRoutine); assertTrue(analysis.hasOtherEffects)
    }
}
