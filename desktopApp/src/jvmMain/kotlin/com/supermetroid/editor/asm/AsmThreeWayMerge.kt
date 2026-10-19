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

/** Bounded Myers line diff. Ambiguous/expensive merges become review conflicts, never guessed edits. */
internal object AsmThreeWayMerge {
    private data class Edit(val start: Int, val end: Int, val replacement: List<String>, val side: Int = 0)
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

    private fun overlaps(a: Edit, b: Edit): Boolean = when {
        a.start == a.end && b.start == b.end -> a.start == b.start
        a.start == a.end -> a.start > b.start && a.start < b.end
        b.start == b.end -> b.start > a.start && b.start < a.end
        else -> a.start < b.end && b.start < a.end
    }

    private fun diff(before: List<String>, after: List<String>): List<Edit>? {
        var prefix = 0
        while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(before.size, after.size) - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        val a = before.subList(prefix, before.size - suffix)
        val b = after.subList(prefix, after.size - suffix)
        if (a.isEmpty() || b.isEmpty()) return listOf(Edit(prefix, prefix + a.size, b))
        val limit = minOf(a.size + b.size, 1024)
        val offset = limit + 1
        val frontier = IntArray(limit * 2 + 3)
        val trace = mutableListOf<IntArray>()
        var work = 0L
        for (distance in 0..limit) {
            trace += frontier.copyOf()
            for (diagonal in -distance..distance step 2) {
                val k = offset + diagonal
                var x = if (diagonal == -distance || diagonal != distance && frontier[k - 1] < frontier[k + 1]) frontier[k + 1] else frontier[k - 1] + 1
                var y = x - diagonal
                while (x < a.size && y < b.size && a[x] == b[y]) { x++; y++; if (++work > 8_000_000) return null }
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
}
