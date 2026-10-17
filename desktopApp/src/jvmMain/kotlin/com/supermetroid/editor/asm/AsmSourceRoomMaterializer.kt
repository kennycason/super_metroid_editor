package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.ProjectRoomExporter
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import com.supermetroid.editor.rom.resolveSourceDoors

internal const val ASM_ROOM_GRAPH_OWNER = "room-graph:project"

internal data class AsmGeneratedRoomClaim(
    val owner: String,
    val label: String,
    val pcOffset: Int,
    val bytes: ByteArray,
) {
    val length: Int get() = bytes.size
    val endExclusive: Int get() = pcOffset + length

    override fun equals(other: Any?): Boolean =
        other is AsmGeneratedRoomClaim &&
            owner == other.owner &&
            label == other.label &&
            pcOffset == other.pcOffset &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = owner.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + pcOffset
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

internal data class AsmRoomMaterialization(
    val source: String,
    val claims: List<AsmGeneratedRoomClaim>,
    val roomIds: Set<String>,
    val eligibleWrites: Int,
    val skippedWrites: Int,
    val plmTables: List<AsmRoomPlmTable> = emptyList(),
    val scrollTables: List<AsmRoomScrollTable> = emptyList(),
    val saveTables: List<AsmRoomSaveTable> = emptyList(),
    val initialScrollTables: List<AsmRoomInitialScrollTable> = emptyList(),
    val fxTables: List<AsmRoomFxTable> = emptyList(),
    val enemyTables: List<AsmRoomEnemyTable> = emptyList(),
    val doorTables: List<AsmRoomDoorTable> = emptyList(),
    val doorListTables: List<AsmRoomDoorListTable> = emptyList(),
    val newRoomIds: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = claims.isEmpty()

    companion object {
        val EMPTY = AsmRoomMaterialization("", emptyList(), emptySet(), 0, 0)
    }
}

/**
 * Promotes the already validated room graph into the ASM input stage.
 *
 * The established room exporter remains the allocation/pointer oracle for this
 * first source-native layer. Its complete final byte set is emitted atomically:
 * either every room-graph write is assembled before export, or the complete
 * graph remains on the post-compile backend. This gives later label/table based
 * emitters one guarded integration boundary without changing ROM output today.
 *
 * Project-created rooms require explicit planner placements. Their durable
 * project-ID labels never infer identity from preview or allocation addresses.
 */
internal class AsmSourceRoomMaterializer {
    fun materialize(
        project: SmEditProject,
        report: RomWritePlanReport,
        plannedRom: ByteArray? = null,
        compiledRomSize: Int = AsmProjectCompiler.ASM_ROM_SIZE,
        sourceScrolls: Map<String, com.supermetroid.editor.rom.SourceScrollResource> = emptyMap(),
        sourceSaves: Map<String, com.supermetroid.editor.rom.SourceSaveResource> = emptyMap(),
        sourceInitialScrolls: Map<Int, Map<String, com.supermetroid.editor.rom.SourceInitialScrollResource>> = emptyMap(),
        sourceFx: Map<Int, Map<String, com.supermetroid.editor.rom.SourceFxResource>> = emptyMap(),
        sourceEnemies: Map<Int, Map<String, com.supermetroid.editor.rom.SourceEnemyResources>> = emptyMap(),
        sourceDoors: Map<Int, com.supermetroid.editor.rom.SourceRoomDoors> = emptyMap(),
        newRoomIds: Map<String, Int> = emptyMap(),
    ): AsmRoomMaterialization {
        if (!ProjectRoomExporter.hasRoomEdits(project)) return AsmRoomMaterialization.EMPTY

        val writes = report.writes.filter { it.owner == ASM_ROOM_GRAPH_OWNER }
        if (writes.isEmpty()) return AsmRoomMaterialization.EMPTY
        require(newRoomIds.keys == project.newRooms.map { it.id }.toSet()) { "New rooms require complete checked build placements." }
        if (writes.any {
                it.kind !in MATERIALIZABLE_KINDS || it.offset < 0 || it.endInclusive >= compiledRomSize
            }
        ) {
            return AsmRoomMaterialization(
                source = "",
                claims = emptyList(),
                roomIds = emptySet(),
                eligibleWrites = writes.size,
                skippedWrites = writes.size,
            )
        }

        val finalBytes = sortedMapOf<Int, Int>()
        writes.forEach { write ->
            write.bytes.forEachIndexed { index, value ->
                finalBytes[write.offset + index] = value and 0xFF
            }
        }
        if (finalBytes.isEmpty() || finalBytes.keys.any { pcToSnesLoRom(it) == null }) {
            return AsmRoomMaterialization(
                source = "",
                claims = emptyList(),
                roomIds = emptySet(),
                eligibleWrites = writes.size,
                skippedWrites = writes.size,
            )
        }

        val previewToBuilt = project.newRooms.associate { it.previewRoomId to newRoomIds.getValue(it.id) }
        val placedProject = if (previewToBuilt.isEmpty()) project else project.copy(newRooms = project.newRooms.map { room ->
            room.copy(previewRoomId = newRoomIds.getValue(room.id))
        }.toMutableList(), rooms = project.rooms.values.associateTo(linkedMapOf()) { room ->
            val placed = previewToBuilt[room.roomId] ?: room.roomId
            project.roomKey(placed) to room.copy(roomId = placed)
        })
        fun <T> place(inputs: Map<Int, T>) = inputs.mapKeys { (id, _) -> previewToBuilt[id] ?: id }
        val plmTables = plannedRom?.let { discoverAsmRoomPlmTables(placedProject, report, it) }.orEmpty()
        val scrollTables = if (sourceScrolls.isEmpty()) emptyList() else discoverAsmRoomScrollTables(report, requireNotNull(plannedRom), sourceScrolls)
        val initialScrollTables = if (sourceInitialScrolls.isEmpty()) emptyList() else discoverAsmInitialScrollTables(placedProject, report, requireNotNull(plannedRom), place(sourceInitialScrolls))
        val enemyTables = if (sourceEnemies.isEmpty()) emptyList() else discoverAsmEnemyTables(placedProject, report, requireNotNull(plannedRom), place(sourceEnemies))
        val plannedParser = plannedRom?.let { com.supermetroid.editor.rom.RomParser(it) }
        val placedDoors = sourceDoors.mapValues { (roomId, doors) ->
            val entries = requireNotNull(plannedParser).parseDoorList(requireNotNull(plannedParser.readRoomHeader(previewToBuilt[roomId] ?: roomId)).doorOut)
            require(entries.size == doors.entries.size) { "Planned structural door count differs from source." }
            doors.entries.mapIndexed { index, door -> door.copy(doorDefPtr = entries[index].doorDefPtr, change = door.change.copy(destRoomPtr = entries[index].destRoomPtr)) }
        }
        val doorPointers = placedDoors.flatMap { (roomId, doors) -> doors.map { door ->
            com.supermetroid.editor.data.ProjectSourceDoorReference(if (sourceDoors.getValue(roomId).projectRoomId == null) roomId else 0, door.identity, sourceDoors.getValue(roomId).projectRoomId) to door.doorDefPtr
        } }.toMap()
        val resolvedSaves = sourceSaves.mapValues { (_, resource) ->
            val resolved = resource.resolveSourceDoors(sourceDoors, doorPointers)
            val id = previewToBuilt[resolved.ownerRoomId] ?: resolved.ownerRoomId
            resolved.copy(ownerRoomId = id, words = resolved.words.mapIndexed { i, word -> if (i == 0 && !resolved.cleared) id else word })
        }
        val resolvedFx = sourceFx.mapValues { (roomId, states) -> states.mapValues { (_, resource) ->
            resource.resolveSourceDoors(roomId, sourceDoors, doorPointers)
        } }
        val saveTables = if (resolvedSaves.isEmpty()) emptyList() else discoverAsmRoomSaveTables(report, requireNotNull(plannedRom), resolvedSaves)
        val fxTables = if (resolvedFx.isEmpty()) emptyList() else discoverAsmFxTables(placedProject, report, requireNotNull(plannedRom), place(resolvedFx))
        val doorListTables = sourceDoors.filterValues { it.structural }.map { (roomId, _) ->
            AsmRoomDoorListTable(previewToBuilt[roomId] ?: roomId, requireNotNull(plannedParser?.readRoomHeader(previewToBuilt[roomId] ?: roomId)).doorOut, placedDoors.getValue(roomId), sourceDoors.getValue(roomId).projectRoomId)
        }
        val doorTables = placedDoors.toSortedMap().flatMap { (roomId, doors) -> doors.map { door ->
            AsmRoomDoorTable(AsmDoorSource.name(roomId, door.identity, sourceDoors.getValue(roomId).projectRoomId), door)
        } }.groupBy { it.door.doorDefPtr }.values.map { tables ->
            val first = tables.first()
            require(tables.all { it.door.entry() == first.door.entry() && it.door.arrival == first.door.arrival }) { "Conflicting source owners for one DoorDef." }
            require(tables.size == 1 || tables.all { it.door.shared }) { "Multiple owners need canonical shared door source." }
            first.copy(aliases = tables.drop(1).map { it.macro })
        }
        val typedClaims = plmTables.flatMap(AsmRoomPlmTable::claims) + scrollTables.map(AsmRoomScrollTable::claim) + saveTables.map(AsmRoomSaveTable::claim) + initialScrollTables.flatMap(AsmRoomInitialScrollTable::claims) + fxTables.flatMap(AsmRoomFxTable::claims) + enemyTables.flatMap(AsmRoomEnemyTable::claims) + doorTables.map(AsmRoomDoorTable::claim) + doorListTables.map(AsmRoomDoorListTable::claim)
        doorTables.forEach { table ->
            val claim = table.claim()
            val bytes = requireNotNull(plannedRom)
            val header = if (bytes.size % 0x8000 == 0x200) 0x200 else 0
            require(bytes.copyOfRange(header + claim.pcOffset, header + claim.endExclusive).contentEquals(claim.bytes)) { "${table.label}: planned DoorDef differs from authored fields." }
            require(report.writes.none { it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive }) {
                "${table.label}: another patch/source owner overlaps the authored DoorDef."
            }
        }
        val typedOffsets = hashSetOf<Int>()
        typedClaims.forEach { claim ->
            (claim.pcOffset until claim.endExclusive).forEach { pc ->
                require(typedOffsets.add(pc)) { "Generated PLM resources overlap at PC ${pc.toString(16)}" }
                finalBytes.remove(pc)
            }
        }
        val ranges = contiguousRanges(finalBytes.keys)
        val rawClaims = ranges.mapIndexed { index, range ->
            val contributing = writes.filter { it.overlaps(range) }
            val allocationLabels = contributing
                .filter { it.kind == RomWriteKind.ALLOCATION }
                .map(RomWriteIntent::label)
                .distinct()
            val labels = (allocationLabels.ifEmpty { contributing.map(RomWriteIntent::label).distinct() })
                .take(MAX_LABELS_PER_CLAIM)
            AsmGeneratedRoomClaim(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = buildString {
                    append("Generated room source")
                    if (labels.isNotEmpty()) append(" · ").append(labels.joinToString(" + "))
                    if (ranges.size > 1) append(" · ").append(index + 1)
                },
                pcOffset = range.first,
                bytes = ByteArray(range.last - range.first + 1) { offset ->
                    checkNotNull(finalBytes[range.first + offset]).toByte()
                },
            )
        }
        val roomIds = placedProject.rooms.entries.asSequence()
            .filter { it.value.hasEdits }
            .mapNotNull { (key, _) -> key.toIntOrNull(16) }
            .map { it.toString(16).uppercase().padStart(4, '0') }
            .toCollection(linkedSetOf())
        roomIds.addAll(newRoomIds.values.map(project::roomKey))
        val projectLabels = buildString {
            for (room in project.newRooms) {
                val pointer = newRoomIds.getValue(room.id)
                require(pointer in 0x8000..0xFFFE && plannedParser?.readRoomHeader(pointer) != null) { "Project room placement has no readable header." }
                appendLine("; Project room ${room.id.replace('\n', ' ').replace('\r', ' ')} · ${room.name.replace('\n', ' ').replace('\r', ' ')}")
                appendLine("; @room ${pointer.toString(16).uppercase()} ${room.name.replace('\n', ' ').replace('\r', ' ')}")
                appendLine("; @project-room ${AsmProjectRoomLabels.encoded(room.id)} ${pointer.toString(16).uppercase()}")
                appendLine("; @section ${AsmProjectRoomLabels.entry(room.id)} Project room entry")
                appendLine("; @section ${AsmProjectRoomLabels.prefix(room.id)}_Header Header")
                appendLine("org \$8F${pointer.toString(16).uppercase().padStart(4, '0')}")
                appendLine("${AsmProjectRoomLabels.entry(room.id)}:")
                appendLine("${AsmProjectRoomLabels.prefix(room.id)}_Header:")
                appendLine("assert (${AsmProjectRoomLabels.entry(room.id)}>>16) == \$8F")
            }
        }

        return AsmRoomMaterialization(
            source = projectLabels + renderSource(rawClaims) + scrollTables.joinToString("\n", prefix = "\n") { it.renderSource() } +
                plmTables.joinToString("\n", prefix = "\n") { it.renderSource() } + saveTables.joinToString("\n", prefix = "\n") { it.renderSource() } +
                initialScrollTables.joinToString("\n", prefix = "\n") { it.renderSource() } + fxTables.joinToString("\n", prefix = "\n") { it.renderSource() } + enemyTables.joinToString("\n", prefix = "\n") { it.renderSource() } + doorTables.joinToString("\n", prefix = "\n") { it.renderSource() } + doorListTables.joinToString("\n", prefix = "\n") { it.renderSource() },
            claims = rawClaims + typedClaims,
            roomIds = roomIds,
            eligibleWrites = writes.size,
            skippedWrites = 0,
            plmTables = plmTables,
            scrollTables = scrollTables,
            saveTables = saveTables,
            initialScrollTables = initialScrollTables,
            fxTables = fxTables,
            enemyTables = enemyTables,
            doorTables = doorTables,
            doorListTables = doorListTables,
            newRoomIds = newRoomIds.toMap(),
        )
    }

    private fun renderSource(claims: List<AsmGeneratedRoomClaim>): String = buildString {
        appendLine("; Generated placement plan for this build. Do not edit this artifact.")
        appendLine("; Authored object macros own their values; other resources still come from the room model.")
        appendLine("; Typed PLM tables use symbolic state pointers; other resources retain byte overlays.")
        claims.forEachIndexed { index, claim ->
            val snesAddress = checkNotNull(pcToSnesLoRom(claim.pcOffset))
            appendLine()
            appendLine("; ${claim.label.replace('\n', ' ')}")
            appendLine("org \$${snesAddress.toString(16).uppercase().padStart(6, '0')}")
            appendLine("SmeditRoomGraph_${(index + 1).toString().padStart(3, '0')}:")
            claim.bytes.asList().chunked(BYTES_PER_LINE).forEach { row ->
                append("db ")
                appendLine(row.joinToString(",") { "\$${(it.toInt() and 0xFF).hex2()}" })
            }
        }
    }

    private fun contiguousRanges(indices: Collection<Int>): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val sorted = indices.sorted()
        val result = mutableListOf<IntRange>()
        var start = sorted.first()
        var previous = start
        for (index in sorted.drop(1)) {
            if (index != previous + 1 || index % 0x8000 == 0) {
                result += start..previous
                start = index
            }
            previous = index
        }
        result += start..previous
        return result
    }

    private fun RomWriteIntent.overlaps(range: IntRange): Boolean =
        offset <= range.last && range.first <= endInclusive

    private fun Int.hex2(): String = toString(16).uppercase().padStart(2, '0')

    private companion object {
        const val BYTES_PER_LINE = 16
        const val MAX_LABELS_PER_CLAIM = 2
        val MATERIALIZABLE_KINDS = setOf(RomWriteKind.ROOM, RomWriteKind.ALLOCATION)
    }
}
