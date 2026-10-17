package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.data.ProjectSourceDoorReference

/** Same authored bank buffer as placements; the build supplies the fixed bank-$80 slot address. */
internal object AsmSaveStationSource {
    private val identity = Regex("SmeditObjects_([0-9A-F]+)_Save_([0-5])_([0-7])")
    val notes = listOf("Room · bank \$8F", "Incoming door · bank \$83", "Door BTS", "Camera X", "Camera Y", "Samus Y · screen top", "Samus X · screen center")
    fun name(roomId: Int, area: Int, slot: Int) = "${AsmRoomObjectSource.prefix(roomId)}Save_${area}_$slot"

    fun parse(name: String, body: String, symbols: Map<String, Int> = emptyMap()): SourceSaveResource {
        val match = requireNotNull(identity.matchEntire(name)) { "Unsupported save resource identity $name." }
        val tokens = body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            require(line.startsWith("dw ", true)) { "$name: expected seven dw fields for the load-station record." }
            line.drop(3).split(',').map { it.trim() }
        }.toList()
        val reference = tokens.getOrNull(1)?.let(ProjectSourceDoorReference::fromEntryLabel)
        val words = tokens.mapIndexed { index, token ->
            if (index == 1 && reference != null) 0 else {
                require(ProjectSourceDoorReference.fromEntryLabel(token) == null) { "$name: DoorDef labels are only valid in the incoming-door field." }
                AsmPlmDocument.literal(token) ?: symbols[token] ?: error("$name: unsupported save field '$token'.")
            }
        }
        return SourceSaveResource(name, match.groupValues[1].toInt(16), match.groupValues[2].toInt(), match.groupValues[3].toInt(), words, reference)
            .also(SourceSaveResource::validate)
    }

    fun render(resource: SourceSaveResource): String {
        resource.validate()
        require(resource.identity == name(resource.ownerRoomId, resource.area, resource.slot))
        return buildString {
            appendLine("macro ${resource.identity}()")
            if (resource.cleared) appendLine("    ; Released save slot · area ${resource.area}, slot ${resource.slot}")
            resource.words.forEachIndexed { index, word ->
                val value = if (index == 1) resource.sourceDoor?.entryLabel ?: "\$${word.toString(16).uppercase().padStart(4, '0')}"
                    else "\$${word.toString(16).uppercase().padStart(4, '0')}"
                appendLine("    dw $value ; ${notes[index]}")
            }
            appendLine("endmacro")
        }
    }
}
