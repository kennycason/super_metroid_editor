package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmPlmDrawEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("plm-draw.") }
    private fun recipe() = current.copy(revision = "plm-draw-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("plm-draw.") }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" +
            group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } } + """

DrawPLM_HardCoded:
    REP #${'$'}30

DrawPLM:
    LDA.W PLM_XBlock
  .horizontal:
    AND.W #${'$'}7FFF
  .loopHorizBlock:
    LDA.B (DP_Temp03)
  .vertical:
    AND.W #${'$'}7FFF
  .nextDrawEntry:
    PLX
"""
    }
    private fun source(texts: Map<String, String>): File =
        File(temporary, "src-${temporary.list().orEmpty().size}").apply {
            mkdirs()
            File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
            texts.forEach { (file, text) -> File(this, file).writeText(text) }
        }

    @Test fun `v10 adds PLM drawing and VRAM notes without changing the exact frozen v9 recipe`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-9.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-10", current.revision)
        assertEquals(previous, current.copy(revision = previous.revision,
            documentation = current.documentation.filterNot { it.id.startsWith("plm-draw.") },
            lineDocumentation = current.lineDocumentation.filterNot { it.id.startsWith("plm-draw.") }))
        assertEquals(10, current.documentation.count { it.id.startsWith("plm-draw.") })
        assertEquals(3, groups.size)
        assertEquals(61, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(146, groups.sumOf { it.lines.size })
        val prose = current.documentation.filter { it.id.startsWith("plm-draw.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all draw scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("BMI .column", "BMI .column ; Select column run")
                .replace("LDA.B #${'$'}7E", "LDA.B #${'$'}7E" + if (addresses) " ;849240;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.getValue("bank_84.asm")
            assertContains(output, "; Select column run | Bit 15 set selects a vertical run.")
            assertContains(output, if (addresses) ";849240; Both staging sources" else "; Both staging sources")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
            current.documentation.filter { it.id.startsWith("plm-draw.") }.flatMap { it.paragraphs }
                .forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed run grammar signed offsets screen math metatile masks and VRAM staging`() {
        val original = sources()
        for ((old, changed) in listOf(
            "BMI .column" to "BPL .column",
            "ORA.W #${'$'}FF00" to "ORA.W #${'$'}FE00",
            "AND.W #${'$'}001F" to "AND.W #${'$'}000F",
            "ADC.B VRAMWrite.size,X" to "ADC.B VRAMWrite[1].size,X",
            "REP #${'$'}30" to "REP #${'$'}20",
            "LDA.B (DP_Temp03)" to "LDA.B [DP_Temp03]",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().apply(root) }
        }
    }

    @Test fun `documented draw arithmetic covers signed offsets runs flips and staging limits`() {
        fun signedByte(byte: Int) = if (byte and 0x80 == 0) byte else byte - 0x100
        fun relativeDestination(origin: Int, width: Int, xByte: Int, yByte: Int): Int =
            origin + signedByte(xByte) * 2 + signedByte(yByte) * width * 2
        fun runDestinations(origin: Int, width: Int, count: Int, vertical: Boolean): List<Int> =
            List(count) { block -> origin + block * if (vertical) width * 2 else 2 }
        for (byte in 0..0xFF) assertEquals(if (byte < 0x80) byte else byte - 0x100, signedByte(byte))
        assertEquals((-128..127).toList(), (0x80..0xFF).map(::signedByte) + (0..0x7F).map(::signedByte))

        for (width in listOf(1, 16, 63, 255)) {
            val origin = 0x4000
            for (count in listOf(1, 2, 16)) {
                assertEquals(List(count) { origin + it * 2 }, runDestinations(origin, width, count, vertical = false))
                assertEquals(List(count) { origin + it * width * 2 }, runDestinations(origin, width, count, vertical = true))
            }
            for (xByte in 0..0xFF step 17) for (yByte in 0..0xFF step 17) {
                val expected = origin + (signedByte(yByte) * width + signedByte(xByte)) * 2
                assertEquals(expected, relativeDestination(origin, width, xByte, yByte))
            }
        }

        data class Tile(val quadrant: String, val xorMask: Int)
        val source = listOf("TL", "TR", "BL", "BR")
        fun expand(flip: Int): List<Tile> {
            val horizontal = flip and 0x0400 != 0
            val vertical = flip and 0x0800 != 0
            val mask = (if (horizontal) 0x4000 else 0) or (if (vertical) 0x8000 else 0)
            return (0..1).flatMap { outputY -> (0..1).map { outputX ->
                val sourceX = if (horizontal) 1 - outputX else outputX
                val sourceY = if (vertical) 1 - outputY else outputY
                Tile(source[sourceY * 2 + sourceX], mask)
            } }
        }
        val permutations = mapOf(
            0x0000 to listOf("TL", "TR", "BL", "BR"),
            0x0400 to listOf("TR", "TL", "BR", "BL"),
            0x0800 to listOf("BL", "BR", "TL", "TR"),
            0x0C00 to listOf("BR", "BL", "TR", "TL"),
        )
        for ((flip, quadrants) in permutations) {
            val mask = ((if (flip and 0x0400 != 0) 0x4000 else 0) or
                (if (flip and 0x0800 != 0) 0x8000 else 0))
            assertEquals(quadrants.map { Tile(it, mask) }, expand(flip))
        }
        for (blocks in 0..128) {
            assertEquals(blocks * 8, blocks * 4 * 2, "Two staged tile rows or columns per metatile")
            assertEquals(blocks * 4, blocks * 2 * 2, "Two word tiles per VRAM record")
            assertEquals(blocks <= 64, blocks * 8 <= 0x200)
        }
    }
}
