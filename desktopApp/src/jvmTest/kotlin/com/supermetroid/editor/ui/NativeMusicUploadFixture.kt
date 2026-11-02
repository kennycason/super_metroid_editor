package com.supermetroid.editor.ui

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.supermetroid.editor.libretro.LibretroCore
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Isolated full-console test, not a debugger attachment or a fresh-SPC-RAM renderer. */
internal class NativeMusicUploadFixture(private val directory: File, rom: ByteArray) : AutoCloseable {
    companion object {
        private fun root(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        private fun required(available: Boolean, message: String) {
            if (System.getenv("SMEDIT_REQUIRE_MUSIC_UPLOAD_RUNTIME") == "true") check(available) { message }
            assumeTrue(available, message)
        }

        fun assembleResource(directory: File, rom: ByteArray, resource: String): File {
            directory.mkdirs()
            val asar = File(System.getenv("SMEDIT_ASAR")
                ?: File(root(), "desktopApp/build/toolchains/asar-1.81/runtime/asar-standalone").path)
            required(asar.isFile, "Native music-upload fixture needs local Asar (no downloads)")
            val canonical = if (rom.size % 0x8000 == 512) rom.copyOfRange(512, rom.size) else rom
            val romFile = File(directory, "upload-test.sfc").apply { writeBytes(canonical) }
            val source = File(directory, "fixture.asm").apply {
                writeText(requireNotNull(NativeMusicUploadFixture::class.java.getResource(resource)).readText())
            }
            val output = File(directory, "asar.log")
            val process = ProcessBuilder(asar.path, "--no-title-check", source.path, romFile.path)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Test harness assembly timed out") }
            assertEquals(0, process.exitValue(), output.readText())
            return romFile
        }
    }

    private val root = root()
    private val coreFile = File(System.getenv("SMEDIT_MUSIC_UPLOAD_CORE")
        ?: File(root, "tools/snes9x/libretro/snes9x_libretro${if (System.getProperty("os.name").contains("Mac", true)) ".dylib" else ".so"}").path)
    private val core: LibretroCore
    private val native: NativeLibrary
    private var acknowledgments = 0

    init {
        required(coreFile.isFile, "Native music-upload fixture needs the local Snes9x core (no downloads)")
        val romFile = assembleResource(directory, rom, "/sound/upload-harness.asm")
        native = NativeLibrary.getInstance(coreFile.path)
        // Pinned Snes9x's save-state ABI starts with 64 KiB of SMP RAM. Source:
        // tools/snes9x/apu/bapu/smp/smp_state.cpp. No save/load or port side effects.
        required(runCatching { native.getFunction("_Z15S9xAPUSaveStatePh") }.isSuccess,
            "Native music-upload fixture needs Snes9x's APU save-state diagnostic symbol")
        core = LibretroCore(coreFile.path)
        core.setSaveDirectory(File(directory, "saves").path)
        core.init()
        try {
            assertTrue(core.loadGame(romFile.path), "Cannot load isolated upload fixture")
            awaitAcknowledgment(1, "base SPC upload")
            frames(12) // Let the boot entry initialize; the game also delays queued uploads.
        } catch (error: Throwable) { core.close(); throw error }
    }

    fun upload(pointer: Int, narrowCaller: Boolean = false) {
        core.writeWram(0x1E02, byteArrayOf(pointer.toByte(), (pointer ushr 8).toByte(), (pointer ushr 16).toByte(), 0))
        core.writeWram(0x1E0C, byteArrayOf(if (narrowCaller) 1 else 0))
        command(1, "upload ${pointer.toString(16)}")
        val status = core.readWram(0x1E06, 4)
        assertEquals(if (narrowCaller) 0x31 else 0, status[0].toInt() and 0x31, "Caller M/X/carry flags must survive")
        assertEquals(0, status[1].toInt(), "Caller data bank must survive")
        assertEquals(0x1FFF, (status[2].toInt() and 255) or ((status[3].toInt() and 255) shl 8), "Upload must balance the CPU stack")
        frames(8) // Match HandleMusicQueue's post-upload sound-handler downtime.
    }

    fun play(index: Int) { core.writeWram(0x1E02, byteArrayOf(index.toByte())); command(2, "play track") }
    /** Use the game's actual HandleMusicQueue, including its pointer-table lookup. */
    fun queueBank(songSet: Int) {
        core.writeWram(0x1E02, byteArrayOf(songSet.toByte(), 0))
        command(6, "queued bank ${songSet.toString(16)}")
        val status = core.readWram(0x1E06, 4)
        assertEquals(0, status[1].toInt(), "Queued upload must preserve the caller's data bank")
        assertEquals(0x1FFF, (status[2].toInt() and 255) or ((status[3].toInt() and 255) shl 8), "Queued upload must balance the CPU stack")
        val current = core.readWram(0x7F3, 2)
        assertEquals(songSet, (current[0].toInt() and 255) or ((current[1].toInt() and 255) shl 8))
        frames(8)
    }
    /** One-way handoff: execute the original soft reset and title game loop. */
    fun softResetToTitle(): ShortArray {
        core.writeWram(0x1E00, byteArrayOf(7))
        val audio = frames(360)
        val current = core.readWram(0x7F3, 2)
        assertEquals(3, (current[0].toInt() and 255) or ((current[1].toInt() and 255) shl 8), "Soft reset must reach the title music bank")
        return audio
    }
    fun effect(library: Int, index: Int) {
        require(library in 1..3)
        core.writeWram(0x1E02, byteArrayOf(index.toByte())); command(2 + library, "effect $library/$index")
    }
    fun frames(count: Int): ShortArray {
        val audio = ArrayList<Short>()
        repeat(count) { core.run(); audio.addAll(core.drainAudio().toList()) }
        return audio.toShortArray()
    }
    fun ram(): ByteArray = Memory(65 * 1024L).use { memory ->
        native.getFunction("_Z15S9xAPUSaveStatePh").invokeVoid(arrayOf(memory))
        memory.getByteArray(0, 65536)
    }
    private fun command(value: Int, label: String) {
        core.writeWram(0x1E00, byteArrayOf(value.toByte()))
        awaitAcknowledgment((acknowledgments + 1) and 255, label)
    }
    private fun awaitAcknowledgment(expected: Int, label: String) {
        repeat(180) {
            core.run(); core.drainAudio()
            if ((core.readWram(0x1E01, 1)[0].toInt() and 255) == expected) {
                acknowledgments = expected
                return
            }
        }
        val registers = native.getGlobalVariableAddress("Registers").getByteArray(0, 20)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val spc = Memory(65 * 1024L).use { memory ->
            native.getFunction("_Z15S9xAPUSaveStatePh").invokeVoid(arrayOf(memory))
            "pc=${memory.getInt(65536 + 12).toString(16)}, ports=${memory.getByteArray(0xF4, 4).joinToString { (it.toInt() and 255).toString(16) }}"
        }
        fail("CPU/APU $label did not finish within 180 frames (mailbox=${core.readWram(0x1E00, 10).joinToString { (it.toInt() and 255).toString(16) }}, CPU=$registers, SPC=$spc)")
    }
    override fun close() { core.close() }
}
