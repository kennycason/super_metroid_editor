package com.supermetroid.editor.asm

/** Exact text, including line endings. Conflicts are data, never executable source markers. */
internal data class AsmMergeConflict(val base: String, val project: String, val updated: String)
internal data class AsmMergePiece(val text: String = "", val conflict: AsmMergeConflict? = null)
internal data class AsmTextMerge(val pieces: List<AsmMergePiece>) {
    val conflicts: List<AsmMergeConflict> get() = pieces.mapNotNull { it.conflict }
    fun resolved(choices: Map<Int, String> = emptyMap()): String? {
        var conflict = 0
        return buildString {
            pieces.forEach { piece ->
                if (piece.conflict == null) append(piece.text)
                else append(choices[conflict++] ?: return null)
            }
        }
    }
}

internal enum class AsmDiffLineKind { CONTEXT, ADDED, REMOVED, OMITTED }

internal data class AsmDiffLine(
    val kind: AsmDiffLineKind,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val text: String,
)

internal data class AsmSourceDiff(
    val lines: List<AsmDiffLine>,
    val addedLines: Int,
    val removedLines: Int,
    val truncated: Boolean = false,
    val approximateAlignment: Boolean = false,
) {
    val hunks: List<AsmDiffHunk> get() {
        val result = mutableListOf<AsmDiffHunk>()
        val current = mutableListOf<AsmDiffLine>()
        fun flush() {
            if (current.isNotEmpty()) {
                result += AsmDiffHunk(current.toList())
                current.clear()
            }
        }
        lines.forEach { line ->
            if (line.kind == AsmDiffLineKind.OMITTED && "unchanged lines" in line.text) flush()
            else current += line
        }
        flush()
        return result
    }
}

internal data class AsmDiffHunk(val lines: List<AsmDiffLine>) {
    val oldStart: Int? get() = lines.firstNotNullOfOrNull(AsmDiffLine::oldLine)
    val oldEnd: Int? get() = lines.asReversed().firstNotNullOfOrNull(AsmDiffLine::oldLine)
    val newStart: Int? get() = lines.firstNotNullOfOrNull(AsmDiffLine::newLine)
    val newEnd: Int? get() = lines.asReversed().firstNotNullOfOrNull(AsmDiffLine::newLine)
}

/** Bounded Myers line diff. Ambiguous/expensive merges become review conflicts, never guessed edits. */
internal object AsmThreeWayMerge {
    private data class Edit(val start: Int, val end: Int, val replacement: List<String>, val side: Int = 0)
    private data class Anchor(val before: Int, val after: Int)
    private data class DiffBudget(var work: Long = 0, var segments: Int = 0)
    private fun lines(text: String): List<String> = buildList {
        var start = 0
        text.forEachIndexed { at, c -> if (c == '\n') { add(text.substring(start, at + 1)); start = at + 1 } }
        if (start < text.length) add(text.substring(start))
    }

    fun merge(base: String, project: String, updated: String): AsmTextMerge {
        fun clean(text: String) = AsmTextMerge(listOf(AsmMergePiece(text)))
        if (project == base) return clean(updated)
        if (updated == base || updated == project) return clean(project)
        val original = lines(base)
        val ours = diff(original, lines(project))
        val theirs = diff(original, lines(updated))
        if (ours == null || theirs == null) return AsmTextMerge(listOf(AsmMergePiece(conflict = AsmMergeConflict(base, project, updated))))
        val edits = (ours.map { it.copy(side = 0) } + theirs.map { it.copy(side = 1) })
            .sortedWith(compareBy(Edit::start, Edit::end, Edit::side))
        val pieces = mutableListOf<AsmMergePiece>()
        var cursor = 0
        var at = 0
        while (at < edits.size) {
            val group = mutableListOf(edits[at++])
            while (at < edits.size && group.any { overlaps(it, edits[at]) }) group += edits[at++]
            val start = group.minOf { it.start }
            val end = group.maxOf { it.end }
            if (cursor < start) pieces += AsmMergePiece(original.subList(cursor, start).joinToString(""))
            fun apply(side: Int): String = buildString {
                var position = start
                group.filter { it.side == side }.forEach { edit ->
                    append(original.subList(position, edit.start).joinToString(""))
                    append(edit.replacement.joinToString(""))
                    position = edit.end
                }
                append(original.subList(position, end).joinToString(""))
            }
            val left = apply(0)
            val right = apply(1)
            pieces += when {
                group.none { it.side == 0 } -> AsmMergePiece(right)
                group.none { it.side == 1 } || left == right -> AsmMergePiece(left)
                else -> AsmMergePiece(conflict = AsmMergeConflict(original.subList(start, end).joinToString(""), left, right))
            }
            cursor = end
        }
        if (cursor < original.size) pieces += AsmMergePiece(original.subList(cursor, original.size).joinToString(""))
        return AsmTextMerge(pieces)
    }

    /** A bounded, line-oriented review view. It never feeds the merge or changes its decisions. */
    fun compare(before: String, after: String, context: Int = 3, maxChangedLines: Int = 8_000): AsmSourceDiff {
        require(context >= 0 && maxChangedLines > 0)
        if (before == after) return AsmSourceDiff(emptyList(), 0, 0)
        val oldLines = lines(before)
        val newLines = lines(after)
        var approximate = false
        val edits = diff(oldLines, newLines) ?: run {
            // A complex diff is still reviewable as one conservative replacement. The merge remains unresolved.
            approximate = true
            var prefix = 0
            while (prefix < minOf(oldLines.size, newLines.size) && oldLines[prefix] == newLines[prefix]) prefix++
            var suffix = 0
            while (suffix < minOf(oldLines.size, newLines.size) - prefix &&
                oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
            listOf(Edit(prefix, oldLines.size - suffix, newLines.subList(prefix, newLines.size - suffix)))
        }
        val added = edits.sumOf { it.replacement.size }
        val removed = edits.sumOf { it.end - it.start }
        if (edits.isEmpty()) return AsmSourceDiff(emptyList(), 0, 0)

        val rows = mutableListOf<AsmDiffLine>()
        var oldAt = 0
        var newAt = 0
        var renderedChanges = 0
        var truncated = false
        fun text(line: String) = line.removeSuffix("\n").removeSuffix("\r")
        fun unchanged(count: Int) {
            repeat(count) {
                rows += AsmDiffLine(AsmDiffLineKind.CONTEXT, oldAt + 1, newAt + 1, text(oldLines[oldAt]))
                oldAt++; newAt++
            }
        }
        fun omitted(count: Int) {
            if (count <= 0) return
            rows += AsmDiffLine(AsmDiffLineKind.OMITTED, text = "… $count unchanged lines …")
            oldAt += count; newAt += count
        }

        edits.forEachIndexed { index, edit ->
            if (truncated) return@forEachIndexed
            val gap = edit.start - oldAt
            when {
                index == 0 && gap > context -> { omitted(gap - context); unchanged(context) }
                index > 0 && gap > context * 2 -> { unchanged(context); omitted(gap - context * 2); unchanged(context) }
                else -> unchanged(gap)
            }
            val editChanges = edit.end - edit.start + edit.replacement.size
            if (renderedChanges + editChanges > maxChangedLines) {
                val remaining = added + removed - renderedChanges
                rows += AsmDiffLine(AsmDiffLineKind.OMITTED,
                    text = "… $remaining changed lines omitted from this large preview …")
                truncated = true
                return@forEachIndexed
            }
            while (oldAt < edit.end) {
                rows += AsmDiffLine(AsmDiffLineKind.REMOVED, oldAt + 1, null, text(oldLines[oldAt]))
                oldAt++; renderedChanges++
            }
            edit.replacement.forEach { line ->
                rows += AsmDiffLine(AsmDiffLineKind.ADDED, null, newAt + 1, text(line))
                newAt++; renderedChanges++
            }
        }
        if (!truncated) {
            val tail = oldLines.size - oldAt
            if (tail > context) { unchanged(context); omitted(tail - context) } else unchanged(tail)
        }
        return AsmSourceDiff(rows, added, removed, truncated, approximate)
    }

    private fun overlaps(a: Edit, b: Edit): Boolean = when {
        a.start == a.end && b.start == b.end -> a.start == b.start
        a.start == a.end -> a.start > b.start && a.start < b.end
        b.start == b.end -> b.start > a.start && b.start < a.end
        else -> a.start < b.end && b.start < a.end
    }

    private fun diff(before: List<String>, after: List<String>): List<Edit>? =
        anchoredDiff(before, after, DiffBudget(), 0)

    /**
     * Patience anchors split large localized insertions before the bounded Myers pass.
     * This keeps generated room source granular without guessing through genuinely
     * divergent files: a segment with no trustworthy unique anchor still fails closed.
     */
    private fun anchoredDiff(before: List<String>, after: List<String>, budget: DiffBudget, depth: Int): List<Edit>? {
        myersDiff(before, after, budget)?.let { return it }
        if (depth >= 16 || before.size + after.size > MAX_ANCHORED_DIFF_LINES) return null
        val anchors = patienceAnchors(before, after)
        if (anchors.isEmpty()) return null
        val result = mutableListOf<Edit>()
        var oldAt = 0
        var newAt = 0
        for (anchor in anchors) {
            val segment = anchoredDiff(before.subList(oldAt, anchor.before), after.subList(newAt, anchor.after), budget, depth + 1)
                ?: return null
            result += segment.map { it.copy(start = it.start + oldAt, end = it.end + oldAt) }
            oldAt = anchor.before + 1
            newAt = anchor.after + 1
        }
        val tail = anchoredDiff(before.subList(oldAt, before.size), after.subList(newAt, after.size), budget, depth + 1)
            ?: return null
        result += tail.map { it.copy(start = it.start + oldAt, end = it.end + oldAt) }
        return result
    }

    private fun patienceAnchors(before: List<String>, after: List<String>): List<Anchor> {
        fun uniquePositions(lines: List<String>): Map<String, Int> {
            val positions = HashMap<String, Int>(lines.size * 4 / 3 + 1)
            lines.forEachIndexed { index, line ->
                positions[line] = when (positions[line]) {
                    null -> index
                    else -> -1
                }
            }
            return positions.filterValues { it >= 0 }
        }
        val oldPositions = uniquePositions(before)
        if (oldPositions.isEmpty()) return emptyList()
        val newPositions = uniquePositions(after)
        val candidates = oldPositions.mapNotNull { (line, old) ->
            newPositions[line]?.let { Anchor(old, it) }
        }.sortedBy(Anchor::before)
        if (candidates.isEmpty()) return emptyList()

        // Longest increasing subsequence of the new-side positions is the stable order.
        val tails = IntArray(candidates.size)
        val tailCandidates = IntArray(candidates.size)
        val previous = IntArray(candidates.size) { -1 }
        var size = 0
        candidates.forEachIndexed { index, anchor ->
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (tails[middle] < anchor.after) low = middle + 1 else high = middle
            }
            tails[low] = anchor.after
            tailCandidates[low] = index
            if (low > 0) previous[index] = tailCandidates[low - 1]
            if (low == size) size++
        }
        val result = ArrayList<Anchor>(size)
        var at = tailCandidates[size - 1]
        while (at >= 0) {
            result += candidates[at]
            at = previous[at]
        }
        result.reverse()
        return result
    }

    private fun myersDiff(before: List<String>, after: List<String>, budget: DiffBudget): List<Edit>? {
        if (++budget.segments > MAX_DIFF_SEGMENTS) return null
        var prefix = 0
        while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
        if (prefix == before.size && prefix == after.size) return emptyList()
        var suffix = 0
        while (suffix < minOf(before.size, after.size) - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        val a = before.subList(prefix, before.size - suffix)
        val b = after.subList(prefix, after.size - suffix)
        if (a.isEmpty() || b.isEmpty()) return listOf(Edit(prefix, prefix + a.size, b))
        val limit = minOf(a.size + b.size, 1024)
        val offset = limit + 1
        val frontier = IntArray(limit * 2 + 3)
        val trace = mutableListOf<IntArray>()
        for (distance in 0..limit) {
            trace += frontier.copyOf()
            for (diagonal in -distance..distance step 2) {
                if (++budget.work > MAX_DIFF_WORK) return null
                val k = offset + diagonal
                var x = if (diagonal == -distance || diagonal != distance && frontier[k - 1] < frontier[k + 1]) frontier[k + 1] else frontier[k - 1] + 1
                var y = x - diagonal
                while (x < a.size && y < b.size && a[x] == b[y]) {
                    x++; y++
                    if (++budget.work > MAX_DIFF_WORK) return null
                }
                frontier[k] = x
                if (x >= a.size && y >= b.size) {
                    // Backtrack only the edit steps; matching snakes separate adjacent hunks.
                    val steps = mutableListOf<Triple<Int, Boolean, String>>()
                    var bx = a.size
                    var by = b.size
                    for (d in distance downTo 1) {
                        val previous = trace[d]
                        val diagonalAt = bx - by
                        val previousDiagonal = if (diagonalAt == -d || diagonalAt != d && previous[offset + diagonalAt - 1] < previous[offset + diagonalAt + 1]) diagonalAt + 1 else diagonalAt - 1
                        val px = previous[offset + previousDiagonal]
                        val py = px - previousDiagonal
                        if (bx - by == previousDiagonal + 1) steps += Triple(px, false, a[px])
                        else steps += Triple(px, true, b[py])
                        bx = px
                        by = py
                    }
                    steps.reverse()
                    val edits = mutableListOf<Edit>()
                    var start = -1
                    var end = -1
                    var replacements = mutableListOf<String>()
                    fun flush() { if (start >= 0) edits += Edit(prefix + start, prefix + end, replacements.toList()) }
                    steps.forEach { (position, insertion, line) ->
                        if (start < 0 || position > end) {
                            flush(); start = position; end = position; replacements = mutableListOf()
                        }
                        if (insertion) replacements += line else end = position + 1
                    }
                    flush()
                    return edits
                }
            }
        }
        return null
    }

    private const val MAX_ANCHORED_DIFF_LINES = 300_000
    private const val MAX_DIFF_SEGMENTS = 50_000
    private const val MAX_DIFF_WORK = 16_000_000L
}
