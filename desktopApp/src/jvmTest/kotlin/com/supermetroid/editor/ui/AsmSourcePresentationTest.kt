package com.supermetroid.editor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import com.supermetroid.editor.asm.AsmSourceGutterMode
import com.supermetroid.editor.asm.asmCodeBeforeComment
import com.supermetroid.editor.asm.asmExecutableCode
import kotlin.random.Random
import kotlin.test.*

class AsmSourcePresentationTest {
    @Test fun `address annotations disappear without reformatting code or useful comments`() {
        val raw = "  dw CLCRTS_A0C2BC          ;A0C895;  4: Shootable air\n  RTL   ;a3e720;\n"
        val view = AsmSourcePresentation(raw)
        assertEquals("  dw CLCRTS_A0C2BC          ;  4: Shootable air\n  RTL   \n", view.text)
        assertEquals(listOf("A0C895", "A3E720", null), view.originalAddresses)
        assertEquals(raw, view.sourceValue(TextFieldValue(view.text)).text)
    }

    @Test fun `only an exact leading real comment tag is metadata`() {
        val raw = listOf(
            "db \";A0C895;\"", "db \"escaped \\\";A0C895;\" ; ordinary",
            "; See ;A0C895;", ";A0C89;", ";A0C8950;", "; A0C895;", ";G0C895;",
            ";@ LDA #1 ;A0C895;", ";;; \$C895: Air ;;;", "org \$A0C895",
        ).joinToString("\n")
        val view = AsmSourcePresentation(raw)
        assertEquals(raw, view.text)
        assertTrue(view.originalAddresses.all { it == null })
    }

    @Test fun `missing addresses are never inferred from neighbours`() {
        val view = AsmSourcePresentation("LDA #1 ;A08000;\nSTA \$12\nRTL ;A08005;")
        assertEquals(listOf("A08000", null, "A08005"), view.originalAddresses)
    }

    @Test fun `offset maps are monotonic and every visible boundary round trips`() {
        for (raw in listOf("", ";A08000;", "LDA ;A08000;\nRTL ;A08003; comment", ";A08000; comment\n")) {
            val view = AsmSourcePresentation(raw)
            val offsets = (0..raw.length).map(view::displayOffset)
            assertEquals(offsets.sorted(), offsets)
            for (i in 0..view.text.length) assertEquals(i, view.displayOffset(view.sourceOffset(i)), "$raw: $i")
        }
    }

    @Test fun `syntax search styles and symbol links survive projection`() {
        val raw = "  JSL Target ;A08000; useful comment"
        val rich = buildAnnotatedString {
            append(raw)
            addStyle(SpanStyle(color = Color.Red), 2, 5)
            addStyle(SpanStyle(background = Color.Yellow), raw.indexOf("useful"), raw.length)
            addStringAnnotation("symbol", "Target", 6, 12)
            addStringAnnotation("address", "A08000", 14, 20)
        }
        val projected = AsmSourcePresentation(raw).annotated(rich)
        assertEquals("  JSL Target ; useful comment", projected.text)
        assertEquals("Target", projected.getStringAnnotations("symbol", 6, 12).single().item)
        assertTrue(projected.getStringAnnotations("address", 0, projected.length).isEmpty())
        assertTrue(projected.spanStyles.any { it.item.background == Color.Yellow && it.end == projected.length })
    }

    @Test fun `selection composition and reversed ranges use source coordinates`() {
        val raw = "LDA ;A08000;\nTarget ;A08003;"
        val view = AsmSourcePresentation(raw)
        val visible = TextFieldValue(view.text, TextRange(view.text.length, 0), TextRange(5, 11))
        val restored = view.sourceValue(visible)
        val roundTrip = view.displayValue(restored)
        assertEquals(visible.selection, roundTrip.selection)
        assertEquals(visible.composition, roundTrip.composition)
        assertEquals(raw, restored.text)
    }

    @Test fun `editing code and comment preserves annotation spelling whitespace and other lines`() {
        val raw = "LDA #1   ;a08000; first\nSTA \$12 ;A08003;"
        val changed = edit(raw) { it.replace("#1", "#2").replace("first", "Load counter") }
        assertEquals("LDA #2   ;a08000; Load counter\nSTA \$12 ;A08003;", changed.text)
    }

    @Test fun `enter keeps origin on original line and no address on inserted line`() {
        val raw = "LDA #1 ;A08000;\nRTL ;A08003;"
        val view = AsmSourcePresentation(raw)
        val at = view.text.indexOf('\n')
        val result = view.sourceValue(TextFieldValue(view.text.replaceRange(at, at, "\n  "), TextRange(at + 3)))
        assertEquals("LDA #1 ;A08000;\n  \nRTL ;A08003;", result.text)
        assertEquals(listOf("A08000", null, "A08003"), AsmSourcePresentation(result.text).originalAddresses)
    }

    @Test fun `splitting at line start keeps origin with old code not inserted line`() {
        val result = edit("LDA #1 ;A08000;") { "\n$it" }
        assertEquals("\nLDA #1 ;A08000;", result.text)
    }

    @Test fun `join relocates annotation to end so following code is not commented out`() {
        val raw = "LDA #1 ;A08000;\n : STA \$12 ;A08003;"
        val result = edit(raw) { it.replace("\n", "") }
        assertEquals("LDA #1  : STA \$12 ;A08000;", result.text)
        assertTrue(asmCodeBeforeComment(result.text).contains("STA \$12"))
        assertEquals(listOf("A08000"), AsmSourcePresentation(result.text).originalAddresses)
    }

    @Test fun `deleting a complete line discards its annotation and preserves the next`() {
        val raw = "LDA #1 ;A08000;\nSTA \$12 ;A08003;\nRTL ;A08006;"
        val result = edit(raw) { it.substringAfter('\n') }
        assertEquals("STA \$12 ;A08003;\nRTL ;A08006;", result.text)
    }

    @Test fun `delete all removes annotations too`() {
        assertEquals("", edit("LDA #1 ;A08000;\nRTL ;A08003;") { "" }.text)
    }

    @Test fun `multiline indentation preserves each address`() {
        val raw = "LDA #1 ;A08000;\nSTA \$12 ;A08003;\nRTL ;A08006;"
        val result = edit(raw) { it.lines().joinToString("\n") { line -> "    $line" } }
        assertEquals(raw.lines().joinToString("\n") { "    $it" }, result.text)
    }

    @Test fun `new ordinary comments are visible including an empty comment`() {
        assertEquals("LDA ;A08000; useful", edit("LDA ;A08000;") { "$it; useful" }.text)
        val result = edit("LDA ;A08000;") { "$it;" }
        assertEquals("LDA ;", result.text)
        assertEquals("LDA ;", AsmSourcePresentation(result.text).text)
    }

    @Test fun `pasted raw source annotation wins over old origin`() {
        val result = edit("LDA ;A08000;") { "STA ;A08042; new" }
        assertEquals("STA ;A08042; new", result.text)
        assertEquals("STA ; new", AsmSourcePresentation(result.text).text)
    }

    @Test fun `transient unterminated strings and executable compatibility directives take precedence over metadata`() {
        for (visible in listOf("db \"unfinished", ";@ LDA #1", "  ;@ org \$808000", "LDA #1\r;@ NOP")) {
            val result = edit("LDA ;A08000;") { visible }
            assertEquals(visible, result.text)
            assertEquals(visible, AsmSourcePresentation(result.text).text)
            assertEquals(asmExecutableCode(visible), asmExecutableCode(result.text))
        }
    }

    @Test fun `CRLF final newline and unicode survive no op and edits`() {
        val raw = "LDA #1 ;A08000; Pokémon 🦎\r\nRTL ;A08003;\r\n"
        val view = AsmSourcePresentation(raw)
        assertEquals(raw, view.sourceValue(TextFieldValue(view.text)).text)
        assertEquals(raw.replace("#1", "#2"), edit(raw) { it.replace("#1", "#2") }.text)
        assertEquals("RTS ;A08003;\r\n", edit("RTL ;A08003;\r\n") { it.replace("RTL", "RTS") }.text)
    }

    @Test fun `find ignores hidden tags and returns exact raw comment positions`() {
        val raw = "LDA ;A08000; useful\nSTA ;A08003; more useful"
        val view = AsmSourcePresentation(raw)
        assertTrue(view.find("A08000", false).isEmpty())
        val matches = view.find("useful", false)
        assertEquals(2, matches.size)
        matches.forEach { assertEquals("useful", raw.substring(it.offset, it.offset + it.length)) }
        assertEquals(raw.lines()[1].indexOf("useful"), matches[1].column)
        assertEquals(1, matches[1].line)
    }

    @Test fun `random visible edits never inject annotations into executable code or lose visible text`() {
        val random = Random(173)
        repeat(2_000) {
            val raw = listOf(
                "LDA #1 ;A08000;\nSTA \$12 ;A08003;\nRTL ;A08006;",
                "LDA #1 ;A08000; Load counter\nSTA \$12 ;A08003; Store it\nRTL ;A08006;",
                "db \"quoted; text 🦎\" ;A08000;\r\nSTA \$12 ;A08003; comment\r\n",
            )[random.nextInt(3)]
            val view = AsmSourcePresentation(raw)
            val start = random.nextInt(view.text.length + 1)
            val end = random.nextInt(start, view.text.length + 1)
            val insert = listOf("", "x", "\n", "  ", " : NOP", "\nPHA\nPLA\n", "\"", "; note", ";@ NOP")[random.nextInt(9)]
            val visible = view.text.replaceRange(start, end, insert)
            val caret = start + insert.length
            val restored = view.sourceValue(TextFieldValue(visible, TextRange(caret)))
            val after = AsmSourcePresentation(restored.text)
            assertEquals(visible, after.text, "Edit $start..$end -> '$insert'")
            assertEquals(caret, after.displayValue(restored).selection.end)
            assertEquals(visible.lines().map(::asmExecutableCode), restored.text.lines().map(::asmExecutableCode),
                "Raw: '$raw'\nEdit $start..$end -> '$insert'\nVisible: '$visible'\nRestored: '${restored.text}'")
        }
    }

    @Test fun `gutter cycles through three distinct presentation modes`() {
        assertEquals(AsmSourceGutterMode.ADDRESSES, AsmSourceGutterMode.LINES.next())
        assertEquals(AsmSourceGutterMode.BOTH, AsmSourceGutterMode.ADDRESSES.next())
        assertEquals(AsmSourceGutterMode.LINES, AsmSourceGutterMode.BOTH.next())
    }

    private fun edit(raw: String, transform: (String) -> String): TextFieldValue {
        val view = AsmSourcePresentation(raw)
        val updated = transform(view.text)
        return view.sourceValue(TextFieldValue(updated, TextRange(updated.length)))
    }
}
