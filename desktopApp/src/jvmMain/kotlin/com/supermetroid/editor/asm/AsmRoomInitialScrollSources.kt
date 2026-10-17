package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import com.supermetroid.editor.rom.SourceInitialScrollResource
import com.supermetroid.editor.rom.baseSourceStateIndex

/** Typed placement and pointer claims; the authored macro, not a byte mirror, owns the values. */
internal data class AsmRoomInitialScrollTable(val resource: SourceInitialScrollResource, val pcOffset: Int, val pointerOffsets: List<Int>) {
    fun claims(): List<AsmGeneratedRoomClaim> {
        val bytes = resource.bytes()
        require(resource.identity.matches(Regex("SmeditScrolls_[A-Za-z0-9_]+_Table_[0-9]+"))) { "Invalid initial scroll source identity." }
        require(pcOffset >= 0x78000 && pcOffset + bytes.size <= 0x80000)
        require(pointerOffsets.isNotEmpty() && pointerOffsets.distinct().size == pointerOffsets.size)
        val ptr = checkNotNull(pcToSnesLoRom(pcOffset)) and 0xFFFF
        return listOf(AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, resource.identity, pcOffset, bytes)) + pointerOffsets.map {
            require(it >= 0x78000 && it + 2 <= 0x80000)
            AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, "Initial scroll state pointer", it, byteArrayOf(ptr.toByte(), (ptr ushr 8).toByte()))
        }
    }
    fun renderSource(): String = buildString {
        claims()
        appendLine("; Initial screen scroll placement; values live in bank_8F.asm.")
        appendLine("org \$${checkNotNull(pcToSnesLoRom(pcOffset)).toString(16).uppercase()}")
        appendLine("${resource.identity}:")
        appendLine("    %${resource.identity}()")
        appendLine("${resource.identity}_End:")
        appendLine("assert (${resource.identity}>>16) == \$8F")
        appendLine("assert ${resource.identity}_End-${resource.identity} == ${resource.width * resource.height}")
        for (pc in pointerOffsets) {
            appendLine("org \$${checkNotNull(pcToSnesLoRom(pc)).toString(16).uppercase()}")
            appendLine("    dw ${resource.identity}&\$FFFF")
        }
    }
}

internal fun discoverAsmInitialScrollTables(project: SmEditProject, report: RomWritePlanReport, plannedRom: ByteArray,
                                            inputs: Map<Int, Map<String, SourceInitialScrollResource>>): List<AsmRoomInitialScrollTable> {
    val parser = RomParser(plannedRom)
    val header = if (plannedRom.size % 0x8000 == 0x200) 0x200 else 0
    return inputs.flatMap { (roomId, resources) ->
        val room = project.rooms.getValue(project.roomKey(roomId))
        val states = parser.inspectRoomStates(roomId).states
        resources.values.distinct().map { resource ->
            val allocation = report.writes.singleOrNull { it.owner == ASM_ROOM_GRAPH_OWNER && it.kind == RomWriteKind.ALLOCATION &&
                it.label.endsWith(" source initial scrolls ${resource.identity}") } ?: error("Initial scrolls have no unique checked allocation.")
            val offsets = room.states.mapIndexedNotNull { index, state ->
                val builtIndex = if (room.stateGraphChanged) index else requireNotNull(state.sourceStateIndex)
                if (resources.getValue(state.id).identity == resource.identity) requireNotNull(states.getOrNull(builtIndex)?.stateDataPcOffset) + 14 - header else null
            }
            val table = AsmRoomInitialScrollTable(resource, allocation.offset, offsets)
            require(allocation.bytes.map(Int::toByte).toByteArray().contentEquals(resource.bytes())) { "Initial scroll allocation differs from source." }
            for (claim in table.claims()) {
                require(plannedRom.copyOfRange(header + claim.pcOffset, header + claim.endExclusive).contentEquals(claim.bytes)) { "Initial scroll placement differs from checked plan." }
                require(report.writes.none { it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive }) { "Initial scrolls overlap another write owner." }
            }
            table
        }
    }
}

/** A user edit to a baseline table/pointer cannot be silently hidden by the GUI-owned replacement. */
internal fun protectInitialScrollBaseline(project: SmEditProject, baseline: RomParser, compiled: RomParser) {
    val rooms = project.rooms.values.filter { it.asmInitialScrollSourceVersion != 0 }
    if (rooms.isEmpty()) return
    // $82:EB20-$EB6B owns state +$0E, the fixed 50-byte copy and preset generation.
    // A custom loader needs a matching codec, not a successful assembly alone.
    val loaderStart = baseline.snesToPc(0x82EB20)
    val compiledLoader = compiled.snesToPc(0x82EB20)
    val loaderSize = 0x4C
    require(baseline.getRomData().copyOfRange(loaderStart, loaderStart + loaderSize)
        .contentEquals(compiled.getRomData().copyOfRange(compiledLoader, compiledLoader + loaderSize))) {
        "The initial scroll loader also changed in ASM. A custom scroll layout/preset needs a matching editor codec before using authored grids."
    }
    for (room in rooms) {
        if (project.newRoomForPreviewId(room.roomId) != null) continue
        val header = requireNotNull(baseline.readRoomHeader(room.roomId))
        val compiledHeader = requireNotNull(compiled.readRoomHeader(room.roomId))
        require(header.width == compiledHeader.width && header.height == compiledHeader.height) { "Initial scroll room dimensions also changed in original ASM. Resolve ownership before building." }
        val states = baseline.inspectRoomStates(room.roomId).states
        for (index in room.states.mapNotNull { it.baseSourceStateIndex() }.distinct()) {
            val pc = requireNotNull(states.getOrNull(index)?.stateDataPcOffset)
            val pointer = baseline.readStateData(pc).getValue("roomScrollsPtr")
            require(compiled.readStateData(pc)["roomScrollsPtr"] == pointer) { "Initial scroll pointer also changed in original ASM. Resolve ownership before building." }
            if (pointer >= 0x8000) {
                val start = baseline.snesToPc(0x8F0000 or pointer)
                val compiledStart = compiled.snesToPc(0x8F0000 or pointer)
                val count = header.width * header.height
                require(baseline.getRomData().copyOfRange(start, start + count).contentEquals(compiled.getRomData().copyOfRange(compiledStart, compiledStart + count))) {
                    "Initial scroll bytes also changed in original ASM. Resolve ownership before building."
                }
            }
        }
    }
}
