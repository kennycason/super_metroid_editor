package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SourcePlmResource
import com.supermetroid.editor.rom.SourceScrollResource
import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.rom.words
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.displaySummary
import com.supermetroid.editor.rom.ensureStateManifest

/** Authored, relocatable PLM macros. The planner owns addresses; these tokens own object values.
 * State wrappers preserve whole-list sharing; every object is readable directly in its list.
 * This bounded grammar is checked before Asar and again against its actual output. */
internal class AsmRoomObjectSource(val text: String, private val symbols: Map<String, Int> = emptyMap(), private val projectRoomIds: Map<Int, String> = emptyMap()) {
    private fun prefix(roomId: Int) = Companion.prefix(roomId, projectRoomIds[roomId])
    private fun stateMacro(roomId: Int, id: String) = Companion.stateMacro(roomId, id, projectRoomIds[roomId])

    data class Macro(val name: String, val start: Int, val bodyStart: Int, val end: Int, val bodyEnd: Int)
    data class ObjectRecord(val record: AsmPlmDocument.Record, val lineRange: IntRange, val commonMacro: String?)
    data class Table(val root: Macro, val resource: Macro, val objects: List<ObjectRecord>, val document: AsmPlmDocument)

    private val macros = Regex("(?m)^macro (SmeditObjects_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n")
        .findAll(text).map { match ->
            val end = Regex("(?m)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").find(text, match.range.last + 1)
                ?: error("${match.groupValues[1]} has no endmacro")
            Macro(match.groupValues[1], match.range.first, match.range.last + 1, end.range.last + 1, end.range.first)
        }.toList().also { all ->
            require(all.map { it.name }.distinct().size == all.size) { "Duplicate authored room-object macro." }
            require(all.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Nested room-object macro definitions are not supported." }
        }.associateBy { it.name }

    fun macro(name: String): Macro = macros[name] ?: error("Missing authored object source $name")
    fun contains(name: String): Boolean = name in macros

    /** A semantic edit may simplify our helpers, but never rebuild handwritten source wholesale.
     * External references (including disabled modules) pin helpers. Names are not renumbered and
     * independent equal-valued entries/lists are never merged. Merely browsing does not call this. */
    fun tidy(room: RoomEdits, externalReferences: Set<String> = emptySet()): String {
        val before = resources(room)
        val pruned = pruneHelpers(room, externalReferences)
        val source = AsmRoomObjectSource(pruned, symbols, projectRoomIds)
        // Expand legacy per-object helpers once per physical list, even when several states use it.
        val uses = room.states.map { source.table(room.roomId, it.id) }
            .distinctBy { it.resource.name }.flatMap { it.objects }
            .filter { it.commonMacro != null }.groupBy { it.commonMacro!! }
        val inlines = mutableListOf<Pair<IntRange, String>>()
        for ((name, consumers) in uses) {
            val helper = source.macro(name)
            for (call in consumers) {
                val notes = source.retainedComments(pruned.substring(helper.start, helper.bodyStart)) +
                    source.retainedComments(pruned.substring(helper.bodyEnd, helper.end)) +
                    source.retainedComments(pruned.substring(call.lineRange))
                inlines += call.lineRange to (notes + pruned.substring(helper.bodyStart, helper.bodyEnd))
            }
        }
        val expanded = source.replaceRanges(inlines)
        // Keep definitions still called by custom ASM; do not duplicate notes already moved to rows.
        val result = AsmRoomObjectSource(expanded, symbols, projectRoomIds).pruneHelpers(room, externalReferences, uses.keys)
        check(AsmRoomObjectSource(result, symbols, projectRoomIds).resources(room) == before) {
            "Room-object cleanup changed values or resource sharing; no source was replaced."
        }
        return result
    }

    private fun pruneHelpers(room: RoomEdits, externalReferences: Set<String>, expandedHelpers: Set<String> = emptySet()): String {
        val activeStates = room.states.map { stateMacro(room.roomId, it.id) }.toSet()
        val helperName = Regex("${prefix(room.roomId)}(?:(?:Common|List|Scroll)_[0-9]+|State_[0-9a-f]+)")
        val helpers = macros.values.filter { helperName.matches(it.name) && it.name !in activeStates }.associateBy { it.name }
        // Everything outside these owned definitions is a root, including other rooms and custom
        // code in this bank. Preserve unsupported helper bodies rather than treating them as trash.
        var outside = text
        for (helper in helpers.values.sortedByDescending { it.start }) {
            outside = outside.removeRange(helper.start, helper.end)
        }
        val live = (referencedHelpers(outside) + externalReferences).toMutableSet()
        helpers.values.filter { helper ->
            val body = lines(helper).map { it.second }
            if (helper.name.contains("_State_")) body.size != 1 || !CALL.matches(body.single())
            else if (helper.name.contains("_Common_")) body.size != 1 || !ENTRY.matches(body.single())
            else if (helper.name.contains("_Scroll_")) runCatching {
                AsmScrollCommandSource.parse(helper.name, text.substring(helper.bodyStart, helper.bodyEnd), 128)
            }.isFailure
            else body.lastOrNull()?.matches(TERMINATOR) != true ||
                body.dropLast(1).any { !ENTRY.matches(it) && !CALL.matches(it) }
        }.forEach { live += it.name }
        val pending = ArrayDeque(live)
        while (pending.isNotEmpty()) {
            val helper = helpers[pending.removeFirst()] ?: continue
            referencedHelpers(text.substring(helper.bodyStart, helper.bodyEnd)).forEach {
                if (live.add(it)) pending.addLast(it)
            }
        }
        val edits = helpers.values.filter { it.name !in live }.map {
            removalRange(it) to if (it.name in expandedHelpers) "" else retainedComments(text.substring(it.start, it.end))
        }
        return replaceRanges(edits)
    }

    private fun replaceRanges(edits: List<Pair<IntRange, String>>): String {
        var result = text
        for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
            result = result.replaceRange(range, replacement)
        }
        return result
    }

    private fun removalRange(macro: Macro): IntRange {
        // Consume at most one adjacent blank separator, so add/remove cycles do not grow whitespace.
        val previous = text.lastIndexOf('\n', macro.start - 2) + 1
        val start = if (text.substring(previous, macro.start).isBlank()) previous else macro.start
        return start until macro.end
    }

    private fun retainedComments(fragment: String): String = buildString {
        val newline = if (fragment.contains("\r\n")) "\r\n" else "\n"
        fragment.lineSequence().forEach { line ->
            var comment = line.substringAfter(';', "").trimStart()
            if (comment.startsWith("PLM: ") || comment.startsWith("End of PLM list")) {
                comment = comment.substringAfter(';', "").trimStart()
            }
            if (comment.isNotEmpty()) append("    ; ").append(comment).append(newline)
        }
    }

    /** Annotate authored rows (including legacy helpers), never the original bank or free-form notes. */
    fun withComments(roomId: Int, describe: (RomParser.PlmEntry) -> String = ::defaultDescription): String {
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val commonPrefix = prefix(roomId) + "Common_"
        val listPrefix = prefix(roomId) + "List_"
        val first = macros.values.firstOrNull { it.name.startsWith(prefix(roomId)) }
        if (first != null) {
            val start = text.lastIndexOf("; SMEDIT authored objects · ", first.start)
            if (start >= 0) {
                val header = text.substring(start, first.start)
                if (header.lineSequence().all { it.isBlank() || it.trimStart().startsWith(';') }) {
                    for (old in LEGACY_VALUES_HEADERS) {
                        val match = Regex("(?m)^${Regex.escape(old)}(?=\\r?$)").find(header) ?: continue
                        replacements += (start + match.range.first..start + match.range.last) to VALUES_HEADER
                    }
                }
            }
        }
        for (macro in macros.values.filter { it.name.startsWith(commonPrefix) || it.name.startsWith(listPrefix) }) {
            for ((range, code) in lines(macro)) {
                val entry = if (ENTRY.matches(code)) parseEntry(range, code).entry else {
                    val common = CALL.matchEntire(code)?.groupValues?.get(1)?.takeIf { it.startsWith(commonPrefix) }
                        ?: continue
                    val definition = lines(macro(common)).singleOrNull() ?: error("$common must contain exactly one PLMPopEntry record.")
                    parseEntry(definition.first, definition.second).entry
                }
                // Names are comments, never source code. Also reserve ';' for handwritten notes.
                val name = describe(entry).replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp};]+"), " ").trim()
                    .ifEmpty { defaultDescription(entry) }
                val line = text.substring(range)
                val content = line.trimEnd('\r', '\n')
                val commentStart = content.indexOf(';').let { if (it < 0) content.length else it }
                val codeAndSpacing = content.take(commentStart)
                val comment = content.substring(commentStart)
                val handwritten = if (comment.startsWith(NAME_COMMENT)) {
                    // Only replace our first comment; preserve any following '; my note' verbatim.
                    val next = comment.indexOf(';', NAME_COMMENT.length)
                    if (next < 0) "" else comment.substring(next)
                } else comment
                val updated = codeAndSpacing + (if (codeAndSpacing.lastOrNull()?.isWhitespace() == true) "" else " ") +
                    NAME_COMMENT + name + (if (handwritten.isEmpty()) "" else " $handwritten") + line.substring(content.length)
                if (updated != line) replacements += range to updated
            }
        }
        var result = text
        for ((range, replacement) in replacements.sortedByDescending { it.first.first }) result = result.replaceRange(range, replacement)
        return result
    }

    private fun lines(macro: Macro): List<Pair<IntRange, String>> {
        val result = mutableListOf<Pair<IntRange, String>>()
        var start = macro.bodyStart
        while (start < macro.bodyEnd) {
            val end = text.indexOf('\n', start).let { if (it < 0) macro.bodyEnd else minOf(it + 1, macro.bodyEnd) }
            val code = text.substring(start, end).substringBefore(';').trim()
            if (code.isNotEmpty()) result += (start until end) to code
            start = end
        }
        return result
    }

    fun table(roomId: Int, stateId: String): Table {
        val root = macro(stateMacro(roomId, stateId))
        val calls = lines(root)
        val resourceName = calls.singleOrNull()?.second?.let { CALL.matchEntire(it)?.groupValues?.get(1) }
            ?: error("${root.name} must call one shared or private object-list macro.")
        require(resourceName.startsWith(prefix(roomId) + "List_")) { "${root.name} references another room or an unsupported resource." }
        val resource = macro(resourceName)
        val body = lines(resource)
        require(body.lastOrNull()?.second?.matches(TERMINATOR) == true) { "$resourceName must end with dw \$0000." }
        val objects = body.dropLast(1).map { (range, code) ->
            val common = CALL.matchEntire(code)?.groupValues?.get(1)
            if (common != null) {
                require(common.startsWith(prefix(roomId) + "Common_")) { "$resourceName: only common-object calls and PLMPopEntry records are supported." }
                val entry = lines(macro(common)).singleOrNull() ?: error("$common must contain exactly one PLMPopEntry record.")
                ObjectRecord(parseEntry(entry.first, entry.second), range, common)
            } else ObjectRecord(parseEntry(range, code), range, null)
        }
        return Table(root, resource, objects, AsmPlmDocument(text, resource.name,
            text.take(resource.start).count { it == '\n' }, objects.map { it.record }))
    }

    private fun parseEntry(line: IntRange, code: String): AsmPlmDocument.Record {
        val match = ENTRY.matchEntire(code) ?: error(
            "$PLM_SOURCE_FILE:${text.take(line.first).count { it == '\n' } + 1}: " +
                "Expected %PLMPopEntry(ID, X, Y, Param); unsupported object source: $code"
        )
        val start = line.first + text.substring(line).indexOf(code)
        val fields = (1..4).map { n ->
            val group = checkNotNull(match.groups[n])
            val token = group.value.trim()
            val offset = start + group.range.first + group.value.indexOf(token)
            offset until offset + token.length
        }
        val scrollReference = AsmScrollCommandSource.reference(text.substring(fields[3]))
        val values = fields.mapIndexed { index, range ->
            val token = text.substring(range)
            if (index == 3 && scrollReference != null) 0
            else AsmPlmDocument.literal(token) ?: symbols[token] ?: error("Unsupported or unknown PLM field '$token'.")
        }
        val entry = RomParser.PlmEntry(values[0], values[1], values[2], values[3])
        AsmPlmDocument.validateEntry(entry)
        require(scrollReference == null || entry.id == 0xB703) { "Named scroll commands require a \$B703 trigger." }
        return AsmPlmDocument.Record(entry, fields, scrollReference)
    }

    fun resources(room: RoomEdits, screenCount: Int = 128): Map<String, SourcePlmResource> {
        require(room.asmPlmSourceVersion == 1) { "Unsupported room-object source format ${room.asmPlmSourceVersion}" }
        require(room.states.isNotEmpty()) { "Authored room objects need stable state identities." }
        require(room.states.map { it.id }.distinct().size == room.states.size) { "Duplicate object-source state identities." }
        require(room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() }) { "Room objects have both GUI deltas and authored source." }
        require(room.customScrollCommands.isEmpty() && room.states.all { it.customScrollCommands.isEmpty() }) {
            "Custom scroll commands cannot be mixed with authored object lists yet."
        }
        val saves = saveResources(room.roomId)
        val activeArea = if (room.asmRoomPropertiesSourceVersion != 0 && saves.isNotEmpty()) AsmRoomPropertiesSource(text, projectRoomIds).area(room.roomId)
            else room.effectiveHeaderChange?.area
        require(saves.none { save -> room.saveStationSpawns.any { it.area == save.area && it.saveIndex == save.slot } }) {
            "Save records have both GUI overrides and authored source."
        }
        return room.states.associate { state ->
            val table = table(room.roomId, state.id)
            val references = table.document.records.mapIndexedNotNull { index, record ->
                record.scrollResource?.let { index to scrollResource(room.roomId, it, screenCount) }
            }.toMap()
            for (entry in table.document.entries.filter { it.id == 0xB76F }) {
                require((entry.param and 255) in 0..7) { "Save PLM indices must be 0–7; the engine masks with \$0007." }
                require(saves.none { it.slot == (entry.param and 255) && it.cleared &&
                    (activeArea == null || it.area == activeArea) }) { "Save PLM references a released spawn record." }
            }
            state.id to SourcePlmResource(table.resource.name, table.document.entries, references, saves)
        }
    }

    /** Save helpers are independent fixed-slot resources, not garbage-collected object-list helpers.
     * A released slot's explicit zero record must survive so the old ROM cannot restore it. */
    fun saveResources(roomId: Int): List<SourceSaveResource> = macros.values.filter { it.name.startsWith(prefix(roomId) + "Save_") }.map { macro ->
        try { AsmSaveStationSource.parse(macro.name, text.substring(macro.bodyStart, macro.bodyEnd), symbols, projectRoomIds) }
        catch (error: Exception) { throw IllegalArgumentException("$PLM_SOURCE_FILE:${text.take(macro.start).count { it == '\n' } + 1}: ${error.message}", error) }
    }

    fun withSave(resource: SourceSaveResource): String {
        require(resource.identity == AsmSaveStationSource.name(resource.ownerRoomId, resource.area, resource.slot, resource.ownerProjectRoomId))
        val rendered = AsmSaveStationSource.render(resource)
        val old = macros[resource.identity]
        return if (old == null) text + "\n" + rendered else {
            val before = saveResources(resource.ownerRoomId).single { it.identity == resource.identity }
            val body = text.substring(old.bodyStart, old.bodyEnd)
            val spans = Regex("(?im)^[ \\t]*dw[ \\t]+([^;\\r\\n]+)").findAll(body).flatMap { line ->
                val fields = line.groups[1]!!
                Regex("[^,]+").findAll(fields.value).map { field ->
                    val token = field.value.trim()
                    val start = old.bodyStart + fields.range.first + field.range.first + field.value.indexOf(token)
                    start until start + token.length
                }
            }.toList()
            require(spans.size == 7) { "Save source changed; no field was replaced." }
            var result = text
            for (index in 6 downTo 0) if (before.words[index] != resource.words[index] || (index == 1 && before.sourceDoor != resource.sourceDoor)) {
                val token = text.substring(spans[index])
                require(AsmPlmDocument.literal(token) != null || (index == 1 && before.sourceDoor != null) ||
                    (index == 0 && before.ownerProjectRoomId?.let { AsmProjectRoomLabels.entry(it) == token } == true)) { "Save field '$token' is symbolic; edit that expression in ASM first." }
                val value = resource.words[index]
                val replacement = when {
                    index == 0 && !resource.cleared && resource.ownerProjectRoomId != null -> AsmProjectRoomLabels.entry(requireNotNull(resource.ownerProjectRoomId))
                    index == 1 && resource.sourceDoor != null -> requireNotNull(resource.sourceDoor).entryLabel
                    index == 1 && before.sourceDoor != null -> "\$${value.toString(16).uppercase().padStart(4, '0')}"
                    token.startsWith('$') -> "\$${value.toString(16).uppercase().padStart(4, '0')}"
                    token.startsWith('%') -> "%${value.toString(2).padStart(16, '0')}"
                    else -> value.toString()
                }
                result = result.replaceRange(spans[index], replacement)
            }
            result
        }
    }

    fun editSaveStation(room: RoomEdits, stateId: String, remove: (RomParser.PlmEntry) -> Boolean,
                        add: RomParser.PlmEntry?, spawn: SourceSaveResource?, externalReferences: Set<String>,
                        allStates: Boolean = false): String {
        var result = edit(room, stateId, allStates, remove, add, externalReferences)
        if (spawn != null) result = AsmRoomObjectSource(result, symbols, projectRoomIds).withSave(spawn)
        val source = AsmRoomObjectSource(result, symbols, projectRoomIds)
        val activeSlots = room.states.flatMap { source.table(room.roomId, it.id).document.entries }
            .filter { it.id == 0xB76F }.map { it.param and 255 }.toSet()
        // Only release slots explicitly removed by this operation, never arbitrary orphan records.
        val removedSlots = (if (allStates) room.states.map { it.id } else listOf(stateId))
            .flatMap { table(room.roomId, it).document.entries }.filter(remove).filter { it.id == 0xB76F }
            .map { it.param and 255 }.filter { it !in activeSlots }
        for (slot in removedSlots) {
            val area = if (room.asmRoomPropertiesSourceVersion != 0) AsmRoomPropertiesSource(result).area(room.roomId) else room.effectiveHeaderChange?.area
            val save = source.saveResources(room.roomId).singleOrNull { it.slot == slot && (area == null || it.area == area) }
            if (save != null) result = AsmRoomObjectSource(result, symbols, projectRoomIds).withSave(save.copy(words = List(7) { 0 }, sourceDoor = null))
        }
        AsmRoomObjectSource(result, symbols, projectRoomIds).resources(room)
        return result
    }

    /** Change save indices in place across every state, preserving list sharing, row order and notes. */
    fun remapSaveParameters(room: RoomEdits, replacements: Map<RomParser.PlmEntry, Int>, externalReferences: Set<String>): String {
        val source = AsmRoomObjectSource(tidy(room, externalReferences), symbols, projectRoomIds)
        val edits = linkedMapOf<IntRange, String>()
        for (table in room.states.map { source.table(room.roomId, it.id) }.distinctBy { it.resource.name }) {
            for (record in table.document.records.filter { it.entry.id == 0xB76F }) {
                val param = replacements[record.entry] ?: continue
                if (param == record.entry.param) continue
                require(param in 0..65535 && (param and 255) in 0..7) { "Invalid destination save index." }
                val field = record.fields[3]
                require(AsmPlmDocument.literal(source.text.substring(field)) != null) {
                    "A save PLM parameter is symbolic; update that binding in ASM before moving its room."
                }
                edits[field] = "\$${param.toString(16).uppercase().padStart(4, '0')}"
            }
        }
        return source.replaceRanges(edits.toList())
    }

    fun scrollResource(roomId: Int, name: String, screenCount: Int = 128): SourceScrollResource {
        require(name.startsWith(prefix(roomId) + "Scroll_")) { "Scroll references cannot cross room ownership." }
        val macro = macro(name)
        return try {
            AsmScrollCommandSource.parse(name, text.substring(macro.bodyStart, macro.bodyEnd), screenCount)
        } catch (problem: IllegalArgumentException) {
            throw IllegalArgumentException("$PLM_SOURCE_FILE:${text.take(macro.start).count { it == '\n' } + 1}: ${problem.message}", problem)
        }
    }

    /** Repoint all room lists together; original streams and externally referenced helpers stay intact. */
    fun resizeScrolls(room: RoomEdits, oldWidth: Int, oldHeight: Int, width: Int, height: Int,
                      originalCommands: (Int) -> List<ScrollCommand>): String {
        resources(room, oldWidth * oldHeight)
        val names = mutableMapOf<String, String>()
        val additions = StringBuilder()
        val edits = mutableListOf<Pair<IntRange, String>>()
        var ordinal = 1
        for (table in room.states.map { table(room.roomId, it.id) }.distinctBy { it.resource.name }) {
            for (record in table.document.records.filter { it.entry.id == 0xB703 }) {
                val identity = record.scrollResource ?: "ROM:${record.entry.param}"
                val commands = record.scrollResource?.let { scrollResource(room.roomId, it, oldWidth * oldHeight).commands }
                    ?: originalCommands(record.entry.param)
                val remapped = resizeScrollCommands(commands, oldWidth, width, height)
                if (record.scrollResource != null && commands == remapped) continue
                val name = names.getOrPut(identity) {
                    while (contains(prefix(room.roomId) + "Scroll_$ordinal")) ordinal++
                    (prefix(room.roomId) + "Scroll_${ordinal++}").also {
                        additions.appendLine(AsmScrollCommandSource.render(it, remapped, width, height))
                    }
                }
                edits += record.fields[3] to "$name&\$FFFF"
            }
        }
        val updated = replaceRanges(edits) + if (additions.isEmpty()) "" else "\n$additions"
        AsmRoomObjectSource(updated, symbols, projectRoomIds).resources(room, width * height)
        return updated
    }

    /** One source transaction: allocate a named resource and repoint only this state's trigger.
     * Shared command/list resources are detached rather than changed behind another consumer's back. */
    fun editScroll(room: RoomEdits, stateId: String, x: Int, y: Int, commands: List<ScrollCommand>, width: Int, height: Int,
                   externalReferences: Set<String> = emptySet(), allStates: Boolean = false): String {
        resources(room, width * height)
        require(table(room.roomId, stateId).document.entries.count { it.id == 0xB703 && it.x == x && it.y == y } <= 1) {
            "Multiple scroll triggers occupy this tile; resolve them in ASM before visual editing."
        }
        val name = uniqueName(prefix(room.roomId) + "Scroll_")
        val staged = text + "\n" + AsmScrollCommandSource.render(name, commands, width, height)
        val result = AsmRoomObjectSource(staged, symbols, projectRoomIds).edit(room, stateId, allStates,
            { it.id == 0xB703 && it.x == x && it.y == y }, RomParser.PlmEntry(0xB703, x, y, 0), externalReferences, name)
        AsmRoomObjectSource(result, symbols, projectRoomIds).resources(room, width * height)
        return result
    }

    /** Refresh only our generated annotation, retaining handwritten notes and scalar spelling. */
    fun withScrollComments(room: RoomEdits, width: Int, height: Int): String {
        val names = resources(room, width * height).values.flatMap { it.scrollReferences.values }.map { it.identity }.toSet()
        val edits = mutableListOf<Pair<IntRange, String>>()
        for (name in names) {
            for ((range, code) in lines(macro(name))) {
                val tokens = code.removePrefix("db ").split(',').map { AsmPlmDocument.literal(it.trim()) }
                if (tokens.size != 2 || tokens.any { it == null }) continue
                val line = text.substring(range)
                val content = line.trimEnd('\r', '\n')
                val at = content.indexOf(';').let { if (it < 0) content.length else it }
                val old = content.substring(at)
                val note = if (old.startsWith("; Screen (")) old.substringAfter(';', "").substringAfter(';', "").trim()
                    else old.removePrefix(";").trim()
                val label = RomParser.formatScrollCommand(tokens[0]!!, tokens[1]!!, width)
                val updated = content.take(at).trimEnd() + " ; $label" + (if (note.isEmpty()) "" else " ; $note") + line.substring(content.length)
                if (updated != line) edits += range to updated
            }
        }
        return replaceRanges(edits)
    }

    /** Changing one state first forks its list; All states writes direct rows to each current list. */
    fun edit(room: RoomEdits, stateId: String, allStates: Boolean, remove: (RomParser.PlmEntry) -> Boolean,
             add: RomParser.PlmEntry?, externalReferences: Set<String> = emptySet(), addScrollReference: String? = null,
             companions: List<RomParser.PlmEntry> = emptyList(), stateIds: Set<String>? = null): String {
        add?.let(AsmPlmDocument::validateEntry)
        companions.forEach(AsmPlmDocument::validateEntry)
        val states = stateIds?.toList() ?: if (allStates) room.states.map { it.id } else listOf(stateId)
        require(states.isNotEmpty() && states.all { id -> room.states.any { it.id == id } }) { "Unknown object source states." }
        var result = text
        val edited = mutableSetOf<String>()
        for (state in states) {
            var source = AsmRoomObjectSource(result, symbols, projectRoomIds)
            var table = source.table(room.roomId, state)
            if (room.states.any { it.id !in states && source.table(room.roomId, it.id).resource.name == table.resource.name }) {
                val name = source.uniqueName(prefix(room.roomId) + "List_")
                val body = result.substring(table.resource.bodyStart, table.resource.bodyEnd)
                val wrappers = states.map { source.table(room.roomId, it) }.filter { it.resource.name == table.resource.name }.map { it.root }
                result = wrappers.sortedByDescending { it.bodyStart }.fold(result) { current, wrapper ->
                    current.replaceRange(wrapper.bodyStart, wrapper.bodyEnd,
                        current.substring(wrapper.bodyStart, wrapper.bodyEnd).replace("%${table.resource.name}()", "%$name()"))
                } + "\nmacro $name()\n${body}endmacro\n"
                source = AsmRoomObjectSource(result, symbols, projectRoomIds)
                table = source.table(room.roomId, state)
            }
            if (!edited.add(table.resource.name)) continue
            val replacements = table.objects.filter { remove(it.record.entry) }.map { it.lineRange to "" }.toMutableList()
            if (add != null) {
                val terminator = source.lines(table.resource).last().first.first
                replacements += (terminator until terminator) to
                    (companions.map { "    ${renderEntry(it)}\n" }.joinToString("") + "    ${renderEntry(add, addScrollReference)}\n")
            }
            for ((range, replacement) in replacements.sortedByDescending { it.first.first }) result = result.replaceRange(range, replacement)
        }
        return AsmRoomObjectSource(result, symbols, projectRoomIds).tidy(room, externalReferences)
    }

    fun cloneState(roomId: Int, template: String, stateId: String): String {
        require(!contains(stateMacro(roomId, stateId))) { "Source already defines state $stateId." }
        val table = table(roomId, template)
        return text + "\n" + renderMacro(stateMacro(roomId, stateId), listOf("%${table.resource.name}()"))
    }

    private fun uniqueName(stem: String): String = generateSequence(1) { it + 1 }.map { stem + it }.first { it !in macros }

    companion object {
        private const val VALUES_HEADER = "; Values live here. Each list contains its PLMPopEntry rows; state wrappers select lists."
        private val LEGACY_VALUES_HEADERS = listOf(
            "; Values live here. State wrappers select lists; Common helpers share objects across states.",
            "; Values live here. State wrappers share lists; Common macros are All states objects.",
            "; Values live here. State wrappers share lists; Common macros are All states items.",
        )
        private const val NAME_COMMENT = "; PLM: "
        private val HELPER_REFERENCE = Regex("\\bSmeditObjects_[A-Za-z0-9_]+\\b")
        // Deliberately conservative: mentions inside strings/comments also protect custom references.
        fun referencedHelpers(text: String): Set<String> = if ("SmeditObjects_" !in text) emptySet()
            else HELPER_REFERENCE.findAll(text).map { it.value }.toSet()
        fun defaultDescription(entry: RomParser.PlmEntry): String = RomParser.plmDisplayName(entry.id, entry.param)
        private val ENTRY = Regex("%PLMPopEntry\\(\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+)\\s*\\)")
        private val CALL = Regex("%(SmeditObjects_[A-Za-z0-9_]+)\\(\\)")
        private val TERMINATOR = Regex("dw\\s+(\\$0+|0+|%0+)", RegexOption.IGNORE_CASE)
        fun prefix(roomId: Int, projectId: String? = null): String = AsmRoomSourceContext.prefix("SmeditObjects", roomId, projectId)
        // Encode, do not sanitize: distinct state IDs must never become the same assembler identifier.
        fun stateMacro(roomId: Int, stateId: String, projectId: String? = null): String = prefix(roomId, projectId) + "State_" + stateId.toByteArray(Charsets.UTF_8)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun renderEntry(entry: RomParser.PlmEntry, scrollReference: String? = null): String = "%PLMPopEntry(" +
            listOf(entry.id to 4, entry.x to 2, entry.y to 2, entry.param to 4)
                .mapIndexed { index, (value, width) ->
                    if (index == 3 && scrollReference != null) "$scrollReference&\$FFFF"
                    else "$" + value.toString(16).uppercase().padStart(width, '0')
                }.joinToString(", ") + ")"
        private fun renderMacro(name: String, lines: List<String>): String =
            "macro $name()\n" + lines.joinToString("\n", postfix = "\n") { "    $it" } + "endmacro\n"

        data class Migration(val text: String, val states: List<RoomStateEdits>, val expected: Map<String, List<RomParser.PlmEntry>>)

        fun migrate(text: String, room: RoomEdits, parser: RomParser, name: String,
                    symbols: Map<String, Int>, isItem: (Int) -> Boolean = { RomParser.isItemPlm(it) },
                    describe: (RomParser.PlmEntry) -> String = ::defaultDescription,
                    externalReferences: Set<String> = emptySet(), projectRoomIds: Map<Int, String> = emptyMap(),
                    projectScrollCommands: Map<Int, List<ScrollCommand>> = emptyMap()): Migration {
            require(room.asmPlmSourceVersion == 0) { "Room already uses authored objects." }
            require(!text.contains("macro ${prefix(room.roomId, projectRoomIds[room.roomId])}")) { "Existing authored object declarations need review; none were overwritten." }
            val states = room.copy(states = room.states.toMutableList()).ensureStateManifest(parser).toList()
            require(states.none { it.resourcesChanged }) { "Changed object resource links need review before migration." }
            fun apply(base: List<RomParser.PlmEntry>, changes: List<PlmChange>): List<RomParser.PlmEntry> {
                if (changes.isEmpty()) return base
                val result = base.toMutableList()
                changes.forEach { change -> when (change.action) {
                    "add" -> result += RomParser.PlmEntry(change.plmId, change.x, change.y, change.param)
                    "remove" -> result.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                    else -> error("Unsupported PLM edit '${change.action}'.")
                } }
                val seen = mutableSetOf<Pair<Int, Int>>()
                return result.asReversed().filter { !isItem(it.id) || seen.add(it.x to it.y) }.asReversed()
            }
            val runtime = parser.parseRoomStatesWithData(room.roomId)
            val expected = linkedMapOf<String, List<RomParser.PlmEntry>>()
            val resources = linkedMapOf<Pair<Int, List<String>>, String>()
            val scrollResources = linkedMapOf<Pair<String, List<ScrollCommand>>, String>()
            val header = requireNotNull(parser.readRoomHeader(room.roomId))
            val width = room.effectiveHeaderChange?.width ?: header.width
            val height = room.effectiveHeaderChange?.height ?: header.height
            val expectedReferences = linkedMapOf<String, Map<Int, SourceScrollResource>>()
            val body = StringBuilder("\n; SMEDIT authored objects · ${name.replace('\n', ' ').replace('\r', ' ')}\n")
            body.appendLine(VALUES_HEADER)
            body.appendLine("; Placement addresses and state pointers are supplied by the checked build planner.")
            body.appendLine("; PLMPopEntry(type, tile X, tile Y, parameter): zero-based tiles; item parameter = pickup flag ID.")
            for (spawn in room.saveStationSpawns) {
                val base = requireNotNull(parser.readSaveEntry(spawn.area, spawn.saveIndex)) { "Save override has no base load-station record." }
                val values = if (spawn.clearSlot) List(7) { 0 } else listOf(spawn.roomId, spawn.doorPtr, base.doorBts,
                    spawn.scrollX, spawn.scrollY, spawn.samusY, spawn.samusX)
                body.appendLine(AsmSaveStationSource.render(SourceSaveResource(AsmSaveStationSource.name(room.roomId, spawn.area, spawn.saveIndex, projectRoomIds[room.roomId]),
                    room.roomId, spawn.area, spawn.saveIndex, values, ownerProjectRoomId = projectRoomIds[room.roomId])))
            }
            for (state in states) {
                val base = runtime.getOrNull(state.baseSourceStateIndex() ?: -1) ?: error("${state.id} has no supported source state.")
                val baseline = parser.parsePlmSet(base.plmSetPtr)
                val seedStreams = projectScrollCommands.entries.mapNotNull { (index, commands) ->
                    baseline.getOrNull(index)?.let { it.param to (index to commands) }
                }.toMap()
                val entries = apply(apply(baseline, room.plmChanges), state.plmChanges)
                entries.forEach(AsmPlmDocument::validateEntry)
                val references = entries.mapIndexedNotNull { index, entry ->
                    if (entry.id != 0xB703) return@mapIndexedNotNull null
                    val legacyId = "cmd_${entry.param and 255}"
                    val local = if ((entry.param and 0xFF00) == 0xCC00) state.customScrollCommands[legacyId] else null
                    val common = if ((entry.param and 0xFF00) == 0xCC00) room.customScrollCommands[legacyId] else null
                    val seed = seedStreams[entry.param]
                    val commands = local ?: common ?: seed?.second
                    if (commands == null) return@mapIndexedNotNull null // A verified native stream remains numeric until explicitly detached.
                    val id = if (local != null || common != null) legacyId else "seed_${seed!!.first}"
                    val owner = if (local != null) state.id else "room"
                    val scrollName = scrollResources.getOrPut(("$owner:$id" to commands.toList())) {
                        (prefix(room.roomId, projectRoomIds[room.roomId]) + "Scroll_" + (scrollResources.size + 1)).also {
                            body.appendLine(AsmScrollCommandSource.render(it, commands, width, height))
                        }
                    }
                    index to SourceScrollResource(scrollName, commands.toList())
                }.toMap()
                expectedReferences[state.id] = references
                expected[state.id] = entries.mapIndexed { index, entry -> if (index in references) entry.copy(param = 0) else entry }
                val lines = entries.mapIndexed { index, entry -> renderEntry(entry, references[index]?.identity) }
                val key = base.plmSetPtr to lines
                val resource = resources.getOrPut(key) {
                    (prefix(room.roomId, projectRoomIds[room.roomId]) + "List_" + (resources.size + 1)).also { macro ->
                        body.appendLine(renderMacro(macro, lines + "dw \$0000 ; End of PLM list"))
                    }
                }
                body.appendLine("; ${state.id} · ${state.condition.displaySummary(parser.readRoomHeader(room.roomId)?.area ?: 0)}")
                body.appendLine(renderMacro(stateMacro(room.roomId, state.id, projectRoomIds[room.roomId]), listOf("%$resource()")))
            }
            val authoredRoom = room.copy(asmPlmSourceVersion = 1, plmChanges = mutableListOf(), customScrollCommands = linkedMapOf(), saveStationSpawns = mutableListOf(),
                states = states.map { it.copy(plmChanges = mutableListOf(), customScrollCommands = linkedMapOf()) }.toMutableList())
            val tidy = AsmRoomObjectSource(text + "\n" + body, symbols, projectRoomIds).tidy(authoredRoom, externalReferences)
            val updated = AsmRoomObjectSource(tidy, symbols, projectRoomIds).withComments(room.roomId, describe)
            val decoded = AsmRoomObjectSource(updated, symbols, projectRoomIds)
            require(states.all { decoded.table(room.roomId, it.id).document.entries == expected[it.id] }) { "Room-object migration changed values." }
            require(decoded.resources(authoredRoom, width * height).all { (state, resource) -> resource.scrollReferences == expectedReferences[state] }) {
                "Scroll-command migration changed values or resource sharing."
            }
            return Migration(updated, states, expected)
        }
    }
}
