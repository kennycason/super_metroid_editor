package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTextCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `fixed text fields survive cold source build and restore`() {
        val fixture = fixture()
        val bridge = AsmTextSourceBridge(fixture.repository)
        val replacements = mapOf(
            "area_test" to "ZEBES",
            "message_test" to "SAVE COMPLETE.",
            "item_test" to "MOON BOOTS",
        )
        val expected = linkedMapOf<TextEntry, ByteArray>()
        fixture.entries.forEach { entry ->
            val opened = assertIs<AsmTextSourceAccess.Editable>(
                bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
            )
            val text = replacements.getValue(entry.id)
            bridge.save(fixture.projectFile.path, entry, text, opened.sourceText)
            expected[entry] = TextData.encodeEntry(entry, text)
        }

        val compiled = AsmProjectCompiler(fixture.repository).compile(fixture.projectFile.path, fixture.rom)
        expected.forEach { (entry, bytes) ->
            val pc = requireNotNull(snesLoRomToPc(entry.snesAddress))
            assertContentEquals(bytes, compiled.romBytes.copyOfRange(pc, pc + bytes.size), entry.label)
        }

        AsmProjectCompiler.clearBuildCacheForTests()
        val coldRepository = fixture.newRepository()
        val cold = AsmProjectCompiler(coldRepository).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(compiled.romBytes, cold.romBytes)

        fixture.entries.forEach { entry ->
            val current = assertIs<AsmTextSourceAccess.Editable>(
                bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
            )
            assertFalse(bridge.restore(fixture.projectFile.path, entry, current.sourceText).modified)
        }
        val restored = AsmProjectCompiler(fixture.repository).compile(fixture.projectFile.path, fixture.rom)
        fixture.entries.forEach { entry ->
            val pc = requireNotNull(snesLoRomToPc(entry.snesAddress))
            assertContentEquals(entry.rawBytes, restored.romBytes.copyOfRange(pc, pc + entry.rawBytes.size), entry.label)
        }
    }

    private data class Fixture(
        val projectFile: File,
        val repository: AsmProjectWorkspaceRepository,
        val rom: ByteArray,
        val entries: List<TextEntry>,
    ) {
        fun newRepository() = AsmProjectWorkspaceRepository(
            assetRanges = emptyList(),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
    }

    private fun fixture(): Fixture {
        val definitions = listOf(
            field("area_test", "Area", TextCategory.AREA_NAME, 0x82966F, 12, "CRATERIA", 0x28),
            field("message_test", "Message", TextCategory.UI_MESSAGE, 0x85923F, 32, "ENERGY RECHARGE", 0x3C),
            field("item_test", "Item", TextCategory.ITEM_NAME, 0x858793, 15, "ENERGY TANK", 0x3C),
        )
        val rom = ByteArray(RomConstants.ROM_SIZE)
        definitions.forEach { entry ->
            entry.rawBytes.copyInto(rom, requireNotNull(snesLoRomToPc(entry.snesAddress)))
        }
        val referenceRoot = File(temporary, "reference")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(dataRoot, "base.bin").writeBytes(rom)
        File(sourceRoot, "main.asm").writeText(buildString {
            appendLine("lorom")
            appendLine("arch 65816")
            for (offset in rom.indices step 0x8000) {
                val address = (0x80 + offset / 0x8000) * 0x10000 + 0x8000
                appendLine("org ${'$'}${address.toString(16).uppercase()}")
                appendLine("incbin \"../data/base.bin\":${offset.toString(16)}-${(offset + 0x8000).toString(16)}")
            }
            appendLine("incsrc \"bank_text.asm\"")
            appendLine("print \"Assembly complete. Total bytes written: \", bytes")
        })
        File(sourceRoot, "bank_text.asm").writeText(buildString {
            definitions.forEach { entry ->
                appendLine("org ${'$'}${entry.snesAddress.toString(16).uppercase()}")
                append("    dw ")
                append(entry.rawBytes.toWords().joinToString(",") { word ->
                    "${'$'}${word.toString(16).uppercase().padStart(4, '0')}"
                })
                appendLine(" ;${entry.snesAddress.toString(16).uppercase()};")
            }
        })
        val reference = AsmReferenceWorkspace(
            referenceRoot,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "text-fixture",
                "fixture.sfc",
                AsmVerifiedCache.hash(rom),
                rom.size,
                0,
            ),
            AsmSourceParser().parse(sourceRoot, dataRoot, emptyList()),
        )
        val projectFile = File(temporary, "project/Text.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = emptyList(),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(projectFile.path, reference)
        return Fixture(projectFile, repository, rom, definitions)
    }

    private fun field(
        id: String,
        label: String,
        category: TextCategory,
        address: Int,
        width: Int,
        text: String,
        property: Int,
    ): TextEntry {
        val words = text.padEnd(width).map { character ->
            val tile = when (category) {
                TextCategory.AREA_NAME -> if (character == ' ') 0x01 else 0x30 + (character - 'A')
                else -> if (character == ' ') 0x4E else if (character == '.') 0xFA else 0xE0 + (character - 'A')
            }
            property shl 8 or tile
        }
        val raw = ByteArray(words.size * 2)
        words.forEachIndexed { index, word ->
            raw[index * 2] = word.toByte()
            raw[index * 2 + 1] = (word ushr 8).toByte()
        }
        return TextEntry(
            id = id,
            label = label,
            category = category,
            pcOffset = requireNotNull(snesLoRomToPc(address)),
            snesAddress = address,
            maxLength = width,
            text = text,
            rawBytes = raw,
        )
    }

    private fun ByteArray.toWords(): List<Int> = indices.step(2).map { index ->
        (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)
    }
}
