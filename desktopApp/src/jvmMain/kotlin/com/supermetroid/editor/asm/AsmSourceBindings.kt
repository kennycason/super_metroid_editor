package com.supermetroid.editor.asm

import kotlinx.serialization.Serializable
import java.io.File

/** A stable upstream identity, independent of comments, current spelling, or relocated project code. */
@Serializable
internal data class AsmNativeSymbol(val fileId: String, val name: String, val address: Int,
    val generatedMembers: List<String> = emptyList())

/** Names in code contracts are canonical; authored source and compiler output keep active spellings. */
internal class AsmSourceSymbolNames(renamed: Map<String, String> = emptyMap()) {
    val renamed: Map<String, String> = renamed.toMap()
    private val originals = this.renamed.entries.associate { it.value to it.key }
    init {
        require(originals.size == this.renamed.size) { "Ambiguous ASM symbol mapping" }
        require(this.renamed.all { (old, new) -> ASM_GLOBAL_IDENTIFIER.matches(old) && ASM_GLOBAL_IDENTIFIER.matches(new) }) {
            "Invalid ASM symbol mapping"
        }
    }
    fun active(name: String): String = renamed[name] ?: name
    fun canonical(name: String): String = originals[name] ?: name
    fun canonicalCode(code: String): String = rewriteAsmIdentifiers(code, ::canonical)
    fun activeCode(code: String): String = rewriteAsmIdentifiers(code, ::active)
    fun canonicalLabels(labels: Map<String, Int>): Map<String, Int> = buildMap {
        labels.forEach { (name, value) ->
            val identity = canonical(name)
            require(identity !in this || get(identity) == value) { "Ambiguous compiled symbol identity $identity" }
            put(identity, value)
        }
    }
}

/** Shared boundary for baseline identities, native editor lenses, and newly inserted bundled code. */
internal class AsmSourceBindings(
    val names: AsmSourceSymbolNames = AsmSourceSymbolNames(),
    nativeSymbols: List<AsmNativeSymbol> = emptyList(),
) {
    val nativeSymbols = nativeSymbols.toList()

    fun enemyHeaders(text: String): Map<Int, String> {
        val declarations = asmGlobalDeclarations(text)
        val headers = linkedMapOf<Int, String>()
        nativeSymbols.filter { it.fileId == ENEMY_HEADER_SOURCE_FILE && it.name.startsWith("EnemyHeaders_") }.forEach { native ->
            val name = names.active(native.name)
            val occurrences = declarations[name].orEmpty()
            if (occurrences.isEmpty()) return@forEach
            require(occurrences.size == 1) { "Ambiguous native enemy declaration $name" }
            require(native.address ushr 16 == 0xA0 && native.address and 0xFFFF >= 0x8000) { "Invalid native enemy identity $name" }
            val species = native.address and 0xFFFF
            require(headers.put(species, name) == null) { "Ambiguous native enemy identity ${species.toString(16)}" }
        }
        return headers
    }

    /** Bundled templates use contract names. Only first insertion binds them to the target project. */
    fun template(text: String): String {
        if (names.renamed.isEmpty()) return text
        val declarations = asmGlobalDeclarations(text).keys
        val reserved = names.renamed.keys + names.renamed.values
        require(declarations.none { declaration -> reserved.any { it == declaration || it.startsWith(declaration + "_") } }) {
            "A module declaration collides with a native symbol mapping"
        }
        require(text.lineSequence().none { line ->
            asmBoundParameterNames(line).any { it in names.renamed }
        }) { "A module parameter collides with a native symbol mapping" }
        require(text.lineSequence().none { asmScopedTemplate.containsMatchIn(asmExecutableCode(it)) }) {
            "Namespaced or structured templates require explicit scoped bindings"
        }
        return buildString {
            var start = 0
            text.forEachIndexed { index, character ->
                if (character == '\n') {
                    append(names.activeCode(text.substring(start, index)))
                    append('\n')
                    start = index + 1
                }
            }
            append(names.activeCode(text.substring(start)))
        }
    }

    companion object {
        fun current() = AsmSourceBindings(nativeSymbols = AsmNativeSymbolManifest.load(AsmReferenceContract.COMMIT))
        fun load(original: File): AsmSourceBindings = AsmSourceBaseline.forOriginalDirectory(original)?.bindings ?: current()
    }
}

/** Engineering-generated compiler identities. Files are revision-addressed so old baselines stay meaningful. */
internal object AsmNativeSymbolManifest {
    private val manifests = object : LinkedHashMap<String, List<AsmNativeSymbol>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AsmNativeSymbol>>?) = size > 4
    }
    @Synchronized fun load(revision: String): List<AsmNativeSymbol> = manifests.getOrPut(revision) {
        require(revision.matches(Regex("[a-f0-9]{40}"))) { "Invalid native-symbol source revision" }
        val stream = javaClass.getResourceAsStream("/asm-reference/native-symbols/$revision.tsv")
            ?: return@getOrPut emptyList()
        val lines = stream.bufferedReader().use { it.readLines() }
        require(lines.firstOrNull() == "# sm_disassembly.commit=$revision") { "Native symbol manifest revision mismatch" }
        lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 4 && ASM_GLOBAL_IDENTIFIER.matches(fields[1])) { "Invalid native symbol record" }
            AsmNativeSymbol(fields[0], fields[1], fields[2].toInt(16), fields[3].split(',').filter(String::isNotEmpty))
        }.also { symbols ->
            require(symbols.isNotEmpty()) { "Empty native symbol inventory" }
            validateAsmNativeSymbols(symbols)
        }
    }
}

internal val ASM_GLOBAL_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val asmSourceFileId = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.asm", RegexOption.IGNORE_CASE)
internal fun isAsmSourceFileId(fileId: String) = asmSourceFileId.matches(fileId) && fileId.split('/').none { it == "." || it == ".." }
internal fun validateAsmNativeSymbols(symbols: List<AsmNativeSymbol>) {
    require(symbols.all { native -> isAsmSourceFileId(native.fileId) && ASM_GLOBAL_IDENTIFIER.matches(native.name) &&
        native.address in 0..0xFFFFFF && native.generatedMembers.all { ASM_GLOBAL_IDENTIFIER.matches(it) } &&
        native.generatedMembers.distinct().size == native.generatedMembers.size
    } && symbols.map { it.fileId to it.name }.distinct().size == symbols.size) { "Invalid native symbol inventory" }
}
private val asmGlobalDeclaration = Regex("(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_]*):")
private val asmScopedTemplate = Regex("^\\s*(?:namespace|struct)\\b", RegexOption.IGNORE_CASE)
internal fun asmGlobalDeclarations(text: String): Map<String, List<IntRange>> =
    asmGlobalDeclaration.findAll(text).groupBy({ it.groupValues[1] }, { it.groups[1]!!.range })

/** Span edits preserve literals, comments, whitespace and line endings. Never use substring replacement. */
private fun rewriteAsmIdentifiers(line: String, name: (String) -> String): String {
    var text = line
    asmIdentifierTokens(asmExecutableCode(line)).asReversed().forEach { (range, token) ->
        val replacement = name(token)
        if (replacement != token) text = text.replaceRange(range, replacement)
    }
    return text
}
