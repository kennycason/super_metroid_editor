package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.*

internal const val ENEMY_SOURCE_FILE = "bank_A1.asm"

/** One document owns the paired values; checked placements emit population in A1 and GFX in B4. */
internal class AsmEnemySource(val text: String, private val symbols: Map<String, Int> = emptyMap(), private val projectRoomIds: Map<Int, String> = emptyMap()) {
    private fun prefix(roomId: Int) = Companion.prefix(roomId, projectRoomIds[roomId])
    private fun stateName(roomId: Int, id: String) = Companion.stateName(roomId, id, projectRoomIds[roomId])
    private fun renderState(roomId: Int, id: String, population: String, graphics: String) = Companion.renderState(roomId, id, population, graphics, projectRoomIds[roomId])
    private val resourceCache = mutableMapOf<Pair<Int, String>, SourceEnemyResources>()
    private data class Macro(val name: String, val range: IntRange, val start: Int, val body: String)
    private val macros by lazy {
        val found = Regex("(?m)^macro (SmeditEnemies_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)")
            .findAll(text).map { Macro(it.groupValues[1], it.range, it.groups[2]!!.range.first, it.groupValues[2]) }.toList()
        require(found.map { it.name }.distinct().size == found.size) { "Duplicate enemy source macros." }
        found.associateBy { it.name }
    }
    private fun macro(name: String) = requireNotNull(macros[name]) { "Missing enemy source $name." }
    private fun refs(roomId: Int, stateId: String): Pair<String, String> {
        val lines = codeLines(macro(stateName(roomId, stateId)).body)
        require(lines.size == 2) { "Enemy state must reference one population and one GFX list." }
        fun read(line: String, kind: String) = requireNotNull(Regex("%(${prefix(roomId)}${kind}_[0-9]+)\\(\\)").matchEntire(line)?.groupValues?.get(1)) {
            "Enemy state must reference its room's $kind macro."
        }
        return read(lines[0], "Population") to read(lines[1], "Graphics")
    }
    private fun values(line: String, directive: String): List<Int> {
        val match = requireNotNull(Regex("$directive\\s+(.+)", RegexOption.IGNORE_CASE).matchEntire(line)) { "Expected $directive enemy data." }
        return match.groupValues[1].split(',').map { token ->
            val clean = token.trim()
            requireNotNull(AsmPlmDocument.literal(clean) ?: symbols[clean]) { "Unsupported enemy expression '$clean'; use literal words or known source labels." }.also {
                require(it in 0..if (directive == "db") 255 else 0xFFFF) { "Enemy value '$clean' does not fit $directive." }
            }
        }
    }
    fun resource(roomId: Int, stateId: String): SourceEnemyResources = resourceCache.getOrPut(roomId to stateId) {
        val (pop, gfx) = refs(roomId, stateId)
        val population = codeLines(macro(pop).body)
        require(population.size >= 2 && population[population.lastIndex - 1].let { values(it, "dw") } == listOf(0xFFFF)) {
            "$pop: expected dw FFFF followed by one db kill quota."
        }
        val quota = values(population.last(), "db").singleOrNull() ?: error("$pop: expected one kill quota byte.")
        val entries = population.dropLast(2).map { enemyEntryFromFields(values(it, "dw")) }
        val graphics = codeLines(macro(gfx).body)
        require(graphics.isNotEmpty() && values(graphics.last(), "dw") == listOf(0xFFFF)) { "$gfx: expected final dw FFFF." }
        val transfers = graphics.dropLast(1).map { line ->
            val row = values(line, "dw")
            require(row.size == 2) { "$gfx: expected species and VRAM destination words." }
            RomParser.EnemyGfxEntry(row[0], row[1])
        }
        SourceEnemyResources(SourceEnemyPopulation(pop, entries, quota), SourceEnemyGraphics(gfx, transfers))
    }
    fun resources(room: RoomEdits): Map<String, SourceEnemyResources> {
        require(room.asmEnemySourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported enemy source ownership." }
        require(room.enemyChanges.isEmpty() && room.states.all { it.enemyChanges.isEmpty() }) { "Enemy data has both source ownership and GUI deltas." }
        require(room.states.map { it.id }.distinct().size == room.states.size) { "Duplicate enemy state identities." }
        return room.states.associate { state ->
            val input = try { resource(room.roomId, state.id) } catch (error: Exception) {
                throw IllegalArgumentException("$ENEMY_SOURCE_FILE:${errorLine(room.roomId, state.id, error.message.orEmpty()) + 1}: ${error.message}", error)
            }
            state.id to input
        }
    }
    fun errorLine(roomId: Int, stateId: String, message: String): Int {
        val names = runCatching { refs(roomId, stateId) }.getOrNull()
        val target = runCatching { names?.let { macro(if (message.contains("GFX", true) || message.contains(it.second)) it.second else it.first) } }.getOrNull()
        val token = Regex("'([^']+)'").find(message)?.groupValues?.get(1)
        val offset = target?.let { m -> token?.let { m.body.indexOf(it).takeIf { index -> index >= 0 } }?.plus(m.start) ?: m.range.first }
            ?: runCatching { macro(stateName(roomId, stateId)).range.first }.getOrDefault(0)
        return text.take(offset).count { it == '\n' }
    }
    /** Only machine-owned descriptions change; authored operands, notes and line endings remain byte-for-byte. */
    fun withComments(room: RoomEdits): String {
        val inputs = resources(room)
        val changes = mutableListOf<Pair<IntRange, String>>()
        fun annotate(name: String, descriptions: List<String>) {
            val m = macro(name)
            var offset = m.start
            var index = 0
            for (line in m.body.split('\n')) {
                if (line.substringBefore(';').trim().startsWith("dw ", true) && index < descriptions.size) {
                    val comment = Regex("; (?:Actor [0-9]+:|GFX:) [^|\\r\\n]*").find(line)
                    if (comment != null) changes += (offset + comment.range.first..offset + comment.range.last) to
                        ("; ${descriptions[index]}" + if (comment.value.endsWith(' ')) " " else "")
                    index++
                }
                offset += line.length + 1
            }
        }
        inputs.values.map { it.population }.distinctBy { it.identity }.forEach { pop ->
            annotate(pop.identity, pop.entries.mapIndexed { i, e -> "Actor $i: ${RomParser.enemyName(e.id)}" })
        }
        inputs.values.map { it.graphics }.distinctBy { it.identity }.forEach { gfx ->
            annotate(gfx.identity, gfx.entries.map { "GFX: ${RomParser.enemyName(it.speciesId)} · palette ${8 + (it.paletteIndex and 255)}" })
        }
        return changes.sortedByDescending { it.first.first }.fold(text) { t, (span, replacement) -> t.replaceRange(span, replacement) }
    }
    fun line(roomId: Int, stateId: String, index: Int? = null, graphics: Boolean = false): Int {
        val m = runCatching { val names = refs(roomId, stateId); macro(if (graphics) names.second else names.first) }
            .getOrElse { macro(stateName(roomId, stateId)) }
        val base = text.take(m.start).count { it == '\n' }
        if (index == null) return text.take(m.range.first).count { it == '\n' }
        return base + m.body.lineSequence().withIndex().filter { it.value.substringBefore(';').trim().startsWith("dw ", true) }
            .elementAtOrNull(index)?.index.orZero()
    }
    private fun Int?.orZero() = this ?: 0
    fun hasState(roomId: Int, stateId: String) = stateName(roomId, stateId) in macros
    fun cloneState(roomId: Int, template: String, newId: String): String {
        require(!hasState(roomId, newId)) { "Enemy state already exists." }
        val (pop, gfx) = refs(roomId, template)
        return text.trimEnd() + "\n\n" + renderState(roomId, newId, pop, gfx)
    }
    /** Detach each list independently. One source replacement changes both or neither. */
    fun replace(room: RoomEdits, stateId: String, population: List<RomParser.EnemyEntry>, graphics: List<RomParser.EnemyGfxEntry>): String {
        val inputs = resources(room)
        val selected = inputs.getValue(stateId)
        if (population == selected.population.entries && graphics == selected.graphics.entries) return text
        var result = text
        var pop = selected.population.identity
        var gfx = selected.graphics.identity
        fun next(kind: String): String {
            var ordinal = 1
            while (prefix(room.roomId) + "${kind}_$ordinal" in macros) ordinal++
            return prefix(room.roomId) + "${kind}_$ordinal"
        }
        if (population != selected.population.entries) {
            if (inputs.values.count { it.population.identity == pop } > 1) {
                val originalMacro = macro(pop)
                pop = next("Population")
                result = result.trimEnd() + "\n\n" + text.substring(originalMacro.range)
                    .replaceFirst("macro ${selected.population.identity}()", "macro $pop()")
            }
            val current = AsmEnemySource(result, symbols, projectRoomIds)
            result = current.replaceRows(pop, selected.population.entries.map { it.fields() }, population.map { it.fields() })
                ?: result.replaceRange(current.macro(pop).range, renderPopulation(selected.population.copy(identity = pop, entries = population), current.macro(pop).body, selected.population.entries))
        }
        if (graphics != selected.graphics.entries) {
            if (inputs.values.count { it.graphics.identity == gfx } > 1) {
                val originalMacro = macro(gfx)
                gfx = next("Graphics")
                result = result.trimEnd() + "\n\n" + text.substring(originalMacro.range)
                    .replaceFirst("macro ${selected.graphics.identity}()", "macro $gfx()")
            }
            val current = AsmEnemySource(result, symbols, projectRoomIds)
            result = current.replaceRows(gfx, selected.graphics.entries.map { listOf(it.speciesId, it.paletteIndex) }, graphics.map { listOf(it.speciesId, it.paletteIndex) })
                ?: result.replaceRange(current.macro(gfx).range, renderGraphics(selected.graphics.copy(identity = gfx, entries = graphics), current.macro(gfx).body, selected.graphics.entries))
        }
        val current = AsmEnemySource(result, symbols, projectRoomIds)
        val old = current.macro(stateName(room.roomId, stateId))
        return result.replaceRange(old.range, result.substring(old.range)
            .replace("%${selected.population.identity}()", "%$pop()")
            .replace("%${selected.graphics.identity}()", "%$gfx()"))
    }
    private fun replaceRows(name: String, before: List<List<Int>>, after: List<List<Int>>): String? {
        if (before.size != after.size) return null
        val m = macro(name)
        val changes = mutableListOf<Pair<IntRange, String>>()
        var offset = m.start
        var row = 0
        for (line in m.body.split('\n')) {
            val code = line.substringBefore(';')
            val directive = Regex("^[ \\t]*dw[ \\t]+", RegexOption.IGNORE_CASE).find(code)
            if (directive != null && row < before.size) {
                var local = directive.range.last + 1
                code.substring(local).trimEnd('\r').split(',').forEachIndexed { field, token ->
                    val clean = token.trim()
                    val start = offset + local + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    if (before[row][field] != after[row][field]) changes += (start until start + clean.length) to hex(after[row][field])
                    local += token.length + 1
                }
                row++
            }
            offset += line.length + 1
        }
        return changes.sortedByDescending { it.first.first }.fold(text) { t, (span, value) -> t.replaceRange(span, value) }
    }
    fun tidy(room: RoomEdits, external: Set<String>): String {
        val owned = macros.values.filter { it.name.startsWith(prefix(room.roomId)) }
        val outside = owned.sortedByDescending { it.range.first }.fold(text) { t, m -> t.removeRange(m.range) }
        val live = (room.states.map { stateName(room.roomId, it.id) } + references(outside) + external).toMutableSet()
        val queue = java.util.ArrayDeque(live)
        while (queue.isNotEmpty()) macros[queue.removeFirst()]?.let { m -> references(m.body).forEach { if (live.add(it)) queue.add(it) } }
        return owned.filter { it.name !in live }.sortedByDescending { it.range.first }.fold(text) { t, m -> t.removeRange(m.range) }
    }
    companion object {
        fun prefix(roomId: Int, projectId: String? = null) = AsmRoomSourceContext.prefix("SmeditEnemies", roomId, projectId)
        fun stateName(roomId: Int, id: String, projectId: String? = null) = prefix(roomId, projectId) + "State_" + id.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun renderState(roomId: Int, id: String, population: String, graphics: String, projectId: String? = null) = "macro ${stateName(roomId, id, projectId)}()\n    %$population()\n    %$graphics()\nendmacro\n"
        private fun codeLines(text: String) = text.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.toList()
        private fun hex(value: Int, digits: Int = 4) = "\$${value.toString(16).uppercase().padStart(digits, '0')}"
        private fun notes(body: String) = body.lineSequence().filter { it.trimStart().startsWith("dw ", true) }
            .map { line -> val note = line.substringAfter(';', "").trim()
                if (note.startsWith("Actor ") || note.startsWith("GFX:")) note.substringAfter("|", "").trim() else note }.toList()
        fun renderPopulation(resource: SourceEnemyPopulation, oldBody: String = "", oldEntries: List<RomParser.EnemyEntry> = resource.entries) = buildString {
            val annotations = notes(oldBody)
            val unused = oldEntries.indices.toMutableSet()
            appendLine("macro ${resource.identity}()")
            oldBody.lineSequence().filter { it.trimStart().startsWith(';') }.forEach(::appendLine)
            resource.entries.forEachIndexed { i, e ->
                append("    dw ${e.fields().joinToString(", ") { hex(it) }} ; Actor $i: ${RomParser.enemyName(e.id)}")
                val oldIndex = unused.firstOrNull { oldEntries[it] == e }?.also { unused.remove(it) }
                oldIndex?.let { annotations.getOrNull(it) }?.takeIf { it.isNotBlank() }?.let { append(" | $it") }
                appendLine()
            }
            appendLine("    dw \$FFFF ; End of population")
            appendLine("    db ${hex(resource.killCount, 2)} ; Required kills")
            appendLine("endmacro")
        }
        fun renderGraphics(resource: SourceEnemyGraphics, oldBody: String = "", oldEntries: List<RomParser.EnemyGfxEntry> = resource.entries) = buildString {
            val annotations = notes(oldBody)
            val unused = oldEntries.indices.toMutableSet()
            appendLine("macro ${resource.identity}()")
            oldBody.lineSequence().filter { it.trimStart().startsWith(';') }.forEach(::appendLine)
            resource.entries.forEach { e ->
                append("    dw ${hex(e.speciesId)}, ${hex(e.paletteIndex)} ; GFX: ${RomParser.enemyName(e.speciesId)} · palette ${8 + (e.paletteIndex and 255)}")
                val oldIndex = unused.firstOrNull { oldEntries[it] == e }?.also { unused.remove(it) }
                oldIndex?.let { annotations.getOrNull(it) }?.takeIf { it.isNotBlank() }?.let { append(" | $it") }
                appendLine()
            }
            appendLine("    dw \$FFFF ; End of GFX list")
            appendLine("endmacro")
        }
        fun references(text: String) = Regex("%?(SmeditEnemies_[A-Za-z0-9_]+)").findAll(text.lineSequence().joinToString("\n") { it.substringBefore(';') })
            .map { it.groupValues[1] }.toSet()
    }
}
