package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmRoomEnemyTable(val identity: String, val bytes: ByteArray, val bank: Int, val pcOffset: Int, val pointerOffsets: List<Int>) {
    fun claims(): List<AsmGeneratedRoomClaim> {
        require(identity.matches(Regex("SmeditEnemies_[A-Za-z0-9_]+_(Population|Graphics)_[0-9]+"))) { "Invalid enemy source identity." }
        require(bank in listOf(0xA1, 0xB4) && pcToSnesLoRom(pcOffset)?.ushr(16) == bank &&
            pcToSnesLoRom(pcOffset + bytes.size - 1)?.ushr(16) == bank) { "Enemy source placement crosses its bank." }
        require(pointerOffsets.isNotEmpty() && pointerOffsets.distinct().size == pointerOffsets.size)
        val ptr = requireNotNull(pcToSnesLoRom(pcOffset)) and 0xFFFF
        return listOf(AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, identity, pcOffset, bytes)) + pointerOffsets.map {
            require(it in 0x78000..0x7FFFE) { "Enemy state pointer is outside bank 8F." }
            AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, "Enemy source state pointer", it, byteArrayOf(ptr.toByte(), (ptr ushr 8).toByte()))
        }
    }
    fun renderSource() = buildString {
        claims()
        appendLine("; Enemy placement: authored paired values live in bank_A1.asm.")
        appendLine("org \$${requireNotNull(pcToSnesLoRom(pcOffset)).toString(16).uppercase()}")
        appendLine("$identity:")
        appendLine("    %$identity()")
        appendLine("${identity}_End:")
        appendLine("assert ($identity>>16) == \$${bank.toString(16).uppercase()}")
        appendLine("assert ${identity}_End-$identity == ${bytes.size}")
        pointerOffsets.forEach { pc ->
            appendLine("org \$${requireNotNull(pcToSnesLoRom(pc)).toString(16).uppercase()}")
            appendLine("    dw $identity&\$FFFF")
        }
    }
}

internal fun discoverAsmEnemyTables(project: SmEditProject, report: RomWritePlanReport, plannedRom: ByteArray,
                                    inputs: Map<Int, Map<String, SourceEnemyResources>>): List<AsmRoomEnemyTable> {
    val parser = RomParser(plannedRom)
    val header = if (plannedRom.size % 0x8000 == 0x200) 0x200 else 0
    return inputs.flatMap { (roomId, resources) ->
        val room = project.rooms.getValue(project.roomKey(roomId))
        val states = parser.inspectRoomStates(roomId).states
        val records = resources.values.flatMap { listOf(Triple(it.population.identity, it.population.bytes(), 0xA1), Triple(it.graphics.identity, it.graphics.bytes(), 0xB4)) }
            .distinctBy { it.first }
        records.map { (identity, bytes, bank) ->
            val allocation = report.writes.singleOrNull { it.owner == ASM_ROOM_GRAPH_OWNER && it.kind == RomWriteKind.ALLOCATION &&
                it.label.endsWith(" ${if (bank == 0xA1) "source enemies" else "source enemy GFX"} $identity") }
                ?: error("Enemy resource $identity has no unique checked allocation.")
            val offsets = room.states.mapIndexedNotNull { index, state ->
                val input = resources.getValue(state.id)
                if (identity != if (bank == 0xA1) input.population.identity else input.graphics.identity) null else {
                    val builtIndex = if (room.stateGraphChanged) index else requireNotNull(state.sourceStateIndex)
                    requireNotNull(states.getOrNull(builtIndex)?.stateDataPcOffset) + (if (bank == 0xA1) 8 else 10) - header
                }
            }
            val table = AsmRoomEnemyTable(identity, bytes, bank, allocation.offset, offsets)
            require(allocation.bytes.map(Int::toByte).toByteArray().contentEquals(bytes)) { "Enemy allocation differs from source." }
            table.claims().forEach { claim ->
                require(plannedRom.copyOfRange(header + claim.pcOffset, header + claim.endExclusive).contentEquals(claim.bytes)) { "Enemy placement differs from checked plan." }
                require(report.writes.none { it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive }) { "Enemy resource overlaps another write owner." }
            }
            table
        }
    }
}

/** Prevent original tables/loader edits from being silently overwritten by authored replacements. */
internal fun protectEnemyBaseline(project: SmEditProject, baseline: RomParser, compiled: RomParser,
                                  inputs: Map<Int, Map<String, SourceEnemyResources>> = emptyMap()) {
    val rooms = project.rooms.values.filter { it.asmEnemySourceVersion != 0 }
    if (rooms.isEmpty()) return
    // Native loader, graphics transfer and 4-slot processing. Different layouts need a different codec.
    val start = baseline.snesToPc(0xA08A1E)
    val builtStart = compiled.snesToPc(0xA08A1E)
    require(baseline.getRomData().copyOfRange(start, start + 0x498).contentEquals(compiled.getRomData().copyOfRange(builtStart, builtStart + 0x498))) {
        "The enemy population/GFX loader changed in ASM. A custom layout needs a matching editor codec."
    }
    for (room in rooms) {
        if (project.newRoomForPreviewId(room.roomId) != null) continue
        val states = baseline.inspectRoomStates(room.roomId).states
        require(compiled.inspectRoomStates(room.roomId).states.map { it.stateDataPcOffset to it.condition } ==
            states.map { it.stateDataPcOffset to it.condition }) {
            "The native room state graph also changed in ASM. Resolve ownership before emitting authored enemies."
        }
        for (index in room.states.mapNotNull { it.baseSourceStateIndex() }.distinct()) {
            val pc = requireNotNull(states.getOrNull(index)?.stateDataPcOffset)
            val fields = baseline.readStateData(pc)
            for (key in listOf("enemySetPtr", "enemyGfxPtr")) {
                val ptr = fields.getValue(key)
                require(compiled.readStateData(pc)[key] == ptr) { "$key also changed in original ASM. Resolve source ownership before building." }
                val original = if (key == "enemySetPtr") readSourceEnemyPopulation(baseline, ptr, "Original").bytes() else readSourceEnemyGraphics(baseline, ptr, "Original").bytes()
                val actual = if (key == "enemySetPtr") readSourceEnemyPopulation(compiled, ptr, "Compiled").bytes() else readSourceEnemyGraphics(compiled, ptr, "Compiled").bytes()
                require(original.contentEquals(actual)) { "$key data also changed in original ASM. Edit the authored room macros instead; neither version was discarded." }
            }
        }
    }
    for (species in inputs.values.flatMap { it.values }.flatMap { it.graphics.entries }.map { it.speciesId }.distinct()) {
        val original = baseline.snesToPc(0xA00000 or species)
        val built = compiled.snesToPc(0xA00000 or species)
        for (offset in listOf(0, 1, 12, 54, 55, 56)) {
            require(baseline.readByteAt(original + offset) == compiled.readByteAt(built + offset)) {
                "Species ${species.toString(16).uppercase()} changed its GFX size/bank/address in original ASM. Resolve the transfer codec before building authored enemy GFX."
            }
        }
    }
}
