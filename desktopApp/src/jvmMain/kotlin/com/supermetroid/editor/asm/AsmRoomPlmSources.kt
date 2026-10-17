package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWritePlanReport

/** One engine-format PLM resource, independent of where the allocator placed it. */
internal data class AsmRoomPlmTable(
    val label: String,
    val pcOffset: Int,
    val entries: List<RomParser.PlmEntry>,
    val pointerPcOffsets: List<Int>,
    val authoredMacro: String? = null,
) {
    val bytes: ByteArray get() = RomParser.serializePlmSet(entries).map(Int::toByte).toByteArray()

    fun claims(): List<AsmGeneratedRoomClaim> {
        require(entries.all { it.id in 1..0xFFFF && it.x in 0..0xFF && it.y in 0..0xFF && it.param in 0..0xFFFF }) {
            "$label requires nonzero word IDs, byte coordinates, and word parameters"
        }
        val payload = bytes
        require(pcOffset >= BANK_START && pcOffset + payload.size <= BANK_END) {
            "$label must fit entirely inside bank \$8F"
        }
        val pointer = checkNotNull(pcToSnesLoRom(pcOffset)) and 0xFFFF
        return listOf(AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, label, pcOffset, payload)) +
            pointerPcOffsets.map { offset ->
                require(offset >= BANK_START && offset + 2 <= BANK_END) { "$label state pointer is outside bank \$8F" }
                AsmGeneratedRoomClaim(
                    ASM_ROOM_GRAPH_OWNER, "$label state pointer", offset,
                    byteArrayOf(pointer.toByte(), (pointer ushr 8).toByte()),
                )
            }
    }

    fun renderSource(): String = buildString {
        claims() // Validate placement before emitting source.
        appendLine("; PLM list: word ID, byte X/Y, word parameter; word zero terminator.")
        appendLine("org ${snesLiteral(pcOffset)}")
        appendLine("$label:")
        if (authoredMacro != null) {
            require(authoredMacro.matches(Regex("SmeditObjects_[A-Za-z0-9_]+"))) { "Invalid authored PLM macro name." }
            appendLine("    %$authoredMacro()")
        } else {
            entries.forEach { entry ->
                // Explicit primitives keep the verified layout independent of user-edited macros.
                appendLine("    dw ${hex(entry.id, 4)}")
                appendLine("    db ${hex(entry.x, 2)}, ${hex(entry.y, 2)}")
                appendLine("    dw ${hex(entry.param, 4)}")
            }
            appendLine("    dw \$0000")
        }
        appendLine("${label}_End:")
        appendLine("assert ($label>>16) == \$8F")
        appendLine("assert ${label}_End-$label == ${entries.size * 6 + 2}")
        pointerPcOffsets.forEach { offset ->
            appendLine("org ${snesLiteral(offset)}")
            appendLine("    dw $label&\$FFFF")
        }
    }

    companion object {
        private const val BANK_START = 0x78000
        private const val BANK_END = 0x80000
        private fun hex(value: Int, digits: Int): String = "\$" + value.toString(16).uppercase().padStart(digits, '0')
        private fun snesLiteral(pc: Int): String = hex(checkNotNull(pcToSnesLoRom(pc)), 6)
    }
}

/**
 * Decode the validated room plan into typed resources. Only edited, reachable
 * PLM lists are promoted; managed object lists can also belong to rewritten graphs.
 * Other resources retain the atomic byte backend. Sharing follows actual state pointers, never content
 * equality. Complete promoted ranges must have a single GUI owner.
 */
internal fun discoverAsmRoomPlmTables(
    project: SmEditProject,
    report: RomWritePlanReport,
    plannedRom: ByteArray,
): List<AsmRoomPlmTable> {
    val header = if (plannedRom.size % 0x8000 == 0x200) 0x200 else 0
    val parser = RomParser(plannedRom)
    val roomWrites = report.writes.filter { it.owner == ASM_ROOM_GRAPH_OWNER }
    val tables = linkedMapOf<Int, AsmRoomPlmTable>()
    fun word(pc: Int): Int = (plannedRom[pc].toInt() and 0xFF) or ((plannedRom[pc + 1].toInt() and 0xFF) shl 8)
    project.rooms.toSortedMap().forEach { (roomKey, edits) ->
        if (!edits.hasEdits || (edits.stateGraphChanged && edits.asmPlmSourceVersion == 0)) return@forEach
        val roomId = requireNotNull(roomKey.toIntOrNull(16))
        parser.findAllStateDataOffsets(roomId).forEachIndexed stateLoop@{ index, statePc ->
            val pointer = word(statePc + 20)
            if (pointer == 0 || pointer == 0xFFFF) return@stateLoop
            val pc = parser.snesToPc(0x8F0000 or pointer) - header
            val existing = tables[pc]
            val stateIdentity = edits.states.getOrNull(index)?.sourceStateIndex ?: index
            val state = if (edits.stateGraphChanged) edits.states.getOrNull(index)
                else edits.states.singleOrNull { it.sourceStateIndex == index }
            val authored = if (edits.asmPlmSourceVersion != 0) {
                AsmRoomObjectSource.stateMacro(roomId, requireNotNull(state) { "Missing authored state for room $roomKey" }.id,
                    project.newRoomForPreviewId(roomId)?.id)
            } else null
            val label = if (authored != null) "${authored}_Plms"
                else "SmeditRoom_${roomId.toString(16).uppercase()}_State${stateIdentity}_Plms"
            tables[pc] = if (existing == null) {
                AsmRoomPlmTable(label, pc, parser.parsePlmSet(pointer), listOf(statePc + 20 - header), authored)
            } else {
                existing.copy(pointerPcOffsets = (existing.pointerPcOffsets + (statePc + 20 - header)).distinct())
            }
        }
    }
    return tables.values.filter { table ->
        val spans = listOf(table.pcOffset until table.pcOffset + table.bytes.size) +
            table.pointerPcOffsets.map { it until it + 2 }
        spans.any { span -> roomWrites.any { it.offset <= span.last && span.first <= it.endInclusive } }
    }.onEach { table ->
        table.claims().forEach { claim ->
            val actual = plannedRom.copyOfRange(header + claim.pcOffset, header + claim.endExclusive)
            require(actual.contentEquals(claim.bytes)) {
                "${table.label} does not match the supported six-byte PLM format with a zero terminator"
            }
            val other = report.writes.firstOrNull {
                it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive
            }
            require(other == null) {
                "Generated PLM resource ${table.label} overlaps ${other?.owner} (${other?.label}); " +
                    "edit this resource through one owner"
            }
        }
    }
}
