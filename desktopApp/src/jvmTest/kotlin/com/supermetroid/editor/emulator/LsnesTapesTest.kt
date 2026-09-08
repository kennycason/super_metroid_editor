package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LsnesTapesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `bundled sniq tapes match locked sha256`() {
        for (tape in LsnesTapes.all()) {
            val stream = javaClass.classLoader.getResourceAsStream(tape.resourcePath)
            assertNotNull(stream) { "missing ${tape.resourcePath}" }
            val bytes = stream!!.use { it.readBytes() }
            assertEquals(tape.sha256, LsnesTapes.sha256Hex(bytes), tape.id)
        }
    }

    @Test
    fun `extract writes cache and match finds the tape by filename`() {
        val extracted = LsnesTapes.extract(LsnesTapes.SNIQ_100, userHome = tempDir.absolutePath)
        assertTrue(extracted.isFile)
        assertEquals(LsnesTapes.SNIQ_100.fileName, extracted.name)
        assertEquals(8319, LsnesTapes.SNIQ_100.skipToFrame)
        assertEquals(LsnesTapes.SNIQ_100, LsnesTapes.match(extracted.absolutePath))
        assertNull(LsnesTapes.match(""))
        assertNull(LsnesTapes.match(null))
    }
}
