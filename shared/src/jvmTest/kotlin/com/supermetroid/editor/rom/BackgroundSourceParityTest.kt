package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundSourceParityTest {

    @Tag("parity")
    @Test
    fun `all library background programs and commands match named source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertEquals(TestRomHelper.referenceInt("background.program.count"), root.int("programCount"))
        assertEquals(TestRomHelper.referenceInt("background.program.active.count"), root.int("activeProgramCount"))
        assertEquals(TestRomHelper.referenceInt("background.program.unused.count"), root.int("unusedProgramCount"))
        assertEquals(TestRomHelper.referenceInt("background.stateAssociation.count"), root.int("stateAssociationCount"))
        assertEquals(TestRomHelper.referenceInt("background.embeddedState.count"), root.int("embeddedLayer2StateCount"))
        assertEquals(TestRomHelper.referenceInt("background.command.count"), root.int("commandCount"))
        assertEquals(TestRomHelper.referenceInt("background.doorTransfer.count"), root.int("doorDependentTransferCount"))
        assertPinnedHash(root, "programs")
        assertPinnedHash(root, "commands")
        assertPinnedHash(root, "consumers")

        var commandCount = 0
        root.getValue("programs").jsonArray.map { it.jsonObject }.forEach { expected ->
            val program = assertNotNull(
                parser.parseLibraryBackground(expected.int("pointer")),
                expected.string("sourceLabel"),
            )
            assertTrue(program.isComplete, expected.string("sourceLabel"))
            assertEquals(expected.int("byteCount"), program.byteCount, expected.string("sourceLabel"))
            val commands = expected.getValue("commands").jsonArray.map { it.jsonObject }
            assertEquals(commands.size, program.commands.size, expected.string("sourceLabel"))
            commands.zip(program.commands).forEach { (source, actual) ->
                assertEquals(source.int("opcode"), actual.type.opcode, expected.string("sourceLabel"))
                assertEquals((source.int("snesAddress") and 0xFFFF) - expected.int("pointer"), actual.offset)
                assertEquals(source.optionalInt("sourceAddress"), actual.sourceAddress)
                assertEquals(source.optionalInt("vramDestination"), actual.vramDestination)
                assertEquals(source.optionalInt("size"), actual.size)
                assertEquals(source.optionalInt("wramDestination"), actual.wramDestination)
                assertEquals(source.optionalInt("doorDefPtr"), actual.doorDefPtr)
                commandCount++
            }
            val pc = parser.snesToPc(expected.int("snesAddress"))
            val raw = parser.getRomData().copyOfRange(pc, pc + program.byteCount)
            assertEquals(expected.string("rawSha256"), TestRomHelper.sha256(raw), expected.string("sourceLabel"))
        }
        assertEquals(root.int("commandCount"), commandCount)
    }

    @Tag("parity")
    @Test
    fun `compressed assets transfers and embedded ownership cover every room state`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertEquals(
            TestRomHelper.referenceInt("background.compressed.count"),
            root.int("compressedBackgroundCount"),
        )
        assertEquals(
            TestRomHelper.referenceInt("background.compressed.referenced.count"),
            root.int("referencedCompressedBackgroundCount"),
        )
        assertEquals(
            TestRomHelper.referenceInt("background.compressed.unreferenced.count"),
            root.int("unreferencedCompressedBackgroundCount"),
        )
        assertEquals(
            listOf("Background_Blank"),
            root.getValue("unreferencedCompressedBackgrounds").jsonArray.map { it.jsonPrimitive.content },
        )

        root.getValue("programs").jsonArray.map { it.jsonObject }.forEach { program ->
            program.getValue("commands").jsonArray.map { it.jsonObject }.forEach { command ->
                if (command.int("opcode") == 0x0004) {
                    val decoded = parser.decompressLZ2(command.int("sourceAddress"))
                    assertEquals(command.int("decompressedSize"), decoded.size, command.string("sourceLabel"))
                    assertEquals(
                        command.string("decompressedSha256"),
                        TestRomHelper.sha256(decoded),
                        command.string("sourceLabel"),
                    )
                }
            }
        }
    }

    @Tag("parity")
    @Test
    fun `production reconstructs repeated wide Kraid and door selected tilemaps`() {
        val parser = TestRomHelper.requireRomParser()

        val repeated = assertNotNull(parser.readBgTilemapLayout(0xB87E))
        assertEquals(64, repeated.widthTiles)
        assertEquals(32, repeated.heightTiles)
        for (y in 0 until 32) for (x in 0 until 32) {
            assertEquals(repeated.words[y * 64 + x], repeated.words[y * 64 + x + 32])
        }

        val wide = assertNotNull(parser.readBgTilemapLayout(0xE248))
        assertEquals(64, wide.widthTiles)
        assertEquals(32, wide.heightTiles)
        val rawWide = parser.decompressLZ2(0xBAA475)
        assertEquals(0x1000, rawWide.size)
        for (y in 0 until 32) for (x in 0 until 32) {
            assertEquals(word(rawWide, y * 32 + x), wide.words[y * 64 + x])
            assertEquals(word(rawWide, 1024 + y * 32 + x), wide.words[y * 64 + x + 32])
        }
        assertNotEquals(
            wide.words.copyOfRange(0, 32).toList(),
            wide.words.copyOfRange(32, 64).toList(),
            "the right 32×32 screen must not be replaced with a repeated left half",
        )

        val kraid = assertNotNull(parser.readBgTilemapLayout(0xB815))
        assertEquals(64, kraid.widthTiles)
        assertEquals(64, kraid.heightTiles)
        assertEquals(4096, kraid.words.size)

        val landing = assertNotNull(parser.readBgTilemapLayout(0xB76A, incomingDoorDefPtr = 0x8946))
        assertEquals(32, landing.widthTiles)
        assertEquals(32, landing.heightTiles)
        val sky = parser.getRomData().copyOfRange(parser.snesToPc(0x8AC180), parser.snesToPc(0x8AC180) + 0x800)
        val landingBytes = ByteArray(landing.words.size * 2)
        landing.words.forEachIndexed { index, value ->
            landingBytes[index * 2] = value.toByte()
            landingBytes[index * 2 + 1] = (value ushr 8).toByte()
        }
        assertContentEquals(sky, landingBytes)

        assertNull(parser.readBgTilemapLayout(0xB84D), "Crocomire's \$7E:2000 tilemap is runtime-generated")
    }

    private fun word(bytes: ByteArray, index: Int): Int =
        (bytes[index * 2].toInt() and 0xFF) or ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)

    private fun assertPinnedHash(root: JsonObject, name: String) {
        assertEquals(
            TestRomHelper.referenceString("background.$name.aggregate.sha256"),
            root.getValue("aggregateHashes").jsonObject.string(name),
            name,
        )
    }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("backgrounds.json", "parityBackgrounds").readText(),
    ).jsonObject

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.optionalInt(name: String): Int? = get(name)?.jsonPrimitive?.int
}
