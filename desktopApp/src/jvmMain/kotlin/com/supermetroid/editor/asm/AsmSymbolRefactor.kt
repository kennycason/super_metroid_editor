package com.supermetroid.editor.asm

internal data class AsmSymbolRenameOccurrence(
    val fileId: String,
    val lineIndex: Int,
    val column: Int,
    val before: String,
    val after: String,
    val declaration: Boolean,
)

/** A complete immutable preview. Every replacement retains its expected source
 * so applying it through [AsmWorkspaceState.replaceProjectSources] is one guarded
 * multi-file history transaction. */
internal data class AsmSymbolRenamePlan(
    val targetFileId: String,
    val targetLineIndex: Int,
    val oldName: String,
    val newName: String,
    val occurrences: List<AsmSymbolRenameOccurrence>,
    val replacements: Map<String, Pair<String, String>>,
)

internal object AsmSymbolRefactor {
    private val globalIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val namespace = Regex("^\\s*namespace\\b", RegexOption.IGNORE_CASE)

    fun planRename(
        index: AsmReferenceIndex,
        sourceTexts: Map<String, String>,
        fileId: String,
        lineIndex: Int,
        token: String,
        requestedName: String,
    ): AsmSymbolRenamePlan {
        val oldName = asmCanonicalSymbolToken(token)
        val newName = requestedName.trim()
        require(globalIdentifier.matches(oldName) && globalIdentifier.matches(newName) && oldName != newName) {
            "Rename requires two different ordinary global ASM identifiers. Local labels, defines, and macros are intentionally excluded."
        }
        require(index.files.all { source ->
            sourceTexts[source.id]?.reader()?.readLines() == source.lines
        }) { "Save every source draft and wait for ASM indexing before renaming a symbol." }
        require(index.macros.none { it.name.equals(oldName, ignoreCase = true) || it.name.equals(newName, ignoreCase = true) }) {
            "The requested name is also used by a macro; macro tokens are never rewritten by symbol rename."
        }
        val boundParameters = sourceTexts.values.asSequence().flatMap { it.lineSequence() }
            .flatMap { asmBoundParameterNames(it).asSequence() }.toSet()
        require(oldName !in boundParameters && newName !in boundParameters) {
            "The requested name is bound as a macro/function parameter; parameter tokens are never rewritten by symbol rename."
        }

        val root = index.definition(fileId, lineIndex, oldName)
            ?: index.resolveUniqueLabel(fileId, lineIndex, oldName)
            ?: error("$oldName does not resolve to one editable global symbol.")
        require(root.localScope == null && root.name == oldName && !root.name.startsWith('!')) {
            "Only an ordinary global label can be renamed safely."
        }
        val rootSource = checkNotNull(index.file(root.fileId))
        val rootLine = rootSource.lines[root.lineIndex]
        val declaration = Regex("^[ \\t]*(${Regex.escape(oldName)}):").find(asmExecutableCode(rootLine))
            ?.groups?.get(1) ?: error("$oldName is not an ordinary global label declaration.")

        val symbolNames = linkedMapOf(oldName to newName)
        index.labels.filter { label ->
            label.fileId == root.fileId && label.localScope == null && label.name.startsWith("${oldName}_") &&
                rootSource.scopePathByLine.getOrNull(label.lineIndex)?.firstOrNull() == oldName
        }.forEach { derived ->
            symbolNames[derived.name] = newName + derived.name.removePrefix(oldName)
        }
        require(symbolNames.values.distinct().size == symbolNames.size) { "Renamed symbol aliases would collide." }
        symbolNames.forEach { (before, after) ->
            val collisions = index.labels.filter { it.localScope == null && it.name == after }
            require(collisions.isEmpty() || before in symbolNames && collisions.all { collision ->
                symbolNames.containsKey(collision.name)
            }) { "Rename would collide with existing symbol $after." }
        }

        data class Edit(val start: Int, val end: Int, val replacement: String, val occurrence: AsmSymbolRenameOccurrence)
        val editsByFile = linkedMapOf<String, MutableList<Edit>>()
        val lineStarts = sourceTexts.mapValues { (_, text) ->
            buildList { add(0); text.forEachIndexed { offset, character -> if (character == '\n') add(offset + 1) } }
        }
        fun addEdit(file: String, line: Int, column: Int, before: String, after: String, isDeclaration: Boolean) {
            val start = checkNotNull(lineStarts[file]?.getOrNull(line)) + column
            editsByFile.getOrPut(file, ::mutableListOf) += Edit(
                start,
                start + before.length,
                after,
                AsmSymbolRenameOccurrence(file, line, column, before, after, isDeclaration),
            )
        }
        addEdit(root.fileId, root.lineIndex, declaration.range.first, oldName, newName, true)

        symbolNames.forEach { (before, after) ->
            val declarationLabel = index.labels.filter { it.localScope == null && it.name == before }.singleOrNull()
                ?: if (before == oldName) root else error("Derived symbol $before is ambiguous; rename was refused.")
            index.usagesFor(declarationLabel).filter { it.token == before }.forEach { usage ->
                require(index.resolveUniqueLabel(usage.fileId, usage.lineIndex, usage.token)?.symbolId == declarationLabel.symbolId) {
                    "${usage.fileId}:${usage.lineIndex + 1} has an ambiguous reference to $before."
                }
                addEdit(usage.fileId, usage.lineIndex, usage.column, before, after, false)
            }
        }

        // A permissive navigation choice must never become a partial refactor.
        sourceTexts.forEach { (sourceFileId, text) ->
            var lineOffset = 0
            text.splitToSequence('\n').forEachIndexed { sourceLineIndex, line ->
                asmIdentifierTokens(asmExecutableCode(line)).forEach { (range, identifier) ->
                    if (identifier in symbolNames) require(editsByFile[sourceFileId].orEmpty().any { edit ->
                        edit.start == lineOffset + range.first && edit.end == lineOffset + range.last + 1
                    }) { "$sourceFileId:${sourceLineIndex + 1} contains an unsupported or ambiguous reference to $identifier." }
                }
                lineOffset += line.length + 1
            }
        }

        require(editsByFile.keys.none { editedFileId ->
            sourceTexts.getValue(editedFileId).lineSequence().any {
                namespace.containsMatchIn(asmExecutableCode(it))
            }
        }) { "A touched file uses namespaces and requires an explicit scoped refactor; no source was changed." }

        val replacements = editsByFile.mapValues { (sourceFileId, edits) ->
            val before = sourceTexts.getValue(sourceFileId)
            val ordered = edits.distinctBy { it.start to it.end }.sortedBy { it.start }
            require(ordered.zipWithNext().all { (left, right) -> left.end <= right.start }) {
                "Rename produced overlapping edits in $sourceFileId."
            }
            val after = buildString {
                var cursor = 0
                ordered.forEach { edit ->
                    append(before, cursor, edit.start)
                    append(edit.replacement)
                    cursor = edit.end
                }
                append(before, cursor, before.length)
            }
            before to after
        }
        require(replacements.isNotEmpty()) { "Rename found no editable occurrences." }
        val occurrences = editsByFile.values.flatten().distinctBy { it.start to it.occurrence }
            .map { it.occurrence }
            .sortedWith(compareBy(AsmSymbolRenameOccurrence::fileId, AsmSymbolRenameOccurrence::lineIndex,
                AsmSymbolRenameOccurrence::column))
        return AsmSymbolRenamePlan(root.fileId, root.lineIndex, oldName, newName, occurrences, replacements)
    }
}
