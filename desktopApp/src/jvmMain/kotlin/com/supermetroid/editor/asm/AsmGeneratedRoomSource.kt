package com.supermetroid.editor.asm

/** A build artifact, deliberately absent from the editable source index/buffers.
 * This indexes only SMEDIT's own emitted labels, not arbitrary Asar syntax. */
internal data class AsmGeneratedRoomSource(
    val lines: List<String>,
    val generatedAt: String,
    val definitions: Map<String, Int>,
    val sections: List<AsmGeneratedRoomSection>,
    val roomNames: Map<Int, String> = emptyMap(),
    val isRoomDataSnapshot: Boolean = false,
) {
    fun sectionAt(lineIndex: Int): AsmGeneratedRoomSection? =
        sections.lastOrNull { it.lineIndex <= lineIndex }

    fun groups(fallbackNames: Map<Int, String> = emptyMap(), query: String = ""): List<AsmRoomDataGroup> =
        sections.groupBy { it.roomId }.mapNotNull { (roomId, sections) ->
            val title = roomId?.let { roomNames[it] ?: fallbackNames[it] ?: "Room $${it.toString(16).uppercase()}" }
                ?: "Other build writes"
            val matchesRoom = query.isBlank() || title.contains(query, true) ||
                roomId?.toString(16)?.contains(query.removePrefix("$"), true) == true
            val visible = if (matchesRoom) sections else sections.filter {
                it.title.contains(query, true) || it.label.contains(query, true)
            }
            if (visible.isEmpty()) null else AsmRoomDataGroup(roomId, title, visible)
        }.sortedWith(compareBy<AsmRoomDataGroup> { it.roomId == null }.thenBy { it.title.lowercase() })

    companion object {
        const val FILE_NAME = "generated-rooms.asm"
        private val label = Regex("^((?:SmeditRoom|SmeditProjectRoom|SmeditObjects_)[A-Za-z0-9_]+):$")
        private val plm = Regex("SmeditRoom_([0-9A-F]+)_State([0-9]+)_Plms")
        private val roomLabel = Regex("SmeditRoom_([0-9A-F]+)_.*")
        private val authoredRoom = Regex("SmeditObjects_([0-9A-F]+)_.*")
        private val roomMetadata = Regex("^; @room ([0-9A-F]+) (.+)$")
        private val sectionMetadata = Regex("^; @section (\\S+) (.+)$")
        private val origin = Regex("^org \\$([0-9A-Fa-f]{6})$")

        fun parse(source: String, generatedAt: String): AsmGeneratedRoomSource? {
            val lines = source.lines()
            val definitions = lines.mapIndexedNotNull { index, line ->
                label.matchEntire(line.trim())?.groupValues?.get(1)?.let { it to index }
            }.toMap()
            if (definitions.isEmpty()) return null
            val names = lines.mapNotNull { roomMetadata.matchEntire(it)?.groupValues?.let { it[1].toInt(16) to it[2] } }.toMap()
            val titles = lines.mapNotNull { sectionMetadata.matchEntire(it)?.groupValues?.let { it[1] to it[2] } }.toMap()
            val projectRooms = lines.mapNotNull { Regex("^; @project-room ([0-9A-F]+) ([0-9A-F]+)$").matchEntire(it)?.groupValues?.let { fields ->
                AsmProjectRoomLabels.decode(fields[1])?.let { id -> id to fields[2].toInt(16) }
            } }.toMap()
            val sections = definitions.filterKeys { !it.endsWith("_End") }.map { (name, line) ->
                val match = plm.matchEntire(name)
                val projectId = AsmProjectRoomLabels.identity(name)
                val roomId = projectId?.let(projectRooms::get) ?: (roomLabel.matchEntire(name) ?: authoredRoom.matchEntire(name))?.groupValues?.get(1)?.toIntOrNull(16)
                val address = (line - 1 downTo 0).firstNotNullOfOrNull { origin.matchEntire(lines[it])?.groupValues?.get(1) }
                AsmGeneratedRoomSection(
                    label = name,
                    lineIndex = line,
                    roomId = roomId,
                    projectRoomId = projectId,
                    title = titles[name] ?: when {
                        match != null -> "Source state ${match.groupValues[2]} · Objects (PLMs)"
                        name.startsWith("SmeditObjects_") && name.contains("_Scroll_") -> "Scroll trigger commands"
                        name.startsWith("SmeditObjects_") && name.contains("_Save_") -> "Save spawn record"
                        name.startsWith("SmeditObjects_") && name.endsWith("_Plms") -> "Authored objects (PLMs)"
                        else -> address?.let { "Bytes at $${it.take(2)}:${it.drop(2)}" } ?: "Unmapped build write"
                    },
                )
            }
            return AsmGeneratedRoomSource(lines, generatedAt, definitions, sections, names,
                lines.firstOrNull() == AsmRoomDataSnapshot.MARKER)
        }
    }
}

internal data class AsmGeneratedRoomSection(
    val label: String,
    val lineIndex: Int,
    val roomId: Int?,
    val title: String,
    val projectRoomId: String? = null,
)

internal data class AsmRoomDataGroup(val roomId: Int?, val title: String, val sections: List<AsmGeneratedRoomSection>)
internal fun roomDataGroupKey(roomId: Int?, buildWrites: Boolean) = "$buildWrites:${roomId ?: "other"}"
