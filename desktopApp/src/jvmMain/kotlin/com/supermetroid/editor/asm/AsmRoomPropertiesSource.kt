package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.SourceRoomProperties

/** Conservative literal lens: ordinary room fields, not arbitrary expressions or pointer formats. */
internal class AsmRoomPropertiesSource(val text: String, private val projectRoomIds: Map<Int, String> = emptyMap()) {
    fun roomPrefix(roomId: Int) = projectRoomIds[roomId]?.let(::projectPrefix) ?: prefix(roomId)
    private fun header(roomId: Int) = roomPrefix(roomId) + "Header"
    private fun stateDeclaration(roomId: Int, id: String) = roomPrefix(roomId) + "State_" + encodeState(id)
    private data class Macro(val name: String, val range: IntRange, val start: Int, val body: String)
    private data class Field(val range: IntRange, val size: Int, val value: Int)
    private val macros by lazy {
        val found = Regex("(?m)^macro (SmeditRoomInfo_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)")
            .findAll(text).map { Macro(it.groupValues[1], it.range, it.groups[2]!!.range.first, it.groupValues[2]) }.toList()
        require(found.map { it.name }.distinct().size == found.size) { "Duplicate room-property declarations." }
        found.associateBy { it.name }
    }
    private val cache = mutableMapOf<String, List<Field>>()
    private fun macro(name: String) = requireNotNull(macros[name]) { "Missing room-property declaration $name." }
    private fun fields(name: String): List<Field> = cache.getOrPut(name) {
        val m = macro(name)
        var offset = m.start
        buildList {
            for (line in m.body.split('\n')) {
                val code = line.substringBefore(';').trimEnd('\r')
                if (code.isNotBlank()) {
                    val directive = requireNotNull(Regex("^[ \\t]*(db|dw)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected literal db/dw fields." }
                    val size = if (directive.groupValues[1].equals("dw", true)) 2 else 1
                    var start = directive.range.last + 1
                    for (token in code.substring(start).split(',')) {
                        val clean = token.trim()
                        val local = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        val value = AsmPlmDocument.literal(clean)
                        require(value != null && value in 0..if (size == 1) 255 else 65535) { "$name: '$clean' is not a ${size * 8}-bit literal." }
                        add(Field(offset + local until offset + local + clean.length, size, value))
                        start += token.length + 1
                    }
                }
                offset += line.length + 1
            }
        }
    }
    private fun values(name: String, sizes: List<Int>): List<Int> {
        val fields = fields(name)
        require(fields.map { it.size } == sizes) { "$name: room field widths/order changed; repair its declaration." }
        return fields.map { it.value }
    }
    fun resource(room: RoomEdits): SourceRoomProperties {
        require(room.asmRoomPropertiesSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported room-property source ownership." }
        require(room.roomHeaderChange?.copy(width = null, height = null, doorOut = null).let { it == null || it == RoomHeaderChange() } &&
            room.stateDataChange == null && room.states.all { it.stateDataChange == null }) { "Room properties have both authored source and GUI overrides." }
        val h = values(header(room.roomId), List(7) { 1 })
        val states = room.states.associate { state ->
            val v = values(stateDeclaration(room.roomId, state.id), listOf(1, 1, 1, 2))
            state.id to StateDataChange(v[0], v[1], v[2], v[3])
        }
        return SourceRoomProperties(RoomHeaderChange(index = h[0], area = h[1], mapX = h[2], mapY = h[3], upScroller = h[4], downScroller = h[5], creBitflag = h[6]), states)
    }
    fun editHeader(room: RoomEdits, header: RoomHeaderChange): String {
        val current = resource(room)
        SourceRoomProperties(header, current.states)
        return replace(header(room.roomId), listOf(header.index!!, header.area!!, header.mapX!!, header.mapY!!, header.upScroller!!, header.downScroller!!, header.creBitflag!!))
    }
    /** Area-dependent save lenses need the current header even before a GUI projection is loaded. */
    fun area(roomId: Int): Int = values(header(roomId), List(7) { 1 })[1].also {
        require(it in 0..6) { "Invalid authored room area." }
    }
    fun editStates(room: RoomEdits, replacements: Map<String, StateDataChange>): String {
        val current = resource(room)
        require(current.states.keys.containsAll(replacements.keys)) { "Unknown room-property states." }
        SourceRoomProperties(current.header, current.states + replacements)
        var result = text
        for ((id, fields) in replacements) result = AsmRoomPropertiesSource(result, projectRoomIds).replace(stateDeclaration(room.roomId, id),
            listOf(fields.tileset!!, fields.musicData!!, fields.musicTrack!!, fields.bgScrolling!!))
        return result
    }
    private fun replace(name: String, values: List<Int>): String = fields(name).mapIndexedNotNull { index, field ->
        if (field.value == values[index]) null else field.range to hex(values[index], field.size * 2)
    }.sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
    fun cloneState(room: RoomEdits, template: String, newId: String): String {
        resource(room)
        require(stateDeclaration(room.roomId, newId) !in macros) { "Room-property state already exists." }
        val definition = macro(stateDeclaration(room.roomId, template))
        return text.trimEnd() + "\n\n" + text.substring(definition.range).replaceFirst(stateDeclaration(room.roomId, template), stateDeclaration(room.roomId, newId))
    }
    fun hasState(roomId: Int, stateId: String): Boolean = stateDeclaration(roomId, stateId) in macros
    fun line(roomId: Int, stateId: String? = null): Int = text.take(macro(if (stateId == null) header(roomId) else stateDeclaration(roomId, stateId)).range.first).count { it == '\n' }
    companion object {
        fun prefix(roomId: Int) = "SmeditRoomInfo_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun projectPrefix(id: String) = "SmeditRoomInfo_Project_${AsmProjectRoomLabels.encoded(id)}_"
        fun projectIdentities(project: com.supermetroid.editor.data.SmEditProject) = AsmProjectRoomLabels.identities(project)
        fun headerName(roomId: Int) = prefix(roomId) + "Header"
        private fun encodeState(id: String) = id.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun stateName(roomId: Int, stateId: String) = prefix(roomId) + "State_" + encodeState(stateId)
        fun render(room: RoomEdits, name: String, properties: SourceRoomProperties, projectRoomId: String? = null): String = buildString {
            val roomPrefix = projectRoomId?.let(::projectPrefix) ?: prefix(room.roomId)
            appendLine("; SMEDIT room properties · ${name.replace('\n', ' ').replace('\r', ' ')}")
            appendLine("; Header scalars only: dimensions and door pointers use their joint Resize/Doors workflows.")
            appendLine("macro ${roomPrefix}Header()")
            val header = properties.header
            listOf("Room index" to header.index, "Area" to header.area, "Map X" to header.mapX, "Map Y" to header.mapY,
                "Up scroller" to header.upScroller, "Down scroller" to header.downScroller, "CRE flags" to header.creBitflag).forEach { (label, value) ->
                appendLine("    db ${hex(requireNotNull(value), 2)} ; $label")
            }
            appendLine("endmacro\n")
            for ((id, state) in properties.states) {
                appendLine("macro ${roomPrefix}State_${encodeState(id)}()")
                appendLine("    db ${hex(state.tileset!!, 2)} ; Tileset")
                appendLine("    db ${hex(state.musicData!!, 2)} ; Music data")
                appendLine("    db ${hex(state.musicTrack!!, 2)} ; Music track")
                appendLine("    dw ${hex(state.bgScrolling!!, 4)} ; BG scrolling (horizontal low / vertical high)")
                appendLine("endmacro\n")
            }
        }
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditRoomInfo_Project_([0-9A-F]+)_(Header|State_([0-9a-f]+))\\(\\)").find(lines[index])?.let { m ->
                    val id = AsmProjectRoomLabels.decode(m.groupValues[1]) ?: return null
                    val state = m.groupValues[3].takeIf { it.isNotEmpty() && it.length % 2 == 0 }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()?.toString(Charsets.UTF_8)
                    return AsmEditorTarget.Room(0, stateId = state, projectRoomId = id)
                }
                Regex("^macro SmeditRoomInfo_([0-9A-F]{4})_(Header|State_([0-9a-f]+))\\(\\)").find(lines[index])?.let { m ->
                    val state = m.groupValues[3].takeIf { it.isNotEmpty() }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()?.toString(Charsets.UTF_8)
                    return AsmEditorTarget.Room(m.groupValues[1].toInt(16), stateId = state)
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
    }
}
