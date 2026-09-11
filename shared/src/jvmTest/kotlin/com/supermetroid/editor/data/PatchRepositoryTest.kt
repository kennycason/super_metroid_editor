package com.supermetroid.editor.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun `spider ball label graphics do not overwrite Varia wireframe tiles`() {
        val patches = PatchRepository.loadBundledPatches().filter {
            it.id == "bundled_spider_ball" || it.id == "bundled_spider_ball_hold_aim_down"
        }
        assertEquals(2, patches.size)

        for (patch in patches) {
            val labelTileIds = patch.resources
                .filter { it.namespace == "pause_bg_tile" }
                .flatMap { it.start..it.endInclusive }

            assertEquals(
                (0x23E..0x241).toList() + (0x257..0x25A).toList(),
                labelTileIds,
            )
            assertTrue(labelTileIds.toSet().intersect((0x1E0..0x1E7).toSet()).isEmpty())

            // Pause BG tiles $0200-$02FF read from the pause/menu sprite graphics
            // loaded from $B6:C000-$DFFF. Only the two audited four-tile holes may
            // be replaced; Varia's $01E0-$01E7 remain in $B6:BC00-$BCFF.
            val pauseCharacterWrites = patch.writes.filter { write ->
                rangesOverlap(
                    write.offset,
                    write.offset + write.bytes.size,
                    loromPc(0xB6, 0x8000),
                    loromPc(0xB6, 0xE000),
                )
            }
            assertEquals(
                listOf(loromPc(0xB6, 0xC7C0), loromPc(0xB6, 0xCAE0)),
                pauseCharacterWrites.map { it.offset },
            )
            assertEquals(listOf(0x80, 0x80), pauseCharacterWrites.map { it.bytes.size })

            val variaGraphicsStart = loromPc(0xB6, 0x8000 + 0x1E0 * 32)
            val variaGraphicsEnd = loromPc(0xB6, 0x8000 + 0x1E8 * 32)
            assertTrue(patch.writes.none { write ->
                rangesOverlap(
                    write.offset,
                    write.offset + write.bytes.size,
                    variaGraphicsStart,
                    variaGraphicsEnd,
                )
            })

            val equipmentData = assertNotNull(
                patch.writes.firstOrNull { it.offset == loromPc(0x82, 0xF7C0) }
            ).bytes
            val spiderLabelTilemap = listOf(0x08FF) + labelTileIds.map { 0x0800 or it }
            val spiderLabelBytes = spiderLabelTilemap.flatMap { listOf(it and 0xFF, it ushr 8) }
            assertTrue(equipmentData.windowed(spiderLabelBytes.size).any { it == spiderLabelBytes })
        }
    }

    @Test
    fun `spider ball activation variants share assets but use distinct movement gates`() {
        val patches = PatchRepository.loadBundledPatches()
        val directional = assertNotNull(patches.firstOrNull { it.id == "bundled_spider_ball" })
        val holdAimDown = assertNotNull(
            patches.firstOrNull { it.id == "bundled_spider_ball_hold_aim_down" }
        )
        val directionalCodeOffset = loromPc(0x90, 0xF800)
        val holdCodeOffset = loromPc(0x90, 0xF700)
        val directionalCode = assertNotNull(
            directional.writes.firstOrNull { it.offset == directionalCodeOffset }
        )
        val holdCode = assertNotNull(holdAimDown.writes.firstOrNull { it.offset == holdCodeOffset })

        assertEquals("spider_ball_activation", directional.exclusiveGroup)
        assertEquals(directional.exclusiveGroup, holdAimDown.exclusiveGroup)
        assertEquals(directional.customItems, holdAimDown.customItems)
        assertEquals(directional.resources, holdAimDown.resources)
        assertEquals(2019, directionalCode.bytes.size)
        assertEquals(2048, holdCode.bytes.size)

        // AND $09BC reads Super Metroid's configurable Aim Down binding. The
        // hold variant is only a gate around the shared directional path; it
        // does not introduce a second state or surface-selection sentinel.
        assertTrue(holdCode.bytes.containsSequence(listOf(0x2D, 0xBC, 0x09)))
        assertTrue(!holdCode.bytes.containsSequence(listOf(0xA9, 0xA5, 0xA5, 0x8D, 0x1C, 0x0B)))
        assertTrue(!directionalCode.bytes.containsSequence(listOf(0x2D, 0xBC, 0x09)))

        val movementHooks = setOf(
            loromPc(0x90, 0xA353),
            loromPc(0x90, 0xA35B),
            loromPc(0x90, 0xA36D),
            loromPc(0x90, 0xA36F),
            loromPc(0x90, 0xA371),
        )
        val directionalSharedWrites = directional.writes.filterNot {
            it.offset == directionalCodeOffset || it.offset in movementHooks
        }
        val holdSharedWrites = holdAimDown.writes.filterNot {
            it.offset == holdCodeOffset || it.offset in movementHooks
        }
        assertEquals(directionalSharedWrites, holdSharedWrites)
        assertEquals(
            movementHooks,
            directional.writes.filter { it.offset in movementHooks }.map { it.offset }.toSet(),
        )
        assertEquals(
            movementHooks,
            holdAimDown.writes.filter { it.offset in movementHooks }.map { it.offset }.toSet(),
        )
        assertTrue(
            directional.writes
                .filter { it.offset in movementHooks }
                .all { it.bytes == listOf(0x00, 0xF8) }
        )
        assertTrue(
            holdAimDown.writes
                .filter { it.offset in movementHooks }
                .all { it.bytes == listOf(0x00, 0xF7) }
        )
    }

    private fun List<Int>.containsSequence(sequence: List<Int>): Boolean =
        windowed(sequence.size).any { it == sequence }

    private fun loromPc(bank: Int, address: Int): Long =
        ((bank and 0x7F) * 0x8000L) + (address and 0x7FFF)

    private fun rangesOverlap(startA: Long, endA: Long, startB: Long, endB: Long): Boolean =
        startA < endB && startB < endA
}
