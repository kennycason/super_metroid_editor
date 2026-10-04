package com.supermetroid.editor.rom

import java.io.File

data class WlaSymbol(
    val name: String,
    val snesAddress: Int,
) {
    val bank: Int get() = (snesAddress ushr 16) and 0xFF
    val offset: Int get() = snesAddress and 0xFFFF
    val formattedAddress: String get() = "%02X:%04X".format(bank, offset)
}

/** Strict reader for the `[labels]` section emitted by Asar's WLA symbol format. */
class WlaSymbolCatalog private constructor(
    val symbols: List<WlaSymbol>,
) {
    private val byName = symbols.groupBy(WlaSymbol::name)
    private val byAddress = symbols.groupBy(WlaSymbol::snesAddress)

    fun named(name: String): List<WlaSymbol> = byName[name].orEmpty()

    fun requireUnique(name: String): WlaSymbol {
        val matches = named(name)
        check(matches.size == 1) {
            "Expected exactly one source symbol named $name, found ${matches.size}"
        }
        return matches.single()
    }

    fun at(snesAddress: Int): List<WlaSymbol> = byAddress[snesAddress].orEmpty()

    companion object {
        private val labelPattern = Regex("^([0-9A-Fa-f]{2}):([0-9A-Fa-f]{4})\\s+(.+?)\\s*$")

        fun read(file: File): WlaSymbolCatalog = parse(file.readLines(), file.absolutePath)

        internal fun parse(lines: List<String>, source: String = "<memory>"): WlaSymbolCatalog {
            var inLabels = false
            val symbols = mutableListOf<WlaSymbol>()
            for ((index, rawLine) in lines.withIndex()) {
                val line = rawLine.trim()
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (inLabels) break
                    inLabels = line == "[labels]"
                    continue
                }
                if (!inLabels || line.isEmpty() || line.startsWith(";")) continue
                val match = labelPattern.matchEntire(line)
                    ?: error("Malformed WLA label at $source:${index + 1}: $rawLine")
                val (bank, offset, name) = match.destructured
                symbols += WlaSymbol(
                    name = name,
                    snesAddress = (bank.toInt(16) shl 16) or offset.toInt(16),
                )
            }
            check(symbols.isNotEmpty()) { "No [labels] entries found in $source" }
            return WlaSymbolCatalog(symbols)
        }
    }
}
