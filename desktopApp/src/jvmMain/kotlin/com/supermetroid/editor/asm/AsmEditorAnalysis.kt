package com.supermetroid.editor.asm

/** Exact, deliberately small Asar expression evaluator used by editor inspections.
 * Unknown symbols/functions and invalid arithmetic stay unknown; callers must never
 * use a guessed value for placement or instruction-width diagnostics. */
internal object AsmConstantExpression {
    fun evaluate(expression: String, symbols: Map<String, Long> = emptyMap()): Long? =
        runCatching { Parser(expression, symbols).parse() }.getOrNull()

    private enum class Kind { NUMBER, SYMBOL, OPERATOR, LEFT, RIGHT, END }
    private data class Token(val kind: Kind, val text: String, val value: Long? = null)

    private class Parser(source: String, private val symbols: Map<String, Long>) {
        private val tokens = tokenize(source)
        private var cursor = 0

        fun parse(): Long {
            val value = expression(0)
            require(peek().kind == Kind.END) { "Trailing expression input" }
            return value
        }

        private fun expression(minimumPrecedence: Int): Long {
            var left = prefix()
            while (true) {
                val operator = peek().takeIf { it.kind == Kind.OPERATOR }?.text ?: break
                val precedence = PRECEDENCE[operator] ?: break
                if (precedence < minimumPrecedence) break
                cursor++
                val right = expression(precedence + 1)
                left = binary(operator, left, right)
            }
            return left
        }

        private fun prefix(): Long {
            val token = tokens[cursor++]
            return when (token.kind) {
                Kind.NUMBER -> checkNotNull(token.value)
                Kind.OPERATOR -> when (token.text) {
                    "+" -> expression(100)
                    "-" -> -expression(100)
                    "~" -> expression(100).inv()
                    "!" -> if (expression(100) == 0L) 1 else 0
                    else -> error("Unsupported unary operator")
                }
                Kind.LEFT -> expression(0).also {
                    require(tokens[cursor++].kind == Kind.RIGHT) { "Missing closing parenthesis" }
                }
                Kind.SYMBOL -> {
                    if (peek().kind == Kind.LEFT) {
                        cursor++
                        val argument = expression(0)
                        require(tokens[cursor++].kind == Kind.RIGHT) { "Missing function parenthesis" }
                        function(token.text, argument)
                    } else symbols[token.text] ?: error("Unknown symbol")
                }
                else -> error("Expected expression")
            }
        }

        private fun peek(): Token = tokens[cursor]

        private fun function(name: String, value: Long): Long = when (name.lowercase()) {
            "low" -> value and 0xFF
            "high" -> (value ushr 8) and 0xFF
            "bank", "bankbyte" -> (value ushr 16) and 0xFF
            "word", "loword" -> value and 0xFFFF
            "snestopc" -> snesLoRomToPc(value.toInt())?.toLong() ?: error("Not a LoROM address")
            "pctosnes" -> pcToSnesLoRom(value.toInt())?.toLong() ?: error("Not a LoROM offset")
            else -> error("Unknown function")
        }

        private fun binary(operator: String, left: Long, right: Long): Long = when (operator) {
            "*" -> Math.multiplyExact(left, right)
            "/" -> left / right
            "%" -> left % right
            "+" -> Math.addExact(left, right)
            "-" -> Math.subtractExact(left, right)
            "<<" -> left shl right.toInt()
            ">>" -> left shr right.toInt()
            "&" -> left and right
            "^" -> left xor right
            "|" -> left or right
            "==" -> if (left == right) 1 else 0
            "!=" -> if (left != right) 1 else 0
            "<" -> if (left < right) 1 else 0
            "<=" -> if (left <= right) 1 else 0
            ">" -> if (left > right) 1 else 0
            ">=" -> if (left >= right) 1 else 0
            "&&" -> if (left != 0L && right != 0L) 1 else 0
            "||" -> if (left != 0L || right != 0L) 1 else 0
            else -> error("Unsupported operator")
        }
    }

    private val PRECEDENCE = mapOf(
        "||" to 1, "&&" to 2, "|" to 3, "^" to 4, "&" to 5,
        "==" to 6, "!=" to 6, "<" to 7, "<=" to 7, ">" to 7, ">=" to 7,
        "<<" to 8, ">>" to 8, "+" to 9, "-" to 9, "*" to 10, "/" to 10, "%" to 10,
    )

    private fun tokenize(source: String): List<Token> = buildList {
        var cursor = 0
        while (cursor < source.length) {
            val character = source[cursor]
            when {
                character.isWhitespace() -> cursor++
                character == '(' -> { add(Token(Kind.LEFT, "(")); cursor++ }
                character == ')' -> { add(Token(Kind.RIGHT, ")")); cursor++ }
                character == '$' -> {
                    val start = cursor++
                    while (cursor < source.length && source[cursor].isHexDigit()) cursor++
                    require(cursor > start + 1) { "Empty hexadecimal literal" }
                    val high = source.substring(start + 1, cursor)
                    if (cursor < source.length && source[cursor] == ':') {
                        cursor++
                        val lowStart = cursor
                        while (cursor < source.length && source[cursor].isHexDigit()) cursor++
                        require(cursor > lowStart) { "Empty bank-relative literal" }
                        add(Token(Kind.NUMBER, source.substring(start, cursor),
                            (high + source.substring(lowStart, cursor)).toLong(16)))
                    } else add(Token(Kind.NUMBER, source.substring(start, cursor), high.toLong(16)))
                }
                character == '%' && source.getOrNull(cursor + 1) in '0'..'1' -> {
                    val start = cursor++
                    while (source.getOrNull(cursor) in '0'..'1') cursor++
                    add(Token(Kind.NUMBER, source.substring(start, cursor),
                        source.substring(start + 1, cursor).toLong(2)))
                }
                character.isDigit() -> {
                    val start = cursor++
                    while (source.getOrNull(cursor)?.isDigit() == true) cursor++
                    add(Token(Kind.NUMBER, source.substring(start, cursor), source.substring(start, cursor).toLong()))
                }
                character.isLetter() || character == '_' || character == '.' ||
                    character == '!' && source.getOrNull(cursor + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    val start = cursor++
                    while (source.getOrNull(cursor)?.let { it.isLetterOrDigit() || it == '_' || it == '.' } == true) cursor++
                    add(Token(Kind.SYMBOL, source.substring(start, cursor)))
                }
                else -> {
                    val pair = source.substring(cursor, (cursor + 2).coerceAtMost(source.length))
                    val operator = pair.takeIf { it in PRECEDENCE || it in setOf("<<", ">>") }
                        ?: character.toString().takeIf { it in PRECEDENCE || it in setOf("+", "-", "~", "!") }
                        ?: error("Unknown expression token")
                    add(Token(Kind.OPERATOR, operator))
                    cursor += operator.length
                }
            }
        }
        add(Token(Kind.END, ""))
    }
}

internal data class AsmEditorAnalysisContext(
    val index: AsmReferenceIndex?,
    val sourceTexts: Map<String, String>,
    val excludedFileIds: Set<String> = emptySet(),
) {
    val constants: Map<String, Long> = resolveConstants(sourceTexts, excludedFileIds)
    val returnKinds: Map<AsmSymbolId, AsmRoutineReturnKind> =
        index?.let { classifyRoutineReturns(it, sourceTexts, excludedFileIds) }.orEmpty()
}

internal enum class AsmRoutineReturnKind { NEAR, LONG }

internal object AsmEditorAnalyzer {
    private val dataWidths = mapOf("db" to 1, "dw" to 2, "dl" to 3, "dd" to 4)
    private val instruction = Regex("^\\s*([A-Za-z]{3})(?:\\.([BbWwLl]))?\\b(?:\\s+(.*))?$")
    private val directive = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*)\\b(?:\\s+(.*))?$")
    private val immediateAccumulator = setOf("ADC", "AND", "BIT", "CMP", "EOR", "LDA", "ORA", "SBC")
    private val immediateIndex = setOf("CPX", "CPY", "LDX", "LDY")
    private val controlFlow = setOf(
        "BCC", "BCS", "BEQ", "BMI", "BNE", "BPL", "BRA", "BRL", "BVC", "BVS",
        "JMP", "JML", "JSR", "JSL", "RTI", "RTS", "RTL", "BRK", "COP",
    )
    private val ignoredSymbols = setOf("A", "X", "Y", "S", "SP", "PC", "true", "false")

    fun inspectFile(
        fileId: String,
        text: String,
        macros: List<AsmMacro>? = null,
        context: AsmEditorAnalysisContext = AsmEditorAnalysisContext(null, mapOf(fileId to text)),
    ): List<AsmBuildDiagnostic> = buildList {
        val macrosByName = macros?.associateBy { it.name.lowercase() }
        val referenceIndex = context.index
        val referencesAreCurrent = referenceIndex?.file(fileId)?.lines == text.reader().readLines()
        var outputCursor: Long? = null
        var accumulator8: Boolean? = null
        var index8: Boolean? = null
        var insideMacro = false
        var namespaceActive = false

        fun problem(lineIndex: Int, severity: AsmDiagnosticSeverity, message: String) {
            add(AsmBuildDiagnostic(severity, message, fileId, lineIndex))
        }

        text.lineSequence().forEachIndexed { lineIndex, sourceLine ->
            parseAsmMacroDefinition(fileId, lineIndex, sourceLine)?.let { insideMacro = true }
            val code = asmExecutableCode(sourceLine)
            val hasDeclaration = referencesAreCurrent && referenceIndex?.labelsAt(fileId, lineIndex).orEmpty().isNotEmpty()
            if (hasDeclaration) {
                accumulator8 = null
                index8 = null
            }
            val statements = splitAsmStatements(code)
            statements.forEach statementLoop@{ rawStatement ->
                val statement = rawStatement.trim()
                if (statement.isEmpty()) return@statementLoop
                val parsedDirective = directive.matchEntire(statement)
                val directiveName = parsedDirective?.groupValues?.get(1)?.lowercase()
                val directiveOperand = parsedDirective?.groupValues?.getOrNull(2).orEmpty().trim()
                if (directiveName == "namespace") {
                    namespaceActive = !directiveOperand.equals("off", ignoreCase = true)
                    return@statementLoop
                }

                if (directiveName in dataWidths) {
                    val width = checkNotNull(dataWidths[directiveName])
                    val operands = splitAsmOperands(directiveOperand)
                    operands.forEach { operand ->
                        if (operand.startsWith('"')) return@forEach
                        val bits = width * 8
                        val minimum = -(1L shl (bits - 1).coerceAtMost(62))
                        val maximum = if (bits >= 63) Long.MAX_VALUE else (1L shl bits) - 1
                        val intentionalMask = operand.contains(Regex("&\\s*\\$${"F".repeat(width * 2)}\\b", RegexOption.IGNORE_CASE))
                        val value = AsmConstantExpression.evaluate(operand, context.constants)
                        val containsSymbol = AsmSourceLexing.references(operand).any()
                        val oversizedLiteral = Regex("\\$([0-9A-Fa-f]+)").findAll(operand)
                            .firstOrNull { it.groupValues[1].length > width * 2 }
                        if (!intentionalMask && !containsSymbol && value != null && value !in minimum..maximum) {
                            problem(lineIndex, AsmDiagnosticSeverity.ERROR,
                                "$operand evaluates to $value and does not fit $directiveName ($bits bits); Asar would silently keep only the low $bits bits. Use an in-range value or mask explicitly with &\$${"F".repeat(width * 2)} if truncation is intentional.")
                        } else if (!intentionalMask && value == null && oversizedLiteral != null) {
                            problem(lineIndex, AsmDiagnosticSeverity.ERROR,
                                "${oversizedLiteral.value} does not fit $directiveName ($bits bits); Asar would silently keep only the low $bits bits. Use an in-range value or mask explicitly with &\$${"F".repeat(width * 2)} if truncation is intentional.")
                        }
                    }
                    val emitted = operands.takeIf { it.isNotEmpty() && it.none { operand -> operand.startsWith('"') } }
                        ?.size?.times(width)?.toLong()
                    outputCursor = advanceCursor(outputCursor, emitted, lineIndex, ::problem)
                    inspectReferences(fileId, lineIndex, directiveOperand, referenceIndex, referencesAreCurrent,
                        context.excludedFileIds, insideMacro || namespaceActive, directTarget = false, ::problem)
                    return@statementLoop
                }

                when (directiveName) {
                    "org" -> {
                        outputCursor = AsmConstantExpression.evaluate(directiveOperand, context.constants)
                            ?.takeIf { it in 0..0xFFFFFF }
                        inspectReferences(fileId, lineIndex, directiveOperand, referenceIndex, referencesAreCurrent,
                            context.excludedFileIds, insideMacro || namespaceActive, directTarget = false, ::problem)
                        return@statementLoop
                    }
                    "warnpc" -> {
                        val boundary = AsmConstantExpression.evaluate(directiveOperand, context.constants)
                        val cursor = outputCursor
                        if (boundary != null && cursor != null) {
                            if ((boundary ushr 16) != (cursor ushr 16)) {
                                problem(lineIndex, AsmDiagnosticSeverity.WARNING,
                                    "warnpc boundary ${asmHex(boundary)} is in a different bank than the exact output cursor ${asmHex(cursor)}.")
                            } else if (cursor > boundary) {
                                problem(lineIndex, AsmDiagnosticSeverity.ERROR,
                                    "Exact output cursor ${asmHex(cursor)} exceeds warnpc ${asmHex(boundary)} by ${cursor - boundary} byte(s).")
                            }
                        }
                        inspectReferences(fileId, lineIndex, directiveOperand, referenceIndex, referencesAreCurrent,
                            context.excludedFileIds, insideMacro || namespaceActive, directTarget = false, ::problem)
                        return@statementLoop
                    }
                    "fill" -> {
                        val count = splitAsmOperands(directiveOperand).firstOrNull()
                            ?.let { AsmConstantExpression.evaluate(it, context.constants) }
                            ?.takeIf { it >= 0 }
                        outputCursor = advanceCursor(outputCursor, count, lineIndex, ::problem)
                        return@statementLoop
                    }
                    "pad" -> {
                        val target = AsmConstantExpression.evaluate(directiveOperand, context.constants)
                        outputCursor = if (target != null && outputCursor != null && target >= outputCursor!!) target else null
                        return@statementLoop
                    }
                    "incbin" -> { outputCursor = null; return@statementLoop }
                }

                if (statement.startsWith('%')) {
                    if (macrosByName != null) inspectMacroCalls(statement, macrosByName, fileId, lineIndex, ::problem)
                    outputCursor = null
                    return@statementLoop
                }
                val parsedInstruction = instruction.matchEntire(statement) ?: return@statementLoop
                val mnemonic = parsedInstruction.groupValues[1].uppercase()
                if (AsmInstructionReference.find(mnemonic) == null) return@statementLoop
                val suffix = parsedInstruction.groupValues[2].uppercase().ifBlank { null }
                val operand = parsedInstruction.groupValues.getOrNull(3).orEmpty().trim()

                if (mnemonic == "JSR" || mnemonic == "JSL") {
                    val directToken = AsmSourceLexing.references(operand).singleOrNull()?.value
                    val target = directToken?.takeIf { operand.trim() == it }
                        ?.let { referenceIndex?.resolveUniqueLabel(fileId, lineIndex, it) }
                    val returnKind = target?.symbolId?.let(context.returnKinds::get)
                    if (mnemonic == "JSR" && returnKind == AsmRoutineReturnKind.LONG) {
                        problem(lineIndex, AsmDiagnosticSeverity.WARNING,
                            "JSR targets ${target.name}, whose proven terminal return is RTL. Use a matching near routine/RTS or a checked long call.")
                    } else if (mnemonic == "JSL" && returnKind == AsmRoutineReturnKind.NEAR) {
                        problem(lineIndex, AsmDiagnosticSeverity.WARNING,
                            "JSL targets ${target.name}, whose proven terminal return is RTS. Use a matching long routine/RTL or a checked near call.")
                    }
                }

                val expected8 = when (mnemonic) {
                    in immediateAccumulator -> accumulator8
                    in immediateIndex -> index8
                    else -> null
                }
                if (operand.startsWith('#') && expected8 != null) {
                    val expression = operand.removePrefix("#").trim()
                    val value = AsmConstantExpression.evaluate(expression, context.constants)
                    val selectedBytes = when (suffix) {
                        "B" -> 1
                        "W" -> 2
                        "L" -> 3
                        else -> value?.let(::smallestImmediateWidth)
                    }
                    val expectedBytes = if (expected8) 1 else 2
                    if (selectedBytes == null && suffix == null) {
                        problem(lineIndex, AsmDiagnosticSeverity.WARNING,
                            "$mnemonic immediate width is not explicit while ${if (expected8) "8-bit" else "16-bit"} processor state is proven here; add .${if (expected8) "B" else "W"}.")
                    } else if (selectedBytes != null && selectedBytes != expectedBytes) {
                        problem(lineIndex, AsmDiagnosticSeverity.WARNING,
                            "$mnemonic selects a $selectedBytes-byte immediate while ${if (expected8) "8-bit" else "16-bit"} processor state is proven here; use .${if (expected8) "B" else "W"} and a matching value.")
                    }
                }

                inspectReferences(fileId, lineIndex, operand.removePrefix("#"), referenceIndex, referencesAreCurrent,
                    context.excludedFileIds, insideMacro || namespaceActive, mnemonic in controlFlow, ::problem)
                outputCursor = advanceCursor(outputCursor,
                    exactInstructionSize(mnemonic, suffix, operand, context.constants), lineIndex, ::problem)

                if (mnemonic == "REP" || mnemonic == "SEP") {
                    val mask = AsmConstantExpression.evaluate(operand.removePrefix("#").trim(), context.constants)
                    if (mask != null) {
                        if (mask and 0x20 != 0L) accumulator8 = mnemonic == "SEP"
                        if (mask and 0x10 != 0L) index8 = mnemonic == "SEP"
                    } else {
                        accumulator8 = null
                        index8 = null
                    }
                } else if (mnemonic in controlFlow) {
                    accumulator8 = null
                    index8 = null
                }
            }
            if (macrosByName != null) inspectMacroCalls(code, macrosByName, fileId, lineIndex, ::problem)
            if (sourceLine.trimStart().startsWith("endmacro", ignoreCase = true)) insideMacro = false
        }
    }.distinct()

    private fun advanceCursor(
        cursor: Long?,
        emitted: Long?,
        lineIndex: Int,
        problem: (Int, AsmDiagnosticSeverity, String) -> Unit,
    ): Long? {
        if (cursor == null || emitted == null) return null
        if (emitted < 0 || cursor > 0xFFFFFF - emitted) return null
        val next = cursor + emitted
        if (emitted > 0 && (cursor ushr 16) != ((next - 1) ushr 16)) {
            problem(lineIndex, AsmDiagnosticSeverity.ERROR,
                "This exact $emitted-byte emission crosses the ROM bank boundary at ${asmHex((cursor and 0xFF0000) + 0x10000)} (starts at ${asmHex(cursor)}). Move or split the data instead of relying on bank wraparound.")
        }
        return next
    }

    private fun inspectReferences(
        fileId: String,
        lineIndex: Int,
        expression: String,
        index: AsmReferenceIndex?,
        referencesAreCurrent: Boolean,
        excludedFileIds: Set<String>,
        unsafeScope: Boolean,
        directTarget: Boolean,
        problem: (Int, AsmDiagnosticSeverity, String) -> Unit,
    ) {
        if (index == null || !referencesAreCurrent || unsafeScope || !directTarget || expression.isBlank()) return
        val matches = AsmSourceLexing.references(expression).toList()
        if (matches.size != 1) return
        val match = matches.single()
        val token = match.value
        if (token.startsWith('%') || token.startsWith('!') || token.startsWith('"') ||
            token.uppercase() in ignoredSymbols || expression.substring(match.range.last + 1).trimStart().startsWith('(')
        ) return
        val target = index.resolveLabel(fileId, lineIndex, token)
        if (target == null || target.fileId in excludedFileIds) {
            problem(lineIndex, AsmDiagnosticSeverity.ERROR,
                "Unresolved ASM control-flow target $token.")
        }
    }

    private fun inspectMacroCalls(
        code: String,
        macros: Map<String, AsmMacro>,
        fileId: String,
        lineIndex: Int,
        problem: (Int, AsmDiagnosticSeverity, String) -> Unit,
    ) {
        parseCompleteMacroCalls(code).forEach { call ->
            val macro = macros[call.name.lowercase()]
            if (macro == null) {
                problem(lineIndex, AsmDiagnosticSeverity.ERROR, "Unknown macro %${call.name}(...).")
                return@forEach
            }
            val fixed = macro.parameters.count { it != "..." }
            val valid = if (macro.variadic) call.argumentCount >= fixed else call.argumentCount == fixed
            if (!valid) {
                val expectation = if (macro.variadic) "at least $fixed" else fixed.toString()
                problem(lineIndex, AsmDiagnosticSeverity.ERROR,
                    "${macro.signature} expects $expectation argument${if (fixed == 1 && !macro.variadic) "" else "s"}; found ${call.argumentCount}.")
            }
        }
    }
}

private data class CompleteMacroCall(val name: String, val argumentCount: Int)
private val completeMacroStart = Regex("%([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

private fun parseCompleteMacroCalls(code: String): List<CompleteMacroCall> = completeMacroStart.findAll(code).mapNotNull { match ->
    if (asmOffsetInsideString(code, match.range.first)) return@mapNotNull null
    val open = match.range.last
    var depth = 1
    var quoted = false
    var escaped = false
    var close = -1
    var cursor = open + 1
    while (cursor < code.length && close < 0) {
        when (val character = code[cursor]) {
            '\\' -> if (quoted && !escaped) escaped = true else escaped = false
            '"' -> if (!escaped) quoted = !quoted
            '(' -> if (!quoted) depth++
            ')' -> if (!quoted && --depth == 0) close = cursor
            else -> escaped = false
        }
        cursor++
    }
    if (close < 0) null else CompleteMacroCall(match.groupValues[1], countAsmArguments(code.substring(open + 1, close)))
}.toList()

private fun countAsmArguments(arguments: String): Int =
    if (arguments.isBlank()) 0 else splitAsmOperands(arguments).size

internal fun splitAsmStatements(code: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    code.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
        }
        val addressColon = character == ':' && !quoted &&
            current.takeLast(3).matches(Regex("\\$[0-9A-Fa-f]{2}")) &&
            code.drop(index + 1).take(4).matches(Regex("[0-9A-Fa-f]{4}"))
        if (character == ':' && !quoted && !addressColon) {
            result += current.toString()
            current.clear()
        } else current.append(character)
    }
    result += current.toString()
    return result
}

internal fun splitAsmOperands(source: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var parentheses = 0
    var brackets = 0
    var quoted = false
    var escaped = false
    source.forEach { character ->
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
            !quoted && character == '(' -> parentheses++
            !quoted && character == ')' -> parentheses--
            !quoted && character == '[' -> brackets++
            !quoted && character == ']' -> brackets--
        }
        if (character == ',' && !quoted && parentheses == 0 && brackets == 0) {
            result += current.toString().trim()
            current.clear()
        } else current.append(character)
    }
    current.toString().trim().takeIf { it.isNotEmpty() }?.let(result::add)
    return result
}

private fun resolveConstants(texts: Map<String, String>, excluded: Set<String>): Map<String, Long> {
    val definitions = linkedMapOf<String, String>()
    val definition = Regex("^\\s*(![A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\?=|#=|=)\\s*(.+)$")
    texts.filterKeys { it !in excluded }.values.forEach { text ->
        text.lineSequence().forEach { line ->
            definition.matchEntire(asmExecutableCode(line))?.let { definitions[it.groupValues[1]] = it.groupValues[2].trim() }
        }
    }
    val result = linkedMapOf<String, Long>()
    repeat(definitions.size.coerceAtLeast(1)) {
        var changed = false
        definitions.forEach { (name, expression) ->
            if (name !in result) AsmConstantExpression.evaluate(expression, result)?.let {
                result[name] = it
                changed = true
            }
        }
        if (!changed) return result
    }
    return result
}

private fun classifyRoutineReturns(
    index: AsmReferenceIndex,
    texts: Map<String, String>,
    excluded: Set<String>,
): Map<AsmSymbolId, AsmRoutineReturnKind> = buildMap {
    index.files.filterNot { it.id in excluded }.forEach { source ->
        val text = texts[source.id] ?: return@forEach
        if (source.lines != text.reader().readLines()) return@forEach
        val roots = index.labels.filter { label ->
            label.fileId == source.id && label.localScope == null &&
                Regex("^\\s*${Regex.escape(label.name)}:").containsMatchIn(asmExecutableCode(source.lines[label.lineIndex]))
        }.distinctBy { it.lineIndex }.sortedBy { it.lineIndex }
        roots.forEachIndexed { rootIndex, root ->
            val end = roots.getOrNull(rootIndex + 1)?.lineIndex ?: source.lines.size
            val bodyLines = source.lines.subList(root.lineIndex + 1, end)
            val hasUnknownExpansion = bodyLines.any { line ->
                val code = asmExecutableCode(line)
                '%' in code || Regex("^\\s*(?:if|elseif|else|endif|while|endwhile|incsrc|namespace)\\b",
                    RegexOption.IGNORE_CASE).containsMatchIn(code)
            }
            if (hasUnknownExpansion) return@forEachIndexed
            val instructions = bodyLines.flatMap { line ->
                splitAsmStatements(asmExecutableCode(line)).mapNotNull { statement ->
                    Regex("^\\s*([A-Za-z]{3})(?:\\.[BbWwLl])?\\b").find(statement)?.groupValues?.get(1)?.uppercase()
                        ?.takeIf { AsmInstructionReference.find(it) != null }
                }
            }
            val returns = instructions.filter { it == "RTS" || it == "RTL" }.distinct()
            if (returns.size == 1 && instructions.lastOrNull() == returns.single()) {
                put(root.symbolId, if (returns.single() == "RTS") AsmRoutineReturnKind.NEAR else AsmRoutineReturnKind.LONG)
            }
        }
    }
}

private fun exactInstructionSize(mnemonic: String, suffix: String?, operand: String, constants: Map<String, Long>): Long? {
    if (operand.isBlank()) return 1
    if (mnemonic in setOf("BCC", "BCS", "BEQ", "BMI", "BNE", "BPL", "BRA", "BVC", "BVS")) return 2
    if (mnemonic in setOf("BRL", "PER", "PEA")) return 3
    if (mnemonic in setOf("JSL", "JML")) return 4
    if (mnemonic in setOf("JSR", "JMP")) return 3
    if (mnemonic in setOf("REP", "SEP", "BRK", "COP", "WDM", "PEI")) return 2
    if (mnemonic in setOf("MVN", "MVP")) return 3
    suffix?.let { return 1L + when (it) { "B" -> 1; "W" -> 2; "L" -> 3; else -> return null } }
    if (operand.startsWith('#')) {
        val value = AsmConstantExpression.evaluate(operand.removePrefix("#").trim(), constants) ?: return null
        return 1L + (smallestImmediateWidth(value) ?: return null)
    }
    if (operand.any { it == '(' || it == '[' || it == ']' }) return null
    val address = AsmConstantExpression.evaluate(operand.substringBefore(',').trim(), constants) ?: return null
    return when (address) { in -128..0xFF -> 2; in -32768..0xFFFF -> 3; in 0..0xFFFFFF -> 4; else -> null }
}

private fun smallestImmediateWidth(value: Long): Int? = when (value) {
    in -128..0xFF -> 1
    in -32768..0xFFFF -> 2
    in -0x800000..0xFFFFFF -> 3
    else -> null
}

private fun asmHex(value: Long): String = "\$${value.toString(16).uppercase().padStart(6, '0')}"

private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'
