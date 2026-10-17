package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.SourceInitialScrollResource

/** Small typed lens over authored db tables. Never interpret arbitrary Asar expressions. */
internal class AsmInitialScrollSource(val text: String, private val symbols: Map<String, Int> = emptyMap(), private val projectRoomIds: Map<Int, String> = emptyMap()) {
    private fun prefix(roomId: Int) = Companion.prefix(roomId, projectRoomIds[roomId])
    private fun stateName(roomId: Int, id: String) = Companion.stateName(roomId, id, projectRoomIds[roomId])
    private fun renderState(roomId: Int, id: String, table: String) = Companion.renderState(roomId, id, table, projectRoomIds[roomId])
    private data class Macro(val name: String, val range: IntRange, val bodyStart: Int, val body: String)
    private val fieldCache = mutableMapOf<String, List<Pair<IntRange, Int>>>()
    private val macros: Map<String, Macro> by lazy {
        val envelopes = Regex("(?m)^macro (SmeditScrolls_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)")
            .findAll(text).map { m -> Macro(m.groupValues[1], m.range, m.groups[2]!!.range.first, m.groupValues[2]) }.toList()
        require(envelopes.map { it.name }.distinct().size == envelopes.size) { "Duplicate initial scroll macros." }
        envelopes.associateBy { it.name }
    }
    private fun macro(name: String) = requireNotNull(macros[name]) { "Missing initial scroll source $name." }
    private fun reference(roomId: Int, stateId: String): String {
        val body = macro(stateName(roomId, stateId)).body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.toList()
        require(body.size == 1) { "Initial scroll state must reference one table." }
        val ref = Regex("%(${prefix(roomId)}Table_[0-9]+)\\(\\)").matchEntire(body.single())?.groupValues?.get(1)
        return requireNotNull(ref) { "Initial scroll state must reference its room's table." }
    }
    private fun fields(name: String): List<Pair<IntRange, Int>> {
        fieldCache[name]?.let { return it }
        val m = macro(name)
        val fields = mutableListOf<Pair<IntRange, Int>>()
        var offset = m.bodyStart
        for (line in m.body.split('\n')) {
            val code = line.substringBefore(';').trimEnd('\r')
            if (code.isNotBlank()) {
                val db = requireNotNull(Regex("^[ \\t]*db[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected db scroll bytes (no terminator)." }
                var start = db.range.last + 1
                for (token in code.substring(start).split(',')) {
                    val clean = token.trim()
                    val local = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    val value = AsmPlmDocument.literal(clean) ?: symbols[clean]
                    require(value != null && value in 0..2) { "$name: '$clean' is not a Red/Blue/Green byte." }
                    fields += (offset + local until offset + local + clean.length) to value
                    start += token.length + 1
                }
            }
            offset += line.length + 1
        }
        return fields.toList().also { fieldCache[name] = it }
    }
    fun resource(roomId: Int, stateId: String, width: Int, height: Int): SourceInitialScrollResource {
        val name = reference(roomId, stateId)
        return SourceInitialScrollResource(name, width, height, fields(name).map { it.second })
    }
    fun resources(room: RoomEdits, width: Int, height: Int): Map<String, SourceInitialScrollResource> {
        require(room.asmInitialScrollSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported initial scroll source ownership." }
        require(room.scrollChanges.isEmpty() && room.states.all { it.scrollChanges.isEmpty() }) { "Initial scrolls have both GUI deltas and authored source." }
        require(room.states.map { it.id }.distinct().size == room.states.size) { "Duplicate scroll state identities." }
        return room.states.associate { it.id to resource(room.roomId, it.id, width, height) }
    }
    /** Invalid color bytes must still be navigable for repair; fall back to the state wrapper. */
    fun line(roomId: Int, stateId: String): Int {
        val target = runCatching { macro(reference(roomId, stateId)) }.getOrElse { macro(stateName(roomId, stateId)) }
        return text.take(target.range.first).count { it == '\n' }
    }
    fun cloneState(roomId: Int, templateId: String, newId: String): String {
        require(stateName(roomId, newId) !in macros) { "Initial scroll state already exists." }
        return text.trimEnd() + "\n\n" + renderState(roomId, newId, reference(roomId, templateId))
    }
    fun hasState(roomId: Int, stateId: String): Boolean = stateName(roomId, stateId) in macros
    /** Visual edits are state-local, even if two state wrappers share a table. Raw table edits affect aliases. */
    fun edit(room: RoomEdits, stateId: String, width: Int, height: Int, index: Int, value: Int): String {
        val inputs = resources(room, width, height)
        require(index in 0 until width * height && value in 0..2)
        val selected = inputs.getValue(stateId)
        return editGrids(room, setOf(stateId), width, height, selected.values.toMutableList().also { it[index] = value })
    }

    /** Batch grid edits preserve independent identities and fork a shared table only once per affected group. */
    fun editGrids(room: RoomEdits, stateIds: Set<String>, width: Int, height: Int, values: List<Int>): String {
        val inputs = resources(room, width, height)
        require(stateIds.isNotEmpty() && inputs.keys.containsAll(stateIds)) { "Unknown initial scroll states." }
        require(values.size == width * height && values.all { it in 0..2 }) { "Invalid initial scroll grid." }
        var result = text
        for ((identity, states) in inputs.entries.filter { it.key in stateIds }.groupBy { it.value.identity }) {
            if (states.first().value.values == values) continue
            val source = AsmInitialScrollSource(result, symbols, projectRoomIds)
            val table = source.macro(identity)
            val changes = source.fields(identity).mapIndexedNotNull { index, (span, old) ->
                if (old == values[index]) null else span to hex(values[index])
            }
            if (inputs.any { it.key !in stateIds && it.value.identity == identity }) {
                var ordinal = 1
                while (prefix(room.roomId) + "Table_$ordinal" in source.macros) ordinal++
                val name = prefix(room.roomId) + "Table_$ordinal"
                val clone = changes.sortedByDescending { it.first.first }.fold(result.substring(table.range)) { body, (span, value) ->
                    body.replaceRange(span.first - table.range.first..span.last - table.range.first, value)
                }.replaceFirst("macro $identity()", "macro $name()")
                result = states.map { source.macro(stateName(room.roomId, it.key)) }.sortedByDescending { it.range.first }
                    .fold(result) { current, wrapper -> current.replaceRange(wrapper.range, current.substring(wrapper.range).replace("%$identity()", "%$name()")) }
                    .trimEnd() + "\n\n" + clone
            } else {
                result = changes.sortedByDescending { it.first.first }.fold(result) { current, (span, value) -> current.replaceRange(span, value) }
            }
        }
        return result
    }

    /** Room dimensions are shared: resize every physical table once, retaining independent identities. */
    fun resize(room: RoomEdits, oldWidth: Int, oldHeight: Int, width: Int, height: Int): String {
        val inputs = resources(room, oldWidth, oldHeight)
        val changes = inputs.values.distinctBy { it.identity }.map { table ->
            val values = List(width * height) { index ->
                val x = index % width
                val y = index / width
                if (x < oldWidth && y < oldHeight) table.values[y * oldWidth + x] else 1
            }
            val resized = SourceInitialScrollResource(table.identity, width, height, values)
            val m = macro(table.identity)
            val tokens = fields(table.identity).map { text.substring(it.first) }
            // Restructuring db rows keeps literal/symbol spelling and handwritten notes.
            val notes = m.body.lineSequence().mapNotNull { line ->
                val comment = line.substringAfter(';', "").trim()
                    .replace(Regex("^Screen \\([0-9]+,[0-9]+\\) · (Red|Blue|Green)\\s*;?\\s*"), "")
                comment.takeIf { it.isNotEmpty() }
            }.joinToString("") { "    ; $it\n" }
            val body = buildString {
                append(notes)
                resized.values.forEachIndexed { index, value ->
                    val x = index % width
                    val y = index / width
                    val token = if (x < oldWidth && y < oldHeight) tokens[y * oldWidth + x] else hex(value)
                    appendLine("    db $token ; Screen (${x + 1},${y + 1}) · ${listOf("Red", "Blue", "Green")[value]}")
                }
            }
            (m.bodyStart until m.bodyStart + m.body.length) to body
        }
        return changes.sortedByDescending { it.first.first }.fold(text) { current, (span, replacement) -> current.replaceRange(span, replacement) }
    }

    fun tidy(room: RoomEdits, externalReferences: Set<String> = emptySet()): String {
        val owned = macros.values.filter { it.name.startsWith(prefix(room.roomId)) }
        val outside = owned.sortedByDescending { it.range.first }.fold(text) { current, m -> current.removeRange(m.range) }
        val live = (room.states.map { stateName(room.roomId, it.id) } + references(outside) + externalReferences).toMutableSet()
        val queue = java.util.ArrayDeque(live)
        while (queue.isNotEmpty()) {
            macros[queue.removeFirst()]?.let { m -> references(m.body).forEach { if (live.add(it)) queue.add(it) } }
        }
        return owned.filter { it.name !in live }.sortedByDescending { it.range.first }.fold(text) { current, m -> current.removeRange(m.range) }
    }

    /** Refresh only machine-owned coordinate/color comments; leave handwritten notes intact. */
    fun withComments(room: RoomEdits, width: Int, height: Int): String {
        val tables = resources(room, width, height).values.distinctBy { it.identity }
        val changes = mutableListOf<Pair<IntRange, String>>()
        for (table in tables) fields(table.identity).forEachIndexed { index, (span, value) ->
            val start = text.indexOf(';', span.last + 1)
            val end = text.indexOf('\n', span.last + 1).takeIf { it >= 0 } ?: text.length
            if (start >= 0 && start < end) {
                val comment = text.substring(start, end)
                val match = Regex("^; Screen \\([0-9]+,[0-9]+\\) · (Red|Blue|Green)").find(comment)
                if (match != null) changes += (start..start + match.range.last) to "; Screen (${index % width + 1},${index / width + 1}) · ${listOf("Red", "Blue", "Green")[value]}"
            }
        }
        return changes.distinctBy { it.first }.sortedByDescending { it.first.first }.fold(text) { current, (span, replacement) -> current.replaceRange(span, replacement) }
    }

    companion object {
        fun prefix(roomId: Int, projectId: String? = null) = AsmRoomSourceContext.prefix("SmeditScrolls", roomId, projectId)
        fun stateName(roomId: Int, stateId: String, projectId: String? = null) = prefix(roomId, projectId) + "State_" + stateId.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun renderState(roomId: Int, stateId: String, table: String, projectId: String? = null) = "macro ${stateName(roomId, stateId, projectId)}()\n    %$table()\nendmacro\n"
        fun renderTable(resource: SourceInitialScrollResource): String = buildString {
            appendLine("macro ${resource.identity}()")
            resource.values.forEachIndexed { i, value ->
                val color = listOf("Red", "Blue", "Green")[value]
                appendLine("    db ${hex(value)} ; Screen (${i % resource.width + 1},${i / resource.width + 1}) · $color")
            }
            appendLine("endmacro")
        }
        private fun hex(value: Int) = "\$${value.toString(16).uppercase().padStart(2, '0')}"
        fun references(text: String): Set<String> = Regex("%?(SmeditScrolls_[A-Za-z0-9_]+)").findAll(text.lineSequence()
            .joinToString("\n") { it.substringBefore(';') }).map { it.groupValues[1] }.toSet()
    }
}
