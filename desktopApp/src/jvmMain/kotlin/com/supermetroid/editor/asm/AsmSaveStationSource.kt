package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.data.ProjectSourceDoorReference

/** Same authored bank buffer as placements; the build supplies the fixed bank-$80 slot address. */
internal object AsmSaveStationSource {
    private val identity = Regex("SmeditObjects_([0-9A-F]{4}|Project_[0-9A-F]+)_Save_([0-5])_([0-7])")
    val notes = listOf("Room · bank \$8F", "Incoming door · bank \$83", "Door BTS", "Camera X", "Camera Y", "Samus Y · screen top", "Samus X · screen center")
    fun name(roomId: Int, area: Int, slot: Int, projectId: String? = null) = "${AsmRoomObjectSource.prefix(roomId, projectId)}Save_${area}_$slot"

    fun parse(name: String, body: String, symbols: Map<String, Int> = emptyMap(), projectRoomIds: Map<Int, String> = emptyMap()): SourceSaveResource {
        val match = requireNotNull(identity.matchEntire(name)) { "Unsupported save resource identity $name." }
        val projectId = match.groupValues[1].takeIf { it.startsWith("Project_") }?.removePrefix("Project_")?.let {
            requireNotNull(AsmProjectRoomLabels.decode(it)) { "Invalid project room save identity." }
        }
        val roomId = if (projectId == null) match.groupValues[1].toInt(16) else
            requireNotNull(projectRoomIds.entries.singleOrNull { it.value == projectId }?.key) { "Save owner project room is missing or ambiguous." }
        val tokens = body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            require(line.startsWith("dw ", true)) { "$name: expected seven dw fields for the load-station record." }
            line.drop(3).split(',').map { it.trim() }
        }.toList()
        val reference = tokens.getOrNull(1)?.let(ProjectSourceDoorReference::fromEntryLabel)
        val words = tokens.mapIndexed { index, token ->
            if (index == 0 && projectId != null && token == AsmProjectRoomLabels.entry(projectId)) roomId
            else if (index == 1 && reference != null) 0 else {
                require(ProjectSourceDoorReference.fromEntryLabel(token) == null) { "$name: DoorDef labels are only valid in the incoming-door field." }
                AsmPlmDocument.literal(token) ?: symbols[token] ?: error("$name: unsupported save field '$token'.")
            }
        }
        return SourceSaveResource(name, roomId, match.groupValues[2].toInt(), match.groupValues[3].toInt(), words, reference, projectId)
            .also(SourceSaveResource::validate)
    }

    fun render(resource: SourceSaveResource): String {
        resource.validate()
        require(resource.identity == name(resource.ownerRoomId, resource.area, resource.slot, resource.ownerProjectRoomId))
        return buildString {
            appendLine("macro ${resource.identity}()")
            if (resource.cleared) appendLine("    ; Released save slot · area ${resource.area}, slot ${resource.slot}")
            resource.words.forEachIndexed { index, word ->
                val value = if (index == 0 && !resource.cleared && resource.ownerProjectRoomId != null) AsmProjectRoomLabels.entry(requireNotNull(resource.ownerProjectRoomId))
                    else if (index == 1) resource.sourceDoor?.entryLabel ?: "\$${word.toString(16).uppercase().padStart(4, '0')}"
                    else "\$${word.toString(16).uppercase().padStart(4, '0')}"
                appendLine("    dw $value ; ${notes[index]}")
            }
            appendLine("endmacro")
        }
    }
}
