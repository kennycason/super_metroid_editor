package com.supermetroid.editor.asm

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmSourceBindingsTest {
    @TempDir lateinit var temporary: File

    @Test fun `symbol maps are immutable snapshots with unique ordinary identifier targets`() {
        val mutable = mutableMapOf("Old" to "New")
        val names = AsmSourceSymbolNames(mutable)
        mutable["Old"] = "Changed"
        assertEquals("New", names.active("Old"))
        assertEquals("Old", names.canonical("New"))
        assertFailsWith<IllegalArgumentException> { AsmSourceSymbolNames(mapOf("A" to "Same", "B" to "Same")) }
        assertFailsWith<IllegalArgumentException> { AsmSourceSymbolNames(mapOf("A" to "Not.AnOrdinaryGlobal")) }
    }

    @Test fun `template binding preserves CRLF strings comments literals local names and source positions`() {
        val binding = AsmSourceBindings(AsmSourceSymbolNames(mapOf("Native" to "BetterNative", "ABCD" to "HexNamedSymbol")))
        val text = "Module:\r\n.loop:\r\n\tLDA.W Native ; Native note\r\n dw ABCD,\$ABCD,%1010,42,NativeOther\r\n db \"Native; ABCD\"\r\n BNE .loop"
        val expected = "Module:\r\n.loop:\r\n\tLDA.W BetterNative ; Native note\r\n dw HexNamedSymbol,\$ABCD,%1010,42,NativeOther\r\n db \"Native; ABCD\"\r\n BNE .loop"
        assertEquals(expected, binding.template(text))
        assertEquals("Module:", binding.names.canonicalCode(expected.lineSequence().first()))
    }

    @Test fun `template symbol sets are applied simultaneously including swaps and chains`() {
        val original = "Module:\n dw A,B\n"
        val chain = AsmSourceBindings(AsmSourceSymbolNames(mapOf("A" to "B", "B" to "C")))
        assertEquals("Module:\n dw B,C\n", chain.template(original))
        val swap = AsmSourceBindings(AsmSourceSymbolNames(mapOf("A" to "B", "B" to "A")))
        assertEquals("Module:\n dw B,A\n", swap.template(original))
    }

    @Test fun `module definitions and macro parameters cannot shadow a renamed native symbol`() {
        val bindings = AsmSourceBindings(AsmSourceSymbolNames(mapOf("Native" to "BetterNative")))
        for (text in listOf("Native:\n RTS\n", "BetterNative:\n RTS\n", "macro emit(Native)\n dw <Native>\nendmacro\n",
            "function emit(Native) = Native+1\n", "namespace Named\nModule:\n dw Native\nnamespace off\n")) {
            assertFailsWith<IllegalArgumentException>(text) { bindings.template(text) }
        }
    }

    @Test fun `template aliases cannot shadow canonical native identities`() {
        val bindings = AsmSourceBindings(AsmSourceSymbolNames(mapOf("Module_local" to "NativeRenamed")))
        assertFailsWith<IllegalArgumentException> { bindings.template("Module:\n.local:\n dw Module_local\n") }
    }

    @Test fun `native enemy identity survives removed misleading comments and completely different spellings`() {
        val bindings = AsmSourceBindings(AsmSourceSymbolNames(mapOf("EnemyHeaders_Zoomer" to "CrawlerRecord")),
            listOf(AsmNativeSymbol(ENEMY_HEADER_SOURCE_FILE, "EnemyHeaders_Zoomer", 0xA0DCFF)))
        assertEquals(mapOf(0xDCFF to "CrawlerRecord"), bindings.enemyHeaders("CrawlerRecord:\n %health(15)\n"))
        assertEquals(mapOf(0xDCFF to "CrawlerRecord"), bindings.enemyHeaders("CrawlerRecord: ;A0D47F;\n %health(15)\n"))
        assertEquals(emptyMap(), bindings.enemyHeaders("Unrelated: ;A0DCFF;\n %health(15)\n"))
        assertFailsWith<IllegalArgumentException> { bindings.enemyHeaders("CrawlerRecord:\nCrawlerRecord:\n") }
    }

    @Test fun `native identity collisions are rejected instead of guessing a species`() {
        val bindings = AsmSourceBindings(nativeSymbols = listOf(
            AsmNativeSymbol(ENEMY_HEADER_SOURCE_FILE, "EnemyHeaders_First", 0xA0DCFF),
            AsmNativeSymbol(ENEMY_HEADER_SOURCE_FILE, "EnemyHeaders_Second", 0xA0DCFF),
        ))
        assertFailsWith<IllegalArgumentException> { bindings.enemyHeaders("EnemyHeaders_First:\nEnemyHeaders_Second:\n") }
    }

    @Test fun `bundled native inventory contains exact unique revision-addressed header identities`() {
        val symbols = AsmNativeSymbolManifest.load(AsmReferenceContract.COMMIT)
        assertEquals(163, symbols.size)
        assertEquals(163, symbols.map { it.address }.distinct().size)
        assertTrue(symbols.all { it.generatedMembers.size == 29 })
        assertTrue(symbols.all { it.fileId == ENEMY_HEADER_SOURCE_FILE && it.address ushr 16 == 0xA0 })
        assertEquals(0xA0E2BF, symbols.single { it.name == "EnemyHeaders_Kraid" }.address)
        assertTrue(AsmNativeSymbolManifest.load("0".repeat(40)).isEmpty())
        assertFailsWith<IllegalArgumentException> { AsmNativeSymbolManifest.load("../current") }
    }

    @Test fun `shared lexing preserves indexed references and does not mistake numeric literals for symbols`() {
        val code = "LDA.W Enemy[1].properties+Native+\$ABCD+%1010+42 ; stripped separately"
        val executable = asmCodeBeforeComment(code)
        val tokens = AsmSourceLexing.references(executable).toList()
        assertEquals(listOf("LDA.W", "Enemy[1].properties", "Native"), tokens.map { it.value })
        tokens.forEach { assertEquals(it.value, code.substring(it.range)) }
        assertEquals(listOf("db", "Native"), AsmSourceLexing.references("db \"escaped \\\" Native; literal\", Native").map { it.value }.toList())
    }

    @Test fun `code canonicalization leaves comments strings and hexadecimal data untouched`() {
        val names = AsmSourceSymbolNames(mapOf("Old" to "ABCD"))
        assertEquals("dw Old,\$ABCD ; ABCD", names.canonicalCode("dw ABCD,\$ABCD ; ABCD"))
        assertEquals("db \"ABCD\",Old", names.canonicalCode("db \"ABCD\",ABCD"))
    }

    @Test fun `strict resolution refuses both global and local ambiguity without changing navigation policy`() {
        val source = File(temporary, "src").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_80.asm\nincsrc bank_81.asm\n")
        File(source, "bank_80.asm").writeText("Duplicate:\n.loop:\n.loop:\n BNE .loop\n")
        File(source, "bank_81.asm").writeText("Duplicate:\n RTS\n")
        val index = AsmSourceParser().parse(source, File(temporary, "data"), emptyList())
        assertNotNull(index.resolveLabel("bank_80.asm", 3, "Duplicate"))
        assertNotNull(index.resolveLabel("bank_80.asm", 3, ".loop"))
        assertNull(index.resolveUniqueLabel("bank_80.asm", 3, "Duplicate"))
        assertNull(index.resolveUniqueLabel("bank_80.asm", 3, ".loop"))
    }

    @Test fun `statement guards distinguish literal whitespace from source formatting`() {
        assertEquals(asmStatementSignature(" dw  \$0001 ; old note"), asmStatementSignature("\tdw\t\$0001"))
        assertNotEquals(asmStatementSignature("db \"a b\""), asmStatementSignature("db \"ab\""))
        assertEquals("db\"a ; b\"", asmStatementSignature("db \"a ; b\" ; ignored"))
    }
}
