package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject

internal data class AsmRoomResetPlan(
    val owner: SmEditProject,
    val room: RoomEdits,
    val original: RoomEdits,
    val restored: RoomEdits,
    val replacements: Map<String, Pair<String, String>>,
    val externalReferences: Set<String>,
    val relocation: AsmRoomAreaMovePlan? = null,
)

/** Remove only generated declarations belonging to this room. Custom code, comments and
 * externally referenced helpers survive, including their transitive cross-bank dependencies. */
internal fun resetRoomSource(
    room: RoomEdits,
    sources: Map<String, String>,
    externalTexts: Sequence<String>,
    retainedRoots: Set<String> = emptySet(),
    projectId: String? = null,
): Map<String, String> {
    data class Definition(val file: String, val name: String, val range: IntRange, val body: String)
    val ownedNames = buildList {
        if (room.asmDoorSourceVersion != 0) add(Regex(AsmDoorSource.prefix(room.roomId, projectId) + "(?:Door_[0-9]+|List)"))
        if (room.asmDoorSourceVersion != 0) add(Regex(AsmRoomSourceContext.prefix("SmeditArrival", room.roomId, projectId) + "Door_[0-9]+"))
        if (room.asmPlmSourceVersion != 0) add(Regex(AsmRoomObjectSource.prefix(room.roomId, projectId) + "(?:(?:List|Common|Scroll)_[0-9]+|State_[0-9a-f]+|Save_[0-5]_[0-7])"))
        if (room.asmInitialScrollSourceVersion != 0) add(Regex(AsmInitialScrollSource.prefix(room.roomId, projectId) + "(?:Table_[0-9]+|State_[0-9a-f]+)"))
        if (room.asmLayoutSourceVersion != 0) add(Regex(AsmRoomLayoutSource.prefix(room.roomId, projectId) + "(?:Data|State)_[0-9a-f]+"))
        if (room.asmRoomPropertiesSourceVersion != 0) add(Regex(AsmRoomPropertiesSource.prefix(room.roomId, projectId) + "(?:Header|State_[0-9a-f]+)"))
        if (room.asmStateSelectorsSourceVersion != 0) add(Regex(AsmRoomSelectorsSource.prefix(room.roomId, projectId) + "(?:Order|Condition_[0-9a-f]+)"))
        if (room.asmFxSourceVersion != 0) add(Regex(AsmFxSource.prefix(room.roomId, projectId) + "(?:Table_[0-9]+|State_[0-9a-f]+)"))
        if (room.asmEnemySourceVersion != 0) add(Regex(AsmEnemySource.prefix(room.roomId, projectId) + "(?:(?:Population|Graphics)_[0-9]+|State_[0-9a-f]+)"))
    }
    val definitions = mutableListOf<Definition>()
    val starts = Regex("(?m)^[ \\t]*macro[ \\t]+([A-Za-z0-9_]+)([^\\r\\n]*)(?:\\r?\\n|$)")
    val ends = Regex("(?m)^[ \\t]*endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)")
    for ((file, text) in sources) {
        val allStarts = starts.findAll(text).toList()
        for ((index, start) in allStarts.withIndex()) {
            val name = start.groupValues[1]
            if (ownedNames.none { it.matches(name) }) continue
            require(start.groupValues[2].substringBefore(';').trim() == "()") { "Cannot reset malformed authored macro $name." }
            val end = requireNotNull(ends.find(text, start.range.last + 1)) { "Missing endmacro for $name." }
            val previous = allStarts.getOrNull(index - 1)
            require(previous == null || ends.find(text, previous.range.last + 1)?.range?.first?.let { it < start.range.first } == true) {
                "Authored macro $name is nested inside another definition; repair it before resetting."
            }
            require(allStarts.getOrNull(index + 1)?.range?.first?.let { it < end.range.first } != true) { "Nested authored macro $name cannot be reset safely." }
            definitions += Definition(file, name, start.range.first..end.range.last,
                text.substring(start.range.last + 1, end.range.first))
        }
    }
    require(definitions.map { it.name }.distinct().size == definitions.size) { "Duplicate authored room declarations; repair them before resetting." }
    val byName = definitions.associateBy { it.name }
    val outside = sources.mapValues { (file, text) ->
        definitions.filter { it.file == file }.sortedByDescending { it.range.first }
            .fold(text) { result, definition -> result.removeRange(definition.range) }
    }
    val live = (outside.values.asSequence() + externalTexts).flatMap { resetSourceReferences(it).asSequence() }.toMutableSet()
    live.addAll(retainedRoots)
    val pending = ArrayDeque(live)
    while (pending.isNotEmpty()) {
        val definition = byName[pending.removeFirst()] ?: continue
        for (reference in resetSourceReferences(definition.body)) if (live.add(reference)) pending.addLast(reference)
    }
    return sources.mapValues { (file, text) ->
        definitions.filter { it.file == file && it.name !in live }.sortedByDescending { it.range.first }
            .fold(text) { result, definition ->
                // Preserve notes even when their old data records are retired. Reset never
                // rewrites handwritten source surrounding the generated macro envelopes.
                val newline = if (text.contains("\r\n")) "\r\n" else "\n"
                val notes = text.substring(definition.range).lineSequence().mapNotNull { line ->
                    line.indexOf(';').takeIf { it >= 0 }?.let { line.substring(it) + newline }
                }.joinToString("")
                result.replaceRange(definition.range, notes)
            }
    }
}

internal fun resetSourceReferences(text: String): Set<String> =
    Regex("\\bSmedit(?:Objects|Scrolls|Fx|Enemies|Layout|RoomInfo|Selectors|Doors|Arrival)_[A-Za-z0-9_]+\\b")
        .findAll(text.lineSequence().joinToString("\n") { it.substringBefore(';') })
        .map { if (it.value.startsWith("SmeditArrival_") || it.value.startsWith("SmeditDoors_")) it.value.removeSuffix("_End").removeSuffix("_Entry") else it.value }.toSet()
