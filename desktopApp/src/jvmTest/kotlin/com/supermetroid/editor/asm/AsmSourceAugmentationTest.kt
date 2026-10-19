package com.supermetroid.editor.asm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmSourceAugmentationTest {
    @TempDir lateinit var temporary: File

    private fun source(vararg files: Pair<String, String>): File {
        val root = File(temporary, "src").apply { mkdirs() }
        File(root, "main.asm").writeText(files.joinToString("\n", postfix = "\n") { "incsrc \"${it.first}\"" })
        files.forEach { (name, text) -> File(root, name).writeText(text) }
        return root
    }
    private fun note(symbol: String = "Timers", code: String = "dw \$0001") = AsmSourceDocumentation(
        "timing", "bank_91.asm", symbol, code, listOf("Timer reload words, indexed by the counter times two."),
    )
    private fun parse(root: File) = AsmSourceParser().parse(root, File(temporary, "data"), emptyList())

    @Test fun `documentation preserves original comments CRLF bytes and upstream line correspondence`() {
        val original = "; Old explanation\r\nTimers:\r\n    dw \$0001 ;91B61F;\r\n"
        val root = source("bank_91.asm" to original)
        val result = AsmSourceAugmentation("one", documentation = listOf(note())).apply(root)
        val augmented = result.texts.getValue("bank_91.asm")
        assertEquals("; Old explanation\r\n; Timer reload words, indexed by the counter times two.\r\nTimers:\r\n    dw \$0001 ;91B61F;\r\n", augmented)
        assertEquals(listOf(0, -1, 1, 2, 3), result.upstreamLines["bank_91.asm"])
        assertEquals(original, File(root, "bank_91.asm").readText())
        assertEquals(AsmVerifiedCache.hash(original), result.upstreamSourceHashes["bank_91.asm"])
    }

    @Test fun `documentation accepts missing address annotations and whitespace without changing executable code`() {
        val root = source("bank_91.asm" to "Timers:\n\tdw    \$0001\n")
        val result = AsmSourceAugmentation("one", documentation = listOf(note())).apply(root)
        assertEquals(listOf("Timers:", "\tdw    \$0001"), result.texts.getValue("bank_91.asm").lineSequence()
            .map(::asmExecutableCode).filter(String::isNotBlank).toList())
    }

    @Test fun `missing ambiguous and changed documentation anchors fail without publishing partial edits`() {
        val root = source("bank_91.asm" to "Timers:\n dw \$0002\n")
        val before = readAsmSourceTree(root)
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation("one", documentation = listOf(note())).apply(root) }
        assertFailsWith<IllegalStateException> { AsmSourceAugmentation("one", documentation = listOf(note("Missing"))).apply(root) }
        File(root, "bank_91.asm").appendText("Timers:\n dw \$0001\n")
        assertFailsWith<IllegalStateException> { AsmSourceAugmentation("one", documentation = listOf(note())).apply(root) }
        assertEquals(before.getValue("main.asm"), File(root, "main.asm").readText())
    }

    @Test fun `duplicate documentation records are rejected`() {
        val root = source("bank_91.asm" to "Timers:\n dw \$0001\n")
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation("one", documentation = listOf(note(), note())).apply(root) }
    }

    @Test fun `global rename updates declarations expressions macro arguments and cross bank references only`() {
        val root = source(
            "memory.asm" to "org \$14\nScratch: skip 2\nScratchOther: skip 2\n",
            "bank_90.asm" to "Root:\n LDA.B Scratch\n dw Scratch+2\n %emit(Scratch)\n db \"Scratch; literal\" ; Scratch note\n LDA.B ScratchOther\n",
        )
        val augmentation = AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("Scratch", "TemporaryWord")))
        val result = augmentation.apply(root)
        assertTrue(result.texts.getValue("memory.asm").contains("TemporaryWord: skip 2"))
        assertEquals("Root:\n LDA.B TemporaryWord\n dw TemporaryWord+2\n %emit(TemporaryWord)\n db \"Scratch; literal\" ; Scratch note\n LDA.B ScratchOther\n", result.texts["bank_90.asm"])
        assertTrue(File(root, "memory.asm").readText().contains("Scratch: skip 2"))
    }

    @Test fun `parent rename updates flattened nested aliases and preserves local branch identities`() {
        val root = source("bank_90.asm" to "Root:\n.loop:\n BNE .loop\n.Table:\n..value:\n dw Root_Table_value\nOther:\n.loop:\n BRA .loop\n")
        val before = parse(root)
        val result = AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("Root", "Movement"))).apply(root)
        result.texts.forEach { (name, text) -> File(root, name).writeText(text) }
        val after = parse(root)
        assertEquals("Movement_Table_value", result.symbolNames["Root_Table_value"])
        assertEquals("Movement_loop", after.resolveLabel("bank_90.asm", 2, ".loop")?.localScope?.let { it + "_loop" })
        assertEquals("Other", after.resolveLabel("bank_90.asm", 8, ".loop")?.localScope)
        assertEquals(before.labels.size, after.labels.size)
        val old = assertNotNull(before.resolveLabel("bank_90.asm", 5, "Root_Table_value"))
        val updated = assertNotNull(after.resolveLabel("bank_90.asm", 5, "Movement_Table_value"))
        assertEquals(before.usagesFor(old).size, after.usagesFor(updated).size)
    }

    @Test fun `rename sets are simultaneous including chains and swaps`() {
        val root = source("bank_90.asm" to "A: dw A,B\nB: dw B,A\n")
        val chain = AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("A", "B"), AsmSourceRename("B", "C"))).apply(root)
        assertEquals("B: dw B,C\nC: dw C,B\n", chain.texts["bank_90.asm"])
        val swap = AsmSourceAugmentation("two", renames = listOf(AsmSourceRename("A", "B"), AsmSourceRename("B", "A"))).apply(root)
        assertEquals("B: dw B,A\nA: dw A,B\n", swap.texts["bank_90.asm"])
        assertEquals(mapOf("A" to 10, "B" to 20), AsmSourceSymbolNames(chain.symbolNames).canonicalLabels(mapOf("B" to 10, "C" to 20)))
    }

    @Test fun `colliding missing and duplicate declarations cannot be renamed`() {
        val root = source("bank_90.asm" to "A:\n RTS\nB:\n RTS\n")
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("A", "B"))).apply(root) }
        assertFailsWith<IllegalStateException> { AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("Missing", "New"))).apply(root) }
        File(root, "bank_90.asm").appendText("A:\n RTS\n")
        assertFailsWith<IllegalStateException> { AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("A", "New"))).apply(root) }
    }

    @Test fun `unclassified executable compatibility references and macro parameter collisions fail closed`() {
        val root = source("bank_90.asm" to "A:\n RTS\n;@ LDA.W A\n")
        val rename = AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("A", "New")))
        assertFailsWith<IllegalArgumentException> { rename.apply(root) }
        File(root, "bank_90.asm").writeText("A:\n RTS\nmacro emit(A)\n dw <A>\nendmacro\n")
        assertFailsWith<IllegalArgumentException> { rename.apply(root) }
    }

    @Test fun `namespaced source requires an explicitly scoped transformation`() {
        val root = source("bank_90.asm" to "namespace Named\nA:\n RTS\nnamespace off\n")
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("A", "New"))).apply(root) }
    }

    @Test fun `documentation and rename compose from one upstream snapshot`() {
        val root = source("bank_91.asm" to "Timers:\n dw \$0001\n dw Timers\n")
        val result = AsmSourceAugmentation("one", documentation = listOf(note()), renames = listOf(AsmSourceRename("Timers", "Reloads"))).apply(root)
        assertTrue(result.texts.getValue("bank_91.asm").contains("\nReloads:\n dw \$0001\n dw Reloads\n"))
    }

    @Test fun `symbol contract translation changes identifiers without touching strings or source spans`() {
        val names = AsmSourceSymbolNames(mapOf("Old" to "New"))
        assertEquals("LDA.W Old ; \"New\"", names.canonicalCode("LDA.W New ; \"New\""))
        assertEquals("New", names.active("Old"))
        assertEquals("Unrelated", names.active("Unrelated"))
        assertFailsWith<IllegalArgumentException> { names.canonicalLabels(mapOf("Old" to 1, "New" to 2)) }
        assertEquals(mapOf("Old" to 2), names.canonicalLabels(mapOf("Old" to 2, "New" to 2)))
    }

    @Test fun `an unrelated upstream source change fails the complete source fingerprint guard`() {
        val root = source("bank_91.asm" to "Timers:\n dw \$0001\n")
        val recipe = AsmSourceAugmentation("one", upstreamSourceSha256 = AsmVerifiedCache.fingerprint(readAsmSourceTree(root)), documentation = listOf(note()))
        assertTrue(recipe.apply(root).texts.getValue("bank_91.asm").contains("; Timer reload words"))
        File(root, "main.asm").appendText("; Another upstream revision\n")
        assertFailsWith<IllegalArgumentException> { recipe.apply(root) }
    }

    @Test fun `compiler generated members keep their canonical identity when a root is renamed`() {
        val root = source("bank_A0.asm" to "EnemyHeaders_Test:\n %EnemyHeader()\n")
        val identities = listOf(AsmNativeSymbol("bank_A0.asm", "EnemyHeaders_Test", 0xA0DCFF, listOf("health", "damage")))
        val recipe = AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("EnemyHeaders_Test", "CrawlerRecord")))
        val augmented = recipe.apply(root, nativeSymbols = identities)
        assertEquals("CrawlerRecord_health", augmented.symbolNames["EnemyHeaders_Test_health"])
        assertEquals(mapOf("EnemyHeaders_Test_health" to 0xA0DD03),
            AsmSourceSymbolNames(augmented.symbolNames).canonicalLabels(mapOf("CrawlerRecord_health" to 0xA0DD03)))
        File(root, "bank_A0.asm").appendText(" dw EnemyHeaders_Test_health\n")
        assertFailsWith<IllegalArgumentException> { recipe.apply(root, nativeSymbols = identities) }
    }

    @Test fun `documentation guard cannot erase meaningful whitespace inside a literal`() {
        val root = source("bank_91.asm" to "Timers:\n db \"ab\"\n")
        val recipe = AsmSourceAugmentation("one", documentation = listOf(note(code = "db \"a b\"")))
        assertFailsWith<IllegalArgumentException> { recipe.apply(root) }
    }

    @Test fun `bound function parameter names do not become global variable renames`() {
        val root = source("bank_90.asm" to "Scratch:\n RTS\nfunction scale(Scratch) = Scratch*2\n")
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation("one", renames = listOf(AsmSourceRename("Scratch", "TemporaryWord"))).apply(root) }
    }

    @Test fun `partial or stale indexes cannot publish even an unmodified baseline`() {
        val root = source("bank_91.asm" to "Timers:\n dw \$0001\n")
        val parser = AsmSourceParser()
        val partial = parser.parse(root, File(temporary, "data"), emptyList(), fileIds = setOf("main.asm"))
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation.NONE.apply(root, partial) }
        val full = parser.parse(root, File(temporary, "data"), emptyList())
        File(root, "bank_91.asm").appendText("; Changed while preparing the snapshot.\n")
        assertFailsWith<IllegalArgumentException> { AsmSourceAugmentation.NONE.apply(root, full) }
    }
}
