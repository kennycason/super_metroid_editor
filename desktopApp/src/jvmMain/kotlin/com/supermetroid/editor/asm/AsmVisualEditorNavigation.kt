package com.supermetroid.editor.asm

private val globalDeclaration = Regex("^([A-Za-z_][A-Za-z0-9_]*):")
private val placementBoundary = Regex("(?i)^[ \\t]*(?:org|base|freecode|freedata|incsrc|macro|endmacro)\\b")
private val macroDeclaration = Regex("(?i)^macro\\s+")

/** Global-label sections and adjacent aliases, bounded by labels and explicit placement directives. */
internal class AsmSourceSections(text: String) {
    data class Section(val names: List<String>, val labelLines: List<Int>, val firstCodeLine: Int?, val endLine: Int) {
        val firstLine get() = labelLines.first()
        operator fun contains(line: Int) = line in firstLine until endLine
    }
    private val lines = text.lines()
    private val offsets = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    private val declarations = lines.mapIndexedNotNull { line, textLine ->
        globalDeclaration.find(textLine)?.let { line to it.groupValues[1] }
    }
    private val sections = buildList {
        var index = 0
        while (index < declarations.size) {
            val first = index
            while (index + 1 < declarations.size &&
                lines.subList(declarations[index].first + 1, declarations[index + 1].first).all { asmCodeBeforeComment(it).isBlank() } &&
                asmCodeBeforeComment(lines[declarations[index].first]).substringAfter(':').isBlank()) index++
            val labels = declarations.subList(first, index + 1)
            val next = declarations.getOrNull(index + 1)?.first ?: lines.size
            val end = (labels.last().first + 1 until next).firstOrNull {
                placementBoundary.containsMatchIn(asmCodeBeforeComment(lines[it]))
            } ?: next
            val code = (labels.last().first until end).firstOrNull {
                val line = asmCodeBeforeComment(lines[it])
                (if (it == labels.last().first) line.substringAfter(':') else line).isNotBlank()
            }
            add(Section(labels.map { it.second }, labels.map { it.first }, code, end))
            index++
        }
    }
    private val byName = sections.flatMap { section -> section.names.map { it to section } }.groupBy({ it.first }, { it.second })
    fun unique(name: String): Section? = byName[name]?.singleOrNull()
    fun at(line: Int): Section? {
        val index = sections.binarySearchBy(line) { it.firstLine }.let { if (it >= 0) it else -it - 2 }
        return sections.getOrNull(index)?.takeIf { line in it }
    }
    fun lineAt(offset: Int): Int = offsets.binarySearch(offset.coerceAtLeast(0)).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
}

/** One resolver for the link bar, caret and pointer context menus. Live mappings outrank stale addresses. */
internal class AsmVisualEditorResolver(
    private val source: AsmSourceFile,
    private val lines: List<String>,
    private val semanticIndex: AsmSemanticIndex?,
    private val indexedAddresses: Map<Int, Int>,
    private val liveLinks: ((Int) -> List<AsmEditorLink>)? = null,
) {
    // readLines omits the terminal empty row; that does not shift any source address.
    private val indexedTextMatches = lines.dropLastWhile(String::isEmpty) == source.lines.dropLastWhile(String::isEmpty)
    private val sections by lazy { AsmSourceSections(lines.joinToString("\n")) }
    private val cache = mutableMapOf<Int, List<AsmEditorLink>>()
    private val macroStartByLine by lazy {
        var start = -1
        IntArray(lines.size) { line ->
            val code = asmCodeBeforeComment(lines[line]).trim()
            if (macroDeclaration.containsMatchIn(code)) start = line
            start.also { if (code.equals("endmacro", ignoreCase = true)) start = -1 }
        }
    }
    private val roomByMacro = mutableMapOf<Int, List<AsmEditorLink>>()
    fun linksAt(line: Int): List<AsmEditorLink> = cache.getOrPut(line) {
        if (line !in lines.indices) emptyList() else resolve(line).distinctBy { it.target }
    }
    private fun resolve(line: Int): List<AsmEditorLink> {
        val macro = macroStartByLine[line]
        if (macro >= 0) roomByMacro.getOrPut(macro) {
            // The declaration carries identity; never scan an entire bank backwards on each caret move.
            val header = listOf(lines[macro].trim())
            listOfNotNull(
                AsmRoomLayoutSource.targetAt(header, 0)?.let { it to "Decoded layout · tile/BTS source" },
                AsmRoomPropertiesSource.targetAt(header, 0)?.let { it to "Room header / state fields" },
                AsmRoomSelectorsSource.targetAt(header, 0)?.let { it to "State selectors / branch order" },
                AsmDoorSource.targetAt(header, 0)?.let { it to "Door connections" },
                AsmDoorArrivalsSource.targetAt(header, 0)?.let { it to "Arrival ASM" },
                AsmProjectRoomLabels.sourceTarget(header, 0)?.let { it to "Project room source" },
            ).map { (target, detail) -> AsmEditorLink("Edit room", detail, target) }
        }.takeIf { it.isNotEmpty() }?.let { return it }
        liveLinks?.invoke(line)?.takeIf { it.isNotEmpty() }?.let { return it }
        // A saved/compiled line number must never be reused against shifted unsaved text.
        if (semanticIndex == null || !indexedTextMatches) return emptyList()
        fun at(at: Int) = indexedAddresses[at]?.let { semanticIndex.linksFor(it) }.orEmpty()
        at(line).takeIf { it.isNotEmpty() }?.let { return it }
        val section = sections.at(line) ?: return emptyList()
        if (section.names.any { sections.unique(it) == null }) return emptyList()
        return (section.labelLines + listOfNotNull(section.firstCodeLine)).flatMap(::at)
    }
}
