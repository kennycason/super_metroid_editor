package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

class NspcPhrasePackingTest {
    private fun expand(plan: NspcPhrasePacking.Plan, start: Int): List<ByteArray?> {
        val ram = ByteArray(65536)
        val streams = plan.render(start)
        var at = start
        val addresses = streams.map { bytes -> bytes?.let { at.also { address -> it.copyInto(ram, address); at += it.size } } }
        for (body in plan.phrases) { body.copyInto(ram, at); at += body.size }
        fun byte(at: Int) = ram[at].toInt() and 255
        fun body(address: Int, nested: Boolean): List<Byte> {
            var cursor = address
            val out = mutableListOf<Byte>()
            repeat(100_000) {
                val opcode = byte(cursor++)
                if (opcode == 0) return out
                if (opcode == 0xEF) {
                    assertFalse(nested, "The native engine has one subroutine return address per voice")
                    val target = byte(cursor) or (byte(cursor + 1) shl 8)
                    val count = byte(cursor + 2); cursor += 3
                    assertTrue(count in 1..255)
                    val bytes = body(target, true)
                    repeat(count) { out += bytes }
                } else {
                    out += opcode.toByte()
                    if (opcode in 1..127) {
                        if (byte(cursor) < 0x80) out += ram[cursor++]
                        assertTrue(byte(cursor) in 0x80..0xDF); out += ram[cursor++]
                    } else if (opcode >= 0xE0) repeat(NspcSequence.commandParameterCount(opcode) ?: 0) { out += ram[cursor++] }
                }
            }
            error("Unterminated packed stream")
        }
        return addresses.map { it?.let { (body(it, false) + 0.toByte()).toByteArray() } }
    }

    @Test fun `random repeated native phrases expand to exact original bytes at different relocated addresses`() {
        val random = Random(401)
        repeat(32) {
            val motif = buildList<Byte> {
                repeat(8) {
                    addAll(listOf(0xED.toByte(), random.nextInt(256).toByte(), 24, 0x7F, (0x90 + random.nextInt(24)).toByte()))
                    addAll(listOf(0xE3.toByte(), 0, 0xEF.toByte(), 0xFF.toByte())) // Operand bytes cannot become calls/END.
                }
            }
            val raw = (listOf<Byte>(0xE0.toByte(), 24) + List(6) { motif }.flatten() + 0.toByte()).toByteArray()
            val channels = listOf(raw, null, raw, byteArrayOf(24, 0x7F, 0xA4.toByte(), 0))
            val plan = NspcPhrasePacking.pack(channels)
            assertTrue(plan.size < plan.flatBytes / 2)
            assertTrue(plan.phrases.isNotEmpty()); assertTrue(plan.calls > 0)
            for (base in listOf(0x6000, 0x9800)) {
                val expanded = expand(plan, base)
                channels.indices.forEach { ch -> assertContentEquals(channels[ch], expanded[ch]) }
            }
        }
    }

    @Test fun `long runs split native repeat counts and unprofitable data stays literal`() {
        val raw = (listOf<Byte>(1, 0x7F, 0xA4.toByte()) + List(70_000) { 0xA4.toByte() } + 0.toByte()).toByteArray()
        val plan = NspcPhrasePacking.pack(listOf(raw))
        assertTrue(plan.size < 250); assertTrue(plan.calls >= 2)
        assertTrue(plan.streams.single()!!.fixups.any { (plan.streams.single()!!.bytes[it.offset + 2].toInt() and 255) == 255 })
        assertContentEquals(raw, expand(plan, 0x6000).single())
        val unique = byteArrayOf(0xE7.toByte(), 36, 0xE0.toByte(), 24, 48, 0x7F, 0xA4.toByte(), 0)
        val literal = NspcPhrasePacking.pack(listOf(unique))
        assertEquals(unique.size, literal.size); assertTrue(literal.phrases.isEmpty())
        assertContentEquals(unique, literal.render(0x6000).single())
    }
}
