package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmSelectorConversionPreview(
    val roomId: Int,
    val sourceText: String,
    val selectors: SourceRoomSelectors,
    val stateIds: List<String>,
)

/** A typed prefix-tree lens. Arguments may name stable DoorDefs, never arbitrary expressions. */
internal class AsmRoomSelectorsSource(val text: String) {
    private data class Definition(val range: IntRange, val bodyStart: Int, val body: String)
    private data class Field(val range: IntRange, val size: Int, val value: Int?, val door: ProjectSourceDoorReference?) {
        val literal: Int get() = requireNotNull(value) { "A DoorDef symbol is only valid as an incoming-door argument." }
    }
    private val definitions by lazy {
        val matches = Regex("(?m)^macro (SmeditSelectors_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").findAll(text).toList()
        require(matches.map { it.groupValues[1] }.distinct().size == matches.size) { "Duplicate selector declarations." }
        matches.associate { it.groupValues[1] to Definition(it.range, it.groups[2]!!.range.first, it.groupValues[2]) }
    }
    private fun definition(name: String) = requireNotNull(definitions[name]) { "Missing selector declaration $name." }
    private fun fields(name: String): List<Field> {
        val d = definition(name)
        var offset = d.bodyStart
        return buildList {
            for (line in d.body.split('\n')) {
                val code = line.substringBefore(';').trimEnd('\r')
                if (code.isNotBlank()) {
                    val directive = requireNotNull(Regex("^[ \\t]*(db|dw)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected literal db/dw fields." }
                    val size = if (directive.groupValues[1].equals("db", true)) 1 else 2
                    var start = directive.range.last + 1
                    for (token in code.substring(start).split(',')) {
                        val clean = token.trim()
                        val first = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        val value = AsmPlmDocument.literal(clean)
                        val door = if (size == 2) ProjectSourceDoorReference.fromEntryLabel(clean) else null
                        require(door != null || value != null && value in 0..if (size == 1) 255 else 65535) {
                            "$name: expected a ${size * 8}-bit literal or an incoming-door DoorDef entry label, got '$clean'."
                        }
                        add(Field(offset + first until offset + first + clean.length, size, value, door))
                        start += token.length + 1
                    }
                }
                offset += line.length + 1
            }
        }
    }
    fun resource(room: RoomEdits): SourceRoomSelectors = readResource(room, recognizePredicates = false)

    private fun isPredicate(name: String) = definition(name).body.lineSequence().any { it.trim() == AsmSelectorPredicateRecognizer.MARKER }
    private fun isAsmPredicate(name: String) = definition(name).body.lineSequence().any { it.trim() == ASM_PREDICATE_MARKER }
    private fun isManagedPredicate(name: String) = definition(name).body.lineSequence().any { it.trim() == MANAGED_PREDICATE_MARKER }

    /** These bodies are assembled only by the managed placement gateway, never as table data. */
    fun managedPredicates(): List<AsmManagedRoomPredicate> = definitions.keys.filter(::isManagedPredicate).map { name ->
        AsmManagedRoomPredicate(name).also { it.validateBody(definition(name).body) }
    }.sortedBy { it.label }

    /** A proposal, NOT a build input. Invalid typed siblings/order still reject the whole proposal. */
    fun conversionPreview(room: RoomEdits): AsmSelectorConversionPreview? {
        val ids = room.states.filter { isPredicate(conditionName(room.roomId, it.id)) }.map { it.id }
        return if (ids.isEmpty()) null else AsmSelectorConversionPreview(room.roomId, text, readResource(room, recognizePredicates = true), ids)
    }

    fun convertPredicates(room: RoomEdits): String {
        val preview = requireNotNull(conversionPreview(room)) { "No recognized predicates to convert." }
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        return preview.stateIds.map { id ->
            val d = definition(conditionName(room.roomId, id))
            // An explicit conversion retires the original code to comments. Keeping
            // every line makes this auditable even after the undo history is closed.
            val notes = d.body.lineSequence().filter { it.isNotBlank() }
                .joinToString("") { "    ; Original predicate: ${it.trimEnd('\r')}$newline" }
            (d.bodyStart until d.bodyStart + d.body.length) to
                (notes + renderCondition(preview.selectors.conditions.getValue(id)).replace("\n", newline))
        }.sortedByDescending { it.first.first }.fold(text) { result, (range, replacement) -> result.replaceRange(range, replacement) }
            .also { require(AsmRoomSelectorsSource(it).resource(room) == preview.selectors) { "Converted predicates do not match the reviewed proposal." } }
    }

    private fun readResource(room: RoomEdits, recognizePredicates: Boolean): SourceRoomSelectors {
        require(room.asmStateSelectorsSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported selector source ownership." }
        require(room.states.all { !it.conditionChanged && it.condition == baseline(it) }) { "Selectors have both source and GUI condition overrides." }
        val order = definition(orderName(room.roomId)).body.lineSequence().mapNotNull { line ->
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) null else requireNotNull(Regex("^%${prefix(room.roomId)}Condition_([0-9a-f]+)\\(\\)$").matchEntire(code)) { "Selector order must contain only state-condition macro references." }
                .groupValues[1].let(::decodeId)
        }.toList()
        val routines = linkedMapOf<String, SourceRoomSelectorRoutine>()
        val conditions = room.states.mapNotNull { state ->
            val name = conditionName(room.roomId, state.id)
            if (isManagedPredicate(name)) {
                val managed = AsmManagedRoomPredicate(name)
                managed.validateBody(definition(name).body)
                routines[state.id] = SourceRoomSelectorRoutine(managed.label)
                return@mapNotNull null
            }
            if (isAsmPredicate(name)) {
                require(!isPredicate(name)) { "$name: choose either executable authority or conversion, not both." }
                val body = definition(name).body
                require(body.lineSequence().count { it.trim() == ASM_PREDICATE_MARKER } == 1) { "$name: duplicate executable predicate marker." }
                val code = body.lineSequence().map { it.substringBefore(';').trim() }.filter(String::isNotEmpty).toList()
                val label = code.singleOrNull()?.let { Regex("dw[ \\t]+([A-Za-z_][A-Za-z0-9_]*)", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1) }
                routines[state.id] = SourceRoomSelectorRoutine(requireNotNull(label) { "$name: expected exactly 'dw PredicateLabel' with an explicit executable marker." })
                return@mapNotNull null
            }
            if (isPredicate(name)) {
                require(recognizePredicates) { "$name: handwritten predicate requires review and explicit conversion before building." }
                return@mapNotNull state.id to try {
                    AsmSelectorPredicateRecognizer.recognize(definition(name).body)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("$name: ${error.message}", error)
                } catch (error: IllegalStateException) {
                    throw IllegalArgumentException("$name: ${error.message}", error)
                }
            }
            val f = fields(name)
            var cursor = 0
            var nodes = 0
            fun read(depth: Int): ProjectRoomStateCondition {
                require(depth <= 8 && ++nodes <= 64 && cursor + 4 <= f.size) { "Truncated or oversized condition tree." }
                val record = f.subList(cursor, cursor + 4); cursor += 4
                require(record.map { it.size } == listOf(1, 1, 1, 2)) { "Selector node widths must be db kind, NOT, children; dw argument." }
                val kind = requireNotNull(KINDS.getOrNull(record[0].literal)) { "Unknown selector kind." }
                val not = record[1].literal
                val children = record[2].literal
                require(not in 0..1 && children <= 64) { "Invalid selector NOT/child count." }
                val argument = record[3].value
                val door = record[3].door
                require(door == null || kind == ProjectRoomStateConditionKind.INCOMING_DOOR) { "A DoorDef symbol is only valid as an incoming-door argument." }
                val typed = projectRoomStateCondition(kind)
                require(typed.argumentKind !in setOf(ProjectRoomStateConditionArgumentKind.NONE, ProjectRoomStateConditionArgumentKind.CHILDREN) || argument == 0) { "Argument must be zero for ${kind.name}." }
                return projectRoomStateCondition(kind, argument.takeUnless { typed.argumentKind in setOf(ProjectRoomStateConditionArgumentKind.NONE, ProjectRoomStateConditionArgumentKind.CHILDREN) },
                    not == 1, List(children) { read(depth + 1) }, door)
            }
            val condition = read(0)
            require(cursor == f.size) { "Trailing selector fields." }
            state.id to condition
        }.toMap()
        val result = SourceRoomSelectors(order, conditions, routines)
        require(conditions.keys + routines.keys == room.states.map { it.id }.toSet())
        require(result.order.last() == room.states.single { baseline(it).kind == ProjectRoomStateConditionKind.DEFAULT }.id) { "The mandatory default state's identity cannot change." }
        return result
    }
    fun editCondition(room: RoomEdits, id: String, condition: ProjectRoomStateCondition): String {
        val current = resource(room)
        require(current.routines.isEmpty()) { "ASM-only selector logic must be edited in source." }
        require(current.conditions.getValue(id).kind != ProjectRoomStateConditionKind.DEFAULT && condition.kind != ProjectRoomStateConditionKind.DEFAULT) { "The mandatory default cannot be changed." }
        SourceRoomSelectors(current.order, current.conditions + (id to condition))
        val name = conditionName(room.roomId, id)
        val replacements = nodeFields(condition)
        val existing = fields(name)
        return if (replacements.map { it.first } == existing.map { it.size }) {
            val literals = existing.mapIndexedNotNull { index, field ->
                val token = replacements[index].second
                if (field.value != null && field.value == AsmPlmDocument.literal(token) ||
                    field.door != null && field.door == ProjectSourceDoorReference.fromEntryLabel(token)) null
                else field.range to token
            }
            // Refresh only our generated kind hint. Handwritten comments and unrelated spelling survive.
            val hints = condition.flattened().mapIndexedNotNull { index, node ->
                val old = KINDS[existing[index * 4].literal]
                if (old == node.kind) null else {
                    val start = existing[index * 4 + 2].range.last + 1
                    val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
                    Regex("; ${old.name}: kind, NOT, child count").find(text.substring(start, end))?.let {
                        (start + it.range.first..start + it.range.last) to "; ${node.kind.name}: kind, NOT, child count"
                    }
                }
            }
            (literals + hints).sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
        } else {
            val d = definition(name)
            val notes = d.body.lines().mapNotNull { it.substringAfter(';', "").trim().takeIf(String::isNotBlank) }.joinToString("") { "    ; Retained note: $it\n" }
            text.replaceRange(d.bodyStart until d.bodyStart + d.body.length, notes + renderCondition(condition))
        }
    }
    fun editOrder(room: RoomEdits, order: List<String>): String {
        val current = resource(room)
        SourceRoomSelectors(order, current.conditions, current.routines)
        require(order.last() == current.order.last()) { "The default branch must remain last." }
        val d = definition(orderName(room.roomId))
        val refs = Regex("%${prefix(room.roomId)}Condition_([0-9a-f]+)\\(\\)").findAll(d.body).toList()
        return refs.mapIndexed { index, match -> (d.bodyStart + match.range.first..d.bodyStart + match.range.last) to "%${conditionName(room.roomId, order[index])}()" }
            .sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
    }
    /** Membership changes are published with the room/resource transaction, never independently. */
    fun changeStates(room: RoomEdits, next: SourceRoomSelectors): String {
        val current = resource(room)
        require(current.routines.isEmpty() && next.routines.isEmpty()) { "State membership changes require typed selectors; preserve custom predicates in ASM." }
        require(next.order.last() == current.order.last()) { "The mandatory default state's identity cannot change." }
        require(next.conditions.filterKeys { it in current.conditions } == current.conditions.filterKeys { it in next.conditions }) {
            "A state membership edit cannot also replace existing conditions."
        }
        val d = definition(orderName(room.roomId))
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val notes = d.body.lineSequence().mapNotNull { line ->
            line.indexOf(';').takeIf { it >= 0 }?.let { "    " + line.substring(it) + newline }
        }.joinToString("")
        var result = text.replaceRange(d.bodyStart until d.bodyStart + d.body.length,
            notes + next.order.joinToString("") { "    %${conditionName(room.roomId, it)}()$newline" })
        for ((id, condition) in next.conditions.filterKeys { it !in current.conditions }) {
            val name = conditionName(room.roomId, id)
            require(name !in definitions) { "Retained selector source already uses state identity $id." }
            result += "$newline" + "macro $name()$newline" + renderCondition(condition).replace("\n", newline) + "endmacro$newline"
        }
        return result
    }
    fun hasState(roomId: Int, stateId: String) = conditionName(roomId, stateId) in definitions
    fun line(roomId: Int, stateId: String? = null) = text.take(definition(if (stateId == null) orderName(roomId) else conditionName(roomId, stateId)).range.first).count { it == '\n' }
    companion object {
        const val ASM_PREDICATE_MARKER = "; smedit:asm-predicate-v1"
        const val MANAGED_PREDICATE_MARKER = "; smedit:managed-predicate-v1"
        // Explicit, versioned wire order: never derive these IDs from enum ordinals.
        val KINDS = "DEFAULT INCOMING_DOOR AREA_MAIN_BOSS_DEAD NEVER EVENT_SET AREA_BOSS_BIT_SET MORPH_BALL_COLLECTED MORPH_BALL_AND_MISSILES POWER_BOMBS_COLLECTED SPEED_BOOSTER_COLLECTED EQUIPMENT_COLLECTED BEAM_COLLECTED MISSILE_CAPACITY_AT_LEAST SUPER_MISSILE_CAPACITY_AT_LEAST POWER_BOMB_CAPACITY_AT_LEAST ENERGY_CAPACITY_AT_LEAST RESERVE_CAPACITY_AT_LEAST ITEM_PICKUP_COLLECTED BOSS_DEFEATED EQUIPMENT_EQUIPPED BEAM_EQUIPPED CURRENT_ENERGY_AT_LEAST CURRENT_MISSILES_AT_LEAST CURRENT_SUPER_MISSILES_AT_LEAST CURRENT_POWER_BOMBS_AT_LEAST CURRENT_RESERVE_ENERGY_AT_LEAST DOOR_BIT_SET CHOZO_BLOCK_DESTROYED ESCAPE_ACTIVE ALL_OF ANY_OF".split(' ').map(ProjectRoomStateConditionKind::valueOf)
        fun baseline(state: RoomStateEdits) = state.sourceCondition ?: projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER)
        fun prefix(roomId: Int) = "SmeditSelectors_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun orderName(roomId: Int) = prefix(roomId) + "Order"
        private fun idHex(id: String) = id.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun decodeId(hex: String): String { require(hex.length % 2 == 0); return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8) }
        fun conditionName(roomId: Int, id: String) = prefix(roomId) + "Condition_" + idHex(id)
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
        private fun argumentToken(c: ProjectRoomStateCondition) = c.sourceDoor?.entryLabel ?: hex(c.argument ?: 0, 4)
        private fun nodeFields(c: ProjectRoomStateCondition): List<Pair<Int, String>> = listOf(
            1 to hex(KINDS.indexOf(c.kind), 2), 1 to hex(if (c.negated) 1 else 0, 2),
            1 to hex(c.children.size, 2), 2 to argumentToken(c)) + c.children.flatMap(::nodeFields)
        private fun renderCondition(c: ProjectRoomStateCondition): String = buildString {
            appendLine("    db ${hex(KINDS.indexOf(c.kind), 2)}, ${hex(if (c.negated) 1 else 0, 2)}, ${hex(c.children.size, 2)} ; ${c.kind}: kind, NOT, child count")
            appendLine("    dw ${argumentToken(c)} ; argument (zero for NONE/compound)")
            c.children.forEach { append(renderCondition(it)) }
        }
        fun render(roomId: Int, name: String, selectors: SourceRoomSelectors): String = buildString {
            require(selectors.routines.isEmpty()) { "Executable predicates must not be rendered as typed GUI data." }
            appendLine("; SMEDIT selectors v1 · ${name.replace('\n', ' ').replace('\r', ' ')} · typed input, not executable placement")
            appendLine("; INCOMING_DOOR arguments may name an active SmeditDoors_ROOM_Door_ID_Entry; its address is resolved at build.")
            appendLine("; Bounded predicate import: mark a conditional macro body with '; smedit:predicate-v1'.")
            appendLine("; Room Info offers review/conversion; marked code cannot build until explicitly converted.")
            appendLine("; Custom executable logic: replace a NON-default condition body with '; smedit:managed-predicate-v1' and instructions.")
            appendLine("; Managed bodies enter with 16-bit A/X/Y; return carry=match via RTS with a balanced stack. Placement/end labels are automatic.")
            KINDS.forEachIndexed { index, kind -> appendLine("; ${hex(index, 2)} = $kind") }
            appendLine("macro ${orderName(roomId)}()")
            selectors.order.forEach { appendLine("    %${conditionName(roomId, it)}()") }
            appendLine("endmacro\n")
            selectors.conditions.forEach { (id, c) -> appendLine("macro ${conditionName(roomId, id)}()"); append(renderCondition(c)); appendLine("endmacro\n") }
        }
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditSelectors_([0-9A-F]{4})_(Order|Condition_([0-9a-f]+))\\(\\)").find(lines[index])?.let {
                    return AsmEditorTarget.Room(it.groupValues[1].toInt(16), stateId = it.groupValues[3].takeIf(String::isNotEmpty)?.let(::decodeId))
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
    }
}
