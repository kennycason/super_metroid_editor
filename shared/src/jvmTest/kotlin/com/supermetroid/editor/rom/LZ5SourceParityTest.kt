package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LZ5SourceParityTest {

    @Tag("parity")
    @Test
    fun `every extracted LZ5 stream matches the independent source oracle`() {
        val (disassembly, records) = loadCorpus()

        records.forEach { record ->
            val asset = record.string("asset")
            val compressed = File(disassembly, asset).readBytes()
            val decoded = LZ5Codec.decompress(compressed)

            assertEquals(
                compressed.size,
                decoded.consumed,
                "$asset did not consume exactly its extracted source range",
            )
            assertEquals(record.int("decompressedSize"), decoded.data.size, "$asset decoded size")
            assertEquals(
                record.string("decompressedSha256"),
                TestRomHelper.sha256(decoded.data),
                "$asset decoded SHA-256",
            )
        }
    }

    @Tag("parity")
    @Test
    fun `every extracted LZ5 payload survives SMEDIT recompression`() {
        val (disassembly, records) = loadCorpus()

        records.forEach { record ->
            val asset = record.string("asset")
            val original = LZ5Codec.decompress(File(disassembly, asset).readBytes()).data
            val recompressed = LZ5Compressor.compress(original)
            val restored = LZ5Codec.decompress(recompressed)

            assertEquals(recompressed.size, restored.consumed, "$asset recompressed byte count")
            assertContentEquals(original, restored.data, "$asset recompression round trip")
        }
    }

    private fun loadCorpus(): Pair<File, List<kotlinx.serialization.json.JsonObject>> {
        val disassembly = TestRomHelper.requireDisassemblyDir()
        val report = TestRomHelper.requireParityReport("lz5.json", "parityLz5Oracle")
        val root = Json.parseToJsonElement(report.readText()).jsonObject
        val records = root.getValue("streams").jsonArray.map { it.jsonObject }

        assertEquals(
            TestRomHelper.referenceInt("lz5.streams.count"),
            records.size,
            "pinned exact compressed-stream count",
        )
        assertEquals(LZ5Codec.MAX_ENGINE_OUTPUT, root.getValue("maxEngineOutput").jsonPrimitive.int)
        val commandCounts = root.getValue("aggregateCommandCounts").jsonObject
        (0..6).forEach { command ->
            assertTrue(
                commandCounts.getValue(command.toString()).jsonPrimitive.int > 0,
                "Vanilla corpus did not exercise LZ5 command $command",
            )
        }
        assertEquals(
            0,
            commandCounts.getValue("7").jsonPrimitive.int,
            "The pinned vanilla corpus unexpectedly began using command 7; review the oracle",
        )
        return disassembly to records
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun kotlinx.serialization.json.JsonObject.int(name: String): Int =
        getValue(name).jsonPrimitive.int
}
