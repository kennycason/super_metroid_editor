package com.supermetroid.editor.asm

/** Shared lexical boundaries, not an expression evaluator or macro expander. */
internal object AsmSourceLexing {
    private fun pattern(indexed: Boolean): Regex {
        val index = if (indexed) "(?:\\[[^]\\r\\n]+])?" else ""
        val part = "[A-Za-z_][A-Za-z0-9_]*$index"
        return Regex("\"(?:\\\\.|[^\"\\\\])*\"|\\$[0-9A-Fa-f]+|%[01]+|[0-9]+|[!%]?(?:\\.+)?$part(?:\\.$part)*")
    }
    private val references = pattern(indexed = true)
    private val identifiers = pattern(indexed = false)
    private fun symbols(pattern: Regex, code: String): Sequence<MatchResult> = pattern.findAll(code).filter {
        val token = it.value
        token.first() != '"' && token.first() != '$' && !token.first().isDigit() &&
            !(token.startsWith('%') && token.getOrNull(1)?.isDigit() == true)
    }
    fun references(code: String): Sequence<MatchResult> = symbols(references, code)
    fun identifiers(code: String): Sequence<MatchResult> = symbols(identifiers, code)
}

internal fun asmIdentifierTokens(code: String): List<Pair<IntRange, String>> =
    AsmSourceLexing.identifiers(code).map { it.range to it.value }.toList()

private val asmFunctionParameters = Regex("^\\s*function\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
internal fun asmBoundParameterNames(line: String): List<String> =
    parseAsmMacroDefinition("source.asm", 0, line)?.parameters
        ?: asmFunctionParameters.find(asmExecutableCode(line))?.groupValues?.get(1)?.split(',')?.map(String::trim).orEmpty()

/** Whitespace inside a quoted literal is executable data, not formatting. */
internal fun asmStatementSignature(line: String): String = buildString {
    var quoted = false
    var escaped = false
    asmExecutableCode(line).forEach { character ->
        if (quoted || !character.isWhitespace()) append(character)
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
        }
    }
}
