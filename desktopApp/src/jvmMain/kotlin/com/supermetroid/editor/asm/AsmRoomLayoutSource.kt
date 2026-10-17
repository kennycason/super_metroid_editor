package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.SourceRoomLayoutResource

/** Readable, uncompressed Asar declarations; the build validates and LZ5-encodes these assets. */
internal class AsmRoomLayoutSource(val text: String, private val projectRoomIds: Map<Int, String> = emptyMap()) {
    fun roomPrefix(roomId: Int) = projectRoomIds[roomId]?.let(::projectPrefix) ?: prefix(roomId)
    private fun dataDeclaration(roomId: Int, id: String) = roomPrefix(roomId) + "Data_" + encoded(id)
    private fun stateDeclaration(roomId: Int, id: String) = roomPrefix(roomId) + "State_" + encoded(id)
    private fun stateWrapper(roomId: Int, stateId: String, levelId: String) = renderState(roomId, stateId, levelId, projectRoomIds[roomId])
    private data class Macro(val name: String, val range: IntRange, val start: Int, val body: String)
    private data class Field(val range: IntRange, val offset: Int, val size: Int, val value: Int)
    private val macros by lazy {
        val found = Regex("(?m)^macro (SmeditLayout_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)")
            .findAll(text).map { Macro(it.groupValues[1], it.range, it.groups[2]!!.range.first, it.groupValues[2]) }.toList()
        require(found.map { it.name }.distinct().size == found.size) { "Duplicate layout declarations." }
        found.associateBy { it.name }
    }
    private val fieldCache = mutableMapOf<String, List<Field>>()
    private fun macro(name: String) = requireNotNull(macros[name]) { "Missing layout declaration $name." }
    private fun fields(name: String): List<Field> = fieldCache.getOrPut(name) {
        val m = macro(name)
        var offset = m.start
        var byteOffset = 0
        buildList {
            for (line in m.body.split('\n')) {
                val code = line.substringBefore(';').trimEnd('\r')
                if (code.isNotBlank()) {
                    val directive = requireNotNull(Regex("^[ \\t]*(dw|db)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected literal dw tiles or db BTS bytes." }
                    val size = if (directive.groupValues[1].equals("dw", true)) 2 else 1
                    var start = directive.range.last + 1
                    for (token in code.substring(start).split(',')) {
                        val clean = token.trim()
                        val local = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        val value = AsmPlmDocument.literal(clean)
                        require(value != null && value in 0..if (size == 2) 65535 else 255) { "$name: '$clean' is not a ${size * 8}-bit literal." }
                        add(Field(offset + local until offset + local + clean.length, byteOffset, size, value))
                        byteOffset += size
                        start += token.length + 1
                    }
                }
                offset += line.length + 1
            }
        }
    }
    fun resource(roomId: Int, levelId: String, width: Int, height: Int): SourceRoomLayoutResource {
        val name = dataDeclaration(roomId, levelId)
        val bytes = fields(name).flatMap { field -> List(field.size) { (field.value ushr (it * 8)) and 255 } }
        return SourceRoomLayoutResource(name, width, height, bytes)
    }
    fun resources(room: RoomEdits, width: Int, height: Int): Map<String, SourceRoomLayoutResource> {
        require(room.asmLayoutSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported room layout source ownership." }
        require(room.operations.all { it.edits.isEmpty() } && room.levelResourceOperations.values.flatten().all { it.edits.isEmpty() } &&
            room.states.all { state -> state.operations.all { it.edits.isEmpty() } }) { "Room layout has both authored source and GUI tile deltas." }
        return room.states.associate { state ->
            val expected = dataDeclaration(room.roomId, state.resources.level)
            val code = macro(stateDeclaration(room.roomId, state.id)).body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.toList()
            require(code == listOf("%$expected()")) { "Layout state ${state.id} no longer matches its shared/private resource. Use the layout sharing controls." }
            state.id to resource(room.roomId, state.resources.level, width, height)
        }
    }
    fun edit(room: RoomEdits, stateId: String, width: Int, height: Int, edits: List<TileEdit>): String {
        val current = resources(room, width, height).getValue(stateId)
        val updated = current.edited(edits)
        val changes = fields(current.identity).mapNotNull { field ->
            val value = (0 until field.size).fold(0) { value, index -> value or (updated.values[field.offset + index] shl (index * 8)) }
            if (value == field.value) null else field.range to hex(value, field.size * 2)
        }
        return changes.sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
    }
    fun line(roomId: Int, levelId: String): Int = text.take(macro(dataDeclaration(roomId, levelId)).range.first).count { it == '\n' }
    fun hasLayout(roomId: Int, levelId: String) = dataDeclaration(roomId, levelId) in macros
    fun cloneLayout(room: RoomEdits, stateId: String, newLevel: String): String {
        val oldLevel = room.states.single { it.id == stateId }.resources.level
        val old = dataDeclaration(room.roomId, oldLevel)
        val name = dataDeclaration(room.roomId, newLevel)
        require(name !in macros) { "Layout identity already exists." }
        return replaceStateLink(room.roomId, stateId, newLevel).trimEnd() + "\n\n" +
            text.substring(macro(old).range).replaceFirst("macro $old()", "macro $name()")
    }
    fun link(roomId: Int, stateId: String, levelId: String): String {
        macro(dataDeclaration(roomId, levelId))
        return replaceStateLink(roomId, stateId, levelId)
    }
    private fun replaceStateLink(roomId: Int, stateId: String, levelId: String): String {
        val wrapper = macro(stateDeclaration(roomId, stateId))
        val reference = Regex("(?m)^[ \\t]*(%SmeditLayout_[A-Za-z0-9_]+\\(\\))(?=[ \\t]*(?:;[^\\r\\n]*)?\\r?$)")
            .findAll(wrapper.body).toList().singleOrNull()?.groups?.get(1)
        val code = wrapper.body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.toList()
        require(reference != null && code == listOf(reference.value)) { "Layout state $stateId no longer has one typed resource reference." }
        return text.replaceRange(wrapper.start + reference.range.first, wrapper.start + reference.range.last + 1,
            "%${dataDeclaration(roomId, levelId)}()")
    }
    fun appendState(roomId: Int, stateId: String, levelId: String): String {
        require(stateDeclaration(roomId, stateId) !in macros) { "Layout state already exists." }
        macro(dataDeclaration(roomId, levelId))
        return text.trimEnd() + "\n\n" + stateWrapper(roomId, stateId, levelId)
    }
    fun resize(room: RoomEdits, oldWidth: Int, oldHeight: Int, width: Int, height: Int): String {
        val resources = resources(room, oldWidth * 16, oldHeight * 16).values.distinctBy { it.identity }
        val replacements = resources.map { resource ->
            val bytes = com.supermetroid.editor.ui.resizeLevelData(resource.bytes(), oldWidth, oldHeight, width, height)
            val resized = resource.copy(width = width * 16, height = height * 16, values = bytes.map { it.toInt() and 255 })
            val m = macro(resource.identity)
            val notes = m.body.lineSequence().mapNotNull { line ->
                val comment = line.substringAfter(';', "").trim()
                val owned = comment.startsWith("L1 tiles") || comment.startsWith("BTS") || comment.startsWith("L2 / retained tail") ||
                    comment.startsWith("Layer 1 byte count") || comment == "Retained trailing byte"
                if (owned) comment.substringAfter('|', "").trim().takeIf { it.isNotEmpty() } else comment.takeIf { it.isNotEmpty() }
            }.joinToString("") { "    ; $it\n" }
            m.range to render(resized).replaceFirst("()\n", "()\n$notes")
        }
        return replacements.sortedByDescending { it.first.first }.fold(text) { result, (range, body) -> result.replaceRange(range, body) }
    }
    companion object {
        fun prefix(roomId: Int) = "SmeditLayout_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun projectPrefix(id: String) = "SmeditLayout_Project_${AsmProjectRoomLabels.encoded(id)}_"
        private fun encoded(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun dataName(roomId: Int, levelId: String, projectRoomId: String? = null) = (projectRoomId?.let(::projectPrefix) ?: prefix(roomId)) + "Data_" + encoded(levelId)
        fun stateName(roomId: Int, stateId: String, projectRoomId: String? = null) = (projectRoomId?.let(::projectPrefix) ?: prefix(roomId)) + "State_" + encoded(stateId)
        fun renderState(roomId: Int, stateId: String, levelId: String, projectRoomId: String? = null) = "macro ${stateName(roomId, stateId, projectRoomId)}()\n    %${dataName(roomId, levelId, projectRoomId)}()\nendmacro\n"
        fun render(resource: SourceRoomLayoutResource): String = buildString {
            appendLine("macro ${resource.identity}()")
            val layerBytes = resource.values[0] or (resource.values[1] shl 8)
            val allocated = layerBytes / 2
            appendLine("    dw ${hex(layerBytes, 4)} ; Layer 1 byte count (keep allocation size)")
            fun rows(start: Int, count: Int, size: Int, label: String) {
                appendLine("    ; $label · row-major · coordinates are zero-based")
                var index = 0
                while (index < count) {
                    val length = minOf(16, resource.width - index % resource.width, count - index)
                    val tokens = (index until index + length).map { cell ->
                        val offset = start + cell * size
                        val value = resource.values[offset] or (if (size == 2) resource.values[offset + 1] shl 8 else 0)
                        hex(value, size * 2)
                    }
                    appendLine("    ${if (size == 2) "dw" else "db"} ${tokens.joinToString(", ")} ; $label y=${index / resource.width}, x=${index % resource.width}")
                    index += length
                }
            }
            rows(2, allocated, 2, "L1 tiles (type/flip/metatile)")
            rows(2 + layerBytes, allocated, 1, "BTS")
            val tail = 2 + layerBytes + allocated
            val words = (resource.values.size - tail) / 2
            if (words > 0) rows(tail, words, 2, "L2 / retained tail")
            if ((resource.values.size - tail) % 2 != 0) appendLine("    db ${hex(resource.values.last(), 2)} ; Retained trailing byte")
            appendLine("endmacro")
        }
        /** Context navigation works without a built ROM address, including invalid drafts. */
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditLayout_Project_([0-9A-F]+)_(Data|State)_([0-9a-f]+)\\(\\)").find(lines[index])?.let {
                    val projectId = AsmProjectRoomLabels.decode(it.groupValues[1]) ?: return null
                    val encoded = it.groupValues[3]
                    if (encoded.length % 2 != 0) return null
                    val id = encoded.chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
                    return AsmEditorTarget.Room(0, projectRoomId = projectId,
                        layoutId = id.takeIf { _ -> it.groupValues[2] == "Data" }, stateId = id.takeIf { _ -> it.groupValues[2] == "State" })
                }
                Regex("^macro SmeditLayout_([0-9A-F]{4})_(Data|State)_([0-9a-f]+)\\(\\)").find(lines[index])?.let {
                    val encoded = it.groupValues[3]
                    val identity = runCatching { encoded.chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
                    return AsmEditorTarget.Room(it.groupValues[1].toInt(16),
                        layoutId = identity.takeIf { _ -> it.groupValues[2] == "Data" }, stateId = identity.takeIf { _ -> it.groupValues[2] == "State" })
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
        fun roomAt(lines: List<String>, line: Int): Int? = targetAt(lines, line)?.roomId
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
    }
}
