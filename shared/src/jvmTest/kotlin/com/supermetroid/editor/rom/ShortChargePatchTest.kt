package com.supermetroid.editor.rom

import kotlin.test.*

class ShortChargePatchTest {
    @Test fun `stage choices produce the requested number of native transitions and preserve authored delays`() {
        for (delay in listOf(1, 2, 127, 255)) for (stages in 0..4) {
            val original = listOf(delay, 3, 5, 7, 9)
            val table = ShortChargePatch.encode(stages, original)
            assertEquals(stages, ShortChargePatch.decode(table))
            assertEquals(delay, table.first() and 255)
            assertEquals(original.drop(1), table.drop(1))
            var packed = table.first(); var transitions = 0; var elapsed = 0
            while ((packed ushr 8) and 4 == 0) {
                packed = (packed - 1) and 65535; elapsed++
                if (packed and 255 == 0) {
                    val counter = (packed ushr 8) + 1
                    packed = (counter shl 8) or table[counter]
                    transitions++
                }
                assertTrue(elapsed <= 1024)
            }
            assertEquals(stages, transitions)
        }
    }
    @Test fun `legacy aliases must agree and empty settings invent no stage override`() {
        assertNull(ShortChargePatch.requestedStages(null, emptyMap()))
        assertEquals(2, ShortChargePatch.requestedStages(2, mapOf("stages" to 2, "taps" to 2)))
        for (data in listOf(mapOf("stages" to 1, "taps" to 2), mapOf("stages" to 5), mapOf("other" to 1)))
            assertFailsWith<IllegalArgumentException> { ShortChargePatch.requestedStages(null, data) }
        assertFailsWith<IllegalArgumentException> { ShortChargePatch.requestedStages(4, mapOf("taps" to 2)) }
    }
    @Test fun `underflow counter bits and incomplete tables are not coerced into GUI stages`() {
        for (table in listOf(listOf(0, 1, 1, 1, 2), listOf(0x0501, 1, 1, 1, 2), listOf(1, 0, 1, 1, 2),
            listOf(1, 0x0101, 1, 1, 2), listOf(1, 1, 1, 1))) {
            assertNull(ShortChargePatch.decode(table))
            assertFailsWith<IllegalArgumentException> { ShortChargePatch.encode(2, table) }
        }
    }
}
