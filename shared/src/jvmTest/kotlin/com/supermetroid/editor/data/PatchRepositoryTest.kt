package com.supermetroid.editor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PatchRepositoryTest {
    @Test
    fun `parses normal and RLE records with a required EOF`() {
        val ips = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(),
            0x00, 0x01, 0x00, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte(),
            0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x03, 0xCC.toByte(),
            'E'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(),
        )

        assertEquals(
            listOf(
                PatchWrite(0x100, listOf(0xAA, 0xBB)),
                PatchWrite(0x200, listOf(0xCC, 0xCC, 0xCC)),
            ),
            PatchRepository.parseIps(ips),
        )
    }

    @Test
    fun `accepts the standard optional three-byte post-EOF size`() {
        val ips = "PATCHEOF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x40, 0x00, 0x00)
        assertEquals(emptyList(), PatchRepository.parseIps(ips))
    }

    @Test
    fun `rejects every truncated record form instead of returning a partial patch`() {
        val validPrefix = "PATCH".toByteArray(Charsets.US_ASCII)
        val malformed = listOf(
            validPrefix,
            validPrefix + byteArrayOf(0x00, 0x01),
            validPrefix + byteArrayOf(0x00, 0x01, 0x00, 0x00),
            validPrefix + byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x02, 0xAA.toByte()),
            validPrefix + byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x03),
        )

        for (ips in malformed) {
            assertFailsWith<IllegalArgumentException> { PatchRepository.parseIps(ips) }
        }
    }

    @Test
    fun `rejects zero-length RLE and invalid trailing bytes`() {
        val zeroRle = "PATCH".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F, 0x45, 0x4F, 0x46)
        val trailing = "PATCHEOF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)

        assertFailsWith<IllegalArgumentException> { PatchRepository.parseIps(zeroRle) }
        assertFailsWith<IllegalArgumentException> { PatchRepository.parseIps(trailing) }
    }
}
