package com.supermetroid.editor.asm

import kotlinx.serialization.Serializable

/** Every code/label line in one ordinary global scope, in source order. */
@Serializable
internal data class AsmSourceLineDocumentation(
    val id: String,
    val fileId: String,
    val symbol: String,
    val lines: List<AsmSourceLineNote>,
)

@Serializable
internal data class AsmSourceLineNote(
    val expectedCode: String,
    /** Null entries guard control flow or data without adding redundant prose. */
    val comment: String? = null,
)

internal data class AsmSourceCommentInsertion(val fileId: String, val lineIndex: Int, val column: Int, val text: String)

/**
 * Match complete scoped code, not address tags, line numbers or the nth textual
 * occurrence of an opcode. Labels (including anonymous/nested ones) participate
 * in the guard, so repeated instructions cannot inherit the wrong explanation.
 * Return insertions only; executable bytes and all upstream comments stay intact.
 */
internal fun planAsmSourceLineDocumentation(
    notes: List<AsmSourceLineDocumentation>,
    index: AsmReferenceIndex,
): List<AsmSourceCommentInsertion> {
    require(notes.map { it.id }.distinct().size == notes.size) { "Duplicate ASM line documentation identity" }
    require(notes.map { it.fileId to it.symbol }.distinct().size == notes.size) { "Duplicate ASM line documentation target" }
    val globals = index.labels.filter { it.localScope == null }.groupBy { it.name }
    return notes.flatMap { note ->
        require(note.id.isNotBlank() && note.lines.isNotEmpty() && note.lines.any { it.comment != null }) {
            "Empty ASM line documentation record"
        }
        val root = globals[note.symbol]?.singleOrNull()?.takeIf { it.fileId == note.fileId }
            ?: error("${note.id}: expected one ${note.symbol} declaration in ${note.fileId}")
        val source = checkNotNull(index.file(root.fileId))
        require(Regex("\\s*${Regex.escape(root.name)}:\\s*").matches(asmExecutableCode(source.lines[root.lineIndex]))) {
            "${note.id}: line documentation requires a standalone ordinary global label"
        }
        val end = (root.lineIndex + 1 until source.lines.size).firstOrNull {
            source.scopePathByLine[it].firstOrNull() != root.name
        } ?: source.lines.size
        val codeLines = (root.lineIndex + 1 until end).filter { asmExecutableCode(source.lines[it]).isNotBlank() }
        note.lines.forEach { line ->
            require(line.expectedCode.isNotBlank() && '\n' !in line.expectedCode && '\r' !in line.expectedCode &&
                asmCodeBeforeComment(line.expectedCode) == line.expectedCode) { "${note.id}: expected one complete code line" }
            line.comment?.let { comment ->
                require(comment.isNotBlank() && '\n' !in comment && '\r' !in comment) {
                    "${note.id}: line comments must be nonempty single lines"
                }
            }
        }
        require(codeLines.size == note.lines.size && codeLines.zip(note.lines).all { (line, expected) ->
            asmStatementSignature(source.lines[line]) == asmStatementSignature(expected.expectedCode)
        }) { "${note.id}: ${note.symbol} scoped code shape changed" }
        codeLines.zip(note.lines).mapNotNull { (lineIndex, lineNote) ->
            val comment = lineNote.comment ?: return@mapNotNull null
            val line = source.lines[lineIndex]
            val code = asmCodeBeforeComment(line)
            require(!line.trimStart().startsWith(";@") && !code.trimEnd().endsWith('\\') &&
                !asmOffsetInsideString(code, code.length)) {
                "${note.id}: comments on compatibility/continuation lines or unfinished strings are unsupported"
            }
            val oldComment = line.substring(code.length).trim()
            val prefix = when {
                oldComment.isEmpty() -> if (line.lastOrNull()?.isWhitespace() == true) "; " else " ; "
                oldComment == ";" || Regex(";[0-9A-Fa-f]{6};").matches(oldComment) -> " "
                else -> " | "
            }
            AsmSourceCommentInsertion(root.fileId, lineIndex, line.length, prefix + comment)
        }
    }
}
