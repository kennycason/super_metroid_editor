package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SourceScrollResource
import com.supermetroid.editor.rom.validateScrollCommands

/** Bounded byte-stream lens. Unsupported expressions stay in ASM, never guessed by the GUI. */
internal object AsmScrollCommandSource {
    private val reference = Regex("(SmeditObjects_[A-Za-z0-9_]+_Scroll_[0-9]+)(?:\\s*&\\s*\\${'$'}FFFF)?", RegexOption.IGNORE_CASE)
    fun reference(token: String): String? = reference.matchEntire(token)?.groupValues?.get(1)

    fun fromText(text: String, name: String, screenCount: Int): SourceScrollResource {
        return parse(name, authoredMacroBody(text, name), screenCount)
    }

    fun parse(name: String, body: String, screenCount: Int): SourceScrollResource {
        val bytes = body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            require(line.startsWith("db ", ignoreCase = true)) { "$name: expected db screen, value and db \$80 terminator." }
            line.drop(3).split(',').map { token ->
                val value = AsmPlmDocument.literal(token.trim())
                require(value != null && value in 0..255) { "$name: unsupported scroll byte '${token.trim()}'." }
                value
            }
        }.toList()
        require(bytes.isNotEmpty() && bytes.last() == 0x80 && bytes.size % 2 == 1) { "$name: missing or misplaced db \$80 terminator." }
        val commands = bytes.dropLast(1).chunked(2).map { ScrollCommand(it[0], it[1]) }
        validateScrollCommands(commands, screenCount)
        return SourceScrollResource(name, commands)
    }

    fun render(name: String, commands: List<ScrollCommand>, width: Int, height: Int): String {
        validateScrollCommands(commands, width * height)
        return buildString {
            appendLine("macro $name()")
            commands.forEach { cmd ->
                appendLine("    db ${hex(cmd.screenIndex)}, ${hex(cmd.scrollValue)} ; ${RomParser.formatScrollCommand(cmd.screenIndex, cmd.scrollValue, width)}")
            }
            appendLine("    db \$80 ; End of scroll commands")
            appendLine("endmacro")
        }
    }

    /** Known original streams retain their own source. Unmapped hack pointers use the loaded ROM. */
    fun original(text: String, symbols: Map<String, Int>, pointer: Int, parser: RomParser, screenCount: Int,
                 baselineText: String? = null): List<ScrollCommand> {
        val name = symbols.entries.firstOrNull { it.value == pointer && it.key.startsWith("RoomPLM_") }?.key
        if (name != null) {
            val current = declaration(text, name, screenCount)
            if (baselineText != null) {
                val baseline = declaration(baselineText, name, screenCount)
                require(current.size == baseline.size) { "$name: original stream resizing needs relocatable source; use Edit commands to make a named resource." }
                val loaded = romCommands(pointer, parser, screenCount)
                if (loaded != baseline) {
                    require(current == baseline) { "$name: both the loaded ROM and ASM command source differ; resolve ownership before editing." }
                    return loaded // A hack's data is not replaced with vanilla bytes just because its pointer matches.
                }
            }
            return current
        }
        return romCommands(pointer, parser, screenCount)
    }

    fun verifiedOriginalName(text: String, symbols: Map<String, Int>, pointer: Int, parser: RomParser, screenCount: Int): String? {
        val name = symbols.entries.firstOrNull { it.value == pointer && it.key.startsWith("RoomPLM_") }?.key ?: return null
        return name.takeIf { declaration(text, name, screenCount) == romCommands(pointer, parser, screenCount) }
    }

    private fun declaration(text: String, name: String, screenCount: Int): List<ScrollCommand> {
        val matches = Regex("(?m)^${Regex.escape(name)}:[^\\r\\n]*\\r?\\n").findAll(text).toList()
        require(matches.size == 1) { "Original scroll source $name changed; its declaration needs review." }
        val tail = text.substring(matches.single().range.last + 1)
        val body = tail.lineSequence().takeWhile { line ->
            val code = line.substringBefore(';').trim()
            code.isEmpty() || code.startsWith("db ", true)
        }.joinToString("\n")
        return parse(name, body, screenCount).commands
    }

    private fun romCommands(pointer: Int, parser: RomParser, screenCount: Int): List<ScrollCommand> {
        require(pointer in 0x8000..0xFFFF) { "Scroll command pointer is outside bank \$8F ROM space." }
        var pc = parser.snesToPc(0x8F0000 or pointer)
        val rom = parser.getRomData()
        val bankEnd = parser.snesToPc(0x8FFFFF) + 1
        val commands = mutableListOf<ScrollCommand>()
        while (pc < bankEnd && pc < rom.size) {
            val screen = rom[pc++].toInt() and 255
            if (screen >= 0x80) {
                validateScrollCommands(commands, screenCount)
                return commands
            }
            require(pc < bankEnd && pc < rom.size) { "Truncated scroll command stream." }
            commands += ScrollCommand(screen, rom[pc++].toInt() and 255)
            require(commands.size <= 128) { "Unterminated scroll command stream." }
        }
        error("Unterminated scroll command stream.")
    }

    private fun hex(value: Int) = "\$${value.toString(16).uppercase().padStart(2, '0')}"
}
