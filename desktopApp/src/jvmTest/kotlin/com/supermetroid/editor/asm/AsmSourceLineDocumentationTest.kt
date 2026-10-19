package com.supermetroid.editor.asm

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmSourceLineDocumentationTest {
    @TempDir lateinit var directory: File

    private fun source(text: String): File {
        val root = File(directory, "src").apply { mkdirs() }
        File(root, "main.asm").writeText("incsrc bank_A3.asm\n")
        File(root, "bank_A3.asm").writeText(text)
        return root
    }

    private fun group(vararg lines: Pair<String, String?>) = AsmSourceLineDocumentation(
        "crawler.lines", "bank_A3.asm", "CrawlerAI",
        lines.map { (code, comment) -> AsmSourceLineNote(code, comment) },
    )

    private fun recipe(note: AsmSourceLineDocumentation) = AsmSourceAugmentation("test", lineDocumentation = listOf(note))

    @Test fun `append useful descriptions after address tags without changing existing bytes or line origins`() {
        val original = "CrawlerAI:\n    LDX.W EnemyIndex ;A3E669;\n    RTL ;A3E66C;\n"
        val root = source(original)
        val result = recipe(group("LDX.W EnemyIndex" to "Select this enemy slot.", "RTL" to "Finish initialisation.")).apply(root)
        assertEquals("CrawlerAI:\n    LDX.W EnemyIndex ;A3E669; Select this enemy slot.\n    RTL ;A3E66C; Finish initialisation.\n",
            result.texts["bank_A3.asm"])
        assertTrue(result.upstreamLines.isEmpty(), "Inline comments do not insert or renumber source lines")
        assertEquals(original, File(root, "bank_A3.asm").readText())
        assertEquals(AsmVerifiedCache.hash(original), result.upstreamSourceHashes["bank_A3.asm"])
    }

    @Test fun `existing meaningful comments remain verbatim before additional explanation`() {
        val root = source("CrawlerAI:\n STA.W Enemy.instList,X ;A3E677; fallthrough to Common\n")
        val result = recipe(group("STA.W Enemy.instList,X" to "Install the orientation script.")).apply(root)
        assertContains(result.texts.getValue("bank_A3.asm"),
            ";A3E677; fallthrough to Common | Install the orientation script.")
    }

    @Test fun `missing tags empty comments and quoted semicolons use ordinary comment syntax`() {
        val root = source("CrawlerAI:\n LDA #1\n STA \$12   \n db \"literal;A3E669;\"\n RTL ; \n")
        val note = group("LDA #1" to "Load one.", "STA \$12" to "Save the value.",
            "db \"literal;A3E669;\"" to "Keep literal bytes.", "RTL" to "Return.")
        val output = recipe(note).apply(root).texts.getValue("bank_A3.asm")
        assertEquals("CrawlerAI:\n LDA #1 ; Load one.\n STA \$12   ; Save the value.\n db \"literal;A3E669;\" ; Keep literal bytes.\n RTL ;  Return.\n", output)
    }

    @Test fun `CRLF indentation case of tags and absent final newline are preserved`() {
        val original = "CrawlerAI:\r\n\tLDA.W   \$0001 ;a3e669;\r\n\tRTL"
        val output = recipe(group("LDA.W \$0001" to "Read the word.", "RTL" to "Return."))
            .apply(source(original)).texts.getValue("bank_A3.asm")
        assertEquals("CrawlerAI:\r\n\tLDA.W   \$0001 ;a3e669; Read the word.\r\n\tRTL ; Return.", output)
        assertFalse(output.endsWith('\n'))
    }

    @Test fun `scope guards include nested and anonymous labels and do not cross into the next global`() {
        val root = source("CrawlerAI:\n LDA #1\n .turn:\n LDA #1\n ..done:\n+ RTL\nOther:\n LDA #1\n RTL\n")
        val note = group("LDA #1" to "First load.", ".turn:" to null, "LDA #1" to "Load after turning.",
            "..done:" to null, "+ RTL" to "Return after the anonymous join.")
        val output = recipe(note).apply(root).texts.getValue("bank_A3.asm")
        assertContains(output, " .turn:\n LDA #1 ; Load after turning.")
        assertTrue(output.endsWith("Other:\n LDA #1\n RTL\n"))
    }

    @Test fun `changed added removed and reordered scoped code fail closed`() {
        val note = group("LDA #1" to "Load one.", ".turn:" to null, "RTL" to "Return.")
        for (body in listOf("LDA #2\n .turn:\n RTL", "LDA #1\n NOP\n .turn:\n RTL",
            "LDA #1\n .turn:", ".turn:\n LDA #1\n RTL", "LDA #1\nOther:\n .turn:\n RTL")) {
            val original = "CrawlerAI:\n $body\n"
            val root = source(original)
            assertFailsWith<IllegalArgumentException>(body) { recipe(note).apply(root) }
            assertEquals(original, File(root, "bank_A3.asm").readText())
        }
    }

    @Test fun `moved branch labels cannot attach explanations to an identical opcode elsewhere`() {
        val root = source("CrawlerAI:\n .second:\n LDA #1\n .first:\n LDA #1\n")
        val note = group(".first:" to null, "LDA #1" to "First path.", ".second:" to null, "LDA #1" to "Second path.")
        assertFailsWith<IllegalArgumentException> { recipe(note).apply(root) }
    }

    @Test fun `guard comparison preserves significant whitespace inside literals`() {
        val root = source("CrawlerAI:\n db \"ab\"\n")
        assertFailsWith<IllegalArgumentException> {
            recipe(group("db \"a b\"" to "Two letters separated by a space.")).apply(root)
        }
    }

    @Test fun `missing ambiguous and wrong file roots are rejected`() {
        val root = source("Other:\n RTL\n")
        val note = group("RTL" to "Return.")
        assertFailsWith<IllegalStateException> { recipe(note).apply(root) }
        source("CrawlerAI:\n RTL\nCrawlerAI:\n RTL\n")
        assertFailsWith<IllegalStateException> { recipe(note).apply(root) }
        source("CrawlerAI:\n RTL\n")
        assertFailsWith<IllegalStateException> { recipe(note.copy(fileId = "missing.asm")).apply(root) }
    }

    @Test fun `flattened local aliases inline declarations and macro bodies are not ordinary global scopes`() {
        val root = source("CrawlerAI:\n .part:\n RTL\n")
        assertFailsWith<IllegalArgumentException> { recipe(group("RTL" to "Return.").copy(symbol = "CrawlerAI_part")).apply(root) }
        source("CrawlerAI: NOP\n RTL\n")
        assertFailsWith<IllegalArgumentException> { recipe(group("RTL" to "Return.")).apply(root) }
        source("macro emit()\nCrawlerAI:\n RTL\nendmacro\n")
        assertFailsWith<IllegalStateException> { recipe(group("RTL" to "Return.")).apply(root) }
    }

    @Test fun `duplicate identities and duplicate scoped targets cannot publish`() {
        val root = source("CrawlerAI:\n RTL\n")
        val note = group("RTL" to "Return.")
        assertFailsWith<IllegalArgumentException> { recipe(note).copy(lineDocumentation = listOf(note, note)).apply(root) }
        assertFailsWith<IllegalArgumentException> {
            recipe(note).copy(lineDocumentation = listOf(note, note.copy(id = "other"))).apply(root)
        }
        assertFailsWith<IllegalArgumentException> {
            recipe(note).copy(documentation = listOf(AsmSourceDocumentation(note.id, note.fileId, note.symbol, "RTL", listOf("Return.")))).apply(root)
        }
    }

    @Test fun `empty records and injected newlines or comments in expected code are rejected`() {
        val root = source("CrawlerAI:\n RTL\n")
        val invalid = listOf(
            group(), group("RTL" to null), group("RTL" to ""), group("RTL" to " "),
            group("RTL" to "Return.\norg \$808000"), group("RTL" to "Return.\rNOP"),
            group("RTL\nNOP" to "Return."), group("RTL ; comment" to "Return."),
        )
        invalid.forEach { note -> assertFailsWith<IllegalArgumentException> { recipe(note).apply(root) } }
    }

    @Test fun `compatibility continuation and unterminated literal lines are not comment targets`() {
        for ((code, expected) in listOf(";@ NOP" to "NOP", "db 1,\\" to "db 1,\\", "db \"open" to "db \"open")) {
            val root = source("CrawlerAI:\n $code\n")
            assertFailsWith<IllegalArgumentException>(code) { recipe(group(expected to "An explanation.")).apply(root) }
        }
    }

    @Test fun `line notes compose with block notes and renames from the same immutable image`() {
        val original = "CrawlerAI:\n LDA #CrawlerAI\n .again:\n BRA .again\n RTL\n"
        val root = source(original)
        val note = group("LDA #CrawlerAI" to "CrawlerAI is an address, not a state number.", ".again:" to null,
            "BRA .again" to "Repeat this local path.", "RTL" to null)
        val result = recipe(note).copy(
            documentation = listOf(AsmSourceDocumentation("block", note.fileId, note.symbol, "LDA #CrawlerAI", listOf("Dispatch state."))),
            renames = listOf(AsmSourceRename("CrawlerAI", "MovementAI")),
        ).apply(root)
        assertEquals("; Dispatch state.\nMovementAI:\n LDA #MovementAI ; CrawlerAI is an address, not a state number.\n .again:\n BRA .again ; Repeat this local path.\n RTL\n",
            result.texts[note.fileId])
        assertEquals(listOf(-1, 0, 1, 2, 3, 4, 5), result.upstreamLines[note.fileId])
        assertEquals(original, File(root, note.fileId).readText())
    }

    @Test fun `a late invalid group does not publish earlier valid insertions`() {
        val original = "CrawlerAI:\n RTL\nOther:\n NOP\n"
        val root = source(original)
        val first = group("RTL" to "Return.")
        val second = first.copy(id = "other", symbol = "Other", lines = listOf(AsmSourceLineNote("RTL", "Return too.")))
        assertFailsWith<IllegalArgumentException> { recipe(first).copy(lineDocumentation = listOf(first, second)).apply(root) }
        assertEquals(original, File(root, "bank_A3.asm").readText())
    }

    @Test fun `new recipe records round trip while old recipes default to no inline documentation`() {
        val note = group("RTL" to "Return.")
        val value = recipe(note)
        assertEquals(value, Json.decodeFromString(AsmSourceAugmentation.serializer(),
            Json.encodeToString(AsmSourceAugmentation.serializer(), value)))
        assertTrue(Json.decodeFromString(AsmSourceAugmentation.serializer(), """{"revision":"old"}""").lineDocumentation.isEmpty())
    }
}
