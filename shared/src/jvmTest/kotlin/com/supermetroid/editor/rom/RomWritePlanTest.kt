package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RomWritePlanTest {
    @Test
    fun `stages compatible writes and preserves immutable base`() {
        val base = ByteArray(32) { it.toByte() }
        val plan = RomWritePlan(base)

        plan.add(
            owner = "patch:first",
            label = "first write",
            offset = 4,
            bytes = listOf(0xAA, 0xBB),
            kind = RomWriteKind.FIXED_PATCH,
            expectedBefore = listOf(4, 5),
        )
        plan.add("config:bombs", "bomb value", 12, listOf(0x34, 0x12), RomWriteKind.CONFIG)

        assertEquals(4, base[4].toInt())
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), plan.finalRom().copyOfRange(4, 6))
        assertEquals(2, plan.report().totalWrites)
        assertEquals(4, plan.report().totalBytes)
    }

    @Test
    fun `rejects overlapping writes without applying the second write`() {
        val plan = RomWritePlan(ByteArray(32))
        plan.add("patch:first", "first", 8, listOf(1, 2, 3), RomWriteKind.FIXED_PATCH)

        val error = assertFailsWith<RomWriteConflictException> {
            plan.add("patch:second", "second", 10, listOf(9, 9), RomWriteKind.FIXED_PATCH)
        }

        assertTrue(error.message!!.contains("patch:first"))
        assertTrue(error.message!!.contains("patch:second"))
        assertContentEquals(byteArrayOf(1, 2, 3, 0), plan.finalRom().copyOfRange(8, 12))
        assertEquals(1, plan.report().totalWrites)
    }

    @Test
    fun `explicit identical overlap coalesces shared data but differing data fails`() {
        val plan = RomWritePlan(ByteArray(16))
        plan.add("palette:15", "shared palette", 4, listOf(1, 2))
        plan.add(
            owner = "palette:17",
            label = "shared palette alias",
            offset = 4,
            bytes = listOf(1, 2),
            overlapPolicy = RomOverlapPolicy.ALLOW_IDENTICAL,
        )

        assertFailsWith<RomWriteConflictException> {
            plan.add(
                owner = "palette:19",
                label = "different shared palette",
                offset = 4,
                bytes = listOf(1, 3),
                overlapPolicy = RomOverlapPolicy.ALLOW_IDENTICAL,
            )
        }
        assertContentEquals(byteArrayOf(1, 2), plan.finalRom().copyOfRange(4, 6))
    }

    @Test
    fun `rejects stale expected bytes`() {
        val plan = RomWritePlan(ByteArray(16) { 0x55 })

        val error = assertFailsWith<RomWritePreconditionException> {
            plan.add(
                owner = "patch:stale",
                label = "hook",
                offset = 2,
                bytes = listOf(0x22, 0x00),
                expectedBefore = listOf(0x55, 0x99),
            )
        }

        assertTrue(error.message!!.contains("expected 0x99, found 0x55"))
        assertTrue(plan.finalRom().all { it == 0x55.toByte() })
    }

    @Test
    fun `captures legacy mutation and detects collision with earlier owner`() {
        val plan = RomWritePlan(ByteArray(32))
        plan.add("patch:first", "owned byte", 6, listOf(1))

        assertFailsWith<RomWriteConflictException> {
            plan.capture("room:91F8", "room export", RomWriteKind.ROOM) { rom ->
                rom[6] = 2
                rom[7] = 3
            }
        }

        assertEquals(1, plan.finalRom()[6].toInt())
        assertEquals(0, plan.finalRom()[7].toInt())
        assertEquals(1, plan.report().totalWrites)
    }

    @Test
    fun `stateful exporter can evolve its own bytes but not another owner's bytes`() {
        val plan = RomWritePlan(ByteArray(16))
        plan.capture(
            "room-graph",
            "first room",
            RomWriteKind.ROOM,
            RomOverlapPolicy.ALLOW_SAME_OWNER,
        ) { it[4] = 1 }
        plan.capture(
            "room-graph",
            "second room rebuild",
            RomWriteKind.ROOM,
            RomOverlapPolicy.ALLOW_SAME_OWNER,
        ) { it[4] = 2 }

        assertEquals(2, plan.finalRom()[4].toInt())
        assertFailsWith<RomWriteConflictException> {
            plan.capture(
                "different subsystem",
                "foreign overwrite",
                RomWriteKind.GRAPHICS,
                RomOverlapPolicy.ALLOW_SAME_OWNER,
            ) { it[4] = 3 }
        }
    }

    @Test
    fun `declared allocation owns unchanged ff bytes`() {
        val plan = RomWritePlan(ByteArray(16) { 0xFF.toByte() })
        val before = plan.romData.copyOf()
        plan.romData[4] = 0x12
        plan.recordDeclaredMutation(
            before = before,
            declaredWrites = listOf(
                RomWriteIntent(
                    owner = "generated:names",
                    label = "allocated payload",
                    offset = 4,
                    bytes = listOf(0x12, 0xFF),
                    expectedBefore = listOf(0xFF, 0xFF),
                    kind = RomWriteKind.ALLOCATION,
                )
            ),
        )

        assertFailsWith<RomWriteConflictException> {
            plan.add("generated:later", "later allocation", 5, listOf(0x34), RomWriteKind.ALLOCATION)
        }
        assertEquals(0xFF.toByte(), plan.finalRom()[5])
    }

    @Test
    fun `current range claim owns unchanged allocation bytes`() {
        val plan = RomWritePlan(ByteArray(32) { 0xFF.toByte() })
        plan.add("allocator", "payload byte", 10, listOf(0x42))
        plan.claimCurrentRange("allocator", "full allocation", 10, 4)

        assertFailsWith<RomWriteConflictException> {
            plan.add("later feature", "hidden collision", 12, listOf(0x99))
        }
        assertEquals(
            listOf(0x42, 0xFF, 0xFF, 0xFF),
            plan.finalRom().slice(10..13).map { it.toInt() and 0xFF },
        )
        assertFailsWith<RomWriteConflictException> {
            plan.claimCurrentRange("different allocator", "duplicate allocation", 10, 4)
        }
    }

    @Test
    fun `maps unheadered offsets without modifying copier header`() {
        val headerSize = 4
        val base = ByteArray(20)
        base.fill(0x7E, 0, headerSize)
        val plan = RomWritePlan(base, headerSize)

        plan.add("patch:test", "body", 0, listOf(0xAA))

        assertContentEquals(ByteArray(headerSize) { 0x7E }, plan.finalRom().copyOfRange(0, headerSize))
        assertEquals(0xAA.toByte(), plan.finalRom()[headerSize])
    }

    @Test
    fun `rejects incompatible exclusive runtime resource claims`() {
        val plan = RomWritePlan(ByteArray(32))
        plan.claimResource(
            RomResourceClaim("spider", "wram", 0x0B1C, 0x0B1F, "Spider state")
        )

        val error = assertFailsWith<RomResourceConflictException> {
            plan.claimResource(
                RomResourceClaim("other", "wram", 0x0B1E, 0x0B20, "Other state")
            )
        }

        assertTrue(error.message!!.contains("wram resource conflict"))
    }

    @Test
    fun `allows explicitly shared resource group`() {
        val plan = RomWritePlan(ByteArray(32))
        plan.claimResource(
            RomResourceClaim(
                "boss flags",
                "hook",
                0x1096E,
                0x10971,
                "combined hook",
                RomResourceAccess.SHARED,
                "per-frame-hook",
            )
        )
        plan.claimResource(
            RomResourceClaim(
                "hyper beam",
                "hook",
                0x1096E,
                0x10971,
                "combined hook",
                RomResourceAccess.SHARED,
                "per-frame-hook",
            )
        )

        assertEquals(2, plan.report().resources.size)
    }
}
