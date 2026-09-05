package com.supermetroid.editor.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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

    @Test
    fun `echolocation beam bundles verified projectile and Samus contact hooks`() {
        val patch = assertNotNull(
            PatchRepository.loadBundledPatches().firstOrNull { it.id == "bundled_echolocation_beam" }
        )

        assertEquals("Echolocation Beam", patch.name)
        assertEquals(
            listOf(
                0x8EB6FL, 0x8EFD2L, 0x8EFD8L, 0x8FFEEL,
                0xA22AAL, 0xA22C3L, 0xA2339L, 0xA234BL, 0xA23D0L, 0xA2462L, 0xA319FL,
            ),
            patch.writes.map { it.offset },
        )
        assertContentEquals(listOf(0x22, 0xE7, 0xB1, 0x94, 0x60), patch.writes[0].bytes)
        assertContentEquals(listOf(0xEE, 0xFF), patch.writes[1].bytes)
        assertContentEquals(listOf(0xEE, 0xFF), patch.writes[2].bytes)
        assertContentEquals(
            listOf(0x22, 0xDD, 0xB1, 0x94, 0xE0, 0x02, 0x00, 0xF0, 0x03, 0x4C, 0xDF, 0xEF, 0x4C, 0x10, 0xF0),
            patch.writes[3].bytes,
        )
        assertContentEquals(listOf(0x20, 0x9F, 0xB1), patch.writes[4].bytes)
        assertContentEquals(listOf(0x22, 0xC3, 0xB1, 0x94), patch.writes[5].bytes)
        assertContentEquals(listOf(0x20, 0xB1, 0xB1), patch.writes[6].bytes)
        assertContentEquals(listOf(0x22, 0xC3, 0xB1, 0x94), patch.writes[7].bytes)
        assertContentEquals(listOf(0x20, 0x9F, 0xB1), patch.writes[8].bytes)
        assertContentEquals(listOf(0x20, 0xB1, 0xB1), patch.writes[9].bytes)

        val payload = patch.writes[10].bytes
        assertEquals(88, payload.size)
        assertContentEquals(
            listOf(
                0x20, 0xB5, 0xA1, 0x90, 0x0B, 0x48, 0xA9, 0x36, 0x00,
                0x22, 0x21, 0x90, 0x80, 0x68, 0x38, 0x60, 0x18, 0x60,
            ),
            payload.take(18),
        )
        assertContentEquals(
            listOf(
                0x08, 0x48, 0xAF, 0xF5, 0x05, 0x7E, 0x48, 0xA9, 0x01, 0x00, 0x8F, 0xF5, 0x05,
                0x7E, 0x22, 0x06, 0xAE, 0x90, 0x68, 0x8F, 0xF5, 0x05, 0x7E, 0x68, 0x28, 0x6B,
            ),
            payload.slice(36 until 62),
        )
        assertContentEquals(
            listOf(0x48, 0xA9, 0x37, 0x00, 0x22, 0x21, 0x90, 0x80, 0x68, 0x6B),
            payload.slice(62 until 72),
        )
        assertContentEquals(
            listOf(0x08, 0x48, 0xA9, 0x37, 0x00, 0x22, 0x21, 0x90, 0x80, 0x68, 0x28, 0x9C, 0xCE, 0x0D, 0x38, 0x6B),
            payload.drop(72),
        )
        assertEquals(9, patch.resources.size)
        assertEquals("rom_hook", patch.resources.first().namespace)
    }
}
