package com.supermetroid.editor.asm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmAddressAtlasTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `LoROM addresses convert in both directions`() {
        assertEquals(0x000000, snesLoRomToPc(0x808000))
        assertEquals(0x07805A, snesLoRomToPc(0x8F805A))
        assertEquals(0x2FFFFF, snesLoRomToPc(0xDFFFFF))
        assertEquals(0x3FFFFF, snesLoRomToPc(0xFFFFFF))
        assertNull(snesLoRomToPc(0x7E8000))
        assertNull(snesLoRomToPc(0x8F7FFF))

        assertEquals(0x808000, pcToSnesLoRom(0x000000))
        assertEquals(0x8F805A, pcToSnesLoRom(0x07805A))
        assertEquals(0xFFFFFF, pcToSnesLoRom(0x3FFFFF))
        assertNull(pcToSnesLoRom(0x400000))
    }

    @Test
    fun `address search accepts editor friendly SNES and PC forms`() {
        listOf("\$8F:805A", "\$8F805A", "8F805A", "SNES:8F805A").forEach { text ->
            val query = assertNotNull(parseAsmAddressQuery(text), text)
            assertEquals(0x8F805A, query.snesAddress, text)
            assertEquals(AsmAddressSpace.SNES, query.enteredAs, text)
        }
        listOf("0x07805A", "PC:07805A", "07805A").forEach { text ->
            val query = assertNotNull(parseAsmAddressQuery(text), text)
            assertEquals(0x8F805A, query.snesAddress, text)
            assertEquals(AsmAddressSpace.PC, query.enteredAs, text)
        }
        assertNull(parseAsmAddressQuery("\$7E:8000"))
        assertNull(parseAsmAddressQuery("805A"))
    }

    @Test
    fun `parser builds exact anchors and safe nearest context`() {
        val source = File(tempDirectory, "src").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_8F.asm ; Rooms\n")
        File(source, "bank_8F.asm").writeText(
            """
            ; Rooms
            org ${'$'}8F8000
            ;;; ${'$'}8000: Room data ;;;
            RoomHeader_Test:                         ;8F8000;
                dw ${'$'}1234

            PLMPopulation_Test:
            ; Used by the test room
                dw ${'$'}0000                        ;8F8010;
            """.trimIndent(),
        )
        val assetRange = AsmAssetRange("Room_Test.bin", 0x078008, 4)
        val index = AsmSourceParser().parse(source, File(tempDirectory, "data"), listOf(assetRange))
        val atlas = index.addressAtlas

        val roomLabel = index.labels.single { it.name == "RoomHeader_Test" }
        assertEquals(0x8F8000, atlas.exactAt(roomLabel.fileId, roomLabel.lineIndex).single().snesAddress)

        val plmLabel = index.labels.single { it.name == "PLMPopulation_Test" }
        val plmAnchor = atlas.exactAt(plmLabel.fileId, plmLabel.lineIndex).single()
        assertEquals(AsmAddressAnchorKind.LABEL, plmAnchor.kind)
        assertEquals(0x8F8010, plmAnchor.snesAddress)

        val exact = atlas.resolve(assertNotNull(parseAsmAddressQuery("\$8F:8000")))
        assertTrue(exact.exactSourceAnchors.isNotEmpty())
        assertEquals(AsmAddressAnchorKind.RECORDED, exact.exactSourceAnchors.first().kind)
        assertEquals(0, exact.sourceDelta)

        val withinAsset = atlas.resolve(assertNotNull(parseAsmAddressQuery("\$8F:800A")))
        assertEquals("Room_Test.bin", assertNotNull(withinAsset.containingAsset).range.path)
        assertEquals(2, withinAsset.assetDelta)
        assertEquals(0x8F8000, assertNotNull(withinAsset.nearestSourceAnchor).snesAddress)
        assertEquals(0xA, withinAsset.sourceDelta)
    }
}
