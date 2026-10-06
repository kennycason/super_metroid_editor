package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatchRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class IpsExportTest {
    @Test
    fun `IPS diff records expansion and exact target size`() {
        val original = byteArrayOf(1, 2)
        val patched = byteArrayOf(1, 3, 0, 0, 9, 0)

        val ips = buildIpsPatch(original, patched)
        assertContentEquals(byteArrayOf(0, 0, patched.size.toByte()), ips.takeLast(3).toByteArray())

        val rebuilt = original.copyOf(patched.size)
        for (write in PatchRepository.parseIps(ips)) {
            for (index in write.bytes.indices) {
                rebuilt[write.offset.toInt() + index] = write.bytes[index].toByte()
            }
        }
        assertContentEquals(patched, rebuilt)
        assertEquals("EOF", ips.copyOfRange(ips.size - 6, ips.size - 3).decodeToString())
    }
}
