package com.supermetroid.editor.rom

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestFixtureContractTest {

    @Test
    fun `system property takes precedence over environment setting`() {
        val value = TestRomHelper.configuredValue(
            propertyName = "test.property",
            environmentName = "TEST_ENV",
            propertyLookup = { " /property/path " },
            environmentLookup = { "/environment/path" },
        )

        assertEquals("/property/path", value)
    }

    @Test
    fun `incorrect vanilla bytes fail identity validation`() {
        val error = assertFailsWith<IllegalStateException> {
            TestRomHelper.validateVanillaRom(ByteArray(32))
        }

        assertTrue(error.message.orEmpty().contains("expected 3145728"))
    }

    @Tag("parity")
    @Test
    fun `configured vanilla fixture has exact identity`() {
        val bytes = TestRomHelper.requireRomBytes()

        assertEquals(3 * 1024 * 1024, bytes.size)
        assertEquals(
            "12b77c4bc9c1832cee8881244659065ee1d84c70c3d29e6eaf92e6798cc2ca72",
            TestRomHelper.sha256(bytes),
        )
    }

    @Tag("parity")
    @Test
    fun `configured disassembly fixture exposes required source oracle`() {
        val directory = TestRomHelper.requireDisassemblyDir()

        assertTrue(File(directory, "src/main.asm").isFile)
        assertTrue(File(directory, "tools/rip_assets.py").isFile)
    }
}
