package com.supermetroid.editor.rom

import kotlin.test.*

class ControllerBindingsTest {
    private fun rom(header: Int = 0): ByteArray = ByteArray(0x300000 + header).also { bytes ->
        fun word(pc: Int, value: Int) { bytes[pc + header] = value.toByte(); bytes[pc + header + 1] = (value ushr 8).toByte() }
        ControllerBindings.menuMasks.forEachIndexed { index, mask -> word(0x17575 + index * 2, mask) }
        ControllerBindings.slots.forEachIndexed { index, slot -> word(0x1754A + index * 2, slot.ramAddress) }
        for (slot in ControllerBindings.slots) for (snes in listOf(slot.newSaveOperand, slot.resetOperand)) {
            val at = ControllerBindings.pc(snes) + header
            bytes[at - 1] = 0xA9.toByte()
            bytes[at] = slot.defaultMask.toByte(); bytes[at + 1] = (slot.defaultMask ushr 8).toByte()
            bytes[at + 2] = 0x8D.toByte()
            bytes[at + 3] = slot.ramAddress.toByte(); bytes[at + 4] = (slot.ramAddress ushr 8).toByte()
        }
    }

    @Test fun `occupied buttons swap in new game and options reset without touching button decoder`() {
        for (header in listOf(0, 512)) {
            val bytes = rom(header); val before = bytes.copyOf()
            val writes = ControllerBindings.writes(bytes, mapOf("jump" to 0x4000))
            assertEquals(setOf(0xB325, 0x17233, 0xB337, 0x1723F), writes.map { it.pc }.toSet())
            for (write in writes) {
                bytes[header + write.pc] = write.value.toByte()
                bytes[header + write.pc + 1] = (write.value ushr 8).toByte()
            }
            for (reset in listOf(false, true)) {
                val mapping = ControllerBindings.read(bytes, reset)
                assertEquals(0x4000, mapping["jump"]); assertEquals(0x0080, mapping["item_cancel"])
                ControllerBindings.requireMapping(mapping)
            }
            val allowed = writes.flatMap { listOf(header + it.pc, header + it.pc + 1) }.toSet()
            assertTrue(bytes.indices.all { it in allowed || bytes[it] == before[it] })
            assertContentEquals(before.copyOfRange(header + 0x17575, header + 0x17587), bytes.copyOfRange(header + 0x17575, header + 0x17587))
        }
    }

    @Test fun `invalid mappings and changed initialization bindings fail before producing writes`() {
        assertFailsWith<IllegalArgumentException> { ControllerBindings.resolve(ControllerBindings.defaults, mapOf("jump" to 0x1000)) }
        assertFailsWith<IllegalArgumentException> { ControllerBindings.resolve(ControllerBindings.defaults, mapOf("jump" to 0x40, "dash" to 0x40)) }
        assertFailsWith<IllegalArgumentException> { ControllerBindings.resolve(ControllerBindings.defaults, mapOf("typo" to 0x80)) }
        val bytes = rom(); bytes[0xB328] = 0
        assertFailsWith<IllegalArgumentException> { ControllerBindings.writes(bytes, mapOf("jump" to 0x4000)) }
        assertTrue(ControllerBindings.writes(bytes, emptyMap()).isEmpty(), "Untouched defaults are a no-op, even with custom game code")
        val oldBrokenPatch = rom(); oldBrokenPatch[0x17577] = 0; oldBrokenPatch[0x17578] = 0x40
        assertFailsWith<IllegalArgumentException> { ControllerBindings.writes(oldBrokenPatch, mapOf("jump" to 0x4000)) }
    }

    @Test fun `every action can take every supported button and successive edits preserve a permutation`() {
        var base = ControllerBindings.defaults
        for (slot in ControllerBindings.slots) for (mask in ControllerBindings.masks) {
            val result = ControllerBindings.swap(base, slot.key, mask)
            ControllerBindings.requireMapping(result); assertEquals(mask, result[slot.key])
            assertTrue(result.filter { (key, value) -> base[key] != value }.size <= 2)
            base = result
        }
        assertEquals(listOf("angle_up", "angle_down"), ControllerBindings.slots.takeLast(2).map { it.key })
    }
}
