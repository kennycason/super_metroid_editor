package com.supermetroid.editor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import com.supermetroid.editor.asm.AsmDocumentMatch
import com.supermetroid.editor.asm.AsmSourceTextChange
import com.supermetroid.editor.asm.asmCodeBeforeComment
import com.supermetroid.editor.asm.asmOffsetInsideString
import com.supermetroid.editor.asm.findAsmDocumentText

/**
 * An editable view of source, not a rewrite of it. Only a six-digit address at
 * the beginning of a real comment is metadata. Strings, ordinary comments and
 * Asar's executable ;@ lines are never interpreted as address annotations.
 * Whitespace is intentionally retained: hiding metadata must not reformat code.
 */
internal class AsmSourcePresentation(val source: String) {
    private val lines = source.split('\n').map(::PresentedLine)
    private val kept = mutableListOf<IntRange>()
    private val sourceToDisplay = IntArray(source.length + 1)
    private val displayToSource: IntArray
    val text: String
    val originalAddresses: List<String?> = lines.map { it.address }

    init {
        val displayOffsets = IntArray(source.length + 1)
        var displayedLength = 0
        text = buildString {
            var sourceStart = 0
            lines.forEachIndexed { index, line ->
                val hidden = line.hidden
                val lineEnd = sourceStart + line.source.length
                val ranges = if (hidden == null) listOf(sourceStart until lineEnd) else listOf(
                    sourceStart until sourceStart + hidden.first,
                    sourceStart + hidden.last + 1 until lineEnd,
                )
                ranges.filterNot(IntRange::isEmpty).forEach(kept::add)
                for (local in line.source.indices) {
                    val raw = sourceStart + local
                    sourceToDisplay[raw] = length
                    if (hidden == null || local !in hidden) {
                        append(line.source[local])
                        displayOffsets[++displayedLength] = raw + 1
                    }
                    sourceToDisplay[raw + 1] = length
                }
                if (index < lines.lastIndex) {
                    kept += lineEnd..lineEnd
                    sourceToDisplay[lineEnd] = length
                    append('\n')
                    displayOffsets[++displayedLength] = lineEnd + 1
                    sourceToDisplay[lineEnd + 1] = length
                }
                sourceStart = lineEnd + 1
            }
        }
        displayToSource = displayOffsets.copyOf(text.length + 1)
    }

    fun displayOffset(sourceOffset: Int): Int = sourceToDisplay[sourceOffset.coerceIn(0, source.length)]
    fun sourceOffset(displayOffset: Int): Int = displayToSource[displayOffset.coerceIn(0, text.length)]

    fun displayValue(value: TextFieldValue): TextFieldValue = TextFieldValue(
        text = text,
        selection = TextRange(displayOffset(value.selection.start), displayOffset(value.selection.end)),
        composition = value.composition?.let { TextRange(displayOffset(it.start), displayOffset(it.end)) },
    )

    /** Project syntax styles, search highlights and links through the same boundaries. */
    fun annotated(value: AnnotatedString): AnnotatedString {
        require(value.text == source)
        return buildAnnotatedString { kept.forEach { append(value.subSequence(it.first, it.last + 1)) } }
    }

    /**
     * Edits operate on visible characters. Restore metadata only on retained
     * lines, always inside a comment (never between instructions after a join).
     * A split keeps the origin with the first fragment; a join keeps the first
     * available origin. Inserted lines have no invented address. For equal line
     * counts, line identities survive multi-line indentation/replacements.
     */
    fun sourceValue(updated: TextFieldValue): TextFieldValue {
        val change = AsmSourceTextChange.between(text, updated.text)
        if (change == null) return TextFieldValue(
            source,
            selection = TextRange(sourceOffset(updated.selection.start), sourceOffset(updated.selection.end)),
            composition = updated.composition?.let { TextRange(sourceOffset(it.start), sourceOffset(it.end)) },
        )
        val nextLines = updated.text.split('\n')
        val starts = IntArray(nextLines.size)
        for (i in 1 until starts.size) starts[i] = starts[i - 1] + nextLines[i - 1].length + 1
        fun lineAt(offset: Int): Int {
            val found = starts.binarySearch(offset.coerceIn(0, updated.text.length))
            return if (found >= 0) found else -found - 2
        }
        val origins = arrayOfNulls<PresentedLine>(nextLines.size)
        val changeEnd = change.offset + change.removed.length
        val delta = change.inserted.length - change.removed.length
        var start = 0
        lines.forEachIndexed { index, line ->
            val end = start + line.display.length
            val deleted = change.offset <= start && changeEnd > end
            if ((!deleted || lines.size == nextLines.size) && updated.text.isNotEmpty()) {
                val anchor = when {
                    lines.size == nextLines.size -> null
                    end <= change.offset -> start
                    start >= changeEnd -> start + delta
                    start < change.offset -> change.offset
                    end > changeEnd -> change.offset + change.inserted.length
                    else -> -1
                }
                val target = if (anchor == null) index else if (anchor < 0) -1 else
                    lineAt(anchor)
                if (target in origins.indices && origins[target]?.address == null) origins[target] = line
            }
            start = end + 1
        }
        val restored = nextLines.mapIndexed { index, line -> origins[index]?.restore(line) ?: line }
        val nextSource = restored.joinToString("\n")
        // Each restored line projects exactly to the requested visible line.
        // Offset conversion also handles a pasted raw annotation, which becomes
        // metadata on the very next render rather than an invisible caret trap.
        val rawStarts = IntArray(restored.size)
        for (i in 1 until starts.size) {
            rawStarts[i] = rawStarts[i - 1] + restored[i - 1].length + 1
        }
        fun rawOffset(offset: Int): Int {
            val safe = offset.coerceIn(0, updated.text.length)
            val line = lineAt(safe)
            val column = safe - starts[line]
            return rawStarts[line] + if (restored[line] == nextLines[line]) column
            else AsmSourcePresentation(restored[line]).sourceOffset(column)
        }
        return TextFieldValue(
            nextSource,
            selection = TextRange(rawOffset(updated.selection.start), rawOffset(updated.selection.end)),
            composition = updated.composition?.let { TextRange(rawOffset(it.start), rawOffset(it.end)) },
        )
    }

    /** Find in what is displayed, returning authoritative source coordinates. */
    fun find(query: String, matchCase: Boolean): List<AsmDocumentMatch> =
        findAsmDocumentText(text, query, matchCase).map { match ->
            // At a collapsed metadata boundary choose the first visible character,
            // rather than selecting an invisible annotation before that character.
            var start = sourceOffset(match.offset)
            while (start < source.length && displayOffset(start + 1) == match.offset) start++
            val end = sourceOffset(match.offset + match.length)
            val lineStart = source.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
            AsmDocumentMatch(start, end - start, match.line, start - lineStart)
        }

    private class PresentedLine(val source: String) {
        private val comment = asmCodeBeforeComment(source).length
        private val tag = if (comment < source.length) ADDRESS.find(source, comment)
            ?.takeIf { it.range.first == comment } else null
        val address: String? = tag?.groupValues?.get(1)?.uppercase()
        // Preserve the comment's first semicolon when meaningful text follows.
        val hidden: IntRange? = tag?.let {
            val hasComment = source.substring(it.range.last + 1).any { c -> !c.isWhitespace() }
            (comment + if (hasComment) 1 else 0)..it.range.last
        }
        val display = hidden?.let { source.removeRange(it.first, it.last + 1) } ?: source

        fun restore(value: String): String {
            if (value == display) return source
            if (address == null || PresentedLine(value).address != null) return value
            // A partial CRLF edit can leave a lone CR inside a text-field row.
            // Asar may treat it as a line boundary; do not cross it with metadata.
            if ('\r' in value.removeSuffix("\r")) return value
            // Never turn an executable Asar compatibility directive into an
            // ordinary comment just to retain an optional historical address.
            if (value.trimStart().startsWith(";@")) return value
            val at = asmCodeBeforeComment(value).length
            if (at == value.length) {
                // An in-progress string literal cannot safely contain a comment
                // annotation. The user's exact visible edit takes precedence.
                if (asmOffsetInsideString(value, value.length)) return value
                return value.removeSuffix("\r") + tag!!.value + if (value.endsWith('\r')) "\r" else ""
            }
            // An intentionally empty ordinary comment must remain visible, not
            // be mistaken for an annotation-only line. Its origin can be absent.
            if (value.substring(at + 1).isBlank()) return value
            return value.substring(0, at + 1) + tag!!.groupValues[1] + ";" + value.substring(at + 1)
        }
    }

    private companion object {
        val ADDRESS = Regex(";([0-9A-Fa-f]{6});")
    }
}

internal fun findAsmPresentedDocumentText(text: String, query: String, matchCase: Boolean): List<AsmDocumentMatch> =
    AsmSourcePresentation(text).find(query, matchCase)
