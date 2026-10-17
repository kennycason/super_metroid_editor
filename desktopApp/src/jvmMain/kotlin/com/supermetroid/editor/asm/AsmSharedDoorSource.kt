package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*

/** A canonical fixed-address record owns every byte, even when a field equals the base ROM. */
internal fun protectSharedDoorSource(source: AsmDoorSource, project: SmEditProject, original: RomParser,
                                     compiled: AsmCompiledBuildBase, nativeSymbols: Map<String, Int> = emptyMap()): AsmCompiledBuildBase {
    val inputs = source.sharedResources(original)
    if (inputs.isEmpty()) return compiled
    val resolved = resolveDoorArrivals(inputs.mapValues { (_, door) -> SourceRoomDoors(0x8000, listOf(door)) }, compiled)
    val built = RomParser(compiled.romBytes)
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val allConsumers = nativeDoorConsumers(original)
    val claims = inputs.map { (pointer, _) ->
        val door = resolved.getValue(pointer).entries.single()
        validateNativeDoorArrivalAnchor(door, labels, nativeSymbols)
        val name = AsmDoorSource.sharedName(pointer)
        val address = 0x830000 or pointer
        require(labels[name + "_Entry"] == address && labels[name + "_End"] == address + 12) { "$name must retain its complete native twelve-byte placement." }
        val pc = original.snesToPc(address)
        require(compiled.romBytes.copyOfRange(pc, pc + 12).contentEquals(door.bytes())) { "$name differs from its typed shared fields." }
        val consumers = allConsumers.getValue(pointer)
        for ((roomId, index) in consumers) {
            val base = requireNotNull(original.readRoomHeader(roomId))
            val generatedDoorList = project.roomKey(roomId) in compiled.generatedRoomIds &&
                project.rooms[project.roomKey(roomId)]?.asmDoorSourceVersion == 1
            require(generatedDoorList || built.readRoomHeader(roomId)?.doorOut == base.doorOut) {
                "$name native door-list pointer changed in room ${roomId.toString(16)}; resolve that competing source edit."
            }
            // Final generated lists may relocate, but the original list bytes must not be repurposed.
            require(built.readUInt16At(original.snesToPc(0x8F0000 or base.doorOut) + index * 2) == pointer) {
                "$name native list ownership changed; resolve that competing source edit."
            }
            require(project.rooms[project.roomKey(roomId)]?.doorChanges.orEmpty().none { it.doorIndex == index }) {
                "$name also has a GUI override in room ${roomId.toString(16)}. Resolve the competing owner."
            }
        }
        val target = requireNotNull(original.readRoomHeader(door.change.destRoomPtr)) { "$name has no readable destination." }
        val change = project.rooms[project.roomKey(target.roomId)]?.effectiveHeaderChange
        val width = change?.width ?: target.width; val height = change?.height ?: target.height
        val baseline = original.parseDoorEntry(requireNotNull(original.readRoomHeader(consumers.first().first)).doorOut, consumers.first().second)!!
        if (door.entry() != baseline.copy(distFromDoor = door.change.distFromDoor, entryCode = door.change.entryCode,
                bitflag = (baseline.bitflag and 0x40.inv()) or (door.change.bitflag and 0x40)) || width != target.width || height != target.height) {
            require(door.change.screenX < width && door.change.screenY < height &&
                (door.change.bitflag ushr 8 < 4 || ((door.change.doorCapCode and 255) < width * 16 && (door.change.doorCapCode ushr 8) < height * 16))) {
                "$name arrival geometry is outside its destination room."
            }
        }
        pc until pc + 12
    }
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        var spans = listOf(range.pcOffset until range.pcOffset + range.length)
        for (claim in claims) spans = spans.flatMap { span ->
            if (claim.last < span.first || claim.first > span.last) listOf(span) else buildList {
                if (span.first < claim.first) add(span.first until claim.first)
                if (claim.last < span.last) add(claim.last + 1..span.last)
            }
        }
        spans.map { range.copy(pcOffset = it.first, length = it.count()) }
    }
    return compiled.copy(sourceOwnedRanges = retained + claims.filter { claim ->
        compiled.generatedRoomClaims.none { it.pcOffset <= claim.first && it.endExclusive > claim.last }
    }.map { AsmSourceOwnedRange(it.first, it.count(), DOOR_SOURCE_FILE) })
}
