package com.supermetroid.editor.asm

import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Test

class AsmThreeWayMergeTest {
    @Test fun `comments inserted beside changed code merge without losing either side`() {
        val base = "Root:\n dw 1\n RTL\n"
        assertEquals("; Explanation\nRoot:\n dw 9\n RTL\n", AsmThreeWayMerge.merge(base,
            "Root:\n dw 9\n RTL\n", "; Explanation\n$base").resolved())
        assertEquals("Root:\n ; Explanation\n dw 9\n RTL\n", AsmThreeWayMerge.merge(base,
            "Root:\n dw 9\n RTL\n", "Root:\n ; Explanation\n dw 1\n RTL\n").resolved())
    }

    @Test fun `conflicting edits remain typed unresolved data and retain surrounding clean changes`() {
        val merge = AsmThreeWayMerge.merge("A\nB\nC\n", "A\nours\nC\n", "; note\nA\ntheirs\nC\n")
        assertNull(merge.resolved())
        assertEquals(listOf(AsmMergeConflict("B\n", "ours\n", "theirs\n")), merge.conflicts)
        assertEquals("; note\nA\nours\nC\n", merge.resolved(mapOf(0 to "ours\n")))
        assertEquals("; note\nA\ncustom\nC\n", merge.resolved(mapOf(0 to "custom\n")))
        assertEquals("; note\nA\nC\n", merge.resolved(mapOf(0 to "")))
    }

    @Test fun `identical edits converge while competing insertions and delete modify conflict`() {
        assertEquals("A\nx\nB\n", AsmThreeWayMerge.merge("A\nB\n", "A\nx\nB\n", "A\nx\nB\n").resolved())
        assertEquals(1, AsmThreeWayMerge.merge("A\nB\n", "A\nx\nB\n", "A\ny\nB\n").conflicts.size)
        assertEquals(1, AsmThreeWayMerge.merge("A\nB\nC\n", "A\nC\n", "A\nchanged\nC\n").conflicts.size)
    }

    @Test fun `multiple conflicts are independently resolved and CRLF and EOF are preserved`() {
        val merge = AsmThreeWayMerge.merge("A\r\nB\r\nC\r\nD", "oursA\r\nB\r\nC\r\noursD", "newA\r\nB\r\nC\r\nnewD")
        assertEquals(2, merge.conflicts.size)
        assertNull(merge.resolved(mapOf(0 to "chosen\r\n")))
        assertEquals("chosen\r\nB\r\nC\r\nlast", merge.resolved(mapOf(0 to "chosen\r\n", 1 to "last")))
    }

    @Test fun `empty and unchanged sources preserve exact text`() {
        for (text in listOf("", "abc", "abc\n", "abc\r\n", "\n\n")) {
            assertEquals(text, AsmThreeWayMerge.merge("", text, "").resolved())
            assertEquals(text, AsmThreeWayMerge.merge(text, text, text).resolved())
            assertEquals(text, AsmThreeWayMerge.merge("", "", text).resolved())
        }
    }

    @Test fun `bounded complex diffs fall back to review rather than partial merging`() {
        val base = (0..1200).joinToString("\n") { "base$it" }
        val project = (0..1200).joinToString("\n") { "project$it" }
        val updated = (0..1200).joinToString("\n") { "updated$it" }
        val merge = AsmThreeWayMerge.merge(base, project, updated)
        assertEquals(listOf(AsmMergeConflict(base, project, updated)), merge.conflicts)
        assertNull(merge.resolved())
    }

    @Test fun `random insert delete replace sequences never lose project lines while merging a separate append`() {
        val random = Random(7183)
        repeat(1000) {
            val base = MutableList(random.nextInt(1, 30)) { "line${random.nextInt(7)}\n" }
            val project = base.toMutableList()
            repeat(random.nextInt(1, 12)) {
                when (random.nextInt(3)) {
                    0 -> project.add(random.nextInt(project.size + 1), "insert${random.nextInt(7)}\n")
                    1 -> if (project.isNotEmpty()) project.removeAt(random.nextInt(project.size))
                    else -> if (project.isNotEmpty()) project[random.nextInt(project.size)] = "replace${random.nextInt(7)}\n"
                }
            }
            // A unique retained final line makes the independent append unambiguous.
            val original = base.joinToString("") + "END\n"
            val authored = project.joinToString("") + "END\n"
            val merge = AsmThreeWayMerge.merge(original, authored, original + "new tail\n")
            assertEquals(authored + "new tail\n", merge.resolved(), "iteration $it")
        }
    }
}
