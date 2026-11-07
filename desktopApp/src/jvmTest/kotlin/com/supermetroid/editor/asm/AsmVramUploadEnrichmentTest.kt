package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmVramUploadEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("vram-upload.") }
    private fun recipe() = current.copy(
        revision = "vram-upload-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("vram-upload.") },
        lineDocumentation = groups,
    )
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" +
            group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File =
        File(temporary, "src-${temporary.list().orEmpty().size}").apply {
            mkdirs()
            File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
            texts.forEach { (file, text) -> File(this, file).writeText(text) }
        }

    @Test fun `frozen v11 adds VRAM consumer notes without changing the exact frozen v10 recipe`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-10.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v11 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-11.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-11", v11.revision)
        assertEquals(previous, v11.copy(
            revision = previous.revision,
            documentation = v11.documentation.filterNot { it.id.startsWith("vram-upload.") },
            lineDocumentation = v11.lineDocumentation.filterNot { it.id.startsWith("vram-upload.") },
        ))
        assertEquals(v11.documentation.filter { it.id.startsWith("vram-upload.") },
            current.documentation.filter { it.id.startsWith("vram-upload.") })
        assertEquals(v11.lineDocumentation.filter { it.id.startsWith("vram-upload.") }, groups)
        assertEquals(8, v11.documentation.count { it.id.startsWith("vram-upload.") })
        assertEquals(3, v11.lineDocumentation.count { it.id.startsWith("vram-upload.") })
        assertEquals(95, v11.lineDocumentation.filter { it.id.startsWith("vram-upload.") }
            .flatMap { it.lines }.count { it.comment != null })
        assertEquals(219, v11.lineDocumentation.filter { it.id.startsWith("vram-upload.") }.sumOf { it.lines.size })
        val prose = v11.documentation.filter { it.id.startsWith("vram-upload.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all VRAM upload scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W ${'$'}420B", "STA.W ${'$'}420B ; Trigger DMA")
                .replace("LDA.B #${'$'}81", "LDA.B #${'$'}81" + if (addresses) " ;808CD8;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.getValue("bank_80.asm")
            assertContains(output, "; Trigger DMA | Run this record's transfer synchronously.")
            assertContains(output, if (addresses) ";808CD8; Increment VMADD" else "; Increment VMADD")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
            current.documentation.filter { it.id.startsWith("vram-upload.") }.flatMap { it.paragraphs }
                .forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed record stride DMA mode wrap geometry and forced status branch`() {
        val original = sources()
        for ((old, changed) in listOf(
            "ADC.W #${'$'}0007" to "ADC.W #${'$'}0008",
            "BPL .skip" to "BMI .skip",
            "LDX.W #${'$'}1801" to "LDX.W #${'$'}1800",
            "LDX.W #BG1ColumnUpdateTilemapLeftHalves" to "LDX.W #BG1ColumnUpdateTilemapRightHalves",
            "ORA.W #${'$'}0020" to "ORA.W #${'$'}0040",
            "SEP #${'$'}02" to "REP #${'$'}02",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented record and tilemap DMA arithmetic covers queue and wrap boundaries`() {
        fun recordOffsets(stack: Int) = if (stack == 0) emptyList() else
            generateSequence(0) { (it + 7).takeIf { next -> next < stack } }.toList()
        for (records in 0..73) {
            val stack = records * 7
            assertEquals(records, recordOffsets(stack).size)
            assertEquals(stack, records * (2 + 3 + 2))
        }

        fun vmain(destination: Int) = if (destination and 0x8000 == 0) 0x80 else 0x81
        for (destination in listOf(0x0000, 0x53FF, 0x7FFF, 0x8000, 0xD3FF, 0xFFFF)) {
            assertEquals(destination < 0x8000, vmain(destination) == 0x80)
        }
        assertEquals(0x18, 0x1801 ushr 8, "DMA B-bus target is the VRAM data port")
        assertEquals(0x01, 0x1801 and 0xFF, "DMA mode 1 alternates low and high data ports")

        for (destination in listOf(0x0000, 0x4000, 0x53C0, 0x7F80)) {
            assertEquals(destination + 1, destination.inc(), "Column halves target adjacent VRAM words")
            assertEquals(destination + 0x20, destination or 0x20, "Bottom tile rows select bit 5")
        }
        for (status in 0..0xFF) {
            val afterSep02 = status or 0x02
            assertTrue(afterSep02 and 0x02 != 0, "SEP #${'$'}02 must force the BEQ condition true")
        }
    }
}
