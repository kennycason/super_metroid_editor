package com.supermetroid.editor.asm

internal data class AsmSourceText(
    val fileId: String,
    val displayName: String,
    val text: String,
)

internal data class AsmSourceTextMatch(
    val fileId: String,
    val displayName: String,
    val lineIndex: Int,
    val columnIndex: Int,
    val line: String,
)

internal data class AsmSourceReplacementPreview(
    val query: String,
    val replacement: String,
    val caseSensitive: Boolean,
    val matches: List<AsmSourceTextMatch>,
) {
    val occurrenceCount: Int get() = matches.size
    val fileCount: Int get() = matches.mapTo(linkedSetOf(), AsmSourceTextMatch::fileId).size
}

/** Literal source search used by the sidebar and guarded project replace.
 * One result is returned per occurrence, including multiple matches on a line. */
internal fun findAsmSourceText(
    sources: List<AsmSourceText>,
    query: String,
    caseSensitive: Boolean = false,
    limit: Int = Int.MAX_VALUE,
): List<AsmSourceTextMatch> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val matches = mutableListOf<AsmSourceTextMatch>()
    sources.forEach { source ->
        source.text.lineSequence().forEachIndexed { lineIndex, line ->
            var startIndex = 0
            while (startIndex <= line.length - query.length) {
                val column = line.indexOf(query, startIndex, ignoreCase = !caseSensitive)
                if (column < 0) break
                matches += AsmSourceTextMatch(
                    fileId = source.fileId,
                    displayName = source.displayName,
                    lineIndex = lineIndex,
                    columnIndex = column,
                    line = line,
                )
                if (matches.size >= limit) return matches
                startIndex = column + query.length.coerceAtLeast(1)
            }
        }
    }
    return matches
}

internal fun previewAsmSourceReplacement(
    sources: List<AsmSourceText>,
    query: String,
    replacement: String,
    caseSensitive: Boolean,
    limit: Int = Int.MAX_VALUE,
): AsmSourceReplacementPreview = AsmSourceReplacementPreview(
    query = query,
    replacement = replacement,
    caseSensitive = caseSensitive,
    matches = findAsmSourceText(sources, query, caseSensitive, limit).filter { match ->
        match.line.substring(match.columnIndex, match.columnIndex + query.length) != replacement
    },
)

/** Replaces literal text without interpreting '$' or '\\' in the replacement. */
internal fun replaceAsmSourceText(
    text: String,
    query: String,
    replacement: String,
    caseSensitive: Boolean,
): Pair<String, Int> {
    if (query.isEmpty()) return text to 0
    val result = StringBuilder(text.length)
    var cursor = 0
    var count = 0
    while (cursor <= text.length - query.length) {
        val match = text.indexOf(query, cursor, ignoreCase = !caseSensitive)
        if (match < 0) break
        result.append(text, cursor, match)
        result.append(replacement)
        if (text.substring(match, match + query.length) != replacement) count++
        cursor = match + query.length
    }
    if (count == 0) return text to 0
    result.append(text, cursor, text.length)
    return result.toString() to count
}
