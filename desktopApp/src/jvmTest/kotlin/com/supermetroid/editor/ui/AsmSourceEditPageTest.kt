package com.supermetroid.editor.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

    @Test
    fun `enter inherits opcode indentation`() {
        val previous = TextFieldValue("    LDA.W #\$01", TextRange(14))
        val updated = TextFieldValue("    LDA.W #\$01\n", TextRange(15))

        val result = asmApplySmartIndentation(previous, updated)

        assertEquals("    LDA.W #\$01\n    ", result.text)
        assertEquals(TextRange(19), result.selection)
    }

    @Test
    fun `enter after a label starts an indented instruction`() {
        val previous = TextFieldValue("HandleJump:", TextRange(11))
        val updated = TextFieldValue("HandleJump:\n", TextRange(12))

        val result = asmApplySmartIndentation(previous, updated)

        assertEquals("HandleJump:\n    ", result.text)
        assertEquals(TextRange(16), result.selection)
    }

    @Test
    fun `paste and multi-line edits are never reformatted`() {
        val previous = TextFieldValue("LDA.W #\$01", TextRange(10))
        val pasted = "LDA.W #\$01\nSTA.W \$12\n"
        val updated = TextFieldValue(pasted, TextRange(pasted.length))

        assertEquals(updated, asmApplySmartIndentation(previous, updated))
    }

    @Test
    fun `editable symbols resolve their whole-file line`() {
        val text = "    dw \$0600*!SPF*\$100\n    RTS"
        val offset = text.indexOf("!SPF") + 2

        assertEquals(
            AsmEditableSymbolReference(lineIndex = 500, token = "!SPF"),
            asmEditableSymbolAt(text, offset, pageStartLine = 500),
        )
    }

    @Test
    fun `editable symbols in comments are not navigation targets`() {
        val text = "    RTS ; !SPF is only commentary"

        assertEquals(null, asmEditableSymbolAt(text, text.indexOf("!SPF") + 1, pageStartLine = 0))
    }

    @Test
    fun `caret offset resolves the active source row`() {
        val text = "first\nsecond\nthird"

        assertEquals(0, asmLineIndexAtOffset(text, 0))
        assertEquals(1, asmLineIndexAtOffset(text, text.indexOf("second") + 2))
        assertEquals(2, asmLineIndexAtOffset(text, text.length))
        assertEquals(2, asmColumnIndexAtOffset(text, text.indexOf("second") + 2))
        assertEquals(5, asmColumnIndexAtOffset(text, text.length))
    }

    @Test
    fun `tab advances a caret to the next four column stop`() {
        val value = TextFieldValue("  LDA.W #\$01", TextRange(2))

        val result = asmApplyTabIndentation(value, unindent = false)

        assertEquals("    LDA.W #\$01", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun `tab and shift tab format only selected rows`() {
        val original = "Label:\nLDA.W #\$01\nSTA.W \$12\nRTS"
        val selectionStart = original.indexOf("LDA")
        val selectionEnd = original.indexOf("RTS")
        val selected = TextFieldValue(original, TextRange(selectionStart, selectionEnd))

        val indented = asmApplyTabIndentation(selected, unindent = false)
        assertEquals("Label:\n    LDA.W #\$01\n    STA.W \$12\nRTS", indented.text)

        val restored = asmApplyTabIndentation(indented, unindent = true)
        assertEquals(original, restored.text)
        assertEquals(selected.selection, restored.selection)
    }
}
