package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsmSourceDocumentationResourcesTest {
    private fun manifest(banks: String = "\"bank_90.json\"") = """
        {
          "schemaVersion": 1,
          "revision": "super-metroid-ntsc-57",
          "updatedAt": "2026-11-07",
          "upstreamRevision": "11c906f547edc1b57f5a5923cf977fe7b50a3694",
          "upstreamSourceSha256": "949d0de18cb91a6292c691eb6888645054528fe623f95d904e768b1841494950",
          "banks": [$banks]
        }
    """.trimIndent()

    private fun bank(documentation: String, lineDocumentation: String = "") = """
        {
          "fileId": "bank_90.asm",
          "documentation": [$documentation],
          "lineDocumentation": [$lineDocumentation]
        }
    """.trimIndent()

    private val block = """
        {
          "id": "test.block",
          "symbol": "TestBlock",
          "expectedCode": "RTS",
          "paragraphs": ["Test block."]
        }
    """.trimIndent()

    private val lines = """
        {
          "id": "test.lines",
          "symbol": "TestLines",
          "lines": [{"expectedCode": "RTS", "comment": "Return."}]
        }
    """.trimIndent()

    @Test fun `current bank resources preserve all documentation and inject their file identities`() {
        val recipe = AsmSourceAugmentation.bundled()
        val banks = setOf("bank_80.asm", "bank_81.asm", "bank_82.asm", "bank_84.asm", "bank_85.asm",
            "bank_8F.asm", "bank_90.asm", "bank_91.asm", "bank_93.asm", "bank_9B.asm", "bank_A0.asm", "bank_A3.asm")

        assertEquals("super-metroid-ntsc-57", recipe.revision)
        assertEquals(455, recipe.documentation.size)
        assertEquals(299, recipe.lineDocumentation.size)
        assertEquals(10_083, recipe.lineDocumentation.sumOf { it.lines.size })
        assertEquals(5_467, recipe.lineDocumentation.sumOf { group -> group.lines.count { it.comment != null } })
        assertEquals(banks, (recipe.documentation.map { it.fileId } + recipe.lineDocumentation.map { it.fileId }).toSet())
        assertTrue(recipe.documentation.all { it.fileId.isNotBlank() })
        assertTrue(recipe.lineDocumentation.all { it.fileId.isNotBlank() })
    }

    @Test fun `manifest loads every listed bank exactly once and bank records inherit fileId`() {
        val resources = mapOf("manifest.json" to manifest(), "bank_90.json" to bank(block, lines))
        val requested = mutableListOf<String>()
        val recipe = AsmSourceAugmentation.loadDocumentation { name -> requested += name; resources.getValue(name) }

        assertEquals(listOf("manifest.json", "bank_90.json"), requested)
        assertEquals("bank_90.asm", recipe.documentation.single().fileId)
        assertEquals("bank_90.asm", recipe.lineDocumentation.single().fileId)
    }

    @Test fun `manifest rejects a bank listed more than once`() {
        assertFailsWith<IllegalArgumentException> {
            AsmSourceAugmentation.loadDocumentation { name ->
                if (name == "manifest.json") manifest("\"bank_90.json\", \"bank_90.json\"") else bank(block, lines)
            }
        }
    }

    @Test fun `bank loader rejects duplicate record identities and targets`() {
        val duplicateIdentity = bank(block, lines.replace("test.lines", "test.block"))
        val duplicateBlockTarget = bank("$block, ${block.replace("test.block", "test.other")}")
        val duplicateLineTarget = bank(block,
            "$lines, ${lines.replace("test.lines", "test.other-lines")}")

        for (invalidBank in listOf(duplicateIdentity, duplicateBlockTarget, duplicateLineTarget)) {
            assertFailsWith<IllegalArgumentException> {
                AsmSourceAugmentation.loadDocumentation { name ->
                    if (name == "manifest.json") manifest() else invalidBank
                }
            }
        }
    }
}
