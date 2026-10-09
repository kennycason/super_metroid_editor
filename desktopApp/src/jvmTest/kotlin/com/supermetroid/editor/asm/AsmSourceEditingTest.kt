package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertEquals

class AsmSourceEditingTest {
    private val sources = listOf(
        AsmSourceText(
            fileId = "bank_90.asm",
            displayName = "Bank 90 · Samus",
            text = "JumpSpeed: dw !SPF\n    LDA.W !SPF ; !SPF scales physics\n",
        ),
        AsmSourceText(
            fileId = "bank_91.asm",
            displayName = "Bank 91 · Animation",
            text = "    lda.w !spf\n",
        ),
    )

    @Test
    fun `literal search reports every occurrence with source coordinates`() {
        val matches = findAsmSourceText(sources, "!SPF")

        assertEquals(4, matches.size)
        assertEquals("bank_90.asm", matches.first().fileId)
        assertEquals(0, matches.first().lineIndex)
        assertEquals(14, matches.first().columnIndex)
        assertEquals("bank_91.asm", matches.last().fileId)
    }

    @Test
    fun `case sensitive search excludes differently cased source`() {
        val matches = findAsmSourceText(sources, "!SPF", caseSensitive = true)

        assertEquals(3, matches.size)
        assertEquals(setOf("bank_90.asm"), matches.mapTo(mutableSetOf(), AsmSourceTextMatch::fileId))
    }

    @Test
    fun `replacement treats Asar punctuation and dollar signs literally`() {
        val (updated, count) = replaceAsmSourceText(
            text = "dw !SPF, !spf ; keep \$ literal",
            query = "!SPF",
            replacement = "!PAL_\$1",
            caseSensitive = false,
        )

        assertEquals(2, count)
        assertEquals("dw !PAL_\$1, !PAL_\$1 ; keep \$ literal", updated)
    }

    @Test
    fun `preview counts occurrences and affected files`() {
        val preview = previewAsmSourceReplacement(sources, "!SPF", "!SPEED", caseSensitive = false)

        assertEquals(4, preview.occurrenceCount)
        assertEquals(2, preview.fileCount)
    }

    @Test
    fun `preview and replacement exclude literal no-op matches`() {
        val preview = previewAsmSourceReplacement(sources, "!SPF", "!SPF", caseSensitive = false)
        val (updated, count) = replaceAsmSourceText("!SPF !spf", "!SPF", "!SPF", caseSensitive = false)

        assertEquals(1, preview.matches.count { it.fileId == "bank_91.asm" })
        assertEquals("!SPF !SPF", updated)
        assertEquals(1, count)
    }
}
