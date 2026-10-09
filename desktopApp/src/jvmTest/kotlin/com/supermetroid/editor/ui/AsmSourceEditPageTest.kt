package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AsmSourceEditPageTest {
    @Test
    fun `page isolates bounded lines and merges edits into the complete source`() {
        val source = (1..1_205).joinToString("\n") { "line $it" }

        val page = asmSourceEditPage(source, requestedStartLine = 500, maxLines = 500)

        assertEquals(500, page.startLine)
        assertEquals(500, page.lineCount)
        assertEquals(1_205, page.totalLineCount)
        assertEquals("line 501", page.text.lineSequence().first())
        assertEquals("line 1000", page.text.lineSequence().last())

        val updated = page.text.replaceFirst("line 501", "line 501 ; edited")
        val merged = page.merge(updated)
        assertEquals("line 500", merged.lineSequence().elementAt(499))
        assertEquals("line 501 ; edited", merged.lineSequence().elementAt(500))
        assertEquals("line 1001", merged.lineSequence().elementAt(1_000))
        assertEquals("line 1205", merged.lineSequence().last())
    }

    @Test
    fun `last page preserves a trailing newline and accepts inserted lines`() {
        val source = "one\ntwo\nthree\n"
        val page = asmSourceEditPage(source, requestedStartLine = 2, maxLines = 500)

        assertEquals("three\n", page.text)
        assertEquals("one\ntwo\nthree\ninserted\n", page.merge(page.text + "inserted\n"))
    }

    @Test
    fun `page size must be positive`() {
        assertFailsWith<IllegalArgumentException> { asmSourceEditPage("source", 0, 0) }
    }
}
