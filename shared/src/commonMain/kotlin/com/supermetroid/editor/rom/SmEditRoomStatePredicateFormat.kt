package com.supermetroid.editor.rom

/**
 * SMEDIT's typed room-state predicate ABI.
 *
 * The vanilla room-state dispatcher calls a selector with X pointing at the
 * bytes immediately after its two-byte routine pointer. Every generated entry
 * has the same six-byte payload:
 *
 *     predicate type : byte
 *     flags          : byte (bit 0 = invert)
 *     value          : word
 *     state pointer  : word
 *
 * The shared bank-$8F routine below evaluates the predicate, jumps to the
 * vanilla matching-state handler on success, or skips all six payload bytes
 * and returns to the dispatch loop on failure. An unreachable signature makes
 * exported predicates safely recognizable when the ROM is reopened.
 */
object SmEditRoomStatePredicateFormat {
    const val ENTRY_SIZE_BYTES = 8
    const val FLAG_INVERTED = 0x01

    const val TYPE_EQUIPMENT = 1
    const val TYPE_BEAM = 2
    const val TYPE_MAX_MISSILES = 3
    const val TYPE_MAX_SUPER_MISSILES = 4
    const val TYPE_MAX_POWER_BOMBS = 5
    const val TYPE_MAX_ENERGY = 6
    const val TYPE_MAX_RESERVE_ENERGY = 7
    const val TYPE_ITEM_PICKUP = 8
    const val TYPE_BOSS = 9

    private class RoutineBuilder {
        private val bytes = mutableListOf<Int>()
        private val labels = mutableMapOf<String, Int>()
        private val relativeFixups = mutableListOf<Pair<Int, String>>()

        fun emit(vararg values: Int) {
            bytes.addAll(values.toList())
        }

        fun label(name: String) {
            check(labels.put(name, bytes.size) == null) { "Duplicate routine label $name" }
        }

        fun branch(opcode: Int, target: String) {
            emit(opcode, 0)
            relativeFixups += (bytes.lastIndex to target)
        }

        fun build(): ByteArray {
            for ((operandIndex, targetName) in relativeFixups) {
                val target = checkNotNull(labels[targetName]) { "Missing routine label $targetName" }
                val displacement = target - (operandIndex + 1)
                check(displacement in -128..127) {
                    "Branch to $targetName is out of range ($displacement bytes)"
                }
                bytes[operandIndex] = displacement and 0xFF
            }
            return bytes.map(Int::toByte).toByteArray()
        }
    }

    /** Pre-assembled 65816 code; labels are resolved here to keep branch offsets auditable. */
    private fun buildRoutine(fixedPersistentBitFlags: Boolean): ByteArray = RoutineBuilder().run {
        // A = predicate type (the low byte at 0,X), then a compact DEC/BEQ dispatch.
        emit(0xBD, 0x00, 0x00) // LDA $0000,X
        emit(0x29, 0xFF, 0x00) // AND #$00FF
        for (label in listOf(
            "equipment", "beam", "maxMissiles", "maxSupers", "maxPowerBombs",
            "maxEnergy", "maxReserve", "itemPickup", "boss",
        )) {
            emit(0x3A) // DEC A
            branch(0xF0, label) // BEQ
        }
        branch(0x80, "rawFalse") // Unknown predicate types fail closed.

        label("equipment")
        emit(0xAD, 0xA4, 0x09) // LDA $09A4 (collected equipment)
        branch(0x80, "testMask")
        label("beam")
        emit(0xAD, 0xA8, 0x09) // LDA $09A8 (collected beams)
        label("testMask")
        emit(0x3D, 0x02, 0x00) // AND $0002,X
        branch(0xF0, "rawFalse")
        branch(0x80, "rawTrue")

        fun threshold(label: String, address: Int) {
            label(label)
            emit(0xAD, address and 0xFF, (address ushr 8) and 0xFF) // LDA address
            emit(0xDD, 0x02, 0x00) // CMP $0002,X
            branch(0xB0, "rawTrue") // BCS: current maximum >= requested value
            branch(0x80, "rawFalse")
        }
        threshold("maxMissiles", 0x09C8)
        threshold("maxSupers", 0x09CC)
        threshold("maxPowerBombs", 0x09D0)
        threshold("maxEnergy", 0x09C4)
        threshold("maxReserve", 0x09D4)

        label("itemPickup")
        emit(0xDA) // PHX (preserve the selector payload pointer)
        emit(0xBD, 0x02, 0x00) // LDA $0002,X (persistent item bit index)
        emit(0x22, 0x8E, 0x81, 0x80) // JSL $80:818E (bit index -> X and $05E7 mask)
        emit(0xBF, 0x70, 0xD8, 0x7E) // LDA.l $7E:D870,X (item collection bits)
        if (fixedPersistentBitFlags) {
            emit(0xFA) // PLX; this changes N/Z, so mask afterward
            emit(0x2D, 0xE7, 0x05) // AND $05E7 and restore the tested N/Z result
        } else {
            // Predicate ABI format 01 ordering remains readable for early exported ROMs.
            emit(0x2D, 0xE7, 0x05)
            emit(0xFA)
        }
        branch(0xF0, "rawFalse")
        branch(0x80, "rawTrue")

        label("boss")
        // value = area in the high byte, boss mask in the low byte.
        emit(0xDA) // PHX
        emit(0xBD, 0x02, 0x00) // LDA $0002,X
        emit(0xEB) // XBA (area -> low byte)
        emit(0x29, 0xFF, 0x00) // AND #$00FF
        emit(0xAA) // TAX
        emit(0xBF, 0x28, 0xD8, 0x7E) // LDA.l $7E:D828,X (boss byte for area)
        emit(0x29, 0xFF, 0x00) // AND #$00FF
        emit(0xFA) // PLX
        emit(0x3D, 0x02, 0x00) // AND $0002,X (low byte is boss mask)
        branch(0xF0, "rawFalse")
        branch(0x80, "rawTrue")

        // The flags byte is the high byte of 0,X. A nonzero high byte inverts.
        label("rawTrue")
        emit(0xBD, 0x00, 0x00)
        emit(0x29, 0x00, 0xFF)
        branch(0xD0, "failure")
        branch(0x80, "success")
        label("rawFalse")
        emit(0xBD, 0x00, 0x00)
        emit(0x29, 0x00, 0xFF)
        branch(0xD0, "success")
        branch(0x80, "failure")

        label("success")
        emit(0xBD, 0x04, 0x00) // LDA $0004,X (state pointer)
        emit(0xAA) // TAX
        emit(0x4C, 0xE6, 0xE5) // JMP $E5E6 (vanilla matching-state handler)
        label("failure")
        emit(0x8A) // TXA
        emit(0x18) // CLC
        emit(0x69, 0x06, 0x00) // ADC #$0006 (skip descriptor and state pointer)
        emit(0xAA) // TAX
        emit(0x60) // RTS to the vanilla selector loop

        // Unreachable format signature and version.
        emit(
            0x53, 0x4D, 0x45, 0x44, 0x50, 0x52, 0x45, 0x44,
            if (fixedPersistentBitFlags) 0x02 else 0x01,
        ) // SMEDPRED
        build()
    }

    /** Read compatibility for ROMs exported before the item-bit flag-order fix. */
    val legacyRoutineBytes: ByteArray = buildRoutine(fixedPersistentBitFlags = false)

    /** Current generated predicate routine. */
    val routineBytes: ByteArray = buildRoutine(fixedPersistentBitFlags = true)

    fun matchesAt(bytes: ByteArray, pcOffset: Int): Boolean =
        matches(bytes, pcOffset, routineBytes) || matches(bytes, pcOffset, legacyRoutineBytes)

    private fun matches(bytes: ByteArray, pcOffset: Int, signature: ByteArray): Boolean =
        pcOffset >= 0 && pcOffset + signature.size <= bytes.size &&
            signature.indices.all { bytes[pcOffset + it] == signature[it] }
}
