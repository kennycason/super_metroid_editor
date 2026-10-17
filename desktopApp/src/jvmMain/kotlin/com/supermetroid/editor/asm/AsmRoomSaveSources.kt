package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWritePlanReport
import com.supermetroid.editor.rom.SourceSaveResource

internal data class AsmRoomSaveTable(val resource: SourceSaveResource, val pcOffset: Int) {
    fun claim(): AsmGeneratedRoomClaim {
        require(pcOffset in 0 until 0x8000 && pcOffset + 14 <= 0x8000) { "Save record must fit bank \$80." }
        require(resource.identity == AsmSaveStationSource.name(resource.ownerRoomId, resource.area, resource.slot, resource.ownerProjectRoomId))
        return AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, resource.identity, pcOffset, resource.bytes())
    }
    fun renderSource(): String = buildString {
        val bytes = claim().bytes
        val name = resource.identity
        appendLine("; Load station · area ${resource.area}, slot ${resource.slot}; authored fields live in bank_8F.asm.")
        appendLine("org \$${checkNotNull(pcToSnesLoRom(pcOffset)).toString(16).uppercase()}")
        appendLine("$name:")
        appendLine("    %$name()")
        appendLine("${name}_End:")
        appendLine("assert ($name>>16) == \$80")
        appendLine("assert ${name}_End-$name == ${bytes.size}")
    }
}

internal fun discoverAsmRoomSaveTables(report: RomWritePlanReport, rom: ByteArray, resources: Map<String, SourceSaveResource>): List<AsmRoomSaveTable> {
    val parser = RomParser(rom)
    val header = if (rom.size % 0x8000 == 0x200) 0x200 else 0
    return resources.map { (name, resource) ->
        val entry = requireNotNull(parser.readSaveEntry(resource.area, resource.slot))
        val table = AsmRoomSaveTable(resource, entry.pcOffset - header)
        val claim = table.claim()
        require(rom.copyOfRange(entry.pcOffset, entry.pcOffset + 14).contentEquals(claim.bytes)) { "Save source differs from the planned record." }
        val owned = report.writes.singleOrNull { it.owner == ASM_ROOM_GRAPH_OWNER && it.label == "Source save $name" }
        require(owned != null && owned.offset == claim.pcOffset && owned.bytes.map(Int::toByte).toByteArray().contentEquals(claim.bytes)) {
            "Save source has no complete matching checked record claim."
        }
        require(report.writes.none { it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive }) {
            "Save source overlaps another ROM writer."
        }
        table
    }
}
