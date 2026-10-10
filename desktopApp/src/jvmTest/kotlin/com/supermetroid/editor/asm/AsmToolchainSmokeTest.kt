package com.supermetroid.editor.asm

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Copyright-free, native-process proof for the exact Asar executable that is
 * staged into this OS's application resources. GitHub Actions runs this test
 * separately on macOS, Windows, and Linux.
 */
@Tag("asm-toolchain")
class AsmToolchainSmokeTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `packaged Asar installs verifies and assembles a synthetic LoROM`() {
        val resources = File(
            requireNotNull(System.getProperty("smedit.testPackagedResourcesDir")) {
                "asarToolchainSmokeTest must provide the staged application resources directory"
            },
        )
        val osName = System.getProperty("os.name")
        val executableName = if (osName.contains("Windows", ignoreCase = true)) {
            "asar-standalone.exe"
        } else {
            "asar-standalone"
        }
        val packaged = File(resources, "tools/asar/1.81/$executableName")
        assertTrue(packaged.isFile, "Packaged-layout Asar is missing: $packaged")
        assertTrue(File(packaged.parentFile, "SHA256").isFile, "Packaged Asar checksum is missing")

        val testHome = File(tempDirectory, "home").apply { mkdirs() }
        val toolchain = AsmToolchain(
            homeDirectory = testHome,
            environment = emptyMap(),
            systemProperties = mapOf(
                "compose.application.resources.dir" to resources.absolutePath,
                "os.name" to osName,
                "os.arch" to System.getProperty("os.arch"),
                "user.dir" to tempDirectory.absolutePath,
            ),
        )
        val resolved = toolchain.resolve(File(tempDirectory, "reference"))

        assertNotEquals(packaged.canonicalPath, resolved.canonicalPath)
        assertTrue(resolved.canonicalPath.startsWith(File(testHome, ".smedit").canonicalPath))
        assertEquals(-1L, Files.mismatch(packaged.toPath(), resolved.toPath()))
        assertTrue(toolchain.version(resolved).contains("Asar 1.81"))
        if (!osName.contains("Windows", ignoreCase = true)) {
            assertTrue(resolved.canExecute(), "Installed Asar lost its executable permission")
        }

        val assembly = File(tempDirectory, "smoke.asm").apply {
            writeText(
                """
                lorom
                org ${'$'}808000
                SmokeStart:
                    db ${'$'}53,${'$'}4D,${'$'}45,${'$'}44,${'$'}49,${'$'}54
                """.trimIndent() + "\n",
            )
        }
        val rom = File(tempDirectory, "smoke.sfc").apply {
            writeBytes(ByteArray(0x8000) { 0xFF.toByte() })
        }
        val symbols = File(tempDirectory, "symbols.sym")
        val process = ProcessBuilder(
            resolved.absolutePath,
            "--no-title-check",
            "--symbols=wla",
            "--symbols-path=${symbols.name}",
            assembly.name,
            rom.name,
        )
            .directory(tempDirectory)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Asar smoke assembly timed out")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
        assertEquals(
            listOf(0x53, 0x4D, 0x45, 0x44, 0x49, 0x54),
            rom.readBytes().take(6).map { it.toInt() and 0xFF },
        )
        assertTrue(symbols.isFile && symbols.readText().contains("SmokeStart"), output)
    }
}
