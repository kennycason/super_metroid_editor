package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CeresGfxDumpTest {
    private var romParser: RomParser? = null

    @BeforeAll
    fun setUp() {
        romParser = TestRomHelper.loadRomParser()
    }

    @Test
    fun `dump decompressed Ceres GFX for comparison`() {
        val rp = romParser ?: return
        val romData = rp.getRomData()
        val tablePC = rp.snesToPc(0x8FE6A2)

        for (tsId in listOf(15, 17, 18)) {
            val off = tablePC + tsId * 9
            val gfxPtr = (romData[off + 3].toInt() and 0xFF) or
                ((romData[off + 4].toInt() and 0xFF) shl 8) or
                ((romData[off + 5].toInt() and 0xFF) shl 16)

            val gfxData = rp.decompressLZ2(gfxPtr)
            val outFile = File("/tmp/kotlin_gfx_ts${tsId}.bin")
            outFile.writeBytes(gfxData)
            println("Tileset $tsId: GFX ptr=0x${gfxPtr.toString(16)}, decompressed ${gfxData.size} bytes → ${outFile.path}")
            println("  First 64 bytes: ${gfxData.take(64).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}")
            println("  Last 16 bytes: ${gfxData.takeLast(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}")
        }
    }
}
