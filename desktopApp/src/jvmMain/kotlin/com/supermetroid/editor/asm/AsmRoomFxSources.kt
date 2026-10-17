package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*

/** FX values stay authored in bank_83.asm; only placement and symbolic pointers are generated. */
internal data class AsmRoomFxTable(val resource: SourceFxResource, val pcOffset: Int, val pointerOffsets: List<Int>) {
    fun claims(): List<AsmGeneratedRoomClaim> {
        require(resource.identity.matches(Regex("SmeditFx_[A-Za-z0-9_]+_Table_[0-9]+")))
        val bytes = resource.bytes()
        require(pcOffset >= 0x18000 && pcOffset + bytes.size <= 0x20000) { "FX table must fit bank \$83." }
        require(pointerOffsets.isNotEmpty() && pointerOffsets.distinct().size == pointerOffsets.size)
        val pointer = checkNotNull(pcToSnesLoRom(pcOffset)) and 0xFFFF
        return listOf(AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, resource.identity, pcOffset, bytes)) + pointerOffsets.map {
            require(it >= 0x78000 && it + 2 <= 0x80000)
            AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, "FX state pointer", it, byteArrayOf(pointer.toByte(), (pointer ushr 8).toByte()))
        }
    }
    fun renderSource(): String = buildString {
        val size = claims().first().length
        appendLine("; FX placement; values live in bank_83.asm.")
        appendLine("org \$${checkNotNull(pcToSnesLoRom(pcOffset)).toString(16).uppercase()}")
        appendLine("${resource.identity}:")
        appendLine("    %${resource.identity}()")
        appendLine("${resource.identity}_End:")
        appendLine("assert (${resource.identity}>>16) == \$83")
        appendLine("assert ${resource.identity}_End-${resource.identity} == $size")
        pointerOffsets.forEach { pc ->
            appendLine("org \$${checkNotNull(pcToSnesLoRom(pc)).toString(16).uppercase()}")
            appendLine("    dw ${resource.identity}&\$FFFF")
        }
    }
}

internal fun discoverAsmFxTables(project: SmEditProject, report: RomWritePlanReport, plannedRom: ByteArray,
                                 inputs: Map<Int, Map<String, SourceFxResource>>): List<AsmRoomFxTable> {
    val parser = RomParser(plannedRom)
    val header = if (plannedRom.size % 0x8000 == 0x200) 0x200 else 0
    return inputs.flatMap { (id, resources) ->
        val room = project.rooms.getValue(project.roomKey(id))
        val states = parser.inspectRoomStates(id).states
        resources.values.distinct().map { resource ->
            val allocation = report.writes.singleOrNull { it.owner == ASM_ROOM_GRAPH_OWNER && it.kind == RomWriteKind.ALLOCATION && it.label.endsWith(" source FX ${resource.identity}") }
                ?: error("FX source has no unique checked allocation.")
            val pointers = room.states.mapIndexedNotNull { index, state ->
                val builtIndex = if (room.stateGraphChanged) index else requireNotNull(state.sourceStateIndex)
                if (resources.getValue(state.id).identity == resource.identity) requireNotNull(states.getOrNull(builtIndex)?.stateDataPcOffset) + 6 - header else null
            }
            val table = AsmRoomFxTable(resource, allocation.offset, pointers)
            require(allocation.bytes.map(Int::toByte).toByteArray().contentEquals(resource.bytes())) { "FX allocation differs from authored values." }
            table.claims().forEach { claim ->
                require(plannedRom.copyOfRange(header + claim.pcOffset, header + claim.endExclusive).contentEquals(claim.bytes)) { "FX placement differs from checked plan." }
                require(report.writes.none { it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive }) { "FX overlaps another writer." }
            }
            table
        }
    }
}

internal fun protectFxBaseline(project: SmEditProject, baseline: RomParser, compiled: RomParser) {
    val rooms = project.rooms.values.filter { it.asmFxSourceVersion != 0 }
    if (rooms.isEmpty()) return
    fun unchanged(address: Int, size: Int, message: String) {
        val pc = baseline.snesToPc(address)
        val built = compiled.snesToPc(address)
        require(baseline.getRomData().copyOfRange(pc, pc + size).contentEquals(compiled.getRomData().copyOfRange(built, built + size))) { message }
    }
    unchanged(0x89AB82, 0x141, "The FX loader changed in ASM; a custom header layout needs a matching editor codec.")
    unchanged(0x83AC18, 0x2E, "The native FX dispatch changed in ASM; extended types need a matching editor codec.")
    val seen = mutableSetOf<Int>()
    for (room in rooms) {
        if (project.newRoomForPreviewId(room.roomId) != null) continue
        requireDoorRoutedFxOwnership(room, baseline)
        val states = baseline.inspectRoomStates(room.roomId).states
        for (index in room.states.mapNotNull { it.baseSourceStateIndex() }.distinct()) {
            val pc = requireNotNull(states.getOrNull(index)?.stateDataPcOffset)
            val pointer = baseline.readStateData(pc).getValue("fxPtr")
            require(compiled.readStateData(pc)["fxPtr"] == pointer) { "FX pointer also changed in original Bank \$8F ASM; resolve ownership before replacing it." }
            if (pointer in 0x8000..0xFFFE && seen.add(pointer)) {
                val resource = readSourceFxResource(baseline, pointer, "Original FX")
                unchanged(0x830000 or pointer, resource.bytes().size, "FX bytes also changed in original Bank \$83 ASM; resolve ownership before replacing them.")
            }
        }
    }
}
